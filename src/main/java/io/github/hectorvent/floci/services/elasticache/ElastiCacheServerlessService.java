package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.dns.ContainerEndpoints;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ServerlessCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ServerlessCacheContainerManager.TlsMaterial;
import io.github.hectorvent.floci.services.elasticache.container.ServerlessCacheDataPlane;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCacheSnapshot;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCacheSnapshotEntry;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * ElastiCache serverless caches and their snapshots.
 *
 * <p>Each cache is backed by a real engine container (Valkey for {@code valkey} and {@code redis},
 * Memcached for {@code memcached}) that serves TLS on the cache's AWS-shaped hostname, which
 * Floci's DNS answers with the container's address for the containers Floci launches (see
 * {@link ContainerEndpoints}). Status transitions follow AWS: a create answers
 * {@code creating} and the cache turns {@code available} once its engine is ready, a modify
 * answers {@code modifying}, and a delete answers {@code deleting} before the cache disappears.
 * Snapshots hold the cache's keyspace, so restoring one into a new cache brings the data back.
 */
@ApplicationScoped
public class ElastiCacheServerlessService implements Resettable {

    private static final Logger LOG = Logger.getLogger(ElastiCacheServerlessService.class);

    static final String CREATING = "creating";
    static final String AVAILABLE = "available";
    static final String MODIFYING = "modifying";
    static final String DELETING = "deleting";
    static final String CREATE_FAILED = "create-failed";
    static final String SNAPSHOT_FAILED = "failed";

    static final int MAX_TAGS = 50;
    private static final int MAX_NAME_LENGTH = 40;
    private static final int MAX_SNAPSHOT_NAME_LENGTH = 255;
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern DAILY_SNAPSHOT_TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final List<String> NETWORK_TYPES = List.of("ipv4", "ipv6", "dual_stack");
    private static final Map<String, Map<String, String>> ENGINE_VERSIONS = Map.of(
            "valkey", orderedVersions("7", "7.2", "8", "8.1"),
            "redis", orderedVersions("7", "7.1"),
            "memcached", orderedVersions("1.6", "1.6.22"));
    private static final Map<String, String> DEFAULT_MAJOR_VERSIONS = Map.of(
            "valkey", "8", "redis", "7", "memcached", "1.6");

    /** The validated CacheUsageLimits of a request; a {@code *Present} flag marks a given sub-structure. */
    public record UsageLimits(boolean dataStoragePresent, Integer dataStorageMaximum, Integer dataStorageMinimum,
                              String dataStorageUnit, boolean ecpuPresent, Integer ecpuMaximum,
                              Integer ecpuMinimum) {
    }

    public record CreateRequest(String serverlessCacheName, String description, String engine,
                                String majorEngineVersion, UsageLimits cacheUsageLimits, String kmsKeyId,
                                List<String> securityGroupIds, List<String> snapshotArnsToRestore,
                                Map<String, String> tags, String userGroupId, List<String> subnetIds,
                                Integer snapshotRetentionLimit, String dailySnapshotTime, String networkType) {
    }

    public record ModifyRequest(String serverlessCacheName, String description, UsageLimits cacheUsageLimits,
                                Boolean removeUserGroup, String userGroupId, List<String> securityGroupIds,
                                Integer snapshotRetentionLimit, String dailySnapshotTime, String engine,
                                String majorEngineVersion) {
    }

    public record Page<T>(List<T> items, String nextToken) {
    }

    /** The engine container behind one cache, or no container when Docker is unreachable. */
    private record EngineRuntime(String engine, ElastiCacheContainerHandle handle) {
        boolean hasBackend() {
            return handle != null;
        }
    }

    private record Network(String vpcId, List<String> subnetIds, List<String> securityGroupIds) {
    }

    private final AccountAwareStorageBackend<ServerlessCache> caches;
    private final AccountAwareStorageBackend<ServerlessCacheSnapshot> snapshots;
    private final RegionResolver regionResolver;
    private final Ec2Service ec2Service;
    private final KmsService kmsService;
    private final ServerlessCacheContainerManager containers;
    private final ContainerEndpoints containerEndpoints;
    private final FlociCertificateAuthority certificateAuthority;
    private final ServerlessCacheDataPlane dataPlane;
    private final String valkeyImage;
    private final String memcachedImage;
    private final Executor executor;
    private final Map<String, EngineRuntime> runtimes = new ConcurrentHashMap<>();
    private final AtomicBoolean runtimeRestored = new AtomicBoolean();

