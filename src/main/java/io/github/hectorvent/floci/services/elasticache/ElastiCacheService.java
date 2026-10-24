package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.BackupWindows;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.dns.ContainerEndpoints;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheTagResourceArn.TagResourceRef;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ValkeyClusterFormation;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.model.CacheParameterGroup;
import io.github.hectorvent.floci.services.elasticache.model.CacheSnapshot;
import io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup;
import io.github.hectorvent.floci.services.elasticache.model.ClusterNode;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUser;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUserGroup;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupSettings;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheAuthProxy;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsKeyUsage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Core ElastiCache business logic — replication groups and users.
 * Creates Valkey containers and auth proxies on group creation.
 */
@ApplicationScoped
public class ElastiCacheService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(ElastiCacheService.class);

    /** AWS's default Port for Redis OSS and Valkey replication groups. */
    static final int DEFAULT_ENGINE_PORT = 6379;

    private final StorageBackend<String, ReplicationGroup> groups;
    private final StorageBackend<String, ElastiCacheUser> users;
    private final StorageBackend<String, CacheSubnetGroup> subnetGroups;
    private final StorageBackend<String, CacheSnapshot> snapshots;
    private final AccountAwareStorageBackend<CacheParameterGroup> parameterGroups;
    private final ElastiCacheContainerManager containerManager;
    private final ElastiCacheProxyManager proxyManager;
    private final ValkeyClusterFormation clusterFormation;
    private final EmulatorConfig config;
    private final DockerHostResolver dockerHostResolver;
    private final ContainerDetector containerDetector;
    private final KmsService kmsService;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;
    private final ContainerEndpoints containerEndpoints;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    /** Proxy ports published to containers, by replication group. */
    private final Map<String, Integer> publishedProxyPorts = new ConcurrentHashMap<>();
    private final Set<String> provisioningGroupIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> parameterGroupLocks = new ConcurrentHashMap<>();
    /**
     * Parameter groups claimed by in-flight creates, keyed by account and name (see
     * {@link #parameterGroupReservationKey}) with the number of creates holding each. A
     * replication group is only persisted once provisioning finishes, so the stored-groups scan
     * alone would let a delete slip in during that window and leave the new group pointing at a
     * parameter group that no longer exists.
     */
    private final ConcurrentHashMap<String, Integer> reservedParameterGroups = new ConcurrentHashMap<>();
    /**
     * Guards user groups, their members and their association with replication groups, so a
     * delete, a membership change and an association cannot interleave. Taken inside a
     * replication group's lock, never around one.
     */
    private final Object userGroupLock = new Object();
    /**
     * User groups named by in-flight creates, keyed by account and id (see
     * {@link #userGroupReservationKey}) with the number of creates holding each: a
     * replication group is only stored once provisioning finishes, so the stored-groups scan
     * alone would let DeleteUserGroup remove a group that the new cache is about to use.
     */
    private final Map<String, Integer> reservedUserGroups = new HashMap<>();

    public ElastiCacheService(ElastiCacheContainerManager containerManager,
                              ElastiCacheProxyManager proxyManager,
                              ValkeyClusterFormation clusterFormation,
                              StorageFactory storageFactory,
                              EmulatorConfig config,
                              DockerHostResolver dockerHostResolver,
                              ContainerDetector containerDetector,
                              Ec2Service ec2Service,
                              RegionResolver regionResolver,
                              KmsService kmsService) {
        this(containerManager, proxyManager, clusterFormation, storageFactory, config, dockerHostResolver,
                containerDetector, ec2Service, regionResolver, kmsService, null);
    }

    @Inject
    public ElastiCacheService(ElastiCacheContainerManager containerManager,
                              ElastiCacheProxyManager proxyManager,
                              ValkeyClusterFormation clusterFormation,
                              StorageFactory storageFactory,
                              EmulatorConfig config,
                              DockerHostResolver dockerHostResolver,
                              ContainerDetector containerDetector,
                              Ec2Service ec2Service,
                              RegionResolver regionResolver,
                              KmsService kmsService,
                              ContainerEndpoints containerEndpoints) {
        this.containerEndpoints = containerEndpoints;
        this.kmsService = kmsService;
        this.provisioningIds = provisioningIds;
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.clusterFormation = clusterFormation;
        this.config = config;
        this.dockerHostResolver = dockerHostResolver;
        this.containerDetector = containerDetector;
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.groups = storageFactory.create("elasticache", "elasticache-groups.json",
                new TypeReference<Map<String, ReplicationGroup>>() {});
        this.cacheClusters = storageFactory.create("elasticache", "elasticache-redis-clusters.json",
                new TypeReference<Map<String, CacheCluster>>() {});
        this.memcachedClusters = storageFactory.create("elasticache", "elasticache-cache-clusters.json",
                new TypeReference<Map<String, CacheCluster>>() {});
        this.users = storageFactory.create("elasticache", "elasticache-users.json",
                new TypeReference<Map<String, ElastiCacheUser>>() {});
        this.snapshots = storageFactory.create("elasticache", "elasticache-snapshots.json",
                new TypeReference<Map<String, CacheSnapshot>>() {});
        this.userGroups = storageFactory.create("elasticache", "elasticache-user-groups.json",
                new TypeReference<Map<String, ElastiCacheUserGroup>>() {});
        this.parameterGroups = storageFactory.create("elasticache", "elasticache-parameter-groups.json",
                new TypeReference<Map<String, CacheParameterGroup>>() {});
        this.subnetGroups = storageFactory.create("elasticache", "elasticache-subnet-groups.json",
                new TypeReference<Map<String, CacheSubnetGroup>>() {});
    }

    public ElastiCacheService(ElastiCacheContainerManager containerManager,
                              ElastiCacheProxyManager proxyManager,
                              ValkeyClusterFormation clusterFormation,
                              StorageFactory storageFactory, EmulatorConfig config,
                              Ec2Service ec2Service, RegionResolver regionResolver, KmsService kmsService) {
        this(containerManager, proxyManager, clusterFormation, storageFactory, config,
                null, new ContainerDetector(), ec2Service, regionResolver, kmsService);
    }

    /**
     * The CreateReplicationGroup parameters floci models. Anything unset falls back to the
     * defaults a bare create has always had: one non-cluster node behind one proxy port.
     */
    public record CreateReplicationGroupRequest(
            String replicationGroupId,
            String description,
            AuthMode authMode,
            String authToken,
            String region,
            String engine,
            String engineVersion,
            String cacheNodeType,
            String cacheParameterGroupName,
            String cacheSubnetGroupName,
            String clusterMode,
            Integer numNodeGroups,
            Integer replicasPerNodeGroup,
            Integer numCacheClusters,
            Boolean automaticFailoverEnabled,
            Boolean multiAzEnabled,
            Integer port,
            ReplicationGroupSettings settings,
            Map<String, String> tags,
            List<String> userGroupIds,
            List<String> securityGroupIds) {
    }

    public ReplicationGroup createReplicationGroup(String groupId, String description,
                                                   AuthMode authMode, String authToken, String region) {
        return createReplicationGroup(groupId, description, authMode, authToken, region,
                ReplicationGroupSettings.defaults(), Map.of());
    }

    public ReplicationGroup createReplicationGroup(String groupId, String description,
                                                   AuthMode authMode, String authToken, String region,
                                                   ReplicationGroupSettings settings,
                                                   Map<String, String> tags) {
        return createReplicationGroup(new CreateReplicationGroupRequest(groupId, description,
                authMode, authToken, region, null, null, null, null, null, null,
                null, null, null, null, null, null, settings, tags, null, null));
    }

    public ReplicationGroup createReplicationGroup(CreateReplicationGroupRequest request) {
        ReplicationGroupSettings settings = request.settings() != null
                ? request.settings() : ReplicationGroupSettings.defaults();
        settings.validate();
        settings.validateTransitEncryption(request.authMode() != AuthMode.NO_AUTH);
        // resolved with the other validations, before a port is taken or a container started
        ReplicationGroupSettings resolvedSettings =
                settings.withKmsKeyId(resolveKmsKeyArn(settings.kmsKeyId(), request.region()));
        List<String> userGroupReservation = reserveUserGroups(request);
        try {
            return createReplicationGroupWithSettings(request, resolvedSettings);
        } finally {
            releaseUserGroups(userGroupReservation);
        }
    }

    /**
     * Checks the user groups a create names and marks them in use until the create finishes,
     * under {@link #userGroupLock} so the check and the claim cannot straddle a DeleteUserGroup.
     */
    private List<String> reserveUserGroups(CreateReplicationGroupRequest request) {
        if (request.userGroupIds() == null || request.userGroupIds().isEmpty()) {
            return List.of();
        }
        String engine = engineFor(request);
        synchronized (userGroupLock) {
            List<String> reserved = new ArrayList<>();
            for (String userGroupId : request.userGroupIds()) {
                ElastiCacheUserGroup userGroup = getUserGroup(userGroupId);
                requireAssociable(userGroup, request.authMode() != null && request.authMode() != AuthMode.NO_AUTH, engine);
                reserved.add(userGroupReservationKey(userGroup.getUserGroupId()));
            }
            reserved.forEach(key -> reservedUserGroups.merge(key, 1, Integer::sum));
            return reserved;
        }
    }

    private void releaseUserGroups(List<String> reserved) {
        synchronized (userGroupLock) {
            reserved.forEach(key -> reservedUserGroups.computeIfPresent(key, (k, count) -> count > 1 ? count - 1 : null));
        }
    }

    // user groups are stored per account, so two accounts can each hold a group of the same id
    private String userGroupReservationKey(String userGroupId) {
        return userGroups.accountId() + "/" + userGroupId;
    }

    private ReplicationGroup createReplicationGroupWithSettings(CreateReplicationGroupRequest request,
                                                                ReplicationGroupSettings resolvedSettings) {
        String groupId = request.replicationGroupId();
        // Held until the group is persisted (or the attempt fails) so a concurrent
        // DeleteCacheParameterGroup sees the dependency before the stored-groups scan can.
        String parameterGroupReservation = reserveParameterGroup(request.cacheParameterGroupName());
        try {
            // A standalone cache cluster of that id counts as taken, memcached included: see
            // provisioningIds, and cacheClusterIdTaken for the same test the other way round.
            if (groups.get(groupId).isPresent() || cacheClusters.get(groupId).isPresent()
                    || memcachedClusters.get(groupId).isPresent()) {
                throw new AwsException("ReplicationGroupAlreadyExists",
                        "Replication group " + groupId + " already exists.", 400);
            }
            // Claim the id for the whole provisioning attempt so a concurrent create can't race
            // ahead and be stopped by this request's handle-less rollback fallback.
            if (!provisioningIds.claim(groupId)) {
                throw new AwsException("ReplicationGroupAlreadyExists",
                        "Replication group " + groupId + " is already being created.", 400);
            }

            try {
                if (resolveClusterEnabled(request)) {
                    return provisionClusterModeGroup(request, resolvedSettings);
                }
                return provisionSingleNodeGroup(request, resolvedSettings);
            } finally {
                provisioningIds.release(groupId);
            }
        } finally {
            releaseParameterGroup(parameterGroupReservation);
        }
    }

    /**
     * Checks the parameter group exists and marks it in use for the calling create, under the
     * same monitor {@link #deleteCacheParameterGroup} takes, so the existence check and the claim
     * can't straddle a delete. Returns the reservation key, or {@code null} when the request
     * names no parameter group, for the matching {@link #releaseParameterGroup} call.
     */
    private String reserveParameterGroup(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            return null;
        }
        synchronized (lockFor(requestedName)) {
            requireParameterGroup(requestedName);
            String reservation = parameterGroupReservationKey(requestedName);
            reservedParameterGroups.merge(reservation, 1, Integer::sum);
            return reservation;
        }
    }

    private void releaseParameterGroup(String reservation) {
        if (reservation == null) {
            return;
        }
        reservedParameterGroups.computeIfPresent(reservation,
                (key, holders) -> holders > 1 ? holders - 1 : null);
    }

    /**
     * Parameter groups are stored per account, so a reservation is scoped the same way: the
     * account the store is currently prefixing keys with, then the name. Without that, one
     * account's in-flight create would block another account's delete of its own group of the
     * same name.
     */
    private String parameterGroupReservationKey(String name) {
        return parameterGroups.accountId() + "/" + name;
    }

    private ReplicationGroup provisionSingleNodeGroup(CreateReplicationGroupRequest request,
                                                      ReplicationGroupSettings resolvedSettings) {
        String groupId = request.replicationGroupId();
        AuthMode authMode = request.authMode();
        // As on AWS every group has its own hostname, so its Port (6379 unless one is given) is
        // never contended: the engine listens on it and the endpoint names it, and containers
        // reach the group by name (see routeEndpoint). The auth proxy host clients use is a
        // separate listener on Floci's single address, within the proxy range; it takes the
        // group's Port when that is in the range and no other group's proxy holds it, otherwise
        // a free port of the range.
        int enginePort = request.port() != null
                ? validateRange("Port", request.port(), 1, 65535) : DEFAULT_ENGINE_PORT;
        int proxyPort = allocateProxyPortPreferring(enginePort);
        String region = request.region() != null ? request.region() : regionResolver.getRegion();
        String endpointHost = ElastiCacheEndpoints.primary(groupId, regionResolver.getAccountId(), region,
                config.hostname());
        String image = config.services().elasticache().defaultImage();

        LOG.infov("Creating replication group {0} with authMode={1} on port {2}, proxy port {3}",
                groupId, authMode, String.valueOf(enginePort), String.valueOf(proxyPort));

        ElastiCacheContainerHandle handle = null;
        try {
            // A replication group record is metadata: its id, endpoint host and proxy port are
            // derived from configuration and need no Docker, so the group is created and reaches
            // 'available' even when no daemon is reachable. Only connecting to the cache needs
            // the container.
            handle = containerManager.tryStart(groupId, image, enginePort);

            Endpoint endpoint = new Endpoint(endpointHost, enginePort);
            ReplicationGroup group = new ReplicationGroup(
                    groupId, request.description(), ReplicationGroupStatus.AVAILABLE,
                    authMode, endpoint, Instant.now(), proxyPort);
            group.setEnginePort(enginePort);
            group.setAuthToken(request.authToken());
            group.setArn(regionResolver.buildArn("elasticache", request.region(),
                    "replicationgroup:" + groupId));
            group.setRegion(request.region());
            int replicas = request.replicasPerNodeGroup() != null
                    ? validateRange("ReplicasPerNodeGroup", request.replicasPerNodeGroup(), 0, 5) : 0;
            group.setNumCacheClusters(request.numCacheClusters() != null
                    ? validateRange("NumCacheClusters", request.numCacheClusters(), 1, 6)
                    : replicas + 1);
            group.setReplicasPerNodeGroup(group.getNumCacheClusters() - 1);
            applyCommonAttributes(group, request, resolvedSettings);

            if (handle != null) {
                group.setContainerId(handle.getContainerId());
                group.setContainerHost(handle.getHost());
                group.setContainerPort(handle.getPort());
            }

            synchronized (lockFor("rg:" + groupId)) {
                if (handle != null) {
                    proxyPort = startGroupProxy(groupId, authMode, proxyPort, enginePort, handle);
                    group.setProxyPort(proxyPort);
                    group.setConfigurationEndpoint(new Endpoint(endpointHost, endpointPortOf(group)));
                } else {
                    LOG.warnv("Replication group {0} created without a backing cache container: no "
                            + "Docker daemon is reachable. Metadata operations work; connections to "
                            + "the cache do not until a daemon appears.", groupId);
                }
                groups.put(groupId, group);
                routeEndpoint(group, handle);
                // Under the same monitor as the put, for the reason given on recordsHoldingTheirPort.
                recordsHoldingTheirPort.add(recordKey(groups.accountId(), groupId));
            }

            LOG.infov("Replication group {0} created, endpoint={1}:{2}", groupId, endpoint.address(),
                    String.valueOf(group.getConfigurationEndpoint().port()));
            return group;
        } catch (RuntimeException e) {
            LOG.warnv("Replication group {0} provisioning failed, rolling back: {1}", groupId, e.getMessage());
            rollbackReplicationGroup(groupId, handle, proxyPort);
            throw e;
        }
    }

    private ReplicationGroup provisionClusterModeGroup(CreateReplicationGroupRequest request,
                                                       ReplicationGroupSettings resolvedSettings) {
        String groupId = request.replicationGroupId();
        AuthMode authMode = request.authMode();
        int numNodeGroups = request.numNodeGroups() != null
                ? validateNumNodeGroups(request.numNodeGroups())
                : 1;
        int replicasPerNodeGroup = request.replicasPerNodeGroup() != null
                ? validateRange("ReplicasPerNodeGroup", request.replicasPerNodeGroup(), 0, 5)
                : 0;
        String image = config.services().elasticache().defaultImage();
        String endpointHost = resolveClusterAnnounceHost();
        String announceIp = resolveAnnounceIp(endpointHost);

        LOG.infov("Creating cluster-mode replication group {0} with {1} node group(s), {2} replica(s) each",
                groupId, String.valueOf(numNodeGroups), String.valueOf(replicasPerNodeGroup));

        List<ClusterNode> nodes = new ArrayList<>();
        List<ElastiCacheContainerHandle> handles = new ArrayList<>();
        List<String> startedProxyKeys = new ArrayList<>();
        String inFlightMemberId = null;
        try {
            for (int shard = 0; shard < numNodeGroups; shard++) {
                String nodeGroupId = String.format("%04d", shard + 1);
                int[] slots = ValkeyClusterFormation.slotRange(shard, numNodeGroups);
                for (int member = 0; member <= replicasPerNodeGroup; member++) {
                    String memberId = groupId + "-" + nodeGroupId + "-" + String.format("%03d", member + 1);
                    // The group's Port is reported from the first node's proxy port, so only that
                    // node can honor a requested port; the rest take whatever is free.
                    Integer requestedPort = nodes.isEmpty() ? request.port() : null;
                    nodes.add(new ClusterNode(memberId, nodeGroupId, member == 0,
                            allocateProxyPort(requestedPort), slots[0] + "-" + slots[1]));
                }
            }

            List<ValkeyClusterFormation.Node> formationNodes = new ArrayList<>(nodes.size());
            for (ClusterNode node : nodes) {
                inFlightMemberId = node.getMemberClusterId();
                ElastiCacheContainerHandle handle = containerManager.start(
                        node.getMemberClusterId(), image,
                        clusterNodeFlags(endpointHost, announceIp, node.getProxyPort()), request.region());
                inFlightMemberId = null;
                handles.add(handle);
                attachContainer(node, handle);
                formationNodes.add(new ValkeyClusterFormation.Node(
                        handle.getHost(), handle.getPort(), node.getNetworkIp(),
                        Integer.parseInt(node.getNodeGroupId()) - 1, node.isPrimary()));
            }

            clusterFormation.form(groupId, formationNodes, numNodeGroups);

            for (int i = 0; i < nodes.size(); i++) {
                ClusterNode node = nodes.get(i);
                ElastiCacheContainerHandle handle = handles.get(i);
                proxyManager.startProxy(node.getMemberClusterId(), authMode, node.getProxyPort(),
                        handle.getHost(), handle.getPort(),
                        groupAuthenticator(groups.accountId(), groupId));
                startedProxyKeys.add(node.getMemberClusterId());
            }

            Endpoint configurationEndpoint = new Endpoint(endpointHost, nodes.getFirst().getProxyPort());
            ReplicationGroup group = new ReplicationGroup(
                    groupId, request.description(), ReplicationGroupStatus.AVAILABLE,
                    authMode, configurationEndpoint, Instant.now(), nodes.getFirst().getProxyPort());
            group.setAuthToken(request.authToken());
            group.setArn(regionResolver.buildArn("elasticache", request.region(),
                    "replicationgroup:" + groupId));
            group.setRegion(request.region());
            group.setClusterEnabled(true);
            group.setNumNodeGroups(numNodeGroups);
            group.setReplicasPerNodeGroup(replicasPerNodeGroup);
            group.setNumCacheClusters(nodes.size());
            group.setClusterNodes(nodes);
            applyCommonAttributes(group, request, resolvedSettings);

            groups.put(groupId, group);
            LOG.infov("Cluster-mode replication group {0} created, configuration endpoint={1}:{2}",
                    groupId, endpointHost, String.valueOf(configurationEndpoint.port()));
            return group;
        } catch (RuntimeException e) {
            LOG.warnv("Cluster-mode replication group {0} provisioning failed, rolling back: {1}",
                    groupId, e.getMessage());
            rollbackClusterModeGroup(groupId, startedProxyKeys, handles, inFlightMemberId,
                    nodes.stream().map(ClusterNode::getProxyPort).toList());
            throw e;
        }
    }

    private void applyCommonAttributes(ReplicationGroup group, CreateReplicationGroupRequest request,
                                       ReplicationGroupSettings resolvedSettings) {
        group.setEngine(engineFor(request));
        if (request.userGroupIds() != null) {
            request.userGroupIds().forEach(id -> group.getUserGroupIds().add(normalizeUserGroupId(id)));
        }
        group.setEngineVersion(request.engineVersion() != null && !request.engineVersion().isBlank()
                ? request.engineVersion()
                : defaultEngineVersion(group.getEngine()));
        group.setCacheNodeType(request.cacheNodeType() != null && !request.cacheNodeType().isBlank()
                ? request.cacheNodeType()
                : "cache.t4g.micro");
        group.setCacheParameterGroupName(request.cacheParameterGroupName());
        group.setCacheSubnetGroupName(request.cacheSubnetGroupName());
        group.setSecurityGroupIds(request.securityGroupIds());
        group.setAutomaticFailoverEnabled(Boolean.TRUE.equals(request.automaticFailoverEnabled()));
        group.setMultiAzEnabled(Boolean.TRUE.equals(request.multiAzEnabled()));
        group.setSnapshotWindow(ReplicationGroupSettings.DEFAULT_SNAPSHOT_WINDOW);
        resolvedSettings.applyTo(group);
        if (request.tags() != null && !request.tags().isEmpty()) {
            group.setTags(new LinkedHashMap<>(request.tags()));
        }
    }

    private String engineFor(CreateReplicationGroupRequest request) {
        return request.engine() != null && !request.engine().isBlank()
                ? normalizeEngine(request.engine())
                : defaultEngineForImage();
    }

    private static String defaultEngineVersion(String engine) {
        return "valkey".equals(engine) ? "8.1" : "7.1";
    }

    private String defaultEngineForImage() {
        String image = config.services().elasticache().defaultImage();
        return image != null && image.contains("valkey") ? "valkey" : "redis";
    }

    /**
     * Cluster mode follows AWS's signal — the parameter group — plus the request shapes that
     * imply it: an explicit ClusterMode, or more than one node group. ClusterMode
     * {@code compatible} — the mid-migration state — is deliberately not modelled; it enables
     * cluster mode only when one of the other signals also does.
     */
    private boolean resolveClusterEnabled(CreateReplicationGroupRequest request) {
        if ("enabled".equalsIgnoreCase(request.clusterMode())) {
            return true;
        }
        String parameterGroupName = request.cacheParameterGroupName();
        if (parameterGroupName != null) {
            if (parameterGroupName.endsWith(".cluster.on")) {
                return true;
            }
            boolean customClusterGroup = findParameterGroup(parameterGroupName)
                    .map(group -> "yes".equalsIgnoreCase(group.getParameters().get("cluster-enabled")))
                    .orElse(false);
            if (customClusterGroup) {
                return true;
            }
        }
        return request.numNodeGroups() != null && request.numNodeGroups() > 1;
    }

    /**
     * Nodes announce the cluster announce hostname as their preferred endpoint, so MOVED/ASK
     * redirects and CLUSTER SLOTS hand clients the exact name floci reports as the configuration
     * endpoint — how (or whether) that name resolves inside floci's own container is irrelevant.
     * The best-effort IPv4 stays announced for consumers that read the address fields instead.
     */
    private static List<String> clusterNodeFlags(String endpointHost, String announceIp, int proxyPort) {
        return List.of(
                "--cluster-enabled", "yes",
                "--cluster-announce-hostname", endpointHost,
                "--cluster-preferred-endpoint-type", "hostname",
                "--cluster-announce-client-ipv4", announceIp,
                "--cluster-announce-port", String.valueOf(proxyPort),
                "--cluster-announce-bus-port", "16379");
    }

    private static String resolveAnnounceIp(String endpointHost) {
        try {
            for (InetAddress address : InetAddress.getAllByName(endpointHost)) {
                if (address instanceof Inet4Address) {
                    return address.getHostAddress();
                }
            }
        } catch (UnknownHostException e) {
            LOG.warnv("Could not resolve {0} to an IPv4 for cluster announce, using 127.0.0.1: {1}",
                    endpointHost, e.getMessage());
        }
        return "127.0.0.1";
    }

    private static int validateRange(String parameterName, int value, int min, int max) {
        if (value < min || value > max) {
            throw new AwsException("InvalidParameterValue",
                    parameterName + " must be between " + min + " and " + max + ".", 400);
        }
        return value;
    }

    /** 500 is AWS's raised NumNodeGroups quota (the default is 90; 500 with a limit increase). */
    private static final int MAX_NODE_GROUPS = 500;

    private static int validateNumNodeGroups(int value) {
        if (value > MAX_NODE_GROUPS) {
            throw new AwsException("NodeGroupsPerReplicationGroupQuotaExceeded",
                    "The request cannot be processed because it would exceed the maximum allowed"
                            + " number of node groups (shards) in a single replication group."
                            + " The maximum is " + MAX_NODE_GROUPS + ".", 400);
        }
        return validateRange("NumNodeGroups", value, 1, MAX_NODE_GROUPS);
    }

    private void rollbackClusterModeGroup(String groupId, List<String> startedProxyKeys,
                                          List<ElastiCacheContainerHandle> handles,
                                          String inFlightMemberId, Collection<Integer> proxyPorts) {
        for (String proxyKey : startedProxyKeys) {
            try {
                proxyManager.stopProxy(proxyKey);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping proxy {0} during rollback of group {1}: {2}",
                        proxyKey, groupId, e.getMessage());
            }
        }
        for (ElastiCacheContainerHandle handle : handles) {
            try {
                containerManager.stop(handle);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container {0} during rollback of group {1}: {2}",
                        handle.getContainerId(), groupId, e.getMessage());
            }
        }
        if (inFlightMemberId != null) {
            try {
                containerManager.stopByGroupId(inFlightMemberId);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping in-flight container {0} during rollback of group {1}: {2}",
                        inFlightMemberId, groupId, e.getMessage());
            }
        }
        for (int port : proxyPorts) {
            releaseProxyPort(port);
        }
    }

    /**
     * Brings the data plane back up for every group restored from disk. Invoked from
     * {@code EmulatorLifecycle} after {@code storageFactory.loadAll()}, alongside the other
     * services that restore persisted runtime. Only topology survives a restart — containers,
     * proxies and port reservations are process-local — so each group is re-provisioned from
     * its persisted record, and a group whose data plane cannot come back is reported
     * {@code create-failed} instead of available.
     *
     * <p>A group that is not re-provisioned is worse than one that is reported failed. Its
     * status stays {@code available} with nothing behind the endpoint, so the control plane
     * answers healthy while every connection fails, and the proxy port it still advertises is
     * free for the next create to take — pointing the old endpoint at an unrelated cache rather
     * than failing cleanly.
     *
     * <p>Standalone Redis and Valkey cache clusters are restored the same way, and must be: they
     * are the same Valkey container behind the same auth proxy on a port from the same range as a
     * cluster-mode-disabled group, so leaving them out would reproduce exactly the state above
     * for the one shape that reaches Floci through {@code aws_elasticache_cluster}. Memcached
     * clusters are {@code ElastiCacheMemcachedService}'s to restore, from its own store.
     *
     * <p>Container restarts and cluster formation can take a readiness timeout and a formation
     * timeout per group, so the data-plane work runs in the background and must not delay
     * emulator readiness. Records are marked {@code creating} synchronously, each proxy port
     * reserved at the same time, before any request can claim it, and flip to {@code available}
     * or to the failure status their store models as their restoration finishes.
     */
    public CompletableFuture<Void> restorePersistedRuntime() {
        List<ReplicationGroup> clusterModeToRestore = new ArrayList<>();
        List<ReplicationGroup> singleNodeToRestore = new ArrayList<>();
        for (ReplicationGroup group : groups.scan(k -> true)) {
            if (group.getStatus() == ReplicationGroupStatus.DELETING) {
                continue;
            }
            if (group.isClusterEnabled() && !group.getClusterNodes().isEmpty()) {
                reserveClusterModeGroup(group, clusterModeToRestore);
            } else {
                reserveSingleNodeGroup(group, singleNodeToRestore);
            }
            groups.putForAccount(entry.accountId(), entry.key(), group);
        }
        if (clusterModeToRestore.isEmpty() && singleNodeToRestore.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        LOG.infov("Restoring {0} cluster-mode and {1} single-node replication group(s) in the background",
                String.valueOf(clusterModeToRestore.size()), String.valueOf(singleNodeToRestore.size()));
        return CompletableFuture.runAsync(() -> {
            clusterModeToRestore.forEach(this::restoreClusterModeGroup);
            singleNodeToRestore.forEach(this::restoreSingleNodeGroup);
        });
    }

    private void reserveClusterModeGroup(ReplicationGroup group, List<ReplicationGroup> toRestore) {
        List<Integer> reserved = new ArrayList<>();
        try {
            for (ClusterNode node : group.getClusterNodes()) {
                node.setProxyPort(reserveOrAllocateProxyPort(node.getProxyPort()));
                reserved.add(node.getProxyPort());
            }
            group.setStatus(ReplicationGroupStatus.CREATING);
            toRestore.add(group);
        } catch (RuntimeException e) {
            reserved.forEach(this::releaseProxyPort);
            group.setStatus(ReplicationGroupStatus.CREATE_FAILED);
            group.setConfigurationEndpoint(null);
            LOG.warnv(e, "Failed to restore cluster-mode replication group {0}",
                    group.getReplicationGroupId());
        }
    }

    /**
     * Reserves the port a cluster-mode-disabled group comes back advertising and marks it
     * {@code creating} for {@link #restoreSingleNodeGroup}. The endpoint host is re-derived from
     * configuration rather than replayed from the record, so a group restored under a changed
     * {@code FLOCI_HOSTNAME} advertises the name this process actually answers to.
     */
    private void reserveSingleNodeGroup(ReplicationGroup group, List<ReplicationGroup> toRestore) {
        try {
            group.setProxyPort(reserveOrAllocateProxyPort(group.getProxyPort()));
            group.setConfigurationEndpoint(new Endpoint(endpointHostFor(group), endpointPortOf(group)));
            group.setStatus(ReplicationGroupStatus.CREATING);
            toRestore.add(group);
        } catch (RuntimeException e) {
            group.setStatus(ReplicationGroupStatus.CREATE_FAILED);
            group.setConfigurationEndpoint(null);
            LOG.warnv(e, "Failed to restore replication group {0}", group.getReplicationGroupId());
        }
    }

    /**
     * Restarts the container and auth proxy behind a cluster-mode-disabled group, the same pair
     * {@link #provisionSingleNodeGroup} creates. The cache comes back empty, as it does for
     * cluster-mode groups: only the topology is persisted, never the keyspace.
     *
     * <p>Uses {@code tryStart} for the same reason the create path does: with no Docker daemon
     * reachable, the group keeps its metadata and reports available without a container, rather
     * than being failed for a daemon that may appear later. A failure raised while the daemon
     * <em>is</em> reachable is a genuine container problem and fails the group.
     *
     * <p>The container work runs outside the group's monitor, as it does on create: a restore
     * that may pull an image must not hold a delete of the same group behind it. The write-back
     * takes the monitor and re-reads the record, because a delete taken while the container
     * started has already removed it.
     */
    private void restoreSingleNodeGroup(ReplicationGroup group) {
        String groupId = group.getReplicationGroupId();
        String image = config.services().elasticache().defaultImage();
        try {
            ElastiCacheContainerHandle handle = containerManager.tryStart(groupId, image, enginePortOf(group));
            synchronized (lockFor("rg:" + groupId)) {
                if (restoreTargetLost(groupId)) {
                    abandonRestoredContainer(groupId, handle);
                    return;
                }
                if (handle != null) {
                    group.setContainerId(handle.getContainerId());
                    group.setContainerHost(handle.getHost());
                    group.setContainerPort(handle.getPort());
                    group.setProxyPort(startGroupProxy(groupId, group.getAuthMode(), group.getProxyPort(),
                            enginePortOf(group), handle));
                } else {
                    // Cleared rather than left alone: whatever the record carried describes a
                    // container from the previous process, and nothing must read it as live.
                    group.setContainerId(null);
                    group.setContainerHost(null);
                    group.setContainerPort(0);
                    LOG.warnv("Replication group {0} restored without a backing cache container: no "
                            + "Docker daemon is reachable. Metadata operations work; connections to "
                            + "the cache do not until a daemon appears.", groupId);
                }
                group.setConfigurationEndpoint(new Endpoint(endpointHostFor(group), endpointPortOf(group)));
                group.setStatus(ReplicationGroupStatus.AVAILABLE);
                groups.put(groupId, group);
                routeEndpoint(group, handle);
                LOG.infov("Restored replication group {0}, endpoint={1}:{2}", groupId,
                        group.getConfigurationEndpoint().address(),
                        String.valueOf(group.getProxyPort()));
            }
        } catch (RuntimeException e) {
            failSingleNodeRestore(group, e);
        }
    }

    /**
     * Whether the record a restore is about to write back is still there to write back to. Callers
     * must hold the group's monitor: a delete taken while the container work ran removed the record
     * under that same monitor, and a restore that put its group back afterwards would resurrect a
     * group the caller was told had been deleted, as {@code available}, with a fresh container
     * behind it.
     */
    private boolean restoreTargetLost(String groupId) {
        return groups.get(groupId)
                .map(current -> current.getStatus() == ReplicationGroupStatus.DELETING)
                .orElse(true);
    }

    /**
     * Drops the container a restore started for a group that was deleted while it ran. The proxy
     * port is deliberately not released: the delete that removed the record released it already,
     * and releasing it a second time would return a port a later create has since taken to the
     * pool, letting two groups be handed the same one.
     */
    private void abandonRestoredContainer(String groupId, ElastiCacheContainerHandle handle) {
        if (handle != null) {
            try {
                containerManager.stop(handle);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container for deleted replication group {0}: {1}",
                        groupId, e.getMessage());
            }
        }
        LOG.infov("Discarded the restored data plane for replication group {0}: it was deleted "
                + "while it was being restored", groupId);
    }

    /**
     * Reports a group whose data plane could not be brought back, without deleting the record:
     * the group still exists on the control plane, it just has nothing behind it. Tears down
     * whatever the attempt left running and frees the port, so the next create can use it rather
     * than leaking a reservation nothing serves.
     *
     * <p>A group deleted while the failed attempt ran gets the teardown but no write-back and no
     * port release: reporting {@code create-failed} would put a deleted group back, and the
     * delete released the port itself.
     */
    private void failSingleNodeRestore(ReplicationGroup group, RuntimeException cause) {
        String groupId = group.getReplicationGroupId();
        synchronized (lockFor("rg:" + groupId)) {
            try {
                proxyManager.stopProxy(groupId);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping proxy for replication group {0}: {1}", groupId, e.getMessage());
            }
            try {
                containerManager.stopByGroupId(groupId);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container for replication group {0}: {1}", groupId, e.getMessage());
            }
            if (restoreTargetLost(groupId)) {
                LOG.warnv(cause, "Failed to restore replication group {0}, which was deleted while "
                        + "it was being restored", groupId);
                return;
            }
            releaseProxyPort(group.getProxyPort());
            group.setContainerId(null);
            group.setContainerHost(null);
            group.setContainerPort(0);
            group.setStatus(ReplicationGroupStatus.CREATE_FAILED);
            group.setConfigurationEndpoint(null);
            try {
                groups.put(groupId, group);
            } catch (RuntimeException persistFailure) {
                cause.addSuppressed(persistFailure);
            }
            LOG.warnv(cause, "Failed to restore replication group {0}", groupId);
        }
    }

    /**
     * Restarts the containers, cluster formation and proxies behind a cluster-mode group. Like
     * {@link #restoreSingleNodeGroup}, the container work runs outside the group's monitor and
     * only the write-back takes it, so a delete that ran in the meantime is seen before the
     * group is put back.
     */
    private void restoreClusterModeGroup(ReplicationGroup group) {
        String groupId = group.getReplicationGroupId();
        String image = config.services().elasticache().defaultImage();
        String endpointHost = resolveClusterAnnounceHost();
        String announceIp = resolveAnnounceIp(endpointHost);
        List<ClusterNode> nodes = group.getClusterNodes();

        List<ElastiCacheContainerHandle> handles = new ArrayList<>();
        List<String> startedProxyKeys = new ArrayList<>();
        List<Integer> reservedPorts = nodes.stream().map(ClusterNode::getProxyPort).toList();
        String inFlightMemberId = null;
        try {
            // Shards are laid out by position, not by id: a removed shard leaves a gap in the ids.
            List<String> nodeGroupIds = nodeGroupIds(group);
            List<ValkeyClusterFormation.Node> formationNodes = new ArrayList<>(nodes.size());
            for (ClusterNode node : nodes) {
                inFlightMemberId = node.getMemberClusterId();
                ElastiCacheContainerHandle handle = containerManager.start(
                        node.getMemberClusterId(), image,
                        clusterNodeFlags(endpointHost, announceIp, node.getProxyPort()), regionOf(group.getArn()));
                inFlightMemberId = null;
                handles.add(handle);
                attachContainer(node, handle);
                formationNodes.add(new ValkeyClusterFormation.Node(
                        handle.getHost(), handle.getPort(), node.getNetworkIp(),
                        nodeGroupIds.indexOf(node.getNodeGroupId()), node.isPrimary()));
            }

            clusterFormation.form(groupId, formationNodes, group.getNumNodeGroups());

            synchronized (lockFor("rg:" + groupId)) {
                if (restoreTargetLost(groupId)) {
                    // No ports: the delete that removed the record released every one of them.
                    rollbackClusterModeGroup(groupId, startedProxyKeys, handles, inFlightMemberId,
                            List.of());
                    LOG.infov("Discarded the restored data plane for cluster-mode replication group "
                            + "{0}: it was deleted while it was being restored", groupId);
                    return;
                }
                for (int i = 0; i < nodes.size(); i++) {
                    ClusterNode node = nodes.get(i);
                    ElastiCacheContainerHandle handle = handles.get(i);
                    proxyManager.startProxy(node.getMemberClusterId(), group.getAuthMode(), node.getProxyPort(),
                            handle.getHost(), handle.getPort(),
                            (username, password) -> validatePassword(groupId, username, password));
                    startedProxyKeys.add(node.getMemberClusterId());
                }

                group.setConfigurationEndpoint(new Endpoint(endpointHost, nodes.getFirst().getProxyPort()));
                group.setStatus(ReplicationGroupStatus.AVAILABLE);
                groups.putForAccount(accountId, groupId, group);
                LOG.infov("Restored cluster-mode replication group {0}: {1} node(s), configuration endpoint={2}:{3}",
                        groupId, String.valueOf(nodes.size()), endpointHost,
                        String.valueOf(group.getConfigurationEndpoint().port()));
            }
        } catch (RuntimeException e) {
            failClusterModeRestore(group, startedProxyKeys, handles, inFlightMemberId, reservedPorts, e);
        }
    }

    /**
     * Reports a cluster-mode group whose data plane could not be brought back, mirroring
     * {@link #failSingleNodeRestore}: the record stays, reporting {@code create-failed} with no
     * endpoint, unless a delete removed it while the attempt ran. In that case the rollback still
     * runs but the ports are left to the delete that released them, and nothing is written back.
     */
    private void failClusterModeRestore(ReplicationGroup group, List<String> startedProxyKeys,
                                        List<ElastiCacheContainerHandle> handles,
                                        String inFlightMemberId, Collection<Integer> reservedPorts,
                                        RuntimeException cause) {
        String groupId = group.getReplicationGroupId();
        synchronized (lockFor("rg:" + groupId)) {
            boolean lost = restoreTargetLost(groupId);
            rollbackClusterModeGroup(groupId, startedProxyKeys, handles, inFlightMemberId,
                    lost ? List.of() : reservedPorts);
            if (lost) {
                LOG.warnv(cause, "Failed to restore cluster-mode replication group {0}, which was "
                        + "deleted while it was being restored", groupId);
                return;
            }
            for (ClusterNode node : group.getClusterNodes()) {
                node.setContainerId(null);
                node.setContainerHost(null);
                node.setContainerPort(0);
            }
            group.setStatus(ReplicationGroupStatus.CREATE_FAILED);
            group.setConfigurationEndpoint(null);
            try {
                groups.putForAccount(accountId, groupId, group);
            } catch (RuntimeException persistFailure) {
                cause.addSuppressed(persistFailure);
            }
            LOG.warnv(cause, "Failed to restore cluster-mode replication group {0}", groupId);
        }
    }

    private void rollbackReplicationGroup(String groupId, ElastiCacheContainerHandle handle, int proxyPort) {
        if (containerEndpoints != null) {
            withdrawProxyPort(groupId);
        }
        try {
            if (handle != null) {
                proxyManager.stopProxy(groupId);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Error stopping proxy for replication group {0}: {1}", groupId, e.getMessage());
        }
        try {
            if (handle != null) {
                containerManager.stop(handle);
            } else {
                // No handle: a readiness timeout throws before start() can return one.
                containerManager.stopByGroupId(groupId);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Error stopping container for replication group {0}: {1}", groupId, e.getMessage());
        } finally {
            groups.delete(groupId);
            recordsHoldingTheirPort.remove(recordKey(groups.accountId(), groupId));
            releaseProxyPort(proxyPort);
        }
    }

    /** The group's AWS-shaped primary endpoint hostname, re-derived from its ARN and region. */
    private String endpointHostFor(ReplicationGroup group) {
        String account = AwsArnUtils.accountOrDefault(group.getArn(), regionResolver.getAccountId());
        String region = group.getRegion() != null ? group.getRegion()
                : AwsArnUtils.regionOrDefault(group.getArn(), regionResolver.getDefaultRegion());
        return ElastiCacheEndpoints.primary(group.getReplicationGroupId(), account, region, config.hostname());
    }

    /**
     * Starts the group's auth proxy for host clients. When the proxy was given the group's own
     * Port and something else on this machine already listens there (a local Redis, say), the
     * proxy moves to a free port of the range instead of failing the create. The Port the group
     * reports is the engine's, which does not depend on where the proxy listens.
     *
     * @return the port the proxy listens on
     */
    private int startGroupProxy(String groupId, AuthMode authMode, int proxyPort, int enginePort,
                                ElastiCacheContainerHandle handle) {
        try {
            proxyManager.startProxy(groupId, authMode, proxyPort, handle.getHost(), handle.getPort(),
                    (username, password) -> validatePassword(groupId, username, password));
            return proxyPort;
        } catch (RuntimeException e) {
            if (proxyPort != enginePort) {
                throw e;
            }
            int fallback = allocateProxyPort(null);
            releaseProxyPort(proxyPort);
            LOG.infov("Port {0} is not available on this host ({1}); replication group {2} keeps it as "
                            + "its Port and serves host clients on {3}",
                    String.valueOf(proxyPort), e.getMessage(), groupId, String.valueOf(fallback));
            try {
                proxyManager.startProxy(groupId, authMode, fallback, handle.getHost(), handle.getPort(),
                        (username, password) -> validatePassword(groupId, username, password));
            } catch (RuntimeException retryFailure) {
                releaseProxyPort(fallback);
                retryFailure.addSuppressed(e);
                throw retryFailure;
            }
            return fallback;
        }
    }

    /**
     * Makes a cluster-mode-disabled group reachable from the containers Floci launches on its
     * endpoint hostname and Port, as on AWS, whatever other groups use the same Port:
     *
     * <ul>
     *   <li>A group without authentication is served by its engine container, which listens on
     *       the endpoint's Port, so the hostname resolves to that container.</li>
     *   <li>A group with authentication must be reached through its auth proxy. When the proxy
     *       holds the endpoint's Port on Floci's address, the hostname resolves to Floci and the
     *       port is published to containers. Otherwise the group gets a relay of its own that
     *       listens on the endpoint's Port and relays to its proxy, and the hostname resolves to
     *       the relay.</li>
     * </ul>
     *
     * Host clients reach a group at {@code 127.0.0.1:<Port>} only while its proxy holds that Port:
     * every endpoint hostname resolves to the one loopback address there, so of the groups sharing
     * a Port the first one created holds it and the others are served on their proxy port.
     */
    private void routeEndpoint(ReplicationGroup group, ElastiCacheContainerHandle handle) {
        if (containerEndpoints == null) {
            return;
        }
        String groupId = group.getReplicationGroupId();
        Endpoint endpoint = group.getConfigurationEndpoint();
        String hostname = endpoint != null && isRoutableHostname(endpoint.address()) ? endpoint.address() : null;
        try {
            withdrawProxyPort(groupId);
            if (handle == null || endpoint == null) {
                containerEndpoints.release(hostname);
                return;
            }
            int endpointPort = endpoint.port();
            if (hostname != null && group.getAuthMode() == AuthMode.NO_AUTH && handle.getNetworkIp() != null
                    && enginePortOf(group) == endpointPort) {
                containerEndpoints.routeToContainer(hostname, handle.getNetworkIp());
                return;
            }
            if (group.getProxyPort() == endpointPort) {
                containerEndpoints.release(hostname);
                containerEndpoints.publishHostPort(group.getProxyPort());
                publishedProxyPorts.put(groupId, group.getProxyPort());
                return;
            }
            // A configured IP literal is every group's address, so it cannot be relayed per group.
            if (hostname == null) {
                return;
            }
            if (!containerEndpoints.relayToFloci(hostname, endpointPort, group.getProxyPort())) {
                containerEndpoints.release(hostname);
            }
        } catch (RuntimeException e) {
            // Never hand the name to whatever else listens on the Port: that is another group.
            try {
                containerEndpoints.refuse(hostname);
            } catch (RuntimeException refuseFailure) {
                e.addSuppressed(refuseFailure);
            }
            LOG.warnv("Replication group {0} is not reachable from containers on {1}:{2}: {3}", groupId,
                    hostname, endpoint == null ? "" : String.valueOf(endpoint.port()), e.getMessage());
        }
    }

    private void unrouteEndpoint(ReplicationGroup group) {
        if (containerEndpoints == null) {
            return;
        }
        withdrawProxyPort(group.getReplicationGroupId());
        Endpoint endpoint = group.getConfigurationEndpoint();
        if (endpoint != null && isRoutableHostname(endpoint.address())) {
            containerEndpoints.release(endpoint.address());
        }
    }

    private void withdrawProxyPort(String groupId) {
        Integer published = publishedProxyPorts.remove(groupId);
        if (published != null) {
            containerEndpoints.withdrawHostPort(published);
        }
    }

    /** A per-group name; a configured IP literal is shared by every group and is never routed. */
    private static boolean isRoutableHostname(String address) {
        return address != null && !address.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")
                && !"localhost".equalsIgnoreCase(address);
    }

    /** The Port a cluster-mode-disabled group's endpoint reports and serves: its engine's. */
    private int endpointPortOf(ReplicationGroup group) {
        return enginePortOf(group);
    }

    /** The port the group's engine listens on; records predating it used the proxy port. */
    private static int enginePortOf(ReplicationGroup group) {
        return group.getEnginePort() > 0 ? group.getEnginePort() : group.getProxyPort();
    }

    public ReplicationGroup getReplicationGroup(String groupId) {
        return groups.get(groupId).orElseThrow(() ->
                new AwsException("ReplicationGroupNotFoundFault",
                        "Replication group " + groupId + " not found.", 404));
    }

    public Collection<ReplicationGroup> listReplicationGroups(String filterGroupId) {
        if (filterGroupId != null && !filterGroupId.isBlank()) {
            return groups.get(filterGroupId)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("ReplicationGroupNotFoundFault",
                            "Replication group " + filterGroupId + " not found.", 404));
        }
        return groups.scan(k -> true);
    }

    public void deleteReplicationGroup(String groupId) {
        deleteReplicationGroup(groupId, null);
    }

    public void deleteReplicationGroup(String groupId, String finalSnapshotName) {
        // one monitor per group for delete and modify: a modify's read-modify-write could
        // otherwise write the group back after this removed it
        synchronized (lockFor("rg:" + groupId)) {
            ReplicationGroup group = groups.get(groupId).orElseThrow(() ->
                    new AwsException("ReplicationGroupNotFoundFault",
                            "Replication group " + groupId + " not found.", 404));

            if (finalSnapshotName != null && !finalSnapshotName.isBlank()) {
                createSnapshot(finalSnapshotName, groupId);
            }
            group.setStatus(ReplicationGroupStatus.DELETING);
            groups.put(groupId, group);

            if (group.isClusterEnabled() && !group.getClusterNodes().isEmpty()) {
                for (ClusterNode node : group.getClusterNodes()) {
                    stopClusterNode(node);
                }
            } else {
                unrouteEndpoint(group);
                proxyManager.stopProxy(groupId);

                if (group.getContainerId() != null) {
                    containerManager.stop(new ElastiCacheContainerHandle(
                            group.getContainerId(), groupId, group.getContainerHost(), group.getContainerPort()));
                }

                // Only a port this process reserved for this record, as on the cluster path: a
                // restored group whose port was already taken advertises one it does not own.
                if (recordsHoldingTheirPort.remove(recordKey(groups.accountId(), groupId))) {
                    releaseProxyPort(group.getProxyPort());
                }
            }
            groups.delete(groupId);
            LOG.infov("Replication group {0} deleted", groupId);
        }
    }

    private void stopClusterNode(ClusterNode node) {
        proxyManager.stopProxy(node.getMemberClusterId());
        if (node.getContainerId() != null) {
            containerManager.stop(new ElastiCacheContainerHandle(
                    node.getContainerId(), node.getMemberClusterId(),
                    node.getContainerHost(), node.getContainerPort()));
        } else {
            // Transient container fields are lost across a Floci restart; the
            // deterministic container name still finds the node.
            containerManager.stopByGroupId(node.getMemberClusterId());
        }
        releaseProxyPort(node.getProxyPort());
    }

    private static void attachContainer(ClusterNode node, ElastiCacheContainerHandle handle) {
        node.setContainerId(handle.getContainerId());
        node.setContainerHost(handle.getHost());
        node.setContainerPort(handle.getPort());
        node.setNetworkIp(handle.getNetworkIp() != null ? handle.getNetworkIp() : handle.getHost());
    }

    /** The group's node group ids in the order its shards are laid out. */
    private static List<String> nodeGroupIds(ReplicationGroup group) {
        List<String> ids = new ArrayList<>();
        for (ClusterNode node : group.getClusterNodes()) {
            if (!ids.contains(node.getNodeGroupId())) {
                ids.add(node.getNodeGroupId());
            }
        }
        return ids;
    }

    /**
     * ModifyReplicationGroupShardConfiguration. A cluster-mode-enabled group is resharded online
     * for real: added shards get their own nodes and proxies and join the running cluster, every
     * shard ends up owning an equal contiguous slot range, and removed shards hand their slots
     * and keys to the shards that remain before their nodes are forgotten and stopped. A
     * cluster-mode-disabled group has no data-plane shards, so only its recorded count changes.
     */
    public ReplicationGroup modifyShardConfiguration(String groupId, int nodeGroupCount,
                                                     List<String> nodeGroupsToRemove,
                                                     List<String> nodeGroupsToRetain) {
        if (nodeGroupCount > MAX_NODE_GROUPS) {
            validateNumNodeGroups(nodeGroupCount);
        }
        validateRange("NodeGroupCount", nodeGroupCount, 1, MAX_NODE_GROUPS);
        synchronized (lockFor("rg:" + groupId)) {
            ReplicationGroup group = getReplicationGroup(groupId);
            if (!group.isClusterEnabled() || group.getClusterNodes().isEmpty()) {
                group.setNodeGroupCount(nodeGroupCount);
                groups.put(groupId, group);
                return group;
            }
            if (group.getStatus() != ReplicationGroupStatus.AVAILABLE) {
                throw new AwsException("InvalidReplicationGroupState",
                        "Replication group " + groupId + " is not in available state.", 400);
            }
            List<String> current = nodeGroupIds(group);
            List<String> removed = resolveRemovedNodeGroups(groupId, current, nodeGroupCount,
                    nodeGroupsToRemove, nodeGroupsToRetain);
            int added = nodeGroupCount - (current.size() - removed.size());
            if (added == 0 && removed.isEmpty()) {
                return group;
            }
            reshardClusterModeGroup(group, removed, added);
            groups.put(groupId, group);
            return group;
        }
    }

    private static List<String> resolveRemovedNodeGroups(String groupId, List<String> current,
                                                         int nodeGroupCount, List<String> toRemove,
                                                         List<String> toRetain) {
        boolean removeGiven = toRemove != null && !toRemove.isEmpty();
        boolean retainGiven = toRetain != null && !toRetain.isEmpty();
        if (nodeGroupCount >= current.size()) {
            if (removeGiven || retainGiven) {
                throw new AwsException("InvalidParameterCombination",
                        "NodeGroupsToRemove and NodeGroupsToRetain apply only when NodeGroupCount is "
                                + "less than the current number of node groups.", 400);
            }
            return List.of();
        }
        if (removeGiven && retainGiven) {
            throw new AwsException("InvalidParameterCombination",
                    "Only one of NodeGroupsToRemove or NodeGroupsToRetain can be specified.", 400);
        }
        if (!removeGiven && !retainGiven) {
            throw new AwsException("InvalidParameterValue",
                    "Either NodeGroupsToRemove or NodeGroupsToRetain is required when NodeGroupCount "
                            + "is less than the current number of node groups.", 400);
        }
        List<String> named = removeGiven ? toRemove : toRetain;
        for (String id : named) {
            if (!current.contains(id)) {
                throw new AwsException("InvalidParameterValue",
                        "Node group " + id + " does not exist in replication group " + groupId + ".", 400);
            }
        }
        List<String> removed = removeGiven
                ? current.stream().filter(toRemove::contains).toList()
                : current.stream().filter(id -> !toRetain.contains(id)).toList();
        if (current.size() - removed.size() != nodeGroupCount) {
            throw new AwsException("InvalidParameterValue",
                    "NodeGroupCount " + nodeGroupCount + " does not match the " + current.size()
                            + " current node groups less the " + removed.size() + " being removed.", 400);
        }
        return removed;
    }

    private void reshardClusterModeGroup(ReplicationGroup group, List<String> removedIds, int addedShards) {
        String groupId = group.getReplicationGroupId();
        String image = config.services().elasticache().defaultImage();
        String endpointHost = resolveClusterAnnounceHost();
        String announceIp = resolveAnnounceIp(endpointHost);
        List<ClusterNode> existing = group.getClusterNodes();
        for (ClusterNode node : existing) {
            if (node.getContainerHost() == null) {
                throw new AwsException("InvalidReplicationGroupState",
                        "Replication group " + groupId + " has no running node " + node.getMemberClusterId()
                                + " to reshard.", 400);
            }
        }
        List<String> currentIds = nodeGroupIds(group);
        int nextId = currentIds.stream().mapToInt(Integer::parseInt).max().orElse(0) + 1;

        List<ClusterNode> added = new ArrayList<>();
        List<ElastiCacheContainerHandle> addedHandles = new ArrayList<>();
        List<String> startedProxyKeys = new ArrayList<>();
        String inFlightMemberId = null;
        Map<String, String> desiredSlots = new LinkedHashMap<>();
        try {
            for (int shard = 0; shard < addedShards; shard++) {
                String nodeGroupId = String.format("%04d", nextId + shard);
                for (int member = 0; member <= group.getReplicasPerNodeGroup(); member++) {
                    String memberId = groupId + "-" + nodeGroupId + "-" + String.format("%03d", member + 1);
                    added.add(new ClusterNode(memberId, nodeGroupId, member == 0, allocateProxyPort(), null));
                }
            }
            for (ClusterNode node : added) {
                inFlightMemberId = node.getMemberClusterId();
                ElastiCacheContainerHandle handle = containerManager.start(node.getMemberClusterId(), image,
                        clusterNodeFlags(endpointHost, announceIp, node.getProxyPort()));
                inFlightMemberId = null;
                addedHandles.add(handle);
                attachContainer(node, handle);
            }
            // Proxies first, so the reshard is the last step that can fail: once slots have moved
            // to the added nodes, rolling those nodes back would take the slots with them.
            for (ClusterNode node : added) {
                proxyManager.startProxy(node.getMemberClusterId(), group.getAuthMode(), node.getProxyPort(),
                        node.getContainerHost(), node.getContainerPort(),
                        (username, password) -> validatePassword(groupId, username, password));
                startedProxyKeys.add(node.getMemberClusterId());
            }

            List<String> finalIds = new ArrayList<>(currentIds);
            finalIds.removeAll(removedIds);
            added.stream().map(ClusterNode::getNodeGroupId).distinct().forEach(finalIds::add);
            for (int i = 0; i < finalIds.size(); i++) {
                int[] range = ValkeyClusterFormation.slotRange(i, finalIds.size());
                desiredSlots.put(finalIds.get(i), range[0] + "-" + range[1]);
            }
            Map<String, String> currentSlots = new LinkedHashMap<>();
            List<ValkeyClusterFormation.ShardMember> members = new ArrayList<>();
            for (ClusterNode node : existing) {
                if (node.isPrimary()) {
                    currentSlots.put(node.getNodeGroupId(), node.getSlots());
                }
                members.add(new ValkeyClusterFormation.ShardMember(node.getContainerHost(),
                        node.getContainerPort(), networkIpOf(node), node.getNodeGroupId(),
                        node.isPrimary(), false, removedIds.contains(node.getNodeGroupId())));
            }
            for (ClusterNode node : added) {
                members.add(new ValkeyClusterFormation.ShardMember(node.getContainerHost(),
                        node.getContainerPort(), node.getNetworkIp(), node.getNodeGroupId(),
                        node.isPrimary(), true, false));
            }
            clusterFormation.reshard(groupId, members, currentSlots, desiredSlots);
        } catch (RuntimeException e) {
            LOG.warnv("Resharding replication group {0} failed, rolling back the added nodes: {1}",
                    groupId, e.getMessage());
            rollbackClusterModeGroup(groupId, startedProxyKeys, addedHandles, inFlightMemberId,
                    added.stream().map(ClusterNode::getProxyPort).toList());
            throw e;
        }

        List<ClusterNode> remaining = new ArrayList<>();
        for (ClusterNode node : existing) {
            if (removedIds.contains(node.getNodeGroupId())) {
                stopClusterNode(node);
            } else {
                remaining.add(node);
            }
        }
        remaining.addAll(added);
        for (ClusterNode node : remaining) {
            node.setSlots(desiredSlots.get(node.getNodeGroupId()));
        }
        group.setClusterNodes(remaining);
        group.setNumNodeGroups(desiredSlots.size());
        group.setNumCacheClusters(remaining.size());
        group.setProxyPort(remaining.getFirst().getProxyPort());
        String address = group.getConfigurationEndpoint() != null
                ? group.getConfigurationEndpoint().address() : endpointHost;
        group.setConfigurationEndpoint(new Endpoint(address, remaining.getFirst().getProxyPort()));
        LOG.infov("Resharded replication group {0}: {1} node group(s), {2} added, {3} removed",
                groupId, String.valueOf(desiredSlots.size()), String.valueOf(addedShards),
                String.valueOf(removedIds.size()));
    }

    private static String networkIpOf(ClusterNode node) {
        return node.getNetworkIp() != null ? node.getNetworkIp() : node.getContainerHost();
    // ── Cache Clusters (single-node Redis/Valkey) ─────────────────────────────

    /**
     * The CreateCacheCluster parameters floci models for a standalone redis or valkey cluster.
     * AWS accepts that action for redis and valkey as well as memcached, as long as the cluster
     * has exactly one node, and terraform's {@code aws_elasticache_cluster} with
     * {@code engine = "redis"} emits precisely that call: no replication group is involved, so
     * these clusters are stored on their own and never appear in DescribeReplicationGroups.
     */
    public record CreateCacheClusterRequest(
            String cacheClusterId,
            String engine,
            String engineVersion,
            String cacheNodeType,
            Integer numCacheNodes,
            Integer port,
            AuthMode authMode,
            String authToken,
            String cacheParameterGroupName,
            String cacheSubnetGroupName,
            Integer snapshotRetentionLimit,
            String snapshotWindow,
            String preferredMaintenanceWindow,
            String preferredAvailabilityZone,
            List<String> securityGroupIds,
            String networkType,
            String ipDiscovery,
            Boolean atRestEncryptionEnabled,
            String region,
            Map<String, String> tags) {
    }

    public CacheCluster createCacheCluster(CreateCacheClusterRequest request) {
        return createCacheCluster(request, null, null, null);
    }

    CacheCluster createServerlessBacking(CreateCacheClusterRequest request, String cacheName,
                                        String accountId, String userGroupId) {
        return createCacheCluster(request, cacheName, accountId, userGroupId);
    }

    void validateServerlessDependencies(List<String> subnetIds, List<String> securityGroupIds,
                                       String kmsKeyId, String region) {
        String vpc = null;
        if (subnetIds != null && !subnetIds.isEmpty()) {
            List<Subnet> resolved = ec2Service.describeSubnets(region, subnetIds, Map.of());
            if (new HashSet<>(subnetIds).size() != subnetIds.size() || resolved.size() != subnetIds.size()) {
                throw new AwsException("InvalidParameterValue", "SubnetIds contain duplicate or missing subnets.", 400);
            }
            vpc = resolved.getFirst().getVpcId();
            for (Subnet subnet : resolved) {
                if (!vpc.equals(subnet.getVpcId())) {
                    throw new AwsException("InvalidParameterValue", "All subnets must belong to the same VPC.", 400);
                }
            }
        }
        if (securityGroupIds != null && !securityGroupIds.isEmpty()) {
            List<SecurityGroup> resolved = ec2Service.describeSecurityGroups(region, securityGroupIds, List.of(), Map.of());
            if (new HashSet<>(securityGroupIds).size() != securityGroupIds.size() || resolved.size() != securityGroupIds.size()) {
                throw new AwsException("InvalidParameterValue", "SecurityGroupIds contain duplicate or missing groups.", 400);
            }
            if (vpc == null) {
                vpc = resolved.getFirst().getVpcId();
            }
            for (SecurityGroup group : resolved) {
                if (!vpc.equals(group.getVpcId())) {
                    throw new AwsException("InvalidParameterValue", "Security groups and subnets must belong to the same VPC.", 400);
                }
            }
        }
        if (kmsKeyId != null) {
            KmsKey key;
            try {
                key = kmsService.describeKey(kmsKeyId, region);
            } catch (AwsException exception) {
                if ("NotFoundException".equals(exception.getErrorCode())) {
                    throw new AwsException("InvalidParameterValue", "KmsKeyId does not identify a key in this account and region.", 400);
                }
                throw exception;
            }
            if (!key.isEnabled() || key.getKeySpec() != KmsKeySpec.SYMMETRIC_DEFAULT
                    || key.getKeyUsage() != KmsKeyUsage.ENCRYPT_DECRYPT) {
                throw new AwsException("InvalidParameterValue", "KmsKeyId must identify an enabled symmetric encryption key.", 400);
            }
        }
    }

    private CacheCluster createCacheCluster(CreateCacheClusterRequest request, String cacheName,
                                           String accountId, String userGroupId) {
        String clusterId = request.cacheClusterId();
        String engine = normalizeEngine(request.engine());
        if (request.numCacheNodes() != null && request.numCacheNodes() != 1) {
            throw new AwsException("InvalidParameterValue",
                    "NumCacheNodes should be 1 if engine is " + engine, 400);
        }
        // The snapshot members take the replication group's checks, so one request worded one way
        // is refused the same way whichever action carries it.
        cacheClusterSettings(request).validate();
        if (request.preferredMaintenanceWindow() != null && !request.preferredMaintenanceWindow().isBlank()) {
            BackupWindows.parseMaintenanceWindow(request.preferredMaintenanceWindow());
        }
        requireCacheSubnetGroup(subnetGroups, request.cacheSubnetGroupName());
        String parameterGroupReservation = reserveParameterGroup(request.cacheParameterGroupName());
        try {
            if (cacheClusterIdTaken(clusterId)) {
                throw new AwsException("CacheClusterAlreadyExists",
                        "Cache cluster " + clusterId + " already exists.", 400);
            }
            if (!provisioningIds.claim(clusterId)) {
                throw new AwsException("CacheClusterAlreadyExists",
                        "Cache cluster " + clusterId + " is already being created.", 400);
            }
            try {
                return provisionCacheCluster(request, engine, cacheName, accountId, userGroupId);
            } finally {
                provisioningIds.release(clusterId);
            }
        } finally {
            releaseParameterGroup(parameterGroupReservation);
        }
    }

    /**
     * Whether any of the three stores already answers for that id. A Memcached cluster of the
     * same name is only a describe that reports one id twice, but a replication group is worse:
     * see provisioningIds for what its container and proxy would lose.
     */
    private boolean cacheClusterIdTaken(String clusterId) {
        return cacheClusters.get(clusterId).isPresent()
                || groups.get(clusterId).isPresent()
                || memcachedClusters.get(clusterId).isPresent();
    }

    /**
     * A named subnet group must exist. Storing a name nothing resolves would have the describe
     * report a group a caller cannot look up, and AWS refuses the create instead.
     *
     * <p>{@code CreateReplicationGroup} does not make this check, so a replication group can
     * still be created against a subnet group that is not there.
     */
    static void requireCacheSubnetGroup(StorageBackend<String, CacheSubnetGroup> subnetGroups, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (subnetGroups.get(name).isEmpty()) {
            throw new AwsException("CacheSubnetGroupNotFoundFault",
                    "Cache subnet group " + name + " not found.", 400);
        }
    }

    /**
     * The request's snapshot members as the replication group models them, so both actions share
     * one set of checks and one set of defaults.
     */
    private static ReplicationGroupSettings cacheClusterSettings(CreateCacheClusterRequest request) {
        return new ReplicationGroupSettings(request.atRestEncryptionEnabled(), null,
                request.snapshotRetentionLimit(), request.snapshotWindow());
    }

    /**
     * Provisions the cluster exactly as {@link #provisionSingleNodeGroup} provisions a
     * cluster-mode-disabled replication group: the same Valkey container, fronted by the same
     * auth proxy on a port from the same range, so the endpoint a describe reports answers RESP.
     */
    private CacheCluster provisionCacheCluster(CreateCacheClusterRequest request, String engine,
                                              String cacheName, String accountId, String userGroupId) {
        String clusterId = request.cacheClusterId();
        AuthMode authMode = request.authMode() != null ? request.authMode() : AuthMode.NO_AUTH;
        int proxyPort = allocateProxyPort(request.port());
        String image = config.services().elasticache().defaultImage();

        LOG.infov("Creating single-node {0} cache cluster {1} with authMode={2} on proxy port {3}",
                engine, clusterId, authMode, String.valueOf(proxyPort));

        ElastiCacheContainerHandle handle = null;
        try {
            // As for a replication group, the record is metadata: it reaches 'available' even
            // when no Docker daemon is reachable, and only connecting to the cache needs the
            // container.
            handle = containerManager.tryStart(clusterId, image, request.region());

            CacheCluster cluster = new CacheCluster(clusterId, CacheClusterStatus.AVAILABLE, engine,
                    request.engineVersion() != null && !request.engineVersion().isBlank()
                            ? request.engineVersion()
                            : defaultEngineVersion(engine),
                    new Endpoint(resolveEndpointHost(), proxyPort), Instant.now());
            cluster.setNumCacheNodes(1);
            cluster.setCacheNodeType(request.cacheNodeType() != null && !request.cacheNodeType().isBlank()
                    ? request.cacheNodeType()
                    : "cache.t4g.micro");
            cluster.setAuthMode(authMode);
            cluster.setAuthToken(request.authToken());
            cluster.setRegion(request.region());
            cluster.setServerlessCacheName(cacheName);
            cluster.setOwnerAccountId(accountId);
            cluster.setUserGroupId(userGroupId);
            cluster.setArn(regionResolver.buildArn("elasticache", request.region(), "cluster:" + clusterId));
            cluster.setCacheParameterGroupName(request.cacheParameterGroupName());
            cluster.setCacheSubnetGroupName(request.cacheSubnetGroupName());
            applyCacheClusterSettings(cluster, request);
            if (request.tags() != null && !request.tags().isEmpty()) {
                cluster.setTags(new LinkedHashMap<>(request.tags()));
            }
            if (handle != null) {
                cluster.setContainerId(handle.getContainerId());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());
            }

            // Resolved here, inside the request: the proxy calls the validator on its own thread,
            // where the store's implicit account is the default one rather than the owner's.
            String ownerAccountId = cacheClusters.accountId();
            synchronized (lockFor("cc:" + clusterId)) {
                cacheClusters.put(clusterId, cluster);
                if (handle != null) {
                    startCacheClusterProxy(ownerAccountId, cluster, handle);
                } else {
                    LOG.warnv("Cache cluster {0} created without a backing cache container: no "
                            + "Docker daemon is reachable. Metadata operations work; connections to "
                            + "the cache do not until a daemon appears.", clusterId);
                }
                // Under the same monitor as the put: a delete slipping into the gap would find
                // no claim on the port and leave the reservation behind for good.
                recordsHoldingTheirPort.add(recordKey(ownerAccountId, clusterId));
            }

            LOG.infov("Cache cluster {0} created, endpoint={1}:{2}", clusterId,
                    cluster.getConfigurationEndpoint().address(), String.valueOf(proxyPort));
            return cluster;
        } catch (RuntimeException e) {
            LOG.warnv("Cache cluster {0} provisioning failed, rolling back: {1}", clusterId, e.getMessage());
            rollbackCacheCluster(clusterId, handle, proxyPort);
            throw e;
        }
    }

    /**
     * The optional members a request may carry, stored so a describe can echo them. Every one is
     * an optional {@code aws_elasticache_cluster} argument: dropping any of them would read back
     * as unset on the next plan, which is a diff terraform can never settle.
     */
    private void applyCacheClusterSettings(CacheCluster cluster, CreateCacheClusterRequest request) {
        cluster.setSnapshotRetentionLimit(request.snapshotRetentionLimit() != null
                ? request.snapshotRetentionLimit() : 0);
        cluster.setSnapshotWindow(request.snapshotWindow() != null && !request.snapshotWindow().isBlank()
                ? request.snapshotWindow() : ReplicationGroupSettings.DEFAULT_SNAPSHOT_WINDOW);
        cluster.setPreferredMaintenanceWindow(request.preferredMaintenanceWindow() != null
                && !request.preferredMaintenanceWindow().isBlank()
                ? BackupWindows.lowerCase(request.preferredMaintenanceWindow())
                : BackupWindows.DEFAULT_MAINTENANCE_WINDOW);
        cluster.setPreferredAvailabilityZone(request.preferredAvailabilityZone() != null
                && !request.preferredAvailabilityZone().isBlank()
                ? request.preferredAvailabilityZone()
                : regionResolver.getRegion() + "a");
        cluster.setNetworkType(request.networkType() != null && !request.networkType().isBlank()
                ? request.networkType() : "ipv4");
        cluster.setIpDiscovery(request.ipDiscovery() != null && !request.ipDiscovery().isBlank()
                ? request.ipDiscovery() : "ipv4");
        cluster.setAtRestEncryptionEnabled(Boolean.TRUE.equals(request.atRestEncryptionEnabled()));
        if (request.securityGroupIds() != null && !request.securityGroupIds().isEmpty()) {
            cluster.setSecurityGroupIds(new ArrayList<>(request.securityGroupIds()));
        }
    }

    private void rollbackCacheCluster(String clusterId, ElastiCacheContainerHandle handle, int proxyPort) {
        try {
            if (handle != null) {
                proxyManager.stopProxy(clusterId);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Error stopping proxy for cache cluster {0}: {1}", clusterId, e.getMessage());
        }
        try {
            if (handle != null) {
                containerManager.stop(handle);
            } else {
                // No handle: a readiness timeout throws before start() can return one.
                containerManager.stopByGroupId(clusterId);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Error stopping container for cache cluster {0}: {1}", clusterId, e.getMessage());
        } finally {
            cacheClusters.delete(clusterId);
            recordsHoldingTheirPort.remove(recordKey(cacheClusters.accountId(), clusterId));
            releaseProxyPort(proxyPort);
        }
    }

    /**
     * The standalone cluster by that id, or all of them. Reports no match as an empty list rather
     * than a fault: DescribeCacheClusters answers from three sources, and only the last one to
     * find nothing can say the id does not exist.
     */
    public List<CacheCluster> findCacheClusters(String filterClusterId) {
        if (filterClusterId != null && !filterClusterId.isBlank()) {
            return cacheClusters.get(filterClusterId).filter(cluster -> cluster.getServerlessCacheName() == null)
                    .map(List::of).orElseGet(List::of);
        }
        return cacheClusters.scan(k -> true).stream().filter(cluster -> cluster.getServerlessCacheName() == null).toList();
    }

    public CacheCluster deleteCacheCluster(String clusterId) {
        return deleteCacheCluster(clusterId, false);
    }

    CacheCluster deleteServerlessBacking(String clusterId) {
        return deleteCacheCluster(clusterId, true);
    }

    private CacheCluster deleteCacheCluster(String clusterId, boolean internal) {
        synchronized (lockFor("cc:" + clusterId)) {
            CacheCluster cluster = cacheClusters.get(clusterId)
                    .filter(record -> internal || record.getServerlessCacheName() == null).orElseThrow(() ->
                    new AwsException("CacheClusterNotFound",
                            "Cache cluster " + clusterId + " not found.", 404));

            cluster.setCacheClusterStatus(CacheClusterStatus.DELETING);
            cacheClusters.put(clusterId, cluster);

            proxyManager.stopProxy(clusterId);
            if (cluster.getContainerId() != null) {
                containerManager.stop(new ElastiCacheContainerHandle(cluster.getContainerId(), clusterId,
                        cluster.getContainerHost(), cluster.getContainerPort()));
            } else {
                // Transient container fields are lost across a Floci restart; the deterministic
                // container name still finds the cluster's container.
                containerManager.stopByGroupId(clusterId);
            }
            // Only a port this process reserved for this record: a restored cluster whose port
            // was already taken advertises one it does not own, and freeing it would hand the
            // holder's port to the next create.
            if (cluster.getConfigurationEndpoint() != null
                    && recordsHoldingTheirPort.remove(recordKey(cacheClusters.accountId(), clusterId))) {
                releaseProxyPort(cluster.getConfigurationEndpoint().port());
            }

            cacheClusters.delete(clusterId);
            LOG.infov("Cache cluster {0} deleted", clusterId);
            return cluster;
        }
    }

    /**
     * A standalone cluster's AUTH token, checked the same way {@link #validatePassword} checks a
     * replication group's: only the single-argument AUTH form, since a cluster created this way
     * carries no user list.
     */
    public boolean validateCacheClusterPassword(String clusterId, String username, String password) {
        return validateCacheClusterPassword(cacheClusters.accountId(), clusterId, username, password);
    }

    /**
     * The account-explicit form the proxy calls: it authenticates on its own thread, outside any
     * request, where the store's implicit account is the default one rather than the owner's.
     */
    private boolean validateCacheClusterPassword(String accountId, String clusterId, String username,
                                                 String password) {
        CacheCluster cluster = cacheClusters.getForAccount(accountId, clusterId).orElse(null);
        if (cluster == null || cluster.getAuthToken() == null) {
            return false;
        }
        return (username == null || username.isEmpty()) && cluster.getAuthToken().equals(password);
    }

    private void putRuntimeCluster(CacheCluster cluster) {
        if (cluster.getOwnerAccountId() == null) {
            cacheClusters.put(cluster.getCacheClusterId(), cluster);
        } else {
            cacheClusters.putForAccount(cluster.getOwnerAccountId(), cluster.getCacheClusterId(), cluster);
        }
    }

    CacheCluster getServerlessBacking(String id, String accountId) {
        return cacheClusters.getForAccount(accountId, id)
                .filter(cluster -> cluster.getServerlessCacheName() != null)
                .orElseThrow(() -> new AwsException("CacheClusterNotFound", "Serverless backing cache not found.", 404));
    }

    void updateServerlessUserGroup(String id, String accountId, String groupId) {
        synchronized (lockFor("cc:" + id)) {
            CacheCluster cluster = getServerlessBacking(id, accountId);
            cluster.setUserGroupId(groupId);
            putRuntimeCluster(cluster);
        }
    }

    private void startCacheClusterProxy(String accountId, CacheCluster cluster, ElastiCacheContainerHandle handle) {
        if (cluster.getServerlessCacheName() == null) {
            proxyManager.startProxy(cluster.getCacheClusterId(), cluster.getAuthMode(),
                    cluster.getConfigurationEndpoint().port(), handle.getHost(), handle.getPort(),
                    (username, password) -> validateCacheClusterPassword(
                            accountId, cluster.getCacheClusterId(), username, password));
        } else {
            proxyManager.startProxy(cluster.getCacheClusterId(), AuthMode.NO_AUTH,
                    cluster.getConfigurationEndpoint().port(), handle.getHost(), handle.getPort(),
                    serverlessAuthenticator(cluster), cluster.getServerlessCacheName());
        }
    }

    private ElastiCacheAuthProxy.PasswordValidator serverlessAuthenticator(CacheCluster original) {
        String id = original.getCacheClusterId();
        String account = original.getOwnerAccountId();
        return new ElastiCacheAuthProxy.PasswordValidator() {
            private ElastiCacheUser member(String username) {
                String name = username == null || username.isEmpty() ? "default" : username;
                synchronized (users) {
                    CacheCluster cluster = cacheClusters.getForAccount(account, id).orElse(null);
                    if (cluster == null || cluster.getUserGroupId() == null) {
                        return null;
                    }
                    ElastiCacheUserGroup group = userGroups.getForAccount(account, normalizeUserGroupId(cluster.getUserGroupId())).orElse(null);
                    if (group == null) {
                        return null;
                    }
                    return group.getUserIds().stream().map(userId -> users.getForAccount(account, userId).orElse(null))
                            .filter(user -> user != null && name.equals(user.getUserName()) && user.isEnabled())
                            .findFirst().orElse(null);
                }
            }

            @Override
            public boolean validatePassword(String username, String password) {
                ElastiCacheUser user = member(username);
                return user != null && (user.getAuthMode() == AuthMode.NO_AUTH
                        || user.getAuthMode() == AuthMode.PASSWORD && user.getPasswords().contains(password));
            }

            @Override
            public boolean hasMembers() {
                return cacheClusters.getForAccount(account, id).map(cluster -> cluster.getUserGroupId() != null)
                        .orElse(true);
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                ElastiCacheUser user = member(username);
                return user == null ? null : user.getAuthMode();
            }

            @Override
            public String ownerAccountId() {
                return account;
            }
        };
    }

    /**
     * A replication-group member viewed as the cache cluster AWS reports it as. The terraform
     * provider follows {@code MemberClusters} into DescribeCacheClusters, so every member name
     * a describe hands out must answer there.
     */
    public record MemberCacheCluster(ReplicationGroup group, String cacheClusterId,
                                     int port, boolean primary) {}

    public List<MemberCacheCluster> listMemberCacheClusters(String filterClusterId) {
        List<MemberCacheCluster> members = new ArrayList<>();
        for (ReplicationGroup group : groups.scan(k -> true)) {
            for (MemberCacheCluster member : memberCacheClusters(group)) {
                if (filterClusterId == null || filterClusterId.isBlank()
                        || filterClusterId.equals(member.cacheClusterId())) {
                    members.add(member);
                }
            }
        }
        return members;
    }

    public List<MemberCacheCluster> memberCacheClusters(ReplicationGroup group) {
        List<MemberCacheCluster> members = new ArrayList<>();
        if (group.isClusterEnabled() && !group.getClusterNodes().isEmpty()) {
            for (ClusterNode node : group.getClusterNodes()) {
                members.add(new MemberCacheCluster(group, node.getMemberClusterId(),
                        node.getProxyPort(), node.isPrimary()));
            }
            return members;
        }
        int port = group.getConfigurationEndpoint() != null
                ? group.getConfigurationEndpoint().port() : group.getProxyPort();
        for (int shard = 1; shard <= group.getNumNodeGroups(); shard++) {
            for (int i = 1; i <= group.getNumCacheClusters(); i++) {
                String suffix = group.getNumNodeGroups() == 1 ? String.format("-%03d", i)
                        : String.format("-%04d-%03d", shard, i);
                members.add(new MemberCacheCluster(group,
                        group.getReplicationGroupId() + suffix, port, i == 1));
            }
        }
        return members;
    }

    public ReplicationGroup modifyReplicationGroup(String groupId, List<String> userGroupIdsToAdd,
                                                    List<String> userGroupIdsToRemove) {
        return modifyReplicationGroup(groupId, userGroupIdsToAdd, userGroupIdsToRemove,
                ReplicationGroupSettings.unchanged());
    }

    /**
     * Each id in {@code userGroupIdsToAdd} names a user group. An id that names no user group but
     * does name a user associates that user directly, which is how Floci accepted the list before
     * it modelled user groups; AWS itself only takes user group ids here.
     */
    public ReplicationGroup modifyReplicationGroup(String groupId, List<String> userGroupIdsToAdd,
                                                    List<String> userGroupIdsToRemove,
                                                    ReplicationGroupSettings settings) {
        return modifyReplicationGroup(groupId, userGroupIdsToAdd, userGroupIdsToRemove, false, false, settings);
    }

    /**
     * {@code removeUserGroups} drops every user group the cache had before this request, and
     * {@code deleteAuthToken} is AuthTokenUpdateStrategy DELETE, which AWS allows only when the
     * cache moves to user groups. A cache whose last user group goes has no access control, as
     * on AWS, unless it kept an auth token by joining user groups without the DELETE.
     */
    public ReplicationGroup modifyReplicationGroup(String groupId, List<String> userGroupIdsToAdd,
                                                    List<String> userGroupIdsToRemove,
                                                    boolean removeUserGroups, boolean deleteAuthToken,
                                                    ReplicationGroupSettings settings) {
        settings.validate();
        synchronized (lockFor("rg:" + groupId)) {
            ReplicationGroup group = getReplicationGroup(groupId);
            boolean encryptedInTransit = settings.transitEncryptionEnabled() != null
                    ? settings.transitEncryptionEnabled() : group.isEncryptedInTransit();
            settings.validateTransitEncryption(encryptedInTransit);
            if (!group.isEncryptedInTransit() && encryptedInTransit
                    && !"preferred".equals(settings.transitEncryptionMode())) {
                throw new AwsException("InvalidParameterCombination",
                        "TransitEncryptionMode must be preferred when enabling transit encryption.", 400);
            }
            if (group.isEncryptedInTransit() && !encryptedInTransit) {
                throw new AwsException("InvalidParameterCombination", "Transit encryption cannot be disabled.", 400);
            }
            // every check before any change: the store hands out its own object, so a mutation
            // made before a later refusal would stay visible
            synchronized (userGroupLock) {
                Set<String> userGroupsToAdd = new LinkedHashSet<>();
                Set<String> directUsersToAdd = new LinkedHashSet<>();
                if (userGroupIdsToAdd != null) {
                    for (String id : userGroupIdsToAdd) {
                        Optional<ElastiCacheUserGroup> userGroup = userGroups.get(normalizeUserGroupId(id));
                        if (userGroup.isPresent()) {
                            requireAssociable(userGroup.get(), encryptedInTransit, group.getEngine());
                            userGroupsToAdd.add(userGroup.get().getUserGroupId());
                        } else if (users.get(id).isPresent()) {
                            directUsersToAdd.add(id);
                        } else {
                            throw userGroupNotFound(id);
                        }
                    }
                }

                Set<String> nextUserGroupIds = new LinkedHashSet<>(removeUserGroups ? Set.of() : group.getUserGroupIds());
                nextUserGroupIds.addAll(userGroupsToAdd);
                if (userGroupIdsToRemove != null) {
                    userGroupIdsToRemove.forEach(id -> nextUserGroupIds.remove(normalizeUserGroupId(id)));
                }
                if (deleteAuthToken && nextUserGroupIds.isEmpty()) {
                    throw new AwsException("InvalidParameterCombination",
                            "AuthTokenUpdateStrategy DELETE is allowed only when transitioning to RBAC: "
                                    + "add a user group in the same request.", 400);
                }

                Set<String> nextUserIds = new HashSet<>(group.getAssociatedUserIds());
                nextUserIds.addAll(directUsersToAdd);
                if (userGroupIdsToRemove != null) {
                    nextUserIds.removeAll(userGroupIdsToRemove);
                }
                Set<String> seenUserNames = new HashSet<>();
                for (String userId : nextUserIds) {
                    ElastiCacheUser u = users.get(userId).orElse(null);
                    if (u != null && !seenUserNames.add(u.getUserName())) {
                        throw new AwsException("DuplicateUserName",
                                "Duplicate user name " + u.getUserName() + " in user group.", 400);
                    }
                }

                boolean hadUserGroups = !group.getUserGroupIds().isEmpty();
                settings.applyTo(group);
                group.setUserGroupIds(nextUserGroupIds);
                group.getAssociatedUserIds().addAll(directUsersToAdd);
                if (userGroupIdsToRemove != null) {
                    group.getAssociatedUserIds().removeAll(userGroupIdsToRemove);
                }
                if (deleteAuthToken) {
                    group.setAuthToken(null);
                }
                if (hadUserGroups && nextUserGroupIds.isEmpty()) {
                    group.setTransitEncryptionEnabled(group.isEncryptedInTransit());
                    group.setAuthMode(group.getAuthToken() != null ? AuthMode.PASSWORD : AuthMode.NO_AUTH);
                }

                groups.put(groupId, group);
                return group;
            }
        }
    }

    /**
     * A live account takes the key as a key id, key ARN or alias and reports the key ARN on the
     * group; a key it cannot use is one fault whatever the reason.
     */
    private String resolveKmsKeyArn(String kmsKeyId, String region) {
        if (kmsKeyId == null || kmsKeyId.isBlank()) {
            return null;
        }
        if (kmsService == null) {
            throw new IllegalStateException("ElastiCacheService was built without a KmsService; "
                    + "a KmsKeyId cannot be resolved");
        }
        KmsKey key;
        try {
            key = kmsService.describeKey(kmsKeyId, region);
        } catch (AwsException e) {
            throw kmsKeyNotAccessible(kmsKeyId);
        }
        if (!key.isEnabled() || "PendingDeletion".equals(key.getKeyState())) {
            throw kmsKeyNotAccessible(kmsKeyId);
        }
        return key.getArn();
    }

    private static AwsException kmsKeyNotAccessible(String kmsKeyId) {
        return new AwsException("InvalidParameterValue",
                "KMS key does not exist with key id: " + kmsKeyId, 400);
    }

    public ReplicationGroup saveReplicationGroup(ReplicationGroup group) {
        synchronized (lockFor("rg:" + group.getReplicationGroupId())) {
            getReplicationGroup(group.getReplicationGroupId());
            groups.put(group.getReplicationGroupId(), group);
            return group;
        }
    }

    public ReplicationGroup setReplicaCount(String groupId, int replicaCount) {
        ReplicationGroup group = getReplicationGroup(groupId);
        group.setReplicasPerNodeGroup(Math.max(0, replicaCount));
        if (!group.isClusterEnabled()) {
            group.setNumCacheClusters(group.getReplicasPerNodeGroup() + 1);
        }
        return saveReplicationGroup(group);
    }

    public ReplicationGroup setNodeGroupCount(String groupId, int nodeGroupCount) {
        if (nodeGroupCount < 1) {
            throw new AwsException("InvalidParameterValue", "NodeGroupCount must be at least 1.", 400);
        }
        ReplicationGroup group = getReplicationGroup(groupId);
        group.setNodeGroupCount(nodeGroupCount);
        return saveReplicationGroup(group);
    }

    public CacheSubnetGroup createSubnetGroup(String name, String description, List<String> subnetIds,
                                               Map<String, String> tags) {
        return createCacheSubnetGroup(name, description, subnetIds, tags);
    }

    public CacheSubnetGroup getSubnetGroup(String name) {
        return describeCacheSubnetGroups(name).getFirst();
    }

    public Collection<CacheSubnetGroup> listSubnetGroups(String name) {
        return describeCacheSubnetGroups(name);
    }

    public CacheSubnetGroup modifySubnetGroup(String name, String description, List<String> subnetIds) {
        return modifyCacheSubnetGroup(name, description, subnetIds);
    }

    public void deleteSubnetGroup(String name) {
        deleteCacheSubnetGroup(name);
    }

    public CacheSnapshot createSnapshot(String snapshotName, String groupId) {
        getReplicationGroup(groupId);
        if (snapshots.get(snapshotName).isPresent()) {
            throw new AwsException("SnapshotAlreadyExistsFault", "Snapshot " + snapshotName + " already exists.", 400);
        }
        CacheSnapshot snapshot = new CacheSnapshot(snapshotName, groupId);
        snapshots.put(snapshotName, snapshot);
        return snapshot;
    }

    public Collection<CacheSnapshot> listSnapshots(String snapshotName) {
        if (snapshotName != null && !snapshotName.isBlank()) {
            return snapshots.get(snapshotName)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("SnapshotNotFoundFault",
                            "Snapshot " + snapshotName + " not found.", 404));
        }
        return snapshots.scan(key -> true);
    }

    public CacheSnapshot deleteSnapshot(String snapshotName) {
        CacheSnapshot snapshot = snapshots.get(snapshotName).orElseThrow(() ->
                new AwsException("SnapshotNotFoundFault", "Snapshot " + snapshotName + " not found.", 404));
        snapshots.delete(snapshotName);
        return snapshot;
    }

    public ElastiCacheUser createUser(String userId, String userName, AuthMode authMode,
                                      List<String> passwords, String accessString, String engine) {
        return createUser(userId, userName, authMode, passwords, accessString, engine, Map.of());
    }

    public ElastiCacheUser createUser(String userId, String userName, AuthMode authMode,
                                      List<String> passwords, String accessString, String engine,
                                      Map<String, String> tags) {
        if (users.get(userId).isPresent()) {
            throw new AwsException("UserAlreadyExists",
                    "User " + userId + " already exists.", 400);
        }

        // Engine is required on AWS, but Floci's CreateUser accepted requests without
        // it, so a missing value keeps the previous implicit redis.
        String normalizedEngine = (engine == null || engine.isBlank()) ? "redis" : normalizeEngine(engine);
        ElastiCacheUser user = new ElastiCacheUser(
                userId, userName, authMode,
                passwords != null ? passwords : List.of(),
                accessString != null ? accessString : "on ~* +@all",
                normalizedEngine, "active", Instant.now());
        user.setArn(regionResolver.buildArn("elasticache", regionResolver.getRegion(), "user:" + userId));
        user.setTags(tags);

        users.put(userId, user);
        LOG.infov("ElastiCache user {0} created with authMode={1}", userId, authMode);
        return user;
    }

    public Map<String, String> addTagsToResource(String resourceArn, Map<String, String> tags,
                                                  String region) {
        TagResourceRef ref = ElastiCacheTagResourceArn.parse(resourceArn, region, regionResolver);
        return switch (ref.type()) {
            case "user" -> updateUserTags(ref, tags);
            case "usergroup" -> updateUserGroupTags(ref, tags);
            case "replicationgroup" -> updateReplicationGroupTags(ref, tags);
            case "cluster" -> updateCacheClusterTags(ref, tags);
            case "subnetgroup" -> updateCacheSubnetGroupTags(ref, tags);
            case "parametergroup" -> updateCacheParameterGroupTags(ref, tags);
            default -> throw ElastiCacheTagResourceArn.invalid(resourceArn);
        };
    }

    public Map<String, String> removeTagsFromResource(String resourceArn, List<String> tagKeys,
                                                       String region) {
        TagResourceRef ref = ElastiCacheTagResourceArn.parse(resourceArn, region, regionResolver);
        return switch (ref.type()) {
            case "user" -> removeUserTags(ref, tagKeys);
            case "usergroup" -> removeUserGroupTags(ref, tagKeys);
            case "replicationgroup" -> removeReplicationGroupTags(ref, tagKeys);
            case "cluster" -> removeCacheClusterTags(ref, tagKeys);
            case "subnetgroup" -> removeCacheSubnetGroupTags(ref, tagKeys);
            case "parametergroup" -> removeCacheParameterGroupTags(ref, tagKeys);
            default -> throw ElastiCacheTagResourceArn.invalid(resourceArn);
        };
    }

    private Map<String, String> updateUserTags(TagResourceRef ref, Map<String, String> tags) {
        synchronized (userGroupLock) {
            ElastiCacheUser user = getUser(ref.id());
            requireResourceArn(user.getArn(), ref.resourceArn(), "UserNotFound", ref.id());
            user.getTags().putAll(tags);
            users.put(ref.id(), user);
            return new LinkedHashMap<>(user.getTags());
        }
    }

    private Map<String, String> removeUserTags(TagResourceRef ref, List<String> tagKeys) {
        synchronized (userGroupLock) {
            ElastiCacheUser user = getUser(ref.id());
            requireResourceArn(user.getArn(), ref.resourceArn(), "UserNotFound", ref.id());
            tagKeys.forEach(user.getTags()::remove);
            users.put(ref.id(), user);
            return new LinkedHashMap<>(user.getTags());
        }
    }

    private Map<String, String> updateUserGroupTags(TagResourceRef ref, Map<String, String> tags) {
        synchronized (userGroupLock) {
            ElastiCacheUserGroup userGroup = getUserGroup(ref.id());
            requireResourceArn(userGroup.getArn(), ref.resourceArn(), "UserGroupNotFound", ref.id());
            userGroup.getTags().putAll(tags);
            userGroups.put(userGroup.getUserGroupId(), userGroup);
            return new LinkedHashMap<>(userGroup.getTags());
        }
    }

    private Map<String, String> removeUserGroupTags(TagResourceRef ref, List<String> tagKeys) {
        synchronized (userGroupLock) {
            ElastiCacheUserGroup userGroup = getUserGroup(ref.id());
            requireResourceArn(userGroup.getArn(), ref.resourceArn(), "UserGroupNotFound", ref.id());
            tagKeys.forEach(userGroup.getTags()::remove);
            userGroups.put(userGroup.getUserGroupId(), userGroup);
            return new LinkedHashMap<>(userGroup.getTags());
        }
    }

    private Map<String, String> updateReplicationGroupTags(TagResourceRef ref, Map<String, String> tags) {
        synchronized (lockFor("rg:" + ref.id())) {
            ReplicationGroup group = getReplicationGroup(ref.id());
            requireResourceArn(group.getArn(), ref.resourceArn(), "ReplicationGroupNotFoundFault", ref.id());
            group.getTags().putAll(tags);
            groups.put(ref.id(), group);
            return new LinkedHashMap<>(group.getTags());
        }
    }

    private Map<String, String> removeReplicationGroupTags(TagResourceRef ref, List<String> tagKeys) {
        synchronized (lockFor("rg:" + ref.id())) {
            ReplicationGroup group = getReplicationGroup(ref.id());
            requireResourceArn(group.getArn(), ref.resourceArn(), "ReplicationGroupNotFoundFault", ref.id());
            tagKeys.forEach(group.getTags()::remove);
            groups.put(ref.id(), group);
            return new LinkedHashMap<>(group.getTags());
        }
    }

    private Map<String, String> updateCacheClusterTags(TagResourceRef ref, Map<String, String> tags) {
        synchronized (lockFor("cc:" + ref.id())) {
            StorageBackend<String, CacheCluster> store = cacheClusters.get(ref.id()).isPresent()
                    ? cacheClusters : memcachedClusters;
            CacheCluster cluster = store.get(ref.id())
                    .filter(value -> value.getServerlessCacheName() == null)
                    .orElseThrow(() -> new AwsException("CacheClusterNotFound",
                            "Cache cluster " + ref.id() + " not found.", 404));
            requireResourceArn(cluster.getArn(), ref.resourceArn(), "CacheClusterNotFound", ref.id());
            cluster.getTags().putAll(tags);
            store.put(ref.id(), cluster);
            return new LinkedHashMap<>(cluster.getTags());
        }
    }

    private Map<String, String> removeCacheClusterTags(TagResourceRef ref, List<String> tagKeys) {
        synchronized (lockFor("cc:" + ref.id())) {
            StorageBackend<String, CacheCluster> store = cacheClusters.get(ref.id()).isPresent()
                    ? cacheClusters : memcachedClusters;
            CacheCluster cluster = store.get(ref.id())
                    .filter(value -> value.getServerlessCacheName() == null)
                    .orElseThrow(() -> new AwsException("CacheClusterNotFound",
                            "Cache cluster " + ref.id() + " not found.", 404));
            requireResourceArn(cluster.getArn(), ref.resourceArn(), "CacheClusterNotFound", ref.id());
            tagKeys.forEach(cluster.getTags()::remove);
            store.put(ref.id(), cluster);
            return new LinkedHashMap<>(cluster.getTags());
        }
    }

    private Map<String, String> updateCacheSubnetGroupTags(TagResourceRef ref, Map<String, String> tags) {
        synchronized (lockFor("sng:" + ref.id())) {
            CacheSubnetGroup group = subnetGroups.get(ref.id()).orElseThrow(() ->
                    new AwsException("CacheSubnetGroupNotFoundFault", "Cache subnet group " + ref.id() + " not found.", 400));
            requireResourceArn(regionResolver.buildArn("elasticache", regionResolver.getRegion(),
                    "subnetgroup:" + ref.id()).toString(), ref.resourceArn(),
                    "CacheSubnetGroupNotFoundFault", ref.id(), 400);
            group.getTags().putAll(tags);
            subnetGroups.put(ref.id(), group);
            return new LinkedHashMap<>(group.getTags());
        }
    }

    private Map<String, String> removeCacheSubnetGroupTags(TagResourceRef ref, List<String> tagKeys) {
        synchronized (lockFor("sng:" + ref.id())) {
            CacheSubnetGroup group = subnetGroups.get(ref.id()).orElseThrow(() ->
                    new AwsException("CacheSubnetGroupNotFoundFault", "Cache subnet group " + ref.id() + " not found.", 400));
            requireResourceArn(regionResolver.buildArn("elasticache", regionResolver.getRegion(),
                    "subnetgroup:" + ref.id()).toString(), ref.resourceArn(),
                    "CacheSubnetGroupNotFoundFault", ref.id(), 400);
            tagKeys.forEach(group.getTags()::remove);
            subnetGroups.put(ref.id(), group);
            return new LinkedHashMap<>(group.getTags());
        }
    }

    private Map<String, String> updateCacheParameterGroupTags(TagResourceRef ref, Map<String, String> tags) {
        synchronized (lockFor(ref.id())) {
            CacheParameterGroup group = parameterGroups.get(ref.id()).orElseThrow(() ->
                    new AwsException("CacheParameterGroupNotFound", "Cache parameter group " + ref.id() + " not found.", 404));
            requireResourceArn(regionResolver.buildArn("elasticache", regionResolver.getRegion(),
                    "parametergroup:" + ref.id()).toString(), ref.resourceArn(),
                    "CacheParameterGroupNotFound", ref.id());
            group.getTags().putAll(tags);
            parameterGroups.put(ref.id(), group);
            return new LinkedHashMap<>(group.getTags());
        }
    }

    private Map<String, String> removeCacheParameterGroupTags(TagResourceRef ref, List<String> tagKeys) {
        synchronized (lockFor(ref.id())) {
            CacheParameterGroup group = parameterGroups.get(ref.id()).orElseThrow(() ->
                    new AwsException("CacheParameterGroupNotFound", "Cache parameter group " + ref.id() + " not found.", 404));
            requireResourceArn(regionResolver.buildArn("elasticache", regionResolver.getRegion(),
                    "parametergroup:" + ref.id()).toString(), ref.resourceArn(),
                    "CacheParameterGroupNotFound", ref.id());
            tagKeys.forEach(group.getTags()::remove);
            parameterGroups.put(ref.id(), group);
            return new LinkedHashMap<>(group.getTags());
        }
    }

    private static void requireResourceArn(String actualArn, String requestedArn, String errorCode, String id) {
        requireResourceArn(actualArn, requestedArn, errorCode, id, 404);
    }

    private static void requireResourceArn(String actualArn, String requestedArn, String errorCode, String id,
                                           int httpStatus) {
        if (actualArn == null || !actualArn.equalsIgnoreCase(requestedArn)) {
            throw new AwsException(errorCode, "Resource " + id + " not found.", httpStatus);
        }
    }

    public ElastiCacheUser getUser(String userId) {
        return users.get(userId).orElseThrow(() ->
                new AwsException("UserNotFound", "User " + userId + " not found.", 404));
    }

    public Collection<ElastiCacheUser> listUsers(String filterUserId, String filterEngine) {
        // UserId wins when both filters are sent; AWS documents no interaction between them.
        if (filterUserId != null && !filterUserId.isBlank()) {
            return users.get(filterUserId)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("UserNotFound",
                            "User " + filterUserId + " not found.", 404));
        }
        if (filterEngine != null && !filterEngine.isBlank()) {
            return users.scan(k -> true).stream()
                    .filter(u -> filterEngine.equalsIgnoreCase(u.getEngine()))
                    .toList();
        }
        return users.scan(k -> true);
    }

    /**
     * Applies a ModifyUser request. A non-null {@code authMode} switches the user's
     * authentication and replaces its passwords with {@code passwords}; {@code accessString}
     * replaces the access string and {@code appendAccessString} adds to it.
     */
    public ElastiCacheUser modifyUser(String userId, AuthMode authMode, List<String> passwords,
                                      String accessString, String appendAccessString, String engine) {
        ElastiCacheUser user = getUser(userId);
        // Storage backends hand back the live stored object, so validate everything
        // before the first setter — a rejected request must not leave changes behind.
        String normalizedEngine = (engine == null || engine.isBlank()) ? null : normalizeEngine(engine);
        synchronized (userGroupLock) {
            String nextEngine = normalizedEngine != null ? normalizedEngine : user.getEngine();
            AuthMode nextAuthMode = authMode != null ? authMode : user.getAuthMode();
            if (!nextEngine.equals(user.getEngine()) || nextAuthMode != user.getAuthMode()) {
                // the user must still satisfy every user group that holds it
                for (ElastiCacheUserGroup userGroup : userGroups.scan(k -> true)) {
                    if (userGroup.getUserIds().contains(userId)) {
                        requireMemberAllowed(userGroup.getEngine(), userId, nextEngine, nextAuthMode);
                    }
                }
            }
            if (authMode != null) {
                user.setAuthMode(authMode);
                user.setPasswords(passwords != null ? passwords : List.of());
            }
            if (accessString != null) {
                user.setAccessString(accessString);
            } else if (appendAccessString != null) {
                String current = user.getAccessString();
                user.setAccessString(current == null || current.isBlank()
                        ? appendAccessString
                        : current + " " + appendAccessString);
            }
            if (normalizedEngine != null) {
                user.setEngine(normalizedEngine);
            }
            users.put(userId, user);
            return user;
        }
    }

    public void deleteUser(String userId) {
        if (users.get(userId).isEmpty()) {
            throw new AwsException("UserNotFound", "User " + userId + " not found.", 404);
        }
        synchronized (userGroupLock) {
            users.delete(userId);
            // AWS removes a deleted user from every user group it belongs to
            for (ElastiCacheUserGroup userGroup : userGroups.scan(k -> true)) {
                if (userGroup.getUserIds().remove(userId)) {
                    userGroups.put(userGroup.getUserGroupId(), userGroup);
                }
            }
        }
        for (ReplicationGroup group : groups.scan(k -> true)) {
            synchronized (lockFor("rg:" + group.getReplicationGroupId())) {
                if (group.getAssociatedUserIds().remove(userId)) {
                    groups.put(group.getReplicationGroupId(), group);
                }
            }
        }
        LOG.infov("ElastiCache user {0} deleted", userId);
    }

    // ── User groups ───────────────────────────────────────────────────────────

    public ElastiCacheUserGroup createUserGroup(String userGroupId, String engine, List<String> userIds,
                                                String region) {
        return createUserGroup(userGroupId, engine, userIds, region, Map.of());
    }

    public ElastiCacheUserGroup createUserGroup(String userGroupId, String engine, List<String> userIds,
                                                String region, Map<String, String> tags) {
        String id = normalizeUserGroupId(userGroupId);
        // Engine is required on AWS; a missing value defaults to redis as CreateUser does.
        String normalizedEngine = (engine == null || engine.isBlank()) ? "redis" : normalizeEngine(engine);
        Set<String> members = new LinkedHashSet<>(userIds != null ? userIds : List.of());
        synchronized (userGroupLock) {
            validateUserGroupMembers(normalizedEngine, members);
            if (userGroups.get(id).isPresent()) {
                throw new AwsException("UserGroupAlreadyExists",
                        "User group " + id + " already exists.", 400);
            }
            ElastiCacheUserGroup userGroup = new ElastiCacheUserGroup(
                    id, normalizedEngine, members, "active", Instant.now());
            userGroup.setArn(regionResolver.buildArn("elasticache", region, "usergroup:" + id));
            userGroup.setTags(tags);
            userGroups.put(id, userGroup);
            LOG.infov("ElastiCache user group {0} created with {1} users", id, members.size());
            return userGroup;
        }
    }

    public ElastiCacheUserGroup getUserGroup(String userGroupId) {
        return userGroups.get(normalizeUserGroupId(userGroupId)).orElseThrow(() -> userGroupNotFound(userGroupId));
    }

    public Collection<ElastiCacheUserGroup> listUserGroups(String filterUserGroupId) {
        if (filterUserGroupId != null && !filterUserGroupId.isBlank()) {
            return List.of(getUserGroup(filterUserGroupId));
        }
        return userGroups.scan(k -> true);
    }

    public ElastiCacheUserGroup modifyUserGroup(String userGroupId, List<String> userIdsToAdd,
                                                List<String> userIdsToRemove, String engine) {
        return updateUserGroup(userGroupId, engine, current -> {
            Set<String> members = new LinkedHashSet<>(current);
            if (userIdsToAdd != null) {
                members.addAll(userIdsToAdd);
            }
            if (userIdsToRemove != null) {
                members.removeAll(userIdsToRemove);
            }
            return members;
        });
    }

    public ElastiCacheUserGroup setUserGroupMembers(String userGroupId, List<String> userIds, String engine) {
        return updateUserGroup(userGroupId, engine,
                current -> userIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(userIds));
    }

    private ElastiCacheUserGroup updateUserGroup(String userGroupId, String engine,
                                                 UnaryOperator<Set<String>> membership) {
        String id = normalizeUserGroupId(userGroupId);
        synchronized (userGroupLock) {
            ElastiCacheUserGroup userGroup = getUserGroup(id);
            // Validate the resulting group before changing the stored object.
            String newEngine = (engine == null || engine.isBlank()) ? userGroup.getEngine() : normalizeEngine(engine);
            Set<String> members = membership.apply(userGroup.getUserIds());
            validateUserGroupMembers(newEngine, members);
            if (!newEngine.equals(userGroup.getEngine())) {
                for (ReplicationGroup group : replicationGroupsUsing(id)) {
                    requireEngineCompatible(newEngine, id, group.getEngine());
                }
                for (String cacheEngine : userGroup.getServerlessCacheEngines().values()) {
                    requireEngineCompatible(newEngine, id, cacheEngine);
                }
            }
            userGroup.setEngine(newEngine);
            userGroup.setUserIds(members);
            userGroups.put(id, userGroup);
            return userGroup;
        }
    }

    public ElastiCacheUserGroup deleteUserGroup(String userGroupId) {
        String id = normalizeUserGroupId(userGroupId);
        synchronized (userGroupLock) {
            ElastiCacheUserGroup userGroup = getUserGroup(id);
            List<String> replicationGroupIds = replicationGroupIdsUsing(id);
            if (!replicationGroupIds.isEmpty() || reservedUserGroups.containsKey(userGroupReservationKey(id))) {
                throw new AwsException("InvalidUserGroupState",
                        "User group " + id + " is in use by a replication group. Disassociate it before deleting it.", 400);
            }
            if (!userGroup.getServerlessCacheEngines().isEmpty()) {
                throw new AwsException("InvalidUserGroupState",
                        "User group " + id + " is in use by a serverless cache. Disassociate it before deleting it.", 400);
            }
            userGroups.delete(id);
            userGroup.setStatus("deleting");
            LOG.infov("ElastiCache user group {0} deleted", id);
            return userGroup;
        }
    }

    /** Checks, under the user group lock, that a serverless cache of this engine may use the group. */
    void validateServerlessAssociation(String userGroupId, String cacheEngine) {
        synchronized (userGroupLock) {
            ElastiCacheUserGroup userGroup = getUserGroup(userGroupId);
            requireEngineCompatible(userGroup.getEngine(), userGroup.getUserGroupId(), normalizeEngine(cacheEngine));
        }
    }

    void attachServerlessCache(String userGroupId, String cacheName, String cacheEngine) {
        synchronized (userGroupLock) {
            ElastiCacheUserGroup userGroup = getUserGroup(userGroupId);
            requireEngineCompatible(userGroup.getEngine(), userGroup.getUserGroupId(), normalizeEngine(cacheEngine));
            userGroup.getServerlessCacheEngines().put(cacheName, normalizeEngine(cacheEngine));
            userGroups.put(userGroup.getUserGroupId(), userGroup);
        }
    }

    void detachServerlessCache(String userGroupId, String cacheName) {
        synchronized (userGroupLock) {
            Optional<ElastiCacheUserGroup> userGroup = userGroups.get(normalizeUserGroupId(userGroupId));
            if (userGroup.isPresent() && userGroup.get().getServerlessCacheEngines().remove(cacheName) != null) {
                userGroups.put(userGroup.get().getUserGroupId(), userGroup.get());
            }
        }
    }

    public List<String> replicationGroupIdsUsing(String userGroupId) {
        return replicationGroupsUsing(normalizeUserGroupId(userGroupId)).stream()
                .map(ReplicationGroup::getReplicationGroupId)
                .toList();
    }

    public List<String> userGroupIdsContaining(String userId) {
        return userGroups.scan(k -> true).stream()
                .filter(g -> g.getUserIds().contains(userId))
                .map(ElastiCacheUserGroup::getUserGroupId)
                .toList();
    }

    private List<ReplicationGroup> replicationGroupsUsing(String normalizedUserGroupId) {
        return groups.scan(k -> true).stream()
                .filter(g -> g.getUserGroupIds().contains(normalizedUserGroupId))
                .toList();
    }

    /**
     * The users that can authenticate against a replication group: the members of its user
     * groups, read at the time of the check so a ModifyUserGroup applies at once, plus any user
     * associated with it directly.
     */
    private Set<String> effectiveUserIds(String accountId, ReplicationGroup group) {
        Set<String> userIds = new LinkedHashSet<>(group.getAssociatedUserIds());
        for (String userGroupId : group.getUserGroupIds()) {
            userGroups.getForAccount(accountId, userGroupId).ifPresent(g -> userIds.addAll(g.getUserIds()));
        }
        return userIds;
    }

    /**
     * A redis user group must contain a user named {@code default}; a valkey one need not, but
     * cannot contain a user without authentication. Valkey users only join valkey user groups,
     * and two members cannot share a user name.
     */
    private void validateUserGroupMembers(String engine, Set<String> userIds) {
        Set<String> userNames = new HashSet<>();
        boolean hasDefaultUser = false;
        for (String userId : userIds) {
            ElastiCacheUser user = getUser(userId);
            requireMemberAllowed(engine, userId, user.getEngine(), user.getAuthMode());
            if (!userNames.add(user.getUserName())) {
                throw new AwsException("DuplicateUserName",
                        "More than one user in the user group has the user name " + user.getUserName() + ".", 400);
            }
            hasDefaultUser |= "default".equals(user.getUserName());
        }
        if ("redis".equals(engine) && !hasDefaultUser) {
            throw new AwsException("DefaultUserRequired", "You must add default user to a user group.", 400);
        }
    }

    private static void requireMemberAllowed(String userGroupEngine, String userId, String userEngine,
                                             AuthMode authMode) {
        if ("redis".equals(userGroupEngine) && "valkey".equals(userEngine)) {
            throw new AwsException("InvalidParameterValue",
                    "User " + userId + " uses the valkey engine and can only belong to valkey user groups.", 400);
        }
        if ("valkey".equals(userGroupEngine) && authMode == AuthMode.NO_AUTH) {
            throw new AwsException("InvalidParameterValue",
                    "User " + userId + " requires no password and cannot belong to a valkey user group.", 400);
        }
    }

    /** RBAC needs in-transit encryption, and a valkey user group only serves valkey caches. */
    private static void requireAssociable(ElastiCacheUserGroup userGroup, boolean encryptedInTransit, String engine) {
        if (!encryptedInTransit) {
            throw new AwsException("InvalidParameterCombination",
                    "User group " + userGroup.getUserGroupId()
                            + " can only be associated with a replication group that has in-transit encryption enabled.", 400);
        }
        requireEngineCompatible(userGroup.getEngine(), userGroup.getUserGroupId(), engine);
    }

    private static void requireEngineCompatible(String userGroupEngine, String userGroupId, String replicationGroupEngine) {
        if ("valkey".equals(userGroupEngine) && !"valkey".equals(replicationGroupEngine)) {
            throw new AwsException("InvalidParameterCombination",
                    "User group " + userGroupId + " uses the valkey engine and can only be associated with a valkey cache.", 400);
        }
    }

    private static String normalizeUserGroupId(String userGroupId) {
        if (userGroupId == null || userGroupId.isBlank()) {
            throw new AwsException("InvalidParameterValue", "UserGroupId is required.", 400);
        }
        // AWS stores the id as a lowercase string
        return userGroupId.toLowerCase(Locale.ROOT);
    }

    private static AwsException userGroupNotFound(String userGroupId) {
        return new AwsException("UserGroupNotFound", "User group " + userGroupId + " not found.", 404);
    }

    /**
     * Validates a Redis AUTH password for the given group.
     * Checks the group-level authToken first, then falls back to the "default" user
     * associated with the group (per Redis 6+ ACL spec, single-arg AUTH only
     * authenticates the default user). Only users explicitly added via
     * ModifyReplicationGroup are checked, preventing cross-group credential leakage.
     */
    public boolean validatePassword(String groupId, String username, String password) {
        return validatePassword(groups.accountId(), groupId, username, password);
    }

    /**
     * The account-explicit form {@link #groupAuthenticator} calls, for the reason given on
     * {@link #validateCacheClusterPassword(String, String, String, String)}.
     */
    private boolean validatePassword(String accountId, String groupId, String username, String password) {
        ReplicationGroup group = groups.getForAccount(accountId, groupId).orElse(null);
        if (group == null) {
            return false;
        }

        if (username == null || username.isEmpty() || "default".equals(username)) {
            // AUTH password form: the group-level authToken first, unless user groups replaced it
            if (group.getUserGroupIds().isEmpty() && group.getAuthToken() != null
                    && password.equals(group.getAuthToken())) {
                return true;
            }
            // Fall back to the "default" user among this group's members
            Set<String> groupUserIds = effectiveUserIds(accountId, group);
            ElastiCacheUser defaultUser = groupUserIds.stream()
                    .map(id -> users.getForAccount(accountId, id).orElse(null))
                    .filter(u -> u != null && "default".equals(u.getUserName()))
                    .findFirst()
                    .orElse(null);
            if (defaultUser == null || !defaultUser.isEnabled()) {
                return false;
            }
            if (defaultUser.getAuthMode() == AuthMode.NO_AUTH) {
                return true;
            }
            if (defaultUser.getAuthMode() == AuthMode.PASSWORD) {
                return defaultUser.getPasswords() != null && defaultUser.getPasswords().contains(password);
            }
            return false;
        }
        // AUTH username password form: find user by userName, scoped to group
        Set<String> groupUserIds = effectiveUserIds(accountId, group);
        ElastiCacheUser targetUser = groupUserIds.stream()
                .map(id -> users.getForAccount(accountId, id).orElse(null))
                .filter(u -> u != null && username.equals(u.getUserName()))
                .findFirst()
                .orElse(null);
        if (targetUser == null || !targetUser.isEnabled()) {
            return false;
        }
        if (targetUser.getAuthMode() == AuthMode.NO_AUTH) {
            return true;
        }
        if (targetUser.getAuthMode() == AuthMode.PASSWORD) {
            return targetUser.getPasswords() != null && targetUser.getPasswords().contains(password);
        }
        return false;
    }

    /**
     * Whether the proxy authenticates each user by its own mode. A user group switches that on
     * even while it has no members, since RBAC then admits nobody rather than every caller.
     */
    public boolean hasMembers(String groupId) {
        return hasMembers(groups.accountId(), groupId);
    }

    private boolean hasMembers(String accountId, String groupId) {
        ReplicationGroup group = groups.getForAccount(accountId, groupId).orElse(null);
        if (group == null) {
            return false;
        }
        if (!group.getUserGroupIds().isEmpty()) {
            return true;
        }
        return group.getAssociatedUserIds().stream()
                .anyMatch(id -> users.getForAccount(accountId, id).isPresent());
    }

    public AuthMode memberAuthMode(String groupId, String username) {
        return memberAuthMode(groups.accountId(), groupId, username);
    }

    private AuthMode memberAuthMode(String accountId, String groupId, String username) {
        ReplicationGroup group = groups.getForAccount(accountId, groupId).orElse(null);
        if (group == null) {
            return null;
        }
        String target = (username == null || username.isEmpty()) ? "default" : username;
        return effectiveUserIds(accountId, group).stream()
                .map(id -> users.getForAccount(accountId, id).orElse(null))
                .filter(u -> u != null && target.equals(u.getUserName()) && u.isEnabled())
                .map(ElastiCacheUser::getAuthMode)
                .findFirst()
                .orElse(null);
    }

    /**
     * Bound to the account that owns the group, captured when the proxy is registered: the proxy
     * calls back on its own thread, where the store's implicit account is always the default one,
     * so a lookup by id alone would miss another account's group or find the default account's
     * group of the same name.
     */
    private ElastiCacheAuthProxy.PasswordValidator groupAuthenticator(String accountId, String groupId) {
        return new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return ElastiCacheService.this.validatePassword(accountId, groupId, username, password);
            }

            @Override
            public boolean hasMembers() {
                return ElastiCacheService.this.hasMembers(accountId, groupId);
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                return ElastiCacheService.this.memberAuthMode(accountId, groupId, username);
            }

            @Override
            public AuthMode authMode() {
                return groups.getForAccount(accountId, groupId).map(ReplicationGroup::getAuthMode).orElse(null);
            }

            @Override
            public String ownerAccountId() {
                return accountId;
            }
        };
    }

    // AWS allows only redis and valkey.
    private static String normalizeEngine(String engine) {
        String normalized = engine.toLowerCase(Locale.ROOT);
        if (!"redis".equals(normalized) && !"valkey".equals(normalized)) {
            throw new AwsException("InvalidParameterValue",
                    "Engine must be 'redis' or 'valkey'.", 400);
        }
        return normalized;
    }

    /** The host cluster-mode nodes announce: their endpoints are the per-node proxies on Floci's address. */
    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    /**
     * Cluster-mode groups bake this name into MOVED/ASK redirects and topology responses that
     * every client must resolve, unlike the plain endpoint host that clients may override —
     * so a hostname resolvable only inside Floci's Docker network (e.g. a Compose service
     * name) needs the cluster-announce-hostname override.
     */
    private String resolveClusterAnnounceHost() {
        return config.services().elasticache().clusterAnnounceHostname()
                .filter(host -> !host.isBlank())
                .orElseGet(this::resolveEndpointHost);
    }

    public int allocateProxyPort() {
        return allocateProxyPort(null);
    }

    /**
     * Honors the request's {@code Port} when it is free and inside the proxy range, and otherwise
     * serves the cache on a port from that range instead of refusing it.
     *
     * <p>On AWS, Port belongs to a cache's own endpoint. 6379 is the Redis default, so nearly
     * every ElastiCache cluster in the world uses it and they coexist: AWS gives each cache a DNS
     * name of its own, and the port never has to distinguish them. Floci multiplexes every
     * cache's proxy onto one host, where a TCP port is exclusive and the port is the only thing
     * that does distinguish them. Refusing the second cache on 6379, which is what this method
     * used to do, diverges from AWS on an entirely ordinary request: any module standing up two
     * Redis clusters, and any suite running one per test in parallel, failed at the second
     * create.
     *
     * <p>Which leaves a choice about the port a caller pinned but cannot have, and only two of
     * the three obvious answers are safe. Refusing is the divergence above. Reporting the pinned
     * port anyway, while listening on another, is worse than it sounds: the endpoint then names
     * a listener belonging to a <em>different</em> cache, so a client that dials it reads someone
     * else's data and is told nothing. That is the failure #4094 removed on the restore path,
     * where "the old endpoint then reached an unrelated cache instead of failing cleanly".
     *
     * <p>So the port a cache reports is always the port it is actually on. A caller that pinned
     * an unavailable port sees drift, which terraform surfaces as a replacement-forcing diff on
     * the next plan. That is a visible, reversible failure, where the alternative is a silent
     * wrong answer, and the create still succeeds. The WARN below names the port that was served
     * and why the pinned one could not be.
     *
     * <p>A port outside the proxy range is substituted for the same reason rather than refused:
     * the range bounds what this emulator may listen on, and never bounded what AWS accepts. The
     * only rejection left is a range check, 1150 to 65535, which is the bound
     * {@code RdsService.reserveProxyPort} applies to the same argument. Nothing here establishes
     * that it is ElastiCache's real lower bound on AWS, so do not read it as one; it is strictly
     * more permissive than the proxy-range check it replaced, which bounds the risk to accepting
     * a port AWS would refuse rather than refusing one AWS accepts.
     */
    private int allocateProxyPort(Integer requested) {
        int base = config.services().elasticache().proxyBasePort();
        int max = config.services().elasticache().proxyMaxPort();
        if (requested != null) {
            if (requested < 1150 || requested > 65535) {
                throw new AwsException("InvalidParameterValue",
                        "Port must be between 1150 and 65535.", 400);
            }
            if (requested >= base && requested <= max && usedPorts.add(requested)) {
                return requested;
            }
            int substitute = scanForFreePort(base, max);
            LOG.warnv("ElastiCache port {0} could not be served: it is {1}. This cache is on {2} "
                            + "instead, and that is the port its endpoint reports.",
                    String.valueOf(requested),
                    requested < base || requested > max
                            ? "outside the proxy range " + base + "-" + max
                            : "already in use by another cache here",
                    String.valueOf(substitute));
            return substitute;
        }
        return scanForFreePort(base, max);
    }

    private int scanForFreePort(int base, int max) {
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        // Proxy-port exhaustion is a Floci-side limit, but on the wire it must look like the
        // modeled capacity fault. botocore/smithy declare InsufficientCacheClusterCapacityFault
        // for CreateReplicationGroup: awsQueryError code InsufficientCacheClusterCapacity,
        // HTTP 400, Sender fault. The old InsufficientReplicationGroupCapacity/503 is invented —
        // no such shape exists, so SDK clients cannot map it. Keep the real cause in the log.
        LOG.warnv("ElastiCache proxy port range {0}-{1} exhausted; returning InsufficientCacheClusterCapacity", base, max);
        throw new AwsException("InsufficientCacheClusterCapacity",
                "The requested cache node type is not available in the specified Availability Zone.", 400);
    }

    /**
     * A host-proxy port for a cache whose endpoint is served by its own engine: the cache's Port
     * when no other proxy holds it and this host can serve it there, so host access keeps the
     * familiar port, otherwise a free port of the range. The cache's Port does not depend on
     * which one the proxy gets. With Floci on the host its proxies can listen on any port; in
     * Docker only the range is published, so the Port is taken only when it falls inside it.
     */
    private int allocateProxyPortPreferring(int preferred) {
        int base = config.services().elasticache().proxyBasePort();
        int max = config.services().elasticache().proxyMaxPort();
        if (preferred >= base && preferred <= max && usedPorts.add(preferred)) {
            return preferred;
        }
        return allocateProxyPort(null);
    }

    public int allocateHostProxyPort(int preferred) {
        boolean onHost = containerDetector == null || !containerDetector.isRunningInContainer();
        if (onHost && preferred > 0 && usedPorts.add(preferred)) {
            return preferred;
        }
        return allocateProxyPortPreferring(preferred);
    }

    public void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }

    int reserveOrAllocateProxyPort(int persistedPort) {
        if (persistedPort > 0 && usedPorts.add(persistedPort)) {
            return persistedPort;
        }
        return allocateProxyPort();
    }

    // ── Cache Subnet Groups ───────────────────────────────────────────────────

    /**
     * Creates a subnet group. The VPC and each subnet's availability zone are read from the
     * subnets rather than the request, because that is where AWS takes them from — which makes
     * resolving them against EC2 part of answering, not an optional check.
     */
    public CacheSubnetGroup createCacheSubnetGroup(String name, String description,
                                                   List<String> subnetIds, Map<String, String> tags) {
        validateSubnetGroupName(name);
        synchronized (lockFor("sng:" + name)) {
            if (subnetGroups.get(name).isPresent()) {
                throw new AwsException("CacheSubnetGroupAlreadyExists",
                        "Cache subnet group " + name + " already exists.", 400);
            }
            CacheSubnetGroup group = buildSubnetGroup(name, description, subnetIds);
            if (tags != null && !tags.isEmpty()) {
                group.setTags(tags);
            }
            subnetGroups.put(name, group);
            LOG.infov("Created cache subnet group {0} in {1}", name, group.getVpcId());
            return group;
        }
    }

    public List<CacheSubnetGroup> describeCacheSubnetGroups(String name) {
        if (name == null || name.isBlank()) {
            return subnetGroups.scan(key -> true);
        }
        return List.of(subnetGroups.get(name)
                .orElseThrow(() -> new AwsException("CacheSubnetGroupNotFoundFault",
                        "Cache subnet group " + name + " not found.", 400)));
    }

    /** Replaces the description and, when subnets are given, the whole subnet set. */
    public CacheSubnetGroup modifyCacheSubnetGroup(String name, String description, List<String> subnetIds) {
        return modifyCacheSubnetGroup(name, description, subnetIds, null);
    }

    /** As above; a non-null {@code tags} replaces the stored tag map, an empty one clears it. */
    public CacheSubnetGroup modifyCacheSubnetGroup(String name, String description, List<String> subnetIds,
                                                   Map<String, String> tags) {
        validateSubnetGroupName(name);
        synchronized (lockFor("sng:" + name)) {
            CacheSubnetGroup existing = subnetGroups.get(name)
                    .orElseThrow(() -> new AwsException("CacheSubnetGroupNotFoundFault",
                            "Cache subnet group " + name + " not found.", 400));
            String effectiveDescription = description == null ? existing.getDescription() : description;
            CacheSubnetGroup updated;
            if (subnetIds == null || subnetIds.isEmpty()) {
                // No subnets given means none change, so the stored ones are kept as they are:
                // re-resolving them would fail a description-only change if a subnet had since
                // been deleted from EC2.
                updated = new CacheSubnetGroup(name, effectiveDescription, existing.getVpcId(),
                        existing.getSubnetAvailabilityZones());
            } else {
                updated = buildSubnetGroup(name, effectiveDescription, subnetIds);
            }
            updated.setTags(tags != null ? tags : existing.getTags());
            subnetGroups.put(name, updated);
            return updated;
        }
    }

    public void deleteCacheSubnetGroup(String name) {
        validateSubnetGroupName(name);
        synchronized (lockFor("sng:" + name)) {
            if (subnetGroups.get(name).isEmpty()) {
                throw new AwsException("CacheSubnetGroupNotFoundFault",
                        "Cache Subnet Group " + name + " does not exist.", 400);
            }
            boolean inUse = cacheClusters.scan(k -> true).stream()
                            .anyMatch(c -> name.equals(c.getCacheSubnetGroupName()))
                    || memcachedClusters.scan(k -> true).stream()
                            .anyMatch(c -> name.equals(c.getCacheSubnetGroupName()))
                    || groups.scan(k -> true).stream()
                            .anyMatch(g -> name.equals(g.getCacheSubnetGroupName()));
            if (inUse) {
                throw new AwsException("CacheSubnetGroupInUse",
                        "The requested cache subnet group is currently in use.", 400);
            }
            subnetGroups.delete(name);
        }
        LOG.infov("Deleted cache subnet group {0}", name);
    }

    private static AwsException invalidSubnets(List<String> subnetIds) {
        return new AwsException("InvalidParameterValue",
                "Some input subnets in :[" + String.join(", ", subnetIds) + "] are invalid.", 400);
    }

    private CacheSubnetGroup buildSubnetGroup(String name, String description, List<String> subnetIds) {
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "The parameter SubnetIds must be provided.", 400);
        }
        // The caller's region, not the configured default: subnets exist in the region they were
        // created in, and the ARN this group is reported under is built from that same region.
        String region = regionResolver.getRegion();
        List<Subnet> resolved;
        try {
            resolved = ec2Service.describeSubnets(region, subnetIds, Map.of());
        } catch (AwsException e) {
            // EC2 rejects an unknown id outright; ElastiCache reports it as its own invalid-subnet error.
            if (e.getErrorCode() != null && e.getErrorCode().startsWith("InvalidSubnetID.")) {
                throw invalidSubnets(subnetIds);
            }
            throw e;
        }
        if (resolved.size() != subnetIds.size()) {
            throw invalidSubnets(subnetIds);
        }

        String vpcId = resolved.getFirst().getVpcId();
        List<String> foreign = resolved.stream()
                .filter(subnet -> subnet.getVpcId() != null && !vpcId.equals(subnet.getVpcId()))
                .map(Subnet::getSubnetId)
                .toList();
        if (!foreign.isEmpty()) {
            throw new AwsException("InvalidSubnet", "Subnets " + resolved.getFirst().getSubnetId()
                    + " and " + foreign.getFirst() + " are not in the same VPC.", 400);
        }

        // In the order the caller gave them: describeSubnets answers in the store's scan order,
        // which would make the reported order of a group's subnets arbitrary between calls.
        Map<String, String> zoneBySubnet = new LinkedHashMap<>();
        resolved.forEach(subnet -> zoneBySubnet.put(subnet.getSubnetId(), subnet.getAvailabilityZone()));
        Map<String, String> availabilityZones = new LinkedHashMap<>();
        subnetIds.forEach(id -> availabilityZones.put(id, zoneBySubnet.get(id)));
        return new CacheSubnetGroup(name, description, vpcId, availabilityZones);
    }

    /**
     * Subnet group names take the same identifier rule as parameter group names.
     *
     * <p>The not-found faults do not match, though, and the difference is AWS's: the subnet-group
     * fault keeps its {@code Fault} suffix on the wire and is a 400, while the parameter-group one
     * drops the suffix and is a 404. Both are declared that way in the service model.
     */
    private static void validateSubnetGroupName(String name) {
        if (name == null || name.isBlank() || !PARAMETER_GROUP_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "The parameter CacheSubnetGroupName is not a valid identifier. Identifiers must "
                            + "begin with a letter; must contain only ASCII letters, digits, and hyphens; "
                            + "and must not end with a hyphen or contain two consecutive hyphens.", 400);
        }
    }

    // ── Cache Parameter Groups ────────────────────────────────────────────────

    /** The families AWS accepts, and validates a create against. */
    private static final List<String> PARAMETER_GROUP_FAMILIES = List.of(
            "memcached1.4", "memcached1.5", "memcached1.6",
            "redis2.6", "redis2.8", "redis3.2", "redis4.0", "redis5.0", "redis6.x", "redis7",
            "valkey7", "valkey8", "valkey9");

    /** The families AWS also publishes a cluster-mode default for. */
    private static final List<String> CLUSTER_MODE_FAMILIES = List.of(
            "redis3.2", "redis4.0", "redis5.0", "redis6.x", "redis7", "valkey7", "valkey8", "valkey9");

    /**
     * Names begin with a letter and hold only letters, digits and single interior hyphens. AWS
     * applies this to deletes as well as creates, which is why a {@code default.*} group cannot be
     * deleted: the dot fails this rule before anything looks the group up.
     */
    private static final Pattern PARAMETER_GROUP_NAME =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*$");

    /**
     * The {@code default.*} groups AWS publishes. Derived rather than stored: nothing can modify or
     * delete them, so persisting them would only add records to migrate, a writer to race, and an
     * install predating this that needs backfilling.
     */
    private static List<CacheParameterGroup> defaultParameterGroups() {
        List<CacheParameterGroup> defaults = new ArrayList<>();
        for (String family : PARAMETER_GROUP_FAMILIES) {
            defaults.add(new CacheParameterGroup("default." + family, family,
                    "Default parameter group for " + family));
            if (CLUSTER_MODE_FAMILIES.contains(family)) {
                defaults.add(new CacheParameterGroup("default." + family + ".cluster.on", family,
                        "Customized default parameter group for " + family + " with cluster mode on"));
            }
        }
        return defaults;
    }

    private static void validateParameterGroupName(String name) {
        if (name == null || name.isBlank() || !PARAMETER_GROUP_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "The parameter CacheParameterGroupName is not a valid identifier. Identifiers must "
                            + "begin with a letter; must contain only ASCII letters, digits, and hyphens; "
                            + "and must not end with a hyphen or contain two consecutive hyphens.", 400);
        }
    }

    /**
     * One monitor per group name, taken by every writer before it reads. Guarding the record itself
     * would be too late: a create has no record yet, so two of them could both pass the existence
     * check, and a modify that resolved its record before locking would write back one a concurrent
     * delete had already removed.
     */
    private Object lockFor(String parameterGroupName) {
        return parameterGroupLocks.computeIfAbsent(parameterGroupName, key -> new Object());
    }

    public CacheParameterGroup createCacheParameterGroup(String name, String family,
                                                         String description, Map<String, String> tags) {
        validateParameterGroupName(name);
        if (family == null || !PARAMETER_GROUP_FAMILIES.contains(family)) {
            throw new AwsException("InvalidParameterValue",
                    "CacheParameterGroupFamily " + family + " is not a valid parameter group family.", 400);
        }

        synchronized (lockFor(name)) {
            if (parameterGroups.get(name).isPresent()) {
                throw new AwsException("CacheParameterGroupAlreadyExists",
                        "Parameter group " + name + " already exists", 400);
            }
            CacheParameterGroup group = new CacheParameterGroup(name, family, description);
            if (tags != null) {
                group.setTags(new LinkedHashMap<>(tags));
            }
            parameterGroups.put(name, group);
            LOG.infov("Created cache parameter group {0} ({1})", name, family);
            return group;
        }
    }

    /** Every group, or the one named. The published defaults are listed, as AWS lists them. */
    public List<CacheParameterGroup> describeCacheParameterGroups(String name) {
        if (name == null || name.isBlank()) {
            List<CacheParameterGroup> all = new ArrayList<>(defaultParameterGroups());
            all.addAll(parameterGroups.scan(key -> true));
            return all;
        }
        return List.of(requireParameterGroup(name));
    }

    private static boolean isDefaultParameterGroup(String name) {
        return defaultParameterGroups().stream().anyMatch(group -> group.getName().equals(name));
    }

    /** The group by that name, whether stored or one of the published defaults. */
    public Optional<CacheParameterGroup> findParameterGroup(String name) {
        return parameterGroups.get(name)
                .or(() -> defaultParameterGroups().stream()
                        .filter(group -> group.getName().equals(name))
                        .findFirst());
    }

    public CacheParameterGroup requireParameterGroup(String name) {
        return findParameterGroup(name)
                .orElseThrow(() -> new AwsException("CacheParameterGroupNotFound",
                        "CacheParameterGroup " + name + " not found.", 404));
    }

    /**
     * Records the parameters a caller sets. floci does not carry AWS's per-family catalogue of
     * parameter names, so it cannot tell a real name from a typo and does not try: rejecting names
     * missing from a partial catalogue would refuse configurations AWS accepts.
     */
    public void modifyCacheParameterGroup(String name, Map<String, String> parameters) {
        synchronized (lockFor(name)) {
            // Read inside the lock: a record resolved before it could have been deleted since, and
            // writing it back would restore the group the delete removed.
            CacheParameterGroup group = parameterGroups.get(name).orElse(null);
            if (group == null) {
                // Absent from the store means one of two different things, and they do not share
                // an error: a published default was never stored, whereas anything else is gone.
                if (isDefaultParameterGroup(name)) {
                    throw new AwsException("InvalidParameterValue",
                            "The parameter group " + name + " is a default group and cannot be modified.", 400);
                }
                throw new AwsException("CacheParameterGroupNotFound",
                        "CacheParameterGroup not found: " + name, 404);
            }
            Map<String, String> updated = new LinkedHashMap<>(group.getParameters());
            updated.putAll(parameters);
            group.setParameters(updated);
            parameterGroups.put(name, group);
        }
        LOG.debugv("Modified {0} parameter(s) on cache parameter group {1}", parameters.size(), name);
    }

    public void updateCacheParameterGroupTags(String name, Map<String, String> tags) {
        synchronized (lockFor(name)) {
            CacheParameterGroup group = parameterGroups.get(name).orElseThrow(() ->
                    new AwsException("CacheParameterGroupNotFound",
                            "CacheParameterGroup " + name + " not found.", 404));
            group.setTags(new LinkedHashMap<>(tags));
            parameterGroups.put(name, group);
        }
    }

    public void deleteCacheParameterGroup(String name) {
        validateParameterGroupName(name);
        synchronized (lockFor(name)) {
            if (parameterGroups.get(name).isEmpty()) {
                // AWS emits this without the space after the type name. Deliberately not
                // corrected: matching it is the point, and each action words this differently —
                // describe says "CacheParameterGroup <name> not found." and modify says
                // "CacheParameterGroup not found: <name>".
                throw new AwsException("CacheParameterGroupNotFound",
                        "CacheParameterGroupnot found: " + name, 404);
            }
            if (isParameterGroupInUse(name)) {
                throw new AwsException("InvalidCacheParameterGroupState",
                        "One or more cache clusters are still members of this parameter group "
                                + name + ", so the group cannot be deleted.", 400);
            }
            parameterGroups.delete(name);
        }
        LOG.infov("Deleted cache parameter group {0}", name);
    }

    /**
     * Whether a stored replication group or cache cluster still references the parameter group by
     * that name, or a create that named it is still provisioning and about to store one.
     */
    private boolean isParameterGroupInUse(String name) {
        return reservedParameterGroups.containsKey(parameterGroupReservationKey(name))
                || groups.scan(key -> true).stream()
                        .anyMatch(group -> name.equals(group.getCacheParameterGroupName()))
                || cacheClusters.scan(key -> true).stream()
                        .anyMatch(cluster -> name.equals(cluster.getCacheParameterGroupName()));
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (ReplicationGroup group : groups.scan(k -> true)) {
            if (group.getArn() == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(group.getArn());
            resources.add(new ExplorerResource(
                    group.getArn(), "elasticache:cluster", "elasticache",
                    parsed.region(), parsed.accountId(),
                    group.getCreatedAt() != null ? group.getCreatedAt() : Instant.now(),
                    Map.of()));
        }
        for (CacheCluster cluster : cacheClusters.scan(k -> true)) {
            if (cluster.getArn() == null || cluster.getServerlessCacheName() != null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(cluster.getArn());
            resources.add(new ExplorerResource(
                    cluster.getArn(), "elasticache:cluster", "elasticache",
                    parsed.region(), parsed.accountId(),
                    cluster.getCacheClusterCreateTime() != null
                            ? cluster.getCacheClusterCreateTime() : Instant.now(),
                    Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("elasticache:cluster", "elasticache", true));
    }
}
