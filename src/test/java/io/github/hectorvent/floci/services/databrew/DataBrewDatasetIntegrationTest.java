package io.github.hectorvent.floci.services.databrew;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/** Verifies DataBrew restJson1 dataset create/describe/update/delete and tags. */
@QuarkusTest
class DataBrewDatasetIntegrationTest {

    private static final String AUTH = auth("000000000401", "us-east-1");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeDatasetOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/datasets/missing-dataset")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDescribeUpdateDeleteAndTagsLifecycle() {
        String name = "sales-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {
                  "Name": "%s",
                  "Format": "CSV",
                  "FormatOptions": { "Csv": { "Delimiter": ",", "HeaderRow": true } },
                  "Input": {
                    "S3InputDefinition": { "Bucket": "raw-data", "Key": "raw/sales.csv" }
                  },
                  "Tags": { "Environment": "test" }
                }
                """.formatted(name);

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body(body)
                .when()
                .post("/datasets")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        String arn = given()
                .header("Authorization", AUTH)
                .when()
                .get("/datasets/" + name)
                .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("Format", equalTo("CSV"))
                .body("Input.S3InputDefinition.Bucket", equalTo("raw-data"))
                .body("Input.S3InputDefinition.Key", equalTo("raw/sales.csv"))
                .body("FormatOptions.Csv.Delimiter", equalTo(","))
                .body("FormatOptions.Csv.HeaderRow", equalTo(true))
                .body("Source", equalTo("S3"))
                .body("ResourceArn", notNullValue())
                .body("Tags.Environment", equalTo("test"))
                .extract()
                .path("ResourceArn");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/datasets")
                .then()
                .statusCode(200)
                .body("Datasets.find { it.Name == '" + name + "' }.Format", equalTo("CSV"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Format": "CSV",
                          "FormatOptions": { "Csv": { "Delimiter": ";", "HeaderRow": false } },
                          "Input": {
                            "S3InputDefinition": { "Bucket": "raw-data", "Key": "raw/sales-v2.csv" }
                          }
                        }
                        """)
                .when()
                .put("/datasets/" + name)
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/datasets/" + name)
                .then()
                .statusCode(200)
                .body("Input.S3InputDefinition.Key", equalTo("raw/sales-v2.csv"))
                .body("FormatOptions.Csv.Delimiter", equalTo(";"))
                .body("FormatOptions.Csv.HeaderRow", equalTo(false))
                .body("Tags.Environment", equalTo("test"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags.Environment", equalTo("test"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"Tags\":{\"Team\":\"platform\"}}")
                .when()
                .post("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags.Team", equalTo("platform"))
                .body("Tags.Environment", equalTo("test"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/tags/" + arn + "?tagKeys=Environment")
                .then()
                .statusCode(204);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags.Environment", nullValue())
                .body("Tags.Team", equalTo("platform"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body(body)
                .when()
                .post("/datasets")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/datasets/" + name)
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/datasets/" + name)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDatasetWithoutInputFailsWithValidationException() {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"Name\":\"no-input\"}")
                .when()
                .post("/datasets")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/databrew/aws4_request";
    }
}
