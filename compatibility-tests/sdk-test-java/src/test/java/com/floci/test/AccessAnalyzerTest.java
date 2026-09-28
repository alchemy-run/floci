package com.floci.test;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.accessanalyzer.AccessAnalyzerClient;
import software.amazon.awssdk.services.accessanalyzer.model.Access;
import software.amazon.awssdk.services.accessanalyzer.model.AnalyzerSummary;
import software.amazon.awssdk.services.accessanalyzer.model.ArchiveRuleSummary;
import software.amazon.awssdk.services.accessanalyzer.model.ConflictException;
import software.amazon.awssdk.services.accessanalyzer.model.Criterion;
import software.amazon.awssdk.services.accessanalyzer.model.ListAnalyzersResponse;
import software.amazon.awssdk.services.accessanalyzer.model.ResourceNotFoundException;
import software.amazon.awssdk.services.accessanalyzer.model.ServiceQuotaExceededException;
import software.amazon.awssdk.services.accessanalyzer.model.Type;
import software.amazon.awssdk.services.accessanalyzer.model.ValidationException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("IAM Access Analyzer lifecycle")
class AccessAnalyzerTest {

    private static final Logger LOG = Logger.getLogger(AccessAnalyzerTest.class);

    @Test
    void analyzerLifecycleAndTypeSpecificQuotaUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only analyzer names and quota assertions");

