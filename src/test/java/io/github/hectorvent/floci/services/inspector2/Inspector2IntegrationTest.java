package io.github.hectorvent.floci.services.inspector2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * Verifies Inspector2 restJson1 account scan enablement — the operations Alchemy
 * {@code Enabler.test.ts} drives.
 */
@QuarkusTest
class Inspector2IntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000701";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void batchGetAccountStatusOnAFreshAccountIsDisabled() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000702", EAST))
                .body("{}")
                .when()
                .post("/status/batch/get")
                .then()
                .statusCode(200)
                .body("accounts", hasSize(1))
                .body("accounts[0].accountId", equalTo("000000000702"))
                .body("accounts[0].state.status", equalTo("DISABLED"))
                .body("accounts[0].resourceState.ec2.status", equalTo("DISABLED"))
                .body("accounts[0].resourceState.ecr.status", equalTo("DISABLED"))
                .body("accounts[0].resourceState.lambda.status", equalTo("DISABLED"));
    }

    @Test
    void enableEc2AndEcrThenAddLambdaThenDisable() {
        String authorization = auth(ACCOUNT, EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"accountIds\":[\"" + ACCOUNT + "\"],\"resourceTypes\":[\"EC2\",\"ECR\"]}")
                .when()
                .post("/enable")
                .then()
                .statusCode(200)
                .body("accounts", hasSize(1))
                .body("accounts[0].accountId", equalTo(ACCOUNT))
                .body("accounts[0].status", equalTo("ENABLED"))
                .body("accounts[0].resourceStatus.ec2", equalTo("ENABLED"))
                .body("accounts[0].resourceStatus.ecr", equalTo("ENABLED"))
                .body("accounts[0].resourceStatus.lambda", equalTo("DISABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"accountIds\":[\"" + ACCOUNT + "\"]}")
                .when()
                .post("/status/batch/get")
                .then()
                .statusCode(200)
                .body("accounts[0].state.status", equalTo("ENABLED"))
                .body("accounts[0].resourceState.ec2.status", equalTo("ENABLED"))
                .body("accounts[0].resourceState.ecr.status", equalTo("ENABLED"))
                .body("accounts[0].resourceState.lambda.status", equalTo("DISABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"accountIds\":[\"" + ACCOUNT + "\"],\"resourceTypes\":[\"LAMBDA\"]}")
                .when()
                .post("/enable")
                .then()
                .statusCode(200)
                .body("accounts[0].resourceStatus.lambda", equalTo("ENABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"accountIds\":[\"" + ACCOUNT + "\"]}")
                .when()
                .post("/status/batch/get")
                .then()
                .statusCode(200)
                .body("accounts[0].resourceState.lambda.status", equalTo("ENABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"accountIds\":[\"" + ACCOUNT + "\"],\"resourceTypes\":[\"EC2\",\"ECR\",\"LAMBDA\"]}")
                .when()
                .post("/disable")
                .then()
                .statusCode(200)
                .body("accounts[0].status", equalTo("DISABLED"))
                .body("accounts[0].resourceStatus.ec2", equalTo("DISABLED"))
                .body("accounts[0].resourceStatus.ecr", equalTo("DISABLED"))
                .body("accounts[0].resourceStatus.lambda", equalTo("DISABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/status/batch/get")
                .then()
                .statusCode(200)
                .body("accounts[0].state.status", not(equalTo("ENABLED")))
                .body("accounts[0].resourceState.ec2.status", not(equalTo("ENABLED")))
                .body("accounts[0].resourceState.ecr.status", not(equalTo("ENABLED")))
                .body("accounts[0].resourceState.lambda.status", not(equalTo("ENABLED")));
    }

    @Test
    void enableWithoutResourceTypesIsValidationException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000703", EAST))
                .body("{\"accountIds\":[\"000000000703\"]}")
                .when()
                .post("/enable")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void enableWithUnknownScanTypeIsValidationException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000704", EAST))
                .body("{\"resourceTypes\":[\"NETWORK\"]}")
                .when()
                .post("/enable")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/inspector2/aws4_request";
    }
}
