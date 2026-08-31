package io.github.hectorvent.floci.services.quicksight;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format coverage for QuickSight Create/Describe/Update/DeleteDataSource
 * and resource tags — the operations Alchemy DataSource.provider exercises.
 */
@QuarkusTest
class QuickSightDataSourceIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000911";
    private static final String ID = "alchemy-quicksight-datasource-it";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createDescribeTagUpdateDeleteDataSourceRoundTrip() {
        String authorization = auth(ACCOUNT, REGION);
        String base = "/accounts/" + ACCOUNT + "/data-sources";

        given()
                .header("Authorization", authorization)
                .when()
                .get(base + "/" + ID)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "DataSourceId":"%s",
                          "Name":"Alchemy Athena Source",
                          "Type":"ATHENA",
                          "DataSourceParameters":{"AthenaParameters":{"WorkGroup":"primary"}},
                          "Tags":[{"Key":"alchemy::id","Value":"AthenaSource"}]
                        }
                        """.formatted(ID))
                .when()
                .post(base)
                .then()
                .statusCode(202)
                .body("DataSourceId", equalTo(ID))
                .body("CreationStatus", equalTo("CREATION_SUCCESSFUL"))
                .body("Arn", startsWith("arn:aws:quicksight:" + REGION + ":" + ACCOUNT + ":datasource/"))
                .extract()
                .path("Arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get(base + "/" + ID)
                .then()
                .statusCode(200)
                .body("DataSource.DataSourceId", equalTo(ID))
                .body("DataSource.Arn", equalTo(arn))
                .body("DataSource.Type", equalTo("ATHENA"))
                .body("DataSource.Status", equalTo("CREATION_SUCCESSFUL"))
                .body("DataSource.DataSourceParameters.AthenaParameters.WorkGroup", equalTo("primary"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/resources/" + arn + "/tags")
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'alchemy::id' }.Value", equalTo("AthenaSource"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"Tags":[{"Key":"env","Value":"test"}]}
                        """)
                .when()
                .post("/resources/" + arn + "/tags")
                .then()
                .statusCode(200);

        List<Map<String, String>> tags = given()
                .header("Authorization", authorization)
                .when()
                .get("/resources/" + arn + "/tags")
                .then()
                .statusCode(200)
                .extract()
                .path("Tags");
        assertTrue(tags.stream().anyMatch(t -> "env".equals(t.get("Key")) && "test".equals(t.get("Value"))));

        given()
                .header("Authorization", authorization)
                .queryParam("keys", "env")
                .when()
                .delete("/resources/" + arn + "/tags")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"Alchemy Athena Source updated",
                          "DataSourceParameters":{"AthenaParameters":{"WorkGroup":"primary"}}
                        }
                        """)
                .when()
                .put(base + "/" + ID)
                .then()
                .statusCode(202)
                .body("UpdateStatus", equalTo("UPDATE_SUCCESSFUL"));

        given()
                .header("Authorization", authorization)
                .when()
                .get(base + "/" + ID)
                .then()
                .statusCode(200)
                .body("DataSource.Name", equalTo("Alchemy Athena Source updated"))
                .body("DataSource.Status", equalTo("UPDATE_SUCCESSFUL"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "DataSourceId":"%s",
                          "Name":"dup",
                          "Type":"ATHENA"
                        }
                        """.formatted(ID))
                .when()
                .post(base)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ResourceExistsException"));

        List<Map<String, Object>> listed = given()
                .header("Authorization", authorization)
                .when()
                .get(base)
                .then()
                .statusCode(200)
                .extract()
                .path("DataSources");
        assertEquals(1, listed.size());
        assertEquals(ID, listed.getFirst().get("DataSourceId"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete(base + "/" + ID)
                .then()
                .statusCode(200)
                .body("DataSourceId", equalTo(ID));

        given()
                .header("Authorization", authorization)
                .when()
                .get(base + "/" + ID)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete(base + "/" + ID)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createWithoutNameIsInvalidParameterValueException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000912", REGION))
                .body("""
                        {"DataSourceId":"missing-name","Type":"ATHENA"}
                        """)
                .when()
                .post("/accounts/000000000912/data-sources")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/quicksight/aws4_request";
    }
}
