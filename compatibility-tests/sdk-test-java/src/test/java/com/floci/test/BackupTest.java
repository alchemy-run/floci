package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.backup.BackupClient;
import software.amazon.awssdk.services.backup.model.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AWS Backup")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupTest {

    private static BackupClient backup;

    private static final String VAULT_NAME = "compat-test-vault";
    private static final String IAM_ROLE   = "arn:aws:iam::000000000000:role/backup-role";
    private static final String RESOURCE_ARN = "arn:aws:dynamodb:us-east-1:000000000000:table/my-table";

    private static String vaultArn;
    private static String planId;
    private static String planArn;
    private static String selectionId;
    private static String jobId;
    private static final String MISSING_POINT = "arn:aws:ec2:us-east-1::snapshot/snap-sdk-missing";

    @BeforeAll
    static void setup() {
        backup = TestFixtures.backupClient();
    }

    @AfterAll
    static void cleanup() {
        if (backup == null) return;
        try { backup.deleteBackupSelection(r -> r.backupPlanId(planId).selectionId(selectionId)); } catch (Exception ignored) {}
        try { backup.deleteBackupPlan(r -> r.backupPlanId(planId)); } catch (Exception ignored) {}
        try {
            backup.listRecoveryPointsByBackupVault(r -> r.backupVaultName(VAULT_NAME))
                  .recoveryPoints()
                  .forEach(rp -> {
                      try {
                          backup.deleteRecoveryPoint(r -> r
                                  .backupVaultName(VAULT_NAME)
                                  .recoveryPointArn(rp.recoveryPointArn()));
                      } catch (Exception ignored2) {}
                  });
        } catch (Exception ignored) {}
        try { backup.deleteBackupVault(r -> r.backupVaultName(VAULT_NAME)); } catch (Exception ignored) {}
        backup.close();
    }

    // ── Vault ──────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("CreateBackupVault - creates vault with tags")
    void createBackupVault() {
        CreateBackupVaultResponse resp = backup.createBackupVault(r -> r
                .backupVaultName(VAULT_NAME)
                .backupVaultTags(Map.of("env", "compat-test")));

        vaultArn = resp.backupVaultArn();
        assertThat(resp.backupVaultName()).isEqualTo(VAULT_NAME);
        assertThat(vaultArn).contains("backup-vault:" + VAULT_NAME);
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(11)
    @DisplayName("CreateBackupVault - duplicate returns AlreadyExistsException")
    void createVaultDuplicateFails() {
        assertThatThrownBy(() -> backup.createBackupVault(r -> r.backupVaultName(VAULT_NAME)))
                .isInstanceOf(AlreadyExistsException.class);
    }

    @Test
    @Order(12)
    @DisplayName("DescribeBackupVault - returns vault metadata")
    void describeBackupVault() {
        DescribeBackupVaultResponse resp = backup.describeBackupVault(r -> r.backupVaultName(VAULT_NAME));

        assertThat(resp.backupVaultName()).isEqualTo(VAULT_NAME);
        assertThat(resp.backupVaultArn()).isEqualTo(vaultArn);
        assertThat(resp.numberOfRecoveryPoints()).isEqualTo(0);
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(13)
    @DisplayName("ListBackupVaults - includes created vault")
    void listBackupVaults() {
        ListBackupVaultsResponse resp = backup.listBackupVaults(r -> r.build());

        assertThat(resp.backupVaultList()).isNotEmpty();
        assertThat(resp.backupVaultList())
                .anyMatch(v -> VAULT_NAME.equals(v.backupVaultName()));
    }

    @Test
    @Order(14)
    @DisplayName("DescribeBackupVault - non-existent returns ResourceNotFoundException")
    void describeNonExistentVaultFails() {
        assertThatThrownBy(() -> backup.describeBackupVault(r -> r.backupVaultName("no-such-vault")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(15)
    @DisplayName("Vault policy and notification configuration persist and delete through the SDK")
    void vaultConfigurationRoundTrip() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":\"*\",\"Action\":\"backup:DeleteRecoveryPoint\",\"Resource\":\"*\"}]}";
        backup.putBackupVaultAccessPolicy(r -> r.backupVaultName(VAULT_NAME).policy(policy));
        assertThat(backup.getBackupVaultAccessPolicy(r -> r.backupVaultName(VAULT_NAME)).policy()).isEqualTo(policy);
        backup.deleteBackupVaultAccessPolicy(r -> r.backupVaultName(VAULT_NAME));
        backup.deleteBackupVaultAccessPolicy(r -> r.backupVaultName(VAULT_NAME));
        assertThatThrownBy(() -> backup.getBackupVaultAccessPolicy(r -> r.backupVaultName(VAULT_NAME)))
                .isInstanceOf(ResourceNotFoundException.class);
        String topic = "arn:aws:sns:us-east-1:000000000000:backup-sdk-events";
        backup.putBackupVaultNotifications(r -> r.backupVaultName(VAULT_NAME).snsTopicArn(topic)
                .backupVaultEvents(BackupVaultEvent.BACKUP_JOB_FAILED));
        GetBackupVaultNotificationsResponse notifications = backup.getBackupVaultNotifications(r -> r.backupVaultName(VAULT_NAME));
        assertThat(notifications.snsTopicArn()).isEqualTo(topic);
        assertThat(notifications.backupVaultEvents()).containsExactly(BackupVaultEvent.BACKUP_JOB_FAILED);
        backup.deleteBackupVaultNotifications(r -> r.backupVaultName(VAULT_NAME));
        assertThatThrownBy(() -> backup.getBackupVaultNotifications(r -> r.backupVaultName(VAULT_NAME)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(16)
    @DisplayName("Runtime query and error routes decode as Backup responses, not S3 errors")
    void runtimeQueriesAndTypedErrors() {
        assertThat(backup.listRestoreJobs(r -> r.maxResults(25)).restoreJobs()).isNotNull();
        assertThat(backup.listCopyJobs(r -> r.maxResults(25)).copyJobs()).isNotNull();
        assertThat(backup.listProtectedResources(r -> r.maxResults(25)).results()).isNotNull();
        String missing = "00000000-0000-0000-0000-000000000000";
        String point = "arn:aws:ec2:us-east-1::snapshot/snap-00000000000000000";
        assertThatThrownBy(() -> backup.describeRestoreJob(r -> r.restoreJobId(missing))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> backup.describeCopyJob(r -> r.copyJobId(missing))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> backup.getRestoreJobMetadata(r -> r.restoreJobId(missing))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> backup.getRecoveryPointRestoreMetadata(r -> r.backupVaultName(VAULT_NAME).recoveryPointArn(point)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> backup.putRestoreValidationResult(r -> r.restoreJobId(missing).validationStatus(RestoreValidationStatus.SUCCESSFUL)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> backup.startRestoreJob(r -> r.recoveryPointArn(point).metadata(Map.of()).iamRoleArn(IAM_ROLE)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> backup.startCopyJob(r -> r.recoveryPointArn(point).sourceBackupVaultName(VAULT_NAME)
                .destinationBackupVaultArn(vaultArn).iamRoleArn(IAM_ROLE))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> backup.stopBackupJob(r -> r.backupJobId(missing))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> backup.describeProtectedResource(r -> r.resourceArn(RESOURCE_ARN + "-missing")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(backup.listRecoveryPointsByResource(r -> r.resourceArn(RESOURCE_ARN + "-missing")).recoveryPoints()).isEmpty();
    }

    // ── Plan ───────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("CreateBackupPlan - creates plan with rules")
    void createBackupPlan() {
        CreateBackupPlanResponse resp = backup.createBackupPlan(r -> r
                .backupPlanTags(Map.of("env", "compat-test"))
                .backupPlan(p -> p
                        .backupPlanName("compat-daily")
                        .rules(BackupRuleInput.builder()
                                .ruleName("daily")
                                .targetBackupVaultName(VAULT_NAME)
                                .scheduleExpression("cron(0 12 * * ? *)")
                                .startWindowMinutes(60L)
                                .completionWindowMinutes(120L)
                                .build())));

        planId  = resp.backupPlanId();
        planArn = resp.backupPlanArn();
        assertThat(planId).isNotNull();
        assertThat(planArn).contains("backup-plan:");
        assertThat(resp.versionId()).isNotNull();
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(21)
    @DisplayName("GetBackupPlan - returns plan with rules")
    void getBackupPlan() {
        GetBackupPlanResponse resp = backup.getBackupPlan(r -> r.backupPlanId(planId));

        assertThat(resp.backupPlanId()).isEqualTo(planId);
        assertThat(resp.backupPlan().backupPlanName()).isEqualTo("compat-daily");
        assertThat(resp.backupPlan().rules()).hasSize(1);
        assertThat(resp.backupPlan().rules().get(0).ruleName()).isEqualTo("daily");
        assertThat(resp.backupPlan().rules().get(0).ruleId()).isNotNull();
    }

    @Test
    @Order(22)
    @DisplayName("UpdateBackupPlan - replaces rules and bumps versionId")
    void updateBackupPlan() {
        String oldVersionId = backup.getBackupPlan(r -> r.backupPlanId(planId)).versionId();

        UpdateBackupPlanResponse resp = backup.updateBackupPlan(r -> r
                .backupPlanId(planId)
                .backupPlan(p -> p
                        .backupPlanName("compat-daily-v2")
                        .rules(BackupRuleInput.builder()
                                .ruleName("daily-v2")
                                .targetBackupVaultName(VAULT_NAME)
                                .scheduleExpression("cron(0 6 * * ? *)")
                                .build())));

        assertThat(resp.backupPlanId()).isEqualTo(planId);
        assertThat(resp.versionId()).isNotEqualTo(oldVersionId);
    }

    @Test
    @Order(23)
    @DisplayName("ListBackupPlans - includes created plan")
    void listBackupPlans() {
        ListBackupPlansResponse resp = backup.listBackupPlans(r -> r.build());

        assertThat(resp.backupPlansList()).isNotEmpty();
        assertThat(resp.backupPlansList())
                .anyMatch(p -> planId.equals(p.backupPlanId()));
    }

    @Test
    @Order(24)
    @DisplayName("Backup plan tags persist from creation and reconcile updates and removals")
    void planTagRoundTrip() {
        assertThat(backup.listTags(r -> r.resourceArn(planArn)).tags()).containsEntry("env", "compat-test");
        backup.tagResource(r -> r.resourceArn(planArn).tags(Map.of("phase", "two")));
        backup.untagResource(r -> r.resourceArn(planArn).tagKeyList("env"));
        assertThat(backup.listTags(r -> r.resourceArn(planArn)).tags()).containsEntry("phase", "two").doesNotContainKey("env");
    }

    // ── Selection ──────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("CreateBackupSelection - creates selection")
    void createBackupSelection() {
        CreateBackupSelectionResponse resp = backup.createBackupSelection(r -> r
                .backupPlanId(planId)
                .backupSelection(s -> s
                        .listOfTags(Condition.builder().conditionType(ConditionType.STRINGEQUALS)
                                .conditionKey("aws:ResourceTag/backup").conditionValue("daily").build())
                        .selectionName("compat-selection")
                        .iamRoleArn(IAM_ROLE)
                        .resources(RESOURCE_ARN)));

        selectionId = resp.selectionId();
        assertThat(selectionId).isNotNull();
        assertThat(resp.backupPlanId()).isEqualTo(planId);
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(31)
    @DisplayName("GetBackupSelection - returns selection detail")
    void getBackupSelection() {
        GetBackupSelectionResponse resp = backup.getBackupSelection(r -> r
                .backupPlanId(planId)
                .selectionId(selectionId));

        assertThat(resp.selectionId()).isEqualTo(selectionId);
        assertThat(resp.backupSelection().selectionName()).isEqualTo("compat-selection");
        assertThat(resp.backupSelection().iamRoleArn()).isEqualTo(IAM_ROLE);
        assertThat(resp.backupSelection().resources()).contains(RESOURCE_ARN);
        assertThat(resp.backupSelection().listOfTags()).hasSize(1);
        assertThat(resp.backupSelection().listOfTags().get(0).conditionKey()).isEqualTo("aws:ResourceTag/backup");
    }

    @Test
    @Order(32)
    @DisplayName("ListBackupSelections - includes created selection")
    void listBackupSelections() {
        ListBackupSelectionsResponse resp = backup.listBackupSelections(r -> r.backupPlanId(planId));

        assertThat(resp.backupSelectionsList()).hasSize(1);
        assertThat(resp.backupSelectionsList().get(0).selectionId()).isEqualTo(selectionId);
        assertThat(resp.backupSelectionsList().get(0).selectionName()).isEqualTo("compat-selection");
    }

    @Test
    @Order(33)
    @DisplayName("DeleteBackupPlan with active selection returns InvalidRequestException")
    void deletePlanWithSelectionFails() {
        assertThatThrownBy(() -> backup.deleteBackupPlan(r -> r.backupPlanId(planId)))
                .isInstanceOf(InvalidRequestException.class);
    }

    // ── Job ────────────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("StartBackupJob - returns a tracked job ID")
    void startBackupJob() {
        StartBackupJobResponse resp = backup.startBackupJob(r -> r
                .backupVaultName(VAULT_NAME)
                .resourceArn(RESOURCE_ARN)
                .iamRoleArn(IAM_ROLE));

        jobId = resp.backupJobId();
        assertThat(jobId).isNotNull();
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(41)
    @DisplayName("DescribeBackupJob - unsupported execution is a failed job")
    void describeBackupJobExecutionFailure() {
        DescribeBackupJobResponse resp = backup.describeBackupJob(r -> r.backupJobId(jobId));

        assertThat(resp.backupJobId()).isEqualTo(jobId);
        assertThat(resp.state()).isEqualTo(BackupJobState.FAILED);
        assertThat(resp.backupVaultName()).isEqualTo(VAULT_NAME);
        assertThat(resp.resourceArn()).isEqualTo(RESOURCE_ARN);
    }

    @Test
    @Order(42)
    @DisplayName("Failed backup execution never creates a recovery point")
    void failedBackupDoesNotCreateRecoveryPoint() {
        DescribeBackupJobResponse job = backup.describeBackupJob(r -> r.backupJobId(jobId));
        assertThat(job.state()).isEqualTo(BackupJobState.FAILED);
        assertThat(job.statusMessage()).contains("not supported");
        assertThat(job.recoveryPointArn()).isNull();
        assertThat(job.completionDate()).isNotNull();
        assertThat(backup.listRecoveryPointsByBackupVault(r -> r.backupVaultName(VAULT_NAME)).recoveryPoints()).isEmpty();
    }

    @Test
    @Order(43)
    @DisplayName("ListBackupJobs - filter by vault name")
    void listBackupJobsByVault() {
        ListBackupJobsResponse resp = backup.listBackupJobs(r -> r.byBackupVaultName(VAULT_NAME));

        assertThat(resp.backupJobs()).isNotEmpty();
        assertThat(resp.backupJobs()).allMatch(j -> VAULT_NAME.equals(j.backupVaultName()));
    }

    @Test
    @Order(44)
    @DisplayName("ListBackupJobs - filter by FAILED state")
    void listBackupJobsByState() {
        ListBackupJobsResponse resp = backup.listBackupJobs(r -> r.byState(BackupJobState.FAILED));

        assertThat(resp.backupJobs()).isNotEmpty();
        assertThat(resp.backupJobs()).allMatch(j -> j.state() == BackupJobState.FAILED);
    }

    // ── Recovery Point ─────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @DisplayName("DescribeRecoveryPoint - missing point is a typed error")
    void describeMissingRecoveryPoint() {
        assertThatThrownBy(() -> backup.describeRecoveryPoint(r -> r.backupVaultName(VAULT_NAME).recoveryPointArn(MISSING_POINT)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(51)
    @DisplayName("ListRecoveryPointsByBackupVault - failed jobs create no recovery points")
    void listRecoveryPointsByBackupVault() {
        assertThat(backup.listRecoveryPointsByBackupVault(r -> r.backupVaultName(VAULT_NAME)).recoveryPoints()).isEmpty();
    }

    @Test
    @Order(52)
    @DisplayName("DescribeBackupVault - failed jobs leave the recovery-point count unchanged")
    void vaultCountAfterFailedJob() {
        assertThat(backup.describeBackupVault(r -> r.backupVaultName(VAULT_NAME)).numberOfRecoveryPoints()).isZero();
    }

    @Test
    @Order(53)
    @DisplayName("StopBackupJob - terminal failed jobs cannot be stopped")
    void stopFailedBackupJobFails() {
        assertThatThrownBy(() -> backup.stopBackupJob(r -> r.backupJobId(jobId))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @Order(54)
    @DisplayName("DeleteRecoveryPoint - missing point is a typed error")
    void deleteMissingRecoveryPoint() {
        assertThatThrownBy(() -> backup.deleteRecoveryPoint(r -> r.backupVaultName(VAULT_NAME).recoveryPointArn(MISSING_POINT)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Tagging ────────────────────────────────────────────────────────────────

    @Test
    @Order(60)
    @DisplayName("TagResource / ListTags / UntagResource - SDK round-trip")
    void tagRoundTrip() {
        backup.tagResource(r -> r
                .resourceArn(vaultArn)
                .tags(Map.of("team", "platform", "cost-center", "eng")));

        ListTagsResponse listed = backup.listTags(r -> r.resourceArn(vaultArn));
        assertThat(listed.tags())
                .containsEntry("env", "compat-test")
                .containsEntry("team", "platform")
                .containsEntry("cost-center", "eng");

        backup.untagResource(r -> r
                .resourceArn(vaultArn)
                .tagKeyList(List.of("team")));

        ListTagsResponse afterUntag = backup.listTags(r -> r.resourceArn(vaultArn));
        assertThat(afterUntag.tags())
                .doesNotContainKey("team")
                .containsEntry("cost-center", "eng")
                .containsEntry("env", "compat-test");
    }

    // ── Supported Resource Types ────────────────────────────────────────────────

    @Test
    @Order(70)
    @DisplayName("GetSupportedResourceTypes - returns non-empty list including S3 and DynamoDB")
    void getSupportedResourceTypes() {
        GetSupportedResourceTypesResponse resp = backup.getSupportedResourceTypes(
                GetSupportedResourceTypesRequest.builder().build());

        assertThat(resp.resourceTypes()).isNotEmpty();
        assertThat(resp.resourceTypes()).contains("S3", "DynamoDB");
    }

    // ── Teardown ───────────────────────────────────────────────────────────────

    @Test
    @Order(80)
    @DisplayName("DeleteBackupSelection - removes selection")
    void deleteBackupSelection() {
        backup.deleteBackupSelection(r -> r
                .backupPlanId(planId)
                .selectionId(selectionId));

        assertThat(backup.listBackupSelections(r -> r.backupPlanId(planId))
                .backupSelectionsList()).isEmpty();
    }

    @Test
    @Order(81)
    @DisplayName("DeleteBackupPlan - removes plan")
    void deleteBackupPlan() {
        backup.deleteBackupPlan(r -> r.backupPlanId(planId));

        assertThatThrownBy(() -> backup.getBackupPlan(r -> r.backupPlanId(planId)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(82)
    @DisplayName("DeleteBackupVault - removes empty vault")
    void deleteBackupVault() {
        backup.deleteBackupVault(r -> r.backupVaultName(VAULT_NAME));

        assertThatThrownBy(() -> backup.describeBackupVault(r -> r.backupVaultName(VAULT_NAME)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
