package io.floci.aws.services.dataexchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigateway.ApiGatewayService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.dataexchange.DataExchangeService.invalid;
import static io.floci.aws.services.dataexchange.DataExchangeService.key;
import static io.floci.aws.services.dataexchange.DataExchangeService.notFound;
import static io.floci.aws.services.dataexchange.DataExchangeService.text;

/**
 * Data Exchange jobs: importing assets from S3, a signed upload URL, Amazon Redshift datashares, an API Gateway API,
 * an S3 bucket's data access or a Lake Formation tag policy, and exporting assets or whole revisions to S3 or a signed
 * download URL. A job is {@code WAITING} until started, then {@code IN_PROGRESS}, and ends {@code COMPLETED} or
 * {@code ERROR} with its errors. Auto-export event actions run {@code EXPORT_REVISIONS_TO_S3} jobs for each revision
 * published to their data set. {@code SendApiAsset} calls an API asset's API Gateway stage.
 */
@ApplicationScoped
public class DataExchangeJobs implements Resettable {

    private static final Logger LOG = Logger.getLogger(DataExchangeJobs.class);
    private static final String DEFAULT_KEY_PATTERN = "${Revision.CreatedAt}/${Asset.Name}";
    private static final Map<String, String> DETAILS = new LinkedHashMap<>();

    static {
        DETAILS.put("IMPORT_ASSETS_FROM_S3", "ImportAssetsFromS3");
        DETAILS.put("IMPORT_ASSET_FROM_SIGNED_URL", "ImportAssetFromSignedUrl");
        DETAILS.put("EXPORT_ASSETS_TO_S3", "ExportAssetsToS3");
        DETAILS.put("EXPORT_ASSET_TO_SIGNED_URL", "ExportAssetToSignedUrl");
        DETAILS.put("EXPORT_REVISIONS_TO_S3", "ExportRevisionsToS3");
        DETAILS.put("IMPORT_ASSETS_FROM_REDSHIFT_DATA_SHARES", "ImportAssetsFromRedshiftDataShares");
        DETAILS.put("IMPORT_ASSET_FROM_API_GATEWAY_API", "ImportAssetFromApiGatewayApi");
        DETAILS.put("CREATE_S3_DATA_ACCESS_FROM_S3_BUCKET", "CreateS3DataAccessFromS3Bucket");
        DETAILS.put("IMPORT_ASSETS_FROM_LAKE_FORMATION_TAG_POLICY", "ImportAssetsFromLakeFormationTagPolicy");
    }

    private static final Map<String, String> IMPORT_ASSET_TYPES = Map.of(
            "IMPORT_ASSETS_FROM_S3", "S3_SNAPSHOT",
            "IMPORT_ASSET_FROM_SIGNED_URL", "S3_SNAPSHOT",
            "IMPORT_ASSETS_FROM_REDSHIFT_DATA_SHARES", "REDSHIFT_DATA_SHARE",
            "IMPORT_ASSET_FROM_API_GATEWAY_API", "API_GATEWAY_API",
            "CREATE_S3_DATA_ACCESS_FROM_S3_BUCKET", "S3_DATA_ACCESS",
            "IMPORT_ASSETS_FROM_LAKE_FORMATION_TAG_POLICY", "LAKE_FORMATION_DATA_PERMISSION");

