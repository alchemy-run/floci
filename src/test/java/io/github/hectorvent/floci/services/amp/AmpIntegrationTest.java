package io.github.hectorvent.floci.services.amp;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies AMP restJson1 scraper operations used by Alchemy. */
@QuarkusTest
class AmpIntegrationTest {

    private static final String REGION = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeScraperReturnsResourceNotFoundForAMissingScraper() {
        String scraperId = "s-00000000-0000-0000-0000-000000000000";
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000301", REGION))
                .when()
                .get("/scrapers/" + scraperId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo(scraperId))
                .body("resourceType", equalTo("scraper"));
    }

    @Test
    void getDefaultScraperConfigurationReturnsPrometheusYaml() {
        String encoded = given()
                .header("Authorization", auth("000000000302", REGION))
                .when()
                .get("/scraperconfiguration")
                .then()
                .statusCode(200)
                .extract().path("configuration");
        String yaml = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        assertTrue(yaml.contains("scrape_configs"));
    }

    @Test
    void scraperCreateDescribeUpdateDeleteAndLoggingLifecycle() {
        String authorization = auth("000000000303", REGION);
        String blob = Base64.getEncoder().encodeToString(
                AmpService.DEFAULT_SCRAPER_CONFIGURATION.getBytes(StandardCharsets.UTF_8));
        String createBody = """
                {
                  "alias": "alchemy-test-scraper",
                  "scrapeConfiguration": { "configurationBlob": "%s" },
                  "source": {
                    "eksConfiguration": {
                      "clusterArn": "arn:aws:eks:us-east-1:000000000303:cluster/demo",
                      "subnetIds": ["subnet-a", "subnet-b"]
                    }
                  },
                  "destination": {
                    "ampConfiguration": {
                      "workspaceArn": "arn:aws:aps:us-east-1:000000000303:workspace/ws-demo"
                    }
                  },
                  "tags": { "Environment": "test", "alchemy::id": "Scraper" }
                }
                """.formatted(blob);

        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(createBody)
                .when()
                .post("/scrapers")
                .then()
                .statusCode(200)
                .body("scraperId", startsWith("s-"))
                .body("status.statusCode", equalTo("ACTIVE"))
                .extract().response();
        String scraperId = created.path("scraperId");
        String arn = created.path("arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/scrapers/" + scraperId)
                .then()
                .statusCode(200)
                .body("scraper.scraperId", equalTo(scraperId))
                .body("scraper.alias", equalTo("alchemy-test-scraper"))
                .body("scraper.status.statusCode", equalTo("ACTIVE"))
                .body("scraper.tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"alias\":\"alchemy-test-scraper-2\"}")
                .when()
                .put("/scrapers/" + scraperId)
                .then()
                .statusCode(200)
                .body("scraperId", equalTo(scraperId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/scrapers/" + scraperId)
                .then()
                .statusCode(200)
                .body("scraper.alias", equalTo("alchemy-test-scraper-2"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "loggingDestination": {
                            "cloudWatchLogs": {
                              "logGroupArn": "arn:aws:logs:us-east-1:000000000303:log-group:/aws/vendedlogs/prometheus/test:*"
                            }
                          }
                        }
                        """)
                .when()
                .put("/scrapers/" + scraperId + "/logging-configuration")
                .then()
                .statusCode(200)
                .body("status.statusCode", equalTo("ACTIVE"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/scrapers/" + scraperId + "/logging-configuration")
                .then()
                .statusCode(200)
                .body("scraperId", equalTo(scraperId))
                .body("status.statusCode", equalTo("ACTIVE"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/scrapers/" + scraperId)
                .then()
                .statusCode(200)
                .body("status.statusCode", equalTo("DELETING"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/scrapers/" + scraperId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void scrapersAreIsolatedByAccount() {
        String first = auth("000000000304", REGION);
        String second = auth("000000000305", REGION);
        String blob = Base64.getEncoder().encodeToString("scrape_configs: []".getBytes(StandardCharsets.UTF_8));
        String body = """
                {
                  "scrapeConfiguration": { "configurationBlob": "%s" },
                  "source": { "vpcConfiguration": { "subnetIds": ["subnet-a"], "securityGroupIds": ["sg-a"] } },
                  "destination": { "ampConfiguration": { "workspaceArn": "arn:aws:aps:us-east-1:000000000304:workspace/ws" } }
                }
                """.formatted(blob);

        String firstId = given()
                .contentType("application/json")
                .header("Authorization", first)
                .body(body)
                .when()
                .post("/scrapers")
                .then()
                .statusCode(200)
                .extract().path("scraperId");

        given()
                .header("Authorization", second)
                .when()
                .get("/scrapers/" + firstId)
                .then()
                .statusCode(404);

        List<Map<String, Object>> listed = given()
                .header("Authorization", first)
                .when()
                .get("/scrapers")
                .then()
                .statusCode(200)
                .extract().path("scrapers");
        assertEquals(1, listed.size());
        assertEquals(firstId, listed.getFirst().get("scraperId"));
    }

    @Test
    void workspaceListCreateDescribeQueryAndDelete() {
        String authorization = auth("000000000401", REGION);
        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces")
                .then()
                .statusCode(200)
                .body("workspaces", org.hamcrest.Matchers.notNullValue());

        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "alias":"floci-amp-bindings",
                          "tags":{"Environment":"test"}
                        }
                        """)
                .when()
                .post("/workspaces")
                .then()
                .statusCode(200)
                .body("workspaceId", startsWith("ws-"))
                .body("status.statusCode", equalTo("ACTIVE"))
                .extract()
                .response();
        String workspaceId = created.path("workspaceId");
        String arn = created.path("arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId)
                .then()
                .statusCode(200)
                .body("workspace.workspaceId", equalTo(workspaceId))
                .body("workspace.status.statusCode", equalTo("ACTIVE"))
                .body("workspace.prometheusEndpoint", containsString("/workspaces/" + workspaceId + "/"))
                .body("workspace.prometheusEndpoint", containsString("aps-workspaces"))
                .body("workspace.prometheusEndpoint", containsString("http://"));

        given()
                .header("Authorization", authorization)
                .queryParam("alias", "floci-amp-bindings")
                .when()
                .get("/workspaces")
                .then()
                .statusCode(200)
                .body("workspaces.workspaceId", hasItem(workspaceId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        String metric = "alchemy_amp_bindings_test_total";
        byte[] payload = AmpRemoteWriteCodec.snappyCompress(AmpRemoteWriteCodec.encodeWriteRequest(List.of(
                new AmpRemoteWriteCodec.Series(
                        Map.of("__name__", metric, "source", "bindings-test"),
                        List.of(new AmpRemoteWriteCodec.Sample(2.0, System.currentTimeMillis()))))));
        given()
                .header("Authorization", authorization)
                .header("Content-Encoding", "snappy")
                .contentType("application/x-protobuf")
                .body(payload)
                .when()
                .post("/workspaces/" + workspaceId + "/api/v1/remote_write")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .header("Host", "aps-workspaces.us-east-1.amazonaws.com:4566")
                .header("Content-Encoding", "snappy")
                .contentType("application/x-protobuf")
                .body(payload)
                .when()
                .post("/workspaces/" + workspaceId + "/api/v1/remote_write")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .contentType("application/x-www-form-urlencoded")
                .formParam("query", metric)
                .when()
                .post("/workspaces/" + workspaceId + "/api/v1/query")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("data.resultType", equalTo("vector"))
                .body("data.result.size()", greaterThan(0))
                .body("data.result[0].metric.__name__", equalTo(metric));

        long end = System.currentTimeMillis() / 1000;
        given()
                .header("Authorization", authorization)
                .contentType("application/x-www-form-urlencoded")
                .formParam("query", metric)
                .formParam("start", Long.toString(end - 900))
                .formParam("end", Long.toString(end))
                .formParam("step", "30s")
                .when()
                .post("/workspaces/" + workspaceId + "/api/v1/query_range")
                .then()
                .statusCode(200)
                .body("data.resultType", equalTo("matrix"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/api/v1/labels")
                .then()
                .statusCode(200)
                .body("data", hasItem("__name__"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/api/v1/label/__name__/values")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("data", hasItem(metric));

        given()
                .header("Authorization", authorization)
                .queryParam("match[]", "{__name__=\"" + metric + "\"}")
                .when()
                .get("/workspaces/" + workspaceId + "/api/v1/series")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("data[0].__name__", equalTo(metric))
                .body("data[0].source", equalTo("bindings-test"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/api/v1/metadata")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("data", org.hamcrest.Matchers.notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/workspaces/" + workspaceId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/aps/aws4_request";
    }
}
