package io.github.hectorvent.floci.services.healthlake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local AWS HealthLake stub. FHIR data stores and bulk import/export jobs are
 * in-memory; provisioning is instantaneous ({@code ACTIVE} / {@code COMPLETED}).
 *
 * @see <a href="https://docs.aws.amazon.com/healthlake/latest/APIReference/API_Operations.html">HealthLake API</a>
 */
@ApplicationScoped
public class HealthLakeService implements Resettable {

    enum JobKind {
        IMPORT, EXPORT
    }

    static final class Datastore {
        String id;
        String arn;
        String name;
        String status;
        String typeVersion;
        String endpoint;
        long createdAt;
        JsonNode sseConfiguration;
        JsonNode preloadDataConfig;
        JsonNode identityProviderConfiguration;
        JsonNode nlpConfiguration;
        JsonNode analyticsConfiguration;
        JsonNode profileConfiguration;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    static final class Job {
        JobKind kind;
        String jobId;
        String jobName;
        String status;
        long submitTime;
        Long endTime;
        String datastoreId;
        JsonNode inputDataConfig;
        JsonNode outputDataConfig;
        String dataAccessRoleArn;
        String validationLevel;
        String message;
    }

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final ConcurrentHashMap<String, Datastore> datastores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> createTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> importTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> exportTokens = new ConcurrentHashMap<>();

    @Inject
    public HealthLakeService(ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        datastores.clear();
        jobs.clear();
        createTokens.clear();
        importTokens.clear();
        exportTokens.clear();
    }

    public ObjectNode createDatastore(JsonNode request, String region) {
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            String existingId = createTokens.get(token);
            if (existingId != null) {
                Datastore existing = datastores.get(existingId);
                if (existing != null) {
                    return createResponse(existing);
                }
            }
        }
        String typeVersion = requireText(request, "DatastoreTypeVersion");
        if (!"R4".equals(typeVersion)) {
            throw invalid("DatastoreTypeVersion " + typeVersion + " is not supported.");
        }
        String id = newId();
        Datastore datastore = new Datastore();
        datastore.id = id;
        datastore.arn = regionResolver.buildArn("healthlake", region, "datastore/fhir/" + id);
        datastore.name = textOrNull(request, "DatastoreName");
        datastore.status = "ACTIVE";
        datastore.typeVersion = typeVersion;
        datastore.endpoint = "https://healthlake." + region + ".amazonaws.com/datastore/" + id + "/r4/";
        datastore.createdAt = nowSeconds();
        datastore.sseConfiguration = copyOrDefaultSse(request.get("SseConfiguration"));
        datastore.preloadDataConfig = copy(request.get("PreloadDataConfig"));
        datastore.identityProviderConfiguration = copy(request.get("IdentityProviderConfiguration"));
        datastore.nlpConfiguration = copy(request.get("NlpConfiguration"));
        datastore.analyticsConfiguration = copy(request.get("AnalyticsConfiguration"));
        datastore.profileConfiguration = copy(request.get("ProfileConfiguration"));
        datastore.tags.putAll(readTags(request));
        datastores.put(id, datastore);
        if (token != null) {
            createTokens.put(token, id);
        }
        return createResponse(datastore);
    }

    public ObjectNode describeDatastore(JsonNode request) {
        return objectMapper.createObjectNode().set("DatastoreProperties", datastoreNode(requireDatastore(request)));
    }

    public ObjectNode updateDatastore(JsonNode request) {
        Datastore datastore = requireDatastore(request);
        if (request.hasNonNull("DatastoreName")) {
            datastore.name = request.get("DatastoreName").asText();
        }
        if (request.has("IdentityProviderConfiguration")) {
            datastore.identityProviderConfiguration = copy(request.get("IdentityProviderConfiguration"));
        }
        if (request.has("NlpConfiguration")) {
            datastore.nlpConfiguration = copy(request.get("NlpConfiguration"));
        }
        if (request.has("AnalyticsConfiguration")) {
            datastore.analyticsConfiguration = copy(request.get("AnalyticsConfiguration"));
        }
        if (request.has("ProfileConfiguration")) {
            datastore.profileConfiguration = copy(request.get("ProfileConfiguration"));
        }
        return objectMapper.createObjectNode().set("DatastoreProperties", datastoreNode(datastore));
    }

    public ObjectNode deleteDatastore(JsonNode request) {
        Datastore datastore = requireDatastore(request);
        datastores.remove(datastore.id);
        jobs.values().removeIf(job -> datastore.id.equals(job.datastoreId));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DatastoreId", datastore.id);
        response.put("DatastoreArn", datastore.arn);
        response.put("DatastoreStatus", "DELETING");
        response.put("DatastoreEndpoint", datastore.endpoint);
        return response;
    }

    public ObjectNode listDatastores(JsonNode request) {
        JsonNode filter = request.path("Filter");
        String name = textOrNull(filter, "DatastoreName");
        String status = textOrNull(filter, "DatastoreStatus");
        ArrayNode list = objectMapper.createArrayNode();
        datastores.values().stream()
                .filter(ds -> name == null || name.equals(ds.name))
                .filter(ds -> status == null || status.equals(ds.status))
                .sorted(Comparator.comparingLong((Datastore ds) -> ds.createdAt).reversed())
                .forEach(ds -> list.add(datastoreNode(ds)));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("DatastorePropertiesList", list);
        return response;
    }

