package io.floci.aws.services.medicalimaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

/**
 * DICOM import jobs. A job reads every object under its input URI through its data access role (which must exist and
 * trust {@code medical-imaging.amazonaws.com}), parses each as a DICOM Part 10 file, and groups the instances into
 * image sets by study and series: a series already in the data store gets a new version of its primary image set, and
 * instances that conflict with it go to a new image set that isn't primary. Image frames are stored as HTJ2K lossless
 * (or JPEG 2000 lossless, by the data store's format) through {@link MedicalImagingCodecs}; JPEG baseline, JPEG 2000,
 * HTJ2K and MPEG frames are stored as they are. The job writes its output manifest and its success and failure lists
 * under its output URI, then completes; each state change of jobs and image sets goes to the default event bus.
 */
@ApplicationScoped
public class MedicalImagingImports {

    private static final Logger LOG = Logger.getLogger(MedicalImagingImports.class);
    static final String HTJ2K = "1.2.840.10008.1.2.4.202";
    static final String J2K_LOSSLESS = "1.2.840.10008.1.2.4.90";
    private static final Set<String> NATIVE = Set.of(MedicalImagingDicom.IMPLICIT_LE, MedicalImagingDicom.EXPLICIT_LE,
            MedicalImagingDicom.EXPLICIT_BE, MedicalImagingDicom.DEFLATED);
    private static final Set<String> AS_IS = Set.of("1.2.840.10008.1.2.4.50", "1.2.840.10008.1.2.4.51",
            "1.2.840.10008.1.2.4.81", "1.2.840.10008.1.2.4.91", "1.2.840.10008.1.2.4.201", "1.2.840.10008.1.2.4.202",
            "1.2.840.10008.1.2.4.203", "1.2.840.10008.1.2.4.100", "1.2.840.10008.1.2.4.101",
            "1.2.840.10008.1.2.4.102", "1.2.840.10008.1.2.4.103", "1.2.840.10008.1.2.4.104",
            "1.2.840.10008.1.2.4.105", "1.2.840.10008.1.2.4.106", "1.2.840.10008.1.2.4.107",
            "1.2.840.10008.1.2.4.108");
    private static final Set<String> DCMTK = Set.of("1.2.840.10008.1.2.4.57", "1.2.840.10008.1.2.4.70",
            "1.2.840.10008.1.2.4.80");

    static final Set<String> PATIENT = Set.of("PatientName", "PatientID", "IssuerOfPatientID", "TypeOfPatientID",
            "IssuerOfPatientIDQualifiersSequence", "PatientBirthDate", "PatientBirthTime", "PatientSex",
            "OtherPatientIDsSequence", "OtherPatientNames", "EthnicGroup", "PatientComments",
            "PatientSpeciesDescription", "PatientSpeciesCodeSequence", "PatientBreedDescription",
            "PatientBreedCodeSequence", "ResponsiblePerson", "ResponsiblePersonRole", "ResponsibleOrganization",
            "PatientIdentityRemoved", "DeidentificationMethod", "DeidentificationMethodCodeSequence", "QualityControlSubject");
    static final Set<String> STUDY = Set.of("StudyInstanceUID", "StudyDate", "StudyTime", "ReferringPhysicianName",
            "ReferringPhysicianIdentificationSequence", "StudyID", "AccessionNumber",
            "IssuerOfAccessionNumberSequence", "StudyDescription", "PhysiciansOfRecord",
            "NameOfPhysiciansReadingStudy", "ProcedureCodeSequence", "AdmittingDiagnosesDescription",
            "AdmittingDiagnosesCodeSequence", "PatientAge", "PatientSize", "PatientWeight", "Occupation",
            "AdditionalPatientHistory", "RequestedProcedureDescription", "ReasonForPerformedProcedureCodeSequence");
    static final Set<String> SERIES = Set.of("SeriesInstanceUID", "Modality", "SeriesNumber", "Laterality",
            "SeriesDate", "SeriesTime", "PerformingPhysicianName", "ProtocolName", "SeriesDescription",
            "OperatorsName", "BodyPartExamined", "PatientPosition", "Manufacturer", "InstitutionName",
            "InstitutionAddress", "StationName", "InstitutionalDepartmentName", "ManufacturerModelName",
            "DeviceSerialNumber", "SoftwareVersions", "FrameOfReferenceUID", "PositionReferenceIndicator",
            "SmallestPixelValueInSeries", "LargestPixelValueInSeries", "RequestAttributesSequence");

    /** An instance read from an input object. */
    record Instance(String inputFile, byte[] data, MedicalImagingDicom.Parsed parsed, String study, String series,
                    String sop) {
    }

