package io.github.hectorvent.floci.services.guardduty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.guardduty.model.AdminAccount;
import io.github.hectorvent.floci.services.guardduty.model.Detector;
import io.github.hectorvent.floci.services.guardduty.model.DetectorAdditionalConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.DetectorFeature;
import io.github.hectorvent.floci.services.guardduty.model.MemberAccount;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationAdditionalConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationFeature;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardDutyServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GuardDutyService service =
            new GuardDutyService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());

    @Test
    void createDetectorAppliesDefaultsAndGeneratesIdentifiers() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        assertEquals(32, detector.getId().length());
        assertEquals("ENABLED", detector.getStatus());
        assertEquals("SIX_HOURS", detector.getFindingPublishingFrequency());
        assertEquals(
                "arn:aws:iam::" + ACCOUNT
                        + ":role/aws-service-role/guardduty.amazonaws.com/AWSServiceRoleForAmazonGuardDuty",
                detector.getServiceRole());
        assertEquals(detector.getCreatedAt(), detector.getUpdatedAt());
        assertNull(detector.getFeatures());
    }

    @Test
    void createDetectorRejectsSecondDetectorInSameRegion() throws Exception {
        service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")));

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(
                "The request is rejected because a detector already exists for the current account.",
                error.getMessage());
    }

    @Test
    void detectorsAreRegionScoped() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));
        service.createDetector("us-west-2", ACCOUNT, request("{\"enable\":true}"));

        AwsException error = assertThrows(
                AwsException.class, () -> service.getDetector("us-west-2", detector.getId()));

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE, error.getMessage());
    }

    @Test
    void getDetectorRejectsMissingDetectorWithProviderMatchedMessage() {
        AwsException error = assertThrows(
                AwsException.class, () -> service.getDetector(REGION, "does-not-exist"));

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE, error.getMessage());
    }

    @Test
    void updateDetectorTogglesStatusAndFrequency() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        service.updateDetector(REGION, detector.getId(), request(
                "{\"enable\":false,\"findingPublishingFrequency\":\"ONE_HOUR\"}"));

        Detector updated = service.getDetector(REGION, detector.getId());
        assertEquals("DISABLED", updated.getStatus());
        assertEquals("ONE_HOUR", updated.getFindingPublishingFrequency());
    }

    @Test
    void featureAdditionalConfigurationPreservesSubmittedOrder() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("""
                {"enable":true,"features":[
                  {"name":"RUNTIME_MONITORING","status":"ENABLED","additionalConfiguration":[
                    {"name":"ECS_FARGATE_AGENT_MANAGEMENT","status":"ENABLED"},
                    {"name":"EC2_AGENT_MANAGEMENT","status":"ENABLED"},
                    {"name":"EKS_ADDON_MANAGEMENT","status":"DISABLED"}
                  ]},
                  {"name":"S3_DATA_EVENTS","status":"ENABLED"}
                ]}
                """));

        List<DetectorFeature> features = service.getDetector(REGION, detector.getId()).getFeatures();
        assertEquals(List.of("RUNTIME_MONITORING", "S3_DATA_EVENTS"),
                features.stream().map(DetectorFeature::getName).toList());
        assertEquals(
                List.of("ECS_FARGATE_AGENT_MANAGEMENT", "EC2_AGENT_MANAGEMENT", "EKS_ADDON_MANAGEMENT"),
                features.get(0).getAdditionalConfiguration().stream()
                        .map(DetectorAdditionalConfiguration::getName)
                        .toList());
    }

    @Test
    void updateDetectorMergesFeaturesByNameAndAppendsNewOnes() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("""
                {"enable":true,"features":[
                  {"name":"S3_DATA_EVENTS","status":"ENABLED"},
                  {"name":"RDS_LOGIN_EVENTS","status":"ENABLED"}
                ]}
                """));

        service.updateDetector(REGION, detector.getId(), request("""
                {"features":[
                  {"name":"RDS_LOGIN_EVENTS","status":"DISABLED"},
                  {"name":"LAMBDA_NETWORK_LOGS","status":"ENABLED"}
                ]}
                """));

        List<DetectorFeature> features = service.getDetector(REGION, detector.getId()).getFeatures();
        assertEquals(List.of("S3_DATA_EVENTS", "RDS_LOGIN_EVENTS", "LAMBDA_NETWORK_LOGS"),
                features.stream().map(DetectorFeature::getName).toList());
        assertEquals("ENABLED", features.get(0).getStatus());
        assertEquals("DISABLED", features.get(1).getStatus());
        assertEquals("ENABLED", features.get(2).getStatus());
    }

    @Test
    void createDetectorRejectsUnknownFeatureName() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.createDetector(REGION, ACCOUNT, request(
                        "{\"enable\":true,\"features\":[{\"name\":\"NOT_A_FEATURE\",\"status\":\"ENABLED\"}]}")));

        assertEquals("BadRequestException", error.getErrorCode());
    }

    @Test
    void createDetectorRejectsInvalidFrequencyAndMissingEnable() {
        AwsException frequencyError = assertThrows(
                AwsException.class,
                () -> service.createDetector(REGION, ACCOUNT, request(
                        "{\"enable\":true,\"findingPublishingFrequency\":\"NEVER\"}")));
        assertEquals("BadRequestException", frequencyError.getErrorCode());

        AwsException enableError = assertThrows(
                AwsException.class, () -> service.createDetector(REGION, ACCOUNT, request("{}")));
        assertEquals("BadRequestException", enableError.getErrorCode());
    }

    @Test
    void deleteDetectorRemovesItAndRejectsSecondDelete() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        service.deleteDetector(REGION, detector.getId());

        AwsException error = assertThrows(
                AwsException.class, () -> service.deleteDetector(REGION, detector.getId()));
        assertEquals(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE, error.getMessage());
        assertTrue(service.listDetectorIds(REGION, ACCOUNT, null, null).items().isEmpty());
    }

    @Test
    void listDetectorIdsReturnsTheRegionalDetector() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        GuardDutyService.Page<String> page = service.listDetectorIds(REGION, ACCOUNT, null, null);

        assertEquals(List.of(detector.getId()), page.items());
        assertNull(page.nextToken());
    }

    @Test
    void detectorsAreScopedByAccountWithinTheSameRegion() throws Exception {
        String otherAccount = "222222222222";
        Detector first = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));
        Detector second = service.createDetector(REGION, otherAccount, request("{\"enable\":true}"));

        assertEquals(List.of(first.getId()), service.listDetectorIds(REGION, ACCOUNT, null, null).items());
        assertEquals(List.of(second.getId()), service.listDetectorIds(REGION, otherAccount, null, null).items());
    }

    @Test
    void describeOrganizationConfigurationDefaultsToNone() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        OrganizationConfiguration configuration =
                service.describeOrganizationConfiguration(REGION, detector.getId());

        assertEquals(false, configuration.getAutoEnable());
        assertEquals("NONE", configuration.getAutoEnableOrganizationMembers());
        assertTrue(configuration.getFeatures().isEmpty());
    }

    @Test
    void organizationConfigurationEchoesMembersAndDerivesAutoEnable() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        service.updateOrganizationConfiguration(REGION, detector.getId(), request(
                "{\"autoEnableOrganizationMembers\":\"ALL\"}"));

        OrganizationConfiguration configuration =
                service.describeOrganizationConfiguration(REGION, detector.getId());
        assertEquals(true, configuration.getAutoEnable());
        assertEquals("ALL", configuration.getAutoEnableOrganizationMembers());
    }

    @Test
    void organizationFeatureUpdatesMergeWithoutClobberingOtherFeatures() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));
        service.updateOrganizationConfiguration(REGION, detector.getId(), request(
                "{\"autoEnableOrganizationMembers\":\"ALL\"}"));

        service.updateOrganizationConfiguration(REGION, detector.getId(), request("""
                {"features":[
                  {"name":"RUNTIME_MONITORING","autoEnable":"ALL","additionalConfiguration":[
                    {"name":"ECS_FARGATE_AGENT_MANAGEMENT","autoEnable":"ALL"},
                    {"name":"EC2_AGENT_MANAGEMENT","autoEnable":"ALL"},
                    {"name":"EKS_ADDON_MANAGEMENT","autoEnable":"NONE"}
                  ]}
                ]}
                """));
        service.updateOrganizationConfiguration(REGION, detector.getId(), request(
                "{\"features\":[{\"name\":\"S3_DATA_EVENTS\",\"autoEnable\":\"NEW\"}]}"));

        OrganizationConfiguration configuration =
                service.describeOrganizationConfiguration(REGION, detector.getId());
        assertEquals("ALL", configuration.getAutoEnableOrganizationMembers());
        assertEquals(List.of("RUNTIME_MONITORING", "S3_DATA_EVENTS"),
                configuration.getFeatures().stream().map(OrganizationFeature::getName).toList());
        assertEquals(
                List.of("ECS_FARGATE_AGENT_MANAGEMENT", "EC2_AGENT_MANAGEMENT", "EKS_ADDON_MANAGEMENT"),
                configuration.getFeatures().get(0).getAdditionalConfiguration().stream()
                        .map(OrganizationAdditionalConfiguration::getName)
                        .toList());
    }

    @Test
    void updateOrganizationConfigurationRejectsMissingDetectorAndBadValues() throws Exception {
        AwsException notFound = assertThrows(
                AwsException.class,
                () -> service.updateOrganizationConfiguration(REGION, "missing", request(
                        "{\"autoEnableOrganizationMembers\":\"ALL\"}")));
        assertEquals(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE, notFound.getMessage());

        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));
        AwsException badValue = assertThrows(
                AwsException.class,
                () -> service.updateOrganizationConfiguration(REGION, detector.getId(), request(
                        "{\"autoEnableOrganizationMembers\":\"SOME\"}")));
        assertEquals("BadRequestException", badValue.getErrorCode());
    }

    @Test
    void delegatedAdministratorCreatesEnabledOrganizationMembers() throws Exception {
        String adminAccount = "111111111111";
        String managementAccount = "222222222222";
        service.enableOrganizationAdminAccount(REGION, request("{\"adminAccountId\":\"" + adminAccount + "\"}"));
        Detector adminDetector = service.createDetector(REGION, adminAccount, request("{\"enable\":true}"));
        service.createDetector(REGION, managementAccount, request("{\"enable\":true}"));

        service.createMembers(REGION, adminDetector.getId(), request(
                "{\"accountDetails\":[{\"accountId\":\"" + managementAccount
                        + "\",\"email\":\"management@example.com\"}]}"));

        List<MemberAccount> members = service.listMembers(REGION, adminDetector.getId(), null, null, "true").items();
        assertEquals(1, members.size());
        assertEquals(managementAccount, members.get(0).accountId());
        assertEquals("Enabled", members.get(0).relationshipStatus());
    }

    @Test
    void delegatedAdministratorIsVisibleAcrossAccountPartitions() throws Exception {
        String adminAccount = "111111111111";
        String managementAccount = "222222222222";
        AccountAwareStorageBackend<Detector> detectors = AccountAwareStorageBackend.inMemory(adminAccount);
        AccountAwareStorageBackend<AdminAccount> admins = AccountAwareStorageBackend.inMemory(adminAccount);
        AccountAwareStorageBackend<MemberAccount> members = AccountAwareStorageBackend.inMemory(adminAccount);
        admins.putForAccount(managementAccount, REGION + "::" + adminAccount,
                new AdminAccount(adminAccount, "ENABLED"));
        GuardDutyService partitioned = new GuardDutyService(detectors, admins, members);
        Detector adminDetector = partitioned.createDetector(REGION, adminAccount, request("{\"enable\":true}"));

        partitioned.createMembers(REGION, adminDetector.getId(), request(
                "{\"accountDetails\":[{\"accountId\":\"" + managementAccount
                        + "\",\"email\":\"management@example.com\"}]}"));

        List<MemberAccount> listed = partitioned.listMembers(
                REGION, adminDetector.getId(), null, null, "true").items();
        assertEquals(1, listed.size());
        assertEquals("Enabled", listed.get(0).relationshipStatus());
    }

    @Test
    void adminAccountLifecycle() throws Exception {
        service.enableOrganizationAdminAccount(REGION, request("{\"adminAccountId\":\"111111111111\"}"));

        GuardDutyService.Page<AdminAccount> accounts =
                service.listOrganizationAdminAccounts(REGION, null, null);
        assertEquals(1, accounts.items().size());
        assertEquals("111111111111", accounts.items().get(0).getAdminAccountId());
        assertEquals("ENABLED", accounts.items().get(0).getAdminStatus());

        AwsException conflict = assertThrows(
                AwsException.class,
                () -> service.enableOrganizationAdminAccount(REGION, request(
                        "{\"adminAccountId\":\"222222222222\"}")));
        assertEquals("BadRequestException", conflict.getErrorCode());

        service.disableOrganizationAdminAccount(REGION, request("{\"adminAccountId\":\"111111111111\"}"));
        assertTrue(service.listOrganizationAdminAccounts(REGION, null, null).items().isEmpty());

        AwsException alreadyDisabled = assertThrows(
                AwsException.class,
                () -> service.disableOrganizationAdminAccount(REGION, request(
                        "{\"adminAccountId\":\"111111111111\"}")));
        assertEquals(GuardDutyService.ADMIN_ALREADY_DISABLED_MESSAGE, alreadyDisabled.getMessage());
    }

    @Test
    void enableOrganizationAdminAccountRejectsMalformedAccountId() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.enableOrganizationAdminAccount(REGION, request(
                        "{\"adminAccountId\":\"not-an-account\"}")));

        assertEquals("BadRequestException", error.getErrorCode());
    }

    @Test
    void tagOperationsRoundTripThroughTheDetectorArn() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request(
                "{\"enable\":true,\"tags\":{\"env\":\"test\"}}"));
        String arn = "arn:aws:guardduty:" + REGION + ":" + ACCOUNT + ":detector/" + detector.getId();

        service.tagResource(arn, Map.of("team", "security"));
        assertEquals(Map.of("env", "test", "team", "security"), service.listTags(arn));

        service.untagResource(arn, List.of("env"));
        assertEquals(Map.of("team", "security"), service.listTags(arn));

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.listTags("arn:aws:guardduty:" + REGION + ":" + ACCOUNT + ":detector/missing"));
        assertEquals(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE, error.getMessage());
    }

    @Test
    void detectorSurvivesPersistentStorageReloadWithOrderIntact(@TempDir Path tempDir) throws Exception {
        Path detectorFile = tempDir.resolve("detectors.json");
        Path adminFile = tempDir.resolve("admins.json");
        Path memberFile = tempDir.resolve("members.json");
        GuardDutyService firstService = new GuardDutyService(
                loadedStore(detectorFile, new TypeReference<Map<String, Detector>>() {
                }),
                loadedStore(adminFile, new TypeReference<Map<String, AdminAccount>>() {
                }),
                loadedStore(memberFile, new TypeReference<Map<String, MemberAccount>>() {
                }));
        Detector created = firstService.createDetector(REGION, ACCOUNT, request("""
                {"enable":true,"tags":{"env":"test"},"features":[
                  {"name":"RUNTIME_MONITORING","status":"ENABLED","additionalConfiguration":[
                    {"name":"ECS_FARGATE_AGENT_MANAGEMENT","status":"ENABLED"},
                    {"name":"EC2_AGENT_MANAGEMENT","status":"ENABLED"},
                    {"name":"EKS_ADDON_MANAGEMENT","status":"DISABLED"}
                  ]}
                ]}
                """));
        firstService.updateOrganizationConfiguration(REGION, created.getId(), request(
                "{\"autoEnableOrganizationMembers\":\"ALL\"}"));
        firstService.createSampleFindings(REGION, created.getId(), sampleRequest());
        firstService.createResource(REGION, created.getId(), "filter", request("""
                {"name":"persisted-filter","action":"ARCHIVE","findingCriteria":{"criterion":{"severity":{"gte":7}}}}
                """));

        GuardDutyService reloadedService = new GuardDutyService(
                loadedStore(detectorFile, new TypeReference<Map<String, Detector>>() {
                }),
                loadedStore(adminFile, new TypeReference<Map<String, AdminAccount>>() {
                }),
                loadedStore(memberFile, new TypeReference<Map<String, MemberAccount>>() {
                }));
        Detector reloaded = reloadedService.getDetector(REGION, created.getId());

        assertEquals(created.getId(), reloaded.getId());
        assertEquals(1, reloadedService.listFindings(REGION, created.getId(), request("{}")).path("findingIds").size());
        assertEquals("ARCHIVE", reloadedService.getResource(REGION, created.getId(), "filter", "persisted-filter")
                .path("action").asText());
        assertEquals(created.getCreatedAt(), reloaded.getCreatedAt());
        assertEquals("test", reloaded.getTags().get("env"));
        assertEquals(
                List.of("ECS_FARGATE_AGENT_MANAGEMENT", "EC2_AGENT_MANAGEMENT", "EKS_ADDON_MANAGEMENT"),
                reloaded.getFeatures().get(0).getAdditionalConfiguration().stream()
                        .map(DetectorAdditionalConfiguration::getName)
                        .toList());
        assertEquals("ALL",
                reloadedService.describeOrganizationConfiguration(REGION, created.getId())
                        .getAutoEnableOrganizationMembers());
    }

    @Test
    void samplesAreExplicitStatefulFindingsWithTriageAndStatistics() {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));
        String id = detector.getId();
        assertEquals(0, service.listFindings(REGION, id, request("{}")).path("findingIds").size());
        service.createSampleFindings(REGION, id, sampleRequest());
        String findingId = service.listFindings(REGION, id, request("{}")).path("findingIds").get(0).asText();
        JsonNode ids = request("{\"findingIds\":[\"" + findingId + "\"]}");
        JsonNode finding = service.getFindings(REGION, id, ids).path("findings").get(0);
        assertEquals("Recon:EC2/PortProbeUnprotectedPort", finding.path("type").asText());
        assertEquals(ACCOUNT, finding.path("accountId").asText());
        assertEquals(REGION, finding.path("region").asText());
        assertEquals("arn:aws:guardduty:" + REGION + ":" + ACCOUNT + ":detector/" + id + "/finding/" + findingId,
                finding.path("arn").asText());
        assertEquals("2.0", finding.path("schemaVersion").asText());
        assertEquals("Instance", finding.path("resource").path("resourceType").asText());
        assertEquals("{\"sample\":true}", finding.path("service").path("additionalInfo").path("value").asText());
        assertEquals(id, finding.path("service").path("detectorId").asText());
        assertFalse(finding.path("service").path("archived").asBoolean());
        assertEquals(1, service.getFindingsStatistics(REGION, id,
                request("{\"findingStatisticTypes\":[\"COUNT_BY_SEVERITY\"]}"))
                .path("findingStatistics").path("countBySeverity").path("2.0").asInt());

        service.updateFindings(REGION, id, ids, true);
        JsonNode archived = request("{\"findingCriteria\":{\"criterion\":{\"service.archived\":{\"eq\":[\"true\"]}}}}");
        assertEquals(1, service.listFindings(REGION, id, archived).path("findingIds").size());
        service.updateFindings(REGION, id, ids, false);
        assertEquals(0, service.listFindings(REGION, id, archived).path("findingIds").size());
        service.updateFindings(REGION, id,
                request("{\"findingIds\":[\"" + findingId + "\"],\"feedback\":\"USEFUL\"}"), null);
        assertEquals("USEFUL", service.getFindings(REGION, id, ids).path("findings").get(0)
                .path("service").path("userFeedback").asText());
    }

    @Test
    void findingsValidateBeforeMutationAndAreDetectorScoped() {
        String detectorId = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        assertThrows(AwsException.class, () -> service.createSampleFindings(REGION, detectorId,
                request("{\"findingTypes\":[\"Recon:EC2/PortProbeUnprotectedPort\",\"not-a-finding\"]}")));
        assertEquals(0, service.listFindings(REGION, detectorId, request("{}")).path("findingIds").size());
        service.createSampleFindings(REGION, detectorId, sampleRequest());
        String findingId = service.listFindings(REGION, detectorId, request("{}")).path("findingIds").get(0).asText();
        assertThrows(AwsException.class, () -> service.updateFindings(REGION, detectorId,
                request("{\"findingIds\":[\"" + findingId + "\",\"missing\"]}"), true));
        assertFalse(service.getFindings(REGION, detectorId, request("{\"findingIds\":[\"" + findingId + "\"]}"))
                .path("findings").get(0).path("service").path("archived").asBoolean());
        assertThrows(AwsException.class, () -> service.listFindings("us-west-2", detectorId, request("{}")));
        String other = service.createDetector(REGION, "111111111111", request("{\"enable\":true}")).getId();
        assertEquals(0, service.getFindings(REGION, other, request("{\"findingIds\":[\"" + findingId + "\"]}"))
                .path("findings").size());
        assertThrows(AwsException.class, () -> service.updateFindings(REGION, other,
                request("{\"findingIds\":[\"" + findingId + "\"]}"), true));
        service.updateDetector(REGION, detectorId, request("{\"enable\":false}"));
        assertThrows(AwsException.class, () -> service.createSampleFindings(REGION, detectorId, sampleRequest()));
    }

    @Test
    void findingsPaginateAndRejectUnsupportedCriteria() {
        String detectorId = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        service.createSampleFindings(REGION, detectorId, sampleRequest());
        service.createSampleFindings(REGION, detectorId, sampleRequest());
        JsonNode first = service.listFindings(REGION, detectorId, request("{\"maxResults\":1}"));
        JsonNode second = service.listFindings(REGION, detectorId,
                request("{\"maxResults\":1,\"nextToken\":\"" + first.path("nextToken").asText() + "\"}"));
        assertEquals(1, first.path("findingIds").size());
        assertEquals(1, second.path("findingIds").size());
        assertFalse(first.path("findingIds").get(0).equals(second.path("findingIds").get(0)));
        assertFalse(second.has("nextToken"));
        assertEquals(0, service.listFindings(REGION, detectorId,
                request("{\"findingCriteria\":{\"criterion\":{\"severity\":{\"gte\":7}}}}"))
                .path("findingIds").size());
        assertThrows(AwsException.class, () -> service.listFindings(REGION, detectorId,
                request("{\"findingCriteria\":{\"criterion\":{\"unknown\":{\"eq\":[\"x\"]}}}}")));
        assertThrows(AwsException.class, () -> service.listFindings(REGION, detectorId, request("{\"maxResults\":0}")));
        assertThrows(AwsException.class, () -> service.listFindings(REGION, detectorId, request("{\"nextToken\":\"invalid\"}")));
    }

    @Test
    void readsDoNotInventTelemetryCoverageOrFreeTrialEntitlements() {
        String detectorId = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        service.createSampleFindings(REGION, detectorId, sampleRequest());
        JsonNode usage = service.getUsageStatistics(REGION, detectorId, request("""
                {"usageStatisticsType":"SUM_BY_DATA_SOURCE","usageCriteria":{"dataSources":["FLOW_LOGS"]}}
                """));
        assertEquals(0, usage.path("usageStatistics").path("sumByDataSource").size());
        assertEquals(0, service.listCoverage(REGION, detectorId, request("{}")).path("resources").size());
        JsonNode trial = service.getRemainingFreeTrialDays(REGION, detectorId,
                request("{\"accountIds\":[\"" + ACCOUNT + "\",\"111111111111\"]}"));
        assertEquals(0, trial.path("accounts").size());
        assertEquals(2, trial.path("unprocessedAccounts").size());
        assertTrue(trial.path("unprocessedAccounts").get(0).path("result").asText().contains("no AWS billing enrollment"));
        assertTrue(trial.path("unprocessedAccounts").get(1).path("result").asText().contains("not associated"));
        assertThrows(AwsException.class, () -> service.getUsageStatistics(REGION, detectorId,
                request("{\"usageStatisticsType\":\"invalid\",\"usageCriteria\":{}}")));
        assertThrows(AwsException.class, () -> service.getUsageStatistics(REGION, "missing", request("{}")));
        assertThrows(AwsException.class, () -> service.getRemainingFreeTrialDays(REGION, "missing", request("{}")));
        service.updateDetector(REGION, detectorId,
                request("{\"features\":[{\"name\":\"RUNTIME_MONITORING\",\"status\":\"ENABLED\"}]}"));
        assertThrows(AwsException.class, () -> service.listCoverage(REGION, detectorId, request("{}")));
    }

    @Test
    void invitationReadsRequireAnActualInvitationAndRespectRecipientAndRegion() {
        String recipient = "111111111111";
        String detectorId = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        service.createMembers(REGION, detectorId,
                request("{\"accountDetails\":[{\"accountId\":\"" + recipient + "\",\"email\":\"member@example.com\"}]}"));
        assertEquals(0, service.getInvitationsCount(REGION, recipient));
        assertThrows(AwsException.class, () -> service.inviteMembers(REGION, detectorId,
                request("{\"accountIds\":[\"" + recipient + "\"]}")));
        JsonNode result = service.inviteMembers(REGION, detectorId,
                request("{\"accountIds\":[\"" + recipient + "\",\"222222222222\"],\"disableEmailNotification\":true}"));
        assertEquals(1, result.path("unprocessedAccounts").size());
        assertEquals(1, service.getInvitationsCount(REGION, recipient));
        assertEquals(0, service.getInvitationsCount(REGION, ACCOUNT));
        assertEquals(0, service.getInvitationsCount("us-west-2", recipient));
        MemberAccount invitation = service.listInvitations(REGION, recipient, null, null).items().get(0);
        assertEquals(ACCOUNT, invitation.administratorId());
        assertEquals(32, invitation.invitationId().length());
        assertTrue(invitation.invitedAt().endsWith("Z"));
        service.deleteDetector(REGION, detectorId);
        assertEquals(0, service.getInvitationsCount(REGION, recipient));
    }

    @Test
    void filtersPersistCriteriaTagsAndApplyArchiveOnlyToMatchingNewFindings() {
        String detectorId = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        JsonNode create = request("""
                {"name":"sample-filter","action":"ARCHIVE","rank":1,"tags":{"env":"test"},
                 "findingCriteria":{"criterion":{"severity":{"gte":7}}},"clientToken":"filter-token"}
                """);
        String name = service.createResource(REGION, detectorId, "filter", create);
        assertEquals(name, service.createResource(REGION, detectorId, "filter", create));
        assertEquals(List.of(name), service.listResources(REGION, detectorId, "filter", null, null).items());
        service.createSampleFindings(REGION, detectorId, sampleRequest());
        assertFalse(service.getDetector(REGION, detectorId).getFindings().values().iterator().next()
                .path("service").path("archived").asBoolean());
        service.updateResource(REGION, detectorId, "filter", name,
                request("{\"findingCriteria\":{\"criterion\":{\"severity\":{\"gte\":1}}},\"description\":\"archive samples\"}"));
        service.createSampleFindings(REGION, detectorId, sampleRequest());
        assertEquals(1, service.listFindings(REGION, detectorId,
                request("{\"findingCriteria\":{\"criterion\":{\"service.archived\":{\"eq\":[\"true\"]}}}}"))
                .path("findingIds").size());
        String arn = "arn:aws:guardduty:" + REGION + ":" + ACCOUNT + ":detector/" + detectorId + "/filter/" + name;
        service.tagResource(arn, Map.of("team", "security"));
        service.untagResource(arn, List.of("env"));
        assertEquals(Map.of("team", "security"), service.listTags(arn));
        assertEquals("security", service.getResource(REGION, detectorId, "filter", name).path("tags").path("team").asText());
        assertThrows(AwsException.class, () -> service.listTags(arn.replace(ACCOUNT, "999999999999")));
        GuardDutyTagHandler tags = new GuardDutyTagHandler(service);
        assertThrows(AwsException.class, () -> tags.listTags("us-west-2", arn));
        assertThrows(AwsException.class, () -> tags.tagResource("us-west-2", arn, Map.of("foreign", "tag")));
        assertEquals(Map.of("team", "security"), service.listTags(arn));
        assertThrows(AwsException.class, () -> service.updateResource(REGION, detectorId, "filter", name,
                request("{\"action\":\"NOOP\",\"rank\":2}")));
        assertEquals("ARCHIVE", service.getResource(REGION, detectorId, "filter", name).path("action").asText());
        service.deleteResource(REGION, detectorId, "filter", name);
        assertThrows(AwsException.class, () -> service.getResource(REGION, detectorId, "filter", name));
    }

    @Test
    void ipSetsLoadRealObjectsAndRejectMissingForeignOrMalformedSources() {
        S3Service s3 = mock(S3Service.class);
        when(s3.listBuckets()).thenReturn(List.of(new Bucket("lists-bucket")));
        when(s3.getBucketRegion("lists-bucket")).thenReturn(REGION);
        when(s3.getObject("lists-bucket", "ips.txt")).thenReturn(new S3Object("lists-bucket", "ips.txt",
                "203.0.113.10\n203.0.113.0/24\n".getBytes(StandardCharsets.UTF_8), "text/plain"));
        GuardDutyService withS3 = new GuardDutyService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), s3);
        String detectorId = withS3.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        JsonNode create = request("""
                {"name":"trusted-ips","format":"TXT","location":"https://s3.amazonaws.com/lists-bucket/ips.txt",
                 "activate":true,"clientToken":"ip-token"}
                """);
        String setId = withS3.createResource(REGION, detectorId, "ipset", create);
        assertEquals(setId, withS3.createResource(REGION, detectorId, "ipset", create));
        assertEquals("ACTIVE", withS3.getResource(REGION, detectorId, "ipset", setId).path("status").asText());
        assertFalse(withS3.getResource(REGION, detectorId, "ipset", setId).has("_addresses"));
        assertEquals(2, withS3.getDetector(REGION, detectorId).getResources().get("ipset/" + setId).path("_addresses").size());
        assertThrows(AwsException.class, () -> withS3.updateResource(REGION, detectorId, "ipset", setId,
                request("{\"expectedBucketOwner\":\"111111111111\",\"activate\":true}")));
        assertThrows(AwsException.class, () -> withS3.updateResource(REGION, detectorId, "ipset", setId,
                request("{\"location\":\"https://example.com/ips.txt\"}")));
        when(s3.getObject("lists-bucket", "ips.txt")).thenThrow(new AwsException("NoSuchKey", "missing", 404));
        assertThrows(AwsException.class, () -> withS3.updateResource(REGION, detectorId, "ipset", setId,
                request("{\"activate\":true}")));
        doReturn(new S3Object("lists-bucket", "ips.txt", "not-an-ip\n".getBytes(StandardCharsets.UTF_8), "text/plain"))
                .when(s3).getObject("lists-bucket", "ips.txt");
        assertThrows(AwsException.class, () -> withS3.updateResource(REGION, detectorId, "ipset", setId,
                request("{\"activate\":true}")));
        assertEquals(2, withS3.getDetector(REGION, detectorId).getResources().get("ipset/" + setId).path("_addresses").size());
        when(s3.listBuckets()).thenReturn(List.of());
        assertThrows(AwsException.class, () -> withS3.updateResource(REGION, detectorId, "ipset", setId,
                request("{\"activate\":true}")));
        withS3.updateResource(REGION, detectorId, "ipset", setId, request("{\"activate\":false}"));
        assertEquals("INACTIVE", withS3.getResource(REGION, detectorId, "ipset", setId).path("status").asText());
        withS3.deleteResource(REGION, detectorId, "ipset", setId);
        assertTrue(withS3.listResources(REGION, detectorId, "ipset", null, null).items().isEmpty());
    }

    @Test
    void organizationStatisticsCountOrganizationAccountsAssociatedWithTheDelegatedAdministrator() {
        String admin = "111111111111";
        String activeMember = "222222222222";
        String suspendedMember = "333333333333";
        String outsider = "444444444444";
        OrganizationsService organizations = mock(OrganizationsService.class);
        when(organizations.listAccounts(admin)).thenReturn(List.of(
                orgAccount(admin, "ACTIVE"), orgAccount(activeMember, "ACTIVE"),
                orgAccount(suspendedMember, "SUSPENDED"), orgAccount(outsider, "ACTIVE")));
        GuardDutyService withOrganizations = new GuardDutyService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), null, organizations);

        assertThrows(AwsException.class, () -> withOrganizations.getOrganizationStatistics(REGION, admin));
        withOrganizations.enableOrganizationAdminAccount(REGION, request("{\"adminAccountId\":\"" + admin + "\"}"));
        Detector adminDetector = withOrganizations.createDetector(REGION, admin, request(
                "{\"enable\":true,\"features\":[{\"name\":\"S3_DATA_EVENTS\",\"status\":\"ENABLED\","
                        + "\"additionalConfiguration\":[]}]}"));
        withOrganizations.createMembers(REGION, adminDetector.getId(), request("{\"accountDetails\":["
                + "{\"accountId\":\"" + activeMember + "\",\"email\":\"active@example.com\"},"
                + "{\"accountId\":\"" + suspendedMember + "\",\"email\":\"suspended@example.com\"}]}"));
        withOrganizations.createDetector(REGION, activeMember, request(
                "{\"enable\":true,\"features\":[{\"name\":\"S3_DATA_EVENTS\",\"status\":\"DISABLED\"}]}"));
        withOrganizations.createDetector(REGION, suspendedMember, request("{\"enable\":true}"));

        JsonNode statistics = withOrganizations.getOrganizationStatistics(REGION, admin)
                .path("organizationDetails").path("organizationStatistics");
        assertEquals(4, statistics.path("totalAccountsCount").asInt());
        assertEquals(3, statistics.path("memberAccountsCount").asInt());
        assertEquals(2, statistics.path("activeAccountsCount").asInt());
        assertEquals(2, statistics.path("enabledAccountsCount").asInt());
        assertEquals(1, statistics.path("countByFeature").size());
        assertEquals("S3_DATA_EVENTS", statistics.path("countByFeature").get(0).path("name").asText());
        assertEquals(1, statistics.path("countByFeature").get(0).path("enabledAccountsCount").asInt());

        when(organizations.listAccounts(admin)).thenThrow(
                new AwsException("AWSOrganizationsNotInUseException", "not in use", 400));
        AwsException outside = assertThrows(AwsException.class,
                () -> withOrganizations.getOrganizationStatistics(REGION, admin));
        assertEquals("BadRequestException", outside.getErrorCode());
    }

    @Test
    void administratorAccountIgnoresUnacceptedInvitations() {
        Detector administrator = service.createDetector(REGION, "555555555555", request("{\"enable\":true}"));
        service.createMembers(REGION, administrator.getId(), request(
                "{\"accountDetails\":[{\"accountId\":\"" + ACCOUNT + "\",\"email\":\"member@example.com\"}]}"));
        service.inviteMembers(REGION, administrator.getId(), request(
                "{\"accountIds\":[\"" + ACCOUNT + "\"],\"disableEmailNotification\":true}"));
        Detector member = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        assertNull(service.getAdministratorAccount(REGION, member.getId(), ACCOUNT));
        assertThrows(AwsException.class, () -> service.getAdministratorAccount(REGION, "missing", ACCOUNT));
    }

    @Test
    void malwareScanSettingsPersistWithTheDetector() {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));
        assertEquals("NO_RETENTION",
                service.getMalwareScanSettings(REGION, detector.getId()).path("ebsSnapshotPreservation").asText());

        service.updateMalwareScanSettings(REGION, detector.getId(), request(
                "{\"scanResourceCriteria\":{\"exclude\":{\"EC2_INSTANCE_TAG\":{\"mapEquals\":[{\"key\":\"skip\"}]}}}}"));
        JsonNode settings = service.getMalwareScanSettings(REGION, detector.getId());
        assertEquals("NO_RETENTION", settings.path("ebsSnapshotPreservation").asText());
        assertEquals("skip", settings.at("/scanResourceCriteria/exclude/EC2_INSTANCE_TAG/mapEquals/0/key").asText());
        assertThrows(AwsException.class, () -> service.updateMalwareScanSettings(REGION, detector.getId(),
                request("{\"scanResourceCriteria\":{\"include\":{\"RESOURCE_TYPE\":{\"mapEquals\":[{\"key\":\"k\"}]}}}}")));

        service.deleteDetector(REGION, detector.getId());
        assertThrows(AwsException.class, () -> service.getMalwareScanSettings(REGION, detector.getId()));
    }

    private static OrganizationAccount orgAccount(String id, String status) {
        OrganizationAccount account = new OrganizationAccount();
        account.setId(id);
        account.setStatus(status);
        return account;
    }

    private JsonNode sampleRequest() {
        return request("{\"findingTypes\":[\"Recon:EC2/PortProbeUnprotectedPort\"]}");
    }

    private static <V> PersistentStorage<String, V> loadedStore(
            Path file, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> store = new PersistentStorage<>(file, type);
        store.load();
        return store;
    }

    private JsonNode request(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
