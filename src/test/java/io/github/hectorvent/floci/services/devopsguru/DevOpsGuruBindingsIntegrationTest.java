package io.github.hectorvent.floci.services.devopsguru;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * restJson1 coverage for the account-level operations Alchemy
 * {@code test/AWS/DevOpsGuru/Bindings.test.ts} drives through the Lambda fixture.
 */
@QuarkusTest
class DevOpsGuruBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeAccountHealthReturnsZeroCounters() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000701", EAST))
                .when()
                .get("/accounts/health")
                .then()
                .statusCode(200)
                .body("OpenReactiveInsights", greaterThanOrEqualTo(0))
                .body("OpenProactiveInsights", greaterThanOrEqualTo(0))
                .body("MetricsAnalyzed", greaterThanOrEqualTo(0));
    }

    @Test
    void describeAccountOverviewSummarizesTheTrailingWindow() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000702", EAST))
                .body("{\"FromTime\":1710000000,\"ToTime\":1710086400}")
                .when()
                .post("/accounts/overview")
                .then()
                .statusCode(200)
                .body("ReactiveInsights", greaterThanOrEqualTo(0))
                .body("ProactiveInsights", greaterThanOrEqualTo(0));
    }

    @Test
    void listAndSearchInsightsReturnEmptyReactiveLists() {
        String authorization = auth("000000000703", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StatusFilter\":{\"Ongoing\":{\"Type\":\"REACTIVE\"}}}")
                .when()
                .post("/insights")
                .then()
                .statusCode(200)
                .body("ReactiveInsights", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Type\":\"REACTIVE\",\"StartTimeRange\":{\"FromTime\":1710000000,\"ToTime\":1710086400}}")
                .when()
                .post("/insights/search")
                .then()
                .statusCode(200)
                .body("ReactiveInsights", hasSize(0));
    }

    @Test
    void searchInsightsWithoutToTimeIsAValidationException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000704", EAST))
                .body("{\"Type\":\"REACTIVE\",\"StartTimeRange\":{\"FromTime\":1710000000}}")
                .when()
                .post("/insights/search")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void describeResourceCollectionHealthIsEmptyWithoutCoverage() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000705", EAST))
                .when()
                .get("/accounts/health/resource-collection/AWS_CLOUD_FORMATION")
                .then()
                .statusCode(200)
                .body("CloudFormation", hasSize(0));
    }

    @Test
    void listMonitoredResourcesWithoutACollectionIsResourceNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000706", EAST))
                .body("{}")
                .when()
                .post("/monitoredResources")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("ResourceId", equalTo("ResourceCollection"))
                .body("message", equalTo("No CustomerResourceFilter present"));
    }

    @Test
    void resourceCollectionAddGetAndMonitoredResourcesLifecycle() {
        String authorization = auth("000000000707", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/resource-collections/AWS_CLOUD_FORMATION")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Action\":\"ADD\",\"ResourceCollection\":{\"CloudFormation\":{\"StackNames\":[\"my-app-prod\"]}}}")
                .when()
                .put("/resource-collections")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/resource-collections/AWS_CLOUD_FORMATION")
                .then()
                .statusCode(200)
                .body("ResourceCollection.CloudFormation.StackNames[0]", equalTo("my-app-prod"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/monitoredResources")
                .then()
                .statusCode(200)
                .body("MonitoredResourceIdentifiers", hasSize(0));
    }

    @Test
    void collectionsAreIsolatedByAccountAndRegion() {
        String first = auth("000000000708", EAST);
        String second = auth("000000000709", EAST);
        String west = auth("000000000708", WEST);

        given().contentType("application/json").header("Authorization", first)
                .body("{\"Action\":\"ADD\",\"ResourceCollection\":{\"CloudFormation\":{\"StackNames\":[\"east-a\"]}}}")
                .when().put("/resource-collections").then().statusCode(200);

        given().contentType("application/json").header("Authorization", second)
                .when().get("/resource-collections/AWS_CLOUD_FORMATION")
                .then().statusCode(404);

        given().contentType("application/json").header("Authorization", west)
                .when().get("/resource-collections/AWS_CLOUD_FORMATION")
                .then().statusCode(404);

        given().contentType("application/json").header("Authorization", first)
                .when().get("/resource-collections/AWS_CLOUD_FORMATION")
                .then().statusCode(200)
                .body("ResourceCollection.CloudFormation.StackNames[0]", equalTo("east-a"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/devops-guru/aws4_request";
    }
}
