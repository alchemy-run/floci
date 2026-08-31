package io.github.hectorvent.floci.services.mediaconnect;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Binding-plane MediaConnect ops the alchemy Bindings suite exercises:
 * idle source metadata/thumbnail, StopFlow on STANDBY, and entitlement grant/revoke.
 */
@QuarkusTest
class MediaConnectBindingsIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void idleObservabilityStopAndEntitlementRoundtrip() {
        String name = "bindings-" + UUID.randomUUID().toString().substring(0, 8);
        String authorization = auth(EAST);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "source":{
                            "name":"primary",
                            "protocol":"rtp",
                            "whitelistCidr":"10.24.34.0/23",
                            "ingestPort":5000
                          }
                        }
                        """.formatted(name))
                .when()
                .post("/v1/flows")
                .then()
                .statusCode(200)
                .body("flow.status", equalTo("STANDBY"))
                .body("flow.source.name", equalTo("primary"))
                .extract().path("flow.flowArn");

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", authorization)
                .when()
                .get("/v1/flows/" + encode(arn) + "/source-metadata")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", authorization)
                .when()
                .get("/v1/flows/" + encode(arn) + "/source-thumbnail")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", authorization)
                .when()
                .post("/v1/flows/stop/" + encode(arn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/entitlements")
                .then()
                .statusCode(200)
                .body("entitlements.size()", greaterThanOrEqualTo(0));

        String entitlementArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .urlEncodingEnabled(false)
                .body("""
                        {
                          "entitlements":[{
                            "name":"alchemy-binding-test",
                            "subscribers":["000000000000"]
                          }]
                        }
                        """)
                .when()
                .post("/v1/flows/" + encode(arn) + "/entitlements")
                .then()
                .statusCode(200)
                .body("entitlements[0].entitlementArn", notNullValue())
                .extract().path("entitlements[0].entitlementArn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/entitlements")
                .then()
                .statusCode(200)
                .body("entitlements.size()", greaterThanOrEqualTo(1));

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", authorization)
                .when()
                .delete("/v1/flows/" + encode(arn) + "/entitlements/" + encode(entitlementArn))
                .then()
                .statusCode(200)
                .body("entitlementArn", equalTo(entitlementArn));

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", authorization)
                .when()
                .delete("/v1/flows/" + encode(arn))
                .then()
                .statusCode(200);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/mediaconnect/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
