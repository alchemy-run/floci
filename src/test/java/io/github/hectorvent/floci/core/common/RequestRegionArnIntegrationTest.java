package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;

/**
 * Resources created by a request signed for a non-default region carry that region in their ARN,
 * not the emulator's default region.
 */
@QuarkusTest
class RequestRegionArnIntegrationTest {

    private static final String REGION = "eu-west-1";

    private static final String OTHER_REGION = "us-west-2";

    private static String auth(String service) {
        return auth(REGION, service);
    }

    private static String auth(String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    @Test
    void appSyncDomainNameUsesTheRequestRegionAndChannelNamespaceItsApisRegion() {
        String domain = "region-arn-" + System.nanoTime() + ".example.com";
        given()
                .header("Authorization", auth("appsync"))
                .contentType("application/json")
                .body("""
                    {"domainName": "%s", "certificateArn": "arn:aws:acm:%s:000000000000:certificate/1"}
                    """.formatted(domain, REGION))
            .when()
                .post("/v1/domainnames")
            .then()
                .statusCode(200)
                .body("domainNameConfig.domainNameArn", startsWith("arn:aws:appsync:" + REGION + ":"))
                .body("domainNameConfig.appsyncDomainName", endsWith(".cloudfront.net"));
        given().header("Authorization", auth("appsync")).delete("/v1/domainnames/" + domain)
                .then().statusCode(204);

        String apiId = given()
                .header("Authorization", auth("appsync"))
                .contentType("application/json")
                .body("{\"name\": \"region-arn-api\", \"authenticationType\": \"API_KEY\"}")
            .when()
                .post("/v1/apis")
            .then()
                .statusCode(200)
                .extract().path("graphqlApi.apiId");
        // A namespace lives in its API's region, even when the request is signed for another one.
        given()
                .header("Authorization", auth(OTHER_REGION, "appsync"))
                .contentType("application/json")
                .body("{\"name\": \"region-arn-ns\"}")
            .when()
                .post("/v2/apis/" + apiId + "/channelNamespaces")
            .then()
                .statusCode(200)
                .body("channelNamespace.channelNamespaceArn", startsWith("arn:aws:appsync:" + REGION + ":"));
        given().header("Authorization", auth("appsync"))
                .delete("/v2/apis/" + apiId + "/channelNamespaces/region-arn-ns").then().statusCode(204);
        given().header("Authorization", auth("appsync")).delete("/v1/apis/" + apiId).then().statusCode(204);
    }
}
