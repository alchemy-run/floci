package io.github.hectorvent.floci.services.securityhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityHubServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "222222222222";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SecurityHubService service;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        service = new SecurityHubService(AccountAwareStorageBackend.inMemory(ACCOUNT_ID), regionResolver,
                mock(OrganizationsService.class));
    }

    @Test
    void enableSecurityHubPersistsConfigurationAndHubTags() throws Exception {
        service.enableSecurityHub(REGION, objectMapper.readTree("""
                {
                  "ControlFindingGenerator": "STANDARD_CONTROL",
                  "Tags": {"env": "test"}
                }
                """));

        SecurityHubState state = service.state(REGION);
        assertTrue(state.isEnabled());
        assertEquals("STANDARD_CONTROL", state.getControlFindingGenerator());
        assertEquals(Map.of("env", "test"), service.tagsForResource(REGION, service.hubArn(REGION)));
    }

    @Test
    void invalidReservedTagPrefixIsRejected() throws Exception {
        AwsException error = assertThrows(AwsException.class, () -> service.enableSecurityHub(REGION,
                objectMapper.readTree("{\"Tags\":{\"aws:owner\":\"test\"}}")));

        assertEquals("InvalidInputException", error.getErrorCode());
    }

    @Test
    void invalidAdministratorFeatureIsRejected() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.enableOrganizationAdminAccount(REGION, "111111111111", "Other"));

        assertEquals("InvalidInputException", error.getErrorCode());
    }

    @Test
    void importedFindingRoundTripsThroughUpdatesHistoryAndSerializedState() throws Exception {
        service.enableSecurityHub(REGION, objectMapper.readTree("{\"EnableDefaultStandards\":false}"));
        ObjectNode request = findingRequest();
        assertEquals(1, service.batchImportFindings(REGION, request).path("SuccessCount").asInt());
        JsonNode identifier = objectMapper.createObjectNode()
                .put("Id", "custom/finding-1").put("ProductArn", request.path("Findings").get(0).path("ProductArn").asText());
        ObjectNode update = objectMapper.createObjectNode();
        update.putArray("FindingIdentifiers").add(identifier);
        update.putObject("Workflow").put("Status", "NOTIFIED");
        update.putObject("Note").put("Text", "acknowledged").put("UpdatedBy", "alchemy");
        assertEquals(1, service.batchUpdateFindings(REGION, update).path("ProcessedFindings").size());

        ObjectNode query = objectMapper.createObjectNode();
        query.putObject("Filters").putArray("Id").addObject().put("Value", "custom/finding-1").put("Comparison", "EQUALS");
        assertEquals("NOTIFIED", service.getFindings(REGION, query).path("Findings").get(0).path("Workflow").path("Status").asText());
        ObjectNode history = objectMapper.createObjectNode();
        history.set("FindingIdentifier", identifier);
        assertEquals(2, service.getFindingHistory(REGION, history).path("Records").size());
        assertEquals("BATCH_UPDATE_FINDINGS", service.getFindingHistory(REGION, history).path("Records").get(1)
                .path("UpdateSource").path("Type").asText());

        SecurityHubState restored = objectMapper.readValue(objectMapper.writeValueAsString(service.state(REGION)), SecurityHubState.class);
        AccountAwareStorageBackend<SecurityHubState> storage = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        storage.put(REGION, restored);
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn(ACCOUNT_ID);
        SecurityHubService reloaded = new SecurityHubService(storage, resolver, mock(OrganizationsService.class));
        assertEquals("NOTIFIED", reloaded.getFindings(REGION, query).path("Findings").get(0).path("Workflow").path("Status").asText());
        assertEquals(2, reloaded.getFindingHistory(REGION, history).path("Records").size());
        reloaded.enableSecurityHub("eu-west-1", objectMapper.readTree("{\"EnableDefaultStandards\":false}"));
        assertEquals(0, reloaded.getFindings("eu-west-1", objectMapper.createObjectNode()).path("Findings").size());
        assertEquals(0, reloaded.getEnabledStandards(REGION, objectMapper.createObjectNode()).path("StandardsSubscriptions").size());

        ((ObjectNode) query.path("Filters").path("Id").get(0)).put("Value", "does-not-exist");
        assertEquals(0, reloaded.getFindings(REGION, query).path("Findings").size());
        assertEquals(1, reloaded.batchImportFindings(REGION, request).path("SuccessCount").asInt());
        assertEquals(1, reloaded.getFindings(REGION, objectMapper.createObjectNode()).path("Findings").size());
        assertEquals("NOTIFIED", reloaded.getFindings(REGION, objectMapper.createObjectNode()).path("Findings")
                .get(0).path("Workflow").path("Status").asText());
    }

    @Test
    void invalidAndForeignFindingsAreNotStored() throws Exception {
        service.enableSecurityHub(REGION, objectMapper.readTree("{\"EnableDefaultStandards\":false}"));
        ObjectNode request = findingRequest();
        ((ObjectNode) request.path("Findings").get(0)).put("AwsAccountId", "333333333333");
        assertEquals(1, service.batchImportFindings(REGION, request).path("FailedCount").asInt());
        ((ObjectNode) request.path("Findings").get(0)).put("AwsAccountId", ACCOUNT_ID).put("CreatedAt", "invalid");
        assertEquals(1, service.batchImportFindings(REGION, request).path("FailedCount").asInt());
        assertEquals(0, service.getFindings(REGION, objectMapper.createObjectNode()).path("Findings").size());
        ObjectNode update = objectMapper.createObjectNode();
        update.putArray("FindingIdentifiers").addObject().put("Id", "missing").put("ProductArn", "missing");
        update.putObject("Workflow").put("Status", "NOTIFIED");
        assertEquals("FindingNotFound", service.batchUpdateFindings(REGION, update).path("UnprocessedFindings").get(0)
                .path("ErrorCode").asText());
    }

    @Test
    void resourceMetadataSupportsUpdatesTagsAndMissingResourceErrors() throws Exception {
        service.enableSecurityHub(REGION, objectMapper.readTree("{\"EnableDefaultStandards\":false}"));
        String actionArn = service.createActionTarget(REGION,
                objectMapper.readTree("{\"Id\":\"escalate\",\"Name\":\"Escalate\",\"Description\":\"initial\"}"))
                .path("ActionTargetArn").asText();
        service.updateActionTarget(REGION, actionArn, objectMapper.readTree("{\"Description\":\"updated\"}"));
        assertEquals("updated", service.describeActionTargets(REGION, objectMapper.createObjectNode()).path("ActionTargets")
                .get(0).path("Description").asText());
        String insightArn = service.createInsight(REGION, objectMapper.readTree("""
                {"Name":"Active","Filters":{"RecordState":[{"Value":"ACTIVE","Comparison":"EQUALS"}]},
                 "GroupByAttribute":"ResourceId"}
                """)).path("InsightArn").asText();
        service.updateInsight(REGION, insightArn, objectMapper.readTree("{\"GroupByAttribute\":\"SeverityLabel\"}"));
        assertEquals("SeverityLabel", service.getInsights(REGION, objectMapper.createObjectNode()).path("Insights")
                .get(0).path("GroupByAttribute").asText());
        String ruleArn = service.createAutomationRule(REGION, objectMapper.readTree("""
                {"RuleName":"SuppressInfo","Description":"Suppress informational findings","RuleOrder":1,
                 "Criteria":{"SeverityLabel":[{"Value":"INFORMATIONAL","Comparison":"EQUALS"}]},
                 "Actions":[{"Type":"FINDING_FIELDS_UPDATE","FindingFieldsUpdate":{"Workflow":{"Status":"SUPPRESSED"}}}],
                 "Tags":{"env":"test"}}
                """)).path("RuleArn").asText();
        ObjectNode update = objectMapper.createObjectNode();
        update.putArray("UpdateAutomationRulesRequestItems").addObject().put("RuleArn", ruleArn)
                .put("RuleOrder", 5).put("RuleStatus", "DISABLED");
        assertEquals(1, service.batchAutomationRules(REGION, update, "update").path("ProcessedAutomationRules").size());
        service.tagResource(REGION, ruleArn, Map.of("env", "prod"));
        assertEquals("prod", service.tagsForResource(REGION, ruleArn).get("env"));
        ObjectNode get = objectMapper.createObjectNode();
        get.putArray("AutomationRulesArns").add(ruleArn);
        assertEquals(5, service.batchAutomationRules(REGION, get, "get").path("Rules").get(0).path("RuleOrder").asInt());
        service.batchAutomationRules(REGION, get, "delete");
        assertEquals(1, service.batchAutomationRules(REGION, get, "get").path("UnprocessedAutomationRules").size());
        service.deleteInsight(REGION, insightArn);
        service.deleteActionTarget(REGION, actionArn);
        AwsException error = assertThrows(AwsException.class, () -> service.deleteActionTarget(REGION, actionArn));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    private ObjectNode findingRequest() throws Exception {
        ObjectNode finding = (ObjectNode) objectMapper.readTree("""
                {"SchemaVersion":"2018-10-08","Id":"custom/finding-1","GeneratorId":"alchemy",
                 "AwsAccountId":"222222222222","Types":["Software and Configuration Checks"],
                 "CreatedAt":"2026-09-21T00:00:00Z","UpdatedAt":"2026-09-21T00:00:00Z",
                 "Severity":{"Label":"INFORMATIONAL"},"Title":"Imported finding","Description":"Custom finding",
                 "Resources":[{"Type":"Other","Id":"custom-resource"}]}
                """);
        finding.put("ProductArn", "arn:aws:securityhub:" + REGION + ":" + ACCOUNT_ID + ":product/" + ACCOUNT_ID + "/default");
        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("Findings").add(finding);
        return request;
    }

    @Test
    void clearRemovesState() throws Exception {
        service.enableSecurityHub(REGION, objectMapper.readTree("{}"));
        assertTrue(service.state(REGION).isEnabled());

        service.clear();

        assertFalse(service.state(REGION).isEnabled());
    }
}
