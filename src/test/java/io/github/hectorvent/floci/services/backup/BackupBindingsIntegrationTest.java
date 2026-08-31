package io.github.hectorvent.floci.services.backup;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Binding-surface operations Alchemy Backup Bindings exercise: empty list
 * round-trips and typed not-found errors for restore/copy/recovery-point APIs.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupBindingsIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/backup/aws4_request";
    private static final String VAULT_NAME = "bindings-ops-vault";
    private static final String BOGUS_JOB_ID = "00000000-0000-0000-0000-000000000000";
    private static final String BOGUS_RP_ARN =
            "arn:aws:ec2:us-east-1::snapshot/snap-00000000000000000";

    @Test
    @Order(10)
    void createVault() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{}")
        .when()
            .put("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(anyOf(is(200), is(400)));
    }

    @Test
    @Order(20)
    void listRestoreJobsReturnsEmptyArray() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/restore-jobs")
        .then()
            .statusCode(200)
            .body("RestoreJobs", notNullValue());
    }

    @Test
    @Order(21)
    void listCopyJobsReturnsEmptyArray() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/copy-jobs")
        .then()
            .statusCode(200)
            .body("CopyJobs", notNullValue());
    }

    @Test
    @Order(22)
    void listProtectedResourcesReturnsResultsArray() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/resources")
        .then()
            .statusCode(200)
            .body("Results", notNullValue());
    }

    @Test
    @Order(23)
    void listBackupJobsWithoutTrailingSlash() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-jobs")
        .then()
            .statusCode(200)
            .body("BackupJobs", notNullValue());
    }

    @Test
    @Order(24)
    void listRecoveryPointsWithoutTrailingSlash() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME + "/recovery-points")
        .then()
            .statusCode(200)
            .body("RecoveryPoints", notNullValue());
    }

    @Test
    @Order(30)
    void describeBackupJobUnknownIdIsResourceNotFound() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-jobs/" + BOGUS_JOB_ID)
        .then()
            .statusCode(anyOf(is(400), is(404)))
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(31)
    void describeRecoveryPointUnknownArnIsResourceNotFound() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME + "/recovery-points/" + BOGUS_RP_ARN)
        .then()
            .statusCode(anyOf(is(400), is(404)))
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(32)
    void getRecoveryPointRestoreMetadataUnknownArnIsResourceNotFound() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME + "/recovery-points/" + BOGUS_RP_ARN + "/restore-metadata")
        .then()
            .statusCode(anyOf(is(400), is(404)))
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(33)
    void getRestoreJobMetadataUnknownIdIsResourceNotFound() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/restore-jobs/" + BOGUS_JOB_ID + "/metadata")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(34)
    void putRestoreValidationUnknownIdIsTypedError() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"ValidationStatus\":\"SUCCESSFUL\"}")
        .when()
            .put("/restore-jobs/" + BOGUS_JOB_ID + "/validations")
        .then()
            .statusCode(anyOf(is(400), is(404)))
            .body("__type", anyOf(
                    equalTo("ResourceNotFoundException"),
                    equalTo("InvalidParameterValueException"),
                    equalTo("MissingParameterValueException"),
                    equalTo("InvalidRequestException")));
    }

    @Test
    @Order(35)
    void stopBackupJobUnknownIdIsTypedError() {
        given()
            .header("Authorization", AUTH)
        .when()
            .post("/backup-jobs/" + BOGUS_JOB_ID)
        .then()
            .statusCode(anyOf(is(400), is(404)))
            .body("__type", anyOf(
                    equalTo("ResourceNotFoundException"),
                    equalTo("InvalidParameterValueException"),
                    equalTo("InvalidRequestException")));
    }

    @Test
    @Order(36)
    void startRestoreJobUnknownRecoveryPointIsTypedError() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "RecoveryPointArn": "%s",
                  "IamRoleArn": "arn:aws:iam::000000000000:role/backup-role",
                  "Metadata": {}
                }
                """.formatted(BOGUS_RP_ARN))
        .when()
            .put("/restore-jobs")
        .then()
            .statusCode(anyOf(is(400), is(404)))
            .body("__type", anyOf(
                    equalTo("ResourceNotFoundException"),
                    equalTo("InvalidParameterValueException"),
                    equalTo("MissingParameterValueException"),
                    equalTo("InvalidRequestException")));
    }

    @Test
    @Order(90)
    void deleteVault() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(anyOf(is(204), is(400), is(404)));
    }
}
