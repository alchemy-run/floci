package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheEvent;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUser;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCacheSnapshot;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Core ElastiCache business logic — replication groups, users, and serverless caches.
 * Creates Valkey containers and auth proxies on group creation.
 */
@ApplicationScoped
public class ElastiCacheService {

    private static final Logger LOG = Logger.getLogger(ElastiCacheService.class);

    private final StorageBackend<String, ReplicationGroup> groups;
    private final StorageBackend<String, ElastiCacheUser> users;
    private final StorageBackend<String, ServerlessCache> serverlessCaches;
    private final StorageBackend<String, ServerlessCacheSnapshot> serverlessSnapshots;
    private final ElastiCacheContainerManager containerManager;
    private final ElastiCacheProxyManager proxyManager;
    private final EmulatorConfig config;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    private final Set<String> provisioningGroupIds = ConcurrentHashMap.newKeySet();
    private final List<CacheEvent> events = new CopyOnWriteArrayList<>();

    @Inject
    public ElastiCacheService(ElastiCacheContainerManager containerManager,
                              ElastiCacheProxyManager proxyManager,
                              StorageFactory storageFactory,
                              EmulatorConfig config) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.config = config;
        this.groups = storageFactory.create("elasticache", "elasticache-groups.json",
                new TypeReference<Map<String, ReplicationGroup>>() {});
        this.users = storageFactory.create("elasticache", "elasticache-users.json",
                new TypeReference<Map<String, ElastiCacheUser>>() {});
        this.serverlessCaches = storageFactory.create("elasticache", "elasticache-serverless-caches.json",
                new TypeReference<Map<String, ServerlessCache>>() {});
        this.serverlessSnapshots = storageFactory.create("elasticache", "elasticache-serverless-snapshots.json",
                new TypeReference<Map<String, ServerlessCacheSnapshot>>() {});
    }

    public ReplicationGroup createReplicationGroup(String groupId, String description,
                                                   AuthMode authMode, String authToken) {
        if (groups.get(groupId).isPresent()) {
            throw new AwsException("ReplicationGroupAlreadyExistsFault",
                    "Replication group " + groupId + " already exists.", 400);
        }
        // Claim the id for the whole provisioning attempt so a concurrent create can't race
        // ahead and be stopped by this request's handle-less rollback fallback.
        if (!provisioningGroupIds.add(groupId)) {
            throw new AwsException("ReplicationGroupAlreadyExistsFault",
                    "Replication group " + groupId + " is already being created.", 400);
        }

        try {
            int proxyPort = allocateProxyPort();
            String image = config.services().elasticache().defaultImage();

            LOG.infov("Creating replication group {0} with authMode={1} on proxy port {2}",
                    groupId, authMode, String.valueOf(proxyPort));

            ElastiCacheContainerHandle handle = null;
            try {
                handle = containerManager.start(groupId, image);

                String endpointHost = resolveEndpointHost();
                Endpoint endpoint = new Endpoint(endpointHost, proxyPort);
                ReplicationGroup group = new ReplicationGroup(
                        groupId, description, ReplicationGroupStatus.AVAILABLE,
                        authMode, endpoint, Instant.now(), proxyPort);
                group.setContainerId(handle.getContainerId());
                group.setContainerHost(handle.getHost());
                group.setContainerPort(handle.getPort());
                group.setAuthToken(authToken);

                proxyManager.startProxy(groupId, authMode, proxyPort,
                        handle.getHost(), handle.getPort(),
                        (username, password) -> validatePassword(groupId, username, password));

                groups.put(groupId, group);
                LOG.infov("Replication group {0} created, endpoint={1}:{2}", groupId, endpointHost, String.valueOf(proxyPort));
                return group;
            } catch (RuntimeException e) {
                LOG.warnv("Replication group {0} provisioning failed, rolling back: {1}", groupId, e.getMessage());
                rollbackReplicationGroup(groupId, handle, proxyPort);
                throw e;
            }
        } finally {
            provisioningGroupIds.remove(groupId);
        }
    }

    private void rollbackReplicationGroup(String groupId, ElastiCacheContainerHandle handle, int proxyPort) {
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
            releaseProxyPort(proxyPort);
        }
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
        ReplicationGroup group = groups.get(groupId).orElseThrow(() ->
                new AwsException("ReplicationGroupNotFoundFault",
                        "Replication group " + groupId + " not found.", 404));

        group.setStatus(ReplicationGroupStatus.DELETING);
        groups.put(groupId, group);

        proxyManager.stopProxy(groupId);

        if (group.getContainerId() != null) {
            containerManager.stop(new ElastiCacheContainerHandle(
                    group.getContainerId(), groupId, group.getContainerHost(), group.getContainerPort()));
        }

        releaseProxyPort(group.getProxyPort());
        groups.delete(groupId);
        LOG.infov("Replication group {0} deleted", groupId);
    }

    public ReplicationGroup modifyReplicationGroup(String groupId, List<String> userIdsToAdd,
                                                    List<String> userIdsToRemove) {
        ReplicationGroup group = getReplicationGroup(groupId);

        if (userIdsToAdd != null) {
            for (String userId : userIdsToAdd) {
                getUser(userId); // validate user exists
                group.getAssociatedUserIds().add(userId);
            }
        }
        if (userIdsToRemove != null) {
            group.getAssociatedUserIds().removeAll(userIdsToRemove);
        }

        groups.put(groupId, group);
        return group;
    }

    public ElastiCacheUser createUser(String userId, String userName, AuthMode authMode,
                                      List<String> passwords, String accessString) {
        if (users.get(userId).isPresent()) {
            throw new AwsException("UserAlreadyExistsFault",
                    "User " + userId + " already exists.", 400);
        }

        ElastiCacheUser user = new ElastiCacheUser(
                userId, userName, authMode,
                passwords != null ? passwords : List.of(),
                accessString != null ? accessString : "on ~* +@all",
                "active", Instant.now());

        users.put(userId, user);
        LOG.infov("ElastiCache user {0} created with authMode={1}", userId, authMode);
        return user;
    }

    public ElastiCacheUser getUser(String userId) {
        return users.get(userId).orElseThrow(() ->
                new AwsException("UserNotFoundFault", "User " + userId + " not found.", 404));
    }

    public Collection<ElastiCacheUser> listUsers(String filterUserId) {
        if (filterUserId != null && !filterUserId.isBlank()) {
            return users.get(filterUserId)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("UserNotFoundFault",
                            "User " + filterUserId + " not found.", 404));
        }
        return users.scan(k -> true);
    }

    public ElastiCacheUser modifyUser(String userId, List<String> passwords) {
        ElastiCacheUser user = getUser(userId);
        if (passwords != null) {
            user.setPasswords(passwords);
        }
        users.put(userId, user);
        return user;
    }

    public void deleteUser(String userId) {
        if (users.get(userId).isEmpty()) {
            throw new AwsException("UserNotFoundFault", "User " + userId + " not found.", 404);
        }
        users.delete(userId);
        LOG.infov("ElastiCache user {0} deleted", userId);
    }

    public ServerlessCache createServerlessCache(ServerlessCache cache) {
        String name = cache.getServerlessCacheName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "ServerlessCacheName is required.", 400);
        }
        if (serverlessCaches.get(name).isPresent()) {
            throw new AwsException("ServerlessCacheAlreadyExistsFault",
                    "Serverless cache " + name + " already exists.", 400);
        }

        String engine = cache.getEngine() == null || cache.getEngine().isBlank()
                ? "valkey" : cache.getEngine().toLowerCase();
        cache.setEngine(engine);
        if (cache.getMajorEngineVersion() == null || cache.getMajorEngineVersion().isBlank()) {
            cache.setMajorEngineVersion(defaultMajorEngineVersion(engine));
        }
        if (cache.getFullEngineVersion() == null || cache.getFullEngineVersion().isBlank()) {
            cache.setFullEngineVersion(defaultFullEngineVersion(engine, cache.getMajorEngineVersion()));
        }
        if (cache.getStatus() == null) {
            cache.setStatus("available");
        }
        if (cache.getCreateTime() == null) {
            cache.setCreateTime(Instant.now());
        }
        if (cache.getNetworkType() == null || cache.getNetworkType().isBlank()) {
            cache.setNetworkType("ipv4");
        }
        if (cache.getSnapshotRetentionLimit() == null) {
            cache.setSnapshotRetentionLimit(0);
        }
        if (cache.getDataStorageMaximum() == null) {
            cache.setDataStorageMaximum(5000);
        }
        if (cache.getDataStorageMinimum() == null) {
            cache.setDataStorageMinimum(1);
        }
        if (cache.getDataStorageUnit() == null || cache.getDataStorageUnit().isBlank()) {
            cache.setDataStorageUnit("GB");
        }
        if (cache.getEcpuPerSecondMaximum() == null) {
            cache.setEcpuPerSecondMaximum(15_000_000);
        }
        if (cache.getEcpuPerSecondMinimum() == null) {
            cache.setEcpuPerSecondMinimum(1000);
        }
        cache.setStorageEncryptionType(cache.getKmsKeyId() == null || cache.getKmsKeyId().isBlank()
                ? "AWS_OWNED_KMS" : "KMS");

        String region = config.defaultRegion();
        String account = config.defaultAccountId();
        cache.setArn(AwsArnUtils.Arn.of("elasticache", region, account, "serverlesscache:" + name).toString());

        int port = "memcached".equals(engine) ? 11211 : 6379;
        String suffix = Integer.toHexString(name.hashCode() & 0xfffffff);
        String address = name + "-" + suffix + ".serverless." + region + ".cache.amazonaws.com";
        cache.setEndpoint(new Endpoint(address, port));
        if (!"memcached".equals(engine)) {
            cache.setReaderEndpoint(new Endpoint(
                    name + "-" + suffix + "-ro.serverless." + region + ".cache.amazonaws.com", port));
        }

        serverlessCaches.put(name, cache);
        recordEvent(name, "serverless-cache", "Serverless cache created");
        LOG.infov("Serverless cache {0} created, endpoint={1}:{2}", name, address, String.valueOf(port));
        return cache;
    }

    public ServerlessCache getServerlessCache(String name) {
        return serverlessCaches.get(name).orElseThrow(() ->
                new AwsException("ServerlessCacheNotFoundFault",
                        "Serverless cache " + name + " not found.", 404));
    }

    public Collection<ServerlessCache> listServerlessCaches(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return List.of(getServerlessCache(filterName));
        }
        return serverlessCaches.scan(k -> true);
    }

    public ServerlessCache modifyServerlessCache(String name, String description,
                                                 Integer dataStorageMaximum, Integer dataStorageMinimum,
                                                 String dataStorageUnit,
                                                 Integer ecpuMaximum, Integer ecpuMinimum,
                                                 Boolean removeUserGroup, String userGroupId,
                                                 List<String> securityGroupIds,
                                                 Integer snapshotRetentionLimit, String dailySnapshotTime,
                                                 String engine, String majorEngineVersion) {
        ServerlessCache cache = getServerlessCache(name);
        if (description != null) {
            cache.setDescription(description);
        }
        if (dataStorageMaximum != null) {
            cache.setDataStorageMaximum(dataStorageMaximum);
        }
        if (dataStorageMinimum != null) {
            cache.setDataStorageMinimum(dataStorageMinimum);
        }
        if (dataStorageUnit != null) {
            cache.setDataStorageUnit(dataStorageUnit);
        }
        if (ecpuMaximum != null) {
            cache.setEcpuPerSecondMaximum(ecpuMaximum);
        }
        if (ecpuMinimum != null) {
            cache.setEcpuPerSecondMinimum(ecpuMinimum);
        }
        if (Boolean.TRUE.equals(removeUserGroup)) {
            cache.setUserGroupId(null);
        } else if (userGroupId != null) {
            cache.setUserGroupId(userGroupId);
        }
        if (securityGroupIds != null) {
            cache.setSecurityGroupIds(securityGroupIds);
        }
        if (snapshotRetentionLimit != null) {
            cache.setSnapshotRetentionLimit(snapshotRetentionLimit);
        }
        if (dailySnapshotTime != null) {
            cache.setDailySnapshotTime(dailySnapshotTime);
        }
        if (engine != null && !engine.isBlank()) {
            cache.setEngine(engine.toLowerCase());
        }
        if (majorEngineVersion != null && !majorEngineVersion.isBlank()) {
            cache.setMajorEngineVersion(majorEngineVersion);
            cache.setFullEngineVersion(defaultFullEngineVersion(cache.getEngine(), majorEngineVersion));
        }
        cache.setStatus("available");
        serverlessCaches.put(name, cache);
        recordEvent(name, "serverless-cache", "Serverless cache modified");
        return cache;
    }

    public ServerlessCache deleteServerlessCache(String name, String finalSnapshotName) {
        ServerlessCache cache = getServerlessCache(name);
        if (finalSnapshotName != null && !finalSnapshotName.isBlank()) {
            createServerlessCacheSnapshot(name, finalSnapshotName, cache.getKmsKeyId(), Map.of());
        }
        cache.setStatus("deleting");
        serverlessCaches.delete(name);
        recordEvent(name, "serverless-cache", "Serverless cache deleted");
        LOG.infov("Serverless cache {0} deleted", name);
        return cache;
    }

    public ServerlessCacheSnapshot createServerlessCacheSnapshot(String cacheName, String snapshotName,
                                                                 String kmsKeyId, Map<String, String> tags) {
        if (snapshotName == null || snapshotName.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "ServerlessCacheSnapshotName is required.", 400);
        }
        ServerlessCache cache = getServerlessCache(cacheName);
        if (serverlessSnapshots.get(snapshotName).isPresent()) {
            throw new AwsException("ServerlessCacheSnapshotAlreadyExistsFault",
                    "Serverless cache snapshot " + snapshotName + " already exists.", 400);
        }
        ServerlessCacheSnapshot snapshot = new ServerlessCacheSnapshot();
        snapshot.setServerlessCacheSnapshotName(snapshotName);
        snapshot.setServerlessCacheName(cache.getServerlessCacheName());
        snapshot.setEngine(cache.getEngine());
        snapshot.setMajorEngineVersion(cache.getMajorEngineVersion());
        snapshot.setKmsKeyId(kmsKeyId != null ? kmsKeyId : cache.getKmsKeyId());
        snapshot.setSnapshotType("manual");
        snapshot.setStatus("available");
        snapshot.setCreateTime(Instant.now());
        snapshot.setBytesUsedForCache("0");
        snapshot.setTags(tags);
        String region = config.defaultRegion();
        String account = config.defaultAccountId();
        snapshot.setArn(AwsArnUtils.Arn.of("elasticache", region, account,
                "serverlesscachesnapshot:" + snapshotName).toString());
        serverlessSnapshots.put(snapshotName, snapshot);
        recordEvent(snapshotName, "serverless-cache-snapshot", "Serverless cache snapshot created");
        return snapshot;
    }

    public ServerlessCacheSnapshot getServerlessCacheSnapshot(String name) {
        return serverlessSnapshots.get(name).orElseThrow(() ->
                new AwsException("ServerlessCacheSnapshotNotFoundFault",
                        "Serverless cache snapshot " + name + " not found.", 404));
    }

    public Collection<ServerlessCacheSnapshot> listServerlessCacheSnapshots(String cacheName,
                                                                            String snapshotName,
                                                                            String snapshotType) {
        if (snapshotName != null && !snapshotName.isBlank()) {
            ServerlessCacheSnapshot snapshot = getServerlessCacheSnapshot(snapshotName);
            if (cacheName != null && !cacheName.isBlank()
                    && !cacheName.equals(snapshot.getServerlessCacheName())) {
                throw new AwsException("ServerlessCacheSnapshotNotFoundFault",
                        "Serverless cache snapshot " + snapshotName + " not found.", 404);
            }
            if (snapshotType != null && !snapshotType.isBlank()
                    && !snapshotType.equalsIgnoreCase(snapshot.getSnapshotType())) {
                return List.of();
            }
            return List.of(snapshot);
        }
        if (cacheName != null && !cacheName.isBlank()) {
            getServerlessCache(cacheName);
        }
        return serverlessSnapshots.scan(k -> true).stream()
                .filter(s -> cacheName == null || cacheName.isBlank()
                        || cacheName.equals(s.getServerlessCacheName()))
                .filter(s -> snapshotType == null || snapshotType.isBlank()
                        || snapshotType.equalsIgnoreCase(s.getSnapshotType()))
                .toList();
    }

    public ServerlessCacheSnapshot deleteServerlessCacheSnapshot(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "ServerlessCacheSnapshotName is required.", 400);
        }
        ServerlessCacheSnapshot snapshot = getServerlessCacheSnapshot(name);
        if ("creating".equals(snapshot.getStatus())) {
            throw new AwsException("InvalidServerlessCacheSnapshotStateFault",
                    "Serverless cache snapshot " + name + " is not in a valid state.", 400);
        }
        serverlessSnapshots.delete(name);
        recordEvent(name, "serverless-cache-snapshot", "Serverless cache snapshot deleted");
        return snapshot;
    }

    public ServerlessCacheSnapshot copyServerlessCacheSnapshot(String sourceName, String targetName,
                                                               String kmsKeyId, Map<String, String> tags) {
        if (sourceName == null || sourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "SourceServerlessCacheSnapshotName is required.", 400);
        }
        if (targetName == null || targetName.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "TargetServerlessCacheSnapshotName is required.", 400);
        }
        ServerlessCacheSnapshot source = getServerlessCacheSnapshot(sourceName);
        if (serverlessSnapshots.get(targetName).isPresent()) {
            throw new AwsException("ServerlessCacheSnapshotAlreadyExistsFault",
                    "Serverless cache snapshot " + targetName + " already exists.", 400);
        }
        ServerlessCacheSnapshot copy = new ServerlessCacheSnapshot();
        copy.setServerlessCacheSnapshotName(targetName);
        copy.setServerlessCacheName(source.getServerlessCacheName());
        copy.setEngine(source.getEngine());
        copy.setMajorEngineVersion(source.getMajorEngineVersion());
        copy.setKmsKeyId(kmsKeyId != null ? kmsKeyId : source.getKmsKeyId());
        copy.setSnapshotType("manual");
        copy.setStatus("available");
        copy.setCreateTime(Instant.now());
        copy.setBytesUsedForCache(source.getBytesUsedForCache());
        Map<String, String> copyTags = new LinkedHashMap<>();
        if (source.getTags() != null) {
            copyTags.putAll(source.getTags());
        }
        if (tags != null) {
            copyTags.putAll(tags);
        }
        copy.setTags(copyTags);
        String region = config.defaultRegion();
        String account = config.defaultAccountId();
        copy.setArn(AwsArnUtils.Arn.of("elasticache", region, account,
                "serverlesscachesnapshot:" + targetName).toString());
        serverlessSnapshots.put(targetName, copy);
        recordEvent(targetName, "serverless-cache-snapshot", "Serverless cache snapshot copied");
        return copy;
    }

    public ServerlessCacheSnapshot exportServerlessCacheSnapshot(String name, String s3BucketName) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "ServerlessCacheSnapshotName is required.", 400);
        }
        if (s3BucketName == null || s3BucketName.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "S3BucketName is required.", 400);
        }
        ServerlessCacheSnapshot snapshot = getServerlessCacheSnapshot(name);
        recordEvent(name, "serverless-cache-snapshot",
                "Serverless cache snapshot exported to s3://" + s3BucketName);
        return snapshot;
    }

    public List<CacheEvent> listEvents(String sourceType, String sourceIdentifier) {
        List<CacheEvent> matched = new ArrayList<>();
        for (CacheEvent event : events) {
            if (sourceType != null && !sourceType.isBlank()
                    && !sourceType.equalsIgnoreCase(event.getSourceType())) {
                continue;
            }
            if (sourceIdentifier != null && !sourceIdentifier.isBlank()
                    && !sourceIdentifier.equals(event.getSourceIdentifier())) {
                continue;
            }
            matched.add(event);
        }
        return matched;
    }

    public Map<String, String> listTagsForResource(String resourceName) {
        return new LinkedHashMap<>(tagsOf(resourceName));
    }

    public Map<String, String> addTagsToResource(String resourceName, Map<String, String> tags) {
        Map<String, String> existing = tagsOf(resourceName);
        if (tags != null) {
            existing.putAll(tags);
        }
        persistTags(resourceName, existing);
        return new LinkedHashMap<>(existing);
    }

    public Map<String, String> removeTagsFromResource(String resourceName, List<String> keys) {
        Map<String, String> existing = tagsOf(resourceName);
        if (keys != null) {
            keys.forEach(existing::remove);
        }
        persistTags(resourceName, existing);
        return new LinkedHashMap<>(existing);
    }

    /**
     * Validates a Redis AUTH password for the given group.
     * Checks the group-level authToken first, then falls back to the "default" user
     * associated with the group (per Redis 6+ ACL spec, single-arg AUTH only
     * authenticates the default user). Only users explicitly added via
     * ModifyReplicationGroup are checked, preventing cross-group credential leakage.
     */
    public boolean validatePassword(String groupId, String username, String password) {
        ReplicationGroup group = groups.get(groupId).orElse(null);
        if (group == null) {
            return false;
        }

        if (username == null || username.isEmpty()) {
            // AUTH password form: check group-level authToken first
            if (group.getAuthToken() != null && password.equals(group.getAuthToken())) {
                return true;
            }
            // Fall back to the "default" PASSWORD user associated with this group
            Set<String> groupUserIds = group.getAssociatedUserIds();
            return groupUserIds.stream()
                    .map(id -> users.get(id).orElse(null))
                    .filter(u -> u != null
                            && "default".equals(u.getUserName())
                            && u.getAuthMode() == AuthMode.PASSWORD)
                    .anyMatch(u -> u.getPasswords() != null && u.getPasswords().contains(password));
        }
        // AUTH username password form: find user by userName, scoped to group
        Set<String> groupUserIds = group.getAssociatedUserIds();
        return groupUserIds.stream()
                .map(id -> users.get(id).orElse(null))
                .filter(u -> u != null && username.equals(u.getUserName()) && u.getAuthMode() == AuthMode.PASSWORD)
                .anyMatch(u -> u.getPasswords() != null && u.getPasswords().contains(password));
    }

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    private int allocateProxyPort() {
        int base = config.services().elasticache().proxyBasePort();
        int max = config.services().elasticache().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        throw new AwsException("InsufficientReplicationGroupCapacity",
                "No available proxy ports in range " + base + "-" + max, 503);
    }

    private void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }

    private void recordEvent(String sourceIdentifier, String sourceType, String message) {
        events.add(new CacheEvent(sourceIdentifier, sourceType, message, Instant.now()));
        while (events.size() > 200) {
            events.remove(0);
        }
    }

    private static String defaultMajorEngineVersion(String engine) {
        if ("redis".equalsIgnoreCase(engine)) {
            return "7";
        }
        if ("memcached".equalsIgnoreCase(engine)) {
            return "1.6";
        }
        return "8";
    }

    private static String defaultFullEngineVersion(String engine, String major) {
        if (major == null || major.isBlank()) {
            major = defaultMajorEngineVersion(engine);
        }
        if ("memcached".equalsIgnoreCase(engine)) {
            return major.contains(".") ? major + ".22" : major + ".0";
        }
        return major.contains(".") ? major : major + ".0.0";
    }

    private Map<String, String> tagsOf(String resourceName) {
        Object resource = locateTaggedResource(resourceName);
        if (resource instanceof ServerlessCache cache) {
            return cache.getTags();
        }
        if (resource instanceof ServerlessCacheSnapshot snapshot) {
            return snapshot.getTags();
        }
        throw new AwsException("InvalidARNFault",
                "Resource " + resourceName + " not found.", 400);
    }

    private void persistTags(String resourceName, Map<String, String> tags) {
        Object resource = locateTaggedResource(resourceName);
        if (resource instanceof ServerlessCache cache) {
            cache.setTags(tags);
            serverlessCaches.put(cache.getServerlessCacheName(), cache);
            return;
        }
        if (resource instanceof ServerlessCacheSnapshot snapshot) {
            snapshot.setTags(tags);
            serverlessSnapshots.put(snapshot.getServerlessCacheSnapshotName(), snapshot);
        }
    }

    private Object locateTaggedResource(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidARNFault", "ResourceName is required.", 400);
        }
        try {
            String resource = AwsArnUtils.parse(resourceName).resource();
            if (resource.startsWith("serverlesscachesnapshot:")) {
                return getServerlessCacheSnapshot(resource.substring("serverlesscachesnapshot:".length()));
            }
            if (resource.startsWith("serverlesscache:")) {
                return getServerlessCache(resource.substring("serverlesscache:".length()));
            }
        } catch (IllegalArgumentException ignored) {
            // not an ARN — try raw names next
        }
        ServerlessCache byCacheName = serverlessCaches.get(resourceName).orElse(null);
        if (byCacheName != null) {
            return byCacheName;
        }
        ServerlessCacheSnapshot bySnapshotName = serverlessSnapshots.get(resourceName).orElse(null);
        if (bySnapshotName != null) {
            return bySnapshotName;
        }
        throw new AwsException("InvalidARNFault",
                "Resource " + resourceName + " not found.", 400);
    }
}
