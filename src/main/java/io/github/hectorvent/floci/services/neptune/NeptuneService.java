package io.github.hectorvent.floci.services.neptune;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.neptune.container.NeptuneContainerHandle;
import io.github.hectorvent.floci.services.neptune.container.NeptuneContainerManager;
import io.github.hectorvent.floci.services.neptune.model.NeptuneCluster;
import io.github.hectorvent.floci.services.neptune.model.NeptuneClusterParameterGroup;
import io.github.hectorvent.floci.services.neptune.model.NeptuneClusterSnapshot;
import io.github.hectorvent.floci.services.neptune.model.NeptuneDbType;
import io.github.hectorvent.floci.services.neptune.model.NeptuneInstance;
import io.github.hectorvent.floci.services.neptune.model.NeptuneParameterGroup;
import io.github.hectorvent.floci.services.neptune.proxy.NeptuneProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class NeptuneService {

    private static final Logger LOG = Logger.getLogger(NeptuneService.class);
    private static final String ENGINE_VERSION_DEFAULT = "1.3.2.1";

    private final StorageBackend<String, NeptuneCluster> clusters;
    private final StorageBackend<String, NeptuneInstance> instances;
    private final StorageBackend<String, NeptuneClusterSnapshot> clusterSnapshots;
    private final StorageBackend<String, NeptuneClusterParameterGroup> clusterParameterGroups;
    private final StorageBackend<String, NeptuneParameterGroup> parameterGroups;
    private final Set<String> deletedParameterGroups = ConcurrentHashMap.newKeySet();
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final NeptuneContainerManager containerManager;
    private final NeptuneProxyManager proxyManager;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();

    @Inject
    public NeptuneService(EmulatorConfig config,
                          RegionResolver regionResolver,
                          NeptuneContainerManager containerManager,
                          NeptuneProxyManager proxyManager,
                          StorageFactory storageFactory) {
        this.config = config;
        this.regionResolver = regionResolver;
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.clusters = storageFactory.create("neptune", "neptune-clusters.json",
                new TypeReference<Map<String, NeptuneCluster>>() {});
        this.instances = storageFactory.create("neptune", "neptune-instances.json",
                new TypeReference<Map<String, NeptuneInstance>>() {});
        this.clusterSnapshots = storageFactory.create("neptune", "neptune-cluster-snapshots.json",
                new TypeReference<Map<String, NeptuneClusterSnapshot>>() {});
        this.clusterParameterGroups = storageFactory.create("neptune", "neptune-cluster-parameter-groups.json",
                new TypeReference<Map<String, NeptuneClusterParameterGroup>>() {});
        this.parameterGroups = storageFactory.create("neptune", "neptune-parameter-groups.json",
                new TypeReference<Map<String, NeptuneParameterGroup>>() {});
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    public NeptuneCluster createDbCluster(String id, String engineVersion, boolean iamEnabled) {
        if (clusters.get(id).isPresent()) {
            throw new AwsException("DBClusterAlreadyExistsFault",
                    "Neptune cluster " + id + " already exists.", 400);
        }

        // Open the try immediately after reserving the port so config reads below can't leak it.
        int proxyPort = allocateProxyPort();
        NeptuneContainerHandle handle = null;
        boolean provisioned = false;
        try {
            String configuredDbType = config.services().neptune().dbType();
            NeptuneDbType dbType = NeptuneDbType.fromConfig(configuredDbType).orElseGet(() -> {
                LOG.warnv("Unsupported Neptune db-type ''{0}'', falling back to {1}. Supported: gremlin, neo4j.",
                        configuredDbType, NeptuneDbType.GREMLIN);
                return NeptuneDbType.GREMLIN;
            });
            String image = switch (dbType) {
                case GREMLIN -> config.services().neptune().defaultImage();
                case NEO4J -> config.services().neptune().defaultNeo4jImage();
            };

            LOG.infov("Creating Neptune cluster {0} on proxy port {1}, dbType={2}, image={3}",
                    id, String.valueOf(proxyPort), dbType, image);

            handle = containerManager.start(id, image, dbType);

            String region = regionResolver.getDefaultRegion();
            String endpointHost = resolveEndpointHost();

            NeptuneCluster cluster = new NeptuneCluster();
            cluster.setDbClusterIdentifier(id);
            cluster.setStatus("available");
            cluster.setEngineVersion(engineVersion != null ? engineVersion : ENGINE_VERSION_DEFAULT);
            cluster.setEndpoint(endpointHost);
            cluster.setReaderEndpoint(endpointHost);
            cluster.setPort(proxyPort);
            cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
            cluster.setDbClusterArn(regionResolver.buildArn("neptune", region, "cluster:" + id));
            cluster.setDbClusterResourceId("cluster-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 24).toUpperCase());
            cluster.setCreatedAt(Instant.now());
            cluster.setDbClusterMembers(new ArrayList<>());
            cluster.setContainerId(handle.getContainerId());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
            cluster.setProxyPort(proxyPort);

            proxyManager.startProxy(id, proxyPort, handle.getHost(), handle.getPort());

            clusters.put(id, cluster);
            provisioned = true;
            LOG.infov("Neptune cluster {0} created ({1}), endpoint={2}:{3}",
                    id, dbType, endpointHost, String.valueOf(proxyPort));
            return cluster;
        } catch (RuntimeException e) {
            LOG.warnv("Neptune cluster {0} provisioning failed, rolling back: {1}", id, e.getMessage());
            throw e;
        } finally {
            // Roll back on ANY non-success exit — including a JVM Error, which a
            // catch (RuntimeException) would miss — so a failed create never leaks the
            // reserved port or leaves a container behind. Idempotent and a no-op on success.
            if (!provisioned) {
                rollbackDbCluster(id, handle, proxyPort);
            }
        }
    }

    private void rollbackDbCluster(String id, NeptuneContainerHandle handle, int proxyPort) {
        try {
            try {
                // The proxy only starts after the container is ready, so a null handle means it
                // never started — nothing to stop.
                if (handle != null) {
                    proxyManager.stopProxy(id);
                }
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping proxy for Neptune cluster {0}: {1}", id, e.getMessage());
            }
            try {
                // Stop by id, not handle: a readiness timeout in containerManager.start() throws
                // after the container was created and registered but before the handle is returned,
                // so cleaning up by handle here would miss (and orphan) it. stopByClusterId is
                // idempotent, so it's safe when the container never started.
                containerManager.stopByClusterId(id);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container for Neptune cluster {0}: {1}", id, e.getMessage());
            }
        } finally {
            // Always release the port — even if a cleanup step throws a non-RuntimeException
            // (e.g. an Error) — since leaking the port is the exact failure this rollback prevents.
            releaseProxyPort(proxyPort);
        }
    }

    public NeptuneCluster getDbCluster(String id) {
        return clusters.get(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "Neptune cluster " + id + " not found.", 404));
    }

    public boolean hasCluster(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return clusters.get(id).isPresent();
    }

    public boolean hasInstance(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return instances.get(id).isPresent();
    }

    public boolean hasSnapshot(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return clusterSnapshots.get(id).isPresent();
    }

    public boolean hasClusterParameterGroup(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return clusterParameterGroups.get(name).isPresent();
    }

    public boolean hasParameterGroup(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return parameterGroups.get(name).isPresent() || deletedParameterGroups.contains(name);
    }

    /**
     * True when a Query-protocol request should be handled by Neptune rather than RDS:
     * a {@code neptune*} family, an existing cluster parameter group, or a tag ARN
     * that this service owns.
     */
    public boolean handlesClusterParameterGroupRequest(String family, String groupName, String resourceName) {
        if (family != null && family.toLowerCase(Locale.ROOT).startsWith("neptune")) {
            return true;
        }
        return hasClusterParameterGroup(groupName) || ownsTagResource(resourceName);
    }

    /**
     * Instance-level DB parameter groups share the RDS Query wire and the
     * {@code rds} signing name. Route by {@code neptune*} family, live or
     * just-deleted group name, or a {@code pg:} tag ARN this service owns.
     */
    public boolean handlesParameterGroupRequest(String family, String groupName, String resourceName) {
        if (family != null && family.toLowerCase(Locale.ROOT).startsWith("neptune")) {
            return true;
        }
        return hasParameterGroup(groupName) || ownsTagResource(resourceName);
    }

    public boolean ownsTagResource(String resourceName) {
        if (resourceName == null || resourceName.isBlank() || !resourceName.startsWith("arn:")) {
            return false;
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceName);
            String resource = arn.resource();
            int sep = resource.indexOf(':');
            if (sep < 0) {
                return false;
            }
            String type = resource.substring(0, sep);
            String id = resource.substring(sep + 1);
            return ("cluster-pg".equals(type) && hasClusterParameterGroup(id))
                    || ("pg".equals(type) && hasParameterGroup(id));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public Collection<NeptuneCluster> listDbClusters(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            // The db-cluster-id filter accepts ARNs as well as identifiers. Match the
            // full ARN against each cluster's stored ARN rather than reducing it to
            // the bare identifier, so a cross-account or cross-region ARN does not
            // resolve a same-named local cluster.
            if (filterId.startsWith("arn:")) {
                return clusters.scan(k -> true).stream()
                        .filter(c -> filterId.equalsIgnoreCase(c.getDbClusterArn()))
                        .toList();
            }
            return clusters.scan(k -> k.equalsIgnoreCase(filterId));
        }
        return clusters.scan(k -> true);
    }

    public NeptuneCluster modifyDbCluster(String id, String engineVersion, Boolean iamEnabled) {
        NeptuneCluster cluster = getDbCluster(id);
        if (engineVersion != null && !engineVersion.isBlank()) {
            cluster.setEngineVersion(engineVersion);
        }
        if (iamEnabled != null) {
            cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        clusters.put(id, cluster);
        LOG.infov("Neptune cluster {0} modified", id);
        return cluster;
    }

    public void deleteDbCluster(String id) {
        NeptuneCluster cluster = clusters.get(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "Neptune cluster " + id + " not found.", 404));

        if (cluster.getDbClusterMembers() != null && !cluster.getDbClusterMembers().isEmpty()) {
            throw new AwsException("InvalidDBClusterStateFault",
                    "Cannot delete Neptune cluster " + id + " — it still has DB instances.", 400);
        }

        cluster.setStatus("deleting");
        clusters.put(id, cluster);

        proxyManager.stopProxy(id);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new NeptuneContainerHandle(
                    cluster.getContainerId(), id,
                    cluster.getContainerHost(), cluster.getContainerPort()));
        }

        releaseProxyPort(cluster.getProxyPort());
        clusters.delete(id);
        LOG.infov("Neptune cluster {0} deleted", id);
    }

    // ── Instances ─────────────────────────────────────────────────────────────

    public NeptuneInstance createDbInstance(String id, String dbClusterIdentifier,
                                            String dbInstanceClass, String engineVersion,
                                            boolean iamEnabled) {
        if (instances.get(id).isPresent()) {
            throw new AwsException("DBInstanceAlreadyExists",
                    "Neptune instance " + id + " already exists.", 400);
        }

        NeptuneCluster cluster = getDbCluster(dbClusterIdentifier);
        String region = regionResolver.getDefaultRegion();

        NeptuneInstance instance = new NeptuneInstance();
        instance.setDbInstanceIdentifier(id);
        instance.setDbClusterIdentifier(dbClusterIdentifier);
        instance.setDbInstanceClass(dbInstanceClass != null ? dbInstanceClass : "db.r5.large");
        instance.setEngineVersion(engineVersion != null ? engineVersion : cluster.getEngineVersion());
        instance.setStatus("available");
        instance.setEndpoint(cluster.getEndpoint());
        instance.setPort(cluster.getPort());
        instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
        instance.setDbInstanceArn(regionResolver.buildArn("neptune", region, "db:" + id));
        instance.setDbiResourceId("db-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24).toUpperCase());
        instance.setCreatedAt(Instant.now());

        cluster.getDbClusterMembers().add(id);
        clusters.put(dbClusterIdentifier, cluster);

        instances.put(id, instance);
        LOG.infov("Neptune instance {0} created in cluster {1}", id, dbClusterIdentifier);
        return instance;
    }

    public NeptuneInstance getDbInstance(String id) {
        return instances.get(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "Neptune instance " + id + " not found.", 404));
    }

    public Collection<NeptuneInstance> listDbInstances(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            // The db-instance-id filter accepts ARNs as well as identifiers; see
            // listDbClusters for why the match is against the stored ARN.
            if (filterId.startsWith("arn:")) {
                return instances.scan(k -> true).stream()
                        .filter(i -> filterId.equalsIgnoreCase(i.getDbInstanceArn()))
                        .toList();
            }
            return instances.scan(k -> k.equalsIgnoreCase(filterId));
        }
        return instances.scan(k -> true);
    }

    public NeptuneInstance modifyDbInstance(String id, String dbInstanceClass, Boolean iamEnabled) {
        NeptuneInstance instance = getDbInstance(id);
        if (dbInstanceClass != null && !dbInstanceClass.isBlank()) {
            instance.setDbInstanceClass(dbInstanceClass);
        }
        if (iamEnabled != null) {
            instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        instances.put(id, instance);
        LOG.infov("Neptune instance {0} modified", id);
        return instance;
    }

    public void deleteDbInstance(String id) {
        NeptuneInstance instance = instances.get(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "Neptune instance " + id + " not found.", 404));

        String clusterId = instance.getDbClusterIdentifier();
        NeptuneCluster cluster = clusters.get(clusterId).orElse(null);
        if (cluster != null) {
            cluster.getDbClusterMembers().remove(id);
            clusters.put(clusterId, cluster);
        }

        instances.delete(id);
        LOG.infov("Neptune instance {0} deleted", id);
    }

    // ── Cluster parameter groups ──────────────────────────────────────────────

    public NeptuneClusterParameterGroup createDbClusterParameterGroup(String name, String family,
                                                                      String description,
                                                                      Map<String, String> tags) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "DBClusterParameterGroupName is required.", 400);
        }
        if (family == null || family.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "DBParameterGroupFamily is required.", 400);
        }
        if (clusterParameterGroups.get(name).isPresent()) {
            throw new AwsException("DBParameterGroupAlreadyExists",
                    "DB cluster parameter group " + name + " already exists.", 400);
        }
        NeptuneClusterParameterGroup group = new NeptuneClusterParameterGroup(name, family, description);
        group.setDbClusterParameterGroupArn(
                regionResolver.buildArn("rds", regionResolver.getRegion(), "cluster-pg:" + name));
        if (tags != null && !tags.isEmpty()) {
            group.setTags(new HashMap<>(tags));
        }
        clusterParameterGroups.put(name, group);
        LOG.infov("Neptune cluster parameter group {0} created", name);
        return group;
    }

    public NeptuneClusterParameterGroup getDbClusterParameterGroup(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "DBClusterParameterGroupName is required.", 400);
        }
        return clusterParameterGroups.get(name).orElseThrow(() ->
                new AwsException("DBParameterGroupNotFound",
                        "DBParameterGroupName doesn't refer to an existing DB parameter group.", 404));
    }

    public Collection<NeptuneClusterParameterGroup> listDbClusterParameterGroups(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return List.of(getDbClusterParameterGroup(filterName));
        }
        return clusterParameterGroups.scan(k -> true);
    }

    public void deleteDbClusterParameterGroup(String name) {
        getDbClusterParameterGroup(name);
        clusterParameterGroups.delete(name);
        LOG.infov("Neptune cluster parameter group {0} deleted", name);
    }

    public NeptuneClusterParameterGroup modifyDbClusterParameterGroup(String name,
                                                                      Map<String, String> parameters) {
        NeptuneClusterParameterGroup group = getDbClusterParameterGroup(name);
        if (parameters != null) {
            group.getParameters().putAll(parameters);
        }
        clusterParameterGroups.put(name, group);
        return group;
    }

    public NeptuneClusterParameterGroup resetDbClusterParameterGroup(String name, boolean resetAll,
                                                                     Collection<String> parameterNames) {
        NeptuneClusterParameterGroup group = getDbClusterParameterGroup(name);
        if (resetAll) {
            group.getParameters().clear();
        } else if (parameterNames != null) {
            parameterNames.forEach(group.getParameters()::remove);
        }
        clusterParameterGroups.put(name, group);
        return group;
    }

    // ── Instance parameter groups ─────────────────────────────────────────────

    public NeptuneParameterGroup createDbParameterGroup(String name, String family,
                                                        String description, Map<String, String> tags) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "DBParameterGroupName is required.", 400);
        }
        if (family == null || family.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "DBParameterGroupFamily is required.", 400);
        }
        if (parameterGroups.get(name).isPresent()) {
            throw new AwsException("DBParameterGroupAlreadyExists",
                    "DB parameter group " + name + " already exists.", 400);
        }
        deletedParameterGroups.remove(name);
        NeptuneParameterGroup group = new NeptuneParameterGroup(name, family, description);
        group.setDbParameterGroupArn(
                regionResolver.buildArn("rds", regionResolver.getRegion(), "pg:" + name));
        if (tags != null && !tags.isEmpty()) {
            group.setTags(new HashMap<>(tags));
        }
        parameterGroups.put(name, group);
        LOG.infov("Neptune DB parameter group {0} created", name);
        return group;
    }

    public NeptuneParameterGroup getDbParameterGroup(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "DBParameterGroupName is required.", 400);
        }
        return parameterGroups.get(name).orElseThrow(() ->
                new AwsException("DBParameterGroupNotFound",
                        "DBParameterGroupName doesn't refer to an existing DB parameter group.", 404));
    }

    public Collection<NeptuneParameterGroup> listDbParameterGroups(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return List.of(getDbParameterGroup(filterName));
        }
        return parameterGroups.scan(k -> true);
    }

    public void deleteDbParameterGroup(String name) {
        getDbParameterGroup(name);
        parameterGroups.delete(name);
        deletedParameterGroups.add(name);
        LOG.infov("Neptune DB parameter group {0} deleted", name);
    }

    public NeptuneParameterGroup modifyDbParameterGroup(String name, Map<String, String> parameters) {
        NeptuneParameterGroup group = getDbParameterGroup(name);
        if (parameters != null) {
            group.getParameters().putAll(parameters);
        }
        parameterGroups.put(name, group);
        return group;
    }

    public NeptuneParameterGroup resetDbParameterGroup(String name, boolean resetAll,
                                                       Collection<String> parameterNames) {
        NeptuneParameterGroup group = getDbParameterGroup(name);
        if (resetAll) {
            group.getParameters().clear();
        } else if (parameterNames != null) {
            parameterNames.forEach(group.getParameters()::remove);
        }
        parameterGroups.put(name, group);
        return group;
    }

    public void addTagsToResource(String resourceName, Map<String, String> tags) {
        ArnTarget target = requireTagTarget(resourceName);
        if (target.instanceGroup() != null) {
            NeptuneParameterGroup group = target.instanceGroup();
            Map<String, String> updated = new LinkedHashMap<>(group.getTags());
            if (tags != null) {
                updated.putAll(tags);
            }
            group.setTags(updated);
            parameterGroups.put(group.getDbParameterGroupName(), group);
            return;
        }
        NeptuneClusterParameterGroup group = target.clusterGroup();
        Map<String, String> updated = new LinkedHashMap<>(group.getTags());
        if (tags != null) {
            updated.putAll(tags);
        }
        group.setTags(updated);
        clusterParameterGroups.put(group.getDbClusterParameterGroupName(), group);
    }

    public Map<String, String> listTagsForResource(String resourceName) {
        ArnTarget target = requireTagTarget(resourceName);
        if (target.instanceGroup() != null) {
            return new LinkedHashMap<>(target.instanceGroup().getTags());
        }
        return new LinkedHashMap<>(target.clusterGroup().getTags());
    }

    public void removeTagsFromResource(String resourceName, Collection<String> tagKeys) {
        ArnTarget target = requireTagTarget(resourceName);
        if (target.instanceGroup() != null) {
            NeptuneParameterGroup group = target.instanceGroup();
            Map<String, String> updated = new LinkedHashMap<>(group.getTags());
            if (tagKeys != null) {
                tagKeys.forEach(updated::remove);
            }
            group.setTags(updated);
            parameterGroups.put(group.getDbParameterGroupName(), group);
            return;
        }
        NeptuneClusterParameterGroup group = target.clusterGroup();
        Map<String, String> updated = new LinkedHashMap<>(group.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(updated::remove);
        }
        group.setTags(updated);
        clusterParameterGroups.put(group.getDbClusterParameterGroupName(), group);
    }

    private record ArnTarget(NeptuneParameterGroup instanceGroup, NeptuneClusterParameterGroup clusterGroup) {}

    private ArnTarget requireTagTarget(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceName is required.", 400);
        }
        if (!resourceName.startsWith("arn:")) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceName);
        } catch (IllegalArgumentException malformed) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        String resource = arn.resource();
        int sep = resource.indexOf(':');
        if (sep < 0) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        String type = resource.substring(0, sep);
        String id = resource.substring(sep + 1);
        return switch (type) {
            case "pg" -> new ArnTarget(getDbParameterGroup(id), null);
            case "cluster-pg" -> new ArnTarget(null, getDbClusterParameterGroup(id));
            default -> throw new AwsException("InvalidParameterValue",
                    "Invalid resource name: " + resourceName, 400);
        };
    }

    // ── Cluster snapshots ─────────────────────────────────────────────────────

    public NeptuneClusterSnapshot getDbClusterSnapshot(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "DBClusterSnapshotIdentifier is required.", 400);
        }
        return clusterSnapshots.get(snapshotId).orElseThrow(() ->
                new AwsException("DBClusterSnapshotNotFoundFault",
                        "DBClusterSnapshot " + snapshotId + " not found.", 404));
    }

    public Collection<NeptuneClusterSnapshot> listDbClusterSnapshots(String snapshotId, String clusterId) {
        if (snapshotId != null && !snapshotId.isBlank()) {
            return clusterSnapshots.get(snapshotId).map(List::of).orElse(List.of());
        }
        return clusterSnapshots.scan(k -> true).stream()
                .filter(s -> clusterId == null || clusterId.isBlank()
                        || clusterId.equals(s.getDbClusterIdentifier()))
                .toList();
    }

    public void deleteDbClusterSnapshot(String snapshotId) {
        getDbClusterSnapshot(snapshotId);
        clusterSnapshots.delete(snapshotId);
        LOG.infov("Neptune cluster snapshot {0} deleted", snapshotId);
    }

    public NeptuneClusterSnapshot copyDbClusterSnapshot(String sourceId, String targetId) {
        NeptuneClusterSnapshot source = getDbClusterSnapshot(sourceId);
        if (targetId == null || targetId.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "TargetDBClusterSnapshotIdentifier is required.", 400);
        }
        if (clusterSnapshots.get(targetId).isPresent()) {
            throw new AwsException("DBClusterSnapshotAlreadyExistsFault",
                    "DB cluster snapshot " + targetId + " already exists.", 400);
        }
        NeptuneClusterSnapshot copy = new NeptuneClusterSnapshot();
        copy.setDbClusterSnapshotIdentifier(targetId);
        copy.setDbClusterIdentifier(source.getDbClusterIdentifier());
        copy.setEngine(source.getEngine());
        copy.setSnapshotType(source.getSnapshotType());
        copy.setStatus("available");
        copy.setSnapshotCreateTime(Instant.now());
        copy.setDbClusterSnapshotArn(
                regionResolver.buildArn("rds", regionResolver.getDefaultRegion(), "cluster-snapshot:" + targetId));
        clusterSnapshots.put(targetId, copy);
        LOG.infov("Neptune cluster snapshot {0} copied to {1}", sourceId, targetId);
        return copy;
    }

    /**
     * Apply a pending maintenance action. Floci has no real Neptune maintenance
     * window, so an existing resource is a no-op that echoes the identifier. A
     * missing ARN is {@code ResourceNotFoundFault} — the typed tag Alchemy
     * bindings decode for {@code ApplyPendingMaintenanceAction}.
     */
    public String applyPendingMaintenanceAction(String resourceIdentifier) {
        if (resourceIdentifier == null || resourceIdentifier.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceIdentifier is required.", 400);
        }
        if (!resourceExists(resourceIdentifier)) {
            throw new AwsException("ResourceNotFoundFault",
                    "The resource identified by ResourceIdentifier " + resourceIdentifier + " does not exist.",
                    404);
        }
        return resourceIdentifier;
    }

    private boolean resourceExists(String resourceName) {
        String type = "cluster";
        String id = resourceName;
        if (resourceName.startsWith("arn:")) {
            AwsArnUtils.Arn arn;
            try {
                arn = AwsArnUtils.parse(resourceName);
            } catch (IllegalArgumentException malformed) {
                throw new AwsException("InvalidParameterValue",
                        "Invalid resource identifier: " + resourceName, 400);
            }
            // Neptune publishes ARNs under the rds service prefix; floci historically
            // also minted arn:aws:neptune:... for clusters.
            if (!"rds".equals(arn.service()) && !"neptune".equals(arn.service())) {
                throw new AwsException("InvalidParameterValue",
                        "Invalid resource identifier: " + resourceName, 400);
            }
            String resource = arn.resource();
            int sep = resource.indexOf(':');
            if (sep < 0) {
                throw new AwsException("InvalidParameterValue",
                        "Invalid resource identifier: " + resourceName, 400);
            }
            type = resource.substring(0, sep);
            id = resource.substring(sep + 1);
        }
        return switch (type) {
            case "db" -> instances.get(id).isPresent();
            case "cluster" -> clusters.get(id).isPresent();
            case "cluster-snapshot" -> clusterSnapshots.get(id).isPresent();
            case "cluster-pg" -> clusterParameterGroups.get(id).isPresent();
            case "pg" -> parameterGroups.get(id).isPresent();
            default -> false;
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    private int allocateProxyPort() {
        int base = config.services().neptune().proxyBasePort();
        int max = config.services().neptune().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        // Wire code the SDK maps to InsufficientStorageClusterCapacityFault (the only
        // capacity fault CreateDBCluster declares); "InsufficientNeptuneCapacity" isn't a
        // real Neptune code, so callers got an unmapped generic NeptuneException.
        throw new AwsException("InsufficientStorageClusterCapacity",
                "No available proxy ports in range " + base + "-" + max, 400);
    }

    private void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }
}
