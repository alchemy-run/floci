package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Manages Docker container lifecycle for EC2 instances.
 * Handles launch, stop, start, terminate, and reboot operations.
 * SSH key injection and UserData execution are performed asynchronously after launch.
 * A running instance has a reachable container address, while best-effort link-local IMDS setup,
 * SSH initialization, and UserData may still be completing in the launch worker.
 */
@ApplicationScoped
public class Ec2ContainerManager {

    private static final Logger LOG = Logger.getLogger(Ec2ContainerManager.class);
    // Guest-created /tmp mounts hide files copied through Docker's archive API.
    private static final String USER_DATA_SCRIPT_PATH = "/var/lib/user-data.sh";
    private static final Pattern MIME_BOUNDARY = Pattern.compile("(?im)^content-type:\\s*multipart/[^;]+;\\s*boundary=\"?([^\";\\n\\r]+)\"?.*$");
    private static final List<String> ALLOWED_SSHD_PATHS = List.of("/usr/sbin/sshd", "/usr/local/sbin/sshd", "/sbin/sshd");
    /** Exit code the sshd install probe uses for "sshd is present but scp is not". See startSshd. */
    static final int SSH_CLIENT_MISSING_EXIT_CODE = 2;
    /** Base64 alphabet plus the line breaks base64-encoded UserData is commonly wrapped at. */
    private static final Pattern BASE64_BODY = Pattern.compile("[A-Za-z0-9+/\\s]+={0,2}");
    private static final int MAX_USER_DATA_DECODE_ROUNDS = 3;
    /** Mirrors AwsJsonCborController.decodeBody's cap: no AWS service should need more than
     *  10 MB decompressed, and it bounds how much a caller-controlled gzip stream can expand
     *  to in the shared emulator JVM. */
    private static final int MAX_DECOMPRESSED_USER_DATA_BYTES = 10 * 1024 * 1024;
    private static final int MAX_EXEC_OUTPUT_BYTES = 2048;
    /** Caps concurrent UserData gzip decompressions across ALL instance launches, not just one.
     *  Launches run independently on {@link #executor}, so the
     *  per-payload cap above only bounds a single launch's allocation: without this, N concurrent
     *  RunInstances/CreateLaunchConfiguration calls, each smuggling a near-cap gzip payload, could
     *  together decompress N * 10 MB at once in the shared emulator JVM with no aggregate ceiling.
     *  4 permits budgets ~40 MB of decompressed UserData in flight at a time - a few multiples of
     *  the per-payload cap rather than 1, so an ordinary burst of legitimate concurrent launches
     *  (e.g. an Auto Scaling group launching a handful of instances together) is not serialized
     *  down to one decompression at a time, while a launch storm still cannot grow memory usage
     *  without bound. */
    private static final int MAX_CONCURRENT_USER_DATA_DECOMPRESSIONS = 4;
    /** Gates entry to {@link #gunzip}; see {@link #MAX_CONCURRENT_USER_DATA_DECOMPRESSIONS}. */
    private static final Semaphore USER_DATA_DECOMPRESSION_BUDGET =
            new Semaphore(MAX_CONCURRENT_USER_DATA_DECOMPRESSIONS);
    private static final int LAUNCH_CORE_THREADS = 4;
    private static final int LAUNCH_MAX_THREADS = 8;
    private static final int LAUNCH_QUEUE_CAPACITY = 64;
    private static final long USER_DATA_EXECUTION_TIMEOUT_MINUTES = 30;
    // Wait for capacity so accepted launches are not dropped, but never run launch work on callers.
    static final RejectedExecutionHandler BLOCKING_BACKPRESSURE = (runnable, executor) -> {
        if (executor.isShutdown()) {
            throw new RejectedExecutionException("EC2 container manager is stopped");
        }
        try {
            executor.getQueue().put(runnable);
            if (executor.isShutdown() && executor.getQueue().remove(runnable)) {
                throw new RejectedExecutionException("EC2 container manager is stopped");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("Interrupted while waiting for EC2 launch capacity", e);
        }
    };
    /** Test seam: when non-null, invoked by {@link #gunzip} right after it acquires a
     *  decompression-budget permit and before it starts decompressing, so tests can observe and
     *  serialize concurrent decompressions deterministically. Always null in production. */
    static volatile Runnable userDataDecompressionTestHook;

    /**
     * Label identifying the Floci process that created an EC2 instance container, by its API
     * port. {@link #reconcileOrphanedContainers} lists on the existing {@code io.floci.service=ec2}
     * identity label (see {@link ContainerStorageHelper#resourceIdentityLabels}) and then keeps
     * only containers carrying <em>this</em> process's owner port: several emulators can share one
     * Docker daemon, and an unscoped sweep would reap a sibling's live instances.
     * {@code floci_namespace} is the documented scoping mechanism for that, but it is absent
     * unless a resource namespace is configured, so it cannot scope the default configuration.
     * Containers created before this label existed carry no owner and are therefore never swept.
     */
    static final String LABEL_OWNER_PORT = "floci_owner_port";

    /**
     * Identity of the Floci deployment that owns a container, for scoping the startup sweep.
     * The API port alone collides when two independently namespaced Flocis share a Docker daemon
     * on the same internal port, and each would then reap the other's live containers. Composing
     * the documented resource namespace in front of it separates exactly those deployments; an
     * unnamespaced single Floci keeps the bare port it already stamped.
     */
    private String ownerIdentity() {
        String ns = config.docker() == null || config.docker().resourceNamespace() == null
                ? "" : config.docker().resourceNamespace().orElse("");
        return ns.isBlank() ? String.valueOf(config.port()) : ns + "/" + config.port();
    }
    static final String LABEL_SERVICE = "io.floci.service";
    static final String SERVICE_VALUE = "ec2";
    static final String LABEL_RESOURCE_ID = "io.floci.resource-id";
    static final String LABEL_REGION = "io.floci.region";

    static int containerBridgeIpAttempts = 30;
    static long containerBridgeIpPollMillis = 500;

    private final ContainerBuilder containerBuilder;
    private final Ec2BootstrapImage bootstrapImage;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final DockerHostResolver dockerHostResolver;
    private final DockerClient dockerClient;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private final Ec2MetadataServer metadataServer;
    private final Ec2PortForwardManager portForwardManager;
    private final RegionResolver regionResolver;
    private final ContainerNetworkReachability containerNetworkReachability;
    private final VpcNetworkManager vpcNetworkManager;
    private final ContainerReachableEndpoint reachableEndpoint;
    private SecurityGroupFirewallManager firewallManager;
    private final ExecutorService executor;
    private final Duration userDataExecutionTimeout;
    private final Set<ResultCallback<Frame>> activeUserDataCallbacks = ConcurrentHashMap.newKeySet();
    private final Map<Instance, Object> metadataLocks = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Instance, Backing> backings = new ConcurrentHashMap<>();

    private volatile boolean dockerUnavailableLogged;

    public void refreshSecurityGroups(String region, Map<String, SecurityGroup> groups,
                                      Map<String, List<String>> prefixLists) {
        if (firewallManager != null && firewallManager.enabled()) {
            firewallManager.refreshPolicies(region, groups, prefixLists);
        }
    }

    public void updateSecurityGroups(String eniId, Set<String> groupIds,
                                     Map<String, SecurityGroup> groups,
                                     Map<String, List<String>> prefixLists) {
        if (firewallManager != null && firewallManager.enabled()) {
            firewallManager.updateGroups(eniId, groupIds, groups, prefixLists);
        }
    }

    /** A workload's own Floci-owned namespace, or null when the instance is not protected. */
    private ProtectedNamespace protectedNamespace(Instance instance) {
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            return null;
        }
        InspectContainerResponse worker = dockerClient.inspectContainerCmd(containerId).exec();
        String mode = worker.getHostConfig() == null ? null : worker.getHostConfig().getNetworkMode();
        if (mode == null || !mode.startsWith("container:")) {
            return null;
        }
        String helperId = mode.substring("container:".length());
        InspectContainerResponse helper = dockerClient.inspectContainerCmd(helperId).exec();
        Map<String, String> labels = helper.getConfig().getLabels();
        if (!"true".equals(labels.get("floci.security-group-helper"))
                || !"ec2".equals(labels.get(LABEL_SERVICE))
                || !instance.getInstanceId().equals(labels.get(LABEL_RESOURCE_ID))
                || !ownerIdentity().equals(labels.get("floci_owner_port"))
                || !Objects.equals(instance.getRegion(), labels.get(LABEL_REGION))
                || !Objects.equals(Ec2MetadataServer.instanceAccountId(instance, regionResolver.getAccountId()),
                        labels.get("io.floci.account"))) {
            throw new IllegalStateException("EC2 workload network namespace is not Floci protected");
        }
        Map<String, ContainerNetwork> networks = helper.getNetworkSettings().getNetworks();
        String retained = instance.getContainerBridgeIp();
        String address = networks.values().stream().map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank() && ip.equals(retained)).findFirst().orElse(null);
        if (retained != null && !retained.isBlank() && address == null) {
            throw new IllegalStateException("EC2 namespace lost its retained Docker transport address");
        }
        if (address == null && vpcNetworkManager != null) {
            String network = vpcNetworkManager.networkNameFor(instance.getRegion(), instance.getVpcId()).orElse(null);
            ContainerNetwork attachment = network == null ? null : networks.get(network);
            address = attachment == null ? null : attachment.getIpAddress();
        }
        if (address == null || address.isBlank()) {
            address = networks.values().stream().map(ContainerNetwork::getIpAddress)
                    .filter(ip -> ip != null && !ip.isBlank()).findFirst()
                    .orElseThrow(() -> new IllegalStateException("EC2 namespace has no Docker transport"));
        }
        if (vpcNetworkManager != null && vpcNetworkManager.enabled()) {
            String network = vpcNetworkManager.networkNameFor(instance.getRegion(), instance.getVpcId()).orElse(null);
            if (network != null && networks.containsKey(network)
                    && !vpcNetworkManager.reserveTransportPrivateIp(instance.getRegion(), instance.getSubnetId(),
                            instance.getLogicalPrivateIpAddress() != null ? instance.getLogicalPrivateIpAddress()
                                    : instance.getPrivateIpAddress(), address, instance.getInstanceId())) {
                throw new IllegalStateException("EC2 namespace transport lease is owned by another instance");
            }
        }
        return new ProtectedNamespace(helperId, address);
    }

    private record ProtectedNamespace(String helperId, String transportAddress) {}

    private boolean needsPrivateNamespace(Instance instance) {
        return instance.getLogicalPrivateIpAddress() != null
                || (firewallManager != null && firewallManager.enabled())
                || (vpcNetworkManager != null && vpcNetworkManager.enabled());
    }

    private static InstanceNetworkInterface primaryInterface(Instance instance) {
        return instance.getNetworkInterfaces().stream().filter(eni -> eni.getDeviceIndex() == 0)
                .findFirst().orElseThrow(() -> new IllegalStateException("EC2 instance has no primary ENI"));
    }

    private void registerNamespace(Instance instance, String region, List<SecurityGroup> groups,
                                   Map<String, List<String>> prefixLists, String helperId, String transport) {
        if (firewallManager == null) {
            throw new IllegalStateException("EC2 private networking requires a namespace manager");
        }
        InstanceNetworkInterface eni = primaryInterface(instance);
        String logical = instance.getLogicalPrivateIpAddress() != null
                ? instance.getLogicalPrivateIpAddress() : eni.getPrivateIpAddress();
        SecurityGroupNftCompiler.Endpoint endpoint = new SecurityGroupNftCompiler.Endpoint(
                Ec2MetadataServer.instanceAccountId(instance, regionResolver.getAccountId()), region,
                instance.getVpcId(), eni.getNetworkInterfaceId(), logical, transport,
                instance.getSecurityGroups().stream().map(GroupIdentifier::getGroupId)
                        .collect(Collectors.toSet()), groups);
        if (firewallManager.enabled()) {
            firewallManager.register(endpoint, helperId, prefixLists);
        } else {
            firewallManager.registerNetworkEndpoint(endpoint, helperId);
        }
    }

    public void restoreSecurityGroups(Instance instance, String region, List<SecurityGroup> groups,
                                      Map<String, List<String>> prefixLists) {
        if (!needsPrivateNamespace(instance)) {
            return;
        }
        ProtectedNamespace namespace = protectedNamespace(instance);
        if (namespace == null) {
            throw new IllegalStateException("EC2 workload has no protected network namespace");
        }
        instance.setContainerBridgeIp(namespace.transportAddress());
        registerNamespace(instance, region, groups, prefixLists, namespace.helperId(), namespace.transportAddress());
    }

    @Inject
    public Ec2ContainerManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerLogStreamer logStreamer,
                               ContainerDetector containerDetector,
                               DockerHostResolver dockerHostResolver,
                               DockerClient dockerClient,
                               PortAllocator portAllocator,
                               EmulatorConfig config,
                               Ec2MetadataServer metadataServer,
                               Ec2PortForwardManager portForwardManager,
                               RegionResolver regionResolver,
                               ContainerNetworkReachability containerNetworkReachability,
                               VpcNetworkManager vpcNetworkManager,
                               ContainerReachableEndpoint reachableEndpoint,
                               SecurityGroupFirewallManager firewallManager) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver,
                dockerClient, portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint);
        this.firewallManager = firewallManager;
    }

    public Ec2ContainerManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerLogStreamer logStreamer,
                               ContainerDetector containerDetector,
                               DockerHostResolver dockerHostResolver,
                               DockerClient dockerClient,
                               PortAllocator portAllocator,
                               EmulatorConfig config,
                               Ec2MetadataServer metadataServer,
                               Ec2PortForwardManager portForwardManager,
                               RegionResolver regionResolver,
                               ContainerNetworkReachability containerNetworkReachability,
                               VpcNetworkManager vpcNetworkManager,
                               ContainerReachableEndpoint reachableEndpoint) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver, dockerClient,
                portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint, createLaunchExecutor(),
                Duration.ofMinutes(USER_DATA_EXECUTION_TIMEOUT_MINUTES));
    }

    Ec2ContainerManager(ContainerBuilder containerBuilder,
                        ContainerLifecycleManager lifecycleManager,
                        ContainerLogStreamer logStreamer,
                        ContainerDetector containerDetector,
                        DockerHostResolver dockerHostResolver,
                        DockerClient dockerClient,
                        PortAllocator portAllocator,
                        EmulatorConfig config,
                        Ec2MetadataServer metadataServer,
                        Ec2PortForwardManager portForwardManager,
                        RegionResolver regionResolver,
                        ContainerNetworkReachability containerNetworkReachability,
                        VpcNetworkManager vpcNetworkManager,
                        ContainerReachableEndpoint reachableEndpoint,
                        ExecutorService executor,
                        Duration userDataExecutionTimeout) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver,
                dockerClient, portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint, executor,
                userDataExecutionTimeout, null);
    }

    Ec2ContainerManager(ContainerBuilder containerBuilder,
                        ContainerLifecycleManager lifecycleManager,
                        ContainerLogStreamer logStreamer,
                        ContainerDetector containerDetector,
                        DockerHostResolver dockerHostResolver,
                        DockerClient dockerClient,
                        PortAllocator portAllocator,
                        EmulatorConfig config,
                        Ec2MetadataServer metadataServer,
                        Ec2PortForwardManager portForwardManager,
                        RegionResolver regionResolver,
                        ContainerNetworkReachability containerNetworkReachability,
                        VpcNetworkManager vpcNetworkManager,
                        ContainerReachableEndpoint reachableEndpoint,
                        ExecutorService executor,
                        Duration userDataExecutionTimeout,
                        SecurityGroupFirewallManager firewallManager) {
        this.firewallManager = firewallManager;
        this.containerBuilder = containerBuilder;
        this.bootstrapImage = new Ec2BootstrapImage(dockerClient, containerBuilder, lifecycleManager,
                new ImageCacheService(dockerClient, config));
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.dockerHostResolver = dockerHostResolver;
        this.dockerClient = dockerClient;
        this.portAllocator = portAllocator;
        this.config = config;
        this.regionResolver = regionResolver;
        this.metadataServer = metadataServer;
        this.portForwardManager = portForwardManager;
        this.containerNetworkReachability = containerNetworkReachability;
        this.vpcNetworkManager = vpcNetworkManager;
        this.reachableEndpoint = reachableEndpoint;
        this.executor = executor;
        this.userDataExecutionTimeout = userDataExecutionTimeout;
    }

    private static ExecutorService createLaunchExecutor() {
        return new ThreadPoolExecutor(
                LAUNCH_CORE_THREADS,
                LAUNCH_MAX_THREADS,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(LAUNCH_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "ec2-container-launcher");
                    thread.setDaemon(true);
                    return thread;
                },
                BLOCKING_BACKPRESSURE);
    }

    @PreDestroy
    void stop() {
        closeActiveUserDataCallbacks();
        executor.shutdownNow();
        closeActiveUserDataCallbacks();
    }

    /**
     * Launches a Docker container for the given EC2 instance.
     * Guest startup is asynchronous and preserves an already-running control-plane record.
     * With no Docker daemon reachable, the instance runs as metadata only.
     *
     * @param instance    the EC2 instance model (mutated in-place as state transitions occur)
     * @param dockerImage Docker image URI resolved from the instance's AMI ID
     * @param publicKey   SSH public key content to inject (may be null)
     * @param region      AWS region (for CloudWatch log group naming)
     */
    public void launch(Instance instance, String dockerImage, String publicKey, String region) {
        launch(instance, ResolvedAmiImage.minimal(dockerImage), publicKey, region, Set.of());
    }

    public void launch(Instance instance, ResolvedAmiImage image, String publicKey, String region) {
        launch(instance, image, publicKey, region, Set.of());
    }

    /**
     * @param appPorts TCP ports opened by the instance's security groups to publish on the host
     *                 via socat sidecars once the container is running (empty for none)
     */
    public void launch(Instance instance, ResolvedAmiImage image, String publicKey, String region, Set<Integer> appPorts) {
        launch(instance, image, publicKey, region, appPorts, List.of(), Map.of());
    }

    public void launch(Instance instance, ResolvedAmiImage image, String publicKey, String region,
                       Set<Integer> appPorts, List<SecurityGroup> groups,
                       Map<String, List<String>> prefixLists) {
        synchronized (instance) {
            if (isLaunchCancelledState(instance)) {
                return;
            }
            instance.setContainerLaunchPending(true);
            instance.setContainerLaunchFailed(false);
            if (needsPrivateNamespace(instance) && instance.getDockerContainerId() == null) {
                instance.setState(InstanceState.pending());
                backings.putIfAbsent(instance, new Backing());
            }
        }
        if (instance.getState() == null || instance.getState().getName() == null) {
            instance.setState(InstanceState.pending());
        }

        // An instance record is metadata: id, addresses, tags and lifecycle state are all
        // served without a container runtime. Only the guest itself (SSH, UserData, SSM
        // commands) needs Docker, so when no daemon is reachable the instance still runs
        // instead of dying: Floci in Docker without a mounted socket, or a stopped daemon
        // on the host, would otherwise terminate every instance the moment it launched.
        if (!isDockerAvailable()) {
            if (needsPrivateNamespace(instance)) {
                failLaunch(instance);
            } else {
                markContainerlessRunning(instance);
            }
            return;
        }

        // Captured before anything can overwrite it: on a launch that never attaches to the VPC
        // network, exposeReachablePrivateAddress replaces the reported private IP with the bridge
        // one, and the address actually leased would otherwise be unrecoverable on the paths below.
        String leasedPrivateIp = instance.getPrivateIpAddress();

        try {
            executor.execute(() -> {
                try {
                    String instanceId = instance.getInstanceId();
                ResolvedAmiImage preparedImage = prepareImage(image);
                StartedContainer started = createAndStartContainer(instance, preparedImage, region,
                        leasedPrivateIp, groups, prefixLists);
                if (started == null) {
                    return;
                }
                int sshHostPort = started.sshHostPort();
                String containerId = started.containerId();
                String vpcAddress = started.vpcAddress();
                if (vpcAddress == null && started.namespace() == null && !needsPrivateNamespace(instance)) {
                    // Nothing holds the address, and moments from now this instance will be
                    // reporting its bridge address instead, so the lease would no longer be
                    // findable from the instance at terminate time. Give it back here.
                    vpcNetworkManager.releasePrivateIp(region, instance.getSubnetId(), leasedPrivateIp);
                }

                if (isLaunchCancelled(instance)) {
                    failLaunch(instance, leasedPrivateIp);
                    return;
                }

                // Poll until Docker confirms the container is running
                boolean running = false;
                for (int i = 0; i < 30 && !running; i++) {
                    if (isLaunchCancelled(instance)) {
                        failLaunch(instance, leasedPrivateIp);
                        return;
                    }
                    running = lifecycleManager.isContainerRunning(containerId);
                    if (!running) {
                        Thread.sleep(500);
                    }
                }

                if (!running) {
                    LOG.warnv("EC2 instance {0} container {1} did not reach running state", instanceId, containerId);
                    failLaunch(instance, leasedPrivateIp);
                    return;
                }

                if (isLaunchCancelled(instance)) {
                    failLaunch(instance, leasedPrivateIp);
                    return;
                }

                // Discover the container's bridge IP for IMDS registration.
                // Docker can report the container as running before network
                // settings are populated; wait here so IMDS is registered
                // before link-local metadata validation and UserData run.
                String containerIp = started.namespace() == null
                        ? waitForContainerBridgeIp(containerId, instanceId, instance)
                        : started.namespace().transportAddress();
                if (vpcAddress != null && started.namespace() == null) {
                    // The VPC address is the one Floci reports and the one peers in the same VPC
                    // dial. The bridge address still identifies this container to IMDS, because
                    // the default route, and so the source address of its metadata requests, is
                    // the bridge.
                    if (containerIp != null && !containerIp.equals(vpcAddress)) {
                        instance.setImdsSourceIp(containerIp);
                        metadataServer.registerContainer(containerIp, instanceId, instance);
                    }
                    containerIp = vpcAddress;
                }
                if (containerIp != null && !containerIp.isBlank()) {
                    instance.setContainerBridgeIp(containerIp);
                    if (started.namespace() == null) {
                        exposeReachablePrivateAddress(instance, containerIp,
                                config.services().ec2().awsFaithfulPrivateIp());
                    }
                    metadataServer.registerContainer(containerIp, instanceId, instance);
                }
                else {
                    LOG.warnv("EC2 instance {0} container {1} did not receive a usable bridge IP for IMDS; keeping control-plane record",
                            instanceId, containerId);
                    failLaunch(instance, leasedPrivateIp);
                    return;
                }

                String networkContainer = started.namespace() == null ? containerId : started.namespace().helperId();
                if (started.namespace() != null) {
                    refreshImdsSourceRegistration(instance, networkContainer, containerIp);
                }
                refreshMetadataAddresses(instance, networkContainer);

                prepareGuestFilesystem(containerId, instanceId);
                installSystemctlShim(containerId, instanceId);

                if (!needsPrivateNamespace(instance) && !markRunning(instance)) {
                    failLaunch(instance, leasedPrivateIp);
                    return;
                }

                // Set public-facing addresses only for instances whose subnet
                // opts in via MapPublicIpOnLaunch (#1984). Private-subnet
                // instances have no public IP/DNS, matching real EC2.
                if (instance.isAssociatePublicIp() && instance.getPublicIpAddress() == null) {
                    exposeReachablePublicAddress(instance);
                }

                LOG.infov("EC2 instance {0} running in container {1} (SSH host port {2})",
                        instanceId, containerId, String.valueOf(sshHostPort));

                boolean metadataReady = configureLinkLocalMetadataEndpoint(instance, networkContainer);
                if (needsPrivateNamespace(instance) && (!metadataReady || !markRunning(instance))) {
                    failLaunch(instance, leasedPrivateIp);
                    return;
                }

                // Publish security-group TCP ingress ports on the host via socat sidecars.
                if (appPorts != null && !appPorts.isEmpty()) {
                    portForwardManager.reconcile(instance, appPorts);
                }

                String userData = instance.getUserData();
                if (userData != null && !userData.isBlank()) {
                    executeUserData(containerId, instanceId, userData, region);
                }

                if (publicKey != null && !publicKey.isBlank()) {
                    injectSshKey(containerId, publicKey);
                }
                startSshd(containerId, instanceId);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failLaunch(instance, leasedPrivateIp);
                } catch (Exception e) {
                    LOG.warnv("Failed to launch EC2 instance {0}: {1}", instance.getInstanceId(), e.getMessage());
                    // The daemon can disappear between the probe above and any of the calls in
                    // this block. Losing Docker is not the instance's fault, so degrade to a
                    // metadata-only instance; a genuine container failure still fails the launch.
                    if (needsPrivateNamespace(instance) || isDockerAvailable()) {
                        failLaunch(instance, leasedPrivateIp);
                    } else {
                        markContainerlessRunning(instance);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.warnv("Could not schedule EC2 instance {0} launch because the launch executor is saturated or stopping",
                    instance.getInstanceId());
            failLaunch(instance, leasedPrivateIp);
        }
    }

    private StartedContainer createAndStartContainer(Instance instance, ResolvedAmiImage image, String region,
                                                     String leasedPrivateIp,
                                                     List<SecurityGroup> groups,
                                                     Map<String, List<String>> prefixLists) {
        if (needsPrivateNamespace(instance)) {
            return createManagedContainer(instance, image, region, groups, prefixLists);
        }
        String instanceId = instance.getInstanceId();
        String containerName = ContainerStorageHelper.resourceName(config, "ec2", null, instanceId);
        String imdsEndpoint = Ec2MetadataProxy.ENDPOINT;
        String accountId = Ec2MetadataServer.instanceAccountId(instance, regionResolver.getAccountId());
        String serviceEndpoint = reachableEndpoint.baseUrl();

        while (true) {
            if (isLaunchCancelled(instance)) {
                return null;
            }
            int sshHostPort = portAllocator.allocate(
                    config.services().ec2().sshPortRangeStart(),
                    config.services().ec2().sshPortRangeEnd());
            SecurityGroupFirewallManager.Namespace namespace = null;
            String eniId = null;
            String containerId = null;
            boolean recorded = false;
            try {
                if (firewallManager != null && firewallManager.enabled()) {
                    if (instance.getNetworkInterfaces() == null || instance.getNetworkInterfaces().isEmpty()) {
                        throw new IllegalStateException("EC2 instance has no network interface to protect");
                    }
                    InstanceNetworkInterface eni = instance.getNetworkInterfaces().getFirst();
                    eniId = eni.getNetworkInterfaceId();
                    instance.setLogicalPrivateIpAddress(eni.getPrivateIpAddress());
                    namespace = firewallManager.createNamespace("ec2", instanceId,
                            accountId, region, Optional.empty(), Map.of(22, sshHostPort));
                    firewallManager.register(new SecurityGroupNftCompiler.Endpoint(accountId,
                            region, instance.getVpcId(), eniId, eni.getPrivateIpAddress(),
                            namespace.transportAddress(),
                            instance.getSecurityGroups().stream().map(g -> g.getGroupId())
                                    .collect(Collectors.toSet()), groups),
                            namespace.helperId(), prefixLists);
                }
                ContainerSpec spec = buildContainerSpec(containerName, image, region, serviceEndpoint, imdsEndpoint,
                        instanceId, sshHostPort, namespace, instance.getIamInstanceProfileArn() != null, accountId);
                containerId = image.dockerPlatform() == null
                        ? lifecycleManager.create(spec)
                        : lifecycleManager.create(spec, image.dockerPlatform());
                if (!recordCreatedContainer(instance, containerId, sshHostPort)) {
                    lifecycleManager.removeIfExists(containerId);
                    if (namespace != null) {
                        firewallManager.unregister(eniId);
                        lifecycleManager.removeIfExists(namespace.helperId());
                    }
                    portAllocator.release(sshHostPort);
                    return null;
                }
                recorded = true;
                // Join the VPC's Docker network at the address the subnet allocated, before the
                // container starts, so the guest comes up already holding its private IP. The
                // default bridge attachment stays: it is what carries the published SSH host
                // port, which a network mode set at creation time would suppress.
                String vpcAddress = namespace == null
                        ? vpcNetworkManager.attach(region, instance.getVpcId(), instance.getSubnetId(),
                                containerId, leasedPrivateIp).map(network -> leasedPrivateIp).orElse(null)
                        : null;
                lifecycleManager.startCreated(containerId, spec);
                return new StartedContainer(containerId, sshHostPort, vpcAddress, namespace, eniId);
            } catch (Exception e) {
                boolean ownsCleanup = !recorded || clearRecordedContainer(instance, containerId, sshHostPort);
                if (!ownsCleanup) {
                    return null;
                }
                if (containerId != null) {
                    lifecycleManager.removeIfExists(containerId);
                }
                if (namespace != null) {
                    if (eniId != null) {
                        firewallManager.unregister(eniId);
                    }
                    lifecycleManager.removeIfExists(namespace.helperId());
                }
                if (isHostPortCollision(e)) {
                    // Docker Desktop can own a published port without exposing it to a host-side
                    // ServerSocket probe. Keep it unavailable for this process and try the next port.
                    portAllocator.markReserved(sshHostPort);
                    LOG.warnv("EC2 instance {0} could not use SSH host port {1}; trying another port",
                            instanceId, String.valueOf(sshHostPort));
                    continue;
                }
                portAllocator.release(sshHostPort);
                throw e;
            }
        }
    }

    private static final class Backing {
        String containerId;
        String helperId;
        String eniId;
        int sshHostPort;
        boolean creating;
        boolean collidedPort;
    }

    private StartedContainer createManagedContainer(Instance instance, ResolvedAmiImage image, String region,
                                                     List<SecurityGroup> groups,
                                                     Map<String, List<String>> prefixLists) {
        if (firewallManager == null) {
            throw new IllegalStateException("EC2 private networking requires a namespace manager");
        }
        String instanceId = instance.getInstanceId();
        String accountId = Ec2MetadataServer.instanceAccountId(instance, regionResolver.getAccountId());
        for (int attempt = 0; attempt < 10; attempt++) {
            Backing backing = new Backing();
            synchronized (instance) {
                if (isLaunchCancelledState(instance)) {
                    return null;
                }
                backing.eniId = primaryInterface(instance).getNetworkInterfaceId();
                backing.creating = true;
                backings.put(instance, backing);
            }
            try {
                backing.sshHostPort = portAllocator.allocate(config.services().ec2().sshPortRangeStart(),
                        config.services().ec2().sshPortRangeEnd());
                backing.helperId = ContainerStorageHelper.resourceName(config, "sg", null,
                        instanceId.replaceAll("[^a-zA-Z0-9_.-]", "-"));
                SecurityGroupFirewallManager.Namespace namespace = firewallManager.createNetworkNamespace(
                        "ec2", instanceId, accountId, region, Optional.empty(), Map.of(22, backing.sshHostPort));
                backing.helperId = namespace.helperId();
                String logical = instance.getLogicalPrivateIpAddress() != null
                        ? instance.getLogicalPrivateIpAddress() : primaryInterface(instance).getPrivateIpAddress();
                instance.setLogicalPrivateIpAddress(logical);
                String transport = namespace.transportAddress();
                if (vpcNetworkManager.enabled()) {
                    transport = vpcNetworkManager.allocateTransportPrivateIp(region, instance.getSubnetId(),
                            logical, instanceId).orElseThrow(() -> new IllegalStateException("No Docker transport address available"));
                    if (vpcNetworkManager.attach(region, instance.getVpcId(), instance.getSubnetId(),
                            backing.helperId, transport).isEmpty()) {
                        throw new IllegalStateException("Cannot attach EC2 namespace to its VPC transport");
                    }
                }
                namespace = new SecurityGroupFirewallManager.Namespace(backing.helperId, transport);
                instance.setContainerBridgeIp(transport);
                registerNamespace(instance, region, groups, prefixLists, backing.helperId, transport);
                ContainerSpec spec = buildContainerSpec(ContainerStorageHelper.resourceName(config, "ec2", null, instanceId),
                        image, region, reachableEndpoint.baseUrl(), Ec2MetadataProxy.ENDPOINT, instanceId,
                        backing.sshHostPort, namespace, instance.getIamInstanceProfileArn() != null, accountId);
                if (!isLaunchCancelled(instance)) {
                    backing.containerId = image.dockerPlatform() == null ? lifecycleManager.create(spec)
                            : lifecycleManager.create(spec, image.dockerPlatform());
                    synchronized (instance) {
                        instance.setDockerContainerId(backing.containerId);
                        instance.setSshHostPort(backing.sshHostPort);
                    }
                    if (!isLaunchCancelled(instance)) {
                        lifecycleManager.startCreated(backing.containerId, spec);
                    }
                }
                synchronized (instance) {
                    backing.creating = false;
                }
                if (isLaunchCancelled(instance)) {
                    failLaunch(instance);
                    return null;
                }
                return new StartedContainer(backing.containerId, backing.sshHostPort, transport, namespace, backing.eniId);
            } catch (RuntimeException e) {
                synchronized (instance) {
                    backing.creating = false;
                    backing.collidedPort = isHostPortCollision(e);
                }
                boolean removed = cleanupManagedBacking(instance, backing);
                if (removed && backing.collidedPort && !isLaunchCancelled(instance)) {
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("EC2 SSH port allocation failed after ten attempts");
    }

    private Backing restoredBacking(Instance instance) {
        Backing existing = backings.get(instance);
        if (existing != null) {
            return existing;
        }
        Backing restored = new Backing();
        restored.containerId = instance.getDockerContainerId();
        restored.sshHostPort = instance.getSshHostPort();
        restored.eniId = primaryInterface(instance).getNetworkInterfaceId();
        try {
            ProtectedNamespace namespace = protectedNamespace(instance);
            if (namespace != null) {
                restored.helperId = namespace.helperId();
            }
        } catch (NotFoundException missingWorker) {
            // The namespace can survive a workload removed before an emulator restart.
        }
        if (restored.helperId == null) {
            String name = ContainerStorageHelper.resourceName(config, "sg", null,
                    instance.getInstanceId().replaceAll("[^a-zA-Z0-9_.-]", "-"));
            try {
                InspectContainerResponse helper = dockerClient.inspectContainerCmd(name).exec();
                Map<String, String> labels = helper.getConfig().getLabels();
                if (!ownerIdentity().equals(labels.get(LABEL_OWNER_PORT))
                        || !instance.getInstanceId().equals(labels.get(LABEL_RESOURCE_ID))
                        || !"true".equals(labels.get("floci.security-group-helper"))) {
                    throw new IllegalStateException("Cannot clean an EC2 namespace owned by another instance");
                }
                restored.helperId = helper.getId();
            } catch (NotFoundException missingHelper) {
                // Both resources may already have been removed on the previous attempt.
            }
        }
        backings.put(instance, restored);
        return restored;
    }

    private void requireOwnedHelper(Instance instance, String helperId) {
        if (helperId == null) {
            return;
        }
        try {
            InspectContainerResponse helper = dockerClient.inspectContainerCmd(helperId).exec();
            Map<String, String> labels = helper.getConfig().getLabels();
            if (!"true".equals(labels.get("floci.security-group-helper"))
                    || !SERVICE_VALUE.equals(labels.get(LABEL_SERVICE))
                    || !ownerIdentity().equals(labels.get(LABEL_OWNER_PORT))
                    || !instance.getInstanceId().equals(labels.get(LABEL_RESOURCE_ID))
                    || !Objects.equals(instance.getRegion(), labels.get(LABEL_REGION))
                    || !Objects.equals(Ec2MetadataServer.instanceAccountId(instance, regionResolver.getAccountId()),
                            labels.get("io.floci.account"))) {
                throw new IllegalStateException("EC2 cleanup target is not this instance's namespace");
            }
        } catch (NotFoundException removed) {
            // An already removed helper cannot own a Docker endpoint.
        }
    }

    private boolean removeConfirmed(String containerId) {
        if (containerId == null) {
            return true;
        }
        lifecycleManager.removeIfExists(containerId);
        try {
            dockerClient.inspectContainerCmd(containerId).exec();
            return false;
        } catch (NotFoundException removed) {
            return true;
        }
    }

    private boolean cleanupManagedBacking(Instance instance, Backing backing) {
        synchronized (instance) {
            if (backings.get(instance) != backing || backing.creating) {
                return false;
            }
            try {
                requireOwnedHelper(instance, backing.helperId);
                portForwardManager.unpublishAll(instance);
                if (!removeConfirmed(backing.containerId) || !removeConfirmed(backing.helperId)) {
                    LOG.warnv("EC2 instance {0} retains its private lease because Docker removal is incomplete",
                            instance.getInstanceId());
                    return false;
                }
                if (backing.helperId != null) {
                    firewallManager.unregister(backing.eniId, backing.helperId);
                }
                vpcNetworkManager.releaseTransportPrivateIp(instance.getRegion(), instance.getSubnetId(), instance.getInstanceId());
                if (backing.sshHostPort > 0 && !backing.collidedPort) {
                    portAllocator.release(backing.sshHostPort);
                }
                metadataServer.unregisterContainer(instance.getContainerBridgeIp(), instance);
                metadataServer.unregisterContainer(instance.getImdsSourceIp(), instance);
                instance.setDockerContainerId(null);
                instance.setSshHostPort(0);
                instance.setContainerBridgeIp(null);
                instance.setImdsSourceIp(null);
                backings.remove(instance, backing);
                return true;
            } catch (RuntimeException e) {
                LOG.warnv("EC2 instance {0} cleanup failed; retaining its private lease: {1}",
                        instance.getInstanceId(), e.getMessage());
                return false;
            }
        }
    }

    private void finishManagedRemoval(Instance instance, Backing backing) {
        synchronized (instance) {
            if (cleanupManagedBacking(instance, backing)) {
                instance.setContainerLaunchPending(false);
                instance.setState(InstanceState.terminated());
                instance.setTerminatedAt(System.currentTimeMillis());
            }
        }
    }

    private ContainerSpec buildContainerSpec(String containerName, ResolvedAmiImage image, String region,
                                             String serviceEndpoint, String imdsEndpoint, String instanceId,
                                             int sshHostPort, SecurityGroupFirewallManager.Namespace namespace,
                                             boolean hasInstanceProfile, String accountId) {
        // Minimal images keep the historic tail command, while cloud-image AMI guests can boot their init.
        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image.dockerImage())
                .withName(containerName)
                .withDockerNetwork(Optional.empty())
                .withEnv(localAwsEnvironment(region, serviceEndpoint, imdsEndpoint,
                        hasInstanceProfile))
                .withEnv("AWS_EC2_INSTANCE_ID", instanceId)
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "ec2", instanceId, accountId, region))
                // Which Floci owns this container, so the startup reconciler cannot reap a
                // sibling emulator's live instances off a shared daemon. See LABEL_OWNER_PORT.
                .withLabels(Map.of(LABEL_OWNER_PORT, ownerIdentity()))
                // EC2 instances expose IMDS on 169.254.169.254. Floci needs network administration
                // privileges in the local container to attach that link-local address.
                .withPrivileged(namespace == null)
                .withCmd(image.systemd() ? List.of("/sbin/init") : List.of("tail", "-f", "/dev/null"));
        if (namespace == null) {
            specBuilder.withEmbeddedDns().withHostDockerInternalOnLinux().withPortBinding(22, sshHostPort);
        } else {
            specBuilder.withNetworkMode("container:" + namespace.helperId());
            specBuilder.withLabels(Map.of("floci.security-group-workload", "true"));
        }
        if (image.systemd()) {
            specBuilder
                    .withCgroupnsMode("host")
                    .withMount(new Mount().withType(MountType.TMPFS).withTarget("/run"))
                    .withMount(new Mount().withType(MountType.TMPFS).withTarget("/run/lock"))
                    .withBind("/sys/fs/cgroup", "/sys/fs/cgroup");
        }
        return specBuilder.build();
    }

    private void failLaunch(Instance instance) {
        failLaunch(instance, instance.getPrivateIpAddress());
    }

    /** Releases failed guest resources without revoking an already-ready control-plane record. */
    private void failLaunch(Instance instance, String leasedPrivateIp) {
        if (needsPrivateNamespace(instance)) {
            synchronized (instance) {
                instance.setContainerLaunchFailed(true);
                instance.setContainerLaunchPending(false);
                metadataServer.unregisterInstance(instance);
                Backing backing = backings.get(instance);
                if (backing == null && instance.getDockerContainerId() != null) {
                    backing = restoredBacking(instance);
                }
                if (backing == null) {
                    instance.setState(InstanceState.terminated());
                    instance.setTerminatedAt(System.currentTimeMillis());
                } else {
                    instance.setState(InstanceState.shuttingDown());
                    finishManagedRemoval(instance, backing);
                }
            }
            return;
        }
        String containerId;
        int sshHostPort;
        String containerIp;
        boolean alreadyCleaned;
        synchronized (instance) {
            alreadyCleaned = instance.isContainerLaunchFailed() && !instance.isContainerLaunchPending()
                    && instance.getDockerContainerId() == null
                    && instance.getSshHostPort() <= 0
                    && (instance.getContainerBridgeIp() == null || instance.getContainerBridgeIp().isBlank());
            instance.setContainerLaunchFailed(true);
            instance.setContainerLaunchPending(false);
            if (instance.getState() == null || !"running".equals(instance.getState().getName())) {
                instance.setState(InstanceState.terminated());
            }
            containerId = instance.getDockerContainerId();
            sshHostPort = instance.getSshHostPort();
            containerIp = instance.getContainerBridgeIp();
            instance.setDockerContainerId(null);
            instance.setSshHostPort(0);
            instance.setContainerBridgeIp(null);
            metadataServer.unregisterInstance(instance);
        }
        if (alreadyCleaned) {
            return;
        }
        try {
            vpcNetworkManager.detach(instance.getRegion(), instance.getVpcId(), containerId);
            vpcNetworkManager.releasePrivateIp(instance.getRegion(), instance.getSubnetId(), leasedPrivateIp);
        } catch (Exception e) {
            LOG.warnv("Error releasing the VPC address of failed EC2 launch {0}: {1}",
                    instance.getInstanceId(), e.getMessage());
        }
        try {
            portForwardManager.unpublishAll(instance);
        } catch (Exception e) {
            LOG.warnv("Error removing EC2 port forwards during failed launch of {0}: {1}",
                    instance.getInstanceId(), e.getMessage());
        }
        if (containerId != null) {
            try {
                lifecycleManager.removeIfExists(containerId);
            } catch (Exception e) {
                LOG.warnv("Error removing failed EC2 container {0}: {1}", containerId, e.getMessage());
            }
        }
        if (firewallManager != null && firewallManager.enabled()
                && instance.getNetworkInterfaces() != null && !instance.getNetworkInterfaces().isEmpty()) {
            firewallManager.unregister(instance.getNetworkInterfaces().getFirst().getNetworkInterfaceId());
        }
        if (sshHostPort > 0) {
            portAllocator.release(sshHostPort);
        }
        if (containerIp != null && !containerIp.isBlank()) {
            try {
                metadataServer.unregisterContainer(containerIp, instance);
            } catch (Exception e) {
                LOG.warnv("Error unregistering failed EC2 instance {0}: {1}", instance.getInstanceId(), e.getMessage());
            }
        }
    }

    /** Cancels an unfinished guest launch, preventing a late Docker create from publishing a container. */
    boolean cancelLaunch(Instance instance) {
        synchronized (instance) {
            String state = instance.getState() != null ? instance.getState().getName() : null;
            if ("running".equals(state) && !instance.isContainerLaunchPending()) {
                return false;
            }
            instance.setContainerLaunchFailed(true);
            if (!"running".equals(state)) {
                instance.setState(needsPrivateNamespace(instance) ? InstanceState.shuttingDown() : InstanceState.terminated());
            }
        }
        failLaunch(instance);
        return true;
    }

    private static boolean isLaunchCancelled(Instance instance) {
        synchronized (instance) {
            return isLaunchCancelledState(instance);
        }
    }

    private static boolean markRunning(Instance instance) {
        synchronized (instance) {
            if (isLaunchCancelledState(instance)) {
                return false;
            }
            instance.setState(InstanceState.running());
            instance.setContainerLaunchPending(false);
            return true;
        }
    }

    private static boolean recordCreatedContainer(Instance instance, String containerId, int sshHostPort) {
        synchronized (instance) {
            if (isLaunchCancelledState(instance)) {
                return false;
            }
            instance.setSshHostPort(sshHostPort);
            instance.setDockerContainerId(containerId);
            return true;
        }
    }

    private static boolean clearRecordedContainer(Instance instance, String containerId, int sshHostPort) {
        synchronized (instance) {
            if (!Objects.equals(containerId, instance.getDockerContainerId())
                    || sshHostPort != instance.getSshHostPort()) {
                return false;
            }
            instance.setDockerContainerId(null);
            instance.setSshHostPort(0);
            return true;
        }
    }

    private static boolean isLaunchCancelledState(Instance instance) {
        String state = instance.getState() != null ? instance.getState().getName() : null;
        return instance.isContainerLaunchFailed() || "shutting-down".equals(state) || "terminated".equals(state);
    }

    private static boolean isHostPortCollision(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.toLowerCase(Locale.ROOT).contains("port is already allocated")
                    || message.toLowerCase(Locale.ROOT).contains("address already in use"))) {
                return true;
            }
        }
        return false;
    }

    private record StartedContainer(String containerId, int sshHostPort, String vpcAddress,
                                    SecurityGroupFirewallManager.Namespace namespace, String eniId) {
    }

    /**
     * Reports whether a Docker daemon is reachable, logging the transition in each
     * direction once rather than on every launch.
     */
    public boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            if (dockerUnavailableLogged) {
                dockerUnavailableLogged = false;
                LOG.info("Docker daemon is reachable again; new EC2 instances get a backing container.");
            }
            return true;
        } catch (Exception e) {
            if (!dockerUnavailableLogged) {
                dockerUnavailableLogged = true;
                LOG.warnv("No Docker daemon is reachable from Floci ({0}). EC2 instances are emulated as "
                        + "metadata only: they reach running and honour stop, start and terminate, but have "
                        + "no backing container, so SSH, UserData and SSM command execution stay unavailable "
                        + "until a daemon is reachable.", e.getMessage());
            }
            return false;
        }
    }

    /**
     * Brings an instance to running with no container behind it. Stop, start, terminate and
     * reboot already handle a null container id, so the rest of the lifecycle keeps working.
     */
    private void markContainerlessRunning(Instance instance) {
        LOG.infov("EC2 instance {0} is running without a backing container (no Docker daemon reachable)",
                instance.getInstanceId());
        markRunning(instance);
    }

    /**
     * Synchronous stop for emulator shutdown: tears down the port-forward sidecars and
     * stops the container with a short timeout so N instances cannot exhaust the SIGTERM
     * grace window. Unlike {@link #stop}, runs on the caller's thread (the async executor
     * would be abandoned mid-flight during shutdown) and leaves the final stopped state to the caller.
     */
    public void stopForShutdown(Instance instance) {
        synchronized (instance) {
            instance.setState(InstanceState.stopping());
            metadataServer.unregisterInstance(instance);
        }
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            return;
        }
        portForwardManager.unpublishAll(instance);
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
        } catch (NotFoundException e) {
            // already gone
        } catch (Exception e) {
            LOG.warnv("Error stopping EC2 container {0} on shutdown: {1}", containerId, e.getMessage());
        }
    }

    /**
     * Gracefully stops a running container (30 second timeout then SIGKILL).
     * Updates instance state through stopping → stopped.
     */
    public void stop(Instance instance) {
        String containerId;
        synchronized (instance) {
            if (needsPrivateNamespace(instance) && isLaunchCancelledState(instance)) {
                return;
            }
            containerId = instance.getDockerContainerId();
            instance.setState(containerId == null ? InstanceState.stopped() : InstanceState.stopping());
            metadataServer.unregisterInstance(instance);
        }
        if (containerId == null) {
            return;
        }
        executor.submit(() -> {
            // Sidecars forward to the container's current IP, which Docker reassigns on the
            // next start; tear them down so no forward is left pointing at a stale address.
            portForwardManager.unpublishAll(instance);
            try {
                dockerClient.stopContainerCmd(containerId).withTimeout(30).exec();
            } catch (NotFoundException e) {
                // already gone
            } catch (Exception e) {
                LOG.warnv("Error stopping EC2 container {0}: {1}", containerId, e.getMessage());
            }
            synchronized (instance) {
                if (!isLaunchCancelledState(instance)
                        && Objects.equals(containerId, instance.getDockerContainerId())
                        && "stopping".equals(instance.getState().getName())) {
                    instance.setState(InstanceState.stopped());
                }
            }
        });
    }

    /**
     * Starts a previously stopped container.
     * Updates instance state through pending → running.
     */
    public void start(Instance instance) {
        String containerId;
        synchronized (instance) {
            if (needsPrivateNamespace(instance) && isLaunchCancelledState(instance)) {
                throw new IllegalStateException("Cannot start an EC2 instance whose backing is being removed");
            }
            metadataServer.unregisterInstance(instance);
            containerId = instance.getDockerContainerId();
            if (containerId == null) {
                if (needsPrivateNamespace(instance)) {
                    throw new IllegalStateException("EC2 private instance has no backing container to start");
                }
                instance.setState(InstanceState.running());
                return;
            }
            instance.setState(InstanceState.pending());
        }
        executor.submit(() -> {
            try {
                dockerClient.startContainerCmd(containerId).exec();
                boolean running = false;
                for (int i = 0; i < 20 && !running; i++) {
                    running = lifecycleManager.isContainerRunning(containerId);
                    if (!running) {
                        Thread.sleep(500);
                    }
                }
                String instanceId = instance.getInstanceId();
                // A protected workload shares the helper's namespace, so it has no Docker network
                // attachment of its own to rediscover: the helper keeps the transport address
                // across the workload's stop/start, exactly as launch used it.
                ProtectedNamespace namespace = protectedNamespace(instance);
                if (needsPrivateNamespace(instance) && (namespace == null || !running)) {
                    throw new IllegalStateException("EC2 private namespace did not restart");
                }
                String containerIp = namespace == null
                        ? waitForContainerBridgeIp(containerId, instanceId)
                        : namespace.transportAddress();
                if (containerIp != null && !containerIp.isBlank()) {
                    instance.setContainerBridgeIp(containerIp);
                    if (namespace == null) {
                        exposeReachablePrivateAddress(instance, containerIp,
                                config.services().ec2().awsFaithfulPrivateIp());
                    }
                    // Docker hands out a new bridge IP on restart, so the previously reported
                    // public address can now point at another container entirely.
                    if (instance.isAssociatePublicIp() || instance.getPublicIpAddress() != null) {
                        exposeReachablePublicAddress(instance);
                    }
                    metadataServer.registerContainer(containerIp, instanceId, instance);
                    String networkContainer = namespace == null ? containerId : namespace.helperId();
                    refreshImdsSourceRegistration(instance, networkContainer, containerIp);
                    refreshMetadataAddresses(instance, networkContainer);
                    if (!configureLinkLocalMetadataEndpoint(instance, networkContainer) && needsPrivateNamespace(instance)) {
                        throw new IllegalStateException("Cannot configure EC2 namespace metadata proxy");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markStartFailed(instance, containerId);
                return;
            } catch (Exception e) {
                LOG.warnv("Error starting EC2 container {0}: {1}", containerId, e.getMessage());
                markStartFailed(instance, containerId);
                return;
            }
            if (markRunning(instance) && needsPrivateNamespace(instance)) {
                portForwardManager.restore(instance);
            }
        });
    }

    private void markStartFailed(Instance instance, String containerId) {
        synchronized (instance) {
            if (!isLaunchCancelledState(instance) && Objects.equals(containerId, instance.getDockerContainerId())
                    && "pending".equals(instance.getState().getName())) {
                metadataServer.unregisterInstance(instance);
                instance.setState(InstanceState.stopped());
            }
        }
    }

    boolean isContainerRunning(String containerId) {
        return containerId != null && !containerId.isBlank() && lifecycleManager.isContainerRunning(containerId);
    }

    /**
     * Removes EC2 instance containers this Floci left behind on a previous run.
     *
     * <p>{@link #terminate} removes the container asynchronously after flipping the record to
     * {@code shutting-down}, and {@code launch} persists the container id only once Docker has
     * created it. A process killed across either edge — SIGKILL, OOM, {@code docker kill} — leaves
     * a container on the daemon that no surviving record refers to, so nothing on the next run
     * would ever collect it. {@code Ec2Service.stopManagedContainers} only covers the graceful
     * ShutdownEvent path, and {@code restoreMetadataRegistration} deliberately skips records in
     * {@code terminated}/{@code shutting-down}, so neither reaches these.
     *
     * <p><strong>Stopped instances are not orphans.</strong> Floci stops every running container
     * on shutdown and keeps the id so StartInstances can revive it; those records come back as
     * {@code stopped} and {@code stillDeclared} keeps them. Only containers with no surviving
     * record, or whose record is already terminated/shutting-down, are removed.
     *
     * @param stillDeclared answers whether (region, instanceId) is still a live instance record
     * @return the number of containers removed
     */
    public int reconcileOrphanedContainers(BiPredicate<String, String> stillDeclared) {
        if (!config.services().ec2().reconcileContainersOnStartup()) {
            return 0;
        }
        String owner = ownerIdentity();
        int removed = 0;
        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(LABEL_SERVICE, SERVICE_VALUE))
                    .exec();
            for (Container container : containers) {
                Map<String, String> labels = container.getLabels() == null ? Map.of() : container.getLabels();
                if (!owner.equals(labels.get(LABEL_OWNER_PORT))) {
                    continue;
                }
                String instanceId = labels.get(LABEL_RESOURCE_ID);
                String region = labels.get(LABEL_REGION);
                if (instanceId != null && !instanceId.isBlank()
                        && region != null && !region.isBlank()
                        && stillDeclared.test(region, instanceId)) {
                    continue;
                }
                lifecycleManager.removeIfExists(container.getId());
                removed++;
                LOG.infov("Reconciled orphaned EC2 container {0} (instance {1}) left by a previous run",
                        container.getId(), String.valueOf(instanceId));
            }
        } catch (Exception e) {
            LOG.warnv("Could not reconcile orphaned EC2 containers: {0}", e.getMessage());
        }
        if (removed > 0) {
            LOG.infov("Removed {0} orphaned EC2 container(s)", String.valueOf(removed));
        }
        return removed;
    }

    private void refreshMetadataAddresses(Instance instance, String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            if (inspect.getNetworkSettings() == null || inspect.getNetworkSettings().getNetworks() == null) {
                return;
            }
            Set<String> addresses = new HashSet<>();
            for (ContainerNetwork network : inspect.getNetworkSettings().getNetworks().values()) {
                if (network != null && network.getIpAddress() != null && !network.getIpAddress().isBlank()) {
                    addresses.add(network.getIpAddress());
                }
            }
            // An incomplete Docker response must not discard the last known registrations.
            if (!addresses.isEmpty()) {
                metadataServer.reconcileContainerAddresses(addresses, instance);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Could not refresh IMDS addresses for EC2 instance {0}, keeping existing registrations: {1}",
                    instance.getInstanceId(), e.getMessage());
        }
    }

    boolean restoreMetadataRegistration(Instance instance) {
        if (instance == null || instance.getDockerContainerId() == null) {
            return false;
        }
        String containerId = instance.getDockerContainerId();
        if (!lifecycleManager.isContainerRunning(containerId)) {
            return false;
        }

        ProtectedNamespace namespace = needsPrivateNamespace(instance) ? protectedNamespace(instance) : null;
        if (needsPrivateNamespace(instance) && namespace == null) {
            throw new IllegalStateException("EC2 private networking has no retained namespace");
        }
        String networkContainer = namespace == null ? containerId : namespace.helperId();
        String containerIp = namespace == null ? getContainerBridgeIp(containerId) : namespace.transportAddress();
        if (containerIp == null || containerIp.isBlank()) {
            containerIp = instance.getContainerBridgeIp();
        }
        if (containerIp == null || containerIp.isBlank()) {
            LOG.warnv("Could not restore IMDS registration for EC2 instance {0}: no container IP",
                    instance.getInstanceId());
            return false;
        }

        String previousContainerIp = instance.getContainerBridgeIp();
        if (previousContainerIp != null && !previousContainerIp.isBlank() && !previousContainerIp.equals(containerIp)) {
            metadataServer.unregisterContainer(previousContainerIp, instance);
        }
        instance.setContainerBridgeIp(containerIp);
        exposeReachablePrivateAddress(instance, containerIp, config.services().ec2().awsFaithfulPrivateIp());
        if (instance.isAssociatePublicIp() || instance.getPublicIpAddress() != null) {
            exposeReachablePublicAddress(instance);
        }
        metadataServer.registerContainer(containerIp, instance.getInstanceId(), instance);
        refreshImdsSourceRegistration(instance, networkContainer, containerIp);
        refreshMetadataAddresses(instance, networkContainer);
        return configureMetadataEndpoint(instance);
    }

    /**
     * Keeps the IMDS registration of a VPC-attached instance's bridge address current.
     *
     * <p>An instance on a VPC network has two addresses, and only one of them is the one IMDS
     * sees. {@code Ec2MetadataServer} resolves an instance from the source address of the
     * request, and the container's default route is the bridge, so its metadata requests arrive
     * from the bridge address. The address Floci reports, and the one
     * {@link #getContainerBridgeIp} prefers, is the VPC address, so registering only that one
     * leaves IMDS with no entry for the address the requests actually come from.
     *
     * <p>{@code launch} gets this right. {@link #start} and {@link #restoreMetadataRegistration}
     * did not, and both are exactly where it goes wrong: Docker hands out a fresh bridge address
     * when a stopped container starts again, and an emulator restart rebuilds the registration
     * map empty. Either way the instance came back with its bridge address unregistered and
     * {@code imdsSourceIp} still naming the address of a previous run, which then also survived
     * as a stale entry pointing at this instance.
     *
     * @param reportedIp the address the instance reports, already registered by the caller; when
     *                   the bridge address is the same one, there is no second address to track
     */
    private void refreshImdsSourceRegistration(Instance instance, String containerId, String reportedIp) {
        String bridgeIp;
        try {
            bridgeIp = bridgeNetworkIp(containerId);
        } catch (RuntimeException e) {
            // An inspect that failed says nothing about where the container is attached, and the
            // unregister below is only correct for a container that definitely has no separate
            // bridge address. Reading "I could not find out" as "there is none" would tear down a
            // healthy instance's IMDS source registration over a transient Docker hiccup and put
            // nothing in its place, leaving its metadata requests unresolvable until some later
            // start or restore happened to succeed. Leave the registration exactly as it is; the
            // next start or restore refreshes it once inspect works again.
            LOG.warnv("Could not inspect container {0} for its bridge IP, leaving the IMDS source "
                    + "registration of EC2 instance {1} unchanged: {2}",
                    containerId, instance.getInstanceId(), e.getMessage());
            return;
        }
        // Nothing to track separately when the reported address is the bridge address: that is a
        // plain bridge-only instance, and the caller has already registered it.
        String current = bridgeIp != null && !bridgeIp.isBlank() && !bridgeIp.equals(reportedIp) ? bridgeIp : null;
        String previous = instance.getImdsSourceIp();

        if (previous != null && !previous.isBlank() && !previous.equals(current)) {
            metadataServer.unregisterContainer(previous, instance);
            instance.setImdsSourceIp(null);
        }
        if (current != null) {
            instance.setImdsSourceIp(current);
            metadataServer.registerContainer(current, instance.getInstanceId(), instance);
        }
    }

    /**
     * The container's address on Docker's default bridge, which is where its default route, and
     * so the source address of its IMDS requests, lives. Distinct from
     * {@link #getContainerBridgeIp}, which despite the name prefers the VPC network's address.
     *
     * <p>Returning null has to mean one thing only, "this container has no bridge address",
     * because callers act on that answer. A failed inspect is a different answer, "I could not
     * find out", so it propagates instead of being folded into the same null.
     *
     * @return the address, or null when the container is not on the default bridge at all
     * @throws RuntimeException if the container could not be inspected, leaving its bridge
     *                          attachment unknown rather than known to be absent
     */
    private String bridgeNetworkIp(String containerId) {
        var inspect = dockerClient.inspectContainerCmd(containerId).exec();
        if (inspect.getNetworkSettings() == null || inspect.getNetworkSettings().getNetworks() == null) {
            return null;
        }
        ContainerNetwork bridge = inspect.getNetworkSettings().getNetworks().get("bridge");
        return bridge == null || bridge.getIpAddress() == null || bridge.getIpAddress().isBlank()
                ? null : bridge.getIpAddress();
    }

    /**
     * The address Floci is willing to hand out as this instance's public address — chosen so
     * that whatever dials it reaches the guest on the service's own port.
     *
     * <p>Where container IPs are routable that is the container's IP: port 22 is really port
     * 22 there, and so is every other port the guest listens on, with no published mapping to
     * translate. Where they are not, it falls back to {@code 127.0.0.1}, which is where the
     * published high host ports live — reachable, though not on the ports AWS clients assume.
     *
     * @return the address, or null if the instance has no container IP yet
     */
    public String reachablePublicAddress(Instance instance) {
        if (instance == null) {
            return null;
        }
        String containerIp = instance.getContainerBridgeIp();
        if (containerIp == null || containerIp.isBlank()) {
            return null;
        }
        return containerNetworkReachability.isContainerIpRoutable(containerIp) ? containerIp : "127.0.0.1";
    }

    /**
     * Publishes {@link #reachablePublicAddress} as the instance's public IP and DNS name.
     * The DNS name is set to the same literal rather than an AWS-shaped
     * {@code ec2-…​.compute-1.amazonaws.com} hostname, because that hostname resolves nowhere
     * and callers that prefer PublicDnsName over PublicIpAddress would be handed a dead name.
     */
    void exposeReachablePublicAddress(Instance instance) {
        if (instance != null
                && (instance.getInstanceId() + ".localhost.floci.io").equals(instance.getPublicIpAddress())) {
            return;
        }
        String publicAddress = reachablePublicAddress(instance);
        if (publicAddress == null) {
            return;
        }
        instance.setPublicIpAddress(publicAddress);
        instance.setPublicDnsName("127.0.0.1".equals(publicAddress) ? "localhost" : publicAddress);
    }

    static void exposeReachablePrivateAddress(Instance instance, String privateIp) {
        exposeReachablePrivateAddress(instance, privateIp, false);
    }

    /**
     * Overwrite the instance's reported private address with the container's
     * reachable bridge IP — unless {@code awsFaithful} is true (#1983), in which
     * case the CFN/subnet-allocated private IP set at launch is left untouched.
     * The container bridge IP is tracked separately (setContainerBridgeIp) and
     * used for routing/IMDS regardless of this flag.
     */
    static void exposeReachablePrivateAddress(Instance instance, String privateIp, boolean awsFaithful) {
        if (instance == null || privateIp == null || privateIp.isBlank()) {
            return;
        }
        if (awsFaithful || instance.getLogicalPrivateIpAddress() != null) {
            return;
        }

        String privateDnsName = "ip-" + privateIp.replace('.', '-') + ".ec2.internal";
        instance.setPrivateIpAddress(privateIp);
        instance.setPrivateDnsName(privateDnsName);
        if (instance.getNetworkInterfaces() != null) {
            instance.getNetworkInterfaces().forEach(networkInterface -> {
                networkInterface.setPrivateIpAddress(privateIp);
                networkInterface.setPrivateDnsName(privateDnsName);
            });
        }
    }

    /**
     * Terminates an instance: forcefully removes the container.
     * Updates state through shutting-down → terminated.
     * Sets terminatedAt for TTL pruning.
     */
    public void terminate(Instance instance) {
        if (needsPrivateNamespace(instance)) {
            Backing backing;
            synchronized (instance) {
                if (instance.getState() != null && "terminated".equals(instance.getState().getName())) {
                    return;
                }
                instance.setState(InstanceState.shuttingDown());
                metadataServer.unregisterInstance(instance);
                backing = restoredBacking(instance);
            }
            try {
                executor.execute(() -> finishManagedRemoval(instance, backing));
            } catch (RejectedExecutionException e) {
                finishManagedRemoval(instance, backing);
            }
            return;
        }
        String containerId;
        String containerIp;
        String imdsSourceIp;
        int sshHostPort;
        synchronized (instance) {
            containerId = instance.getDockerContainerId();
            containerIp = instance.getContainerBridgeIp();
            imdsSourceIp = instance.getImdsSourceIp();
            sshHostPort = instance.getSshHostPort();
            instance.setState(InstanceState.shuttingDown());
            metadataServer.unregisterInstance(instance);
        }
        executor.submit(() -> {
            portForwardManager.unpublishAll(instance);
            if (containerId != null) {
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                } catch (NotFoundException e) {
                    // already gone
                } catch (Exception e) {
                    LOG.warnv("Error removing EC2 container {0}: {1}", containerId, e.getMessage());
                }
                try {
                    // iptables/veth teardown lags behind container removal; prevents port-reuse conflicts.
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (firewallManager != null && firewallManager.enabled()
                    && instance.getNetworkInterfaces() != null && !instance.getNetworkInterfaces().isEmpty()) {
                firewallManager.unregister(instance.getNetworkInterfaces().getFirst().getNetworkInterfaceId());
            }
            if (sshHostPort > 0) {
                portAllocator.release(sshHostPort);
            }
            metadataServer.unregisterContainer(containerIp, instance);
            metadataServer.unregisterContainer(imdsSourceIp, instance);
            // Give the address back only now that the container is gone: releasing it while
            // Docker still holds the endpoint would hand the same IP to the next launch and
            // have Docker refuse it.
            vpcNetworkManager.releasePrivateIp(instance.getRegion(), instance.getSubnetId(),
                    instance.getPrivateIpAddress());
            instance.setState(InstanceState.terminated());
            instance.setTerminatedAt(System.currentTimeMillis());
        });
    }

    /**
     * Reboots an instance via docker restart.
     */
    public void reboot(Instance instance) {
        if (needsPrivateNamespace(instance) && isLaunchCancelled(instance)) {
            throw new IllegalStateException("Cannot reboot an EC2 instance whose backing is being removed");
        }
        metadataServer.unregisterInstance(instance);
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            return;
        }
        executor.submit(() -> {
            try {
                dockerClient.restartContainerCmd(containerId).exec();
                LOG.infov("Rebooted EC2 container {0}", containerId);
                restartGuestServices(instance, containerId);
            } catch (Exception e) {
                LOG.warnv("Error rebooting EC2 container {0}: {1}", containerId, e.getMessage());
            }
        });
    }

    /**
     * Post-reboot guest bring-up. On a real instance systemd restarts enabled
     * units at boot — ExecStartPre included, which re-downloads the Alchemy
     * hosted bundle and env file. The systemctl shim's unit processes die
     * with the container and nothing inside the guest re-runs them, so mirror
     * the launch-time bring-up here: wait for the restarted container,
     * refresh the IMDS wiring for its (possibly changed) bridge IP, re-run
     * the persisted user data (idempotent for Alchemy hosted guests: it
     * rewrites the same unit files and {@code systemctl enable --now}s them —
     * the shim sees the stale pidfile and starts a fresh process with a fresh
     * ExecStartPre), and re-point the port-forward mux at the new IP.
     */
    private void restartGuestServices(Instance instance, String containerId) {
        try {
            String instanceId = instance.getInstanceId();
            boolean running = false;
            for (int i = 0; i < 30 && !running; i++) {
                running = lifecycleManager.isContainerRunning(containerId);
                if (!running) {
                    Thread.sleep(500);
                }
            }
            if (!running) {
                LOG.warnv("EC2 container {0} did not come back after reboot", containerId);
                return;
            }
            ProtectedNamespace namespace = protectedNamespace(instance);
            if (needsPrivateNamespace(instance) && (namespace == null || isLaunchCancelled(instance))) {
                throw new IllegalStateException("EC2 private namespace is unavailable after reboot");
            }
            String containerIp = namespace == null
                    ? waitForContainerBridgeIp(containerId, instanceId) : namespace.transportAddress();
            if (containerIp != null && !containerIp.isBlank()) {
                instance.setContainerBridgeIp(containerIp);
                metadataServer.registerContainer(containerIp, instanceId, instance);
                String networkContainer = namespace == null ? containerId : namespace.helperId();
                refreshImdsSourceRegistration(instance, networkContainer, containerIp);
                refreshMetadataAddresses(instance, networkContainer);
            }
            if (!configureLinkLocalMetadataEndpoint(instance, namespace == null ? containerId : namespace.helperId())
                    && needsPrivateNamespace(instance)) {
                throw new IllegalStateException("Cannot configure EC2 namespace metadata proxy after reboot");
            }
            String userData = instance.getUserData();
            if (userData != null && !userData.isBlank()) {
                executeUserData(containerId, instanceId, userData, instance.getRegion());
            }
            // Mux backends capture the instance's bridge IP at registration —
            // re-register so a changed IP does not strand the published port.
            portForwardManager.restore(instance);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warnv("Post-reboot bring-up failed for EC2 container {0}: {1}",
                    containerId, e.getMessage());
        }
    }

    public boolean isContainerRunning(Instance instance) {
        String containerId = instance.getDockerContainerId();
        return containerId != null && lifecycleManager.isContainerRunning(containerId);
    }

    /** Signals that an instance's file system could not be captured as a Docker image. */
    public static class CaptureFailedException extends RuntimeException {
        public CaptureFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Captures an instance's file system as a new Docker image, so that an AMI created from it
     * carries what was provisioned rather than pointing back at the base image.
     *
     * <p>Returns the image reference, or null when the instance has no container to capture.
     * A commit that is attempted and fails throws instead of returning null: an AMI with no
     * captured file system launches its ancestor, so reporting the failure as "no capture" would
     * hand back an available AMI whose contents are silently not what was asked for.
     *
     * @param tag repository:tag to commit to, unique per AMI
     * @throws CaptureFailedException if the commit was attempted and did not succeed
     */
    public String commitInstance(Instance instance, String tag) {
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            return null;
        }
        try {
            // Committing a running container is what AWS does for CreateImage without
            // NoReboot; docker quiesces nothing either way, so the semantics match closely
            // enough. The container is left running -- CreateImage does not terminate its
            // source instance.
            String imageId = dockerClient.commitCmd(containerId)
                    .withRepository(tag.contains(":") ? tag.substring(0, tag.indexOf(':')) : tag)
                    .withTag(tag.contains(":") ? tag.substring(tag.indexOf(':') + 1) : "latest")
                    .exec();
            LOG.infov("Captured EC2 instance {0} as Docker image {1} ({2})",
                    instance.getInstanceId(), tag, imageId);
            return tag;
        } catch (Exception e) {
            LOG.warnv("Could not capture EC2 instance {0} as an image: {1}",
                    instance.getInstanceId(), e.getMessage());
            throw new CaptureFailedException("could not commit container " + containerId
                    + " of instance " + instance.getInstanceId() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Removes an image previously produced by {@link #commitInstance}. Called when the AMI that
     * owns it is deregistered, so captures do not accumulate on disk indefinitely.
     *
     * @return true when the layer is known to be gone, either removed now or already absent;
     *         false when the daemon refused, in which case the caller must keep the reference
     *         so the layer can still be found and removed later
     */
    public boolean removeCommittedImage(String tag) {
        if (tag == null) {
            return true;
        }
        try {
            dockerClient.removeImageCmd(tag).withForce(true).exec();
            LOG.infov("Removed captured Docker image {0}", tag);
            return true;
        } catch (NotFoundException e) {
            // Already gone: deregistering twice, or the daemon was pruned. Not an error.
            LOG.debugv("Captured Docker image {0} was already absent", tag);
            return true;
        } catch (Exception e) {
            LOG.warnv("Could not remove captured Docker image {0}: {1}", tag, e.getMessage());
            return false;
        }
    }

    private void injectSshKey(String containerId, String publicKey) {
        try {
            // Ensure .ssh directory exists with correct permissions
            execInContainer(containerId, new String[]{"sh", "-c",
                    "mkdir -p /root/.ssh && chmod 700 /root/.ssh"}, 10);

            // Copy authorized_keys via docker cp
            String keyContent = publicKey.trim() + "\n";
            byte[] tar = buildSingleFileTar("authorized_keys", keyContent.getBytes(StandardCharsets.UTF_8), 0600);
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath("/root/.ssh")
                    .withTarInputStream(new ByteArrayInputStream(tar))
                    .exec();

            execInContainer(containerId, new String[]{"chmod", "600", "/root/.ssh/authorized_keys"}, 5);
            LOG.infov("Injected SSH public key into container {0}", containerId);
        } catch (Exception e) {
            LOG.warnv("Could not inject SSH key into container {0}: {1}", containerId, e.getMessage());
        }
    }

    private void startSshd(String containerId, String instanceId) {
        try {
            // Custom and captured guests supply their own tools; startup never downloads packages.
            ContainerExecResult install = execInContainerForResult(containerId, sshdInstallProbeCommand(), 10);
            if (install.exitCode() == SSH_CLIENT_MISSING_EXIT_CODE) {
                LOG.warnv("sshd is available on EC2 instance {0} but the OpenSSH client package is not:"
                        + " scp is missing, so provisioners that upload files over scp will fail: {1}",
                        instanceId, install.summary());
            } else if (install.exitCode() != 0) {
                LOG.warnv("OpenSSH server is unavailable for EC2 instance {0}: {1}",
                        instanceId, install.summary());
                return;
            }
            // Generate host keys
            ContainerExecResult keygen = execInContainerForResult(containerId, new String[]{"ssh-keygen", "-A"}, 10);
            if (keygen.exitCode() != 0) {
                LOG.warnv("Could not generate SSH host keys for EC2 instance {0}: {1}",
                        instanceId, keygen.summary());
                return;
            }
            // Modern OpenSSH refuses to start without its privilege-separation directory, and /run
            // is a fresh tmpfs in most container images, so /run/sshd genuinely isn't there yet.
            ContainerExecResult mkdir = execInContainerForResult(containerId,
                    new String[]{"mkdir", "-p", "/run/sshd"}, 5);
            if (mkdir.exitCode() != 0) {
                LOG.warnv("Could not create /run/sshd for EC2 instance {0}: {1}",
                        instanceId, mkdir.summary());
                return;
            }
            // Start sshd without -D so it daemonizes itself and survives this exec session. Since sshd
            // requires execution with an absolute path, several paths are tried until it starts
            for (String sshdPath : ALLOWED_SSHD_PATHS) {
                ContainerExecResult start = execInContainerForResult(containerId, new String[]{sshdPath}, 5);
                if (start.exitCode() != 0) {
                    LOG.warnv("Could not start sshd using path {0} for EC2 instance {1}: {2}", sshdPath, instanceId, start.summary());
                    continue;
                }
                LOG.infov("Started sshd in EC2 instance {0}", instanceId);
                return;
            }
        } catch (Exception e) {
            LOG.warnv("Could not start sshd in EC2 instance {0}: {1}", instanceId, e.getMessage());
        }
    }

    private void executeUserData(String containerId, String instanceId, String userData, String region) {
        try {
            String logGroup = "/aws/ec2/" + instanceId;
            String logStream = logStreamer.generateLogStreamName("user-data");

            List<String> shellScripts = userDataShellScripts(userData);
            if (shellScripts.isEmpty()) {
                LOG.warnv("UserData for EC2 instance {0} did not contain executable shellscript parts, "
                        + "so nothing it was meant to install has run. Floci executes cloud-init "
                        + "text/x-shellscript parts and bare '#!' scripts only.", instanceId);
                return;
            }

            // Execute the script and stream output to CloudWatch
            for (int i = 0; i < shellScripts.size(); i++) {
                executeUserDataShellScript(
                        containerId, instanceId, shellScripts.get(i), i + 1, shellScripts.size(),
                        logGroup, logStream, region
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnv("UserData execution interrupted for EC2 instance {0}", instanceId);
        } catch (Exception e) {
            LOG.warnv("UserData execution failed for EC2 instance {0}: {1}", instanceId, e.getMessage());
        }
    }

    private void executeUserDataShellScript(
            String containerId, String instanceId, String scriptContent, int partNumber, int partCount,
            String logGroup, String logStream, String region
    ) throws Exception {
        byte[] script = scriptContent.getBytes(StandardCharsets.UTF_8);
        byte[] tar = buildSingleFileTar("user-data.sh", script, 0755);
        dockerClient.copyArchiveToContainerCmd(containerId)
                .withRemotePath("/var/lib")
                .withTarInputStream(new ByteArrayInputStream(tar))
                .exec();

        // Execute the script directly so Docker honors its shebang, matching cloud-init shellscript behavior.
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd(userDataExecutionCommand())
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();

        BoundedOutput output = new BoundedOutput(MAX_EXEC_OUTPUT_BYTES);
        CountDownLatch latch = new CountDownLatch(1);

        AtomicBoolean cancelled = new AtomicBoolean();
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onStart(Closeable stream) {
                if (cancelled.get()) {
                    closeUserDataStream(stream, instanceId);
                    return;
                }
                super.onStart(stream);
                if (cancelled.get()) {
                    closeUserDataStream(stream, instanceId);
                }
            }

            @Override
            public void onNext(Frame frame) {
                if (cancelled.get()) {
                    return;
                }
                byte[] payload = frame.getPayload();
                if (payload == null) {
                    return;
                }
                try { output.write(payload); } catch (IOException ignored) {}
                String line = new String(payload, StandardCharsets.UTF_8).stripTrailing();
                if (!line.isEmpty()) {
                    logStreamer.streamToCloudWatchLogs(logGroup, logStream, region, line);
                }
            }
            @Override
            public void onComplete() { latch.countDown(); }
            @Override
            public void onError(Throwable t) { latch.countDown(); }
            @Override
            public void close() throws IOException {
                cancelled.set(true);
                super.close();
            }
        };
        activeUserDataCallbacks.add(callback);

        try {
            dockerClient.execStartCmd(execId).exec(callback);

            boolean completed = latch.await(userDataExecutionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                LOG.warnv("UserData shellscript part {0}/{1} timed out for EC2 instance {2}",
                        partNumber, partCount, instanceId);
                return;
            }

            Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            if (exitCode != null && exitCode != 0) {
                LOG.warnv("UserData shellscript part {0}/{1} failed for EC2 instance {2} with exit code {3}: {4}",
                        partNumber, partCount, instanceId, exitCode, summarizeUserDataOutput(output));
                return;
            }

            LOG.infov("UserData shellscript part {0}/{1} completed for EC2 instance {2}: {3}",
                    partNumber, partCount, instanceId, summarizeUserDataOutput(output));
        } finally {
            activeUserDataCallbacks.remove(callback);
            closeUserDataCallback(callback, instanceId);
        }
    }

    private void closeActiveUserDataCallbacks() {
        activeUserDataCallbacks.forEach(callback -> closeUserDataCallback(callback, "active UserData"));
    }

    private void closeUserDataCallback(ResultCallback<Frame> callback, String context) {
        try {
            callback.close();
        } catch (IOException e) {
            LOG.warnv("Could not close Docker UserData callback for {0}: {1}", context, e.getMessage());
        }
    }

    private void closeUserDataStream(Closeable stream, String instanceId) {
        try {
            stream.close();
        } catch (IOException e) {
            LOG.warnv("Could not close Docker UserData stream for EC2 instance {0}: {1}",
                    instanceId, e.getMessage());
        }
    }

    static List<String> userDataShellScripts(String userData) {
        String decoded = decodeUserDataPayload(userData);
        if (decoded == null || decoded.isBlank()) {
            return List.of();
        }

        String normalized = decoded.replace("\r\n", "\n").replace('\r', '\n');
        String trimmed = normalized.stripLeading();
        if (trimmed.startsWith("#!")) {
            return List.of(normalized);
        }

        Matcher matcher = MIME_BOUNDARY.matcher(normalized);
        if (!matcher.find()) {
            return List.of();
        }

        String boundary = matcher.group(1).trim();
        if (boundary.isEmpty()) {
            return List.of();
        }

        List<String> scripts = new ArrayList<>();
        String marker = "--" + boundary;
        for (String segment : normalized.split(Pattern.quote(marker))) {
            String part = segment.stripLeading();
            if (part.isBlank() || part.startsWith("--")) {
                continue;
            }
            int headerEnd = part.indexOf("\n\n");
            if (headerEnd < 0) {
                continue;
            }
            String headers = part.substring(0, headerEnd);
            String body = part.substring(headerEnd + 2);
            if (hasShellscriptContentType(headers)) {
                scripts.add(body.stripTrailing() + "\n");
            }
        }
        return List.copyOf(scripts);
    }

    /**
     * Unwraps whatever encoding the UserData arrived in until a cloud-init document is
     * visible: gzip, base64, and base64-of-gzip, in any nesting the callers produce.
     *
     * <p>RunInstances decodes the wire-level base64 (and its gzip) before Floci ever stores
     * the value, but not every path does — CreateLaunchConfiguration stores what the client
     * sent, still base64 — and {@code data.cloudinit_config { gzip = true }}, the documented
     * way to pass a multipart cloud-init, produces a payload that survives one decode still
     * compressed. Left unwrapped it matches neither the {@code #!} nor the MIME test below and
     * the whole document is silently dropped, so the instance boots without anything its
     * user-data was supposed to install.
     *
     * @return the decoded document, or the input unchanged when it is not encoded
     */
    static String decodeUserDataPayload(String userData) {
        if (userData == null || userData.isBlank()) {
            return null;
        }
        byte[] payload = userData.getBytes(StandardCharsets.UTF_8);
        // Bounded so a crafted payload cannot make this loop forever; two rounds already
        // covers base64(gzip(document)), the deepest form in practice.
        for (int round = 0; round < MAX_USER_DATA_DECODE_ROUNDS; round++) {
            byte[] next = gunzip(payload);
            if (next == null) {
                next = base64Decode(payload);
            }
            if (next == null) {
                break;
            }
            payload = next;
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    /** @return the decompressed bytes, or null when the input does not start with the gzip magic */
    private static byte[] gunzip(byte[] payload) {
        if (payload.length < 2 || (payload[0] & 0xff) != 0x1f || (payload[1] & 0xff) != 0x8b) {
            return null;
        }
        // Aggregate bound (see MAX_CONCURRENT_USER_DATA_DECOMPRESSIONS): this call runs on
        // whichever launch worker submitted it to the unbounded #executor, independently of every
        // other concurrent launch, so the per-payload cap below is not enough on its own - it only
        // stops one caller from expanding past 10 MB, not many callers doing so at once. Blocking
        // here (rather than failing the launch) mirrors ContainerLauncher's POPULATE_SEMAPHORE: a
        // burst of legitimate concurrent launches queues briefly instead of being rejected.
        try {
            USER_DATA_DECOMPRESSION_BUDGET.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warnv("Interrupted while waiting for UserData decompression budget; discarding payload");
            return null;
        }
        try {
            Runnable hook = userDataDecompressionTestHook;
            if (hook != null) {
                hook.run();
            }
            // Bounded the same way AwsJsonCborController.decodeBody is: a crafted payload can
            // otherwise expand to gigabytes of image-heap while the launch worker holds it, since
            // UserData is caller-controlled and this runs in the shared emulator JVM.
            byte[] buffer = new byte[64 * 1024];
            int totalRead = 0;
            ByteArrayOutputStream decompressed = new ByteArrayOutputStream();
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                int read;
                while ((read = gzip.read(buffer)) != -1) {
                    totalRead += read;
                    if (totalRead > MAX_DECOMPRESSED_USER_DATA_BYTES) {
                        LOG.warnv("UserData decompressed past {0} bytes; discarding as oversized",
                                MAX_DECOMPRESSED_USER_DATA_BYTES);
                        return null;
                    }
                    decompressed.write(buffer, 0, read);
                }
                return decompressed.toByteArray();
            } catch (IOException e) {
                LOG.warnv("UserData starts with the gzip magic bytes but could not be decompressed: {0}", e.getMessage());
                return null;
            }
        } finally {
            USER_DATA_DECOMPRESSION_BUDGET.release();
        }
    }

    /**
     * @return the decoded bytes when the input is base64 that unwraps to something recognisable
     *         (gzip, a shebang, or MIME headers), null otherwise. The recognisability test is
     *         what keeps a plain shell script — which can be accidentally valid base64 — from
     *         being mangled into binary noise.
     */
    private static byte[] base64Decode(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8).strip();
        if (text.isEmpty() || !BASE64_BODY.matcher(text).matches()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded.length < 2) {
            return null;
        }
        if ((decoded[0] & 0xff) == 0x1f && (decoded[1] & 0xff) == 0x8b) {
            return decoded;
        }
        String head = new String(decoded, 0, Math.min(decoded.length, 512), StandardCharsets.UTF_8).stripLeading();
        return head.startsWith("#!") || head.toLowerCase(Locale.ROOT).startsWith("content-type:")
                || head.toLowerCase(Locale.ROOT).startsWith("mime-version:") || head.startsWith("#cloud-config")
                ? decoded
                : null;
    }

    private static boolean hasShellscriptContentType(String headers) {
        for (String line : headers.split("\n")) {
            String lower = line.toLowerCase(Locale.ROOT).strip();
            if (lower.startsWith("content-type:") && lower.contains("text/x-shellscript")) {
                return true;
            }
        }
        return false;
    }

    static String[] userDataExecutionCommand() {
        return new String[]{USER_DATA_SCRIPT_PATH};
    }

    /**
     * Official amazonlinux/ubuntu images have no systemd. Alchemy hosted
     * user-data writes a unit and calls {@code systemctl enable --now}; this
     * shim runs ExecStartPre + ExecStart with Restart=always so the HTTP
     * server comes up the same way it does on a real AMI.
     */
    static String[] systemctlShimInstallCommand() {
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                "mkdir -p /etc/systemd/system /lib/systemd/system /usr/local/sbin /usr/local/bin /var/log /var/run",
                "if command -v systemctl >/dev/null 2>&1; then",
                "  if [ -d /run/systemd/system ] || ! systemctl --version 2>/dev/null | grep -q '^systemd '; then exit 0; fi",
                "fi",
                "cat > /usr/local/sbin/systemctl <<'SHIM'",
                "#!/bin/sh",
                "cmd=\"${1:-}\"",
                "shift || true",
                "case \"$cmd\" in",
                "  daemon-reload|status|is-enabled|is-active) exit 0 ;;",
                "  enable|start|restart)",
                "    unit=\"\"",
                "    for a in \"$@\"; do",
                "      case \"$a\" in --now) ;; *) unit=\"$a\" ;; esac",
                "    done",
                "    [ -n \"$unit\" ] || exit 0",
                "    unit=\"${unit%.service}\"",
                "    unitfile=\"/etc/systemd/system/${unit}.service\"",
                "    [ -f \"$unitfile\" ] || unitfile=\"/lib/systemd/system/${unit}.service\"",
                "    [ -f \"$unitfile\" ] || exit 0",
                "    wd=$(sed -n 's/^WorkingDirectory=//p' \"$unitfile\" | tail -n 1)",
                "    pre=$(sed -n 's/^ExecStartPre=//p' \"$unitfile\" | tail -n 1)",
                "    start=$(sed -n 's/^ExecStart=//p' \"$unitfile\" | tail -n 1)",
                "    envfile=$(sed -n 's/^EnvironmentFile=-\\{0,1\\}//p' \"$unitfile\" | tail -n 1)",
                "    pidfile=\"/var/run/${unit}.pid\"",
                "    # PIDs restart from 1 after a container reboot, so a stale pidfile can",
                "    # name an unrelated live process: only trust it if it is this unit's loop.",
                "    if [ -f \"$pidfile\" ]; then",
                "      pid=$(cat \"$pidfile\")",
                "      if [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null \\",
                "          && tr '\\000' ' ' < \"/proc/$pid/cmdline\" 2>/dev/null | grep -qF \"$unitfile\"; then",
                "        exit 0",
                "      fi",
                "    fi",
                "    # ExecStartPre downloads EnvironmentFile (Alchemy hosted env).",
                "    # Run it INSIDE the restart loop, like systemd Restart=always",
                "    # does: every unit restart re-syncs the bundle and re-sources",
                "    # the env before ExecStart.",
                "    nohup sh -c '",
                "      unitfile=\"$0\"",
                "      wd=$(sed -n \"s/^WorkingDirectory=//p\" \"$unitfile\" | tail -n 1)",
                "      pre=$(sed -n \"s/^ExecStartPre=//p\" \"$unitfile\" | tail -n 1)",
                "      start=$(sed -n \"s/^ExecStart=//p\" \"$unitfile\" | tail -n 1)",
                "      envfile=$(sed -n \"s/^EnvironmentFile=-\\{0,1\\}//p\" \"$unitfile\" | tail -n 1)",
                "      if [ -n \"$wd\" ]; then cd \"$wd\" || exit 1; fi",
                "      while true; do",
                "        if [ -n \"$pre\" ]; then $pre || { sleep 5; continue; }; fi",
                "        if [ -n \"$envfile\" ] && [ -f \"$envfile\" ]; then set -a; . \"$envfile\"; set +a; fi",
                "        if [ -n \"$start\" ]; then $start; fi",
                "        sleep 5",
                "      done",
                "    ' \"$unitfile\" >/var/log/${unit}.log 2>&1 &",
                "    echo $! > \"$pidfile\"",
                "    exit 0",
                "    ;;",
                "  *) exit 0 ;;",
                "esac",
                "SHIM",
                "chmod 755 /usr/local/sbin/systemctl",
                "if [ ! -e /usr/bin/systemctl ]; then ln -s /usr/local/sbin/systemctl /usr/bin/systemctl; fi")};
    }

    /**
     * Catalog images (amazonlinux:2023 / ubuntu:24.04) have no systemd tree.
     * Alchemy hosted user-data writes {@code /etc/systemd/system/*.service};
     * without the directory {@code cat >} fails and {@code systemctl enable}
     * is a no-op.
     */
    static String[] prepareGuestFilesystemCommand() {
        return new String[]{"sh", "-c",
                "mkdir -p /etc/systemd/system /lib/systemd/system /usr/local/bin /usr/local/sbin /var/log /var/run"};
    }

    ResolvedAmiImage prepareImage(ResolvedAmiImage image) {
        return bootstrapImage.prepare(image);
    }

    private void prepareGuestFilesystem(String containerId, String instanceId) {
        try {
            ContainerExecResult result = execInContainerForResult(containerId, prepareGuestFilesystemCommand(), 10);
            if (result.exitCode() != 0) {
                LOG.warnv("Could not prepare guest filesystem for EC2 instance {0}: {1}",
                        instanceId, result.summary());
            }
        } catch (Exception e) {
            LOG.warnv("Could not prepare guest filesystem for EC2 instance {0}: {1}",
                    instanceId, e.getMessage());
        }
    }

    private void installSystemctlShim(String containerId, String instanceId) {
        try {
            ContainerExecResult result = execInContainerForResult(containerId, systemctlShimInstallCommand(), 15);
            if (result.exitCode() != 0) {
                LOG.warnv("Could not install systemctl shim for EC2 instance {0}: {1}",
                        instanceId, result.summary());
            }
        } catch (Exception e) {
            LOG.warnv("Could not install systemctl shim for EC2 instance {0}: {1}",
                    instanceId, e.getMessage());
        }
    }

    /** Missing scp does not prevent a supplied sshd from serving the guest. */
    static String[] sshdInstallProbeCommand() {
        return new String[]{"sh", "-c",
                "command -v sshd >/dev/null 2>&1 || exit 1;"
                        + "command -v scp >/dev/null 2>&1 || exit 2"};
    }

    static String[] metadataProxyInstallCommand() {
        return Ec2MetadataProxy.installCommand();
    }

    static String[] metadataProxyStartCommand(String flociHost, int imdsPort) {
        return Ec2MetadataProxy.startCommand(flociHost, imdsPort);
    }


    static List<String> localAwsEnvironment(String region, String serviceEndpoint, String imdsEndpoint) {
        return localAwsEnvironment(region, serviceEndpoint, imdsEndpoint, false);
    }

    static List<String> localAwsEnvironment(String region, String serviceEndpoint, String imdsEndpoint,
                                            boolean hasInstanceProfile) {
        List<String> environment = new ArrayList<>(List.of(
                "AWS_EC2_METADATA_SERVICE_ENDPOINT=" + imdsEndpoint,
                "AWS_ENDPOINT_URL=" + serviceEndpoint,
                "AWS_DEFAULT_REGION=" + region,
                "AWS_REGION=" + region));
        if (!hasInstanceProfile) {
            environment.addAll(List.of("AWS_ACCESS_KEY_ID=test", "AWS_SECRET_ACCESS_KEY=test",
                    "AWS_SESSION_TOKEN=test-session-token"));
        }
        return environment;
    }

    static String summarizeUserDataOutput(BoundedOutput output) {
        String text = output.utf8Tail().stripTrailing();
        if (text.isBlank()) {
            text = "(no output)";
        }
        if (output.truncated()) {
            return "(output truncated; showing last " + output.capacity() + " bytes)\n" + text;
        }
        return text;
    }

    static final class BoundedOutput extends OutputStream {
        private final byte[] buffer;
        private int size;
        private long totalBytes;

        BoundedOutput(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            buffer = new byte[capacity];
        }

        @Override
        public void write(int value) {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] source, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, source.length);
            if (length == 0) {
                return;
            }
            totalBytes += length;
            if (length >= buffer.length) {
                System.arraycopy(source, offset + length - buffer.length, buffer, 0, buffer.length);
                size = buffer.length;
                return;
            }
            int overflow = Math.max(0, size + length - buffer.length);
            if (overflow > 0) {
                System.arraycopy(buffer, overflow, buffer, 0, size - overflow);
                size -= overflow;
            }
            System.arraycopy(source, offset, buffer, size, length);
            size += length;
        }

        String utf8Tail() {
            int start = 0;
            while (start < size) {
                int sequenceLength = utf8SequenceLength(buffer[start]);
                if (sequenceLength == 0) {
                    start++;
                    continue;
                }
                if (sequenceLength == 1) {
                    break;
                }
                if (sequenceLength <= size - start && hasContinuationBytes(start, sequenceLength)) {
                    break;
                }
                start++;
            }
            return new String(buffer, start, size - start, StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return totalBytes > buffer.length;
        }

        int capacity() {
            return buffer.length;
        }

        private boolean hasContinuationBytes(int start, int sequenceLength) {
            for (int i = 1; i < sequenceLength; i++) {
                if ((buffer[start + i] & 0xC0) != 0x80) {
                    return false;
                }
            }
            return true;
        }

        private static int utf8SequenceLength(byte value) {
            int unsigned = value & 0xFF;
            if (unsigned < 0x80) {
                return 1;
            }
            if ((unsigned & 0xC0) == 0x80) {
                return 0;
            }
            if ((unsigned & 0xE0) == 0xC0) {
                return 2;
            }
            if ((unsigned & 0xF0) == 0xE0) {
                return 3;
            }
            if ((unsigned & 0xF8) == 0xF0) {
                return 4;
            }
            return 1;
        }
    }

    boolean configureMetadataEndpoint(Instance instance) {
        try {
            ProtectedNamespace namespace = protectedNamespace(instance);
            return configureLinkLocalMetadataEndpoint(instance,
                    namespace == null ? instance.getDockerContainerId() : namespace.helperId());
        } catch (RuntimeException e) {
            LOG.warnv("Could not resolve IMDS proxy container for EC2 instance {0}: {1}",
                    instance.getInstanceId(), e.getMessage());
            return false;
        }
    }

    boolean configureLinkLocalMetadataEndpoint(Instance instance, String containerId) {
        Object lock = metadataLocks.computeIfAbsent(instance, key -> new Object());
        synchronized (lock) {
            return configureMetadataProxy(instance, containerId);
        }
    }

    private boolean configureMetadataProxy(Instance instance, String containerId) {
        String instanceId = instance.getInstanceId();
        String capability;
        synchronized (instance) {
            String state = instance.getState() == null ? null : instance.getState().getName();
            if (isLaunchCancelledState(instance) || "stopping".equals(state) || "stopped".equals(state)) {
                return false;
            }
            if ("disabled".equals(instance.effectiveMetadataOptions().getHttpEndpoint())) {
                metadataServer.unregisterInstance(instance);
                return true;
            }
            capability = metadataServer.registerProxy(instance);
        }
        boolean configured = false;
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            Map<String, String> labels = inspect.getConfig() == null ? Map.of() : inspect.getConfig().getLabels();
            String accountId = Ec2MetadataServer.instanceAccountId(instance, regionResolver.getAccountId());
            if (accountId == null || !accountId.matches("[0-9]{12}")
                    || labels == null || !ownerIdentity().equals(labels.get(LABEL_OWNER_PORT))
                    || !SERVICE_VALUE.equals(labels.get(LABEL_SERVICE))
                    || !instanceId.equals(labels.get(LABEL_RESOURCE_ID))
                    || !Objects.equals(accountId, labels.get("io.floci.account"))
                    || !Objects.equals(instance.getRegion(), labels.get(LABEL_REGION))) {
                throw new IllegalStateException("IMDS proxy target is not the owned EC2 container");
            }
            ContainerExecResult install = execInContainerForResult(containerId,
                    new String[]{"sh", "-c", "command -v ip && command -v python3 && command -v curl"}, 10);
            if (install.exitCode() != 0) {
                LOG.warnv("IMDS proxy dependencies are unavailable for EC2 instance {0}: {1}",
                        instanceId, install.summary());
                return false;
            }
            String proxyConfig = new JsonObject()
                    .put("host", dockerHostResolver.resolve())
                    .put("port", config.services().ec2().imdsPort())
                    .put("capability", capability).encode();
            copyMetadataFile(containerId, "floci-imds-proxy.py", Ec2MetadataProxy.authenticatedProxyScript(), 0700);
            copyMetadataFile(containerId, "floci-imds-proxy.json.next", proxyConfig, 0600);
            ContainerExecResult start = execInContainerForResult(containerId,
                    Ec2MetadataProxy.authenticatedStartCommand(instanceId), 45);
            if (start.exitCode() != 0) {
                LOG.warnv("Could not start link-local IMDS proxy for EC2 instance {0}: {1}",
                        instanceId, start.summary());
                return false;
            }
            configured = true;
            LOG.infov("Configured link-local IMDS endpoint for EC2 instance {0}", instanceId);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debugv("Interrupted IMDS proxy setup for EC2 instance {0}", instanceId);
            return false;
        } catch (Exception e) {
            LOG.warnv("Could not configure link-local IMDS endpoint for EC2 instance {0}: {1}", instanceId, e.getMessage());
            return false;
        } finally {
            if (!configured) {
                metadataServer.unregisterProxy(instance, capability);
            }
        }
    }

    private void copyMetadataFile(String containerId, String name, String content, int mode) throws IOException {
        byte[] archive = buildSingleFileTar(name, content.getBytes(StandardCharsets.UTF_8), mode);
        dockerClient.copyArchiveToContainerCmd(containerId)
                .withRemotePath("/var/lib")
                .withTarInputStream(new ByteArrayInputStream(archive))
                .exec();
    }

    private void execInContainer(String containerId, String[] cmd, int timeoutSeconds) throws Exception {
        execInContainerForResult(containerId, cmd, timeoutSeconds);
    }

    private ContainerExecResult execInContainerForResult(String containerId, String[] cmd, int timeoutSeconds) throws Exception {
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();

        CountDownLatch latch = new CountDownLatch(1);
        BoundedOutput output = new BoundedOutput(MAX_EXEC_OUTPUT_BYTES);
        dockerClient.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                if (frame.getPayload() != null) {
                    try { output.write(frame.getPayload()); } catch (IOException ignored) {}
                }
            }
            @Override
            public void onComplete() { latch.countDown(); }
            @Override
            public void onError(Throwable t) { latch.countDown(); }
        });
        boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            return new ContainerExecResult(-1, "Timed out after " + timeoutSeconds + "s");
        }
        Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
        return new ContainerExecResult(exitCode != null ? exitCode : -1, summarizeUserDataOutput(output));
    }

    record ContainerExecResult(long exitCode, String output) {
        String summary() {
            return output == null || output.isBlank() ? "(no output)" : output;
        }
    }

    private String getContainerBridgeIp(String containerId) {
        try {
            var inspect = dockerClient.inspectContainerCmd(containerId).exec();
            if (inspect.getNetworkSettings() != null) {
                var networks = inspect.getNetworkSettings().getNetworks();
                if (networks != null) {
                    Optional<String> preferredIp = preferredMetadataSourceIp(networks);
                    if (preferredIp.isPresent()) {
                        return preferredIp.get();
                    }
                }
                String ip = inspect.getNetworkSettings().getIpAddress();
                if (ip != null && !ip.isBlank()) {
                    return ip;
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not inspect container {0} for bridge IP: {1}", containerId, e.getMessage());
        }
        return null;
    }

    private String waitForContainerBridgeIp(String containerId, String instanceId) throws InterruptedException {
        return waitForContainerBridgeIp(containerId, instanceId, null);
    }

    private String waitForContainerBridgeIp(String containerId, String instanceId, Instance instance)
            throws InterruptedException {
        for (int i = 0; i < containerBridgeIpAttempts; i++) {
            if (instance != null && isLaunchCancelled(instance)) {
                return null;
            }
            String containerIp = getContainerBridgeIp(containerId);
            if (containerIp != null && !containerIp.isBlank()) {
                return containerIp;
            }
            Thread.sleep(containerBridgeIpPollMillis);
        }
        LOG.warnv("Timed out waiting for EC2 instance {0} container {1} bridge IP", instanceId, containerId);
        return null;
    }

    static Optional<String> preferredMetadataSourceIp(Map<String, ContainerNetwork> networks) {
        return Ec2MetadataProxy.preferredMetadataSourceIp(networks);
    }

    private byte[] buildSingleFileTar(String filename, byte[] content, int mode) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            TarArchiveEntry entry = new TarArchiveEntry(filename);
            entry.setSize(content.length);
            entry.setMode(mode);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return bos.toByteArray();
    }
}
