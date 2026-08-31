package io.github.hectorvent.floci.services.applicationsignals;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Verifies Application Signals grouping configuration and StartDiscovery restJson1 APIs. */
@QuarkusTest
public class ApplicationSignalsGroupingConfigurationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listGroupingAttributeDefinitionsIsEmptyWhenUnconfigured() {
        Response response = list(auth("000000000801", EAST));
        assertEquals(List.of(), response.path("GroupingAttributeDefinitions"));
        assertNull(response.path("UpdatedAt"));
    }

    @Test
    void putListReplaceAndDeleteGroupingConfigurationLifecycle() {
        String authorization = auth("000000000802", EAST);

        Response created = put(authorization, """
                {
                  "GroupingAttributeDefinitions": [
                    {
                      "GroupingName": "AlchemyTestTeam",
                      "GroupingSourceKeys": ["Tag.team"],
                      "DefaultGroupingValue": "unassigned"
                    }
                  ]
                }
                """);
        assertEquals("AlchemyTestTeam",
                created.path("GroupingConfiguration.GroupingAttributeDefinitions[0].GroupingName"));
        assertEquals("Tag.team",
                created.path("GroupingConfiguration.GroupingAttributeDefinitions[0].GroupingSourceKeys[0]"));
        assertEquals("unassigned",
                created.path("GroupingConfiguration.GroupingAttributeDefinitions[0].DefaultGroupingValue"));
        Number createdAt = created.path("GroupingConfiguration.UpdatedAt");
        assertNotNull(createdAt);

        Response listed = list(authorization);
        assertEquals(List.of("AlchemyTestTeam"), names(listed));
        assertEquals("unassigned", listed.path("GroupingAttributeDefinitions[0].DefaultGroupingValue"));
        assertEquals(createdAt.longValue(), ((Number) listed.path("UpdatedAt")).longValue());

        Response updated = put(authorization, """
                {
                  "GroupingAttributeDefinitions": [
                    {
                      "GroupingName": "AlchemyTestTeam",
                      "GroupingSourceKeys": ["Tag.team"]
                    },
                    {
                      "GroupingName": "AlchemyTestCostCenter",
                      "GroupingSourceKeys": ["Tag.cost-center"],
                      "DefaultGroupingValue": "shared"
                    }
                  ]
                }
                """);
        assertEquals(List.of("AlchemyTestTeam", "AlchemyTestCostCenter"), namesFromPut(updated));
        assertNull(updated.path("GroupingConfiguration.GroupingAttributeDefinitions[0].DefaultGroupingValue"));
        assertEquals("shared",
                updated.path("GroupingConfiguration.GroupingAttributeDefinitions[1].DefaultGroupingValue"));

        Response listedAfterUpdate = list(authorization);
        assertEquals(2, ((List<?>) listedAfterUpdate.path("GroupingAttributeDefinitions")).size());

        delete(authorization).then().statusCode(200);
        Response afterDelete = list(authorization);
        assertEquals(List.of(), afterDelete.path("GroupingAttributeDefinitions"));
        assertNull(afterDelete.path("UpdatedAt"));

        delete(authorization).then().statusCode(200);
    }

    @Test
    void startDiscoveryIsIdempotent() {
        String authorization = auth("000000000803", EAST);
        startDiscovery(authorization).then().statusCode(200);
        startDiscovery(authorization).then().statusCode(200);
        assertEquals(List.of(), list(authorization).path("GroupingAttributeDefinitions"));
    }

    @Test
    void groupingConfigurationIsIsolatedByAccountAndRegion() {
        String first = auth("000000000804", EAST);
        String second = auth("000000000805", EAST);
        String west = auth("000000000804", WEST);

        put(first, """
                {"GroupingAttributeDefinitions":[{"GroupingName":"First","GroupingSourceKeys":["Tag.team"]}]}
                """);
        put(second, """
                {"GroupingAttributeDefinitions":[{"GroupingName":"Second","GroupingSourceKeys":["Tag.team"]}]}
                """);
        put(west, """
                {"GroupingAttributeDefinitions":[{"GroupingName":"West","GroupingSourceKeys":["Tag.team"]}]}
                """);

        assertEquals(List.of("First"), names(list(first)));
        assertEquals(List.of("Second"), names(list(second)));
        assertEquals(List.of("West"), names(list(west)));
    }

    @Test
    void putGroupingConfigurationRejectsMissingDefinitions() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000806", EAST))
                .body("{}")
                .when()
                .put("/grouping-configuration")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static Response list(String authorization) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/grouping-attribute-definitions")
                .then()
                .statusCode(200)
                .body("GroupingAttributeDefinitions", notNullValue())
                .extract()
                .response();
    }

    private static Response put(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .put("/grouping-configuration")
                .then()
                .statusCode(200)
                .body("GroupingConfiguration.UpdatedAt", notNullValue())
                .extract()
                .response();
    }

    private static Response delete(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .delete("/grouping-configuration");
    }

    private static Response startDiscovery(String authorization) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/start-discovery");
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(Response response) {
        List<Map<String, Object>> definitions = response.path("GroupingAttributeDefinitions");
        return definitions.stream().map(d -> (String) d.get("GroupingName")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> namesFromPut(Response response) {
        List<Map<String, Object>> definitions = response.path("GroupingConfiguration.GroupingAttributeDefinitions");
        return definitions.stream().map(d -> (String) d.get("GroupingName")).toList();
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/application-signals/aws4_request";
    }
}
