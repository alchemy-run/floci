package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerHandle;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerManager;
import io.github.hectorvent.floci.services.memorydb.model.Acl;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.model.Endpoint;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.EngineVersion;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.Event;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.Parameter;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.ParameterGroup;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.Snapshot;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.SubnetGroup;
import io.github.hectorvent.floci.services.memorydb.model.User;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Core MemoryDB business logic — clusters, ACLs and users.
 *
 * <p>Authentication follows the real MemoryDB model: a {@link User} is created with a
 * password or IAM auth mode, attached to an {@link Acl}, and a cluster references that
 * ACL via {@code ACLName}. A cluster therefore has no auth mode of its own — its
 * effective authentication is resolved from the users of the ACL it references.
 *
 * <p>The built-in {@code open-access} ACL and {@code default} user (which AWS provides
 * out of the box and which cannot be created or deleted) are synthesized rather than
 * stored, so they always exist for every account and map to the no-auth path.
 */
@ApplicationScoped
public class MemoryDbService {

    private static final Logger LOG = Logger.getLogger(MemoryDbService.class);
    private static final String DEFAULT_ENGINE = "redis";
    private static final String DEFAULT_ENGINE_VERSION = "7.1";
    private static final String DEFAULT_ACL = "open-access";
    private static final String DEFAULT_USER = "default";
    private static final String ACTIVE = "active";
    private static final int REDIS_PORT = 6379;

