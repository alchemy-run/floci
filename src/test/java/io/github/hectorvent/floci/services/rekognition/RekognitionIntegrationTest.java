package io.github.hectorvent.floci.services.rekognition;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the Amazon Rekognition stub.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: RekognitionService.&lt;Action&gt;
 */
@QuarkusTest
class RekognitionIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/rekognition/aws4_request";
    private static final String IMAGE_BODY =
            "{\"Image\":{\"Bytes\":\"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==\"}}";
    private static final String BOGUS_JOB_ID = "0".repeat(64);
    private static final String BOGUS_VIDEO =
            "{\"Video\":{\"S3Object\":{\"Bucket\":\"alchemy-nonexistent-rekognition-test-bucket\",\"Name\":\"video.mp4\"}}}";

    @Test
    void detectLabels_returnsSkyLabel() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectLabels")
            .header("Authorization", AUTH_HEADER)
            .body(IMAGE_BODY)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Labels.Name", hasItem("Sky"))
            .body("Labels.size()", greaterThan(0));
    }

    @Test
    void detectFaces_returnsEmptyFaceDetails() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectFaces")
            .header("Authorization", AUTH_HEADER)
            .body(IMAGE_BODY)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FaceDetails", empty());
    }

    @Test
    void remainingImageOps_returnEmptyDetections() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectModerationLabels")
            .header("Authorization", AUTH_HEADER)
            .body(IMAGE_BODY)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModerationLabels", empty());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectText")
            .header("Authorization", AUTH_HEADER)
            .body(IMAGE_BODY)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TextDetections", empty());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectProtectiveEquipment")
            .header("Authorization", AUTH_HEADER)
            .body(IMAGE_BODY)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Persons", empty());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.RecognizeCelebrities")
            .header("Authorization", AUTH_HEADER)
            .body(IMAGE_BODY)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CelebrityFaces", empty());
    }

    @Test
    void compareFaces_noFaces_returnsInvalidParameterException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.CompareFaces")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SourceImage\":{\"Bytes\":\"QQ==\"},\"TargetImage\":{\"Bytes\":\"QQ==\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void getCelebrityInfo_unknownId_returnsResourceNotFoundException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.GetCelebrityInfo")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Id\":\"0000000000\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void collectionLifecycle_createDescribeListIndexUsersDelete() {
        String collectionId = "alchemy-test-rekognition-bindings";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DeleteCollection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId + "\"}")
        .when()
            .post("/");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.CreateCollection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StatusCode", equalTo(200));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DescribeCollection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FaceCount", equalTo(0));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.ListCollections")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CollectionIds", hasItem(collectionId));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.IndexFaces")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId + "\",\"Image\":{\"Bytes\":\"QQ==\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FaceRecords", empty());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.CreateUser")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId + "\",\"UserId\":\"test-user\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.ListUsers")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Users.UserId", hasItem("test-user"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.SearchFaces")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId
                    + "\",\"FaceId\":\"00000000-0000-0000-0000-000000000000\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.SearchFacesByImage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId + "\",\"Image\":{\"Bytes\":\"QQ==\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.ListFaces")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Faces", empty());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.SearchUsers")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId + "\",\"UserId\":\"test-user\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("UserMatches", empty());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.AssociateFaces")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId
                    + "\",\"UserId\":\"test-user\",\"FaceIds\":[\"00000000-0000-0000-0000-000000000000\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("UnsuccessfulFaceAssociations", not(empty()));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DisassociateFaces")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId
                    + "\",\"UserId\":\"test-user\",\"FaceIds\":[\"00000000-0000-0000-0000-000000000000\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("UnsuccessfulFaceDisassociations", not(empty()));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.SearchUsersByImage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId + "\",\"Image\":{\"Bytes\":\"QQ==\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DeleteFaces")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId
                    + "\",\"FaceIds\":[\"00000000-0000-0000-0000-000000000000\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("UnsuccessfulFaceDeletions", not(empty()));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DeleteUser")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId + "\",\"UserId\":\"test-user\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DeleteCollection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CollectionId\":\"" + collectionId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void faceLiveness_createAndGetCreatedStatus_unknownSessionNotFound() {
        String sessionId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.CreateFaceLivenessSession")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SessionId", not(emptyOrNullString()))
            .extract().path("SessionId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.GetFaceLivenessSessionResults")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SessionId\":\"" + sessionId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Status", equalTo("CREATED"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.GetFaceLivenessSessionResults")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SessionId\":\"00000000-0000-0000-0000-000000000000\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SessionNotFoundException"));
    }

    @Test
    void startLabelDetection_missingS3Object_returnsInvalidS3ObjectException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.StartLabelDetection")
            .header("Authorization", AUTH_HEADER)
            .body(BOGUS_VIDEO)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidS3ObjectException"));
    }

    @Test
    void startFaceSearch_unknownCollection_returnsResourceNotFoundException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.StartFaceSearch")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Video\":{\"S3Object\":{\"Bucket\":\"alchemy-nonexistent-rekognition-test-bucket\",\"Name\":\"video.mp4\"}},"
                    + "\"CollectionId\":\"alchemy-nonexistent-collection\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getLabelDetection_unknownJobId_returnsResourceNotFoundException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.GetLabelDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + BOGUS_JOB_ID + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void remainingVideoStartAndGet_typedS3AndNotFound() {
        for (String start : new String[] {
                "StartCelebrityRecognition",
                "StartContentModeration",
                "StartFaceDetection",
                "StartPersonTracking",
                "StartSegmentDetection",
                "StartTextDetection"
        }) {
            String body = start.equals("StartSegmentDetection")
                    ? "{\"Video\":{\"S3Object\":{\"Bucket\":\"alchemy-nonexistent-rekognition-test-bucket\",\"Name\":\"video.mp4\"}},\"SegmentTypes\":[\"SHOT\"]}"
                    : BOGUS_VIDEO;
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "RekognitionService." + start)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidS3ObjectException"));
        }

        for (String get : new String[] {
                "GetCelebrityRecognition",
                "GetContentModeration",
                "GetFaceDetection",
                "GetFaceSearch",
                "GetPersonTracking",
                "GetSegmentDetection",
                "GetTextDetection"
        }) {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "RekognitionService." + get)
                .header("Authorization", AUTH_HEADER)
                .body("{\"JobId\":\"" + BOGUS_JOB_ID + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        }
    }

    @Test
    void mediaAnalysis_listAndTypedErrors() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.ListMediaAnalysisJobs")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("MediaAnalysisJobs", notNullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.StartMediaAnalysisJob")
            .header("Authorization", AUTH_HEADER)
            .body("{\"OperationsConfig\":{\"DetectModerationLabels\":{\"MinConfidence\":60}},"
                    + "\"Input\":{\"S3Object\":{\"Bucket\":\"alchemy-nonexistent-rekognition-test-bucket\",\"Name\":\"manifest.jsonl\"}},"
                    + "\"OutputConfig\":{\"S3Bucket\":\"alchemy-nonexistent-rekognition-test-bucket\",\"S3KeyPrefix\":\"out/\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidS3ObjectException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.GetMediaAnalysisJob")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + BOGUS_JOB_ID + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void streamProcessors_listAndNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.ListStreamProcessors")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamProcessors", notNullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DescribeStreamProcessor")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Name\":\"alchemy-nonexistent-processor\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.StartStreamProcessor")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Name\":\"alchemy-nonexistent-processor\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.StopStreamProcessor")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Name\":\"alchemy-nonexistent-processor\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void customLabels_describeProjectsAndTypedNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DescribeProjects")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ProjectDescriptions", notNullValue());

        String projectArn = "arn:aws:rekognition:us-east-1:123456789012:project/alchemy-nonexistent/1700000000000";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DescribeProjectVersions")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ProjectArn\":\"" + projectArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        String versionArn = projectArn + "/version/alchemy-nonexistent/1700000000000";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectCustomLabels")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ProjectVersionArn\":\"" + versionArn + "\",\"Image\":{\"Bytes\":\"QQ==\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.StartProjectVersion")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ProjectVersionArn\":\"" + versionArn + "\",\"MinInferenceUnits\":1}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.StopProjectVersion")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ProjectVersionArn\":\"" + versionArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "RekognitionService.DetectSentiment")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