    public ObjectNode startImportJob(JsonNode request, String region) {
        Datastore datastore = requireDatastore(request);
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            String existingId = importTokens.get(token);
            if (existingId != null) {
                Job existing = jobs.get(existingId);
                if (existing != null) {
                    return startResponse(existing);
                }
            }
        }
        JsonNode input = request.get("InputDataConfig");
        if (textOrNull(input, "S3Uri") == null) {
            throw invalid("InputDataConfig.S3Uri is required.");
        }
        JsonNode output = requireS3Output(request.get("JobOutputDataConfig"));
        String roleArn = requireText(request, "DataAccessRoleArn");
        Job job = newJob(JobKind.IMPORT, datastore.id, textOrNull(request, "JobName"), roleArn);
        job.inputDataConfig = copy(input);
        job.outputDataConfig = output;
        job.validationLevel = textOrNull(request, "ValidationLevel");
        jobs.put(job.jobId, job);
        if (token != null) {
            importTokens.put(token, job.jobId);
        }
        return startResponse(job);
    }

    public ObjectNode startExportJob(JsonNode request, String region) {
        Datastore datastore = requireDatastore(request);
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            String existingId = exportTokens.get(token);
            if (existingId != null) {
                Job existing = jobs.get(existingId);
                if (existing != null) {
                    return startResponse(existing);
                }
            }
        }
        JsonNode output = requireS3Output(request.get("OutputDataConfig"));
        String roleArn = requireText(request, "DataAccessRoleArn");
        Job job = newJob(JobKind.EXPORT, datastore.id, textOrNull(request, "JobName"), roleArn);
        job.outputDataConfig = output;
        jobs.put(job.jobId, job);
        if (token != null) {
            exportTokens.put(token, job.jobId);
        }
        return startResponse(job);
    }

    public ObjectNode describeImportJob(JsonNode request) {
        return objectMapper.createObjectNode()
                .set("ImportJobProperties", jobNode(requireJob(request, JobKind.IMPORT)));
    }

    public ObjectNode describeExportJob(JsonNode request) {
        return objectMapper.createObjectNode()
                .set("ExportJobProperties", jobNode(requireJob(request, JobKind.EXPORT)));
    }

    public ObjectNode listImportJobs(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ImportJobPropertiesList", listJobs(request, JobKind.IMPORT));
        return response;
    }

    public ObjectNode listExportJobs(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ExportJobPropertiesList", listJobs(request, JobKind.EXPORT));
        return response;
    }

    public ObjectNode tagResource(JsonNode request) {
        Datastore datastore = requireDatastoreByArn(requireText(request, "ResourceARN"));
        datastore.tags.putAll(readTags(request));
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        Datastore datastore = requireDatastoreByArn(requireText(request, "ResourceARN"));
        JsonNode keys = request.get("TagKeys");
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                datastore.tags.remove(key.asText());
            }
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        Datastore datastore = requireDatastoreByArn(requireText(request, "ResourceARN"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tags = response.putArray("Tags");
        writeTags(tags, datastore.tags);
        return response;
    }

    private ArrayNode listJobs(JsonNode request, JobKind kind) {
        Datastore datastore = requireDatastore(request);
        String jobName = textOrNull(request, "JobName");
        String jobStatus = textOrNull(request, "JobStatus");
        ArrayNode list = objectMapper.createArrayNode();
        jobs.values().stream()
                .filter(job -> job.kind == kind && datastore.id.equals(job.datastoreId))
                .filter(job -> jobName == null || jobName.equals(job.jobName))
                .peek(this::completeIfNeeded)
                .filter(job -> jobStatus == null || jobStatus.equals(job.status))
                .sorted(Comparator.comparingLong((Job job) -> job.submitTime).reversed())
                .forEach(job -> list.add(jobNode(job)));
        return list;
    }

    private Job requireJob(JsonNode request, JobKind kind) {
        Datastore datastore = requireDatastore(request);
        String jobId = requireText(request, "JobId");
        Job job = jobs.get(jobId);
        if (job == null || job.kind != kind || !datastore.id.equals(job.datastoreId)) {
            throw notFound("The requested job was not found.");
        }
        completeIfNeeded(job);
        return job;
    }

    private void completeIfNeeded(Job job) {
        if ("SUBMITTED".equals(job.status) || "QUEUED".equals(job.status) || "IN_PROGRESS".equals(job.status)) {
            job.status = "COMPLETED";
            job.endTime = nowSeconds();
            job.message = "Job completed.";
        }
    }

    private Job newJob(JobKind kind, String datastoreId, String jobName, String roleArn) {
        Job job = new Job();
        job.kind = kind;
        job.jobId = newId();
        job.jobName = jobName;
        job.status = "SUBMITTED";
        job.submitTime = nowSeconds();
        job.datastoreId = datastoreId;
        job.dataAccessRoleArn = roleArn;
        return job;
    }

    private Datastore requireDatastore(JsonNode request) {
        return requireDatastoreId(requireText(request, "DatastoreId"));
    }

    private Datastore requireDatastoreId(String datastoreId) {
        Datastore datastore = datastores.get(datastoreId);
        if (datastore == null) {
            throw notFound("The requested data store was not found.");
        }
        return datastore;
    }

    private Datastore requireDatastoreByArn(String arn) {
        return datastores.values().stream()
                .filter(ds -> arn.equals(ds.arn))
                .findFirst()
                .orElseThrow(() -> notFound("The requested data store was not found."));
    }

    private ObjectNode createResponse(Datastore datastore) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DatastoreId", datastore.id);
        response.put("DatastoreArn", datastore.arn);
        response.put("DatastoreStatus", datastore.status);
        response.put("DatastoreEndpoint", datastore.endpoint);
        return response;
    }

    private ObjectNode startResponse(Job job) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("JobId", job.jobId);
        response.put("JobStatus", job.status);
        response.put("DatastoreId", job.datastoreId);
        return response;
    }

    private ObjectNode datastoreNode(Datastore datastore) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("DatastoreId", datastore.id);
        node.put("DatastoreArn", datastore.arn);
        if (datastore.name != null) {
            node.put("DatastoreName", datastore.name);
        }
        node.put("DatastoreStatus", datastore.status);
        node.put("CreatedAt", datastore.createdAt);
        node.put("DatastoreTypeVersion", datastore.typeVersion);
        node.put("DatastoreEndpoint", datastore.endpoint);
        setIfPresent(node, "SseConfiguration", datastore.sseConfiguration);
        setIfPresent(node, "PreloadDataConfig", datastore.preloadDataConfig);
        setIfPresent(node, "IdentityProviderConfiguration", datastore.identityProviderConfiguration);
        setIfPresent(node, "NlpConfiguration", datastore.nlpConfiguration);
        setIfPresent(node, "AnalyticsConfiguration", datastore.analyticsConfiguration);
        setIfPresent(node, "ProfileConfiguration", datastore.profileConfiguration);
        return node;
    }

    private ObjectNode jobNode(Job job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("JobId", job.jobId);
        if (job.jobName != null) {
            node.put("JobName", job.jobName);
        }
        node.put("JobStatus", job.status);
        node.put("SubmitTime", job.submitTime);
        if (job.endTime != null) {
            node.put("EndTime", job.endTime);
        }
        node.put("DatastoreId", job.datastoreId);
        if (job.kind == JobKind.IMPORT) {
            setIfPresent(node, "InputDataConfig", job.inputDataConfig);
            setIfPresent(node, "JobOutputDataConfig", job.outputDataConfig);
            if ("COMPLETED".equals(job.status)) {
                ObjectNode progress = node.putObject("JobProgressReport");
                progress.put("TotalNumberOfScannedFiles", 1);
                progress.put("TotalSizeOfScannedFilesInMB", 0.001);
                progress.put("TotalNumberOfImportedFiles", 1);
                progress.put("TotalNumberOfResourcesScanned", 1);
                progress.put("TotalNumberOfResourcesImported", 1);
                progress.put("TotalNumberOfResourcesWithCustomerError", 0);
                progress.put("TotalNumberOfFilesReadWithCustomerError", 0);
                progress.put("Throughput", 1.0);
            }
            if (job.validationLevel != null) {
                node.put("ValidationLevel", job.validationLevel);
            }
        } else {
            setIfPresent(node, "OutputDataConfig", job.outputDataConfig);
        }
        if (job.dataAccessRoleArn != null) {
            node.put("DataAccessRoleArn", job.dataAccessRoleArn);
        }
        if (job.message != null) {
            node.put("Message", job.message);
        }
        return node;
    }

    private JsonNode requireS3Output(JsonNode config) {
        JsonNode s3 = config == null ? null : config.get("S3Configuration");
        if (textOrNull(s3, "S3Uri") == null) {
            throw invalid("S3Configuration.S3Uri is required.");
        }
        if (textOrNull(s3, "KmsKeyId") == null) {
            throw invalid("S3Configuration.KmsKeyId is required.");
        }
        return copy(config);
    }

    private JsonNode copyOrDefaultSse(JsonNode sse) {
        JsonNode copied = copy(sse);
        if (copied != null) {
            return copied;
        }
        ObjectNode config = objectMapper.createObjectNode();
        config.putObject("KmsEncryptionConfig").put("CmkType", "AWS_OWNED_KMS_KEY");
        return config;
    }

    private void setIfPresent(ObjectNode node, String field, JsonNode value) {
        if (value != null && !value.isNull() && !value.isMissingNode()) {
            node.set(field, value);
        }
    }

    private JsonNode copy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.deepCopy();
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode node = request == null ? null : request.get("Tags");
        if (node != null && node.isArray()) {
            for (JsonNode tag : node) {
                String key = textOrNull(tag, "Key");
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(""));
                }
            }
        }
        return tags;
    }

    private static void writeTags(ArrayNode list, Map<String, String> tags) {
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

}