    // Per the MemoryDB API: a user name must start with a letter and contain only
    // letters, digits and hyphens.
    private static final Pattern USER_NAME_PATTERN =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9\\-]*");
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9.]+$|.*:.*");

    private final StorageBackend<String, Cluster> clusters;
    private final StorageBackend<String, User> users;
    private final StorageBackend<String, Acl> acls;
    private final StorageBackend<String, ParameterGroup> parameterGroups;
    private final StorageBackend<String, SubnetGroup> subnetGroups;
    private final StorageBackend<String, Snapshot> snapshots;
    private final StorageBackend<String, Event> events;
    private final Ec2Service ec2Service;
    private final MemoryDbContainerManager containerManager;
    private final MemoryDbProxyManager proxyManager;
    private final SigV4Validator sigV4Validator;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    private final Set<String> provisioningClusterNames = ConcurrentHashMap.newKeySet();

    @Inject
    public MemoryDbService(MemoryDbContainerManager containerManager,
                           MemoryDbProxyManager proxyManager,
                           SigV4Validator sigV4Validator,
                           StorageFactory storageFactory,
                           EmulatorConfig config,
                           RegionResolver regionResolver,
                           Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.sigV4Validator = sigV4Validator;
        this.config = config;
        this.regionResolver = regionResolver;
        this.clusters = storageFactory.create("memorydb", "memorydb-clusters.json",
                new TypeReference<Map<String, Cluster>>() {});
        this.users = storageFactory.create("memorydb", "memorydb-users.json",
                new TypeReference<Map<String, User>>() {});
        this.acls = storageFactory.create("memorydb", "memorydb-acls.json",
                new TypeReference<Map<String, Acl>>() {});
        this.parameterGroups = storageFactory.create("memorydb", "memorydb-parameter-groups.json",
                new TypeReference<Map<String, ParameterGroup>>() {});
        this.subnetGroups = storageFactory.create("memorydb", "memorydb-subnet-groups.json",
                new TypeReference<Map<String, SubnetGroup>>() {});
        this.snapshots = storageFactory.create("memorydb", "memorydb-snapshots.json",
                new TypeReference<Map<String, Snapshot>>() {});
        this.events = storageFactory.create("memorydb", "memorydb-events.json",
                new TypeReference<Map<String, Event>>() {});
    }

    // ──────────────────────────── Clusters ────────────────────────────

    public Cluster createCluster(Cluster spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ClusterName is required.", 400);
        }
        String resourceKey = key(region, name);
        if (clusters.get(resourceKey).isPresent() || legacyGet(clusters, name, region).isPresent()) {
            throw new AwsException("ClusterAlreadyExistsFault",
                    "Cluster with specified name already exists.", 400);
        }
        // Claim the name for the whole provisioning attempt so a concurrent create can't race
        // ahead and be stopped by this request's handle-less rollback fallback.
        if (!provisioningClusterNames.add(resourceKey)) {
            throw new AwsException("ClusterAlreadyExistsFault",
                    "Cluster " + name + " is already being created.", 400);
        }

        try {
            String aclName = spec.getAclName();
            if (aclName == null || aclName.isBlank()) {
                throw new AwsException("InvalidParameterValueException", "ACLName is required.", 400);
            }
            requireAclExists(aclName, region);
            if (spec.getParameterGroupName() != null) {
                getParameterGroup(spec.getParameterGroupName(), region);
            }
            if (spec.getSubnetGroupName() != null) {
                getSubnetGroup(spec.getSubnetGroupName(), region);
            }
            boolean authRequired = isAuthRequired(aclName, region);

            Cluster cluster = new Cluster();
            cluster.setName(name);
            cluster.setAccountId(regionResolver.getAccountId());
            cluster.setRegion(region);
            cluster.setDescription(spec.getDescription());
            cluster.setStatus(ClusterStatus.AVAILABLE);
            cluster.setNodeType(spec.getNodeType() != null ? spec.getNodeType() : "db.t4g.small");
            cluster.setNumberOfShards(spec.getNumberOfShards() > 0 ? spec.getNumberOfShards() : 1);
            cluster.setEngine(spec.getEngine() != null ? spec.getEngine() : DEFAULT_ENGINE);
            cluster.setEngineVersion(spec.getEngineVersion() != null ? spec.getEngineVersion() : DEFAULT_ENGINE_VERSION);
            cluster.setAclName(aclName);
            cluster.setParameterGroupName(spec.getParameterGroupName());
            cluster.setSubnetGroupName(spec.getSubnetGroupName());
            cluster.setTlsEnabled(spec.isTlsEnabled());
            cluster.setSecurityGroupIds(spec.getSecurityGroupIds());
            cluster.setArn(buildArn(region, "cluster", name));
            cluster.setCreatedAt(Instant.now());
            cluster.setTags(spec.getTags());

            if (config.services().memorydb().mock()) {
                LOG.infov("Creating MemoryDB cluster {0} in mock mode (no container)", name);
                cluster.setClusterEndpoint(new Endpoint(
                        resolveEndpointHost(name, cluster.getAccountId(), region), REDIS_PORT));
            } else {
                startBackend(cluster, authRequired);
            }

            clusters.put(resourceKey, cluster);
            recordEvent(name, "cluster", "Cluster created", region);
            LOG.infov("MemoryDB cluster {0} created (acl={1}, authRequired={2}), endpoint={3}:{4}",
                    name, aclName, String.valueOf(authRequired), cluster.getClusterEndpoint().address(),
                    String.valueOf(cluster.getClusterEndpoint().port()));
            return cluster;
        } finally {
            provisioningClusterNames.remove(resourceKey);
        }
    }

    public Cluster getCluster(String name) {
        return getCluster(name, currentRegion());
    }

    public Cluster getCluster(String name, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ClusterName is required.", 400);
        }
        return resourceGet(clusters, name, region).orElseThrow(() ->
                new AwsException("ClusterNotFoundFault", "Cluster not found.", 404));
    }

    public Collection<Cluster> describeClusters(String filterName) {
        return describeClusters(filterName, currentRegion());
    }

    public Collection<Cluster> describeClusters(String filterName, String region) {
        migrateLegacyClusters(region);
        if (filterName != null && !filterName.isBlank()) {
            return List.of(getCluster(filterName, region));
        }
        return clusters.scan(k -> k.startsWith(region + ":"));
    }

    public Cluster updateCluster(String name, String description) {
        return updateCluster(name, description, currentRegion());
    }

    public Cluster updateCluster(String name, String description, String region) {
        return updateCluster(name, description, null, region);
    }

    public Cluster updateCluster(String name, String description, List<String> securityGroupIds, String region) {
        Cluster cluster = getCluster(name, region);
        if (description != null) {
            cluster.setDescription(description);
        }
        if (securityGroupIds != null) {
            cluster.setSecurityGroupIds(securityGroupIds);
        }
        clusters.put(key(region, name), cluster);
        recordEvent(name, "cluster", "Cluster updated", region);
        return cluster;
    }

    public Cluster deleteCluster(String name) {
        return deleteCluster(name, currentRegion());
    }

    public Cluster deleteCluster(String name, String region, String finalSnapshotName) {
        if (finalSnapshotName != null) {
            createSnapshot(name, finalSnapshotName, Map.of(), region);
        }
        return deleteCluster(name, region);
    }

    public Cluster deleteCluster(String name, String region) {
        Cluster cluster = getCluster(name, region);
        cluster.setStatus(ClusterStatus.DELETING);
        String identity = identityName(cluster.getAccountId(), region, name);

        proxyManager.stopProxy(identity);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new MemoryDbContainerHandle(
                    cluster.getContainerId(), identity, cluster.getContainerHost(), cluster.getContainerPort()));
        }

        releaseProxyPort(cluster.getProxyPort());
        clusters.delete(key(region, name));
        recordEvent(name, "cluster", "Cluster deleted", region);
        LOG.infov("MemoryDB cluster {0} deleted", name);
        return cluster;
    }

    // ──────────────────────────── Users ────────────────────────────

    public User createUser(User spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "UserName is required.", 400);
        }
        if (!USER_NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValueException",
                    "UserName must start with a letter and contain only letters, digits and hyphens.", 400);
        }
        String resourceKey = key(region, name);
        if (DEFAULT_USER.equals(name) || users.get(resourceKey).isPresent()
                || legacyGet(users, name, region).isPresent()) {
            throw new AwsException("UserAlreadyExistsFault",
                    "User with specified name already exists.", 400);
        }
        if (spec.getAuthMode() == null) {
            throw new AwsException("InvalidParameterValueException",
                    "AuthenticationMode is required.", 400);
        }
        // AuthenticationMode.Type accepts "no-password" in the wire enum, but the service
        // rejects it on create: per the API, all newly-created users must authenticate with
        // a password or IAM. "no-password" is only ever the built-in default user.
        if (spec.getAuthMode() == AuthMode.NO_PASSWORD) {
            throw new AwsException("InvalidParameterValueException",
                    "AuthenticationMode Type must be 'password' or 'iam' for a new user.", 400);
        }
        if (spec.getAuthMode() == AuthMode.PASSWORD
                && (spec.getPasswords() == null || spec.getPasswords().isEmpty())) {
            throw new AwsException("InvalidParameterValueException",
                    "At least one password is required for password authentication.", 400);
        }
        if (spec.getAccessString() == null || spec.getAccessString().isBlank()) {
            throw new AwsException("InvalidParameterValueException", "AccessString is required.", 400);
        }

        User user = new User();
        user.setName(name);
        user.setAccountId(regionResolver.getAccountId());
        user.setRegion(region);
        user.setStatus(ACTIVE);
        user.setAuthMode(spec.getAuthMode());
        user.setPasswords(spec.getPasswords());
        user.setAccessString(spec.getAccessString());
        user.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        user.setArn(buildArn(region, "user", name));
        user.setCreatedAt(Instant.now());
        user.setTags(spec.getTags());

        users.put(resourceKey, user);
        recordEvent(name, "user", "User created", region);
        LOG.infov("MemoryDB user {0} created with authMode={1}", name, user.getAuthMode());
        return user;
    }

    public Collection<User> describeUsers(String filterName, String region) {
        if (filterName != null && !filterName.isBlank()) {
            return resourceGet(users, filterName, region)
                    .map(List::of)
                    .or(() -> DEFAULT_USER.equals(filterName)
                            ? Optional.of(List.of(builtinDefaultUser(region)))
                            : Optional.empty())
                    .orElseThrow(() -> new AwsException("UserNotFoundFault", "User not found.", 404));
        }
        migrateLegacyUsers(region);
        List<User> all = new ArrayList<>();
        all.add(builtinDefaultUser(region));
        all.addAll(users.scan(k -> k.startsWith(region + ":")));
        return all;
    }

    public synchronized User updateUser(String name, String accessString, AuthMode authMode,
                                        List<String> passwords, String region) {
        requireText(name, "UserName");
        User user = resourceGet(users, name, region).orElseThrow(() -> fault("UserNotFoundFault", "User not found."));
        if (accessString != null && accessString.isBlank()) {
            throw invalid("AccessString must not be empty.");
        }
        if (authMode == AuthMode.NO_PASSWORD || (authMode == AuthMode.PASSWORD && passwords.isEmpty())) {
            throw invalid("Authentication requires IAM or at least one password.");
        }
        if (accessString != null) {
            user.setAccessString(accessString);
        }
        if (authMode != null) {
            user.setAuthMode(authMode);
            user.setPasswords(authMode == AuthMode.IAM ? List.of() : new ArrayList<>(passwords));
        }
        users.put(key(region, name), user);
        recordEvent(name, "user", "User updated", region);
        return user;
    }

    public User deleteUser(String name) {
        return deleteUser(name, currentRegion());
    }

    public User deleteUser(String name, String region) {
        if (DEFAULT_USER.equals(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "The default user cannot be deleted.", 400);
        }
        User user = resourceGet(users, name, region).orElseThrow(() ->
                new AwsException("UserNotFoundFault", "User not found.", 404));
        if (!aclNamesForUser(name, region).isEmpty()) {
            throw fault("InvalidUserStateFault", "User is associated with an ACL.");
        }
        users.delete(key(region, name));
        recordEvent(name, "user", "User deleted", region);
        LOG.infov("MemoryDB user {0} deleted", name);
        return user;
    }

    // ──────────────────────────── ACLs ────────────────────────────

    public Acl createAcl(Acl spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ACLName is required.", 400);
        }
        String resourceKey = key(region, name);
        if (DEFAULT_ACL.equals(name) || acls.get(resourceKey).isPresent()
                || legacyGet(acls, name, region).isPresent()) {
            throw new AwsException("ACLAlreadyExistsFault",
                    "ACL with specified name already exists.", 400);
        }
        // A custom ACL holds only custom users and may be empty: the built-in default user
        // belongs to the open-access ACL alone.
        validateAclMembers(spec.getUserNames(), region);

        Acl acl = new Acl();
        acl.setName(name);
        acl.setAccountId(regionResolver.getAccountId());
        acl.setRegion(region);
        acl.setStatus(ACTIVE);
        acl.setUserNames(new ArrayList<>(spec.getUserNames()));
        acl.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        acl.setArn(buildArn(region, "acl", name));
        acl.setCreatedAt(Instant.now());
        acl.setTags(spec.getTags());

        acls.put(resourceKey, acl);
        recordEvent(name, "acl", "ACL created", region);
        LOG.infov("MemoryDB ACL {0} created with users={1}", name, acl.getUserNames());
        return acl;
    }

    public synchronized Acl updateAcl(String name, List<String> userNamesToAdd, List<String> userNamesToRemove,
                                      String region) {
        requireText(name, "ACLName");
        if (DEFAULT_ACL.equals(name)) {
            throw invalid("The open-access ACL cannot be modified.");
        }
        Acl acl = resourceGet(acls, name, region).orElseThrow(() ->
                new AwsException("ACLNotFoundFault", "ACL not found.", 404));
        if (userNamesToAdd.isEmpty() && userNamesToRemove.isEmpty()) {
            throw fault("InvalidParameterCombinationException",
                    "At least one of UserNamesToAdd or UserNamesToRemove must be specified.");
        }
        for (String userName : userNamesToAdd) {
            if (userNamesToRemove.contains(userName)) {
                throw fault("InvalidParameterCombinationException",
                        "User " + userName + " cannot be both added to and removed from the ACL.");
            }
        }
        validateAclMembers(userNamesToAdd, region);
        List<String> members = new ArrayList<>(acl.getUserNames());
        for (String userName : userNamesToAdd) {
            if (members.contains(userName)) {
                throw fault("DuplicateUserNameFault", "User " + userName + " is already a member of the ACL.");
            }
        }
        for (String userName : userNamesToRemove) {
            if (!members.contains(userName)) {
                throw invalid("User " + userName + " is not a member of the ACL.");
            }
        }
        members.removeAll(userNamesToRemove);
        members.addAll(userNamesToAdd);
        acl.setUserNames(members);
        acls.put(key(region, name), acl);
        recordEvent(name, "acl", "ACL updated", region);
        return acl;
    }

    private void validateAclMembers(List<String> userNames, String region) {
        Set<String> seen = new HashSet<>();
        for (String userName : userNames) {
            if (DEFAULT_USER.equals(userName)) {
                throw invalid("The default user can only be a member of the open-access ACL.");
            }
            if (!seen.add(userName)) {
                throw new AwsException("DuplicateUserNameFault",
                        "Duplicate user name " + userName + " in ACL.", 400);
            }
            if (!userExists(userName, region)) {
                throw new AwsException("UserNotFoundFault", "User " + userName + " not found.", 404);
            }
        }
    }

    public Collection<Acl> describeAcls(String filterName, String region) {
        if (filterName != null && !filterName.isBlank()) {
            return resourceGet(acls, filterName, region)
                    .map(List::of)
                    .or(() -> DEFAULT_ACL.equals(filterName)
                            ? Optional.of(List.of(builtinOpenAccessAcl(region)))
                            : Optional.empty())
                    .orElseThrow(() -> new AwsException("ACLNotFoundFault", "ACL not found.", 404));
        }
        migrateLegacyAcls(region);
        List<Acl> all = new ArrayList<>();
        all.add(builtinOpenAccessAcl(region));
        all.addAll(acls.scan(k -> k.startsWith(region + ":")));
        return all;
    }

    public Acl deleteAcl(String name) {
        return deleteAcl(name, currentRegion());
    }

    public Acl deleteAcl(String name, String region) {
        if (DEFAULT_ACL.equals(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "The open-access ACL cannot be deleted.", 400);
        }
        Acl acl = resourceGet(acls, name, region).orElseThrow(() ->
                new AwsException("ACLNotFoundFault", "ACL not found.", 404));
        if (!clustersUsingAcl(name, region).isEmpty()) {
            throw new AwsException("InvalidACLStateFault",
                    "ACL " + name + " is associated with one or more clusters.", 400);
        }
        acls.delete(key(region, name));
        recordEvent(name, "acl", "ACL deleted", region);
        LOG.infov("MemoryDB ACL {0} deleted", name);
        return acl;
    }

    /** Names of ACLs that include the given user; used to populate the user response. */
    public List<String> aclNamesForUser(String userName) {
        return aclNamesForUser(userName, currentRegion());
    }

    public List<String> aclNamesForUser(String userName, String region) {
        migrateLegacyAcls(region);
        List<String> result = new ArrayList<>();
        if (DEFAULT_USER.equals(userName)) {
            result.add(DEFAULT_ACL);
        }
        acls.scan(k -> k.startsWith(region + ":")).stream()
                .filter(a -> a.getUserNames().contains(userName))
                .map(Acl::getName)
                .forEach(result::add);
        return result;
    }

    /** Names of clusters currently referencing the given ACL; used to populate the ACL response. */
    public List<String> clustersUsingAcl(String aclName) {
        return clustersUsingAcl(aclName, currentRegion());
    }

    public List<String> clustersUsingAcl(String aclName, String region) {
        migrateLegacyClusters(region);
        return clusters.scan(k -> k.startsWith(region + ":")).stream()
                .filter(c -> aclName.equals(c.getAclName()))
                .map(Cluster::getName)
                .toList();
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTags(String resourceArn) {
        return listTags(resourceArn, currentRegion());
    }

    public synchronized Map<String, String> listTags(String resourceArn, String region) {
        return new LinkedHashMap<>(tagTarget(resourceArn, region).tags());
    }

    public Map<String, String> tagResource(String resourceArn, Map<String, String> tags) {
        return tagResource(resourceArn, tags, currentRegion());
    }

    public synchronized Map<String, String> tagResource(String resourceArn, Map<String, String> tags, String region) {
        TagTarget target = tagTarget(resourceArn, region);
        target.tags().putAll(tags);
        target.persist().run();
        return new LinkedHashMap<>(target.tags());
    }

    public Map<String, String> untagResource(String resourceArn, List<String> tagKeys) {
        return untagResource(resourceArn, tagKeys, currentRegion());
    }

    public synchronized Map<String, String> untagResource(String resourceArn, List<String> tagKeys, String region) {
        TagTarget target = tagTarget(resourceArn, region);
        tagKeys.forEach(target.tags()::remove);
        target.persist().run();
        return new LinkedHashMap<>(target.tags());
    }

    private static final List<EngineVersion> ENGINE_VERSIONS = List.of(
            new EngineVersion("redis", "7.1", "memorydb_redis7"),
            new EngineVersion("valkey", "7.2", "memorydb_valkey7"),
            new EngineVersion("valkey", "8.0", "memorydb_valkey8"));
    private static final Map<String, String> PARAMETER_DEFAULTS = Map.of(
            "maxmemory-policy", "noeviction", "maxmemory-samples", "3",
            "timeout", "0", "tcp-keepalive", "300", "activedefrag", "no");
    private static final String EVICTION_POLICIES =
            "volatile-lru,allkeys-lru,volatile-lfu,allkeys-lfu,volatile-random,allkeys-random,volatile-ttl,noeviction";

    public synchronized ParameterGroup createParameterGroup(String name, String family, String description,
                                                            Map<String, String> tags, String region) {
        validateGroupName(name);
        if (ENGINE_VERSIONS.stream().noneMatch(version -> version.family().equals(family))) {
            throw invalid("Unknown parameter group family: " + family);
        }
        if (parameterGroups.get(key(region, name)).isPresent()) {
            throw fault("ParameterGroupAlreadyExistsFault", "Parameter group already exists.");
        }
        ParameterGroup group = new ParameterGroup(name, family, description,
                buildArn(region, "parametergroup", name), new LinkedHashMap<>(), new LinkedHashMap<>(tags));
        parameterGroups.put(key(region, name), group);
        recordEvent(name, "parametergroup", "Parameter group created", region);
        return group;
    }

    public ParameterGroup getParameterGroup(String name, String region) {
        requireText(name, "ParameterGroupName");
        if (name.startsWith("default.")) {
            for (EngineVersion version : ENGINE_VERSIONS) {
                if (name.equals("default." + version.family().replace('_', '-'))) {
                    return new ParameterGroup(name, version.family(), "Default parameter group",
                            buildArn(region, "parametergroup", name), new LinkedHashMap<>(), new LinkedHashMap<>());
                }
            }
        }
        return parameterGroups.get(key(region, name)).orElseThrow(() ->
                fault("ParameterGroupNotFoundFault", "Parameter group not found."));
    }

    public List<ParameterGroup> describeParameterGroups(String name, String region) {
        if (name != null) {
            return List.of(getParameterGroup(name, region));
        }
        List<ParameterGroup> groups = new ArrayList<>(parameterGroups.scan(k -> k.startsWith(region + ":")));
        for (EngineVersion version : ENGINE_VERSIONS) {
            groups.add(getParameterGroup("default." + version.family().replace('_', '-'), region));
        }
        return groups.stream().sorted(Comparator.comparing(ParameterGroup::name)).toList();
    }

    public List<Parameter> describeParameters(String name, String region) {
        ParameterGroup group = getParameterGroup(name, region);
        return PARAMETER_DEFAULTS.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new Parameter(entry.getKey(),
                        group.parameters().getOrDefault(entry.getKey(), entry.getValue()),
                        Set.of("maxmemory-policy", "activedefrag").contains(entry.getKey()) ? "string" : "integer",
                        allowedValues(entry.getKey())))
                .toList();
    }

    public synchronized void updateParameterGroup(String name, Map<String, String> values, String region) {
        ParameterGroup group = mutableParameterGroup(name, region);
        if (values.isEmpty() || values.size() > 20) {
            throw invalid("Between 1 and 20 parameter values are required.");
        }
        values.forEach(this::validateParameter);
        Map<String, String> overrides = new LinkedHashMap<>(group.parameters());
        overrides.putAll(values);
        parameterGroups.put(key(region, name), new ParameterGroup(group.name(), group.family(),
                group.description(), group.arn(), overrides, group.tags()));
        recordEvent(name, "parametergroup", "Parameter group updated", region);
    }

    public synchronized void resetParameterGroup(String name, boolean all, List<String> names, String region) {
        ParameterGroup group = mutableParameterGroup(name, region);
        if ((all && !names.isEmpty()) || (!all && names.isEmpty())) {
            throw fault("InvalidParameterCombinationException", "Specify AllParameters or ParameterNames.");
        }
        for (String parameter : names) {
            if (!PARAMETER_DEFAULTS.containsKey(parameter)) {
                throw invalid("Unknown parameter: " + parameter);
            }
        }
        Map<String, String> overrides = new LinkedHashMap<>(group.parameters());
        if (all) {
            overrides.clear();
        } else {
            names.forEach(overrides::remove);
        }
        parameterGroups.put(key(region, name), new ParameterGroup(group.name(), group.family(),
                group.description(), group.arn(), overrides, group.tags()));
        recordEvent(name, "parametergroup", "Parameter group reset", region);
    }

    public synchronized ParameterGroup deleteParameterGroup(String name, String region) {
        ParameterGroup group = mutableParameterGroup(name, region);
        if (describeClusters(null, region).stream().anyMatch(cluster -> name.equals(cluster.getParameterGroupName()))) {
            throw fault("InvalidParameterGroupStateFault", "Parameter group is in use.");
        }
        parameterGroups.delete(key(region, name));
        recordEvent(name, "parametergroup", "Parameter group deleted", region);
        return group;
    }

    private ParameterGroup mutableParameterGroup(String name, String region) {
        ParameterGroup group = getParameterGroup(name, region);
        if (name.startsWith("default.")) {
            throw fault("InvalidParameterGroupStateFault", "Default parameter groups cannot be modified.");
        }
        return group;
    }

    private String allowedValues(String name) {
        return switch (name) {
            case "maxmemory-policy" -> EVICTION_POLICIES;
            case "activedefrag" -> "yes,no";
            case "maxmemory-samples" -> "1-10";
            case "timeout", "tcp-keepalive" -> "0-2147483647";
            default -> throw invalid("Unknown parameter: " + name);
        };
    }

    private void validateParameter(String name, String value) {
        if (name == null || !PARAMETER_DEFAULTS.containsKey(name) || value == null) {
            throw invalid("Unknown parameter or missing value: " + name);
        }
        boolean valid;
        if (name.equals("maxmemory-policy") || name.equals("activedefrag")) {
            valid = List.of(allowedValues(name).split(",")).contains(value);
        } else {
            try {
                int number = Integer.parseInt(value);
                valid = name.equals("maxmemory-samples") ? number >= 1 && number <= 10 : number >= 0;
            } catch (NumberFormatException exception) {
                valid = false;
            }
        }
        if (!valid) {
            throw invalid("Invalid value for parameter " + name);
        }
    }

    public synchronized SubnetGroup createSubnetGroup(String name, String description, List<String> subnetIds,
                                                      Map<String, String> tags, String region) {
        validateGroupName(name);
        if (subnetGroups.get(key(region, name)).isPresent()) {
            throw fault("SubnetGroupAlreadyExistsFault", "Subnet group already exists.");
        }
        List<Subnet> subnets = resolveSubnets(subnetIds, region);
        SubnetGroup group = new SubnetGroup(name, description, subnets.getFirst().getVpcId(),
                buildArn(region, "subnetgroup", name), subnetMetadata(subnets), new LinkedHashMap<>(tags));
        subnetGroups.put(key(region, name), group);
        recordEvent(name, "subnetgroup", "Subnet group created", region);
        return group;
    }

    public SubnetGroup getSubnetGroup(String name, String region) {
        requireText(name, "SubnetGroupName");
        return subnetGroups.get(key(region, name)).orElseThrow(() ->
                fault("SubnetGroupNotFoundFault", "Subnet group not found."));
    }

    public List<SubnetGroup> describeSubnetGroups(String name, String region) {
        return name != null ? List.of(getSubnetGroup(name, region))
                : subnetGroups.scan(k -> k.startsWith(region + ":")).stream()
                .sorted(Comparator.comparing(SubnetGroup::name)).toList();
    }

    public synchronized SubnetGroup updateSubnetGroup(String name, String description,
                                                      List<String> subnetIds, String region) {
        SubnetGroup old = getSubnetGroup(name, region);
        List<Subnet> subnets = subnetIds == null ? null : resolveSubnets(subnetIds, region);
        if (subnets != null && !old.vpcId().equals(subnets.getFirst().getVpcId())) {
            throw fault("InvalidSubnet", "Subnets must belong to the existing VPC.");
        }
        boolean inUse = describeClusters(null, region).stream()
                .anyMatch(cluster -> name.equals(cluster.getSubnetGroupName()));
        if (inUse && subnetIds != null && old.subnets().stream()
                .anyMatch(subnet -> !subnetIds.contains(subnet.identifier()))) {
            throw fault("SubnetInUse", "Cannot remove a subnet from an attached subnet group.");
        }
        SubnetGroup updated = new SubnetGroup(name, description == null ? old.description() : description,
                old.vpcId(), old.arn(), subnets == null ? old.subnets() : subnetMetadata(subnets), old.tags());
        subnetGroups.put(key(region, name), updated);
        recordEvent(name, "subnetgroup", "Subnet group updated", region);
        return updated;
    }

    public synchronized SubnetGroup deleteSubnetGroup(String name, String region) {
        SubnetGroup group = getSubnetGroup(name, region);
        if (describeClusters(null, region).stream().anyMatch(cluster -> name.equals(cluster.getSubnetGroupName()))) {
            throw fault("SubnetGroupInUseFault", "Subnet group is in use.");
        }
        subnetGroups.delete(key(region, name));
        recordEvent(name, "subnetgroup", "Subnet group deleted", region);
        return group;
    }

    private List<Subnet> resolveSubnets(List<String> ids, String region) {
        if (ids == null || ids.isEmpty()) {
            throw invalid("SubnetIds must not be empty.");
        }
        List<Subnet> result = new ArrayList<>();
        for (String id : ids.stream().distinct().toList()) {
            Subnet subnet;
            try {
                subnet = ec2Service.requireSubnet(region, id);
            } catch (AwsException exception) {
                if (!"InvalidSubnetID.NotFound".equals(exception.jsonType())) {
                    throw exception;
                }
                throw fault("InvalidSubnet", "Subnet " + id + " does not exist in this account and region.");
            }
            if (!region.equals(subnet.getRegion()) || !regionResolver.getAccountId().equals(subnet.getOwnerId())) {
                throw fault("InvalidSubnet", "Subnet is outside the request scope.");
            }
            if (!result.isEmpty() && !result.getFirst().getVpcId().equals(subnet.getVpcId())) {
                throw fault("InvalidSubnet", "All subnets must belong to one VPC.");
            }
            result.add(subnet);
        }
        return result;
    }

    private List<MemoryDbMetadata.Subnet> subnetMetadata(List<Subnet> subnets) {
        return subnets.stream().map(subnet -> new MemoryDbMetadata.Subnet(
                subnet.getSubnetId(), subnet.getAvailabilityZone())).toList();
    }

    public synchronized Snapshot createSnapshot(String clusterName, String name, Map<String, String> tags,
                                                 String region) {
        requireText(name, "SnapshotName");
        Cluster cluster = getCluster(clusterName, region);
        if (snapshots.get(key(region, name)).isPresent()) {
            throw fault("SnapshotAlreadyExistsFault", "Snapshot already exists.");
        }
        if (cluster.getStatus() != ClusterStatus.AVAILABLE || cluster.getContainerId() == null) {
            throw fault("InvalidClusterStateFault", "A running cluster backend is required to take a snapshot.");
        }
        if (cluster.getNumberOfShards() != 1) {
            throw invalid("Snapshots require a single-shard local cluster.");
        }
        byte[] data = containerManager.captureSnapshot(new MemoryDbContainerHandle(cluster.getContainerId(),
                identityName(cluster.getAccountId(), region, clusterName),
                cluster.getContainerHost(), cluster.getContainerPort()));
        if (data == null || data.length < 9
                || !new String(data, 0, 5, StandardCharsets.US_ASCII).equals("REDIS")) {
            throw fault("InvalidClusterStateFault", "The backend did not produce a valid RDB snapshot.");
        }
        Snapshot snapshot = new Snapshot(name, buildArn(region, "snapshot", name), clusterName,
                cluster.getNodeType(), cluster.getEngine(), cluster.getEngineVersion(),
                cluster.getNumberOfShards(), Instant.now().toEpochMilli() / 1000.0,
                data.clone(), new LinkedHashMap<>(tags));
        snapshots.put(key(region, name), snapshot);
        recordEvent(clusterName, "cluster", "Snapshot " + name + " created", region);
        return snapshot;
    }

    public Snapshot getSnapshot(String name, String region) {
        requireText(name, "SnapshotName");
        return snapshots.get(key(region, name)).orElseThrow(() ->
                fault("SnapshotNotFoundFault", "Snapshot not found."));
    }

    public List<Snapshot> describeSnapshots(String name, String clusterName, String source, String region) {
        if (source != null && !Set.of("manual", "automated").contains(source)) {
            throw invalid("Source must be manual or automated.");
        }
        Collection<Snapshot> values = name == null ? snapshots.scan(k -> k.startsWith(region + ":"))
                : List.of(getSnapshot(name, region));
        return values.stream().filter(snapshot -> clusterName == null || clusterName.equals(snapshot.clusterName()))
                .filter(snapshot -> source == null || source.equals("manual"))
                .sorted(Comparator.comparing(Snapshot::name)).toList();
    }

    public synchronized Snapshot copySnapshot(String sourceName, String targetName, Map<String, String> tags,
                                               String region) {
        Snapshot source = getSnapshot(sourceName, region);
        requireText(targetName, "TargetSnapshotName");
        if (snapshots.get(key(region, targetName)).isPresent()) {
            throw fault("SnapshotAlreadyExistsFault", "Snapshot already exists.");
        }
        if (source.data() == null || source.data().length == 0) {
            throw fault("InvalidSnapshotStateFault", "Snapshot data is unavailable.");
        }
        Snapshot copy = new Snapshot(targetName, buildArn(region, "snapshot", targetName), source.clusterName(),
                source.nodeType(), source.engine(), source.engineVersion(), source.numShards(), source.createdAt(),
                source.data().clone(), new LinkedHashMap<>(tags == null ? source.tags() : tags));
        snapshots.put(key(region, targetName), copy);
        return copy;
    }

    public synchronized Snapshot deleteSnapshot(String name, String region) {
        Snapshot snapshot = getSnapshot(name, region);
        snapshots.delete(key(region, name));
        return snapshot;
    }

    public List<EngineVersion> describeEngineVersions(String engine, String version, String family,
                                                     boolean defaultOnly) {
        List<EngineVersion> values = ENGINE_VERSIONS.stream()
                .filter(item -> engine == null || engine.equals(item.engine()))
                .filter(item -> version == null || version.equals(item.version()))
                .filter(item -> family == null || family.equals(item.family())).toList();
        if (!defaultOnly) {
            return values;
        }
        Map<String, EngineVersion> defaults = new LinkedHashMap<>();
        values.forEach(item -> defaults.put(item.engine(), item));
        return List.copyOf(defaults.values());
    }

    public List<Event> describeEvents(String name, String type, Double start, Double end,
                                      Integer duration, String region) {
        if (type != null && !Set.of("cluster", "parametergroup", "subnetgroup", "user", "acl").contains(type)) {
            throw invalid("Invalid SourceType.");
        }
        if (name != null && type == null) {
            throw fault("InvalidParameterCombinationException", "SourceType is required with SourceName.");
        }
        if (start != null && duration != null) {
            throw fault("InvalidParameterCombinationException", "StartTime and Duration cannot be combined.");
        }
        if (duration != null && (duration < 0 || duration > 20160)) {
            throw invalid("Duration must be between 0 and 20160 minutes.");
        }
        double until = end == null ? Instant.now().toEpochMilli() / 1000.0 : end;
        double from = start == null ? until - (duration == null ? 60 : duration) * 60.0 : start;
        if (from > until) {
            throw invalid("StartTime must precede EndTime.");
        }
        return events.scan(k -> k.startsWith(region + ":")).stream()
                .filter(event -> name == null || name.equals(event.sourceName()))
                .filter(event -> type == null || type.equals(event.sourceType()))
                .filter(event -> event.date() >= from && event.date() <= until)
                .sorted(Comparator.comparingDouble(Event::date).thenComparing(Event::sourceName)).toList();
    }

    public List<String> describeServiceUpdates(List<String> clusterNames, String updateName, List<String> statuses) {
        if (clusterNames.size() > 20) {
            throw invalid("At most 20 cluster names may be specified.");
        }
        if (statuses.stream().anyMatch(status -> !Set.of("available", "in-progress", "complete", "scheduled")
                .contains(status))) {
            throw invalid("Invalid service update status.");
        }
        // Floci has no managed service-update releases to apply to its local engines.
        return List.of();
    }

    public void batchUpdateCluster(List<String> names, String updateName) {
        if (names.isEmpty() || names.size() > 20) {
            throw invalid("Between 1 and 20 cluster names are required.");
        }
        if (updateName == null || updateName.isBlank()) {
            throw fault("InvalidParameterCombinationException", "No modifications were requested.");
        }
        throw fault("ServiceUpdateNotFoundFault", "Service update " + updateName + " not found.");
    }

    private void recordEvent(String name, String type, String message, String region) {
        events.put(key(region, UUID.randomUUID().toString()),
                new Event(name, type, message, Instant.now().toEpochMilli() / 1000.0));
    }

    public record Page<T>(List<T> values, String nextToken) {}

    public <T> Page<T> page(List<T> values, String scope, String region, Integer maxResults, String token) {
        int limit = maxResults == null ? 100 : maxResults;
        if (limit < 1 || limit > 100) {
            throw invalid("MaxResults must be between 1 and 100.");
        }
        String prefix = regionResolver.getAccountId() + ":" + region + ":" + scope + ":";
        int offset = 0;
        if (token != null) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
                if (!decoded.startsWith(prefix)) {
                    throw new IllegalArgumentException("Token scope mismatch");
                }
                offset = Integer.parseInt(decoded.substring(prefix.length()));
                if (offset < 0 || offset > values.size()) {
                    throw new IllegalArgumentException("Invalid token offset");
                }
            } catch (IllegalArgumentException exception) {
                throw invalid("Invalid NextToken.");
            }
        }
        int to = Math.min(values.size(), offset + limit);
        String next = to < values.size() ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                (prefix + to).getBytes(StandardCharsets.UTF_8)) : null;
        return new Page<>(values.subList(offset, to), next);
    }

    private void validateGroupName(String name) {
        requireText(name, "Name");
        if (!name.matches("[a-zA-Z][a-zA-Z0-9-]{0,39}") || name.endsWith("-") || name.contains("--")) {
            throw invalid("Name must start with a letter and contain up to 40 letters, digits or hyphens.");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required.");
        }
    }

    private AwsException invalid(String message) {
        return fault("InvalidParameterValueException", message);
    }

    private AwsException fault(String type, String message) {
        return new AwsException(type, message, 400);
    }

    // ──────────────────────────── Authentication ────────────────────────────

    /**
     * Validates a Redis AUTH attempt against the ACL the cluster references. A blank
     * username corresponds to the single-argument {@code AUTH <secret>} form and targets
     * the {@code default} user. The user's own auth mode decides how the secret is
     * checked: a password is compared against the user's passwords, an IAM token is
     * verified as a SigV4 presigned URL.
     */
    public boolean authenticate(String clusterName, String username, String secret) {
        return authenticate(clusterName, username, secret, currentRegion());
    }

    public boolean authenticate(String clusterName, String username, String secret, String region) {
        Cluster cluster = resourceGet(clusters, clusterName, region).orElse(null);
        if (cluster == null) {
            return false;
        }
        String aclName = cluster.getAclName();
        if (DEFAULT_ACL.equals(aclName)) {
            return true;
        }
        Acl acl = resourceGet(acls, aclName, region).orElse(null);
        if (acl == null) {
            return false;
        }
        String target = (username == null || username.isEmpty()) ? DEFAULT_USER : username;
        if (!acl.getUserNames().contains(target)) {
            return false;
        }
        User user = resolveUser(target, region);
        if (user == null) {
            return false;
        }
        return switch (user.getAuthMode()) {
            case IAM -> sigV4Validator.validate(secret, clusterName, user.getName());
            case PASSWORD -> user.getPasswords() != null && user.getPasswords().contains(secret);
            case NO_PASSWORD -> true;
        };
    }

    /**
     * True unless every connection may skip AUTH: only the open-access ACL (or a legacy ACL
     * whose members all use no-password) allows that. An empty ACL grants no access at all,
     * so it always demands a credential no user can present.
     */
    private boolean isAuthRequired(String aclName, String region) {
        if (DEFAULT_ACL.equals(aclName)) {
            return false;
        }
        Acl acl = resourceGet(acls, aclName, region).orElse(null);
        if (acl == null) {
            return false;
        }
        if (acl.getUserNames().isEmpty()) {
            return true;
        }
        return acl.getUserNames().stream()
                .map(name -> resolveUser(name, region))
                .filter(Objects::nonNull)
                .anyMatch(u -> u.getAuthMode() != AuthMode.NO_PASSWORD);
    }

    private void requireAclExists(String aclName, String region) {
        if (!DEFAULT_ACL.equals(aclName) && resourceGet(acls, aclName, region).isEmpty()) {
            throw new AwsException("ACLNotFoundFault", "ACL " + aclName + " not found.", 404);
        }
    }

    private boolean userExists(String name, String region) {
        return DEFAULT_USER.equals(name) || resourceGet(users, name, region).isPresent();
    }

    private User resolveUser(String name, String region) {
        return resourceGet(users, name, region)
                .orElseGet(() -> DEFAULT_USER.equals(name) ? builtinDefaultUser(region) : null);
    }

    private User builtinDefaultUser(String region) {
        User user = new User();
        user.setName(DEFAULT_USER);
        user.setAccountId(regionResolver.getAccountId());
        user.setRegion(region);
        user.setStatus(ACTIVE);
        user.setAuthMode(AuthMode.NO_PASSWORD);
        user.setAccessString("on ~* &* +@all");
        user.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        if (region != null) {
            user.setArn(buildArn(region, "user", DEFAULT_USER));
        }
        return user;
    }

    private Acl builtinOpenAccessAcl(String region) {
        Acl acl = new Acl();
        acl.setName(DEFAULT_ACL);
        acl.setAccountId(regionResolver.getAccountId());
        acl.setRegion(region);
        acl.setStatus(ACTIVE);
        acl.setUserNames(new ArrayList<>(List.of(DEFAULT_USER)));
        acl.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        if (region != null) {
            acl.setArn(buildArn(region, "acl", DEFAULT_ACL));
        }
        return acl;
    }

    // ──────────────────────────── Internals ────────────────────────────

    private record TagTarget(Map<String, String> tags, Runnable persist) {}

    private TagTarget tagTarget(String resourceArn, String region) {
        requireText(resourceArn, "ResourceArn");
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid ResourceArn.");
        }
        String[] resource = arn.resource().split("/", 2);
        if (!"memorydb".equals(arn.service()) || resource.length != 2 || resource[1].isBlank()) {
            throw invalid("Invalid MemoryDB ResourceArn.");
        }
        String type = resource[0];
        String name = resource[1];
        String notFound = switch (type) {
            case "cluster" -> "ClusterNotFoundFault";
            case "user" -> "UserNotFoundFault";
            case "acl" -> "ACLNotFoundFault";
            case "parametergroup" -> "ParameterGroupNotFoundFault";
            case "subnetgroup" -> "SubnetGroupNotFoundFault";
            case "snapshot" -> "SnapshotNotFoundFault";
            default -> throw invalid("Unsupported MemoryDB resource type.");
        };
        if (!resourceArn.equals(buildArn(region, type, name))) {
            throw fault(notFound, "Resource not found in this account and region.");
        }
        String resourceKey = key(region, name);
        return switch (type) {
            case "cluster" -> {
                Cluster cluster = resourceGet(clusters, name, region).orElseThrow(() -> fault(notFound, "Cluster not found."));
                yield new TagTarget(cluster.getTags(), () -> clusters.put(resourceKey, cluster));
            }
            case "user" -> {
                User user = resourceGet(users, name, region).orElseThrow(() -> fault(notFound, "User not found."));
                yield new TagTarget(user.getTags(), () -> users.put(resourceKey, user));
            }
            case "acl" -> {
                Acl acl = resourceGet(acls, name, region).orElseThrow(() -> fault(notFound, "ACL not found."));
                yield new TagTarget(acl.getTags(), () -> acls.put(resourceKey, acl));
            }
            case "parametergroup" -> {
                ParameterGroup group = getParameterGroup(name, region);
                yield new TagTarget(group.tags(), () -> {
                    mutableParameterGroup(name, region);
                    parameterGroups.put(resourceKey, group);
                });
            }
            case "subnetgroup" -> {
                SubnetGroup group = getSubnetGroup(name, region);
                yield new TagTarget(group.tags(), () -> subnetGroups.put(resourceKey, group));
            }
            case "snapshot" -> {
                Snapshot snapshot = getSnapshot(name, region);
                yield new TagTarget(snapshot.tags(), () -> snapshots.put(resourceKey, snapshot));
            }
            default -> throw invalid("Unsupported MemoryDB resource type.");
        };
    }

    private void startBackend(Cluster cluster, boolean authRequired) {
        String name = cluster.getName();
        String identity = identityName(cluster.getAccountId(), cluster.getRegion(), name);
        int proxyPort = allocateProxyPort();
        String image = config.services().memorydb().defaultImage();
        LOG.infov("Creating MemoryDB cluster {0} with authRequired={1} on proxy port {2}",
                name, String.valueOf(authRequired), String.valueOf(proxyPort));

        MemoryDbContainerHandle handle = null;
        try {
            // A cluster record is metadata: its name, endpoint host and proxy port are derived
            // from configuration and need no Docker, so the cluster is created and reaches
            // 'available' even when no daemon is reachable. Only connecting to the cache needs
            // the container.
            handle = containerManager.tryStart(identity, image);
            cluster.setClusterEndpoint(new Endpoint(
                    resolveEndpointHost(name, cluster.getAccountId(), cluster.getRegion()), proxyPort));
            cluster.setProxyPort(proxyPort);

            if (handle != null) {
                cluster.setContainerId(handle.getContainerId());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());

                proxyManager.startProxy(identity, authRequired, proxyPort,
                        handle.getHost(), handle.getPort(),
                        (username, secret) -> authenticate(name, username, secret, cluster.getRegion()));
            } else {
                LOG.warnv("MemoryDB cluster {0} created without a backing container: no Docker "
                        + "daemon is reachable. Metadata operations work; connections to the "
                        + "cluster do not until a daemon appears.", name);
            }
        } catch (RuntimeException e) {
            LOG.warnv("MemoryDB cluster {0} provisioning failed, rolling back: {1}", name, e.getMessage());
            rollbackBackend(identity, handle, proxyPort);
            throw e;
        }
    }

    private void rollbackBackend(String name, MemoryDbContainerHandle handle, int proxyPort) {
        try {
            try {
                // The proxy only starts after the container is ready, so a null handle means it
                // never started — nothing to stop.
                if (handle != null) {
                    proxyManager.stopProxy(name);
                }
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping proxy for MemoryDB cluster {0}: {1}", name, e.getMessage());
            }
            try {
                if (handle != null) {
                    // We have the exact handle from this request's start() call, so stop by it
                    // directly. Falling back to stopByClusterName here instead would look up
                    // whatever is currently registered for name, which could be a different
                    // container if an overlapping create for the same name raced ahead of this
                    // rollback.
                    containerManager.stop(handle);
                } else {
                    // No handle: a readiness timeout in containerManager.start() throws after the
                    // container was created and registered but before the handle is returned, so
                    // cleaning up by handle here isn't possible. stopByClusterName is idempotent,
                    // so it's safe when the container never started.
                    containerManager.stopByClusterName(name);
                }
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container for MemoryDB cluster {0}: {1}", name, e.getMessage());
            }
        } finally {
            releaseProxyPort(proxyPort);
        }
    }

    private String key(String region, String name) {
        return region + ":" + name;
    }

    private String currentRegion() {
        String region = regionResolver.getRegion();
        if (region != null) {
            return region;
        }
        String defaultRegion = regionResolver.getDefaultRegion();
        return defaultRegion != null ? defaultRegion : "us-east-1";
    }

    private String identityName(String accountId, String region, String name) {
        String defaultAccount = regionResolver.getDefaultAccountId();
        String defaultRegion = regionResolver.getDefaultRegion();
        if ((defaultAccount == null || Objects.equals(defaultAccount, accountId))
                && (defaultRegion == null || Objects.equals(defaultRegion, region))) {
            return name;
        }
        return accountId + "-" + region + "-" + name;
    }

    private <V> Optional<V> legacyGet(StorageBackend<String, V> store, String name, String region) {
        String defaultAccount = regionResolver.getDefaultAccountId();
        String defaultRegion = regionResolver.getDefaultRegion();
        if ((defaultAccount != null && !Objects.equals(defaultAccount, regionResolver.getAccountId()))
                || (defaultRegion != null && !Objects.equals(defaultRegion, region))) {
            return Optional.empty();
        }
        return store.get(name);
    }

    private <V> Optional<V> resourceGet(StorageBackend<String, V> store, String name, String region) {
        Optional<V> result = store.get(key(region, name));
        if (result.isPresent()) {
            return result;
        }
        Optional<V> legacy = legacyGet(store, name, region);
        if (legacy.isPresent()) {
            setOwner(legacy.get(), region);
            store.put(key(region, name), legacy.get());
            store.delete(name);
        }
        return legacy;
    }

    private void migrateLegacyClusters(String region) {
        migrateLegacy(clusters, region, Cluster.class);
    }

    private void migrateLegacyUsers(String region) {
        migrateLegacy(users, region, User.class);
    }

    private void migrateLegacyAcls(String region) {
        migrateLegacy(acls, region, Acl.class);
    }

    private <V> void migrateLegacy(StorageBackend<String, V> store, String region, Class<V> type) {
        if (!isDefaultOwner(region) || !(store instanceof AccountAwareStorageBackend<V> aware)) {
            return;
        }
        String accountId = regionResolver.getAccountId();
        for (V legacy : aware.scanUnscopedLegacy(value -> type.isInstance(value))) {
            String name = resourceName(legacy);
            if (name == null || name.isBlank()) {
                continue;
            }
            aware.getForAccountMigratingLegacyKeys(accountId, name, List.of(), value -> type.isInstance(value))
                    .ifPresent(value -> migrateLegacyValue(aware, accountId, value, region));
        }
        // A named lookup through AccountAwareStorageBackend may have already moved an old
        // unscoped record to account/name without adding the new region component. Move those
        // intermediate keys as well so unfiltered operations cannot omit them.
        for (String legacyKey : aware.keysForAccount(accountId)) {
            if (legacyKey.contains(":")) {
                continue;
            }
            aware.getForAccount(accountId, legacyKey)
                    .filter(type::isInstance)
                    .ifPresent(value -> migrateLegacyValue(aware, accountId, value, region));
        }
    }

    private <V> void migrateLegacyValue(AccountAwareStorageBackend<V> aware, String accountId,
                                         V legacy, String region) {
        String name = resourceName(legacy);
        if (name == null || name.isBlank()) {
            return;
        }
        setOwner(legacy, region);
        aware.putForAccount(accountId, key(region, name), legacy);
        aware.deleteForAccount(accountId, name);
    }

    private String resourceName(Object resource) {
        return switch (resource) {
            case Cluster cluster -> cluster.getName();
            case User user -> user.getName();
            case Acl acl -> acl.getName();
            default -> null;
        };
    }

    private boolean isDefaultOwner(String region) {
        String defaultAccount = regionResolver.getDefaultAccountId();
        String defaultRegion = regionResolver.getDefaultRegion();
        return (defaultAccount == null || Objects.equals(defaultAccount, regionResolver.getAccountId()))
                && (defaultRegion == null || Objects.equals(defaultRegion, region));
    }

    private void setOwner(Object resource, String region) {
        String accountId = regionResolver.getAccountId();
        if (resource instanceof Cluster cluster) {
            cluster.setAccountId(accountId);
            cluster.setRegion(region);
        } else if (resource instanceof User user) {
            user.setAccountId(accountId);
            user.setRegion(region);
        } else if (resource instanceof Acl acl) {
            acl.setAccountId(accountId);
            acl.setRegion(region);
        }
    }

    private String buildArn(String region, String resourceType, String name) {
        return regionResolver.buildArn("memorydb", region, resourceType + "/" + name);
    }

    /**
     * Cluster endpoint hostname in the AWS shape
     * {@code clustercfg.<cluster>.<id>.memorydb.<region>.amazonaws.com}, with the Floci wildcard
     * domain (or the configured Floci hostname) in place of {@code amazonaws.com}. The embedded
     * DNS resolves it to Floci for containers and public DNS resolves the default domain to
     * loopback on the host. A configured IP address cannot carry a prefix and is used as is.
     */
    String resolveEndpointHost(String clusterName, String accountId, String region) {
        String suffix = config.hostname()
                .filter(h -> !h.isBlank() && !"localhost".equalsIgnoreCase(h))
                .orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
        if (IP_LITERAL.matcher(suffix).matches()) {
            return suffix;
        }
        return "clustercfg." + clusterName.toLowerCase(Locale.ROOT) + "." + endpointId(accountId, region)
                + ".memorydb." + region + "." + suffix;
    }

    // AWS gives every account and region a stable six-character endpoint label.
    private static String endpointId(String accountId, String region) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("memorydb:" + accountId + ":" + region).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private int allocateProxyPort() {
        int base = config.services().memorydb().proxyBasePort();
        int max = config.services().memorydb().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        throw new AwsException("InsufficientClusterCapacityFault",
                "No available proxy ports in range " + base + "-" + max, 503);
    }

    private void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }
}
