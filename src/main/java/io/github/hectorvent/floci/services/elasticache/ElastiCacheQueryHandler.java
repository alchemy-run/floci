package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.model.CacheSnapshot;
import io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUser;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query-protocol handler for all ElastiCache actions (form-encoded POST, XML response).
 * Covers both the management plane (replication groups, users) and the auth-token
 * validation endpoint used by the Redis IAM auth flow.
 */
@ApplicationScoped
public class ElastiCacheQueryHandler {

    private static final Logger LOG = Logger.getLogger(ElastiCacheQueryHandler.class);

    private final SigV4Validator sigV4Validator;
    private final ElastiCacheService service;
    private final ElastiCacheMemcachedService memcachedService;
    private final RegionResolver regionResolver;

    @Inject
    public ElastiCacheQueryHandler(SigV4Validator sigV4Validator, ElastiCacheService service,
                                   ElastiCacheMemcachedService memcachedService,
                                   RegionResolver regionResolver) {
        this.sigV4Validator = sigV4Validator;
        this.service = service;
        this.memcachedService = memcachedService;
        this.regionResolver = regionResolver;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        LOG.debugv("ElastiCache action: {0}", action);
        return switch (action) {
            case "ValidateIamAuthToken"       -> handleValidateIamAuthToken(params);
            case "CreateReplicationGroup"     -> handleCreateReplicationGroup(params);
            case "DescribeReplicationGroups"  -> handleDescribeReplicationGroups(params);
            case "ModifyReplicationGroup"     -> handleModifyReplicationGroup(params);
            case "DeleteReplicationGroup"     -> handleDeleteReplicationGroup(params);
            case "CreateUser"                 -> handleCreateUser(params);
            case "DescribeUsers"              -> handleDescribeUsers(params);
            case "ModifyUser"                 -> handleModifyUser(params);
            case "DeleteUser"                 -> handleDeleteUser(params);
            case "CreateCacheCluster"         -> handleCreateCacheCluster(params);
            case "DescribeCacheClusters"      -> handleDescribeCacheClusters(params);
            case "DescribeCacheEngineVersions" -> handleDescribeCacheEngineVersions(params);
            case "ModifyCacheCluster"         -> handleModifyCacheCluster(params);
            case "DeleteCacheCluster"         -> handleDeleteCacheCluster(params);
            case "CreateCacheSubnetGroup"     -> handleCreateCacheSubnetGroup(params);
            case "DescribeCacheSubnetGroups"  -> handleDescribeCacheSubnetGroups(params);
            case "ModifyCacheSubnetGroup"     -> handleModifyCacheSubnetGroup(params);
            case "DeleteCacheSubnetGroup"     -> handleDeleteCacheSubnetGroup(params);
            case "IncreaseReplicaCount"       -> handleReplicaCount("IncreaseReplicaCount", params);
            case "DecreaseReplicaCount"       -> handleReplicaCount("DecreaseReplicaCount", params);
            case "ModifyReplicationGroupShardConfiguration" -> handleShardCount(params);
            case "TestFailover"               -> handleTestFailover(params);
            case "DescribeSnapshots"          -> handleDescribeSnapshots(params);
            case "DeleteSnapshot"             -> handleDeleteSnapshot(params);
            case "ListTagsForResource"        -> handleListTagsForResource(params);
            case "AddTagsToResource"          -> handleAddTagsToResource(params);
            case "RemoveTagsFromResource"     -> handleRemoveTagsFromResource(params);
            case "DescribeCacheParameterGroups" -> handleDescribeCacheParameterGroups(params);
            default -> AwsQueryResponse.error("UnsupportedOperation",
                    "Operation " + action + " is not supported.", AwsNamespaces.EC, 400);
        };
    }

    // ── Replication Groups ────────────────────────────────────────────────────

    private Response handleCreateReplicationGroup(MultivaluedMap<String, String> params) {
        String groupId = params.getFirst("ReplicationGroupId");
        String description = params.getFirst("ReplicationGroupDescription");
        String authToken = params.getFirst("AuthToken");

        if (groupId == null || groupId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "ReplicationGroupId is required.", AwsNamespaces.EC, 400);
        }

