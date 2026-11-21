package io.floci.aws.services.bedrockagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsEventStreamWriter;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.bedrockagent.BedrockKnowledgeBaseService.Hit;
import io.floci.aws.services.bedrockruntime.BedrockRuntimeService;
import io.floci.aws.services.bedrockruntime.backend.StubModel;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bedrock Agent Runtime: InvokeAgent, agent memory, Rerank, Retrieve, and
 * RetrieveAndGenerate (plain and streamed).
 * <p>
 * Answers come from Bedrock Runtime's configured backend (the deterministic
 * stub, or a proxied model): InvokeAgent sends the agent's instruction as the
 * system prompt, with passages from its enabled knowledge bases when the
 * question matches them, and the session's earlier turns; RetrieveAndGenerate
 * sends the retrieved passages. Retrieval and reranking score passages with
 * {@link Relevance}. Ending a session ({@code endSession}) with a
 * {@code memoryId} on an agent that keeps {@code SESSION_SUMMARY} memory
 * records the session's summary, which GetAgentMemory then returns.
 */
@ApplicationScoped
public class BedrockAgentRuntimeService {
    private static final Pattern MODEL_ARN = Pattern.compile("^arn:[^:]+:bedrock:[^:]*:[^:]*:[^/]+/(.+)$");
    private static final Pattern S3_URI = Pattern.compile("^s3://([^/]+)/(.*)$");
    private static final int DEFAULT_RESULTS = 5;

    private final BedrockAgentService agents;
    private final BedrockKnowledgeBaseService knowledgeBases;
    private final BedrockRuntimeService runtime;
    private final S3Service s3Service;
    private final StorageBackend<String, ObjectNode> memories;
    private final ObjectMapper objectMapper;
    /** Session turns by {@code agentId/aliasId/sessionId}, until the session ends. */
    private final Map<String, ArrayNode> sessions = new ConcurrentHashMap<>();

