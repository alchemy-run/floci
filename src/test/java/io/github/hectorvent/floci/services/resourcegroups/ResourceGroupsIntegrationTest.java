package io.github.hectorvent.floci.services.resourcegroups;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies Resource Groups restJson1 group CRUD, pool grouping, and bindings ops. */
@QuarkusTest
class ResourceGroupsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String POOL_CONFIG = """
            [
              {
                "Type":"AWS::ResourceGroups::Generic",
                "Parameters":[{"Name":"allowed-resource-types","Values":["AWS::EC2::CapacityReservation"]}]
              },
              {"Type":"AWS::EC2::CapacityReservationPool"}
            ]
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getGroupOnAMissingGroupFailsWithNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"Group\":\"alchemy-rg-does-not-exist\"}")
                .when()
                .post("/get-group")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void listGroupingStatusesOnAMissingGroupFailsWithNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"Group\":\"alchemy-rg-does-not-exist\"}")
                .when()
                .post("/list-grouping-statuses")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void capacityPoolCreateListGroupSearchAndTypedRejections() {
        String authorization = auth(EAST);
        String name = "alchemy-rg-pool-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"%s",
                          "Description":"alchemy resource-groups bindings fixture pool",
                          "Configuration":%s,
                          "Tags":{"purpose":"alchemy-test","alchemy::id":"BindingsPoolGroup"}
                        }
                        """.formatted(name, POOL_CONFIG))
                .when()
                .post("/groups")
                .then()
                .statusCode(200)
                .body("Group.Name", equalTo(name))
                .body("Group.GroupArn", notNullValue())
                .body("Group.Description", equalTo("alchemy resource-groups bindings fixture pool"))
                .body("GroupConfiguration.Status", equalTo("UPDATE_COMPLETE"))
                .body("GroupConfiguration.Configuration.Type", hasItem("AWS::EC2::CapacityReservationPool"))
                .extract()
                .path("Group.GroupArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\"}")
                .when()
                .post("/get-group")
                .then()
                .statusCode(200)
                .body("Group.GroupArn", equalTo(arn))
                .body("Group.Name", equalTo(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\"}")
                .when()
                .post("/get-group-configuration")
                .then()
                .statusCode(200)
                .body("GroupConfiguration.Configuration.Type", hasItem("AWS::EC2::CapacityReservationPool"))
                .body("GroupConfiguration.Configuration.Type", hasItem("AWS::ResourceGroups::Generic"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/resources/" + encode(arn) + "/tags")
                .then()
                .statusCode(200)
                .body("Tags.purpose", equalTo("alchemy-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\"}")
                .when()
                .post("/list-group-resources")
                .then()
                .statusCode(200)
                .body("Resources", empty());

        String lambdaArn = "arn:aws:lambda:" + EAST + ":000000000000:function:rg-bindings";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\",\"ResourceArns\":[\"" + lambdaArn + "\"]}")
                .when()
                .post("/group-resources")
                .then()
                .statusCode(200)
                .body("Succeeded", empty())
                .body("Failed[0].ErrorCode", equalTo("ResourceArnValidationException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\",\"ResourceArns\":[\"" + lambdaArn + "\"]}")
                .when()
                .post("/ungroup-resources")
                .then()
                .statusCode(200)
                .body("Failed[0].ErrorCode", equalTo("ResourceArnValidationException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\"}")
                .when()
                .post("/list-grouping-statuses")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", org.hamcrest.Matchers.containsString("application group"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceQuery":{
                            "Type":"TAG_FILTERS_1_0",
                            "Query":"{\\"ResourceTypeFilters\\":[\\"AWS::AllSupported\\"],\\"TagFilters\\":[{\\"Key\\":\\"purpose\\",\\"Values\\":[\\"alchemy-test\\"]}]}"
                          }
                        }
                        """)
                .when()
                .post("/resources/search")
                .then()
                .statusCode(200)
                .body("ResourceIdentifiers", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/get-account-settings")
                .then()
                .statusCode(200)
                .body("AccountSettings.GroupLifecycleEventsStatus", equalTo("INACTIVE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/list-tag-sync-tasks")
                .then()
                .statusCode(200)
                .body("TagSyncTasks.size()", greaterThanOrEqualTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\",\"TagKey\":\"alchemy-rg-sync\",\"TagValue\":\"on\","
                        + "\"RoleArn\":\"arn:aws:iam::000000000000:role/BindingsTagSyncRole\"}")
                .when()
                .post("/start-tag-sync-task")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TaskArn\":\"arn:aws:resource-groups:" + EAST
                        + ":000000000000:group/none/00000000-0000-0000-0000-000000000000\"}")
                .when()
                .post("/get-tag-sync-task")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TaskArn\":\"arn:aws:resource-groups:" + EAST
                        + ":000000000000:group/none/00000000-0000-0000-0000-000000000000\"}")
                .when()
                .post("/cancel-tag-sync-task")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"" + name + "\",\"Configuration\":" + POOL_CONFIG + "}")
                .when()
                .post("/groups")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", org.hamcrest.Matchers.containsString("group already exists"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\"}")
                .when()
                .post("/delete-group")
                .then()
                .statusCode(200)
                .body("Group.Name", equalTo(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\"}")
                .when()
                .post("/get-group")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void tagBasedGroupQueryAndTagsRoundTrip() {
        String authorization = auth(EAST);
        String name = "alchemy-rg-tag-" + UUID.randomUUID().toString().substring(0, 8);
        String query = "{\"ResourceTypeFilters\":[\"AWS::AllSupported\"],"
                + "\"TagFilters\":[{\"Key\":\"alchemy-rg-test\",\"Values\":[\"v1\"]}]}";

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"%s",
                          "Description":"Alchemy resource-groups test",
                          "ResourceQuery":{"Type":"TAG_FILTERS_1_0","Query":%s},
                          "Tags":{"purpose":"alchemy-test"}
                        }
                        """.formatted(name, jsonString(query)))
                .when()
                .post("/groups")
                .then()
                .statusCode(200)
                .body("Group.Name", equalTo(name))
                .extract()
                .path("Group.GroupArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\"}")
                .when()
                .post("/get-group-query")
                .then()
                .statusCode(200)
                .body("GroupQuery.ResourceQuery.Type", equalTo("TAG_FILTERS_1_0"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\",\"Description\":\"updated\"}")
                .when()
                .post("/update-group")
                .then()
                .statusCode(200)
                .body("Group.Description", equalTo("updated"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"extra\":\"1\"}}")
                .when()
                .put("/resources/" + encode(arn) + "/tags")
                .then()
                .statusCode(200)
                .body("Tags.extra", equalTo("1"))
                .body("Tags.purpose", equalTo("alchemy-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Keys\":[\"extra\"]}")
                .when()
                .patch("/resources/" + encode(arn) + "/tags")
                .then()
                .statusCode(200)
                .body("Keys", hasItem("extra"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\"}")
                .when()
                .post("/list-group-resources")
                .then()
                .statusCode(200)
                .body("Resources", empty());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Group\":\"" + name + "\"}")
                .when()
                .post("/delete-group")
                .then()
                .statusCode(200);
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/resource-groups/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
