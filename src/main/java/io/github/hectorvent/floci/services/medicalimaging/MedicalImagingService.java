package io.github.hectorvent.floci.services.medicalimaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.medicalimaging.model.Datastore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;

/**
 * AWS HealthImaging restJson1 — data store lifecycle used by Alchemy
 * {@code Datastore.test.ts}.
 *
 * <p>Create leaves the store {@code ACTIVE} immediately. Tag APIs share
 * {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}
 * using ARN service {@code medical-imaging}.
 *
 * @see <a href="https://docs.aws.amazon.com/healthimaging/latest/APIReference/API_Operations.html">HealthImaging API</a>
 */
@ApplicationScoped
public class MedicalImagingService implements Resettable, TagHandler {

    static final String SERVICE = "medical-imaging";
    private static final Set<String> LOSSLESS_FORMATS = Set.of("HTJ2K", "JPEG_2000_LOSSLESS");
    private static final Set<String> LIVE_STATUSES = Set.of("CREATING", "CREATE_FAILED", "ACTIVE");

    private final StorageBackend<String, Datastore> datastores;
    private final ConcurrentHashMap<String, ImageSet> imageSets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ImportJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> importTokens = new ConcurrentHashMap<>();
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    static final class ImageSet {
        String datastoreId;
        String imageSetId;
        String arn;
        String versionId;
        String state;
        String workflowStatus;
        long createdAt;
        long updatedAt;
        boolean primary;
        final List<String> versions = new ArrayList<>();
        final Map<String, byte[]> frames = new LinkedHashMap<>();
        byte[] metadata = "{}".getBytes(StandardCharsets.UTF_8);
    }

    static final class ImportJob {
        String jobId;
        String jobName;
        String status;
        String datastoreId;
        String dataAccessRoleArn;
        String inputS3Uri;
        String outputS3Uri;
        String message;
        long submittedAt;
        Long endedAt;
        JsonNode importConfiguration;
    }

