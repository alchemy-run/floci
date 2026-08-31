package io.github.hectorvent.floci.services.mediaconnect;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies MediaConnect restJson1 flow, source, output, and tag lifecycle. */
@QuarkusTest
class MediaConnectIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeFlowOnANonexistentFlowArnFailsWithNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/v1/flows/" + encode(
                        "arn:aws:mediaconnect:us-east-1:000000000000:flow:1-00000000000000000000000000000000:missing"))
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void createDescribeUpdateSourceAddRemoveOutputTagAndDeleteLifecycle() {
        String authorization = auth(EAST);

        String flowArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"Broadcast",
                          "source":{
                            "name":"primary",
                            "protocol":"rtp",
                            "whitelistCidr":"10.24.34.0/23",
                            "ingestPort":5000
                          },
                          "flowTags":{"fixture":"mediaconnect-flow"}
                        }
                        """)
                .when()
                .post("/v1/flows")
                .then()
                .statusCode(200)
                .body("flow.flowArn", notNullValue())
                .body("flow.status", equalTo("STANDBY"))
                .body("flow.availabilityZone", equalTo("us-east-1a"))
                .body("flow.source.name", equalTo("primary"))
                .body("flow.source.whitelistCidr", equalTo("10.24.34.0/23"))
                .body("flow.source.ingestPort", equalTo(5000))
                .body("flow.source.sourceArn", notNullValue())
                .body("flow.outputs.size()", equalTo(0))
                .extract()
                .path("flow.flowArn");

        String sourceArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/flows/" + encode(flowArn))
                .then()
                .statusCode(200)
                .body("flow.status", equalTo("STANDBY"))
                .body("flow.source.whitelistCidr", equalTo("10.24.34.0/23"))
                .extract()
                .path("flow.source.sourceArn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/flows")
                .then()
                .statusCode(200)
                .body("flows.find { it.flowArn == '" + flowArn + "' }.name", equalTo("Broadcast"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(flowArn))
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("mediaconnect-flow"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"whitelistCidr\":\"10.24.32.0/20\"}")
                .when()
                .put("/v1/flows/" + encode(flowArn) + "/source/" + encode(sourceArn))
                .then()
                .statusCode(200)
                .body("source.whitelistCidr", equalTo("10.24.32.0/20"));

        String outputArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "outputs":[{
                            "name":"affiliate-east",
                            "protocol":"rtp",
                            "destination":"198.51.100.11",
                            "port":5010
                          }]
                        }
                        """)
                .when()
                .post("/v1/flows/" + encode(flowArn) + "/outputs")
                .then()
                .statusCode(200)
                .body("outputs[0].name", equalTo("affiliate-east"))
                .body("outputs[0].destination", equalTo("198.51.100.11"))
                .body("outputs[0].port", equalTo(5010))
                .extract()
                .path("outputs[0].outputArn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/flows/" + encode(flowArn))
                .then()
                .statusCode(200)
                .body("flow.outputs.name", hasItem("affiliate-east"))
                .body("flow.source.whitelistCidr", equalTo("10.24.32.0/20"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"stage\":\"updated\"}}")
                .when()
                .post("/tags/" + encode(flowArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(flowArn))
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("mediaconnect-flow"))
                .body("tags.stage", equalTo("updated"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/tags/" + encode(flowArn) + "?tagKeys=stage")
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/flows/" + encode(flowArn) + "/outputs/" + encode(outputArn))
                .then()
                .statusCode(200)
                .body("outputArn", equalTo(outputArn));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/flows/" + encode(flowArn))
                .then()
                .statusCode(200)
                .body("flow.outputs.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/flows/" + encode(flowArn))
                .then()
                .statusCode(200)
                .body("status", equalTo("DELETING"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/flows/" + encode(flowArn))
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/mediaconnect/aws4_request";
    }
}
