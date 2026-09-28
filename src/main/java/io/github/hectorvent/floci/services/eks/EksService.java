package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.Ipv4Cidrs;
import io.github.hectorvent.floci.services.ec2.SecurityGroupPolicy;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.eks.model.CertificateAuthority;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.ClusterIdentity;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.CreateFargateProfileRequest;
import io.github.hectorvent.floci.services.eks.model.CreateNodeGroupRequest;
import io.github.hectorvent.floci.services.eks.model.EncryptionConfig;
import io.github.hectorvent.floci.services.eks.model.FargateProfile;
import io.github.hectorvent.floci.services.eks.model.FargateProfileStatus;
import io.github.hectorvent.floci.services.eks.model.KubernetesNetworkConfig;
import io.github.hectorvent.floci.services.eks.model.LogSetup;
import io.github.hectorvent.floci.services.eks.model.Logging;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.NodegroupScalingConfig;
import io.github.hectorvent.floci.services.eks.model.NodegroupStatus;
import io.github.hectorvent.floci.services.eks.model.OidcIdentity;
import io.github.hectorvent.floci.services.eks.model.Provider;
import io.github.hectorvent.floci.services.eks.model.ResourcesVpcConfig;
import io.github.hectorvent.floci.services.eks.model.Update;
import io.github.hectorvent.floci.services.eks.model.UpdateNodegroupConfigRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateNodegroupVersionRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateParam;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;

@ApplicationScoped
public class EksService implements TagHandler, ResourceProvider {

    private static final Logger LOG = Logger.getLogger(EksService.class);

    private static final List<String> ALL_LOG_TYPES = List.of(
            "api", "audit", "authenticator", "controllerManager", "scheduler"
    );

    /** The AWS charset for EKS cluster names. It admits no dot, which the Docker-name account
     *  qualifier relies on — see EksClusterManager#accountQualifiedName. */
    static final String CLUSTER_NAME_REGEX = "[0-9A-Za-z][A-Za-z0-9\\-_]*";

    private static final String CLUSTER_SG_DESCRIPTION =
            "EKS created security group applied to ENI that is attached to EKS Control Plane master nodes, as well as any managed workloads.";

    private final StorageBackend<String, Cluster> storage;
    private final StorageBackend<String, Nodegroup> nodeGroupStorage;
    private final StorageBackend<String, FargateProfile> fargateProfileStorage;
    private final StorageBackend<String, StoredNodegroupUpdate> nodegroupUpdateStorage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final EksClusterManager clusterManager;
    private final Ec2Service ec2Service;
    private final EksOidcService oidcService;
    private final EksAccessEntryService accessEntries;
    private final EksPodIdentityAssociationService podIdentityAssociations;
    private final EksAddonService addons;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TAINT_EFFECTS = Set.of("NO_SCHEDULE", "NO_EXECUTE", "PREFER_NO_SCHEDULE");

    /** How long a nodegroup update stays InProgress (nodegroup UPDATING) before it completes. */
    Duration nodegroupUpdateDuration = Duration.ofSeconds(3);

    /** A nodegroup Update plus what is needed to settle it lazily and replay it idempotently. */
    @RegisterForReflection
    public record StoredNodegroupUpdate(Update update, String nodegroupName, String clientRequestToken,
                                        String requestFingerprint, long completesAtEpochMillis) {}

    @Inject
    public EksService(StorageFactory storageFactory, EmulatorConfig config,
            RegionResolver regionResolver, EksClusterManager clusterManager, Ec2Service ec2Service,
            EksOidcService oidcService, EksAccessEntryService accessEntries,
            EksPodIdentityAssociationService podIdentityAssociations,
            EksAddonService addons) {
        this.storage = storageFactory.create("eks", "eks-clusters.json",
                new TypeReference<Map<String, Cluster>>() {
                });
        this.nodeGroupStorage = storageFactory.create("eks", "eks-nodegroups.json",
                new TypeReference<Map<String, Nodegroup>>() {
                });
        this.fargateProfileStorage = storageFactory.create("eks", "eks-fargate-profiles.json",
                new TypeReference<Map<String, FargateProfile>>() {
                });
        this.nodegroupUpdateStorage = storageFactory.create("eks", "eks-nodegroup-updates.json",
                new TypeReference<Map<String, StoredNodegroupUpdate>>() {
                });
        this.config = config;
        this.regionResolver = regionResolver;
        this.clusterManager = clusterManager;
        this.ec2Service = ec2Service;
        this.oidcService = oidcService;
        this.accessEntries = accessEntries;
        this.podIdentityAssociations = podIdentityAssociations;
        this.addons = addons;
    }

    public EksService(StorageFactory storageFactory, EmulatorConfig config,
            RegionResolver regionResolver, EksClusterManager clusterManager, Ec2Service ec2Service,
            EksOidcService oidcService, EksAccessEntryService accessEntries,
            EksPodIdentityAssociationService podIdentityAssociations) {
        this(storageFactory, config, regionResolver, clusterManager, ec2Service,
                oidcService, accessEntries, podIdentityAssociations, null);
    }

    public EksService(StorageFactory storageFactory, EmulatorConfig config,
            RegionResolver regionResolver, EksClusterManager clusterManager, Ec2Service ec2Service,
            EksOidcService oidcService, EksAccessEntryService accessEntries) {
        this(storageFactory, config, regionResolver, clusterManager, ec2Service,
                oidcService, accessEntries, null, null);
    }

    @PostConstruct
    public void init() {
        backfillOidcIdentities();
        backfillClusterSecurityGroups();
        backfillLogging();
        if (!config.services().eks().mock()) {
            restorePersistedClusters();
            startReadinessPoller();
        }
    }

