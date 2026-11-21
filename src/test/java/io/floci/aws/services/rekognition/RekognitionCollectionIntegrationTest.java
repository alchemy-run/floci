package io.floci.aws.services.rekognition;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/**
 * Rekognition's stored state and jobs: collections, faces, users, liveness
 * sessions, projects, stream processors, tags, stored-video jobs, and media
 * analysis jobs.
 */
@QuarkusTest
class RekognitionCollectionIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/rekognition/aws4_request";
    private static final String IMAGE =
            "{\"Bytes\":\"" + Base64.getEncoder().encodeToString(TestImages.png(100, 100)) + "\"}";
    private static final String UNKNOWN_FACE = "00000000-0000-0000-0000-000000000000";

    private static boolean videoUploaded;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** Video jobs read their S3 object, so the one they name exists. */
    @BeforeEach
    void uploadVideo() {
        if (videoUploaded) {
            return;
        }
        given().when().put("/rekognition-video-test");
        given().body(TestImages.png(100, 100)).when().put("/rekognition-video-test/clip.mp4").then().statusCode(200);
        videoUploaded = true;
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "RekognitionService." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/")
            .then();
    }

    @Test
    void aCollectionWithUsersAndFacelessImages() {
        String arn = call("CreateCollection", "{\"CollectionId\":\"people\",\"Tags\":{\"team\":\"vision\"}}")
            .statusCode(200)
            .body("StatusCode", equalTo(200))
            .body("CollectionArn", endsWith(":collection/people"))
            .extract().path("CollectionArn");
        call("CreateCollection", "{\"CollectionId\":\"people\"}")
            .statusCode(400).body("__type", equalTo("ResourceAlreadyExistsException"));
        call("DescribeCollection", "{\"CollectionId\":\"people\"}")
            .statusCode(200).body("FaceCount", equalTo(0)).body("CollectionARN", equalTo(arn));
        call("ListCollections", "{}").statusCode(200).body("CollectionIds", hasItem("people"));
        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
            .statusCode(200).body("Tags.team", equalTo("vision"));

        call("IndexFaces", "{\"CollectionId\":\"people\",\"Image\":" + IMAGE + "}")
            .statusCode(200).body("FaceRecords", hasSize(0)).body("FaceModelVersion", equalTo("7.0"));
        call("ListFaces", "{\"CollectionId\":\"people\"}").statusCode(200).body("Faces", hasSize(0));
        call("SearchFacesByImage", "{\"CollectionId\":\"people\",\"Image\":" + IMAGE + "}")
            .statusCode(400).body("__type", equalTo("InvalidParameterException"));
        call("SearchFaces", "{\"CollectionId\":\"people\",\"FaceId\":\"" + UNKNOWN_FACE + "\"}")
            .statusCode(400).body("__type", equalTo("InvalidParameterException"));
        call("DeleteFaces", "{\"CollectionId\":\"people\",\"FaceIds\":[\"" + UNKNOWN_FACE + "\"]}")
            .statusCode(200)
            .body("DeletedFaces", hasSize(0))
            .body("UnsuccessfulFaceDeletions[0].Reasons", contains("FACE_NOT_FOUND"));

        call("CreateUser", "{\"CollectionId\":\"people\",\"UserId\":\"ada\"}").statusCode(200);
        call("CreateUser", "{\"CollectionId\":\"people\",\"UserId\":\"ada\"}")
            .statusCode(400).body("__type", equalTo("ConflictException"));
        call("ListUsers", "{\"CollectionId\":\"people\"}")
            .statusCode(200).body("Users.UserId", contains("ada")).body("Users[0].UserStatus", equalTo("ACTIVE"));
        call("AssociateFaces", "{\"CollectionId\":\"people\",\"UserId\":\"ada\",\"FaceIds\":[\"" + UNKNOWN_FACE + "\"]}")
            .statusCode(200)
            .body("AssociatedFaces", hasSize(0))
            .body("UnsuccessfulFaceAssociations[0].Reasons", contains("FACE_NOT_FOUND"));
        call("SearchUsers", "{\"CollectionId\":\"people\",\"UserId\":\"ada\"}")
            .statusCode(200).body("SearchedUser.UserId", equalTo("ada")).body("UserMatches", hasSize(0));
        call("DeleteUser", "{\"CollectionId\":\"people\",\"UserId\":\"ada\"}").statusCode(200);
        call("DeleteUser", "{\"CollectionId\":\"people\",\"UserId\":\"ada\"}")
            .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));

        call("DeleteCollection", "{\"CollectionId\":\"people\"}").statusCode(200).body("StatusCode", equalTo(200));
        call("DescribeCollection", "{\"CollectionId\":\"people\"}")
            .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void livenessSessionsProjectsAndStreamProcessors() {
        String session = call("CreateFaceLivenessSession", "{}").statusCode(200).extract().path("SessionId");
        call("GetFaceLivenessSessionResults", "{\"SessionId\":\"" + session + "\"}")
            .statusCode(200).body("Status", equalTo("CREATED"));
        call("GetFaceLivenessSessionResults", "{\"SessionId\":\"" + UNKNOWN_FACE + "\"}")
            .statusCode(400).body("__type", equalTo("SessionNotFoundException"));

        String project = call("CreateProject", "{\"ProjectName\":\"widgets\"}")
            .statusCode(200).extract().path("ProjectArn");
        call("DescribeProjects", "{\"ProjectNames\":[\"widgets\"]}")
            .statusCode(200).body("ProjectDescriptions.ProjectArn", contains(project));
        call("DescribeProjectVersions", "{\"ProjectArn\":\"" + project + "\"}")
            .statusCode(200).body("ProjectVersionDescriptions", hasSize(0));
        call("DescribeProjectVersions", "{\"ProjectArn\":\"" + project + "x\"}")
            .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        call("StartProjectVersion", "{\"ProjectVersionArn\":\"" + project + "/version/v1/1\",\"MinInferenceUnits\":1}")
            .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        call("DeleteProject", "{\"ProjectArn\":\"" + project + "\"}").statusCode(200).body("Status", equalTo("DELETING"));

        String processor = """
                {"Name":"door-cam","RoleArn":"arn:aws:iam::000000000000:role/rk",
                 "Input":{"KinesisVideoStream":{"Arn":"arn:aws:kinesisvideo:us-east-1:000000000000:stream/door/1"}},
                 "Output":{"S3Destination":{"Bucket":"rekognition-video-test"}},
                 "Settings":{"ConnectedHome":{"Labels":["PERSON"]}}}""";
        call("CreateStreamProcessor", processor).statusCode(200)
            .body("StreamProcessorArn", endsWith(":streamprocessor/door-cam"));
        call("CreateStreamProcessor", processor).statusCode(400).body("__type", equalTo("ResourceInUseException"));
        call("StartStreamProcessor", "{\"Name\":\"door-cam\"}").statusCode(200).body("SessionId", notNullValue());
        call("DescribeStreamProcessor", "{\"Name\":\"door-cam\"}").statusCode(200).body("Status", equalTo("RUNNING"));
        call("ListStreamProcessors", "{}").statusCode(200).body("StreamProcessors.Name", hasItem("door-cam"));
        call("StopStreamProcessor", "{\"Name\":\"door-cam\"}").statusCode(200);
        call("DeleteStreamProcessor", "{\"Name\":\"door-cam\"}").statusCode(200);
        for (String action : List.of("DescribeStreamProcessor", "StartStreamProcessor", "StopStreamProcessor")) {
            call(action, "{\"Name\":\"door-cam\"}").statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        }
    }

    @Test
    void storedVideoJobsCheckTheirInputAndSettle() {
        String video = "{\"S3Object\":{\"Bucket\":\"rekognition-video-test\",\"Name\":\"clip.mp4\"}}";
        String missing = "{\"S3Object\":{\"Bucket\":\"no-such-bucket\",\"Name\":\"clip.mp4\"}}";
        for (String family : List.of("CelebrityRecognition", "ContentModeration", "FaceDetection", "LabelDetection",
                "PersonTracking", "TextDetection")) {
            call("Start" + family, "{\"Video\":" + missing + "}")
                .statusCode(400).body("__type", equalTo("InvalidS3ObjectException"));
            call("Get" + family, "{\"JobId\":\"" + "0".repeat(64) + "\"}")
                .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        }
        call("StartFaceSearch", "{\"Video\":" + video + ",\"CollectionId\":\"nope\"}")
            .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        call("StartSegmentDetection", "{\"Video\":" + video + "}")
            .statusCode(400).body("__type", equalTo("InvalidParameterException"));

        String jobId = call("StartLabelDetection", "{\"Video\":" + video + ",\"ClientRequestToken\":\"t1\"}")
            .statusCode(200).body("JobId", matchesPattern("^[0-9a-f]{64}$")).extract().path("JobId");
        call("StartLabelDetection", "{\"Video\":" + video + ",\"ClientRequestToken\":\"t1\"}")
            .statusCode(200).body("JobId", equalTo(jobId));
        call("GetLabelDetection", "{\"JobId\":\"" + jobId + "\"}").statusCode(200).body("JobStatus", equalTo("IN_PROGRESS"));
        call("GetTextDetection", "{\"JobId\":\"" + jobId + "\"}")
            .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                call("GetLabelDetection", "{\"JobId\":\"" + jobId + "\"}")
                    .statusCode(200)
                    .body("JobStatus", equalTo("SUCCEEDED"))
                    .body("Labels", hasSize(0))
                    .body("VideoMetadata[0].Codec", equalTo("h264")));

        call("StartMediaAnalysisJob", """
                {"OperationsConfig":{"DetectModerationLabels":{"MinConfidence":60}},
                 "Input":{"S3Object":{"Bucket":"no-such-bucket","Name":"manifest.jsonl"}},
                 "OutputConfig":{"S3Bucket":"rekognition-video-test","S3KeyPrefix":"out/"}}""")
            .statusCode(400).body("__type", equalTo("InvalidS3ObjectException"));
        String media = call("StartMediaAnalysisJob", """
                {"JobName":"moderate","OperationsConfig":{"DetectModerationLabels":{"MinConfidence":60}},
                 "Input":{"S3Object":{"Bucket":"rekognition-video-test","Name":"clip.mp4"}},
                 "OutputConfig":{"S3Bucket":"rekognition-video-test","S3KeyPrefix":"out/"}}""")
            .statusCode(200).extract().path("JobId");
        call("ListMediaAnalysisJobs", "{\"MaxResults\":10}")
            .statusCode(200).body("MediaAnalysisJobs.JobId", hasItem(media));
        call("GetMediaAnalysisJob", "{\"JobId\":\"" + "0".repeat(64) + "\"}")
            .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void imageAnalysisAdditions() {
        call("DetectProtectiveEquipment", "{\"Image\":" + IMAGE + "}")
            .statusCode(200).body("Persons", hasSize(0));
        call("RecognizeCelebrities", "{\"Image\":" + IMAGE + "}")
            .statusCode(200).body("CelebrityFaces", hasSize(0));
        call("GetCelebrityInfo", "{\"Id\":\"0000000000\"}")
            .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        call("CompareFaces", "{\"SourceImage\":" + IMAGE + ",\"TargetImage\":" + IMAGE + "}")
            .statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }
}
