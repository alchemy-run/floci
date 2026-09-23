package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.rds.proxy.PasswordValidator;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.ClusterSubnetGroup;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.model.EventSubscription;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.services.redshift.model.RedshiftEvent;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import io.github.hectorvent.floci.services.redshift.model.SnapshotCopyGrant;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import io.github.hectorvent.floci.services.secretsmanager.RandomPasswordGenerator;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

@ApplicationScoped
public class RedshiftService {
    private static final Logger LOG = Logger.getLogger(RedshiftService.class);

    private final AccountAwareStorageBackend<Cluster> clusters;
    private final AccountAwareStorageBackend<Snapshot> snapshots;
    private final AccountAwareStorageBackend<ClusterParameterGroup> parameterGroups;
    private final AccountAwareStorageBackend<ClusterSubnetGroup> subnetGroups;
    private final AccountAwareStorageBackend<RedshiftEvent> events;
    private final AccountAwareStorageBackend<EventSubscription> eventSubscriptions;
    private final SnsService snsService;
    private static final int MIN_INTEGRATION_RECORDS = 20;
    private static final int MAX_INTEGRATION_RECORDS = 100;
    private static final int MAX_INTEGRATION_DESCRIPTION = 1000;
    private static final int MAX_INTEGRATION_NAME = 63;
    // Modelled constraint on CreateIntegration.IntegrationName: a letter first, then alphanumeric
    // groups joined by single hyphens. No leading digit, underscore, or trailing hyphen.
    private static final String INTEGRATION_NAME_PATTERN = "^[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*$";
    private static final Pattern INTEGRATION_NAME = Pattern.compile(INTEGRATION_NAME_PATTERN);

    private final AccountAwareStorageBackend<Integration> integrations;
    private final AccountAwareStorageBackend<SnapshotCopyGrant> snapshotCopyGrants;
    private final RedshiftContainerManager containerManager;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final RedshiftProxyManager proxyManager;
    private final DockerHostResolver dockerHostResolver;
    private final RedshiftCredentialBroker credentialBroker;
    private final Ec2Service ec2Service;
    private final SecretsManagerService secretsManagerService;
    private final ObjectMapper objectMapper;
    private final DynamoDbStreamService streamService;
    // Proxy ports currently handed out, so allocateProxyPort never double-assigns within this JVM.
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();

    @Inject
    public RedshiftService(StorageFactory storageFactory, RedshiftContainerManager containerManager,
                            EmulatorConfig config, RegionResolver regionResolver,
                            RedshiftProxyManager proxyManager, DockerHostResolver dockerHostResolver,
                            RedshiftCredentialBroker credentialBroker, Ec2Service ec2Service, SnsService snsService,
                            SecretsManagerService secretsManagerService, ObjectMapper objectMapper,
                            DynamoDbStreamService streamService) {
        this.clusters = storageFactory.create("redshift", "redshift-clusters.json", new TypeReference<Map<String, Cluster>>() {});
        this.snapshots = storageFactory.create("redshift", "redshift-snapshots.json", new TypeReference<Map<String, Snapshot>>() {});
        this.parameterGroups = storageFactory.create("redshift", "redshift-parameter-groups.json", new TypeReference<Map<String, ClusterParameterGroup>>() {});
        this.subnetGroups = storageFactory.create("redshift", "redshift-subnet-groups.json", new TypeReference<Map<String, ClusterSubnetGroup>>() {});
        this.integrations = storageFactory.create("redshift", "redshift-integrations.json", new TypeReference<Map<String, Integration>>() {});
        this.events = storageFactory.create("redshift", "redshift-events.json", new TypeReference<Map<String, RedshiftEvent>>() {});
        this.eventSubscriptions = storageFactory.create("redshift", "redshift-event-subscriptions.json",
                new TypeReference<Map<String, EventSubscription>>() {});
        this.snsService = snsService;
        this.snapshotCopyGrants = storageFactory.create("redshift", "redshift-snapshot-copy-grants.json", new TypeReference<Map<String, SnapshotCopyGrant>>() {});
        this.containerManager = containerManager;
        this.config = config;
        this.regionResolver = regionResolver;
        this.proxyManager = proxyManager;
        this.dockerHostResolver = dockerHostResolver;
        this.credentialBroker = credentialBroker;
        this.ec2Service = ec2Service;
        this.secretsManagerService = secretsManagerService;
        this.objectMapper = objectMapper;
        this.streamService = streamService;
    }

    RedshiftService(StorageFactory storageFactory, RedshiftContainerManager containerManager,
                    EmulatorConfig config, RegionResolver regionResolver,
                    RedshiftProxyManager proxyManager, DockerHostResolver dockerHostResolver,
                    RedshiftCredentialBroker credentialBroker, Ec2Service ec2Service, SnsService snsService) {
        this(storageFactory, containerManager, config, regionResolver, proxyManager, dockerHostResolver,
                credentialBroker, ec2Service, snsService, null, new ObjectMapper(), null);
    }

    RedshiftService(StorageFactory storageFactory, RedshiftContainerManager containerManager,
                    EmulatorConfig config, RegionResolver regionResolver,
                    RedshiftProxyManager proxyManager, DockerHostResolver dockerHostResolver,
                    RedshiftCredentialBroker credentialBroker) {
        this(storageFactory, containerManager, config, regionResolver, proxyManager, dockerHostResolver,
                credentialBroker, null, null, null, new ObjectMapper(), null);
    }

