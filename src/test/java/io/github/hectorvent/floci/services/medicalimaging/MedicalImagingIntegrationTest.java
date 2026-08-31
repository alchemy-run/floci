package io.github.hectorvent.floci.services.medicalimaging;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * HealthImaging restJson1 coverage used by Alchemy Datastore.test.ts:
 * getDatastore on a missing id returns ResourceNotFoundException; create,
 * tag, and delete round-trip.
 */
@QuarkusTest
class MedicalImagingIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING = "00000000000000000000000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDatastoreOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/datastore/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getImageSetOnANonexistentDatastoreFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .when()
                .post("/datastore/" + MISSING + "/imageSet/" + MISSING + "/getImageSet")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void imageSetAndImportJobRoutesOnANonexistentDatastoreFailWithResourceNotFoundException() {
        String authorization = auth(EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/datastore/" + MISSING + "/imageSet/" + MISSING + "/getImageSetMetadata")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"imageFrameId\":\"" + MISSING + "\"}")
                .when()
                .post("/datastore/" + MISSING + "/imageSet/" + MISSING + "/getImageFrame")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/datastore/" + MISSING + "/searchImageSets")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/datastore/" + MISSING + "/imageSet/" + MISSING + "/listImageSetVersions")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .queryParam("latestVersion", "1")
                .body("{\"revertToVersionId\":\"1\"}")
                .when()
                .post("/datastore/" + MISSING + "/imageSet/" + MISSING + "/updateImageSetMetadata")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"sourceImageSet\":{\"latestVersionId\":\"1\"}}")
                .when()
                .post("/datastore/" + MISSING + "/imageSet/" + MISSING + "/copyImageSet")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/datastore/" + MISSING + "/imageSet/" + MISSING + "/deleteImageSet")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/getDICOMImportJob/datastore/" + MISSING + "/job/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/listDICOMImportJobs/datastore/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "clientToken":"alchemy-medicalimaging-probe",
                          "dataAccessRoleArn":"arn:aws:iam::000000000000:role/alchemy-probe-nonexistent",
                          "inputS3Uri":"s3://alchemy-probe-nonexistent/in/",
                          "outputS3Uri":"s3://alchemy-probe-nonexistent/out/"
                        }
                        """)
                .when()
                .post("/startDICOMImportJob/datastore/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetTagAndDeleteDatastoreLifecycle() {
        String authorization = auth(EAST);

        String datastoreId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "datastoreName":"alchemy-mi-store",
                          "clientToken":"alchemy-mi-create-1",
                          "tags":{"fixture":"medical-imaging"}
                        }
                        """)
                .when()
                .post("/datastore")
                .then()
                .statusCode(200)
                .body("datastoreId", notNullValue())
                .body("datastoreStatus", equalTo("ACTIVE"))
                .extract()
                .path("datastoreId");

        String datastoreArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/datastore/" + datastoreId)
                .then()
                .statusCode(200)
                .body("datastoreProperties.datastoreId", equalTo(datastoreId))
                .body("datastoreProperties.datastoreName", equalTo("alchemy-mi-store"))
                .body("datastoreProperties.datastoreStatus", equalTo("ACTIVE"))
                .body("datastoreProperties.datastoreArn", notNullValue())
                .extract()
                .path("datastoreProperties.datastoreArn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/datastore")
                .then()
                .statusCode(200)
                .body("datastoreSummaries.find { it.datastoreId == '" + datastoreId + "' }.datastoreName",
                        equalTo("alchemy-mi-store"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(datastoreArn))
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("medical-imaging"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"extra\":\"added\"}}")
                .when()
                .post("/tags/" + encode(datastoreArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(datastoreArn))
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("medical-imaging"))
                .body("tags.extra", equalTo("added"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/tags/" + encode(datastoreArn) + "?tagKeys=extra")
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/datastore/" + datastoreId)
                .then()
                .statusCode(200)
                .body("datastoreId", equalTo(datastoreId))
                .body("datastoreStatus", equalTo("DELETING"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/datastore/" + datastoreId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/medical-imaging/aws4_request";
    }
}
