package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsEventStreamWriter;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.bedrockruntime.BedrockRuntimeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static io.floci.aws.services.iotsitewise.SiteWiseStore.meta;

/**
 * The SiteWise Assistant: a conversation with a Bedrock model (through Floci's Bedrock Runtime, its stub or a
 * proxied model) that knows the Region's assets and properties and can run SiteWise SQL to answer from their data.
 * The model calls the {@code execute_query} tool as often as it needs, up to five turns; the answer comes back as an
 * {@code output} event, after a {@code trace} event per step when tracing is on. Conversations are kept by ID.
 */
@ApplicationScoped
public class SiteWiseAssistant {

    static final String CONVERSATION = "assistant-conversation";
    private static final int MAX_TURNS = 5;

    private final SiteWiseStore store;
    private final ObjectMapper mapper;
    private final Instance<BedrockRuntimeService> bedrock;
    private final Instance<SiteWiseQuery> query;
    private final Instance<SiteWiseAssets> assets;

    @Inject
    public SiteWiseAssistant(SiteWiseStore store, Instance<BedrockRuntimeService> bedrock,
                             Instance<SiteWiseQuery> query, Instance<SiteWiseAssets> assets) {
        this.store = store;
        this.mapper = store.mapper();
        this.bedrock = bedrock;
        this.query = query;
        this.assets = assets;
    }

    /** The response: the conversation ID and the event stream body. */
    record Reply(String conversationId, byte[] body) {
    }

    Reply invoke(ObjectNode in, String region) {
        String account = store.account();
        String conversationId = in.hasNonNull("conversationId") ? in.get("conversationId").asText()
                : SiteWiseStore.uuid();
        ObjectNode conversation = store.get(account, region, CONVERSATION, conversationId).orElse(null);
        if (conversation == null) {
            if (in.hasNonNull("conversationId")) {
                throw SiteWiseStore.notFound("Conversation " + conversationId + " not found.");
            }
            conversation = store.record();
            conversation.putArray("messages");
        }
        boolean trace = in.path("enableTrace").asBoolean(false);
        ArrayNode messages = (ArrayNode) conversation.get("messages");
        messages.addObject().put("role", "user").putArray("content").addObject().put("text",
                in.get("message").asText());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String answer = null;
        for (int turn = 0; turn < MAX_TURNS && answer == null; turn++) {
            ObjectNode request = mapper.createObjectNode();
            request.putArray("system").addObject().put("text", context(account, region));
            request.set("messages", messages.deepCopy());
            ObjectNode tool = request.putObject("toolConfig").putArray("tools").addObject().putObject("toolSpec");
            tool.put("name", "execute_query");
            tool.put("description", "Runs an IoT SiteWise SQL query over the views asset, asset_property, "
                    + "raw_time_series, latest_value_time_series and precomputed_aggregates.");
            ObjectNode schema = tool.putObject("inputSchema").putObject("json");
            schema.put("type", "object");
            schema.putObject("properties").putObject("statement").put("type", "string")
                    .put("description", "The SQL statement.");
            schema.putArray("required").add("statement");
            ObjectNode response;
            try {
                response = bedrock.get().buildConverseResponse(store.config().assistantModelId(), request);
            } catch (RuntimeException e) {
                throw new AwsException("InternalFailureException", "The assistant's model failed: " + e.getMessage(),
                        500);
            }
            JsonNode message = response.path("output").path("message");
            messages.add(message.deepCopy());
            List<JsonNode> uses = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (JsonNode block : message.path("content")) {
                if (block.has("toolUse")) {
                    uses.add(block.get("toolUse"));
                } else if (block.has("text")) {
                    text.append(block.get("text").asText());
                }
            }
            if (uses.isEmpty()) {
                answer = text.toString();
                break;
            }
            ArrayNode results = mapper.createArrayNode();
            for (JsonNode use : uses) {
                String statement = use.path("input").path("statement").asText("");
                String result;
                try {
                    result = query.get().executeQuery(mapper.createObjectNode().put("queryStatement", statement),
                            region).toString();
                } catch (AwsException e) {
                    result = "The query failed: " + e.getMessage();
                }
                if (trace) {
                    AwsEventStreamWriter.writeEvent(mapper, body, "trace", mapper.createObjectNode().put("text",
                            "Ran " + statement));
                }
                ObjectNode toolResult = results.addObject().putObject("toolResult");
                toolResult.put("toolUseId", use.path("toolUseId").asText());
                toolResult.putArray("content").addObject().put("text", result);
            }
            ObjectNode turnMessage = messages.addObject().put("role", "user");
            turnMessage.set("content", results);
        }
        if (answer == null) {
            answer = "I couldn't finish answering in " + MAX_TURNS + " steps.";
        }
        ObjectNode output = mapper.createObjectNode().put("message", answer);
        output.putArray("citations");
        AwsEventStreamWriter.writeEvent(mapper, body, "output", output);
        meta(conversation).put("updatedAt", System.currentTimeMillis());
        store.put(account, region, CONVERSATION, conversationId, conversation);
        return new Reply(conversationId, body.toByteArray());
    }

    /** What the model knows about the Region: each asset's properties, with aliases and latest values. */
    private String context(String account, String region) {
        StringBuilder context = new StringBuilder("You are the AWS IoT SiteWise Assistant. Answer questions about"
                + " industrial assets from their data. The assets and their properties:\n");
        int count = 0;
        for (ObjectNode asset : assets.get().all(account, region)) {
            if (count++ >= 100) {
                context.append("(and more; query the asset view)\n");
                break;
            }
            context.append("- ").append(asset.get("assetName").asText()).append(" (").append(asset.get("assetId")
                    .asText()).append("):");
            for (ObjectNode property : SiteWiseAssets.properties(asset)) {
                context.append(' ').append(property.get("name").asText()).append(" [").append(property.get("id")
                        .asText()).append(", ").append(property.get("dataType").asText()).append(']');
            }
            context.append('\n');
        }
        return context.toString();
    }
}