    @Inject
    public BedrockAgentRuntimeService(BedrockAgentService agents, BedrockKnowledgeBaseService knowledgeBases,
                                      BedrockRuntimeService runtime, S3Service s3Service,
                                      StorageFactory storageFactory, ObjectMapper objectMapper) {
        this.agents = agents;
        this.knowledgeBases = knowledgeBases;
        this.runtime = runtime;
        this.s3Service = s3Service;
        this.memories = storageFactory.create("bedrock-agent", "bedrock-agent-memories.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.objectMapper = objectMapper;
    }

    // ── InvokeAgent ────────────────────────────────────────────────────────

    /** The completion as InvokeAgent streams it, and the ids its headers carry. */
    public record Invocation(Consumer<OutputStream> completion, String sessionId, String memoryId) {}

    public Invocation invokeAgent(String agentId, String aliasId, String sessionId, JsonNode request, String region) {
        BedrockAgentService.Resolved resolved = agents.resolve(agentId, aliasId, region);
        JsonNode version = resolved.version();
        if (!"PREPARED".equals(version.path("agentStatus").asText())) {
            throw BedrockAgentService.validation("Agent " + agentId + " is not prepared. Call PrepareAgent first.");
        }
        if (resolved.alias() != null
                && "REJECT_INVOCATIONS".equals(resolved.alias().path("aliasInvocationState").asText())) {
            throw new AwsException("ConflictException", "Alias " + aliasId + " is rejecting invocations.", 409);
        }
        String input = BedrockAgentService.text(request, "inputText");
        boolean endSession = request.path("endSession").asBoolean(false);
        if ((input == null || input.isBlank()) && !endSession) {
            throw BedrockAgentService.validation("inputText is a required field.");
        }
        String sessionKey = agentId + "/" + aliasId + "/" + sessionId;
        ArrayNode history = sessions.computeIfAbsent(sessionKey, k -> objectMapper.createArrayNode());

        String reply = "";
        if (input != null && !input.isBlank()) {
            ObjectNode converse = objectMapper.createObjectNode();
            StringBuilder system = new StringBuilder(version.path("instruction").asText(""));
            List<Hit> context = new ArrayList<>();
            resolved.components().path("knowledgeBases").forEach(kb -> {
                if ("ENABLED".equals(kb.path("knowledgeBaseState").asText())
                        && knowledgeBases.exists(kb.path("knowledgeBaseId").asText(), region)) {
                    context.addAll(knowledgeBases.search(kb.path("knowledgeBaseId").asText(), input, 3, region));
                }
            });
            if (!context.isEmpty()) {
                system.append("\n\nUse these passages when they answer the question:\n");
                context.forEach(hit -> system.append("- ").append(hit.text()).append('\n'));
            }
            if (!system.isEmpty()) {
                converse.putArray("system").addObject().put("text", system.toString());
            }
            ArrayNode messages = converse.putArray("messages");
            history.forEach(turn -> messages.add(turn.deepCopy()));
            ObjectNode user = messages.addObject().put("role", "user");
            user.putArray("content").addObject().put("text", input);
            reply = completionText(runtime.buildConverseResponse(version.path("foundationModel").asText(), converse));
            history.add(user.deepCopy());
            ObjectNode assistant = history.addObject().put("role", "assistant");
            assistant.putArray("content").addObject().put("text", reply);
        }

        String memoryId = BedrockAgentService.text(request, "memoryId");
        if (endSession) {
            sessions.remove(sessionKey);
            if (memoryId != null && keepsSessionSummaries(version)) {
                summarize(agentId, aliasId, memoryId, sessionId, history, version);
            }
        }
        List<String> parts = parts(reply);
        Consumer<OutputStream> completion = output -> {
            for (String part : parts) {
                ObjectNode chunk = objectMapper.createObjectNode();
                chunk.put("bytes", Base64.getEncoder().encodeToString(part.getBytes(StandardCharsets.UTF_8)));
                AwsEventStreamWriter.writeEvent(objectMapper, output, "chunk", chunk);
            }
        };
        return new Invocation(completion, sessionId, memoryId);
    }

    private static boolean keepsSessionSummaries(JsonNode version) {
        for (JsonNode type : version.path("memoryConfiguration").path("enabledMemoryTypes")) {
            if ("SESSION_SUMMARY".equals(type.asText())) {
                return true;
            }
        }
        return false;
    }

    private synchronized void summarize(String agentId, String aliasId, String memoryId, String sessionId,
                                        ArrayNode history, JsonNode version) {
        StringBuilder summary = new StringBuilder();
        history.forEach(turn -> summary.append("user".equals(turn.path("role").asText()) ? "The user said: "
                : "The agent replied: ").append(turn.path("content").path(0).path("text").asText()).append(' '));
        if (summary.isEmpty()) {
            return;
        }
        String key = agentId + "/" + aliasId + "/" + memoryId;
        ObjectNode memory = memories.get(key).orElse(objectMapper.createObjectNode());
        ArrayNode summaries = memory.withArray("/summaries");
        int days = version.path("memoryConfiguration").path("storageDays").asInt(30);
        Instant now = Instant.now();
        ObjectNode entry = summaries.addObject();
        entry.put("memoryId", memoryId);
        entry.put("sessionId", sessionId);
        entry.put("sessionStartTime", now.toString());
        entry.put("sessionExpiryTime", now.plusSeconds(days * 86_400L).toString());
        entry.put("summaryText", summary.toString().trim());
        memories.put(key, memory);
    }

    public ObjectNode getAgentMemory(String agentId, String aliasId, String memoryType, String memoryId,
                                     Integer maxItems, String nextToken, String region) {
        agents.resolve(agentId, aliasId, region);
        if (!"SESSION_SUMMARY".equals(memoryType)) {
            throw BedrockAgentService.validation("memoryType must be SESSION_SUMMARY.");
        }
        if (memoryId == null || memoryId.isBlank()) {
            throw BedrockAgentService.validation("memoryId is a required field.");
        }
        ArrayNode summaries = (ArrayNode) memories.get(agentId + "/" + aliasId + "/" + memoryId)
                .map(m -> m.path("summaries")).filter(JsonNode::isArray).orElse(objectMapper.createArrayNode());
        int start = nextToken == null ? 0 : Integer.parseInt(nextToken);
        int max = maxItems == null ? 1000 : maxItems;
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode contents = response.putArray("memoryContents");
        for (int i = start; i < summaries.size() && i < start + max; i++) {
            contents.addObject().set("sessionSummary", summaries.get(i).deepCopy());
        }
        if (start + max < summaries.size()) {
            response.put("nextToken", Integer.toString(start + max));
        }
        return response;
    }

    public synchronized void deleteAgentMemory(String agentId, String aliasId, String memoryId, String sessionId,
                                               String region) {
        agents.resolve(agentId, aliasId, region);
        String prefix = agentId + "/" + aliasId + "/";
        for (String key : memories.keys()) {
            if (!key.startsWith(prefix) || (memoryId != null && !key.equals(prefix + memoryId))) {
                continue;
            }
            if (sessionId == null) {
                memories.delete(key);
                continue;
            }
            ObjectNode memory = memories.get(key).orElseThrow();
            ArrayNode kept = objectMapper.createArrayNode();
            memory.path("summaries").forEach(s -> {
                if (!sessionId.equals(s.path("sessionId").asText())) {
                    kept.add(s);
                }
            });
            memory.set("summaries", kept);
            memories.put(key, memory);
        }
    }

    // ── Rerank ─────────────────────────────────────────────────────────────

    public ObjectNode rerank(JsonNode request) {
        JsonNode configuration = request.path("rerankingConfiguration");
        if (!"BEDROCK_RERANKING_MODEL".equals(configuration.path("type").asText())) {
            throw BedrockAgentService.validation("rerankingConfiguration.type must be BEDROCK_RERANKING_MODEL.");
        }
        JsonNode bedrock = configuration.path("bedrockRerankingConfiguration");
        String modelArn = bedrock.path("modelConfiguration").path("modelArn").asText("");
        if (!modelArn.contains("rerank")) {
            throw BedrockAgentService.validation("The model " + modelArn + " is not a reranking model.");
        }
        JsonNode queries = request.path("queries");
        if (!queries.isArray() || queries.size() != 1) {
            throw BedrockAgentService.validation("queries must contain exactly one query.");
        }
        String query = queries.get(0).path("textQuery").path("text").asText("");
        JsonNode sources = request.path("sources");
        if (!sources.isArray() || sources.isEmpty() || sources.size() > 1000) {
            throw BedrockAgentService.validation("sources must contain between 1 and 1000 documents.");
        }
        record Ranked(int index, double score, JsonNode document) {}
        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            JsonNode document = sources.get(i).path("inlineDocumentSource");
            String text = "JSON".equals(document.path("type").asText())
                    ? document.path("jsonDocument").toString()
                    : document.path("textDocument").path("text").asText("");
            ranked.add(new Ranked(i, Relevance.score(query, text), document));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparingInt(Ranked::index));
        int limit = bedrock.path("numberOfResults").asInt(ranked.size());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode results = response.putArray("results");
        for (Ranked entry : ranked.subList(0, Math.min(limit, ranked.size()))) {
            ObjectNode result = results.addObject();
            result.put("index", entry.index());
            // Positive and ordered: an unrelated document still has a small score, as reranker scores do.
            result.put("relevanceScore", (float) (0.001 + 0.998 * entry.score()));
            result.set("document", entry.document().deepCopy());
        }
        return response;
    }

