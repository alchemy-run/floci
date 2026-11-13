package io.floci.aws.services.medicalimaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * AWS HealthImaging. Data stores are {@code CREATING}, then {@code ACTIVE}; deleting an empty one makes it
 * {@code DELETING}, then {@code DELETED}, then gone. DICOM import jobs run in {@link MedicalImagingImports}. Image sets
 * keep every version of their metadata; updates, copies and deletes lock an image set while they run, then record a
 * new version (or mark it deleted). Image frames and metadata are read by image set and version. Every state change
 * goes to the default event bus from {@code aws.medical-imaging}.
 */
@ApplicationScoped
public class MedicalImagingService {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._/#-]{1,256}");
    private static final Pattern ID = Pattern.compile("[0-9a-z]{32}");
    private static final long ARCHIVE_AFTER_SECONDS = 30L * 24 * 3600;

    private final MedicalImagingStore store;
    private final ObjectMapper mapper;
    private final MedicalImagingImports imports;
    private final Vertx vertx;

    @Inject
    public MedicalImagingService(MedicalImagingStore store, MedicalImagingImports imports, Vertx vertx) {
        this.store = store;
        this.mapper = store.mapper();
        this.imports = imports;
        this.vertx = vertx;
    }

    private void later(Runnable task) {
        vertx.setTimer(Math.max(1, store.config().transitionMillis()), id -> vertx.executeBlocking(() -> {
            task.run();
            return null;
        }, false));
    }

    private static String id() {
        return MedicalImagingStore.random(32, "0123456789abcdef");
    }

    private static String required(JsonNode in, String member) {
        String value = in.path(member).asText("");
        if (value.isBlank()) {
            throw MedicalImagingStore.validation(member + " is required.");
        }
        return value;
    }

    private static ObjectNode view(ObjectNode record) {
        return MedicalImagingStore.view(record);
    }

    // ================================================================ data stores

    ObjectNode datastore(String region, String id) {
        if (!ID.matcher(id).matches()) {
            throw MedicalImagingStore.validation("The datastoreId " + id + " isn't a valid data store ID.");
        }
        ObjectNode datastore = store.get(region, MedicalImagingStore.DATASTORE, id).orElse(null);
        if (datastore == null) {
            throw MedicalImagingStore.notFound("The data store " + id + " doesn't exist.");
        }
        return datastore;
    }

    private ObjectNode active(String region, String id) {
        ObjectNode datastore = datastore(region, id);
        if (!"ACTIVE".equals(datastore.path("datastoreStatus").asText())) {
            throw MedicalImagingStore.conflict("The data store " + id + " is "
                    + datastore.path("datastoreStatus").asText() + ".");
        }
        return datastore;
    }