    @Inject
    public ElastiCacheServerlessService(StorageFactory storageFactory, EmulatorConfig config,
                                        RegionResolver regionResolver, Ec2Service ec2Service,
                                        KmsService kmsService, ServerlessCacheContainerManager containers,
                                        ContainerEndpoints containerEndpoints,
                                        FlociCertificateAuthority certificateAuthority,
                                        ServerlessCacheDataPlane dataPlane) {
        this(storageFactory.create("elasticache", "elasticache-serverless-caches.json",
                        new TypeReference<Map<String, ServerlessCache>>() {}),
                storageFactory.create("elasticache", "elasticache-serverless-snapshots.json",
                        new TypeReference<Map<String, ServerlessCacheSnapshot>>() {}),
                regionResolver, ec2Service, kmsService, containers, containerEndpoints,
                certificateAuthority, dataPlane,
                config.services().elasticache().defaultImage(),
                config.services().elasticache().defaultMemcachedImage(),
                Executors.newFixedThreadPool(4, runnable -> {
                    Thread thread = new Thread(runnable, "elasticache-serverless-provisioner");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    ElastiCacheServerlessService(AccountAwareStorageBackend<ServerlessCache> caches,
                                 AccountAwareStorageBackend<ServerlessCacheSnapshot> snapshots,
                                 RegionResolver regionResolver, Ec2Service ec2Service, KmsService kmsService,
                                 ServerlessCacheContainerManager containers, ContainerEndpoints containerEndpoints,
                                 FlociCertificateAuthority certificateAuthority, ServerlessCacheDataPlane dataPlane,
                                 String valkeyImage, String memcachedImage, Executor executor) {
        this.caches = caches;
        this.snapshots = snapshots;
        this.regionResolver = regionResolver;
        this.ec2Service = ec2Service;
        this.kmsService = kmsService;
        this.containers = containers;
        this.containerEndpoints = containerEndpoints;
        this.certificateAuthority = certificateAuthority;
        this.dataPlane = dataPlane;
        this.valkeyImage = valkeyImage;
        this.memcachedImage = memcachedImage;
        this.executor = executor;
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }

    // ── Serverless caches ─────────────────────────────────────────────────────

    public synchronized ServerlessCache createServerlessCache(CreateRequest request, String region) {
        ensureRuntimeRestored();
        String accountId = regionResolver.getAccountId();
        String name = cacheName(request.serverlessCacheName());
        String engine = engine(request.engine(), true);
        String majorVersion = majorVersion(engine, request.majorEngineVersion());
        UsageLimits limits = validateUsageLimits(request.cacheUsageLimits());
        Integer retention = snapshotRetentionLimit(request.snapshotRetentionLimit());
        String dailySnapshotTime = dailySnapshotTime(request.dailySnapshotTime());
        String networkType = networkType(request.networkType());
        requireTagQuota(request.tags());
        if (request.userGroupId() != null && !request.userGroupId().isBlank()) {
            requireUserGroup(engine, request.userGroupId());
        }
        if (caches.getForAccount(accountId, key(region, name)).isPresent()) {
            throw new AwsException("ServerlessCacheAlreadyExistsFault",
                    "Serverless cache " + name + " already exists.", 400);
        }
        Network network = resolveNetwork(region, request.subnetIds(), request.securityGroupIds());
        String kmsKeyArn = resolveKmsKey(request.kmsKeyId(), region);
        List<String> restoreFrom = resolveSnapshotsToRestore(accountId, region, engine,
                request.snapshotArnsToRestore());

        ServerlessCache cache = new ServerlessCache();
        cache.setServerlessCacheName(name);
        cache.setArn(arn(region, accountId, "serverlesscache", name));
        cache.setAccountId(accountId);
        cache.setRegion(region);
        cache.setDescription(request.description() != null ? request.description() : "");
        cache.setStatus(CREATING);
        cache.setEngine(engine);
        cache.setMajorEngineVersion(majorVersion);
        cache.setFullEngineVersion(ENGINE_VERSIONS.get(engine).get(majorVersion));
        applyUsageLimits(cache, limits);
        cache.setKmsKeyId(kmsKeyArn);
        cache.setVpcId(network.vpcId());
        cache.setSubnetIds(network.subnetIds());
        cache.setSecurityGroupIds(network.securityGroupIds());
        cache.setUserGroupId(blankToNull(request.userGroupId()));
        cache.setSnapshotRetentionLimit(retention != null ? retention : 0);
        cache.setDailySnapshotTime(dailySnapshotTime);
        cache.setNetworkType(networkType != null ? networkType : "ipv4");
        cache.setCreateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        cache.setSnapshotNamesToRestore(restoreFrom);
        cache.setTags(request.tags());
        caches.putForAccount(accountId, key(region, name), cache);
        ServerlessCache response = cache.copy();
        LOG.infov("Creating serverless cache {0} ({1} {2})", name, engine, majorVersion);
        executor.execute(() -> provision(accountId, region, name));
        return response;
    }

    public Page<ServerlessCache> describeServerlessCaches(String name, Integer maxResults, String nextToken,
                                                          String region) {
        ensureRuntimeRestored();
        String accountId = regionResolver.getAccountId();
        int pageSize = pageSize(maxResults, 50, 100);
        if (name != null && !name.isBlank()) {
            return new Page<>(List.of(requireCache(accountId, region, name).copy()), null);
        }
        List<ServerlessCache> all = caches.scanForAccount(accountId, k -> k.startsWith(region + "/")).stream()
                .sorted(Comparator.comparing(ServerlessCache::getServerlessCacheName))
                .map(ServerlessCache::copy)
                .toList();
        return page(all, pageSize, nextToken);
    }

    public synchronized ServerlessCache modifyServerlessCache(ModifyRequest request, String region) {
        ensureRuntimeRestored();
        String accountId = regionResolver.getAccountId();
        ServerlessCache cache = requireCache(accountId, region, request.serverlessCacheName());
        if (!AVAILABLE.equals(cache.getStatus())) {
            throw invalidCacheState(cache);
        }
        UsageLimits limits = validateUsageLimits(request.cacheUsageLimits());
        Integer retention = snapshotRetentionLimit(request.snapshotRetentionLimit());
        String dailySnapshotTime = dailySnapshotTime(request.dailySnapshotTime());
        boolean removeUserGroup = Boolean.TRUE.equals(request.removeUserGroup());
        String userGroupId = blankToNull(request.userGroupId());
        if (removeUserGroup && userGroupId != null) {
            throw new AwsException("InvalidParameterCombination",
                    "RemoveUserGroup and UserGroupId cannot be specified together.", 400);
        }
        String engine = cache.getEngine();
        String majorVersion = cache.getMajorEngineVersion();
        if (request.engine() != null && !request.engine().isBlank()) {
            String requested = engine(request.engine(), false);
            if (!requested.equals(engine)) {
                if (!"redis".equals(engine) || !"valkey".equals(requested)) {
                    throw new AwsException("InvalidParameterCombination",
                            "Serverless cache " + cache.getServerlessCacheName() + " cannot be modified from "
                                    + engine + " to " + requested + ". Only redis caches can be upgraded to valkey.",
                            400);
                }
                engine = requested;
                majorVersion = DEFAULT_MAJOR_VERSIONS.get(requested);
            }
        }
        if (request.majorEngineVersion() != null && !request.majorEngineVersion().isBlank()) {
            String requested = majorVersion(engine, request.majorEngineVersion());
            if (engine.equals(cache.getEngine()) && compareVersions(requested, majorVersion) < 0) {
                throw new AwsException("InvalidParameterCombination",
                        "Cannot downgrade serverless cache " + cache.getServerlessCacheName()
                                + " from major engine version " + majorVersion + " to " + requested + ".", 400);
            }
            majorVersion = requested;
        }
        if (userGroupId != null) {
            requireUserGroup(engine, userGroupId);
        }
        List<String> securityGroupIds = null;
        if (request.securityGroupIds() != null && !request.securityGroupIds().isEmpty()) {
            securityGroupIds = resolveSecurityGroups(region, cache.getVpcId(), request.securityGroupIds());
        }

        if (request.description() != null) {
            cache.setDescription(request.description());
        }
        if (limits != null) {
            if (limits.dataStoragePresent()) {
                cache.setDataStorageMaximum(limits.dataStorageMaximum());
                cache.setDataStorageMinimum(limits.dataStorageMinimum());
                cache.setDataStorageUnit(limits.dataStorageUnit());
            }
            if (limits.ecpuPresent()) {
                cache.setEcpuPerSecondMaximum(limits.ecpuMaximum());
                cache.setEcpuPerSecondMinimum(limits.ecpuMinimum());
            }
        }
        if (removeUserGroup) {
            cache.setUserGroupId(null);
        } else if (userGroupId != null) {
            cache.setUserGroupId(userGroupId);
        }
        if (securityGroupIds != null) {
            cache.setSecurityGroupIds(securityGroupIds);
        }
        if (retention != null) {
            cache.setSnapshotRetentionLimit(retention);
        }
        if (dailySnapshotTime != null) {
            cache.setDailySnapshotTime(dailySnapshotTime);
        }
        cache.setEngine(engine);
        cache.setMajorEngineVersion(majorVersion);
        cache.setFullEngineVersion(ENGINE_VERSIONS.get(engine).get(majorVersion));
        cache.setStatus(MODIFYING);
        caches.putForAccount(accountId, key(region, cache.getServerlessCacheName()), cache);
        ServerlessCache response = cache.copy();
        String name = cache.getServerlessCacheName();
        executor.execute(() -> finishModify(accountId, region, name));
        return response;
    }

    public synchronized ServerlessCache deleteServerlessCache(String name, String finalSnapshotName, String region) {
        ensureRuntimeRestored();
        String accountId = regionResolver.getAccountId();
        ServerlessCache cache = requireCache(accountId, region, name);
        if (!AVAILABLE.equals(cache.getStatus()) && !CREATE_FAILED.equals(cache.getStatus())) {
            throw invalidCacheState(cache);
        }
        String finalSnapshot = null;
        if (finalSnapshotName != null && !finalSnapshotName.isBlank()) {
            if (!AVAILABLE.equals(cache.getStatus())) {
                throw new AwsException("InvalidParameterCombination",
                        "A final snapshot can only be taken of an available serverless cache.", 400);
            }
            ServerlessCacheSnapshot snapshot = newSnapshot(accountId, region, finalSnapshotName, cache,
                    cache.getKmsKeyId(), Map.of());
            snapshots.putForAccount(accountId, key(region, snapshot.getServerlessCacheSnapshotName()), snapshot);
            finalSnapshot = snapshot.getServerlessCacheSnapshotName();
        }
        cache.setStatus(DELETING);
        caches.putForAccount(accountId, key(region, cache.getServerlessCacheName()), cache);
        ServerlessCache response = cache.copy();
        String cacheName = cache.getServerlessCacheName();
        String snapshotName = finalSnapshot;
        executor.execute(() -> teardown(accountId, region, cacheName, snapshotName));
        return response;
    }

    // ── Snapshots ─────────────────────────────────────────────────────────────

    public synchronized ServerlessCacheSnapshot createServerlessCacheSnapshot(String snapshotName, String cacheName,
                                                                              String kmsKeyId,
                                                                              Map<String, String> tags,
                                                                              String region) {
        ensureRuntimeRestored();
        String accountId = regionResolver.getAccountId();
        requireSnapshotName(snapshotName);
        if (cacheName == null || cacheName.isBlank()) {
            throw missingParameter("ServerlessCacheName");
        }
        ServerlessCache cache = requireCache(accountId, region, cacheName);
        if (!AVAILABLE.equals(cache.getStatus())) {
            throw invalidCacheState(cache);
        }
        requireTagQuota(tags);
        String kmsKeyArn = kmsKeyId != null && !kmsKeyId.isBlank()
                ? resolveKmsKey(kmsKeyId, region) : cache.getKmsKeyId();
        ServerlessCacheSnapshot snapshot = newSnapshot(accountId, region, snapshotName, cache, kmsKeyArn, tags);
        snapshots.putForAccount(accountId, key(region, snapshot.getServerlessCacheSnapshotName()), snapshot);
        ServerlessCacheSnapshot response = snapshot.copy();
        String name = snapshot.getServerlessCacheSnapshotName();
        String source = cache.getServerlessCacheName();
        executor.execute(() -> captureSnapshot(accountId, region, name, source));
        return response;
    }

    public Page<ServerlessCacheSnapshot> describeServerlessCacheSnapshots(String cacheName, String snapshotName,
                                                                          String snapshotType, Integer maxResults,
                                                                          String nextToken, String region) {
        ensureRuntimeRestored();
        String accountId = regionResolver.getAccountId();
        int pageSize = pageSize(maxResults, 50, 50);
        if (snapshotType != null && !snapshotType.isBlank()
                && !"automated".equals(snapshotType) && !"manual".equals(snapshotType)) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid SnapshotType " + snapshotType + ". Valid values are automated and manual.", 400);
        }
        if (cacheName != null && !cacheName.isBlank()) {
            requireCache(accountId, region, cacheName);
        }
        if (snapshotName != null && !snapshotName.isBlank()) {
            ServerlessCacheSnapshot snapshot = requireSnapshot(accountId, region, snapshotName);
            if (cacheName != null && !cacheName.isBlank()
                    && !normalize(cacheName).equals(snapshot.getServerlessCacheName())) {
                return new Page<>(List.of(), null);
            }
            return new Page<>(List.of(snapshot.copy()), null);
        }
        List<ServerlessCacheSnapshot> all = snapshots.scanForAccount(accountId, k -> k.startsWith(region + "/"))
                .stream()
                .filter(s -> cacheName == null || cacheName.isBlank()
                        || normalize(cacheName).equals(s.getServerlessCacheName()))
                .filter(s -> snapshotType == null || snapshotType.isBlank()
                        || snapshotType.equals(s.getSnapshotType()))
                .sorted(Comparator.comparing(ServerlessCacheSnapshot::getCreateTime)
                        .thenComparing(ServerlessCacheSnapshot::getServerlessCacheSnapshotName))
                .map(ServerlessCacheSnapshot::copy)
                .toList();
        return page(all, pageSize, nextToken);
    }

    public synchronized ServerlessCacheSnapshot deleteServerlessCacheSnapshot(String snapshotName, String region) {
        ensureRuntimeRestored();
        String accountId = regionResolver.getAccountId();
        if (snapshotName == null || snapshotName.isBlank()) {
            throw missingParameter("ServerlessCacheSnapshotName");
        }
        ServerlessCacheSnapshot snapshot = requireSnapshot(accountId, region, snapshotName);
        if (!AVAILABLE.equals(snapshot.getStatus()) && !SNAPSHOT_FAILED.equals(snapshot.getStatus())) {
            throw invalidSnapshotState(snapshot);
        }
        snapshots.deleteForAccount(accountId, key(region, snapshot.getServerlessCacheSnapshotName()));
        ServerlessCacheSnapshot response = snapshot.copy();
        response.setStatus(DELETING);
        return response;
    }

    public synchronized ServerlessCacheSnapshot copyServerlessCacheSnapshot(String sourceName, String targetName,
                                                                            String kmsKeyId,
                                                                            Map<String, String> tags,
                                                                            String region) {
        ensureRuntimeRestored();
        String accountId = regionResolver.getAccountId();
        if (sourceName == null || sourceName.isBlank()) {
            throw missingParameter("SourceServerlessCacheSnapshotName");
        }
        ServerlessCacheSnapshot source = requireSnapshot(accountId, region, sourceName);
        requireSnapshotName(targetName);
        if (!AVAILABLE.equals(source.getStatus())) {
            throw invalidSnapshotState(source);
        }
        requireTagQuota(tags);
        String target = normalize(targetName);
        if (snapshots.getForAccount(accountId, key(region, target)).isPresent()) {
            throw new AwsException("ServerlessCacheSnapshotAlreadyExistsFault",
                    "Serverless cache snapshot " + target + " already exists.", 400);
        }
        ServerlessCacheSnapshot copy = source.copy();
        copy.setServerlessCacheSnapshotName(target);
        copy.setArn(arn(region, accountId, "serverlesscachesnapshot", target));
        copy.setSnapshotType("manual");
        copy.setStatus(CREATING);
        copy.setCreateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        copy.setExpiryTime(null);
        copy.setKmsKeyId(kmsKeyId != null && !kmsKeyId.isBlank()
                ? resolveKmsKey(kmsKeyId, region) : source.getKmsKeyId());
        copy.setTags(tags);
        snapshots.putForAccount(accountId, key(region, target), copy);
        ServerlessCacheSnapshot response = copy.copy();
        executor.execute(() -> updateSnapshot(accountId, region, target, s -> s.setStatus(AVAILABLE)));
        return response;
    }

    /**
     * Validates an export the way AWS does, then refuses it: AWS writes the snapshot to S3 as RDB
     * files, and Floci keeps snapshots as captured keys, not as RDB files it could hand to S3.
     */
    public ServerlessCacheSnapshot exportServerlessCacheSnapshot(String snapshotName, String bucketName,
                                                                 String region) {
        ensureRuntimeRestored();
        String accountId = regionResolver.getAccountId();
        if (snapshotName == null || snapshotName.isBlank()) {
            throw missingParameter("ServerlessCacheSnapshotName");
        }
        ServerlessCacheSnapshot snapshot = requireSnapshot(accountId, region, snapshotName);
        if (!AVAILABLE.equals(snapshot.getStatus())) {
            throw invalidSnapshotState(snapshot);
        }
        if (bucketName == null || bucketName.isBlank()) {
            throw missingParameter("S3BucketName");
        }
        throw new AwsException("InvalidParameterValue",
                "Exporting serverless cache snapshots to Amazon S3 is not supported by this emulator.", 400);
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public Map<String, String> listTags(String resourceType, String name, String region) {
        String accountId = regionResolver.getAccountId();
        return new LinkedHashMap<>("serverlesscache".equals(resourceType)
                ? requireCache(accountId, region, name).getTags()
                : requireSnapshot(accountId, region, name).getTags());
    }

    public synchronized void addTags(String resourceType, String name, Map<String, String> tags, String region) {
        String accountId = regionResolver.getAccountId();
        if ("serverlesscache".equals(resourceType)) {
            ServerlessCache cache = requireCache(accountId, region, name);
            if (DELETING.equals(cache.getStatus())) {
                throw invalidCacheState(cache);
            }
            Map<String, String> merged = new LinkedHashMap<>(cache.getTags());
            merged.putAll(tags);
            requireTagQuota(merged);
            cache.setTags(merged);
            caches.putForAccount(accountId, key(region, cache.getServerlessCacheName()), cache);
        } else {
            ServerlessCacheSnapshot snapshot = requireSnapshot(accountId, region, name);
            Map<String, String> merged = new LinkedHashMap<>(snapshot.getTags());
            merged.putAll(tags);
            requireTagQuota(merged);
            snapshot.setTags(merged);
            snapshots.putForAccount(accountId, key(region, snapshot.getServerlessCacheSnapshotName()), snapshot);
        }
    }

    public synchronized void removeTags(String resourceType, String name, List<String> keys, String region) {
        String accountId = regionResolver.getAccountId();
        if ("serverlesscache".equals(resourceType)) {
            ServerlessCache cache = requireCache(accountId, region, name);
            Map<String, String> remaining = new LinkedHashMap<>(cache.getTags());
            keys.forEach(remaining::remove);
            cache.setTags(remaining);
            caches.putForAccount(accountId, key(region, cache.getServerlessCacheName()), cache);
        } else {
            ServerlessCacheSnapshot snapshot = requireSnapshot(accountId, region, name);
            Map<String, String> remaining = new LinkedHashMap<>(snapshot.getTags());
            keys.forEach(remaining::remove);
            snapshot.setTags(remaining);
            snapshots.putForAccount(accountId, key(region, snapshot.getServerlessCacheSnapshotName()), snapshot);
        }
    }

    // ── Data plane ────────────────────────────────────────────────────────────

    private void provision(String accountId, String region, String name) {
        ServerlessCache cache = caches.getForAccount(accountId, key(region, name)).orElse(null);
        if (cache == null || !CREATING.equals(cache.getStatus())) {
            return;
        }
        String address = ServerlessCacheEndpoints.address(name, accountId, region);
        EngineRuntime runtime;
        try {
            runtime = startRuntime(accountId, cache, address);
            if (runtime.hasBackend()) {
                for (String snapshotName : cache.getSnapshotNamesToRestore()) {
                    ServerlessCacheSnapshot snapshot = snapshots.getForAccount(accountId, key(region, snapshotName))
                            .orElseThrow(() -> new IllegalStateException("Snapshot " + snapshotName
                                    + " was deleted before it could be restored"));
                    dataPlane.restore(cache.getEngine(), runtime.handle().getHost(), runtime.handle().getPort(),
                            snapshot.getEntries());
                }
            } else if (!cache.getSnapshotNamesToRestore().isEmpty()) {
                LOG.warnv("Serverless cache {0} has no backing container, so its snapshots were not restored", name);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warnv(e, "Serverless cache {0} failed to provision", name);
            stopRuntime(accountId, region, name);
            synchronized (this) {
                caches.getForAccount(accountId, key(region, name))
                        .filter(current -> CREATING.equals(current.getStatus()))
                        .ifPresent(current -> {
                            current.setStatus(CREATE_FAILED);
                            caches.putForAccount(accountId, key(region, name), current);
                        });
            }
            return;
        }
        synchronized (this) {
            ServerlessCache current = caches.getForAccount(accountId, key(region, name)).orElse(null);
            if (current == null || !CREATING.equals(current.getStatus())) {
                stopRuntime(accountId, region, name);
                return;
            }
            current.setEndpoint(new Endpoint(address, ServerlessCacheEndpoints.primaryPort(current.getEngine())));
            current.setReaderEndpoint(new Endpoint(address, ServerlessCacheEndpoints.readerPort(current.getEngine())));
            current.setStatus(AVAILABLE);
            caches.putForAccount(accountId, key(region, name), current);
            routeEndpoint(address, runtime);
        }
        LOG.infov("Serverless cache {0} is available at {1}", name, address);
    }

    /**
     * Points the cache hostname at its engine container for the containers Floci launches, which
     * serves TLS on the endpoint ports itself. Without a container the name is refused rather than
     * left to resolve to Floci, where no listener serves it.
     */
    private void routeEndpoint(String address, EngineRuntime runtime) {
        if (containerEndpoints == null) {
            return;
        }
        try {
            if (runtime.hasBackend() && runtime.handle().getNetworkIp() != null) {
                containerEndpoints.routeToContainer(address, runtime.handle().getNetworkIp());
                return;
            }
            containerEndpoints.refuse(address);
            LOG.warnv("Serverless cache {0} has no container address, so containers cannot reach it: {1}",
                    address, runtime.hasBackend()
                            ? "the container's network address could not be resolved"
                            : "no Docker daemon is reachable");
        } catch (RuntimeException e) {
            LOG.warnv("Serverless cache {0} is not reachable from containers: {1}", address, e.getMessage());
        }
    }

    private EngineRuntime startRuntime(String accountId, ServerlessCache cache, String address) {
        String runtimeKey = runtimeKey(accountId, cache.getRegion(), cache.getServerlessCacheName());
        String containerKey = containerKey(accountId, cache.getRegion(), cache.getServerlessCacheName());
        String image = "memcached".equals(cache.getEngine()) ? memcachedImage : valkeyImage;
        ElastiCacheContainerHandle handle = containers.tryStart(containerKey, cache.getEngine(), image, accountId,
                cache.getRegion(), tlsMaterial(address));
        EngineRuntime runtime = new EngineRuntime(cache.getEngine(), handle);
        runtimes.put(runtimeKey, runtime);
        return runtime;
    }

    /** A leaf for the cache hostname issued by Floci's CA, which the containers Floci launches trust. */
    private TlsMaterial tlsMaterial(String address) {
        CertificateGenerator.GeneratedCertificate issued = certificateAuthority.issueServerCertificate(
                ServerlessCacheEndpoints.certificateCommonName(address), List.of(address), KeyAlgorithm.RSA_2048,
                null);
        String leaf = issued.certificatePem().strip() + "\n";
        String ca = certificateAuthority.caPem().strip() + "\n";
        return new TlsMaterial(leaf + ca, issued.privateKeyPem(), ca);
    }

    private void stopRuntime(String accountId, String region, String name) {
        EngineRuntime runtime = runtimes.remove(runtimeKey(accountId, region, name));
        if (runtime == null || !runtime.hasBackend()) {
            return;
        }
        try {
            containers.stop(runtime.handle());
        } catch (RuntimeException e) {
            LOG.warnv("Error stopping the container of serverless cache {0}: {1}", name, e.getMessage());
        }
    }

    private void finishModify(String accountId, String region, String name) {
        synchronized (this) {
            caches.getForAccount(accountId, key(region, name))
                    .filter(cache -> MODIFYING.equals(cache.getStatus()))
                    .ifPresent(cache -> {
                        cache.setStatus(AVAILABLE);
                        caches.putForAccount(accountId, key(region, name), cache);
                    });
        }
    }

    private void teardown(String accountId, String region, String name, String finalSnapshotName) {
        if (finalSnapshotName != null) {
            captureSnapshot(accountId, region, finalSnapshotName, name);
        }
        stopRuntime(accountId, region, name);
        if (containerEndpoints != null) {
            containerEndpoints.release(ServerlessCacheEndpoints.address(name, accountId, region));
        }
        synchronized (this) {
            caches.getForAccount(accountId, key(region, name))
                    .filter(cache -> DELETING.equals(cache.getStatus()))
                    .ifPresent(cache -> caches.deleteForAccount(accountId, key(region, name)));
        }
        LOG.infov("Serverless cache {0} deleted", name);
    }

    private void captureSnapshot(String accountId, String region, String snapshotName, String cacheName) {
        EngineRuntime runtime = runtimes.get(runtimeKey(accountId, region, cacheName));
        List<ServerlessCacheSnapshotEntry> entries;
        try {
            entries = runtime != null && runtime.hasBackend()
                    ? dataPlane.capture(runtime.engine(), runtime.handle().getHost(), runtime.handle().getPort())
                    : List.of();
        } catch (IOException | RuntimeException e) {
            LOG.warnv(e, "Snapshot {0} of serverless cache {1} failed", snapshotName, cacheName);
            updateSnapshot(accountId, region, snapshotName, s -> s.setStatus(SNAPSHOT_FAILED));
            return;
        }
        long bytes = 0;
        for (ServerlessCacheSnapshotEntry entry : entries) {
            bytes += Base64.getDecoder().decode(entry.getKey()).length
                    + Base64.getDecoder().decode(entry.getValue()).length;
        }
        long bytesUsed = bytes;
        updateSnapshot(accountId, region, snapshotName, s -> {
            s.setEntries(entries);
            s.setBytesUsedForCache(bytesUsed);
            s.setStatus(AVAILABLE);
        });
    }

    private synchronized void updateSnapshot(String accountId, String region, String name,
                                             Consumer<ServerlessCacheSnapshot> update) {
        snapshots.getForAccount(accountId, key(region, name))
                .filter(snapshot -> CREATING.equals(snapshot.getStatus()))
                .ifPresent(snapshot -> {
                    update.accept(snapshot);
                    snapshots.putForAccount(accountId, key(region, name), snapshot);
                });
    }

    /**
     * Only records are persisted: after a restart every cache comes back without its engine. The
     * first serverless call of the process re-provisions them, empty, as Floci does for replication
     * groups, and finishes deletes and modifies that were in flight.
     */
    private void ensureRuntimeRestored() {
        if (!runtimeRestored.compareAndSet(false, true)) {
            return;
        }
        for (AccountAwareStorageBackend.AccountEntry<ServerlessCache> entry : caches.scanAllAccountEntries(k -> true)) {
            ServerlessCache cache = entry.value();
            String accountId = entry.accountId();
            String region = cache.getRegion();
            String name = cache.getServerlessCacheName();
            if (runtimes.containsKey(runtimeKey(accountId, region, name))) {
                continue;
            }
            switch (cache.getStatus()) {
                case AVAILABLE, MODIFYING, CREATING -> {
                    cache.setStatus(CREATING);
                    cache.setEndpoint(null);
                    cache.setReaderEndpoint(null);
                    cache.setSnapshotNamesToRestore(List.of());
                    caches.putForAccount(accountId, key(region, name), cache);
                    executor.execute(() -> provision(accountId, region, name));
                }
                case DELETING -> executor.execute(() -> teardown(accountId, region, name, null));
                default -> {
                }
            }
        }
    }

    @Override
    public synchronized void clear() {
        for (String runtimeKey : List.copyOf(runtimes.keySet())) {
            String[] parts = runtimeKey.split("/", 3);
            stopRuntime(parts[0], parts[1], parts[2]);
            if (containerEndpoints != null) {
                containerEndpoints.release(ServerlessCacheEndpoints.address(parts[2], parts[0], parts[1]));
            }
        }
        runtimeRestored.set(true);
    }

    // ── Validation and lookups ────────────────────────────────────────────────

    private ServerlessCache requireCache(String accountId, String region, String name) {
        if (name == null || name.isBlank()) {
            throw missingParameter("ServerlessCacheName");
        }
        String normalized = normalize(name);
        return caches.getForAccount(accountId, key(region, normalized))
                .orElseThrow(() -> new AwsException("ServerlessCacheNotFoundFault",
                        "Serverless cache " + normalized + " not found.", 404));
    }

    private ServerlessCacheSnapshot requireSnapshot(String accountId, String region, String name) {
        String normalized = normalize(name);
        return snapshots.getForAccount(accountId, key(region, normalized))
                .orElseThrow(() -> new AwsException("ServerlessCacheSnapshotNotFoundFault",
                        "Serverless cache snapshot " + normalized + " not found.", 404));
    }

    private ServerlessCacheSnapshot newSnapshot(String accountId, String region, String snapshotName,
                                                ServerlessCache cache, String kmsKeyArn, Map<String, String> tags) {
        String name = requireSnapshotName(snapshotName);
        if (snapshots.getForAccount(accountId, key(region, name)).isPresent()) {
            throw new AwsException("ServerlessCacheSnapshotAlreadyExistsFault",
                    "Serverless cache snapshot " + name + " already exists.", 400);
        }
        ServerlessCacheSnapshot snapshot = new ServerlessCacheSnapshot();
        snapshot.setServerlessCacheSnapshotName(name);
        snapshot.setArn(arn(region, accountId, "serverlesscachesnapshot", name));
        snapshot.setAccountId(accountId);
        snapshot.setRegion(region);
        snapshot.setKmsKeyId(kmsKeyArn);
        snapshot.setSnapshotType("manual");
        snapshot.setStatus(CREATING);
        snapshot.setCreateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        snapshot.setServerlessCacheName(cache.getServerlessCacheName());
        snapshot.setEngine(cache.getEngine());
        snapshot.setMajorEngineVersion(cache.getMajorEngineVersion());
        snapshot.setTags(tags);
        return snapshot;
    }

    private static String cacheName(String name) {
        if (name == null || name.isBlank()) {
            throw missingParameter("ServerlessCacheName");
        }
        String normalized = normalize(name);
        if (normalized.length() > MAX_NAME_LENGTH || !NAME.matcher(normalized).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid ServerlessCacheName " + name + ": it must be 1 to " + MAX_NAME_LENGTH
                            + " letters, digits or hyphens, begin with a letter, and must not end with a "
                            + "hyphen or contain two consecutive hyphens.", 400);
        }
        return normalized;
    }

    private static String requireSnapshotName(String name) {
        if (name == null || name.isBlank()) {
            throw missingParameter("ServerlessCacheSnapshotName");
        }
        String normalized = normalize(name);
        if (normalized.length() > MAX_SNAPSHOT_NAME_LENGTH || !NAME.matcher(normalized).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid ServerlessCacheSnapshotName " + name + ": it must be 1 to " + MAX_SNAPSHOT_NAME_LENGTH
                            + " letters, digits or hyphens, begin with a letter, and must not end with a "
                            + "hyphen or contain two consecutive hyphens.", 400);
        }
        return normalized;
    }

    private static String engine(String engine, boolean required) {
        if (engine == null || engine.isBlank()) {
            if (required) {
                throw missingParameter("Engine");
            }
            return null;
        }
        String normalized = engine.toLowerCase(Locale.ROOT);
        if (!ENGINE_VERSIONS.containsKey(normalized)) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid engine " + engine + ". Valid engines are valkey, redis and memcached.", 400);
        }
        return normalized;
    }

    private static String majorVersion(String engine, String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_MAJOR_VERSIONS.get(engine);
        }
        if (!ENGINE_VERSIONS.get(engine).containsKey(requested)) {
            throw new AwsException("InvalidParameterValue",
                    "Major engine version " + requested + " is not supported for engine " + engine
                            + ". Supported versions: " + String.join(", ", ENGINE_VERSIONS.get(engine).keySet()) + ".",
                    400);
        }
        return requested;
    }

    private static UsageLimits validateUsageLimits(UsageLimits limits) {
        if (limits == null) {
            return null;
        }
        if (limits.dataStoragePresent()) {
            if (!"GB".equals(limits.dataStorageUnit())) {
                throw new AwsException("InvalidParameterValue",
                        "CacheUsageLimits.DataStorage.Unit must be GB.", 400);
            }
            requireRange("CacheUsageLimits.DataStorage.Maximum", limits.dataStorageMaximum(), 1, 5_000);
            requireRange("CacheUsageLimits.DataStorage.Minimum", limits.dataStorageMinimum(), 1, 5_000);
            if (limits.dataStorageMaximum() == null && limits.dataStorageMinimum() == null) {
                throw new AwsException("InvalidParameterCombination",
                        "CacheUsageLimits.DataStorage requires a Maximum or a Minimum.", 400);
            }
            if (limits.dataStorageMaximum() != null && limits.dataStorageMinimum() != null
                    && limits.dataStorageMinimum() > limits.dataStorageMaximum()) {
                throw new AwsException("InvalidParameterCombination",
                        "CacheUsageLimits.DataStorage.Minimum cannot be greater than Maximum.", 400);
            }
        }
        if (limits.ecpuPresent()) {
            requireRange("CacheUsageLimits.ECPUPerSecond.Maximum", limits.ecpuMaximum(), 1_000, 15_000_000);
            requireRange("CacheUsageLimits.ECPUPerSecond.Minimum", limits.ecpuMinimum(), 1_000, 15_000_000);
            if (limits.ecpuMaximum() == null && limits.ecpuMinimum() == null) {
                throw new AwsException("InvalidParameterCombination",
                        "CacheUsageLimits.ECPUPerSecond requires a Maximum or a Minimum.", 400);
            }
            if (limits.ecpuMaximum() != null && limits.ecpuMinimum() != null
                    && limits.ecpuMinimum() > limits.ecpuMaximum()) {
                throw new AwsException("InvalidParameterCombination",
                        "CacheUsageLimits.ECPUPerSecond.Minimum cannot be greater than Maximum.", 400);
            }
        }
        return limits;
    }

    private static void applyUsageLimits(ServerlessCache cache, UsageLimits limits) {
        if (limits == null) {
            return;
        }
        if (limits.dataStoragePresent()) {
            cache.setDataStorageMaximum(limits.dataStorageMaximum());
            cache.setDataStorageMinimum(limits.dataStorageMinimum());
            cache.setDataStorageUnit(limits.dataStorageUnit());
        }
        if (limits.ecpuPresent()) {
            cache.setEcpuPerSecondMaximum(limits.ecpuMaximum());
            cache.setEcpuPerSecondMinimum(limits.ecpuMinimum());
        }
    }

    private static void requireRange(String name, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            throw new AwsException("InvalidParameterValue",
                    name + " must be between " + min + " and " + max + ".", 400);
        }
    }

