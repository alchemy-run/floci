package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CloudWatch dashboard lifecycle over JSON 1.0
 * ({@code Content-Type: application/x-amz-json-1.0},
 * {@code X-Amz-Target: GraniteServiceVersion20100801.&lt;Action&gt;}).
 */
@QuarkusTest
class CloudWatchDashboardIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "GraniteServiceVersion20100801.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/monitoring/aws4_request";
    private static final String NAME = "floci-dashboard-lifecycle";
    private static final String BODY = "{\"widgets\":[{\"type\":\"text\",\"x\":0,\"y\":0,\"width\":6,\"height\":3,\"properties\":{\"markdown\":\"# list test\"}}]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDashboardOnAMissingNameFailsWithResourceNotFound() {
        post("GetDashboard", "{\"DashboardName\":\"floci-dashboard-missing\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("DashboardNotFoundError"));
    }

    @Test
    void putGetListAndDeleteDashboard() {
        post("PutDashboard", putBody(NAME, BODY))
                .then()
                .statusCode(200);

        post("GetDashboard", "{\"DashboardName\":\"" + NAME + "\"}")
                .then()
                .statusCode(200)
                .body("DashboardName", equalTo(NAME))
                .body("DashboardBody", equalTo(BODY))
                .body("DashboardArn", containsString(":dashboard/" + NAME));

        Response list = post("ListDashboards", "{}");
        list.then().statusCode(200);
        List<String> names = list.jsonPath().getList("DashboardEntries.DashboardName");
        assertTrue(names.contains(NAME), "listDashboards should include " + NAME + ", got " + names);

        post("DeleteDashboards", "{\"DashboardNames\":[\"" + NAME + "\"]}")
                .then()
                .statusCode(200);

        post("GetDashboard", "{\"DashboardName\":\"" + NAME + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("DashboardNotFoundError"));

        post("DeleteDashboards", "{\"DashboardNames\":[\"" + NAME + "\"]}")
                .then()
                .statusCode(200);
    }

    private static String putBody(String name, String body) {
        return "{\"DashboardName\":\"" + name + "\",\"DashboardBody\":\""
                + body.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private static Response post(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
