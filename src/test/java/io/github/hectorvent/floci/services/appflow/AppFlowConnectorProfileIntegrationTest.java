package io.github.hectorvent.floci.services.appflow;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies AppFlow restJson1 connector-profile operations used by Alchemy
 * {@code ConnectorProfile.test.ts}: unreachable Salesforce create fails with a
 * typed error, and describe of an unknown name returns an empty list.
 */
@QuarkusTest
class AppFlowConnectorProfileIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String SALESFORCE_CREATE = """
            {
              "connectorProfileName":"alchemy-test-appflow-cp-probe",
              "connectorType":"Salesforce",
              "connectionMode":"Public",
              "connectorProfileConfig":{
                "connectorProfileProperties":{
                  "Salesforce":{"instanceUrl":"https://invalid-example.my.salesforce.com"}
                },
                "connectorProfileCredentials":{
                  "Salesforce":{"accessToken":"bogus-access-token","refreshToken":"bogus-refresh-token"}
                }
              }
            }
            """;
    private static final String SINGULAR_CREATE = """
            {
              "connectorProfileName":"%s",
              "connectorType":"Singular",
              "connectionMode":"Public",
              "connectorProfileConfig":{
                "connectorProfileProperties":{"Singular":{}}
              }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createSalesforceWithAnUnreachableConnectorFailsWithConnectorServerException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000501", EAST))
                .body(SALESFORCE_CREATE)
                .when()
                .post("/create-connector-profile")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ConnectorServerException"))
                .body("__type", equalTo("ConnectorServerException"));
    }

    @Test
    void describeConnectorProfilesReturnsAnEmptyListForAnUnknownName() {
        List<Map<String, Object>> details = given()
                .contentType("application/json")
                .header("Authorization", auth("000000000502", EAST))
                .body("{\"connectorProfileNames\":[\"alchemy-test-appflow-cp-missing\"]}")
                .when()
                .post("/describe-connector-profiles")
                .then()
                .statusCode(200)
                .extract()
                .path("connectorProfileDetails");
        assertTrue(details == null || details.isEmpty());
    }

    @Test
    void connectorProfileCreateDescribeUpdateDeleteLifecycle() {
        String authorization = auth("000000000503", EAST);
        String name = "lifecycle-singular";
        String arn = create(authorization, SINGULAR_CREATE.formatted(name));
        assertTrue(arn.contains(":appflow:"));
        assertTrue(arn.endsWith(":connectorprofile/" + name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"connectorProfileNames\":[\"" + name + "\"]}")
                .when()
                .post("/describe-connector-profiles")
                .then()
                .statusCode(200)
                .body("connectorProfileDetails.size()", equalTo(1))
                .body("connectorProfileDetails[0].connectorProfileName", equalTo(name))
                .body("connectorProfileDetails[0].connectorProfileArn", equalTo(arn))
                .body("connectorProfileDetails[0].connectorType", equalTo("Singular"))
                .body("connectorProfileDetails[0].connectionMode", equalTo("Public"))
                .body("connectorProfileDetails[0].credentialsArn", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "connectorProfileName":"%s",
                          "connectionMode":"Private",
                          "connectorProfileConfig":{
                            "connectorProfileProperties":{"Singular":{}}
                          }
                        }
                        """.formatted(name))
                .when()
                .post("/update-connector-profile")
                .then()
                .statusCode(200)
                .body("connectorProfileArn", equalTo(arn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"connectorProfileNames\":[\"" + name + "\"]}")
                .when()
                .post("/describe-connector-profiles")
                .then()
                .statusCode(200)
                .body("connectorProfileDetails[0].connectionMode", equalTo("Private"))
                .body("connectorProfileDetails[0].connectorProfileArn", equalTo(arn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"connectorProfileName\":\"" + name + "\",\"forceDelete\":true}")
                .when()
                .post("/delete-connector-profile")
                .then()
                .statusCode(200);

        List<Map<String, Object>> gone = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"connectorProfileNames\":[\"" + name + "\"]}")
                .when()
                .post("/describe-connector-profiles")
                .then()
                .statusCode(200)
                .extract()
                .path("connectorProfileDetails");
        assertTrue(gone == null || gone.isEmpty());
    }

    @Test
    void deleteOfAMissingProfileFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000504", EAST))
                .body("{\"connectorProfileName\":\"missing-profile\"}")
                .when()
                .post("/delete-connector-profile")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void connectorProfilesAreIsolatedByAccount() {
        String first = auth("000000000505", EAST);
        String second = auth("000000000506", EAST);
        String name = "shared-profile";

        String firstArn = create(first, SINGULAR_CREATE.formatted(name));
        String secondArn = create(second, SINGULAR_CREATE.formatted(name));
        assertNotEquals(firstArn, secondArn);

        describe(first, name).then().body("connectorProfileDetails.size()", equalTo(1));
        describe(second, name).then().body("connectorProfileDetails.size()", equalTo(1));
        describe(first, name).then()
                .body("connectorProfileDetails[0].connectorProfileArn", equalTo(firstArn));
    }

    @Test
    void connectorProfilesAreIsolatedByRegion() {
        String east = auth("000000000507", EAST);
        String west = auth("000000000507", WEST);
        String name = "regional-profile";

        create(east, SINGULAR_CREATE.formatted(name));
        create(west, SINGULAR_CREATE.formatted(name));

        describe(east, name).then().body("connectorProfileDetails.size()", equalTo(1));
        describe(west, name).then().body("connectorProfileDetails.size()", equalTo(1));
        describe(east, name).then()
                .body("connectorProfileDetails[0].connectorProfileArn",
                        equalTo("arn:aws:appflow:" + EAST + ":000000000507:connectorprofile/" + name));
        describe(west, name).then()
                .body("connectorProfileDetails[0].connectorProfileArn",
                        equalTo("arn:aws:appflow:" + WEST + ":000000000507:connectorprofile/" + name));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/appflow/aws4_request";
    }

    private static String create(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/create-connector-profile")
                .then()
                .statusCode(200)
                .body("connectorProfileArn", notNullValue())
                .extract()
                .path("connectorProfileArn");
    }

    private static Response describe(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"connectorProfileNames\":[\"" + name + "\"]}")
                .when()
                .post("/describe-connector-profiles")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }
}
