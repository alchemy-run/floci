package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.backup.model.BackupVault;
import io.github.hectorvent.floci.services.backup.model.RecoveryPoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for AWS Backup via REST JSON protocol.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/backup/aws4_request";
    private static final String VAULT_NAME = "test-vault";
    private static final String IAM_ROLE = "arn:aws:iam::000000000000:role/backup-role";
    private static final String RESOURCE_ARN = "arn:aws:dynamodb:us-east-1:000000000000:table/my-table";

    private static String planId;
    private static String selectionId;
    private static String jobId;
    private static String recoveryPointArn;

    @Inject
    StorageFactory storageFactory;

    // ── Vault ──────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void createBackupVault() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"BackupVaultTags\":{\"env\":\"test\"}}")
        .when()
            .put("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(200)
            .body("BackupVaultName", equalTo(VAULT_NAME))
            .body("BackupVaultArn", containsString("backup-vault:" + VAULT_NAME));
    }

    @Test
    @Order(11)
    void describeBackupVault() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(200)
            .body("BackupVaultName", equalTo(VAULT_NAME))
            .body("NumberOfRecoveryPoints", equalTo(0));
    }

    @Test
    @Order(12)
    void listBackupVaults() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/")
        .then()
            .statusCode(200)
            .body("BackupVaultList", hasSize(greaterThanOrEqualTo(1)))
            .body("BackupVaultList[0].BackupVaultName", notNullValue());
    }

    @Test
    @Order(13)
    void createVaultAlreadyExistsReturns400() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{}")
        .when()
            .put("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(400);
    }

    @Test
    @Order(14)
    void getBackupVaultNotificationsReturnsResourceNotFound() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME + "/notification-configuration")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(15)
    void getBackupVaultAccessPolicyReturnsResourceNotFound() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME + "/access-policy")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(16)
    void vaultPolicyAndNotificationsRoundTripAndDelete() {
        String base = "/backup-vaults/" + VAULT_NAME;
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":\"*\",\"Action\":\"backup:DeleteRecoveryPoint\",\"Resource\":\"*\"}]}";
        given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("Policy", policy)).put(base + "/access-policy").then().statusCode(200);
        given().header("Authorization", AUTH).get(base + "/access-policy").then().statusCode(200)
                .body("Policy", equalTo(policy)).body("BackupVaultName", equalTo(VAULT_NAME));
        given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("Policy", "not json")).put(base + "/access-policy").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
        given().header("Authorization", AUTH).get(base + "/access-policy").then().body("Policy", equalTo(policy));
        for (int i = 0; i < 2; i++) {
            given().header("Authorization", AUTH).delete(base + "/access-policy").then().statusCode(200);
        }
        given().header("Authorization", AUTH).get(base + "/access-policy").then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        String topic = "arn:aws:sns:us-east-1:000000000000:backup-events";
        given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("SNSTopicArn", topic, "BackupVaultEvents", List.of("BACKUP_JOB_FAILED")))
                .put(base + "/notification-configuration").then().statusCode(200);
        given().header("Authorization", AUTH).get(base + "/notification-configuration").then().statusCode(200)
                .body("SNSTopicArn", equalTo(topic)).body("BackupVaultEvents", contains("BACKUP_JOB_FAILED"));
        given().header("Authorization", AUTH).delete(base + "/notification-configuration").then().statusCode(200);
        given().header("Authorization", AUTH).get(base + "/notification-configuration").then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(17)
    void missingJobRoutesReturnBackupJsonErrors() {
        for (String path : List.of("/restore-jobs/missing", "/restore-jobs/missing/metadata", "/copy-jobs/missing",
                "/resources/arn:aws:dynamodb:us-east-1:000000000000:table/missing",
                "/backup-vaults/" + VAULT_NAME + "/recovery-points/arn:aws:ec2:us-east-1::snapshot/missing/restore-metadata")) {
            given().header("Authorization", AUTH).get(path).then().statusCode(anyOf(is(400), is(404)))
                    .contentType("application/json").body("__type", equalTo("ResourceNotFoundException"));
        }
        given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("ValidationStatus", "SUCCESSFUL"))
                .put("/restore-jobs/missing/validations").then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("RecoveryPointArn", "arn:aws:ec2:us-east-1::snapshot/missing", "Metadata", Map.of(), "IamRoleArn", IAM_ROLE))
                .put("/restore-jobs").then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("SourceBackupVaultName", VAULT_NAME, "RecoveryPointArn", "arn:aws:ec2:us-east-1::snapshot/missing",
                        "DestinationBackupVaultArn", "arn:aws:backup:us-east-1:000000000000:backup-vault:destination", "IamRoleArn", IAM_ROLE))
                .put("/copy-jobs").then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        for (String path : List.of("/restore-jobs", "/copy-jobs", "/resources")) {
            given().header("Authorization", AUTH).queryParam("maxResults", 25).get(path).then().statusCode(200)
                    .contentType("application/json");
            given().header("Authorization", AUTH).queryParam("maxResults", 0).get(path).then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterValueException"));
        }
    }

    @Test
    @Order(18)
    void storedRestoreAndCopyJobsAreFilteredPagedAndValidated() {
        StorageBackend<String, Map<String, Object>> restores = storageFactory.create("backup", "backup-restore-jobs.json", new TypeReference<>() {});
        StorageBackend<String, Map<String, Object>> copies = storageFactory.create("backup", "backup-copy-jobs.json", new TypeReference<>() {});
        String vaultArn = "arn:aws:backup:us-east-1:000000000000:backup-vault:" + VAULT_NAME;
        try {
            for (int i = 0; i < 2; i++) {
                Map<String, Object> job = new LinkedHashMap<>();
                job.put("RestoreJobId", "stored-restore-" + i);
                job.put("BackupVaultArn", vaultArn);
                job.put("Status", "COMPLETED");
                job.put("ResourceType", "DynamoDB");
                job.put("CreationDate", 100 + i);
                job.put("CreatedBy", Map.of("RestoreTestingPlanArn", "arn:aws:backup:us-east-1:000000000000:restore-testing-plan:query"));
                job.put("Metadata", Map.of("targetTableName", "restored-table"));
                restores.put("stored-restore-" + i, job);
            }
            copies.put("stored-copy", Map.of("CopyJobId", "stored-copy", "SourceBackupVaultArn", vaultArn,
                    "DestinationBackupVaultArn", vaultArn, "ResourceArn", RESOURCE_ARN, "State", "FAILED", "CreationDate", 100));
            String next = given().header("Authorization", AUTH).queryParam("status", "COMPLETED")
                    .queryParam("maxResults", 1).get("/restore-jobs").then().statusCode(200)
                    .body("RestoreJobs", hasSize(1)).body("RestoreJobs[0].Metadata", nullValue())
                    .extract().path("NextToken");
            given().header("Authorization", AUTH).queryParam("status", "COMPLETED")
                    .queryParam("maxResults", 1).queryParam("nextToken", next).get("/restore-jobs").then().statusCode(200)
                    .body("RestoreJobs", hasSize(1)).body("NextToken", nullValue());
            given().header("Authorization", AUTH).queryParam("status", "FAILED").get("/restore-jobs").then()
                    .statusCode(200).body("RestoreJobs", empty());
            given().header("Authorization", AUTH).get("/restore-jobs/stored-restore-0/metadata").then().statusCode(200)
                    .body("RestoreJobId", equalTo("stored-restore-0")).body("Metadata.targetTableName", equalTo("restored-table"));
            given().header("Authorization", AUTH).contentType("application/json")
                    .body(Map.of("ValidationStatus", "SUCCESSFUL", "ValidationStatusMessage", "verified"))
                    .put("/restore-jobs/stored-restore-0/validations").then().statusCode(200);
            given().header("Authorization", AUTH).get("/restore-jobs/stored-restore-0").then().statusCode(200)
                    .body("ValidationStatus", equalTo("SUCCESSFUL"));
            given().header("Authorization", AUTH).get("/copy-jobs/stored-copy").then().statusCode(200)
                    .body("CopyJob.CopyJobId", equalTo("stored-copy"));
            given().header("Authorization", AUTH).queryParam("state", "FAILED").queryParam("resourceArn", RESOURCE_ARN)
                    .get("/copy-jobs").then().statusCode(200).body("CopyJobs.CopyJobId", contains("stored-copy"));
            given().header("Authorization", AUTH).queryParam("createdAfter", 101).get("/copy-jobs").then()
                    .statusCode(200).body("CopyJobs", empty());
            given().header("Authorization", AUTH.replace("us-east-1", "eu-west-1"))
                    .get("/restore-jobs/stored-restore-0").then().statusCode(400)
                    .body("__type", equalTo("ResourceNotFoundException"));
            given().header("Authorization", AUTH.replace("us-east-1", "eu-west-1"))
                    .get("/copy-jobs").then().statusCode(200).body("CopyJobs", empty());
        } finally {
            restores.delete("stored-restore-0");
            restores.delete("stored-restore-1");
            copies.delete("stored-copy");
        }
    }

    // ── Plan ───────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void createBackupPlan() {
        planId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "BackupPlanTags": {"env": "test"},
                  "BackupPlan": {
                    "BackupPlanName": "daily-backup",
                    "Rules": [{
                      "RuleName": "daily",
                      "TargetBackupVaultName": "%s",
                      "ScheduleExpression": "cron(0 12 * * ? *)",
                      "StartWindowMinutes": 60,
                      "CompletionWindowMinutes": 120
                    }]
                  }
                }
                """.formatted(VAULT_NAME))
        .when()
            .put("/backup/plans/")
        .then()
            .statusCode(200)
            .body("BackupPlanId", notNullValue())
            .body("BackupPlanArn", containsString("backup-plan:"))
            .body("VersionId", notNullValue())
            .extract().path("BackupPlanId");
    }

    @Test
    @Order(21)
    void getBackupPlan() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup/plans/" + planId + "/")
        .then()
            .statusCode(200)
            .body("BackupPlanId", equalTo(planId))
            .body("BackupPlan.BackupPlanName", equalTo("daily-backup"))
            .body("BackupPlan.Rules[0].RuleName", equalTo("daily"))
            .body("BackupPlan.Rules[0].RuleId", notNullValue());
    }

    @Test
    @Order(22)
    void updateBackupPlan() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "BackupPlan": {
                    "BackupPlanName": "daily-backup-v2",
                    "Rules": [{
                      "RuleName": "daily-v2",
                      "TargetBackupVaultName": "%s",
                      "ScheduleExpression": "cron(0 6 * * ? *)"
                    }]
                  }
                }
                """.formatted(VAULT_NAME))
        .when()
            .post("/backup/plans/" + planId)
        .then()
            .statusCode(200)
            .body("BackupPlanId", equalTo(planId))
            .body("VersionId", notNullValue());
    }

    @Test
    @Order(23)
    void listBackupPlans() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup/plans/")
        .then()
            .statusCode(200)
            .body("BackupPlansList", hasSize(greaterThanOrEqualTo(1)))
            .body("BackupPlansList[0].BackupPlanId", notNullValue());
    }

    @Test
    @Order(24)
    void planTagsPersistAndSupportUpdatesAndRemovals() {
        String arn = given().header("Authorization", AUTH).get("/backup/plans/" + planId)
                .then().statusCode(200).extract().path("BackupPlanArn");
        given().header("Authorization", AUTH).get("/tags/" + arn).then().statusCode(200)
                .body("Tags.env", equalTo("test"));
        given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("Tags", Map.of("phase", "two"))).post("/tags/" + arn).then().statusCode(204);
        given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("TagKeyList", List.of("env"))).post("/untag/" + arn).then().statusCode(204);
        given().header("Authorization", AUTH).get("/tags/" + arn).then().statusCode(200)
                .body("Tags.phase", equalTo("two")).body("Tags.env", nullValue());
    }

    // ── Selection ──────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    void createBackupSelection() {
        selectionId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "BackupSelection": {
                    "SelectionName": "my-selection",
                    "IamRoleArn": "%s",
                    "ListOfTags": [{"ConditionType":"STRINGEQUALS","ConditionKey":"aws:ResourceTag/backup","ConditionValue":"daily"}],
                    "Conditions": {"StringEquals":[{"ConditionKey":"aws:ResourceTag/env","ConditionValue":"test"}]},
                    "Resources": ["%s"]
                  }
                }
                """.formatted(IAM_ROLE, RESOURCE_ARN))
        .when()
            .put("/backup/plans/" + planId + "/selections/")
        .then()
            .statusCode(200)
            .body("SelectionId", notNullValue())
            .body("BackupPlanId", equalTo(planId))
            .extract().path("SelectionId");
    }

    @Test
    @Order(31)
    void getBackupSelection() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup/plans/" + planId + "/selections/" + selectionId)
        .then()
            .statusCode(200)
            .body("SelectionId", equalTo(selectionId))
            .body("BackupSelection.SelectionName", equalTo("my-selection"))
            .body("BackupSelection.IamRoleArn", equalTo(IAM_ROLE))
            .body("BackupSelection.ListOfTags[0].ConditionKey", equalTo("aws:ResourceTag/backup"))
            .body("BackupSelection.Conditions.StringEquals[0].ConditionValue", equalTo("test"));
    }

    @Test
    @Order(32)
    void listBackupSelections() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup/plans/" + planId + "/selections/")
        .then()
            .statusCode(200)
            .body("BackupSelectionsList", hasSize(1))
            .body("BackupSelectionsList[0].SelectionId", equalTo(selectionId));
    }

    @Test
    @Order(33)
    void deleteBackupPlanWithSelectionReturns400() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup/plans/" + planId)
        .then()
            .statusCode(400);
    }

    // ── Job ────────────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    void startBackupJob() {
        jobId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "BackupVaultName": "%s",
                  "ResourceArn": "%s",
                  "IamRoleArn": "%s"
                }
                """.formatted(VAULT_NAME, RESOURCE_ARN, IAM_ROLE))
        .when()
            .put("/backup-jobs")
        .then()
            .statusCode(200)
            .body("BackupJobId", notNullValue())
            .body("BackupVaultArn", containsString("backup-vault:"))
            .extract().path("BackupJobId");
    }

    @Test
    @Order(41)
    void describeBackupJobReportsUnsupportedExecution() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-jobs/" + jobId)
        .then()
            .statusCode(200)
            .body("BackupJobId", equalTo(jobId))
            .body("State", equalTo("FAILED"))
            .body("BackupVaultName", equalTo(VAULT_NAME));
    }

    @Test
    @Order(42)
    void failedBackupDoesNotFabricateRecoveryPoint() {
        given().header("Authorization", AUTH)
                .get("/backup-jobs/" + jobId).then().statusCode(200)
                .body("State", equalTo("FAILED"))
                .body("StatusMessage", containsString("not supported"))
                .body("RecoveryPointArn", nullValue());
        given().header("Authorization", AUTH)
                .get("/backup-vaults/" + VAULT_NAME + "/recovery-points").then().statusCode(200)
                .body("RecoveryPoints", empty());
    }

    @Test
    @Order(43)
    void listBackupJobsByVault() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-jobs/?backupVaultName=" + VAULT_NAME)
        .then()
            .statusCode(200)
            .body("BackupJobs", hasSize(greaterThanOrEqualTo(1)))
            .body("BackupJobs[0].BackupVaultName", equalTo(VAULT_NAME));
    }

    @Test
    @Order(44)
    void listBackupJobsByState() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-jobs/?state=FAILED")
        .then()
            .statusCode(200)
            .body("BackupJobs", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(49)
    void loadRecoveryPointMetadataForQueryLifecycle() {
        // Existing persisted metadata remains queryable without claiming a backup was executed.
        StorageBackend<String, BackupVault> vaults = storageFactory.create("backup", "backup-vaults.json", new TypeReference<>() {});
        StorageBackend<String, RecoveryPoint> points = storageFactory.create("backup", "backup-recovery-points.json", new TypeReference<>() {});
        BackupVault vault = vaults.get("us-east-1:" + VAULT_NAME).orElseThrow();
        recoveryPointArn = "arn:aws:ec2:us-east-1::snapshot/snap-backup-query";
        RecoveryPoint point = new RecoveryPoint();
        point.setRecoveryPointArn(recoveryPointArn);
        point.setBackupVaultArn(vault.getBackupVaultArn());
        point.setBackupVaultName(VAULT_NAME);
        point.setResourceArn(RESOURCE_ARN);
        point.setResourceType("DynamoDB");
        point.setStatus("COMPLETED");
        point.setCreationDate(100);
        point.setCompletionDate(101L);
        point.setBackupSizeInBytes(128L);
        point.setRestoreMetadata(Map.of("targetTableName", "restored-table"));
        points.put(recoveryPointArn, point);
        vault.setNumberOfRecoveryPoints(1);
        vaults.put("us-east-1:" + VAULT_NAME, vault);
    }

    // ── Recovery Point ─────────────────────────────────────────────────────────

    @Test
    @Order(50)
    void describeRecoveryPoint() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME + "/recovery-points/" + recoveryPointArn)
        .then()
            .statusCode(200)
            .body("RecoveryPointArn", equalTo(recoveryPointArn))
            .body("BackupVaultName", equalTo(VAULT_NAME))
            .body("Status", equalTo("COMPLETED"));
    }

    @Test
    @Order(51)
    void listRecoveryPointsByBackupVault() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME + "/recovery-points/")
        .then()
            .statusCode(200)
            .body("RecoveryPoints", hasSize(1))
            .body("RecoveryPoints[0].RecoveryPointArn", equalTo(recoveryPointArn));
    }

    @Test
    @Order(51)
    void recoveryMetadataAndProtectedResourceQueriesUseStoredPoints() {
        String path = "/backup-vaults/" + VAULT_NAME + "/recovery-points/" + recoveryPointArn;
        given().header("Authorization", AUTH).get(path + "/restore-metadata").then().statusCode(200)
                .body("RecoveryPointArn", equalTo(recoveryPointArn))
                .body("RestoreMetadata.targetTableName", equalTo("restored-table"));
        given().header("Authorization", AUTH).get("/resources/" + RESOURCE_ARN).then().statusCode(200)
                .body("ResourceArn", equalTo(RESOURCE_ARN)).body("LastRecoveryPointArn", equalTo(recoveryPointArn));
        given().header("Authorization", AUTH).get("/resources/" + RESOURCE_ARN + "/recovery-points").then().statusCode(200)
                .body("RecoveryPoints[0].BackupSizeBytes", equalTo(128));
        given().header("Authorization", AUTH).get("/resources").then().statusCode(200)
                .body("Results.ResourceArn", hasItem(RESOURCE_ARN));
        given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("RecoveryPointArn", recoveryPointArn, "Metadata", Map.of(), "IamRoleArn", IAM_ROLE))
                .put("/restore-jobs").then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException")).body("message", containsString("not supported"));
        given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("RecoveryPointArn", recoveryPointArn, "SourceBackupVaultName", VAULT_NAME,
                        "DestinationBackupVaultArn", "arn:aws:backup:us-east-1:000000000000:backup-vault:destination", "IamRoleArn", IAM_ROLE))
                .put("/copy-jobs").then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException")).body("message", containsString("not supported"));
    }

    @Test
    @Order(52)
    void vaultCountReflectsStoredRecoveryPoints() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(200)
            .body("NumberOfRecoveryPoints", equalTo(1));
    }

    @Test
    @Order(53)
    void deleteVaultWithRecoveryPointsReturns400() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(400);
    }

    @Test
    @Order(54)
    void deleteRecoveryPoint() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup-vaults/" + VAULT_NAME + "/recovery-points/" + recoveryPointArn)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(55)
    void vaultCountDecrementedAfterDelete() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(200)
            .body("NumberOfRecoveryPoints", equalTo(0));
    }

    // ── Tags ───────────────────────────────────────────────────────────────────

    @Test
    @Order(60)
    void tagBackupVault() {
        String vaultArn = given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .extract().path("BackupVaultArn");

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"Tags\":{\"team\":\"platform\"}}")
        .when()
            .post("/tags/" + vaultArn)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(61)
    void listTagsForVault() {
        String vaultArn = given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .extract().path("BackupVaultArn");

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/tags/" + vaultArn)
        .then()
            .statusCode(200)
            .body("Tags.env", equalTo("test"))
            .body("Tags.team", equalTo("platform"));
    }

    @Test
    @Order(62)
    void untagBackupVault() {
        String vaultArn = given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .extract().path("BackupVaultArn");

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"TagKeyList\":[\"team\"]}")
        .when()
            .post("/untag/" + vaultArn)
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/tags/" + vaultArn)
        .then()
            .statusCode(200)
            .body("Tags.team", nullValue())
            .body("Tags.env", equalTo("test"));
    }

    // ── Supported Resource Types ───────────────────────────────────────────────

    @Test
    @Order(70)
    void getSupportedResourceTypes() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/supported-resource-types")
        .then()
            .statusCode(200)
            .body("ResourceTypes", hasSize(greaterThan(0)))
            .body("ResourceTypes", hasItem("S3"))
            .body("ResourceTypes", hasItem("DynamoDB"));
    }

    // ── Teardown ───────────────────────────────────────────────────────────────

    @Test
    @Order(80)
    void deleteBackupSelection() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup/plans/" + planId + "/selections/" + selectionId)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(81)
    void deleteBackupPlan() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup/plans/" + planId)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(82)
    void deleteBackupVault() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(83)
    void describeDeletedVaultReturns404() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(404);
    }
}
