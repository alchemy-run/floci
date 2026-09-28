package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sagemaker.SageMakerHyperPodNodeLauncher.NodeLaunch;
import io.github.hectorvent.floci.services.sagemaker.SageMakerStateSupport.Scheduler;
import io.github.hectorvent.floci.services.sagemaker.SageMakerStateSupport.StateEvents;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.ClusterNodeResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.ClusterResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.ClusterSchedulerConfigResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.ComputeQuotaResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static io.github.hectorvent.floci.services.sagemaker.SageMakerFeatureStoreService.boundedInt;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerFeatureStoreService.offsetToken;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.epoch;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.listMap;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.map;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.required;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.tagsFromList;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.text;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.validation;

/**
 * SageMaker HyperPod control plane: clusters, cluster policies (ClusterSchedulerConfig) and
 * compute quotas.
 *
 * <p>Every instance an instance group asks for becomes a node. A node with a
 * {@code LifeCycleConfig} is provisioned by running its {@code OnCreate} script for real through
 * {@link SageMakerHyperPodNodeLauncher}; the cluster reaches {@code InService} once every node is
 * {@code Running}, and {@code Failed} when a lifecycle script fails during creation. A failed
 * scale-up during {@code UpdateCluster} drops the failed nodes and returns to {@code InService}
 * with a {@code FailureMessage}. Cluster policies and compute quotas are configuration attached
 * to an existing EKS-orchestrated cluster and are stored and versioned like AWS does.
 */
@ApplicationScoped
public class SageMakerHyperPodService {
    static final Duration TRANSITION_DURATION = Duration.ofSeconds(2);
    static final String CLUSTER_STATE_CHANGE = "SageMaker HyperPod Cluster State Change";

    /** What HyperPod needs to know about the orchestrating Amazon EKS cluster. */
    record EksClusterState(String status, String authenticationMode) {
    }

