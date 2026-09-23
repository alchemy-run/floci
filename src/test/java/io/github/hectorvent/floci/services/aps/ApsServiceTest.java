package io.github.hectorvent.floci.services.aps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.aps.ApsService.ConfigurationKind;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import io.github.hectorvent.floci.services.aps.model.RuleGroupsNamespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ApsServiceTest {

    private static final String US_EAST_1 = "us-east-1";
    private static final String EU_WEST_1 = "eu-west-1";
    private static final String RULES = Base64.getEncoder().encodeToString(
            "groups:\n- name: alerts\n  rules: []\n".getBytes(StandardCharsets.UTF_8));

    private ApsService service;
    private ApsPrometheusBackend backend;
    private final Map<String, StorageBackend<String, ?>> backendsByFile = new HashMap<>();

    @BeforeEach
    void setUp() {
        backendsByFile.clear();
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> {
                    StorageBackend<String, ?> backend = AccountAwareStorageBackend.inMemory("000000000000");
                    backendsByFile.put(invocation.getArgument(1), backend);
                    return backend;
                });

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("https://localhost:4566");
        backend = Mockito.mock(ApsPrometheusBackend.class);
        service = new ApsService(storageFactory, new RegionResolver(US_EAST_1, "000000000000"), config, backend);
    }

    @Test
    void createWorkspaceIsActiveWithArnAndEndpoint() {
        PrometheusWorkspace workspace =
                service.createWorkspace(US_EAST_1, "my-workspace", Map.of("team", "devops"), null);

        assertTrue(workspace.getWorkspaceId().startsWith("ws-"));
        assertEquals("ACTIVE", workspace.getStatus());
        assertEquals("arn:aws:aps:us-east-1:000000000000:workspace/" + workspace.getWorkspaceId(),
                workspace.getArn());
        assertEquals("https://aps-workspaces-" + workspace.getWorkspaceId() + ".localhost.floci.io:4566/workspaces/"
                + workspace.getWorkspaceId() + "/", workspace.getPrometheusEndpoint());
        Mockito.verifyNoInteractions(backend);
        assertNotNull(workspace.getCreatedAt());
        assertEquals("devops", workspace.getTags().get("team"));
    }

    @Test
    void describeWorkspaceUnknownIdThrowsResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeWorkspace(US_EAST_1, "ws-missing"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void describeWorkspaceAfterDeleteThrowsResourceNotFound() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "doomed", null, null);
        service.deleteWorkspace(US_EAST_1, workspace.getWorkspaceId());

        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeWorkspace(US_EAST_1, workspace.getWorkspaceId()));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void deletionStopsOwnedRuntimeBeforeRemovingWorkspace() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "runtime", null, null);
        service.forward(US_EAST_1, workspace.getWorkspaceId(), "GET", "/api/v1/query", "query=up",
                Map.of(), new byte[0]);
        assertTrue(workspace.isPrometheusRuntimeOwned());
        service.deleteWorkspace(US_EAST_1, workspace.getWorkspaceId());
        Mockito.verify(backend).remove(workspace.getArn());
        assertThrows(AwsException.class, () -> service.forward(US_EAST_1, workspace.getWorkspaceId(),
                "GET", "/api/v1/query", "query=up", Map.of(), new byte[0]));
    }

    @Test
    void failedRuntimeCleanupRetainsWorkspaceForRetry() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "runtime", null, null);
        service.forward(US_EAST_1, workspace.getWorkspaceId(), "GET", "/api/v1/query", "query=up",
                Map.of(), new byte[0]);
        Mockito.doThrow(new IllegalStateException("Docker unavailable")).when(backend).remove(workspace.getArn());
        assertThrows(IllegalStateException.class,
                () -> service.deleteWorkspace(US_EAST_1, workspace.getWorkspaceId()));
        assertTrue(service.describeWorkspace(US_EAST_1, workspace.getWorkspaceId()).isPrometheusRuntimeOwned());
    }

    @Test
    void metadataOnlyDeletionDoesNotStartDocker() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "metadata", null, null);
        service.deleteWorkspace(US_EAST_1, workspace.getWorkspaceId());
        Mockito.verifyNoInteractions(backend);
    }

    @Test
    void workspacesAreScopedToTheirRegion() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "regional", null, null);

        AwsException describe = assertThrows(AwsException.class,
                () -> service.describeWorkspace(EU_WEST_1, workspace.getWorkspaceId()));
        assertEquals("ResourceNotFoundException", describe.getErrorCode());
        assertThrows(AwsException.class, () -> service.forward(EU_WEST_1, workspace.getWorkspaceId(),
                "GET", "/api/v1/query", "query=up", Map.of(), new byte[0]));
        Mockito.verifyNoInteractions(backend);

        assertEquals(0, service.listWorkspaces(EU_WEST_1, null, null, null).items().size());
        assertEquals(1, service.listWorkspaces(US_EAST_1, null, null, null).items().size());

        AwsException delete = assertThrows(AwsException.class,
                () -> service.deleteWorkspace(EU_WEST_1, workspace.getWorkspaceId()));
        assertEquals("ResourceNotFoundException", delete.getErrorCode());
        // The cross-region delete must not have touched the real workspace.
        assertEquals(workspace.getWorkspaceId(),
                service.describeWorkspace(US_EAST_1, workspace.getWorkspaceId()).getWorkspaceId());
    }

    @Test
    void listWorkspacesFiltersByAliasPrefix() {
        service.createWorkspace(US_EAST_1, "prod-metrics", null, null);
        service.createWorkspace(US_EAST_1, "prod-traces", null, null);
        service.createWorkspace(US_EAST_1, "staging-metrics", null, null);

        assertEquals(2, service.listWorkspaces(US_EAST_1, "prod-", null, null).items().size());
        assertEquals(3, service.listWorkspaces(US_EAST_1, null, null, null).items().size());
        assertEquals(0, service.listWorkspaces(US_EAST_1, "missing", null, null).items().size());
    }

    @Test
    void aliasesAreStrippedOnCreateUpdateAndListFilter() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, " prod ", null, null);
        assertEquals("prod", workspace.getAlias());

        // AWS strips the filter value too, so " prod " round-trips against a "prod" alias.
        assertEquals(1, service.listWorkspaces(US_EAST_1, " prod ", null, null).items().size());

        service.updateWorkspaceAlias(US_EAST_1, workspace.getWorkspaceId(), "  renamed  ");
        assertEquals("renamed",
                service.describeWorkspace(US_EAST_1, workspace.getWorkspaceId()).getAlias());
    }

    @Test
    void listWorkspacesPaginates() {
        service.createWorkspace(US_EAST_1, "a", null, null);
        service.createWorkspace(US_EAST_1, "b", null, null);
        service.createWorkspace(US_EAST_1, "c", null, null);

        PaginatedResult<PrometheusWorkspace> firstPage =
                service.listWorkspaces(US_EAST_1, null, 2, null);
        assertEquals(2, firstPage.items().size());
        assertNotNull(firstPage.nextToken());

        PaginatedResult<PrometheusWorkspace> secondPage =
                service.listWorkspaces(US_EAST_1, null, 2, firstPage.nextToken());
        assertEquals(1, secondPage.items().size());
        assertNull(secondPage.nextToken());
    }

    @Test
    void listWorkspacesDefaultsToPagesOf100() {
        for (int i = 0; i < 101; i++) {
            service.createWorkspace(US_EAST_1, "bulk-" + i, null, null);
        }

        PaginatedResult<PrometheusWorkspace> page = service.listWorkspaces(US_EAST_1, null, null, null);
        assertEquals(100, page.items().size());
        assertNotNull(page.nextToken());
    }

    @Test
    void listWorkspacesRejectsZeroMaxResultsWithValidationException() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.listWorkspaces(US_EAST_1, null, 0, null));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void updateWorkspaceAliasPersists() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "old-alias", null, null);
        service.updateWorkspaceAlias(US_EAST_1, workspace.getWorkspaceId(), "new-alias");
        assertEquals("new-alias",
                service.describeWorkspace(US_EAST_1, workspace.getWorkspaceId()).getAlias());
    }

    @Test
    void tagHandlerRoundTripsTagsByArn() {
        PrometheusWorkspace workspace =
                service.createWorkspace(US_EAST_1, "tagged", Map.of("env", "test"), null);
        String arn = workspace.getArn();

        assertEquals("aps", service.serviceKey());
        assertEquals(Map.of("env", "test"), service.listTags(US_EAST_1, arn));

        service.tagResource(US_EAST_1, arn, Map.of("team", "devops"));
        assertEquals(Map.of("env", "test", "team", "devops"), service.listTags(US_EAST_1, arn));

        service.untagResource(US_EAST_1, arn, List.of("env"));
        assertEquals(Map.of("team", "devops"), service.listTags(US_EAST_1, arn));
    }

    @Test
    void tagOperationsAreScopedToTheRequestRegion() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "tagged", null, null);

        // A tag call served by another region must not see (or mutate) this workspace.
        AwsException ex = assertThrows(AwsException.class,
                () -> service.tagResource(EU_WEST_1, workspace.getArn(), Map.of("team", "devops")));
        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(service.listTags(US_EAST_1, workspace.getArn()).isEmpty());
    }

    @Test
    void tagResourceRejectsReservedAwsKeyPrefix() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "tagged", null, null);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.tagResource(US_EAST_1, workspace.getArn(), Map.of("aws:cloudformation:stack", "x")));
        assertEquals("ValidationException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void tagHandlerUnknownWorkspaceArnThrowsResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.listTags(US_EAST_1, "arn:aws:aps:us-east-1:000000000000:workspace/ws-missing"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void tagHandlerMalformedArnThrowsValidationException() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.listTags(US_EAST_1, "arn:aws:aps:us-east-1:000000000000:workspace"));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void describeMissingScraperReturnsTypedNotFound() {
        AwsException error = assertThrows(AwsException.class, () -> service.describeScraper(US_EAST_1,
                "s-00000000-0000-0000-0000-000000000000"));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void definitionsValidateYamlAndPreserveControlPlaneOnlyLifecycle() {
        String workspaceId = service.createWorkspace(US_EAST_1, "definitions", null, null).getWorkspaceId();
        String definition = encoded("alertmanager_config: |\n  route:\n    receiver: default\n  receivers:\n    - name: default\n");
        ObjectNode created = service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.ALERT_MANAGER,
                Map.of("data", definition), true);
        assertEquals(definition, created.path("data").asText());
        assertTrue(created.path("status").path("statusReason").asText().contains("not implemented"));
        assertTrue(created.path("createdAt").isNumber());
        assertEquals("ConflictException", assertThrows(AwsException.class, () ->
                service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.ALERT_MANAGER,
                        Map.of("data", definition), true)).getErrorCode());
        String changed = encoded("alertmanager_config: |\n  route:\n    receiver: updated\n  receivers:\n    - name: updated\n");
        service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.ALERT_MANAGER, Map.of("data", changed), false);
        for (String invalid : List.of("not-base64", encoded("route: {}"),
                encoded("alertmanager_config: |\n  route: {receiver: missing}\n  receivers: [{name: default}]\n"))) {
            assertEquals("ValidationException", assertThrows(AwsException.class, () ->
                    service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.ALERT_MANAGER,
                            Map.of("data", invalid), false)).getErrorCode());
        }
        assertEquals(changed, service.describeConfiguration(US_EAST_1, workspaceId,
                ConfigurationKind.ALERT_MANAGER).path("data").asText());
        assertEquals("ValidationException", assertThrows(AwsException.class, () ->
                service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "invalid",
                        encoded("groups:\n- name: bad\n  rules:\n  - record: rate\n"), null)).getErrorCode());
        service.deleteConfiguration(US_EAST_1, workspaceId, ConfigurationKind.ALERT_MANAGER, null);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () ->
                service.describeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.ALERT_MANAGER)).getErrorCode());
        Mockito.verifyNoInteractions(backend);
    }

    @Test
    void workspaceSettingsMergeAndRejectInvalidChanges() throws Exception {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "configuration", null, null);
        String workspaceId = workspace.getWorkspaceId();
        service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.WORKSPACE,
                Map.of("retentionPeriodInDays", 30, "outOfOrderTimeWindowInSeconds", 60), false);
        service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.WORKSPACE,
                Map.of("retentionPeriodInDays", 45), false);
        ObjectNode settings = service.describeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.WORKSPACE);
        assertEquals(45, settings.path("retentionPeriodInDays").asInt());
        assertEquals(60, settings.path("outOfOrderTimeWindowInSeconds").asInt());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PrometheusWorkspace restored = mapper.readValue(mapper.writeValueAsBytes(workspace), PrometheusWorkspace.class);
        assertEquals(settings, restored.getConfigurations().get(ConfigurationKind.WORKSPACE.name()));
        PrometheusWorkspace legacy = mapper.readValue("{}", PrometheusWorkspace.class);
        assertTrue(legacy.getConfigurations().isEmpty());
        assertTrue(legacy.getAnomalyDetectors().isEmpty());
        settings.put("retentionPeriodInDays", 999);
        assertEquals(45, service.describeConfiguration(US_EAST_1, workspaceId,
                ConfigurationKind.WORKSPACE).path("retentionPeriodInDays").asInt());
        assertEquals("ValidationException", assertThrows(AwsException.class, () ->
                service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.WORKSPACE,
                        Map.of("retentionPeriodInDays", -1), false)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () ->
                service.describeConfiguration(EU_WEST_1, workspaceId, ConfigurationKind.WORKSPACE)).getErrorCode());
        Mockito.verifyNoInteractions(backend);
    }

    @Test
    void loggingConfigurationAndQueryDestinationsRoundTripAndCascade() {
        String workspaceId = service.createWorkspace(US_EAST_1, "logging", null, null).getWorkspaceId();
        String arn = "arn:aws:logs:us-east-1:000000000000:log-group:/aws/vendedlogs/prometheus/test:*";
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () ->
                service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.LOGGING,
                        Map.of("logGroupArn", arn), false)).getErrorCode());
        service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.LOGGING, Map.of("logGroupArn", arn), true);
        Map<String, Object> destination = Map.of("cloudWatchLogs", Map.of("logGroupArn", arn),
                "filters", Map.of("qspThreshold", 0));
        service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.QUERY_LOGGING,
                Map.of("destinations", List.of(destination)), true);
        ObjectNode queryLogging = service.describeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.QUERY_LOGGING);
        assertEquals(workspaceId, queryLogging.path("workspace").asText());
        assertEquals(0, queryLogging.path("destinations").get(0).path("filters").path("qspThreshold").asInt());
        assertEquals("ValidationException", assertThrows(AwsException.class, () ->
                service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.LOGGING,
                        Map.of("logGroupArn", arn.replace("000000000000", "999999999999")), false)).getErrorCode());
        service.deleteWorkspace(US_EAST_1, workspaceId);
        for (ConfigurationKind kind : List.of(ConfigurationKind.LOGGING, ConfigurationKind.QUERY_LOGGING)) {
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () ->
                    service.describeConfiguration(US_EAST_1, workspaceId, kind)).getErrorCode());
        }
        Mockito.verifyNoInteractions(backend);
    }

    @Test
    void policiesValidateAndEnforceRevisionPreconditions() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "policy", null, null);
        String workspaceId = workspace.getWorkspaceId();
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::000000000000:root"},
                "Action":["aps:QueryMetrics"],"Resource":"%s"}]}
                """.formatted(workspace.getArn());
        ObjectNode first = service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.POLICY,
                Map.of("policyDocument", policy), false);
        String revision = first.path("revisionId").asText();
        assertEquals(revision, service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.POLICY,
                Map.of("policyDocument", policy, "revisionId", revision), false).path("revisionId").asText());
        String updated = policy.replace("aps:QueryMetrics", "aps:GetLabels");
        ObjectNode second = service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.POLICY,
                Map.of("policyDocument", updated, "revisionId", revision), false);
        assertNotEquals(revision, second.path("revisionId").asText());
        assertEquals("ConflictException", assertThrows(AwsException.class, () ->
                service.deleteConfiguration(US_EAST_1, workspaceId, ConfigurationKind.POLICY, revision)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class, () ->
                service.writeConfiguration(US_EAST_1, workspaceId, ConfigurationKind.POLICY,
                        Map.of("policyDocument", "{}"), false)).getErrorCode());
        assertEquals(updated, service.describeConfiguration(US_EAST_1, workspaceId,
                ConfigurationKind.POLICY).path("policyDocument").asText());
        service.deleteConfiguration(US_EAST_1, workspaceId, ConfigurationKind.POLICY, second.path("revisionId").asText());
    }

    @Test
    void anomalyMetadataPersistsWithoutClaimingExecution() throws Exception {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "detectors", null, null);
        String workspaceId = workspace.getWorkspaceId();
        Map<String, Object> request = Map.of("alias", "detector", "configuration", Map.of("randomCutForest", Map.of("query", "up")),
                "evaluationIntervalInSeconds", 60, "missingDataAction", Map.of("skip", true),
                "tags", Map.of("team", "metrics"), "clientToken", "create-detector");
        ObjectNode first = service.createAnomalyDetector(US_EAST_1, workspaceId, request);
        String detectorId = first.path("anomalyDetectorId").asText();
        String arn = first.path("arn").asText();
        assertEquals("CREATION_FAILED", first.path("status").path("statusCode").asText());
        assertTrue(first.path("status").path("statusReason").asText().contains("not implemented"));
        assertEquals(detectorId, service.createAnomalyDetector(US_EAST_1, workspaceId, request).path("anomalyDetectorId").asText());
        Map<String, Object> conflicting = new HashMap<>(request);
        conflicting.put("evaluationIntervalInSeconds", 120);
        assertEquals("ConflictException", assertThrows(AwsException.class, () ->
                service.createAnomalyDetector(US_EAST_1, workspaceId, conflicting)).getErrorCode());
        service.tagResource(US_EAST_1, arn, Map.of("alchemy::id", "Detector"));
        service.untagResource(US_EAST_1, arn, List.of("team"));
        assertEquals(Map.of("alchemy::id", "Detector"), service.listTags(US_EAST_1, arn));
        ObjectNode updated = service.putAnomalyDetector(US_EAST_1, workspaceId, detectorId,
                Map.of("configuration", request.get("configuration"), "evaluationIntervalInSeconds", 120));
        assertEquals(120, updated.path("evaluationIntervalInSeconds").asInt());
        assertEquals("UPDATE_FAILED", updated.path("status").path("statusCode").asText());
        assertEquals(1, service.listAnomalyDetectors(US_EAST_1, workspaceId, "det", 1, null).items().size());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PrometheusWorkspace restored = mapper.readValue(mapper.writeValueAsBytes(workspace), PrometheusWorkspace.class);
        assertEquals(updated.path("configuration"), restored.getAnomalyDetectors().get(detectorId).getDescription().get("configuration"));
        assertEquals("Detector", restored.getAnomalyDetectors().get(detectorId).getTags().get("alchemy::id"));
        service.deleteAnomalyDetector(US_EAST_1, workspaceId, detectorId);
        service.deleteAnomalyDetector(US_EAST_1, workspaceId, detectorId);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () ->
                service.describeAnomalyDetector(US_EAST_1, workspaceId, detectorId)).getErrorCode());
        Mockito.verifyNoInteractions(backend);
    }

    private static String encoded(String yaml) {
        return Base64.getEncoder().encodeToString(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private String workspaceWithNamespace(String namespaceName) {
        String workspaceId = service.createWorkspace(US_EAST_1, "rules", null, null).getWorkspaceId();
        service.createRuleGroupsNamespace(US_EAST_1, workspaceId, namespaceName, RULES, null);
        return workspaceId;
    }

    @Test
    void createRuleGroupsNamespaceIsActiveWithArnAndRoundTripsData() {
        String workspaceId = service.createWorkspace(US_EAST_1, "rules", null, null).getWorkspaceId();

        RuleGroupsNamespace namespace = service.createRuleGroupsNamespace(
                US_EAST_1, workspaceId, "alerts", RULES, Map.of("team", "devops"));

        assertEquals("alerts", namespace.getName());
        assertEquals("ACTIVE", namespace.getStatus());
        assertEquals("arn:aws:aps:us-east-1:000000000000:rulegroupsnamespace/" + workspaceId + "/alerts",
                namespace.getArn());
        assertEquals(RULES, namespace.getEncodedData());
        assertNotNull(namespace.getCreatedAt());
        assertNotNull(namespace.getModifiedAt());
        assertEquals("devops", namespace.getTags().get("team"));
    }

    @Test
    void createRuleGroupsNamespaceUnknownWorkspaceThrowsResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.createRuleGroupsNamespace(US_EAST_1, "ws-missing", "alerts", RULES, null));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void createRuleGroupsNamespaceRejectsDuplicateNameWithConflict() {
        String workspaceId = workspaceWithNamespace("alerts");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts", RULES, null));
        assertEquals("ConflictException", ex.getErrorCode());
        assertEquals(409, ex.getHttpStatus());
    }

    @Test
    void createRuleGroupsNamespaceRejectsMissingData() {
        String workspaceId = service.createWorkspace(US_EAST_1, "rules", null, null).getWorkspaceId();

        AwsException ex = assertThrows(AwsException.class, () ->
                service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts", null, null));
        assertEquals("ValidationException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void putRuleGroupsNamespaceReplacesTheStoredData() {
        String workspaceId = workspaceWithNamespace("alerts");
        String updated = Base64.getEncoder().encodeToString(
                "groups:\n- name: updated\n  rules: []\n".getBytes(StandardCharsets.UTF_8));

        service.putRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts", updated);

        assertEquals(updated,
                service.describeRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts").getEncodedData());
    }

    @Test
    void putRuleGroupsNamespaceUnknownNameThrowsResourceNotFound() {
        String workspaceId = workspaceWithNamespace("alerts");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.putRuleGroupsNamespace(US_EAST_1, workspaceId, "missing", RULES));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void listRuleGroupsNamespacesFiltersByNamePrefixAndPaginates() {
        String workspaceId = workspaceWithNamespace("prod-alerts");
        service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "prod-records", RULES, null);
        service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "staging-alerts", RULES, null);

        assertEquals(3, service.listRuleGroupsNamespaces(US_EAST_1, workspaceId, null, null, null)
                .items().size());
        assertEquals(2, service.listRuleGroupsNamespaces(US_EAST_1, workspaceId, "prod-", null, null)
                .items().size());

        PaginatedResult<RuleGroupsNamespace> firstPage =
                service.listRuleGroupsNamespaces(US_EAST_1, workspaceId, null, 2, null);
        assertEquals(2, firstPage.items().size());
        assertNotNull(firstPage.nextToken());
        assertEquals(1, service.listRuleGroupsNamespaces(US_EAST_1, workspaceId, null, 2,
                firstPage.nextToken()).items().size());
    }

    @Test
    void ruleGroupsNamespacesAreScopedToTheirWorkspace() {
        String workspaceId = workspaceWithNamespace("alerts");
        String otherWorkspaceId =
                service.createWorkspace(US_EAST_1, "other", null, null).getWorkspaceId();

        assertEquals(0, service.listRuleGroupsNamespaces(US_EAST_1, otherWorkspaceId, null, null, null)
                .items().size());
        AwsException ex = assertThrows(AwsException.class, () ->
                service.describeRuleGroupsNamespace(US_EAST_1, otherWorkspaceId, "alerts"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals("alerts",
                service.describeRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts").getName());
    }

    @Test
    void deleteRuleGroupsNamespaceThenDescribeThrowsResourceNotFound() {
        String workspaceId = workspaceWithNamespace("alerts");

        service.deleteRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.describeRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void deleteWorkspaceRemovesItsRuleGroupsNamespaces() {
        String workspaceId = workspaceWithNamespace("alerts");
        service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "records", RULES, null);

        service.deleteWorkspace(US_EAST_1, workspaceId);

        assertTrue(backendsByFile.get("aps-rule-groups-namespaces.json").scan(k -> true).isEmpty());
    }

    @Test
    void createRuleGroupsNamespaceRejectsNamesThatBreakPathAddressing() {
        String workspaceId = service.createWorkspace(US_EAST_1, "rules", null, null).getWorkspaceId();

        for (String invalid : List.of("nested/name", "", "!!!", "x".repeat(129))) {
            AwsException ex = assertThrows(AwsException.class, () ->
                    service.createRuleGroupsNamespace(US_EAST_1, workspaceId, invalid, RULES, null));
            assertEquals("ValidationException", ex.getErrorCode(), "name: " + invalid);
            assertEquals(400, ex.getHttpStatus());
        }
    }

    @Test
    void tagHandlerRejectsArnsFromAnotherAccountOrRegion() {
        String workspaceId = service.createWorkspace(US_EAST_1, "rules", null, null).getWorkspaceId();
        service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts", RULES, null);

        String foreignAccount = "arn:aws:aps:us-east-1:999999999999:rulegroupsnamespace/"
                + workspaceId + "/alerts";
        AwsException byAccount = assertThrows(AwsException.class,
                () -> service.listTags(US_EAST_1, foreignAccount));
        assertEquals("ValidationException", byAccount.getErrorCode());

        String foreignRegion = "arn:aws:aps:eu-west-1:000000000000:rulegroupsnamespace/"
                + workspaceId + "/alerts";
        AwsException byRegion = assertThrows(AwsException.class,
                () -> service.listTags(US_EAST_1, foreignRegion));
        assertEquals("ValidationException", byRegion.getErrorCode());

        String foreignService = "arn:aws:ecs:us-east-1:000000000000:rulegroupsnamespace/"
                + workspaceId + "/alerts";
        AwsException byService = assertThrows(AwsException.class,
                () -> service.listTags(US_EAST_1, foreignService));
        assertEquals("ValidationException", byService.getErrorCode());
    }

    @Test
    void tagHandlerRoundTripsRuleGroupsNamespaceTagsByArn() {
        String workspaceId = service.createWorkspace(US_EAST_1, "rules", null, null).getWorkspaceId();
        String arn = service.createRuleGroupsNamespace(
                US_EAST_1, workspaceId, "alerts", RULES, Map.of("env", "test")).getArn();

        assertEquals(Map.of("env", "test"), service.listTags(US_EAST_1, arn));

        service.tagResource(US_EAST_1, arn, Map.of("team", "devops"));
        assertEquals(Map.of("env", "test", "team", "devops"), service.listTags(US_EAST_1, arn));

        service.untagResource(US_EAST_1, arn, List.of("env"));
        assertEquals(Map.of("team", "devops"), service.listTags(US_EAST_1, arn));
    }
}
