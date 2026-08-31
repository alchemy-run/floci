package io.github.hectorvent.floci.services.dax;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dax.model.Cluster;
import io.github.hectorvent.floci.services.dax.model.DaxEvent;
import io.github.hectorvent.floci.services.dax.model.DaxSubnet;
import io.github.hectorvent.floci.services.dax.model.Node;
import io.github.hectorvent.floci.services.dax.model.Parameter;
import io.github.hectorvent.floci.services.dax.model.ParameterGroup;
import io.github.hectorvent.floci.services.dax.model.SubnetGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;

/**
 * DynamoDB Accelerator (DAX) control plane. Clusters become {@code available}
 * immediately — there is no DAX data-plane cache process.
 */
@ApplicationScoped
public class DaxService implements Resettable {

    private static final Logger LOG = Logger.getLogger(DaxService.class);
    static final String DEFAULT_PARAMETER_GROUP = "default.dax1.0";
    static final String DEFAULT_GROUP_NAME = DEFAULT_PARAMETER_GROUP;
    static final String QUERY_TTL = "query-ttl-millis";
    static final String RECORD_TTL = "record-ttl-millis";
    static final String DEFAULT_NODE_TYPE = "dax.t3.small";
    static final int PLAIN_PORT = 8111;
    static final int TLS_PORT = 9111;
    private static final int MAX_EVENTS = 100;
    private static final Pattern CLUSTER_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9\\-]{0,19}");

    static final Map<String, String> DEFAULT_PARAMETERS = Map.of(
            "query-ttl-millis", "300000",
            "record-ttl-millis", "300000");

    private final StorageBackend<String, Cluster> clusters;
    private final StorageBackend<String, ParameterGroup> parameterGroups;
    private final StorageBackend<String, SubnetGroup> subnetGroups;
    private final ConcurrentLinkedDeque<DaxEvent> events = new ConcurrentLinkedDeque<>();
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Ec2Service ec2Service;

    @Inject
    public DaxService(StorageFactory storageFactory, EmulatorConfig config,
                      RegionResolver regionResolver, Ec2Service ec2Service) {
        this.clusters = storageFactory.create("dax", "dax-clusters.json",
                new TypeReference<Map<String, Cluster>>() {});
        this.parameterGroups = storageFactory.create("dax", "dax-parameter-groups.json",
                new TypeReference<Map<String, ParameterGroup>>() {});
        this.subnetGroups = storageFactory.create("dax", "dax-subnet-groups.json",
                new TypeReference<Map<String, SubnetGroup>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.ec2Service = ec2Service;
        ensureDefaultParameterGroup();
    }

    DaxService(StorageBackend<String, ParameterGroup> parameterGroups) {
        this(new InMemoryStorage<>(), parameterGroups, new InMemoryStorage<>(), null, null, null);
    }

    DaxService(StorageBackend<String, Cluster> clusters,
               StorageBackend<String, ParameterGroup> parameterGroups,
               StorageBackend<String, SubnetGroup> subnetGroups,
               EmulatorConfig config, RegionResolver regionResolver, Ec2Service ec2Service) {
        this.clusters = clusters;
        this.parameterGroups = parameterGroups;
        this.subnetGroups = subnetGroups;
        this.config = config;
        this.regionResolver = regionResolver;
        this.ec2Service = ec2Service;
        ensureDefaultParameterGroup();
    }

    @Override
    public void clear() {
        clusters.clear();
        parameterGroups.clear();
        subnetGroups.clear();
        events.clear();
        ensureDefaultParameterGroup();
    }

    // ──────────────────────────── Clusters ────────────────────────────

    public Cluster createCluster(Cluster spec, String region) {
        int factor = spec.getNodes() == null || spec.getNodes().isEmpty() ? 1 : spec.getNodes().size();
        return createCluster(spec, factor, region);
    }

    public Cluster createCluster(Cluster spec, int replicationFactor, String region) {
        String name = requireName(spec.getClusterName(), "ClusterName");
        if (!CLUSTER_NAME.matcher(name).matches()) {
            throw invalid("ClusterName must be 1-20 alphanumeric characters or hyphens and start with a letter.");
        }
        if (clusters.get(name).isPresent()) {
            throw new AwsException("ClusterAlreadyExistsFault",
                    "Cluster with name " + name + " already exists.", 400);
        }
        if (spec.getIamRoleArn() == null || spec.getIamRoleArn().isBlank()) {
            throw invalid("IamRoleArn is required.");
        }
        if (replicationFactor < 1) {
            throw invalid("ReplicationFactor must be at least 1.");
        }

        String subnetGroupName = spec.getSubnetGroupName();
        if (subnetGroupName != null && !subnetGroupName.isBlank()) {
            requireSubnetGroup(subnetGroupName);
        }

        String parameterGroupName = spec.getParameterGroupName();
        if (parameterGroupName == null || parameterGroupName.isBlank()) {
            parameterGroupName = DEFAULT_PARAMETER_GROUP;
        } else {
            requireParameterGroup(parameterGroupName);
        }

        boolean tls = "TLS".equalsIgnoreCase(spec.getClusterEndpointEncryptionType());
        int port = tls ? TLS_PORT : PLAIN_PORT;
        String host = discoveryHost(name, region);
        String scheme = tls ? "daxs" : "dax";

        Cluster cluster = new Cluster();
        cluster.setClusterName(name);
        cluster.setDescription(spec.getDescription());
        cluster.setClusterArn(regionResolver.buildArn("dax", region, "cache/" + name));
        cluster.setNodeType(spec.getNodeType() != null && !spec.getNodeType().isBlank()
                ? spec.getNodeType() : DEFAULT_NODE_TYPE);
        cluster.setStatus("available");
        cluster.setDiscoveryAddress(host);
        cluster.setDiscoveryPort(port);
        cluster.setDiscoveryUrl(scheme + "://" + host + ":" + port);
        cluster.setPreferredMaintenanceWindow(spec.getPreferredMaintenanceWindow());
        cluster.setNotificationTopicArn(spec.getNotificationTopicArn());
        cluster.setSubnetGroupName(subnetGroupName);
        cluster.setIamRoleArn(spec.getIamRoleArn());
        cluster.setParameterGroupName(parameterGroupName);
        cluster.setSseEnabled(spec.isSseEnabled());
        cluster.setClusterEndpointEncryptionType(tls ? "TLS" : "NONE");
        cluster.setNetworkType(spec.getNetworkType() != null && !spec.getNetworkType().isBlank()
                ? spec.getNetworkType() : "ipv4");
        cluster.setRegion(region);
        cluster.setAvailabilityZones(spec.getAvailabilityZones());
        cluster.setSecurityGroupIds(spec.getSecurityGroupIds());
        cluster.setTags(spec.getTags());
        cluster.setNodes(buildNodes(cluster, replicationFactor, Instant.now().getEpochSecond()));

        clusters.put(name, cluster);
        recordEvent(name, "CLUSTER", "Created cluster " + name);
        LOG.infov("DAX cluster {0} created", name);
        return cluster;
    }

    public List<Cluster> describeClusters(List<String> names) {
        if (names == null || names.isEmpty()) {
            return clusters.values();
        }
        List<Cluster> result = new ArrayList<>();
        for (String name : names) {
            result.add(requireCluster(name));
        }
        return result;
    }

    public Cluster updateCluster(Cluster patch) {
        Cluster cluster = requireCluster(patch.getClusterName());
        if (patch.getDescription() != null) {
            cluster.setDescription(patch.getDescription());
        }
        if (patch.getPreferredMaintenanceWindow() != null) {
            cluster.setPreferredMaintenanceWindow(patch.getPreferredMaintenanceWindow());
        }
        if (patch.getNotificationTopicArn() != null) {
            cluster.setNotificationTopicArn(patch.getNotificationTopicArn());
        }
        if (patch.getParameterGroupName() != null && !patch.getParameterGroupName().isBlank()) {
            requireParameterGroup(patch.getParameterGroupName());
            cluster.setParameterGroupName(patch.getParameterGroupName());
        }
        if (patch.getSecurityGroupIds() != null && !patch.getSecurityGroupIds().isEmpty()) {
            cluster.setSecurityGroupIds(patch.getSecurityGroupIds());
        }
        clusters.put(cluster.getClusterName(), cluster);
        recordEvent(cluster.getClusterName(), "CLUSTER", "Updated cluster " + cluster.getClusterName());
        return cluster;
    }

    public Cluster deleteCluster(String name) {
        Cluster cluster = requireCluster(name);
        cluster.setStatus("deleting");
        clusters.delete(name);
        recordEvent(name, "CLUSTER", "Deleted cluster " + name);
        return cluster;
    }

    public Cluster increaseReplicationFactor(String name, int newFactor, List<String> availabilityZones) {
        Cluster cluster = requireCluster(name);
        int current = cluster.getNodes().size();
        if (newFactor <= current) {
            throw invalid("NewReplicationFactor must be greater than the current replication factor ("
                    + current + ").");
        }
        if (availabilityZones != null && !availabilityZones.isEmpty()) {
            cluster.setAvailabilityZones(availabilityZones);
        }
        cluster.setNodes(buildNodes(cluster, newFactor, Instant.now().getEpochSecond()));
        clusters.put(name, cluster);
        recordEvent(name, "CLUSTER", "Increased replication factor of " + name + " to " + newFactor);
        return cluster;
    }

    public Cluster decreaseReplicationFactor(String name, int newFactor, List<String> nodeIdsToRemove) {
        Cluster cluster = requireCluster(name);
        int current = cluster.getNodes().size();
        if (newFactor < 1) {
            throw invalid("NewReplicationFactor must be at least 1.");
        }
        if (newFactor >= current) {
            throw invalid("NewReplicationFactor must be less than the current replication factor ("
                    + current + ").");
        }
        List<Node> remaining = new ArrayList<>(cluster.getNodes());
        if (nodeIdsToRemove != null && !nodeIdsToRemove.isEmpty()) {
            remaining.removeIf(n -> nodeIdsToRemove.contains(n.getNodeId()));
        }
        while (remaining.size() > newFactor) {
            remaining.remove(remaining.size() - 1);
        }
        cluster.setNodes(remaining);
        clusters.put(name, cluster);
        recordEvent(name, "CLUSTER", "Decreased replication factor of " + name + " to " + newFactor);
        return cluster;
    }

    public Cluster rebootNode(String name, String nodeId) {
        Cluster cluster = requireCluster(name);
        Node node = cluster.getNodes().stream()
                .filter(n -> nodeId.equals(n.getNodeId()))
                .findFirst()
                .orElseThrow(() -> new AwsException("NodeNotFoundFault",
                        "Node " + nodeId + " was not found.", 404));
        node.setNodeStatus("rebooting");
        clusters.put(name, cluster);
        recordEvent(name, "CLUSTER", "Rebooted node " + nodeId);
        return cluster;
    }

    // ──────────────────────────── Parameter groups ────────────────────────────

    public ParameterGroup createParameterGroup(String name, String description) {
        name = requireName(name, "ParameterGroupName");
        if (parameterGroups.get(name).isPresent()) {
            throw new AwsException("ParameterGroupAlreadyExistsFault",
                    "Parameter group " + name + " already exists.", 400);
        }
        ParameterGroup group = new ParameterGroup();
        group.setParameterGroupName(name);
        group.setDescription(description);
        group.setParameters(new LinkedHashMap<>(DEFAULT_PARAMETERS));
        parameterGroups.put(name, group);
        recordEvent(name, "PARAMETER_GROUP", "Created parameter group " + name);
        return group;
    }

    public List<ParameterGroup> describeParameterGroups(List<String> names) {
        ensureDefaultParameterGroup();
        if (names == null || names.isEmpty()) {
            return parameterGroups.values();
        }
        List<ParameterGroup> result = new ArrayList<>();
        for (String name : names) {
            result.add(requireParameterGroup(name));
        }
        return result;
    }

    public Map<String, String> describeParameters(String name) {
        ParameterGroup group = requireParameterGroup(name);
        Map<String, String> values = new LinkedHashMap<>(DEFAULT_PARAMETERS);
        values.putAll(group.getParameters());
        return values;
    }

    public List<Parameter> describeParameters(String name, String sourceFilter) {
        ParameterGroup group = requireParameterGroup(name);
        Map<String, String> values = describeParameters(name);
        List<Parameter> parameters = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String defaultValue = DEFAULT_PARAMETERS.get(entry.getKey());
            boolean overridden = defaultValue != null && !defaultValue.equals(entry.getValue());
            String source = group.getSources().getOrDefault(entry.getKey(), overridden ? "user" : "system");
            if (sourceFilter != null && !sourceFilter.isBlank() && !sourceFilter.equals(source)) {
                continue;
            }
            Parameter parameter = new Parameter();
            parameter.setParameterName(entry.getKey());
            parameter.setParameterType("DEFAULT");
            parameter.setParameterValue(entry.getValue());
            parameter.setSource(source);
            parameter.setDataType("integer");
            parameter.setIsModifiable("true");
            parameter.setChangeType("immediate");
            parameters.add(parameter);
        }
        return parameters;
    }

    public Map<String, String> describeDefaultParameters() {
        return new LinkedHashMap<>(DEFAULT_PARAMETERS);
    }

    public ParameterGroup updateParameterGroup(String name, Map<String, String> updates) {
        ParameterGroup group = requireParameterGroup(name);
        if (updates != null) {
            for (Map.Entry<String, String> entry : updates.entrySet()) {
                if (!DEFAULT_PARAMETERS.containsKey(entry.getKey())) {
                    throw invalid("Unknown parameter " + entry.getKey() + ".");
                }
                group.getParameters().put(entry.getKey(), entry.getValue());
                group.getSources().put(entry.getKey(), "user");
            }
        }
        parameterGroups.put(name, group);
        recordEvent(name, "PARAMETER_GROUP", "Updated parameter group " + name);
        return group;
    }

    public ParameterGroup updateParameterGroup(String name, List<Map.Entry<String, String>> values) {
        Map<String, String> updates = new LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, String> entry : values) {
                updates.put(entry.getKey(), entry.getValue());
            }
        }
        return updateParameterGroup(name, updates);
    }

    public String deleteParameterGroup(String name) {
        requireParameterGroup(name);
        if (DEFAULT_PARAMETER_GROUP.equals(name)) {
            throw new AwsException("InvalidParameterGroupStateFault",
                    "The default parameter group cannot be deleted.", 400);
        }
        boolean inUse = clusters.values().stream()
                .anyMatch(c -> name.equals(c.getParameterGroupName()));
        if (inUse) {
            throw new AwsException("InvalidParameterGroupStateFault",
                    "Parameter group " + name + " is in use.", 400);
        }
        parameterGroups.delete(name);
        return "Parameter group " + name + " has been deleted.";
    }

    // ──────────────────────────── Subnet groups ────────────────────────────

    public SubnetGroup createSubnetGroup(String name, String description, List<String> subnetIds, String region) {
        name = requireName(name, "SubnetGroupName");
        if (subnetGroups.get(name).isPresent()) {
            throw new AwsException("SubnetGroupAlreadyExistsFault",
                    "Subnet group " + name + " already exists.", 400);
        }
        SubnetGroup group = new SubnetGroup();
        group.setSubnetGroupName(name);
        group.setDescription(description);
        applySubnets(group, subnetIds, region);
        subnetGroups.put(name, group);
        recordEvent(name, "SUBNET_GROUP", "Created subnet group " + name);
        return group;
    }

    public List<SubnetGroup> describeSubnetGroups(List<String> names) {
        if (names == null || names.isEmpty()) {
            return subnetGroups.values();
        }
        List<SubnetGroup> result = new ArrayList<>();
        for (String name : names) {
            result.add(requireSubnetGroup(name));
        }
        return result;
    }

    public SubnetGroup updateSubnetGroup(String name, String description, List<String> subnetIds, String region) {
        SubnetGroup group = requireSubnetGroup(name);
        if (description != null) {
            group.setDescription(description);
        }
        if (subnetIds != null && !subnetIds.isEmpty()) {
            applySubnets(group, subnetIds, region);
        }
        subnetGroups.put(name, group);
        return group;
    }

    public SubnetGroup deleteSubnetGroup(String name) {
        SubnetGroup group = requireSubnetGroup(name);
        boolean inUse = clusters.values().stream()
                .anyMatch(c -> name.equals(c.getSubnetGroupName()));
        if (inUse) {
            throw new AwsException("SubnetGroupInUseFault",
                    "Subnet group " + name + " is in use.", 400);
        }
        subnetGroups.delete(name);
        return group;
    }

    // ──────────────────────────── Events / tags ────────────────────────────

    public List<DaxEvent> describeEvents(String sourceName, String sourceType) {
        List<DaxEvent> result = new ArrayList<>();
        for (DaxEvent event : events) {
            if (sourceName != null && !sourceName.isBlank() && !sourceName.equals(event.getSourceName())) {
                continue;
            }
            if (sourceType != null && !sourceType.isBlank() && !sourceType.equalsIgnoreCase(event.getSourceType())) {
                continue;
            }
            result.add(event);
        }
        return result;
    }

    public Map<String, String> listTags(String resourceName) {
        return new LinkedHashMap<>(requireClusterByArn(resourceName).getTags());
    }

    public Map<String, String> tagResource(String resourceName, Map<String, String> tags) {
        Cluster cluster = requireClusterByArn(resourceName);
        if (tags != null) {
            cluster.getTags().putAll(tags);
        }
        clusters.put(cluster.getClusterName(), cluster);
        return new LinkedHashMap<>(cluster.getTags());
    }

    public Map<String, String> untagResource(String resourceName, List<String> tagKeys) {
        Cluster cluster = requireClusterByArn(resourceName);
        if (tagKeys != null) {
            tagKeys.forEach(cluster.getTags()::remove);
        }
        clusters.put(cluster.getClusterName(), cluster);
        return new LinkedHashMap<>(cluster.getTags());
    }

    // ──────────────────────────── Internals ────────────────────────────

    private Cluster requireCluster(String name) {
        String resolved = requireName(name, "ClusterName");
        return clusters.get(resolved).orElseThrow(() -> new AwsException("ClusterNotFoundFault",
                "Cluster " + resolved + " not found.", 404));
    }

    private Cluster requireClusterByArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("InvalidARNFault", "ResourceName is required.", 400);
        }
        return clusters.values().stream()
                .filter(c -> arn.equals(c.getClusterArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ClusterNotFoundFault",
                        "Cluster " + arn + " not found.", 404));
    }

    private ParameterGroup requireParameterGroup(String name) {
        String resolved = requireName(name, "ParameterGroupName");
        ensureDefaultParameterGroup();
        return parameterGroups.get(resolved).orElseThrow(() -> new AwsException("ParameterGroupNotFoundFault",
                "Parameter group " + resolved + " not found.", 404));
    }

    private SubnetGroup requireSubnetGroup(String name) {
        String resolved = requireName(name, "SubnetGroupName");
        return subnetGroups.get(resolved).orElseThrow(() -> new AwsException("SubnetGroupNotFoundFault",
                "Subnet group " + resolved + " not found.", 404));
    }

    private void ensureDefaultParameterGroup() {
        if (parameterGroups.get(DEFAULT_PARAMETER_GROUP).isPresent()) {
            return;
        }
        ParameterGroup group = new ParameterGroup();
        group.setParameterGroupName(DEFAULT_PARAMETER_GROUP);
        group.setDescription("Default DAX parameter group");
        group.setParameters(new LinkedHashMap<>(DEFAULT_PARAMETERS));
        parameterGroups.put(DEFAULT_PARAMETER_GROUP, group);
    }

    private void applySubnets(SubnetGroup group, List<String> subnetIds, String region) {
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw invalid("SubnetIds is required.");
        }
        List<DaxSubnet> resolved = new ArrayList<>();
        String vpcId = null;
        int azIndex = 0;
        for (String subnetId : subnetIds) {
            if (subnetId == null || subnetId.isBlank()) {
                throw new AwsException("InvalidSubnet", "SubnetIds contains an empty subnet id.", 400);
            }
            Subnet subnet = ec2Service != null
                    ? ec2Service.findSubnetById(region, subnetId).orElse(null)
                    : null;
            String az;
            String subnetVpc;
            if (subnet != null) {
                az = subnet.getAvailabilityZone();
                subnetVpc = subnet.getVpcId();
            } else {
                az = (region != null ? region : "us-east-1") + (char) ('a' + Math.min(azIndex, 25));
                subnetVpc = "vpc-00000000";
            }
            if (vpcId == null) {
                vpcId = subnetVpc;
            } else if (!vpcId.equals(subnetVpc)) {
                throw new AwsException("InvalidSubnet", "All subnets must belong to the same VPC.", 400);
            }
            resolved.add(new DaxSubnet(subnetId, az));
            azIndex++;
        }
        group.setVpcId(vpcId);
        group.setSubnets(resolved);
    }

    private List<Node> buildNodes(Cluster cluster, int count, long now) {
        List<Node> nodes = new ArrayList<>();
        List<String> azs = cluster.getAvailabilityZones();
        for (int i = 0; i < count; i++) {
            Node node = new Node();
            String nodeId = cluster.getClusterName() + "-" + String.format("%04d", i + 1);
            node.setNodeId(nodeId);
            String address = nodeId + ".node." + cluster.getDiscoveryAddress();
            node.setAddress(address);
            node.setPort(cluster.getDiscoveryPort());
            String scheme = cluster.getDiscoveryUrl().startsWith("daxs://") ? "daxs" : "dax";
            node.setUrl(scheme + "://" + address + ":" + cluster.getDiscoveryPort());
            node.setNodeCreateTime(now);
            if (azs != null && !azs.isEmpty()) {
                node.setAvailabilityZone(azs.get(i % azs.size()));
            } else {
                node.setAvailabilityZone(config != null ? config.defaultAvailabilityZone() : "us-east-1a");
            }
            node.setNodeStatus("available");
            node.setParameterGroupStatus("in-sync");
            nodes.add(node);
        }
        return nodes;
    }

    private String discoveryHost(String clusterName, String region) {
        String host = config != null ? config.hostname().orElse("localhost") : "localhost";
        String effectiveRegion = region != null && !region.isBlank() ? region : "us-east-1";
        return clusterName + "." + effectiveRegion + ".dax." + host;
    }

    private void recordEvent(String sourceName, String sourceType, String message) {
        events.addFirst(new DaxEvent(sourceName, sourceType, message, Instant.now().getEpochSecond()));
        while (events.size() > MAX_EVENTS) {
            events.removeLast();
        }
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }
}
