package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcore.model.MemoryRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AgentCore Memory long-term records and extraction jobs: {@code BatchCreateMemoryRecords},
 * {@code BatchUpdateMemoryRecords}, {@code BatchDeleteMemoryRecords}, {@code GetMemoryRecord},
 * {@code DeleteMemoryRecord}, {@code ListMemoryRecords}, {@code RetrieveMemoryRecords},
 * {@code ListMemoryExtractionJobs} and {@code StartMemoryExtractionJob}.
 *
 * <p>Batch operations report per-record outcomes: a record that cannot be written lands in
 * {@code failedRecords} with an HTTP-style {@code errorCode}, and the call itself still succeeds.
 * Only request-level problems (unknown memory, an oversized batch) fail the whole call.
 *
 * <p>Retrieval is a local stand-in for AgentCore's semantic search. Floci runs no embedding
 * model, so {@code RetrieveMemoryRecords} ranks by lexical similarity: the cosine of the query's
 * and each record's term-frequency vectors over lower-cased alphanumeric tokens, with a short
 * stop-word list removed. Records sharing no term with the query are not returned, and the score
 * is that cosine, in [0, 1].
 *
 * <p>Floci does not run long-term extraction either, so events never become records on their
 * own and no extraction job ever fails. {@code ListMemoryExtractionJobs}, which lists the jobs
 * eligible to be restarted (the failed ones), is therefore always empty, and
 * {@code StartMemoryExtractionJob} cannot name a job that exists.
 */
