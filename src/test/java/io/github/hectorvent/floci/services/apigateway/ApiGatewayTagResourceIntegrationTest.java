package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayTagResourceIntegrationTest {

    @Test
    void stageTagsRemainIsolatedThroughTheHttpLifecycle() {
        Map<String, String> apiTags = Map.of("alchemy::id", "AgEsApi", "shared", "api");
        Map<String, String> stageTags = Map.of("alchemy::id", "AgEsStage", "shared", "stage");
        Map<String, String> siblingTags = Map.of("alchemy::id", "SiblingStage", "shared", "sibling");
        String apiId = given().contentType("application/json")
                .body(Map.of("name", "stage-tag-isolation", "tags", apiTags))
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        String apiPath = "/restapis/" + apiId;
        String apiArn = "arn:aws:apigateway:us-east-1::" + apiPath;
        String stageArn = apiArn + "/stages/blue";
        try {
            String deploymentId = given().contentType("application/json").body("{}")
                    .when().post(apiPath + "/deployments").then().statusCode(201).extract().path("id");
            given().contentType("application/json").body(Map.of("stageName", "blue",
                            "deploymentId", deploymentId, "tags", stageTags))
                    .when().post(apiPath + "/stages").then().statusCode(201).body("tags", equalTo(stageTags));
            given().contentType("application/json").body(Map.of("stageName", "green",
                            "deploymentId", deploymentId, "tags", siblingTags))
                    .when().post(apiPath + "/stages").then().statusCode(201).body("tags", equalTo(siblingTags));
            assertHttpTags(stageArn, stageTags);
            assertHttpIsolation(apiId, apiTags, siblingTags);

            Map<String, String> updatedTags = Map.of("alchemy::id", "UpdatedStage", "shared", "stage",
                    "added", "value");
            given().pathParam("arn", stageArn).contentType("application/json")
                    .body(Map.of("tags", Map.of("alchemy::id", "UpdatedStage", "added", "value")))
                    .when().put("/tags/{arn}").then().statusCode(204);
            assertHttpTags(stageArn, updatedTags);
            given().contentType("application/json").body(Map.of("patchOperations", List.of(
                            Map.of("op", "replace", "path", "/description", "value", "updated"))))
                    .when().patch(apiPath + "/stages/blue").then().statusCode(200)
                    .body("tags", equalTo(updatedTags));
            given().when().get(apiPath + "/stages/blue").then().statusCode(200)
                    .body("tags", equalTo(updatedTags));
            given().when().get(apiPath + "/stages").then().statusCode(200)
                    .body("item.find { it.stageName == 'blue' }.tags", equalTo(updatedTags))
                    .body("item.find { it.stageName == 'green' }.tags", equalTo(siblingTags));
            assertHttpIsolation(apiId, apiTags, siblingTags);

            for (int attempt = 0; attempt < 2; attempt++) {
                given().pathParam("arn", stageArn).queryParam("tagKeys", "alchemy::id", "added", "absent")
                        .when().delete("/tags/{arn}").then().statusCode(204);
                assertHttpTags(stageArn, Map.of("shared", "stage"));
            }
            given().when().get(apiPath + "/stages/blue").then().statusCode(200)
                    .body("tags", equalTo(Map.of("shared", "stage")));
            assertHttpIsolation(apiId, apiTags, siblingTags);

            given().when().delete(apiPath + "/stages/blue").then().statusCode(202);
            assertHttpTagFailure(stageArn, 404, "NotFoundException");
            given().contentType("application/json").body(Map.of("stageName", "blue", "deploymentId", deploymentId))
                    .when().post(apiPath + "/stages").then().statusCode(201).body("tags", equalTo(Map.of()));
            assertHttpTags(stageArn, Map.of());
            assertHttpIsolation(apiId, apiTags, siblingTags);
        } finally {
            given().when().delete(apiPath).then().statusCode(202);
        }
        assertHttpTagFailure(stageArn, 404, "NotFoundException");
    }

    @Test
    void invalidSubresourceArnsFailInsteadOfTaggingTheParentOverHttp() {
        Map<String, String> tags = Map.of("owner::id", "api");
        String apiId = given().contentType("application/json")
                .body(Map.of("name", "invalid-stage-tag-targets", "tags", tags))
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        String apiArn = "arn:aws:apigateway:us-east-1::/restapis/" + apiId;
        try {
            for (String suffix : List.of("/", "/stages", "/stages/", "/stages/blue/extra",
                    "/resources/root", "/deployments/deployment")) {
                assertHttpTagFailure(apiArn + suffix, 400, "BadRequestException");
            }
            assertHttpTagFailure("arn:aws:apigateway:us-east-1::/restapis//stages/blue", 400, "BadRequestException");
            assertHttpTagFailure("arn:aws:apigateway:us-east-1::/domainnames/example.com/basepathmappings/test",
                    400, "BadRequestException");
            assertHttpTagFailure(apiArn + "/stages/missing", 404, "NotFoundException");
            assertHttpTags(apiArn, tags);
            given().when().get("/restapis/" + apiId).then().statusCode(200).body("tags", equalTo(tags));
        } finally {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    private static void assertHttpIsolation(String apiId, Map<String, String> apiTags, Map<String, String> siblingTags) {
        String apiArn = "arn:aws:apigateway:us-east-1::/restapis/" + apiId;
        assertHttpTags(apiArn, apiTags);
        assertHttpTags(apiArn + "/stages/green", siblingTags);
        given().when().get("/restapis/" + apiId).then().statusCode(200).body("tags", equalTo(apiTags));
        given().when().get("/restapis").then().statusCode(200)
                .body("item.find { it.id == '" + apiId + "' }.tags", equalTo(apiTags));
        given().when().get("/restapis/" + apiId + "/stages/green").then().statusCode(200)
                .body("tags", equalTo(siblingTags));
    }

    private static void assertHttpTags(String arn, Map<String, String> tags) {
        given().pathParam("arn", arn).when().get("/tags/{arn}").then()
                .statusCode(200).body("tags", equalTo(tags));
    }

    private static void assertHttpTagFailure(String arn, int status, String code) {
        given().pathParam("arn", arn).when().get("/tags/{arn}").then()
                .statusCode(status).body("__type", equalTo(code));
        given().pathParam("arn", arn).contentType("application/json")
                .body(Map.of("tags", Map.of("owner::id", "wrong-target")))
                .when().put("/tags/{arn}").then().statusCode(status).body("__type", equalTo(code));
        given().pathParam("arn", arn).queryParam("tagKeys", "owner::id")
                .when().delete("/tags/{arn}").then().statusCode(status).body("__type", equalTo(code));
    }

    @Test
    void testTagResource() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"taggable-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");

        String resourceArn = "arn:aws:apigateway:us-east-1::/restapis/" + apiId;

        given()
                .pathParam("resourceArn", resourceArn)
                .contentType("application/json")
                .body("{\"tags\":{\"environment\":\"test\"}}")
                .when()
                .put("/tags/{resourceArn}")
                .then()
                .statusCode(204);

        given()
                .pathParam("apiId", apiId)
                .when()
                .get("/restapis/{apiId}")
                .then()
                .statusCode(200)
                .body("tags.environment", equalTo("test"));
    }
}