    // ── Retrieve ───────────────────────────────────────────────────────────

    public ObjectNode retrieve(String kbId, JsonNode request, String region) {
        String query = request.path("retrievalQuery").path("text").asText(null);
        if (query == null || query.isBlank()) {
            throw BedrockAgentService.validation("retrievalQuery.text is a required field.");
        }
        int limit = request.path("retrievalConfiguration").path("vectorSearchConfiguration")
                .path("numberOfResults").asInt(DEFAULT_RESULTS);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode results = response.putArray("retrievalResults");
        for (Hit hit : knowledgeBases.search(kbId, query, limit, region)) {
            ObjectNode result = reference(hit);
            result.put("score", hit.score());
            results.add(result);
        }
        return response;
    }

    private ObjectNode reference(Hit hit) {
        ObjectNode result = objectMapper.createObjectNode();
        result.putObject("content").put("type", "TEXT").put("text", hit.text());
        ObjectNode location = result.putObject("location");
        ObjectNode metadata = result.putObject("metadata");
        if ("S3".equals(hit.type())) {
            location.put("type", "S3");
            location.putObject("s3Location").put("uri", hit.identifier());
            metadata.put("x-amz-bedrock-kb-source-uri", hit.identifier());
        } else {
            location.put("type", "CUSTOM");
            location.putObject("customDocumentLocation").put("id", hit.identifier());
        }
        metadata.put("x-amz-bedrock-kb-data-source-id", hit.dataSourceId());
        if (hit.metadata() != null && hit.metadata().path("inlineAttributes").isArray()) {
            hit.metadata().path("inlineAttributes").forEach(attribute -> metadata.set(
                    attribute.path("key").asText(), attribute.path("value").path("stringValue").deepCopy()));
        }
        return result;
    }

    // ── RetrieveAndGenerate ────────────────────────────────────────────────

    /** A generated answer, the passages it was built from, and the session it belongs to. */
    public record Generated(String sessionId, String text, List<Hit> references) {}

