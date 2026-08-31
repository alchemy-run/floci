package io.github.hectorvent.floci.services.devopsguru;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies the DevOps Guru restJson1 service-integration singleton:
 * describe defaults, partial update, restore, and account/region isolation.
 */
@QuarkusTest
class DevOpsGuruServiceIntegrationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeReturnsAccountDefaults() {
        given()
                .header("Authorization", auth("000000000401", EAST))
                .when()
                .get("/service-integrations")
                .then()
                .statusCode(200)
                .body("ServiceIntegration.OpsCenter.OptInStatus", equalTo("DISABLED"))
                .body("ServiceIntegration.LogsAnomalyDetection.OptInStatus", equalTo("DISABLED"))
                .body("ServiceIntegration.KMSServerSideEncryption.Type", equalTo("AWS_OWNED_KMS_KEY"))
                .body("ServiceIntegration.KMSServerSideEncryption.KMSKeyId", nullValue());
    }

    @Test
    void updateMergesOnlySuppliedSectionsAndRestoreDefaults() {
        String authorization = auth("000000000402", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"ServiceIntegration":{"LogsAnomalyDetection":{"OptInStatus":"ENABLED"}}}
                        """)
                .when()
                .put("/service-integrations")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/service-integrations")
                .then()
                .statusCode(200)
                .body("ServiceIntegration.LogsAnomalyDetection.OptInStatus", equalTo("ENABLED"))
                .body("ServiceIntegration.OpsCenter.OptInStatus", equalTo("DISABLED"))
                .body("ServiceIntegration.KMSServerSideEncryption.Type", equalTo("AWS_OWNED_KMS_KEY"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"ServiceIntegration":{"OpsCenter":{"OptInStatus":"ENABLED"}}}
                        """)
                .when()
                .put("/service-integrations")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/service-integrations")
                .then()
                .statusCode(200)
                .body("ServiceIntegration.LogsAnomalyDetection.OptInStatus", equalTo("ENABLED"))
                .body("ServiceIntegration.OpsCenter.OptInStatus", equalTo("ENABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ServiceIntegration": {
                            "OpsCenter": {"OptInStatus": "DISABLED"},
                            "LogsAnomalyDetection": {"OptInStatus": "DISABLED"}
                          }
                        }
                        """)
                .when()
                .put("/service-integrations")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/service-integrations")
                .then()
                .statusCode(200)
                .body("ServiceIntegration.OpsCenter.OptInStatus", equalTo("DISABLED"))
                .body("ServiceIntegration.LogsAnomalyDetection.OptInStatus", equalTo("DISABLED"))
                .body("ServiceIntegration.KMSServerSideEncryption.Type", equalTo("AWS_OWNED_KMS_KEY"));
    }

    @Test
    void integrationIsIsolatedByAccountAndRegion() {
        String eastA = auth("000000000403", EAST);
        String eastB = auth("000000000404", EAST);
        String westA = auth("000000000403", WEST);

        given()
                .contentType("application/json")
                .header("Authorization", eastA)
                .body("""
                        {"ServiceIntegration":{"LogsAnomalyDetection":{"OptInStatus":"ENABLED"}}}
                        """)
                .when()
                .put("/service-integrations")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", eastB)
                .when()
                .get("/service-integrations")
                .then()
                .statusCode(200)
                .body("ServiceIntegration.LogsAnomalyDetection.OptInStatus", equalTo("DISABLED"));

        given()
                .header("Authorization", westA)
                .when()
                .get("/service-integrations")
                .then()
                .statusCode(200)
                .body("ServiceIntegration.LogsAnomalyDetection.OptInStatus", equalTo("DISABLED"));
    }

    @Test
    void updateRejectsMissingServiceIntegrationAndInvalidOptIn() {
        String authorization = auth("000000000405", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .put("/service-integrations")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"ServiceIntegration":{"OpsCenter":{"OptInStatus":"MAYBE"}}}
                        """)
                .when()
                .put("/service-integrations")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/devops-guru/aws4_request";
    }
}
