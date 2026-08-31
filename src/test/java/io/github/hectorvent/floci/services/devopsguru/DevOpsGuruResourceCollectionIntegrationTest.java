package io.github.hectorvent.floci.services.devopsguru;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * restJson1 GetResourceCollection / UpdateResourceCollection matching Alchemy
 * ResourceCollection.test.ts: typed not-found when empty, tag coverage ADD /
 * delta REMOVE+ADD, lingering empty TagValues after the last value is removed.
 */
@QuarkusTest
class DevOpsGuruResourceCollectionIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String KEY = "devops-guru-alchemy";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getResourceCollectionWithoutCoverageIsTypedNotFound() {
        given()
                .header("Authorization", auth("000000000701", EAST))
                .when()
                .get("/resource-collections/AWS_TAGS")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("ResourceId", equalTo("ResourceCollection"))
                .body("message", equalTo("No CustomerResourceFilter present"));
    }

    @Test
    void tagCoverageAddConvergeAndRemoveLifecycle() {
        String authorization = auth("000000000702", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(update("ADD", KEY, List.of("devopsguru-test-a", "devopsguru-test-b")))
                .when()
                .put("/resource-collections")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/resource-collections/AWS_TAGS")
                .then()
                .statusCode(200)
                .body("ResourceCollection.Tags", hasSize(1))
                .body("ResourceCollection.Tags[0].AppBoundaryKey", equalTo(KEY))
                .body("ResourceCollection.Tags[0].TagValues",
                        containsInAnyOrder("devopsguru-test-a", "devopsguru-test-b"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/resource-collections/AWS_CLOUD_FORMATION")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(update("REMOVE", KEY, List.of("devopsguru-test-b")))
                .when()
                .put("/resource-collections")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(update("ADD", KEY, List.of("devopsguru-test-c")))
                .when()
                .put("/resource-collections")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/resource-collections/AWS_TAGS")
                .then()
                .statusCode(200)
                .body("ResourceCollection.Tags[0].TagValues",
                        containsInAnyOrder("devopsguru-test-a", "devopsguru-test-c"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(update("REMOVE", KEY, List.of("devopsguru-test-a", "devopsguru-test-c")))
                .when()
                .put("/resource-collections")
                .then()
                .statusCode(200);

        List<String> leftover = given()
                .header("Authorization", authorization)
                .when()
                .get("/resource-collections/AWS_TAGS")
                .then()
                .statusCode(200)
                .body("ResourceCollection.Tags", hasSize(1))
                .body("ResourceCollection.Tags[0].AppBoundaryKey", equalTo(KEY))
                .extract()
                .path("ResourceCollection.Tags[0].TagValues");
        assertEquals(List.of(), leftover);
    }

    @Test
    void collectionsAreIsolatedByAccountAndRegion() {
        String first = auth("000000000703", EAST);
        String second = auth("000000000704", EAST);
        String west = auth("000000000703", WEST);

        given().contentType("application/json").header("Authorization", first)
                .body(update("ADD", KEY, List.of("east-a")))
                .when().put("/resource-collections").then().statusCode(200);

        given().header("Authorization", second)
                .when().get("/resource-collections/AWS_TAGS")
                .then().statusCode(404);

        given().header("Authorization", west)
                .when().get("/resource-collections/AWS_TAGS")
                .then().statusCode(404);

        given().header("Authorization", first)
                .when().get("/resource-collections/AWS_TAGS")
                .then().statusCode(200)
                .body("ResourceCollection.Tags[0].TagValues[0]", equalTo("east-a"));
    }

    @Test
    void cloudFormationAddGetAndRemove() {
        String authorization = auth("000000000705", EAST);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/resource-collections/AWS_CLOUD_FORMATION")
                .then()
                .statusCode(404)
                .body("message", equalTo("No CustomerResourceFilter present"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"Action":"ADD","ResourceCollection":{"CloudFormation":{"StackNames":["my-app-prod"]}}}
                        """)
                .when()
                .put("/resource-collections")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/resource-collections/AWS_CLOUD_FORMATION")
                .then()
                .statusCode(200)
                .body("ResourceCollection.CloudFormation.StackNames[0]", equalTo("my-app-prod"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"Action":"REMOVE","ResourceCollection":{"CloudFormation":{"StackNames":["my-app-prod"]}}}
                        """)
                .when()
                .put("/resource-collections")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/resource-collections/AWS_CLOUD_FORMATION")
                .then()
                .statusCode(404);
    }

    private static String update(String action, String key, List<String> values) {
        String joined = String.join("\",\"", values);
        return "{\"Action\":\"" + action + "\",\"ResourceCollection\":{\"Tags\":[{"
                + "\"AppBoundaryKey\":\"" + key + "\",\"TagValues\":[\"" + joined + "\"]}]}}";
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/devops-guru/aws4_request";
    }
}
