package io.github.hectorvent.floci.services.account;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/** Verifies AWS Account restJson1 Region opt-in Get/Enable/Disable/List. */
@QuarkusTest
class AccountRegionIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getRegionOptStatusReturnsEnabledByDefaultForUsEast1() {
        String authorization = auth("000000000501", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RegionName\":\"us-east-1\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(200)
                .body("RegionName", equalTo("us-east-1"))
                .body("RegionOptStatus", equalTo("ENABLED_BY_DEFAULT"));
    }

    @Test
    void getRegionOptStatusReturnsDisabledForAnOptInRegion() {
        String authorization = auth("000000000502", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RegionName\":\"ap-east-1\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(200)
                .body("RegionName", equalTo("ap-east-1"))
                .body("RegionOptStatus", equalTo("DISABLED"));
    }

    @Test
    void enableThenGetRoundTripsAnOptInRegionAndDisableRestoresDisabled() {
        String authorization = auth("000000000503", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RegionName\":\"ap-east-1\"}")
                .when()
                .post("/enableRegion")
                .then()
                .statusCode(200)
                .body(equalTo("{}"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RegionName\":\"ap-east-1\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(200)
                .body("RegionOptStatus", equalTo("ENABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RegionName\":\"ap-east-1\"}")
                .when()
                .post("/disableRegion")
                .then()
                .statusCode(200)
                .body(equalTo("{}"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RegionName\":\"ap-east-1\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(200)
                .body("RegionOptStatus", equalTo("DISABLED"));
    }

    @Test
    void disableRegionRejectsEnabledByDefaultRegions() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000504", EAST))
                .body("{\"RegionName\":\"us-east-1\"}")
                .when()
                .post("/disableRegion")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"))
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void enableRegionRejectsEnabledByDefaultRegions() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000505", EAST))
                .body("{\"RegionName\":\"us-east-1\"}")
                .when()
                .post("/enableRegion")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"))
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getRegionOptStatusRejectsUnknownRegionNames() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000506", EAST))
                .body("{\"RegionName\":\"not-a-region-1\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"));
    }

    @Test
    void listRegionsCanFilterToEnabledByDefault() {
        String authorization = auth("000000000507", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RegionOptStatusContains\":[\"ENABLED_BY_DEFAULT\"]}")
                .when()
                .post("/listRegions")
                .then()
                .statusCode(200)
                .body("Regions.RegionName", hasItem("us-east-1"))
                .body("Regions.RegionName", not(hasItem("ap-east-1")))
                .body("Regions.find { it.RegionName == 'us-east-1' }.RegionOptStatus",
                        equalTo("ENABLED_BY_DEFAULT"));
    }

    @Test
    void listRegionsHonorsMaxResultsAndNextToken() {
        String authorization = auth("000000000508", EAST);
        String nextToken = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"MaxResults\":2}")
                .when()
                .post("/listRegions")
                .then()
                .statusCode(200)
                .body("Regions.size()", equalTo(2))
                .extract()
                .path("NextToken");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"MaxResults\":2,\"NextToken\":\"" + nextToken + "\"}")
                .when()
                .post("/listRegions")
                .then()
                .statusCode(200)
                .body("Regions.size()", equalTo(2))
                .body("NextToken", not(nullValue()));
    }

    @Test
    void regionOptStatusIsIsolatedPerAccount() {
        String first = auth("000000000509", EAST);
        String second = auth("000000000510", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", first)
                .body("{\"RegionName\":\"ap-east-1\"}")
                .when()
                .post("/enableRegion")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", second)
                .body("{\"RegionName\":\"ap-east-1\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(200)
                .body("RegionOptStatus", equalTo("DISABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", first)
                .body("{\"RegionName\":\"ap-east-1\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(200)
                .body("RegionOptStatus", equalTo("ENABLED"));
    }

    @Test
    void enableRegionWithAccountIdTargetsThatAccount() {
        String caller = auth("000000000511", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", caller)
                .body("{\"RegionName\":\"me-south-1\",\"AccountId\":\"000000000512\"}")
                .when()
                .post("/enableRegion")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", caller)
                .body("{\"RegionName\":\"me-south-1\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(200)
                .body("RegionOptStatus", equalTo("DISABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", caller)
                .body("{\"RegionName\":\"me-south-1\",\"AccountId\":\"000000000512\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(200)
                .body("RegionOptStatus", equalTo("ENABLED"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/account/aws4_request";
    }
}
