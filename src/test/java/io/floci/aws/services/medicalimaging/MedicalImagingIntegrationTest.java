package io.floci.aws.services.medicalimaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HealthImaging: data stores; DICOM import jobs from S3 (parsed files, grouped into image sets by series, with failures
 * and output manifests); image set versions from imports, metadata updates, reverts and copies; search; deletes; tags;
 * and the events each change sends. These imports carry no pixel data; frames are imported in
 * {@link MedicalImagingDockerIntegrationTest}.
 */
@QuarkusTest
class MedicalImagingIntegrationTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String STUDY = "1.2.826.0.1.3680043.8.498.1";
    static final String SERIES = "1.2.826.0.1.3680043.8.498.1.1";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static RequestSpecification mi() {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/medical-imaging/aws4_request")
                .contentType("application/json");
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static void bucket(String name) {
        given().header("Authorization", auth("s3")).put("/" + name).then().statusCode(200);
    }

    static void put(String bucket, String key, byte[] body) {
        given().header("Authorization", auth("s3")).contentType("application/dicom").body(body)
                .put("/" + bucket + "/" + key).then().statusCode(200);
    }

    static String get(String bucket, String key) {
        return given().header("Authorization", auth("s3")).get("/" + bucket + "/" + key).then().statusCode(200)
                .extract().asString();
    }

    static String role(String name, String service) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"" + service + "\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    static String datastore(String name, String format) {
        String id = mi().body("{\"datastoreName\":\"" + name + "\",\"clientToken\":\"" + name + "\""
                        + (format == null ? "" : ",\"losslessStorageFormat\":\"" + format + "\"") + "}")
                .post("/datastore").then().statusCode(200).body("datastoreStatus", equalTo("CREATING")).extract()
                .path("datastoreId");
        await().atMost(Duration.ofSeconds(10)).until(() -> "ACTIVE".equals(mi().get("/datastore/" + id).then()
                .extract().path("datastoreProperties.datastoreStatus")));
        return id;
    }

    static String importJob(String datastore, String role, String bucket, String prefix) {
        String job = mi().body("{\"jobName\":\"" + prefix + "\",\"dataAccessRoleArn\":\"" + role + "\",\"clientToken\":"
                        + "\"" + datastore + prefix + Math.abs(role.hashCode()) + "\",\"inputS3Uri\":\"s3://" + bucket + "/" + prefix + "/\","
                        + "\"outputS3Uri\":\"s3://" + bucket + "/out/\"}")
                .post("/startDICOMImportJob/datastore/" + datastore).then().statusCode(200)
                .body("jobStatus", equalTo("SUBMITTED")).extract().path("jobId");
        await().atMost(Duration.ofSeconds(60)).until(() -> List.of("COMPLETED", "FAILED").contains(mi()
                .get("/getDICOMImportJob/datastore/" + datastore + "/job/" + job).then().extract()
                .path("jobProperties.jobStatus")));
        return job;
    }

    static JsonNode search(String datastore, String filters) {
        try {
            return MAPPER.readTree(mi().body(filters == null ? "{}" : "{\"filters\":" + filters + "}")
                    .post("/datastore/" + datastore + "/searchImageSets").then().statusCode(200).extract().asString());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    static JsonNode metadata(String datastore, String imageSet, String version) {
        Response response = mi().queryParam("version", version).post("/datastore/" + datastore + "/imageSet/"
                + imageSet + "/getImageSetMetadata");
        response.then().statusCode(200).header("Content-Encoding", "gzip");
        byte[] body = response.asByteArray();
        try {
            // RestAssured inflates a gzip Content-Encoding itself; a raw client gets the gzip bytes
            if (body.length > 1 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    body = gzip.readAllBytes();
                }
            }
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    static String state(String datastore, String imageSet) {
        return mi().post("/datastore/" + datastore + "/imageSet/" + imageSet + "/getImageSet").then().statusCode(200)
                .extract().path("imageSetState");
    }

    static void awaitActive(String datastore, String imageSet) {
        await().atMost(Duration.ofSeconds(10)).until(() -> "ACTIVE".equals(state(datastore, imageSet)));
    }

    /** An instance without pixel data (a key object selection), explicit or implicit VR. */
    static byte[] document(String patient, String sop, boolean implicit) {
        Map<Integer, DicomFiles.Value> elements = DicomFiles.instance(patient, "Doe^" + patient, STUDY, SERIES, sop,
                "KO");
        return DicomFiles.write(elements, implicit ? MedicalImagingDicom.IMPLICIT_LE : MedicalImagingDicom.EXPLICIT_LE);
    }

    @Test
    void datastoresAndErrors() {
        mi().get("/datastore/00000000000000000000000000000000").then().statusCode(404)
                .header("x-amzn-ErrorType", containsString("ResourceNotFoundException"));
        mi().get("/datastore/not-an-id").then().statusCode(400);
        mi().body("{\"datastoreId\":\"x\"}").post("/datastore/0123456789abcdef0123456789abcdef/searchImageSets").then()
                .statusCode(404);
        mi().get("/listDICOMImportJobs/datastore/0123456789abcdef0123456789abcdef").then().statusCode(404);
        String id = datastore("stores", null);
        String again = mi().body("{\"datastoreName\":\"stores\",\"clientToken\":\"stores\"}").post("/datastore").then()
                .statusCode(200).extract().path("datastoreId");
        assertEquals(id, again);
        mi().get("/datastore/" + id).then().statusCode(200).body("datastoreProperties.datastoreName", equalTo("stores"))
                .body("datastoreProperties.losslessStorageFormat", equalTo("HTJ2K"))
                .body("datastoreProperties.datastoreArn", containsString(":datastore/" + id));
        mi().queryParam("datastoreStatus", "ACTIVE").get("/datastore").then().statusCode(200)
                .body("datastoreSummaries.datastoreId", hasItem(id));
        String arn = mi().get("/datastore/" + id).then().extract().path("datastoreProperties.datastoreArn");
        mi().body("{\"tags\":{\"team\":\"radiology\",\"x\":\"y\"}}").post("/tags/" + arn).then().statusCode(200);
        mi().queryParam("tagKeys", "x").delete("/tags/" + arn).then().statusCode(200);
        mi().get("/tags/" + arn).then().statusCode(200).body("tags.team", equalTo("radiology"))
                .body("tags.x", equalTo(null));
        mi().delete("/datastore/" + id).then().statusCode(200).body("datastoreStatus", equalTo("DELETING"));
        await().atMost(Duration.ofSeconds(10)).until(() -> mi().get("/datastore/" + id).statusCode() == 404);
    }

    @Test
    void importsVersionsUpdatesCopiesAndDeletes() throws IOException {
        bucket("mi-input");
        String queue = MedicalImagingEvents.queue("mi-events");
        String role = role("mi-import", "medical-imaging.amazonaws.com");
        String id = datastore("imports", null);
        put("mi-input", "first/a.dcm", document("P1", STUDY + ".1.1", false));
        put("mi-input", "first/b.dcm", document("P1", STUDY + ".1.2", true));
        put("mi-input", "first/junk.dcm", "not dicom".getBytes(StandardCharsets.UTF_8));
        String job = importJob(id, role, "mi-input", "first");
        mi().get("/getDICOMImportJob/datastore/" + id + "/job/" + job).then()
                .body("jobProperties.jobStatus", equalTo("COMPLETED"))
                .body("jobProperties.jobName", equalTo("first"));
        mi().get("/listDICOMImportJobs/datastore/" + id).then().body("jobSummaries.jobId", hasItem(job));
        JsonNode manifest = MAPPER.readTree(get("mi-input", "out/" + job + "/job-output-manifest.json"));
        assertEquals(3, manifest.path("jobSummary").path("numberOfScannedFiles").asInt(), manifest.toString());
        assertEquals(2, manifest.path("jobSummary").path("numberOfImportedFiles").asInt(), manifest.toString());
        assertEquals(1, manifest.path("jobSummary").path("numberOfFilesWithCustomerError").asInt());
        assertTrue(get("mi-input", "out/" + job + "/FAILURE/failure.ndjson").contains("first/junk.dcm"));
        assertTrue(get("mi-input", "out/" + job + "/SUCCESS/success.ndjson").contains("first/b.dcm"));

        JsonNode found = search(id, "[{\"operator\":\"EQUAL\",\"values\":[{\"DICOMStudyInstanceUID\":\"" + STUDY
                + "\"}]}]");
        assertEquals(1, found.path("imageSetsMetadataSummaries").size(), found.toString());
        JsonNode summary = found.path("imageSetsMetadataSummaries").path(0);
        assertEquals("P1", summary.path("DICOMTags").path("DICOMPatientId").asText());
        assertEquals(2, summary.path("DICOMTags").path("DICOMNumberOfStudyRelatedInstances").asInt());
        assertEquals(7, summary.path("DICOMTags").path("DICOMSeriesNumber").asInt());
        assertTrue(summary.path("isPrimary").asBoolean());
        String imageSet = summary.path("imageSetId").asText();
        JsonNode doc = metadata(id, imageSet, "1");
        assertEquals("Doe^P1", doc.path("Patient").path("DICOM").path("PatientName").asText(), doc.toString());
        assertEquals("Floci study", doc.path("Study").path("DICOM").path("StudyDescription").asText());
        assertEquals("KO", doc.path("Study").path("Series").path(SERIES).path("DICOM").path("Modality").asText());
        assertEquals(2, doc.path("Study").path("Series").path(SERIES).path("Instances").size());
        assertEquals("1", doc.path("Study").path("Series").path(SERIES).path("Instances").path(STUDY + ".1.2")
                .path("DICOM").path("InstanceNumber").asText(), doc.toString());

        put("mi-input", "second/c.dcm", document("P1", STUDY + ".1.3", false));
        put("mi-input", "second/a-changed.dcm", document("P2", STUDY + ".1.1", false));
        importJob(id, role, "mi-input", "second");
        mi().post("/datastore/" + id + "/imageSet/" + imageSet + "/getImageSet").then().body("versionId", equalTo("2"))
                .body("imageSetWorkflowStatus", equalTo("IMPORTED"));
        assertEquals(3, metadata(id, imageSet, "2").path("Study").path("Series").path(SERIES).path("Instances").size());
        JsonNode all = search(id, "[{\"operator\":\"EQUAL\",\"values\":[{\"isPrimary\":false}]}]");
        assertEquals(1, all.path("imageSetsMetadataSummaries").size(), all.toString());
        String conflict = all.path("imageSetsMetadataSummaries").path(0).path("imageSetId").asText();

        String updates = Base64.getEncoder().encodeToString(("{\"SchemaVersion\":1.1,\"Study\":{\"DICOM\":{"
                + "\"StudyDescription\":\"Updated\"}}}").getBytes(StandardCharsets.UTF_8));
        mi().queryParam("latestVersion", "1").body("{\"DICOMUpdates\":{\"updatableAttributes\":\"" + updates + "\"}}")
                .post("/datastore/" + id + "/imageSet/" + imageSet + "/updateImageSetMetadata").then()
                .statusCode(409);
        String uid = Base64.getEncoder().encodeToString(("{\"SchemaVersion\":1.1,\"Study\":{\"DICOM\":{"
                + "\"StudyInstanceUID\":\"1.2.3\"}}}").getBytes(StandardCharsets.UTF_8));
        mi().queryParam("latestVersion", "2").body("{\"DICOMUpdates\":{\"updatableAttributes\":\"" + uid + "\"}}")
                .post("/datastore/" + id + "/imageSet/" + imageSet + "/updateImageSetMetadata").then()
                .statusCode(400);
        mi().queryParam("latestVersion", "2").body("{\"DICOMUpdates\":{\"updatableAttributes\":\"" + updates + "\"}}")
                .post("/datastore/" + id + "/imageSet/" + imageSet + "/updateImageSetMetadata").then().statusCode(200)
                .body("imageSetState", equalTo("LOCKED")).body("latestVersionId", equalTo("3"));
        awaitActive(id, imageSet);
        assertEquals("Updated", metadata(id, imageSet, "3").path("Study").path("DICOM").path("StudyDescription")
                .asText());
        mi().queryParam("latestVersion", "3").body("{\"revertToVersionId\":\"1\"}")
                .post("/datastore/" + id + "/imageSet/" + imageSet + "/updateImageSetMetadata").then().statusCode(200);
        awaitActive(id, imageSet);
        assertEquals(metadata(id, imageSet, "1").path("Study"), metadata(id, imageSet, "4").path("Study"));
        mi().post("/datastore/" + id + "/imageSet/" + imageSet + "/listImageSetVersions").then().statusCode(200)
                .body("imageSetPropertiesList", hasSize(4)).body("imageSetPropertiesList[0].versionId", equalTo("4"));

        Map<String, Object> copy = mi().body("{\"sourceImageSet\":{\"latestVersionId\":\"4\"}}")
                .post("/datastore/" + id + "/imageSet/" + imageSet + "/copyImageSet").then().statusCode(200)
                .body("destinationImageSetProperties.imageSetState", equalTo("LOCKED")).extract().path("$");
        String copied = (String) ((Map<?, ?>) copy.get("destinationImageSetProperties")).get("imageSetId");
        awaitActive(id, copied);
        awaitActive(id, imageSet);
        mi().post("/datastore/" + id + "/imageSet/" + copied + "/getImageSet").then().body("isPrimary", equalTo(false))
                .body("imageSetWorkflowStatus", equalTo("COPIED"));
        assertEquals(2, metadata(id, copied, "1").path("Study").path("Series").path(SERIES).path("Instances").size());

        for (String doomed : List.of(copied, conflict, imageSet)) {
            mi().post("/datastore/" + id + "/imageSet/" + doomed + "/deleteImageSet").then().statusCode(200)
                    .body("imageSetWorkflowStatus", equalTo("DELETING"));
        }
        mi().delete("/datastore/" + id).then().statusCode(409);
        await().atMost(Duration.ofSeconds(10)).until(() -> List.of(copied, conflict, imageSet).stream().allMatch(
                doomed -> "DELETED".equals(state(id, doomed))));
        mi().post("/datastore/" + id + "/imageSet/" + imageSet + "/getImageSetMetadata").then().statusCode(404);
        assertEquals(0, search(id, null).path("imageSetsMetadataSummaries").size());

        // every change was sent: the wait ends once they all arrived
        MedicalImagingEvents.detailTypes(queue, List.of("Data Store Creating", "Data Store Created",
                "Import Job Submitted", "Import Job In Progress", "Import Job Completed", "Image Set Created",
                "Image Set Updating", "Image Set Updated", "Image Set Copying", "Image Set Copied",
                "Image Set Deleting", "Image Set Deleted"));
        await().atMost(Duration.ofSeconds(10)).until(() -> mi().delete("/datastore/" + id).statusCode() == 200);
    }

    @Test
    void jobsFailWithoutAUsableRole() {
        bucket("mi-roles");
        put("mi-roles", "in/a.dcm", document("P9", "1.2.3.9", false));
        String id = datastore("roles", null);
        String untrusted = role("mi-untrusted", "lambda.amazonaws.com");
        String job = importJob(id, untrusted, "mi-roles", "in");
        mi().get("/getDICOMImportJob/datastore/" + id + "/job/" + job).then()
                .body("jobProperties.jobStatus", equalTo("FAILED"))
                .body("jobProperties.message", containsString("doesn't trust"));
        mi().body("{\"dataAccessRoleArn\":\"not-an-arn\",\"clientToken\":\"t\",\"inputS3Uri\":\"s3://b/\","
                + "\"outputS3Uri\":\"s3://b/o/\"}").post("/startDICOMImportJob/datastore/" + id).then().statusCode(400);
        String missing = "arn:aws:iam::000000000000:role/mi-missing";
        String failed = importJob(id, missing, "mi-roles", "in");
        mi().get("/getDICOMImportJob/datastore/" + id + "/job/" + failed).then()
                .body("jobProperties.jobStatus", equalTo("FAILED"));
        mi().queryParam("jobStatus", "FAILED").get("/listDICOMImportJobs/datastore/" + id).then()
                .body("jobSummaries", hasSize(2)).body("jobSummaries.jobStatus", not(hasItem("COMPLETED")));
        assertFalse(missing.isEmpty());
        mi().delete("/datastore/" + id).then().statusCode(200);
    }
}