    public Generated retrieveAndGenerate(JsonNode request, String region) {
        String question = request.path("input").path("text").asText(null);
        if (question == null || question.isBlank()) {
            throw BedrockAgentService.validation("input.text is a required field.");
        }
        JsonNode configuration = request.path("retrieveAndGenerateConfiguration");
        String type = configuration.path("type").asText();
        List<Hit> references;
        String modelArn;
        if ("KNOWLEDGE_BASE".equals(type)) {
            JsonNode kb = configuration.path("knowledgeBaseConfiguration");
            int limit = kb.path("retrievalConfiguration").path("vectorSearchConfiguration")
                    .path("numberOfResults").asInt(DEFAULT_RESULTS);
            references = knowledgeBases.search(BedrockAgentService.requireText(kb, "knowledgeBaseId"), question,
                    limit, region);
            modelArn = BedrockAgentService.requireText(kb, "modelArn");
        } else if ("EXTERNAL_SOURCES".equals(type)) {
            JsonNode external = configuration.path("externalSourcesConfiguration");
            modelArn = BedrockAgentService.requireText(external, "modelArn");
            references = new ArrayList<>();
            for (JsonNode source : external.path("sources")) {
                String id;
                String text;
                if ("BYTE_CONTENT".equals(source.path("sourceType").asText())) {
                    JsonNode content = source.path("byteContent");
                    id = content.path("identifier").asText("document");
                    text = new String(Base64.getDecoder().decode(content.path("data").asText("")),
                            StandardCharsets.UTF_8);
                } else {
                    id = source.path("s3Location").path("uri").asText();
                    text = readS3(id);
                }
                references.add(new Hit(text, "S3".equals(source.path("sourceType").asText()) ? "S3" : "CUSTOM", id,
                        "", Relevance.score(question, text), null));
            }
        } else {
            throw BedrockAgentService.validation("retrieveAndGenerateConfiguration.type must be KNOWLEDGE_BASE or "
                    + "EXTERNAL_SOURCES.");
        }
        Matcher model = MODEL_ARN.matcher(modelArn);
        String modelId = model.matches() ? model.group(1) : modelArn;
        ObjectNode converse = objectMapper.createObjectNode();
        StringBuilder system = new StringBuilder("Answer the question using only these passages:\n");
        references.forEach(hit -> system.append("- ").append(hit.text()).append('\n'));
        converse.putArray("system").addObject().put("text", system.toString());
        converse.putArray("messages").addObject().put("role", "user").putArray("content")
                .addObject().put("text", question);
        String text = completionText(runtime.buildConverseResponse(modelId, converse));
        String sessionId = BedrockAgentService.text(request, "sessionId");
        return new Generated(sessionId == null ? UUID.randomUUID().toString() : sessionId, text, references);
    }

    public ObjectNode retrieveAndGenerateResponse(Generated generated) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("sessionId", generated.sessionId());
        response.putObject("output").put("text", generated.text());
        response.set("citations", objectMapper.createArrayNode().add(citation(generated)));
        return response;
    }

    public Consumer<OutputStream> retrieveAndGenerateStream(Generated generated) {
        return output -> {
            for (String delta : StubModel.deltas(generated.text())) {
                AwsEventStreamWriter.writeEvent(objectMapper, output, "output",
                        objectMapper.createObjectNode().put("text", delta));
            }
            ObjectNode event = objectMapper.createObjectNode();
            ObjectNode citation = citation(generated);
            event.set("citation", citation.deepCopy());
            event.set("generatedResponsePart", citation.get("generatedResponsePart").deepCopy());
            event.set("retrievedReferences", citation.get("retrievedReferences").deepCopy());
            AwsEventStreamWriter.writeEvent(objectMapper, output, "citation", event);
        };
    }

    /** One citation spanning the whole answer, referencing every passage it was built from. */
    private ObjectNode citation(Generated generated) {
        ObjectNode citation = objectMapper.createObjectNode();
        ObjectNode part = citation.putObject("generatedResponsePart").putObject("textResponsePart");
        part.put("text", generated.text());
        part.putObject("span").put("start", 0).put("end", Math.max(0, generated.text().length() - 1));
        ArrayNode references = citation.putArray("retrievedReferences");
        generated.references().forEach(hit -> references.add(reference(hit)));
        return citation;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String completionText(ObjectNode converse) {
        StringBuilder text = new StringBuilder();
        converse.path("output").path("message").path("content").forEach(block -> {
            if (block.has("text")) {
                text.append(block.path("text").asText());
            }
        });
        return text.toString();
    }

    /** The reply split into a few chunks, as InvokeAgent streams it. */
    private static List<String> parts(String text) {
        List<String> parts = new ArrayList<>();
        if (text.isEmpty()) {
            return parts;
        }
        int size = Math.max(1, (text.length() + 2) / 3);
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return parts;
    }

    private String readS3(String uri) {
        Matcher matcher = S3_URI.matcher(uri);
        if (!matcher.matches()) {
            throw BedrockAgentService.validation("Invalid S3 URI: " + uri);
        }
        try {
            return new String(s3Service.getObject(matcher.group(1), matcher.group(2)).getData(),
                    StandardCharsets.UTF_8);
        } catch (AwsException e) {
            throw BedrockAgentService.validation("Unable to read " + uri + ": " + e.getMessage());
        }
    }
}
