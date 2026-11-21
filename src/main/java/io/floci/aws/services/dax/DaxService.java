package io.floci.aws.services.dax;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.model.Vpc;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amazon DynamoDB Accelerator (DAX) control plane: clusters and their nodes, parameter groups and subnet groups,
 * with the events each change records and the SNS notifications a cluster's topic receives.
 * <p>
 * Clusters move through AWS's states on a timer ({@code floci.services.dax.transition-millis}): a new cluster and
 * new nodes are {@code creating}, a resized cluster is {@code modifying}, a rebooted node is {@code rebooting}
 * and a deleted cluster is {@code deleting} until the transition elapses. Subnet groups are built from the EC2
 * subnets they name, and a cluster created without one uses the {@code default} group over the default VPC.
 * Parameter changes apply to a cluster's nodes as they reboot, as DAX's {@code REQUIRES_REBOOT} parameters do.
 * The DAX data plane (the cluster endpoints on port 8111) is not served.
 */
@ApplicationScoped
public class DaxService implements Resettable {

    private static final Logger LOG = Logger.getLogger(DaxService.class);
    private static final Pattern NAME = Pattern.compile("^[A-Za-z](?!.*--)[A-Za-z0-9-]*(?<!-)$");
    private static final Pattern WINDOW = Pattern.compile(
            "^(sun|mon|tue|wed|thu|fri|sat):([01]\\d|2[0-3]):[0-5]\\d-(sun|mon|tue|wed|thu|fri|sat):([01]\\d|2[0-3]):[0-5]\\d$");
    private static final List<String> DAYS = List.of("sun", "mon", "tue", "wed", "thu", "fri", "sat");
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_NODES_PER_CLUSTER = 11;
    private static final int MAX_NODES = 50;
    private static final int MAX_CLUSTERS = 50;
    private static final int MAX_PARAMETER_GROUPS = 20;
    private static final int MAX_SUBNET_GROUPS = 50;
    private static final int MAX_SUBNETS = 20;
    private static final int MAX_TAGS = 50;
    private static final long EVENT_RETENTION_SECONDS = 14L * 24 * 3600;

    private final AccountAwareStorageBackend<ObjectNode> clusters;
    private final AccountAwareStorageBackend<ObjectNode> parameterGroups;
    private final AccountAwareStorageBackend<ObjectNode> subnetGroups;
    private final AccountAwareStorageBackend<ObjectNode> events;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;
    private final Ec2Service ec2;
    private final Instance<IamService> iam;
    private final Instance<SnsService> sns;
    private final long transitionMillis;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public DaxService(StorageFactory storageFactory, ObjectMapper mapper, RegionResolver regionResolver,
                      Ec2Service ec2, Instance<IamService> iam, Instance<SnsService> sns, EmulatorConfig config) {
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.clusters = storageFactory.create("dax", "clusters.json", type);
        this.parameterGroups = storageFactory.create("dax", "parameter-groups.json", type);
        this.subnetGroups = storageFactory.create("dax", "subnet-groups.json", type);
        this.events = storageFactory.create("dax", "events.json", type);
        this.mapper = mapper;
        this.regionResolver = regionResolver;
        this.ec2 = ec2;
        this.iam = iam;
        this.sns = sns;
        this.transitionMillis = config.services().dax().transitionMillis();
    }

    // ---------------------------------------------------------------- clusters

    public synchronized ObjectNode createCluster(JsonNode request, String region) {
        String account = clusters.accountId();
        String name = name(request, "ClusterName", 20);
        String nodeType = text(request, "NodeType", 1024);
        if (!DaxParameters.NODE_TYPES.contains(nodeType)) {
            throw invalidValue("Invalid node type: " + nodeType + ".");
        }
        int replication = request.path("ReplicationFactor").asInt(0);
        if (replication < 1) {
            throw invalidValue("ReplicationFactor must be at least 1.");
        }
        if (replication > MAX_NODES_PER_CLUSTER) {
            throw new AwsException("NodeQuotaForClusterExceededFault",
                    "A cluster may have at most " + MAX_NODES_PER_CLUSTER + " nodes.", 400);
        }
        if (clusters.getForAccount(account, key(region, name)).isPresent()) {
            throw new AwsException("ClusterAlreadyExistsFault", "Cluster " + name + " already exists.", 400);
        }
        List<ObjectNode> existing = regional(clusters, region);
        if (existing.size() >= MAX_CLUSTERS) {
            throw new AwsException("ClusterQuotaForCustomerExceededFault",
                    "The account has reached its limit of " + MAX_CLUSTERS + " clusters.", 400);
        }
        int nodes = existing.stream().mapToInt(cluster -> cluster.path("Nodes").size()).sum();
        if (nodes + replication > MAX_NODES) {
            throw new AwsException("NodeQuotaForCustomerExceededFault",
                    "The account has reached its limit of " + MAX_NODES + " nodes.", 400);
        }
        String roleArn = role(text(request, "IamRoleArn", 1024));
        ObjectNode subnetGroup = present(request, "SubnetGroupName")
                ? subnetGroup(region, name(request, "SubnetGroupName", 255))
                : defaultSubnetGroup(region);
        Set<String> zones = zones(subnetGroup);
        List<String> placement = placement(request, zones, replication, 0);
        String networkType = present(request, "NetworkType")
                ? oneOf(request, "NetworkType", Set.of("ipv4", "ipv6", "dual_stack")) : "ipv4";
        if (!supportedNetworkTypes(subnetGroup).contains(networkType)) {
            throw new AwsException("InvalidParameterCombinationException", "Subnet group "
                    + subnetGroup.path("SubnetGroupName").asText() + " does not support network type "
                    + networkType + ".", 400);
        }
        String parameterGroup = present(request, "ParameterGroupName")
                ? parameterGroup(region, groupName(request)).path("ParameterGroupName").asText()
                : DaxParameters.DEFAULT_GROUP;
        String encryption = present(request, "ClusterEndpointEncryptionType")
                ? oneOf(request, "ClusterEndpointEncryptionType", Set.of("NONE", "TLS")) : "NONE";
        List<ObjectNode> tags = tags(request.path("Tags"));
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("TagQuotaPerResourceExceeded", "A cluster may have at most " + MAX_TAGS
                    + " tags.", 400);
        }

        String id = randomId(6);
        String suffix = "." + id + ".dax-clusters." + region + "." + AwsRegions.dnsSuffixFor(region);
        int port = "TLS".equals(encryption) ? 9111 : 8111;
        String scheme = "TLS".equals(encryption) ? "daxs://" : "dax://";
        long now = System.currentTimeMillis();