    private final MedicalImagingStore store;
    private final ObjectMapper mapper;
    private final MedicalImagingCodecs codecs;
    private final Provider<S3Service> s3;
    private final Provider<IamService> iam;
    private final Provider<EventBridgeService> events;
    private final Vertx vertx;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "medicalimaging-imports");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public MedicalImagingImports(MedicalImagingStore store, MedicalImagingCodecs codecs, Provider<S3Service> s3,
                                 Provider<IamService> iam, Provider<EventBridgeService> events, Vertx vertx) {
        this.store = store;
        this.mapper = store.mapper();
        this.codecs = codecs;
        this.s3 = s3;
        this.iam = iam;
        this.events = events;
        this.vertx = vertx;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    // ================================================================ events

    void publish(String account, String region, String detailType, ObjectNode detail, String resource) {
        detail.put("imagingVersion", "1.0");
        try {
            RequestScopes.runAs(account, region, () -> events.get().publishServiceEvent("aws.medical-imaging",
                    detailType, detail.toString(), resource, region));
        } catch (RuntimeException e) {
            LOG.debugv("HealthImaging could not send the {0} event: {1}", detailType, e.getMessage());
        }
    }

    void imageSetEvent(String account, String region, ObjectNode imageSet, String detailType) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("datastoreId", MedicalImagingStore.meta(imageSet).path("datastoreId").asText());
        detail.put("imageSetId", imageSet.path("imageSetId").asText());
        detail.put("imageSetState", imageSet.path("imageSetState").asText());
        detail.put("imageSetWorkflowStatus", imageSet.path("imageSetWorkflowStatus").asText());
        publish(account, region, detailType, detail, imageSet.path("imageSetArn").asText());
    }

    private void jobEvent(String account, String region, ObjectNode job, String detailType) {
        ObjectNode detail = mapper.createObjectNode();
        for (String member : List.of("datastoreId", "jobId", "jobName", "jobStatus", "inputS3Uri", "outputS3Uri")) {
            detail.put(member, job.path(member).asText());
        }
        publish(account, region, detailType, detail, MedicalImagingStore.meta(job).path("datastoreArn").asText());
    }

    // ================================================================ jobs

    /** Starts a submitted job: in progress after the transition, then run. */
    void start(String account, String region, String datastoreId, String jobId) {
        jobEvent(account, region, job(account, region, datastoreId, jobId), "Import Job Submitted");
        vertx.setTimer(Math.max(1, store.config().transitionMillis()), id -> worker.submit(() -> {
            try {
                ObjectNode job = job(account, region, datastoreId, jobId);
                job.put("jobStatus", "IN_PROGRESS");
                save(account, region, job);
                jobEvent(account, region, job, "Import Job In Progress");
                run(account, region, job);
            } catch (RuntimeException e) {
                LOG.warnv("HealthImaging import job {0} failed: {1}", jobId, e.getMessage());
                ObjectNode job = job(account, region, datastoreId, jobId);
                finish(account, region, job, "FAILED", "The import job failed: " + e.getMessage());
            }
        }));
    }

    private ObjectNode job(String account, String region, String datastoreId, String jobId) {
        return store.getForAccount(account, region, MedicalImagingStore.JOB, datastoreId + "/" + jobId).orElseThrow(
                () -> new IllegalStateException("The job " + jobId + " is gone."));
    }

    private void save(String account, String region, ObjectNode job) {
        store.putForAccount(account, region, MedicalImagingStore.JOB, job.path("datastoreId").asText() + "/"
                + job.path("jobId").asText(), job);
    }

    private void finish(String account, String region, ObjectNode job, String status, String message) {
        job.put("jobStatus", status);
        job.put("endedAt", MedicalImagingStore.epoch());
        if (message != null) {
            job.put("message", message);
        }
        save(account, region, job);
        jobEvent(account, region, job, "COMPLETED".equals(status) ? "Import Job Completed" : "Import Job Failed");
    }

    static String[] s3Uri(String uri) {
        if (uri == null || !uri.startsWith("s3://")) {
            return null;
        }
        String rest = uri.substring(5);
        int slash = rest.indexOf('/');
        return slash < 0 ? new String[] {rest, ""} : new String[] {rest.substring(0, slash), rest.substring(slash + 1)};
    }

    private void run(String account, String region, ObjectNode job) {
        String datastoreId = job.path("datastoreId").asText();
        ObjectNode datastore = store.getForAccount(account, region, MedicalImagingStore.DATASTORE, datastoreId)
                .orElse(null);
        if (datastore == null || !"ACTIVE".equals(datastore.path("datastoreStatus").asText())) {
            finish(account, region, job, "FAILED", "The data store " + datastoreId + " isn't active.");
            return;
        }
        String roleProblem = role(account, region, job.path("dataAccessRoleArn").asText());
        if (roleProblem != null) {
            finish(account, region, job, "FAILED", roleProblem);
            return;
        }
        String[] input = s3Uri(job.path("inputS3Uri").asText());
        String[] output = s3Uri(job.path("outputS3Uri").asText());
        List<S3Object> objects;
        try {
            objects = RequestScopes.callAs(account, region, () -> s3.get().listObjects(input[0], input[1], null,
                    100_000));
        } catch (AwsException e) {
            finish(account, region, job, "FAILED", "The input " + job.path("inputS3Uri").asText()
                    + " couldn't be read: " + e.getMessage());
            return;
        }
        ArrayNode successes = mapper.createArrayNode();
        ArrayNode failures = mapper.createArrayNode();
        List<Instance> instances = new ArrayList<>();
        int scanned = 0;
        for (S3Object object : objects) {
            if (object.getKey().endsWith("/")) {
                continue;
            }
            scanned++;
            String file = "s3://" + input[0] + "/" + object.getKey();
            try {
                byte[] data = RequestScopes.callAs(account, region, () -> s3.get().getObject(input[0],
                        object.getKey()).getData());
                MedicalImagingDicom.Parsed parsed = MedicalImagingDicom.parse(data);
                String study = parsed.text(0x0020000D);
                String series = parsed.text(0x0020000E);
                String sop = parsed.text(0x00080018);
                if (blank(study) || blank(series) || blank(sop)) {
                    throw new MedicalImagingDicom.DicomException("The instance has no StudyInstanceUID, "
                            + "SeriesInstanceUID or SOPInstanceUID.");
                }
                instances.add(new Instance(file, data, parsed, study, series, sop));
            } catch (MedicalImagingDicom.DicomException e) {
                failure(failures, file, "ValidationException", e.getMessage());
            } catch (AwsException e) {
                failure(failures, file, "AccessDeniedException", e.getMessage());
            } catch (RuntimeException e) {
                failure(failures, file, "ValidationException", "The file couldn't be read as DICOM: "
                        + e.getMessage());
            }
        }
        Map<String, List<Instance>> series = new LinkedHashMap<>();
        for (Instance instance : instances) {
            series.computeIfAbsent(instance.study() + "/" + instance.series(), key -> new ArrayList<>()).add(instance);
        }
        JsonNode overrides = jsonMetadata(account, region, job, input);
        ArrayNode summaries = mapper.createArrayNode();
        int imported = 0;
        int serverErrors = 0;
        for (List<Instance> group : series.values()) {
            Map<String, ObjectNode> built = new LinkedHashMap<>();
            for (Instance instance : group) {
                try {
                    built.put(instance.inputFile(), instanceMetadata(account, region, datastore, instance));
                } catch (MedicalImagingDicom.DicomException e) {
                    failure(failures, instance.inputFile(), "ValidationException", e.getMessage());
                } catch (MedicalImagingCodecs.TranscodeException e) {
                    serverErrors++;
                    failure(failures, instance.inputFile(), "InternalServerException", "The image frames couldn't "
                            + "be encoded: " + e.getMessage());
                }
            }
            if (built.isEmpty()) {
                continue;
            }
            List<ObjectNode> placed = place(account, region, datastore, group, built, overrides);
            for (ObjectNode imageSet : placed) {
                ObjectNode summary = summaries.addObject();
                summary.put("imageSetId", imageSet.path("imageSetId").asText());
                summary.put("imageSetVersion", Integer.parseInt(imageSet.path("versionId").asText()));
                summary.put("isPrimary", imageSet.path("isPrimary").asBoolean());
                summary.put("numberOfMatchedSOPInstances", MedicalImagingStore.meta(imageSet).path("imported")
                        .asInt());
            }
            for (Instance instance : group) {
                if (!built.containsKey(instance.inputFile())) {
                    continue;
                }
                imported++;
                ObjectNode line = successes.addObject();
                line.put("inputFile", instance.inputFile());
                ObjectNode response = line.putObject("importResponse");
                ObjectNode target = target(placed, instance.sop());
                response.put("imageSetId", target.path("imageSetId").asText());
                response.put("isPrimary", target.path("isPrimary").asBoolean());
                response.put("studyInstanceUID", instance.study());
                response.put("seriesInstanceUID", instance.series());
                response.put("sopInstanceUID", instance.sop());
            }
        }
        String base = output[1].isEmpty() || output[1].endsWith("/") ? output[1] : output[1] + "/";
        String jobId = job.path("jobId").asText();
        ObjectNode manifest = mapper.createObjectNode();
        ObjectNode summary = manifest.putObject("jobSummary");
        summary.put("jobId", jobId);
        summary.put("datastoreId", datastoreId);
        summary.put("inputS3Uri", job.path("inputS3Uri").asText());
        summary.put("outputS3Uri", job.path("outputS3Uri").asText());
        summary.put("successOutputS3Uri", "s3://" + output[0] + "/" + base + jobId + "/SUCCESS/");
        summary.put("failureOutputS3Uri", "s3://" + output[0] + "/" + base + jobId + "/FAILURE/");
        summary.put("numberOfScannedFiles", scanned);
        summary.put("numberOfImportedFiles", imported);
        summary.put("numberOfFilesWithCustomerError", failures.size() - serverErrors);
        summary.put("numberOfFilesWithServerError", serverErrors);
        summary.put("numberOfGeneratedImageSets", summaries.size());
        summary.set("imageSetsSummary", summaries);
        try {
            write(account, region, output[0], base + jobId + "/job-output-manifest.json", manifest.toString());
            write(account, region, output[0], base + jobId + "/SUCCESS/success.ndjson", ndjson(successes));
            write(account, region, output[0], base + jobId + "/FAILURE/failure.ndjson", ndjson(failures));
        } catch (AwsException e) {
            finish(account, region, job, "FAILED", "The output " + job.path("outputS3Uri").asText()
                    + " couldn't be written: " + e.getMessage());
            return;
        }
        finish(account, region, job, "COMPLETED", null);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void failure(ArrayNode failures, String file, String type, String message) {
        ObjectNode line = failures.addObject();
        line.put("inputFile", file);
        ObjectNode exception = line.putObject("exception");
        exception.put("exceptionType", type);
        exception.put("message", message);
    }

    private static String ndjson(ArrayNode lines) {
        StringBuilder out = new StringBuilder();
        lines.forEach(line -> out.append(line).append('\n'));
        return out.toString();
    }

    private void write(String account, String region, String bucket, String key, String body) {
        RequestScopes.runAs(account, region, () -> s3.get().putObject(bucket, key, body.getBytes(
                StandardCharsets.UTF_8), key.endsWith(".json") ? "application/json" : "application/x-ndjson",
                Map.of()));
    }

    /** Why a data access role can't be assumed, or null when it can. */
    private String role(String account, String region, String arn) {
        if (!AwsArnUtils.isArnFor(arn, "iam") || !AwsArnUtils.parse(arn).resource().startsWith("role/")) {
            return "The data access role " + arn + " isn't a role ARN.";
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        String principal = ServicePrincipals.of(MedicalImagingStore.SERVICE);
        try {
            IamRole role = RequestScopes.callAs(account, region, () -> iam.get().getRole(name));
            String trust = role.getAssumeRolePolicyDocument() == null ? "" : URLDecoder.decode(
                    role.getAssumeRolePolicyDocument(), StandardCharsets.UTF_8);
            if (!trust.contains(principal)) {
                return "The data access role " + arn + " doesn't trust " + principal + ".";
            }
            return null;
        } catch (AwsException e) {
            return "HealthImaging couldn't assume the data access role " + arn + ": " + e.getMessage();
        }
    }

    // ================================================================ metadata

    private ObjectNode level(MedicalImagingDicom.Parsed parsed, Set<String> keywords, boolean exclude) {
        MedicalImagingDicom.Dictionary dictionary = MedicalImagingDicom.Dictionary.get();
        return MedicalImagingDicom.json(mapper, parsed.dataset, parsed.bigEndian, parsed.charset(), tag -> {
            String keyword = dictionary.keyword(tag);
            return keyword != null && (exclude != keywords.contains(keyword));
        });
    }

    /** An instance's own metadata, with its frames encoded and stored. */
    private ObjectNode instanceMetadata(String account, String region, ObjectNode datastore, Instance instance)
            throws MedicalImagingDicom.DicomException {
        MedicalImagingDicom.Parsed parsed = instance.parsed();
        HashSet<String> upper = new HashSet<>(PATIENT);
        upper.addAll(STUDY);
        upper.addAll(SERIES);
        ObjectNode out = mapper.createObjectNode();
        out.set("DICOM", level(parsed, upper, true));
        if (parsed.dataset.containsKey(MedicalImagingDicom.PIXEL_DATA)) {
            String format = "JPEG_2000_LOSSLESS".equals(datastore.path("losslessStorageFormat").asText())
                    ? J2K_LOSSLESS : HTJ2K;
            Encoded encoded = encode(parsed, instance.data(), format);
            out.put("StoredTransferSyntaxUID", encoded.transferSyntax());
            ArrayNode frames = out.putArray("ImageFrames");
            String datastoreId = datastore.path("datastoreId").asText();
            for (EncodedFrame frame : encoded.frames()) {
                String id = MedicalImagingStore.random(32, "0123456789abcdef");
                store.putFrame(account, region, datastoreId, id, frame.data());
                ObjectNode node = frames.addObject();
                node.put("ID", id);
                if (!frame.checksums().isEmpty()) {
                    ArrayNode checksums = node.putArray("PixelDataChecksumFromBaseToFullResolution");
                    frame.checksums().forEach(level -> checksums.addObject().put("Width", level[0])
                            .put("Height", level[1]).put("Checksum", level[2]));
                }
                if (frame.min() != null) {
                    node.put("MinPixelValue", frame.min());
                    node.put("MaxPixelValue", frame.max());
                }
                node.put("FrameSizeInBytes", frame.data().length);
            }
        }
        return out;
    }

    /** Places a series' instances: into its primary image set when they fit it, else into a new image set. */
    private List<ObjectNode> place(String account, String region, ObjectNode datastore, List<Instance> group,
                                   Map<String, ObjectNode> built, JsonNode overrides) {
        String datastoreId = datastore.path("datastoreId").asText();
        Instance first = group.get(0);
        ObjectNode patient = level(first.parsed(), PATIENT, false);
        ObjectNode study = level(first.parsed(), STUDY, false);
        ObjectNode seriesLevel = level(first.parsed(), SERIES, false);
        applyOverrides(overrides, first.study(), first.series(), patient, study, seriesLevel);
        ObjectNode primary = null;
        ObjectNode primaryDoc = null;
        for (ObjectNode imageSet : store.listForAccount(account, region, MedicalImagingStore.IMAGE_SET)) {
            if (!datastoreId.equals(MedicalImagingStore.meta(imageSet).path("datastoreId").asText())
                    || !imageSet.path("isPrimary").asBoolean() || !"ACTIVE".equals(imageSet.path("imageSetState")
                    .asText())) {
                continue;
            }
            ObjectNode doc = metadata(account, region, imageSet, imageSet.path("versionId").asText());
            if (doc != null && first.study().equals(doc.path("Study").path("DICOM").path("StudyInstanceUID").asText())
                    && doc.path("Study").path("Series").has(first.series())) {
                primary = imageSet;
                primaryDoc = doc;
            }
        }
        List<ObjectNode> placed = new ArrayList<>();
        Map<String, ObjectNode> fitting = new LinkedHashMap<>();
        Map<String, ObjectNode> conflicting = new LinkedHashMap<>();
        for (Instance instance : group) {
            ObjectNode metadata = built.get(instance.inputFile());
            if (metadata == null) {
                continue;
            }
            boolean conflicts = false;
            if (primaryDoc != null) {
                JsonNode existing = primaryDoc.path("Study").path("Series").path(instance.series()).path("Instances")
                        .path(instance.sop());
                ObjectNode samePatient = level(instance.parsed(), PATIENT, false);
                conflicts = !samePatient.equals(primaryDoc.path("Patient").path("DICOM"))
                        || existing.isObject() && !existing.path("DICOM").equals(metadata.path("DICOM"));
            }
            (conflicts ? conflicting : fitting).put(instance.sop(), metadata);
        }
        if (!fitting.isEmpty()) {
            if (primary != null) {
                ObjectNode doc = primaryDoc.deepCopy();
                ObjectNode instances = child(child(child(child(doc, "Study"), "Series"), first.series()),
                        "Instances");
                fitting.forEach(instances::set);
                placed.add(newVersion(account, region, primary, doc, "IMPORTED", fitting.size()));
            } else {
                placed.add(create(account, region, datastoreId, patient, study, seriesLevel, first.series(), fitting,
                        true));
            }
        }
        if (!conflicting.isEmpty()) {
            placed.add(create(account, region, datastoreId, patient, study, seriesLevel, first.series(), conflicting,
                    false));
        }
        return placed;
    }

    private static ObjectNode target(List<ObjectNode> placed, String sop) {
        for (ObjectNode imageSet : placed) {
            if (MedicalImagingStore.meta(imageSet).path("sops").toString().contains("\"" + sop + "\"")) {
                return imageSet;
            }
        }
        return placed.get(0);
    }

    ObjectNode create(String account, String region, String datastoreId, ObjectNode patient, ObjectNode study,
                      ObjectNode series, String seriesUid, Map<String, ObjectNode> instances, boolean primary) {
        String id = MedicalImagingStore.random(32, "0123456789abcdef");
        ObjectNode doc = mapper.createObjectNode();
        doc.put("SchemaVersion", "1.1");
        doc.put("DatastoreID", datastoreId);
        doc.put("ImageSetID", id);
        doc.putObject("Patient").set("DICOM", patient.deepCopy());
        ObjectNode studyNode = doc.putObject("Study");
        studyNode.set("DICOM", study.deepCopy());
        ObjectNode seriesNode = studyNode.putObject("Series").putObject(seriesUid);
        seriesNode.set("DICOM", series.deepCopy());
        ObjectNode instanceNode = seriesNode.putObject("Instances");
        instances.forEach(instanceNode::set);
        double now = MedicalImagingStore.epoch();
        ObjectNode imageSet = store.record();
        imageSet.put("imageSetId", id);
        imageSet.put("versionId", "1");
        imageSet.put("imageSetState", "ACTIVE");
        imageSet.put("imageSetWorkflowStatus", "CREATED");
        imageSet.put("createdAt", now);
        imageSet.put("updatedAt", now);
        imageSet.put("isPrimary", primary);
        imageSet.put("imageSetArn", store.arnFor(account, region, "datastore/" + datastoreId + "/imageset/" + id));
        imageSet.put("storageTier", "FREQUENT_ACCESS");
        ObjectNode meta = MedicalImagingStore.meta(imageSet);
        meta.put("datastoreId", datastoreId);
        meta.put("imported", instances.size());
        ArrayNode sops = meta.putArray("sops");
        instances.keySet().forEach(sops::add);
        meta.putArray("versions").add(version(imageSet));
        store.putForAccount(account, region, MedicalImagingStore.METADATA, metadataKey(datastoreId, id, "1"), doc);
        store.putForAccount(account, region, MedicalImagingStore.IMAGE_SET, datastoreId + "/" + id, imageSet);
        imageSetEvent(account, region, imageSet, "Image Set Created");
        return imageSet;
    }

    /** Records a new version of an image set with its metadata, and makes it the latest. */
    ObjectNode newVersion(String account, String region, ObjectNode imageSet, ObjectNode doc, String workflow,
                          int imported) {
        String datastoreId = MedicalImagingStore.meta(imageSet).path("datastoreId").asText();
        String id = imageSet.path("imageSetId").asText();
        int next = Integer.parseInt(imageSet.path("versionId").asText()) + 1;
        String version = String.valueOf(next);
        imageSet.put("versionId", version);
        imageSet.put("imageSetState", "ACTIVE");
        imageSet.put("imageSetWorkflowStatus", workflow);
        imageSet.put("updatedAt", MedicalImagingStore.epoch());
        imageSet.remove("message");
        MedicalImagingStore.meta(imageSet).put("imported", imported);
        ArrayNode sops = MedicalImagingStore.meta(imageSet).putArray("sops");
        doc.path("Study").path("Series").forEach(series -> series.path("Instances").fieldNames().forEachRemaining(
                sops::add));
        ObjectNode meta = MedicalImagingStore.meta(imageSet);
        if (!meta.path("versions").isArray()) {
            meta.putArray("versions");
        }
        ((ArrayNode) meta.get("versions")).add(version(imageSet));
        store.putForAccount(account, region, MedicalImagingStore.METADATA, metadataKey(datastoreId, id, version), doc);
        store.putForAccount(account, region, MedicalImagingStore.IMAGE_SET, datastoreId + "/" + id, imageSet);
        imageSetEvent(account, region, imageSet, "IMPORTED".equals(workflow) ? "Image Set Updated"
                : "COPIED".equals(workflow) ? "Image Set Copied" : "Image Set Updated");
        return imageSet;
    }

    static ObjectNode child(ObjectNode parent, String name) {
        if (!parent.path(name).isObject()) {
            parent.putObject(name);
        }
        return (ObjectNode) parent.get(name);
    }

    ObjectNode version(ObjectNode imageSet) {
        ObjectNode version = mapper.createObjectNode();
        MedicalImagingStore.copy(imageSet, version, "imageSetId", "versionId", "imageSetState",
                "imageSetWorkflowStatus", "createdAt", "updatedAt", "deletedAt", "message", "overrides", "isPrimary");
        version.put("ImageSetWorkflowStatus", imageSet.path("imageSetWorkflowStatus").asText());
        version.remove("imageSetWorkflowStatus");
        return version;
    }

    static String metadataKey(String datastoreId, String imageSetId, String version) {
        return datastoreId + "/" + imageSetId + "/" + version;
    }

    ObjectNode metadata(String account, String region, ObjectNode imageSet, String version) {
        return store.getForAccount(account, region, MedicalImagingStore.METADATA, metadataKey(
                MedicalImagingStore.meta(imageSet).path("datastoreId").asText(), imageSet.path("imageSetId").asText(),
                version)).map(doc -> {
                    ObjectNode copy = doc.deepCopy();
                    copy.remove("_");
                    return copy;
                }).orElse(null);
    }

    // ================================================================ DICOM JSON metadata

    /** The DICOM JSON metadata files an import configuration maps to studies and series, by study and series. */
    private JsonNode jsonMetadata(String account, String region, ObjectNode job, String[] input) {
        ObjectNode found = mapper.createObjectNode();
        for (JsonNode mapping : job.path("importConfiguration").path("dicomJsonMetadataImportConfiguration")
                .path("dicomMetadataMappings")) {
            String path = mapping.path("metadataFilePath").asText();
            String key = path.startsWith("s3://") ? s3Uri(path)[1] : (input[1].isEmpty() || input[1].endsWith("/")
                    ? input[1] : input[1] + "/") + path;
            String bucket = path.startsWith("s3://") ? s3Uri(path)[0] : input[0];
            try {
                byte[] data = RequestScopes.callAs(account, region, () -> s3.get().getObject(bucket, key).getData());
                JsonNode document = mapper.readTree(data);
                JsonNode dataset = document.isArray() ? document.path(0) : document;
                found.set(mapping.path("studyInstanceUID").asText() + "/" + mapping.path("seriesInstanceUID")
                        .asText(""), fromDicomJson(dataset));
            } catch (Exception e) {
                LOG.debugv("HealthImaging could not read the DICOM JSON metadata {0}: {1}", path, e.getMessage());
            }
        }
        return found;
    }

    /** A DICOM JSON (PS3.18 Annex F) data set as keyword-named attributes. */
    ObjectNode fromDicomJson(JsonNode dataset) {
        ObjectNode out = mapper.createObjectNode();
        MedicalImagingDicom.Dictionary dictionary = MedicalImagingDicom.Dictionary.get();
        dataset.properties().forEach(attribute -> {
            String keyword;
            try {
                keyword = dictionary.keyword(Integer.parseUnsignedInt(attribute.getKey(), 16));
            } catch (NumberFormatException e) {
                keyword = attribute.getKey();
            }
            if (keyword == null) {
                return;
            }
            JsonNode node = attribute.getValue();
            String vr = node.path("vr").asText("");
            JsonNode values = node.path("Value");
            if ("SQ".equals(vr)) {
                ArrayNode items = out.putArray(keyword);
                values.forEach(item -> items.add(fromDicomJson(item)));
            } else if (!values.isArray() || values.isEmpty()) {
                out.putNull(keyword);
            } else if ("PN".equals(vr)) {
                out.put(keyword, values.path(0).path("Alphabetic").asText(""));
            } else if (values.size() == 1) {
                out.set(keyword, values.get(0));
            } else if (Set.of("US", "SS", "UL", "SL", "FL", "FD", "UV", "SV").contains(vr)) {
                out.set(keyword, values);
            } else {
                List<String> texts = new ArrayList<>();
                values.forEach(value -> texts.add(value.asText()));
                out.put(keyword, String.join("\\", texts));
            }
        });
        return out;
    }

    private static void applyOverrides(JsonNode overrides, String study, String series, ObjectNode patient,
                                       ObjectNode studyLevel, ObjectNode seriesLevel) {
        JsonNode attributes = overrides.has(study + "/" + series) ? overrides.get(study + "/" + series)
                : overrides.path(study + "/");
        attributes.properties().forEach(attribute -> {
            String keyword = attribute.getKey();
            ObjectNode target = PATIENT.contains(keyword) ? patient : STUDY.contains(keyword) ? studyLevel
                    : SERIES.contains(keyword) ? seriesLevel : null;
            if (target != null && !"StudyInstanceUID".equals(keyword) && !"SeriesInstanceUID".equals(keyword)) {
                target.set(keyword, attribute.getValue().deepCopy());
            }
        });
    }

    // ================================================================ frames

    record EncodedFrame(byte[] data, List<long[]> checksums, Long min, Long max) {
    }

    record Encoded(String transferSyntax, List<EncodedFrame> frames) {
    }

    /** An instance's frames as HealthImaging stores them. */
    Encoded encode(MedicalImagingDicom.Parsed parsed, byte[] file, String format)
            throws MedicalImagingDicom.DicomException {
        String syntax = parsed.transferSyntax;
        if (AS_IS.contains(syntax) || J2K_LOSSLESS.equals(syntax) && J2K_LOSSLESS.equals(format)) {
            List<EncodedFrame> frames = new ArrayList<>();
            for (byte[] frame : MedicalImagingDicom.frameData(parsed)) {
                frames.add(new EncodedFrame(frame, List.of(), null, null));
            }
            return new Encoded(syntax, frames);
        }
        MedicalImagingDicom.Pixels layout = MedicalImagingDicom.pixels(parsed);
        if (layout.bitsAllocated() != 8 && layout.bitsAllocated() != 16) {
            throw new MedicalImagingDicom.DicomException("Floci encodes pixel data of 8 or 16 bits allocated, not "
                    + layout.bitsAllocated() + ".");
        }
        List<byte[]> natives = new ArrayList<>();
        if (NATIVE.contains(syntax)) {
            natives.addAll(MedicalImagingDicom.frameData(parsed));
        } else if (MedicalImagingDicom.RLE.equals(syntax)) {
            for (byte[] frame : MedicalImagingDicom.frameData(parsed)) {
                natives.add(MedicalImagingDicom.rleDecode(frame, layout));
            }
        } else if (DCMTK.contains(syntax)) {
            String tool = "1.2.840.10008.1.2.4.80".equals(syntax) ? "dcmdjpls" : "dcmdjpeg";
            byte[] decompressed = codecs.run(new LinkedHashMap<>(Map.of("in.dcm", file)), tool
                    + " in.dcm out.dcm").get("out.dcm");
            if (decompressed == null) {
                throw new MedicalImagingCodecs.TranscodeException(tool + " wrote no file.");
            }
            natives.addAll(MedicalImagingDicom.frameData(MedicalImagingDicom.parse(decompressed)));
        } else if (J2K_LOSSLESS.equals(syntax)) {
            natives.addAll(decompress(parsed, layout));
        } else {
            throw new MedicalImagingDicom.DicomException("HealthImaging doesn't import the transfer syntax " + syntax
                    + ".");
        }
        return new Encoded(format, encodeNative(natives, layout, format));
    }

    /** Decompresses JPEG 2000 lossless frames to native samples with OpenJPEG. */
    private List<byte[]> decompress(MedicalImagingDicom.Parsed parsed, MedicalImagingDicom.Pixels layout)
            throws MedicalImagingDicom.DicomException {
        List<byte[]> frames = MedicalImagingDicom.frameData(parsed);
        Map<String, byte[]> inputs = new LinkedHashMap<>();
        StringBuilder script = new StringBuilder("set -e; ");
        for (int i = 0; i < frames.size(); i++) {
            inputs.put("in" + i + ".j2k", frames.get(i));
            script.append("opj_decompress -quiet -i in").append(i).append(".j2k -o out").append(i).append(".rawl; ");
        }
        Map<String, byte[]> out = codecs.run(inputs, script.toString());
        List<byte[]> natives = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            byte[] planar = out.get("out" + i + ".rawl");
            if (planar == null) {
                throw new MedicalImagingCodecs.TranscodeException("opj_decompress wrote no frame " + i + ".");
            }
            natives.add(interleave(planar, layout));
        }
        return natives;
    }

    private static int levels(MedicalImagingDicom.Pixels layout) {
        int smallest = Math.min(layout.rows(), layout.columns());
        int levels = 0;
        while (levels < 5 && smallest >> (levels + 1) >= 1) {
            levels++;
        }
        return levels;
    }

    private List<EncodedFrame> encodeNative(List<byte[]> natives, MedicalImagingDicom.Pixels layout, String format) {
        int levels = levels(layout);
        // OpenJPH reads planar raw samples from .yuv; its .raw reader (0.21) zeroes 8-bit samples of 128 and above
        String raw = ".yuv";
        int bytes = layout.bitsAllocated() / 8;
        int bits = Math.min(layout.bitsStored(), layout.bitsAllocated());
        Map<String, byte[]> inputs = new LinkedHashMap<>();
        List<long[]> ranges = new ArrayList<>();
        for (int i = 0; i < natives.size(); i++) {
            byte[] planar = planar(natives.get(i), layout);
            ranges.add(mask(planar, bytes, bits, layout.signed()));
            inputs.put((HTJ2K.equals(format) ? "f" + i + raw : "f" + i + ".rawl"), planar);
        }
        String signed = String.join(",", Collections.nCopies(layout.samples(), String.valueOf(
                layout.signed())));
        String depth = String.join(",", Collections.nCopies(layout.samples(), String.valueOf(bits)));
        String downsample = String.join(",", Collections.nCopies(layout.samples(), "{1,1}"));
        StringBuilder script = new StringBuilder("set -e; for i in $(seq 0 ").append(natives.size() - 1)
                .append("); do ");
        if (HTJ2K.equals(format)) {
            script.append("ojph_compress -i f$i").append(raw).append(" -o f$i.j2c -reversible true -prog_order RPCL -num_decomps ")
                    .append(levels).append(" -dims '{").append(layout.columns()).append(',').append(layout.rows())
                    .append("}' -num_comps ").append(layout.samples()).append(" -signed ").append(signed)
                    .append(" -bit_depth ").append(depth).append(" -downsamp '").append(downsample)
                    .append("' >/dev/null; for r in $(seq 0 ").append(levels).append("); do ojph_expand -i f$i.j2c ")
                    .append("-o f${i}_r$r").append(raw).append(" -skip_res $r,$r >/dev/null; done; rm f$i").append(raw)
                    .append("; ");
        } else {
            script.append("opj_compress -quiet -i f$i.rawl -o f$i.j2c -n ").append(levels + 1).append(" -F ")
                    .append(layout.columns()).append(',').append(layout.rows()).append(',').append(layout.samples())
                    .append(',').append(bits).append(',').append(layout.signed() ? "s" : "u").append("; for r in $(seq 0 ")
                    .append(levels).append("); do opj_decompress -quiet -i f$i.j2c -o f${i}_r$r.rawl -r $r; done; ")
                    .append("rm f$i.rawl; ");
        }
        script.append("done");
        Map<String, byte[]> out = codecs.run(inputs, script.toString());
        List<EncodedFrame> frames = new ArrayList<>();
        String extension = HTJ2K.equals(format) ? raw : ".rawl";
        for (int i = 0; i < natives.size(); i++) {
            byte[] encoded = out.get("f" + i + ".j2c");
            if (encoded == null) {
                throw new MedicalImagingCodecs.TranscodeException("The encoder wrote no frame " + i + ".");
            }
            List<long[]> checksums = new ArrayList<>();
            for (int r = levels; r >= 0; r--) {
                byte[] level = out.get("f" + i + "_r" + r + extension);
                if (level == null) {
                    continue;
                }
                int width = (layout.columns() + (1 << r) - 1) >> r;
                int height = (layout.rows() + (1 << r) - 1) >> r;
                CRC32 crc = new CRC32();
                crc.update(level);
                checksums.add(new long[] {width, height, crc.getValue()});
            }
            frames.add(new EncodedFrame(encoded, checksums, ranges.get(i)[0], ranges.get(i)[1]));
        }
        return frames;
    }

    /** A frame's samples plane by plane, as the encoders read raw input. */
    static byte[] planar(byte[] frame, MedicalImagingDicom.Pixels layout) {
        if (layout.samples() == 1 || layout.planar()) {
            return frame.clone();
        }
        int bytes = layout.bitsAllocated() / 8;
        int pixels = layout.rows() * layout.columns();
        byte[] out = new byte[frame.length];
        for (int p = 0; p < pixels; p++) {
            for (int s = 0; s < layout.samples(); s++) {
                System.arraycopy(frame, (p * layout.samples() + s) * bytes, out, (s * pixels + p) * bytes, bytes);
            }
        }
        return out;
    }

    private static byte[] interleave(byte[] planar, MedicalImagingDicom.Pixels layout) {
        if (layout.samples() == 1) {
            return planar;
        }
        int bytes = layout.bitsAllocated() / 8;
        int pixels = layout.rows() * layout.columns();
        byte[] out = new byte[planar.length];
        for (int p = 0; p < pixels; p++) {
            for (int s = 0; s < layout.samples(); s++) {
                System.arraycopy(planar, (s * pixels + p) * bytes, out, (p * layout.samples() + s) * bytes, bytes);
            }
        }
        return out;
    }

    /** Masks samples to their stored bits (sign-extending signed ones) and returns their range. */
    static long[] mask(byte[] samples, int bytes, int bits, boolean signed) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        ByteBuffer buffer = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);
        long maskBits = (1L << bits) - 1;
        for (int i = 0; i + bytes <= samples.length; i += bytes) {
            long raw = bytes == 1 ? samples[i] & 0xFF : buffer.getShort(i) & 0xFFFF;
            long value = raw & maskBits;
            if (signed && (value & (1L << (bits - 1))) != 0) {
                value -= 1L << bits;
            }
            long store = value & (bytes == 1 ? 0xFF : 0xFFFF);
            if (bytes == 1) {
                samples[i] = (byte) store;
            } else {
                buffer.putShort(i, (short) store);
            }
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new long[] {min == Long.MAX_VALUE ? 0 : min, max == Long.MIN_VALUE ? 0 : max};
    }
}
