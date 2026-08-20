package io.github.hectorvent.floci.services.lambda.microvm;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmImageRecord;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmImageVersionRecord;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Running MicroVMs, each backed by a local Docker container built by
 * {@link MicrovmBuildService}. Suspend/resume map to {@code docker pause} /
 * {@code unpause} (no memory snapshotting locally). The MicroVM endpoint is a
 * hostname under Floci's embedded-DNS suffix
 * ({@code <id>.lambda-microvm.<region>.localhost.floci.io}) which resolves to
 * Floci both on the host (public wildcard DNS → 127.0.0.1) and inside launched
 * containers (embedded DNS → Floci's network IP); Floci's gateway then proxies
 * the request to the container (see {@code MicrovmEndpointProxyController}).
 */
@ApplicationScoped
public class MicrovmRuntimeService {

    private static final Logger LOG = Logger.getLogger(MicrovmRuntimeService.class);

    static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_MEMORY_MIB = 512;
    private static final int DEFAULT_MAX_DURATION_SECONDS = 3600;

    private final MicrovmStore microvmStore;
    private final MicrovmImageService imageService;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerBuilder containerBuilder;
    private final LaunchedContainerAwsEnv awsEnv;
    private final EmulatorConfig config;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public MicrovmRuntimeService(MicrovmStore microvmStore, MicrovmImageService imageService,
                                 ContainerLifecycleManager lifecycleManager,
                                 ContainerBuilder containerBuilder,
                                 LaunchedContainerAwsEnv awsEnv, EmulatorConfig config) {
        this.microvmStore = microvmStore;
        this.imageService = imageService;
        this.lifecycleManager = lifecycleManager;
        this.containerBuilder = containerBuilder;
        this.awsEnv = awsEnv;
        this.config = config;
    }

    // ──────────────────────────── lifecycle ────────────────────────────

    public Map<String, Object> runMicrovm(String region, String accountId, Map<String, Object> request) {
        Object imageIdentifier = request.get("imageIdentifier");
        if (!(imageIdentifier instanceof String imageId) || imageId.isBlank()) {
            throw new AwsException("ValidationException", "imageIdentifier is required", 400);
        }
        MicrovmImageRecord image = imageService.requireImage(region, imageId);
        String requestedVersion = request.get("imageVersion") instanceof String v && !v.isBlank()
                ? v
                : image.getLatestActiveImageVersion();
        if (requestedVersion == null) {
            throw new AwsException("ValidationException",
                    "MicroVM image " + image.getName() + " has no active image version", 400);
        }
        MicrovmImageVersionRecord version = imageService.requireVersion(image, requestedVersion);
        if (!"SUCCESSFUL".equals(version.getState()) || version.getDockerImageTag() == null) {
            throw new AwsException("ValidationException",
                    "MicroVM image version " + image.getName() + "/" + requestedVersion
                            + " is not runnable (state " + version.getState() + ")", 400);
        }

        String microvmId = "mvm-" + HexFormat.of().formatHex(randomBytes(8));
        int port = resolvePort(version);

        MicrovmRecord vm = new MicrovmRecord();
        vm.setRegion(region);
        vm.setAccountId(accountId);
        vm.setMicrovmId(microvmId);
        vm.setImageArn(image.getImageArn());
        vm.setImageVersion(requestedVersion);
        vm.setPort(port);
        vm.setEndpoint(microvmId + ".lambda-microvm." + region + "." + EmbeddedDnsServer.DEFAULT_SUFFIX);
        vm.setStartedAt(System.currentTimeMillis());
        vm.setState("PENDING");
        if (request.get("executionRoleArn") instanceof String role && !role.isBlank()) {
            vm.setExecutionRoleArn(role);
        }
        vm.setIdlePolicy(normalizeIdlePolicy(objectMap(request.get("idlePolicy"))));
        if (request.get("maximumDurationInSeconds") instanceof Number n) {
            vm.setMaximumDurationInSeconds(n.intValue());
        } else {
            vm.setMaximumDurationInSeconds(DEFAULT_MAX_DURATION_SECONDS);
        }
        vm.setIngressNetworkConnectors(stringList(request.get("ingressNetworkConnectors")));
        vm.setEgressNetworkConnectors(stringList(request.get("egressNetworkConnectors")));

        List<String> env = new ArrayList<>();
        env.add("PORT=" + port);
        // Baseline AWS env so in-VM SDK calls (capability bindings) reach Floci.
        env.addAll(awsEnv.sdkBaselineEnv(region, Optional.empty()));
        Object versionEnv = version.getConfig().get("environmentVariables");
        if (versionEnv instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    env.add(k + "=" + v);
                }
            });
        }

        ContainerSpec spec = containerBuilder.newContainer(version.getDockerImageTag())
                .withName("floci-microvm-" + microvmId)
                .withEnv(env)
                .withMemoryMb(resolveMemoryMib(version))
                // Dynamic host port so the proxy reaches the VM in native mode;
                // in container mode it connects via the docker-network IP.
                .withDynamicPort(port)
                .withDockerNetwork(config.services().lambda().dockerNetwork())
                .withEmbeddedDns()
                .withHostDockerInternalOnLinux()
                .withLogRotation()
                .build();

        ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
        vm.setContainerId(info.containerId());
        vm.setState("RUNNING");
        microvmStore.save(vm);
        LOG.infov("Started MicroVM {0} (image {1} v{2}, container {3})",
                microvmId, image.getName(), requestedVersion, info.containerId());
        return microvmResponse(vm);
    }

    public Map<String, Object> getMicrovm(String region, String microvmIdentifier) {
        return microvmResponse(requireMicrovm(region, microvmIdentifier));
    }

    public Map<String, Object> listMicrovms(String region, String imageIdentifier, String imageVersion) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (MicrovmRecord vm : microvmStore.list(region)) {
            if (imageIdentifier != null && !imageIdentifier.isBlank()
                    && !matchesImage(vm.getImageArn(), imageIdentifier)) {
                continue;
            }
            if (imageVersion != null && !imageVersion.isBlank()
                    && !imageVersion.equals(vm.getImageVersion())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("microvmId", vm.getMicrovmId());
            item.put("state", vm.getState());
            item.put("imageArn", vm.getImageArn());
            item.put("imageVersion", vm.getImageVersion());
            item.put("startedAt", vm.getStartedAt() / 1000);
            items.add(item);
        }
        return Map.of("items", items);
    }

    public void suspendMicrovm(String region, String microvmIdentifier) {
        MicrovmRecord vm = requireMicrovm(region, microvmIdentifier);
        if ("SUSPENDED".equals(vm.getState())) {
            return;
        }
        requireState(vm, "RUNNING", "suspend");
        lifecycleManager.getDockerClient().pauseContainerCmd(vm.getContainerId()).exec();
        vm.setState("SUSPENDED");
        microvmStore.save(vm);
    }

    public void resumeMicrovm(String region, String microvmIdentifier) {
        MicrovmRecord vm = requireMicrovm(region, microvmIdentifier);
        if ("RUNNING".equals(vm.getState())) {
            return;
        }
        requireState(vm, "SUSPENDED", "resume");
        lifecycleManager.getDockerClient().unpauseContainerCmd(vm.getContainerId()).exec();
        vm.setState("RUNNING");
        microvmStore.save(vm);
    }

    public void terminateMicrovm(String region, String microvmIdentifier) {
        MicrovmRecord vm = requireMicrovm(region, microvmIdentifier);
        if ("TERMINATED".equals(vm.getState())) {
            return;
        }
        if (vm.getContainerId() != null) {
            // A paused container cannot be stopped; unpause first (best-effort).
            if ("SUSPENDED".equals(vm.getState())) {
                try {
                    lifecycleManager.getDockerClient().unpauseContainerCmd(vm.getContainerId()).exec();
                } catch (Exception ignored) {
                    // container may already be gone
                }
            }
            lifecycleManager.stopAndRemove(vm.getContainerId(), null);
        }
        vm.setContainerId(null);
        vm.setState("TERMINATED");
        vm.setTerminatedAt(System.currentTimeMillis());
        microvmStore.save(vm);
        LOG.infov("Terminated MicroVM {0}", vm.getMicrovmId());
    }

    // ──────────────────────────── endpoint proxy support ────────────────────────────

    public Optional<MicrovmRecord> findById(String microvmId) {
        return microvmStore.findById(microvmId);
    }

    /** The host:port the endpoint proxy forwards to (mode-aware). */
    public ContainerLifecycleManager.EndpointInfo resolveVmEndpoint(MicrovmRecord vm) {
        return lifecycleManager.resolveEndpoint(vm.getContainerId(), vm.getPort());
    }

    // ──────────────────────────── helpers ────────────────────────────

    public MicrovmRecord requireMicrovm(String region, String microvmIdentifier) {
        return microvmStore.get(region, microvmIdentifier)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "MicroVM not found: " + microvmIdentifier, 404));
    }

    private static void requireState(MicrovmRecord vm, String expected, String action) {
        if (!expected.equals(vm.getState())) {
            throw new AwsException("ConflictException",
                    "Cannot " + action + " MicroVM " + vm.getMicrovmId()
                            + " in state " + vm.getState(), 409);
        }
    }

    /** Matches a MicroVM's image ARN against an identifier that may be an ARN or a bare name. */
    private static boolean matchesImage(String imageArn, String identifier) {
        return identifier.startsWith("arn:")
                ? imageArn.equals(identifier)
                : imageArn.endsWith(":microvm-image/" + identifier);
    }

    private static int resolvePort(MicrovmImageVersionRecord version) {
        Object hooks = version.getConfig().get("hooks");
        if (hooks instanceof Map<?, ?> map && map.get("port") instanceof Number n) {
            return n.intValue();
        }
        return DEFAULT_PORT;
    }

    private static int resolveMemoryMib(MicrovmImageVersionRecord version) {
        Object resources = version.getConfig().get("resources");
        if (resources instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> map
                && map.get("minimumMemoryInMiB") instanceof Number n) {
            return Math.max(n.intValue(), 128);
        }
        return DEFAULT_MEMORY_MIB;
    }

    /** GetMicrovmResponse / RunMicrovmResponse. */
    static Map<String, Object> microvmResponse(MicrovmRecord vm) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("microvmId", vm.getMicrovmId());
        out.put("state", vm.getState());
        out.put("endpoint", vm.getEndpoint());
        out.put("imageArn", vm.getImageArn());
        out.put("imageVersion", vm.getImageVersion());
        if (vm.getExecutionRoleArn() != null) {
            out.put("executionRoleArn", vm.getExecutionRoleArn());
        }
        if (vm.getIdlePolicy() != null) {
            out.put("idlePolicy", vm.getIdlePolicy());
        }
        out.put("maximumDurationInSeconds", vm.getMaximumDurationInSeconds());
        out.put("startedAt", vm.getStartedAt() / 1000);
        if (vm.getTerminatedAt() != null) {
            out.put("terminatedAt", vm.getTerminatedAt() / 1000);
        }
        if (vm.getStateReason() != null) {
            out.put("stateReason", vm.getStateReason());
        }
        if (vm.getIngressNetworkConnectors() != null) {
            out.put("ingressNetworkConnectors", vm.getIngressNetworkConnectors());
        }
        if (vm.getEgressNetworkConnectors() != null) {
            out.put("egressNetworkConnectors", vm.getEgressNetworkConnectors());
        }
        return out;
    }

    private byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        random.nextBytes(bytes);
        return bytes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    /**
     * The wire shape requires BOTH IdlePolicy members when the struct is
     * present, but clients may send only one — fill the other with the
     * documented AWS defaults so responses always decode.
     */
    static Map<String, Object> normalizeIdlePolicy(Map<String, Object> idlePolicy) {
        if (idlePolicy == null) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(idlePolicy);
        normalized.putIfAbsent("maxIdleDurationSeconds", 300);
        normalized.putIfAbsent("suspendedDurationSeconds", 3600);
        normalized.putIfAbsent("autoResumeEnabled", Boolean.FALSE);
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return value instanceof List<?> ? (List<String>) value : null;
    }
}
