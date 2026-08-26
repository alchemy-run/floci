package io.github.hectorvent.floci.services.resourceexplorer;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the Resource Explorer restJson1 index, view, search, and type catalog used by Alchemy. */
@QuarkusTest
class ResourceExplorerIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getIndexWhenMissingFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, "eu-west-1"))
                .body("{}")
                .when()
                .post("/GetIndex")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createViewWithoutAnIndexFailsWithUnauthorizedException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, "ap-southeast-2"))
                .body("{\"ViewName\":\"no-index-view\"}")
                .when()
                .post("/CreateView")
                .then()
                .statusCode(401)
                .body("__type", equalTo("UnauthorizedException"));
    }

    @Test
    void listSupportedResourceTypesReturnsANonEmptyCatalog() {
        List<Map<String, Object>> types = given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{}")
                .when()
                .post("/ListSupportedResourceTypes")
                .then()
                .statusCode(200)
                .body("ResourceTypes.size()", greaterThan(0))
                .extract().path("ResourceTypes");
        assertFalse(types.isEmpty());
        assertTrue(types.stream().anyMatch(type -> "s3:bucket".equals(type.get("ResourceType"))));
    }

    @Test
    void indexViewSearchListResourcesTagAndDeleteLifecycle() {
        String region = "us-west-2";
        String authorization = auth(ACCOUNT, region);
        String viewName = "alchemy-re2-" + UUID.randomUUID().toString().substring(0, 8);

        String indexArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Tags":{"purpose":"alchemy-re2-test"}
                        }
                        """)
                .when()
                .post("/CreateIndex")
                .then()
                .statusCode(200)
                .body("Arn", notNullValue())
                .body("State", equalTo("ACTIVE"))
                .extract().path("Arn");
        assertTrue(indexArn.contains(":resource-explorer-2:" + region + ":"));
        assertTrue(indexArn.contains(":index/"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetIndex")
                .then()
                .statusCode(200)
                .body("Arn", equalTo(indexArn))
                .body("Type", equalTo("LOCAL"))
                .body("State", equalTo("ACTIVE"))
                .body("Tags.purpose", equalTo("alchemy-re2-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"team\":\"platform\"}}")
                .when()
                .post("/tags/" + encode(indexArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(indexArn))
                .then()
                .statusCode(200)
                .body("Tags.purpose", equalTo("alchemy-re2-test"))
                .body("Tags.team", equalTo("platform"));

        given()
                .header("Authorization", authorization)
                .queryParam("tagKeys", "purpose")
                .when()
                .delete("/tags/" + encode(indexArn))
                .then()
                .statusCode(204);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetIndex")
                .then()
                .statusCode(200)
                .body("Tags.team", equalTo("platform"))
                .body("Tags.purpose", equalTo(null));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"purpose\":\"again\"}}")
                .when()
                .post("/CreateIndex")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        String viewArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ViewName":"%s",
                          "Filters":{"FilterString":"service:s3"},
                          "IncludedProperties":[{"Name":"tags"}],
                          "Tags":{"alchemy::id":"TestView"}
                        }
                        """.formatted(viewName))
                .when()
                .post("/CreateView")
                .then()
                .statusCode(200)
                .body("View.ViewArn", notNullValue())
                .body("View.ViewName", equalTo(viewName))
                .body("View.Filters.FilterString", equalTo("service:s3"))
                .body("View.IncludedProperties[0].Name", equalTo("tags"))
                .extract().path("View.ViewArn");
        assertTrue(viewArn.contains(":view/" + viewName + "/"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ViewArn\":\"" + viewArn + "\"}")
                .when()
                .post("/GetView")
                .then()
                .statusCode(200)
                .body("View.ViewArn", equalTo(viewArn))
                .body("View.Filters.FilterString", equalTo("service:s3"))
                .body("Tags.'alchemy::id'", equalTo("TestView"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ViewArn":"%s",
                          "Filters":{"FilterString":"service:sqs"}
                        }
                        """.formatted(viewArn))
                .when()
                .post("/UpdateView")
                .then()
                .statusCode(200)
                .body("View.ViewArn", equalTo(viewArn))
                .body("View.Filters.FilterString", equalTo("service:sqs"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ViewArn\":\"" + viewArn + "\"}")
                .when()
                .post("/GetView")
                .then()
                .statusCode(200)
                .body("View.Filters.FilterString", equalTo("service:sqs"))
                .body("View.IncludedProperties", equalTo(null));

        List<String> listed = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListViews")
                .then()
                .statusCode(200)
                .extract().path("Views");
        assertTrue(listed.contains(viewArn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "QueryString":"service:s3",
                          "ViewArn":"%s",
                          "MaxResults":25
                        }
                        """.formatted(viewArn))
                .when()
                .post("/Search")
                .then()
                .statusCode(200)
                .body("ViewArn", equalTo(viewArn))
                .body("Count.Complete", equalTo(true))
                .body("Resources.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Filters":{"FilterString":"service:s3"},
                          "ViewArn":"%s"
                        }
                        """.formatted(viewArn))
                .when()
                .post("/ListResources")
                .then()
                .statusCode(200)
                .body("ViewArn", equalTo(viewArn))
                .body("Resources.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Arn":"%s",
                          "Type":"AGGREGATOR"
                        }
                        """.formatted(indexArn))
                .when()
                .post("/UpdateIndexType")
                .then()
                .statusCode(200)
                .body("Arn", equalTo(indexArn))
                .body("Type", equalTo("AGGREGATOR"))
                .body("State", equalTo("ACTIVE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ViewArn\":\"" + viewArn + "\"}")
                .when()
                .post("/DeleteView")
                .then()
                .statusCode(200)
                .body("ViewArn", equalTo(viewArn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ViewArn\":\"" + viewArn + "\"}")
                .when()
                .post("/GetView")
                .then()
                .statusCode(401)
                .body("__type", equalTo("UnauthorizedException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Arn\":\"" + indexArn + "\"}")
                .when()
                .post("/DeleteIndex")
                .then()
                .statusCode(200)
                .body("Arn", equalTo(indexArn))
                .body("State", equalTo("DELETED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetIndex")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getViewOnANonexistentArnFailsWithUnauthorizedException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"ViewArn\":\"arn:aws:resource-explorer-2:us-east-1:000000000000:view/missing/00000000-0000-0000-0000-000000000000\"}")
                .when()
                .post("/GetView")
                .then()
                .statusCode(401)
                .body("__type", equalTo("UnauthorizedException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/resource-explorer-2/aws4_request";
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }
}
