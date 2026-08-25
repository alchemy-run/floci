package io.github.hectorvent.floci.services.b2bi;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class B2biCapabilityIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/b2bi/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void transformerAndCapabilityLifecycle() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String transformerName = "floci-b2bi-cap-transformer-" + suffix;
        String capabilityName = "floci-b2bi-capability-" + suffix;

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.ListTransformers")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("transformers", org.hamcrest.Matchers.notNullValue());

        String transformerId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.CreateTransformer")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "name": "%s",
                          "inputConversion": {
                            "fromFormat": "X12",
                            "formatOptions": {
                              "x12": { "transactionSet": "X12_850", "version": "VERSION_4010" }
                            }
                          },
                          "mapping": {
                            "templateLanguage": "JSONATA",
                            "template": "{ \\"orderId\\": \\"test\\" }"
                          },
                          "tags": [{"Key":"env","Value":"test"}]
                        }
                        """.formatted(transformerName))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("transformerId", startsWith("tr-"))
                .body("name", equalTo(transformerName))
                .body("status", equalTo("inactive"))
                .extract().path("transformerId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.UpdateTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + transformerId + "\",\"status\":\"active\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("status", equalTo("active"))
                .body("transformerId", equalTo(transformerId));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.UpdateTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + transformerId + "\",\"name\":\"nope\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        String capabilityId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.CreateCapability")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "name": "%s",
                          "type": "edi",
                          "configuration": {
                            "edi": {
                              "capabilityDirection": "INBOUND",
                              "type": {
                                "x12Details": {
                                  "transactionSet": "X12_850",
                                  "version": "VERSION_4010"
                                }
                              },
                              "inputLocation": { "bucketName": "bucket", "key": "inbound/" },
                              "outputLocation": { "bucketName": "bucket", "key": "processed/" },
                              "transformerId": "%s"
                            }
                          }
                        }
                        """.formatted(capabilityName, transformerId))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("capabilityId", startsWith("ca-"))
                .body("name", equalTo(capabilityName))
                .body("type", equalTo("edi"))
                .body("configuration.edi.capabilityDirection", equalTo("INBOUND"))
                .body("configuration.edi.inputLocation.key", equalTo("inbound/"))
                .extract().path("capabilityId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetCapability")
                .header("Authorization", AUTH)
                .body("{\"capabilityId\":\"" + capabilityId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("capabilityId", equalTo(capabilityId))
                .body("configuration.edi.transformerId", equalTo(transformerId));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.ListCapabilities")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("capabilities.capabilityId", org.hamcrest.Matchers.hasItem(capabilityId));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.DeleteCapability")
                .header("Authorization", AUTH)
                .body("{\"capabilityId\":\"" + capabilityId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetCapability")
                .header("Authorization", AUTH)
                .body("{\"capabilityId\":\"" + capabilityId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.DeleteTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + transformerId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }
}