        ObjectNode cluster = mapper.createObjectNode();
        cluster.put("ClusterName", name);
        if (present(request, "Description")) {
            cluster.put("Description", text(request, "Description", 1024));
        }
        cluster.put("ClusterArn", regionResolver.buildArn("dax", region, account, "cache/" + name));
        cluster.put("NodeType", nodeType);
        cluster.put("Status", "creating");
        ObjectNode discovery = cluster.putObject("ClusterDiscoveryEndpoint");
        discovery.put("Address", name + suffix);
        discovery.put("Port", port);
        discovery.put("URL", scheme + name + suffix + ":" + port);
        ArrayNode nodeList = cluster.putArray("Nodes");
        for (String zone : placement) {
            nodeList.add(node(cluster, zone, suffix, port, now));
        }
        cluster.put("PreferredMaintenanceWindow", present(request, "PreferredMaintenanceWindow")
                ? window(text(request, "PreferredMaintenanceWindow", 64)) : defaultWindow(name));
        if (present(request, "NotificationTopicArn")) {
            ObjectNode notification = cluster.putObject("NotificationConfiguration");
            notification.put("TopicArn", topic(text(request, "NotificationTopicArn", 1024)));
            notification.put("TopicStatus", "active");
        }
        cluster.put("SubnetGroup", subnetGroup.path("SubnetGroupName").asText());
        ArrayNode securityGroups = cluster.putArray("SecurityGroups");
        for (String group : securityGroups(request, region, subnetGroup.path("VpcId").asText())) {
            securityGroups.addObject().put("SecurityGroupIdentifier", group).put("Status", "active");
        }
        cluster.put("IamRoleArn", roleArn);
        ObjectNode parameterStatus = cluster.putObject("ParameterGroup");
        parameterStatus.put("ParameterGroupName", parameterGroup);
        parameterStatus.put("ParameterApplyStatus", "in-sync");
        parameterStatus.putArray("NodeIdsToReboot");
        boolean sse = request.path("SSESpecification").path("Enabled").asBoolean(false);
        cluster.putObject("SSEDescription").put("Status", sse ? "ENABLED" : "DISABLED");
        cluster.put("ClusterEndpointEncryptionType", encryption);
        cluster.put("NetworkType", networkType);

