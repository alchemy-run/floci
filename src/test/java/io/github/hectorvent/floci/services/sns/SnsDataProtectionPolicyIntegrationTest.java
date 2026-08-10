package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Integration tests for SNS GetDataProtectionPolicy / PutDataProtectionPolicy
 * over both the query (form-encoded) and JSON protocol paths.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SnsDataProtectionPolicyIntegrationTest {

    private static final String SNS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String POLICY =
            "{\"Name\":\"data_protection_policy\",\"Version\":\"2021-06-01\",\"Statement\":[]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String topicArn;

    @Test
    @Order(1)
    void createTopic() {
        topicArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "dpp-integration-topic")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
    }

    @Test
    @Order(2)
    void getDataProtectionPolicy_query_emptyWhenUnset() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetDataProtectionPolicy")
            .formParam("ResourceArn", topicArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<GetDataProtectionPolicyResult>"))
            .body("GetDataProtectionPolicyResponse.GetDataProtectionPolicyResult.DataProtectionPolicy",
                    equalTo(""));
    }

    @Test
    @Order(3)
    void putDataProtectionPolicy_query_thenGetRoundTrips() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "PutDataProtectionPolicy")
            .formParam("ResourceArn", topicArn)
            .formParam("DataProtectionPolicy", POLICY)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PutDataProtectionPolicyResponse"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetDataProtectionPolicy")
            .formParam("ResourceArn", topicArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetDataProtectionPolicyResponse.GetDataProtectionPolicyResult.DataProtectionPolicy",
                    equalTo(POLICY));
    }

    @Test
    @Order(4)
    void getDataProtectionPolicy_json_returnsStoredPolicy() {
        given()
            .contentType(SNS_CONTENT_TYPE)
            .header("X-Amz-Target", "SNS_20100331.GetDataProtectionPolicy")
            .body("{\"ResourceArn\":\"" + topicArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DataProtectionPolicy", equalTo(POLICY));
    }

    @Test
    @Order(5)
    void putDataProtectionPolicy_json_clearsWithEmptyString() {
        given()
            .contentType(SNS_CONTENT_TYPE)
            .header("X-Amz-Target", "SNS_20100331.PutDataProtectionPolicy")
            .body("{\"ResourceArn\":\"" + topicArn + "\",\"DataProtectionPolicy\":\"\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(SNS_CONTENT_TYPE)
            .header("X-Amz-Target", "SNS_20100331.GetDataProtectionPolicy")
            .body("{\"ResourceArn\":\"" + topicArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DataProtectionPolicy", equalTo(""));
    }

    @Test
    @Order(6)
    void getDataProtectionPolicy_query_missingTopicReturns404() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetDataProtectionPolicy")
            .formParam("ResourceArn", "arn:aws:sns:us-east-1:000000000000:dpp-no-such-topic")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("ResourceNotFoundException"));
    }
}