        String transitEncryption = params.getFirst("TransitEncryptionEnabled");
        AuthMode authMode;
        if (authToken != null && !authToken.isBlank()) {
            authMode = AuthMode.PASSWORD;
        } else if ("true".equalsIgnoreCase(transitEncryption)) {
            authMode = AuthMode.IAM;
        } else {
            authMode = AuthMode.NO_AUTH;
        }

        try {
            ReplicationGroup group = service.createReplicationGroup(
                    groupId, description != null ? description : "", authMode, authToken);
            group = configureReplicationGroup(group, params);
            String result = replicationGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("CreateReplicationGroup", AwsNamespaces.EC, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDescribeReplicationGroups(MultivaluedMap<String, String> params) {
        String filterId = params.getFirst("ReplicationGroupId");
        try {
            Collection<ReplicationGroup> groups = service.listReplicationGroups(filterId);
            var xml = new XmlBuilder().start("ReplicationGroups");
            for (ReplicationGroup g : groups) {
                xml.raw(replicationGroupXml(g));
            }
            xml.end("ReplicationGroups").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeReplicationGroups", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDeleteReplicationGroup(MultivaluedMap<String, String> params) {
        String groupId = params.getFirst("ReplicationGroupId");
        if (groupId == null || groupId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "ReplicationGroupId is required.", AwsNamespaces.EC, 400);
        }
        try {
            ReplicationGroup group = service.getReplicationGroup(groupId);
            service.deleteReplicationGroup(groupId, params.getFirst("FinalSnapshotIdentifier"));
            String result = replicationGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("DeleteReplicationGroup", AwsNamespaces.EC, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleModifyReplicationGroup(MultivaluedMap<String, String> params) {
        String groupId = params.getFirst("ReplicationGroupId");
        if (groupId == null || groupId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "ReplicationGroupId is required.", AwsNamespaces.EC, 400);
        }
        List<String> userIdsToAdd = extractMemberList(params, "UserGroupIdsToAdd.member.");
        List<String> userIdsToRemove = extractMemberList(params, "UserGroupIdsToRemove.member.");
        try {
            ReplicationGroup group = service.modifyReplicationGroup(groupId,
                    userIdsToAdd.isEmpty() ? null : userIdsToAdd,
                    userIdsToRemove.isEmpty() ? null : userIdsToRemove);
            group = configureReplicationGroup(group, params);
            String result = replicationGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("ModifyReplicationGroup", AwsNamespaces.EC, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    private Response handleCreateUser(MultivaluedMap<String, String> params) {
        String userId = params.getFirst("UserId");
        String userName = params.getFirst("UserName");
        String accessString = params.getFirst("AccessString");
        String authModeType = params.getFirst("AuthenticationMode.Type");

        if (userId == null || userId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "UserId is required.", AwsNamespaces.EC, 400);
        }
        if (userName == null || userName.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "UserName is required.", AwsNamespaces.EC, 400);
        }

        AuthMode authMode;
        List<String> passwords = new ArrayList<>();
        if ("iam".equalsIgnoreCase(authModeType)) {
            authMode = AuthMode.IAM;
        } else if ("password".equalsIgnoreCase(authModeType)) {
            authMode = AuthMode.PASSWORD;
            passwords = extractMemberList(params, "AuthenticationMode.Passwords.member.");
        } else {
            authMode = AuthMode.NO_AUTH;
        }

        try {
            ElastiCacheUser user = service.createUser(userId, userName, authMode, passwords, accessString);
            return Response.ok(AwsQueryResponse.envelope("CreateUser", AwsNamespaces.EC, userXml(user))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDescribeUsers(MultivaluedMap<String, String> params) {
        String filterId = params.getFirst("UserId");
        try {
            Collection<ElastiCacheUser> users = service.listUsers(filterId);
            var xml = new XmlBuilder().start("Users");
            for (ElastiCacheUser u : users) {
                xml.start("member").raw(userXml(u)).end("member");
            }
            xml.end("Users").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeUsers", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleModifyUser(MultivaluedMap<String, String> params) {
        String userId = params.getFirst("UserId");
        if (userId == null || userId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "UserId is required.", AwsNamespaces.EC, 400);
        }
        List<String> passwords = extractMemberList(params, "AuthenticationMode.Passwords.member.");
        try {
            ElastiCacheUser user = service.modifyUser(userId, passwords.isEmpty() ? null : passwords);
            return Response.ok(AwsQueryResponse.envelope("ModifyUser", AwsNamespaces.EC, userXml(user))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDeleteUser(MultivaluedMap<String, String> params) {
        String userId = params.getFirst("UserId");
        if (userId == null || userId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "UserId is required.", AwsNamespaces.EC, 400);
        }
        try {
            ElastiCacheUser user = service.getUser(userId);
            service.deleteUser(userId);
            return Response.ok(AwsQueryResponse.envelope("DeleteUser", AwsNamespaces.EC, userXml(user))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    // ── Cache Clusters (Memcached) ────────────────────────────────────────────

    private Response handleCreateCacheCluster(MultivaluedMap<String, String> params) {
        String clusterId = params.getFirst("CacheClusterId");
        String engine = params.getFirst("Engine");

        if (clusterId == null || clusterId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "CacheClusterId is required.", AwsNamespaces.EC, 400);
        }
        if (!"memcached".equalsIgnoreCase(engine)) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "Engine must be 'memcached'. For Redis/Valkey use CreateReplicationGroup.", AwsNamespaces.EC, 400);
        }

        try {
            CacheCluster cluster = configureCacheCluster(memcachedService.createCacheCluster(clusterId), params);
            return Response.ok(AwsQueryResponse.envelope("CreateCacheCluster", AwsNamespaces.EC, cacheClusterXml(cluster))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDescribeCacheClusters(MultivaluedMap<String, String> params) {
        String filterId = params.getFirst("CacheClusterId");
        try {
            var xml = new XmlBuilder().start("CacheClusters");
            CacheCluster replicationMember = filterId == null ? null : replicationGroupMember(filterId);
            if (replicationMember != null) {
                xml.raw(cacheClusterXml(replicationMember));
            } else {
                for (CacheCluster c : memcachedService.listCacheClusters(filterId)) {
                    xml.raw(cacheClusterXml(c));
                }
            }
            xml.end("CacheClusters").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeCacheClusters", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private CacheCluster replicationGroupMember(String clusterId) {
        for (ReplicationGroup group : service.listReplicationGroups(null)) {
            for (int nodeGroup = 1; nodeGroup <= group.getNodeGroupCount(); nodeGroup++) {
                for (int member = 1; member <= group.getReplicasPerNodeGroup() + 1; member++) {
                    String memberId = group.getReplicationGroupId()
                            + String.format("-%04d-%03d", nodeGroup, member);
                    if (!memberId.equals(clusterId)) continue;
                    CacheCluster result = new CacheCluster(memberId, CacheClusterStatus.AVAILABLE,
                            group.getEngine(), group.getEngineVersion(),
                            group.getConfigurationEndpoint(), group.getCreatedAt());
                    result.setCacheNodeType(group.getCacheNodeType());
                    result.setSecurityGroupIds(group.getSecurityGroupIds());
                    return result;
                }
            }
        }
        return null;
    }

    private Response handleDescribeCacheEngineVersions(MultivaluedMap<String, String> params) {
        String engine = params.getFirst("Engine");
        String version = params.getFirst("EngineVersion");
        String selectedEngine = engine == null || engine.isBlank() ? "valkey" : engine;
        List<String> versions = "redis".equalsIgnoreCase(selectedEngine)
                ? List.of("7.0", "7.1")
                : List.of("8.0", "8.1");
        var xml = new XmlBuilder().start("CacheEngineVersions");
        for (String candidate : versions) {
            if (version != null && !version.equals(candidate)) continue;
            xml.start("CacheEngineVersion")
               .elem("Engine", selectedEngine)
               .elem("EngineVersion", candidate)
               .elem("CacheParameterGroupFamily", selectedEngine + candidate.split("\\.")[0])
               .end("CacheEngineVersion");
        }
        xml.end("CacheEngineVersions").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeCacheEngineVersions", AwsNamespaces.EC, xml.build())).build();
    }

    private Response handleTestFailover(MultivaluedMap<String, String> params) {
        try {
            ReplicationGroup group = service.getReplicationGroup(params.getFirst("ReplicationGroupId"));
            if (group.getReplicasPerNodeGroup() < 1) {
                return AwsQueryResponse.error("InvalidReplicationGroupStateFault",
                        "Replication group has no replica to fail over to.", AwsNamespaces.EC, 400);
            }
            return Response.ok(AwsQueryResponse.envelope("TestFailover", AwsNamespaces.EC, replicationGroupXml(group))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleModifyCacheCluster(MultivaluedMap<String, String> params) {
        String clusterId = params.getFirst("CacheClusterId");
        if (clusterId == null || clusterId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "CacheClusterId is required.", AwsNamespaces.EC, 400);
        }
        try {
            CacheCluster cluster = configureCacheCluster(memcachedService.getCacheCluster(clusterId), params);
            return Response.ok(AwsQueryResponse.envelope("ModifyCacheCluster", AwsNamespaces.EC, cacheClusterXml(cluster))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDeleteCacheCluster(MultivaluedMap<String, String> params) {
        String clusterId = params.getFirst("CacheClusterId");
        if (clusterId == null || clusterId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "CacheClusterId is required.", AwsNamespaces.EC, 400);
        }
        try {
            CacheCluster cluster = memcachedService.deleteCacheCluster(clusterId);
            return Response.ok(AwsQueryResponse.envelope("DeleteCacheCluster", AwsNamespaces.EC, cacheClusterXml(cluster))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    // ── Subnet / Parameter Groups ────────────────────────────────────────────

    private Response handleCreateCacheSubnetGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("CacheSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "CacheSubnetGroupName is required.", AwsNamespaces.EC, 400);
        }
        try {
            CacheSubnetGroup group = service.createSubnetGroup(name,
                    params.getFirst("CacheSubnetGroupDescription"),
                    extractMemberList(params, "SubnetIds.SubnetIdentifier."), extractTags(params));
            return Response.ok(AwsQueryResponse.envelope("CreateCacheSubnetGroup", AwsNamespaces.EC, subnetGroupXml(group))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDescribeCacheSubnetGroups(MultivaluedMap<String, String> params) {
        try {
            Collection<CacheSubnetGroup> groups = service.listSubnetGroups(params.getFirst("CacheSubnetGroupName"));
            var xml = new XmlBuilder().start("CacheSubnetGroups");
            for (CacheSubnetGroup group : groups) {
                xml.raw(subnetGroupXml(group));
            }
            xml.end("CacheSubnetGroups");
            return Response.ok(AwsQueryResponse.envelope("DescribeCacheSubnetGroups", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleModifyCacheSubnetGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("CacheSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "CacheSubnetGroupName is required.", AwsNamespaces.EC, 400);
        }
        try {
            CacheSubnetGroup group = service.modifySubnetGroup(name,
                    params.getFirst("CacheSubnetGroupDescription"), extractMemberList(params, "SubnetIds.SubnetIdentifier."));
            return Response.ok(AwsQueryResponse.envelope("ModifyCacheSubnetGroup", AwsNamespaces.EC, subnetGroupXml(group))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDeleteCacheSubnetGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("CacheSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "CacheSubnetGroupName is required.", AwsNamespaces.EC, 400);
        }
        try {
            service.deleteSubnetGroup(name);
            return Response.ok(AwsQueryResponse.envelope("DeleteCacheSubnetGroup", AwsNamespaces.EC, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDescribeCacheParameterGroups(MultivaluedMap<String, String> params) {
        // Cache parameter groups are not modeled by the emulator; return the wire-accurate
        // empty result so SDK clients get a valid 200 instead of an unsupported-action 400.
        var xml = new XmlBuilder().start("CacheParameterGroups").end("CacheParameterGroups");
        return Response.ok(AwsQueryResponse.envelope("DescribeCacheParameterGroups", AwsNamespaces.EC, xml.build())).build();
    }

    private Response handleReplicaCount(String action, MultivaluedMap<String, String> params) {
        String groupId = params.getFirst("ReplicationGroupId");
        Integer replicaCount = parseInteger(params.getFirst("NewReplicaCount"));
        if (groupId == null || groupId.isBlank() || replicaCount == null) {
            return AwsQueryResponse.error("InvalidParameterValue", "ReplicationGroupId and NewReplicaCount are required.", AwsNamespaces.EC, 400);
        }
        try {
            ReplicationGroup group = service.setReplicaCount(groupId, replicaCount);
            return Response.ok(AwsQueryResponse.envelope(action, AwsNamespaces.EC, replicationGroupXml(group))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleShardCount(MultivaluedMap<String, String> params) {
        String groupId = params.getFirst("ReplicationGroupId");
        Integer nodeGroupCount = parseInteger(params.getFirst("NodeGroupCount"));
        if (groupId == null || groupId.isBlank() || nodeGroupCount == null) {
            return AwsQueryResponse.error("InvalidParameterValue", "ReplicationGroupId and NodeGroupCount are required.", AwsNamespaces.EC, 400);
        }
        try {
            ReplicationGroup group = service.setNodeGroupCount(groupId, nodeGroupCount);
            return Response.ok(AwsQueryResponse.envelope("ModifyReplicationGroupShardConfiguration", AwsNamespaces.EC, replicationGroupXml(group))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDescribeSnapshots(MultivaluedMap<String, String> params) {
        try {
            Collection<CacheSnapshot> snapshots = service.listSnapshots(params.getFirst("SnapshotName"));
            var xml = new XmlBuilder().start("Snapshots");
            for (CacheSnapshot snapshot : snapshots) {
                xml.raw(snapshotXml(snapshot));
            }
            xml.end("Snapshots");
            return Response.ok(AwsQueryResponse.envelope("DescribeSnapshots", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDeleteSnapshot(MultivaluedMap<String, String> params) {
        String name = params.getFirst("SnapshotName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "SnapshotName is required.", AwsNamespaces.EC, 400);
        }
        try {
            CacheSnapshot snapshot = service.deleteSnapshot(name);
            return Response.ok(AwsQueryResponse.envelope("DeleteSnapshot", AwsNamespaces.EC, snapshotXml(snapshot))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleListTagsForResource(MultivaluedMap<String, String> params) {
        try {
            var xml = new XmlBuilder().start("TagList");
            for (Map.Entry<String, String> tag : tagsForResource(params.getFirst("ResourceName")).entrySet()) {
                xml.start("Tag").elem("Key", tag.getKey()).elem("Value", tag.getValue()).end("Tag");
            }
            xml.end("TagList");
            return Response.ok(AwsQueryResponse.envelope("ListTagsForResource", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleAddTagsToResource(MultivaluedMap<String, String> params) {
        try {
            String resourceName = params.getFirst("ResourceName");
            tagsForResource(resourceName).putAll(extractTags(params));
            saveTaggedResource(resourceName);
            return Response.ok(AwsQueryResponse.envelope("AddTagsToResource", AwsNamespaces.EC, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleRemoveTagsFromResource(MultivaluedMap<String, String> params) {
        try {
            String resourceName = params.getFirst("ResourceName");
            Map<String, String> tags = tagsForResource(resourceName);
            for (String key : extractMemberList(params, "TagKeys.member.")) {
                tags.remove(key);
            }
            saveTaggedResource(resourceName);
            return Response.ok(AwsQueryResponse.envelope("RemoveTagsFromResource", AwsNamespaces.EC, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    // ── IAM Token Validation ──────────────────────────────────────────────────

    private Response handleValidateIamAuthToken(MultivaluedMap<String, String> params) {
        String token = params.getFirst("Token");
        if (token == null || token.isBlank()) {
            return AwsQueryResponse.error("InvalidParameter", "Token parameter is required.", AwsNamespaces.EC, 400);
        }
        try {
            boolean valid = sigV4Validator.validate(token, null, null);
            if (!valid) {
                return AwsQueryResponse.error("SignatureDoesNotMatch",
                        "The request signature does not match.", AwsNamespaces.EC, 403);
            }
            String clusterId = extractUriHost(token);
            String userId = extractQueryParam(token, "User");
            LOG.infov("ElastiCache IAM token validated: clusterId={0} userId={1}", clusterId, userId);
            String result = new XmlBuilder()
                    .elem("Valid", true)
                    .elem("ClusterId", clusterId)
                    .elem("UserId", userId)
                    .build();
            return Response.ok(AwsQueryResponse.envelope("ValidateIamAuthToken", AwsNamespaces.EC, result)).build();
        } catch (Exception e) {
            LOG.warnv("ElastiCache token validation error: {0}", e.getMessage());
            return AwsQueryResponse.error("InvalidToken",
                    "Failed to validate token: " + e.getMessage(), AwsNamespaces.EC, 400);
        }
    }

    // ── XML helpers ───────────────────────────────────────────────────────────

    private ReplicationGroup configureReplicationGroup(ReplicationGroup group, MultivaluedMap<String, String> params) {
        if (params.containsKey("ReplicationGroupDescription")) group.setDescription(params.getFirst("ReplicationGroupDescription"));
        if (params.containsKey("Engine")) group.setEngine(params.getFirst("Engine"));
        if (params.containsKey("EngineVersion")) group.setEngineVersion(params.getFirst("EngineVersion"));
        if (params.containsKey("CacheNodeType")) group.setCacheNodeType(params.getFirst("CacheNodeType"));
        if (params.containsKey("NumNodeGroups")) group.setNodeGroupCount(parseInteger(params.getFirst("NumNodeGroups"), group.getNodeGroupCount()));
        if (params.containsKey("ReplicasPerNodeGroup")) group.setReplicasPerNodeGroup(parseInteger(params.getFirst("ReplicasPerNodeGroup"), group.getReplicasPerNodeGroup()));
        if (params.containsKey("TransitEncryptionEnabled")) group.setTransitEncryptionEnabled(parseBoolean(params.getFirst("TransitEncryptionEnabled")));
        if (params.containsKey("AutomaticFailoverEnabled")) group.setAutomaticFailoverEnabled(parseBoolean(params.getFirst("AutomaticFailoverEnabled")));
        if (params.containsKey("MultiAZEnabled")) group.setMultiAzEnabled(parseBoolean(params.getFirst("MultiAZEnabled")));
        if (params.containsKey("SecurityGroupIds.SecurityGroupId.1")) {
            group.setSecurityGroupIds(extractMemberList(params, "SecurityGroupIds.SecurityGroupId."));
        }
        if (params.containsKey("Port") && group.getConfigurationEndpoint() != null) {
            group.setConfigurationEndpoint(new Endpoint(group.getConfigurationEndpoint().address(),
                    parseInteger(params.getFirst("Port"), group.getConfigurationEndpoint().port())));
        }
        if (params.containsKey("SnapshotRetentionLimit")) group.setSnapshotRetentionLimit(parseInteger(params.getFirst("SnapshotRetentionLimit"), group.getSnapshotRetentionLimit()));
        if (params.containsKey("SnapshotWindow")) group.setSnapshotWindow(params.getFirst("SnapshotWindow"));
        if (params.containsKey("PreferredMaintenanceWindow")) group.setMaintenanceWindow(params.getFirst("PreferredMaintenanceWindow"));
        if (params.containsKey("Tags.Tag.1.Key")) group.setTags(extractTags(params));
        return service.saveReplicationGroup(group);
    }

    private CacheCluster configureCacheCluster(CacheCluster cluster, MultivaluedMap<String, String> params) {
        if (params.containsKey("NumCacheNodes")) cluster.setNumCacheNodes(parseInteger(params.getFirst("NumCacheNodes"), cluster.getNumCacheNodes()));
        if (params.containsKey("CacheNodeType")) cluster.setCacheNodeType(params.getFirst("CacheNodeType"));
        if (params.containsKey("Tags.Tag.1.Key")) cluster.setTags(extractTags(params));
        return memcachedService.saveCacheCluster(cluster);
    }

    private Map<String, String> tagsForResource(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceName is required.", 400);
        }
        String id = arn.substring(arn.lastIndexOf(':') + 1);
        if (arn.contains(":replicationgroup:")) return service.getReplicationGroup(id).getTags();
        if (arn.contains(":cluster:")) return memcachedService.getCacheCluster(id).getTags();
        if (arn.contains(":subnetgroup:")) return service.getSubnetGroup(id).getTags();
        throw new AwsException("InvalidParameterValue", "Unknown ElastiCache resource " + arn + ".", 400);
    }

    private void saveTaggedResource(String arn) {
        String id = arn.substring(arn.lastIndexOf(':') + 1);
        if (arn.contains(":replicationgroup:")) {
            service.saveReplicationGroup(service.getReplicationGroup(id));
        } else if (arn.contains(":cluster:")) {
            memcachedService.saveCacheCluster(memcachedService.getCacheCluster(id));
        } else if (arn.contains(":subnetgroup:")) {
            CacheSubnetGroup group = service.getSubnetGroup(id);
            service.modifySubnetGroup(id, group.getDescription(), group.getSubnetIds());
        }
    }

    private String elasticacheArn(String type, String id) {
        return AwsArnUtils.Arn.of("elasticache", regionResolver.getDefaultRegion(), regionResolver.getAccountId(), type + ":" + id).toString();
    }

    private String cacheClusterXml(CacheCluster c) {
        Endpoint ep = c.getConfigurationEndpoint();
        var xml = new XmlBuilder()
                .start("CacheCluster")
                  .elem("CacheClusterId", c.getCacheClusterId())
                  .elem("ARN", elasticacheArn("cluster", c.getCacheClusterId()))
                  .elem("CacheClusterStatus", c.getCacheClusterStatus().name().toLowerCase())
                  .elem("Engine", c.getEngine())
                  .elem("EngineVersion", c.getEngineVersion())
                  .elem("CacheNodeType", c.getCacheNodeType())
                  .elem("NumCacheNodes", (long) c.getNumCacheNodes());
        if (ep != null) {
            xml.start("ConfigurationEndpoint")
               .elem("Address", ep.address())
               .elem("Port", (long) ep.port())
               .end("ConfigurationEndpoint");
            xml.start("CacheNodes");
            for (int i = 1; i <= c.getNumCacheNodes(); i++) {
                xml.start("CacheNode")
                   .elem("CacheNodeId", c.getCacheClusterId() + String.format("-%04d", i))
                   .start("Endpoint")
                     .elem("Address", ep.address())
                     .elem("Port", (long) ep.port())
                   .end("Endpoint")
                   .end("CacheNode");
            }
            xml.end("CacheNodes");
        }
        if (!c.getSecurityGroupIds().isEmpty()) {
            xml.start("SecurityGroups");
            for (String groupId : c.getSecurityGroupIds()) {
                xml.start("member")
                   .elem("SecurityGroupId", groupId)
                   .elem("Status", "active")
                   .end("member");
            }
            xml.end("SecurityGroups");
        }
        return xml.end("CacheCluster").build();
    }

    private String replicationGroupXml(ReplicationGroup g) {
        Endpoint ep = g.getConfigurationEndpoint();
        boolean authTokenEnabled = g.getAuthMode() == AuthMode.PASSWORD;
        var xml = new XmlBuilder()
                .start("ReplicationGroup")
                  .elem("ReplicationGroupId", g.getReplicationGroupId())
                  .elem("ARN", elasticacheArn("replicationgroup", g.getReplicationGroupId()))
                  .elem("Description", g.getDescription())
                  .elem("Status", g.getStatus().name().toLowerCase())
                  .elem("Engine", g.getEngine())
                  .elem("EngineVersion", g.getEngineVersion())
                  .elem("CacheNodeType", g.getCacheNodeType())
                  .elem("AuthTokenEnabled", authTokenEnabled)
                  .elem("TransitEncryptionEnabled", g.isTransitEncryptionEnabled())
                  .elem("AtRestEncryptionEnabled", false)
                  .elem("ClusterEnabled", false)
                  .elem("MultiAZ", g.isMultiAzEnabled() ? "enabled" : "disabled")
                  .elem("AutomaticFailover", g.isAutomaticFailoverEnabled() ? "enabled" : "disabled")
                  .elem("SnapshotRetentionLimit", (long) g.getSnapshotRetentionLimit())
                  .elem("SnapshotWindow", g.getSnapshotWindow())
                  .elem("PreferredMaintenanceWindow", g.getMaintenanceWindow());
        xml.start("MemberClusters");
        for (int group = 1; group <= g.getNodeGroupCount(); group++) {
            for (int member = 1; member <= g.getReplicasPerNodeGroup() + 1; member++) {
                xml.elem("ClusterId", g.getReplicationGroupId()
                        + String.format("-%04d-%03d", group, member));
            }
        }
        xml.end("MemberClusters");
        if (ep != null) {
            xml.start("ConfigurationEndpoint")
               .elem("Address", ep.address())
               .elem("Port", (long) ep.port())
               .end("ConfigurationEndpoint");
            xml.start("NodeGroups");
            for (int group = 1; group <= g.getNodeGroupCount(); group++) {
                xml.start("NodeGroup")
                   .elem("NodeGroupId", String.format("%04d", group))
                   .start("PrimaryEndpoint")
                     .elem("Address", ep.address())
                     .elem("Port", (long) ep.port())
                   .end("PrimaryEndpoint")
                   .start("ReaderEndpoint")
                     .elem("Address", ep.address())
                     .elem("Port", (long) ep.port())
                   .end("ReaderEndpoint")
                   .start("NodeGroupMembers");
                for (int member = 0; member <= g.getReplicasPerNodeGroup(); member++) {
                    xml.start("NodeGroupMember")
                       .elem("CacheClusterId", g.getReplicationGroupId() + String.format("-%04d-%03d", group, member + 1))
                       .elem("CurrentRole", member == 0 ? "primary" : "replica")
                       .end("NodeGroupMember");
                }
                xml.end("NodeGroupMembers").end("NodeGroup");
            }
            xml.end("NodeGroups");
        }
        return xml.end("ReplicationGroup").build();
    }

    private String subnetGroupXml(CacheSubnetGroup group) {
        var xml = new XmlBuilder()
                .start("CacheSubnetGroup")
                  .elem("CacheSubnetGroupName", group.getCacheSubnetGroupName())
                  .elem("CacheSubnetGroupDescription", group.getDescription())
                  .elem("VpcId", "vpc-default")
                  .elem("ARN", elasticacheArn("subnetgroup", group.getCacheSubnetGroupName()))
                  .start("Subnets");
        for (String subnetId : group.getSubnetIds()) {
            xml.start("Subnet").elem("SubnetIdentifier", subnetId).end("Subnet");
        }
        return xml.end("Subnets").end("CacheSubnetGroup").build();
    }

    private String snapshotXml(CacheSnapshot snapshot) {
        return new XmlBuilder()
                .start("Snapshot")
                  .elem("SnapshotName", snapshot.getSnapshotName())
                  .elem("ReplicationGroupId", snapshot.getReplicationGroupId())
                  .elem("SnapshotStatus", "available")
                .end("Snapshot")
                .build();
    }

    private String userXml(ElastiCacheUser u) {
        String authType = switch (u.getAuthMode()) {
            case IAM -> "iam";
            case PASSWORD -> "password";
            case NO_AUTH -> "no-password-required";
        };
        int pwCount = (u.getPasswords() != null) ? u.getPasswords().size() : 0;
        return new XmlBuilder()
                .elem("UserId", u.getUserId())
                .elem("UserName", u.getUserName())
                .elem("Status", u.getStatus())
                .elem("AccessString", u.getAccessString())
                .start("Authentication")
                  .elem("Type", authType)
                  .elem("PasswordCount", (long) pwCount)
                .end("Authentication")
                .elem("Engine", "redis")
                .elem("MinimumEngineVersion", "6.0")
                .start("UserGroupIds").end("UserGroupIds")
                .elem("ARN", AwsArnUtils.Arn.of("elasticache", regionResolver.getDefaultRegion(), regionResolver.getAccountId(), "user:" + u.getUserId()).toString())
                .build();
    }

    private static List<String> extractMemberList(MultivaluedMap<String, String> params, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = params.getFirst(prefix + i);
            if (value == null) {
                break;
            }
            values.add(value);
        }
        return values;
    }

    private static Map<String, String> extractTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 1; ; i++) {
            String key = params.getFirst("Tags.Tag." + i + ".Key");
            if (key == null) {
                break;
            }
            tags.put(key, params.getFirst("Tags.Tag." + i + ".Value"));
        }
        return tags;
    }

    private static Integer parseInteger(String value) {
        try {
            return value != null ? Integer.valueOf(value) : null;
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", "Expected an integer value.", 400);
        }
    }

    private static int parseInteger(String value, int fallback) {
        Integer parsed = parseInteger(value);
        return parsed != null ? parsed : fallback;
    }

    private static boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value) || "enabled".equalsIgnoreCase(value);
    }

    private static String extractUriHost(String token) {
        try {
            return java.net.URI.create("http://" + token).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractQueryParam(String token, String name) {
        try {
            String rawQuery = java.net.URI.create("http://" + token).getRawQuery();
            if (rawQuery == null) {
                return "";
            }
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq >= 0 && name.equals(pair.substring(0, eq))) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}
        return "";
    }
}