@ApplicationScoped
public class BedrockAgentCoreMemoryRecordService {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreMemoryRecordService.class);

    private static final int MAX_BATCH = 100;
    private static final int DEFAULT_PAGE = 20;
    private static final int MAX_PAGE = 100;
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 100;
    private static final int MAX_QUERY_LENGTH = 10000;
    private static final int DEFAULT_JOB_PAGE = 20;
    private static final int MAX_JOB_PAGE = 50;
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";

    private static final Pattern REQUEST_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_-]{1,80}$");
    private static final Pattern NAMESPACE =
            Pattern.compile("^[a-zA-Z0-9/*][a-zA-Z0-9-_/*]*(?::[a-zA-Z0-9-_/*]+)*[a-zA-Z0-9-_/*]*$");
    private static final Pattern STRATEGY_ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9-_]{0,99}$");
    private static final Pattern RECORD_ID = Pattern.compile("^mem-[a-zA-Z0-9-_]{36,46}$");
    private static final Set<String> METADATA_VALUE_KINDS =
            Set.of("stringValue", "stringListValue", "numberValue", "dateTimeValue");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "has", "have", "in", "is", "it",
            "its", "of", "on", "or", "s", "that", "the", "this", "to", "was", "were", "with");

    private final StorageBackend<String, MemoryRecord> recordStore;
    private final BedrockAgentCoreEventService eventService;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreMemoryRecordService(StorageFactory storageFactory,
                                               BedrockAgentCoreEventService eventService,
                                               ObjectMapper objectMapper) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-memory-records.json",
                new TypeReference<Map<String, MemoryRecord>>() {}), eventService, objectMapper);
    }

    BedrockAgentCoreMemoryRecordService(StorageBackend<String, MemoryRecord> recordStore,
                                        BedrockAgentCoreEventService eventService,
                                        ObjectMapper objectMapper) {
        this.recordStore = recordStore;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    // ── batch writes ─────────────────────────────────────────────

    public ObjectNode batchCreate(String memoryId, JsonNode request, String region) {
        eventService.requireMemory(memoryId, region);
        List<JsonNode> records = batch(request, "records");
        String clientToken = optionalText(request, "clientToken");
        Set<String> seen = new HashSet<>();
        for (JsonNode record : records) {
            String requestIdentifier = optionalText(record, "requestIdentifier");
            if (requestIdentifier != null && !seen.add(requestIdentifier)) {
                throw new AwsException("ValidationException",
                        "Duplicate requestIdentifier in records: " + requestIdentifier, 400);
            }
        }

        ObjectNode response = batchResponse();
        for (JsonNode input : records) {
            String requestIdentifier = optionalText(input, "requestIdentifier");
            try {
                MemoryRecord record = createOne(memoryId, input, clientToken, region);
                addOutcome(response, SUCCEEDED, record.getMemoryRecordId(), requestIdentifier, null);
            } catch (AwsException e) {
                addOutcome(response, FAILED, "", requestIdentifier, e);
            }
        }
        return response;
    }

    private MemoryRecord createOne(String memoryId, JsonNode input, String clientToken, String region) {
        String requestIdentifier = optionalText(input, "requestIdentifier");
        if (requestIdentifier == null || !REQUEST_IDENTIFIER.matcher(requestIdentifier).matches()) {
            throw invalid("requestIdentifier must match [a-zA-Z0-9_-]{1,80}");
        }
        if (clientToken != null) {
            Optional<MemoryRecord> replay = recordStore.scan(k -> k.startsWith(memoryPrefix(region, memoryId)))
                    .stream()
                    .filter(r -> clientToken.equals(r.getClientToken())
                            && requestIdentifier.equals(r.getRequestIdentifier()))
                    .findFirst();
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        List<String> namespaces = namespaces(input.get("namespaces"), true);
        String text = contentText(input.get("content"), true);
        Double timestamp = timestamp(input.get("timestamp"));
        String strategyId = strategyId(input);
        Map<String, JsonNode> metadata = metadata(input.get("metadata"));

        MemoryRecord record = new MemoryRecord();
        record.setMemoryId(memoryId);
        record.setMemoryRecordId("mem-" + UUID.randomUUID());
        record.setText(text);
        record.setNamespaces(namespaces);
        record.setCreatedAt(timestamp);
        record.setUpdatedAt(timestamp);
        record.setMemoryStrategyId(strategyId);
        record.setMetadata(metadata);
        record.setRequestIdentifier(requestIdentifier);
        record.setClientToken(clientToken);
        recordStore.put(recordKey(region, memoryId, record.getMemoryRecordId()), record);
        LOG.debugv("BatchCreateMemoryRecords: memory={0} record={1}", memoryId, record.getMemoryRecordId());
        return record;
    }

    public ObjectNode batchUpdate(String memoryId, JsonNode request, String region) {
        eventService.requireMemory(memoryId, region);
        ObjectNode response = batchResponse();
        for (JsonNode input : batch(request, "records")) {
            String recordId = optionalText(input, "memoryRecordId");
            try {
                MemoryRecord record = find(region, memoryId, recordId)
                        .orElseThrow(() -> recordNotFound(recordId));
                Double timestamp = timestamp(input.get("timestamp"));
                String text = contentText(input.get("content"), false);
                List<String> namespaces = namespaces(input.get("namespaces"), false);
                String strategyId = strategyId(input);
                Map<String, JsonNode> metadata = metadata(input.get("metadata"));
                if (text != null) {
                    record.setText(text);
                }
                if (namespaces != null) {
                    record.setNamespaces(namespaces);
                }
                if (strategyId != null) {
                    record.setMemoryStrategyId(strategyId);
                }
                if (metadata != null) {
                    record.setMetadata(metadata);
                }
                record.setUpdatedAt(timestamp);
                recordStore.put(recordKey(region, memoryId, recordId), record);
                addOutcome(response, SUCCEEDED, recordId, null, null);
            } catch (AwsException e) {
                addOutcome(response, FAILED, recordId == null ? "" : recordId, null, e);
            }
        }
        return response;
    }

    public ObjectNode batchDelete(String memoryId, JsonNode request, String region) {
        eventService.requireMemory(memoryId, region);
        ObjectNode response = batchResponse();
        for (JsonNode input : batch(request, "records")) {
            String recordId = optionalText(input, "memoryRecordId");
            if (recordId != null && find(region, memoryId, recordId).isPresent()) {
                recordStore.delete(recordKey(region, memoryId, recordId));
                addOutcome(response, SUCCEEDED, recordId, null, null);
            } else {
                addOutcome(response, FAILED, recordId == null ? "" : recordId, null, recordNotFound(recordId));
            }
        }
        return response;
    }

    // ── single-record operations ────────────────────────────────

    public ObjectNode getRecord(String memoryId, String memoryRecordId, String region) {
        eventService.requireMemory(memoryId, region);
        requireRecordId(memoryRecordId);
        MemoryRecord record = find(region, memoryId, memoryRecordId)
                .orElseThrow(() -> recordNotFound(memoryRecordId));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("memoryRecord", describe(record, null));
        return response;
    }

    public String deleteRecord(String memoryId, String memoryRecordId, String region) {
        eventService.requireMemory(memoryId, region);
        requireRecordId(memoryRecordId);
        if (find(region, memoryId, memoryRecordId).isEmpty()) {
            throw recordNotFound(memoryRecordId);
        }
        recordStore.delete(recordKey(region, memoryId, memoryRecordId));
        return memoryRecordId;
    }

    // ── listing and retrieval ────────────────────────────────────

    /** Records in scope, newest first. */
    public ObjectNode list(String memoryId, JsonNode request, String region) {
        eventService.requireMemory(memoryId, region);
        List<MemoryRecord> matches = inScope(memoryId, request, optionalText(request, "memoryStrategyId"),
                request.get("metadataFilters"), region);
        List<Ranked> ranked = new ArrayList<>();
        for (MemoryRecord record : matches) {
            ranked.add(new Ranked(record, null, newestFirstCursor(record)));
        }
        return page(ranked, optionalInt(request, "maxResults"), optionalText(request, "nextToken"));
    }

    /** Records in scope ranked by lexical similarity to the query, best first, capped at topK. */
    public ObjectNode retrieve(String memoryId, JsonNode request, String region) {
        eventService.requireMemory(memoryId, region);
        JsonNode criteria = request.get("searchCriteria");
        if (criteria == null || !criteria.isObject()) {
            throw invalid("searchCriteria is required");
        }
        String query = optionalText(criteria, "searchQuery");
        if (query == null || query.isEmpty() || query.length() > MAX_QUERY_LENGTH) {
            throw invalid("searchCriteria.searchQuery must be between 1 and " + MAX_QUERY_LENGTH + " characters");
        }
        Integer topKValue = optionalInt(criteria, "topK");
        int topK = topKValue == null ? DEFAULT_TOP_K : topKValue;
        if (topK < 1 || topK > MAX_TOP_K) {
            throw invalid("searchCriteria.topK must be between 1 and " + MAX_TOP_K);
        }
        List<MemoryRecord> matches = inScope(memoryId, request, optionalText(criteria, "memoryStrategyId"),
                criteria.get("metadataFilters"), region);

        Map<String, Integer> queryTerms = termFrequencies(query);
        List<Ranked> ranked = new ArrayList<>();
        for (MemoryRecord record : matches) {
            double score = cosine(queryTerms, termFrequencies(record.getText()));
            if (score > 0d) {
                ranked.add(new Ranked(record, score, bestFirstCursor(score, record)));
            }
        }
        ranked.sort((a, b) -> a.cursor().compareTo(b.cursor()));
        List<Ranked> top = ranked.size() > topK ? new ArrayList<>(ranked.subList(0, topK)) : ranked;
        return page(top, optionalInt(request, "maxResults"), optionalText(request, "nextToken"));
    }

    // ── extraction jobs ──────────────────────────────────────────

    public ObjectNode listExtractionJobs(String memoryId, JsonNode request, String region) {
        eventService.requireMemory(memoryId, region);
        Integer maxResults = optionalInt(request, "maxResults");
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_JOB_PAGE)) {
            throw invalid("maxResults must be between 1 and " + MAX_JOB_PAGE);
        }
        PaginatedResult<String> page = Pagination.paginate(new ArrayList<String>(), job -> job, maxResults,
                optionalText(request, "nextToken"), DEFAULT_JOB_PAGE, MAX_JOB_PAGE, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("jobs");
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    public String startExtractionJob(String memoryId, JsonNode request, String region) {
        eventService.requireMemory(memoryId, region);
        JsonNode job = request.get("extractionJob");
        String jobId = job == null ? null : optionalText(job, "jobId");
        if (jobId == null || jobId.isBlank()) {
            throw invalid("extractionJob.jobId is required");
        }
        throw new AwsException("ResourceNotFoundException", "Extraction job not found: " + jobId, 404);
    }

    // ── wire shapes ──────────────────────────────────────────────

    ObjectNode describe(MemoryRecord record, Double score) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("memoryRecordId", record.getMemoryRecordId());
        node.putObject("content").put("text", record.getText());
        if (record.getMemoryStrategyId() != null) {
            node.put("memoryStrategyId", record.getMemoryStrategyId());
        }
        ArrayNode namespaces = node.putArray("namespaces");
        List<String> recordNamespaces = record.getNamespaces();
        if (recordNamespaces != null) {
            recordNamespaces.forEach(namespaces::add);
        }
        node.put("createdAt", record.getCreatedAt() == null ? 0d : record.getCreatedAt());
        if (score != null) {
            node.put("score", score);
        }
        Map<String, JsonNode> metadata = record.getMetadata();
        if (metadata != null && !metadata.isEmpty()) {
            ObjectNode out = node.putObject("metadata");
            metadata.forEach((key, value) -> out.set(key, value.deepCopy()));
        }
        return node;
    }

    private ObjectNode page(List<Ranked> ranked, Integer maxResults, String nextToken) {
        PaginatedResult<Ranked> page = Pagination.paginate(ranked, Ranked::cursor, maxResults, nextToken,
                DEFAULT_PAGE, MAX_PAGE, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("memoryRecordSummaries");
        for (Ranked entry : page.items()) {
            summaries.add(describe(entry.record(), entry.score()));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    private record Ranked(MemoryRecord record, Double score, String cursor) {}

    private static String newestFirstCursor(MemoryRecord record) {
        long millis = record.getCreatedAt() == null ? 0L : Math.round(record.getCreatedAt() * 1000d);
        return String.format("%019d", Long.MAX_VALUE - Math.max(0L, millis)) + "#" + record.getMemoryRecordId();
    }

    private static String bestFirstCursor(double score, MemoryRecord record) {
        long inverted = Math.round((1d - Math.min(1d, score)) * 1_000_000_000_000L);
        return String.format("%013d", inverted) + "#" + newestFirstCursor(record);
    }

    // ── scope and filters ────────────────────────────────────────

    private List<MemoryRecord> inScope(String memoryId, JsonNode request, String strategyId,
                                       JsonNode metadataFilters, String region) {
        String namespace = optionalText(request, "namespace");
        String namespacePath = optionalText(request, "namespacePath");
        if ((namespace == null || namespace.isEmpty()) && (namespacePath == null || namespacePath.isEmpty())) {
            throw invalid("Either namespace or namespacePath is required");
        }
        List<MetadataFilter> filters = metadataFilters(metadataFilters);
        String hierarchy = namespacePath == null ? null : stripTrailingSlash(namespacePath);
        List<MemoryRecord> matches = new ArrayList<>();
        for (MemoryRecord record : recordStore.scan(k -> k.startsWith(memoryPrefix(region, memoryId)))) {
            List<String> recordNamespaces = record.getNamespaces() == null ? List.of() : record.getNamespaces();
            boolean inNamespace = namespace == null
                    || recordNamespaces.stream().anyMatch(ns -> ns.startsWith(namespace));
            boolean inHierarchy = hierarchy == null
                    || recordNamespaces.stream().anyMatch(ns -> underHierarchy(ns, hierarchy));
            boolean strategyMatches = strategyId == null || strategyId.equals(record.getMemoryStrategyId());
            if (inNamespace && inHierarchy && strategyMatches && passes(record, filters)) {
                matches.add(record);
            }
        }
        return matches;
    }

    private static boolean underHierarchy(String namespace, String hierarchy) {
        String candidate = stripTrailingSlash(namespace);
        return hierarchy.isEmpty() || candidate.equals(hierarchy) || candidate.startsWith(hierarchy + "/");
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private record MetadataFilter(String key, String operator, JsonNode value) {}

    private List<MetadataFilter> metadataFilters(JsonNode node) {
        List<MetadataFilter> filters = new ArrayList<>();
        if (node == null || node.isNull()) {
            return filters;
        }
        if (!node.isArray()) {
            throw invalid("metadataFilters must be a list");
        }
        for (JsonNode expression : node) {
            JsonNode left = expression.get("left");
            String key = left == null ? null : optionalText(left, "metadataKey");
            String operator = optionalText(expression, "operator");
            if (key == null || operator == null) {
                throw invalid("metadataFilters entries require left.metadataKey and operator");
            }
            JsonNode right = expression.get("right");
            JsonNode value = right == null ? null : right.get("metadataValue");
            boolean unary = "EXISTS".equals(operator) || "NOT_EXISTS".equals(operator);
            if (!unary && (value == null || !value.isObject())) {
                throw invalid("metadataFilters operator " + operator + " requires right.metadataValue");
            }
            filters.add(new MetadataFilter(key, operator, value));
        }
        return filters;
    }

    private static boolean passes(MemoryRecord record, List<MetadataFilter> filters) {
        Map<String, JsonNode> metadata = record.getMetadata() == null ? Map.of() : record.getMetadata();
        for (MetadataFilter filter : filters) {
            if (!matches(metadata.get(filter.key()), filter)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(JsonNode actual, MetadataFilter filter) {
        JsonNode expected = filter.value();
        return switch (filter.operator()) {
            case "EXISTS" -> actual != null;
            case "NOT_EXISTS" -> actual == null;
            case "EQUALS_TO" -> actual != null && actual.equals(expected);
            case "CONTAINS" -> actual != null && contains(actual, expected);
            case "BEFORE" -> ordered(actual, expected, "dateTimeValue", -1, false);
            case "AFTER" -> ordered(actual, expected, "dateTimeValue", 1, false);
            case "GREATER_THAN" -> ordered(actual, expected, "numberValue", 1, false);
            case "GREATER_THAN_OR_EQUALS" -> ordered(actual, expected, "numberValue", 1, true);
            case "LESS_THAN" -> ordered(actual, expected, "numberValue", -1, false);
            case "LESS_THAN_OR_EQUALS" -> ordered(actual, expected, "numberValue", -1, true);
            default -> throw new AwsException("ValidationException",
                    "Unsupported metadata filter operator: " + filter.operator(), 400);
        };
    }

    /** Whether {@code actual} sits on the {@code direction} side of {@code expected}; false when incomparable. */
    private static boolean ordered(JsonNode actual, JsonNode expected, String kind, int direction, boolean inclusive) {
        if (actual == null || expected == null || !actual.path(kind).isNumber() || !expected.path(kind).isNumber()) {
            return false;
        }
        int result = Double.compare(actual.get(kind).asDouble(), expected.get(kind).asDouble());
        return result == 0 ? inclusive : Integer.signum(result) == direction;
    }

    private static boolean contains(JsonNode actual, JsonNode expected) {
        String needle = expected.path("stringValue").asText(null);
        if (needle == null) {
            return false;
        }
        if (actual.has("stringValue")) {
            return actual.get("stringValue").asText().contains(needle);
        }
        if (actual.has("stringListValue") && actual.get("stringListValue").isArray()) {
            for (JsonNode item : actual.get("stringListValue")) {
                if (needle.equals(item.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── lexical similarity ───────────────────────────────────────

    static Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> terms = new HashMap<>();
        if (text == null) {
            return terms;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty() && !STOP_WORDS.contains(token)) {
                terms.merge(token, 1, Integer::sum);
            }
        }
        return terms;
    }

    static double cosine(Map<String, Integer> a, Map<String, Integer> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        double dot = 0d;
        for (Map.Entry<String, Integer> entry : a.entrySet()) {
            Integer other = b.get(entry.getKey());
            if (other != null) {
                dot += (double) entry.getValue() * other;
            }
        }
        if (dot == 0d) {
            return 0d;
        }
        return dot / (norm(a) * norm(b));
    }

    private static double norm(Map<String, Integer> vector) {
        double sum = 0d;
        for (int value : vector.values()) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }

    // ── parsing and validation ───────────────────────────────────

    private static List<JsonNode> batch(JsonNode request, String field) {
        JsonNode records = request.get(field);
        if (records == null || !records.isArray()) {
            throw invalid(field + " is required");
        }
        if (records.size() > MAX_BATCH) {
            throw invalid("1 validation error detected: Value at '" + field
                    + "' failed to satisfy constraint: Member must have length less than or equal to " + MAX_BATCH);
        }
        List<JsonNode> out = new ArrayList<>();
        records.forEach(out::add);
        return out;
    }

    private static List<String> namespaces(JsonNode node, boolean required) {
        if (node == null || node.isNull()) {
            if (required) {
                throw invalid("namespaces is required");
            }
            return null;
        }
        if (!node.isArray() || node.isEmpty()) {
            throw invalid("namespaces must be a non-empty list");
        }
        List<String> namespaces = new ArrayList<>();
        for (JsonNode item : node) {
            String namespace = item.asText();
            if (namespace.isEmpty() || namespace.length() > 1024 || !NAMESPACE.matcher(namespace).matches()) {
                throw invalid("Invalid namespace: " + namespace);
            }
            namespaces.add(namespace);
        }
        return namespaces;
    }

    private static String contentText(JsonNode content, boolean required) {
        if (content == null || content.isNull()) {
            if (required) {
                throw invalid("content is required");
            }
            return null;
        }
        String text = optionalText(content, "text");
        if (text == null || text.isEmpty()) {
            throw invalid("content.text is required");
        }
        return text;
    }

    private static Double timestamp(JsonNode node) {
        if (node == null || !node.isNumber()) {
            throw invalid("timestamp is required");
        }
        return node.asDouble();
    }

    private static String strategyId(JsonNode input) {
        String strategyId = optionalText(input, "memoryStrategyId");
        if (strategyId != null && !STRATEGY_ID.matcher(strategyId).matches()) {
            throw invalid("Invalid memoryStrategyId: " + strategyId);
        }
        return strategyId;
    }

    private static Map<String, JsonNode> metadata(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw invalid("metadata must be a map");
        }
        Map<String, JsonNode> metadata = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (!value.isObject() || value.size() != 1
                    || !METADATA_VALUE_KINDS.contains(value.fieldNames().next())) {
                throw invalid("metadata value for " + field.getKey()
                        + " must set exactly one of stringValue, stringListValue, numberValue, dateTimeValue");
            }
            metadata.put(field.getKey(), value.deepCopy());
        }
        return metadata;
    }

    private static void requireRecordId(String memoryRecordId) {
        if (memoryRecordId == null || !RECORD_ID.matcher(memoryRecordId).matches()) {
            throw invalid("Invalid memoryRecordId: " + memoryRecordId);
        }
    }

    private Optional<MemoryRecord> find(String region, String memoryId, String recordId) {
        if (recordId == null) {
            return Optional.empty();
        }
        return recordStore.get(recordKey(region, memoryId, recordId));
    }

    private ObjectNode batchResponse() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("successfulRecords");
        response.putArray("failedRecords");
        return response;
    }

    private static void addOutcome(ObjectNode response, String status, String recordId,
                                   String requestIdentifier, AwsException failure) {
        ArrayNode target = (ArrayNode) response.get(SUCCEEDED.equals(status) ? "successfulRecords" : "failedRecords");
        ObjectNode outcome = target.addObject();
        outcome.put("memoryRecordId", recordId);
        outcome.put("status", status);
        if (requestIdentifier != null) {
            outcome.put("requestIdentifier", requestIdentifier);
        }
        if (failure != null) {
            outcome.put("errorCode", failure.getHttpStatus());
            outcome.put("errorMessage", failure.getMessage());
        }
    }

    private static AwsException recordNotFound(String recordId) {
        return new AwsException("ResourceNotFoundException", "Memory record not found: " + recordId, 404);
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer optionalInt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt()) {
            throw invalid(field + " must be an integer");
        }
        return value.asInt();
    }

    private static String memoryPrefix(String region, String memoryId) {
        return region + "::" + memoryId + "::";
    }

    private static String recordKey(String region, String memoryId, String recordId) {
        return memoryPrefix(region, memoryId) + recordId;
    }
}
