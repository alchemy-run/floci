package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BedrockAgentCoreMemoryRecordServiceTest {

    private static final String REGION = "us-east-1";
    private static final String MEMORY = "TestMemory-abcdefghij";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BedrockAgentCoreMemoryRecordService service;
    private BedrockAgentCoreMemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryService = mock(BedrockAgentCoreMemoryService.class);
        BedrockAgentCoreEventService events = new BedrockAgentCoreEventService(new InMemoryStorage<>(), memoryService);
        service = new BedrockAgentCoreMemoryRecordService(new InMemoryStorage<>(), events, MAPPER);
    }

    private static JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }

    private static String record(String requestId, String namespace, String text, double timestamp) {
        return "{\"requestIdentifier\":\"" + requestId + "\",\"namespaces\":[\"" + namespace + "\"],"
                + "\"content\":{\"text\":\"" + text + "\"},\"timestamp\":" + timestamp + "}";
    }

    private ObjectNode create(String... records) throws Exception {
        return service.batchCreate(MEMORY, json("{\"records\":[" + String.join(",", records) + "]}"), REGION);
    }

    @Test
    void batchCreateGetUpdateAndDeleteRoundTrip() throws Exception {
        ObjectNode created = create(
                record("rec-1", "facts/batch-actor", "The user's favorite color is teal.", 1789300800d),
                record("rec-2", "facts/batch-actor", "The user prefers window seats.", 1789300801d));
        assertEquals(2, created.get("successfulRecords").size());
        assertEquals(0, created.get("failedRecords").size());
        String first = created.get("successfulRecords").get(0).get("memoryRecordId").asText();
        String second = created.get("successfulRecords").get(1).get("memoryRecordId").asText();
        assertTrue(first.matches("mem-[a-zA-Z0-9-_]{36,46}"), first);
        assertEquals("rec-1", created.get("successfulRecords").get(0).get("requestIdentifier").asText());
        assertEquals("SUCCEEDED", created.get("successfulRecords").get(0).get("status").asText());

        ObjectNode fetched = service.getRecord(MEMORY, first, REGION);
        assertEquals(first, fetched.get("memoryRecord").get("memoryRecordId").asText());
        assertEquals("The user's favorite color is teal.",
                fetched.get("memoryRecord").get("content").get("text").asText());
        assertEquals(1789300800d, fetched.get("memoryRecord").get("createdAt").asDouble());

        ObjectNode updated = service.batchUpdate(MEMORY, json("{\"records\":[{\"memoryRecordId\":\"" + first
                + "\",\"timestamp\":1789300900,\"content\":{\"text\":\"The user's favorite color is green.\"},"
                + "\"namespaces\":[\"facts/batch-actor\"]}]}"), REGION);
        assertEquals(1, updated.get("successfulRecords").size());
        assertEquals("The user's favorite color is green.", service.getRecord(MEMORY, first, REGION)
                .get("memoryRecord").get("content").get("text").asText());

        assertEquals(first, service.deleteRecord(MEMORY, first, REGION));
        AwsException gone = assertThrows(AwsException.class, () -> service.getRecord(MEMORY, first, REGION));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());

        ObjectNode deleted = service.batchDelete(MEMORY,
                json("{\"records\":[{\"memoryRecordId\":\"" + second + "\"}]}"), REGION);
        assertEquals(1, deleted.get("successfulRecords").size());
    }

    @Test
    void invalidRecordFailsWithoutFailingTheBatch() throws Exception {
        ObjectNode created = create(
                record("ok", "facts/a", "valid", 1d),
                record("bad", "facts/a", "", 1d));
        assertEquals(1, created.get("successfulRecords").size());
        assertEquals(1, created.get("failedRecords").size());
        JsonNode failed = created.get("failedRecords").get(0);
        assertEquals("bad", failed.get("requestIdentifier").asText());
        assertEquals("FAILED", failed.get("status").asText());
        assertEquals(400, failed.get("errorCode").asInt());
    }

    @Test
    void duplicateRequestIdentifierRejectsTheBatch() {
        AwsException e = assertThrows(AwsException.class, () -> create(
                record("same", "facts/a", "one", 1d),
                record("same", "facts/a", "two", 2d)));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void clientTokenReplayReturnsTheOriginalRecord() throws Exception {
        String body = "{\"clientToken\":\"token-1\",\"records\":[" + record("r", "facts/a", "hi", 1d) + "]}";
        String first = service.batchCreate(MEMORY, json(body), REGION)
                .get("successfulRecords").get(0).get("memoryRecordId").asText();
        String replay = service.batchCreate(MEMORY, json(body), REGION)
                .get("successfulRecords").get(0).get("memoryRecordId").asText();
        assertEquals(first, replay);
    }

    @Test
    void updatingAndDeletingMissingRecordsReportPerRecordFailures() throws Exception {
        String missing = "mem-00000000-0000-0000-0000-000000000000";
        ObjectNode updated = service.batchUpdate(MEMORY, json("{\"records\":[{\"memoryRecordId\":\"" + missing
                + "\",\"timestamp\":1}]}"), REGION);
        assertEquals(404, updated.get("failedRecords").get(0).get("errorCode").asInt());
        ObjectNode deleted = service.batchDelete(MEMORY,
                json("{\"records\":[{\"memoryRecordId\":\"" + missing + "\"}]}"), REGION);
        assertEquals(0, deleted.get("successfulRecords").size());
        assertEquals(missing, deleted.get("failedRecords").get(0).get("memoryRecordId").asText());
        assertThrows(AwsException.class, () -> service.deleteRecord(MEMORY, missing, REGION));
    }

    @Test
    void listMatchesNamespacePrefixNewestFirst() throws Exception {
        create(record("a", "facts/actor-1", "older", 100d),
                record("b", "facts/actor-1", "newer", 200d),
                record("c", "facts/actor-2", "other actor", 300d),
                record("d", "summaries/actor-1", "other namespace", 400d));

        ObjectNode listed = service.list(MEMORY, json("{\"namespace\":\"facts/actor-1\"}"), REGION);
        assertEquals(2, listed.get("memoryRecordSummaries").size());
        assertEquals("newer", listed.get("memoryRecordSummaries").get(0).get("content").get("text").asText());
        assertEquals("older", listed.get("memoryRecordSummaries").get(1).get("content").get("text").asText());

        assertEquals(3, service.list(MEMORY, json("{\"namespace\":\"facts/\"}"), REGION)
                .get("memoryRecordSummaries").size());
        assertEquals(2, service.list(MEMORY, json("{\"namespacePath\":\"facts/actor-1/\"}"), REGION)
                .get("memoryRecordSummaries").size());
        assertEquals(0, service.list(MEMORY, json("{\"namespace\":\"facts/nobody\"}"), REGION)
                .get("memoryRecordSummaries").size());
    }

    @Test
    void listPaginatesWithAnOpaqueToken() throws Exception {
        create(record("a", "facts/p", "one", 1d), record("b", "facts/p", "two", 2d), record("c", "facts/p", "three", 3d));
        ObjectNode first = service.list(MEMORY, json("{\"namespace\":\"facts/p\",\"maxResults\":2}"), REGION);
        assertEquals(2, first.get("memoryRecordSummaries").size());
        String token = first.get("nextToken").asText();
        ObjectNode second = service.list(MEMORY,
                json("{\"namespace\":\"facts/p\",\"maxResults\":2,\"nextToken\":\"" + token + "\"}"), REGION);
        assertEquals(1, second.get("memoryRecordSummaries").size());
        assertEquals("one", second.get("memoryRecordSummaries").get(0).get("content").get("text").asText());
        assertFalse(second.has("nextToken"));
    }

    @Test
    void listRequiresANamespace() {
        AwsException e = assertThrows(AwsException.class, () -> service.list(MEMORY, json("{}"), REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void retrieveRanksLexicallyAndHonorsTopK() throws Exception {
        create(record("a", "facts/actor-1", "The user's favorite color is teal.", 1d),
                record("b", "facts/actor-1", "The user prefers window seats.", 2d),
                record("c", "facts/actor-1", "Favorite food is pizza and favorite drink is tea.", 3d));

        ObjectNode result = service.retrieve(MEMORY, json("{\"namespace\":\"facts/actor-1\","
                + "\"searchCriteria\":{\"searchQuery\":\"favorite color\",\"topK\":3}}"), REGION);
        JsonNode summaries = result.get("memoryRecordSummaries");
        assertEquals(2, summaries.size(), "the record sharing no term with the query is not a match");
        assertEquals("The user's favorite color is teal.", summaries.get(0).get("content").get("text").asText());
        double best = summaries.get(0).get("score").asDouble();
        assertTrue(best > summaries.get(1).get("score").asDouble());
        assertTrue(best > 0d && best <= 1d);

        ObjectNode top1 = service.retrieve(MEMORY, json("{\"namespace\":\"facts/actor-1\","
                + "\"searchCriteria\":{\"searchQuery\":\"favorite color\",\"topK\":1}}"), REGION);
        assertEquals(1, top1.get("memoryRecordSummaries").size());
    }

    @Test
    void retrieveValidatesItsCriteria() {
        assertThrows(AwsException.class, () -> service.retrieve(MEMORY,
                json("{\"namespace\":\"facts/a\"}"), REGION));
        assertThrows(AwsException.class, () -> service.retrieve(MEMORY,
                json("{\"namespace\":\"facts/a\",\"searchCriteria\":{\"searchQuery\":\"x\",\"topK\":0}}"), REGION));
    }

    @Test
    void metadataFiltersNarrowResults() throws Exception {
        create("{\"requestIdentifier\":\"a\",\"namespaces\":[\"facts/m\"],\"content\":{\"text\":\"alpha\"},"
                        + "\"timestamp\":1,\"metadata\":{\"topic\":{\"stringValue\":\"travel\"},"
                        + "\"rank\":{\"numberValue\":5}}}",
                "{\"requestIdentifier\":\"b\",\"namespaces\":[\"facts/m\"],\"content\":{\"text\":\"beta\"},"
                        + "\"timestamp\":2,\"metadata\":{\"rank\":{\"numberValue\":1}}}");

        ObjectNode equal = service.list(MEMORY, json("{\"namespace\":\"facts/m\",\"metadataFilters\":[{"
                + "\"left\":{\"metadataKey\":\"topic\"},\"operator\":\"EQUALS_TO\","
                + "\"right\":{\"metadataValue\":{\"stringValue\":\"travel\"}}}]}"), REGION);
        assertEquals(1, equal.get("memoryRecordSummaries").size());
        assertEquals("travel", equal.get("memoryRecordSummaries").get(0)
                .get("metadata").get("topic").get("stringValue").asText());

        ObjectNode greater = service.list(MEMORY, json("{\"namespace\":\"facts/m\",\"metadataFilters\":[{"
                + "\"left\":{\"metadataKey\":\"rank\"},\"operator\":\"GREATER_THAN\","
                + "\"right\":{\"metadataValue\":{\"numberValue\":2}}}]}"), REGION);
        assertEquals(1, greater.get("memoryRecordSummaries").size());

        ObjectNode missing = service.list(MEMORY, json("{\"namespace\":\"facts/m\",\"metadataFilters\":[{"
                + "\"left\":{\"metadataKey\":\"topic\"},\"operator\":\"NOT_EXISTS\"}]}"), REGION);
        assertEquals("beta", missing.get("memoryRecordSummaries").get(0).get("content").get("text").asText());
    }

    @Test
    void extractionJobsAreEmptyAndCannotBeStarted() throws Exception {
        assertEquals(0, service.listExtractionJobs(MEMORY, json("{}"), REGION).get("jobs").size());
        AwsException e = assertThrows(AwsException.class, () -> service.startExtractionJob(MEMORY,
                json("{\"extractionJob\":{\"jobId\":\"job-1\"}}"), REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void unknownMemoryIsNotFound() {
        when(memoryService.get("Missing-abcdefghij", REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "Memory not found", 404));
        AwsException e = assertThrows(AwsException.class, () -> service.list("Missing-abcdefghij",
                json("{\"namespace\":\"facts/a\"}"), REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }
}