        ObjectNode internal = cluster.putObject("_");
        internal.put("region", region);
        internal.put("suffix", suffix);
        internal.put("readyAt", now + transitionMillis);
        internal.set("tags", mapper.valueToTree(tags));
        clusters.putForAccount(account, key(region, name), cluster);
        event(region, name, "CLUSTER", "Cluster created");
        return clusterResponse(cluster);
    }

    public synchronized ObjectNode describeClusters(JsonNode request, String region) {
        List<ObjectNode> result = new ArrayList<>();
        if (present(request, "ClusterNames")) {
            for (String name : strings(request, "ClusterNames")) {
                result.add(cluster(region, name.toLowerCase()));
            }
        } else {
            for (ObjectNode cluster : regional(clusters, region)) {
                ObjectNode settled = settle(cluster);
                if (settled != null) {
                    result.add(settled);
                }
            }
            result.sort(Comparator.comparing(cluster -> cluster.path("ClusterName").asText()));
        }
        return page(result.stream().map(this::view).toList(), request, "Clusters",
                cluster -> cluster.path("ClusterName").asText());
    }

    public synchronized ObjectNode updateCluster(JsonNode request, String region) {
        ObjectNode cluster = available(region, text(request, "ClusterName", 20));
        String name = cluster.path("ClusterName").asText();
        if (present(request, "Description")) {
            cluster.put("Description", text(request, "Description", 1024));
        }
        if (present(request, "PreferredMaintenanceWindow")) {
            cluster.put("PreferredMaintenanceWindow", window(text(request, "PreferredMaintenanceWindow", 64)));
        }
        if (present(request, "NotificationTopicArn")) {
            ObjectNode notification = cluster.putObject("NotificationConfiguration");
            notification.put("TopicArn", topic(text(request, "NotificationTopicArn", 1024)));
            notification.put("TopicStatus", "active");
        }
        if (present(request, "NotificationTopicStatus")) {
            String status = oneOf(request, "NotificationTopicStatus", Set.of("active", "inactive"));
            if (!cluster.has("NotificationConfiguration")) {
                throw new AwsException("InvalidParameterCombinationException",
                        "NotificationTopicStatus requires a notification topic.", 400);
            }
            ((ObjectNode) cluster.get("NotificationConfiguration")).put("TopicStatus", status);
        }
        if (present(request, "ParameterGroupName")) {
            String group = parameterGroup(region, groupName(request))
                    .path("ParameterGroupName").asText();
            ObjectNode status = (ObjectNode) cluster.get("ParameterGroup");
            if (!group.equals(status.path("ParameterGroupName").asText())) {
                status.put("ParameterGroupName", group);
                pendingReboot(cluster);
            }
        }
        if (present(request, "SecurityGroupIds")) {
            String vpc = subnetGroup(region, cluster.path("SubnetGroup").asText()).path("VpcId").asText();
            ArrayNode groups = cluster.putArray("SecurityGroups");
            for (String group : securityGroups(request, region, vpc)) {
                groups.addObject().put("SecurityGroupIdentifier", group).put("Status", "active");
            }
        }
        save(cluster);
        event(region, name, "CLUSTER", "Cluster updated");
        return clusterResponse(cluster);
    }

    public synchronized ObjectNode deleteCluster(JsonNode request, String region) {
        ObjectNode cluster = cluster(region, text(request, "ClusterName", 20).toLowerCase());
        if ("deleting".equals(cluster.path("Status").asText())) {
            throw new AwsException("InvalidClusterStateFault", "Cluster " + cluster.path("ClusterName").asText()
                    + " is already being deleted.", 400);
        }
        cluster.put("Status", "deleting");
        for (JsonNode node : cluster.path("Nodes")) {
            ((ObjectNode) node).put("NodeStatus", "deleting");
        }
        ((ObjectNode) cluster.get("_")).put("readyAt", System.currentTimeMillis() + transitionMillis);
        save(cluster);
        return clusterResponse(cluster);
    }

    public synchronized ObjectNode increaseReplicationFactor(JsonNode request, String region) {
        ObjectNode cluster = available(region, text(request, "ClusterName", 20));
        int current = cluster.path("Nodes").size();
        int target = request.path("NewReplicationFactor").asInt(0);
        if (target <= current) {
            throw invalidValue("NewReplicationFactor must be greater than the current replication factor ("
                    + current + ").");
        }
        if (target > MAX_NODES_PER_CLUSTER) {
            throw new AwsException("NodeQuotaForClusterExceededFault",
                    "A cluster may have at most " + MAX_NODES_PER_CLUSTER + " nodes.", 400);
        }
        int accountNodes = regional(clusters, region).stream().mapToInt(value -> value.path("Nodes").size()).sum();
        if (accountNodes + target - current > MAX_NODES) {
            throw new AwsException("NodeQuotaForCustomerExceededFault",
                    "The account has reached its limit of " + MAX_NODES + " nodes.", 400);
        }
        Set<String> zones = zones(subnetGroup(region, cluster.path("SubnetGroup").asText()));
        List<String> placement = placement(request, zones, target - current, current);
        long now = System.currentTimeMillis();
        ArrayNode nodes = (ArrayNode) cluster.get("Nodes");
        String suffix = cluster.path("_").path("suffix").asText();
        int port = cluster.path("ClusterDiscoveryEndpoint").path("Port").asInt();
        for (String zone : placement) {
            nodes.add(node(cluster, zone, suffix, port, now));
        }
        cluster.put("Status", "modifying");
        ((ObjectNode) cluster.get("_")).put("readyAt", now + transitionMillis);
        save(cluster);
        event(region, cluster.path("ClusterName").asText(), "CLUSTER",
                "Increasing replication factor to " + target);
        return clusterResponse(cluster);
    }

    public synchronized ObjectNode decreaseReplicationFactor(JsonNode request, String region) {
        ObjectNode cluster = available(region, text(request, "ClusterName", 20));
        ArrayNode nodes = (ArrayNode) cluster.get("Nodes");
        int current = nodes.size();
        int target = request.path("NewReplicationFactor").asInt(0);
        if (target < 1 || target >= current) {
            throw invalidValue("NewReplicationFactor must be at least 1 and less than the current replication "
                    + "factor (" + current + ").");
        }
        List<String> remove = new ArrayList<>();
        if (present(request, "NodeIdsToRemove")) {
            Set<String> ids = new HashSet<>();
            nodes.forEach(node -> ids.add(node.path("NodeId").asText()));
            for (String nodeId : strings(request, "NodeIdsToRemove")) {
                if (!ids.contains(nodeId)) {
                    throw new AwsException("NodeNotFoundFault", "Node " + nodeId + " not found.", 404);
                }
                remove.add(nodeId);
            }
            if (remove.size() != current - target) {
                throw new AwsException("InvalidParameterCombinationException",
                        "NodeIdsToRemove must name " + (current - target) + " nodes.", 400);
            }
        } else if (present(request, "AvailabilityZones")) {
            List<String> zones = strings(request, "AvailabilityZones");
            if (zones.size() != current - target) {
                throw new AwsException("InvalidParameterCombinationException",
                        "AvailabilityZones must name one zone for each of the " + (current - target)
                                + " nodes to remove.", 400);
            }
            for (String zone : zones) {
                String found = null;
                for (int index = nodes.size() - 1; index >= 0; index--) {
                    String nodeId = nodes.get(index).path("NodeId").asText();
                    if (zone.equals(nodes.get(index).path("AvailabilityZone").asText()) && !remove.contains(nodeId)) {
                        found = nodeId;
                        break;
                    }
                }
                if (found == null) {
                    throw invalidValue("The cluster has no node to remove in " + zone + ".");
                }
                remove.add(found);
            }
        } else {
            for (int index = current - 1; index >= target; index--) {
                remove.add(nodes.get(index).path("NodeId").asText());
            }
        }
        for (JsonNode node : nodes) {
            if (remove.contains(node.path("NodeId").asText())) {
                ((ObjectNode) node).put("NodeStatus", "deleting");
            }
        }
        ArrayNode toRemove = cluster.putArray("NodeIdsToRemove");
        remove.forEach(toRemove::add);
        cluster.put("Status", "modifying");
        ((ObjectNode) cluster.get("_")).put("readyAt", System.currentTimeMillis() + transitionMillis);
        save(cluster);
        event(region, cluster.path("ClusterName").asText(), "CLUSTER",
                "Decreasing replication factor to " + target);
        return clusterResponse(cluster);
    }

    public synchronized ObjectNode rebootNode(JsonNode request, String region) {
        ObjectNode cluster = available(region, text(request, "ClusterName", 20));
        String nodeId = text(request, "NodeId", 1024);
        ObjectNode node = null;
        for (JsonNode candidate : cluster.path("Nodes")) {
            if (nodeId.equals(candidate.path("NodeId").asText())) {
                node = (ObjectNode) candidate;
            }
        }
        if (node == null) {
            throw new AwsException("NodeNotFoundFault", "Node " + nodeId + " not found.", 404);
        }
        if (!"available".equals(node.path("NodeStatus").asText())) {
            throw new AwsException("InvalidClusterStateFault", "Node " + nodeId + " is "
                    + node.path("NodeStatus").asText() + ".", 400);
        }
        node.put("NodeStatus", "rebooting");
        node.put("_readyAt", System.currentTimeMillis() + transitionMillis);
        save(cluster);
        event(region, cluster.path("ClusterName").asText(), "CLUSTER", "Rebooting node " + nodeId);
        return clusterResponse(cluster);
    }

    // ---------------------------------------------------------------- tags

    public synchronized ObjectNode listTags(JsonNode request, String region) {
        ObjectNode cluster = taggable(text(request, "ResourceName", 1024), region);
        ObjectNode response = mapper.createObjectNode();
        response.set("Tags", cluster.path("_").path("tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode request, String region) {
        ObjectNode cluster = taggable(text(request, "ResourceName", 1024), region);
        Map<String, String> current = tagMap(cluster);
        for (ObjectNode tag : tags(request.path("Tags"))) {
            current.put(tag.path("Key").asText(), tag.path("Value").asText());
        }
        if (current.size() > MAX_TAGS) {
            throw new AwsException("TagQuotaPerResourceExceeded", "A cluster may have at most " + MAX_TAGS
                    + " tags.", 400);
        }
        return storeTags(cluster, current);
    }

    public synchronized ObjectNode untagResource(JsonNode request, String region) {
        ObjectNode cluster = taggable(text(request, "ResourceName", 1024), region);
        Map<String, String> current = tagMap(cluster);
        for (String key : strings(request, "TagKeys")) {
            if (current.remove(key) == null) {
                throw new AwsException("TagNotFoundFault", "Tag " + key + " not found.", 404);
            }
        }
        return storeTags(cluster, current);
    }

    // ---------------------------------------------------------------- parameter groups

    public synchronized ObjectNode createParameterGroup(JsonNode request, String region) {
        String account = parameterGroups.accountId();
        String name = groupName(request);
        if (DaxParameters.DEFAULT_GROUP.equals(name) || parameterGroups.getForAccount(account, key(region, name))
                .isPresent()) {
            throw new AwsException("ParameterGroupAlreadyExistsFault", "Parameter group " + name
                    + " already exists.", 400);
        }
        if (regional(parameterGroups, region).size() >= MAX_PARAMETER_GROUPS) {
            throw new AwsException("ParameterGroupQuotaExceededFault",
                    "The account has reached its limit of " + MAX_PARAMETER_GROUPS + " parameter groups.", 400);
        }
        ObjectNode group = mapper.createObjectNode();
        group.put("ParameterGroupName", name);
        group.put("Description", present(request, "Description") ? text(request, "Description", 1024) : "");
        group.putObject("_").put("region", region).putObject("values");
        parameterGroups.putForAccount(account, key(region, name), group);
        event(region, name, "PARAMETER_GROUP", "Parameter group created");
        ObjectNode response = mapper.createObjectNode();
        response.set("ParameterGroup", strip(group));
        return response;
    }

    public synchronized ObjectNode describeParameterGroups(JsonNode request, String region) {
        List<ObjectNode> result = new ArrayList<>();
        if (present(request, "ParameterGroupNames")) {
            for (String name : strings(request, "ParameterGroupNames")) {
                result.add(strip(parameterGroup(region, name.toLowerCase())));
            }
        } else {
            result.add(strip(defaultParameterGroup()));
            regional(parameterGroups, region).stream()
                    .sorted(Comparator.comparing(group -> group.path("ParameterGroupName").asText()))
                    .forEach(group -> result.add(strip(group)));
        }
        return page(result, request, "ParameterGroups", group -> group.path("ParameterGroupName").asText());
    }

    public synchronized ObjectNode updateParameterGroup(JsonNode request, String region) {
        String name = groupName(request);
        ObjectNode group = parameterGroup(region, name);
        if (DaxParameters.DEFAULT_GROUP.equals(name)) {
            throw new AwsException("InvalidParameterGroupStateFault",
                    "The default parameter group cannot be modified.", 400);
        }
        JsonNode values = request.path("ParameterNameValues");
        if (!values.isArray() || values.isEmpty() || values.size() > 20) {
            throw invalidValue("ParameterNameValues must contain between 1 and 20 parameters.");
        }
        ObjectNode stored = (ObjectNode) group.get("_").get("values");
        for (JsonNode value : values) {
            String parameter = text(value, "ParameterName", 1024);
            if (!DaxParameters.PARAMETERS.containsKey(parameter)) {
                throw invalidValue("Unknown parameter: " + parameter + ".");
            }
            String number = text(value, "ParameterValue", 1024);
            try {
                if (Long.parseLong(number) < 0) {
                    throw invalidValue(parameter + " must not be negative.");
                }
            } catch (NumberFormatException e) {
                throw invalidValue(parameter + " must be an integer.");
            }
            stored.put(parameter, number);
        }
        parameterGroups.putForAccount(parameterGroups.accountId(), key(region, name), group);
        for (ObjectNode cluster : regional(clusters, region)) {
            if (name.equals(cluster.path("ParameterGroup").path("ParameterGroupName").asText())) {
                pendingReboot(cluster);
                save(cluster);
            }
        }
        event(region, name, "PARAMETER_GROUP", "Parameter group updated");
        ObjectNode response = mapper.createObjectNode();
        response.set("ParameterGroup", strip(group));
        return response;
    }

    public synchronized ObjectNode deleteParameterGroup(JsonNode request, String region) {
        String name = groupName(request);
        parameterGroup(region, name);
        if (DaxParameters.DEFAULT_GROUP.equals(name)) {
            throw new AwsException("InvalidParameterGroupStateFault",
                    "The default parameter group cannot be deleted.", 400);
        }
        for (ObjectNode cluster : regional(clusters, region)) {
            if (name.equals(cluster.path("ParameterGroup").path("ParameterGroupName").asText())) {
                throw new AwsException("InvalidParameterGroupStateFault", "Parameter group " + name
                        + " is in use by cluster " + cluster.path("ClusterName").asText() + ".", 400);
            }
        }
        parameterGroups.deleteForAccount(parameterGroups.accountId(), key(region, name));
        event(region, name, "PARAMETER_GROUP", "Parameter group deleted");
        return mapper.createObjectNode().put("DeletionMessage", "Parameter group " + name + " has been deleted.");
    }

    public synchronized ObjectNode describeParameters(JsonNode request, String region) {
        ObjectNode group = parameterGroup(region, groupName(request));
        String source = present(request, "Source") ? oneOf(request, "Source", Set.of("system", "user")) : null;
        JsonNode values = group.path("_").path("values");
        List<ObjectNode> parameters = new ArrayList<>();
        for (String parameter : DaxParameters.names()) {
            ObjectNode described = DaxParameters.describe(mapper, parameter,
                    values.hasNonNull(parameter) ? values.get(parameter).asText() : null);
            if (source == null || source.equals(described.path("Source").asText())) {
                parameters.add(described);
            }
        }
        return page(parameters, request, "Parameters", parameter -> parameter.path("ParameterName").asText());
    }

    public ObjectNode describeDefaultParameters(JsonNode request) {
        List<ObjectNode> parameters = new ArrayList<>();
        for (String parameter : DaxParameters.names()) {
            parameters.add(DaxParameters.describe(mapper, parameter, null));
        }
        return page(parameters, request, "Parameters", parameter -> parameter.path("ParameterName").asText());
    }

    // ---------------------------------------------------------------- subnet groups

    public synchronized ObjectNode createSubnetGroup(JsonNode request, String region) {
        String account = subnetGroups.accountId();
        String name = name(request, "SubnetGroupName", 255);
        if (subnetGroups.getForAccount(account, key(region, name)).isPresent()) {
            throw new AwsException("SubnetGroupAlreadyExistsFault", "Subnet group " + name + " already exists.", 400);
        }
        if (regional(subnetGroups, region).size() >= MAX_SUBNET_GROUPS) {
            throw new AwsException("SubnetGroupQuotaExceededFault",
                    "The account has reached its limit of " + MAX_SUBNET_GROUPS + " subnet groups.", 400);
        }
        ObjectNode group = buildSubnetGroup(region, name,
                present(request, "Description") ? text(request, "Description", 1024) : "",
                strings(request, "SubnetIds"));
        subnetGroups.putForAccount(account, key(region, name), group);
        event(region, name, "SUBNET_GROUP", "Subnet group created");
        ObjectNode response = mapper.createObjectNode();
        response.set("SubnetGroup", strip(group));
        return response;
    }

    public synchronized ObjectNode describeSubnetGroups(JsonNode request, String region) {
        List<ObjectNode> result = new ArrayList<>();
        if (present(request, "SubnetGroupNames")) {
            for (String name : strings(request, "SubnetGroupNames")) {
                result.add(strip(subnetGroup(region, name.toLowerCase())));
            }
        } else {
            regional(subnetGroups, region).stream()
                    .sorted(Comparator.comparing(group -> group.path("SubnetGroupName").asText()))
                    .forEach(group -> result.add(strip(group)));
        }
        return page(result, request, "SubnetGroups", group -> group.path("SubnetGroupName").asText());
    }

    public synchronized ObjectNode updateSubnetGroup(JsonNode request, String region) {
        String name = name(request, "SubnetGroupName", 255);
        ObjectNode group = subnetGroup(region, name);
        String description = present(request, "Description")
                ? text(request, "Description", 1024) : group.path("Description").asText();
        ObjectNode updated = group;
        if (present(request, "SubnetIds")) {
            updated = buildSubnetGroup(region, name, description, strings(request, "SubnetIds"));
            if (!updated.path("VpcId").asText().equals(group.path("VpcId").asText()) && inUse(region, name)) {
                throw new AwsException("SubnetInUse", "Subnet group " + name
                        + " is in use and cannot move to another VPC.", 400);
            }
            Set<String> zones = zones(updated);
            for (ObjectNode cluster : regional(clusters, region)) {
                if (!name.equals(cluster.path("SubnetGroup").asText())) {
                    continue;
                }
                for (JsonNode node : cluster.path("Nodes")) {
                    if (!zones.contains(node.path("AvailabilityZone").asText())) {
                        throw new AwsException("SubnetInUse", "A subnet in "
                                + node.path("AvailabilityZone").asText() + " is in use by cluster "
                                + cluster.path("ClusterName").asText() + ".", 400);
                    }
                }
            }
        }
        updated.put("Description", description);
        subnetGroups.putForAccount(subnetGroups.accountId(), key(region, name), updated);
        event(region, name, "SUBNET_GROUP", "Subnet group updated");
        ObjectNode response = mapper.createObjectNode();
        response.set("SubnetGroup", strip(updated));
        return response;
    }

    public synchronized ObjectNode deleteSubnetGroup(JsonNode request, String region) {
        String name = name(request, "SubnetGroupName", 255);
        subnetGroup(region, name);
        if (inUse(region, name)) {
            throw new AwsException("SubnetGroupInUseFault", "Subnet group " + name + " is in use.", 400);
        }
        subnetGroups.deleteForAccount(subnetGroups.accountId(), key(region, name));
        event(region, name, "SUBNET_GROUP", "Subnet group deleted");
        return mapper.createObjectNode().put("DeletionMessage", "Subnet group " + name + " has been deleted.");
    }

    // ---------------------------------------------------------------- events

    public synchronized ObjectNode describeEvents(JsonNode request, String region) {
        regional(clusters, region).forEach(this::settle);
        long now = Instant.now().getEpochSecond();
        long end = present(request, "EndTime") ? epoch(request.get("EndTime")) : now;
        long start;
        if (present(request, "StartTime")) {
            start = epoch(request.get("StartTime"));
        } else {
            int minutes = request.path("Duration").asInt(60);
            if (minutes < 0) {
                throw invalidValue("Duration must not be negative.");
            }
            start = end - minutes * 60L;
        }
        if (start > end) {
            throw new AwsException("InvalidParameterCombinationException", "StartTime must precede EndTime.", 400);
        }
        String source = present(request, "SourceName") ? text(request, "SourceName", 1024).toLowerCase() : null;
        String type = present(request, "SourceType")
                ? oneOf(request, "SourceType", Set.of("CLUSTER", "PARAMETER_GROUP", "SUBNET_GROUP")) : null;
        if (source != null && type == null) {
            throw new AwsException("InvalidParameterCombinationException",
                    "SourceType is required when SourceName is given.", 400);
        }
        List<ObjectNode> matching = new ArrayList<>();
        for (ObjectNode event : regional(events, region)) {
            long date = event.path("Date").asLong();
            if (date < start || date > end || (type != null && !type.equals(event.path("SourceType").asText()))
                    || (source != null && !source.equals(event.path("SourceName").asText()))) {
                continue;
            }
            matching.add(event);
        }
        matching.sort(Comparator.comparing((ObjectNode event) -> event.path("_").path("sequence").asLong())
                .reversed());
        return page(matching.stream().map(this::strip).toList(), request, "Events",
                event -> Long.toString(event.path("Date").asLong()) + "/" + event.path("SourceName").asText()
                        + "/" + event.path("Message").asText());
    }

    @Override
    public synchronized void clear() {
        clusters.clear();
        parameterGroups.clear();
        subnetGroups.clear();
        events.clear();
    }

    // ---------------------------------------------------------------- state

    /** Applies elapsed transitions to a stored cluster; returns the cluster, or {@code null} once it is deleted. */
    private ObjectNode settle(ObjectNode cluster) {
        long now = System.currentTimeMillis();
        ObjectNode internal = (ObjectNode) cluster.get("_");
        String region = internal.path("region").asText();
        String name = cluster.path("ClusterName").asText();
        boolean changed = false;
        boolean due = now >= internal.path("readyAt").asLong();
        String status = cluster.path("Status").asText();
        if (due && "deleting".equals(status)) {
            event(region, name, "CLUSTER", "Cluster deleted");
            clusters.deleteForAccount(clusters.accountId(), key(region, name));
            return null;
        }
        if (due && ("creating".equals(status) || "modifying".equals(status))) {
            ArrayNode kept = mapper.createArrayNode();
            List<String> added = new ArrayList<>();
            for (JsonNode node : cluster.path("Nodes")) {
                if ("deleting".equals(node.path("NodeStatus").asText())) {
                    continue;
                }
                if ("creating".equals(node.path("NodeStatus").asText())) {
                    ((ObjectNode) node).put("NodeStatus", "available");
                    added.add(node.path("NodeId").asText());
                }
                kept.add(node);
            }
            List<String> removed = new ArrayList<>();
            cluster.path("NodeIdsToRemove").forEach(id -> removed.add(id.asText()));
            cluster.set("Nodes", kept);
            cluster.remove("NodeIdsToRemove");
            cluster.put("Status", "available");
            ArrayNode reboot = (ArrayNode) cluster.path("ParameterGroup").path("NodeIdsToReboot");
            for (int index = reboot.size() - 1; index >= 0; index--) {
                if (removed.contains(reboot.get(index).asText())) {
                    reboot.remove(index);
                }
            }
            if (reboot.isEmpty()) {
                ((ObjectNode) cluster.get("ParameterGroup")).put("ParameterApplyStatus", "in-sync");
            }
            if ("creating".equals(status)) {
                event(region, name, "CLUSTER", "Cluster is available");
            } else {
                if (!added.isEmpty()) {
                    event(region, name, "CLUSTER", "Added node(s) " + String.join(", ", added));
                }
                if (!removed.isEmpty()) {
                    event(region, name, "CLUSTER", "Removed node(s) " + String.join(", ", removed));
                }
            }
            changed = true;
        }
        for (JsonNode candidate : cluster.path("Nodes")) {
            ObjectNode node = (ObjectNode) candidate;
            if ("rebooting".equals(node.path("NodeStatus").asText()) && now >= node.path("_readyAt").asLong()) {
                node.put("NodeStatus", "available");
                node.remove("_readyAt");
                String nodeId = node.path("NodeId").asText();
                ArrayNode reboot = (ArrayNode) cluster.path("ParameterGroup").path("NodeIdsToReboot");
                for (int index = reboot.size() - 1; index >= 0; index--) {
                    if (nodeId.equals(reboot.get(index).asText())) {
                        reboot.remove(index);
                    }
                }
                node.put("ParameterGroupStatus", "in-sync");
                if (reboot.isEmpty()) {
                    ((ObjectNode) cluster.get("ParameterGroup")).put("ParameterApplyStatus", "in-sync");
                }
                event(region, name, "CLUSTER", "Rebooted node " + nodeId);
                changed = true;
            }
        }
        if (changed) {
            save(cluster);
        }
        return cluster;
    }

    private void pendingReboot(ObjectNode cluster) {
        ObjectNode status = (ObjectNode) cluster.get("ParameterGroup");
        ArrayNode reboot = status.putArray("NodeIdsToReboot");
        for (JsonNode node : cluster.path("Nodes")) {
            if (!"deleting".equals(node.path("NodeStatus").asText())) {
                reboot.add(node.path("NodeId").asText());
                ((ObjectNode) node).put("ParameterGroupStatus", "pending-reboot");
            }
        }
        status.put("ParameterApplyStatus", reboot.isEmpty() ? "in-sync" : "pending-reboot");
    }

    private ObjectNode node(ObjectNode cluster, String zone, String suffix, int port, long now) {
        Set<String> used = new HashSet<>();
        cluster.path("Nodes").forEach(node -> used.add(node.path("NodeId").asText()));
        String name = cluster.path("ClusterName").asText();
        String nodeId = null;
        for (char letter = 'a'; letter <= 'z' && nodeId == null; letter++) {
            if (!used.contains(name + "-" + letter)) {
                nodeId = name + "-" + letter;
            }
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("NodeId", nodeId);
        ObjectNode endpoint = node.putObject("Endpoint");
        endpoint.put("Address", nodeId + suffix.replace(".dax-clusters.", ".nodes.dax-clusters."));
        endpoint.put("Port", port);
        node.put("NodeCreateTime", now / 1000.0);
        node.put("AvailabilityZone", zone);
        node.put("NodeStatus", "creating");
        node.put("ParameterGroupStatus", "in-sync");
        return node;
    }

    private List<String> placement(JsonNode request, Set<String> zones, int count, int offset) {
        if (present(request, "AvailabilityZones")) {
            List<String> requested = strings(request, "AvailabilityZones");
            if (requested.size() != count) {
                throw new AwsException("InvalidParameterCombinationException",
                        "AvailabilityZones must name one zone for each of the " + count + " new nodes.", 400);
            }
            for (String zone : requested) {
                if (!zones.contains(zone)) {
                    throw invalidValue("The subnet group has no subnet in " + zone + ".");
                }
            }
            return requested;
        }
        List<String> ordered = new ArrayList<>(new TreeSet<>(zones));
        List<String> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(ordered.get((offset + index) % ordered.size()));
        }
        return result;
    }

    private ObjectNode buildSubnetGroup(String region, String name, String description, List<String> subnetIds) {
        if (subnetIds.isEmpty()) {
            throw new AwsException("InvalidSubnet", "SubnetIds must name at least one subnet.", 400);
        }
        if (subnetIds.size() > MAX_SUBNETS) {
            throw new AwsException("SubnetQuotaExceededFault", "A subnet group may have at most " + MAX_SUBNETS
                    + " subnets.", 400);
        }
        List<Subnet> subnets;
        try {
            subnets = ec2.requireSubnets(region, new ArrayList<>(new LinkedHashSet<>(subnetIds)));
        } catch (AwsException e) {
            throw new AwsException("InvalidSubnet", e.getMessage(), 400);
        }
        Set<String> vpcs = new HashSet<>();
        subnets.forEach(subnet -> vpcs.add(subnet.getVpcId()));
        if (vpcs.size() != 1) {
            throw new AwsException("InvalidSubnet", "All subnets in a subnet group must belong to the same VPC.", 400);
        }
        ObjectNode group = mapper.createObjectNode();
        group.put("SubnetGroupName", name);
        group.put("Description", description);
        group.put("VpcId", vpcs.iterator().next());
        ArrayNode list = group.putArray("Subnets");
        Set<String> common = null;
        for (Subnet subnet : subnets) {
            ObjectNode entry = list.addObject();
            entry.put("SubnetIdentifier", subnet.getSubnetId());
            entry.put("SubnetAvailabilityZone", subnet.getAvailabilityZone());
            Set<String> types = new LinkedHashSet<>();
            boolean ipv6 = subnet.getIpv6CidrBlockAssociationSet() != null
                    && !subnet.getIpv6CidrBlockAssociationSet().isEmpty();
            types.add("ipv4");
            if (ipv6) {
                types.add("ipv6");
                types.add("dual_stack");
            }
            ArrayNode supported = entry.putArray("SupportedNetworkTypes");
            types.forEach(supported::add);
            if (common == null) {
                common = new LinkedHashSet<>(types);
            } else {
                common.retainAll(types);
            }
        }
        ArrayNode supported = group.putArray("SupportedNetworkTypes");
        common.forEach(supported::add);
        group.putObject("_").put("region", region);
        return group;
    }

    private ObjectNode defaultSubnetGroup(String region) {
        String account = subnetGroups.accountId();
        ObjectNode existing = subnetGroups.getForAccount(account, key(region, "default")).orElse(null);
        if (existing != null) {
            return existing;
        }
        Vpc vpc = ec2.describeVpcs(region, List.of(), Map.of()).stream().filter(Vpc::isDefault).findFirst()
                .orElseThrow(() -> new AwsException("InvalidVPCNetworkStateFault",
                        "The account has no default VPC; specify a SubnetGroupName.", 400));
        List<String> subnetIds = ec2.describeSubnets(region, List.of(), Map.of("vpc-id", List.of(vpc.getVpcId())))
                .stream().filter(Subnet::isDefaultForAz).map(Subnet::getSubnetId).sorted().toList();
        if (subnetIds.isEmpty()) {
            throw new AwsException("InvalidVPCNetworkStateFault", "The default VPC has no default subnets.", 400);
        }
        ObjectNode group = buildSubnetGroup(region, "default", "Default subnet group for the default VPC",
                subnetIds.subList(0, Math.min(MAX_SUBNETS, subnetIds.size())));
        subnetGroups.putForAccount(account, key(region, "default"), group);
        return group;
    }

    private List<String> securityGroups(JsonNode request, String region, String vpcId) {
        if (!present(request, "SecurityGroupIds")) {
            return ec2.describeSecurityGroups(region, List.of(), List.of(),
                            Map.of("vpc-id", List.of(vpcId), "group-name", List.of("default")))
                    .stream().map(SecurityGroup::getGroupId).limit(1).toList();
        }
        List<String> ids = strings(request, "SecurityGroupIds");
        List<SecurityGroup> groups;
        try {
            groups = ec2.describeSecurityGroups(region, ids, List.of(), Map.of());
        } catch (AwsException e) {
            throw invalidValue(e.getMessage());
        }
        Set<String> found = new HashSet<>();
        for (SecurityGroup group : groups) {
            if (!vpcId.equals(group.getVpcId())) {
                throw new AwsException("InvalidParameterCombinationException", "Security group "
                        + group.getGroupId() + " is not in the subnet group's VPC " + vpcId + ".", 400);
            }
            found.add(group.getGroupId());
        }
        for (String id : ids) {
            if (!found.contains(id)) {
                throw invalidValue("Security group " + id + " does not exist.");
            }
        }
        return ids;
    }

    /** The role must exist and trust {@code dax.amazonaws.com}, or DAX cannot assume it. */
    private String role(String roleArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(roleArn);
        } catch (IllegalArgumentException e) {
            throw invalidValue("IamRoleArn must be an IAM role ARN.");
        }
        if (!"iam".equals(arn.service()) || !arn.resource().startsWith("role/")) {
            throw invalidValue("IamRoleArn must be an IAM role ARN.");
        }
        if (iam.isResolvable()) {
            String roleName = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
            IamRole role;
            try {
                role = iam.get().getRole(roleName);
            } catch (AwsException e) {
                throw invalidValue("No permission to assume role: " + roleArn);
            }
            String trust = role.getAssumeRolePolicyDocument();
            String decoded = trust == null ? "" : URLDecoder.decode(trust, StandardCharsets.UTF_8);
            if (!decoded.contains("dax.amazonaws.com")) { // partition-literal: the DAX service principal
                throw invalidValue("No permission to assume role: " + roleArn);
            }
        }
        return roleArn;
    }

    private String topic(String topicArn) {
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(topicArn);
            if (!"sns".equals(arn.service())) {
                throw invalidValue("NotificationTopicArn must be an SNS topic ARN.");
            }
        } catch (IllegalArgumentException e) {
            throw invalidValue("NotificationTopicArn must be an SNS topic ARN.");
        }
        return topicArn;
    }

    private void event(String region, String sourceName, String sourceType, String message) {
        String account = events.accountId();
        long now = Instant.now().getEpochSecond();
        List<ObjectNode> stored = regional(events, region);
        long sequence = stored.stream().mapToLong(event -> event.path("_").path("sequence").asLong()).max().orElse(0) + 1;
        for (ObjectNode old : stored) {
            if (old.path("Date").asLong() < now - EVENT_RETENTION_SECONDS) {
                events.deleteForAccount(account, key(region, old.path("_").path("id").asText()));
            }
        }
        ObjectNode event = mapper.createObjectNode();
        event.put("SourceName", sourceName);
        event.put("SourceType", sourceType);
        event.put("Message", message);
        event.put("Date", now);
        String id = String.format("%020d", sequence);
        event.putObject("_").put("region", region).put("sequence", sequence).put("id", id);
        events.putForAccount(account, key(region, id), event);
        if ("CLUSTER".equals(sourceType)) {
            notify(region, sourceName, message);
        }
    }

    private void notify(String region, String clusterName, String message) {
        ObjectNode cluster = clusters.getForAccount(clusters.accountId(), key(region, clusterName)).orElse(null);
        JsonNode notification = cluster == null ? null : cluster.get("NotificationConfiguration");
        if (notification == null || !"active".equals(notification.path("TopicStatus").asText())
                || !sns.isResolvable()) {
            return;
        }
        try {
            sns.get().publish(notification.path("TopicArn").asText(), null,
                    "DAX cluster " + clusterName + ": " + message, "DAX Notification Message", region);
        } catch (RuntimeException e) {
            LOG.debugv("DAX notification to {0} failed: {1}", notification.path("TopicArn").asText(),
                    e.getMessage());
        }
    }

    // ---------------------------------------------------------------- lookups

    private ObjectNode cluster(String region, String name) {
        ObjectNode cluster = clusters.getForAccount(clusters.accountId(), key(region, name)).orElse(null);
        ObjectNode settled = cluster == null ? null : settle(cluster);
        if (settled == null) {
            throw new AwsException("ClusterNotFoundFault", "Cluster " + name + " not found.", 404);
        }
        return settled;
    }

    private ObjectNode available(String region, String name) {
        ObjectNode cluster = cluster(region, name.toLowerCase());
        if (!"available".equals(cluster.path("Status").asText())) {
            throw new AwsException("InvalidClusterStateFault", "Cluster " + cluster.path("ClusterName").asText()
                    + " is " + cluster.path("Status").asText() + ".", 400);
        }
        return cluster;
    }

    private ObjectNode taggable(String resourceName, String region) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceName);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidARNFault", "Invalid ARN: " + resourceName, 400);
        }
        if (!"dax".equals(arn.service()) || !arn.resource().startsWith("cache/")) {
            throw new AwsException("InvalidARNFault", "Invalid ARN: " + resourceName, 400);
        }
        ObjectNode cluster = cluster(arn.region().isEmpty() ? region : arn.region(),
                arn.resource().substring("cache/".length()).toLowerCase());
        if ("deleting".equals(cluster.path("Status").asText())) {
            throw new AwsException("InvalidClusterStateFault", "Cluster " + cluster.path("ClusterName").asText()
                    + " is deleting.", 400);
        }
        return cluster;
    }

    private ObjectNode parameterGroup(String region, String name) {
        if (DaxParameters.DEFAULT_GROUP.equals(name)) {
            return defaultParameterGroup();
        }
        return parameterGroups.getForAccount(parameterGroups.accountId(), key(region, name))
                .orElseThrow(() -> new AwsException("ParameterGroupNotFoundFault", "Parameter group " + name
                        + " not found.", 404));
    }

    private ObjectNode defaultParameterGroup() {
        ObjectNode group = mapper.createObjectNode();
        group.put("ParameterGroupName", DaxParameters.DEFAULT_GROUP);
        group.put("Description", "Default parameter group for dax1.0");
        group.putObject("_").putObject("values");
        return group;
    }

    private ObjectNode subnetGroup(String region, String name) {
        return subnetGroups.getForAccount(subnetGroups.accountId(), key(region, name))
                .orElseThrow(() -> new AwsException("SubnetGroupNotFoundFault", "Subnet group " + name
                        + " not found.", 404));
    }

    private boolean inUse(String region, String subnetGroup) {
        for (ObjectNode cluster : regional(clusters, region)) {
            ObjectNode settled = settle(cluster);
            if (settled != null && subnetGroup.equals(settled.path("SubnetGroup").asText())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> zones(JsonNode subnetGroup) {
        Set<String> zones = new TreeSet<>();
        subnetGroup.path("Subnets").forEach(subnet -> zones.add(subnet.path("SubnetAvailabilityZone").asText()));
        return zones;
    }

    private static Set<String> supportedNetworkTypes(JsonNode subnetGroup) {
        Set<String> types = new HashSet<>();
        subnetGroup.path("SupportedNetworkTypes").forEach(type -> types.add(type.asText()));
        return types;
    }

    private List<ObjectNode> regional(AccountAwareStorageBackend<ObjectNode> storage, String region) {
        String prefix = region + "/";
        return storage.scanForAccount(storage.accountId(), key -> key.startsWith(prefix));
    }

    private void save(ObjectNode cluster) {
        clusters.putForAccount(clusters.accountId(),
                key(cluster.path("_").path("region").asText(), cluster.path("ClusterName").asText()), cluster);
    }

    private ObjectNode storeTags(ObjectNode cluster, Map<String, String> tags) {
        ArrayNode list = mapper.createArrayNode();
        tags.forEach((key, value) -> list.addObject().put("Key", key).put("Value", value));
        ((ObjectNode) cluster.get("_")).set("tags", list);
        save(cluster);
        ObjectNode response = mapper.createObjectNode();
        response.set("Tags", list.deepCopy());
        return response;
    }

    private static Map<String, String> tagMap(ObjectNode cluster) {
        Map<String, String> tags = new LinkedHashMap<>();
        cluster.path("_").path("tags").forEach(tag -> tags.put(tag.path("Key").asText(), tag.path("Value").asText()));
        return tags;
    }

    private List<ObjectNode> tags(JsonNode input) {
        List<ObjectNode> result = new ArrayList<>();
        if (input == null || input.isMissingNode() || input.isNull()) {
            return result;
        }
        if (!input.isArray()) {
            throw invalidValue("Tags must be a list.");
        }
        for (JsonNode tag : input) {
            String key = text(tag, "Key", 128);
            if (key.isEmpty() || key.regionMatches(true, 0, "aws:", 0, 4)) {
                throw invalidValue("Tag keys must not be empty or start with aws:.");
            }
            result.add(mapper.createObjectNode().put("Key", key)
                    .put("Value", tag.hasNonNull("Value") ? text(tag, "Value", 256) : ""));
        }
        return result;
    }

    // ---------------------------------------------------------------- views

    private ObjectNode clusterResponse(ObjectNode cluster) {
        ObjectNode response = mapper.createObjectNode();
        response.set("Cluster", view(cluster));
        return response;
    }

    private ObjectNode view(ObjectNode cluster) {
        ObjectNode view = strip(cluster);
        ArrayNode nodes = (ArrayNode) view.path("Nodes");
        int active = 0;
        for (JsonNode node : nodes) {
            ((ObjectNode) node).remove("_readyAt");
            if ("available".equals(node.path("NodeStatus").asText())) {
                active++;
            }
        }
        int removing = view.path("NodeIdsToRemove").size();
        view.put("TotalNodes", nodes.size() - removing);
        view.put("ActiveNodes", active);
        return view;
    }

    private ObjectNode strip(ObjectNode stored) {
        ObjectNode copy = stored.deepCopy();
        copy.remove("_");
        return copy;
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode request, String field, Function<ObjectNode, String> cursor) {
        Integer max = null;
        if (present(request, "MaxResults")) {
            int value = request.get("MaxResults").asInt();
            if (value < 20 || value > 100) {
                throw invalidValue("MaxResults must be between 20 and 100.");
            }
            max = value;
        }
        String token = present(request, "NextToken") ? text(request, "NextToken", 8192) : null;
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items, cursor, max == null ? 100 : max, token,
                field, bad -> invalidValue("Invalid NextToken."));
        ObjectNode response = mapper.createObjectNode();
        response.putArray(field).addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextToken", paged.nextToken());
        }
        return response;
    }

    // ---------------------------------------------------------------- input

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private static String name(JsonNode request, String field, int maximum) {
        String value = text(request, field, maximum);
        if (value.isEmpty() || !NAME.matcher(value).matches()) {
            throw invalidValue(field + " must start with a letter, contain only letters, digits and hyphens, and "
                    + "not end with a hyphen or contain two consecutive hyphens.");
        }
        return value.toLowerCase();
    }

    /** A parameter group name: the dotted default group's, or a user group's. */
    private static String groupName(JsonNode request) {
        return DaxParameters.DEFAULT_GROUP.equals(request.path("ParameterGroupName").asText())
                ? DaxParameters.DEFAULT_GROUP : name(request, "ParameterGroupName", 255);
    }

    private static String window(String value) {
        String lower = value.toLowerCase();
        Matcher matcher = WINDOW.matcher(lower);
        if (!matcher.matches()) {
            throw invalidValue("PreferredMaintenanceWindow must be in the format ddd:hh24:mi-ddd:hh24:mi.");
        }
        int start = minuteOfWeek(lower.substring(0, 9));
        int end = minuteOfWeek(lower.substring(10));
        int length = Math.floorMod(end - start, 7 * 24 * 60);
        if (length < 60) {
            throw invalidValue("PreferredMaintenanceWindow must be at least 60 minutes long.");
        }
        return lower;
    }

    private static int minuteOfWeek(String value) {
        return DAYS.indexOf(value.substring(0, 3)) * 1440 + Integer.parseInt(value.substring(4, 6)) * 60
                + Integer.parseInt(value.substring(7, 9));
    }

    /** A weekly hour-long window chosen from the cluster name, as AWS picks one when none is given. */
    private static String defaultWindow(String name) {
        int hash = Math.floorMod(name.hashCode(), 7 * 24);
        String day = DAYS.get(hash / 24);
        int hour = hash % 24;
        String nextDay = hour == 23 ? DAYS.get((hash / 24 + 1) % 7) : day;
        return String.format("%s:%02d:00-%s:%02d:00", day, hour, nextDay, (hour + 1) % 24);
    }

    private String randomId(int length) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < length; index++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    private static long epoch(JsonNode value) {
        if (value.isNumber()) {
            return (long) value.asDouble();
        }
        try {
            return Instant.parse(value.asText()).getEpochSecond();
        } catch (RuntimeException e) {
            throw invalidValue("Invalid timestamp: " + value.asText());
        }
    }

    private static String oneOf(JsonNode request, String field, Set<String> allowed) {
        String value = text(request, field, 1024);
        if (!allowed.contains(value)) {
            throw invalidValue(field + " must be one of " + String.join(", ", new TreeSet<>(allowed)) + ".");
        }
        return value;
    }

    private static List<String> strings(JsonNode request, String field) {
        JsonNode value = request.path(field);
        if (!value.isArray()) {
            throw invalidValue(field + " must be a list of strings.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw invalidValue(field + " must be a list of strings.");
            }
            result.add(element.textValue());
        }
        return result;
    }

    private static String text(JsonNode parent, String field, int maximum) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isTextual()) {
            throw invalidValue(field + " is required.");
        }
        if (value.textValue().length() > maximum) {
            throw invalidValue(field + " must be at most " + maximum + " characters.");
        }
        return value.textValue();
    }

    private static boolean present(JsonNode node, String field) {
        return node != null && node.hasNonNull(field);
    }

    private static AwsException invalidValue(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }
}
