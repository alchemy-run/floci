package io.floci.aws.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stub backend's deterministic "model": it reads a Converse-shaped request
 * and decides the assistant's turn without running inference.
 * <ul>
 *   <li>Offered tools and a user turn that isn't a tool result: it calls a tool
 *       (the one {@code toolChoice} names, else the first), filling string
 *       arguments with the proper-noun phrase of the user's text ("What's the
 *       weather in San Francisco?" gives {@code {"city": "San Francisco"}}),
 *       numbers with the text's first number, booleans with {@code true}, and
 *       enums with their first value.</li>
 *   <li>A tool result: it answers with that result.</li>
 *   <li>Otherwise: a reply that names the user's request, then explains it is a
 *       local stand-in, long enough to stream as many deltas.</li>
 * </ul>
 * The reply is cut at {@code maxTokens} (stop reason {@code max_tokens});
 * a token is a word or punctuation mark, the same unit {@link #countTokens} counts.
 */
public final class StubModel {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}']+|[^\\s\\p{L}\\p{N}]");
    private static final Pattern PROPER_PHRASE = Pattern.compile(
            "\\b(\\p{Lu}[\\p{L}'-]*(?:\\s+\\p{Lu}[\\p{L}'-]*)*)");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final List<String> NOT_PROPER = List.of("What", "What's", "Whats", "How", "Who", "Why", "When",
            "Where", "Which", "Is", "Are", "Can", "Could", "Would", "Should", "Please", "Tell", "Give", "Show",
            "Say", "Write", "I", "The", "A", "An");

    private StubModel() {}

    /** The assistant's turn: text with a stop reason, or a tool call. */
    public record Turn(String text, String stopReason, ToolCall toolCall, int inputTokens, int outputTokens) {
        public boolean isToolCall() {
            return toolCall != null;
        }
    }

    public record ToolCall(String toolUseId, String name, ObjectNode input) {}

    /** Decides the turn for a Converse request ({@code messages}, {@code system}, {@code toolConfig}, ...). */
    public static Turn plan(ObjectMapper mapper, String modelId, ObjectNode request) {
        int inputTokens = Math.max(1, countConverseTokens(request));
        JsonNode messages = request.path("messages");
        JsonNode last = messages.isArray() && !messages.isEmpty() ? messages.get(messages.size() - 1) : null;
        String userText = last == null ? "" : text(last.path("content"));
        JsonNode toolResult = last == null ? null : firstWith(last.path("content"), "toolResult");

        JsonNode tools = request.path("toolConfig").path("tools");
        boolean toolChoiceNone = request.path("toolConfig").path("toolChoice").has("none");
        if (toolResult == null && tools.isArray() && !tools.isEmpty() && !toolChoiceNone) {
            JsonNode tool = chooseTool(tools, request.path("toolConfig").path("toolChoice"));
            if (tool != null) {
                ObjectNode input = arguments(mapper, tool.path("inputSchema").path("json"), userText);
                ToolCall call = new ToolCall("tooluse_" + UUID.randomUUID().toString().replace("-", "").substring(0, 22),
                        tool.path("name").asText(), input);
                return new Turn("", "tool_use", call, inputTokens, Math.max(1, countTokens(input.toString())));
            }
        }

        String reply;
        if (toolResult != null) {
            reply = "Here is the result: " + text(toolResult.path("content")).trim();
        } else {
            String request1 = userText.isBlank() ? "your request" : "\"" + userText.trim() + "\"";
            reply = "Floci stub response for model=" + modelId + " answering " + request1 + ". Floci's Bedrock "
                    + "stub runs no model: it stands in for a real completion so your code can exercise "
                    + "requests, streaming, token limits, and tool use without calling AWS.";
        }
        int maxTokens = maxTokens(request);
        List<String> tokens = tokens(reply);
        if (maxTokens > 0 && tokens.size() > maxTokens) {
            return new Turn(join(reply, maxTokens), "max_tokens", null, inputTokens, maxTokens);
        }
        return new Turn(reply, "end_turn", null, inputTokens, tokens.size());
    }

    /** The reply split into stream deltas: one per word, each with its leading space. */
    public static List<String> deltas(String text) {
        List<String> deltas = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\s*\\S+").matcher(text);
        while (matcher.find()) {
            deltas.add(matcher.group());
        }
        return deltas;
    }

    /** Tool-call arguments as JSON split into a few fragments, as a stream sends them. */
    public static List<String> argumentFragments(String json) {
        List<String> fragments = new ArrayList<>();
        int step = Math.max(1, json.length() / 3);
        for (int i = 0; i < json.length(); i += step) {
            fragments.add(json.substring(i, Math.min(json.length(), i + step)));
        }
        return fragments;
    }

    /** Words and punctuation marks in a string. */
    public static int countTokens(String text) {
        return tokens(text).size();
    }

    /** Tokens in a Converse request's system prompts, messages, and tool definitions. */
    public static int countConverseTokens(JsonNode request) {
        int count = 0;
        for (JsonNode system : request.path("system")) {
            count += countTokens(system.path("text").asText(""));
        }
        for (JsonNode message : request.path("messages")) {
            count += countTokens(text(message.path("content")));
            for (JsonNode block : message.path("content")) {
                if (block.has("toolResult")) {
                    count += countTokens(text(block.path("toolResult").path("content")));
                }
                if (block.has("toolUse")) {
                    count += countTokens(block.path("toolUse").path("input").toString());
                }
            }
        }
        for (JsonNode tool : request.path("toolConfig").path("tools")) {
            count += countTokens(tool.toString());
        }
        return count;
    }

    private static JsonNode chooseTool(JsonNode tools, JsonNode toolChoice) {
        String named = toolChoice.path("tool").path("name").asText(null);
        JsonNode first = null;
        for (JsonNode entry : tools) {
            JsonNode spec = entry.path("toolSpec");
            if (spec.isMissingNode()) {
                continue;
            }
            if (first == null) {
                first = spec;
            }
            if (named != null && named.equals(spec.path("name").asText())) {
                return spec;
            }
        }
        return first;
    }

    private static ObjectNode arguments(ObjectMapper mapper, JsonNode schema, String userText) {
        ObjectNode input = mapper.createObjectNode();
        JsonNode properties = schema.path("properties");
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(name -> required.add(name.asText()));
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        String phrase = properPhrase(userText);
        Matcher number = NUMBER.matcher(userText);
        String firstNumber = number.find() ? number.group() : "0";
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!required.isEmpty() && !required.contains(field.getKey())) {
                continue;
            }
            JsonNode property = field.getValue();
            if (property.path("enum").isArray() && !property.path("enum").isEmpty()) {
                input.set(field.getKey(), property.path("enum").get(0).deepCopy());
                continue;
            }
            switch (property.path("type").asText("string")) {
                case "integer" -> input.put(field.getKey(), (long) Double.parseDouble(firstNumber));
                case "number" -> input.put(field.getKey(), Double.parseDouble(firstNumber));
                case "boolean" -> input.put(field.getKey(), true);
                case "array" -> input.putArray(field.getKey());
                case "object" -> input.putObject(field.getKey());
                default -> input.put(field.getKey(), phrase != null ? phrase : userText.trim());
            }
        }
        return input;
    }

    /** The last run of capitalized words in the text that isn't a question or command word. */
    static String properPhrase(String text) {
        Matcher matcher = PROPER_PHRASE.matcher(text);
        String found = null;
        while (matcher.find()) {
            List<String> words = new ArrayList<>(List.of(matcher.group(1).split("\\s+")));
            while (!words.isEmpty() && NOT_PROPER.contains(words.getFirst())) {
                words.removeFirst();
            }
            if (!words.isEmpty()) {
                found = String.join(" ", words);
            }
        }
        return found;
    }

    private static int maxTokens(JsonNode request) {
        return request.path("inferenceConfig").path("maxTokens").asInt(0);
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /** The text up to and including its {@code count}th token. */
    private static String join(String text, int count) {
        Matcher matcher = TOKEN.matcher(text);
        int end = 0;
        for (int i = 0; i < count && matcher.find(); i++) {
            end = matcher.end();
        }
        return text.substring(0, end);
    }

    /** The concatenated text blocks of a content list (or a plain string). */
    static String text(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if (block.has("text")) {
                text.append(block.path("text").asText()).append(' ');
            } else if (block.has("json")) {
                text.append(block.path("json").toString()).append(' ');
            }
        }
        return text.toString().trim();
    }

    private static JsonNode firstWith(JsonNode content, String field) {
        for (JsonNode block : content) {
            if (block.has(field)) {
                return block.get(field);
            }
        }
        return null;
    }
}
