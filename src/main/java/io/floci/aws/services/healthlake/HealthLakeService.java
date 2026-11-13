package io.floci.aws.services.healthlake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * AWS HealthLake FHIR data stores. Each data store is a FHIR R4 server, a HAPI FHIR container (or, without Docker,
 * floci's own store of the resources), served at its endpoint. Import jobs read NDJSON from S3 into it, one resource
 * a line, and write the outcome of each line to their output location; export jobs write its resources to S3 as
 * NDJSON, a file per resource type. A data store runs one job at a time. A Synthea preload writes a small
 * synthetic population of patients with their encounters and observations. A data store with continuous backups can
 * be restored into a new one.
 */
@ApplicationScoped
public class HealthLakeService implements Resettable {

    private static final Logger LOG = Logger.getLogger(HealthLakeService.class);
    private static final Pattern DATASTORE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]{0,256}");
    private static final Set<String> RUNNING = Set.of("SUBMITTED", "QUEUED", "IN_PROGRESS");
    private static final int MAX_DATASTORES = 10;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final IamPolicyEvaluator POLICIES = new IamPolicyEvaluator(new ObjectMapper());
    private static final AssumeRolePolicyEvaluator TRUST = new AssumeRolePolicyEvaluator(new ObjectMapper(), POLICIES);

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final AccountAwareStorageBackend<ObjectNode> resources;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final HealthLakeFhirServers servers;
    private final Instance<S3Service> s3;
    private final Instance<IamService> iam;
    private final Instance<HealthLakeTransformService> transform;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "healthlake");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public HealthLakeService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                             HealthLakeFhirServers servers, Instance<S3Service> s3, Instance<IamService> iam,
                             Instance<HealthLakeTransformService> transform) {
        this.storage = storageFactory.create("healthlake", "datastores.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.resources = storageFactory.create("healthlake", "fhir-resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.servers = servers;
        this.s3 = s3;
        this.iam = iam;
        this.transform = transform;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public void clear() {
        if (!mock()) {
            try {
                servers.stopManagedContainers();
            } catch (RuntimeException e) {
                LOG.warnv("Failed to stop HealthLake FHIR servers during reset: {0}", e.getMessage());
            }
        }
        storage.clear();
        resources.clear();
    }

    private boolean mock() {
        return config.services().healthlake().mock();
    }

    private String account() {
        return storage.accountId();
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static String datastoreKey(String region, String id) {
        return region + "/datastore/" + id;
    }

    /** The endpoint a data store's FHIR API is served at. */
    String endpoint(String region, String datastoreId) {
        return HealthLakeEndpoint.publicEndpoint(datastoreId, region, config.effectiveBaseUrl());
    }

    // ---------------------------------------------------------------- data stores

    public synchronized ObjectNode createDatastore(JsonNode input, String region) {
        String account = account();
        String clientToken = input.path("ClientToken").asText(null);
        if (clientToken != null) {
            for (ObjectNode existing : list(account, region)) {
                if (clientToken.equals(existing.path("_clientToken").asText(null))) {
                    return created(existing);
                }
            }
        }
        if (!"R4".equals(input.path("DatastoreTypeVersion").asText(null))) {
            throw validation("DatastoreTypeVersion must be R4.");
        }
        long live = list(account, region).stream().filter(store -> !"DELETED".equals(store
                .path("DatastoreStatus").asText())).count();
        if (live >= MAX_DATASTORES) {
            throw validation("The account has reached its limit of " + MAX_DATASTORES + " data stores in this "
                    + "Region.");
        }
        ObjectNode datastore = newDatastore(input, region, account);
        if (clientToken != null) {
            datastore.put("_clientToken", clientToken);
        }
        if (input.has("PreloadDataConfig")) {
            if (!"SYNTHEA".equals(input.path("PreloadDataConfig").path("PreloadDataType").asText())) {
                throw validation("PreloadDataConfig.PreloadDataType must be SYNTHEA.");
            }
            datastore.set("PreloadDataConfig", input.get("PreloadDataConfig").deepCopy());
        }
        save(account, region, datastore);
        provision(account, region, datastore.path("DatastoreId").asText(), datastore.has("PreloadDataConfig")
                ? HealthLakeSynthea.population() : List.of());
        return created(datastore);
    }

    private ObjectNode newDatastore(JsonNode input, String region, String account) {
        String name = input.path("DatastoreName").asText("");
        if (!NAME.matcher(name).matches()) {
            throw validation("DatastoreName is at most 256 letters, digits, spaces and _.:/=+-@.");
        }
        String id = HexFormat.of().formatHex(bytes(16));
        ObjectNode datastore = mapper.createObjectNode();
        datastore.put("DatastoreId", id)
                .put("DatastoreArn", AwsArnUtils.Arn.of("healthlake", region, account, "datastore/fhir/" + id)
                        .toString())
                .put("DatastoreName", name).put("DatastoreStatus", "CREATING").put("CreatedAt", now())
                .put("DatastoreTypeVersion", "R4").put("DatastoreEndpoint", endpoint(region, id));
        datastore.set("SseConfiguration", sse(input.path("SseConfiguration")));
        datastore.set("IdentityProviderConfiguration", identityProvider(input.path("IdentityProviderConfiguration")));
        for (String field : List.of("AnalyticsConfiguration", "NlpConfiguration", "ProfileConfiguration")) {
            if (input.has(field)) {
                datastore.set(field, configuration(field, input.get(field)));
            }
        }
        if (input.has("BackupConfiguration")) {
            datastore.set("BackupStatusInfo", backup(input.get("BackupConfiguration"), null));
        }
        datastore.set("_tags", tags(input.path("Tags"), mapper.createArrayNode()));
        return datastore;
    }

    private ObjectNode sse(JsonNode given) {
        ObjectNode sse = mapper.createObjectNode();
        ObjectNode kms = sse.putObject("KmsEncryptionConfig");
        String type = given.path("KmsEncryptionConfig").path("CmkType").asText("AWS_OWNED_KMS_KEY");
        if (!Set.of("AWS_OWNED_KMS_KEY", "CUSTOMER_MANAGED_KMS_KEY").contains(type)) {
            throw validation("CmkType must be AWS_OWNED_KMS_KEY or CUSTOMER_MANAGED_KMS_KEY.");
        }
        kms.put("CmkType", type);
        String key = given.path("KmsEncryptionConfig").path("KmsKeyId").asText(null);
        if ("CUSTOMER_MANAGED_KMS_KEY".equals(type)) {
            if (key == null || key.isEmpty()) {
                throw validation("A CUSTOMER_MANAGED_KMS_KEY needs a KmsKeyId.");
            }
            kms.put("KmsKeyId", key);
        }
        return sse;
    }

    private ObjectNode identityProvider(JsonNode given) {
        ObjectNode idp = mapper.createObjectNode();
        String strategy = given.path("AuthorizationStrategy").asText("AWS_AUTH");
        if (!Set.of("AWS_AUTH", "SMART_ON_FHIR", "SMART_ON_FHIR_V1").contains(strategy)) {
            throw validation("AuthorizationStrategy must be AWS_AUTH, SMART_ON_FHIR or SMART_ON_FHIR_V1.");
        }
        idp.put("AuthorizationStrategy", strategy)
                .put("FineGrainedAuthorizationEnabled", given.path("FineGrainedAuthorizationEnabled").asBoolean(false));
        if (!"AWS_AUTH".equals(strategy)) {
            String lambda = given.path("IdpLambdaArn").asText(null);
            if (lambda == null || !AwsArnUtils.isArnFor(lambda, "lambda")) {
                throw validation("A SMART on FHIR data store needs the IdpLambdaArn that authorizes its tokens.");
            }
            String metadata = given.path("Metadata").asText(null);
            try {
                JsonNode parsed = metadata == null ? null : mapper.readTree(metadata);
                if (parsed == null || !parsed.isObject() || !parsed.hasNonNull("token_endpoint")
                        || !parsed.hasNonNull("authorization_endpoint")) {
                    throw validation("Metadata must be the SMART configuration JSON, with authorization_endpoint "
                            + "and token_endpoint.");
                }
            } catch (JsonProcessingException e) {
                throw validation("Metadata is not valid JSON: " + e.getOriginalMessage());
            }
            idp.put("IdpLambdaArn", lambda).put("Metadata", metadata);
        }
        return idp;
    }

    private ObjectNode configuration(String field, JsonNode given) {
        ObjectNode out = mapper.createObjectNode();
        if ("ProfileConfiguration".equals(field)) {
            out.set("DefaultProfiles", given.path("DefaultProfiles").isArray() ? given.get("DefaultProfiles")
                    .deepCopy() : mapper.createArrayNode());
            return out;
        }
        String status = given.path("Status").asText("DISABLED");
        Set<String> allowed = "AnalyticsConfiguration".equals(field)
                ? Set.of("ENABLED", "DISABLED", "PAUSED") : Set.of("ENABLED", "DISABLED");
        if (!allowed.contains(status)) {
            throw validation(field + ".Status must be one of " + String.join(", ", allowed) + ".");
        }
        out.put("Status", status);
        return out;
    }

    private ObjectNode backup(JsonNode given, JsonNode previous) {
        ObjectNode status = mapper.createObjectNode();
        ObjectNode configuration = status.putObject("Configuration");
        String state = given.path("Status").asText("DISABLED");
        if (!Set.of("ENABLED", "DISABLED").contains(state)) {
            throw validation("BackupConfiguration.Status must be ENABLED or DISABLED.");
        }
        int retention = given.path("RetentionPeriodInDays").asInt(35);
        if (retention < 1 || retention > 35) {
            throw validation("RetentionPeriodInDays is 1 to 35.");
        }
        configuration.put("Status", state).put("BackupType", "CONTINUOUS").put("RetentionPeriodInDays", retention)
                .put("BackupTagsEnabled", given.path("BackupTagsEnabled").asBoolean(false));
        if ("ENABLED".equals(state)) {
            double enabledAt = previous != null && previous.hasNonNull("BackupEnabledAt")
                    ? previous.get("BackupEnabledAt").asDouble() : now();
            status.put("BackupEnabledAt", enabledAt).put("EarliestRestorePoint", enabledAt);
        }
        return status;
    }

    private ObjectNode created(ObjectNode datastore) {
        ObjectNode response = mapper.createObjectNode();
        for (String field : List.of("DatastoreId", "DatastoreArn", "DatastoreStatus", "DatastoreEndpoint")) {
            response.set(field, datastore.get(field));
        }
        return response;
    }

    /**
     * Starts a data store's FHIR server (or, without Docker, its own store), writes its initial resources, and marks
     * it ACTIVE, or CREATE_FAILED with why.
     */
    private void provision(String account, String region, String datastoreId, List<JsonNode> initial) {
        executor.execute(() -> {
            String failure = null;
            HealthLakeFhirServers.Server server = null;
            try {
                if (!mock() && servers.isDockerReachable()) {
                    server = servers.start(datastoreId, account, region, endpoint(region, datastoreId));
                } else if (!mock()) {
                    LOG.warnv("No Docker daemon is reachable; HealthLake data store {0} keeps its resources in "
                            + "floci", datastoreId);
                }
                for (JsonNode resource : initial) {
                    write(account, datastoreId, server, resource);
                }
                long delay = config.services().healthlake().transitionMillis();
                if (server == null && delay > 0) {
                    Thread.sleep(delay);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = "Provisioning was interrupted.";
            } catch (RuntimeException e) {
                LOG.errorv(e, "Failed to provision HealthLake data store {0}", datastoreId);
                failure = e.getMessage();
            }
            synchronized (HealthLakeService.this) {
                ObjectNode datastore = storage.getForAccount(account, datastoreKey(region, datastoreId)).orElse(null);
                if (datastore == null || !"CREATING".equals(datastore.path("DatastoreStatus").asText())) {
                    if (server != null) {
                        servers.stop(datastoreId, server.containerId());
                    }
                    return;
                }
                if (server != null) {
                    datastore.put("_containerId", server.containerId()).put("_host", server.host())
                            .put("_port", server.port());
                }
                if (failure == null) {
                    datastore.put("DatastoreStatus", "ACTIVE");
                } else {
                    datastore.put("DatastoreStatus", "CREATE_FAILED");
                    datastore.putObject("ErrorCause").put("ErrorMessage", failure)
                            .put("ErrorCategory", "NON_RETRYABLE_ERROR");
                }
                save(account, region, datastore);
            }
        });
    }

    public synchronized ObjectNode describeDatastore(JsonNode input, String region) {
        ObjectNode response = mapper.createObjectNode();
        response.set("DatastoreProperties", view(datastore(region, text(input, "DatastoreId"))));
        return response;
    }

    public synchronized ObjectNode listDatastores(JsonNode input, String region) {
        JsonNode filter = input.path("Filter");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode datastore : list(account(), region)) {
            if (filter.hasNonNull("DatastoreName") && !filter.get("DatastoreName").asText().equals(datastore
                    .path("DatastoreName").asText())) {
                continue;
            }
            if (filter.hasNonNull("DatastoreStatus") && !filter.get("DatastoreStatus").asText().equals(datastore
                    .path("DatastoreStatus").asText())) {
                continue;
            }
            double created = datastore.path("CreatedAt").asDouble();
            if ((filter.hasNonNull("CreatedBefore") && created >= filter.get("CreatedBefore").asDouble())
                    || (filter.hasNonNull("CreatedAfter") && created <= filter.get("CreatedAfter").asDouble())) {
                continue;
            }
            items.add(view(datastore));
        }
        return page(items, input, "DatastorePropertiesList");
    }

    public synchronized ObjectNode updateDatastore(JsonNode input, String region) {
        String account = account();
        ObjectNode datastore = active(region, text(input, "DatastoreId"));
        if (input.has("DatastoreName")) {
            String name = input.get("DatastoreName").asText();
            if (!NAME.matcher(name).matches()) {
                throw validation("DatastoreName is at most 256 letters, digits, spaces and _.:/=+-@.");
            }
            datastore.put("DatastoreName", name);
        }
        for (String field : List.of("AnalyticsConfiguration", "NlpConfiguration", "ProfileConfiguration")) {
            if (input.has(field)) {
                datastore.set(field, configuration(field, input.get(field)));
            }
        }
        if (input.has("IdentityProviderConfiguration")) {
            datastore.set("IdentityProviderConfiguration", identityProvider(input.get(
                    "IdentityProviderConfiguration")));
        }
        if (input.has("BackupConfiguration")) {
            datastore.set("BackupStatusInfo", backup(input.get("BackupConfiguration"),
                    datastore.path("BackupStatusInfo")));
        }
        save(account, region, datastore);
        ObjectNode response = mapper.createObjectNode();
        response.set("DatastoreProperties", view(datastore));
        return response;
    }

    public synchronized ObjectNode deleteDatastore(JsonNode input, String region) {
        String account = account();
        String datastoreId = text(input, "DatastoreId");
        ObjectNode datastore = datastore(region, datastoreId);
        String status = datastore.path("DatastoreStatus").asText();
        if ("DELETED".equals(status) || "DELETING".equals(status)) {
            throw new AwsException("ConflictException", "Data store " + datastoreId + " is " + status + ".", 400);
        }
        if (runningJob(account, region, datastoreId).isPresent()) {
            throw new AwsException("ConflictException", "Data store " + datastoreId + " has a job in progress.", 400);
        }
        datastore.put("DatastoreStatus", "DELETING");
        save(account, region, datastore);
        String containerId = datastore.path("_containerId").asText(null);
        executor.execute(() -> {
            try {
                if (containerId != null || !mock()) {
                    servers.stop(datastoreId, containerId);
                    servers.removeStorage(datastoreId);
                }
            } catch (RuntimeException e) {
                LOG.warnv("Failed to remove the FHIR server of {0}: {1}", datastoreId, e.getMessage());
            }
            synchronized (HealthLakeService.this) {
                for (String key : resources.keysForAccount(account)) {
                    if (key.startsWith(datastoreId + "/")) {
                        resources.deleteForAccount(account, key);
                    }
                }
                storage.getForAccount(account, datastoreKey(region, datastoreId)).ifPresent(current -> {
                    current.put("DatastoreStatus", "DELETED");
                    current.remove(List.of("_containerId", "_host", "_port"));
                    save(account, region, current);
                });
            }
        });
        return created(datastore);
    }

    /**
     * Restores a data store with continuous backups into a new one: the source's resources as they are now, for a
     * restore point between its earliest and now.
     */
    public synchronized ObjectNode restoreDatastore(JsonNode input, String region) {
        String account = account();
        String sourceId = text(input, "SourceDatastoreId");
        ObjectNode source = active(region, sourceId);
        JsonNode backup = source.path("BackupStatusInfo").path("Configuration");
        if (!"ENABLED".equals(backup.path("Status").asText())) {
            throw validation("Data store " + sourceId + " has no continuous backups to restore from.");
        }
        JsonNode point = input.path("RestoreConfiguration").path("ContinuousBackupRestoreConfiguration")
                .path("RestorePointTime");
        if (!point.isMissingNode() && !point.isNull()) {
            double earliest = source.path("BackupStatusInfo").path("EarliestRestorePoint").asDouble();
            if (point.asDouble() < earliest || point.asDouble() > now()) {
                throw validation("RestorePointTime must fall between the earliest restore point and now.");
            }
        }
        String clientToken = input.path("ClientToken").asText(null);
        if (clientToken != null) {
            for (ObjectNode existing : list(account, region)) {
                if (clientToken.equals(existing.path("_clientToken").asText(null))) {
                    return created(existing);
                }
            }
        }
        ObjectNode template = input.deepCopy();
        template.put("DatastoreTypeVersion", "R4");
        if (!template.has("DatastoreName")) {
            template.put("DatastoreName", source.path("DatastoreName").asText() + "-restored");
        }
        ObjectNode datastore = newDatastore(template, region, account);
        if (clientToken != null) {
            datastore.put("_clientToken", clientToken);
        }
        save(account, region, datastore);
        List<JsonNode> copied = new ArrayList<>();
        snapshot(account, source).values().forEach(copied::addAll);
        provision(account, region, datastore.path("DatastoreId").asText(), copied);
        return created(datastore);
    }

    // ---------------------------------------------------------------- the FHIR resources of a data store

    private HealthLakeFhirServers.Server server(ObjectNode datastore) {
        if (!datastore.hasNonNull("_containerId")) {
            return null;
        }
        return new HealthLakeFhirServers.Server(datastore.get("_containerId").asText(),
                datastore.path("_host").asText(), datastore.path("_port").asInt());
    }

    /** The FHIR server a data store's endpoint forwards to, or empty when floci holds its resources itself. */
    synchronized Optional<HealthLakeFhirServers.Server> fhirServer(String account, String region,
                                                                   String datastoreId) {
        return storage.getForAccount(account, datastoreKey(region, datastoreId)).map(this::server);
    }

    /** The account owning a data store, whichever account a request to its endpoint is signed by. */
    synchronized Optional<ObjectNode> owned(String region, String datastoreId) {
        return storage.findAnyAccountEntry(datastoreKey(region, datastoreId))
                .map(entry -> {
                    ObjectNode copy = entry.value().deepCopy();
                    copy.put("_account", entry.account());
                    return copy;
                });
    }

    private void write(String account, String datastoreId, HealthLakeFhirServers.Server server, JsonNode resource) {
        if (server != null) {
            servers.write(server, resource);
            return;
        }
        ObjectNode stored = resource.deepCopy();
        if (!stored.hasNonNull("id")) {
            stored.put("id", UUID.randomUUID().toString());
        }
        String key = datastoreId + "/" + stored.path("resourceType").asText() + "/" + stored.path("id").asText();
        ObjectNode meta = stored.withObject("/meta");
        int version = resources.getForAccount(account, key).map(previous -> previous.path("meta")
                .path("versionId").asInt()).orElse(0) + 1;
        meta.put("versionId", Integer.toString(version)).put("lastUpdated", Instant.now().toString());
        resources.putForAccount(account, key, stored);
    }

    private Map<String, List<JsonNode>> snapshot(String account, ObjectNode datastore) {
        HealthLakeFhirServers.Server server = server(datastore);
        if (server != null) {
            return servers.all(server);
        }
        String prefix = datastore.path("DatastoreId").asText() + "/";
        Map<String, List<JsonNode>> byType = new LinkedHashMap<>();
        resources.scanForAccount(account, key -> key.startsWith(prefix)).forEach(resource -> byType
                .computeIfAbsent(resource.path("resourceType").asText(), type -> new ArrayList<>()).add(resource));
        return byType;
    }

    // ---------------------------------------------------------------- jobs

    private static String jobKey(String region, String datastoreId, String kind, String jobId) {
        return region + "/job/" + datastoreId + "/" + kind + "/" + jobId;
    }

    private Optional<ObjectNode> runningJob(String account, String region, String datastoreId) {
        return storage.scanForAccount(account, key -> key.startsWith(region + "/job/" + datastoreId + "/")).stream()
                .filter(job -> RUNNING.contains(job.path("JobStatus").asText())).findFirst();
    }

    public synchronized ObjectNode startImportJob(JsonNode input, String region) {
        return startJob(input, region, "import");
    }

    public synchronized ObjectNode startExportJob(JsonNode input, String region) {
        return startJob(input, region, "export");
    }

    private ObjectNode startJob(JsonNode input, String region, String kind) {
        String account = account();
        String datastoreId = text(input, "DatastoreId");
        ObjectNode datastore = active(region, datastoreId);
        String clientToken = input.path("ClientToken").asText(null);
        if (clientToken != null) {
            for (ObjectNode existing : storage.scanForAccount(account, key -> key.startsWith(region + "/job/"
                    + datastoreId + "/" + kind + "/"))) {
                if (clientToken.equals(existing.path("_clientToken").asText(null))) {
                    return jobStarted(existing);
                }
            }
        }
        String role = text(input, "DataAccessRoleArn");
        if (!AwsArnUtils.isArnFor(role, "iam")) {
            throw validation("DataAccessRoleArn must be an IAM role ARN.");
        }
        Optional<ObjectNode> running = runningJob(account, region, datastoreId);
        if (running.isPresent()) {
            throw validation("Data store " + datastoreId + " already has a job in progress, "
                    + running.get().path("JobId").asText() + "; it runs one import or export at a time.");
        }
        String jobId = HexFormat.of().formatHex(bytes(16));
        ObjectNode job = mapper.createObjectNode();
        job.put("JobId", jobId).put("JobStatus", "SUBMITTED").put("SubmitTime", now())
                .put("DatastoreId", datastoreId).put("DataAccessRoleArn", role);
        if (input.hasNonNull("JobName")) {
            job.put("JobName", input.get("JobName").asText());
        }
        if (clientToken != null) {
            job.put("_clientToken", clientToken);
        }
        if ("import".equals(kind)) {
            String source = text(input.path("InputDataConfig"), "S3Uri");
            if (!source.startsWith("s3://")) {
                throw validation("InputDataConfig.S3Uri must be an S3 URI.");
            }
            String level = input.path("ValidationLevel").asText("structure-only");
            if (!Set.of("strict", "structure-only", "minimal").contains(level)) {
                throw validation("ValidationLevel must be strict, structure-only or minimal.");
            }
            job.set("InputDataConfig", input.get("InputDataConfig").deepCopy());
            job.set("JobOutputDataConfig", outputConfig(input.path("JobOutputDataConfig"), "JobOutputDataConfig"));
            job.put("ValidationLevel", level);
            if (input.hasNonNull("ProfileId")) {
                // A two-phase import: the source files are converted with the profile, then loaded.
                HealthLakeTransformService.Published profile = transform.get().published(region,
                        input.get("ProfileId").asText());
                String format = input.path("InputFormat").asText(profile.format());
                if (!profile.format().equals(format)) {
                    throw validation("Profile " + profile.profileId() + " converts " + profile.format() + ", not "
                            + format + ".");
                }
                job.put("_profileId", profile.profileId()).put("_inputFormat", format)
                        .put("_provenance", input.path("ProvenanceEnabled").asBoolean(true))
                        .put("_drift", input.path("DriftDetectionEnabled").asBoolean(false));
            } else if (input.hasNonNull("InputFormat") && !"FHIR".equalsIgnoreCase(input.get("InputFormat")
                    .asText())) {
                throw validation("An InputFormat other than FHIR needs the ProfileId that converts it.");
            }
        } else {
            job.set("OutputDataConfig", outputConfig(input.path("OutputDataConfig"), "OutputDataConfig"));
        }
        storage.putForAccount(account, jobKey(region, datastoreId, kind, jobId), job);
        ObjectNode snapshot = datastore.deepCopy();
        executor.execute(() -> runJob(account, region, snapshot, kind, jobId));
        return jobStarted(job);
    }

    private ObjectNode outputConfig(JsonNode given, String field) {
        JsonNode s3Config = given.path("S3Configuration");
        String uri = s3Config.path("S3Uri").asText(null);
        if (uri == null || !uri.startsWith("s3://")) {
            throw validation(field + ".S3Configuration.S3Uri must be an S3 URI.");
        }
        if (!s3Config.hasNonNull("KmsKeyId")) {
            throw validation(field + ".S3Configuration.KmsKeyId is required.");
        }
        return given.deepCopy();
    }

    private ObjectNode jobStarted(ObjectNode job) {
        ObjectNode response = mapper.createObjectNode();
        response.put("JobId", job.path("JobId").asText()).put("JobStatus", job.path("JobStatus").asText())
                .put("DatastoreId", job.path("DatastoreId").asText());
        return response;
    }

    private void runJob(String account, String region, ObjectNode datastore, String kind, String jobId) {
        String datastoreId = datastore.path("DatastoreId").asText();
        String key = jobKey(region, datastoreId, kind, jobId);
        long delay = config.services().healthlake().transitionMillis();
        try {
            if (delay > 0) {
                Thread.sleep(delay);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        ObjectNode job;
        synchronized (this) {
            job = storage.getForAccount(account, key).orElse(null);
            if (job == null) {
                return;
            }
            job.put("JobStatus", "IN_PROGRESS");
            storage.putForAccount(account, key, job);
        }
        String status;
        String message = null;
        ObjectNode report = mapper.createObjectNode();
        try {
            role(job.path("DataAccessRoleArn").asText());
            status = "import".equals(kind) ? runImport(account, datastore, job, report)
                    : runExport(account, datastore, job);
        } catch (IllegalStateException | AwsException e) {
            status = "FAILED";
            message = e.getMessage();
        }
        synchronized (this) {
            ObjectNode current = storage.getForAccount(account, key).orElse(job);
            current.put("JobStatus", status).put("EndTime", now());
            if (message != null) {
                current.put("Message", message);
            }
            if ("import".equals(kind) && !"FAILED".equals(status)) {
                current.set("JobProgressReport", report);
            }
            storage.putForAccount(account, key, current);
        }
    }

    /** The data access role: it must exist and trust {@code healthlake.amazonaws.com}. */
    private void role(String arn) {
        IamRole role;
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            String name = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
            role = iam.get().findRole(parsed.accountId(), name).filter(found -> arn.equals(found.getArn()))
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            role = null;
        }
        if (role == null || !TRUST.allowsService(role.getAssumeRolePolicyDocument(),
                ServicePrincipals.of("healthlake"))) {
            throw new IllegalStateException("HealthLake is unable to assume the role " + arn + ". Make sure it exists "
                    + "and trusts " + ServicePrincipals.of("healthlake") + ".");
        }
    }

    /**
     * Reads every NDJSON object under the input URI, one FHIR resource a line, writes the valid ones to the data
     * store, and writes each line's outcome to the job's output location.
     */
    private String runImport(String account, ObjectNode datastore, ObjectNode job, ObjectNode report) {
        if (job.hasNonNull("_profileId")) {
            return runTransformingImport(account, datastore, job, report);
        }
        String[] input = location(job.path("InputDataConfig").path("S3Uri").asText());
        List<S3Object> objects;
        try {
            objects = s3.get().listObjects(input[0], input[1], null, 1000);
        } catch (AwsException e) {
            throw new IllegalStateException("Unable to read " + job.path("InputDataConfig").path("S3Uri").asText()
                    + ": " + e.getMessage());
        }
        HealthLakeFhirServers.Server server = server(datastore);
        String datastoreId = datastore.path("DatastoreId").asText();
        String level = job.path("ValidationLevel").asText();
        long files = 0;
        long imported = 0;
        long scanned = 0;
        long importedFiles = 0;
        long failed = 0;
        long failedFiles = 0;
        long bytes = 0;
        StringBuilder success = new StringBuilder();
        StringBuilder failure = new StringBuilder();
        for (S3Object object : objects) {
            if (object.getKey().endsWith("/")) {
                continue;
            }
            files++;
            byte[] data = s3.get().getObject(input[0], object.getKey()).getData();
            bytes += data == null ? 0 : data.length;
            String text = data == null ? "" : new String(data, StandardCharsets.UTF_8);
            int line = 0;
            boolean fileFailed = false;
            for (String raw : text.split("\\r?\\n")) {
                line++;
                if (raw.isBlank()) {
                    continue;
                }
                scanned++;
                String error;
                try {
                    JsonNode resource = mapper.readTree(raw);
                    error = check(resource, level);
                    if (error == null) {
                        write(account, datastoreId, server, resource);
                    }
                } catch (IOException e) {
                    error = "The line is not JSON: " + e.getMessage();
                } catch (IllegalArgumentException e) {
                    error = e.getMessage();
                }
                ObjectNode outcome = mapper.createObjectNode();
                outcome.put("file", object.getKey()).put("line", line);
                if (error == null) {
                    imported++;
                    success.append(outcome).append('\n');
                } else {
                    failed++;
                    fileFailed = true;
                    outcome.put("error", error);
                    failure.append(outcome).append('\n');
                }
            }
            if (fileFailed) {
                failedFiles++;
            } else {
                importedFiles++;
            }
        }
        if (files == 0) {
            throw new IllegalStateException("No files were found at " + job.path("InputDataConfig").path("S3Uri")
                    .asText() + ".");
        }
        report.put("TotalNumberOfScannedFiles", files).put("TotalSizeOfScannedFilesInMB", bytes / 1048576.0)
                .put("TotalNumberOfImportedFiles", importedFiles).put("TotalNumberOfResourcesScanned", scanned)
                .put("TotalNumberOfResourcesImported", imported).put("TotalNumberOfResourcesWithCustomerError", failed)
                .put("TotalNumberOfFilesReadWithCustomerError", failedFiles).put("Throughput", scanned);
        String[] output = location(job.path("JobOutputDataConfig").path("S3Configuration").path("S3Uri").asText());
        String folder = prefix(output[1]) + datastoreId + "-FHIR_IMPORT-" + job.path("JobId").asText() + "/";
        put(output[0], folder + "SUCCESS/SUCCESS_OUTPUT.ndjson", success.toString());
        put(output[0], folder + "FAILURE/FAILURE_OUTPUT.ndjson", failure.toString());
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("jobId", job.path("JobId").asText()).put("datastoreId", datastoreId);
        manifest.set("progressReport", report.deepCopy());
        put(output[0], folder + "manifest.json", manifest.toString());
        return failed == 0 ? "COMPLETED" : imported == 0 ? "FAILED" : "COMPLETED_WITH_ERRORS";
    }

    /** Converts the source files with the job's profile, then writes the resources made to the data store. */
    private String runTransformingImport(String account, ObjectNode datastore, ObjectNode job, ObjectNode report) {
        String region = AwsArnUtils.parse(datastore.path("DatastoreArn").asText()).region();
        HealthLakeTransformService.Published profile = transform.get().published(region,
                job.path("_profileId").asText());
        String datastoreId = datastore.path("DatastoreId").asText();
        Map<String, String> files = transform.get().read(job.path("InputDataConfig").path("S3Uri").asText(),
                job.path("_inputFormat").asText());
        HealthLakeTransformService.Conversion conversion;
        try {
            conversion = transform.get().convert(profile, files, datastoreId, job.path("_provenance").asBoolean(),
                    job.path("_drift").asBoolean());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage());
        }
        HealthLakeFhirServers.Server server = server(datastore);
        long imported = 0;
        StringBuilder failure = new StringBuilder();
        for (JsonNode resource : conversion.resources()) {
            try {
                write(account, datastoreId, server, resource);
                imported++;
            } catch (IllegalArgumentException e) {
                failure.append(mapper.createObjectNode().put("resource", resource.path("resourceType").asText() + "/"
                        + resource.path("id").asText()).put("error", e.getMessage())).append('\n');
            }
        }
        conversion.warnings().forEach(warning -> failure.append(mapper.createObjectNode().put("warning", warning))
                .append('\n'));
        long failed = conversion.resources().size() - imported;
        report.put("TotalNumberOfScannedNonFhirFiles", conversion.filesScanned())
                .put("TotalNumberOfImportedNonFhirFiles", conversion.filesConverted())
                .put("TotalNumberOfNonFhirFilesReadWithCustomerError", conversion.filesFailed())
                .put("TotalNumberOfNonFhirResourcesScanned", conversion.resources().size())
                .put("TotalNumberOfNonFhirResourcesImported", imported)
                .put("TotalNumberOfNonFhirResourcesWithCustomerError", failed)
                .put("TotalFilesConverted", conversion.filesConverted())
                .put("TotalResourcesGenerated", conversion.resources().size());
        String[] output = location(job.path("JobOutputDataConfig").path("S3Configuration").path("S3Uri").asText());
        String folder = prefix(output[1]) + datastoreId + "-FHIR_IMPORT-" + job.path("JobId").asText() + "/";
        put(output[0], folder + "FAILURE/FAILURE_OUTPUT.ndjson", failure.toString());
        if (conversion.drift() != null) {
            put(output[0], folder + "drift-report.json", conversion.drift().toString());
        }
        boolean errors = failed > 0 || conversion.filesFailed() > 0;
        return imported == 0 && errors ? "FAILED" : errors ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
    }

    /** Why a resource fails the validation level, or null when it passes. */
    private static String check(JsonNode resource, String level) {
        if (resource == null || !resource.isObject()) {
            return "A FHIR resource is a JSON object.";
        }
        String type = resource.path("resourceType").asText("");
        if (!type.matches("[A-Z][A-Za-z]+")) {
            return "The resource has no resourceType.";
        }
        if ("minimal".equals(level)) {
            return null;
        }
        if (resource.hasNonNull("id") && !resource.get("id").asText().matches("[A-Za-z0-9\\-.]{1,64}")) {
            return "The id " + resource.get("id").asText() + " is not a FHIR id: 1 to 64 letters, digits, '-' and "
                    + "'.'.";
        }
        if ("strict".equals(level) && "Bundle".equals(type)) {
            return "A Bundle is not imported; import its entries' resources.";
        }
        return null;
    }

    /** Writes the data store's resources to the output URI, a file per resource type. */
    private String runExport(String account, ObjectNode datastore, ObjectNode job) {
        String[] output = location(job.path("OutputDataConfig").path("S3Configuration").path("S3Uri").asText());
        String folder = prefix(output[1]) + datastore.path("DatastoreId").asText() + "-FHIR_EXPORT-"
                + job.path("JobId").asText() + "/";
        Map<String, List<JsonNode>> byType = snapshot(account, datastore);
        for (Map.Entry<String, List<JsonNode>> entry : byType.entrySet()) {
            StringBuilder ndjson = new StringBuilder();
            entry.getValue().forEach(resource -> ndjson.append(resource.toString()).append('\n'));
            put(output[0], folder + entry.getKey() + "/" + entry.getKey() + "-0.ndjson", ndjson.toString());
        }
        return "COMPLETED";
    }

    private void put(String bucket, String key, String content) {
        try {
            s3.get().putObject(bucket, key, content.getBytes(StandardCharsets.UTF_8), "application/x-ndjson",
                    Map.of());
        } catch (AwsException e) {
            throw new IllegalStateException("Unable to write s3://" + bucket + "/" + key + ": " + e.getMessage());
        }
    }

    private static String[] location(String uri) {
        String[] parts = uri.substring("s3://".length()).split("/", 2);
        return new String[] {parts[0], parts.length > 1 ? parts[1] : ""};
    }

    private static String prefix(String path) {
        return path.isEmpty() || path.endsWith("/") ? path : path + "/";
    }

    public synchronized ObjectNode describeImportJob(JsonNode input, String region) {
        ObjectNode response = mapper.createObjectNode();
        response.set("ImportJobProperties", job(input, region, "import"));
        return response;
    }

    public synchronized ObjectNode describeExportJob(JsonNode input, String region) {
        ObjectNode response = mapper.createObjectNode();
        response.set("ExportJobProperties", job(input, region, "export"));
        return response;
    }

    private ObjectNode job(JsonNode input, String region, String kind) {
        String datastoreId = text(input, "DatastoreId");
        datastore(region, datastoreId);
        String jobId = text(input, "JobId");
        return storage.getForAccount(account(), jobKey(region, datastoreId, kind, jobId)).map(this::view)
                .orElseThrow(() -> notFound("Job " + jobId + " not found for data store " + datastoreId + "."));
    }

    public synchronized ObjectNode listImportJobs(JsonNode input, String region) {
        return listJobs(input, region, "import", "ImportJobPropertiesList");
    }

    public synchronized ObjectNode listExportJobs(JsonNode input, String region) {
        return listJobs(input, region, "export", "ExportJobPropertiesList");
    }

    private ObjectNode listJobs(JsonNode input, String region, String kind, String field) {
        String datastoreId = text(input, "DatastoreId");
        datastore(region, datastoreId);
        List<ObjectNode> items = new ArrayList<>();
        List<ObjectNode> jobs = new ArrayList<>(storage.scanForAccount(account(), key -> key.startsWith(region
                + "/job/" + datastoreId + "/" + kind + "/")));
        jobs.sort((a, b) -> Double.compare(b.path("SubmitTime").asDouble(), a.path("SubmitTime").asDouble()));
        for (ObjectNode job : jobs) {
            if (input.hasNonNull("JobName") && !input.get("JobName").asText().equals(job.path("JobName").asText())) {
                continue;
            }
            if (input.hasNonNull("JobStatus") && !input.get("JobStatus").asText().equals(job.path("JobStatus")
                    .asText())) {
                continue;
            }
            double submitted = job.path("SubmitTime").asDouble();
            if ((input.hasNonNull("SubmittedBefore") && submitted >= input.get("SubmittedBefore").asDouble())
                    || (input.hasNonNull("SubmittedAfter") && submitted <= input.get("SubmittedAfter").asDouble())) {
                continue;
            }
            items.add(view(job));
        }
        return page(items, input, field);
    }

    // ---------------------------------------------------------------- tags

    private ObjectNode byArn(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "healthlake")) {
            throw validation("ResourceARN must be a HealthLake data store ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        if (!resource.startsWith("datastore/fhir/") || !account().equals(parsed.accountId())) {
            throw notFound("Resource " + arn + " not found.");
        }
        return datastore(parsed.region(), resource.substring("datastore/fhir/".length()));
    }

    public synchronized ObjectNode listTags(JsonNode input, String region) {
        ObjectNode datastore = byArn(text(input, "ResourceARN"), region);
        ObjectNode response = mapper.createObjectNode();
        response.set("Tags", datastore.path("_tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode input, String region) {
        ObjectNode datastore = byArn(text(input, "ResourceARN"), region);
        datastore.set("_tags", tags(input.path("Tags"), (ArrayNode) datastore.path("_tags")));
        save(account(), AwsArnUtils.parse(datastore.path("DatastoreArn").asText()).region(), datastore);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode input, String region) {
        ObjectNode datastore = byArn(text(input, "ResourceARN"), region);
        Set<String> removed = new HashSet<>();
        input.path("TagKeys").forEach(key -> removed.add(key.asText()));
        ArrayNode kept = mapper.createArrayNode();
        datastore.path("_tags").forEach(tag -> {
            if (!removed.contains(tag.path("Key").asText())) {
                kept.add(tag);
            }
        });
        datastore.set("_tags", kept);
        save(account(), AwsArnUtils.parse(datastore.path("DatastoreArn").asText()).region(), datastore);
        return mapper.createObjectNode();
    }

    private ArrayNode tags(JsonNode given, ArrayNode existing) {
        Map<String, String> merged = new LinkedHashMap<>();
        existing.forEach(tag -> merged.put(tag.path("Key").asText(), tag.path("Value").asText()));
        given.forEach(tag -> {
            String key = tag.path("Key").asText();
            if (key.isEmpty() || key.length() > 128 || key.startsWith("aws:")) {
                throw validation("Tag keys are 1 to 128 characters and do not begin with aws:.");
            }
            merged.put(key, tag.path("Value").asText(""));
        });
        if (merged.size() > 50) {
            throw validation("A resource has at most 50 tags.");
        }
        ArrayNode out = mapper.createArrayNode();
        merged.forEach((key, value) -> out.addObject().put("Key", key).put("Value", value));
        return out;
    }

    // ---------------------------------------------------------------- shared

    private List<ObjectNode> list(String account, String region) {
        List<ObjectNode> found = new ArrayList<>(storage.scanForAccount(account, key -> key.startsWith(region
                + "/datastore/")));
        found.sort((a, b) -> Double.compare(a.path("CreatedAt").asDouble(), b.path("CreatedAt").asDouble()));
        return found;
    }

    private void save(String account, String region, ObjectNode datastore) {
        storage.putForAccount(account, datastoreKey(region, datastore.path("DatastoreId").asText()), datastore);
    }

    private ObjectNode datastore(String region, String datastoreId) {
        if (datastoreId == null || !DATASTORE_ID.matcher(datastoreId).matches()) {
            throw validation("DatastoreId must be 32 lowercase hexadecimal characters.");
        }
        return storage.getForAccount(account(), datastoreKey(region, datastoreId))
                .orElseThrow(() -> notFound("Data store " + datastoreId + " not found."));
    }

    private ObjectNode active(String region, String datastoreId) {
        ObjectNode datastore = datastore(region, datastoreId);
        if (!"ACTIVE".equals(datastore.path("DatastoreStatus").asText())) {
            throw validation("Data store " + datastoreId + " is " + datastore.path("DatastoreStatus").asText()
                    + ", not ACTIVE.");
        }
        return datastore;
    }

    private ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        List<String> hidden = new ArrayList<>();
        view.fieldNames().forEachRemaining(field -> {
            if (field.startsWith("_")) {
                hidden.add(field);
            }
        });
        view.remove(hidden);
        return view;
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode input, String field) {
        int limit = input.path("MaxResults").asInt(100);
        if (limit < 1 || limit > 500) {
            throw validation("MaxResults is 1 to 500.");
        }
        int start = 0;
        String token = input.path("NextToken").asText(null);
        if (token != null && !token.isEmpty()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("NextToken is not valid.");
            }
        }
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray(field);
        int end = Math.min(items.size(), start + limit);
        for (int i = start; i < end; i++) {
            list.add(items.get(i));
        }
        if (end < items.size()) {
            response.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Integer.toString(end).getBytes(StandardCharsets.UTF_8)));
        }
        return response;
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw validation(field + " is required.");
        }
        return value.asText();
    }

    private static byte[] bytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }
}
