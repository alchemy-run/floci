package io.floci.aws.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsEventStreamWriter;
import io.floci.aws.services.bedrockruntime.backend.StubModel.Turn;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Bedrock Runtime without a model: {@link StubModel} decides each turn from the
 * request, and this class renders it as Bedrock does, for Converse and
 * ConverseStream, and for InvokeModel and InvokeModelWithResponseStream in the
 * native body of the model's family (Amazon Nova, Anthropic, Amazon Titan,
 * Meta, Mistral, Cohere).
 */
@ApplicationScoped
public class StubBackend implements BedrockBackend {

    private final ObjectMapper objectMapper;

    @Inject
    public StubBackend(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── Converse ───────────────────────────────────────────────────────────

    @Override
    public ObjectNode converse(String modelId, ObjectNode bedrockRequest) {
        Turn turn = StubModel.plan(objectMapper, modelId, bedrockRequest);
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode message = root.putObject("output").putObject("message");
        message.put("role", "assistant");
        ArrayNode content = message.putArray("content");
        if (turn.isToolCall()) {
            ObjectNode toolUse = content.addObject().putObject("toolUse");
            toolUse.put("toolUseId", turn.toolCall().toolUseId());
            toolUse.put("name", turn.toolCall().name());
            toolUse.set("input", turn.toolCall().input());
        } else {
            content.addObject().put("text", turn.text());
        }
        root.put("stopReason", turn.stopReason());
        root.set("usage", usage(turn));
        root.putObject("metrics").put("latencyMs", 1);
        return root;
    }

    @Override
    public Consumer<OutputStream> converseStream(String modelId, ObjectNode bedrockRequest) {
        Turn turn = StubModel.plan(objectMapper, modelId, bedrockRequest);
        return output -> {
            AwsEventStreamWriter.writeEvent(objectMapper, output, "messageStart",
                    objectMapper.createObjectNode().put("role", "assistant"));
            if (turn.isToolCall()) {
                ObjectNode start = objectMapper.createObjectNode().put("contentBlockIndex", 0);
                start.putObject("start").putObject("toolUse")
                        .put("toolUseId", turn.toolCall().toolUseId())
                        .put("name", turn.toolCall().name());
                AwsEventStreamWriter.writeEvent(objectMapper, output, "contentBlockStart", start);
                for (String fragment : StubModel.argumentFragments(turn.toolCall().input().toString())) {
                    ObjectNode delta = objectMapper.createObjectNode().put("contentBlockIndex", 0);
                    delta.putObject("delta").putObject("toolUse").put("input", fragment);
                    AwsEventStreamWriter.writeEvent(objectMapper, output, "contentBlockDelta", delta);
                }
            } else {
                for (String fragment : StubModel.deltas(turn.text())) {
                    ObjectNode delta = objectMapper.createObjectNode().put("contentBlockIndex", 0);
                    delta.putObject("delta").put("text", fragment);
                    AwsEventStreamWriter.writeEvent(objectMapper, output, "contentBlockDelta", delta);
                }
            }
            AwsEventStreamWriter.writeEvent(objectMapper, output, "contentBlockStop",
                    objectMapper.createObjectNode().put("contentBlockIndex", 0));
            AwsEventStreamWriter.writeEvent(objectMapper, output, "messageStop",
                    objectMapper.createObjectNode().put("stopReason", turn.stopReason()));
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.set("usage", usage(turn));
            metadata.putObject("metrics").put("latencyMs", 1);
            AwsEventStreamWriter.writeEvent(objectMapper, output, "metadata", metadata);
        };
    }

    // ── InvokeModel ────────────────────────────────────────────────────────

    @Override
    public byte[] invokeModel(String modelId, byte[] body) {
        Family family = Family.of(modelId);
        JsonNode request = parse(body);
        Turn turn = StubModel.plan(objectMapper, modelId, family.toConverse(objectMapper, request));
        try {
            return objectMapper.writeValueAsBytes(family.response(objectMapper, modelId, turn));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize InvokeModel response", e);
        }
    }

    @Override
    public Consumer<OutputStream> invokeModelWithResponseStream(String modelId, byte[] body) {
        Family family = Family.of(modelId);
        JsonNode request = parse(body);
        Turn turn = StubModel.plan(objectMapper, modelId, family.toConverse(objectMapper, request));
        List<ObjectNode> chunks = family.streamChunks(objectMapper, modelId, turn);
        return output -> {
            for (ObjectNode chunk : chunks) {
                ObjectNode event = objectMapper.createObjectNode();
                event.put("bytes", Base64.getEncoder().encodeToString(
                        chunk.toString().getBytes(StandardCharsets.UTF_8)));
                AwsEventStreamWriter.writeEvent(objectMapper, output, "chunk", event);
            }
        };
    }

    private JsonNode parse(byte[] body) {
        try {
            return body == null || body.length == 0 ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private ObjectNode usage(Turn turn) {
        return objectMapper.createObjectNode()
                .put("inputTokens", turn.inputTokens())
                .put("outputTokens", turn.outputTokens())
                .put("totalTokens", turn.inputTokens() + turn.outputTokens());
    }

    /** A model family's native InvokeModel body: how to read a request and write a response or stream. */
    enum Family {
        NOVA, ANTHROPIC, TITAN, META, MISTRAL, COHERE, GENERIC;

        static Family of(String modelId) {
            String id = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
            if (id.contains("anthropic.")) {
                return ANTHROPIC;
            }
            if (id.contains("amazon.nova")) {
                return NOVA;
            }
            if (id.contains("amazon.titan")) {
                return TITAN;
            }
            if (id.contains("meta.")) {
                return META;
            }
            if (id.contains("mistral.")) {
                return MISTRAL;
            }
            if (id.contains("cohere.")) {
                return COHERE;
            }
            return GENERIC;
        }

        /** The request as Converse would take it, so {@link StubModel} can plan it. */
        ObjectNode toConverse(ObjectMapper mapper, JsonNode body) {
            ObjectNode converse = mapper.createObjectNode();
            ArrayNode messages = converse.putArray("messages");
            switch (this) {
                case NOVA -> {
                    body.path("messages").forEach(m -> messages.add(m.deepCopy()));
                    if (body.has("system")) {
                        converse.set("system", body.get("system").deepCopy());
                    }
                    if (body.has("toolConfig")) {
                        converse.set("toolConfig", body.get("toolConfig").deepCopy());
                    }
                    copyMax(converse, body.path("inferenceConfig").path("maxTokens"));
                }
                case ANTHROPIC -> {
                    for (JsonNode message : body.path("messages")) {
                        ObjectNode converted = messages.addObject();
                        converted.put("role", message.path("role").asText("user"));
                        ArrayNode content = converted.putArray("content");
                        JsonNode original = message.path("content");
                        if (original.isTextual()) {
                            content.addObject().put("text", original.asText());
                        }
                        for (JsonNode block : original) {
                            switch (block.path("type").asText()) {
                                case "text" -> content.addObject().put("text", block.path("text").asText());
                                case "tool_result" -> {
                                    ObjectNode result = content.addObject().putObject("toolResult");
                                    result.put("toolUseId", block.path("tool_use_id").asText());
                                    ArrayNode resultContent = result.putArray("content");
                                    JsonNode inner = block.path("content");
                                    if (inner.isTextual()) {
                                        resultContent.addObject().put("text", inner.asText());
                                    } else {
                                        inner.forEach(i -> resultContent.addObject().put("text", i.path("text").asText()));
                                    }
                                }
                                default -> { }
                            }
                        }
                    }
                    if (body.path("system").isTextual()) {
                        converse.putArray("system").addObject().put("text", body.path("system").asText());
                    }
                    if (body.path("tools").isArray() && !body.path("tools").isEmpty()) {
                        ArrayNode tools = converse.putObject("toolConfig").putArray("tools");
                        body.path("tools").forEach(tool -> tools.addObject().putObject("toolSpec")
                                .put("name", tool.path("name").asText())
                                .putObject("inputSchema").set("json", tool.path("input_schema").deepCopy()));
                    }
                    copyMax(converse, body.path("max_tokens"));
                }
                default -> {
                    String prompt = body.path("inputText").asText(body.path("prompt").asText(
                            body.path("message").asText("")));
                    messages.addObject().put("role", "user").putArray("content").addObject().put("text", prompt);
                    copyMax(converse, firstNumber(body.path("textGenerationConfig").path("maxTokenCount"),
                            body.path("max_gen_len"), body.path("max_tokens")));
                }
            }
            return converse;
        }

        ObjectNode response(ObjectMapper mapper, String modelId, Turn turn) {
            ObjectNode root = mapper.createObjectNode();
            boolean truncated = "max_tokens".equals(turn.stopReason());
            switch (this) {
                case NOVA -> {
                    ObjectNode message = root.putObject("output").putObject("message");
                    message.put("role", "assistant");
                    ArrayNode content = message.putArray("content");
                    if (turn.isToolCall()) {
                        content.addObject().putObject("toolUse")
                                .put("toolUseId", turn.toolCall().toolUseId())
                                .put("name", turn.toolCall().name())
                                .set("input", turn.toolCall().input());
                    } else {
                        content.addObject().put("text", turn.text());
                    }
                    root.put("stopReason", turn.stopReason());
                    root.putObject("usage").put("inputTokens", turn.inputTokens())
                            .put("outputTokens", turn.outputTokens())
                            .put("totalTokens", turn.inputTokens() + turn.outputTokens());
                }
                case ANTHROPIC -> {
                    root.put("id", "msg_stub");
                    root.put("type", "message");
                    root.put("role", "assistant");
                    root.put("model", modelId);
                    ArrayNode content = root.putArray("content");
                    if (turn.isToolCall()) {
                        content.addObject().put("type", "tool_use").put("id", turn.toolCall().toolUseId())
                                .put("name", turn.toolCall().name()).set("input", turn.toolCall().input());
                    } else {
                        content.addObject().put("type", "text").put("text", turn.text());
                    }
                    root.put("stop_reason", turn.stopReason());
                    root.putNull("stop_sequence");
                    root.putObject("usage").put("input_tokens", turn.inputTokens())
                            .put("output_tokens", turn.outputTokens());
                }
                case TITAN -> {
                    root.put("inputTextTokenCount", turn.inputTokens());
                    root.putArray("results").addObject().put("tokenCount", turn.outputTokens())
                            .put("outputText", turn.text()).put("completionReason", truncated ? "LENGTH" : "FINISH");
                }
                case META -> {
                    root.put("generation", turn.text());
                    root.put("prompt_token_count", turn.inputTokens());
                    root.put("generation_token_count", turn.outputTokens());
                    root.put("stop_reason", truncated ? "length" : "stop");
                }
                case COHERE -> {
                    root.put("text", turn.text());
                    root.put("finish_reason", truncated ? "MAX_TOKENS" : "COMPLETE");
                }
                default -> root.putArray("outputs").addObject().put("text", turn.text())
                        .put("stop_reason", truncated ? "length" : "stop");
            }
            return root;
        }

        /** The JSON events a streamed invocation sends, one per {@code chunk}. */
        List<ObjectNode> streamChunks(ObjectMapper mapper, String modelId, Turn turn) {
            List<ObjectNode> chunks = new ArrayList<>();
            List<String> deltas = turn.isToolCall() ? List.of() : StubModel.deltas(turn.text());
            switch (this) {
                case NOVA -> {
                    chunks.add(object(mapper, "messageStart", mapper.createObjectNode().put("role", "assistant")));
                    for (String delta : deltas) {
                        ObjectNode body = mapper.createObjectNode().put("contentBlockIndex", 0);
                        body.putObject("delta").put("text", delta);
                        chunks.add(object(mapper, "contentBlockDelta", body));
                    }
                    chunks.add(object(mapper, "contentBlockStop", mapper.createObjectNode().put("contentBlockIndex", 0)));
                    chunks.add(object(mapper, "messageStop", mapper.createObjectNode().put("stopReason", turn.stopReason())));
                    ObjectNode metadata = mapper.createObjectNode();
                    metadata.putObject("usage").put("inputTokens", turn.inputTokens())
                            .put("outputTokens", turn.outputTokens());
                    chunks.add(object(mapper, "metadata", metadata));
                }
                case ANTHROPIC -> {
                    ObjectNode start = mapper.createObjectNode().put("type", "message_start");
                    start.putObject("message").put("id", "msg_stub").put("type", "message")
                            .put("role", "assistant").put("model", modelId).putArray("content");
                    chunks.add(start);
                    ObjectNode blockStart = mapper.createObjectNode().put("type", "content_block_start").put("index", 0);
                    blockStart.putObject("content_block").put("type", "text").put("text", "");
                    chunks.add(blockStart);
                    for (String delta : deltas) {
                        ObjectNode event = mapper.createObjectNode().put("type", "content_block_delta").put("index", 0);
                        event.putObject("delta").put("type", "text_delta").put("text", delta);
                        chunks.add(event);
                    }
                    chunks.add(mapper.createObjectNode().put("type", "content_block_stop").put("index", 0));
                    ObjectNode messageDelta = mapper.createObjectNode().put("type", "message_delta");
                    messageDelta.putObject("delta").put("stop_reason", turn.stopReason());
                    messageDelta.putObject("usage").put("output_tokens", turn.outputTokens());
                    chunks.add(messageDelta);
                    chunks.add(mapper.createObjectNode().put("type", "message_stop"));
                }
                default -> {
                    ObjectNode full = response(mapper, modelId, turn);
                    chunks.add(full);
                }
            }
            return chunks;
        }

        private static ObjectNode object(ObjectMapper mapper, String key, ObjectNode value) {
            ObjectNode node = mapper.createObjectNode();
            node.set(key, value);
            return node;
        }

        private static void copyMax(ObjectNode converse, JsonNode max) {
            if (max != null && max.canConvertToInt() && max.asInt() > 0) {
                converse.putObject("inferenceConfig").put("maxTokens", max.asInt());
            }
        }

        private static JsonNode firstNumber(JsonNode... candidates) {
            for (JsonNode candidate : candidates) {
                if (candidate != null && candidate.canConvertToInt()) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