    private final DataExchangeService service;
    private final AccountAwareStorageBackend<ObjectNode> jobs;
    private final Instance<S3Service> s3;
    private final Instance<ApiGatewayService> apiGateway;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final long jobMillis;
    private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dataexchange-jobs");
        thread.setDaemon(true);
        return thread;
    });

    /** A signed URL: an upload a job waits for, or a download it serves. */
    record Transfer(String account, String region, String jobId, boolean upload, Instant expires,
                    byte[][] content) {}

    @Inject
    public DataExchangeJobs(DataExchangeService service, StorageFactory storageFactory, Instance<S3Service> s3,
                            Instance<ApiGatewayService> apiGateway, ObjectMapper mapper, EmulatorConfig config) {
        this.service = service;
        this.jobs = storageFactory.create("dataexchange", "jobs.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.s3 = s3;
        this.apiGateway = apiGateway;
        this.mapper = mapper;
        this.baseUrl = config.effectiveBaseUrl();
        this.jobMillis = config.services().dataexchange().jobMillis();
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    // ---------------------------------------------------------------- API

    public ObjectNode createJob(JsonNode request, String region) {
        synchronized (service) {
            service.seed(region);
            String type = DataExchangeService.oneOf(request, "Type", DETAILS.keySet());
            JsonNode details = request.path("Details").path(DETAILS.get(type));
            if (!details.isObject()) {
                throw invalid("Details." + DETAILS.get(type) + " is required for a " + type + " job.");
            }
            String jobId = service.id();
            ObjectNode response = validate(type, (ObjectNode) details.deepCopy(), region, jobId);
            ObjectNode job = mapper.createObjectNode();
            job.put("Arn", service.arn(region, "jobs/" + jobId));
            if (request.has("AssetConfiguration")) {
                job.set("AssetConfiguration", request.get("AssetConfiguration").deepCopy());
            }
            DataExchangeService.stamp(job, true);
            job.putObject("Details").set(DETAILS.get(type), response);
            job.putArray("Errors");
            job.put("Id", jobId);
            job.put("State", "WAITING");
            job.put("Type", type);
            job.putObject("_").put("region", region);
            jobs.putForAccount(service.account(), key(region, jobId), job);
            return service.view(job);
        }
    }

    public ObjectNode getJob(String jobId, String region) {
        synchronized (service) {
            return service.view(job(region, jobId));
        }
    }

    public void startJob(String jobId, String region) {
        synchronized (service) {
            ObjectNode job = job(region, jobId);
            if (!"WAITING".equals(job.path("State").asText())) {
                throw DataExchangeService.conflict("Job " + jobId + " is " + job.path("State").asText()
                        + "; only WAITING jobs can be started.", jobId, "JOB");
            }
            job.put("State", "IN_PROGRESS");
            DataExchangeService.stamp(job, false);
            jobs.putForAccount(service.account(), key(region, jobId), job);
            schedule(region, jobId, null);
        }
    }

    public void cancelJob(String jobId, String region) {
        synchronized (service) {
            ObjectNode job = job(region, jobId);
            if (!"WAITING".equals(job.path("State").asText())) {
                throw DataExchangeService.conflict("Job " + jobId + " is " + job.path("State").asText()
                        + "; only WAITING jobs can be cancelled.", jobId, "JOB");
            }
            job.put("State", "CANCELLED");
            DataExchangeService.stamp(job, false);
            jobs.putForAccount(service.account(), key(region, jobId), job);
            transfers.values().removeIf(transfer -> transfer.jobId().equals(jobId));
        }
    }

    public ObjectNode listJobs(JsonNode query, String region) {
        synchronized (service) {
            String dataSetId = query.path("dataSetId").asText(null);
            String revisionId = query.path("revisionId").asText(null);
            List<ObjectNode> items = new ArrayList<>();
            for (ObjectNode job : service.regional(jobs, region)) {
                JsonNode details = job.path("Details").elements().hasNext() ? job.path("Details").elements().next()
                        : mapper.createObjectNode();
                if (dataSetId != null && !dataSetId.equals(details.path("DataSetId").asText())) {
                    continue;
                }
                if (revisionId != null && !revisionId.equals(details.path("RevisionId").asText())
                        && !revisions(details).contains(revisionId)) {
                    continue;
                }
                items.add(service.view(job));
            }
            items.sort(Comparator.comparing((ObjectNode item) -> item.path("CreatedAt").asText()).reversed());
            return service.page(items, query, "Jobs", item -> item.path("Id").asText());
        }
    }

    boolean hasJobInProgress(String region, String dataSetId) {
        for (ObjectNode job : service.regional(jobs, region)) {
            String state = job.path("State").asText();
            JsonNode details = job.path("Details").elements().hasNext() ? job.path("Details").elements().next()
                    : mapper.createObjectNode();
            if (("IN_PROGRESS".equals(state)) && dataSetId.equals(details.path("DataSetId").asText())) {
                return true;
            }
        }
        return false;
    }

    /** Runs each auto-export event action of {@code dataSet} on a revision just published to it. */
    void autoExport(String region, ObjectNode dataSet, ObjectNode revision) {
        for (ObjectNode action : service.eventActionsFor(region, dataSet.path("Id").asText())) {
            JsonNode export = action.path("Action").path("ExportRevisionToS3");
            String jobId = service.id();
            ObjectNode details = mapper.createObjectNode();
            details.put("DataSetId", dataSet.path("Id").asText());
            if (export.has("Encryption")) {
                details.set("Encryption", export.get("Encryption").deepCopy());
            }
            ObjectNode destination = details.putArray("RevisionDestinations").addObject();
            destination.put("Bucket", export.path("RevisionDestination").path("Bucket").asText());
            destination.put("KeyPattern", export.path("RevisionDestination").path("KeyPattern")
                    .asText(DEFAULT_KEY_PATTERN));
            destination.put("RevisionId", revision.path("Id").asText());
            details.put("EventActionArn", action.path("Arn").asText());
            ObjectNode job = mapper.createObjectNode();
            job.put("Arn", service.arn(region, "jobs/" + jobId));
            DataExchangeService.stamp(job, true);
            job.putObject("Details").set("ExportRevisionsToS3", details);
            job.putArray("Errors");
            job.put("Id", jobId);
            job.put("State", "IN_PROGRESS");
            job.put("Type", "EXPORT_REVISIONS_TO_S3");
            job.putObject("_").put("region", region);
            jobs.putForAccount(service.account(), key(region, jobId), job);
            schedule(region, jobId, action.path("Arn").asText());
        }
    }

    // ---------------------------------------------------------------- signed URLs

    /** Stores an upload for a signed URL; false when the URL is unknown or expired. */
    boolean upload(String token, byte[] body) {
        Transfer transfer = transfers.get(token);
        if (transfer == null || !transfer.upload() || transfer.expires().isBefore(Instant.now())) {
            return false;
        }
        transfer.content()[0] = body;
        return true;
    }

    /** The bytes a signed download URL serves, if it is known and unexpired. */
    Optional<byte[]> download(String token) {
        Transfer transfer = transfers.get(token);
        if (transfer == null || transfer.upload() || transfer.expires().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.ofNullable(transfer.content()[0]);
    }

    private String signedUrl(String region, String jobId, boolean upload, byte[] content, Instant expires) {
        String token = service.id() + service.id();
        transfers.put(token, new Transfer(service.account(), region, jobId, upload, expires,
                new byte[][] {content}));
        return baseUrl + DataExchangeTransferController.PATH + "/" + token;
    }

    // ---------------------------------------------------------------- validation

    private ObjectNode validate(String type, ObjectNode details, String region, String jobId) {
        String dataSetId = text(details, "DataSetId", 40);
        ObjectNode dataSet = service.dataSet(region, dataSetId);
        if (IMPORT_ASSET_TYPES.containsKey(type)) {
            if (!"OWNED".equals(dataSet.path("Origin").asText())) {
                throw invalid("Assets can only be imported into owned data sets.");
            }
            String expected = IMPORT_ASSET_TYPES.get(type);
            if (!expected.equals(dataSet.path("AssetType").asText())) {
                throw invalid("A " + type + " job needs a data set of asset type " + expected + "; "
                        + dataSetId + " is " + dataSet.path("AssetType").asText() + ".");
            }
            String revisionId = text(details, "RevisionId", 40);
            if (service.revision(region, dataSetId, revisionId).path("Finalized").asBoolean(false)) {
                throw DataExchangeService.conflict("Revision " + revisionId + " is finalized; assets cannot be added.",
                        revisionId, "REVISION");
            }
        }
        Instant expires = Instant.now().plus(1, ChronoUnit.HOURS);
        switch (type) {
            case "IMPORT_ASSETS_FROM_S3" -> {
                JsonNode sources = details.path("AssetSources");
                if (!sources.isArray() || sources.isEmpty()) {
                    throw invalid("AssetSources must list at least one S3 object.");
                }
                if (sources.size() > 100) {
                    throw DataExchangeService.limit("Assets per import job from Amazon S3", 100);
                }
                for (JsonNode source : sources) {
                    text(source, "Bucket", 63);
                    text(source, "Key", 1024);
                }
            }
            case "IMPORT_ASSET_FROM_SIGNED_URL" -> {
                text(details, "AssetName", 1024);
                String md5 = text(details, "Md5Hash", 24);
                if (md5.length() != 24) {
                    throw invalid("Md5Hash must be the base64-encoded MD5 of the file (24 characters).");
                }
                details.put("SignedUrl", signedUrl(region, jobId, true, null, expires));
                details.put("SignedUrlExpiresAt", expires.toString());
            }
            case "EXPORT_ASSETS_TO_S3" -> {
                String revisionId = text(details, "RevisionId", 40);
                requireExportable(region, dataSetId, revisionId);
                JsonNode destinations = details.path("AssetDestinations");
                if (!destinations.isArray() || destinations.isEmpty()) {
                    throw invalid("AssetDestinations must list at least one asset.");
                }
                for (JsonNode destination : destinations) {
                    service.asset(region, dataSetId, revisionId, text(destination, "AssetId", 40));
                    text(destination, "Bucket", 63);
                }
                encryption(details);
            }
            case "EXPORT_ASSET_TO_SIGNED_URL" -> {
                String revisionId = text(details, "RevisionId", 40);
                requireExportable(region, dataSetId, revisionId);
                service.asset(region, dataSetId, revisionId, text(details, "AssetId", 40));
            }
            case "EXPORT_REVISIONS_TO_S3" -> {
                JsonNode destinations = details.path("RevisionDestinations");
                if (!destinations.isArray() || destinations.isEmpty()) {
                    throw invalid("RevisionDestinations must list at least one revision.");
                }
                for (JsonNode destination : destinations) {
                    requireExportable(region, dataSetId, text(destination, "RevisionId", 40));
                    text(destination, "Bucket", 63);
                    keyPattern(destination);
                }
                encryption(details);
            }
            case "IMPORT_ASSETS_FROM_REDSHIFT_DATA_SHARES" -> {
                JsonNode sources = details.path("AssetSources");
                if (!sources.isArray() || sources.isEmpty()) {
                    throw invalid("AssetSources must list at least one datashare.");
                }
                for (JsonNode source : sources) {
                    String arn = text(source, "DataShareArn", 2048);
                    if (!arn.contains(":redshift:") || !arn.contains(":datashare:")) {
                        throw invalid("DataShareArn " + arn + " is not a Redshift datashare ARN.");
                    }
                }
            }
            case "IMPORT_ASSET_FROM_API_GATEWAY_API" -> {
                text(details, "ApiId", 64);
                text(details, "ApiName", 1024);
                text(details, "Stage", 128);
                DataExchangeService.oneOf(details, "ProtocolType", Set.of("REST"));
                String md5 = text(details, "ApiSpecificationMd5Hash", 24);
                if (md5.length() != 24) {
                    throw invalid("ApiSpecificationMd5Hash must be the base64-encoded MD5 of the specification.");
                }
                details.put("ApiSpecificationUploadUrl", signedUrl(region, jobId, true, null, expires));
                details.put("ApiSpecificationUploadUrlExpiresAt", expires.toString());
            }
            case "CREATE_S3_DATA_ACCESS_FROM_S3_BUCKET" -> text(details.path("AssetSource"), "Bucket", 63);
            case "IMPORT_ASSETS_FROM_LAKE_FORMATION_TAG_POLICY" -> {
                text(details, "CatalogId", 12);
                String role = text(details, "RoleArn", 2048);
                if (!role.matches("^arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::\\d{12}:role/.+$")) {
                    throw invalid("RoleArn must be an IAM role ARN.");
                }
                if (!details.has("Database") && !details.has("Table")) {
                    throw invalid("A Lake Formation tag policy needs a Database or Table expression.");
                }
            }
            default -> {
            }
        }
        return details;
    }

    private void requireExportable(String region, String dataSetId, String revisionId) {
        ObjectNode revision = service.revision(region, dataSetId, revisionId);
        if (revision.path("Revoked").asBoolean(false)) {
            throw invalid("Revision " + revisionId + " is revoked and cannot be exported.");
        }
        if (!"S3_SNAPSHOT".equals(service.dataSet(region, dataSetId).path("AssetType").asText())) {
            throw invalid("Only S3_SNAPSHOT data sets can be exported.");
        }
    }

    private static void keyPattern(JsonNode destination) {
        if (destination.hasNonNull("KeyPattern")) {
            String pattern = destination.get("KeyPattern").asText();
            if (!pattern.contains("${Asset.Name}") && !pattern.contains("${Asset.Id}")) {
                throw invalid("KeyPattern must include ${Asset.Name} or ${Asset.Id}.");
            }
        }
    }

    private static void encryption(JsonNode details) {
        JsonNode encryption = details.path("Encryption");
        if (encryption.isObject()) {
            String type = text(encryption, "Type", 16);
            if (!Set.of("aws:kms", "AES256").contains(type)) {
                throw invalid("Encryption.Type must be aws:kms or AES256.");
            }
        }
    }

    // ---------------------------------------------------------------- execution

    private void schedule(String region, String jobId, String eventActionArn) {
        String account = service.account();
        worker.schedule(() -> RequestScopes.runAs(account, region, () -> run(region, jobId, eventActionArn)),
                Math.max(1, jobMillis), TimeUnit.MILLISECONDS);
    }

    private void run(String region, String jobId, String eventActionArn) {
        synchronized (service) {
            ObjectNode job = jobs.getForAccount(service.account(), key(region, jobId)).orElse(null);
            if (job == null || !"IN_PROGRESS".equals(job.path("State").asText())) {
                return;
            }
            String type = job.path("Type").asText();
            ObjectNode details = (ObjectNode) job.path("Details").path(DETAILS.get(type));
            ArrayNode errors = job.putArray("Errors");
            try {
                execute(type, details, region, jobId, errors);
            } catch (AwsException | IllegalArgumentException e) {
                errors.addObject().put("Code", code(e)).put("Message", e.getMessage());
            } catch (RuntimeException e) {
                LOG.debugv(e, "Data Exchange job {0} failed", jobId);
                errors.addObject().put("Code", "INTERNAL_SERVER_EXCEPTION").put("Message",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            job.put("State", errors.isEmpty() ? "COMPLETED" : "ERROR");
            DataExchangeService.stamp(job, false);
            jobs.putForAccount(service.account(), key(region, jobId), job);
            if (eventActionArn != null) {
                ObjectNode detail = mapper.createObjectNode();
                detail.put("EventActionArn", eventActionArn);
                detail.put("JobId", jobId);
                ArrayNode revisionIds = detail.putArray("RevisionIds");
                revisions(details).forEach(revisionIds::add);
                if (!errors.isEmpty()) {
                    detail.set("Errors", errors.deepCopy());
                }
                service.publish(region, errors.isEmpty() ? "Auto-export Job Completed" : "Auto-export Job Failed",
                        detail, job.path("Arn").asText());
            }
        }
    }

    private void execute(String type, ObjectNode details, String region, String jobId, ArrayNode errors) {
        String dataSetId = details.path("DataSetId").asText();
        String revisionId = details.path("RevisionId").asText();
        switch (type) {
            case "IMPORT_ASSETS_FROM_S3" -> {
                List<JsonNode> missing = new ArrayList<>();
                List<Object[]> found = new ArrayList<>();
                for (JsonNode source : details.path("AssetSources")) {
                    String bucket = source.path("Bucket").asText();
                    String key = source.path("Key").asText();
                    try {
                        S3Object object = s3.get().getObject(bucket, key);
                        found.add(new Object[] {key, object.getData()});
                    } catch (AwsException e) {
                        missing.add(source);
                    }
                }
                if (!missing.isEmpty()) {
                    ObjectNode error = errors.addObject();
                    error.put("Code", "RESOURCE_NOT_FOUND_EXCEPTION");
                    error.put("Message", missing.size() + " of the S3 objects to import do not exist or cannot be read.");
                    error.put("ResourceType", "ASSET");
                    ArrayNode sources = error.putObject("Details").putArray("ImportAssetsFromS3JobErrorDetails");
                    missing.forEach(source -> sources.add(source.deepCopy()));
                    return;
                }
                for (Object[] entry : found) {
                    byte[] data = (byte[]) entry[1];
                    ObjectNode assetDetails = mapper.createObjectNode();
                    assetDetails.putObject("S3SnapshotAsset").put("Size", data.length);
                    service.putAsset(region, dataSetId, revisionId, (String) entry[0], "S3_SNAPSHOT", assetDetails,
                            data, null);
                }
            }
            case "IMPORT_ASSET_FROM_SIGNED_URL" -> {
                byte[] data = uploaded(jobId);
                if (data == null) {
                    errors.addObject().put("Code", "VALIDATION_EXCEPTION")
                            .put("Message", "Nothing was uploaded to the job's signed URL before it started.")
                            .putObject("Details").putObject("ImportAssetFromSignedUrlJobErrorDetails")
                            .put("AssetName", details.path("AssetName").asText());
                    return;
                }
                if (!md5(data).equals(details.path("Md5Hash").asText())) {
                    errors.addObject().put("Code", "VALIDATION_EXCEPTION")
                            .put("Message", "The uploaded file's MD5 does not match Md5Hash.")
                            .putObject("Details").putObject("ImportAssetFromSignedUrlJobErrorDetails")
                            .put("AssetName", details.path("AssetName").asText());
                    return;
                }
                ObjectNode assetDetails = mapper.createObjectNode();
                assetDetails.putObject("S3SnapshotAsset").put("Size", data.length);
                service.putAsset(region, dataSetId, revisionId, details.path("AssetName").asText(), "S3_SNAPSHOT",
                        assetDetails, data, null);
            }
            case "EXPORT_ASSETS_TO_S3" -> {
                for (JsonNode destination : details.path("AssetDestinations")) {
                    String assetId = destination.path("AssetId").asText();
                    ObjectNode asset = service.asset(region, dataSetId, revisionId, assetId);
                    String key = destination.hasNonNull("Key") ? destination.get("Key").asText()
                            : asset.path("Name").asText();
                    put(destination.path("Bucket").asText(), key,
                            service.assetBytes(region, dataSetId, revisionId, assetId), errors);
                }
            }
            case "EXPORT_ASSET_TO_SIGNED_URL" -> {
                String assetId = details.path("AssetId").asText();
                service.asset(region, dataSetId, revisionId, assetId);
                Instant expires = Instant.now().plus(1, ChronoUnit.HOURS);
                details.put("SignedUrl", signedUrl(region, jobId, false,
                        service.assetBytes(region, dataSetId, revisionId, assetId), expires));
                details.put("SignedUrlExpiresAt", expires.toString());
            }
            case "EXPORT_REVISIONS_TO_S3" -> {
                for (JsonNode destination : details.path("RevisionDestinations")) {
                    String id = destination.path("RevisionId").asText();
                    ObjectNode revision = service.revision(region, dataSetId, id);
                    String pattern = destination.path("KeyPattern").asText(DEFAULT_KEY_PATTERN);
                    for (ObjectNode asset : service.assetsOf(region, dataSetId, id)) {
                        put(destination.path("Bucket").asText(), expand(pattern, revision, asset),
                                service.assetBytes(region, dataSetId, id, asset.path("Id").asText()), errors);
                    }
                }
            }
            case "IMPORT_ASSETS_FROM_REDSHIFT_DATA_SHARES" -> {
                for (JsonNode source : details.path("AssetSources")) {
                    String arn = source.path("DataShareArn").asText();
                    ObjectNode assetDetails = mapper.createObjectNode();
                    assetDetails.putObject("RedshiftDataShareAsset").put("Arn", arn);
                    service.putAsset(region, dataSetId, revisionId, arn.substring(arn.lastIndexOf('/') + 1),
                            "REDSHIFT_DATA_SHARE", assetDetails, null, null);
                }
            }
            case "IMPORT_ASSET_FROM_API_GATEWAY_API" -> {
                String apiId = details.path("ApiId").asText();
                String stage = details.path("Stage").asText();
                if (apiGateway.isResolvable()) {
                    try {
                        apiGateway.get().getRestApi(region, apiId);
                    } catch (AwsException e) {
                        errors.addObject().put("Code", "RESOURCE_NOT_FOUND_EXCEPTION")
                                .put("Message", "API Gateway API " + apiId + " does not exist.")
                                .put("ResourceId", apiId);
                        return;
                    }
                }
                byte[] specification = uploaded(jobId);
                if (specification == null || !md5(specification).equals(details.path("ApiSpecificationMd5Hash")
                        .asText())) {
                    errors.addObject().put("Code", "VALIDATION_EXCEPTION").put("Message", specification == null
                            ? "The API specification was not uploaded before the job started."
                            : "The API specification's MD5 does not match ApiSpecificationMd5Hash.");
                    return;
                }
                ObjectNode assetDetails = mapper.createObjectNode();
                ObjectNode api = assetDetails.putObject("ApiGatewayApiAsset");
                for (String field : List.of("ApiDescription", "ApiId", "ApiKey", "ApiName", "ProtocolType", "Stage")) {
                    if (details.hasNonNull(field)) {
                        api.put(field, details.get(field).asText());
                    }
                }
                api.put("ApiEndpoint", baseUrl + "/execute-api/" + apiId + "/" + stage);
                service.putAsset(region, dataSetId, revisionId, details.path("ApiName").asText(), "API_GATEWAY_API",
                        assetDetails, specification, null);
            }
            case "CREATE_S3_DATA_ACCESS_FROM_S3_BUCKET" -> {
                JsonNode source = details.path("AssetSource");
                String bucket = source.path("Bucket").asText();
                if (!s3.get().bucketExists(bucket)) {
                    errors.addObject().put("Code", "RESOURCE_NOT_FOUND_EXCEPTION")
                            .put("Message", "S3 bucket " + bucket + " does not exist.").put("ResourceId", bucket);
                    return;
                }
                ObjectNode assetDetails = mapper.createObjectNode();
                ObjectNode access = assetDetails.putObject("S3DataAccessAsset");
                access.setAll((ObjectNode) source.deepCopy());
                String suffix = service.id().substring(0, 8);
                access.put("S3AccessPointAlias", "dx-" + suffix + "-" + service.id().substring(0, 26) + "-s3alias");
                access.put("S3AccessPointArn", service.regionResolver.buildArn("s3", region, service.account(),
                        "accesspoint/dx-" + suffix));
                service.putAsset(region, dataSetId, revisionId, bucket, "S3_DATA_ACCESS", assetDetails, null, null);
            }
            case "IMPORT_ASSETS_FROM_LAKE_FORMATION_TAG_POLICY" -> {
                ObjectNode assetDetails = mapper.createObjectNode();
                ObjectNode permission = assetDetails.putObject("LakeFormationDataPermissionAsset");
                ObjectNode policy = permission.putObject("LakeFormationDataPermissionDetails")
                        .putObject("LFTagPolicy");
                policy.put("CatalogId", details.path("CatalogId").asText());
                boolean database = details.has("Database");
                JsonNode scope = details.path(database ? "Database" : "Table");
                policy.put("ResourceType", database ? "DATABASE" : "TABLE");
                policy.putObject("ResourceDetails").putObject(database ? "Database" : "Table")
                        .set("Expression", scope.path("Expression").deepCopy());
                permission.put("LakeFormationDataPermissionType", "LFTagPolicy");
                permission.set("Permissions", scope.path("Permissions").deepCopy());
                permission.put("RoleArn", details.path("RoleArn").asText());
                service.putAsset(region, dataSetId, revisionId, "LF-Tag policy " + service.id().substring(0, 8),
                        "LAKE_FORMATION_DATA_PERMISSION", assetDetails, null, null);
            }
            default -> throw invalid("Unsupported job type " + type + ".");
        }
    }

    private byte[] uploaded(String jobId) {
        for (Transfer transfer : transfers.values()) {
            if (transfer.jobId().equals(jobId) && transfer.upload()) {
                return transfer.content()[0];
            }
        }
        return null;
    }

    private void put(String bucket, String key, byte[] data, ArrayNode errors) {
        if (!s3.get().bucketExists(bucket)) {
            errors.addObject().put("Code", "RESOURCE_NOT_FOUND_EXCEPTION")
                    .put("Message", "S3 bucket " + bucket + " does not exist.").put("ResourceId", bucket);
            return;
        }
        s3.get().putObject(bucket, key, data, "application/octet-stream", Map.of());
    }

    /** Expands an export key pattern's {@code ${Revision...}} and {@code ${Asset...}} variables. */
    static String expand(String pattern, JsonNode revision, JsonNode asset) {
        ZonedDateTime created = Instant.parse(revision.path("CreatedAt").asText()).atZone(ZoneOffset.UTC);
        return pattern.replace("${Revision.CreatedAt.Year}", String.format("%04d", created.getYear()))
                .replace("${Revision.CreatedAt.Month}", String.format("%02d", created.getMonthValue()))
                .replace("${Revision.CreatedAt.Day}", String.format("%02d", created.getDayOfMonth()))
                .replace("${Revision.CreatedAt}", revision.path("CreatedAt").asText())
                .replace("${Revision.Id}", revision.path("Id").asText())
                .replace("${Asset.Name}", asset.path("Name").asText())
                .replace("${Asset.Id}", asset.path("Id").asText());
    }

    private static List<String> revisions(JsonNode details) {
        List<String> ids = new ArrayList<>();
        if (details.hasNonNull("RevisionId")) {
            ids.add(details.get("RevisionId").asText());
        }
        details.path("RevisionDestinations").forEach(destination -> ids.add(destination.path("RevisionId").asText()));
        return ids;
    }

    static String md5(byte[] data) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String code(RuntimeException e) {
        if (e instanceof AwsException aws) {
            return switch (aws.getErrorCode()) {
                case "ResourceNotFoundException" -> "RESOURCE_NOT_FOUND_EXCEPTION";
                case "ServiceLimitExceededException" -> "SERVICE_QUOTA_EXCEEDED_EXCEPTION";
                case "AccessDeniedException" -> "ACCESS_DENIED_EXCEPTION";
                default -> "VALIDATION_EXCEPTION";
            };
        }
        return "VALIDATION_EXCEPTION";
    }

    private ObjectNode job(String region, String jobId) {
        return jobs.getForAccount(service.account(), key(region, jobId))
                .orElseThrow(() -> notFound("Job " + jobId + " was not found.", jobId, "JOB"));
    }

    // ---------------------------------------------------------------- API assets

    /** Calls an API asset's API Gateway stage with the caller's method, path, query, headers and body. */
    public HttpResponse<byte[]> sendApiAsset(String dataSetId, String revisionId, String assetId, String method,
                                             String path, Map<String, String> query, Map<String, String> headers,
                                             byte[] body, String region) {
        ObjectNode asset;
        synchronized (service) {
            service.seed(region);
            asset = service.asset(region, dataSetId, revisionId, assetId);
            if (service.revision(region, dataSetId, revisionId).path("Revoked").asBoolean(false)) {
                throw invalid("Revision " + revisionId + " is revoked.");
            }
        }
        if (!"API_GATEWAY_API".equals(asset.path("AssetType").asText())) {
            throw invalid("Asset " + assetId + " is not an API Gateway API asset.");
        }
        JsonNode api = asset.path("AssetDetails").path("ApiGatewayApiAsset");
        StringBuilder url = new StringBuilder(api.path("ApiEndpoint").asText());
        url.append(path == null || path.isEmpty() ? "/" : path.startsWith("/") ? path : "/" + path);
        if (!query.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            query.forEach((name, value) -> pairs.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(value, StandardCharsets.UTF_8)));
            url.append('?').append(String.join("&", pairs));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(29))
                .method(method == null ? "POST" : method.toUpperCase(), body == null || body.length == 0
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((name, value) -> {
            if (!Set.of("host", "content-length", "connection").contains(name.toLowerCase())) {
                request.header(name, value);
            }
        });
        if (api.hasNonNull("ApiKey")) {
            request.header("x-api-key", api.get("ApiKey").asText());
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw invalid("The API asset's endpoint could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw invalid("The API asset call was interrupted.");
        }
    }

    @Override
    public void clear() {
        jobs.clear();
        transfers.clear();
    }
}