    /**
     * Re-latches persisted clusters onto their k3s containers after a restart (#2609). Without
     * this, a cluster restored from {@code eks-clusters.json} reported ACTIVE but its container
     * was never restarted after a Docker daemon reboot, so every kubectl/deploy against it failed.
     * A surviving container is adopted (and started if stopped); a missing one is recreated
     * against the cluster's retained data volume. Restored clusters go back to CREATING so the
     * readiness poller re-verifies the API server and re-extracts the certificate authority
     * before marking them ACTIVE again.
     */
    private void restorePersistedClusters() {
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : allClusterEntries()) {
            Cluster cluster = entry.value();
            if (cluster.getContainerId() != null
                    || (cluster.getStatus() != ClusterStatus.ACTIVE
                            && cluster.getStatus() != ClusterStatus.CREATING)) {
                continue;
            }
            // Cluster.accountId is @JsonIgnore, so a reloaded record carries none — the owning
            // account must come from the storage key, or a non-default account's cluster would be
            // written back under the default account (stale owner record + duplicate). Rehydrate
            // it on the record too, so the readiness poller's later put lands under the owner.
            if (cluster.getAccountId() == null) {
                cluster.setAccountId(entry.accountId());
            }
            // A persisted name that predates create-time validation may violate the AWS charset —
            // in particular contain a dot, which could spell out another account's qualified
            // Docker name and cross-bind its container. Such a record is never restored; the
            // cluster must be deleted and recreated under a valid name.
            if (cluster.getName() == null || !cluster.getName().matches(CLUSTER_NAME_REGEX)) {
                LOG.errorv("Not restoring EKS cluster \"{0}\" (account {1}): its persisted name "
                        + "violates the AWS charset and could alias another account''s Docker "
                        + "resources. Delete it and recreate it under a valid name.",
                        cluster.getName(), entry.accountId());
                cluster.setStatus(ClusterStatus.FAILED);
                putClusterForAccount(entry.accountId(), cluster);
                continue;
            }
            try {
                LOG.infov("Restoring k3s container for persisted EKS cluster {0}", cluster.getName());
                cluster.setStatus(ClusterStatus.CREATING);
                cluster.setPodCidr(EksClusterManager.DEFAULT_POD_CIDR);
                clusterManager.restoreCluster(cluster);
            } catch (Exception e) {
                if (!clusterManager.isDockerReachable()) {
                    // Same degradation as create: a restored cluster is metadata that stands on
                    // its own, and FAILED is reserved for errors AWS would also report.
                    LOG.warnv("No Docker daemon is reachable from Floci; restored EKS cluster {0} "
                            + "comes back as metadata only.", cluster.getName());
                    markMetadataOnlyActive(cluster);
                } else {
                    LOG.errorv("Failed to restore k3s container for EKS cluster {0}: {1}",
                            cluster.getName(), e.getMessage());
                    cluster.setStatus(ClusterStatus.FAILED);
                }
            }
            putClusterForAccount(entry.accountId(), cluster);
        }
    }

    private List<AccountAwareStorageBackend.AccountEntry<Cluster>> allClusterEntries() {
        if (storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            return aware.scanAllAccountEntries(k -> true);
        }
        return storage.scan(k -> true).stream()
                .map(cluster -> new AccountAwareStorageBackend.AccountEntry<>(
                        cluster.getAccountId() != null ? cluster.getAccountId() : regionResolver.getAccountId(),
                        cluster.getName(), cluster))
                .toList();
    }

    /**
     * Gives clusters persisted before IRSA support an OIDC issuer and signing key. Without this,
     * a cluster restored from {@code eks-clusters.json} would report no
     * {@code identity.oidc.issuer}, and token minting and the JWKS routes would fail for it until
     * it was recreated.
     */
    private void backfillOidcIdentities() {
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : allClusterEntries()) {
            Cluster cluster = entry.value();
            // Runs at startup with no request context, and Cluster.accountId is @JsonIgnore so a
            // reloaded record carries none — the owning account comes from the storage key and is
            // passed explicitly, or the account-scoped put()/get() would resolve to the default
            // account and strand a cluster (and its signing key) owned by any other one.
            String accountId = entry.accountId();
            if (cluster.getAccountId() == null) {
                cluster.setAccountId(accountId);
            }

            if (cluster.getIdentity() != null && cluster.getIdentity().getOidc() != null
                    && cluster.getIdentity().getOidc().getIssuer() != null) {
                oidcService.ensureKeyForAccount(accountId, cluster.getName(),
                        cluster.getIdentity().getOidc().getIssuer());
                continue;
            }
            String issuer = oidcService.newIssuerUrl(config.defaultRegion());
            cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
            oidcService.ensureKeyForAccount(accountId, cluster.getName(), issuer);
            putClusterForAccount(accountId, cluster);
            LOG.infov("Backfilled IRSA OIDC issuer for existing EKS cluster {0} in account {1}",
                    cluster.getName(), accountId);
        }
    }

    void putClusterForAccount(String accountId, Cluster cluster) {
        if (storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            aware.putForAccount(accountId, cluster.getName(), cluster);
            return;
        }
        storage.put(cluster.getName(), cluster);
    }

    void deleteClusterForAccount(String accountId, String clusterName) {
        if (storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            aware.deleteForAccount(accountId, clusterName);
            return;
        }
        storage.delete(clusterName);
    }

    private SecurityGroup createClusterSecurityGroup(String region, String clusterName, String vpcId) {
        String suffix = randomHex(8);
        String groupName = "eks-cluster-sg-" + clusterName + "-" + suffix;
        SecurityGroup sg = ec2Service.createSecurityGroup(region, groupName, CLUSTER_SG_DESCRIPTION, vpcId);
        try {
            List<Tag> tags = List.of(
                    new Tag("Name", groupName),
                    new Tag("kubernetes.io/cluster/" + clusterName, "owned"),
                    new Tag("aws:eks:cluster-name", clusterName)
            );
            ec2Service.createTags(region, List.of(sg.getGroupId()), tags);

            UserIdGroupPair selfPair = new UserIdGroupPair();
            selfPair.setGroupId(sg.getGroupId());

            IpPermission selfIngress = new IpPermission();
            selfIngress.setIpProtocol("-1");
            selfIngress.setUserIdGroupPairs(List.of(selfPair));
            ec2Service.authorizeSecurityGroupIngress(region, sg.getGroupId(), List.of(selfIngress));

            IpPermission selfEgress = new IpPermission();
            selfEgress.setIpProtocol("-1");
            selfEgress.setUserIdGroupPairs(List.of(selfPair));
            ec2Service.authorizeSecurityGroupEgress(region, sg.getGroupId(), List.of(selfEgress));

            return sg;
        } catch (RuntimeException e) {
            try {
                ec2Service.deleteSecurityGroup(region, sg.getGroupId());
            } catch (Exception cleanupEx) {
                LOG.warnv("Failed to clean up cluster security group {0} after configuration failure: {1}",
                        sg.getGroupId(), cleanupEx.getMessage());
            }
            throw e;
        }
    }

    private void deleteClusterSecurityGroup(Cluster cluster) {
        if (ec2Service == null || cluster.getResourcesVpcConfig() == null) {
            return;
        }
        String sgId = cluster.getResourcesVpcConfig().getClusterSecurityGroupId();
        if (sgId == null || sgId.isBlank()) {
            return;
        }
        String region = resolveClusterRegion(cluster);
        try {
            ec2Service.deleteSecurityGroup(region, sgId);
        } catch (AwsException e) {
            if ("InvalidGroup.NotFound".equals(e.getErrorCode())) {
                LOG.debugv("Cluster security group {0} already gone, treating as deleted", sgId);
            } else {
                throw e;
            }
        }
    }

    void backfillClusterSecurityGroups() {
        if (ec2Service == null) {
            return;
        }
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : allClusterEntries()) {
            Cluster cluster = entry.value();
            String accountId = entry.accountId();
            if (cluster.getAccountId() == null) {
                cluster.setAccountId(accountId);
            }

            ResourcesVpcConfig vpcConfig = cluster.getResourcesVpcConfig();
            if (vpcConfig == null) {
                continue;
            }
            if (vpcConfig.getClusterSecurityGroupId() != null && !vpcConfig.getClusterSecurityGroupId().isBlank()) {
                continue;
            }
            if (vpcConfig.getVpcId() == null || vpcConfig.getVpcId().isBlank()) {
                continue;
            }

            try {
                RequestScopes.runAs(accountId, () -> {
                    String region = resolveClusterRegion(cluster);
                    SecurityGroup sg = createClusterSecurityGroup(region, cluster.getName(), vpcConfig.getVpcId());
                    vpcConfig.setClusterSecurityGroupId(sg.getGroupId());
                    putClusterForAccount(accountId, cluster);
                    LOG.infov("Backfilled cluster security group {0} for existing EKS cluster {1} in account {2}",
                            sg.getGroupId(), cluster.getName(), accountId);
                });
            } catch (Exception e) {
                LOG.warnv("Could not backfill cluster security group for existing EKS cluster {0} in account {1}: {2}",
                        cluster.getName(), accountId, e.getMessage());
            }
        }
    }

    void backfillLogging() {
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : allClusterEntries()) {
            Cluster cluster = entry.value();
            if (cluster.getLogging() != null) {
                continue;
            }
            String accountId = entry.accountId();
            if (cluster.getAccountId() == null) {
                cluster.setAccountId(accountId);
            }
            cluster.setLogging(defaultLogging());
            putClusterForAccount(accountId, cluster);
            LOG.infov("Backfilled default logging for existing EKS cluster {0} in account {1}",
                    cluster.getName(), accountId);
        }
    }

    private String resolveClusterRegion(Cluster cluster) {
        if (cluster.getArn() != null && !cluster.getArn().isBlank()) {
            try {
                return AwsArnUtils.parse(cluster.getArn()).region();
            } catch (IllegalArgumentException ignored) {
                // Not a valid ARN, fall back to configured region
            }
        }
        return config != null ? config.defaultRegion() : regionResolver.getRegion();
    }

    private static String randomHex(int len) {
        byte[] bytes = new byte[(len + 1) / 2];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes).substring(0, len);
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!config.services().eks().mock()) {
            for (Cluster cluster : allClusters()) {
                clusterManager.stopCluster(cluster);
            }
        }
    }

    public Cluster createCluster(CreateClusterRequest request) {
        String name = request.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException", "Cluster name is required", 400);
        }
        // The AWS constraint on EKS cluster names. Enforcing it also guarantees no name can
        // contain the dot EksClusterManager uses to account-qualify Docker names, so a
        // default-account cluster name can never spell out another account's qualified name
        // and collide with its container or data volume.
        if (name.length() > 100 || !name.matches(CLUSTER_NAME_REGEX)) {
            throw new AwsException("InvalidParameterException",
                    "Value '" + name + "' at 'name' failed to satisfy constraint: Member must "
                            + "satisfy regular expression pattern: ^" + CLUSTER_NAME_REGEX + "$",
                    400);
        }
        if (storage.get(name).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Cluster already exists: " + name, 409);
        }

        AccessConfig requestedAccess = request.getAccessConfig();
        String authenticationMode = requestedAccess == null || requestedAccess.authenticationMode() == null
                ? "CONFIG_MAP" : requestedAccess.authenticationMode();
        if (!Set.of("CONFIG_MAP", "API_AND_CONFIG_MAP", "API").contains(authenticationMode)) {
            throw new AwsException("InvalidParameterException", "Invalid authenticationMode", 400);
        }
        AccessConfig accessConfig = new AccessConfig(authenticationMode,
                requestedAccess == null || requestedAccess.bootstrapClusterCreatorAdminPermissions() == null
                        || requestedAccess.bootstrapClusterCreatorAdminPermissions());
        String region = regionResolver.getRegion();
        String resolvedVpcId = validateSubnetsAndResolveVpcId(region, request.getResourcesVpcConfig());
        String accountId = regionResolver.getAccountId();
        String arn = AwsArnUtils.Arn.of("eks", region, accountId, "cluster/" + name).toString();

        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setAccessConfig(accessConfig);
        cluster.setArn(arn);
        cluster.setAccountId(accountId);
        cluster.setCreatedAt(Instant.now());

        if (request.getVersion() != null && !request.getVersion().isBlank()) {
            String requestedVersion = request.getVersion().trim();
            Matcher matcher = K8S_VERSION_PATTERN.matcher(requestedVersion);
            if (!matcher.matches()) {
                throw new AwsException("InvalidParameterException",
                        "The specified parameter version is not valid: " + requestedVersion, 400);
            }
            int minor = Integer.parseInt(matcher.group(1));
            if (minor < MIN_SUPPORTED_K8S_MINOR) {
                throw new AwsException("InvalidParameterException",
                        "Unsupported Kubernetes version '" + requestedVersion + "'. Supported versions are 1."
                                + MIN_SUPPORTED_K8S_MINOR + " and above.", 400);
            }
            cluster.setVersion(requestedVersion);
            cluster.setExplicitVersion(true);
        } else {
            cluster.setVersion(DEFAULT_K8S_VERSION);
            cluster.setExplicitVersion(false);
        }

        cluster.setRoleArn(request.getRoleArn());
        ResourcesVpcConfig vpcConfig = buildVpcConfigResponse(request.getResourcesVpcConfig(), resolvedVpcId);
        SecurityGroup clusterSg = null;
        if (ec2Service != null && !vpcConfig.getVpcId().isBlank()) {
            try {
                clusterSg = createClusterSecurityGroup(region, name, vpcConfig.getVpcId());
                vpcConfig.setClusterSecurityGroupId(clusterSg.getGroupId());
            } catch (AwsException e) {
                if ("InvalidVpcID.NotFound".equals(e.getErrorCode())) {
                    throw new AwsException("InvalidParameterException",
                            "VPC '" + vpcConfig.getVpcId() + "' does not exist", 400);
                }
                throw e;
            }
        }
        cluster.setResourcesVpcConfig(vpcConfig);
        String vpcCidr = resolveClusterVpcCidr(region, vpcConfig.getVpcId());
        cluster.setKubernetesNetworkConfig(buildNetworkConfig(request.getKubernetesNetworkConfig(), vpcCidr));
        cluster.setPodCidr(EksClusterManager.DEFAULT_POD_CIDR);
        cluster.setLogging(buildLogging(request.getLogging()));
        cluster.setEncryptionConfig(buildEncryptionConfig(request.getEncryptionConfig()));
        cluster.setStatus(ClusterStatus.CREATING);
        cluster.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());
        cluster.setPlatformVersion("eks.1");
        cluster.setCertificateAuthority(new CertificateAuthority(""));

        try {
            String issuer = oidcService.newIssuerUrl(region);
            cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
            oidcService.ensureKey(name, issuer);

            if (config.services().eks().mock()) {
                markMetadataOnlyActive(cluster);
            } else {
                try {
                    if (!clusterManager.tryStartCluster(cluster)) {
                        // No Docker daemon: the k3s control plane cannot run, but the cluster record is
                        // metadata that stands on its own. FAILED is reserved for provisioning errors
                        // AWS would also report, and would strand every IaC apply that polls for ACTIVE.
                        markMetadataOnlyActive(cluster);
                    }
                } catch (Exception e) {
                    LOG.errorv("Failed to start k3s container for cluster {0}: {1}", name, e.getMessage());
                    cluster.setStatus(ClusterStatus.FAILED);
                }
            }

            storage.put(name, cluster);
        } catch (RuntimeException e) {
            if (clusterSg != null) {
                try {
                    ec2Service.deleteSecurityGroup(region, clusterSg.getGroupId());
                } catch (Exception cleanupEx) {
                    LOG.warnv("Failed to clean up cluster security group {0} after cluster creation failure: {1}",
                            clusterSg.getGroupId(), cleanupEx.getMessage());
                }
            }
            throw e;
        }

        return cluster;
    }

    /**
     * Marks a cluster ACTIVE with no Kubernetes API server behind it, the shape used by mock mode
     * and by a Floci that cannot reach a Docker daemon. Every EKS API Floci implements is control
     * plane (clusters, nodegroups, Fargate profiles, tags) and keeps working; the empty
     * certificateAuthority is what tells a caller no real cluster is listening on the endpoint.
     */
    private void markMetadataOnlyActive(Cluster cluster) {
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setEndpoint("https://localhost:" + config.services().eks().apiServerBasePort());
    }

    public Optional<Cluster> findAuthenticationCluster(String accountId, String name) {
        return storage instanceof AccountAwareStorageBackend<Cluster> aware
                ? aware.getForAccount(accountId, name) : storage.get(name);
    }

    public Cluster describeCluster(String name) {
        Cluster cluster = storage.get(name).filter(this::inRequestRegion)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No cluster found for name: " + name, 404));
        if (cluster.getLogging() == null) {
            cluster.setLogging(defaultLogging());
        }
        return cluster;
    }

    private boolean inRequestRegion(Cluster cluster) {
        String region = cluster.getArn() == null ? config.defaultRegion() : AwsArnUtils.parse(cluster.getArn()).region();
        return regionResolver.getRegion().equals(region);
    }

    public List<String> listClusters() {
        return storage.scan(k -> true).stream()
                .filter(this::inRequestRegion)
                .map(Cluster::getName)
                .sorted()
                .collect(Collectors.toList());
    }

    public Cluster deleteCluster(String name) {
        Cluster cluster = describeCluster(name);

        cluster.setStatus(ClusterStatus.DELETING);
        if (!config.services().eks().mock()) {
            clusterManager.stopCluster(cluster);
        }
        deleteClusterSecurityGroup(cluster);
        accessEntries.deleteClusterEntries(cluster);
        if (podIdentityAssociations != null) {
            podIdentityAssociations.deleteClusterAssociations(cluster);
        }
        if (addons != null) {
            addons.deleteClusterAddons(cluster);
        }
        storage.delete(name);
        oidcService.deleteKey(name);
        return cluster;
    }

    public Nodegroup createNodeGroup(String clusterName, CreateNodeGroupRequest request) {
        Nodegroup nodegroup = new Nodegroup();
        nodegroup.setNodegroupName(request.getNodegroupName());
        nodegroup.setVersion(request.getVersion());
        nodegroup.setReleaseVersion(request.getReleaseVersion());
        nodegroup.setSubnets(request.getSubnets());
        nodegroup.setNodeRole(request.getNodeRole());
        nodegroup.setAmiType(request.getAmiType());
        nodegroup.setCapacityType(request.getCapacityType());
        nodegroup.setDiskSize(request.getDiskSize());
        nodegroup.setInstanceTypes(request.getInstanceTypes());
        nodegroup.setScalingConfig(request.getScalingConfig());
        nodegroup.setUpdateConfig(request.getUpdateConfig());
        nodegroup.setRemoteAccess(request.getRemoteAccess());
        nodegroup.setTaints(request.getTaints());
        nodegroup.setLaunchTemplate(request.getLaunchTemplate());
        nodegroup.setNodeRepairConfig(request.getNodeRepairConfig());
        nodegroup.setWarmPoolConfig(request.getWarmPoolConfig());
        nodegroup.setLabels(request.getLabels());
        nodegroup.setTags(request.getTags());
        nodegroup.setClientRequestToken(request.getClientRequestToken());
        return createNodeGroup(clusterName, nodegroup);
    }

    public Nodegroup createNodeGroup(String clusterName, Nodegroup request) {
        Cluster cluster = describeCluster(clusterName);

        String nodegroupName = request.getNodegroupName();
        if (nodegroupName == null || nodegroupName.isBlank()) {
            throw new AwsException("InvalidParameterException", "Nodegroup name is required", 400);
        }
        if (request.getNodeRole() == null || request.getNodeRole().isBlank()) {
            throw new AwsException("InvalidParameterException", "nodeRole is required", 400);
        }
        if (request.getSubnets() == null || request.getSubnets().isEmpty()) {
            throw new AwsException("InvalidParameterException", "subnets are required", 400);
        }

        String storageKey = nodeGroupKey(clusterName, nodegroupName);
        if (nodeGroupStorage.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Nodegroup already exists: " + nodegroupName, 409);
        }

        String region = resolveClusterRegion(cluster);
        validateLaunchTemplate(region, request.getLaunchTemplate());

        String accountId = regionResolver.getAccountId();
        String id = UUID.randomUUID().toString();
        String arn = AwsArnUtils.Arn.of("eks", region, accountId,
                "nodegroup/" + clusterName + "/" + nodegroupName + "/" + id).toString();

        Instant now = Instant.now();
        Nodegroup nodeGroup = new Nodegroup();
        nodeGroup.setNodegroupName(nodegroupName);
        nodeGroup.setNodegroupArn(arn);
        nodeGroup.setClusterName(clusterName);
        nodeGroup.setAccountId(accountId);
        nodeGroup.setCreatedAt(now);
        nodeGroup.setModifiedAt(now);
        String resolvedVersion = request.getVersion() != null ? request.getVersion() : cluster.getVersion();
        nodeGroup.setVersion(resolvedVersion);
        nodeGroup.setReleaseVersion(request.getReleaseVersion() != null
                ? request.getReleaseVersion() : resolvedVersion + "-eks-1");
        nodeGroup.setStatus(NodegroupStatus.ACTIVE);
        nodeGroup.setCapacityType(request.getCapacityType() != null ? request.getCapacityType() : "ON_DEMAND");
        nodeGroup.setScalingConfig(request.getScalingConfig() != null ? request.getScalingConfig() : defaultScalingConfig());
        nodeGroup.setInstanceTypes(request.getInstanceTypes() != null ? request.getInstanceTypes() : List.of("t3.medium"));
        nodeGroup.setSubnets(request.getSubnets() != null ? request.getSubnets() : List.of());
        nodeGroup.setAmiType(request.getAmiType() != null ? request.getAmiType() : "AL2_x86_64");
        nodeGroup.setNodeRole(request.getNodeRole());
        nodeGroup.setDiskSize(request.getDiskSize() != null ? request.getDiskSize() : 20);
        nodeGroup.setResources(defaultNodeGroupResources(nodegroupName));
        nodeGroup.setHealth(defaultNodeGroupHealth());
        nodeGroup.setUpdateConfig(request.getUpdateConfig() != null ? request.getUpdateConfig() : defaultUpdateConfig());
        // Echoed back verbatim, and left unset when absent: EKS omits these rather than returning
        // an explicit null, and a null is drift to a caller diffing against its declared config.
        nodeGroup.setRemoteAccess(request.getRemoteAccess());
        nodeGroup.setTaints(request.getTaints());
        nodeGroup.setLaunchTemplate(request.getLaunchTemplate());
        nodeGroup.setNodeRepairConfig(request.getNodeRepairConfig());
        nodeGroup.setWarmPoolConfig(request.getWarmPoolConfig());
        nodeGroup.setLabels(request.getLabels() != null ? new HashMap<>(request.getLabels()) : null);
        nodeGroup.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());

        nodeGroupStorage.put(storageKey, nodeGroup);
        return nodeGroup;
    }

    private void validateLaunchTemplate(String region, Object launchTemplateObj) {
        if (!(launchTemplateObj instanceof Map<?, ?> map)) {
            return;
        }

        String id = asNonBlankString(map.get("id"));
        String name = asNonBlankString(map.get("name"));
        String version = asNonBlankString(map.get("version"));

        if ((id != null && name != null) || (id == null && name == null)) {
            throw new AwsException("InvalidParameterException",
                    "You must specify either the launch template ID or the launch template name in the request, but not both.",
                    400);
        }

        try {
            ec2Service.resolveLaunchTemplateData(region, id, name, version);
        } catch (AwsException e) {
            switch (e.getErrorCode()) {
                case "InvalidLaunchTemplateId.NotFound", "InvalidLaunchTemplateName.NotFoundException" ->
                    throw new AwsException("InvalidParameterException",
                            "Launch template could not be found : " + e.getMessage(), 400);
                case "InvalidLaunchTemplateVersion.NotFound", "InvalidLaunchTemplateVersion.Malformed" ->
                    throw new AwsException("InvalidParameterException", e.getMessage(), 400);
                default -> throw e;
            }
        }
    }

    private static String asNonBlankString(Object val) {
        if (val == null) {
            return null;
        }
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    public Nodegroup describeNodeGroup(String clusterName, String nodegroupName) {
        describeCluster(clusterName);
        Nodegroup nodegroup = nodeGroupStorage.get(nodeGroupKey(clusterName, nodegroupName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No nodegroup found for name: " + nodegroupName, 404));
        settleNodegroupUpdates(clusterName, nodegroup);
        return nodegroup;
    }

    /**
     * UpdateNodegroupConfig: applies scaling, label/taint deltas, updateConfig and
     * nodeRepairConfig, then reports the nodegroup UPDATING with an InProgress ConfigUpdate
     * until {@link #nodegroupUpdateDuration} elapses.
     */
    public synchronized Update updateNodegroupConfig(String clusterName, String nodegroupName,
                                                     UpdateNodegroupConfigRequest request) {
        Nodegroup nodegroup = describeNodeGroup(clusterName, nodegroupName);
        if (request == null) {
            throw new AwsException("InvalidParameterException", "No changes needed", 400);
        }
        String fingerprint = fingerprint(request);
        Optional<Update> replay = replayNodegroupUpdate(clusterName, nodegroupName,
                request.clientRequestToken(), fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireNodegroupUpdatable(nodegroup);

        List<UpdateParam> params = new ArrayList<>();
        NodegroupScalingConfig scaling = nodegroup.getScalingConfig();
        if (request.scalingConfig() != null) {
            scaling = mergeScalingConfig(nodegroup.getScalingConfig(), request.scalingConfig());
            params.add(new UpdateParam("MinSize", String.valueOf(scaling.getMinSize())));
            params.add(new UpdateParam("MaxSize", String.valueOf(scaling.getMaxSize())));
            params.add(new UpdateParam("DesiredSize", String.valueOf(scaling.getDesiredSize())));
        }
        Map<String, String> labels = nodegroup.getLabels();
        if (request.labels() != null) {
            labels = applyLabels(nodegroup.getLabels(), request.labels(), params);
        }
        List<Object> taints = nodegroup.getTaints();
        if (request.taints() != null) {
            taints = applyTaints(nodegroup.getTaints(), request.taints(), params);
        }
        Object updateConfig = nodegroup.getUpdateConfig();
        if (request.updateConfig() != null) {
            updateConfig = mergeUpdateConfig(nodegroup.getUpdateConfig(), request.updateConfig(), params);
        }
        Object nodeRepairConfig = nodegroup.getNodeRepairConfig();
        if (request.nodeRepairConfig() != null) {
            Object enabled = request.nodeRepairConfig().get("enabled");
            if (enabled != null && !(enabled instanceof Boolean)) {
                throw new AwsException("InvalidParameterException", "nodeRepairConfig.enabled must be a boolean", 400);
            }
            nodeRepairConfig = new LinkedHashMap<>(request.nodeRepairConfig());
            params.add(new UpdateParam("NodeRepairEnabled", String.valueOf(Boolean.TRUE.equals(enabled))));
        }
        if (params.isEmpty()) {
            throw new AwsException("InvalidParameterException", "No changes needed", 400);
        }

        nodegroup.setScalingConfig(scaling);
        nodegroup.setLabels(labels);
        nodegroup.setTaints(taints);
        nodegroup.setUpdateConfig(updateConfig);
        nodegroup.setNodeRepairConfig(nodeRepairConfig);
        return startNodegroupUpdate(clusterName, nodegroup, "ConfigUpdate", params,
                request.clientRequestToken(), fingerprint);
    }

    /**
     * UpdateNodegroupVersion: moves the nodegroup to {@code version} (default: the cluster's
     * version) and its release version, never past the control plane and never backwards.
     */
    public synchronized Update updateNodegroupVersion(String clusterName, String nodegroupName,
                                                      UpdateNodegroupVersionRequest request) {
        Cluster cluster = describeCluster(clusterName);
        Nodegroup nodegroup = describeNodeGroup(clusterName, nodegroupName);
        UpdateNodegroupVersionRequest body = request != null ? request
                : new UpdateNodegroupVersionRequest(null, null, null, null, null);
        String fingerprint = fingerprint(body);
        Optional<Update> replay = replayNodegroupUpdate(clusterName, nodegroupName,
                body.clientRequestToken(), fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireNodegroupUpdatable(nodegroup);

        String target = body.version() != null && !body.version().isBlank() ? body.version().trim() : cluster.getVersion();
        Matcher targetMatcher = K8S_VERSION_PATTERN.matcher(target == null ? "" : target);
        if (!targetMatcher.matches()) {
            throw new AwsException("InvalidParameterException",
                    "The specified parameter version is not valid: " + target, 400);
        }
        int targetMinor = Integer.parseInt(targetMatcher.group(1));
        Matcher clusterMatcher = K8S_VERSION_PATTERN.matcher(Objects.toString(cluster.getVersion(), ""));
        if (clusterMatcher.matches() && targetMinor > Integer.parseInt(clusterMatcher.group(1))) {
            throw new AwsException("InvalidParameterException", "Requested Nodegroup Kubernetes version "
                    + target + " is newer than the cluster Kubernetes version " + cluster.getVersion(), 400);
        }
        Matcher currentMatcher = K8S_VERSION_PATTERN.matcher(Objects.toString(nodegroup.getVersion(), ""));
        if (currentMatcher.matches() && targetMinor < Integer.parseInt(currentMatcher.group(1))) {
            throw new AwsException("InvalidParameterException", "Requested Nodegroup Kubernetes version "
                    + target + " is older than the current Nodegroup version " + nodegroup.getVersion(), 400);
        }
        if (body.launchTemplate() != null) {
            if (nodegroup.getLaunchTemplate() == null) {
                throw new AwsException("InvalidParameterException",
                        "launchTemplate can only be updated on a nodegroup created with a launch template", 400);
            }
            validateLaunchTemplate(resolveClusterRegion(cluster), body.launchTemplate());
        }
        String releaseVersion = body.releaseVersion() != null && !body.releaseVersion().isBlank()
                ? body.releaseVersion().trim() : target + "-eks-1";

        List<UpdateParam> params = new ArrayList<>();
        params.add(new UpdateParam("Version", target));
        params.add(new UpdateParam("ReleaseVersion", releaseVersion));
        if (body.launchTemplate() != null) {
            Object ltName = body.launchTemplate().get("name");
            Object ltVersion = body.launchTemplate().get("version");
            if (ltName != null) {
                params.add(new UpdateParam("LaunchTemplateName", ltName.toString()));
            }
            if (ltVersion != null) {
                params.add(new UpdateParam("LaunchTemplateVersion", ltVersion.toString()));
            }
            nodegroup.setLaunchTemplate(new LinkedHashMap<>(body.launchTemplate()));
        }
        nodegroup.setVersion(target);
        nodegroup.setReleaseVersion(releaseVersion);
        return startNodegroupUpdate(clusterName, nodegroup, "VersionUpdate", params,
                body.clientRequestToken(), fingerprint);
    }

    public Update describeNodegroupUpdate(String clusterName, String nodegroupName, String updateId) {
        Nodegroup nodegroup = describeNodeGroup(clusterName, nodegroupName);
        return nodegroupUpdateStorage.get(nodegroupUpdateKey(clusterName, nodegroup.getNodegroupName(), updateId))
                .map(stored -> effectiveUpdate(stored, System.currentTimeMillis()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No update found for ID: " + updateId, 404));
    }

    List<String> listNodegroupUpdateIds(String clusterName, String nodegroupName) {
        String prefix = nodeGroupKey(clusterName, nodegroupName) + "/";
        return nodegroupUpdateStorage.scan(key -> key.startsWith(prefix)).stream()
                .map(stored -> stored.update().id())
                .toList();
    }

    private Update startNodegroupUpdate(String clusterName, Nodegroup nodegroup, String type,
                                        List<UpdateParam> params, String clientRequestToken, String fingerprint) {
        Instant now = Instant.now();
        nodegroup.setStatus(NodegroupStatus.UPDATING);
        nodegroup.setModifiedAt(now);
        nodeGroupStorage.put(nodeGroupKey(clusterName, nodegroup.getNodegroupName()), nodegroup);
        Update update = new Update(UUID.randomUUID().toString(), "InProgress", type, List.copyOf(params),
                now.toEpochMilli() / 1000.0, List.of());
        nodegroupUpdateStorage.put(nodegroupUpdateKey(clusterName, nodegroup.getNodegroupName(), update.id()),
                new StoredNodegroupUpdate(update, nodegroup.getNodegroupName(), clientRequestToken, fingerprint,
                        now.toEpochMilli() + nodegroupUpdateDuration.toMillis()));
        return update;
    }

    private Optional<Update> replayNodegroupUpdate(String clusterName, String nodegroupName,
                                                   String clientRequestToken, String fingerprint) {
        if (clientRequestToken == null || clientRequestToken.isBlank()) {
            return Optional.empty();
        }
        String prefix = nodeGroupKey(clusterName, nodegroupName) + "/";
        long now = System.currentTimeMillis();
        return nodegroupUpdateStorage.scan(key -> key.startsWith(prefix)).stream()
                .filter(stored -> clientRequestToken.equals(stored.clientRequestToken())
                        && fingerprint.equals(stored.requestFingerprint()))
                .findFirst()
                .map(stored -> effectiveUpdate(stored, now));
    }

    /** Completes elapsed updates and returns the nodegroup to ACTIVE once none is in progress. */
    private void settleNodegroupUpdates(String clusterName, Nodegroup nodegroup) {
        if (nodegroup.getStatus() != NodegroupStatus.UPDATING) {
            return;
        }
        String prefix = nodeGroupKey(clusterName, nodegroup.getNodegroupName()) + "/";
        long now = System.currentTimeMillis();
        boolean inProgress = nodegroupUpdateStorage.scan(key -> key.startsWith(prefix)).stream()
                .anyMatch(stored -> now < stored.completesAtEpochMillis());
        if (!inProgress) {
            nodegroup.setStatus(NodegroupStatus.ACTIVE);
            nodeGroupStorage.put(nodeGroupKey(clusterName, nodegroup.getNodegroupName()), nodegroup);
        }
    }

    private static Update effectiveUpdate(StoredNodegroupUpdate stored, long nowMillis) {
        Update update = stored.update();
        if (!"InProgress".equals(update.status()) || nowMillis < stored.completesAtEpochMillis()) {
            return update;
        }
        return new Update(update.id(), "Successful", update.type(), update.params(), update.createdAt(),
                update.errors());
    }

    private static void requireNodegroupUpdatable(Nodegroup nodegroup) {
        if (nodegroup.getStatus() != NodegroupStatus.ACTIVE && nodegroup.getStatus() != NodegroupStatus.DEGRADED) {
            throw new AwsException("ResourceInUseException", "Nodegroup " + nodegroup.getNodegroupName()
                    + " cannot be updated while it is " + nodegroup.getStatus(), 409);
        }
    }

    private static NodegroupScalingConfig mergeScalingConfig(NodegroupScalingConfig current,
                                                             NodegroupScalingConfig requested) {
        NodegroupScalingConfig merged = new NodegroupScalingConfig();
        merged.setMinSize(requested.getMinSize() != null ? requested.getMinSize()
                : current != null ? current.getMinSize() : null);
        merged.setMaxSize(requested.getMaxSize() != null ? requested.getMaxSize()
                : current != null ? current.getMaxSize() : null);
        merged.setDesiredSize(requested.getDesiredSize() != null ? requested.getDesiredSize()
                : current != null ? current.getDesiredSize() : null);
        Integer min = merged.getMinSize();
        Integer max = merged.getMaxSize();
        Integer desired = merged.getDesiredSize();
        if (min == null || max == null || desired == null || min < 0 || max < 1 || desired < 0) {
            throw new AwsException("InvalidParameterException",
                    "scalingConfig requires minSize >= 0, maxSize >= 1 and desiredSize >= 0", 400);
        }
        if (min > max) {
            throw new AwsException("InvalidParameterException",
                    "Minimum capacity " + min + " can't be greater than maximum capacity " + max, 400);
        }
        if (desired < min || desired > max) {
            throw new AwsException("InvalidParameterException", "Desired capacity " + desired
                    + " must be between minimum capacity " + min + " and maximum capacity " + max, 400);
        }
        return merged;
    }

    private static Map<String, String> applyLabels(Map<String, String> current, UpdateNodegroupConfigRequest.Labels delta,
                                                   List<UpdateParam> params) {
        Map<String, String> add = delta.addOrUpdateLabels() == null ? Map.of() : delta.addOrUpdateLabels();
        List<String> remove = delta.removeLabels() == null ? List.of() : delta.removeLabels();
        for (Map.Entry<String, String> label : add.entrySet()) {
            if (label.getKey() == null || label.getKey().isBlank() || label.getKey().length() > 63
                    || label.getValue() == null || label.getValue().length() > 63) {
                throw new AwsException("InvalidParameterException", "Invalid label: " + label.getKey(), 400);
            }
            if (remove.contains(label.getKey())) {
                throw new AwsException("InvalidParameterException",
                        "Label " + label.getKey() + " cannot be both added and removed", 400);
            }
        }
        Map<String, String> updated = current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
        remove.forEach(updated::remove);
        updated.putAll(add);
        if (!add.isEmpty()) {
            params.add(new UpdateParam("LabelsToAdd", toJson(add)));
        }
        if (!remove.isEmpty()) {
            params.add(new UpdateParam("LabelsToRemove", toJson(remove)));
        }
        return updated.isEmpty() && current == null ? null : updated;
    }

    private static List<Object> applyTaints(List<Object> current, UpdateNodegroupConfigRequest.Taints delta,
                                            List<UpdateParam> params) {
        List<Map<String, Object>> add = delta.addOrUpdateTaints() == null ? List.of() : delta.addOrUpdateTaints();
        List<Map<String, Object>> remove = delta.removeTaints() == null ? List.of() : delta.removeTaints();
        for (Map<String, Object> taint : add) {
            Object key = taint.get("key");
            if (key == null || key.toString().isBlank() || key.toString().length() > 63
                    || !TAINT_EFFECTS.contains(String.valueOf(taint.get("effect")))) {
                throw new AwsException("InvalidParameterException",
                        "Taints require a key and an effect of NO_SCHEDULE, NO_EXECUTE or PREFER_NO_SCHEDULE", 400);
            }
        }
        List<Object> updated = new ArrayList<>(current == null ? List.of() : current);
        for (Map<String, Object> taint : remove) {
            updated.removeIf(existing -> existing instanceof Map<?, ?> map
                    && Objects.equals(map.get("key"), taint.get("key"))
                    && (taint.get("effect") == null || Objects.equals(map.get("effect"), taint.get("effect"))));
        }
        for (Map<String, Object> taint : add) {
            updated.removeIf(existing -> existing instanceof Map<?, ?> map
                    && Objects.equals(map.get("key"), taint.get("key"))
                    && Objects.equals(map.get("effect"), taint.get("effect")));
            updated.add(new LinkedHashMap<>(taint));
        }
        if (updated.size() > 50) {
            throw new AwsException("InvalidParameterException", "A nodegroup can have at most 50 taints", 400);
        }
        if (!add.isEmpty()) {
            params.add(new UpdateParam("TaintsToAdd", toJson(add)));
        }
        if (!remove.isEmpty()) {
            params.add(new UpdateParam("TaintsToRemove", toJson(remove)));
        }
        return updated.isEmpty() && current == null ? null : updated;
    }

    private static Object mergeUpdateConfig(Object current, Map<String, Object> requested, List<UpdateParam> params) {
        Object maxUnavailable = requested.get("maxUnavailable");
        Object percentage = requested.get("maxUnavailablePercentage");
        Object strategy = requested.get("updateStrategy");
        if (maxUnavailable != null && percentage != null) {
            throw new AwsException("InvalidParameterException",
                    "Specify either maxUnavailable or maxUnavailablePercentage, not both", 400);
        }
        for (Object value : Arrays.asList(maxUnavailable, percentage)) {
            if (value != null && (!(value instanceof Integer number) || number < 1 || number > 100)) {
                throw new AwsException("InvalidParameterException",
                        "maxUnavailable and maxUnavailablePercentage must be between 1 and 100", 400);
            }
        }
        if (strategy != null && !"DEFAULT".equals(strategy) && !"MINIMAL".equals(strategy)) {
            throw new AwsException("InvalidParameterException", "updateStrategy must be DEFAULT or MINIMAL", 400);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (maxUnavailable == null && percentage == null && current instanceof Map<?, ?> existing) {
            if (existing.get("maxUnavailable") != null) {
                merged.put("maxUnavailable", existing.get("maxUnavailable"));
            }
            if (existing.get("maxUnavailablePercentage") != null) {
                merged.put("maxUnavailablePercentage", existing.get("maxUnavailablePercentage"));
            }
        }
        if (maxUnavailable != null) {
            merged.put("maxUnavailable", maxUnavailable);
            params.add(new UpdateParam("MaxUnavailable", maxUnavailable.toString()));
        }
        if (percentage != null) {
            merged.put("maxUnavailablePercentage", percentage);
            params.add(new UpdateParam("MaxUnavailablePercentage", percentage.toString()));
        }
        if (strategy != null) {
            merged.put("updateStrategy", strategy);
            params.add(new UpdateParam("UpdateStrategy", strategy.toString()));
        } else if (current instanceof Map<?, ?> existing && existing.get("updateStrategy") != null) {
            merged.put("updateStrategy", existing.get("updateStrategy"));
        }
        return merged;
    }

    private static String fingerprint(Object request) {
        return toJson(request);
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String nodegroupUpdateKey(String clusterName, String nodegroupName, String updateId) {
        return nodeGroupKey(clusterName, nodegroupName) + "/" + updateId;
    }

    public List<String> listNodeGroups(String clusterName) {
        describeCluster(clusterName);
        String prefix = clusterName + "/";
        return nodeGroupStorage.scan(key -> key.startsWith(prefix)).stream()
                .map(Nodegroup::getNodegroupName)
                .collect(Collectors.toList());
    }

    public Nodegroup deleteNodeGroup(String clusterName, String nodegroupName) {
        Nodegroup nodeGroup = describeNodeGroup(clusterName, nodegroupName);
        nodeGroup.setStatus(NodegroupStatus.DELETING);
        nodeGroup.setModifiedAt(Instant.now());
        nodeGroupStorage.delete(nodeGroupKey(clusterName, nodegroupName));
        String updatePrefix = nodeGroupKey(clusterName, nodegroupName) + "/";
        nodegroupUpdateStorage.keys().stream().filter(key -> key.startsWith(updatePrefix)).toList()
                .forEach(nodegroupUpdateStorage::delete);
        return nodeGroup;
    }

    public FargateProfile createFargateProfile(String clusterName, CreateFargateProfileRequest request) {
        describeCluster(clusterName);

        String fargateProfileName = request.getFargateProfileName();
        if (fargateProfileName == null || fargateProfileName.isBlank()) {
            throw new AwsException("InvalidParameterException", "Fargate profile name is required", 400);
        }
        if (request.getPodExecutionRoleArn() == null || request.getPodExecutionRoleArn().isBlank()) {
            throw new AwsException("InvalidParameterException", "podExecutionRoleArn is required", 400);
        }

        String storageKey = fargateProfileKey(clusterName, fargateProfileName);
        if (fargateProfileStorage.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Fargate profile already exists: " + fargateProfileName, 409);
        }

        String region = config.defaultRegion();
        String accountId = regionResolver.getAccountId();
        String id = UUID.randomUUID().toString();
        String arn = AwsArnUtils.Arn.of("eks", region, accountId,
                "fargateprofile/" + clusterName + "/" + fargateProfileName + "/" + id).toString();

        FargateProfile profile = new FargateProfile();
        profile.setFargateProfileName(fargateProfileName);
        profile.setFargateProfileArn(arn);
        profile.setClusterName(clusterName);
        profile.setAccountId(accountId);
        profile.setCreatedAt(Instant.now());
        profile.setPodExecutionRoleArn(request.getPodExecutionRoleArn());
        profile.setSubnets(request.getSubnets() != null ? request.getSubnets() : List.of());
        profile.setSelectors(request.getSelectors() != null ? request.getSelectors() : List.of());
        profile.setStatus(FargateProfileStatus.ACTIVE);
        profile.setHealth(defaultFargateProfileHealth());
        profile.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());

        fargateProfileStorage.put(storageKey, profile);
        return profile;
    }

    public FargateProfile describeFargateProfile(String clusterName, String fargateProfileName) {
        describeCluster(clusterName);
        return fargateProfileStorage.get(fargateProfileKey(clusterName, fargateProfileName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No fargate profile found for name: " + fargateProfileName, 404));
    }

    public List<String> listFargateProfiles(String clusterName) {
        describeCluster(clusterName);
        String prefix = clusterName + "/";
        return fargateProfileStorage.scan(key -> key.startsWith(prefix)).stream()
                .map(FargateProfile::getFargateProfileName)
                .collect(Collectors.toList());
    }

    public FargateProfile deleteFargateProfile(String clusterName, String fargateProfileName) {
        FargateProfile profile = describeFargateProfile(clusterName, fargateProfileName);
        profile.setStatus(FargateProfileStatus.DELETING);
        fargateProfileStorage.delete(fargateProfileKey(clusterName, fargateProfileName));
        return profile;
    }

    /**
     * ListUpdates for the cluster or, with exactly one filter, for one of its nodegroups, add-ons
     * or capabilities. No capability can exist (CreateCapability is not implemented), so that
     * filter resolves the parent first and then returns what is actually recorded.
     */
    public PaginatedResult<String> listUpdates(String clusterName, String nodegroupName, String addonName,
                                               String capabilityName, Integer maxResults, String nextToken) {
        Cluster cluster = describeCluster(clusterName);
        long filters = Stream.of(nodegroupName, addonName, capabilityName)
                .filter(value -> value != null && !value.isBlank()).count();
        if (filters > 1) {
            throw new AwsException("InvalidParameterException",
                    "Specify at most one of nodegroupName, addonName and capabilityName", 400);
        }
        List<String> updateIds;
        if (capabilityName != null && !capabilityName.isBlank()) {
            describeCapability(clusterName, capabilityName);
            updateIds = List.of();
        } else if (nodegroupName != null && !nodegroupName.isBlank()) {
            describeNodeGroup(clusterName, nodegroupName);
            updateIds = listNodegroupUpdateIds(clusterName, nodegroupName);
        } else if (addonName != null && !addonName.isBlank()) {
            if (addons == null) {
                throw new AwsException("ResourceNotFoundException", "No addon: " + addonName
                        + " found for cluster: " + clusterName, 404);
            }
            addons.describe(cluster, addonName);
            updateIds = addons.listUpdateIds(cluster, addonName);
        } else {
            updateIds = addons == null ? List.of() : addons.listUpdateIds(cluster, null);
        }
        return Pagination.paginate(updateIds, Function.identity(), maxResults, nextToken,
                100, "InvalidParameterException");
    }

    /** No capability can exist because CreateCapability is not implemented. */
    public void describeCapability(String clusterName, String capabilityName) {
        describeCluster(clusterName);
        throw new AwsException("ResourceNotFoundException",
                "No capability found for name: " + capabilityName, 404);
    }

    /** No identity provider config can exist because AssociateIdentityProviderConfig is not implemented. */
    public void describeIdentityProviderConfig(String clusterName, String type, String name) {
        describeCluster(clusterName);
        if (type == null || name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "identityProviderConfig type and name are required", 400);
        }
        if (!"oidc".equals(type)) {
            throw new AwsException("InvalidParameterException",
                    "The identity provider config type " + type + " is not supported", 400);
        }
        throw new AwsException("ResourceNotFoundException",
                "No identity provider config found for name: " + name, 404);
    }

    @Override
    public String serviceKey() {
        return "eks";
    }

    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public void tagResource(String region, String resourceArn, Map<String, String> tags) {
        Cluster cluster = taggedCluster(region, resourceArn);
        if (resourceArn.contains(":podidentityassociation/")) {
            podIdentityAssociations.tag(cluster, associationId(resourceArn), tags, null);
            return;
        }
        if (cluster.getTags() == null) {
            cluster.setTags(new HashMap<>());
        }
        cluster.getTags().putAll(tags);
        storage.put(cluster.getName(), cluster);
    }

    @Override
    public void untagResource(String region, String resourceArn, List<String> tagKeys) {
        Cluster cluster = taggedCluster(region, resourceArn);
        if (resourceArn.contains(":podidentityassociation/")) {
            podIdentityAssociations.tag(cluster, associationId(resourceArn), null, tagKeys);
            return;
        }
        if (cluster.getTags() != null && tagKeys != null) {
            tagKeys.forEach(cluster.getTags()::remove);
        }
        storage.put(cluster.getName(), cluster);
    }

    @Override
    public Map<String, String> listTags(String region, String resourceArn) {
        Cluster cluster = taggedCluster(region, resourceArn);
        if (resourceArn.contains(":podidentityassociation/")) {
            return podIdentityAssociations.describe(cluster, associationId(resourceArn)).tags();
        }
        return cluster.getTags() != null ? cluster.getTags() : Map.of();
    }

    private Cluster taggedCluster(String region, String resourceArn) {
        String[] arn = resourceArn == null ? new String[0] : resourceArn.split(":", 6);
        if (arn.length != 6 || !"arn".equals(arn[0]) || !"eks".equals(arn[2])) {
            throw new AwsException("InvalidParameterException", "Invalid EKS resource ARN", 400);
        }
        String[] resource = arn[5].split("/", -1);
        boolean association = resource.length == 3 && "podidentityassociation".equals(resource[0]);
        if ((!association && !(resource.length == 2 && "cluster".equals(resource[0])))
                || !arn[3].equals(region == null ? regionResolver.getRegion() : region)
                || !arn[4].equals(regionResolver.getAccountId())) {
            throw new AwsException("ResourceNotFoundException", "Resource not found: " + resourceArn, 404);
        }
        Cluster cluster = describeCluster(resource[1]);
        String expected = association ? podIdentityAssociations.describe(cluster, resource[2]).associationArn() : cluster.getArn();
        if (!resourceArn.equals(expected)) {
            throw new AwsException("ResourceNotFoundException", "Resource not found: " + resourceArn, 404);
        }
        return cluster;
    }

    private static String associationId(String resourceArn) {
        return resourceArn.substring(resourceArn.lastIndexOf('/') + 1);
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        tagResource(null, resourceArn, tags);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        untagResource(null, resourceArn, tagKeys);
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        return listTags(null, resourceArn);
    }

    /**
     * Validates every requested subnet and returns the VPC they belong to.
     *
     * CreateCluster carries no vpcId — real EKS derives it from the subnets, and
     * #1942 reported resourcesVpcConfig.vpcId coming back blank because the
     * Subnet that requireSubnet already resolves was discarded here.
     *
     * @return the vpcId of the requested subnets, or null when none were given
     */
    private String validateSubnetsAndResolveVpcId(String region, ResourcesVpcConfig vpcConfig) {
        if (vpcConfig == null || vpcConfig.getSubnetIds() == null) {
            return null;
        }
        String vpcId = null;
        for (String subnetId : vpcConfig.getSubnetIds()) {
            try {
                vpcId = ec2Service.requireSubnet(region, subnetId).getVpcId();
            } catch (AwsException e) {
                throw new AwsException("InvalidParameterException",
                        "Subnet ID '" + subnetId + "' does not exist", 400);
            }
        }
        return vpcId;
    }

    private ResourcesVpcConfig buildVpcConfigResponse(ResourcesVpcConfig request, String resolvedVpcId) {
        ResourcesVpcConfig response = new ResourcesVpcConfig();
        if (request != null) {
            response.setSubnetIds(request.getSubnetIds() != null ? request.getSubnetIds() : List.of());
            response.setSecurityGroupIds(request.getSecurityGroupIds() != null ? request.getSecurityGroupIds() : List.of());
            // A caller-supplied vpcId still wins; otherwise fall back to the one
            // the subnets resolved to, and only then to empty.
            String vpcId = request.getVpcId() != null && !request.getVpcId().isBlank()
                    ? request.getVpcId()
                    : (resolvedVpcId != null ? resolvedVpcId : "");
            response.setVpcId(vpcId);
            response.setEndpointPublicAccess(
                    request.getEndpointPublicAccess() != null ? request.getEndpointPublicAccess() : Boolean.TRUE);
            response.setEndpointPrivateAccess(
                    request.getEndpointPrivateAccess() != null ? request.getEndpointPrivateAccess() : Boolean.FALSE);
            response.setPublicAccessCidrs(
                    request.getPublicAccessCidrs() != null ? request.getPublicAccessCidrs() : List.of("0.0.0.0/0"));
        } else {
            response.setSubnetIds(List.of());
            response.setSecurityGroupIds(List.of());
            response.setVpcId("");
            response.setEndpointPublicAccess(Boolean.TRUE);
            response.setEndpointPrivateAccess(Boolean.FALSE);
            response.setPublicAccessCidrs(List.of("0.0.0.0/0"));
        }
        return response;
    }

    public static final String DEFAULT_SERVICE_IPV4_CIDR = "10.100.0.0/16";
    public static final String ALTERNATIVE_SERVICE_IPV4_CIDR = "172.20.0.0/16";
    public static final String DEFAULT_K8S_VERSION = "1.29";
    public static final Pattern K8S_VERSION_PATTERN = Pattern.compile("^1\\.(\\d+)$");
    public static final int MIN_SUPPORTED_K8S_MINOR = 28;

    static void validateServiceIpv4Cidr(String cidr) {
        if (cidr == null || !SecurityGroupPolicy.validCidr(cidr)) {
            throw new AwsException("InvalidParameterException",
                    "The specified parameter kubernetesNetworkConfig.serviceIpv4Cidr is not valid: " + cidr, 400);
        }
        int slash = cidr.indexOf('/');
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterException",
                    "The specified parameter kubernetesNetworkConfig.serviceIpv4Cidr is not valid: " + cidr, 400);
        }
        if (prefix < 12 || prefix > 24) {
            throw new AwsException("InvalidParameterException",
                    "The specified parameter kubernetesNetworkConfig.serviceIpv4Cidr must have a prefix between /12 and /24: " + cidr, 400);
        }
        if (!Ipv4Cidrs.contains("10.0.0.0/8", cidr)
                && !Ipv4Cidrs.contains("172.16.0.0/12", cidr)
                && !Ipv4Cidrs.contains("192.168.0.0/16", cidr)) {
            throw new AwsException("InvalidParameterException",
                    "The specified parameter kubernetesNetworkConfig.serviceIpv4Cidr must fall within RFC 1918 private address ranges: " + cidr, 400);
        }
    }

    String resolveClusterVpcCidr(String region, String vpcId) {
        if (ec2Service != null && vpcId != null && !vpcId.isBlank()) {
            try {
                Vpc vpc = ec2Service.requireVpc(region, vpcId);
                if (vpc.getCidrBlock() != null && !vpc.getCidrBlock().isBlank()) {
                    return vpc.getCidrBlock();
                }
            } catch (Exception e) {
                LOG.debugv("Could not resolve VPC CIDR for vpc {0}: {1}", vpcId, e.getMessage());
            }
        }
        return null;
    }

    private KubernetesNetworkConfig buildNetworkConfig(KubernetesNetworkConfig request, String vpcCidr) {
        KubernetesNetworkConfig config = new KubernetesNetworkConfig();
        String requestedCidr = request != null ? request.getServiceIpv4Cidr() : null;
        String resolvedCidr;
        if (requestedCidr != null && !requestedCidr.isBlank()) {
            validateServiceIpv4Cidr(requestedCidr);
            if (vpcCidr != null && Ipv4Cidrs.overlaps(requestedCidr, vpcCidr)) {
                throw new AwsException("InvalidParameterException",
                        "The specified service IPv4 CIDR " + requestedCidr + " overlaps with the VPC CIDR " + vpcCidr, 400);
            }
            resolvedCidr = requestedCidr;
        } else {
            if (vpcCidr != null && Ipv4Cidrs.overlaps(DEFAULT_SERVICE_IPV4_CIDR, vpcCidr)) {
                if (Ipv4Cidrs.overlaps(ALTERNATIVE_SERVICE_IPV4_CIDR, vpcCidr)) {
                    throw new AwsException("InvalidParameterException",
                            "Default service IPv4 CIDR blocks (10.100.0.0/16 and 172.20.0.0/16) overlap with the VPC CIDR "
                                    + vpcCidr + ". Please specify a non-overlapping serviceIpv4Cidr.", 400);
                }
                resolvedCidr = ALTERNATIVE_SERVICE_IPV4_CIDR;
            } else {
                resolvedCidr = DEFAULT_SERVICE_IPV4_CIDR;
            }
        }
        config.setServiceIpv4Cidr(resolvedCidr);
        config.setIpFamily(request != null && request.getIpFamily() != null ? request.getIpFamily() : "ipv4");
        return config;
    }

    static Logging defaultLogging() {
        return new Logging(List.of(new LogSetup(new ArrayList<>(ALL_LOG_TYPES), false)));
    }

    private Logging buildLogging(Logging requestedLogging) {
        if (requestedLogging == null || requestedLogging.getClusterLogging() == null
                || requestedLogging.getClusterLogging().isEmpty()) {
            return defaultLogging();
        }

        Set<String> enabledTypes = new LinkedHashSet<>();
        Set<String> specifiedTypes = new HashSet<>();

        for (LogSetup setup : requestedLogging.getClusterLogging()) {
            if (setup == null || setup.getTypes() == null) {
                continue;
            }
            for (String type : setup.getTypes()) {
                if (!ALL_LOG_TYPES.contains(type)) {
                    throw new AwsException("InvalidParameterException",
                            "'" + type + "' is not a valid log type", 400);
                }
                specifiedTypes.add(type);
                if (Boolean.TRUE.equals(setup.getEnabled())) {
                    enabledTypes.add(type);
                } else {
                    enabledTypes.remove(type);
                }
            }
        }

        if (specifiedTypes.isEmpty()) {
            return defaultLogging();
        }

        List<String> enabledList = ALL_LOG_TYPES.stream()
                .filter(enabledTypes::contains)
                .collect(Collectors.toList());
        List<String> disabledList = ALL_LOG_TYPES.stream()
                .filter(t -> !enabledTypes.contains(t))
                .collect(Collectors.toList());

        List<LogSetup> entries = new ArrayList<>();
        if (!enabledList.isEmpty()) {
            entries.add(new LogSetup(enabledList, true));
        }
        if (!disabledList.isEmpty()) {
            entries.add(new LogSetup(disabledList, false));
        }

        return new Logging(entries);
    }

    private List<EncryptionConfig> buildEncryptionConfig(List<EncryptionConfig> requestedConfigs) {
        if (requestedConfigs == null || requestedConfigs.isEmpty()) {
            return null;
        }
        if (requestedConfigs.size() > 1) {
            throw new AwsException("InvalidParameterException",
                    "Only one encryption configuration is allowed", 400);
        }
        EncryptionConfig config = requestedConfigs.getFirst();
        if (config == null || config.getResources() == null || config.getResources().isEmpty()
                || !List.of("secrets").equals(config.getResources())) {
            throw new AwsException("InvalidParameterException",
                    "Invalid k8s resource and provider for encryption", 400);
        }
        if (config.getProvider() == null || config.getProvider().getKeyArn() == null
                || config.getProvider().getKeyArn().isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "Invalid k8s resource and provider for encryption", 400);
        }
        return List.of(new EncryptionConfig(List.of("secrets"), new Provider(config.getProvider().getKeyArn())));
    }

    private String nodeGroupKey(String clusterName, String nodegroupName) {
        return clusterName + "/" + nodegroupName;
    }

    private String fargateProfileKey(String clusterName, String fargateProfileName) {
        return clusterName + "/" + fargateProfileName;
    }

    private NodegroupScalingConfig defaultScalingConfig() {
        NodegroupScalingConfig scalingConfig = new NodegroupScalingConfig();
        scalingConfig.setMinSize(1);
        scalingConfig.setMaxSize(1);
        scalingConfig.setDesiredSize(1);
        return scalingConfig;
    }

    private Map<String, Integer> defaultUpdateConfig() {
        return Map.of("maxUnavailable", 1);
    }

    private Map<String, Object> defaultNodeGroupResources(String nodegroupName) {
        Map<String, Object> resources = new LinkedHashMap<>();
        Map<String, Object> autoScalingGroup = new LinkedHashMap<>();
        autoScalingGroup.put("name", "eks-" + nodegroupName + "-" + UUID.randomUUID().toString().substring(0, 8));
        resources.put("autoScalingGroups", List.of(autoScalingGroup));
        return resources;
    }

    private Map<String, List<Object>> defaultNodeGroupHealth() {
        Map<String, List<Object>> health = new LinkedHashMap<>();
        health.put("issues", new ArrayList<>());
        return health;
    }

    private FargateProfile.Health defaultFargateProfileHealth() {
        FargateProfile.Health health = new FargateProfile.Health();
        health.setIssues(List.of());
        return health;
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                for (Cluster cluster : allClusters()) {
                    if (cluster.getStatus() == ClusterStatus.CREATING) {
                        if (clusterManager.isReady(cluster)) {
                            LOG.infov("EKS cluster {0} is now ACTIVE", cluster.getName());
                            clusterManager.finalizeCluster(cluster);
                            cluster.setStatus(ClusterStatus.ACTIVE);
                            putCluster(cluster);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in EKS readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    public Optional<Cluster> findClusterByIssuer(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return Optional.empty();
        }
        return allClusters().stream()
                .filter(c -> c.getIdentity() != null && c.getIdentity().getOidc() != null
                        && issuer.equals(c.getIdentity().getOidc().getIssuer()))
                .findFirst();
    }

    private List<Cluster> allClusters() {
        if (storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            return aware.scanAllAccounts();
        }
        return storage.scan(k -> true);
    }

    private void putCluster(Cluster cluster) {
        if (cluster.getAccountId() != null && storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            aware.putForAccount(cluster.getAccountId(), cluster.getName(), cluster);
        } else {
            storage.put(cluster.getName(), cluster);
        }
    }

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Cluster cluster : storage.scan(k -> true)) {
            String arn = cluster.getArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "eks:cluster", "eks",
                    parsed.region(), parsed.accountId(),
                    cluster.getCreatedAt() != null ? cluster.getCreatedAt() : Instant.now(),
                    cluster.getTags() != null ? cluster.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("eks:cluster", "eks", true));
    }
}