    private static Integer snapshotRetentionLimit(Integer value) {
        requireRange("SnapshotRetentionLimit", value, 0, 35);
        return value;
    }

    private static String dailySnapshotTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!DAILY_SNAPSHOT_TIME.matcher(value).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid DailySnapshotTime " + value + ". The expected format is HH:MM in UTC.", 400);
        }
        return value;
    }

    private static String networkType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!NETWORK_TYPES.contains(value)) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid NetworkType " + value + ". Valid values are ipv4, ipv6 and dual_stack.", 400);
        }
        return value;
    }

    private static void requireTagQuota(Map<String, String> tags) {
        if (tags != null && tags.size() > MAX_TAGS) {
            throw new AwsException("TagQuotaPerResourceExceeded",
                    "The request cannot be processed because it would cause the resource to have more than "
                            + MAX_TAGS + " tags.", 400);
        }
    }

    /** Floci does not model ElastiCache user groups, so no user group id can name an existing one. */
    private static void requireUserGroup(String engine, String userGroupId) {
        if ("memcached".equals(engine)) {
            throw new AwsException("InvalidParameterCombination",
                    "UserGroupId is not supported for the memcached engine.", 400);
        }
        throw new AwsException("UserGroupNotFound", "User group " + userGroupId + " not found.", 404);
    }

    /**
     * The subnets and security groups the cache endpoint is placed in, read from EC2 as AWS reads
     * them: every given subnet must exist and share one VPC, every security group must exist in
     * that VPC. Omitted subnets default to the default VPC's default-for-AZ subnets and omitted
     * security groups to the VPC's {@code default} group.
     */
    private Network resolveNetwork(String region, List<String> requestedSubnets, List<String> requestedGroups) {
        List<String> subnetIds;
        String vpcId;
        if (requestedSubnets == null || requestedSubnets.isEmpty()) {
            String defaultVpcId = ec2Service.resolveDefaultVpcId(region);
            subnetIds = ec2Service.describeSubnets(region, List.of(), Map.of()).stream()
                    .filter(subnet -> defaultVpcId.equals(subnet.getVpcId()) && subnet.isDefaultForAz())
                    .map(Subnet::getSubnetId)
                    .sorted()
                    .toList();
            if (subnetIds.isEmpty()) {
                throw new AwsException("InvalidParameterValue",
                        "No default VPC subnets were found in " + region + ". Specify SubnetIds.", 400);
            }
            vpcId = defaultVpcId;
        } else {
            List<Subnet> resolved = new ArrayList<>();
            for (String subnetId : requestedSubnets) {
                Subnet subnet = ec2Service.findSubnetById(region, subnetId)
                        .filter(found -> region.equals(found.getRegion()) || found.getRegion() == null)
                        .orElseThrow(() -> new AwsException("InvalidParameterValue",
                                "Some input subnets in :[" + String.join(", ", requestedSubnets)
                                        + "] are invalid.", 400));
                resolved.add(subnet);
            }
            vpcId = resolved.getFirst().getVpcId();
            for (Subnet subnet : resolved) {
                if (vpcId != null && !vpcId.equals(subnet.getVpcId())) {
                    throw new AwsException("InvalidParameterValue",
                            "Subnets " + resolved.getFirst().getSubnetId() + " and " + subnet.getSubnetId()
                                    + " are not in the same VPC. All subnets must belong to the same VPC.", 400);
                }
            }
            subnetIds = List.copyOf(requestedSubnets);
        }
        List<String> groups = requestedGroups == null || requestedGroups.isEmpty()
                ? defaultSecurityGroup(region, vpcId)
                : resolveSecurityGroups(region, vpcId, requestedGroups);
        return new Network(vpcId, subnetIds, groups);
    }

    private List<String> resolveSecurityGroups(String region, String vpcId, List<String> groupIds) {
        for (String groupId : groupIds) {
            SecurityGroup group;
            try {
                group = ec2Service.describeSecurityGroups(region, List.of(groupId), List.of(), Map.of())
                        .stream().findFirst().orElse(null);
            } catch (AwsException e) {
                group = null;
            }
            if (group == null) {
                throw new AwsException("InvalidParameterValue",
                        "The security group " + groupId + " does not exist.", 400);
            }
            if (vpcId != null && group.getVpcId() != null && !vpcId.equals(group.getVpcId())) {
                throw new AwsException("InvalidParameterCombination",
                        "The security group " + groupId + " belongs to VPC " + group.getVpcId()
                                + ", but the serverless cache subnets are in VPC " + vpcId + ".", 400);
            }
        }
        return List.copyOf(groupIds);
    }

    private List<String> defaultSecurityGroup(String region, String vpcId) {
        return ec2Service.getSecurityGroupsForVpc(region, vpcId, Map.of()).stream()
                .filter(group -> "default".equals(group.getGroupName()))
                .map(SecurityGroup::getGroupId)
                .findFirst()
                .map(List::of)
                .orElse(List.of());
    }

    private String resolveKmsKey(String kmsKeyId, String region) {
        if (kmsKeyId == null || kmsKeyId.isBlank()) {
            return null;
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
        return new AwsException("InvalidParameterValue", "KMS key does not exist with key id: " + kmsKeyId, 400);
    }

    /**
     * Serverless caches restore from serverless snapshots (by ARN) of a compatible engine. AWS also
     * accepts RDB files in S3; Floci keeps no RDB files and rejects those ARNs explicitly.
     */
    private List<String> resolveSnapshotsToRestore(String accountId, String region, String engine,
                                                   List<String> arns) {
        List<String> names = new ArrayList<>();
        if (arns == null) {
            return names;
        }
        for (String arn : arns) {
            String[] parts = arn.split(":", 7);
            if (parts.length == 6 && "s3".equals(parts[2])) {
                throw new AwsException("InvalidParameterValue",
                        "Restoring a serverless cache from RDB files in Amazon S3 is not supported by this emulator.",
                        400);
            }
            if (parts.length != 7 || !"elasticache".equals(parts[2]) || !"serverlesscachesnapshot".equals(parts[5])
                    || !region.equals(parts[3]) || !accountId.equals(parts[4])) {
                throw new AwsException("InvalidParameterValue", "Invalid snapshot ARN: " + arn, 400);
            }
            ServerlessCacheSnapshot snapshot = snapshots.getForAccount(accountId, key(region, parts[6]))
                    .orElseThrow(() -> new AwsException("InvalidParameterValue",
                            "Serverless cache snapshot " + parts[6] + " does not exist.", 400));
            if (!AVAILABLE.equals(snapshot.getStatus())) {
                throw new AwsException("InvalidParameterCombination",
                        "Serverless cache snapshot " + parts[6] + " is not available.", 400);
            }
            if ("memcached".equals(snapshot.getEngine()) != "memcached".equals(engine)) {
                throw new AwsException("InvalidParameterCombination",
                        "A " + snapshot.getEngine() + " snapshot cannot be restored into a " + engine + " cache.", 400);
            }
            names.add(snapshot.getServerlessCacheSnapshotName());
        }
        return names;
    }

    private static AwsException invalidCacheState(ServerlessCache cache) {
        return new AwsException("InvalidServerlessCacheStateFault",
                "Serverless cache " + cache.getServerlessCacheName() + " is in " + cache.getStatus()
                        + " state and cannot be modified.", 400);
    }

    private static AwsException invalidSnapshotState(ServerlessCacheSnapshot snapshot) {
        return new AwsException("InvalidServerlessCacheSnapshotStateFault",
                "Serverless cache snapshot " + snapshot.getServerlessCacheSnapshotName() + " is in "
                        + snapshot.getStatus() + " state.", 400);
    }

    private static AwsException missingParameter(String name) {
        return new AwsException("InvalidParameterValue", "The parameter " + name + " must be provided.", 400);
    }

    private static int pageSize(Integer maxResults, int defaultSize, int max) {
        if (maxResults == null) {
            return defaultSize;
        }
        if (maxResults < 1 || maxResults > max) {
            throw new AwsException("InvalidParameterValue", "MaxResults must be between 1 and " + max + ".", 400);
        }
        return maxResults;
    }

    private static <T> Page<T> page(List<T> all, int pageSize, String nextToken) {
        int offset = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(nextToken), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new AwsException("InvalidParameterValue", "Invalid NextToken.", 400);
            }
            if (offset < 0 || offset > all.size()) {
                throw new AwsException("InvalidParameterValue", "Invalid NextToken.", 400);
            }
        }
        int end = Math.min(all.size(), offset + pageSize);
        String next = end < all.size()
                ? Base64.getUrlEncoder().withoutPadding().encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8))
                : null;
        return new Page<>(all.subList(offset, end), next);
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static Map<String, String> orderedVersions(String... majorAndFull) {
        Map<String, String> versions = new LinkedHashMap<>();
        for (int i = 0; i < majorAndFull.length; i += 2) {
            versions.put(majorAndFull[i], majorAndFull[i + 1]);
        }
        return versions;
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private static String runtimeKey(String accountId, String region, String name) {
        return accountId + "/" + region + "/" + name;
    }

    private static String containerKey(String accountId, String region, String name) {
        return "serverless-" + accountId + "-" + region + "-" + name;
    }

    private static String arn(String region, String accountId, String resourceType, String name) {
        String partition = region.startsWith("cn-") ? "aws-cn" : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
        return "arn:" + partition + ":elasticache:" + region + ":" + accountId + ":" + resourceType + ":" + name;
    }
}
