package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies RAM restJson1 customer-managed permission operations used by
 * Alchemy {@code Permission.test.ts}: create, get, list, version, tags, delete.
 */
@QuarkusTest
class RamPermissionIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000004201";
    private static final String POLICY = """
            {"Effect":"Allow","Action":["appsync:SourceGraphQL"]}
            """;
    private static final String GROWN_POLICY = """
            {"Effect":"Allow","Action":["appsync:GraphQL","appsync:SourceGraphQL"]}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getPermissionOnANonexistentArnFailsWithUnknownResourceException() {
        String arn = "arn:aws:ram:" + EAST + ":" + ACCOUNT
                + ":permission/missing/00000000-0000-0000-0000-000000000000";
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"permissionArn\":\"" + arn + "\"}")
                .when()
                .post("/getpermission")
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnknownResourceException"));
    }

    @Test
    void listPermissionsOnAnEmptyAccountReturnsNoItems() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000004299", EAST))
                .body("{\"permissionType\":\"CUSTOMER_MANAGED\"}")
                .when()
                .post("/listpermissions")
                .then()
                .statusCode(200)
                .body("permissions.size()", equalTo(0));
    }

    @Test
    void permissionCreateVersionTagsAndDeleteLifecycle() {
        String authorization = auth(ACCOUNT, EAST);

        Map<String, Object> created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"SourceGraphQLOnly",
                          "resourceType":"appsync:Apis",
                          "policyTemplate":%s,
                          "tags":[{"key":"team","value":"platform"},{"key":"alchemy::id","value":"TestPermission"}]
                        }
                        """.formatted(quote(POLICY)))
                .when()
                .post("/createpermission")
                .then()
                .statusCode(200)
                .body("permission.arn", notNullValue())
                .body("permission.name", equalTo("SourceGraphQLOnly"))
                .body("permission.resourceType", equalTo("appsync:Apis"))
                .body("permission.version", equalTo("1"))
                .body("permission.status", equalTo("ATTACHABLE"))
                .extract().path("permission");
        String arn = (String) created.get("arn");
        assertTrue(arn.startsWith("arn:aws:ram:" + EAST + ":" + ACCOUNT + ":permission/"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"permissionArn\":\"" + arn + "\"}")
                .when()
                .post("/getpermission")
                .then()
                .statusCode(200)
                .body("permission.arn", equalTo(arn))
                .body("permission.status", equalTo("ATTACHABLE"))
                .body("permission.permission", containsString("appsync:SourceGraphQL"))
                .body("permission.tags.key", org.hamcrest.Matchers.hasItem("team"));

        List<Map<String, Object>> listed = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"permissionType\":\"CUSTOMER_MANAGED\"}")
                .when()
                .post("/listpermissions")
                .then()
                .statusCode(200)
                .extract().path("permissions");
        assertTrue(listed.stream().anyMatch(item -> arn.equals(item.get("arn"))));

        String nextVersion = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "permissionArn":"%s",
                          "policyTemplate":%s
                        }
                        """.formatted(arn, quote(GROWN_POLICY)))
                .when()
                .post("/createpermissionversion")
                .then()
                .statusCode(200)
                .body("permission.arn", equalTo(arn))
                .body("permission.version", not(equalTo("1")))
                .body("permission.defaultVersion", equalTo(true))
                .body("permission.permission", containsString("appsync:GraphQL"))
                .extract().path("permission.version");

        given()
                .header("Authorization", authorization)
                .queryParam("permissionArn", arn)
                .queryParam("permissionVersion", 1)
                .when()
                .delete("/deletepermissionversion")
                .then()
                .statusCode(200)
                .body("returnValue", equalTo(true));

        List<Map<String, Object>> versions = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"permissionArn\":\"" + arn + "\"}")
                .when()
                .post("/listpermissionversions")
                .then()
                .statusCode(200)
                .extract().path("permissions");
        List<Map<String, Object>> live = versions.stream()
                .filter(item -> {
                    Object status = item.get("status");
                    return !"DELETED".equals(status) && !"DELETING".equals(status);
                })
                .toList();
        assertEquals(1, live.size());
        assertEquals(nextVersion, live.get(0).get("version"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "resourceArn":"%s",
                          "tags":[{"key":"env","value":"prod"}]
                        }
                        """.formatted(arn))
                .when()
                .post("/tagresource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"permissionArn\":\"" + arn + "\"}")
                .when()
                .post("/getpermission")
                .then()
                .statusCode(200)
                .body("permission.tags.key", org.hamcrest.Matchers.hasItems("team", "env"));

        given()
                .header("Authorization", authorization)
                .queryParam("permissionArn", arn)
                .when()
                .delete("/deletepermission")
                .then()
                .statusCode(200)
                .body("returnValue", equalTo(true))
                .body("permissionStatus", equalTo("DELETED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"permissionArn\":\"" + arn + "\"}")
                .when()
                .post("/getpermission")
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnknownResourceException"));
    }

    @Test
    void createPermissionWithDuplicateNameFailsWithPermissionAlreadyExistsException() {
        String authorization = auth("000000004211", EAST);
        String body = """
                {
                  "name":"DupPermission",
                  "resourceType":"appsync:Apis",
                  "policyTemplate":%s
                }
                """.formatted(quote(POLICY));
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/createpermission")
                .then()
                .statusCode(200);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/createpermission")
                .then()
                .statusCode(409)
                .body("__type", equalTo("PermissionAlreadyExistsException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/ram/aws4_request";
    }

    private static String quote(String json) {
        return "\"" + json.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
