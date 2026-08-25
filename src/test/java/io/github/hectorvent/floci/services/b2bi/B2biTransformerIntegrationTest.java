package io.github.hectorvent.floci.services.b2bi;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Transformer CRUD matching Alchemy's B2BI Transformer resource:
 * create (inactive), in-place mapping update, activate, reject updates while
 * active, delete-first replacement, and delete while active.
 */
@QuarkusTest
class B2biTransformerIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/b2bi/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateActivateRejectThenDeleteWhileActive() {
        String name = "floci-b2bi-transformer-" + UUID.randomUUID().toString().substring(0, 8);
        String createBody = """
                {
                  "name": "%s",
                  "inputConversion": {
                    "fromFormat": "X12",
                    "formatOptions": { "x12": { "transactionSet": "X12_850", "version": "VERSION_4010" } }
                  },
                  "mapping": { "templateLanguage": "JSONATA", "template": "{ \\"orderId\\": \\"test\\" }" },
                  "tags": [{ "Key": "env", "Value": "test" }]
                }
                """.formatted(name);

        String transformerId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.CreateTransformer")
                .header("Authorization", AUTH)
                .body(createBody)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("transformerId", startsWith("tr-"))
                .body("name", equalTo(name))
                .body("status", equalTo("inactive"))
                .extract().path("transformerId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + transformerId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("name", equalTo(name))
                .body("status", equalTo("inactive"))
                .body("mapping.template", equalTo("{ \"orderId\": \"test\" }"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.ListTransformers")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("transformers.transformerId", hasItem(transformerId));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.UpdateTransformer")
                .header("Authorization", AUTH)
                .body("""
                        {"transformerId":"%s",
                         "mapping":{"templateLanguage":"JSONATA","template":"{ \\"orderId\\": \\"updated\\" }"}}
                        """.formatted(transformerId))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("transformerId", equalTo(transformerId))
                .body("status", equalTo("inactive"))
                .body("mapping.template", equalTo("{ \"orderId\": \"updated\" }"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.UpdateTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + transformerId + "\",\"status\":\"active\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("status", equalTo("active"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.UpdateTransformer")
                .header("Authorization", AUTH)
                .body("""
                        {"transformerId":"%s",
                         "mapping":{"templateLanguage":"JSONATA","template":"{ \\"orderId\\": \\"replaced\\" }"}}
                        """.formatted(transformerId))
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        String replacementId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.CreateTransformer")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "name": "%s-next",
                          "inputConversion": {
                            "fromFormat": "X12",
                            "formatOptions": { "x12": { "transactionSet": "X12_850", "version": "VERSION_4010" } }
                          },
                          "mapping": { "templateLanguage": "JSONATA", "template": "{ \\"orderId\\": \\"replaced\\" }" }
                        }
                        """.formatted(name))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("transformerId", not(equalTo(transformerId)))
                .body("status", equalTo("inactive"))
                .extract().path("transformerId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.DeleteTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + transformerId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + transformerId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.UpdateTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + replacementId + "\",\"status\":\"active\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("status", equalTo("active"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.DeleteTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + replacementId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + replacementId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }
}
