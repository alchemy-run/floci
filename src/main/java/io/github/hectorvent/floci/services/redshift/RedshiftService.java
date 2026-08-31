package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.redshift.model.ClusterCredentials;
import io.github.hectorvent.floci.services.redshift.model.ClusterEvent;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.ClusterSnapshot;
import io.github.hectorvent.floci.services.redshift.model.EventSubscription;
import io.github.hectorvent.floci.services.redshift.model.RedshiftCluster;
import io.github.hectorvent.floci.services.redshift.model.RedshiftClusterSubnetGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class RedshiftService {

    private static final Logger LOG = Logger.getLogger(RedshiftService.class);
    private static final String DEFAULT_NODE_TYPE = "ra3.large";
    private static final String DEFAULT_DB_NAME = "dev";
    private static final String DEFAULT_MASTER_USERNAME = "awsuser";
    private static final int DEFAULT_PORT = 5439;
    private static final int DEFAULT_CREDENTIAL_DURATION_SECONDS = 900;
    private static final int MIN_CREDENTIAL_DURATION_SECONDS = 900;
    private static final int MAX_CREDENTIAL_DURATION_SECONDS = 3600;
    private static final char[] TEMP_PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, RedshiftCluster> clusters;
    private final StorageBackend<String, RedshiftClusterSubnetGroup> subnetGroups;
    private final StorageBackend<String, ClusterParameterGroup> parameterGroups;
    private final StorageBackend<String, ClusterSnapshot> snapshots;
    private final StorageBackend<String, EventSubscription> eventSubscriptions;
    private final List<ClusterEvent> events = new CopyOnWriteArrayList<>();
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Ec2Service ec2Service;

    @Inject
    public RedshiftService(EmulatorConfig config,
                           RegionResolver regionResolver,
                           Ec2Service ec2Service,
                           StorageFactory storageFactory) {
        this.config = config;
        this.regionResolver = regionResolver;
        this.ec2Service = ec2Service;
        this.clusters = storageFactory.create("redshift", "redshift-clusters.json",
                new TypeReference<Map<String, RedshiftCluster>>() {});
        this.subnetGroups = storageFactory.create("redshift", "redshift-subnet-groups.json",
                new TypeReference<Map<String, RedshiftClusterSubnetGroup>>() {});
        this.parameterGroups = storageFactory.create("redshift", "redshift-parameter-groups.json",
                new TypeReference<Map<String, ClusterParameterGroup>>() {});
        this.snapshots = storageFactory.create("redshift", "redshift-snapshots.json",
                new TypeReference<Map<String, ClusterSnapshot>>() {});
        this.eventSubscriptions = storageFactory.create("redshift", "redshift-event-subscriptions.json",
                new TypeReference<Map<String, EventSubscription>>() {});
    }

    public RedshiftCluster createCluster(String identifier, String nodeType, String clusterType,
                                         Integer numberOfNodes, String masterUsername,
                                         String masterUserPassword, boolean manageMasterPassword,
                                         String dbName, Integer port, String availabilityZone,
                                         String clusterSubnetGroupName, String clusterParameterGroupName,
                                         List<String> vpcSecurityGroupIds, List<String> iamRoles,
                                         Boolean publiclyAccessible, Boolean encrypted, String kmsKeyId,
                                         String preferredMaintenanceWindow,
                                         Integer automatedSnapshotRetentionPeriod,
                                         Boolean allowVersionUpgrade, Boolean enhancedVpcRouting,
                                         Map<String, String> tags) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ClusterIdentifier is required.", 400);
        }
        if (clusters.get(identifier).isPresent()) {
            throw new AwsException("ClusterAlreadyExists",
                    "Cluster " + identifier + " already exists.", 400);
        }
        if (!manageMasterPassword && (masterUserPassword == null || masterUserPassword.isBlank())) {
            throw new AwsException("InvalidParameterValue",
                    "MasterUserPassword is required unless ManageMasterPassword is true.", 400);
        }

        String region = regionResolver.getDefaultRegion();
        int nodes = resolveNodeCount(clusterType, numberOfNodes);
        RedshiftCluster cluster = new RedshiftCluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setNodeType(nodeType != null && !nodeType.isBlank() ? nodeType : DEFAULT_NODE_TYPE);
        cluster.setClusterStatus("available");
        cluster.setClusterAvailabilityStatus("Available");
        cluster.setMasterUsername(masterUsername != null && !masterUsername.isBlank()
                ? masterUsername : DEFAULT_MASTER_USERNAME);
        cluster.setDbName(dbName != null && !dbName.isBlank() ? dbName : DEFAULT_DB_NAME);
        cluster.setEndpointPort(port != null && port > 0 ? port : DEFAULT_PORT);
        cluster.setEndpointAddress(identifier + "." + region + ".redshift.amazonaws.com");
        cluster.setAvailabilityZone(availabilityZone != null && !availabilityZone.isBlank()
                ? availabilityZone : config.defaultAvailabilityZone());
        cluster.setClusterSubnetGroupName(clusterSubnetGroupName);
        cluster.setClusterParameterGroupName(clusterParameterGroupName);
        cluster.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        cluster.setIamRoles(iamRoles);
        cluster.setNumberOfNodes(nodes);
        cluster.setPubliclyAccessible(Boolean.TRUE.equals(publiclyAccessible));
        cluster.setEncrypted(encrypted == null || encrypted);
        cluster.setKmsKeyId(kmsKeyId);
        cluster.setPreferredMaintenanceWindow(preferredMaintenanceWindow);
        if (automatedSnapshotRetentionPeriod != null) {
            cluster.setAutomatedSnapshotRetentionPeriod(automatedSnapshotRetentionPeriod);
        }
        if (allowVersionUpgrade != null) {
            cluster.setAllowVersionUpgrade(allowVersionUpgrade);
        }
        cluster.setEnhancedVpcRouting(Boolean.TRUE.equals(enhancedVpcRouting));
        cluster.setClusterCreateTime(Instant.now());
        cluster.setClusterNamespaceArn(regionResolver.buildArn("redshift", region, "namespace:" + identifier));
        if (manageMasterPassword) {
            cluster.setMasterPasswordSecretArn(regionResolver.buildArn(
                    "secretsmanager", region, "secret:redshift!" + identifier));
        }
        if (tags != null) {
            cluster.getTags().putAll(tags);
        }
        if (clusterSubnetGroupName != null && !clusterSubnetGroupName.isBlank()) {
            subnetGroups.get(clusterSubnetGroupName).ifPresent(group -> cluster.setVpcId(group.getVpcId()));
        }
        clusters.put(identifier, cluster);
        recordEvent(identifier, "cluster", "Cluster " + identifier + " created", "INFO");
        LOG.infov("Redshift cluster {0} created", identifier);
        return cluster;
    }

    public RedshiftCluster getCluster(String identifier) {
        return clusters.get(identifier).orElseThrow(() ->
                new AwsException("ClusterNotFound",
                        "Cluster " + identifier + " not found.", 404));
    }

    /**
     * Mints temporary credentials for a named database user via
     * {@code GetClusterCredentials}. {@code AutoCreate=true} prefixes the user
     * with {@code IAMA:}; otherwise {@code IAM:}.
     */
    public ClusterCredentials getClusterCredentials(String clusterIdentifier, String dbUser,
                                                    boolean autoCreate, Integer durationSeconds) {
        if (clusterIdentifier == null || clusterIdentifier.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ClusterIdentifier is required.", 400);
        }
        if (dbUser == null || dbUser.isBlank()) {
            throw new AwsException("InvalidParameterValue", "DbUser is required.", 400);
        }
        getCluster(clusterIdentifier);
        return mintCredentials(prefixNamedDbUser(dbUser, autoCreate), durationSeconds);
    }

    /**
     * Mints temporary credentials mapped 1:1 to the caller's IAM identity via
     * {@code GetClusterCredentialsWithIAM}. {@code dbUser} is already prefixed
     * ({@code IAM:} / {@code IAMR:}) by the query handler.
     */
    public ClusterCredentials getClusterCredentialsWithIAM(String clusterIdentifier, String dbUser,
                                                           Integer durationSeconds) {
        if (clusterIdentifier == null || clusterIdentifier.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ClusterIdentifier is required.", 400);
        }
        getCluster(clusterIdentifier);
        String user = dbUser != null && !dbUser.isBlank() ? dbUser : "IAM:root";
        if (!user.regionMatches(true, 0, "IAM", 0, 3)) {
            user = "IAM:" + user;
        }
        return mintCredentials(user, durationSeconds);
    }

    public Collection<RedshiftCluster> listClusters(String identifier) {
        if (identifier != null && !identifier.isBlank()) {
            return List.of(getCluster(identifier));
        }
        return clusters.values();
    }

    public RedshiftCluster modifyCluster(String identifier, String nodeType, Integer numberOfNodes,
                                         String clusterSubnetGroupName, String clusterParameterGroupName,
                                         List<String> vpcSecurityGroupIds, Boolean publiclyAccessible,
                                         Boolean encrypted, String kmsKeyId,
                                         String preferredMaintenanceWindow,
                                         Integer automatedSnapshotRetentionPeriod,
                                         Boolean allowVersionUpgrade, Boolean enhancedVpcRouting,
                                         List<String> iamRoles) {
        RedshiftCluster cluster = getCluster(identifier);
        if (nodeType != null && !nodeType.isBlank()) {
            cluster.setNodeType(nodeType);
        }
        if (numberOfNodes != null && numberOfNodes > 0) {
            cluster.setNumberOfNodes(numberOfNodes);
        }
        if (clusterSubnetGroupName != null) {
            cluster.setClusterSubnetGroupName(clusterSubnetGroupName);
        }
        if (clusterParameterGroupName != null) {
            cluster.setClusterParameterGroupName(clusterParameterGroupName);
        }
        if (vpcSecurityGroupIds != null) {
            cluster.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        }
        if (publiclyAccessible != null) {
            cluster.setPubliclyAccessible(publiclyAccessible);
        }
        if (encrypted != null) {
            cluster.setEncrypted(encrypted);
        }
        if (kmsKeyId != null) {
            cluster.setKmsKeyId(kmsKeyId);
        }
        if (preferredMaintenanceWindow != null) {
            cluster.setPreferredMaintenanceWindow(preferredMaintenanceWindow);
        }
        if (automatedSnapshotRetentionPeriod != null) {
            cluster.setAutomatedSnapshotRetentionPeriod(automatedSnapshotRetentionPeriod);
        }
        if (allowVersionUpgrade != null) {
            cluster.setAllowVersionUpgrade(allowVersionUpgrade);
        }
        if (enhancedVpcRouting != null) {
            cluster.setEnhancedVpcRouting(enhancedVpcRouting);
        }
        if (iamRoles != null) {
            cluster.setIamRoles(iamRoles);
        }
        clusters.put(identifier, cluster);
        return cluster;
    }

    public RedshiftCluster deleteCluster(String identifier, boolean skipFinalSnapshot, String finalSnapshotId) {
        RedshiftCluster cluster = getCluster(identifier);
        if (!skipFinalSnapshot) {
            if (finalSnapshotId == null || finalSnapshotId.isBlank()) {
                throw new AwsException("InvalidParameterValue",
                        "FinalClusterSnapshotIdentifier is required unless SkipFinalClusterSnapshot is true.", 400);
            }
            createSnapshot(cluster.getClusterIdentifier(), finalSnapshotId, "manual");
        }
        clusters.delete(identifier);
        cluster.setClusterStatus("deleted");
        recordEvent(identifier, "cluster", "Cluster " + identifier + " deleted", "INFO");
        return cluster;
    }

    public RedshiftClusterSubnetGroup createSubnetGroup(String name, String description,
                                                        List<String> subnetIds, Map<String, String> tags) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ClusterSubnetGroupName is required.", 400);
        }
        if (subnetGroups.get(name).isPresent()) {
            throw new AwsException("ClusterSubnetGroupAlreadyExists",
                    "Cluster subnet group " + name + " already exists.", 400);
        }
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "SubnetIds is required.", 400);
        }
        RedshiftClusterSubnetGroup group = buildSubnetGroup(name, description, subnetIds);
        if (tags != null) {
            group.getTags().putAll(tags);
        }
        subnetGroups.put(name, group);
        return group;
    }

    public RedshiftClusterSubnetGroup getSubnetGroup(String name) {
        return subnetGroups.get(name).orElseThrow(() ->
                new AwsException("ClusterSubnetGroupNotFoundFault",
                        "Cluster subnet group " + name + " not found.", 400));
    }

    public Collection<RedshiftClusterSubnetGroup> listSubnetGroups(String name) {
        if (name != null && !name.isBlank()) {
            return List.of(getSubnetGroup(name));
        }
        return subnetGroups.values();
    }

    public RedshiftClusterSubnetGroup modifySubnetGroup(String name, String description, List<String> subnetIds) {
        RedshiftClusterSubnetGroup existing = getSubnetGroup(name);
        List<String> resolvedSubnets = subnetIds != null && !subnetIds.isEmpty()
                ? subnetIds : existing.getSubnetIds();
        String resolvedDescription = description != null ? description : existing.getDescription();
        RedshiftClusterSubnetGroup group = buildSubnetGroup(
                existing.getClusterSubnetGroupName(), resolvedDescription, resolvedSubnets);
        group.setTags(existing.getTags());
        subnetGroups.put(existing.getClusterSubnetGroupName(), group);
        return group;
    }

    public void deleteSubnetGroup(String name) {
        getSubnetGroup(name);
        boolean inUse = clusters.values().stream()
                .anyMatch(cluster -> name.equals(cluster.getClusterSubnetGroupName()));
        if (inUse) {
            throw new AwsException("InvalidClusterSubnetGroupStateFault",
                    "Cluster subnet group " + name + " is in use.", 400);
        }
        subnetGroups.delete(name);
    }

    public ClusterSnapshot createSnapshot(String clusterIdentifier, String snapshotIdentifier, String snapshotType) {
        if (snapshotIdentifier == null || snapshotIdentifier.isBlank()) {
            throw new AwsException("InvalidParameterValue", "SnapshotIdentifier is required.", 400);
        }
        if (snapshots.get(snapshotIdentifier).isPresent()) {
            throw new AwsException("ClusterSnapshotAlreadyExists",
                    "Snapshot " + snapshotIdentifier + " already exists.", 400);
        }
        RedshiftCluster cluster = getCluster(clusterIdentifier);
        return putSnapshotFromCluster(cluster, snapshotIdentifier,
                snapshotType != null && !snapshotType.isBlank() ? snapshotType : "manual");
    }

    public ClusterSnapshot getSnapshot(String snapshotIdentifier) {
        return snapshots.get(snapshotIdentifier).orElseThrow(() ->
                new AwsException("ClusterSnapshotNotFound",
                        "Snapshot " + snapshotIdentifier + " not found.", 404));
    }

    public Collection<ClusterSnapshot> listSnapshots(String clusterIdentifier, String snapshotIdentifier,
                                                     String snapshotType) {
        if (snapshotIdentifier != null && !snapshotIdentifier.isBlank()) {
            return List.of(getSnapshot(snapshotIdentifier));
        }
        List<ClusterSnapshot> listed = new ArrayList<>();
        for (ClusterSnapshot snapshot : snapshots.values()) {
            if (clusterIdentifier != null && !clusterIdentifier.isBlank()
                    && !clusterIdentifier.equals(snapshot.getClusterIdentifier())) {
                continue;
            }
            if (snapshotType != null && !snapshotType.isBlank()
                    && !snapshotType.equalsIgnoreCase(snapshot.getSnapshotType())) {
                continue;
            }
            listed.add(snapshot);
        }
        return listed;
    }

    public ClusterSnapshot deleteSnapshot(String snapshotIdentifier) {
        ClusterSnapshot snapshot = getSnapshot(snapshotIdentifier);
        snapshots.delete(snapshotIdentifier);
        snapshot.setStatus("deleted");
        recordEvent(snapshotIdentifier, "snapshot",
                "Snapshot " + snapshotIdentifier + " deleted", "INFO");
        return snapshot;
    }

    public ClusterSnapshot copySnapshot(String sourceSnapshotIdentifier, String targetSnapshotIdentifier) {
        if (targetSnapshotIdentifier == null || targetSnapshotIdentifier.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TargetSnapshotIdentifier is required.", 400);
        }
        ClusterSnapshot source = getSnapshot(sourceSnapshotIdentifier);
        if (snapshots.get(targetSnapshotIdentifier).isPresent()) {
            throw new AwsException("ClusterSnapshotAlreadyExists",
                    "Snapshot " + targetSnapshotIdentifier + " already exists.", 400);
        }
        ClusterSnapshot copy = cloneSnapshot(source, targetSnapshotIdentifier);
        snapshots.put(targetSnapshotIdentifier, copy);
        recordEvent(targetSnapshotIdentifier, "snapshot",
                "Snapshot " + sourceSnapshotIdentifier + " copied to " + targetSnapshotIdentifier, "INFO");
        return copy;
    }

    public Collection<ClusterEvent> listEvents() {
        return List.copyOf(events);
    }

    public EventSubscription createEventSubscription(String name, String snsTopicArn, String sourceType,
                                                     List<String> sourceIds, List<String> eventCategories,
                                                     String severity, Boolean enabled, Map<String, String> tags) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "SubscriptionName is required.", 400);
        }
        if (snsTopicArn == null || snsTopicArn.isBlank()) {
            throw new AwsException("InvalidParameterValue", "SnsTopicArn is required.", 400);
        }
        if (eventSubscriptions.get(name).isPresent()) {
            throw new AwsException("SubscriptionAlreadyExist",
                    "Subscription " + name + " already exists.", 400);
        }
        EventSubscription subscription = new EventSubscription();
        subscription.setCustomerAwsId(regionResolver.getAccountId());
        subscription.setCustSubscriptionId(name);
        subscription.setSnsTopicArn(snsTopicArn);
        subscription.setStatus("active");
        subscription.setSubscriptionCreationTime(Instant.now());
        subscription.setSourceType(sourceType);
        subscription.setSourceIds(sourceIds);
        subscription.setEventCategories(eventCategories);
        subscription.setSeverity(severity);
        subscription.setEnabled(enabled == null || enabled);
        if (tags != null) {
            subscription.getTags().putAll(tags);
        }
        eventSubscriptions.put(name, subscription);
        LOG.infov("Redshift event subscription {0} created", name);
        return subscription;
    }

    public EventSubscription getEventSubscription(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "SubscriptionName is required.", 400);
        }
        return eventSubscriptions.get(name).orElseThrow(() ->
                new AwsException("SubscriptionNotFound",
                        "Subscription " + name + " not found.", 404));
    }

    public Collection<EventSubscription> listEventSubscriptions(String name) {
        if (name != null && !name.isBlank()) {
            return List.of(getEventSubscription(name));
        }
        return eventSubscriptions.values();
    }

    public EventSubscription modifyEventSubscription(String name, String snsTopicArn, String sourceType,
                                                     List<String> sourceIds, List<String> eventCategories,
                                                     String severity, Boolean enabled) {
        EventSubscription subscription = getEventSubscription(name);
        if (snsTopicArn != null) {
            subscription.setSnsTopicArn(snsTopicArn);
        }
        if (sourceType != null) {
            subscription.setSourceType(sourceType);
        }
        if (sourceIds != null) {
            subscription.setSourceIds(sourceIds);
        }
        if (eventCategories != null) {
            subscription.setEventCategories(eventCategories);
        }
        if (severity != null) {
            subscription.setSeverity(severity);
        }
        if (enabled != null) {
            subscription.setEnabled(enabled);
        }
        eventSubscriptions.put(name, subscription);
        return subscription;
    }

    public void deleteEventSubscription(String name) {
        getEventSubscription(name);
        eventSubscriptions.delete(name);
        LOG.infov("Redshift event subscription {0} deleted", name);
    }

    public ClusterParameterGroup createClusterParameterGroup(String name, String family,
                                                             String description, Map<String, String> tags) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ParameterGroupName is required.", 400);
        }
        if (family == null || family.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ParameterGroupFamily is required.", 400);
        }
        if (parameterGroups.get(name).isPresent()) {
            throw new AwsException("ClusterParameterGroupAlreadyExists",
                    "Cluster parameter group " + name + " already exists.", 400);
        }
        ClusterParameterGroup group = new ClusterParameterGroup(name, family, description);
        if (tags != null && !tags.isEmpty()) {
            group.setTags(tags);
        }
        parameterGroups.put(name, group);
        LOG.infov("Redshift cluster parameter group {0} created", name);
        return group;
    }

    public ClusterParameterGroup getClusterParameterGroup(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ParameterGroupName is required.", 400);
        }
        return parameterGroups.get(name).orElseThrow(() ->
                new AwsException("ClusterParameterGroupNotFound",
                        "Cluster parameter group " + name + " not found.", 404));
    }

    public Collection<ClusterParameterGroup> listClusterParameterGroups(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return List.of(getClusterParameterGroup(filterName));
        }
        return parameterGroups.values();
    }

    public void deleteClusterParameterGroup(String name) {
        getClusterParameterGroup(name);
        if (name.startsWith("default.")) {
            throw new AwsException("InvalidClusterParameterGroupState",
                    "Cannot delete default cluster parameter group " + name + ".", 400);
        }
        parameterGroups.delete(name);
        LOG.infov("Redshift cluster parameter group {0} deleted", name);
    }

    public ClusterParameterGroup modifyClusterParameterGroup(String name, Map<String, String> parameters) {
        ClusterParameterGroup group = getClusterParameterGroup(name);
        if (parameters != null) {
            group.getParameters().putAll(parameters);
        }
        parameterGroups.put(name, group);
        return group;
    }

    public ClusterParameterGroup resetClusterParameterGroup(String name, boolean resetAll,
                                                            Collection<String> parameterNames) {
        ClusterParameterGroup group = getClusterParameterGroup(name);
        if (resetAll) {
            group.getParameters().clear();
        } else if (parameterNames != null) {
            parameterNames.forEach(group.getParameters()::remove);
        }
        parameterGroups.put(name, group);
        return group;
    }

    public void createTags(String resourceName, Map<String, String> tags) {
        if (tags != null) {
            tagsFor(resourceName).putAll(tags);
        }
        persistTagged(resourceName);
    }

    public void deleteTags(String resourceName, Collection<String> keys) {
        Map<String, String> current = tagsFor(resourceName);
        if (keys != null) {
            keys.forEach(current::remove);
        }
        persistTagged(resourceName);
    }

    private Map<String, String> tagsFor(String resourceName) {
        ArnRef ref = parseArn(resourceName);
        return switch (ref.type) {
            case "cluster" -> getCluster(ref.name).getTags();
            case "subnetgroup" -> getSubnetGroup(ref.name).getTags();
            case "parametergroup" -> getClusterParameterGroup(ref.name).getTags();
            case "eventsubscription" -> getEventSubscription(ref.name).getTags();
            default -> throw new AwsException("ResourceNotFoundFault",
                    "Resource " + resourceName + " not found.", 404);
        };
    }

    private void persistTagged(String resourceName) {
        ArnRef ref = parseArn(resourceName);
        switch (ref.type) {
            case "cluster" -> clusters.put(ref.name, getCluster(ref.name));
            case "subnetgroup" -> subnetGroups.put(ref.name, getSubnetGroup(ref.name));
            case "parametergroup" -> parameterGroups.put(ref.name, getClusterParameterGroup(ref.name));
            case "eventsubscription" -> eventSubscriptions.put(ref.name, getEventSubscription(ref.name));
            default -> { }
        }
    }

    private static ArnRef parseArn(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceName is required.", 400);
        }
        try {
            String resource = AwsArnUtils.parse(resourceName).resource();
            int colon = resource.indexOf(':');
            if (colon < 0) {
                throw new AwsException("ResourceNotFoundFault",
                        "Resource " + resourceName + " not found.", 404);
            }
            return new ArnRef(resource.substring(0, colon), resource.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue", "Invalid ResourceName ARN.", 400);
        }
    }

    private record ArnRef(String type, String name) {}

    private ClusterSnapshot putSnapshotFromCluster(RedshiftCluster cluster, String snapshotIdentifier,
                                                   String snapshotType) {
        String region = regionResolver.getDefaultRegion();
        Instant now = Instant.now();
        ClusterSnapshot snapshot = new ClusterSnapshot();
        snapshot.setSnapshotIdentifier(snapshotIdentifier);
        snapshot.setClusterIdentifier(cluster.getClusterIdentifier());
        snapshot.setSnapshotType(snapshotType);
        snapshot.setStatus("available");
        snapshot.setNodeType(cluster.getNodeType());
        snapshot.setNumberOfNodes(cluster.getNumberOfNodes());
        snapshot.setPort(cluster.getEndpointPort());
        snapshot.setAvailabilityZone(cluster.getAvailabilityZone());
        snapshot.setMasterUsername(cluster.getMasterUsername());
        snapshot.setDbName(cluster.getDbName());
        snapshot.setClusterVersion(cluster.getClusterVersion());
        snapshot.setEncrypted(cluster.isEncrypted());
        snapshot.setSnapshotCreateTime(now);
        snapshot.setClusterCreateTime(cluster.getClusterCreateTime());
        snapshot.setOwnerAccount(regionResolver.getAccountId());
        snapshot.setSnapshotArn(regionResolver.buildArn("redshift", region,
                "snapshot:" + cluster.getClusterIdentifier() + "/" + snapshotIdentifier));
        snapshots.put(snapshotIdentifier, snapshot);
        recordEvent(snapshotIdentifier, "snapshot",
                "Snapshot " + snapshotIdentifier + " created", "INFO");
        return snapshot;
    }

    private ClusterSnapshot cloneSnapshot(ClusterSnapshot source, String targetIdentifier) {
        String region = regionResolver.getDefaultRegion();
        ClusterSnapshot copy = new ClusterSnapshot();
        copy.setSnapshotIdentifier(targetIdentifier);
        copy.setClusterIdentifier(source.getClusterIdentifier());
        copy.setSnapshotType("manual");
        copy.setStatus("available");
        copy.setNodeType(source.getNodeType());
        copy.setNumberOfNodes(source.getNumberOfNodes());
        copy.setPort(source.getPort());
        copy.setAvailabilityZone(source.getAvailabilityZone());
        copy.setMasterUsername(source.getMasterUsername());
        copy.setDbName(source.getDbName());
        copy.setClusterVersion(source.getClusterVersion());
        copy.setEncrypted(source.isEncrypted());
        copy.setSnapshotCreateTime(Instant.now());
        copy.setClusterCreateTime(source.getClusterCreateTime());
        copy.setOwnerAccount(source.getOwnerAccount());
        copy.setSnapshotArn(regionResolver.buildArn("redshift", region,
                "snapshot:" + source.getClusterIdentifier() + "/" + targetIdentifier));
        return copy;
    }

    private static int resolveNodeCount(String clusterType, Integer numberOfNodes) {
        if (numberOfNodes != null && numberOfNodes > 0) {
            return numberOfNodes;
        }
        if (clusterType != null && clusterType.equalsIgnoreCase("multi-node")) {
            return 2;
        }
        return 1;
    }

    private ClusterCredentials mintCredentials(String dbUser, Integer durationSeconds) {
        int duration = durationSeconds != null
                ? durationSeconds
                : DEFAULT_CREDENTIAL_DURATION_SECONDS;
        if (duration < MIN_CREDENTIAL_DURATION_SECONDS || duration > MAX_CREDENTIAL_DURATION_SECONDS) {
            throw new AwsException("InvalidParameterValue",
                    "DurationSeconds must be between 900 and 3600.", 400);
        }
        Instant expiration = Instant.now().plusSeconds(duration);
        Instant nextRefresh = expiration.minusSeconds(Math.min(300, Math.max(0, duration / 3)));
        if (nextRefresh.isBefore(Instant.now())) {
            nextRefresh = expiration;
        }
        return new ClusterCredentials(dbUser, randomTempPassword(), expiration, nextRefresh);
    }

    private static String prefixNamedDbUser(String dbUser, boolean autoCreate) {
        String trimmed = dbUser.trim();
        if (trimmed.regionMatches(true, 0, "IAM", 0, 3)) {
            return trimmed;
        }
        return (autoCreate ? "IAMA:" : "IAM:") + trimmed;
    }

    private static String randomTempPassword() {
        char[] chars = new char[32];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = TEMP_PASSWORD_ALPHABET[RANDOM.nextInt(TEMP_PASSWORD_ALPHABET.length)];
        }
        return new String(chars);
    }

    private RedshiftClusterSubnetGroup buildSubnetGroup(String name, String description, List<String> subnetIds) {
        String region = regionResolver.getRegion();
        Map<String, String> azs = new LinkedHashMap<>();
        String vpcId = null;
        for (String subnetId : subnetIds) {
            Subnet subnet = ec2Service.findSubnetById(region, subnetId).orElseThrow(() ->
                    new AwsException("InvalidSubnet", "Subnet " + subnetId + " is not valid.", 400));
            if (vpcId == null) {
                vpcId = subnet.getVpcId();
            } else if (subnet.getVpcId() != null && !vpcId.equals(subnet.getVpcId())) {
                throw new AwsException("InvalidSubnet", "Subnets must belong to the same VPC.", 400);
            }
            azs.put(subnetId, subnet.getAvailabilityZone() != null
                    ? subnet.getAvailabilityZone() : config.defaultAvailabilityZone());
        }
        RedshiftClusterSubnetGroup group = new RedshiftClusterSubnetGroup();
        group.setClusterSubnetGroupName(name);
        group.setDescription(description);
        group.setVpcId(vpcId != null ? vpcId : "vpc-00000000");
        group.setSubnetGroupStatus("Complete");
        group.setSubnetIds(subnetIds);
        group.setSubnetAvailabilityZones(azs);
        return group;
    }

    private void recordEvent(String sourceIdentifier, String sourceType, String message, String severity) {
        events.add(new ClusterEvent(sourceIdentifier, sourceType, message, severity,
                "REDSHIFT-" + UUID.randomUUID().toString().substring(0, 8), Instant.now()));
    }

}
