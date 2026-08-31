package io.github.hectorvent.floci.services.ce;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for Cost Explorer anomaly monitor JSON 1.1 operations
 * used by Alchemy {@code AWS.CostExplorer.AnomalyMonitor}.
 *
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: AWSInsightsIndexService.&lt;Action&gt;
 */
@QuarkusTest
@TestProfile(AnomalyMonitorIntegrationTest.IsolatedProfile.class)
class AnomalyMonitorIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ce/aws4_request";
    private static final String ACCOUNT = "000000000000";
    private static final String MISSING_ARN =
            "arn:aws:ce::" + ACCOUNT + ":anomalymonitor/00000000-0000-0000-0000-000000000000";

    public static final class IsolatedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.storage.mode", "memory");
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void deleteAnomalyMonitor_unknownArn_returnsUnknownMonitorException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DeleteAnomalyMonitor")
            .header("Authorization", AUTH)
            .body("{\"MonitorArn\":\"" + MISSING_ARN + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownMonitorException"));
    }

    @Test
    void getAnomalyMonitors_unknownArn_returnsEmptyList() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetAnomalyMonitors")
            .header("Authorization", AUTH)
            .body("{\"MonitorArnList\":[\"" + MISSING_ARN + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AnomalyMonitors", hasSize(0));
    }

    @Test
    void deleteAnomalyMonitor_foreignAccount_returnsAccessDenied() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DeleteAnomalyMonitor")
            .header("Authorization", AUTH)
            .body("{\"MonitorArn\":\"arn:aws:ce::999999999999:anomalymonitor/00000000-0000-0000-0000-000000000000\"}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void lifecycle_createCustom_rename_tag_delete() {
        String name = "am-lifecycle-" + UUID.randomUUID();
        String renamed = name + "-renamed";
        String createBody = "{\"AnomalyMonitor\":{"
                + "\"MonitorName\":\"" + name + "\","
                + "\"MonitorType\":\"CUSTOM\","
                + "\"MonitorSpecification\":{\"Tags\":{\"Key\":\"CostCenter\",\"Values\":[\"alchemy-test\"]}}},"
                + "\"ResourceTags\":[{\"Key\":\"fixture\",\"Value\":\"cost-explorer-anomaly-monitor\"}]}";

        String arn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.CreateAnomalyMonitor")
            .header("Authorization", AUTH)
            .body(createBody)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("MonitorArn", startsWith("arn:aws:ce::" + ACCOUNT + ":anomalymonitor/"))
            .extract().path("MonitorArn");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetAnomalyMonitors")
            .header("Authorization", AUTH)
            .body("{\"MonitorArnList\":[\"" + arn + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AnomalyMonitors", hasSize(1))
            .body("AnomalyMonitors[0].MonitorName", equalTo(name))
            .body("AnomalyMonitors[0].MonitorType", equalTo("CUSTOM"))
            .body("AnomalyMonitors[0].MonitorSpecification.Tags.Values", hasItem("alchemy-test"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.ListTagsForResource")
            .header("Authorization", AUTH)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTags.Key", hasItem("fixture"))
            .body("ResourceTags.find { it.Key == 'fixture' }.Value",
                    equalTo("cost-explorer-anomaly-monitor"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.TagResource")
            .header("Authorization", AUTH)
            .body("{\"ResourceArn\":\"" + arn + "\","
                    + "\"ResourceTags\":[{\"Key\":\"alchemy::id\",\"Value\":\"Monitor\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.UpdateAnomalyMonitor")
            .header("Authorization", AUTH)
            .body("{\"MonitorArn\":\"" + arn + "\",\"MonitorName\":\"" + renamed + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("MonitorArn", equalTo(arn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetAnomalyMonitors")
            .header("Authorization", AUTH)
            .body("{\"MonitorArnList\":[\"" + arn + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AnomalyMonitors[0].MonitorName", equalTo(renamed))
            .body("AnomalyMonitors[0].MonitorArn", equalTo(arn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DeleteAnomalyMonitor")
            .header("Authorization", AUTH)
            .body("{\"MonitorArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetAnomalyMonitors")
            .header("Authorization", AUTH)
            .body("{\"MonitorArnList\":[\"" + arn + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AnomalyMonitors", hasSize(0));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DeleteAnomalyMonitor")
            .header("Authorization", AUTH)
            .body("{\"MonitorArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownMonitorException"));
    }

    @Test
    void createAnomalyMonitor_duplicateName_returnsValidationException() {
        String name = "am-dup-" + UUID.randomUUID();
        String body = customBody(name, "alchemy-dup");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.CreateAnomalyMonitor")
            .header("Authorization", AUTH)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.CreateAnomalyMonitor")
            .header("Authorization", AUTH)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("same monitor name as an existing monitor"));
    }

    @Test
    void createAnomalyMonitor_preservesSpecificationForReplacement() {
        String first = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.CreateAnomalyMonitor")
            .header("Authorization", AUTH)
            .body(customBody("am-replace-1-" + UUID.randomUUID(), "alchemy-replace-1"))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("MonitorArn");

        String second = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.CreateAnomalyMonitor")
            .header("Authorization", AUTH)
            .body(customBody("am-replace-2-" + UUID.randomUUID(), "alchemy-replace-2"))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("MonitorArn");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetAnomalyMonitors")
            .header("Authorization", AUTH)
            .body("{\"MonitorArnList\":[\"" + second + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AnomalyMonitors[0].MonitorArn", equalTo(second))
            .body("AnomalyMonitors[0].MonitorSpecification.Tags.Values",
                    equalTo(java.util.List.of("alchemy-replace-2")));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DeleteAnomalyMonitor")
            .header("Authorization", AUTH)
            .body("{\"MonitorArn\":\"" + first + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static String customBody(String name, String costCenter) {
        return "{\"AnomalyMonitor\":{"
                + "\"MonitorName\":\"" + name + "\","
                + "\"MonitorType\":\"CUSTOM\","
                + "\"MonitorSpecification\":{\"Tags\":{\"Key\":\"CostCenter\",\"Values\":[\"" + costCenter + "\"]}}}}";
    }
}