    private void datastoreEvent(String account, String region, ObjectNode datastore, String detailType) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("datastoreId", datastore.path("datastoreId").asText());
        detail.put("datastoreName", datastore.path("datastoreName").asText());
        detail.put("datastoreStatus", datastore.path("datastoreStatus").asText());
        imports.publish(account, region, detailType, detail, datastore.path("datastoreArn").asText());
    }

    public ObjectNode createDatastore(JsonNode in, String region) {
        ObjectNode repeated = store.repeated(region, "CreateDatastore", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.path("datastoreName").asText(null);
        if (name != null && !NAME.matcher(name).matches()) {
            throw MedicalImagingStore.validation("datastoreName must be 1-256 letters, digits and ._/#- characters.");
        }
        String format = in.path("losslessStorageFormat").asText("HTJ2K");
        if (!Set.of("HTJ2K", "JPEG_2000_LOSSLESS").contains(format)) {
            throw MedicalImagingStore.validation("losslessStorageFormat must be HTJ2K or JPEG_2000_LOSSLESS.");
        }
        String id = id();
        double now = MedicalImagingStore.epoch();
        ObjectNode datastore = store.record();
        datastore.put("datastoreId", id);
        datastore.put("datastoreName", name == null ? id : name);
        datastore.put("datastoreStatus", "CREATING");
        MedicalImagingStore.copy(in, datastore, "kmsKeyArn", "lambdaAuthorizerArn");
        datastore.put("losslessStorageFormat", format);
        datastore.put("datastoreArn", store.arn(region, "datastore/" + id));
        datastore.put("createdAt", now);
        datastore.put("updatedAt", now);
        MedicalImagingStore.addTags(datastore, in.path("tags"));
        store.put(region, MedicalImagingStore.DATASTORE, id, datastore);
        String account = store.account();
        datastoreEvent(account, region, datastore, "Data Store Creating");
        later(() -> store.getForAccount(account, region, MedicalImagingStore.DATASTORE, id).ifPresent(current -> {
            current.put("datastoreStatus", "ACTIVE");
            current.put("updatedAt", MedicalImagingStore.epoch());
            store.putForAccount(account, region, MedicalImagingStore.DATASTORE, id, current);
            datastoreEvent(account, region, current, "Data Store Created");
        }));
        ObjectNode out = mapper.createObjectNode();
        out.put("datastoreId", id);
        out.put("datastoreStatus", "CREATING");
        store.remember(region, "CreateDatastore", in, out);
        return out;
    }

    public ObjectNode getDatastore(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("datastoreProperties", view(datastore(region, required(in, "datastoreId"))));
        return out;
    }

    public ObjectNode listDatastores(JsonNode in, String region) {
        String status = in.path("datastoreStatus").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode datastore : store.list(region, MedicalImagingStore.DATASTORE)) {
            if (status != null && !status.equals(datastore.path("datastoreStatus").asText())) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            MedicalImagingStore.copy(datastore, summary, "datastoreId", "datastoreName", "datastoreStatus",
                    "datastoreArn", "createdAt", "updatedAt");
            items.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        MedicalImagingStore.page(items, in, out, "datastoreSummaries", 50);
        return out;
    }

    public ObjectNode deleteDatastore(JsonNode in, String region) {
        String id = required(in, "datastoreId");
        ObjectNode datastore = datastore(region, id);
        String status = datastore.path("datastoreStatus").asText();
        if (!"ACTIVE".equals(status) && !"CREATE_FAILED".equals(status)) {
            throw MedicalImagingStore.conflict("The data store " + id + " is " + status + ".");
        }
        for (ObjectNode imageSet : imageSets(region, id)) {
            if (!"DELETED".equals(imageSet.path("imageSetState").asText())) {
                throw MedicalImagingStore.conflict("The data store " + id + " still has image sets; delete them "
                        + "first.");
            }
        }
        for (ObjectNode job : store.list(region, MedicalImagingStore.JOB)) {
            if (id.equals(job.path("datastoreId").asText()) && Set.of("SUBMITTED", "IN_PROGRESS").contains(
                    job.path("jobStatus").asText())) {
                throw MedicalImagingStore.conflict("The data store " + id + " has an import job running.");
            }
        }
        datastore.put("datastoreStatus", "DELETING");
        datastore.put("updatedAt", MedicalImagingStore.epoch());
        store.put(region, MedicalImagingStore.DATASTORE, id, datastore);
        String account = store.account();
        datastoreEvent(account, region, datastore, "Data Store Deleting");
        later(() -> store.getForAccount(account, region, MedicalImagingStore.DATASTORE, id).ifPresent(current -> {
            current.put("datastoreStatus", "DELETED");
            current.put("updatedAt", MedicalImagingStore.epoch());
            store.putForAccount(account, region, MedicalImagingStore.DATASTORE, id, current);
            datastoreEvent(account, region, current, "Data Store Deleted");
            later(() -> purge(account, region, id));
        }));
        ObjectNode out = mapper.createObjectNode();
        out.put("datastoreId", id);
        out.put("datastoreStatus", "DELETING");
        return out;
    }

    /** Removes a deleted data store and everything it held. */
    private void purge(String account, String region, String id) {
        for (ObjectNode job : store.listForAccount(account, region, MedicalImagingStore.JOB)) {
            if (id.equals(job.path("datastoreId").asText())) {
                store.deleteForAccount(account, region, MedicalImagingStore.JOB, id + "/" + job.path("jobId").asText());
            }
        }
        for (ObjectNode imageSet : store.listForAccount(account, region, MedicalImagingStore.IMAGE_SET)) {
            if (id.equals(MedicalImagingStore.meta(imageSet).path("datastoreId").asText())) {
                store.deleteForAccount(account, region, MedicalImagingStore.IMAGE_SET, id + "/"
                        + imageSet.path("imageSetId").asText());
            }
        }
        store.deleteForAccount(account, region, MedicalImagingStore.DATASTORE, id);
    }

    // ================================================================ import jobs

    public ObjectNode startDICOMImportJob(JsonNode in, String region) {
        String datastoreId = required(in, "datastoreId");
        ObjectNode datastore = datastore(region, datastoreId);
        ObjectNode repeated = store.repeated(region, "StartDICOMImportJob", in);
        if (repeated != null) {
            return repeated;
        }
        if (!"ACTIVE".equals(datastore.path("datastoreStatus").asText())) {
            throw MedicalImagingStore.conflict("The data store " + datastoreId + " isn't active.");
        }
        String role = required(in, "dataAccessRoleArn");
        if (!AwsArnUtils.isArnFor(role, "iam") || !AwsArnUtils.parse(role).resource().startsWith("role/")) {
            throw MedicalImagingStore.validation("dataAccessRoleArn must be an IAM role ARN.");
        }
        for (String member : List.of("inputS3Uri", "outputS3Uri")) {
            String[] uri = MedicalImagingImports.s3Uri(required(in, member));
            if (uri == null || uri[0].isEmpty()) {
                throw MedicalImagingStore.validation(member + " must be an s3://bucket/prefix URI.");
            }
        }
        String owner = in.path("inputOwnerAccountId").asText(null);
        if (owner != null && !owner.equals(store.account())) {
            throw MedicalImagingStore.denied("The input bucket isn't owned by " + owner + ".");
        }
        String jobId = id();
        double now = MedicalImagingStore.epoch();
        ObjectNode job = store.record();
        job.put("jobId", jobId);
        job.put("jobName", in.path("jobName").asText(jobId));
        job.put("jobStatus", "SUBMITTED");
        job.put("datastoreId", datastoreId);
        job.put("dataAccessRoleArn", role);
        job.put("submittedAt", now);
        job.put("inputS3Uri", in.path("inputS3Uri").asText());
        job.put("outputS3Uri", in.path("outputS3Uri").asText());
        MedicalImagingStore.copy(in, job, "importConfiguration");
        MedicalImagingStore.meta(job).put("datastoreArn", datastore.path("datastoreArn").asText());
        store.put(region, MedicalImagingStore.JOB, datastoreId + "/" + jobId, job);
        imports.start(store.account(), region, datastoreId, jobId);
        ObjectNode out = mapper.createObjectNode();
        out.put("datastoreId", datastoreId);
        out.put("jobId", jobId);
        out.put("jobStatus", "SUBMITTED");
        out.put("submittedAt", now);
        store.remember(region, "StartDICOMImportJob", in, out);
        return out;
    }

    public ObjectNode getDICOMImportJob(JsonNode in, String region) {
        String datastoreId = required(in, "datastoreId");
        datastore(region, datastoreId);
        String jobId = required(in, "jobId");
        ObjectNode job = store.get(region, MedicalImagingStore.JOB, datastoreId + "/" + jobId).orElseThrow(() ->
                MedicalImagingStore.notFound("The import job " + jobId + " doesn't exist."));
        ObjectNode out = mapper.createObjectNode();
        out.set("jobProperties", view(job));
        return out;
    }

    public ObjectNode listDICOMImportJobs(JsonNode in, String region) {
        String datastoreId = required(in, "datastoreId");
        datastore(region, datastoreId);
        String status = in.path("jobStatus").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode job : store.list(region, MedicalImagingStore.JOB)) {
            if (!datastoreId.equals(job.path("datastoreId").asText())
                    || status != null && !status.equals(job.path("jobStatus").asText())) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            MedicalImagingStore.copy(job, summary, "jobId", "jobName", "jobStatus", "datastoreId",
                    "dataAccessRoleArn", "endedAt", "submittedAt", "message");
            items.add(summary);
        }
        items.sort(Comparator.comparingDouble((ObjectNode job) -> job.path("submittedAt").asDouble()).reversed());
        ObjectNode out = mapper.createObjectNode();
        MedicalImagingStore.page(items, in, out, "jobSummaries", 50);
        return out;
    }

    // ================================================================ image sets

    private List<ObjectNode> imageSets(String region, String datastoreId) {
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode imageSet : store.list(region, MedicalImagingStore.IMAGE_SET)) {
            if (datastoreId.equals(MedicalImagingStore.meta(imageSet).path("datastoreId").asText())) {
                found.add(imageSet);
            }
        }
        return found;
    }

    ObjectNode imageSet(String region, String datastoreId, String imageSetId) {
        datastore(region, datastoreId);
        return store.get(region, MedicalImagingStore.IMAGE_SET, datastoreId + "/" + imageSetId).orElseThrow(() ->
                MedicalImagingStore.notFound("The image set " + imageSetId + " doesn't exist."));
    }

    private ObjectNode live(String region, String datastoreId, String imageSetId) {
        ObjectNode imageSet = imageSet(region, datastoreId, imageSetId);
        if ("DELETED".equals(imageSet.path("imageSetState").asText())) {
            throw MedicalImagingStore.notFound("The image set " + imageSetId + " was deleted.");
        }
        return imageSet;
    }

    private void save(String region, ObjectNode imageSet) {
        store.put(region, MedicalImagingStore.IMAGE_SET, MedicalImagingStore.meta(imageSet).path("datastoreId").asText()
                + "/" + imageSet.path("imageSetId").asText(), imageSet);
    }

    private static String tier(ObjectNode imageSet) {
        double accessed = imageSet.path("lastAccessedAt").asDouble(imageSet.path("createdAt").asDouble());
        return MedicalImagingStore.epoch() - accessed > ARCHIVE_AFTER_SECONDS ? "ARCHIVE_INSTANT_ACCESS"
                : "FREQUENT_ACCESS";
    }

    private void accessed(String region, ObjectNode imageSet) {
        imageSet.put("lastAccessedAt", MedicalImagingStore.epoch());
        imageSet.put("storageTier", "FREQUENT_ACCESS");
        save(region, imageSet);
    }

    private ObjectNode versionOf(ObjectNode imageSet, String version) {
        for (JsonNode candidate : MedicalImagingStore.meta(imageSet).path("versions")) {
            if (version.equals(candidate.path("versionId").asText())) {
                return (ObjectNode) candidate;
            }
        }
        throw MedicalImagingStore.notFound("The image set " + imageSet.path("imageSetId").asText()
                + " has no version " + version + ".");
    }

    public ObjectNode getImageSet(JsonNode in, String region) {
        String datastoreId = required(in, "datastoreId");
        ObjectNode imageSet = imageSet(region, datastoreId, required(in, "imageSetId"));
        ObjectNode out = view(imageSet);
        out.put("datastoreId", datastoreId);
        out.put("storageTier", tier(imageSet));
        String version = in.path("versionId").asText(null);
        if (version != null && !version.equals(imageSet.path("versionId").asText())) {
            ObjectNode properties = versionOf(imageSet, version);
            out.put("versionId", version);
            MedicalImagingStore.copy(properties, out, "imageSetState", "createdAt", "updatedAt", "deletedAt",
                    "message", "overrides", "isPrimary");
            out.put("imageSetWorkflowStatus", properties.path("ImageSetWorkflowStatus").asText());
        }
        return out;
    }

    public ObjectNode getImageSetMetadata(JsonNode in, String region) {
        String datastoreId = required(in, "datastoreId");
        ObjectNode imageSet = live(region, datastoreId, required(in, "imageSetId"));
        String version = in.path("versionId").asText(imageSet.path("versionId").asText());
        versionOf(imageSet, version);
        ObjectNode doc = imports.metadata(store.account(), region, imageSet, version);
        if (doc == null) {
            throw MedicalImagingStore.notFound("The image set has no metadata for version " + version + ".");
        }
        accessed(region, imageSet);
        ObjectNode out = mapper.createObjectNode();
        out.put("imageSetMetadataBlob", Base64.getEncoder().encodeToString(gzip(doc.toString())));
        out.put("contentType", "application/json");
        out.put("contentEncoding", "gzip");
        return out;
    }

    private static byte[] gzip(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static Set<String> frameIds(JsonNode doc) {
        Set<String> ids = new HashSet<>();
        doc.path("Study").path("Series").forEach(series -> series.path("Instances").forEach(instance ->
                instance.path("ImageFrames").forEach(frame -> ids.add(frame.path("ID").asText()))));
        return ids;
    }

    public ObjectNode getImageFrame(JsonNode in, String region) {
        String datastoreId = required(in, "datastoreId");
        ObjectNode imageSet = live(region, datastoreId, required(in, "imageSetId"));
        String frameId = required(in.path("imageFrameInformation"), "imageFrameId");
        ObjectNode doc = imports.metadata(store.account(), region, imageSet, imageSet.path("versionId").asText());
        if (doc == null || !frameIds(doc).contains(frameId)) {
            throw MedicalImagingStore.notFound("The image set has no image frame " + frameId + ".");
        }
        byte[] frame = store.frame(store.account(), region, datastoreId, frameId).orElseThrow(() ->
                MedicalImagingStore.notFound("The image frame " + frameId + " isn't stored."));
        accessed(region, imageSet);
        ObjectNode out = mapper.createObjectNode();
        out.put("imageFrameBlob", Base64.getEncoder().encodeToString(frame));
        out.put("contentType", "application/octet-stream");
        return out;
    }

    public ObjectNode listImageSetVersions(JsonNode in, String region) {
        ObjectNode imageSet = imageSet(region, required(in, "datastoreId"), required(in, "imageSetId"));
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode version : MedicalImagingStore.meta(imageSet).path("versions")) {
            items.add(0, (ObjectNode) version.deepCopy());
        }
        ObjectNode out = mapper.createObjectNode();
        MedicalImagingStore.page(items, in, out, "imageSetPropertiesList", 50);
        return out;
    }

    // ================================================================ search

    private ObjectNode tags(String region, ObjectNode imageSet) {
        ObjectNode doc = imports.metadata(store.account(), region, imageSet, imageSet.path("versionId").asText());
        ObjectNode tags = mapper.createObjectNode();
        if (doc == null) {
            return tags;
        }
        JsonNode patient = doc.path("Patient").path("DICOM");
        JsonNode study = doc.path("Study").path("DICOM");
        putText(tags, "DICOMPatientId", patient.path("PatientID"));
        putText(tags, "DICOMPatientName", patient.path("PatientName"));
        putText(tags, "DICOMPatientBirthDate", patient.path("PatientBirthDate"));
        putText(tags, "DICOMPatientSex", patient.path("PatientSex"));
        putText(tags, "DICOMStudyInstanceUID", study.path("StudyInstanceUID"));
        putText(tags, "DICOMStudyId", study.path("StudyID"));
        putText(tags, "DICOMStudyDescription", study.path("StudyDescription"));
        putText(tags, "DICOMAccessionNumber", study.path("AccessionNumber"));
        putText(tags, "DICOMStudyDate", study.path("StudyDate"));
        putText(tags, "DICOMStudyTime", study.path("StudyTime"));
        int series = 0;
        int instances = 0;
        JsonNode first = null;
        for (JsonNode node : doc.path("Study").path("Series")) {
            series++;
            instances += node.path("Instances").size();
            if (first == null) {
                first = node.path("DICOM");
            }
        }
        tags.put("DICOMNumberOfStudyRelatedSeries", series);
        tags.put("DICOMNumberOfStudyRelatedInstances", instances);
        if (first != null) {
            putText(tags, "DICOMSeriesInstanceUID", first.path("SeriesInstanceUID"));
            putText(tags, "DICOMSeriesModality", first.path("Modality"));
            putText(tags, "DICOMSeriesBodyPart", first.path("BodyPartExamined"));
            String number = first.path("SeriesNumber").asText("");
            try {
                if (!number.isBlank()) {
                    tags.put("DICOMSeriesNumber", (int) Double.parseDouble(number.trim()));
                }
            } catch (NumberFormatException e) {
                // a SeriesNumber that isn't a number isn't searchable
            }
        }
        return tags;
    }

    private static void putText(ObjectNode out, String name, JsonNode value) {
        if (value != null && !value.isMissingNode() && !value.isNull() && !value.asText().isEmpty()) {
            out.put(name, value.asText());
        }
    }

    private static boolean matches(JsonNode filter, ObjectNode imageSet, ObjectNode tags) {
        String operator = filter.path("operator").asText();
        JsonNode values = filter.path("values");
        if ("EQUAL".equals(operator) && values.size() != 1 || "BETWEEN".equals(operator) && values.size() != 2) {
            throw MedicalImagingStore.validation("An " + operator + " filter takes " + ("EQUAL".equals(operator)
                    ? "one value." : "two values."));
        }
        JsonNode first = values.path(0);
        if (first.size() != 1) {
            throw MedicalImagingStore.validation("Each search value names one attribute.");
        }
        String attribute = first.fieldNames().next();
        for (JsonNode value : values) {
            if (!value.has(attribute)) {
                throw MedicalImagingStore.validation("A BETWEEN filter's values name the same attribute.");
            }
        }
        if ("EQUAL".equals(operator)) {
            JsonNode expected = first.get(attribute);
            return switch (attribute) {
                case "createdAt", "updatedAt" -> Math.abs(imageSet.path(attribute).asDouble() - expected.asDouble())
                        < 0.001;
                case "isPrimary" -> imageSet.path("isPrimary").asBoolean() == expected.asBoolean();
                case "DICOMStudyDateAndTime" -> studyDateTime(tags).startsWith(dateTime(expected));
                default -> expected.asText().equals(tags.path(attribute).asText(null));
            };
        }
        JsonNode low = values.get(0).get(attribute);
        JsonNode high = values.get(1).get(attribute);
        return switch (attribute) {
            case "createdAt", "updatedAt" -> imageSet.path(attribute).asDouble() >= low.asDouble()
                    && imageSet.path(attribute).asDouble() <= high.asDouble();
            case "DICOMStudyDateAndTime" -> studyDateTime(tags).compareTo(dateTime(low)) >= 0
                    && studyDateTime(tags).compareTo(dateTime(high) + "￿") <= 0;
            default -> throw MedicalImagingStore.validation("BETWEEN filters take createdAt, updatedAt or "
                    + "DICOMStudyDateAndTime.");
        };
    }

    private static String studyDateTime(JsonNode tags) {
        return tags.path("DICOMStudyDate").asText("") + tags.path("DICOMStudyTime").asText("");
    }

    private static String dateTime(JsonNode value) {
        return value.path("DICOMStudyDate").asText("") + value.path("DICOMStudyTime").asText("");
    }

    public ObjectNode searchImageSets(JsonNode in, String region) {
        String datastoreId = required(in, "datastoreId");
        datastore(region, datastoreId);
        JsonNode criteria = in.path("searchCriteria");
        if (criteria.path("filters").size() > 2) {
            throw MedicalImagingStore.validation("searchCriteria takes at most two filters.");
        }
        List<ObjectNode> results = new ArrayList<>();
        for (ObjectNode imageSet : imageSets(region, datastoreId)) {
            if ("DELETED".equals(imageSet.path("imageSetState").asText())) {
                continue;
            }
            ObjectNode tags = tags(region, imageSet);
            boolean keep = true;
            for (JsonNode filter : criteria.path("filters")) {
                keep &= matches(filter, imageSet, tags);
            }
            if (!keep) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            summary.put("imageSetId", imageSet.path("imageSetId").asText());
            summary.put("version", Integer.parseInt(imageSet.path("versionId").asText()));
            MedicalImagingStore.copy(imageSet, summary, "createdAt", "updatedAt", "lastAccessedAt", "isPrimary");
            summary.put("storageTier", tier(imageSet));
            summary.set("DICOMTags", tags);
            results.add(summary);
        }
        JsonNode sort = criteria.path("sort");
        String field = sort.path("sortField").asText("updatedAt");
        Comparator<ObjectNode> order = "DICOMStudyDateAndTime".equals(field)
                ? Comparator.comparing((ObjectNode summary) -> studyDateTime(summary.path("DICOMTags")))
                : Comparator.comparingDouble((ObjectNode summary) -> summary.path(field).asDouble());
        results.sort("ASC".equals(sort.path("sortOrder").asText("DESC")) ? order : order.reversed());
        ObjectNode out = mapper.createObjectNode();
        MedicalImagingStore.page(results, in, out, "imageSetsMetadataSummaries", 50);
        if (sort.isObject()) {
            out.set("sort", sort.deepCopy());
        }
        return out;
    }

    // ================================================================ updates

    private static final Set<String> IDENTIFIERS = Set.of("StudyInstanceUID", "SeriesInstanceUID", "SOPInstanceUID");

    private static JsonNode blob(JsonNode in, String member) {
        if (!in.hasNonNull(member)) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(Base64.getDecoder().decode(in.get(member).asText()));
        } catch (IllegalArgumentException | IOException e) {
            throw MedicalImagingStore.validation(member + " must be a JSON document.");
        }
    }

    private static void checkIdentifiers(JsonNode node, boolean force) {
        if (node == null || !node.isObject()) {
            return;
        }
        node.properties().forEach(entry -> {
            if (IDENTIFIERS.contains(entry.getKey()) && !force) {
                throw MedicalImagingStore.validation("Updating " + entry.getKey() + " needs force.");
            }
            checkIdentifiers(entry.getValue(), force);
        });
    }

    private static void merge(ObjectNode target, JsonNode updates) {
        updates.properties().forEach(entry -> {
            if ("SchemaVersion".equals(entry.getKey())) {
                return;
            }
            JsonNode existing = target.get(entry.getKey());
            if (existing instanceof ObjectNode object && entry.getValue().isObject()) {
                merge(object, entry.getValue());
            } else {
                target.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
    }

    private static void remove(ObjectNode target, JsonNode removals) {
        removals.properties().forEach(entry -> {
            if ("SchemaVersion".equals(entry.getKey())) {
                return;
            }
            JsonNode existing = target.get(entry.getKey());
            if (existing instanceof ObjectNode object && entry.getValue().isObject() && !entry.getValue().isEmpty()) {
                remove(object, entry.getValue());
            } else {
                target.remove(entry.getKey());
            }
        });
    }

    private void lock(String region, ObjectNode imageSet, String workflow, String detailType) {
        imageSet.put("imageSetState", "LOCKED");
        imageSet.put("imageSetWorkflowStatus", workflow);
        imageSet.put("updatedAt", MedicalImagingStore.epoch());
        save(region, imageSet);
        imports.imageSetEvent(store.account(), region, imageSet, detailType);
    }

    private static void ready(ObjectNode imageSet, String latestVersion) {
        String state = imageSet.path("imageSetState").asText();
        if (!"ACTIVE".equals(state)) {
            throw MedicalImagingStore.conflict("The image set " + imageSet.path("imageSetId").asText() + " is "
                    + state + ".");
        }
        if (latestVersion != null && !latestVersion.equals(imageSet.path("versionId").asText())) {
            throw MedicalImagingStore.conflict("The image set's latest version is "
                    + imageSet.path("versionId").asText() + ", not " + latestVersion + ".");
        }
    }

    public ObjectNode updateImageSetMetadata(JsonNode in, String region) {
        String datastoreId = required(in, "datastoreId");
        String imageSetId = required(in, "imageSetId");
        ObjectNode imageSet = live(region, datastoreId, imageSetId);
        ready(imageSet, required(in, "latestVersionId"));
        boolean force = in.path("force").asBoolean(false);
        JsonNode updates = in.path("updateImageSetMetadataUpdates");
        String account = store.account();
        ObjectNode doc;
        String revert = updates.path("revertToVersionId").asText(null);
        if (revert != null) {
            versionOf(imageSet, revert);
            doc = imports.metadata(account, region, imageSet, revert);
        } else {
            JsonNode dicom = updates.path("DICOMUpdates");
            JsonNode updatable = blob(dicom, "updatableAttributes");
            JsonNode removable = blob(dicom, "removableAttributes");
            if (updatable == null && removable == null) {
                throw MedicalImagingStore.validation("The update names no attributes to update or remove.");
            }
            checkIdentifiers(updatable, force);
            doc = imports.metadata(account, region, imageSet, imageSet.path("versionId").asText());
            if (updatable != null) {
                merge(doc, updatable);
            }
            if (removable != null) {
                remove(doc, removable);
            }
        }
        lock(region, imageSet, "UPDATING", "Image Set Updating");
        if (force) {
            imageSet.putObject("overrides").put("forced", true);
        }
        ObjectNode finalDoc = doc;
        boolean study = in.path("includeStudyImageSets").asBoolean(false);
        JsonNode updatable = revert == null ? blob(updates.path("DICOMUpdates"), "updatableAttributes") : null;
        later(() -> {
            ObjectNode current = store.getForAccount(account, region, MedicalImagingStore.IMAGE_SET, datastoreId + "/"
                    + imageSetId).orElse(null);
            if (current == null) {
                return;
            }
            if (force) {
                current.putObject("overrides").put("forced", true);
            }
            imports.newVersion(account, region, current, finalDoc, "UPDATED", 0);
            if (study && updatable != null) {
                propagate(account, region, datastoreId, imageSetId, finalDoc, updatable);
            }
        });
        ObjectNode out = mapper.createObjectNode();
        out.put("datastoreId", datastoreId);
        out.put("imageSetId", imageSetId);
        out.put("latestVersionId", String.valueOf(Integer.parseInt(imageSet.path("versionId").asText()) + 1));
        out.put("imageSetState", "LOCKED");
        out.put("imageSetWorkflowStatus", "UPDATING");
        MedicalImagingStore.copy(imageSet, out, "createdAt", "updatedAt");
        return out;
    }

    /** Applies patient and study level updates to the other image sets of the same study. */
    private void propagate(String account, String region, String datastoreId, String imageSetId, ObjectNode doc,
                           JsonNode updatable) {
        String study = doc.path("Study").path("DICOM").path("StudyInstanceUID").asText();
        for (ObjectNode other : store.listForAccount(account, region, MedicalImagingStore.IMAGE_SET)) {
            if (!datastoreId.equals(MedicalImagingStore.meta(other).path("datastoreId").asText())
                    || imageSetId.equals(other.path("imageSetId").asText())
                    || !"ACTIVE".equals(other.path("imageSetState").asText())) {
                continue;
            }
            ObjectNode otherDoc = imports.metadata(account, region, other, other.path("versionId").asText());
            if (otherDoc == null || !study.equals(otherDoc.path("Study").path("DICOM").path("StudyInstanceUID")
                    .asText())) {
                continue;
            }
            ObjectNode shared = mapper.createObjectNode();
            if (updatable.has("Patient")) {
                shared.set("Patient", updatable.get("Patient").deepCopy());
            }
            if (updatable.path("Study").has("DICOM")) {
                shared.putObject("Study").set("DICOM", updatable.path("Study").get("DICOM").deepCopy());
            }
            merge(otherDoc, shared);
            imports.newVersion(account, region, other, otherDoc, "UPDATED", 0);
        }
    }

    // ================================================================ copies

    /** The part of a source's metadata a copy takes: the instances its copiable attributes name, or all. */
    private ObjectNode copiable(ObjectNode source, JsonNode selection) {
        if (selection == null) {
            return source;
        }
        ObjectNode copy = source.deepCopy();
        JsonNode wanted = selection.path("Study").path("Series");
        ObjectNode series = MedicalImagingImports.child(MedicalImagingImports.child(copy, "Study"), "Series");
        Iterator<Map.Entry<String, JsonNode>> each = series.properties().iterator();
        while (each.hasNext()) {
            Map.Entry<String, JsonNode> entry = each.next();
            if (!wanted.has(entry.getKey())) {
                each.remove();
                continue;
            }
            JsonNode instances = wanted.path(entry.getKey()).path("Instances");
            if (instances.isObject() && !instances.isEmpty()) {
                ObjectNode kept = (ObjectNode) entry.getValue().path("Instances");
                kept.properties().removeIf(instance -> !instances.has(instance.getKey()));
            }
        }
        if (series.isEmpty()) {
            throw MedicalImagingStore.validation("The copiable attributes name no series of the source image set.");
        }
        return copy;
    }

    public ObjectNode copyImageSet(JsonNode in, String region) {
        String datastoreId = required(in, "datastoreId");
        String sourceId = required(in, "sourceImageSetId");
        ObjectNode source = live(region, datastoreId, sourceId);
        JsonNode information = in.path("copyImageSetInformation");
        ready(source, required(information.path("sourceImageSet"), "latestVersionId"));
        boolean force = in.path("force").asBoolean(false);
        boolean promote = in.path("promoteToPrimary").asBoolean(false);
        String account = store.account();
        String copiableText = information.path("sourceImageSet").path("DICOMCopies").path("copiableAttributes")
                .asText(null);
        JsonNode selection;
        try {
            selection = copiableText == null ? null : mapper.readTree(copiableText);
        } catch (IOException e) {
            throw MedicalImagingStore.validation("copiableAttributes must be a JSON document.");
        }
        ObjectNode sourceDoc = imports.metadata(account, region, source, source.path("versionId").asText());
        ObjectNode copied = copiable(sourceDoc, selection);
        JsonNode destinationInfo = information.path("destinationImageSet");
        ObjectNode destination = null;
        if (destinationInfo.isObject() && destinationInfo.hasNonNull("imageSetId")) {
            destination = live(region, datastoreId, destinationInfo.get("imageSetId").asText());
            ready(destination, required(destinationInfo, "latestVersionId"));
            ObjectNode destinationDoc = imports.metadata(account, region, destination, destination.path("versionId")
                    .asText());
            String sourceStudy = copied.path("Study").path("DICOM").path("StudyInstanceUID").asText();
            if (!force && !sourceStudy.equals(destinationDoc.path("Study").path("DICOM").path("StudyInstanceUID")
                    .asText())) {
                throw MedicalImagingStore.validation("The source and destination image sets are of different "
                        + "studies; copy with force.");
            }
        }
        lock(region, source, "COPYING_WITH_READ_ONLY_ACCESS", "Image Set Copying With Read Only Access");
        ObjectNode destinationRecord;
        if (destination == null) {
            Map<String, ObjectNode> instances = new LinkedHashMap<>();
            String seriesUid = copied.path("Study").path("Series").fieldNames().next();
            copied.path("Study").path("Series").path(seriesUid).path("Instances").properties().forEach(entry ->
                    instances.put(entry.getKey(), (ObjectNode) entry.getValue()));
            destinationRecord = imports.create(account, region, datastoreId, (ObjectNode) copied.path("Patient")
                    .path("DICOM"), (ObjectNode) copied.path("Study").path("DICOM"), (ObjectNode) copied
                    .path("Study").path("Series").path(seriesUid).path("DICOM"), seriesUid, instances, promote);
            ObjectNode doc = imports.metadata(account, region, destinationRecord, "1");
            ObjectNode series = MedicalImagingImports.child(MedicalImagingImports.child(doc, "Study"), "Series");
            copied.path("Study").path("Series").properties().forEach(entry -> series.set(entry.getKey(),
                    entry.getValue().deepCopy()));
            store.put(region, MedicalImagingStore.METADATA, MedicalImagingImports.metadataKey(datastoreId,
                    destinationRecord.path("imageSetId").asText(), "1"), doc);
            lock(region, destinationRecord, "COPYING", "Image Set Copying");
        } else {
            destinationRecord = destination;
            lock(region, destinationRecord, "COPYING", "Image Set Copying");
        }
        String destinationId = destinationRecord.path("imageSetId").asText();
        boolean intoExisting = destination != null;
        later(() -> {
            ObjectNode src = store.getForAccount(account, region, MedicalImagingStore.IMAGE_SET, datastoreId + "/"
                    + sourceId).orElse(null);
            ObjectNode dst = store.getForAccount(account, region, MedicalImagingStore.IMAGE_SET, datastoreId + "/"
                    + destinationId).orElse(null);
            if (src != null) {
                src.put("imageSetState", "ACTIVE");
                src.put("imageSetWorkflowStatus", "COPIED");
                store.putForAccount(account, region, MedicalImagingStore.IMAGE_SET, datastoreId + "/" + sourceId, src);
            }
            if (dst == null) {
                return;
            }
            if (promote) {
                dst.put("isPrimary", true);
            }
            if (intoExisting) {
                ObjectNode doc = imports.metadata(account, region, dst, dst.path("versionId").asText());
                ObjectNode series = MedicalImagingImports.child(MedicalImagingImports.child(doc, "Study"), "Series");
                copied.path("Study").path("Series").properties().forEach(entry -> {
                    ObjectNode target = MedicalImagingImports.child(series, entry.getKey());
                    if (!target.has("DICOM")) {
                        target.set("DICOM", entry.getValue().path("DICOM").deepCopy());
                    }
                    ObjectNode instances = MedicalImagingImports.child(target, "Instances");
                    entry.getValue().path("Instances").properties().forEach(instance -> instances.set(
                            instance.getKey(), instance.getValue().deepCopy()));
                });
                imports.newVersion(account, region, dst, doc, "COPIED", 0);
            } else {
                dst.put("imageSetState", "ACTIVE");
                dst.put("imageSetWorkflowStatus", "COPIED");
                store.putForAccount(account, region, MedicalImagingStore.IMAGE_SET, datastoreId + "/" + destinationId,
                        dst);
                imports.imageSetEvent(account, region, dst, "Image Set Copied");
            }
        });
        ObjectNode out = mapper.createObjectNode();
        out.put("datastoreId", datastoreId);
        ObjectNode sourceProperties = out.putObject("sourceImageSetProperties");
        sourceProperties.put("imageSetId", sourceId);
        sourceProperties.put("latestVersionId", source.path("versionId").asText());
        sourceProperties.put("imageSetState", "LOCKED");
        sourceProperties.put("imageSetWorkflowStatus", "COPYING_WITH_READ_ONLY_ACCESS");
        MedicalImagingStore.copy(source, sourceProperties, "createdAt", "updatedAt", "imageSetArn");
        ObjectNode destinationProperties = out.putObject("destinationImageSetProperties");
        destinationProperties.put("imageSetId", destinationId);
        destinationProperties.put("latestVersionId", intoExisting ? String.valueOf(Integer.parseInt(
                destinationRecord.path("versionId").asText()) + 1) : "1");
        destinationProperties.put("imageSetState", "LOCKED");
        destinationProperties.put("imageSetWorkflowStatus", "COPYING");
        MedicalImagingStore.copy(destinationRecord, destinationProperties, "createdAt", "updatedAt", "imageSetArn");
        return out;
    }

    // ================================================================ deletes

    public ObjectNode deleteImageSet(JsonNode in, String region) {
        String datastoreId = required(in, "datastoreId");
        String imageSetId = required(in, "imageSetId");
        ObjectNode imageSet = live(region, datastoreId, imageSetId);
        ready(imageSet, null);
        lock(region, imageSet, "DELETING", "Image Set Deleting");
        String account = store.account();
        later(() -> {
            ObjectNode current = store.getForAccount(account, region, MedicalImagingStore.IMAGE_SET, datastoreId + "/"
                    + imageSetId).orElse(null);
            if (current == null) {
                return;
            }
            ObjectNode doc = imports.metadata(account, region, current, current.path("versionId").asText());
            Set<String> referenced = new HashSet<>();
            for (ObjectNode other : store.listForAccount(account, region, MedicalImagingStore.IMAGE_SET)) {
                if (datastoreId.equals(MedicalImagingStore.meta(other).path("datastoreId").asText())
                        && !imageSetId.equals(other.path("imageSetId").asText())
                        && !"DELETED".equals(other.path("imageSetState").asText())) {
                    ObjectNode otherDoc = imports.metadata(account, region, other, other.path("versionId").asText());
                    if (otherDoc != null) {
                        referenced.addAll(frameIds(otherDoc));
                    }
                }
            }
            if (doc != null) {
                for (String frame : frameIds(doc)) {
                    if (!referenced.contains(frame)) {
                        store.deleteFrame(account, region, datastoreId, frame);
                    }
                }
            }
            for (JsonNode version : MedicalImagingStore.meta(current).path("versions")) {
                store.deleteForAccount(account, region, MedicalImagingStore.METADATA, MedicalImagingImports.metadataKey(
                        datastoreId, imageSetId, version.path("versionId").asText()));
            }
            double now = MedicalImagingStore.epoch();
            current.put("imageSetState", "DELETED");
            current.put("imageSetWorkflowStatus", "DELETED");
            current.put("deletedAt", now);
            current.put("updatedAt", now);
            ArrayNode versions = (ArrayNode) MedicalImagingStore.meta(current).path("versions");
            for (JsonNode version : versions) {
                ((ObjectNode) version).put("imageSetState", "DELETED");
                ((ObjectNode) version).put("ImageSetWorkflowStatus", "DELETED");
                ((ObjectNode) version).put("deletedAt", now);
            }
            store.putForAccount(account, region, MedicalImagingStore.IMAGE_SET, datastoreId + "/" + imageSetId,
                    current);
            imports.imageSetEvent(account, region, current, "Image Set Deleted");
        });
        ObjectNode out = mapper.createObjectNode();
        out.put("datastoreId", datastoreId);
        out.put("imageSetId", imageSetId);
        out.put("imageSetState", "LOCKED");
        out.put("imageSetWorkflowStatus", "DELETING");
        return out;
    }

    // ================================================================ tags

    /** The record an ARN names, with its kind and key. */
    private Object[] tagged(String region, String arn) {
        String resource;
        try {
            resource = AwsArnUtils.parse(arn).resource();
        } catch (RuntimeException e) {
            throw MedicalImagingStore.validation("The ARN " + arn + " isn't valid.");
        }
        String[] parts = resource.split("/");
        if (parts.length == 2 && "datastore".equals(parts[0])) {
            return new Object[] {MedicalImagingStore.DATASTORE, parts[1], datastore(region, parts[1])};
        }
        if (parts.length == 4 && "datastore".equals(parts[0]) && "imageset".equals(parts[2])) {
            return new Object[] {MedicalImagingStore.IMAGE_SET, parts[1] + "/" + parts[3], imageSet(region, parts[1],
                    parts[3])};
        }
        throw MedicalImagingStore.validation("The ARN " + arn + " doesn't name a data store or image set.");
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        Object[] tagged = tagged(region, required(in, "resourceArn"));
        MedicalImagingStore.addTags((ObjectNode) tagged[2], in.path("tags"));
        store.put(region, (String) tagged[0], (String) tagged[1], (ObjectNode) tagged[2]);
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        Object[] tagged = tagged(region, required(in, "resourceArn"));
        in.path("tagKeys").forEach(key -> MedicalImagingStore.tags((ObjectNode) tagged[2]).remove(key.asText()));
        store.put(region, (String) tagged[0], (String) tagged[1], (ObjectNode) tagged[2]);
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("tags", MedicalImagingStore.tags((ObjectNode) tagged(region, required(in, "resourceArn"))[2])
                .deepCopy());
        return out;
    }
}
