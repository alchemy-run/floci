package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
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
import io.github.hectorvent.floci.services.memorydb.model.EngineVersion;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbEvent;
import io.github.hectorvent.floci.services.memorydb.model.ParameterGroup;
import io.github.hectorvent.floci.services.memorydb.model.Snapshot;
import io.github.hectorvent.floci.services.memorydb.model.SubnetGroup;
import io.github.hectorvent.floci.services.memorydb.model.User;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbProxyManager;
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
import java.util.concurrent.ConcurrentLinkedDeque;

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
    private static final String SNAPSHOT_AVAILABLE = "available";
    private static final String SNAPSHOT_SOURCE_MANUAL = "manual";
    private static final int REDIS_PORT = 6379;
    private static final int MAX_EVENTS = 100;

    private static final List<EngineVersion> ENGINE_VERSIONS = List.of(
            new EngineVersion("valkey", "8.1", "8.1.1", "memorydb_valkey8", true),
            new EngineVersion("valkey", "8.0", "8.0.1", "memorydb_valkey8", false),
            new EngineVersion("valkey", "7.3", "7.3.2", "memorydb_valkey7", false),
            new EngineVersion("valkey", "7.2", "7.2.6", "memorydb_valkey7", false),
            new EngineVersion("redis", "7.1", "7.1.1", "memorydb_redis7", true),
            new EngineVersion("redis", "7.0", "7.0.7", "memorydb_redis7", false),
            new EngineVersion("redis", "6.2", "6.2.6", "memorydb_redis6", false)
    );

    // Per the MemoryDB API: a user name must start with a letter and contain only
    // letters, digits and hyphens.
    private static final java.util.regex.Pattern USER_NAME_PATTERN =
            java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9\\-]*");

    // Engine defaults for MemoryDB parameter families. Alchemy's ParameterGroup
    // test asserts maxmemory-policy resets to "noeviction".
    static final Map<String, String> FAMILY_DEFAULTS = Map.of(
            "maxmemory-policy", "noeviction",
            "activedefrag", "no",
            "timeout", "0",
            "tcp-keepalive", "300",
            "maxmemory-samples", "5");

    private final StorageBackend<String, Cluster> clusters;
    private final StorageBackend<String, User> users;
    private final StorageBackend<String, Acl> acls;
    private final StorageBackend<String, SubnetGroup> subnetGroups;
    private final StorageBackend<String, ParameterGroup> parameterGroups;
    private final StorageBackend<String, Snapshot> snapshots;
    private final ConcurrentLinkedDeque<MemoryDbEvent> events = new ConcurrentLinkedDeque<>();
    private final MemoryDbContainerManager containerManager;
    private final MemoryDbProxyManager proxyManager;
    private final SigV4Validator sigV4Validator;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Ec2Service ec2Service;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    private final Set<String> provisioningClusterNames = ConcurrentHashMap.newKeySet();

    public MemoryDbService(MemoryDbContainerManager containerManager,
                           MemoryDbProxyManager proxyManager,
                           SigV4Validator sigV4Validator,
                           StorageFactory storageFactory,
                           EmulatorConfig config,
                           RegionResolver regionResolver) {
        this(containerManager, proxyManager, sigV4Validator, storageFactory, config, regionResolver, null);
    }

    @Inject
    public MemoryDbService(MemoryDbContainerManager containerManager,
                           MemoryDbProxyManager proxyManager,
                           SigV4Validator sigV4Validator,
                           StorageFactory storageFactory,
                           EmulatorConfig config,
                           RegionResolver regionResolver,
                           Ec2Service ec2Service) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.sigV4Validator = sigV4Validator;
        this.config = config;
        this.regionResolver = regionResolver;
        this.ec2Service = ec2Service;
        this.clusters = storageFactory.create("memorydb", "memorydb-clusters.json",
                new TypeReference<Map<String, Cluster>>() {});
        this.users = storageFactory.create("memorydb", "memorydb-users.json",
                new TypeReference<Map<String, User>>() {});
        this.acls = storageFactory.create("memorydb", "memorydb-acls.json",
                new TypeReference<Map<String, Acl>>() {});
        this.subnetGroups = storageFactory.create("memorydb", "memorydb-subnet-groups.json",
                new TypeReference<Map<String, SubnetGroup>>() {});
        this.parameterGroups = storageFactory.create("memorydb", "memorydb-parameter-groups.json",
                new TypeReference<Map<String, ParameterGroup>>() {});
        this.snapshots = storageFactory.create("memorydb", "memorydb-snapshots.json",
                new TypeReference<Map<String, Snapshot>>() {});
    }

    // ──────────────────────────── Clusters ────────────────────────────

    public Cluster createCluster(Cluster spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ClusterName is required.", 400);
        }
        if (clusters.get(name).isPresent()) {
            throw new AwsException("ClusterAlreadyExistsFault",
                    "Cluster with specified name already exists.", 400);
        }
        // Claim the name for the whole provisioning attempt so a concurrent create can't race
        // ahead and be stopped by this request's handle-less rollback fallback.
        if (!provisioningClusterNames.add(name)) {
            throw new AwsException("ClusterAlreadyExistsFault",
                    "Cluster " + name + " is already being created.", 400);
        }

        try {
            String aclName = spec.getAclName();
            if (aclName == null || aclName.isBlank()) {
                throw new AwsException("InvalidParameterValueException", "ACLName is required.", 400);
            }
            requireAclExists(aclName);
            if (spec.getSubnetGroupName() != null && !spec.getSubnetGroupName().isBlank()) {
                requireSubnetGroup(spec.getSubnetGroupName());
            }
            boolean authRequired = isAuthRequired(aclName);

            Cluster cluster = new Cluster();
            cluster.setName(name);
            cluster.setDescription(spec.getDescription());
            cluster.setStatus(ClusterStatus.AVAILABLE);
            cluster.setNodeType(spec.getNodeType() != null ? spec.getNodeType() : "db.t4g.small");
            cluster.setNumberOfShards(spec.getNumberOfShards() > 0 ? spec.getNumberOfShards() : 1);
            cluster.setNumReplicasPerShard(spec.getNumReplicasPerShard());
            cluster.setEngine(spec.getEngine() != null ? spec.getEngine() : DEFAULT_ENGINE);
            cluster.setEngineVersion(spec.getEngineVersion() != null ? spec.getEngineVersion() : DEFAULT_ENGINE_VERSION);
            cluster.setAclName(aclName);
            cluster.setSubnetGroupName(spec.getSubnetGroupName());
            cluster.setSecurityGroupIds(spec.hasSecurityGroupIds() ? spec.getSecurityGroupIds() : null);
            cluster.setParameterGroupName(spec.getParameterGroupName());
            cluster.setTlsEnabled(spec.isTlsEnabled());
            cluster.setArn(buildArn(region, "cluster", name));
            cluster.setCreatedAt(Instant.now());
            cluster.setTags(spec.getTags());

            if (config.services().memorydb().mock()) {
                LOG.infov("Creating MemoryDB cluster {0} in mock mode (no container)", name);
                cluster.setClusterEndpoint(new Endpoint(clusterEndpointAddress(name, region), REDIS_PORT));
            } else {
                startBackend(cluster, authRequired);
            }

            clusters.put(name, cluster);
            LOG.infov("MemoryDB cluster {0} created (acl={1}, authRequired={2}), endpoint={3}:{4}",
                    name, aclName, String.valueOf(authRequired), cluster.getClusterEndpoint().address(),
                    String.valueOf(cluster.getClusterEndpoint().port()));
            return cluster;
        } finally {
            provisioningClusterNames.remove(name);
        }
    }

    public Cluster getCluster(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ClusterName is required.", 400);
        }
        return clusters.get(name).orElseThrow(() ->
                new AwsException("ClusterNotFoundFault", "Cluster not found.", 404));
    }

    public Collection<Cluster> describeClusters(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return clusters.get(filterName)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("ClusterNotFoundFault",
                            "Cluster not found.", 404));
        }
        return clusters.scan(k -> true);
    }

    public Cluster updateCluster(String name, String description) {
        Cluster patch = new Cluster();
        patch.setName(name);
        patch.setDescription(description);
        return updateCluster(patch);
    }

    public Cluster updateCluster(Cluster patch) {
        Cluster cluster = getCluster(patch.getName());
        if (patch.getDescription() != null) {
            cluster.setDescription(patch.getDescription());
        }
        if (patch.hasSecurityGroupIds()) {
            cluster.setSecurityGroupIds(patch.getSecurityGroupIds());
        }
        if (patch.getAclName() != null && !patch.getAclName().isBlank()) {
            requireAclExists(patch.getAclName());
            cluster.setAclName(patch.getAclName());
        }
        if (patch.getNodeType() != null && !patch.getNodeType().isBlank()) {
            cluster.setNodeType(patch.getNodeType());
        }
        if (patch.getEngineVersion() != null && !patch.getEngineVersion().isBlank()) {
            cluster.setEngineVersion(patch.getEngineVersion());
        }
        if (patch.getParameterGroupName() != null && !patch.getParameterGroupName().isBlank()) {
            cluster.setParameterGroupName(patch.getParameterGroupName());
        }
        if (patch.getNumberOfShards() > 0) {
            cluster.setNumberOfShards(patch.getNumberOfShards());
        }
        clusters.put(cluster.getName(), cluster);
        return cluster;
    }

    public Cluster deleteCluster(String name) {
        Cluster cluster = getCluster(name);
        cluster.setStatus(ClusterStatus.DELETING);

        proxyManager.stopProxy(name);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new MemoryDbContainerHandle(
                    cluster.getContainerId(), name, cluster.getContainerHost(), cluster.getContainerPort()));
        }

        releaseProxyPort(cluster.getProxyPort());
        clusters.delete(name);
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
        if (DEFAULT_USER.equals(name) || users.get(name).isPresent()) {
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
        user.setStatus(ACTIVE);
        user.setAuthMode(spec.getAuthMode());
        user.setPasswords(spec.getPasswords());
        user.setAccessString(spec.getAccessString());
        user.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        user.setArn(buildArn(region, "user", name));
        user.setCreatedAt(Instant.now());
        user.setTags(spec.getTags());

        users.put(name, user);
        LOG.infov("MemoryDB user {0} created with authMode={1}", name, user.getAuthMode());
        return user;
    }

    public Collection<User> describeUsers(String filterName, String region) {
        if (filterName != null && !filterName.isBlank()) {
            return users.get(filterName)
                    .map(List::of)
                    .or(() -> DEFAULT_USER.equals(filterName)
                            ? java.util.Optional.of(List.of(builtinDefaultUser(region)))
                            : java.util.Optional.empty())
                    .orElseThrow(() -> new AwsException("UserNotFoundFault", "User not found.", 404));
        }
        List<User> all = new ArrayList<>();
        all.add(builtinDefaultUser(region));
        all.addAll(users.scan(k -> true));
        return all;
    }

    /**
     * Apply {@code AccessString} and/or {@code AuthenticationMode}. Live AWS is
     * asynchronous ({@code modifying} then {@code active}); the emulator converges
     * immediately so Alchemy's wait-until-active loop does not stall.
     */
    public User updateUser(String name, String accessString, AuthMode authMode, List<String> passwords) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "UserName is required.", 400);
        }
        if (DEFAULT_USER.equals(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "The default user cannot be modified.", 400);
        }
        User user = users.get(name).orElseThrow(() ->
                new AwsException("UserNotFoundFault", "User not found.", 404));
        boolean hasAccess = accessString != null && !accessString.isBlank();
        boolean hasAuth = authMode != null;
        if (!hasAccess && !hasAuth) {
            throw new AwsException("InvalidParameterCombinationException",
                    "No modifications were requested.", 400);
        }
        if (hasAccess) {
            user.setAccessString(accessString);
        }
        if (hasAuth) {
            if (authMode == AuthMode.NO_PASSWORD) {
                throw new AwsException("InvalidParameterValueException",
                        "AuthenticationMode Type must be 'password' or 'iam'.", 400);
            }
            if (authMode == AuthMode.PASSWORD
                    && (passwords == null || passwords.isEmpty())
                    && (user.getPasswords() == null || user.getPasswords().isEmpty())) {
                throw new AwsException("InvalidParameterValueException",
                        "At least one password is required for password authentication.", 400);
            }
            user.setAuthMode(authMode);
            if (passwords != null && !passwords.isEmpty()) {
                user.setPasswords(passwords);
            }
        }
        users.put(name, user);
        LOG.infov("MemoryDB user {0} updated (access={1}, authMode={2})",
                name, String.valueOf(hasAccess), user.getAuthMode());
        return user;
    }

    public User deleteUser(String name) {
        if (DEFAULT_USER.equals(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "The default user cannot be deleted.", 400);
        }
        User user = users.get(name).orElseThrow(() ->
                new AwsException("UserNotFoundFault", "User not found.", 404));
        users.delete(name);
        LOG.infov("MemoryDB user {0} deleted", name);
        return user;
    }

    // ──────────────────────────── ACLs ────────────────────────────

    public Acl createAcl(Acl spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ACLName is required.", 400);
        }
        if (DEFAULT_ACL.equals(name) || acls.get(name).isPresent()) {
            throw new AwsException("ACLAlreadyExistsFault",
                    "ACL with specified name already exists.", 400);
        }
        if (!USER_NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValueException",
                    "ACLName must start with a letter and contain only letters, digits and hyphens.", 400);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String userName : spec.getUserNames()) {
            rejectDefaultUserOnCustomAcl(userName);
            if (!seen.add(userName)) {
                throw new AwsException("DuplicateUserNameFault",
                        "Duplicate user name " + userName + " in ACL.", 400);
            }
            if (!userExists(userName)) {
                throw new AwsException("UserNotFoundFault", "User " + userName + " not found.", 404);
            }
        }

        Acl acl = new Acl();
        acl.setName(name);
        acl.setStatus(ACTIVE);
        acl.setUserNames(new ArrayList<>(spec.getUserNames()));
        acl.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        acl.setArn(buildArn(region, "acl", name));
        acl.setCreatedAt(Instant.now());
        acl.setTags(spec.getTags());

        acls.put(name, acl);
        LOG.infov("MemoryDB ACL {0} created with users={1}", name, acl.getUserNames());
        return acl;
    }

    /**
     * Apply {@code UserNamesToAdd} / {@code UserNamesToRemove}. The reserved
     * {@code default} user cannot be added to a custom ACL.
     */
    public Acl updateAcl(String name, List<String> userNamesToAdd, List<String> userNamesToRemove) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ACLName is required.", 400);
        }
        if (DEFAULT_ACL.equals(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "The open-access ACL cannot be modified.", 400);
        }
        Acl acl = acls.get(name).orElseThrow(() ->
                new AwsException("ACLNotFoundFault", "ACL not found.", 404));
        List<String> users = new ArrayList<>(acl.getUserNames());
        if (userNamesToRemove != null) {
            users.removeAll(userNamesToRemove);
        }
        if (userNamesToAdd != null) {
            java.util.Set<String> seen = new java.util.HashSet<>(users);
            for (String userName : userNamesToAdd) {
                rejectDefaultUserOnCustomAcl(userName);
                if (!seen.add(userName)) {
                    throw new AwsException("DuplicateUserNameFault",
                            "Duplicate user name " + userName + " in ACL.", 400);
                }
                if (!userExists(userName)) {
                    throw new AwsException("UserNotFoundFault", "User " + userName + " not found.", 404);
                }
                users.add(userName);
            }
        }
        acl.setUserNames(users);
        acls.put(name, acl);
        LOG.infov("MemoryDB ACL {0} updated with users={1}", name, acl.getUserNames());
        return acl;
    }

    public Collection<Acl> describeAcls(String filterName, String region) {
        if (filterName != null && !filterName.isBlank()) {
            return acls.get(filterName)
                    .map(List::of)
                    .or(() -> DEFAULT_ACL.equals(filterName)
                            ? java.util.Optional.of(List.of(builtinOpenAccessAcl(region)))
                            : java.util.Optional.empty())
                    .orElseThrow(() -> new AwsException("ACLNotFoundFault", "ACL not found.", 404));
        }
        List<Acl> all = new ArrayList<>();
        all.add(builtinOpenAccessAcl(region));
        all.addAll(acls.scan(k -> true));
        return all;
    }

    public Acl deleteAcl(String name) {
        if (DEFAULT_ACL.equals(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "The open-access ACL cannot be deleted.", 400);
        }
        Acl acl = acls.get(name).orElseThrow(() ->
                new AwsException("ACLNotFoundFault", "ACL not found.", 404));
        if (!clustersUsingAcl(name).isEmpty()) {
            throw new AwsException("InvalidACLStateFault",
                    "ACL " + name + " is associated with one or more clusters.", 400);
        }
        acls.delete(name);
        LOG.infov("MemoryDB ACL {0} deleted", name);
        return acl;
    }

    /** Names of ACLs that include the given user; used to populate the user response. */
    public List<String> aclNamesForUser(String userName) {
        List<String> result = new ArrayList<>();
        if (DEFAULT_USER.equals(userName)) {
            result.add(DEFAULT_ACL);
        }
        acls.scan(k -> true).stream()
                .filter(a -> a.getUserNames().contains(userName))
                .map(Acl::getName)
                .forEach(result::add);
        return result;
    }

    /** Names of clusters currently referencing the given ACL; used to populate the ACL response. */
    public List<String> clustersUsingAcl(String aclName) {
        return clusters.scan(k -> true).stream()
                .filter(c -> aclName.equals(c.getAclName()))
                .map(Cluster::getName)
                .toList();
    }

    // ──────────────────────────── Parameter groups ────────────────────────────

    public ParameterGroup createParameterGroup(ParameterGroup spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ParameterGroupName is required.", 400);
        }
        if (spec.getFamily() == null || spec.getFamily().isBlank()) {
            throw new AwsException("InvalidParameterValueException", "Family is required.", 400);
        }
        if (parameterGroups.get(name).isPresent()) {
            throw new AwsException("ParameterGroupAlreadyExistsFault",
                    "Parameter group with specified name already exists.", 400);
        }
        ParameterGroup group = new ParameterGroup();
        group.setName(name);
        group.setFamily(spec.getFamily());
        group.setDescription(spec.getDescription());
        group.setArn(buildArn(region, "parametergroup", name));
        group.setCreatedAt(Instant.now());
        group.setParameters(new LinkedHashMap<>(FAMILY_DEFAULTS));
        group.setTags(spec.getTags());
        parameterGroups.put(name, group);
        LOG.infov("MemoryDB parameter group {0} created (family={1})", name, group.getFamily());
        return group;
    }

    public Collection<ParameterGroup> describeParameterGroups(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return List.of(requireParameterGroup(filterName));
        }
        return parameterGroups.scan(k -> true);
    }

    public Map<String, String> describeParameters(String name) {
        ParameterGroup group = requireParameterGroup(name);
        Map<String, String> values = new LinkedHashMap<>(FAMILY_DEFAULTS);
        values.putAll(group.getParameters());
        return values;
    }

    public ParameterGroup updateParameterGroup(String name, Map<String, String> updates) {
        ParameterGroup group = requireParameterGroup(name);
        if (updates != null) {
            for (Map.Entry<String, String> entry : updates.entrySet()) {
                if (!FAMILY_DEFAULTS.containsKey(entry.getKey())) {
                    throw new AwsException("InvalidParameterValueException",
                            "Unknown parameter " + entry.getKey() + ".", 400);
                }
                group.getParameters().put(entry.getKey(), entry.getValue());
            }
        }
        parameterGroups.put(name, group);
        return group;
    }

    public ParameterGroup resetParameterGroup(String name, boolean allParameters, List<String> parameterNames) {
        ParameterGroup group = requireParameterGroup(name);
        if (allParameters) {
            group.setParameters(new LinkedHashMap<>(FAMILY_DEFAULTS));
        } else {
            if (parameterNames == null || parameterNames.isEmpty()) {
                throw new AwsException("InvalidParameterCombinationException",
                        "ParameterNames is required when AllParameters is not true.", 400);
            }
            for (String parameterName : parameterNames) {
                if (!FAMILY_DEFAULTS.containsKey(parameterName)) {
                    throw new AwsException("InvalidParameterValueException",
                            "Unknown parameter " + parameterName + ".", 400);
                }
                group.getParameters().put(parameterName, FAMILY_DEFAULTS.get(parameterName));
            }
        }
        parameterGroups.put(name, group);
        return group;
    }

    public ParameterGroup deleteParameterGroup(String name) {
        ParameterGroup group = requireParameterGroup(name);
        if (name.startsWith("default.")) {
            throw new AwsException("InvalidParameterGroupStateFault",
                    "The default parameter group cannot be deleted.", 400);
        }
        parameterGroups.delete(name);
        LOG.infov("MemoryDB parameter group {0} deleted", name);
        return group;
    }

    private ParameterGroup requireParameterGroup(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ParameterGroupName is required.", 400);
        }
        return parameterGroups.get(name).orElseThrow(() ->
                new AwsException("ParameterGroupNotFoundFault",
                        "Parameter group " + name + " not found.", 404));
    }

    // ──────────────────────────── Subnet groups ────────────────────────────

    public SubnetGroup createSubnetGroup(SubnetGroup spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "SubnetGroupName is required.", 400);
        }
        if (subnetGroups.get(name).isPresent()) {
            throw new AwsException("SubnetGroupAlreadyExistsFault",
                    "Subnet group with specified name already exists.", 400);
        }
        List<String> subnetIds = spec.getSubnets().stream()
                .map(SubnetGroup.SubnetRef::getIdentifier)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (subnetIds.isEmpty()) {
            throw new AwsException("InvalidParameterValueException", "SubnetIds is required.", 400);
        }
        SubnetGroup group = buildSubnetGroup(name, spec.getDescription(), subnetIds, region);
        group.setTags(spec.getTags());
        subnetGroups.put(name, group);
        LOG.infov("MemoryDB subnet group {0} created", name);
        return group;
    }

    public Collection<SubnetGroup> describeSubnetGroups(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return List.of(requireSubnetGroup(filterName));
        }
        return subnetGroups.scan(k -> true);
    }

    public SubnetGroup updateSubnetGroup(String name, String description, List<String> subnetIds, String region) {
        SubnetGroup existing = requireSubnetGroup(name);
        String effectiveDescription = description != null ? description : existing.getDescription();
        List<String> effectiveSubnetIds = subnetIds;
        if (effectiveSubnetIds == null || effectiveSubnetIds.isEmpty()) {
            effectiveSubnetIds = existing.getSubnets().stream()
                    .map(SubnetGroup.SubnetRef::getIdentifier)
                    .toList();
        }
        SubnetGroup updated = buildSubnetGroup(name, effectiveDescription, effectiveSubnetIds, region);
        updated.setTags(existing.getTags());
        subnetGroups.put(name, updated);
        return updated;
    }

    public SubnetGroup deleteSubnetGroup(String name) {
        SubnetGroup group = requireSubnetGroup(name);
        boolean inUse = clusters.scan(k -> true).stream()
                .anyMatch(c -> name.equals(c.getSubnetGroupName()));
        if (inUse) {
            throw new AwsException("SubnetGroupInUseFault",
                    "Subnet group " + name + " is associated with one or more clusters.", 400);
        }
        subnetGroups.delete(name);
        LOG.infov("MemoryDB subnet group {0} deleted", name);
        return group;
    }

    private SubnetGroup requireSubnetGroup(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "SubnetGroupName is required.", 400);
        }
        return subnetGroups.get(name).orElseThrow(() ->
                new AwsException("SubnetGroupNotFoundFault",
                        "Subnet group " + name + " not found.", 404));
    }

    private SubnetGroup buildSubnetGroup(String name, String description, List<String> subnetIds, String region) {
        String effectiveRegion = region == null || region.isBlank()
                ? regionResolver.getDefaultRegion() : region;
        List<SubnetGroup.SubnetRef> members = new ArrayList<>();
        String vpcId = null;
        int azIndex = 0;
        for (String subnetId : subnetIds) {
            if (subnetId == null || subnetId.isBlank()) {
                throw new AwsException("InvalidSubnet", "SubnetIds contains an empty subnet id.", 400);
            }
            Subnet subnet = ec2Service != null
                    ? ec2Service.findSubnetById(effectiveRegion, subnetId).orElse(null)
                    : null;
            String az;
            String subnetVpc;
            if (subnet != null) {
                az = subnet.getAvailabilityZone();
                subnetVpc = subnet.getVpcId();
            } else if (ec2Service != null) {
                throw new AwsException("InvalidSubnet",
                        "One or more subnets for subnet group " + name + " do not exist.", 400);
            } else {
                az = effectiveRegion + (char) ('a' + Math.min(azIndex, 25));
                subnetVpc = "vpc-floci";
            }
            if (vpcId == null) {
                vpcId = subnetVpc;
            } else if (!vpcId.equals(subnetVpc)) {
                throw new AwsException("InvalidSubnet", "All subnets must belong to the same VPC.", 400);
            }
            members.add(new SubnetGroup.SubnetRef(subnetId, az));
            azIndex++;
        }
        SubnetGroup group = new SubnetGroup();
        group.setName(name);
        group.setDescription(description);
        group.setVpcId(vpcId);
        group.setSubnets(members);
        group.setArn(buildArn(effectiveRegion, "subnetgroup", name));
        group.setCreatedAt(Instant.now());
        return group;
    }

    // ──────────────────────────── Snapshots / events / catalog ────────────────────────────

    public Collection<Snapshot> describeSnapshots(String clusterName, String snapshotName) {
        if (snapshotName != null && !snapshotName.isBlank()) {
            Snapshot snapshot = snapshots.get(snapshotName).orElseThrow(() ->
                    new AwsException("SnapshotNotFoundFault",
                            "Snapshot " + snapshotName + " not found.", 404));
            if (clusterName != null && !clusterName.isBlank()
                    && !clusterName.equals(snapshot.getClusterName())) {
                throw new AwsException("SnapshotNotFoundFault",
                        "Snapshot " + snapshotName + " not found.", 404);
            }
            return List.of(snapshot);
        }
        Collection<Snapshot> all = snapshots.scan(k -> true);
        if (clusterName == null || clusterName.isBlank()) {
            return all;
        }
        return all.stream().filter(s -> clusterName.equals(s.getClusterName())).toList();
    }

    public Snapshot deleteSnapshot(String snapshotName) {
        if (snapshotName == null || snapshotName.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "SnapshotName is required.", 400);
        }
        Snapshot snapshot = snapshots.get(snapshotName).orElseThrow(() ->
                new AwsException("SnapshotNotFoundFault",
                        "Snapshot " + snapshotName + " not found.", 404));
        snapshots.delete(snapshotName);
        snapshot.setStatus("deleting");
        return snapshot;
    }

    public Snapshot copySnapshot(String sourceSnapshotName, String targetSnapshotName,
                                 String kmsKeyId, String region) {
        if (sourceSnapshotName == null || sourceSnapshotName.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "SourceSnapshotName is required.", 400);
        }
        if (targetSnapshotName == null || targetSnapshotName.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "TargetSnapshotName is required.", 400);
        }
        Snapshot source = snapshots.get(sourceSnapshotName).orElseThrow(() ->
                new AwsException("SnapshotNotFoundFault",
                        "Snapshot " + sourceSnapshotName + " not found.", 404));
        if (snapshots.get(targetSnapshotName).isPresent()) {
            throw new AwsException("SnapshotAlreadyExistsFault",
                    "Snapshot " + targetSnapshotName + " already exists.", 400);
        }
        Snapshot copy = new Snapshot();
        copy.setName(targetSnapshotName);
        copy.setStatus(SNAPSHOT_AVAILABLE);
        copy.setSource(SNAPSHOT_SOURCE_MANUAL);
        copy.setKmsKeyId(kmsKeyId != null ? kmsKeyId : source.getKmsKeyId());
        copy.setArn(buildArn(region, "snapshot", targetSnapshotName));
        copy.setClusterName(source.getClusterName());
        copy.setClusterDescription(source.getClusterDescription());
        copy.setNodeType(source.getNodeType());
        copy.setEngine(source.getEngine());
        copy.setEngineVersion(source.getEngineVersion());
        copy.setNumberOfShards(source.getNumberOfShards());
        copy.setCreatedAt(Instant.now());
        snapshots.put(targetSnapshotName, copy);
        return copy;
    }

    public List<MemoryDbEvent> describeEvents(String sourceName, String sourceType) {
        List<MemoryDbEvent> result = new ArrayList<>();
        for (MemoryDbEvent event : events) {
            if (sourceName != null && !sourceName.isBlank() && !sourceName.equals(event.getSourceName())) {
                continue;
            }
            if (sourceType != null && !sourceType.isBlank()
                    && !sourceType.equalsIgnoreCase(event.getSourceType())) {
                continue;
            }
            result.add(event);
        }
        return result;
    }

    public List<EngineVersion> describeEngineVersions(String engine, String engineVersion,
                                                      String parameterGroupFamily, boolean defaultOnly) {
        return ENGINE_VERSIONS.stream()
                .filter(v -> engine == null || engine.isBlank() || engine.equalsIgnoreCase(v.getEngine()))
                .filter(v -> engineVersion == null || engineVersion.isBlank()
                        || engineVersion.equals(v.getEngineVersion()))
                .filter(v -> parameterGroupFamily == null || parameterGroupFamily.isBlank()
                        || parameterGroupFamily.equals(v.getParameterGroupFamily()))
                .filter(v -> !defaultOnly || v.isDefaultVersion())
                .toList();
    }

    public List<Map<String, String>> describeServiceUpdates() {
        return List.of();
    }

    public Map<String, Object> batchUpdateCluster(List<String> clusterNames, String serviceUpdateNameToApply) {
        if (serviceUpdateNameToApply == null || serviceUpdateNameToApply.isBlank()) {
            throw new AwsException("InvalidParameterCombinationException",
                    "No modifications were requested.", 400);
        }
        throw new AwsException("ServiceUpdateNotFoundFault",
                "Service Update " + serviceUpdateNameToApply + " not found.", 404);
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTags(String resourceArn) {
        return tagged(resourceArn).tags();
    }

    public Map<String, String> tagResource(String resourceArn, Map<String, String> tags) {
        Tagged tagged = tagged(resourceArn);
        tagged.tags().putAll(tags);
        tagged.persist().run();
        return tagged.tags();
    }

    public Map<String, String> untagResource(String resourceArn, List<String> tagKeys) {
        Tagged tagged = tagged(resourceArn);
        tagKeys.forEach(tagged.tags()::remove);
        tagged.persist().run();
        return tagged.tags();
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
        Cluster cluster = clusters.get(clusterName).orElse(null);
        if (cluster == null) {
            return false;
        }
        String aclName = cluster.getAclName();
        if (DEFAULT_ACL.equals(aclName)) {
            return true;
        }
        Acl acl = acls.get(aclName).orElse(null);
        if (acl == null) {
            return false;
        }
        String target = (username == null || username.isEmpty()) ? DEFAULT_USER : username;
        if (!acl.getUserNames().contains(target)) {
            return false;
        }
        User user = resolveUser(target);
        if (user == null) {
            return false;
        }
        return switch (user.getAuthMode()) {
            case IAM -> sigV4Validator.validate(secret, clusterName, user.getName());
            case PASSWORD -> user.getPasswords() != null && user.getPasswords().contains(secret);
            case NO_PASSWORD -> true;
        };
    }

    /** True if the ACL has at least one user that requires a credential (password or IAM). */
    private boolean isAuthRequired(String aclName) {
        if (DEFAULT_ACL.equals(aclName)) {
            return false;
        }
        Acl acl = acls.get(aclName).orElse(null);
        if (acl == null) {
            return false;
        }
        return acl.getUserNames().stream()
                .map(this::resolveUser)
                .filter(java.util.Objects::nonNull)
                .anyMatch(u -> u.getAuthMode() != AuthMode.NO_PASSWORD);
    }

    private void requireAclExists(String aclName) {
        if (!DEFAULT_ACL.equals(aclName) && acls.get(aclName).isEmpty()) {
            throw new AwsException("ACLNotFoundFault", "ACL " + aclName + " not found.", 404);
        }
    }

    private void rejectDefaultUserOnCustomAcl(String userName) {
        if (DEFAULT_USER.equals(userName)) {
            throw new AwsException("InvalidParameterValueException",
                    "The default user cannot be added to a custom ACL.", 400);
        }
    }

    private boolean userExists(String name) {
        return DEFAULT_USER.equals(name) || users.get(name).isPresent();
    }

    private User resolveUser(String name) {
        return users.get(name).orElseGet(() -> DEFAULT_USER.equals(name) ? builtinDefaultUser(null) : null);
    }

    private User builtinDefaultUser(String region) {
        User user = new User();
        user.setName(DEFAULT_USER);
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
        acl.setStatus(ACTIVE);
        acl.setUserNames(new ArrayList<>(List.of(DEFAULT_USER)));
        acl.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        if (region != null) {
            acl.setArn(buildArn(region, "acl", DEFAULT_ACL));
        }
        return acl;
    }

    // ──────────────────────────── Internals ────────────────────────────

    private record Tagged(Map<String, String> tags, Runnable persist) {}

    private Tagged tagged(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ResourceArn is required.", 400);
        }
        Cluster cluster = clusters.scan(k -> true).stream()
                .filter(c -> resourceArn.equals(c.getArn()))
                .findFirst()
                .orElse(null);
        if (cluster != null) {
            return new Tagged(cluster.getTags(), () -> clusters.put(cluster.getName(), cluster));
        }
        ParameterGroup group = parameterGroups.scan(k -> true).stream()
                .filter(g -> resourceArn.equals(g.getArn()))
                .findFirst()
                .orElse(null);
        if (group != null) {
            return new Tagged(group.getTags(), () -> parameterGroups.put(group.getName(), group));
        }
        User user = users.scan(k -> true).stream()
                .filter(u -> resourceArn.equals(u.getArn()))
                .findFirst()
                .orElse(null);
        if (user != null) {
            return new Tagged(user.getTags(), () -> users.put(user.getName(), user));
        }
        Acl acl = acls.scan(k -> true).stream()
                .filter(a -> resourceArn.equals(a.getArn()))
                .findFirst()
                .orElse(null);
        if (acl != null) {
            return new Tagged(acl.getTags(), () -> acls.put(acl.getName(), acl));
        }
        SubnetGroup subnetGroup = subnetGroups.scan(k -> true).stream()
                .filter(g -> resourceArn.equals(g.getArn()))
                .findFirst()
                .orElse(null);
        if (subnetGroup != null) {
            return new Tagged(subnetGroup.getTags(), () -> subnetGroups.put(subnetGroup.getName(), subnetGroup));
        }
        if (resourceArn.contains(":parametergroup/")) {
            throw new AwsException("ParameterGroupNotFoundFault", "Parameter group not found.", 404);
        }
        if (resourceArn.contains(":user/")) {
            throw new AwsException("UserNotFoundFault", "User not found.", 404);
        }
        if (resourceArn.contains(":acl/")) {
            throw new AwsException("ACLNotFoundFault", "ACL not found.", 404);
        }
        if (resourceArn.contains(":subnetgroup/")) {
            throw new AwsException("SubnetGroupNotFoundFault", "Subnet group not found.", 404);
        }
        throw new AwsException("ClusterNotFoundFault", "Cluster not found.", 404);
    }

    private void startBackend(Cluster cluster, boolean authRequired) {
        String name = cluster.getName();
        int proxyPort = allocateProxyPort();
        String image = config.services().memorydb().defaultImage();
        LOG.infov("Creating MemoryDB cluster {0} with authRequired={1} on proxy port {2}",
                name, String.valueOf(authRequired), String.valueOf(proxyPort));

        MemoryDbContainerHandle handle = null;
        try {
            handle = containerManager.start(name, image);
            cluster.setClusterEndpoint(new Endpoint(
                    clusterEndpointAddress(name, regionFromArn(cluster.getArn())), proxyPort));
            cluster.setProxyPort(proxyPort);
            cluster.setContainerId(handle.getContainerId());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());

            proxyManager.startProxy(name, authRequired, proxyPort,
                    handle.getHost(), handle.getPort(),
                    (username, secret) -> authenticate(name, username, secret));
        } catch (RuntimeException e) {
            LOG.warnv("MemoryDB cluster {0} provisioning failed, rolling back: {1}", name, e.getMessage());
            rollbackBackend(name, handle, proxyPort);
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

    private String buildArn(String region, String resourceType, String name) {
        return regionResolver.buildArn("memorydb", region, resourceType + "/" + name);
    }

    private String clusterEndpointAddress(String name, String region) {
        String effective = (region == null || region.isBlank())
                ? regionResolver.getDefaultRegion() : region;
        return "clustercfg." + name + ".floci.memorydb." + effective + ".amazonaws.com";
    }

    private String regionFromArn(String arn) {
        if (arn == null) {
            return regionResolver.getDefaultRegion();
        }
        String[] parts = arn.split(":");
        return parts.length > 3 ? parts[3] : regionResolver.getDefaultRegion();
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
