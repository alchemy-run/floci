package io.github.hectorvent.floci.services.opensearch;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;

/**
 * Exercises the OpenSearch REST data plane Alchemy's DataPlane.test.ts drives
 * through DomainRead / DomainWrite / DomainReadWrite (index, bulk, update,
 * get, missing get, HEAD exists, search-via-source, count, cluster health,
 * delete + not_found).
 */
@QuarkusTest
class OpenSearchDataPlaneIntegrationTest {

    private static final String DOMAIN = "dp-songs";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/es/aws4_request";
    private static final String SONG = "{\"title\":\"The Wind Cries Mary\",\"plays\":1}";

    @AfterEach
    void cleanup() {
        given().header("Authorization", AUTH)
                .when().delete("/2021-01-01/opensearch/domain/" + DOMAIN);
    }

    @Test
    void dataPlaneRoundTripMatchesAlchemyBindings() {
        String endpoint = given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"DomainName\":\"" + DOMAIN + "\",\"EngineVersion\":\"OpenSearch_2.19\"}")
                .when().post("/2021-01-01/opensearch/domain")
                .then().statusCode(200)
                .body("DomainStatus.DomainName", equalTo(DOMAIN))
                .body("DomainStatus.Endpoint", containsString("search-" + DOMAIN + "-"))
                .body("DomainStatus.Endpoint", containsString(".es.amazonaws.com"))
                .extract().path("DomainStatus.Endpoint");

        given().contentType("application/json")
                .header("Authorization", AUTH)
                .header("Host", endpoint)
                .body(SONG)
                .when().put("/songs/_doc/1?refresh=true")
                .then().statusCode(200)
                .body("result", is(oneOf("created", "updated")));

        given().contentType("text/plain")
                .header("Authorization", AUTH)
                .header("Host", endpoint)
                .body("{\"index\":{\"_index\":\"songs\",\"_id\":\"2\"}}\n{\"title\":\"Purple Haze\",\"plays\":5}\n")
                .when().post("/_bulk?refresh=true")
                .then().statusCode(200)
                .body("errors", equalTo(false))
                .body("items.size()", equalTo(1));

        given().contentType("application/json")
                .header("Authorization", AUTH)
                .header("Host", endpoint)
                .body("{\"doc\":{\"plays\":2}}")
                .when().post("/songs/_update/1?refresh=true")
                .then().statusCode(200)
                .body("result", is(oneOf("updated", "noop")));

        given().header("Authorization", AUTH)
                .header("Host", endpoint)
                .when().get("/songs/_doc/1")
                .then().statusCode(200)
                .body("found", equalTo(true))
                .body("_source.title", equalTo("The Wind Cries Mary"));

        given().header("Authorization", AUTH)
                .header("Host", endpoint)
                .when().get("/songs/_doc/missing")
                .then().statusCode(404)
                .body("found", equalTo(false));

        given().header("Authorization", AUTH)
                .header("Host", endpoint)
                .when().head("/songs/_doc/1")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .header("Host", endpoint)
                .when().head("/songs/_doc/missing")
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .header("Host", endpoint)
                .queryParam("source", "{\"query\":{\"match\":{\"title\":\"wind\"}}}")
                .queryParam("source_content_type", "application/json")
                .when().get("/songs/_search")
                .then().statusCode(200)
                .body("hits.total.value", greaterThanOrEqualTo(1))
                .body("hits.hits[0]._source.title", equalTo("The Wind Cries Mary"));

        given().header("Authorization", AUTH)
                .header("Host", endpoint)
                .when().get("/songs/_count")
                .then().statusCode(200)
                .body("count", greaterThanOrEqualTo(1));

        given().header("Authorization", AUTH)
                .header("Host", endpoint)
                .when().get("/_cluster/health")
                .then().statusCode(200)
                .body("status", is(oneOf("green", "yellow", "red")));

        given().header("Authorization", AUTH)
                .header("Host", endpoint)
                .when().delete("/songs/_doc/2?refresh=true")
                .then().statusCode(200)
                .body("result", equalTo("deleted"));

        given().header("Authorization", AUTH)
                .header("Host", endpoint)
                .when().delete("/songs/_doc/2?refresh=true")
                .then().statusCode(404)
                .body("result", equalTo("not_found"));
    }

    @Test
    void pathStylePrefixAlsoServesDataPlane() {
        given().contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"DomainName\":\"" + DOMAIN + "\",\"EngineVersion\":\"OpenSearch_2.19\"}")
                .when().post("/2021-01-01/opensearch/domain")
                .then().statusCode(200);

        given().contentType("application/json")
                .header("Authorization", AUTH)
                .body(SONG)
                .when().put("/_floci/opensearch/" + DOMAIN + "/songs/_doc/1")
                .then().statusCode(200)
                .body("result", is(oneOf("created", "updated")));

        given().header("Authorization", AUTH)
                .when().get("/_floci/opensearch/" + DOMAIN + "/songs/_doc/1")
                .then().statusCode(200)
                .body("found", equalTo(true));
    }
}
