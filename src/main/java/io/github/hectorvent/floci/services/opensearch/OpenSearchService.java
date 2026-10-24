package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.opensearch.model.AdvancedSecurityOptions;
import io.github.hectorvent.floci.services.opensearch.model.ClusterConfig;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import io.github.hectorvent.floci.services.opensearch.model.DomainEndpointOptions;
import io.github.hectorvent.floci.services.opensearch.model.EbsOptions;
import io.github.hectorvent.floci.services.opensearch.model.EncryptionAtRestOptions;
import io.github.hectorvent.floci.services.opensearch.model.NodeToNodeEncryptionOptions;
import io.github.hectorvent.floci.services.opensearch.model.VolumeOwnership;
import io.github.hectorvent.floci.services.opensearch.model.VpcOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.services.opensearch.model.DomainMaintenance;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@ApplicationScoped
public class OpenSearchService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(OpenSearchService.class);

    private static final String DEFAULT_ENGINE_VERSION = OpenSearchVersions.DEFAULT_VERSION;

    private final StorageBackend<String, Domain> domainStore;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final OpenSearchDomainManager domainManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public OpenSearchService(StorageFactory storageFactory, EmulatorConfig config,
                             RegionResolver regionResolver, OpenSearchDomainManager domainManager) {
        this.domainStore = storageFactory.create("opensearch", "opensearch-domains.json",
                new TypeReference<Map<String, Domain>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.domainManager = domainManager;
    }

    OpenSearchService(StorageBackend<String, Domain> domainStore, EmulatorConfig config,
                      RegionResolver regionResolver, OpenSearchDomainManager domainManager) {
        this.domainStore = domainStore;
        this.config = config;
        this.regionResolver = regionResolver;
        this.domainManager = domainManager;
    }

    @PostConstruct
    public void init() {
        if (!config.services().opensearch().mock()) {
            startReadinessPoller();
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!config.services().opensearch().mock() && !config.services().opensearch().keepRunningOnShutdown()) {
            for (Domain domain : allDomains()) {
                domainManager.stopDomain(domain);
            }
        }
    }

    /**
     * Bag of optional configuration blocks parsed by {@link OpenSearchController}
     * and round-tripped on Describe. Any field can be null when the request
     * omitted the corresponding block — the service treats null as "leave the
     * existing value untouched" on update and "feature unset" on create.
     */
    public record DomainOptions(
            VpcOptions vpcOptions,
            AdvancedSecurityOptions advancedSecurityOptions,
            EncryptionAtRestOptions encryptionAtRestOptions,
            NodeToNodeEncryptionOptions nodeToNodeEncryptionOptions,
            DomainEndpointOptions domainEndpointOptions) {

        public static final DomainOptions EMPTY = new DomainOptions(null, null, null, null, null);
    }

    public Domain createDomain(String domainName, String engineVersion, ClusterConfig clusterConfig,
                                EbsOptions ebsOptions, Map<String, String> tags, String region) {
        return createDomain(domainName, engineVersion, clusterConfig, ebsOptions, tags, null,
                DomainOptions.EMPTY, region);
    }

    public Domain createDomain(String domainName, String engineVersion, ClusterConfig clusterConfig,
                                EbsOptions ebsOptions, Map<String, String> tags, String accessPolicies,
                                DomainOptions options, String region) {
        validateDomainName(domainName);
        OpenSearchVersions.validate(engineVersion);
        validateOptions(options);

        if (domainStore.get(domainName).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Domain with name " + domainName + " already exists.", 409);
        }

        String accountId = regionResolver.getAccountId();
        Domain domain = new Domain();
        domain.setDomainName(domainName);
        domain.setDomainId(accountId + "/" + domainName);
        domain.setAccountId(accountId);
        domain.setArn(AwsArnUtils.Arn.of("es", region, accountId, "domain/" + domainName).toString());
        domain.setEngineVersion(engineVersion != null ? engineVersion : DEFAULT_ENGINE_VERSION);
        domain.setAccessPolicies(accessPolicies);
        domain.setProcessing(false);
        domain.setDeleted(false);
        domain.setEndpoint("");
        domain.setCreatedAt(Instant.now());
        domain.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));
        // Stamp the volume name now, with the current prefix, so it is persisted rather than
        // recomputed later. Only records predating this field fall back to the legacy name.
        domain.setDockerVolumeName(ContainerStorageHelper.resourceName(
                config, "opensearch", domain.getVolumeId(), domain.getDomainName()));
        // The ownership marker travels with the name, so deletion removes what was persisted
        // instead of recomputing ownership against whatever configuration holds at delete time.
        // A configured data volume overwrites both when startDomain mounts operator storage.
        domain.setVolumeOwnership(VolumeOwnership.FLOCI_MANAGED);

        applyClusterConfig(domain.getClusterConfig(), clusterConfig);
        applyEbsOptions(domain.getEbsOptions(), ebsOptions);
        if (tags != null) {
            domain.setTags(tags);
        }
        applyDomainOptions(domain, options);

        if (config.services().opensearch().mock()) {
            domain.setProcessing(false);
            domain.setEndpoint(synthesizedEndpoint(domainName, region));
        } else {
            domain.setProcessing(true);
            if (!domainManager.tryStartDomain(domain)) {
                domain.setProcessing(false);
                domain.setEndpoint(synthesizedEndpoint(domainName, region));
            }
        }

        domainStore.put(domainName, domain);
        LOG.infov("Created OpenSearch domain: {0}", domainName);
        return domain;
    }

    /**
     * The endpoint reported for a domain with no container behind it. Two cases reach this: the
     * service is mocked, and no Docker daemon is reachable, which is the only condition under which
     * {@link OpenSearchDomainManager#tryStartDomain} returns false rather than rethrowing.
     *
     * <p>It reports an AWS-shaped endpoint in place of a blank one,
     * {@code search-<domain>-<suffix>.<region>.es.<dnsSuffix>}, taking the DNS suffix from the
     * region's partition rather than assuming the commercial one, with the name suffix derived from the
     * domain name so it survives a restart. A domain reporting {@code Processing false} alongside
     * {@code Endpoint ""} describes a state AWS never returns, and a client that reads the endpoint
     * to address the domain gets an empty string to connect to.
     */
    private static String synthesizedEndpoint(String domainName, String region) {
        String suffix = Integer.toHexString(domainName.hashCode() & 0x7fffffff);
        return "search-" + domainName + "-" + suffix + "." + region + ".es."
                + AwsRegions.dnsSuffixFor(region);
    }

    public Domain describeDomain(String domainName) {
        return domainStore.get(domainName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Domain not found: " + domainName, 409));
    }

    public List<Domain> describeDomains(List<String> domainNames) {
        return domainNames.stream()
                .map(name -> domainStore.get(name)
                        .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                                "Domain not found: " + name, 409)))
                .toList();
    }

    public List<Domain> listDomainNames(String engineType) {
        return domainStore.scan(k -> true).stream()
                .filter(d -> !d.isDeleted())
                .filter(d -> engineType == null || engineType.isBlank()
                        || matchesEngineType(d.getEngineVersion(), engineType))
                .toList();
    }

    public Domain updateDomainConfig(String domainName, String engineVersion,
                                      ClusterConfig clusterConfig, EbsOptions ebsOptions) {
        return updateDomainConfig(domainName, engineVersion, clusterConfig, ebsOptions, null,
                DomainOptions.EMPTY);
    }

    public Domain updateDomainConfig(String domainName, String engineVersion,
                                      ClusterConfig clusterConfig, EbsOptions ebsOptions,
                                      String accessPolicies, DomainOptions options) {
        Domain domain = describeDomain(domainName);
        OpenSearchVersions.validate(engineVersion);
        validateOptions(options);

        if (engineVersion != null && !engineVersion.isBlank()) {
            domain.setEngineVersion(engineVersion);
        }
        if (accessPolicies != null) {
            domain.setAccessPolicies(accessPolicies);
        }
        applyClusterConfig(domain.getClusterConfig(), clusterConfig);
        applyEbsOptions(domain.getEbsOptions(), ebsOptions);
        applyDomainOptions(domain, options);

        domainStore.put(domainName, domain);
        return domain;
    }

    /** Copies only the fields the request supplied (non-null) onto {@code existing}. */
    private static void applyClusterConfig(ClusterConfig existing, ClusterConfig supplied) {
        if (supplied == null) {
            return;
        }
        if (supplied.getInstanceType() != null) {
            existing.setInstanceType(supplied.getInstanceType());
        }
        if (supplied.getInstanceCount() != null && supplied.getInstanceCount() > 0) {
            existing.setInstanceCount(supplied.getInstanceCount());
        }
        if (supplied.isDedicatedMasterEnabled() != null) {
            existing.setDedicatedMasterEnabled(supplied.isDedicatedMasterEnabled());
        }
        if (supplied.isZoneAwarenessEnabled() != null) {
            existing.setZoneAwarenessEnabled(supplied.isZoneAwarenessEnabled());
        }
    }

    /** Copies only the fields the request supplied (non-null) onto {@code existing}. */
    private static void applyEbsOptions(EbsOptions existing, EbsOptions supplied) {
        if (supplied == null) {
            return;
        }
        if (supplied.isEbsEnabled() != null) {
            existing.setEbsEnabled(supplied.isEbsEnabled());
        }
        if (supplied.getVolumeType() != null) {
            existing.setVolumeType(supplied.getVolumeType());
        }
        if (supplied.getVolumeSize() != null && supplied.getVolumeSize() > 0) {
            existing.setVolumeSize(supplied.getVolumeSize());
        }
    }

    public Domain deleteDomain(String domainName) {
        Domain domain = describeDomain(domainName);
        domain.setDeleted(true);
        if (!config.services().opensearch().mock()) {
            domainManager.stopDomain(domain);
            domainManager.removeDomainStorage(domain);
        }
        domainStore.delete(domainName);
        LOG.infov("Deleted OpenSearch domain: {0}", domainName);
        return domain;
    }

    public void addTags(String arn, Map<String, String> tags) {
        Domain domain = findByArn(arn);
        domain.getTags().putAll(tags);
        domainStore.put(domain.getDomainName(), domain);
    }

    public Map<String, String> listTags(String arn) {
        return findByArn(arn).getTags();
    }

    public void removeTags(String arn, List<String> tagKeys) {
        Domain domain = findByArn(arn);
        tagKeys.forEach(domain.getTags()::remove);
        domainStore.put(domain.getDomainName(), domain);
    }

    public Domain upgradeDomain(String domainName, String targetVersion) {
        Domain domain = describeDomain(domainName);
        if (targetVersion == null || targetVersion.isBlank()) {
            throw new AwsException("ValidationException",
                    "TargetVersion is required for UpgradeDomain.", 400);
        }
        OpenSearchVersions.validateUpgrade(domain.getEngineVersion(), targetVersion);
        domain.setEngineVersion(targetVersion);
        domainStore.put(domainName, domain);
        return domain;
    }

    private Domain findByArn(String arn) {
        return domainStore.scan(k -> true).stream()
                .filter(d -> arn.equals(d.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Domain not found for ARN: " + arn, 409));
    }

    private void validateDomainName(String name) {
        if (name == null || name.length() < 3 || name.length() > 28) {
            throw new AwsException("ValidationException",
                    "Domain name must be between 3 and 28 characters.", 400);
        }
        if (!name.matches("[a-z][a-z0-9\\-]*")) {
            throw new AwsException("ValidationException",
                    "Domain name must start with a lowercase letter and contain only lowercase letters, numbers, and hyphens.", 400);
        }
    }

    /**
     * Cross-block validation: only the cases where a wrong combination is
     * deterministically rejected by AWS land here. Per-field syntax checks
     * stay in the controller's parsers.
     */
    private void validateOptions(DomainOptions options) {
        if (options == null) {
            return;
        }
        VpcOptions vpc = options.vpcOptions();
        if (vpc != null && !vpc.getSubnetIds().isEmpty() && vpc.getSubnetIds().stream().anyMatch(String::isBlank)) {
            throw new AwsException("ValidationException",
                    "VPCOptions.SubnetIds may not contain blank entries.", 400);
        }
        AdvancedSecurityOptions adv = options.advancedSecurityOptions();
        if (adv != null && adv.isEnabled() && adv.isInternalUserDatabaseEnabled()) {
            // AWS rejects internal user db without a master user — keep
            // emulator-side parity so Terraform plans surface the same error.
            if (adv.getMasterUserOptions() == null
                    || adv.getMasterUserOptions().getMasterUserName() == null
                    || adv.getMasterUserOptions().getMasterUserName().isBlank()) {
                throw new AwsException("ValidationException",
                        "AdvancedSecurityOptions.MasterUserOptions.MasterUserName is required "
                                + "when InternalUserDatabaseEnabled=true.", 400);
            }
        }
        DomainEndpointOptions deo = options.domainEndpointOptions();
        if (deo != null && deo.isCustomEndpointEnabled()
                && (deo.getCustomEndpoint() == null || deo.getCustomEndpoint().isBlank())) {
            throw new AwsException("ValidationException",
                    "DomainEndpointOptions.CustomEndpoint is required when CustomEndpointEnabled=true.", 400);
        }
    }

    /** Copy non-null fields from {@code options} onto {@code domain}. Null leaves the field untouched. */
    private void applyDomainOptions(Domain domain, DomainOptions options) {
        if (options == null) {
            return;
        }
        if (options.vpcOptions() != null) {
            domain.setVpcOptions(options.vpcOptions());
        }
        if (options.advancedSecurityOptions() != null) {
            domain.setAdvancedSecurityOptions(options.advancedSecurityOptions());
        }
        if (options.encryptionAtRestOptions() != null) {
            domain.setEncryptionAtRestOptions(options.encryptionAtRestOptions());
        }
        if (options.nodeToNodeEncryptionOptions() != null) {
            domain.setNodeToNodeEncryptionOptions(options.nodeToNodeEncryptionOptions());
        }
        if (options.domainEndpointOptions() != null) {
            domain.setDomainEndpointOptions(options.domainEndpointOptions());
        }
    }

    private boolean matchesEngineType(String engineVersion, String engineType) {
        if ("Elasticsearch".equalsIgnoreCase(engineType)) {
            return engineVersion != null && engineVersion.startsWith("Elasticsearch");
        }
        return engineVersion == null || engineVersion.startsWith("OpenSearch");
    }

    private void startReadinessPoller() {
        poller.scheduleWithFixedDelay(this::pollReadiness, 3, 3, TimeUnit.SECONDS);
    }

    void pollReadiness() {
        try {
            for (Domain domain : allDomains()) {
                if (domain.isProcessing() && domainManager.isReady(domain)) {
                    domain.setProcessing(false);
                    putDomain(domain);
                    LOG.infov("OpenSearch domain {0} is ready at {1}",
                            domain.getDomainName(), domain.getEndpoint());
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("OpenSearch readiness poll failed; will retry", e);
        }
    }

    private List<Domain> allDomains() {
        if (domainStore instanceof AccountAwareStorageBackend<Domain> aware) {
            return aware.scanAllAccounts();
        }
        return domainStore.scan(k -> true);
    }

    private void putDomain(Domain domain) {
        if (domain.getAccountId() != null && domainStore instanceof AccountAwareStorageBackend<Domain> aware) {
            aware.putForAccount(domain.getAccountId(), domain.getDomainName(), domain);
        } else {
            domainStore.put(domain.getDomainName(), domain);
        }
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Domain domain : listDomainNames(null)) {
            if (domain.getArn() == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(domain.getArn());
            resources.add(new ExplorerResource(
                    domain.getArn(), "es:domain", "es",
                    parsed.region(), parsed.accountId(),
                    domain.getCreatedAt() != null ? domain.getCreatedAt() : Instant.now(),
                    domain.getTags() != null ? domain.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("es:domain", "es", true));
    }

    private static final Set<String> MAINTENANCE_ACTIONS =
            Set.of("REBOOT_NODE", "RESTART_SEARCH_PROCESS", "RESTART_DASHBOARD");

    private final TlsCertificateManager certificateManager;

    @Inject
    public OpenSearchService(StorageFactory storageFactory, EmulatorConfig config,
                             RegionResolver regionResolver, OpenSearchDomainManager domainManager,
                             TlsCertificateManager certificateManager) {
        this.domainStore = storageFactory.create("opensearch", "opensearch-domains.json",
                new TypeReference<Map<String, Domain>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.domainManager = domainManager;
        this.certificateManager = certificateManager;
    }

    /**
     * The endpoint DescribeDomain reports: the gateway host that serves the domain's REST API
     * (see {@link OpenSearchDataPlaneEndpoint}), or empty while no search engine backs the domain.
     */
    public String publicEndpoint(Domain domain) {
        if (domain.getEndpoint() == null || domain.getEndpoint().isBlank()) {
            return "";
        }
        return OpenSearchDataPlaneEndpoint.publicEndpoint(domain.getDomainName(), regionOf(domain), baseUrl());
    }

    private String regionOf(Domain domain) {
        return domain.getArn() != null
                ? AwsArnUtils.parse(domain.getArn()).region()
                : regionResolver.getDefaultRegion();
    }

    private String baseUrl() {
        return config.effectiveBaseUrl();
    }

    /** Makes the HTTPS listener's certificate cover every domain endpoint host in the region. */
    private void ensureEndpointCertificate(String region) {
        if (certificateManager != null) {
            certificateManager.ensureHost(OpenSearchDataPlaneEndpoint.certificateWildcard(region, baseUrl()));
        }
    }

    /**
     * Lookup used by the operations (DescribeDomainHealth, DescribeDomainNodes,
     * DescribeDomainChangeProgress, GetDomainMaintenanceStatus,
     * ListDomainMaintenances) on which AWS reports a missing domain as
     * {@code BaseException} rather than {@code ResourceNotFoundException}.
     */
    public Domain describeDomainOrBaseException(String domainName) {
        return domainStore.get(domainName)
                .orElseThrow(() -> new AwsException("BaseException",
                        "Domain not found: " + domainName, 400));
    }

    /** A data node of a domain as reported by DescribeDomainNodes. */
    public record DomainNode(String nodeId, String nodeType, String availabilityZone,
                             String instanceType, String nodeStatus, String storageType,
                             String storageVolumeType, String storageSize) {}

    /** One data node per configured instance, spread over the domain's availability zones. */
    public List<DomainNode> describeDomainNodes(String domainName) {
        return nodesOf(describeDomainOrBaseException(domainName));
    }

    public DomainMaintenance startDomainMaintenance(String domainName, String action, String nodeId) {
        Domain domain = describeDomain(domainName);
        if (action == null || !MAINTENANCE_ACTIONS.contains(action)) {
            throw new AwsException("ValidationException",
                    "Action must be one of " + MAINTENANCE_ACTIONS + ".", 400);
        }
        if (nodeId != null && nodesOf(domain).stream().noneMatch(n -> n.nodeId().equals(nodeId))) {
            throw new AwsException("ValidationException",
                    "Node " + nodeId + " does not belong to domain " + domainName + ".", 400);
        }

        Instant now = Instant.now();
        DomainMaintenance maintenance = new DomainMaintenance();
        maintenance.setMaintenanceId(UUID.randomUUID().toString());
        maintenance.setAction(action);
        maintenance.setNodeId(nodeId);
        maintenance.setCreatedAt(now);
        maintenance.setStatus("COMPLETED");

        boolean restartsSearchProcess = !"RESTART_DASHBOARD".equals(action);
        if (restartsSearchProcess && !config.services().opensearch().mock()
                && domain.getContainerId() != null) {
            // The single backing container hosts every node, so a node reboot
            // and a search-process restart both restart that container.
            try {
                if (domainManager.tryStartDomain(domain)) {
                    domain.setProcessing(true);
                } else {
                    maintenance.setStatus("FAILED");
                    maintenance.setStatusMessage("No Docker daemon is reachable to restart the domain.");
                }
            } catch (RuntimeException e) {
                maintenance.setStatus("FAILED");
                maintenance.setStatusMessage(e.getMessage());
            }
        }
        maintenance.setUpdatedAt(Instant.now());

        domain.getMaintenances().add(maintenance);
        domainStore.put(domainName, domain);
        return maintenance;
    }

    public DomainMaintenance getDomainMaintenanceStatus(String domainName, String maintenanceId) {
        Domain domain = describeDomainOrBaseException(domainName);
        if (maintenanceId == null || maintenanceId.isBlank()) {
            throw new AwsException("ValidationException", "maintenanceId is required.", 400);
        }
        return domain.getMaintenances().stream()
                .filter(m -> maintenanceId.equals(m.getMaintenanceId()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Maintenance not found: " + maintenanceId, 409));
    }

    public List<DomainMaintenance> listDomainMaintenances(String domainName, String action, String status) {
        Domain domain = describeDomainOrBaseException(domainName);
        return domain.getMaintenances().stream()
                .filter(m -> action == null || action.isBlank() || action.equals(m.getAction()))
                .filter(m -> status == null || status.isBlank() || status.equals(m.getStatus()))
                .toList();
    }

    private List<DomainNode> nodesOf(Domain domain) {
        ClusterConfig cc = domain.getClusterConfig() != null ? domain.getClusterConfig() : new ClusterConfig();
        EbsOptions ebs = domain.getEbsOptions() != null ? domain.getEbsOptions() : new EbsOptions();
        String region = domain.getArn() != null
                ? AwsArnUtils.parse(domain.getArn()).region()
                : regionResolver.getDefaultRegion();
        int zones = cc.isZoneAwarenessEnabled() ? 2 : 1;
        String nodeStatus = domain.isProcessing() ? "NotAvailable" : "Active";

        List<DomainNode> nodes = new ArrayList<>();
        for (int i = 0; i < Math.max(cc.getInstanceCount(), 1); i++) {
            String nodeId = UUID.nameUUIDFromBytes((domain.getArn() + "/data/" + i)
                    .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
            nodes.add(new DomainNode(
                    nodeId,
                    "Data",
                    region + (char) ('a' + (i % zones)),
                    cc.getInstanceType(),
                    nodeStatus,
                    ebs.isEbsEnabled() ? "ebs" : "instance",
                    ebs.isEbsEnabled() ? ebs.getVolumeType() : null,
                    ebs.isEbsEnabled() ? String.valueOf(ebs.getVolumeSize()) : null));
        }
        return nodes;
    }
}
