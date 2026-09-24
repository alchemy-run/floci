package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.accessanalyzer.model.Analyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessAnalyzerServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";

    private final ObjectMapper mapper = new ObjectMapper();
    private AccessAnalyzerService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        AccountAwareStorageBackend<Analyzer> store = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        when(storageFactory.create(eq("accessanalyzer"), eq("accessanalyzer-analyzers.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) store);

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.buildArn(eq("access-analyzer"), eq(REGION), any(String.class)))
                .thenAnswer(invocation -> "arn:aws:access-analyzer:" + REGION + ":" + ACCOUNT_ID + ":"
                        + invocation.getArgument(2, String.class));
        service = new AccessAnalyzerService(storageFactory, regionResolver);
    }

    @Test
    void quotasAreAppliedPerExactAnalyzerType() {
        service.createAnalyzer(request("external", "ACCOUNT"), REGION);
        service.createAnalyzer(request("unused", "ACCOUNT_UNUSED_ACCESS"), REGION);

        AwsException duplicateType = assertThrows(AwsException.class,
                () -> service.createAnalyzer(request("external-2", "ACCOUNT"), REGION));
        assertEquals("ServiceQuotaExceededException", duplicateType.getErrorCode());
    }

    @Test
    void organizationInternalAccessLimitIsOne() {
        service.createAnalyzer(request("internal", "ORGANIZATION_INTERNAL_ACCESS"), REGION);
        AwsException duplicateType = assertThrows(AwsException.class,
                () -> service.createAnalyzer(request("internal-2", "ORGANIZATION_INTERNAL_ACCESS"), REGION));
        assertEquals("ServiceQuotaExceededException", duplicateType.getErrorCode());
    }

    @Test
    void clearRemovesPersistedState() {
        service.createAnalyzer(request("reset-me", "ACCOUNT"), REGION);
        service.clear();
        assertTrue(service.listAnalyzers(REGION, null, null, null).items().isEmpty());
    }

    @Test
    void unusedAccessConfigurationDefaultsAndValidatesBeforePersisting() {
        Analyzer defaulted = service.createAnalyzer(request("unused", "ACCOUNT_UNUSED_ACCESS"), REGION);
        assertEquals(90, defaulted.getConfiguration().path("unusedAccess").path("unusedAccessAge").asInt());
        service.deleteAnalyzer(REGION, "unused");
        for (int age : new int[]{0, 366}) {
            ObjectNode invalid = request("unused", "ACCOUNT_UNUSED_ACCESS");
            invalid.putObject("configuration").putObject("unusedAccess").put("unusedAccessAge", age);
            assertEquals("ValidationException", assertThrows(AwsException.class,
                    () -> service.createAnalyzer(invalid, REGION)).getErrorCode());
            assertTrue(service.listAnalyzers(REGION, null, null, null).items().isEmpty());
        }
        ObjectNode configured = request("unused", "ACCOUNT_UNUSED_ACCESS");
        configured.putObject("configuration").putObject("unusedAccess").put("unusedAccessAge", 180);
        service.createAnalyzer(configured, REGION);
        assertEquals(180, service.getAnalyzer(REGION, "unused").getConfiguration()
                .path("unusedAccess").path("unusedAccessAge").asInt());
        service.deleteAnalyzer(REGION, "unused");
        configured.withObject("/configuration/unusedAccess").put("unusedAccessAge", 365);
        service.createAnalyzer(configured, REGION);
        assertEquals(365, service.getAnalyzer(REGION, "unused").getConfiguration()
                .path("unusedAccess").path("unusedAccessAge").asInt());
        service.deleteAnalyzer(REGION, "unused");
        configured.put("type", "ACCOUNT");
        assertThrows(AwsException.class, () -> service.createAnalyzer(configured, REGION));
    }

    @Test
    void tagsMergeRemoveAndRejectForeignArnsWithoutMutation() {
        ObjectNode request = request("tagged", "ACCOUNT");
        request.putObject("tags").put("keep", "one").put("replace", "old");
        String arn = service.createAnalyzer(request, REGION).getArn();
        service.tag(REGION, arn, Map.of("replace", "new", "added", "two"));
        service.untag(REGION, arn, List.of("added", "missing"));
        assertEquals(Map.of("keep", "one", "replace", "new"), service.listTags(REGION, arn));
        assertThrows(AwsException.class, () -> service.tag(REGION, arn, Map.of("aws:reserved", "x")));
        assertThrows(AwsException.class, () -> service.tag(REGION, arn.replace(ACCOUNT_ID, "999999999999"), Map.of("keep", "bad")));
        assertThrows(AwsException.class, () -> service.tag("us-west-2", arn, Map.of("keep", "bad")));
        assertEquals("one", service.listTags(REGION, arn).get("keep"));
    }

    @Test
    void archiveRulesPersistCopyFiltersPaginateAndCascadeWithAnalyzer() throws Exception {
        service.createAnalyzer(request("rules", "ACCOUNT"), REGION);
        ObjectNode rule = mapper.createObjectNode().put("ruleName", "first");
        rule.putObject("filter").putObject("principal.AWS").putArray("eq").add("111111111111");
        service.createArchiveRule(REGION, "rules", rule);
        String createdAt = service.getArchiveRule(REGION, "rules", "first").path("createdAt").asText();
        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> service.createArchiveRule(REGION, "rules", rule)).getErrorCode());
        rule.withObject("/filter/principal.AWS").putArray("eq").add("222222222222");
        assertEquals("111111111111", service.getArchiveRule(REGION, "rules", "first")
                .path("filter").path("principal.AWS").path("eq").get(0).asText());
        service.updateArchiveRule(REGION, "rules", "first", rule);
        assertEquals(createdAt, service.getArchiveRule(REGION, "rules", "first").path("createdAt").asText());
        assertEquals("222222222222", service.getArchiveRule(REGION, "rules", "first")
                .path("filter").path("principal.AWS").path("eq").get(0).asText());
        rule.put("ruleName", "second");
        service.createArchiveRule(REGION, "rules", rule);
        PaginatedResult<JsonNode> first = service.listArchiveRules(REGION, "rules", 1, null);
        assertEquals("first", first.items().getFirst().path("ruleName").asText());
        assertEquals("second", service.listArchiveRules(REGION, "rules", 1, first.nextToken())
                .items().getFirst().path("ruleName").asText());
        Analyzer stored = mapper.readValue(mapper.writeValueAsBytes(service.getAnalyzer(REGION, "rules")), Analyzer.class);
        assertEquals(2, stored.getArchiveRules().size());
        assertEquals("222222222222", stored.getArchiveRules().get("first")
                .path("filter").path("principal.AWS").path("eq").get(0).asText());
        service.deleteArchiveRule(REGION, "rules", "first");
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.deleteArchiveRule(REGION, "rules", "first")).getErrorCode());
        service.deleteAnalyzer(REGION, "rules");
        service.createAnalyzer(request("rules", "ACCOUNT"), REGION);
        assertTrue(service.listArchiveRules(REGION, "rules", null, null).items().isEmpty());
    }

    @Test
    void invalidInlineRulesDoNotCreateAnalyzerOrPartiallyUpdateRules() {
        ObjectNode request = request("rules", "ACCOUNT");
        request.putArray("archiveRules").addObject().put("ruleName", "invalid").putObject("filter");
        assertThrows(AwsException.class, () -> service.createAnalyzer(request, REGION));
        assertTrue(service.listAnalyzers(REGION, null, null, null).items().isEmpty());
        request.remove("archiveRules");
        ObjectNode inline = request.putArray("archiveRules").addObject().put("ruleName", "inline");
        inline.putObject("filter").putObject("isPublic").put("exists", true);
        service.createAnalyzer(request, REGION);
        ObjectNode invalid = mapper.createObjectNode();
        invalid.putObject("filter").putObject("isPublic").put("exists", "true");
        assertThrows(AwsException.class, () -> service.updateArchiveRule(REGION, "rules", "inline", invalid));
        assertTrue(service.getArchiveRule(REGION, "rules", "inline").path("filter").path("isPublic").path("exists").asBoolean());
    }

    private ObjectNode request(String name, String type) {
        ObjectNode request = mapper.createObjectNode();
        request.put("analyzerName", name);
        request.put("type", type);
        return request;
    }
}