    // Recreate Docker containers for persisted clusters on app restart (across every account, not just default)
    void onStart(@Observes StartupEvent event) {
        List<AccountAwareStorageBackend.AccountEntry<Cluster>> availableClusters =
                clusters.scanAllAccountEntries(k -> true).stream()
                        .filter(entry -> "available".equals(entry.value().getClusterStatus()))
                        .toList();
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : availableClusters) {
            Cluster cluster = entry.value();
            if (containerManager.getContainer(entry.accountId(), cluster.getClusterIdentifier()).isPresent()) {
                continue;
            }
            String password = cluster.getMasterPassword() != null ? cluster.getMasterPassword() : "admin";
            try {
                LOG.infov("Recovering container for persisted cluster: {0}", cluster.getClusterIdentifier());
                RedshiftContainerHandle handle = containerManager.adoptOrStart(
                        entry.accountId(), cluster.getClusterIdentifier(), cluster.getMasterUsername(), password);

                // A cluster persisted before the auth proxy existed has proxyPort == 0. Allocate one
                // now; its endpoint changes exactly once after this upgrade. Existing clusters keep
                // their stored port so the endpoint is stable across restarts.
                int proxyPort = cluster.getProxyPort() > 0 ? cluster.getProxyPort() : allocateProxyPort();
                usedPorts.add(proxyPort);
                Endpoint endpoint = proxyEndpoint(proxyPort);
                cluster.setProxyPort(proxyPort);
                proxyManager.startProxy(
                        relayKey(entry.accountId(), cluster.getClusterIdentifier()), proxyPort,
                        handle.getHost(), handle.getPort(), endpoint.getAddress(),
                        cluster.getMasterUsername(), password, CLUSTER_DB_NAME,
                        passwordValidatorFor(entry.accountId(), cluster.getClusterIdentifier()),
                        cluster.getIamRoleArns());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());
                cluster.setEndpoint(endpoint);
                clusters.putForAccount(entry.accountId(), entry.key(), cluster);
            } catch (Exception e) {
                LOG.warnv(e, "Failed to recover container for cluster {0}, marking as unavailable",
                        cluster.getClusterIdentifier());
                try {
                    proxyManager.stopProxy(relayKey(entry.accountId(), cluster.getClusterIdentifier()));
                } catch (Exception ex) {
                    LOG.warnv(ex, "Failed to stop proxy during recovery rollback for cluster {0}", cluster.getClusterIdentifier());
                }
                try {
                    containerManager.stop(entry.accountId(), cluster.getClusterIdentifier());
                } catch (Exception ex) {
                    LOG.warnv(ex, "Failed to stop container during recovery rollback for cluster {0}", cluster.getClusterIdentifier());
                }
                cluster.setClusterStatus("unavailable");
                clusters.putForAccount(entry.accountId(), entry.key(), cluster);
            }
        }
        clusters.flush();
    }

    public Cluster createCluster(String identifier, String nodeType, String username, String password) {
        return createCluster(identifier, nodeType, username, password, null, List.of(), List.of());
    }

    // synchronized like modify/reboot: the container + proxy + port steps must not
    // interleave with another admin call on the same cluster.
    public synchronized Cluster createCluster(String identifier, String nodeType, String username, String password,
                                  String clusterSubnetGroupName, List<String> vpcSecurityGroupIds) {
        return createCluster(identifier, nodeType, username, password, clusterSubnetGroupName,
                vpcSecurityGroupIds, List.of());
    }

    public synchronized Cluster createCluster(String identifier, String nodeType, String username, String password,
                                               String clusterSubnetGroupName, List<String> vpcSecurityGroupIds,
                                               List<String> iamRoleArns) {
        if (clusters.get(identifier).isPresent()) {
            throw new AwsException("ClusterAlreadyExists", "Cluster " + identifier + " already exists", 400);
        }
        // A previous cluster with this identifier may have been deleted without its temp
        // credentials being cleared; drop them so the new cluster starts with none.
        credentialBroker.revokeCluster(clusters.accountId(), identifier);

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setNodeType(nodeType);
        cluster.setMasterUsername(username);
        cluster.setMasterPassword(password);
        cluster.setClusterSubnetGroupName(clusterSubnetGroupName);
        cluster.setVpcSecurityGroupIds(vpcSecurityGroupIds != null ? vpcSecurityGroupIds : List.of());
        cluster.setIamRoleArns(iamRoleArns != null ? List.copyOf(iamRoleArns) : List.of());
        cluster.setClusterStatus("creating");
        clusters.put(identifier, cluster);
        clusters.flush();

        // Start container, then front it with an auth proxy so the advertised endpoint is
        // reachable from outside the Docker network.
        // Hoisted out of the try so a failure after allocateProxyPort() still returns the port.
        int proxyPort = -1;
        try {
            String accountId = clusters.accountId();
            RedshiftContainerHandle handle = containerManager.start(accountId, identifier, username, password);
            proxyPort = allocateProxyPort();
            Endpoint endpoint = proxyEndpoint(proxyPort);
            cluster.setProxyPort(proxyPort);
            proxyManager.startProxy(relayKey(accountId, identifier), proxyPort,
                    handle.getHost(), handle.getPort(), endpoint.getAddress(),
                    username, password, CLUSTER_DB_NAME,
                    passwordValidatorFor(accountId, identifier), cluster.getIamRoleArns());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
            cluster.setEndpoint(endpoint);
            cluster.setClusterStatus("available");
        } catch (AwsException e) {
            boolean proxyStopped = stopProxyAndReleasePortSafely(identifier, proxyPort);
            try { containerManager.stop(clusters.accountId(), identifier); } catch (Exception ex) { LOG.warnv(ex, "Failed to stop container during rollback of cluster {0}", identifier); }
            if (proxyStopped) {
                clusters.delete(identifier);
                credentialBroker.revokeCluster(clusters.accountId(), identifier);
            } else {
                cluster.setClusterStatus("failed");
                clusters.put(identifier, cluster);
            }
            clusters.flush();
            throw e;
        } catch (Exception e) {
            boolean proxyStopped = stopProxyAndReleasePortSafely(identifier, proxyPort);
            try { containerManager.stop(clusters.accountId(), identifier); } catch (Exception ex) { LOG.warnv(ex, "Failed to stop container during rollback of cluster {0}", identifier); }
            if (proxyStopped) {
                clusters.delete(identifier);
                credentialBroker.revokeCluster(clusters.accountId(), identifier);
            } else {
                cluster.setClusterStatus("failed");
                clusters.put(identifier, cluster);
            }
            clusters.flush();
            throw new AwsException("InternalFailure", "Failed to start container: " + e.getMessage(), 500);
        }

        clusters.put(identifier, cluster);
        clusters.flush();
        recordEvent(identifier, "cluster", "Cluster created.");
        return cluster;
    }

    public synchronized Cluster createClusterWithManagedMasterPassword(
            String identifier, String nodeType, String username, String clusterSubnetGroupName,
            List<String> vpcSecurityGroupIds, List<String> iamRoleArns,
            String kmsKeyId, String region) {
        if (secretsManagerService == null) {
            throw new AwsException("InternalFailure", "Secrets Manager is unavailable", 500);
        }
        String password = RandomPasswordGenerator.generate(objectMapper.createObjectNode());
        Cluster cluster = createCluster(identifier, nodeType, username, password,
                clusterSubnetGroupName, vpcSecurityGroupIds, iamRoleArns);
        String secretName = "redshift/" + identifier;
        String secretString = managedMasterSecret(cluster, password);
        Secret secret;
        try {
            secret = secretsManagerService.createSecret(secretName, secretString, null,
                            "Managed master user secret for Redshift cluster " + identifier,
                            kmsKeyId, List.of(), "redshift", region);
        } catch (RuntimeException e) {
            rollbackManagedMasterPasswordCluster(cluster);
            throw e;
        }
        cluster.setMasterPasswordSecretArn(secret.getArn());
        cluster.setMasterPasswordSecretKmsKeyId(kmsKeyId);
        clusters.put(identifier, cluster);
        clusters.flush();
        return cluster;
    }

    private void rollbackManagedMasterPasswordCluster(Cluster cluster) {
        boolean proxyStopped = stopProxyAndReleasePortSafely(
                cluster.getClusterIdentifier(), cluster.getProxyPort());
        try {
            containerManager.stop(clusters.accountId(), cluster.getClusterIdentifier());
        } catch (Exception e) {
            LOG.warnv(e, "Failed to stop managed-password cluster container {0} during rollback",
                    cluster.getClusterIdentifier());
        }
        if (proxyStopped) {
            clusters.delete(cluster.getClusterIdentifier());
            credentialBroker.revokeCluster(clusters.accountId(), cluster.getClusterIdentifier());
        } else {
            cluster.setClusterStatus("failed");
            clusters.put(cluster.getClusterIdentifier(), cluster);
        }
        clusters.flush();
    }

    // ── Zero-ETL integrations ────────────────────────────────────
    //
    // DynamoDB streams replicate into a PostgreSQL landing table through the zero-ETL consumer.

    public synchronized Integration createIntegration(String integrationName, String sourceArn, String targetArn,
                                                      String kmsKeyId, String description,
                                                      Map<String, String> additionalEncryptionContext,
                                                      Map<String, String> tags, String region) {
        if (integrationName == null || integrationName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "IntegrationName is required.", 400);
        }
        if (integrationName.length() > MAX_INTEGRATION_NAME
                || !INTEGRATION_NAME.matcher(integrationName).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "IntegrationName must match " + INTEGRATION_NAME_PATTERN
                            + " and be at most " + MAX_INTEGRATION_NAME + " characters.", 400);
        }
        if (sourceArn == null || sourceArn.isBlank()) {
            throw new AwsException("InvalidParameterValue", "SourceArn is required.", 400);
        }
        if (targetArn == null || targetArn.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TargetArn is required.", 400);
        }
        AwsArnUtils.Arn source = parseZeroEtlArn(sourceArn, "DynamoDB stream");
        if (!"dynamodb".equals(source.service()) || !source.resource().startsWith("table/")
                || !source.resource().contains("/stream/")) {
            throw new AwsException("InvalidParameterValue",
                    "SourceArn must identify a DynamoDB stream.", 400);
        }
        if (streamService == null) {
            throw new AwsException("InternalFailure", "DynamoDB stream service is unavailable.", 500);
        }
        streamService.describeStream(sourceArn);
        AwsArnUtils.Arn target = parseZeroEtlArn(targetArn, "Redshift cluster");
        if (!"redshift".equals(target.service()) || !target.resource().startsWith("cluster:")) {
            throw new AwsException("InvalidParameterValue",
                    "TargetArn must identify a provisioned Redshift cluster.", 400);
        }
        String clusterIdentifier = target.resource().substring("cluster:".length());
        if (clusterIdentifier.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TargetArn must identify a Redshift cluster.", 400);
        }
        describeClusters(clusterIdentifier);
        if (description != null && description.length() > MAX_INTEGRATION_DESCRIPTION) {
            throw new AwsException("InvalidParameterValue",
                    "Description must be at most " + MAX_INTEGRATION_DESCRIPTION + " characters.", 400);
        }
        // AdditionalEncryptionContext only means anything alongside a customer managed key.
        if (additionalEncryptionContext != null && !additionalEncryptionContext.isEmpty()
                && (kmsKeyId == null || kmsKeyId.isBlank())) {
            throw new AwsException("InvalidParameterValue",
                    "AdditionalEncryptionContext is only valid when KMSKeyId is supplied.", 400);
        }

        boolean nameTaken = integrations.scan(k -> true).stream()
                .anyMatch(existing -> integrationName.equals(existing.getIntegrationName()));
        if (nameTaken) {
            throw new AwsException("IntegrationAlreadyExistsFault",
                    "The integration " + integrationName + " already exists.", 400);
        }

        String integrationId = UUID.randomUUID().toString();
        Integration integration = new Integration();
        integration.setAccountId(integrations.accountId());
        integration.setIntegrationArn("arn:aws:redshift:" + region + ":" + regionResolver.getAccountId()
                + ":integration:" + integrationId);
        integration.setIntegrationName(integrationName);
        integration.setSourceArn(sourceArn);
        integration.setTargetArn(targetArn);
        integration.setSourceStreamArn(sourceArn);
        integration.setTargetClusterIdentifier(clusterIdentifier);
        integration.setLandingTableName("floci_zetl_" + integrationId.replace('-', '_'));
        integration.setCheckpointSequenceNumber(null);
        integration.setRetryCount(0);
        integration.setLastError(null);
        integration.setPollingEnabled(true);
        // Floci approximation: report `syncing` while the backfill scan runs, then `active`.
        integration.setStatus("syncing");
        integration.setKmsKeyId(kmsKeyId);
        integration.setCreateTime(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        integration.setDescription(description);
        integration.setAdditionalEncryptionContext(additionalEncryptionContext);
        integration.setTags(tags);
        integrations.put(integrationId, integration);
        LOG.infov("Created Redshift zero-ETL integration: {0}", integration.getIntegrationArn());
        return integration;
    }

    private static AwsArnUtils.Arn parseZeroEtlArn(String arn, String resourceType) {
        try {
            return AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue", resourceType + " ARN is invalid.", 400);
        }
    }

    /**
     * Lists integrations with the documented filters and marker pagination.
     *
     * <p>An unknown {@code IntegrationArn} is {@code IntegrationNotFoundFault}, measured against
     * real Redshift. An account with no integrations at all is an empty list rather than an error,
     * and no {@code Marker} is emitted on the terminal page.
     */
    public IntegrationPage describeIntegrations(String integrationArn, Integer maxRecords, String marker,
                                                List<IntegrationFilter> filters) {
        List<Integration> all = integrations.scan(k -> true).stream()
                .sorted(Comparator.comparing(Integration::getIntegrationArn))
                .toList();

        if (integrationArn != null && !integrationArn.isBlank()) {
            Integration match = all.stream()
                    .filter(integration -> integrationArn.equals(integration.getIntegrationArn()))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("IntegrationNotFoundFault",
                            "The requested integration doesn't exist.", 404));
            all = List.of(match);
        }

        for (IntegrationFilter filter : filters == null ? List.<IntegrationFilter>of() : filters) {
            all = all.stream().filter(integration -> matchesFilter(integration, filter)).toList();
        }

        int pageSize = resolveMaxRecords(maxRecords);
        int from = 0;
        if (marker != null && !marker.isBlank()) {
            int previous = -1;
            for (int i = 0; i < all.size(); i++) {
                if (marker.equals(all.get(i).getIntegrationArn())) {
                    previous = i;
                    break;
                }
            }
            if (previous < 0) {
                throw new AwsException("InvalidParameterValue", "Invalid Marker specified.", 400);
            }
            from = previous + 1;
        }

        List<Integration> page = all.subList(Math.min(from, all.size()), Math.min(from + pageSize, all.size()));
        boolean more = from + pageSize < all.size();
        // The marker is the last ARN already returned, so a page resumes after a known record
        // rather than at an offset a concurrent create could shift.
        String next = more && !page.isEmpty() ? page.get(page.size() - 1).getIntegrationArn() : null;
        return new IntegrationPage(List.copyOf(page), next);
    }

    public List<Integration> listDynamoDbZeroEtlIntegrations() {
        return integrations.scanAllAccountEntries(key -> true).stream()
                .filter(entry -> entry.value().getSourceStreamArn() != null
                        && !entry.value().getSourceStreamArn().isBlank())
                .map(entry -> {
                    Integration integration = entry.value();
                    if (integration.getAccountId() == null) {
                        integration.setAccountId(entry.accountId());
                    }
                    return integration;
                })
                .toList();
    }

    public synchronized void updateIntegrationRuntime(String accountId, String integrationArn,
                                                       String checkpointSequenceNumber,
                                                       boolean successful, String error) {
        for (String key : integrations.keysForAccount(accountId)) {
            Optional<Integration> stored = integrations.getForAccount(accountId, key);
            if (stored.isEmpty() || !integrationArn.equals(stored.get().getIntegrationArn())) {
                continue;
            }
            Integration integration = stored.get();
            if (successful) {
                integration.setCheckpointSequenceNumber(checkpointSequenceNumber);
                integration.setRetryCount(0);
                integration.setLastError(null);
                integration.setStatus("active");
            } else {
                integration.setRetryCount(integration.getRetryCount() + 1);
                integration.setLastError(error);
                integration.setStatus("failed");
            }
            integrations.putForAccount(accountId, key, integration);
            return;
        }
        throw new AwsException("IntegrationNotFoundFault", "The requested integration doesn't exist.", 404);
    }

    public synchronized void updateIntegrationBackfillProgress(String accountId, String integrationArn,
                                                                String backfillLastEvaluatedKey,
                                                                boolean backfillCompleted) {
        for (String key : integrations.keysForAccount(accountId)) {
            Optional<Integration> stored = integrations.getForAccount(accountId, key);
            if (stored.isEmpty() || !integrationArn.equals(stored.get().getIntegrationArn())) {
                continue;
            }
            Integration integration = stored.get();
            integration.setBackfillLastEvaluatedKey(backfillLastEvaluatedKey);
            integration.setBackfillCompleted(backfillCompleted);
            integration.setRetryCount(0);
            integration.setLastError(null);
            integration.setStatus(backfillCompleted ? "active" : "syncing");
            integrations.putForAccount(accountId, key, integration);
            return;
        }
        throw new AwsException("IntegrationNotFoundFault", "The requested integration doesn't exist.", 404);
    }

    /** One page of integrations plus the marker to continue with, or {@code null} at the end. */
    public record IntegrationPage(List<Integration> integrations, String marker) {}

    /** One {@code Filters.DescribeIntegrationsFilter.N} entry. */
    public record IntegrationFilter(String name, List<String> values) {}

    private static boolean matchesFilter(Integration integration, IntegrationFilter filter) {
        String name = filter.name() == null ? "" : filter.name();
        List<String> values = filter.values() == null ? List.of() : filter.values();
        String actual = switch (name) {
            case "integration-arn" -> integration.getIntegrationArn();
            case "source-arn" -> integration.getSourceArn();
            case "status" -> integration.getStatus();
            // source-types filters on the source's AWS service, which the ARN's third field names.
            case "source-types" -> sourceType(integration.getSourceArn());
            default -> throw new AwsException("InvalidParameterValue",
                    "Unrecognized filter name: " + name, 400);
        };
        return actual != null && values.contains(actual);
    }

    private static String sourceType(String sourceArn) {
        if (sourceArn == null) {
            return null;
        }
        String[] parts = sourceArn.split(":");
        return parts.length > 2 ? parts[2] : null;
    }

    private static int resolveMaxRecords(Integer maxRecords) {
        if (maxRecords == null) {
            return MAX_INTEGRATION_RECORDS;
        }
        if (maxRecords < MIN_INTEGRATION_RECORDS || maxRecords > MAX_INTEGRATION_RECORDS) {
            throw new AwsException("InvalidParameterValue",
                    "MaxRecords must be between " + MIN_INTEGRATION_RECORDS + " and "
                            + MAX_INTEGRATION_RECORDS + ".", 400);
        }
        return maxRecords;
    }

    public synchronized Integration deleteIntegration(String integrationArn) {
        if (integrationArn == null || integrationArn.isBlank()) {
            throw new AwsException("InvalidParameterValue", "IntegrationArn is required.", 400);
        }
        for (String key : integrations.keys()) {
            Optional<Integration> stored = integrations.get(key);
            if (stored.isPresent() && integrationArn.equals(stored.get().getIntegrationArn())) {
                integrations.delete(key);
                LOG.infov("Deleted Redshift zero-ETL integration: {0}", integrationArn);
                return stored.get();
            }
        }
        throw new AwsException("IntegrationNotFoundFault", "The requested integration doesn't exist.", 404);
    }

    public record Page<T>(List<T> items, String marker) {}

    private static <T> Page<T> page(List<T> items, Integer maxRecords, String marker, Function<T, String> key) {
        int pageSize = resolveMaxRecords(maxRecords);
        int from = 0;
        if (marker != null) {
            while (from < items.size() && !marker.equals(key.apply(items.get(from)))) {
                from++;
            }
            if (from == items.size()) {
                throw new AwsException("InvalidParameterValue", "Invalid Marker specified.", 400);
            }
            from++;
        }
        List<T> result = items.subList(from, Math.min(from + pageSize, items.size()));
        return new Page<>(List.copyOf(result), from + pageSize < items.size() ? key.apply(result.getLast()) : null);
    }

    public Page<Cluster> describeClusterDbRevisions(String identifier, Integer maxRecords, String marker) {
        return page(describeClusters(identifier).stream()
                .sorted(Comparator.comparing(Cluster::getClusterIdentifier)).toList(),
                maxRecords, marker, Cluster::getClusterIdentifier);
    }

    public synchronized EventSubscription createEventSubscription(String name, String topicArn, String sourceType,
            List<String> sourceIds, List<String> categories, String severity, Boolean enabled, Map<String, String> tags) {
        if (name == null || name.length() > 255 || !name.matches("[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*")) {
            throw new AwsException("InvalidParameterValue", "Invalid SubscriptionName.", 400);
        }
        String key = subscriptionKey(name);
        if (eventSubscriptions.get(key).isPresent()) {
            throw new AwsException("SubscriptionAlreadyExist", "Subscription " + name + " already exists.", 400);
        }
        validateSubscription(topicArn, sourceType, sourceIds, categories, severity);
        EventSubscription subscription = new EventSubscription(name, regionResolver.getRegion(),
                regionResolver.getAccountId(), topicArn, "active", Instant.now().toString(), sourceType,
                sourceIds, categories, severity, enabled == null || enabled, tags);
        eventSubscriptions.put(key, subscription);
        eventSubscriptions.flush();
        return subscription;
    }

    public synchronized EventSubscription modifyEventSubscription(String name, String topicArn, String sourceType,
            List<String> sourceIds, List<String> categories, String severity, Boolean enabled) {
        EventSubscription previous = getEventSubscription(name);
        String desiredTopic = topicArn == null ? previous.snsTopicArn() : topicArn;
        String desiredType = sourceType == null ? previous.sourceType() : sourceType;
        List<String> desiredIds = sourceIds == null ? previous.sourceIds() : sourceIds;
        List<String> desiredCategories = categories == null ? previous.eventCategories() : categories;
        String desiredSeverity = severity == null ? previous.severity() : severity;
        validateSubscription(desiredTopic, desiredType, desiredIds, desiredCategories, desiredSeverity);
        EventSubscription updated = new EventSubscription(previous.name(), previous.region(), previous.customerAwsId(),
                desiredTopic, "active", previous.creationTime(), desiredType, desiredIds, desiredCategories,
                desiredSeverity, enabled == null ? previous.enabled() : enabled, previous.tags());
        eventSubscriptions.put(subscriptionKey(name), updated);
        eventSubscriptions.flush();
        return updated;
    }

    public synchronized void deleteEventSubscription(String name) {
        getEventSubscription(name);
        eventSubscriptions.delete(subscriptionKey(name));
        eventSubscriptions.flush();
    }

    public synchronized Page<EventSubscription> describeEventSubscriptions(String name, Integer maxRecords,
            String marker, List<String> tagKeys, List<String> tagValues) {
        List<EventSubscription> candidates = name == null
                ? eventSubscriptions.scan(key -> key.startsWith(regionResolver.getRegion() + ":"))
                : List.of(getEventSubscription(name));
        List<EventSubscription> matching = candidates.stream()
                .filter(subscription -> subscription.tags().entrySet().stream().anyMatch(tag ->
                        (tagKeys.isEmpty() || tagKeys.contains(tag.getKey()))
                                && (tagValues.isEmpty() || tagValues.contains(tag.getValue())))
                        || tagKeys.isEmpty() && tagValues.isEmpty())
                .sorted(Comparator.comparing(EventSubscription::name)).toList();
        Page<EventSubscription> result = page(matching, maxRecords, marker, EventSubscription::name);
        List<EventSubscription> observed = result.items().stream().map(subscription -> {
            String status = subscriptionTopicStatus(subscription);
            if (!status.equals(subscription.status())) {
                subscription = subscription.withStatus(status);
                eventSubscriptions.put(subscriptionKey(subscription.name()), subscription);
            }
            return subscription;
        }).toList();
        eventSubscriptions.flush();
        return new Page<>(observed, result.marker());
    }

    private String subscriptionKey(String name) {
        return regionResolver.getRegion() + ":" + name;
    }

    private EventSubscription getEventSubscription(String name) {
        return eventSubscriptions.get(subscriptionKey(name)).orElseThrow(() ->
                new AwsException("SubscriptionNotFound", "Subscription " + name + " not found.", 404));
    }

    private void validateSubscription(String topicArn, String sourceType, List<String> sourceIds,
                                      List<String> categories, String severity) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(topicArn == null ? "" : topicArn);
        } catch (IllegalArgumentException exception) {
            throw new AwsException("SNSInvalidTopic", "SnsTopicArn must be an SNS topic ARN.", 400);
        }
        if (!"sns".equals(arn.service()) || !regionResolver.getRegion().equals(arn.region())
                || arn.resource().endsWith(".fifo")) {
            throw new AwsException("SNSInvalidTopic", "SnsTopicArn must identify a standard topic in this region.", 400);
        }
        try {
            snsService.getTopicAttributes(topicArn, regionResolver.getRegion());
        } catch (AwsException exception) {
            switch (exception.getErrorCode()) {
                case "NotFound" -> throw new AwsException("SNSTopicArnNotFound", "The SNS topic does not exist.", 404);
                case "AuthorizationError" -> throw new AwsException("SNSNoAuthorization", exception.getMessage(), 400);
                default -> throw exception;
            }
        }
        if (sourceType != null && !Set.of("cluster", "cluster-parameter-group", "cluster-security-group",
                "cluster-snapshot", "scheduled-action").contains(sourceType)) {
            throw new AwsException("InvalidParameterValue", "Invalid SourceType: " + sourceType, 400);
        }
        if (!sourceIds.isEmpty() && sourceType == null) {
            throw new AwsException("InvalidParameterCombination", "SourceType is required with SourceIds.", 400);
        }
        for (String id : sourceIds) {
            boolean exists = switch (sourceType) {
                case "cluster" -> clusters.get(id).isPresent();
                case "cluster-parameter-group" -> parameterGroups.get(id).isPresent();
                case "cluster-snapshot" -> snapshots.get(id).isPresent();
                default -> false;
            };
            if (!exists) {
                throw new AwsException("SourceNotFound", "Event source " + id + " not found.", 404);
            }
        }
        for (String category : categories) {
            if (!Set.of("configuration", "management", "monitoring", "security", "pending").contains(category)) {
                throw new AwsException("SubscriptionCategoryNotFound", "Unknown event category: " + category, 404);
            }
        }
        if (severity != null && !Set.of("INFO", "ERROR").contains(severity)) {
            throw new AwsException("SubscriptionSeverityNotFound", "Unknown severity: " + severity, 404);
        }
    }

    private String subscriptionTopicStatus(EventSubscription subscription) {
        try {
            snsService.getTopicAttributes(subscription.snsTopicArn(), subscription.region());
            return "active";
        } catch (AwsException exception) {
            return switch (exception.getErrorCode()) {
                case "NotFound" -> "topic-not-exist";
                case "AuthorizationError" -> "no-permission";
                default -> throw exception;
            };
        }
    }

    private synchronized void publishEvent(RedshiftEvent event) {
        for (EventSubscription subscription : eventSubscriptions.scan(key -> key.startsWith(event.region() + ":"))) {
            if (!subscription.enabled()
                    || subscription.sourceType() != null && !subscription.sourceType().equals(event.sourceType())
                    || !subscription.sourceIds().isEmpty() && !subscription.sourceIds().contains(event.sourceIdentifier())
                    || !subscription.eventCategories().isEmpty() && !subscription.eventCategories().contains("management")
                    || "ERROR".equals(subscription.severity())) {
                continue;
            }
            try {
                String status = subscriptionTopicStatus(subscription);
                if ("active".equals(status)) {
                    String message = new JsonObject().put("Event Source", event.sourceType())
                            .put("Event Time", event.date()).put("Source ID", event.sourceIdentifier())
                            .put("Event ID", event.id()).put("Event Message", event.message()).encode();
                    snsService.publish(subscription.snsTopicArn(), null, message,
                            "Amazon Redshift Event Notification", event.region());
                }
                if (!status.equals(subscription.status())) {
                    eventSubscriptions.put(subscriptionKey(subscription.name()), subscription.withStatus(status));
                }
            } catch (RuntimeException exception) {
                // Notification failures cannot roll back a completed resource operation.
                LOG.warnv(exception, "Failed to publish Redshift event {0} to subscription {1}", event.id(), subscription.name());
            }
        }
        eventSubscriptions.flush();
    }

    private void recordEvent(String identifier, String sourceType, String message) {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        RedshiftEvent recorded = new RedshiftEvent(id, regionResolver.getRegion(), identifier, sourceType, message, now.toString());
        events.put(id, recorded);
        Instant oldest = now.minusSeconds(14L * 24 * 60 * 60);
        for (RedshiftEvent event : events.scan(key -> true)) {
            if (Instant.parse(event.date()).isBefore(oldest)) {
                events.delete(event.id());
            }
        }
        events.flush();
        publishEvent(recorded);
    }

    public record EventPage(List<RedshiftEvent> events, String marker) {}

    public EventPage describeEvents(String identifier, String sourceType, Instant startTime, Instant endTime,
                                    Integer duration, Integer maxRecords, String marker) {
        if (sourceType != null && !Set.of("cluster", "cluster-parameter-group", "cluster-security-group",
                "cluster-snapshot", "scheduled-action").contains(sourceType)) {
            throw new AwsException("InvalidParameterValue", "Invalid SourceType: " + sourceType, 400);
        }
        if (identifier != null && sourceType == null) {
            throw new AwsException("InvalidParameterCombination", "SourceType is required with SourceIdentifier.", 400);
        }
        if (duration != null && (duration < 0 || duration > 20160)) {
            throw new AwsException("InvalidParameterValue", "Duration must be between 0 and 20160 minutes.", 400);
        }
        if (startTime != null && duration != null) {
            throw new AwsException("InvalidParameterCombination", "StartTime and Duration cannot both be specified.", 400);
        }
        Instant end = endTime == null ? Instant.now() : endTime;
        Instant start = startTime == null ? end.minusSeconds((duration == null ? 60L : duration) * 60L) : startTime;
        if (start.isAfter(end)) {
            throw new AwsException("InvalidParameterValue", "StartTime must not be after EndTime.", 400);
        }
        int pageSize = resolveMaxRecords(maxRecords);
        List<RedshiftEvent> matching = events.scan(key -> true).stream()
                .filter(event -> regionResolver.getRegion().equals(event.region()))
                .filter(event -> identifier == null || identifier.equals(event.sourceIdentifier()))
                .filter(event -> sourceType == null || sourceType.equals(event.sourceType()))
                .filter(event -> !Instant.parse(event.date()).isBefore(start) && !Instant.parse(event.date()).isAfter(end))
                .sorted(Comparator.comparing((RedshiftEvent event) -> Instant.parse(event.date()))
                        .thenComparing(RedshiftEvent::id))
                .toList();
        int from = 0;
        if (marker != null) {
            while (from < matching.size() && !marker.equals(matching.get(from).id())) {
                from++;
            }
            if (from == matching.size()) {
                throw new AwsException("InvalidParameterValue", "Invalid Marker specified.", 400);
            }
            from++;
        }
        List<RedshiftEvent> page = matching.subList(from, Math.min(from + pageSize, matching.size()));
        String next = from + pageSize < matching.size() ? page.getLast().id() : null;
        return new EventPage(List.copyOf(page), next);
    }

    public List<Cluster> describeClusters(String identifier) {
        if (identifier != null) {
            Optional<Cluster> cluster = clusters.get(identifier);
            if (cluster.isEmpty()) {
                throw new AwsException("ClusterNotFound", "Cluster " + identifier + " not found", 404);
            }
            return List.of(cluster.get());
        }
        return clusters.scan(k -> true);
    }

    public List<Cluster> describeClustersForAccount(String accountId, String identifier) {
        if (identifier != null) {
            Optional<Cluster> cluster = clusters.getForAccount(accountId, identifier);
            if (cluster.isEmpty()) {
                throw new AwsException("ClusterNotFound", "Cluster " + identifier + " not found", 404);
            }
            return List.of(cluster.get());
        }
        return clusters.scanForAccount(accountId, k -> true);
    }

    public synchronized Cluster deleteCluster(String identifier) {
        Optional<Cluster> clusterOpt = clusters.get(identifier);
        if (clusterOpt.isEmpty()) {
            throw new AwsException("ClusterNotFound", "Cluster " + identifier + " not found", 404);
        }
        Cluster cluster = clusterOpt.get();

        // Tear down the auth proxy and return its port before stopping the container.
        // If it fails, abort deletion so the metadata remains and the user can retry.
        if (!stopProxyAndReleasePortSafely(identifier, cluster.getProxyPort())) {
            throw new AwsException("InternalFailure",
                    "Failed to stop auth proxy; cluster " + identifier + " was not deleted", 500);
        }

        containerManager.stop(clusters.accountId(), identifier);
        clusters.delete(identifier);
        clusters.flush();
        // Invalidate any GetClusterCredentials passwords so a cluster later recreated with this
        // identifier does not accept them as master-equivalent.
        credentialBroker.revokeCluster(clusters.accountId(), identifier);

        if (cluster.getMasterPasswordSecretArn() != null && secretsManagerService != null) {
            try {
                secretsManagerService.deleteSecret(cluster.getMasterPasswordSecretArn(), null, true,
                        regionResolver.getRegion());
            } catch (AwsException e) {
                LOG.warnv(e, "Failed to remove managed master secret for cluster {0}", identifier);
            }
        }

        cluster.setClusterStatus("deleting");
        recordEvent(identifier, "cluster", "Cluster deleted.");
        return cluster;
    }

    public synchronized Cluster modifyCluster(String clusterIdentifier, String nodeType, Integer numberOfNodes,
                                               String masterUserPassword, String clusterParameterGroupName,
                                               List<String> vpcSecurityGroupIds) {
        Cluster cluster = clusters.get(clusterIdentifier)
                .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404));

        // alterUserPassword runs before any mutation of the cluster object (a live reference
        // from HybridStorage, not a copy), if it throws, no metadata has been changed yet.
        if (masterUserPassword != null && !masterUserPassword.isBlank()) {
            containerManager.alterUserPassword(clusters.accountId(), clusterIdentifier,
                    cluster.getMasterUsername(), masterUserPassword);
            cluster.setMasterPassword(masterUserPassword);
            // Keep the proxy's password check in sync so new connections use the new secret.
            proxyManager.updateMasterPassword(
                    relayKey(clusters.accountId(), clusterIdentifier), masterUserPassword);
            updateManagedMasterSecret(cluster, masterUserPassword);
        }

        // NodeType only updates metadata, it does not resize the underlying Postgres container
        // (Redshift node-count has no equivalent here). NumberOfNodes is accepted for API-shape
        // compatibility but is not modelled or stored anywhere, known gap, see plan Task 9.
        if (nodeType != null && !nodeType.isBlank()) {
            cluster.setNodeType(nodeType);
        }
        if (clusterParameterGroupName != null && !clusterParameterGroupName.isBlank()) {
            cluster.setClusterParameterGroupName(clusterParameterGroupName);
        }
        if (vpcSecurityGroupIds != null && !vpcSecurityGroupIds.isEmpty()) {
            cluster.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        }

        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        recordEvent(clusterIdentifier, "cluster", "Cluster modified.");
        return cluster;
    }

    public synchronized Cluster modifyClusterIamRoles(String clusterIdentifier, List<String> addIamRoles,
                                                      List<String> removeIamRoles) {
        Cluster cluster = clusters.get(clusterIdentifier)
                .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404));

        // Validate every ARN before mutating: the cluster object is a live reference from HybridStorage.
        addIamRoles.forEach(RedshiftService::requireIamRoleArn);
        removeIamRoles.forEach(RedshiftService::requireIamRoleArn);

        Set<String> roles = new LinkedHashSet<>(cluster.getIamRoleArns());
        roles.addAll(addIamRoles);
        roles.removeAll(removeIamRoles);
        cluster.setIamRoleArns(List.copyOf(roles));

        proxyManager.updateIamRoles(relayKey(clusters.accountId(), clusterIdentifier), cluster.getIamRoleArns());
        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        return cluster;
    }

    private static void requireIamRoleArn(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue", "Invalid IAM role ARN: " + arn, 400);
        }
        if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith("role/")) {
            throw new AwsException("InvalidParameterValue", "Not an IAM role ARN: " + arn, 400);
        }
    }

    private void updateManagedMasterSecret(Cluster cluster, String password) {
        if (cluster.getMasterPasswordSecretArn() == null || secretsManagerService == null) {
            return;
        }
        secretsManagerService.putSecretValue(cluster.getMasterPasswordSecretArn(),
                        managedMasterSecret(cluster, password), null, null, regionResolver.getRegion(),
                        List.of("AWSCURRENT"));
    }

    private String managedMasterSecret(Cluster cluster, String password) {
        try {
            return objectMapper.createObjectNode()
                    .put("engine", "redshift")
                    .put("username", cluster.getMasterUsername())
                    .put("password", password)
                    .put("host", cluster.getEndpoint() == null ? "" : cluster.getEndpoint().getAddress())
                    .put("port", cluster.getEndpoint() == null ? 0 : cluster.getEndpoint().getPort())
                    .put("dbname", CLUSTER_DB_NAME)
                    .toString();
        } catch (RuntimeException e) {
            throw new AwsException("InternalFailure", "Failed to encode managed master secret", 500);
        }
    }

    public synchronized Cluster rebootCluster(String clusterIdentifier) {
        Cluster cluster = clusters.get(clusterIdentifier)
                .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404));

        // The container backing a cluster has no persistent volume (see RedshiftContainerManager),
        // so a plain stop+recreate would silently drop the cluster's data. Dump before stopping and
        // restore immediately after starting, using a throwaway temp file, no Snapshot resource is
        // created or exposed to the caller.
        Path tempDump;
        try {
            tempDump = Files.createTempFile("redshift-reboot-" + clusterIdentifier, ".sql");
        } catch (IOException e) {
            throw new AwsException("InternalFailure", "Failed to prepare reboot dump file: " + e.getMessage(), 500);
        }

        // Hoisted so a failure after the proxy is (re)started still tears it down and
        // returns the port, matching createCluster/restoreFromClusterSnapshot rollback.
        int proxyPort = cluster.getProxyPort() > 0 ? cluster.getProxyPort() : -1;
        boolean originalTornDown = false; // original proxy + container already stopped
        boolean rebooted = false;
        try {
            String accountId = clusters.accountId();
            String key = relayKey(accountId, clusterIdentifier);

            containerManager.takeSnapshot(accountId, clusterIdentifier, cluster.getMasterUsername(), tempDump);
            proxyManager.stopProxy(key);
            containerManager.stop(accountId, clusterIdentifier);
            originalTornDown = true;

            String password = cluster.getMasterPassword() != null ? cluster.getMasterPassword() : "admin";
            RedshiftContainerHandle handle = containerManager.start(
                    accountId, clusterIdentifier, cluster.getMasterUsername(), password);

            // Reuse the stored proxy port so the advertised endpoint is unchanged by a reboot.
            if (proxyPort < 0) {
                proxyPort = allocateProxyPort();
            }
            usedPorts.add(proxyPort);
            Endpoint endpoint = proxyEndpoint(proxyPort);
            cluster.setProxyPort(proxyPort);
            proxyManager.startProxy(key, proxyPort, handle.getHost(), handle.getPort(),
                    endpoint.getAddress(), cluster.getMasterUsername(), password, CLUSTER_DB_NAME,
                    passwordValidatorFor(accountId, clusterIdentifier), cluster.getIamRoleArns());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
            cluster.setEndpoint(endpoint);

            containerManager.restoreSnapshot(accountId, clusterIdentifier, cluster.getMasterUsername(), tempDump);
            cluster.setClusterStatus("available");
            rebooted = true;
        } catch (AwsException e) {
            rollbackReboot(clusterIdentifier, originalTornDown);
            if (originalTornDown) {
                cluster.setClusterStatus("failed");
            }
            clusters.flush();
            throw e;
        } catch (Exception e) {
            rollbackReboot(clusterIdentifier, originalTornDown);
            if (originalTornDown) {
                cluster.setClusterStatus("failed");
            }
            clusters.flush();
            throw new AwsException("InternalFailure", "Failed to reboot cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
        } finally {
            if (rebooted) {
                try {
                    Files.deleteIfExists(tempDump);
                } catch (IOException ex) {
                    LOG.warnv(ex, "Failed to clean up temporary dump file {0} after rebooting cluster {1}", tempDump, clusterIdentifier);
                }
            } else {
                if (originalTornDown) {
                    // Once the original container is torn down it holds no volume, so this dump can
                    // be the only surviving copy of the cluster's data, keep it for manual recovery.
                    LOG.warnv("Reboot of cluster {0} did not complete; retained pre-reboot data dump at {1}",
                            clusterIdentifier, tempDump);
                } else {
                    try {
                        Files.deleteIfExists(tempDump);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        recordEvent(clusterIdentifier, "cluster", "Cluster rebooted.");
        return cluster;
    }

    // ── Snapshot Operations ──────────────────────────────────────────────────

    // AWS constrains a snapshot identifier to 1-255 characters, first a letter, then
    // letters, digits or single hyphens (no trailing or doubled hyphen). Enforcing that
    // here also keeps the value safe to splice into the dump file path below: '.', '/'
    // and '\' can never appear, so it cannot escape the account's dump directory.
    private static void validateSnapshotIdentifier(String id) {
        if (id == null || !id.matches("[a-zA-Z][a-zA-Z0-9-]{0,254}")
                || id.contains("--") || id.endsWith("-")) {
            throw new AwsException("InvalidParameterValue",
                    "SnapshotIdentifier must be 1-255 characters, start with a letter, and contain "
                    + "only letters, digits and non-consecutive hyphens", 400);
        }
    }

    /** Absolute, normalised {@code <persistentPath>/redshift-dumps/<accountId>} for this request's account. */
    private Path accountDumpDir() {
        return Paths.get(config.storage().persistentPath())
                .resolve("redshift-dumps")
                .resolve(clusters.accountId())
                .toAbsolutePath()
                .normalize();
    }

    /**
     * A stored {@code sqlDump} path is trusted only if it still resolves inside this account's
     * dump directory. Guards restore/delete against a path persisted by an older, unvalidated
     * createSnapshot (or by a different account).
     */
    private boolean isTrustedDumpPath(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return false;
        }
        Path dir = accountDumpDir();
        return Paths.get(storedPath).toAbsolutePath().normalize().startsWith(dir);
    }

    public synchronized Snapshot createSnapshot(String snapshotIdentifier, String clusterIdentifier) {
        validateSnapshotIdentifier(snapshotIdentifier);
        Optional<Cluster> clusterOpt = clusters.get(clusterIdentifier);
        if (clusterOpt.isEmpty()) {
            throw new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404);
        }
        if (snapshots.get(snapshotIdentifier).isPresent()) {
            throw new AwsException("ClusterSnapshotAlreadyExists", "Snapshot " + snapshotIdentifier + " already exists", 400);
        }

        Cluster cluster = clusterOpt.get();
        Snapshot snapshot = new Snapshot();
        snapshot.setSnapshotIdentifier(snapshotIdentifier);
        snapshot.setClusterIdentifier(clusterIdentifier);
        snapshot.setStatus("available");
        snapshot.setMasterUsername(cluster.getMasterUsername());
        snapshot.setMasterPassword(cluster.getMasterPassword());
        if (cluster.getEndpoint() != null) {
            snapshot.setPort(cluster.getEndpoint().getPort());
        } else {
            snapshot.setPort(5439);
        }

        Path dumpDir = accountDumpDir();
        // Defence in depth: validateSnapshotIdentifier already rejects path separators,
        // but keep the containment check so the dump can never land outside the account dir.
        Path dumpFile = dumpDir.resolve(snapshotIdentifier + ".sql").normalize();
        if (!dumpFile.startsWith(dumpDir)) {
            throw new AwsException("InvalidParameterValue", "Invalid snapshot identifier", 400);
        }
        try {
            Files.createDirectories(dumpDir);
            containerManager.takeSnapshot(clusters.accountId(), clusterIdentifier, cluster.getMasterUsername(), dumpFile);
            snapshot.setSqlDump(dumpFile.toString());
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalFailure", "Failed to take snapshot for cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
        }

        snapshots.put(snapshotIdentifier, snapshot);
        snapshots.flush();
        recordEvent(snapshotIdentifier, "cluster-snapshot", "Cluster snapshot created.");
        return snapshot;
    }

    public synchronized Snapshot copyClusterSnapshot(String sourceIdentifier, String sourceClusterIdentifier,
                                                      String targetIdentifier, Integer retentionPeriod) {
        validateSnapshotIdentifier(sourceIdentifier);
        validateSnapshotIdentifier(targetIdentifier);
        Snapshot source = snapshots.get(sourceIdentifier)
                .filter(snapshot -> sourceClusterIdentifier == null
                        || sourceClusterIdentifier.equals(snapshot.getClusterIdentifier()))
                .orElseThrow(() -> new AwsException("ClusterSnapshotNotFound",
                        "Snapshot " + sourceIdentifier + " not found", 404));
        if (snapshots.get(targetIdentifier).isPresent()) {
            throw new AwsException("ClusterSnapshotAlreadyExists", "Snapshot " + targetIdentifier + " already exists", 400);
        }
        if (!"available".equals(source.getStatus())) {
            throw new AwsException("InvalidClusterSnapshotState", "Source snapshot is not available.", 400);
        }
        int retention = retentionPeriod == null ? -1 : retentionPeriod;
        if (retention != -1 && (retention < 1 || retention > 3653)) {
            throw new AwsException("InvalidParameterValue", "ManualSnapshotRetentionPeriod must be -1 or between 1 and 3653.", 400);
        }
        if (!isTrustedDumpPath(source.getSqlDump()) || !Files.isRegularFile(Path.of(source.getSqlDump()))) {
            throw new AwsException("InvalidClusterSnapshotState", "Source snapshot data is unavailable.", 400);
        }
        Path target = accountDumpDir().resolve(targetIdentifier + ".sql");
        Path temporary = null;
        try {
            Files.createDirectories(accountDumpDir());
            temporary = Files.createTempFile(accountDumpDir(), "snapshot-copy-", ".sql");
            Files.copy(Path.of(source.getSqlDump()), temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new AwsException("InternalFailure", "Failed to copy snapshot data: " + exception.getMessage(), 500);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    LOG.warnv(exception, "Failed to clean up temporary snapshot copy {0}", temporary);
                }
            }
        }
        Snapshot copy = new Snapshot(targetIdentifier, source.getClusterIdentifier(), "available", source.getPort(),
                source.getMasterUsername(), target.toString());
        copy.setMasterPassword(source.getMasterPassword());
        copy.setTags(new LinkedHashMap<>(source.getTags()));
        copy.setManualSnapshotRetentionPeriod(retention);
        snapshots.put(targetIdentifier, copy);
        snapshots.flush();
        recordEvent(targetIdentifier, "cluster-snapshot", "Cluster snapshot copied.");
        return copy;
    }

    public List<Snapshot> describeSnapshots(String snapshotIdentifier, String clusterIdentifier) {
        if (snapshotIdentifier != null && !snapshotIdentifier.isBlank()) {
            Optional<Snapshot> snapshot = snapshots.get(snapshotIdentifier);
            if (snapshot.isEmpty()) {
                throw new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotIdentifier + " not found", 404);
            }
            return List.of(snapshot.get());
        }
        if (clusterIdentifier != null && !clusterIdentifier.isBlank()) {
            return snapshots.scan(k -> true).stream()
                    .filter(s -> clusterIdentifier.equals(s.getClusterIdentifier()))
                    .toList();
        }
        return snapshots.scan(k -> true);
    }

    public List<Snapshot> describeSnapshots(String snapshotIdentifier) {
        return describeSnapshots(snapshotIdentifier, null);
    }

    public Optional<Snapshot> getSnapshot(String snapshotIdentifier) {
        return snapshots.get(snapshotIdentifier);
    }

    public synchronized Snapshot deleteSnapshot(String snapshotIdentifier) {
        Optional<Snapshot> snapshotOpt = snapshots.get(snapshotIdentifier);
        if (snapshotOpt.isEmpty()) {
            throw new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotIdentifier + " not found", 404);
        }
        Snapshot snapshot = snapshotOpt.get();
        snapshots.delete(snapshotIdentifier);
        snapshots.flush();
        if (isTrustedDumpPath(snapshot.getSqlDump())) {
            try {
                Files.deleteIfExists(Paths.get(snapshot.getSqlDump()));
            } catch (IOException e) {
                // ignore
            }
        }
        snapshot.setStatus("deleted");
        recordEvent(snapshotIdentifier, "cluster-snapshot", "Cluster snapshot deleted.");
        return snapshot;
    }

    public Cluster restoreFromClusterSnapshot(String clusterIdentifier, String snapshotIdentifier) {
        return restoreFromClusterSnapshot(clusterIdentifier, snapshotIdentifier, null);
    }

    public synchronized Cluster restoreFromClusterSnapshot(String clusterIdentifier, String snapshotIdentifier, String nodeType) {
        if (clusters.get(clusterIdentifier).isPresent()) {
            throw new AwsException("ClusterAlreadyExists", "Cluster " + clusterIdentifier + " already exists", 400);
        }

        Optional<Snapshot> snapshotOpt = snapshots.get(snapshotIdentifier);
        if (snapshotOpt.isEmpty()) {
            throw new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotIdentifier + " not found", 404);
        }

        Snapshot snapshot = snapshotOpt.get();

        // Validate the stored dump location up front, before any provisioning. A snapshot whose
        // sqlDump was written by pre-validation code could point outside this account's dump dir;
        // rejecting it here (rather than mid-restore) avoids leaving a half-created cluster record
        // and an orphaned container behind.
        String sqlDump = snapshot.getSqlDump();
        boolean hasDump = sqlDump != null && !sqlDump.isBlank();
        if (hasDump && !isTrustedDumpPath(sqlDump)) {
            throw new AwsException("InvalidParameterValue",
                    "Snapshot " + snapshotIdentifier + " has an unusable dump location", 400);
        }

        String effectiveNodeType = (nodeType != null && !nodeType.isBlank()) ? nodeType : "dc2.large";
        String username = snapshot.getMasterUsername() != null ? snapshot.getMasterUsername() : "admin";
        String sourceCluster = snapshot.getClusterIdentifier();
        String password = Optional.ofNullable(snapshot.getMasterPassword())
                .filter(p -> !p.isBlank())
                .or(() -> clusters.get(sourceCluster).map(Cluster::getMasterPassword))
                .filter(p -> p != null && !p.isBlank())
                .orElse("admin");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(clusterIdentifier);
        cluster.setNodeType(effectiveNodeType);
        cluster.setMasterUsername(username);
        cluster.setMasterPassword(password);
        cluster.setClusterStatus("creating");
        clusters.put(clusterIdentifier, cluster);
        clusters.flush();

        // Hoisted out of the try so a failure after allocateProxyPort() still returns the port.
        int proxyPort = -1;
        try {
            String accountId = clusters.accountId();
            RedshiftContainerHandle handle = containerManager.start(accountId, clusterIdentifier, username, password);
            proxyPort = allocateProxyPort();
            Endpoint endpoint = proxyEndpoint(proxyPort);
            cluster.setProxyPort(proxyPort);
            proxyManager.startProxy(relayKey(accountId, clusterIdentifier), proxyPort,
                    handle.getHost(), handle.getPort(), endpoint.getAddress(),
                    username, password, CLUSTER_DB_NAME,
                    passwordValidatorFor(accountId, clusterIdentifier), cluster.getIamRoleArns());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
            cluster.setEndpoint(endpoint);

            if (hasDump) {
                containerManager.restoreSnapshot(clusters.accountId(), clusterIdentifier, username, Paths.get(sqlDump));
            }

            cluster.setClusterStatus("available");
        } catch (AwsException e) {
            boolean proxyStopped = stopProxyAndReleasePortSafely(clusterIdentifier, proxyPort);
            try { containerManager.stop(clusters.accountId(), clusterIdentifier); } catch (Exception ex) { LOG.warnv(ex, "Failed to stop container during rollback of cluster {0}", clusterIdentifier); }
            if (proxyStopped) {
                clusters.delete(clusterIdentifier);
            } else {
                cluster.setClusterStatus("failed");
                clusters.put(clusterIdentifier, cluster);
            }
            clusters.flush();
            throw e;
        } catch (Exception e) {
            boolean proxyStopped = stopProxyAndReleasePortSafely(clusterIdentifier, proxyPort);
            try { containerManager.stop(clusters.accountId(), clusterIdentifier); } catch (Exception ex) { LOG.warnv(ex, "Failed to stop container during rollback of cluster {0}", clusterIdentifier); }
            if (proxyStopped) {
                clusters.delete(clusterIdentifier);
            } else {
                cluster.setClusterStatus("failed");
                clusters.put(clusterIdentifier, cluster);
            }
            clusters.flush();
            throw new AwsException("InternalFailure", "Failed to restore cluster from snapshot: " + e.getMessage(), 500);
        }

        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        recordEvent(clusterIdentifier, "cluster", "Cluster restored from snapshot.");
        return cluster;
    }

    // ── Parameter Group Operations ───────────────────────────────────────────

    public ClusterParameterGroup createClusterParameterGroup(String parameterGroupName, String parameterGroupFamily, String description) {
        if (parameterGroups.get(parameterGroupName).isPresent()) {
            throw new AwsException("ClusterParameterGroupAlreadyExists", "Cluster parameter group " + parameterGroupName + " already exists", 400);
        }

        ClusterParameterGroup group = new ClusterParameterGroup(parameterGroupName, parameterGroupFamily, description);
        parameterGroups.put(parameterGroupName, group);
        parameterGroups.flush();
        recordEvent(parameterGroupName, "cluster-parameter-group", "Cluster parameter group created.");
        return group;
    }

    public List<ClusterParameterGroup> describeClusterParameterGroups(String parameterGroupName) {
        if (parameterGroupName != null && !parameterGroupName.isBlank()) {
            Optional<ClusterParameterGroup> group = parameterGroups.get(parameterGroupName);
            if (group.isEmpty()) {
                throw new AwsException("ClusterParameterGroupNotFound", "Cluster parameter group " + parameterGroupName + " not found", 404);
            }
            return List.of(group.get());
        }
        return parameterGroups.scan(k -> true);
    }

    public Optional<ClusterParameterGroup> getClusterParameterGroup(String parameterGroupName) {
        return parameterGroups.get(parameterGroupName);
    }

    public List<Parameter> describeClusterParameters(String parameterGroupName) {
        ClusterParameterGroup group = parameterGroups.get(parameterGroupName)
                .orElseThrow(() -> new AwsException("ClusterParameterGroupNotFound",
                        "Cluster parameter group " + parameterGroupName + " not found", 404));
        return group.getParameters();
    }

    public List<Parameter> describeClusterParameters(String parameterGroupName, String source) {
        if (source != null && !Set.of("user", "engine-default", "system").contains(source)) {
            throw new AwsException("InvalidParameterValue", "Invalid parameter source: " + source, 400);
        }
        return describeClusterParameters(parameterGroupName).stream()
                .filter(parameter -> source == null || source.equals(parameter.getSource()))
                .toList();
    }

    public synchronized ClusterParameterGroup resetClusterParameterGroup(
            String parameterGroupName, boolean resetAllParameters, List<Parameter> parameters) {
        ClusterParameterGroup group = parameterGroups.get(parameterGroupName)
                .orElseThrow(() -> new AwsException("ClusterParameterGroupNotFound",
                        "Cluster parameter group " + parameterGroupName + " not found", 404));
        if (!resetAllParameters && parameters.isEmpty()) {
            throw new AwsException("InvalidParameterCombination", "Specify Parameters or ResetAllParameters.", 400);
        }
        if (resetAllParameters && !parameters.isEmpty()) {
            throw new AwsException("InvalidParameterCombination", "Parameters cannot accompany ResetAllParameters.", 400);
        }
        List<Parameter> defaults = ClusterParameterGroup.defaultParameters();
        if (resetAllParameters) {
            group.setParameters(defaults);
        } else {
            List<String> names = parameters.stream().map(Parameter::getParameterName).toList();
            List<Parameter> current = new ArrayList<>(group.getParameters());
            current.removeIf(parameter -> names.contains(parameter.getParameterName()));
            defaults.stream().filter(parameter -> names.contains(parameter.getParameterName())).forEach(current::add);
            group.setParameters(current);
        }
        parameterGroups.put(parameterGroupName, group);
        parameterGroups.flush();
        recordEvent(parameterGroupName, "cluster-parameter-group", "Cluster parameter group reset.");
        return group;
    }

    public synchronized ClusterParameterGroup modifyClusterParameterGroup(
            String parameterGroupName, List<Parameter> updates) {
        ClusterParameterGroup group = parameterGroups.get(parameterGroupName)
                .orElseThrow(() -> new AwsException("ClusterParameterGroupNotFound",
                        "Cluster parameter group " + parameterGroupName + " not found", 404));

        List<Parameter> current = new ArrayList<>(group.getParameters());
        for (Parameter update : updates) {
            update.setSource("user");
            boolean matched = false;
            for (int i = 0; i < current.size(); i++) {
                Parameter existing = current.get(i);
                if (existing.getParameterName().equals(update.getParameterName())) {
                    // Preserve metadata (description, dataType) from existing parameter if not provided in update
                    existing.setParameterValue(update.getParameterValue());
                    existing.setSource("user");
                    if (update.getDescription() != null) {
                        existing.setDescription(update.getDescription());
                    }
                    if (update.getDataType() != null) {
                        existing.setDataType(update.getDataType());
                    }
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                current.add(update);
            }
        }
        group.setParameters(current);
        parameterGroups.put(parameterGroupName, group);
        parameterGroups.flush();
        recordEvent(parameterGroupName, "cluster-parameter-group", "Cluster parameter group modified.");
        return group;
    }

    public ClusterParameterGroup deleteClusterParameterGroup(String parameterGroupName) {
        Optional<ClusterParameterGroup> groupOpt = parameterGroups.get(parameterGroupName);
        if (groupOpt.isEmpty()) {
            throw new AwsException("ClusterParameterGroupNotFound", "Cluster parameter group " + parameterGroupName + " not found", 404);
        }
        ClusterParameterGroup group = groupOpt.get();
        parameterGroups.delete(parameterGroupName);
        parameterGroups.flush();
        recordEvent(parameterGroupName, "cluster-parameter-group", "Cluster parameter group deleted.");
        return group;
    }

    // ── Cluster Subnet Group Operations ──────────────────────────────────────

    public ClusterSubnetGroup createClusterSubnetGroup(String name, String description, String vpcId, List<String> subnetIds) {
        if (subnetGroups.get(name).isPresent()) {
            throw new AwsException("ClusterSubnetGroupAlreadyExists", "Cluster subnet group " + name + " already exists", 400);
        }
        String observedVpc = subnetVpc(subnetIds);
        if (vpcId != null && !vpcId.equals(observedVpc)) {
            throw new AwsException("InvalidSubnet", "Subnets must belong to the specified VPC.", 400);
        }
        ClusterSubnetGroup group = new ClusterSubnetGroup(name, description, observedVpc, subnetIds);
        subnetGroups.put(name, group);
        subnetGroups.flush();
        return group;
    }

    private String subnetVpc(List<String> subnetIds) {
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "SubnetIds must not be empty.", 400);
        }
        List<Subnet> observed;
        try {
            observed = ec2Service.describeSubnets(regionResolver.getRegion(), subnetIds, Map.of());
        } catch (AwsException exception) {
            if (!"InvalidSubnetID.NotFound".equals(exception.getErrorCode())) {
                throw exception;
            }
            throw new AwsException("InvalidSubnet", "One or more subnets do not exist.", 400);
        }
        List<String> vpcs = observed.stream().map(Subnet::getVpcId).distinct().toList();
        if (vpcs.size() != 1 || observed.stream().map(Subnet::getSubnetId).distinct().count()
                != subnetIds.stream().distinct().count()) {
            throw new AwsException("InvalidSubnet", "Subnets must exist in a single VPC.", 400);
        }
        return vpcs.getFirst();
    }

    public List<ClusterSubnetGroup> describeClusterSubnetGroups(String name) {
        if (name != null && !name.isBlank()) {
            ClusterSubnetGroup group = subnetGroups.get(name)
                    .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFoundFault", "Cluster subnet group " + name + " not found", 404));
            return List.of(group);
        }
        return subnetGroups.scan(k -> true);
    }

    public synchronized ClusterSubnetGroup modifyClusterSubnetGroup(String name, String description, List<String> subnetIds) {
        ClusterSubnetGroup group = subnetGroups.get(name)
                .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFoundFault", "Cluster subnet group " + name + " not found", 404));
        String observedVpc = subnetVpc(subnetIds);
        if (group.getVpcId() != null && !group.getVpcId().equals(observedVpc)) {
            throw new AwsException("InvalidSubnet", "Subnets must remain in the subnet group's VPC.", 400);
        }
        if (description != null) {
            group.setDescription(description);
        }
        group.setVpcId(observedVpc);
        group.setSubnetIds(subnetIds);
        subnetGroups.put(name, group);
        subnetGroups.flush();
        return group;
    }

    public ClusterSubnetGroup deleteClusterSubnetGroup(String name) {
        ClusterSubnetGroup group = subnetGroups.get(name)
                .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFoundFault", "Cluster subnet group " + name + " not found", 404));
        subnetGroups.delete(name);
        subnetGroups.flush();
        return group;
    }

    // ── Snapshot Copy Grant Operations ───────────────────────────────────────

    /**
     * AWS-managed Redshift key an account gets when CreateSnapshotCopyGrant omits KmsKeyId.
     * Floci has no per-account default key, so the alias ARN stands in for it: the value only
     * has to round-trip through Describe, which is what Terraform reads back.
     */
    private String defaultSnapshotCopyGrantKey() {
        return regionResolver.buildArn("kms", regionResolver.getRegion(), "alias/aws/redshift");
    }

    /**
     * AWS constrains a snapshot copy grant name to 1-63 characters, first a lowercase letter,
     * then lowercase letters, digits or single hyphens (no trailing or doubled hyphen). Names
     * Redshift rejects must not create here either, or Terraform sees a grant that cannot
     * exist upstream.
     */
    private static void validateSnapshotCopyGrantName(String name) {
        if (name == null || !name.matches("[a-z][a-z0-9-]{0,62}")
                || name.contains("--") || name.endsWith("-")) {
            throw new AwsException("InvalidParameterValue",
                    "SnapshotCopyGrantName must be 1-63 characters, start with a lowercase letter, "
                    + "and contain only lowercase letters, digits and non-consecutive hyphens", 400);
        }
    }

    // synchronized like createCluster: the free-name check and the write must not interleave,
    // or two concurrent creates of the same name both succeed and the second overwrites the first.
    public synchronized SnapshotCopyGrant createSnapshotCopyGrant(String name, String kmsKeyId, Map<String, String> tags) {
        validateSnapshotCopyGrantName(name);
        if (snapshotCopyGrants.get(name).isPresent()) {
            throw new AwsException("SnapshotCopyGrantAlreadyExistsFault",
                    "Snapshot copy grant " + name + " already exists", 400);
        }
        String effectiveKey = (kmsKeyId != null && !kmsKeyId.isBlank()) ? kmsKeyId : defaultSnapshotCopyGrantKey();
        SnapshotCopyGrant grant = new SnapshotCopyGrant(name, effectiveKey);
        if (tags != null && !tags.isEmpty()) {
            grant.setTags(new LinkedHashMap<>(tags));
        }
        snapshotCopyGrants.put(name, grant);
        snapshotCopyGrants.flush();
        return grant;
    }

    /** Default and maximum page size AWS documents for DescribeSnapshotCopyGrants. */
    private static final int SNAPSHOT_COPY_GRANT_PAGE_DEFAULT = 100;
    private static final int SNAPSHOT_COPY_GRANT_PAGE_MAX = 100;
    private static final int SNAPSHOT_COPY_GRANT_PAGE_MIN = 20;

    /**
     * Pages grants by name, which is their primary key, so the order is stable across calls
     * and a marker stays resumable when grants are created or deleted between pages.
     *
     * <p>AWS documents SnapshotCopyGrantName and Marker as mutually exclusive, but models no
     * error for sending both, so this filters first and then paginates rather than rejecting
     * the combination: a name matches at most one grant, which fits in any page.
     */
    public PaginatedResult<SnapshotCopyGrant> describeSnapshotCopyGrants(String name, Integer maxRecords, String marker) {
        if (maxRecords != null
                && (maxRecords < SNAPSHOT_COPY_GRANT_PAGE_MIN || maxRecords > SNAPSHOT_COPY_GRANT_PAGE_MAX)) {
            throw new AwsException("InvalidParameterValue",
                    "MaxRecords must be between " + SNAPSHOT_COPY_GRANT_PAGE_MIN
                            + " and " + SNAPSHOT_COPY_GRANT_PAGE_MAX + ".", 400);
        }

        List<SnapshotCopyGrant> matching;
        if (name != null && !name.isBlank()) {
            SnapshotCopyGrant grant = snapshotCopyGrants.get(name)
                    .orElseThrow(() -> new AwsException("SnapshotCopyGrantNotFoundFault",
                            "Snapshot copy grant " + name + " not found", 400));
            matching = List.of(grant);
        } else {
            matching = snapshotCopyGrants.scan(k -> true);
        }

        return Pagination.paginate(matching, SnapshotCopyGrant::getSnapshotCopyGrantName,
                maxRecords, marker, SNAPSHOT_COPY_GRANT_PAGE_DEFAULT, SNAPSHOT_COPY_GRANT_PAGE_MAX,
                "InvalidParameterValue");
    }

    public synchronized SnapshotCopyGrant deleteSnapshotCopyGrant(String name) {
        SnapshotCopyGrant grant = snapshotCopyGrants.get(name)
                .orElseThrow(() -> new AwsException("SnapshotCopyGrantNotFoundFault",
                        "Snapshot copy grant " + name + " not found", 400));
        snapshotCopyGrants.delete(name);
        snapshotCopyGrants.flush();
        return grant;
    }

    // ── Tagging Operations ───────────────────────────────────────────────────

    /** A resolved tag target: its current tags plus a sink that persists an updated map. */
    private record TagHandle(Map<String, String> tags, Consumer<Map<String, String>> save) {}

    public record TaggedResource(String resourceName, String resourceType, String tagKey, String tagValue) {}

    public Map<String, String> listTagsForResource(String resourceName) {
        return Map.copyOf(resolveTagHandle(resourceName).tags());
    }

    public synchronized void createTags(String resourceName, Map<String, String> tags) {
        TagHandle handle = resolveTagHandle(resourceName);
        Map<String, String> updated = new LinkedHashMap<>(handle.tags());
        updated.putAll(tags);
        handle.save().accept(updated);
    }

    public synchronized void deleteTags(String resourceName, Collection<String> tagKeys) {
        TagHandle handle = resolveTagHandle(resourceName);
        Map<String, String> updated = new LinkedHashMap<>(handle.tags());
        tagKeys.forEach(updated::remove);
        handle.save().accept(updated);
    }

    public List<TaggedResource> describeTags(String resourceName, String resourceType, List<String> tagKeysFilter) {
        List<TaggedResource> result = new ArrayList<>();
        if (resourceName != null && !resourceName.isBlank()) {
            TagHandle handle = resolveTagHandle(resourceName);
            String type = arnResourceType(resourceName);
            addTaggedResources(result, resourceName, type, handle.tags(), tagKeysFilter);
            return result;
        }
        if (resourceType == null || "cluster".equalsIgnoreCase(resourceType)) {
            for (Cluster c : clusters.scan(k -> true)) {
                addTaggedResources(result, clusterArn(c.getClusterIdentifier()), "cluster", c.getTags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "snapshot".equalsIgnoreCase(resourceType)) {
            for (Snapshot s : snapshots.scan(k -> true)) {
                addTaggedResources(result, snapshotArn(s.getClusterIdentifier(), s.getSnapshotIdentifier()),
                        "snapshot", s.getTags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "parametergroup".equalsIgnoreCase(resourceType)) {
            for (ClusterParameterGroup g : parameterGroups.scan(k -> true)) {
                addTaggedResources(result, parameterGroupArn(g.getParameterGroupName()),
                        "parametergroup", g.getTags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "subnetgroup".equalsIgnoreCase(resourceType)) {
            for (ClusterSubnetGroup g : subnetGroups.scan(k -> true)) {
                addTaggedResources(result, subnetGroupArn(g.getClusterSubnetGroupName()),
                        "subnetgroup", g.getTags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "eventsubscription".equalsIgnoreCase(resourceType)) {
            for (EventSubscription subscription : eventSubscriptions.scan(key -> key.startsWith(regionResolver.getRegion() + ":"))) {
                addTaggedResources(result, regionResolver.buildArn("redshift", subscription.region(),
                        "eventsubscription:" + subscription.name()), "eventsubscription", subscription.tags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "snapshotcopygrant".equalsIgnoreCase(resourceType)) {
            for (SnapshotCopyGrant g : snapshotCopyGrants.scan(k -> true)) {
                addTaggedResources(result, snapshotCopyGrantArn(g.getSnapshotCopyGrantName()),
                        "snapshotcopygrant", g.getTags(), tagKeysFilter);
            }
        }
        return result;
    }

    private void addTaggedResources(List<TaggedResource> out, String arn, String type,
                                     Map<String, String> tags, List<String> tagKeysFilter) {
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (tagKeysFilter != null && !tagKeysFilter.isEmpty() && !tagKeysFilter.contains(e.getKey())) {
                continue;
            }
            out.add(new TaggedResource(arn, type, e.getKey(), e.getValue()));
        }
    }

    private String arnResourceType(String resourceName) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceName);
        String resource = arn.resource();
        int sep = resource.indexOf(':');
        return resource.substring(0, sep);
    }

    private String clusterArn(String clusterIdentifier) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(), "cluster:" + clusterIdentifier);
    }

    private String snapshotArn(String clusterIdentifier, String snapshotIdentifier) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(),
                "snapshot:" + clusterIdentifier + "/" + snapshotIdentifier);
    }

    private String parameterGroupArn(String parameterGroupName) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(), "parametergroup:" + parameterGroupName);
    }

    private String subnetGroupArn(String name) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(), "subnetgroup:" + name);
    }

    private String snapshotCopyGrantArn(String name) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(), "snapshotcopygrant:" + name);
    }

    /**
     * Resolves a tagging ResourceName to its backing resource.
     *
     * Redshift ARNs have the shape {@code arn:aws:redshift:<region>:<account>:<type>:<id>},
     * where {@code <type>} is one of {@code cluster}, {@code snapshot} (id shape
     * {@code <clusterId>/<snapshotId>}), {@code parametergroup}, {@code subnetgroup} or
     * {@code snapshotcopygrant}. Unlike RDS's tag
     * resolution, there is no bare-name fallback, Redshift tagging is new, so there is no
     * existing caller to stay backward compatible with.
     */
    private TagHandle resolveTagHandle(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceName is required.", 400);
        }
        if (!resourceName.startsWith("arn:")) {
            throw new AwsException("InvalidParameterValue", "ResourceName must be a Redshift ARN: " + resourceName, 400);
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceName);
        } catch (IllegalArgumentException malformed) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        if (!"redshift".equals(arn.service())) {
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
            case "cluster" -> {
                Cluster cluster = clusters.get(id)
                        .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + id + " not found", 404));
                yield new TagHandle(cluster.getTags(), updated -> {
                    cluster.setTags(updated);
                    clusters.put(id, cluster);
                    clusters.flush();
                });
            }
            case "snapshot" -> {
                String snapshotId = id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id;
                Snapshot snapshot = snapshots.get(snapshotId)
                        .orElseThrow(() -> new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotId + " not found", 404));
                yield new TagHandle(snapshot.getTags(), updated -> {
                    snapshot.setTags(updated);
                    snapshots.put(snapshotId, snapshot);
                    snapshots.flush();
                });
            }
            case "parametergroup" -> {
                ClusterParameterGroup group = parameterGroups.get(id)
                        .orElseThrow(() -> new AwsException("ClusterParameterGroupNotFound", "Cluster parameter group " + id + " not found", 404));
                yield new TagHandle(group.getTags(), updated -> {
                    group.setTags(updated);
                    parameterGroups.put(id, group);
                    parameterGroups.flush();
                });
            }
            case "subnetgroup" -> {
                ClusterSubnetGroup group = subnetGroups.get(id)
                        .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFoundFault", "Cluster subnet group " + id + " not found", 404));
                yield new TagHandle(group.getTags(), updated -> {
                    group.setTags(updated);
                    subnetGroups.put(id, group);
                    subnetGroups.flush();
                });
            }
            case "eventsubscription" -> {
                if (!regionResolver.getRegion().equals(arn.region()) || !regionResolver.getAccountId().equals(arn.accountId())) {
                    throw new AwsException("SubscriptionNotFound", "Subscription " + id + " not found.", 404);
                }
                EventSubscription subscription = getEventSubscription(id);
                yield new TagHandle(subscription.tags(), updated -> {
                    eventSubscriptions.put(subscriptionKey(id), subscription.withTags(updated));
                    eventSubscriptions.flush();
                });
            }
            case "snapshotcopygrant" -> {
                // ResourceNotFoundFault, not SnapshotCopyGrantNotFoundFault: this path is only
                // reached from CreateTags/DeleteTags/DescribeTags, and those three list
                // ResourceNotFoundFault (404) for a missing resource and do not list the
                // grant-specific fault at all. The three sibling cases above are consistent
                // for the same reason -- ClusterNotFound, ClusterSnapshotNotFound and
                // ClusterParameterGroupNotFound are each modelled at 404. The grant fault is
                // modelled at 400, so emitting it here would pair a code with a status the
                // model never gives it.
                SnapshotCopyGrant grant = snapshotCopyGrants.get(id)
                        .orElseThrow(() -> new AwsException("ResourceNotFoundFault", "Snapshot copy grant " + id + " not found", 404));
                yield new TagHandle(grant.getTags(), updated -> {
                    grant.setTags(updated);
                    snapshotCopyGrants.put(id, grant);
                    snapshotCopyGrants.flush();
                });
            }
            default -> throw new AwsException("InvalidParameterValue",
                    "Tagging for resource type '" + type + "' is not supported: " + resourceName, 400);
        };
    }

    // ── Proxy Helpers (shared with modify/reboot/restore) ────────────────────

    private static final String CLUSTER_DB_NAME = "dev";

    private int allocateProxyPort() {
        int base = config.services().redshift().proxyBasePort();
        int max = config.services().redshift().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        throw new AwsException("InsufficientClusterCapacity",
                "No available Redshift proxy ports in range " + base + "-" + max, 503);
    }

    private boolean stopProxyAndReleasePortSafely(String identifier, int proxyPort) {
        boolean proxyStopped = false;
        try {
            proxyManager.stopProxy(relayKey(clusters.accountId(), identifier));
            proxyStopped = true;
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to stop proxy for cluster {0}; leaking proxy port {1} to prevent reallocation", identifier, proxyPort);
        }
        if (proxyStopped) {
            releaseProxyPort(proxyPort);
        }
        return proxyStopped;
    }

    /**
     * Undo a failed reboot. If {@code originalTornDown} is false the reboot failed before
     * the original proxy + container were stopped, so the original data-bearing container
     * is still running and nothing must be touched. Once it is true the original is gone:
     * tear down the (replacement's) proxy and return its port, and remove any container
     * running under the cluster's name, {@code containerManager.stop} works by name, so
     * this also cleans a replacement that {@code containerManager.start} created before
     * throwing (e.g. its readiness check timed out). The pre-reboot data dump is kept by
     * the caller.
     */
    private void rollbackReboot(String identifier, boolean originalTornDown) {
        if (!originalTornDown) {
            return;
        }
        try {
            proxyManager.stopProxy(relayKey(clusters.accountId(), identifier));
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to stop proxy during reboot rollback for cluster {0}", identifier);
        }
        try {
            containerManager.stop(clusters.accountId(), identifier);
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to stop replacement container during rollback of reboot for cluster {0}", identifier);
        }
    }

    private void releaseProxyPort(int port) {
        if (port > 0) {
            usedPorts.remove(port);
        }
    }

    private Endpoint proxyEndpoint(int proxyPort) {
        String host = config.services().redshift().endpointHost()
                .filter(h -> !h.isBlank())
                .orElseGet(dockerHostResolver::resolve);
        return new Endpoint(host, proxyPort);
    }

    private String relayKey(String accountId, String clusterIdentifier) {
        return accountId + ":" + clusterIdentifier;
    }

    // Classifies a proxy login against current cluster state: the master pair and any live
    // GetClusterCredentials credential both run the backend leg as the cluster master, a known
    // broker user with a stale password is rejected, everyone else passes through to the backend.
    // Reading cluster state per call means a ModifyCluster password change takes effect for new
    // connections without a proxy restart.
    private PasswordValidator passwordValidatorFor(String accountId, String clusterIdentifier) {
        return (user, password) -> {
            Optional<Cluster> cluster = clusters.getForAccount(accountId, clusterIdentifier);
            if (cluster.isEmpty()) {
                // No cluster row to validate against: vouch for nothing. Falling through to the
                // broker would classify an unknown user as PASSTHROUGH, and the wire proxy reads
                // isMaster from its own start-time config, so a PASSTHROUGH there still opens the
                // backend as master, authenticating any password for the master username.
                return PasswordValidator.AuthResult.REJECT;
            }
            Cluster c = cluster.get();
            if (user.equals(c.getMasterUsername())) {
                // The master username is authoritative here: a wrong password must be rejected,
                // never handed to the broker (which only knows minted DbUsers) and never passed
                // through as if the user were unknown.
                return password.equals(c.getMasterPassword())
                        ? PasswordValidator.AuthResult.MASTER_EQUIVALENT
                        : PasswordValidator.AuthResult.REJECT;
            }
            return switch (credentialBroker.classify(accountId, clusterIdentifier, user, password)) {
                case MASTER_EQUIVALENT -> PasswordValidator.AuthResult.MASTER_EQUIVALENT;
                case REJECT -> PasswordValidator.AuthResult.REJECT;
                case PASSTHROUGH -> PasswordValidator.AuthResult.PASSTHROUGH;
            };
        };
    }

    // Package-private hook for tests: passwordValidatorFor is otherwise private.
    PasswordValidator passwordValidatorForTesting(String accountId, String clusterIdentifier) {
        return passwordValidatorFor(accountId, clusterIdentifier);
    }
}