    private static final Pattern CLUSTER_NAME = Pattern.compile("^[a-zA-Z0-9](-*[a-zA-Z0-9]){0,62}$");
    private static final Pattern INSTANCE_GROUP_NAME = Pattern.compile("^[a-zA-Z0-9](-*[a-zA-Z0-9]){0,62}$");
    private static final Pattern POLICY_NAME = Pattern.compile("^[a-zA-Z0-9](-*[a-zA-Z0-9]){0,62}$");
    private static final Pattern RESOURCE_ID = Pattern.compile("^[a-z0-9]{12}$");
    private static final Pattern TEAM_NAME = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9]){0,39}?$");
    private static final Pattern PRIORITY_CLASS_NAME = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9]){0,39}?$");
    private static final Set<String> FAILED_OR_DELETED = Set.of("Deleting", "Deleted");
    private static final Set<String> EKS_AUTHENTICATION_MODES = Set.of("API", "API_AND_CONFIG_MAP");
    private static final char[] ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final char[] HEX_ALPHABET = "0123456789abcdef".toCharArray();

    private final StorageBackend<String, ClusterResource> clusters;
    private final StorageBackend<String, ClusterSchedulerConfigResource> schedulerConfigs;
    private final StorageBackend<String, ComputeQuotaResource> computeQuotas;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Predicate<String> bucketExists;
    private final Predicate<String> roleExists;
    private final Function<String, Optional<EksClusterState>> eksClusterState;
    private final StateEvents events;
    private final Scheduler scheduler;
    private final SageMakerHyperPodNodeLauncher nodeLauncher;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public SageMakerHyperPodService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper mapper,
                                    Instance<S3Service> s3, Instance<IamService> iam, Instance<EksService> eks,
                                    Instance<EventBridgeService> eventBridge, SageMakerHyperPodNodeRunner nodeRunner) {
        this(storageFactory.create("sagemaker", "sagemaker-clusters.json",
                        new TypeReference<Map<String, ClusterResource>>() {}),
                storageFactory.create("sagemaker", "sagemaker-cluster-scheduler-configs.json",
                        new TypeReference<Map<String, ClusterSchedulerConfigResource>>() {}),
                storageFactory.create("sagemaker", "sagemaker-compute-quotas.json",
                        new TypeReference<Map<String, ComputeQuotaResource>>() {}),
                regionResolver, mapper, Clock.systemUTC(),
                SageMakerStateSupport.bucketExists(s3),
                SageMakerStateSupport.roleExists(iam, regionResolver::getAccountId),
                eksClusterState(eks),
                SageMakerStateSupport.eventBridge(eventBridge, mapper),
                Scheduler.DELAYED, nodeRunner);
    }

    SageMakerHyperPodService(StorageBackend<String, ClusterResource> clusters,
                             StorageBackend<String, ClusterSchedulerConfigResource> schedulerConfigs,
                             StorageBackend<String, ComputeQuotaResource> computeQuotas,
                             RegionResolver regionResolver, ObjectMapper mapper, Clock clock,
                             Predicate<String> bucketExists, Predicate<String> roleExists,
                             Function<String, Optional<EksClusterState>> eksClusterState,
                             StateEvents events, Scheduler scheduler, SageMakerHyperPodNodeLauncher nodeLauncher) {
        this.clusters = clusters;
        this.schedulerConfigs = schedulerConfigs;
        this.computeQuotas = computeQuotas;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
        this.clock = clock;
        this.bucketExists = bucketExists;
        this.roleExists = roleExists;
        this.eksClusterState = eksClusterState;
        this.events = events;
        this.scheduler = scheduler;
        this.nodeLauncher = nodeLauncher;
    }

    /** Status and authentication mode of the Floci EKS cluster named by an EKS cluster ARN, when it exists. */
    private static Function<String, Optional<EksClusterState>> eksClusterState(Instance<EksService> eks) {
        return arn -> {
            if (eks == null || !eks.isResolvable() || !arn.contains(":cluster/")) {
                return Optional.empty();
            }
            try {
                Cluster cluster = eks.get().describeCluster(arn.substring(arn.indexOf(":cluster/") + 9));
                ClusterStatus status = cluster.getStatus();
                AccessConfig access = cluster.getAccessConfig();
                String mode = access == null || access.authenticationMode() == null
                        ? "CONFIG_MAP" : access.authenticationMode();
                return Optional.of(new EksClusterState(status == null ? "" : status.name(), mode));
            } catch (AwsException e) {
                return Optional.empty();
            }
        };
    }

    // ─────────────────────────── Clusters ───────────────────────────

    public synchronized ObjectNode createCluster(JsonNode request, String region) {
        String name = required(request, "ClusterName");
        if (!CLUSTER_NAME.matcher(name).matches()) {
            throw validation("ClusterName " + name + " must satisfy regular expression pattern: " + CLUSTER_NAME.pattern());
        }
        if (cluster(region, name).isPresent()) {
            throw new AwsException("ResourceInUse", "Cluster " + name + " already exists.", 400);
        }
        JsonNode orchestrator = request.path("Orchestrator");
        boolean eks = orchestrator.path("Eks").isObject();
        if (eks) {
            validateEksOrchestrator(text(orchestrator.path("Eks"), "ClusterArn"));
            if (!request.path("VpcConfig").isObject()) {
                throw validation("VpcConfig is required for clusters orchestrated by Amazon EKS.");
            }
        }
        List<Map<String, Object>> groups = listMap(request.path("InstanceGroups"));
        List<Map<String, Object>> restricted = listMap(request.path("RestrictedInstanceGroups"));
        if (groups.isEmpty() && restricted.isEmpty()) {
            throw validation("At least one of InstanceGroups or RestrictedInstanceGroups must be specified.");
        }
        Set<String> groupNames = new HashSet<>();
        for (JsonNode group : concat(request.path("InstanceGroups"), request.path("RestrictedInstanceGroups"))) {
            validateInstanceGroup(group, eks, groupNames);
        }

        ClusterResource cluster = new ClusterResource();
        cluster.clusterName = name;
        cluster.clusterArn = arn(region, "cluster/" + newId());
        cluster.clusterStatus = "Creating";
        cluster.instanceGroups = new ArrayList<>(groups);
        cluster.restrictedInstanceGroups = new ArrayList<>(restricted);
        cluster.vpcConfig = objectOrNull(request.path("VpcConfig"));
        cluster.orchestrator = objectOrNull(orchestrator);
        cluster.nodeRecovery = nodeRecovery(text(request, "NodeRecovery"), "Automatic");
        cluster.restrictedInstanceGroupsConfig = objectOrNull(request.path("RestrictedInstanceGroupsConfig"));
        cluster.tieredStorageConfig = objectOrNull(request.path("TieredStorageConfig"));
        cluster.nodeProvisioningMode = text(request, "NodeProvisioningMode");
        cluster.clusterRole = text(request, "ClusterRole");
        cluster.autoScaling = objectOrNull(request.path("AutoScaling"));
        cluster.creationTime = clock.millis();
        cluster.lastModifiedTime = cluster.creationTime;
        cluster.region = region;
        cluster.accountId = regionResolver.getAccountId();
        cluster.tags = tagsFromList(request.path("Tags"));
        List<ClusterNodeResource> pending = new ArrayList<>();
        for (Map<String, Object> spec : allGroups(cluster)) {
            pending.addAll(addNodes(cluster, spec, instanceCount(spec)));
        }
        SageMakerStateSupport.putFor(clusters, cluster.accountId, key(region, name), cluster);
        launchNodes(cluster, pending);
        scheduleClusterSettle(cluster);
        ObjectNode out = mapper.createObjectNode();
        out.put("ClusterArn", cluster.clusterArn);
        return out;
    }

    /**
     * Updates instance groups (add, resize, reconfigure, delete) and cluster settings. New nodes
     * run their group's lifecycle scripts; the cluster is {@code Updating} until they finish.
     */
    public synchronized ObjectNode updateCluster(JsonNode request, String region) {
        String nameOrArn = required(request, "ClusterName");
        ClusterResource cluster = cluster(region, nameOrArn).orElseThrow(() -> clusterNotFound(nameOrArn));
        if (!"InService".equals(cluster.clusterStatus)) {
            throw new AwsException("ConflictException", "Cluster " + cluster.clusterName + " is in "
                    + cluster.clusterStatus + " status. Updates are only allowed when the cluster is InService.", 400);
        }
        boolean eks = isEksOrchestrated(cluster);
        JsonNode orchestrator = request.path("Orchestrator");
        if (orchestrator.isObject()) {
            String requestedEks = text(orchestrator.path("Eks"), "ClusterArn");
            if (orchestrator.path("Eks").isObject() != eks
                    || (eks && !Objects.equals(requestedEks, eksClusterArn(cluster)))) {
                throw validation("The orchestrator of cluster " + cluster.clusterName + " cannot be changed.");
            }
        }

        Set<String> toDelete = new LinkedHashSet<>();
        request.path("InstanceGroupsToDelete").forEach(n -> toDelete.add(n.asText()));
        Set<String> existingNames = new HashSet<>();
        allGroups(cluster).forEach(spec -> existingNames.add(groupName(spec)));
        for (String groupName : toDelete) {
            if (!existingNames.contains(groupName)) {
                throw validation("Instance group " + groupName + " does not exist in cluster " + cluster.clusterName + ".");
            }
        }

        Map<String, String> groupStatus = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> groups = mergeGroups(cluster.instanceGroups, cluster.restrictedInstanceGroups,
                request.path("InstanceGroups"), toDelete, eks, seen, groupStatus);
        List<Map<String, Object>> restricted = mergeGroups(cluster.restrictedInstanceGroups, cluster.instanceGroups,
                request.path("RestrictedInstanceGroups"), toDelete, eks, seen, groupStatus);
        if (groups.isEmpty() && restricted.isEmpty()) {
            throw validation("Cluster " + cluster.clusterName + " must keep at least one instance group.");
        }
        String recovery = nodeRecovery(text(request, "NodeRecovery"), cluster.nodeRecovery);

        cluster.instanceGroups = groups;
        cluster.restrictedInstanceGroups = restricted;
        cluster.nodeRecovery = recovery;
        if (request.path("RestrictedInstanceGroupsConfig").isObject()) {
            cluster.restrictedInstanceGroupsConfig = map(request.path("RestrictedInstanceGroupsConfig"));
        }
        if (request.path("TieredStorageConfig").isObject()) {
            cluster.tieredStorageConfig = map(request.path("TieredStorageConfig"));
        }
        if (request.has("NodeProvisioningMode")) {
            cluster.nodeProvisioningMode = text(request, "NodeProvisioningMode");
        }
        if (request.has("ClusterRole")) {
            cluster.clusterRole = text(request, "ClusterRole");
        }
        if (request.path("AutoScaling").isObject()) {
            cluster.autoScaling = map(request.path("AutoScaling"));
        }

        List<ClusterNodeResource> pending = resizeNodes(cluster, groupStatus);
        cluster.instanceGroupStatus = groupStatus;
        cluster.clusterStatus = "Updating";
        cluster.failureMessage = null;
        cluster.lastModifiedTime = clock.millis();
        SageMakerStateSupport.putFor(clusters, cluster.accountId, key(cluster.region, cluster.clusterName), cluster);
        publishClusterStateChange(cluster);
        launchNodes(cluster, pending);
        scheduleClusterSettle(cluster);
        ObjectNode out = mapper.createObjectNode();
        out.put("ClusterArn", cluster.clusterArn);
        return out;
    }

    public synchronized ObjectNode describeCluster(JsonNode request, String region) {
        String nameOrArn = required(request, "ClusterName");
        ClusterResource cluster = cluster(region, nameOrArn).orElseThrow(() -> clusterNotFound(nameOrArn));
        ObjectNode out = mapper.createObjectNode();
        out.put("ClusterArn", cluster.clusterArn);
        out.put("ClusterName", cluster.clusterName);
        out.put("ClusterStatus", cluster.clusterStatus);
        out.put("CreationTime", epoch(cluster.creationTime));
        if (cluster.failureMessage != null) {
            out.put("FailureMessage", cluster.failureMessage);
        }
        renderInstanceGroups(cluster, cluster.instanceGroups, out.putArray("InstanceGroups"));
        if (!cluster.restrictedInstanceGroups.isEmpty()) {
            renderInstanceGroups(cluster, cluster.restrictedInstanceGroups, out.putArray("RestrictedInstanceGroups"));
        }
        if (cluster.restrictedInstanceGroupsConfig != null) {
            out.set("RestrictedInstanceGroupsConfig", mapper.valueToTree(cluster.restrictedInstanceGroupsConfig));
        }
        if (cluster.vpcConfig != null) {
            out.set("VpcConfig", mapper.valueToTree(cluster.vpcConfig));
        }
        if (cluster.orchestrator != null) {
            out.set("Orchestrator", mapper.valueToTree(cluster.orchestrator));
        }
        if (cluster.tieredStorageConfig != null) {
            out.set("TieredStorageConfig", mapper.valueToTree(cluster.tieredStorageConfig));
        }
        out.put("NodeRecovery", cluster.nodeRecovery);
        if (cluster.nodeProvisioningMode != null) {
            out.put("NodeProvisioningMode", cluster.nodeProvisioningMode);
        }
        if (cluster.clusterRole != null) {
            out.put("ClusterRole", cluster.clusterRole);
        }
        if (cluster.autoScaling != null) {
            ObjectNode autoScaling = mapper.valueToTree(cluster.autoScaling);
            autoScaling.put("Status", "Creating".equals(cluster.clusterStatus) ? "Creating" : "InService");
            out.set("AutoScaling", autoScaling);
        }
        return out;
    }

    /** Echoes each group's specification with the counts and status its nodes add up to. */
    private void renderInstanceGroups(ClusterResource cluster, List<Map<String, Object>> specs, ArrayNode out) {
        for (Map<String, Object> spec : specs) {
            String name = groupName(spec);
            ObjectNode group = mapper.valueToTree(spec);
            group.remove("InstanceCount");
            group.put("CurrentCount", nodesOf(cluster, name).stream().filter(n -> "Running".equals(n.status)).count());
            group.put("TargetCount", instanceCount(spec));
            String status = switch (cluster.clusterStatus) {
                case "Creating" -> "Creating";
                case "Failed" -> "Failed";
                case "Deleting" -> "Deleting";
                default -> cluster.instanceGroupStatus.getOrDefault(name, "InService");
            };
            group.put("Status", status);
            out.add(group);
        }
    }

    public synchronized ObjectNode deleteCluster(JsonNode request, String region) {
        String nameOrArn = required(request, "ClusterName");
        ClusterResource cluster = cluster(region, nameOrArn).orElseThrow(() -> clusterNotFound(nameOrArn));
        if ("Creating".equals(cluster.clusterStatus)) {
            throw new AwsException("ConflictException", "Cluster " + cluster.clusterName
                    + " is in Creating status and cannot be deleted until creation completes.", 400);
        }
        if (!"Deleting".equals(cluster.clusterStatus)) {
            cluster.clusterStatus = "Deleting";
            cluster.lastModifiedTime = clock.millis();
            for (ClusterNodeResource node : cluster.nodes) {
                nodeLauncher.cancel(node.instanceId);
                node.status = "ShuttingDown";
                node.statusMessage = null;
            }
            SageMakerStateSupport.putFor(clusters, cluster.accountId, key(cluster.region, cluster.clusterName), cluster);
            publishClusterStateChange(cluster);
            scheduleClusterSettle(cluster);
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("ClusterArn", cluster.clusterArn);
        return out;
    }

    public synchronized ObjectNode listClusters(JsonNode request, String region) {
        List<ClusterResource> all = new ArrayList<>();
        for (ClusterResource cluster : clusters.scan(k -> k.startsWith(region + "::"))) {
            settleCluster(cluster).ifPresent(all::add);
        }
        String nameContains = text(request, "NameContains");
        List<ClusterResource> filtered = all.stream()
                .filter(c -> nameContains == null || c.clusterName.contains(nameContains))
                .filter(createdWithin(request, "CreationTimeAfter", "CreationTimeBefore", c -> c.creationTime))
                .toList();
        Comparator<ClusterResource> comparator = switch (Optional.ofNullable(text(request, "SortBy")).orElse("CREATION_TIME")) {
            case "NAME" -> Comparator.comparing(c -> c.clusterName);
            case "CREATION_TIME" -> Comparator.comparingLong(c -> c.creationTime);
            default -> throw validation("SortBy must be one of [CREATION_TIME, NAME].");
        };
        return page(sorted(filtered, comparator, request), request, "ClusterSummaries", (c, n) -> {
            n.put("ClusterArn", c.clusterArn);
            n.put("ClusterName", c.clusterName);
            n.put("CreationTime", epoch(c.creationTime));
            n.put("ClusterStatus", c.clusterStatus);
        });
    }

    public synchronized ObjectNode listClusterNodes(JsonNode request, String region) {
        String nameOrArn = required(request, "ClusterName");
        ClusterResource cluster = cluster(region, nameOrArn).orElseThrow(() -> clusterNotFound(nameOrArn));
        String groupContains = text(request, "InstanceGroupNameContains");
        List<ClusterNodeResource> filtered = cluster.nodes.stream()
                .filter(n -> groupContains == null || n.instanceGroupName.contains(groupContains))
                .filter(createdWithin(request, "CreationTimeAfter", "CreationTimeBefore", n -> n.launchTime))
                .toList();
        Comparator<ClusterNodeResource> comparator = switch (Optional.ofNullable(text(request, "SortBy")).orElse("CREATION_TIME")) {
            case "NAME" -> Comparator.<ClusterNodeResource, String>comparing(n -> n.instanceGroupName)
                    .thenComparing(n -> n.instanceId);
            case "CREATION_TIME" -> Comparator.<ClusterNodeResource>comparingLong(n -> n.launchTime)
                    .thenComparing(n -> n.instanceId);
            default -> throw validation("SortBy must be one of [CREATION_TIME, NAME].");
        };
        return page(sorted(filtered, comparator, request, "Ascending"), request, "ClusterNodeSummaries", (node, n) -> {
            n.put("InstanceGroupName", node.instanceGroupName);
            n.put("InstanceId", node.instanceId);
            n.put("InstanceType", node.instanceType);
            n.put("LaunchTime", epoch(node.launchTime));
            renderNodeStatus(node, n);
        });
    }

    public synchronized ObjectNode describeClusterNode(JsonNode request, String region) {
        String nameOrArn = required(request, "ClusterName");
        ClusterResource cluster = cluster(region, nameOrArn).orElseThrow(() -> clusterNotFound(nameOrArn));
        String nodeId = text(request, "NodeId");
        String logicalId = text(request, "NodeLogicalId");
        if (nodeId == null && logicalId == null) {
            throw validation("Either NodeId or NodeLogicalId must be specified.");
        }
        ClusterNodeResource node = cluster.nodes.stream()
                .filter(n -> n.instanceId.equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFound", "Node "
                        + Optional.ofNullable(nodeId).orElse(logicalId) + " not found in cluster " + nameOrArn + ".", 400));
        ObjectNode out = mapper.createObjectNode();
        ObjectNode details = out.putObject("NodeDetails");
        details.put("InstanceGroupName", node.instanceGroupName);
        details.put("InstanceId", node.instanceId);
        renderNodeStatus(node, details);
        details.put("InstanceType", node.instanceType);
        details.put("LaunchTime", epoch(node.launchTime));
        groupSpec(cluster, node.instanceGroupName)
                .map(spec -> spec.get("LifeCycleConfig"))
                .filter(Map.class::isInstance)
                .ifPresent(lifeCycle -> details.set("LifeCycleConfig", mapper.valueToTree(lifeCycle)));
        return out;
    }

    private static void renderNodeStatus(ClusterNodeResource node, ObjectNode out) {
        ObjectNode status = out.putObject("InstanceStatus");
        status.put("Status", node.status);
        if (node.statusMessage != null) {
            status.put("Message", node.statusMessage);
        }
    }

    // ─────────────────────────── Nodes ───────────────────────────

    /** Adds {@code count} nodes to a group: pending when the group has lifecycle scripts to run, else running. */
    private List<ClusterNodeResource> addNodes(ClusterResource cluster, Map<String, Object> spec, int count) {
        List<ClusterNodeResource> pending = new ArrayList<>();
        boolean lifecycle = spec.get("LifeCycleConfig") instanceof Map<?, ?>;
        for (int i = 0; i < count; i++) {
            ClusterNodeResource node = new ClusterNodeResource();
            node.instanceGroupName = groupName(spec);
            node.instanceId = newInstanceId();
            node.instanceType = SageMakerEndpointManager.string(spec.get("InstanceType"));
            node.launchTime = clock.millis();
            node.status = lifecycle ? "Pending" : "Running";
            cluster.nodes.add(node);
            if (lifecycle) {
                pending.add(node);
            }
        }
        return pending;
    }

    /**
     * Brings every group's nodes to its {@code InstanceCount}: nodes of deleted groups and the
     * newest surplus nodes are terminated, missing ones added. Returns the nodes to provision.
     */
    private List<ClusterNodeResource> resizeNodes(ClusterResource cluster, Map<String, String> groupStatus) {
        List<Map<String, Object>> specs = allGroups(cluster);
        Set<String> names = new HashSet<>();
        specs.forEach(spec -> names.add(groupName(spec)));
        for (ClusterNodeResource node : List.copyOf(cluster.nodes)) {
            if (!names.contains(node.instanceGroupName)) {
                removeNode(cluster, node);
            }
        }
        List<ClusterNodeResource> pending = new ArrayList<>();
        for (Map<String, Object> spec : specs) {
            String name = groupName(spec);
            List<ClusterNodeResource> current = nodesOf(cluster, name);
            int target = instanceCount(spec);
            if (current.size() < target) {
                pending.addAll(addNodes(cluster, spec, target - current.size()));
                groupStatus.putIfAbsent(name, "Updating");
            } else if (current.size() > target) {
                current.stream()
                        .sorted(Comparator.<ClusterNodeResource>comparingLong(n -> n.launchTime).reversed())
                        .limit(current.size() - target)
                        .forEach(node -> removeNode(cluster, node));
                groupStatus.putIfAbsent(name, "Updating");
            }
        }
        return pending;
    }

    private void removeNode(ClusterResource cluster, ClusterNodeResource node) {
        nodeLauncher.cancel(node.instanceId);
        cluster.nodes.remove(node);
    }

    private void launchNodes(ClusterResource cluster, List<ClusterNodeResource> pending) {
        if (pending.isEmpty()) {
            return;
        }
        String resourceConfig = resourceConfig(cluster);
        String clusterKey = key(cluster.region, cluster.clusterName);
        String accountId = cluster.accountId;
        for (ClusterNodeResource node : pending) {
            Map<?, ?> lifeCycle = groupSpec(cluster, node.instanceGroupName)
                    .map(spec -> spec.get("LifeCycleConfig"))
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .orElse(Map.of());
            NodeLaunch launch = new NodeLaunch(accountId, cluster.region, cluster.clusterArn, clusterId(cluster),
                    cluster.clusterName, node.instanceGroupName, node.instanceId, node.instanceType,
                    SageMakerEndpointManager.string(lifeCycle.get("SourceS3Uri")),
                    SageMakerEndpointManager.string(lifeCycle.get("OnCreate")), resourceConfig);
            String instanceId = node.instanceId;
            nodeLauncher.launch(launch, (succeeded, message) ->
                    nodeCompleted(accountId, clusterKey, instanceId, succeeded, message));
        }
    }

    private synchronized void nodeCompleted(String accountId, String clusterKey, String instanceId,
                                            boolean succeeded, String message) {
        Optional<ClusterResource> stored = SageMakerStateSupport.getFor(clusters, accountId, clusterKey);
        if (stored.isEmpty()) {
            return;
        }
        ClusterResource cluster = stored.get();
        Optional<ClusterNodeResource> node = cluster.nodes.stream()
                .filter(n -> n.instanceId.equals(instanceId))
                .findFirst();
        if (node.isEmpty() || !"Pending".equals(node.get().status)) {
            return;
        }
        node.get().status = succeeded ? "Running" : "Failure";
        node.get().statusMessage = succeeded ? null : message;
        SageMakerStateSupport.putFor(clusters, accountId, clusterKey, cluster);
        settleCluster(cluster);
    }

    /** The {@code /opt/ml/config/resource_config.json} HyperPod hands every node's lifecycle scripts. */
    private String resourceConfig(ClusterResource cluster) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode clusterConfig = root.putObject("ClusterConfig");
        clusterConfig.put("ClusterArn", cluster.clusterArn);
        clusterConfig.put("ClusterName", cluster.clusterName);
        ArrayNode groups = root.putArray("InstanceGroups");
        for (Map<String, Object> spec : allGroups(cluster)) {
            String name = groupName(spec);
            ObjectNode group = groups.addObject();
            group.put("Name", name);
            group.put("InstanceType", SageMakerEndpointManager.string(spec.get("InstanceType")));
            ArrayNode instances = group.putArray("Instances");
            int index = 1;
            for (ClusterNodeResource node : nodesOf(cluster, name)) {
                ObjectNode instance = instances.addObject();
                instance.put("InstanceName", name + "-" + index++);
                instance.put("InstanceId", node.instanceId);
            }
        }
        return root.toString();
    }

    private static List<ClusterNodeResource> nodesOf(ClusterResource cluster, String groupName) {
        return cluster.nodes.stream().filter(n -> groupName.equals(n.instanceGroupName)).toList();
    }

    private static List<Map<String, Object>> allGroups(ClusterResource cluster) {
        List<Map<String, Object>> all = new ArrayList<>(cluster.instanceGroups);
        all.addAll(cluster.restrictedInstanceGroups);
        return all;
    }

    private static Optional<Map<String, Object>> groupSpec(ClusterResource cluster, String groupName) {
        return allGroups(cluster).stream().filter(spec -> groupName.equals(groupName(spec))).findFirst();
    }

    private static String groupName(Map<String, Object> spec) {
        return SageMakerEndpointManager.string(spec.get("InstanceGroupName"));
    }

    private static int instanceCount(Map<String, Object> spec) {
        return spec.get("InstanceCount") instanceof Number count ? count.intValue() : 0;
    }

    private static String clusterId(ClusterResource cluster) {
        return cluster.clusterArn.substring(cluster.clusterArn.lastIndexOf('/') + 1);
    }

    /**
     * Merges an update's group specifications into one list of existing groups. A new group is
     * {@code Creating}; a changed one {@code Updating}. The instance type of an existing group is fixed.
     */
    private List<Map<String, Object>> mergeGroups(List<Map<String, Object>> current, List<Map<String, Object>> otherList,
                                                  JsonNode requested, Set<String> toDelete, boolean eks,
                                                  Set<String> seen, Map<String, String> groupStatus) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> spec : current) {
            merged.put(groupName(spec), spec);
        }
        Set<String> otherNames = new HashSet<>();
        otherList.forEach(spec -> otherNames.add(groupName(spec)));
        for (JsonNode group : requested) {
            validateInstanceGroup(group, eks, seen);
            String name = text(group, "InstanceGroupName");
            if (toDelete.contains(name)) {
                throw validation("Instance group " + name + " cannot be both updated and deleted.");
            }
            if (otherNames.contains(name)) {
                throw validation("InstanceGroupName " + name + " is duplicated.");
            }
            Map<String, Object> spec = map(group);
            Map<String, Object> prior = merged.get(name);
            if (prior == null) {
                groupStatus.put(name, "Creating");
            } else {
                if (!Objects.equals(prior.get("InstanceType"), spec.get("InstanceType"))) {
                    throw validation("The InstanceType of instance group " + name + " cannot be changed from "
                            + prior.get("InstanceType") + " to " + spec.get("InstanceType") + ".");
                }
                if (!prior.equals(spec)) {
                    groupStatus.put(name, "Updating");
                }
            }
            merged.put(name, spec);
        }
        toDelete.forEach(merged::remove);
        return new ArrayList<>(merged.values());
    }

    private void validateInstanceGroup(JsonNode group, boolean eks, Set<String> groupNames) {
        String groupName = required(group, "InstanceGroupName");
        if (!INSTANCE_GROUP_NAME.matcher(groupName).matches()) {
            throw validation("InstanceGroupName " + groupName + " must satisfy regular expression pattern: "
                    + INSTANCE_GROUP_NAME.pattern());
        }
        if (!groupNames.add(groupName)) {
            throw validation("InstanceGroupName " + groupName + " is duplicated.");
        }
        String instanceType = required(group, "InstanceType");
        if (!instanceType.startsWith("ml.")) {
            throw validation("InstanceType " + instanceType + " of instance group " + groupName + " is not valid.");
        }
        JsonNode count = group.path("InstanceCount");
        if (!count.canConvertToInt() || count.asInt() < 0) {
            throw validation("InstanceCount of instance group " + groupName + " must be a non-negative integer.");
        }
        String executionRole = required(group, "ExecutionRole");
        if (!SageMakerStateSupport.isRoleArn(executionRole) || !roleExists.test(executionRole)) {
            throw validation("SageMaker cannot assume the execution role " + executionRole + " of instance group "
                    + groupName + ". Ensure the role exists and trusts sagemaker.amazonaws.com.");
        }
        JsonNode lifeCycle = group.path("LifeCycleConfig");
        if (lifeCycle.isObject()) {
            String sourceUri = required(lifeCycle, "SourceS3Uri");
            required(lifeCycle, "OnCreate");
            S3Uri parsed;
            try {
                parsed = S3Uri.parse(sourceUri);
            } catch (IllegalArgumentException e) {
                throw validation("LifeCycleConfig.SourceS3Uri of instance group " + groupName + ": " + e.getMessage());
            }
            if (!bucketExists.test(parsed.bucket())) {
                throw validation("The S3 bucket " + parsed.bucket() + " in LifeCycleConfig.SourceS3Uri of instance group "
                        + groupName + " does not exist.");
            }
        } else if (!eks) {
            throw validation("LifeCycleConfig is required for instance group " + groupName
                    + " of a Slurm-orchestrated cluster.");
        }
    }

    /** HyperPod attaches to an ACTIVE EKS cluster that grants access through EKS access entries. */
    private void validateEksOrchestrator(String eksArn) {
        if (eksArn == null || eksArn.isBlank()) {
            throw validation("Orchestrator.Eks.ClusterArn is required.");
        }
        EksClusterState state = eksClusterState.apply(eksArn)
                .orElseThrow(() -> validation("The EKS cluster " + eksArn + " does not exist."));
        if (!"ACTIVE".equals(state.status())) {
            throw validation("The EKS cluster " + eksArn + " must be ACTIVE but is " + state.status() + ".");
        }
        if (!EKS_AUTHENTICATION_MODES.contains(state.authenticationMode())) {
            throw validation("The EKS cluster " + eksArn + " uses the " + state.authenticationMode()
                    + " authentication mode. SageMaker HyperPod requires the API or API_AND_CONFIG_MAP"
                    + " authentication mode.");
        }
    }

    private static boolean isEksOrchestrated(ClusterResource cluster) {
        return cluster.orchestrator != null && cluster.orchestrator.get("Eks") instanceof Map<?, ?>;
    }

    private static String eksClusterArn(ClusterResource cluster) {
        return cluster.orchestrator != null && cluster.orchestrator.get("Eks") instanceof Map<?, ?> eks
                ? SageMakerEndpointManager.string(eks.get("ClusterArn"))
                : null;
    }

    private static String nodeRecovery(String requested, String current) {
        if (requested == null) {
            return current;
        }
        if (!Set.of("Automatic", "None").contains(requested)) {
            throw validation("NodeRecovery must be one of [Automatic, None].");
        }
        return requested;
    }

    private static Map<String, Object> objectOrNull(JsonNode node) {
        return node.isObject() ? map(node) : null;
    }

    // ─────────────────────────── Cluster policies ───────────────────────────

    public synchronized ObjectNode createClusterSchedulerConfig(JsonNode request, String region) {
        String name = required(request, "Name");
        validatePolicyName(name);
        ClusterResource cluster = eksClusterForPolicy(region, required(request, "ClusterArn"));
        JsonNode schedulerConfig = request.path("SchedulerConfig");
        validateSchedulerConfig(schedulerConfig);
        for (ClusterSchedulerConfigResource existing : schedulerConfigs.scan(k -> k.startsWith(region + "::"))) {
            settleSchedulerConfig(existing).filter(c -> c.clusterArn.equals(cluster.clusterArn)).ifPresent(c -> {
                throw new AwsException("ConflictException", "Cluster " + cluster.clusterArn
                        + " already has cluster policy " + c.id + ". A cluster can have only one cluster policy.", 400);
            });
        }
        ClusterSchedulerConfigResource config = new ClusterSchedulerConfigResource();
        config.id = newId();
        config.arn = arn(region, "cluster-scheduler-config/" + config.id);
        config.name = name;
        config.clusterArn = cluster.clusterArn;
        config.version = 1;
        config.status = "Creating";
        config.schedulerConfig = schedulerConfig.isObject() ? map(schedulerConfig) : null;
        config.description = text(request, "Description");
        config.creationTime = clock.millis();
        config.lastModifiedTime = config.creationTime;
        config.region = region;
        config.accountId = regionResolver.getAccountId();
        config.tags = tagsFromList(request.path("Tags"));
        SageMakerStateSupport.putFor(schedulerConfigs, config.accountId, key(region, config.id), config);
        schedule(config.accountId, key(region, config.id), true);
        ObjectNode out = mapper.createObjectNode();
        out.put("ClusterSchedulerConfigArn", config.arn);
        out.put("ClusterSchedulerConfigId", config.id);
        return out;
    }

    public synchronized ObjectNode describeClusterSchedulerConfig(JsonNode request, String region) {
        ClusterSchedulerConfigResource config = schedulerConfig(region, required(request, "ClusterSchedulerConfigId"));
        JsonNode requestedVersion = request.path("ClusterSchedulerConfigVersion");
        if (requestedVersion.canConvertToInt() && requestedVersion.asInt() != config.version) {
            throw new AwsException("ResourceNotFound", "Version " + requestedVersion.asInt()
                    + " of cluster policy " + config.id + " not found.", 400);
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("ClusterSchedulerConfigArn", config.arn);
        out.put("ClusterSchedulerConfigId", config.id);
        out.put("Name", config.name);
        out.put("ClusterSchedulerConfigVersion", config.version);
        out.put("Status", config.status);
        out.put("ClusterArn", config.clusterArn);
        if (config.schedulerConfig != null) {
            out.set("SchedulerConfig", mapper.valueToTree(config.schedulerConfig));
        }
        if (config.description != null) {
            out.put("Description", config.description);
        }
        out.put("CreationTime", epoch(config.creationTime));
        out.put("LastModifiedTime", epoch(config.lastModifiedTime));
        return out;
    }

    public synchronized ObjectNode updateClusterSchedulerConfig(JsonNode request, String region) {
        ClusterSchedulerConfigResource config = schedulerConfig(region, required(request, "ClusterSchedulerConfigId"));
        requireSettled(config.status, "cluster policy " + config.id);
        requireTargetVersion(request, config.version, "cluster policy " + config.id);
        JsonNode schedulerConfig = request.path("SchedulerConfig");
        if (schedulerConfig.isObject()) {
            validateSchedulerConfig(schedulerConfig);
            config.schedulerConfig = map(schedulerConfig);
        }
        if (request.has("Description")) {
            config.description = text(request, "Description");
        }
        config.version++;
        config.status = "Updating";
        config.lastModifiedTime = clock.millis();
        SageMakerStateSupport.putFor(schedulerConfigs, config.accountId, key(config.region, config.id), config);
        schedule(config.accountId, key(config.region, config.id), true);
        ObjectNode out = mapper.createObjectNode();
        out.put("ClusterSchedulerConfigArn", config.arn);
        out.put("ClusterSchedulerConfigVersion", config.version);
        return out;
    }

    public synchronized ObjectNode deleteClusterSchedulerConfig(JsonNode request, String region) {
        ClusterSchedulerConfigResource config = schedulerConfig(region, required(request, "ClusterSchedulerConfigId"));
        if (!"Deleting".equals(config.status)) {
            config.status = "Deleting";
            config.lastModifiedTime = clock.millis();
            SageMakerStateSupport.putFor(schedulerConfigs, config.accountId, key(config.region, config.id), config);
            schedule(config.accountId, key(config.region, config.id), true);
        }
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode listClusterSchedulerConfigs(JsonNode request, String region) {
        List<ClusterSchedulerConfigResource> all = new ArrayList<>();
        for (ClusterSchedulerConfigResource config : schedulerConfigs.scan(k -> k.startsWith(region + "::"))) {
            settleSchedulerConfig(config).ifPresent(all::add);
        }
        String nameContains = text(request, "NameContains");
        String clusterArn = text(request, "ClusterArn");
        String status = text(request, "Status");
        List<ClusterSchedulerConfigResource> filtered = all.stream()
                .filter(c -> nameContains == null || c.name.contains(nameContains))
                .filter(c -> clusterArn == null || clusterArn.equals(c.clusterArn))
                .filter(c -> status == null || status.equals(c.status))
                .filter(createdWithin(request, "CreatedAfter", "CreatedBefore", c -> c.creationTime))
                .toList();
        Comparator<ClusterSchedulerConfigResource> comparator = switch (Optional.ofNullable(text(request, "SortBy")).orElse("CreationTime")) {
            case "Name" -> Comparator.comparing(c -> c.name);
            case "Status" -> Comparator.comparing(c -> c.status);
            case "CreationTime" -> Comparator.comparingLong(c -> c.creationTime);
            default -> throw validation("SortBy must be one of [Name, CreationTime, Status].");
        };
        return page(sorted(filtered, comparator, request), request, "ClusterSchedulerConfigSummaries", (c, n) -> {
            n.put("ClusterSchedulerConfigArn", c.arn);
            n.put("ClusterSchedulerConfigId", c.id);
            n.put("ClusterSchedulerConfigVersion", c.version);
            n.put("Name", c.name);
            n.put("CreationTime", epoch(c.creationTime));
            n.put("LastModifiedTime", epoch(c.lastModifiedTime));
            n.put("Status", c.status);
            n.put("ClusterArn", c.clusterArn);
        });
    }

    // ─────────────────────────── Compute quotas ───────────────────────────

    public synchronized ObjectNode createComputeQuota(JsonNode request, String region) {
        String name = required(request, "Name");
        validatePolicyName(name);
        ClusterResource cluster = eksClusterForPolicy(region, required(request, "ClusterArn"));
        JsonNode target = request.path("ComputeQuotaTarget");
        validateQuotaTarget(target);
        JsonNode quotaConfig = request.path("ComputeQuotaConfig");
        validateQuotaConfig(quotaConfig);
        String activationState = activationState(text(request, "ActivationState"));
        String teamName = text(target, "TeamName");
        for (ComputeQuotaResource existing : computeQuotas.scan(k -> k.startsWith(region + "::"))) {
            settleComputeQuota(existing)
                    .filter(q -> q.clusterArn.equals(cluster.clusterArn))
                    .filter(q -> teamName.equals(SageMakerEndpointManager.string(q.computeQuotaTarget.get("TeamName"))))
                    .ifPresent(q -> {
                        throw new AwsException("ConflictException", "Team " + teamName + " already has compute quota "
                                + q.id + " on cluster " + cluster.clusterArn + ".", 400);
                    });
        }
        ComputeQuotaResource quota = new ComputeQuotaResource();
        quota.id = newId();
        quota.arn = arn(region, "compute-quota/" + quota.id);
        quota.name = name;
        quota.clusterArn = cluster.clusterArn;
        quota.version = 1;
        quota.status = "Creating";
        quota.computeQuotaConfig = quotaConfig.isObject() ? map(quotaConfig) : null;
        quota.computeQuotaTarget = map(target);
        quota.activationState = activationState;
        quota.description = text(request, "Description");
        quota.creationTime = clock.millis();
        quota.lastModifiedTime = quota.creationTime;
        quota.region = region;
        quota.accountId = regionResolver.getAccountId();
        quota.tags = tagsFromList(request.path("Tags"));
        SageMakerStateSupport.putFor(computeQuotas, quota.accountId, key(region, quota.id), quota);
        schedule(quota.accountId, key(region, quota.id), false);
        ObjectNode out = mapper.createObjectNode();
        out.put("ComputeQuotaArn", quota.arn);
        out.put("ComputeQuotaId", quota.id);
        return out;
    }

    public synchronized ObjectNode describeComputeQuota(JsonNode request, String region) {
        ComputeQuotaResource quota = computeQuota(region, required(request, "ComputeQuotaId"));
        JsonNode requestedVersion = request.path("ComputeQuotaVersion");
        if (requestedVersion.canConvertToInt() && requestedVersion.asInt() != quota.version) {
            throw new AwsException("ResourceNotFound", "Version " + requestedVersion.asInt()
                    + " of compute quota " + quota.id + " not found.", 400);
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("ComputeQuotaArn", quota.arn);
        out.put("ComputeQuotaId", quota.id);
        out.put("Name", quota.name);
        if (quota.description != null) {
            out.put("Description", quota.description);
        }
        out.put("ComputeQuotaVersion", quota.version);
        out.put("Status", quota.status);
        out.put("ClusterArn", quota.clusterArn);
        if (quota.computeQuotaConfig != null) {
            out.set("ComputeQuotaConfig", mapper.valueToTree(quota.computeQuotaConfig));
        }
        out.set("ComputeQuotaTarget", mapper.valueToTree(quota.computeQuotaTarget));
        out.put("ActivationState", quota.activationState);
        out.put("CreationTime", epoch(quota.creationTime));
        out.put("LastModifiedTime", epoch(quota.lastModifiedTime));
        return out;
    }

    public synchronized ObjectNode updateComputeQuota(JsonNode request, String region) {
        ComputeQuotaResource quota = computeQuota(region, required(request, "ComputeQuotaId"));
        requireSettled(quota.status, "compute quota " + quota.id);
        requireTargetVersion(request, quota.version, "compute quota " + quota.id);
        JsonNode quotaConfig = request.path("ComputeQuotaConfig");
        if (quotaConfig.isObject()) {
            validateQuotaConfig(quotaConfig);
            quota.computeQuotaConfig = map(quotaConfig);
        }
        JsonNode target = request.path("ComputeQuotaTarget");
        if (target.isObject()) {
            validateQuotaTarget(target);
            String current = SageMakerEndpointManager.string(quota.computeQuotaTarget.get("TeamName"));
            if (!current.equals(text(target, "TeamName"))) {
                throw validation("ComputeQuotaTarget.TeamName cannot be changed.");
            }
            quota.computeQuotaTarget = map(target);
        }
        if (request.has("ActivationState")) {
            quota.activationState = activationState(text(request, "ActivationState"));
        }
        if (request.has("Description")) {
            quota.description = text(request, "Description");
        }
        quota.version++;
        quota.status = "Updating";
        quota.lastModifiedTime = clock.millis();
        SageMakerStateSupport.putFor(computeQuotas, quota.accountId, key(quota.region, quota.id), quota);
        schedule(quota.accountId, key(quota.region, quota.id), false);
        ObjectNode out = mapper.createObjectNode();
        out.put("ComputeQuotaArn", quota.arn);
        out.put("ComputeQuotaVersion", quota.version);
        return out;
    }

    public synchronized ObjectNode deleteComputeQuota(JsonNode request, String region) {
        ComputeQuotaResource quota = computeQuota(region, required(request, "ComputeQuotaId"));
        if (!"Deleting".equals(quota.status)) {
            quota.status = "Deleting";
            quota.lastModifiedTime = clock.millis();
            SageMakerStateSupport.putFor(computeQuotas, quota.accountId, key(quota.region, quota.id), quota);
            schedule(quota.accountId, key(quota.region, quota.id), false);
        }
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode listComputeQuotas(JsonNode request, String region) {
        List<ComputeQuotaResource> all = new ArrayList<>();
        for (ComputeQuotaResource quota : computeQuotas.scan(k -> k.startsWith(region + "::"))) {
            settleComputeQuota(quota).ifPresent(all::add);
        }
        String nameContains = text(request, "NameContains");
        String clusterArn = text(request, "ClusterArn");
        String status = text(request, "Status");
        List<ComputeQuotaResource> filtered = all.stream()
                .filter(q -> nameContains == null || q.name.contains(nameContains))
                .filter(q -> clusterArn == null || clusterArn.equals(q.clusterArn))
                .filter(q -> status == null || status.equals(q.status))
                .filter(createdWithin(request, "CreatedAfter", "CreatedBefore", q -> q.creationTime))
                .toList();
        Comparator<ComputeQuotaResource> comparator = switch (Optional.ofNullable(text(request, "SortBy")).orElse("CreationTime")) {
            case "Name" -> Comparator.comparing(q -> q.name);
            case "Status" -> Comparator.comparing(q -> q.status);
            case "ClusterArn" -> Comparator.comparing(q -> q.clusterArn);
            case "CreationTime" -> Comparator.comparingLong(q -> q.creationTime);
            default -> throw validation("SortBy must be one of [Name, CreationTime, Status, ClusterArn].");
        };
        return page(sorted(filtered, comparator, request), request, "ComputeQuotaSummaries", (q, n) -> {
            n.put("ComputeQuotaArn", q.arn);
            n.put("ComputeQuotaId", q.id);
            n.put("Name", q.name);
            n.put("ComputeQuotaVersion", q.version);
            n.put("Status", q.status);
            n.put("ClusterArn", q.clusterArn);
            if (q.computeQuotaConfig != null) {
                n.set("ComputeQuotaConfig", mapper.valueToTree(q.computeQuotaConfig));
            }
            n.set("ComputeQuotaTarget", mapper.valueToTree(q.computeQuotaTarget));
            n.put("ActivationState", q.activationState);
            n.put("CreationTime", epoch(q.creationTime));
            n.put("LastModifiedTime", epoch(q.lastModifiedTime));
        });
    }

    /** Applies a tag action when {@code ResourceArn} names a HyperPod resource; empty for other ARNs. */
    synchronized Optional<ObjectNode> tags(String action, JsonNode request) {
        return tagTarget(required(request, "ResourceArn")).map(t -> t.apply(action, request, mapper));
    }

    private Optional<SageMakerTagTarget> tagTarget(String arn) {
        String[] parts = arn.split(":", 6);
        if (parts.length < 6) {
            return Optional.empty();
        }
        String region = parts[3];
        String resource = parts[5];
        if (resource.startsWith("cluster/")) {
            ClusterResource cluster = clusters.scan(k -> k.startsWith(region + "::")).stream()
                    .filter(c -> c.clusterArn.equals(arn)).findFirst()
                    .flatMap(this::settleCluster)
                    .orElseThrow(() -> clusterNotFound(arn));
            return Optional.of(new SageMakerTagTarget(cluster.tags, () -> SageMakerStateSupport.putFor(clusters,
                    cluster.accountId, key(cluster.region, cluster.clusterName), cluster)));
        }
        if (resource.startsWith("cluster-scheduler-config/")) {
            ClusterSchedulerConfigResource config = schedulerConfig(region, resource.substring(resource.indexOf('/') + 1));
            return Optional.of(new SageMakerTagTarget(config.tags, () -> SageMakerStateSupport.putFor(schedulerConfigs,
                    config.accountId, key(config.region, config.id), config)));
        }
        if (resource.startsWith("compute-quota/")) {
            ComputeQuotaResource quota = computeQuota(region, resource.substring(resource.indexOf('/') + 1));
            return Optional.of(new SageMakerTagTarget(quota.tags, () -> SageMakerStateSupport.putFor(computeQuotas,
                    quota.accountId, key(quota.region, quota.id), quota)));
        }
        return Optional.empty();
    }

    // ─────────────────────────── Status transitions ───────────────────────────

    /**
     * A creating or updating cluster settles once no node is still provisioning and the minimum
     * transition time has passed; a deleting one disappears after the transition time.
     */
    private Optional<ClusterResource> settleCluster(ClusterResource cluster) {
        long now = clock.millis();
        String clusterKey = key(cluster.region, cluster.clusterName);
        boolean creating = "Creating".equals(cluster.clusterStatus);
        if (creating || "Updating".equals(cluster.clusterStatus)) {
            boolean interrupted = failInterruptedNodes(cluster);
            long since = creating ? cluster.creationTime : cluster.lastModifiedTime;
            boolean provisioning = cluster.nodes.stream().anyMatch(n -> "Pending".equals(n.status));
            if (!provisioning && now >= since + TRANSITION_DURATION.toMillis()) {
                completeTransition(cluster, creating);
                SageMakerStateSupport.putFor(clusters, cluster.accountId, clusterKey, cluster);
                publishClusterStateChange(cluster);
            } else if (interrupted) {
                SageMakerStateSupport.putFor(clusters, cluster.accountId, clusterKey, cluster);
            }
        } else if ("Deleting".equals(cluster.clusterStatus)
                && now >= cluster.lastModifiedTime + TRANSITION_DURATION.toMillis()) {
            SageMakerStateSupport.deleteFor(clusters, cluster.accountId, clusterKey);
            return Optional.empty();
        }
        return Optional.of(cluster);
    }

    /**
     * A pending node whose provisioning is no longer in flight in this process (the emulator
     * restarted mid-provisioning) can never finish, so it fails instead of blocking the cluster.
     */
    private boolean failInterruptedNodes(ClusterResource cluster) {
        boolean changed = false;
        for (ClusterNodeResource node : cluster.nodes) {
            if ("Pending".equals(node.status) && !nodeLauncher.inFlight(node.instanceId)) {
                node.status = "Failure";
                node.statusMessage = "Node provisioning was interrupted before its lifecycle scripts completed.";
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Ends a create or update. Failed lifecycle scripts fail a create outright (its instances are
     * released); during an update the failed new nodes are dropped, their groups' target counts
     * rolled back to the nodes that are running, and the cluster returns to service.
     */
    private void completeTransition(ClusterResource cluster, boolean creating) {
        List<ClusterNodeResource> failed = cluster.nodes.stream().filter(n -> "Failure".equals(n.status)).toList();
        cluster.instanceGroupStatus = new LinkedHashMap<>();
        if (failed.isEmpty()) {
            cluster.clusterStatus = "InService";
            return;
        }
        String message = "Lifecycle scripts did not run successfully on " + failed.size() + " node(s) ("
                + failed.getFirst().instanceId + ": " + failed.getFirst().statusMessage + "). To view lifecycle"
                + " script logs, visit log group '/aws/sagemaker/Clusters/" + cluster.clusterName + "/"
                + clusterId(cluster) + "'.";
        if (creating) {
            cluster.nodes.clear();
            cluster.clusterStatus = "Failed";
        } else {
            cluster.nodes.removeAll(failed);
            Set<String> affected = new HashSet<>();
            failed.forEach(node -> affected.add(node.instanceGroupName));
            for (Map<String, Object> spec : allGroups(cluster)) {
                if (affected.contains(groupName(spec))) {
                    spec.put("InstanceCount", nodesOf(cluster, groupName(spec)).size());
                }
            }
            cluster.clusterStatus = "InService";
        }
        cluster.failureMessage = message;
    }

    private Optional<ClusterSchedulerConfigResource> settleSchedulerConfig(ClusterSchedulerConfigResource config) {
        String settled = settledStatus(config.status, config.lastModifiedTime);
        if (settled == null) {
            SageMakerStateSupport.deleteFor(schedulerConfigs, config.accountId, key(config.region, config.id));
            return Optional.empty();
        }
        if (!settled.equals(config.status)) {
            config.status = settled;
            SageMakerStateSupport.putFor(schedulerConfigs, config.accountId, key(config.region, config.id), config);
        }
        return Optional.of(config);
    }

    private Optional<ComputeQuotaResource> settleComputeQuota(ComputeQuotaResource quota) {
        String settled = settledStatus(quota.status, quota.lastModifiedTime);
        if (settled == null) {
            SageMakerStateSupport.deleteFor(computeQuotas, quota.accountId, key(quota.region, quota.id));
            return Optional.empty();
        }
        if (!settled.equals(quota.status)) {
            quota.status = settled;
            SageMakerStateSupport.putFor(computeQuotas, quota.accountId, key(quota.region, quota.id), quota);
        }
        return Optional.of(quota);
    }

    /** The status a policy resource has reached by now; {@code null} once its deletion completed. */
    private String settledStatus(String status, long since) {
        if (clock.millis() < since + TRANSITION_DURATION.toMillis()) {
            return status;
        }
        return switch (status) {
            case "Creating" -> "Created";
            case "Updating" -> "Updated";
            case "Deleting" -> null;
            default -> status;
        };
    }

    private void scheduleClusterSettle(ClusterResource cluster) {
        String accountId = cluster.accountId;
        String clusterKey = key(cluster.region, cluster.clusterName);
        scheduler.schedule(TRANSITION_DURATION.plusMillis(50), () -> {
            synchronized (this) {
                SageMakerStateSupport.getFor(clusters, accountId, clusterKey).ifPresent(this::settleCluster);
            }
        });
    }

    private void schedule(String accountId, String resourceKey, boolean schedulerConfig) {
        scheduler.schedule(TRANSITION_DURATION.plusMillis(50), () -> {
            synchronized (this) {
                if (schedulerConfig) {
                    SageMakerStateSupport.getFor(schedulerConfigs, accountId, resourceKey).ifPresent(this::settleSchedulerConfig);
                } else {
                    SageMakerStateSupport.getFor(computeQuotas, accountId, resourceKey).ifPresent(this::settleComputeQuota);
                }
            }
        });
    }

    private void publishClusterStateChange(ClusterResource cluster) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("ClusterArn", cluster.clusterArn);
        detail.put("ClusterName", cluster.clusterName);
        detail.put("ClusterStatus", cluster.clusterStatus);
        detail.put("CreationTime", Instant.ofEpochMilli(cluster.creationTime).toString());
        events.publish(cluster.region, cluster.accountId, CLUSTER_STATE_CHANGE, cluster.clusterArn, detail);
    }

    // ─────────────────────────── Lookups and validation ───────────────────────────

    private Optional<ClusterResource> cluster(String region, String nameOrArn) {
        if (nameOrArn.startsWith("arn:")) {
            String[] parts = nameOrArn.split(":", 6);
            if (parts.length < 6 || !parts[5].startsWith("cluster/") || !parts[4].equals(regionResolver.getAccountId())) {
                return Optional.empty();
            }
            return clusters.scan(k -> k.startsWith(parts[3] + "::")).stream()
                    .filter(c -> c.clusterArn.equals(nameOrArn)).findFirst()
                    .flatMap(this::settleCluster);
        }
        return clusters.get(key(region, nameOrArn)).flatMap(this::settleCluster);
    }

    /** Cluster policies and compute quotas attach only to an in-service, EKS-orchestrated cluster. */
    private ClusterResource eksClusterForPolicy(String region, String clusterArn) {
        ClusterResource cluster = cluster(region, clusterArn)
                .orElseThrow(() -> validation("Cluster " + clusterArn + " does not exist."));
        if (cluster.orchestrator == null || !(cluster.orchestrator.get("Eks") instanceof Map<?, ?>)) {
            throw validation("Cluster " + clusterArn + " is not orchestrated by Amazon EKS. Task governance "
                    + "requires an EKS-orchestrated cluster.");
        }
        if (!"InService".equals(cluster.clusterStatus)) {
            throw validation("Cluster " + clusterArn + " is in " + cluster.clusterStatus + " status; it must be InService.");
        }
        return cluster;
    }

    private ClusterSchedulerConfigResource schedulerConfig(String region, String id) {
        validateId(id, "ClusterSchedulerConfigId");
        return SageMakerStateSupport.getFor(schedulerConfigs, regionResolver.getAccountId(), key(region, id))
                .flatMap(this::settleSchedulerConfig)
                .orElseThrow(() -> new AwsException("ResourceNotFound",
                        "Cluster policy with id " + id + " not found.", 400));
    }

    private ComputeQuotaResource computeQuota(String region, String id) {
        validateId(id, "ComputeQuotaId");
        return SageMakerStateSupport.getFor(computeQuotas, regionResolver.getAccountId(), key(region, id))
                .flatMap(this::settleComputeQuota)
                .orElseThrow(() -> new AwsException("ResourceNotFound",
                        "Compute quota with id " + id + " not found.", 400));
    }

    private static void validateId(String id, String field) {
        if (!RESOURCE_ID.matcher(id).matches()) {
            throw validation("1 validation error detected: Value '" + id + "' at '" + Character.toLowerCase(field.charAt(0))
                    + field.substring(1) + "' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                    + RESOURCE_ID.pattern());
        }
    }

    private static void validatePolicyName(String name) {
        if (!POLICY_NAME.matcher(name).matches()) {
            throw validation("Name " + name + " must satisfy regular expression pattern: " + POLICY_NAME.pattern());
        }
    }

    private static void requireSettled(String status, String what) {
        if (FAILED_OR_DELETED.contains(status) || "Creating".equals(status) || "Updating".equals(status)) {
            throw new AwsException("ConflictException", "The " + what + " is in " + status
                    + " status and cannot be updated.", 400);
        }
    }

    private static void requireTargetVersion(JsonNode request, int current, String what) {
        JsonNode target = request.path("TargetVersion");
        if (!target.canConvertToInt()) {
            throw validation("TargetVersion is required.");
        }
        if (target.asInt() != current) {
            throw new AwsException("ConflictException", "TargetVersion " + target.asInt() + " does not match the current version "
                    + current + " of the " + what + ".", 400);
        }
    }

    private static void validateSchedulerConfig(JsonNode config) {
        if (!config.isObject()) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (JsonNode priorityClass : config.path("PriorityClasses")) {
            String name = required(priorityClass, "Name");
            if (!PRIORITY_CLASS_NAME.matcher(name).matches()) {
                throw validation("PriorityClass name " + name + " must satisfy regular expression pattern: "
                        + PRIORITY_CLASS_NAME.pattern());
            }
            if (!names.add(name)) {
                throw validation("PriorityClass name " + name + " is duplicated.");
            }
            JsonNode weight = priorityClass.path("Weight");
            if (!weight.canConvertToInt() || weight.asInt() < 0 || weight.asInt() > 100) {
                throw validation("PriorityClass " + name + " Weight must be between 0 and 100.");
            }
        }
        String fairShare = text(config, "FairShare");
        if (fairShare != null && !Set.of("Enabled", "Disabled").contains(fairShare)) {
            throw validation("FairShare must be one of [Enabled, Disabled].");
        }
    }

    private static void validateQuotaTarget(JsonNode target) {
        if (!target.isObject()) {
            throw validation("ComputeQuotaTarget is required.");
        }
        String team = required(target, "TeamName");
        if (!TEAM_NAME.matcher(team).matches()) {
            throw validation("ComputeQuotaTarget.TeamName " + team + " must satisfy regular expression pattern: "
                    + TEAM_NAME.pattern());
        }
        JsonNode weight = target.path("FairShareWeight");
        if (!weight.isMissingNode() && (!weight.canConvertToInt() || weight.asInt() < 0 || weight.asInt() > 100)) {
            throw validation("ComputeQuotaTarget.FairShareWeight must be between 0 and 100.");
        }
    }

    private static void validateQuotaConfig(JsonNode config) {
        if (!config.isObject()) {
            return;
        }
        for (JsonNode resource : config.path("ComputeQuotaResources")) {
            String type = required(resource, "InstanceType");
            if (!type.startsWith("ml.")) {
                throw validation("ComputeQuotaResources InstanceType " + type + " is not valid.");
            }
            for (String field : List.of("Count", "Accelerators", "VCpu", "MemoryInGiB")) {
                JsonNode value = resource.path(field);
                if (!value.isMissingNode() && (!value.isNumber() || value.asDouble() < 0)) {
                    throw validation("ComputeQuotaResources " + field + " must be a non-negative number.");
                }
            }
        }
        JsonNode sharing = config.path("ResourceSharingConfig");
        String strategy = text(sharing, "Strategy");
        if (sharing.isObject() && (strategy == null || !Set.of("Lend", "DontLend", "LendAndBorrow").contains(strategy))) {
            throw validation("ResourceSharingConfig.Strategy must be one of [Lend, DontLend, LendAndBorrow].");
        }
        String preempt = text(config, "PreemptTeamTasks");
        if (preempt != null && !Set.of("Never", "LowerPriority").contains(preempt)) {
            throw validation("PreemptTeamTasks must be one of [Never, LowerPriority].");
        }
    }

    private static String activationState(String value) {
        if (value == null) {
            return "Enabled";
        }
        if (!Set.of("Enabled", "Disabled").contains(value)) {
            throw validation("ActivationState must be one of [Enabled, Disabled].");
        }
        return value;
    }

    private static <T> Predicate<T> createdWithin(JsonNode request, String afterField, String beforeField,
                                                  java.util.function.ToLongFunction<T> creationTime) {
        JsonNode after = request.path(afterField);
        JsonNode before = request.path(beforeField);
        return item -> (!after.isNumber() || creationTime.applyAsLong(item) > (long) (after.asDouble() * 1000))
                && (!before.isNumber() || creationTime.applyAsLong(item) < (long) (before.asDouble() * 1000));
    }

    private static <T> List<T> sorted(List<T> items, Comparator<T> comparator, JsonNode request) {
        return sorted(items, comparator, request, "Descending");
    }

    private static <T> List<T> sorted(List<T> items, Comparator<T> comparator, JsonNode request, String defaultOrder) {
        String order = Optional.ofNullable(text(request, "SortOrder")).orElse(defaultOrder);
        if (!Set.of("Ascending", "Descending").contains(order)) {
            throw validation("SortOrder must be one of [Ascending, Descending].");
        }
        return items.stream().sorted("Descending".equals(order) ? comparator.reversed() : comparator).toList();
    }

    private <T> ObjectNode page(List<T> items, JsonNode request, String field,
                                java.util.function.BiConsumer<T, ObjectNode> render) {
        int max = boundedInt(request, "MaxResults", 10, 1, 100);
        int start = offsetToken(text(request, "NextToken"));
        ArrayNode out = mapper.createArrayNode();
        items.stream().skip(start).limit(max).forEach(item -> render.accept(item, out.addObject()));
        ObjectNode response = mapper.createObjectNode();
        response.set(field, out);
        if (start + max < items.size()) {
            response.put("NextToken", Integer.toString(start + max));
        }
        return response;
    }

    private static List<JsonNode> concat(JsonNode first, JsonNode second) {
        List<JsonNode> out = new ArrayList<>();
        first.forEach(out::add);
        second.forEach(out::add);
        return out;
    }

    private static AwsException clusterNotFound(String nameOrArn) {
        return new AwsException("ResourceNotFound", "Cluster " + nameOrArn + " not found.", 400);
    }

    private String newId() {
        char[] id = new char[12];
        for (int i = 0; i < id.length; i++) {
            id[i] = ID_ALPHABET[random.nextInt(ID_ALPHABET.length)];
        }
        return new String(id);
    }

    /** An EC2-style instance id, {@code i-} followed by 17 hex digits. */
    private String newInstanceId() {
        char[] id = new char[17];
        for (int i = 0; i < id.length; i++) {
            id[i] = HEX_ALPHABET[random.nextInt(HEX_ALPHABET.length)];
        }
        return "i-" + new String(id);
    }

    private String arn(String region, String resource) {
        return "arn:aws:sagemaker:" + region + ":" + regionResolver.getAccountId() + ":" + resource;
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }
}
