package io.github.hectorvent.floci.services.translate;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 Translate ParallelData coverage used by Alchemy ParallelData:
 * create/get/list/update/delete plus tags. Import completes as {@code ACTIVE}.
 */
@QuarkusTest
class TranslateParallelDataIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/translate/aws4_request";
    private static final String TARGET_PREFIX = "AWSShineFrontendService_20170701.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getParallelData_missing_returnsResourceNotFoundException() {
        translate("GetParallelData", "{\"Name\":\"does-not-exist\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createParallelData_roundTripTagsUpdateAndDelete() {
        String name = "alchemy-test-translate-pd-" + UUID.randomUUID().toString().substring(0, 8);
        String s3Uri = "s3://alchemy-test-translate-parallel-data/examples.csv";

        translate("CreateParallelData", "{"
                + "\"Name\":\"" + name + "\","
                + "\"ClientToken\":\"" + UUID.randomUUID() + "\","
                + "\"Description\":\"alchemy translate parallel data test\","
                + "\"ParallelDataConfig\":{\"S3Uri\":\"" + s3Uri + "\",\"Format\":\"CSV\"},"
                + "\"Tags\":[{\"Key\":\"purpose\",\"Value\":\"alchemy-test\"},"
                + "{\"Key\":\"alchemy::id\",\"Value\":\"TestParallelData\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("Status", equalTo("ACTIVE"));

        String arn = translate("GetParallelData", "{\"Name\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("ParallelDataProperties.Name", equalTo(name))
                .body("ParallelDataProperties.Status", equalTo("ACTIVE"))
                .body("ParallelDataProperties.SourceLanguageCode", equalTo("en"))
                .body("ParallelDataProperties.TargetLanguageCodes", contains("es"))
                .body("ParallelDataProperties.ImportedRecordCount", greaterThan(0))
                .body("ParallelDataProperties.Arn", notNullValue())
                .extract().path("ParallelDataProperties.Arn");
        org.junit.jupiter.api.Assertions.assertTrue(arn.contains(":parallel-data/" + name));

        translate("ListParallelData", "{}")
                .then()
                .statusCode(200)
                .body("ParallelDataPropertiesList.Name", hasItem(name));

        translate("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("alchemy::id"));

        translate("TagResource", "{\"ResourceArn\":\"" + arn + "\","
                + "\"Tags\":[{\"Key\":\"extra\",\"Value\":\"1\"}]}")
                .then()
                .statusCode(200);
        translate("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("extra"));
        translate("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"extra\"]}")
                .then()
                .statusCode(200);

        translate("UpdateParallelData", "{"
                + "\"Name\":\"" + name + "\","
                + "\"ClientToken\":\"" + UUID.randomUUID() + "\","
                + "\"Description\":\"updated\","
                + "\"ParallelDataConfig\":{\"S3Uri\":\"" + s3Uri + "\",\"Format\":\"CSV\"}"
                + "}")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        translate("GetParallelData", "{\"Name\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("ParallelDataProperties.Description", equalTo("updated"))
                .body("ParallelDataProperties.Status", equalTo("ACTIVE"));

        translate("CreateParallelData", "{"
                + "\"Name\":\"" + name + "\","
                + "\"ClientToken\":\"" + UUID.randomUUID() + "\","
                + "\"ParallelDataConfig\":{\"S3Uri\":\"" + s3Uri + "\",\"Format\":\"CSV\"}"
                + "}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        translate("DeleteParallelData", "{\"Name\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("DELETING"));

        translate("GetParallelData", "{\"Name\":\"" + name + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createParallelData_missingName_returnsInvalidRequest() {
        translate("CreateParallelData",
                "{\"ClientToken\":\"tok\",\"ParallelDataConfig\":{\"S3Uri\":\"s3://b/k.csv\",\"Format\":\"CSV\"}}")
                .then()
                .statusCode(400);
    }

    private static Response translate(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
