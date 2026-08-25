package io.github.hectorvent.floci.services.appregistry;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies AppRegistry restJson1 application, attribute-group, and association APIs. */
@QuarkusTest
class AppRegistryIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAttributeGroupOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000501", EAST))
                .when()
                .get("/attribute-groups/missing-group")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void applicationAndAttributeGroupAssociationLifecycle() {
        String authorization = auth("000000000502", EAST);

        Response createdApp = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"assoc-app",
                          "description":"association lifecycle",
                          "clientToken":"app-token-1",
                          "tags":{"Owner":"floci"}
                        }
                        """)
                .when()
                .post("/applications")
                .then()
                .statusCode(200)
                .body("application.id", notNullValue())
                .body("application.name", equalTo("assoc-app"))
                .extract().response();
        String applicationId = createdApp.path("application.id");
        String applicationArn = createdApp.path("application.arn");
        assertTrue(applicationArn.contains(":/applications/" + applicationId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId)
                .then()
                .statusCode(200)
                .body("id", equalTo(applicationId))
                .body("arn", equalTo(applicationArn))
                .body("name", equalTo("assoc-app"))
                .body("description", equalTo("association lifecycle"))
                .body("tags.Owner", equalTo("floci"));

        Response createdGroup = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"assoc-group",
                          "attributes":"{\\"owner\\":\\"alchemy-test\\"}",
                          "clientToken":"group-token-1"
                        }
                        """)
                .when()
                .post("/attribute-groups")
                .then()
                .statusCode(200)
                .body("attributeGroup.id", notNullValue())
                .extract().response();
        String attributeGroupId = createdGroup.path("attributeGroup.id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/attribute-groups/" + attributeGroupId)
                .then()
                .statusCode(200)
                .body("id", equalTo(attributeGroupId))
                .body("name", equalTo("assoc-group"))
                .body("attributes", equalTo("{\"owner\":\"alchemy-test\"}"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .put("/applications/" + applicationId + "/attribute-groups/" + attributeGroupId)
                .then()
                .statusCode(200)
                .body("applicationArn", equalTo(applicationArn))
                .body("attributeGroupArn", containsString(":/attribute-groups/" + attributeGroupId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId + "/attribute-groups")
                .then()
                .statusCode(200)
                .body("attributeGroups", hasItem(attributeGroupId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"options\":[\"SKIP_APPLICATION_TAG\"]}")
                .when()
                .put("/applications/" + applicationId + "/resources/RESOURCE_TAG_VALUE/storefront")
                .then()
                .statusCode(200)
                .body("applicationArn", equalTo(applicationArn))
                .body("resourceArn", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId + "/resources/RESOURCE_TAG_VALUE/storefront")
                .then()
                .statusCode(200)
                .body("resource.name", equalTo("storefront"))
                .body("options[0]", equalTo("SKIP_APPLICATION_TAG"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId + "/resources")
                .then()
                .statusCode(200)
                .body("resources[0].name", equalTo("storefront"))
                .body("resources[0].resourceType", equalTo("RESOURCE_TAG_VALUE"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/applications/" + applicationId + "/resources/RESOURCE_TAG_VALUE/storefront")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/applications/" + applicationId + "/attribute-groups/" + attributeGroupId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/attribute-groups/" + attributeGroupId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/applications/" + applicationId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void associateMissingCloudFormationStackFailsWithResourceNotFoundException() {
        String authorization = auth("000000000503", EAST);
        String applicationId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"cfn-missing-app",
                          "clientToken":"cfn-missing-token"
                        }
                        """)
                .when()
                .post("/applications")
                .then()
                .statusCode(200)
                .extract().path("application.id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"options\":[\"SKIP_APPLICATION_TAG\"]}")
                .when()
                .put("/applications/" + applicationId + "/resources/CFN_STACK/missing-stack")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createSecondApplicationWithTheSameNameConflicts() {
        String authorization = auth("000000000504", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"name":"dup-app","clientToken":"dup-1"}
                        """)
                .when()
                .post("/applications")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"name":"dup-app","clientToken":"dup-2"}
                        """)
                .when()
                .post("/applications")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void tagsRoundTripOnAnApplicationArn() {
        String authorization = auth("000000000505", EAST);
        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"tagged-app",
                          "clientToken":"tag-token",
                          "tags":{"Environment":"test"}
                        }
                        """)
                .when()
                .post("/applications")
                .then()
                .statusCode(200)
                .extract().response();
        String arn = created.path("application.arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"Team\":\"platform\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"))
                .body("tags.Team", equalTo("platform"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/servicecatalog/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