        try (AccessAnalyzerClient client = TestFixtures.accessAnalyzerClient()) {
            String accountName = "floci-account-analyzer";
            String unusedName = "floci-unused-analyzer";
            String secondAccountName = "floci-account-analyzer-2";
            try {
                client.createAnalyzer(request -> request.analyzerName(accountName).type(Type.ACCOUNT));
                client.createAnalyzer(request -> request.analyzerName(unusedName).type(Type.ACCOUNT_UNUSED_ACCESS));

                ListAnalyzersResponse listed = client.listAnalyzers(request -> {});
                assertThat(listed.analyzers())
                        .extracting(analyzer -> analyzer.name())
                        .contains(accountName, unusedName);

                assertThatThrownBy(() -> client.createAnalyzer(request -> request
                                .analyzerName(secondAccountName)
                                .type(Type.ACCOUNT)))
                        .isInstanceOf(ServiceQuotaExceededException.class);
            } finally {
                deleteBestEffort(client, unusedName);
                deleteBestEffort(client, accountName);
            }
        }
    }

    @Test
    @DisplayName("Analyzer read, tags, unused-access configuration and archive rules round-trip through the SDK")
    void analyzerContractsAndIsolationUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only account credentials");
        String name = "sdk-contract-unused";
        try (AccessAnalyzerClient client = client("777788889999", Region.US_EAST_1);
             AccessAnalyzerClient foreign = client("888899990000", Region.US_EAST_1);
             AccessAnalyzerClient otherRegion = client("777788889999", Region.US_WEST_2)) {
            assertThatThrownBy(() -> client.getAnalyzer(request -> request.analyzerName(name)))
                    .isInstanceOf(ResourceNotFoundException.class);
            String arn = client.createAnalyzer(request -> request.analyzerName(name).type(Type.ACCOUNT_UNUSED_ACCESS)
                    .configuration(configuration -> configuration.unusedAccess(unused -> unused.unusedAccessAge(180)))
                    .tags(Map.of("Environment", "test", "remove", "yes"))).arn();
            try {
                AnalyzerSummary analyzer = client.getAnalyzer(request -> request.analyzerName(name)).analyzer();
                assertThat(analyzer.arn()).isEqualTo(arn);
                assertThat(analyzer.createdAt()).isNotNull();
                assertThat(analyzer.configuration().unusedAccess().unusedAccessAge()).isEqualTo(180);
                client.tagResource(request -> request.resourceArn(arn).tags(Map.of("Environment", "prod", "extra", "yes")));
                client.untagResource(request -> request.resourceArn(arn).tagKeys("remove", "extra"));
                assertThat(client.listTagsForResource(request -> request.resourceArn(arn)).tags())
                        .containsExactlyEntriesOf(Map.of("Environment", "prod"));
                assertThat(client.getAnalyzer(request -> request.analyzerName(name)).analyzer().tags())
                        .containsExactlyEntriesOf(Map.of("Environment", "prod"));
                Map<String, Criterion> filter = Map.of("findingType", Criterion.builder().eq("UnusedIAMRole").build());
                client.createArchiveRule(request -> request.analyzerName(name).ruleName("trusted").filter(filter));
                assertThatThrownBy(() -> client.createArchiveRule(request -> request.analyzerName(name).ruleName("trusted").filter(filter)))
                        .isInstanceOf(ConflictException.class);
                ArchiveRuleSummary rule = client.getArchiveRule(request -> request.analyzerName(name).ruleName("trusted")).archiveRule();
                assertThat(rule.filter().get("findingType").eq()).containsExactly("UnusedIAMRole");
                assertThat(rule.createdAt()).isNotNull();
                client.updateArchiveRule(request -> request.analyzerName(name).ruleName("trusted")
                        .filter(Map.of("findingType", Criterion.builder().eq("UnusedPermission").build())));
                assertThat(client.getArchiveRule(request -> request.analyzerName(name).ruleName("trusted"))
                        .archiveRule().createdAt()).isEqualTo(rule.createdAt());
                assertThat(client.listArchiveRules(request -> request.analyzerName(name)).archiveRules())
                        .singleElement().satisfies(updated -> assertThat(updated.filter().get("findingType").eq())
                                .containsExactly("UnusedPermission"));
                client.applyArchiveRule(request -> request.analyzerArn(arn).ruleName("trusted"));
                assertThat(client.listFindingsV2(request -> request.analyzerArn(arn).maxResults(25)).findings()).isEmpty();
                assertThat(client.listAnalyzedResources(request -> request.analyzerArn(arn)).analyzedResources()).isEmpty();
                assertThat(client.getFindingsStatistics(request -> request.analyzerArn(arn)).findingsStatistics())
                        .singleElement().satisfies(statistics -> assertThat(statistics.unusedAccessFindingsStatistics()
                                .totalActiveFindings()).isZero());
                assertThatThrownBy(() -> client.getFindingV2(request -> request.analyzerArn(arn).id("00000000-0000-0000-0000-000000000000")))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> foreign.getAnalyzer(request -> request.analyzerName(name)))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> otherRegion.getAnalyzer(request -> request.analyzerName(name)))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> foreign.tagResource(request -> request.resourceArn(arn).tags(Map.of("Environment", "foreign"))))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> otherRegion.applyArchiveRule(request -> request.analyzerArn(arn).ruleName("trusted")))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> client.startResourceScan(request -> request.analyzerArn(arn).resourceArn("arn:aws:s3:::example")))
                        .isInstanceOf(ValidationException.class);
                client.deleteArchiveRule(request -> request.analyzerName(name).ruleName("trusted"));
                assertThatThrownBy(() -> client.getArchiveRule(request -> request.analyzerName(name).ruleName("trusted")))
                        .isInstanceOf(ResourceNotFoundException.class);
            } finally {
                client.deleteAnalyzer(request -> request.analyzerName(name));
            }
            assertThatThrownBy(() -> client.getAnalyzer(request -> request.analyzerName(name)))
                    .isInstanceOf(ResourceNotFoundException.class);
            client.createAnalyzer(request -> request.analyzerName(name).type(Type.ACCOUNT_UNUSED_ACCESS)
                    .configuration(configuration -> configuration.unusedAccess(unused -> unused.unusedAccessAge(365))));
            try {
                assertThat(client.getAnalyzer(request -> request.analyzerName(name)).analyzer()
                        .configuration().unusedAccess().unusedAccessAge()).isEqualTo(365);
                assertThat(client.listArchiveRules(request -> request.analyzerName(name)).archiveRules()).isEmpty();
            } finally {
                client.deleteAnalyzer(request -> request.analyzerName(name));
            }
        }
    }

    @Test
    @DisplayName("Policy checks evaluate positive and negative inputs and reject unsupported semantics")
    void policyChecksAndGenerationErrorsUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Tests Floci's explicit bounded policy semantics");
        String read = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::example/*\"}]}";
        String wide = read.replace("s3:GetObject", "s3:*");
        String publicPolicy = read.replace("\"Effect\":\"Allow\"", "\"Effect\":\"Allow\",\"Principal\":\"*\"");
        String privatePolicy = publicPolicy.replace("\"Principal\":\"*\"", "\"Principal\":{\"AWS\":\"111111111111\"}");
        try (AccessAnalyzerClient client = TestFixtures.accessAnalyzerClient()) {
            assertThat(client.validatePolicy(request -> request.policyType("IDENTITY_POLICY").policyDocument(read)).findings())
                    .noneMatch(finding -> "ERROR".equals(finding.findingTypeAsString()));
            assertThat(client.validatePolicy(request -> request.policyType("IDENTITY_POLICY").policyDocument("{")).findings())
                    .anyMatch(finding -> "ERROR".equals(finding.findingTypeAsString()));
            assertThat(client.checkNoNewAccess(request -> request.policyType("IDENTITY_POLICY")
                    .existingPolicyDocument(wide).newPolicyDocument(read)).resultAsString()).isEqualTo("PASS");
            assertThat(client.checkNoNewAccess(request -> request.policyType("IDENTITY_POLICY")
                    .existingPolicyDocument(read).newPolicyDocument(wide)).resultAsString()).isEqualTo("FAIL");
            assertThat(client.checkAccessNotGranted(request -> request.policyType("IDENTITY_POLICY").policyDocument(read)
                    .access(Access.builder().actions("s3:DeleteBucket").build())).resultAsString()).isEqualTo("PASS");
            assertThat(client.checkAccessNotGranted(request -> request.policyType("IDENTITY_POLICY").policyDocument(read)
                    .access(Access.builder().actions("s3:GetObject").build())).resultAsString()).isEqualTo("FAIL");
            assertThat(client.checkNoPublicAccess(request -> request.resourceType("AWS::S3::Bucket").policyDocument(privatePolicy))
                    .resultAsString()).isEqualTo("PASS");
            assertThat(client.checkNoPublicAccess(request -> request.resourceType("AWS::S3::Bucket").policyDocument(publicPolicy))
                    .resultAsString()).isEqualTo("FAIL");
            String conditional = publicPolicy.replace("\"Effect\":\"Allow\"", "\"Effect\":\"Allow\",\"Condition\":{}");
            assertThatThrownBy(() -> client.checkNoPublicAccess(request -> request.resourceType("AWS::S3::Bucket").policyDocument(conditional)))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> client.startPolicyGeneration(request -> request.policyGenerationDetails(
                    details -> details.principalArn("arn:aws:iam::111111111111:role/example"))))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("Missing cloudTrailDetails");
            String unknownJob = "00000000-0000-0000-0000-000000000000";
            assertThatThrownBy(() -> client.getGeneratedPolicy(request -> request.jobId(unknownJob)))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> client.cancelPolicyGeneration(request -> request.jobId(unknownJob)))
                    .isInstanceOf(ValidationException.class);
            assertThat(client.listPolicyGenerations(request -> {}).policyGenerations()).isEmpty();
        }
    }

    private AccessAnalyzerClient client(String account, Region region) {
        return AccessAnalyzerClient.builder().endpointOverride(TestFixtures.endpoint()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build();
    }

    private static void deleteBestEffort(AccessAnalyzerClient client, String analyzerName) {
        try {
            client.deleteAnalyzer(request -> request.analyzerName(analyzerName));
        } catch (Exception cleanupError) {
            LOG.warnf(cleanupError, "Best-effort cleanup failed for analyzerName=%s", analyzerName);
        }
    }
}