    @Inject
    public MedicalImagingService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("medicalimaging", "medical-imaging-datastores.json",
                        new TypeReference<Map<String, Datastore>>() {
                        }),
                regionResolver, objectMapper);
    }

    MedicalImagingService(
            StorageBackend<String, Datastore> datastores,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.datastores = datastores;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        datastores.clear();
        imageSets.clear();
        jobs.clear();
        importTokens.clear();
    }

    public synchronized Datastore createDatastore(String region, JsonNode request) {
        requireObject(request, "Request body");
        String token = requireText(request, "clientToken");
        Datastore existing = findByClientToken(token);
        if (existing != null) {
            return existing;
        }
        String format = optionalText(request, "losslessStorageFormat");
        if (format == null) {
            format = "HTJ2K";
        }
        if (!LOSSLESS_FORMATS.contains(format)) {
            throw validation("losslessStorageFormat " + format + " is not supported.");
        }
        String name = optionalText(request, "datastoreName");
        String id = newId();
        if (name == null) {
            name = "datastore-" + id.substring(0, 8);
        }
        long now = nowSeconds();
        Datastore datastore = new Datastore();
        datastore.setDatastoreId(id);
        datastore.setDatastoreName(name);
        datastore.setDatastoreArn(regionResolver.buildArn(SERVICE, region, "datastore/" + id));
        datastore.setDatastoreStatus("ACTIVE");
        datastore.setKmsKeyArn(optionalText(request, "kmsKeyArn"));
        datastore.setLambdaAuthorizerArn(optionalText(request, "lambdaAuthorizerArn"));
        datastore.setLosslessStorageFormat(format);
        datastore.setRegion(region);
        datastore.setClientToken(token);
        datastore.setCreatedAt(now);
        datastore.setUpdatedAt(now);
        datastore.setTags(readTags(request.get("tags")));
        datastores.put(id, datastore);
        return datastore;
    }

    public Datastore getDatastore(String datastoreId) {
        return requireDatastore(datastoreId);
    }

    public List<Datastore> listDatastores(String datastoreStatus) {
        List<Datastore> result = new ArrayList<>();
        for (Datastore datastore : datastores.scan(k -> true)) {
            if (!LIVE_STATUSES.contains(datastore.getDatastoreStatus())) {
                continue;
            }
            if (datastoreStatus != null && !datastoreStatus.equals(datastore.getDatastoreStatus())) {
                continue;
            }
            result.add(datastore);
        }
        result.sort(Comparator.comparingLong(Datastore::getCreatedAt).reversed());
        return result;
    }

    public synchronized Datastore deleteDatastore(String datastoreId) {
        Datastore datastore = requireDatastore(datastoreId);
        if (imageSets.values().stream().anyMatch(set -> datastoreId.equals(set.datastoreId))) {
            throw conflict("Data store " + datastoreId + " still contains image sets.");
        }
        datastores.delete(datastore.getDatastoreId());
        jobs.values().removeIf(job -> datastoreId.equals(job.datastoreId));
        datastore.setDatastoreStatus("DELETING");
        datastore.setUpdatedAt(nowSeconds());
        return datastore;
    }

    public ObjectNode toCreateResponse(Datastore datastore) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("datastoreId", datastore.getDatastoreId());
        node.put("datastoreStatus", datastore.getDatastoreStatus());
        return node;
    }

    public ObjectNode toDeleteResponse(Datastore datastore) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("datastoreId", datastore.getDatastoreId());
        node.put("datastoreStatus", datastore.getDatastoreStatus());
        return node;
    }

    public ObjectNode toProperties(Datastore datastore) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("datastoreId", datastore.getDatastoreId());
        node.put("datastoreName", datastore.getDatastoreName());
        node.put("datastoreStatus", datastore.getDatastoreStatus());
        node.put("datastoreArn", datastore.getDatastoreArn());
        node.put("createdAt", datastore.getCreatedAt());
        node.put("updatedAt", datastore.getUpdatedAt());
        if (datastore.getKmsKeyArn() != null) {
            node.put("kmsKeyArn", datastore.getKmsKeyArn());
        }
        if (datastore.getLambdaAuthorizerArn() != null) {
            node.put("lambdaAuthorizerArn", datastore.getLambdaAuthorizerArn());
        }
        if (datastore.getLosslessStorageFormat() != null) {
            node.put("losslessStorageFormat", datastore.getLosslessStorageFormat());
        }
        return node;
    }

    public ObjectNode toSummary(Datastore datastore) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("datastoreId", datastore.getDatastoreId());
        node.put("datastoreName", datastore.getDatastoreName());
        node.put("datastoreStatus", datastore.getDatastoreStatus());
        node.put("datastoreArn", datastore.getDatastoreArn());
        node.put("createdAt", datastore.getCreatedAt());
        node.put("updatedAt", datastore.getUpdatedAt());
        return node;
    }

    public ObjectNode startDicomImportJob(String datastoreId, JsonNode request) {
        Datastore datastore = requireDatastore(datastoreId);
        String token = requireText(request, "clientToken");
        String existingId = importTokens.get(token);
        if (existingId != null) {
            ImportJob existing = jobs.get(existingId);
            if (existing != null) {
                return startImportResponse(existing);
            }
        }
        ImportJob job = new ImportJob();
        job.jobId = newId();
        String jobName = optionalText(request, "jobName");
        job.jobName = jobName == null ? "DICOMImportJob" : jobName;
        job.status = "SUBMITTED";
        job.datastoreId = datastore.getDatastoreId();
        job.dataAccessRoleArn = requireText(request, "dataAccessRoleArn");
        job.inputS3Uri = requireText(request, "inputS3Uri");
        job.outputS3Uri = requireText(request, "outputS3Uri");
        job.submittedAt = nowSeconds();
        job.importConfiguration = copy(request.get("importConfiguration"));
        jobs.put(job.jobId, job);
        importTokens.put(token, job.jobId);
        return startImportResponse(job);
    }

    public ObjectNode getDicomImportJob(String datastoreId, String jobId) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("jobProperties", jobProperties(requireJob(datastoreId, jobId)));
        return response;
    }

    public ObjectNode listDicomImportJobs(String datastoreId, String jobStatus) {
        requireDatastore(datastoreId);
        ArrayNode summaries = objectMapper.createArrayNode();
        jobs.values().stream()
                .filter(job -> datastoreId.equals(job.datastoreId))
                .peek(this::completeIfNeeded)
                .filter(job -> jobStatus == null || jobStatus.equals(job.status))
                .sorted(Comparator.comparingLong((ImportJob job) -> job.submittedAt).reversed())
                .forEach(job -> summaries.add(jobSummary(job)));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("jobSummaries", summaries);
        return response;
    }

    public ObjectNode searchImageSets(String datastoreId) {
        requireDatastore(datastoreId);
        ArrayNode summaries = objectMapper.createArrayNode();
        imageSets.values().stream()
                .filter(set -> datastoreId.equals(set.datastoreId))
                .sorted(Comparator.comparing(set -> set.imageSetId))
                .forEach(set -> summaries.add(imageSetSearchSummary(set)));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("imageSetsMetadataSummaries", summaries);
        return response;
    }

    public ObjectNode getImageSet(String datastoreId, String imageSetId, String versionId) {
        ImageSet imageSet = requireImageSet(datastoreId, imageSetId);
        if (versionId != null && !imageSet.versions.contains(versionId) && !versionId.equals(imageSet.versionId)) {
            throw notFound("Image set version " + versionId + " was not found.");
        }
        return imageSetNode(imageSet, versionId == null ? imageSet.versionId : versionId);
    }

    public byte[] getImageSetMetadata(String datastoreId, String imageSetId, String versionId) {
        ImageSet imageSet = requireImageSet(datastoreId, imageSetId);
        if (versionId != null && !imageSet.versions.contains(versionId) && !versionId.equals(imageSet.versionId)) {
            throw notFound("Image set version " + versionId + " was not found.");
        }
        return imageSet.metadata;
    }

    public byte[] getImageFrame(String datastoreId, String imageSetId, JsonNode request) {
        ImageSet imageSet = requireImageSet(datastoreId, imageSetId);
        String frameId = requireText(request, "imageFrameId");
        byte[] frame = imageSet.frames.get(frameId);
        if (frame == null) {
            throw notFound("Image frame " + frameId + " was not found.");
        }
        return frame;
    }

    public ObjectNode listImageSetVersions(String datastoreId, String imageSetId) {
        ImageSet imageSet = requireImageSet(datastoreId, imageSetId);
        ArrayNode list = objectMapper.createArrayNode();
        for (String version : imageSet.versions) {
            list.add(imageSetProperties(imageSet, version));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("imageSetPropertiesList", list);
        return response;
    }

    public ObjectNode updateImageSetMetadata(String datastoreId, String imageSetId, String latestVersion,
            JsonNode request) {
        ImageSet imageSet = requireImageSet(datastoreId, imageSetId);
        if (latestVersion == null || latestVersion.isBlank()) {
            throw validation("latestVersion is required.");
        }
        if (!latestVersion.equals(imageSet.versionId)) {
            throw conflict("latestVersion does not match the current image set version.");
        }
        String revertTo = optionalText(request, "revertToVersionId");
        if (revertTo != null) {
            if (!imageSet.versions.contains(revertTo)) {
                throw notFound("Image set version " + revertTo + " was not found.");
            }
            imageSet.versionId = revertTo;
        } else {
            String nextVersion = Integer.toString(imageSet.versions.size() + 1);
            imageSet.versions.add(nextVersion);
            imageSet.versionId = nextVersion;
        }
        imageSet.workflowStatus = "UPDATED";
        imageSet.updatedAt = nowSeconds();
        ObjectNode response = imageSetNode(imageSet, imageSet.versionId);
        response.put("latestVersionId", imageSet.versionId);
        return response;
    }

    public ObjectNode copyImageSet(String datastoreId, String sourceImageSetId, JsonNode request) {
        ImageSet source = requireImageSet(datastoreId, sourceImageSetId);
        JsonNode info = request.get("sourceImageSet");
        String latestVersion = info == null ? null : optionalText(info, "latestVersionId");
        if (latestVersion != null && !latestVersion.equals(source.versionId)
                && !source.versions.contains(latestVersion)) {
            throw notFound("Image set version " + latestVersion + " was not found.");
        }
        Datastore datastore = requireDatastore(datastoreId);
        ImageSet destination = new ImageSet();
        destination.datastoreId = source.datastoreId;
        destination.imageSetId = newId();
        destination.arn = datastore.getDatastoreArn() + "/imageset/" + destination.imageSetId;
        destination.versionId = "1";
        destination.versions.add("1");
        destination.state = "ACTIVE";
        destination.workflowStatus = "COPIED";
        destination.createdAt = nowSeconds();
        destination.updatedAt = destination.createdAt;
        destination.primary = false;
        destination.metadata = source.metadata;
        destination.frames.putAll(source.frames);
        imageSets.put(imageSetKey(destination.datastoreId, destination.imageSetId), destination);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("datastoreId", datastoreId);
        response.set("sourceImageSetProperties", copyProperties(source));
        response.set("destinationImageSetProperties", copyProperties(destination));
        return response;
    }

    public ObjectNode deleteImageSet(String datastoreId, String imageSetId) {
        requireImageSet(datastoreId, imageSetId);
        imageSets.remove(imageSetKey(datastoreId, imageSetId));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("datastoreId", datastoreId);
        response.put("imageSetId", imageSetId);
        response.put("imageSetState", "DELETED");
        response.put("imageSetWorkflowStatus", "DELETED");
        return response;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireDatastoreByArn(arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Datastore datastore = requireDatastoreByArn(arn);
        Map<String, String> current = new LinkedHashMap<>(datastore.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        datastore.setTags(current);
        datastore.setUpdatedAt(nowSeconds());
        datastores.put(datastore.getDatastoreId(), datastore);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Datastore datastore = requireDatastoreByArn(arn);
        Map<String, String> current = new LinkedHashMap<>(datastore.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        datastore.setTags(current);
        datastore.setUpdatedAt(nowSeconds());
        datastores.put(datastore.getDatastoreId(), datastore);
    }

    private ObjectNode startImportResponse(ImportJob job) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("datastoreId", job.datastoreId);
        response.put("jobId", job.jobId);
        response.put("jobStatus", job.status);
        response.put("submittedAt", job.submittedAt);
        return response;
    }

    private ObjectNode jobProperties(ImportJob job) {
        completeIfNeeded(job);
        ObjectNode node = jobSummary(job);
        node.put("dataAccessRoleArn", job.dataAccessRoleArn);
        node.put("inputS3Uri", job.inputS3Uri);
        node.put("outputS3Uri", job.outputS3Uri);
        if (job.importConfiguration != null) {
            node.set("importConfiguration", job.importConfiguration);
        }
        return node;
    }

    private ObjectNode jobSummary(ImportJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jobId", job.jobId);
        node.put("jobName", job.jobName);
        node.put("jobStatus", job.status);
        node.put("datastoreId", job.datastoreId);
        if (job.dataAccessRoleArn != null) {
            node.put("dataAccessRoleArn", job.dataAccessRoleArn);
        }
        node.put("submittedAt", job.submittedAt);
        if (job.endedAt != null) {
            node.put("endedAt", job.endedAt);
        }
        if (job.message != null) {
            node.put("message", job.message);
        }
        return node;
    }

    private ObjectNode imageSetSearchSummary(ImageSet imageSet) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("imageSetId", imageSet.imageSetId);
        node.put("version", Integer.parseInt(imageSet.versionId));
        node.put("createdAt", imageSet.createdAt);
        node.put("updatedAt", imageSet.updatedAt);
        node.put("isPrimary", imageSet.primary);
        return node;
    }

    private ObjectNode imageSetNode(ImageSet imageSet, String versionId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("datastoreId", imageSet.datastoreId);
        node.put("imageSetId", imageSet.imageSetId);
        node.put("versionId", versionId);
        node.put("imageSetState", imageSet.state);
        node.put("imageSetWorkflowStatus", imageSet.workflowStatus);
        node.put("createdAt", imageSet.createdAt);
        node.put("updatedAt", imageSet.updatedAt);
        node.put("imageSetArn", imageSet.arn);
        node.put("isPrimary", imageSet.primary);
        return node;
    }

    private ObjectNode imageSetProperties(ImageSet imageSet, String versionId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("imageSetId", imageSet.imageSetId);
        node.put("versionId", versionId);
        node.put("imageSetState", imageSet.state);
        node.put("ImageSetWorkflowStatus", imageSet.workflowStatus);
        node.put("createdAt", imageSet.createdAt);
        node.put("updatedAt", imageSet.updatedAt);
        node.put("isPrimary", imageSet.primary);
        return node;
    }

    private ObjectNode copyProperties(ImageSet imageSet) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("imageSetId", imageSet.imageSetId);
        node.put("latestVersionId", imageSet.versionId);
        node.put("imageSetState", imageSet.state);
        node.put("imageSetWorkflowStatus", imageSet.workflowStatus);
        node.put("createdAt", imageSet.createdAt);
        node.put("updatedAt", imageSet.updatedAt);
        node.put("imageSetArn", imageSet.arn);
        return node;
    }

    private void completeIfNeeded(ImportJob job) {
        if ("SUBMITTED".equals(job.status) || "IN_PROGRESS".equals(job.status)) {
            job.status = "FAILED";
            job.endedAt = nowSeconds();
            job.message = "Import input is not valid DICOM.";
        }
    }

    private ImportJob requireJob(String datastoreId, String jobId) {
        requireDatastore(datastoreId);
        ImportJob job = jobs.get(jobId);
        if (job == null || !datastoreId.equals(job.datastoreId)) {
            throw notFound("The requested import job was not found.");
        }
        return job;
    }

    private ImageSet requireImageSet(String datastoreId, String imageSetId) {
        requireDatastore(datastoreId);
        ImageSet imageSet = imageSets.get(imageSetKey(datastoreId, imageSetId));
        if (imageSet == null) {
            throw notFound("The requested image set was not found.");
        }
        return imageSet;
    }

    private static String imageSetKey(String datastoreId, String imageSetId) {
        return datastoreId + "/" + imageSetId;
    }

    private JsonNode copy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.deepCopy();
    }

    private Datastore findByClientToken(String token) {
        for (Datastore datastore : datastores.scan(k -> true)) {
            if (token.equals(datastore.getClientToken())) {
                return datastore;
            }
        }
        return null;
    }

    private Datastore requireDatastore(String datastoreId) {
        if (datastoreId == null || datastoreId.isBlank()) {
            throw validation("datastoreId is required.");
        }
        Datastore datastore = datastores.get(datastoreId).orElse(null);
        if (datastore == null) {
            throw notFound("The requested data store was not found.");
        }
        return datastore;
    }

    private Datastore requireDatastoreByArn(String arn) {
        String decoded = arn;
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw notFound("The requested data store was not found.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw notFound("The requested data store was not found.");
        }
        String resource = parsed.resource();
        String id = resource.startsWith("datastore/") ? resource.substring("datastore/".length()) : resource;
        return requireDatastore(id);
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return tags;
        }
        node.fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText("")));
        return tags;
    }

    private static void requireObject(JsonNode request, String field) {
        if (request == null || !request.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
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

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }
}
