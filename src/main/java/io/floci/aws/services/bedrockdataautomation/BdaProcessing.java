package io.floci.aws.services.bedrockdataautomation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.bedrockruntime.BedrockRuntimeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an asset into Bedrock Data Automation's output: the standard output (the document's text as plain text
 * and Markdown, per page and per line, with counts, as the project's standard output configuration asks), and the
 * custom output of the blueprint the asset matches (its fields' values, with the matched blueprint and document
 * class). Blueprint fields are filled by the Bedrock Runtime model when Floci's Bedrock Runtime proxies to one, and
 * otherwise read from the text by their labels: a field {@code invoice_number} is the value after
 * {@code Invoice Number:} (or {@code Invoice Number} at the start of a line), and its instruction's words are tried
 * as labels too.
 */
@ApplicationScoped
public class BdaProcessing {
    private static final Logger LOG = Logger.getLogger(BdaProcessing.class);
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'.-]*");
    private static final Set<String> STOP_WORDS = Set.of("the", "a", "an", "of", "on", "in", "for", "to", "and",
            "or", "is", "this", "that", "with", "from", "by", "as", "at", "its", "it", "be", "if", "any");

    private final Instance<BedrockRuntimeService> runtime;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;

    @Inject
    public BdaProcessing(Instance<BedrockRuntimeService> runtime, EmulatorConfig config, ObjectMapper mapper) {
        this.runtime = runtime;
        this.config = config;
        this.mapper = mapper;
    }

    /** The asset as BDA reads it. */
    record Asset(String modality, List<String> pages, String bucket, String key) {
        String text() {
            return String.join("\n\n", pages);
        }
    }

    // ── Standard output ────────────────────────────────────────────────────

    /** A document's standard output, shaped by the project's {@code standardOutputConfiguration.document}. */
    ObjectNode standardOutput(Asset asset, JsonNode configuration) {
        ObjectNode out = mapper.createObjectNode();
        ObjectNode metadata = out.putObject("metadata");
        metadata.put("asset_id", "0");
        metadata.put("semantic_modality", asset.modality());
        if (asset.bucket() != null) {
            metadata.put("s3_bucket", asset.bucket());
            metadata.put("s3_key", asset.key());
        }
        if (!"DOCUMENT".equals(asset.modality())) {
            ObjectNode summary = out.putObject(asset.modality().toLowerCase(Locale.ROOT));
            summary.put("summary", "");
            summary.putArray("transcript");
            return out;
        }
        metadata.put("number_of_pages", asset.pages().size());
        JsonNode document = configuration.path("document");
        List<String> granularity = new ArrayList<>();
        document.path("extraction").path("granularity").path("types").forEach(t -> granularity.add(t.asText()));
        if (granularity.isEmpty()) {
            granularity.addAll(List.of("DOCUMENT", "PAGE", "ELEMENT"));
        }
        Set<String> formats = new LinkedHashSet<>();
        document.path("outputFormat").path("textFormat").path("types").forEach(t -> formats.add(t.asText()));
        if (formats.isEmpty()) {
            formats.add("MARKDOWN");
        }
        String text = asset.text();
        if (granularity.contains("DOCUMENT")) {
            ObjectNode node = out.putObject("document");
            node.set("representation", representation(text, formats));
            node.set("statistics", statistics(text));
            if ("ENABLED".equals(document.path("generativeField").path("state").asText())) {
                node.put("summary", summary(text));
                node.put("description", "A " + asset.pages().size() + "-page document.");
            }
        }
        if (granularity.contains("PAGE")) {
            ArrayNode pages = out.putArray("pages");
            for (int p = 0; p < asset.pages().size(); p++) {
                ObjectNode page = pages.addObject();
                page.put("page_index", p);
                page.put("id", "page-" + p);
                page.put("detected_page_number", p + 1);
                page.set("representation", representation(asset.pages().get(p), formats));
                page.set("statistics", statistics(asset.pages().get(p)));
            }
        }
        if (granularity.contains("ELEMENT")) {
            ArrayNode elements = out.putArray("elements");
            int index = 0;
            for (int p = 0; p < asset.pages().size(); p++) {
                for (String paragraph : asset.pages().get(p).split("\\n\\s*\\n")) {
                    if (paragraph.isBlank()) {
                        continue;
                    }
                    ObjectNode element = elements.addObject();
                    element.put("id", "element-" + index++);
                    element.put("type", "TEXT");
                    element.put("sub_type", index == 1 ? "TITLE" : "PARAGRAPH");
                    element.put("reading_order", index - 1);
                    element.putArray("page_indices").add(p);
                    element.set("representation", representation(paragraph.strip(), formats));
                }
            }
        }
        if (granularity.contains("LINE") || granularity.contains("WORD")) {
            ArrayNode lines = out.putArray("text_lines");
            ArrayNode words = out.putArray("text_words");
            int lineIndex = 0;
            int wordIndex = 0;
            for (int p = 0; p < asset.pages().size(); p++) {
                for (String line : asset.pages().get(p).split("\n")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String lineId = "line-" + lineIndex++;
                    if (granularity.contains("LINE")) {
                        lines.addObject().put("id", lineId).put("text", line.strip()).put("page_index", p);
                    }
                    if (granularity.contains("WORD")) {
                        for (String word : line.strip().split("\\s+")) {
                            words.addObject().put("id", "word-" + wordIndex++).put("text", word)
                                    .put("line_id", lineId).put("page_index", p);
                        }
                    }
                }
            }
            if (!granularity.contains("LINE")) {
                out.remove("text_lines");
            }
            if (!granularity.contains("WORD")) {
                out.remove("text_words");
            }
        }
        return out;
    }

    private ObjectNode representation(String text, Set<String> formats) {
        ObjectNode out = mapper.createObjectNode();
        if (formats.contains("PLAIN_TEXT")) {
            out.put("text", text);
        }
        if (formats.contains("MARKDOWN")) {
            out.put("markdown", markdown(text));
        }
        if (formats.contains("HTML")) {
            StringBuilder html = new StringBuilder();
            for (String paragraph : text.split("\\n\\s*\\n")) {
                if (!paragraph.isBlank()) {
                    html.append("<p>").append(paragraph.strip().replace("&", "&amp;").replace("<", "&lt;")
                            .replace(">", "&gt;").replace("\n", "<br/>")).append("</p>");
                }
            }
            out.put("html", html.toString());
        }
        if (formats.contains("CSV")) {
            out.put("csv", "");
        }
        return out;
    }

    private static String markdown(String text) {
        String[] paragraphs = text.strip().split("\\n\\s*\\n");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < paragraphs.length; i++) {
            String paragraph = paragraphs[i].strip();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            boolean title = i == 0 && !paragraph.contains("\n") && paragraph.length() <= 80;
            out.append(title ? "# " + paragraph : paragraph);
        }
        return out.toString();
    }

    private ObjectNode statistics(String text) {
        ObjectNode out = mapper.createObjectNode();
        int words = 0;
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            words++;
        }
        int lines = 0;
        for (String line : text.split("\n")) {
            lines += line.isBlank() ? 0 : 1;
        }
        out.put("element_count", (int) Arrays.stream(text.split("\\n\\s*\\n"))
                .filter(p -> !p.isBlank()).count());
        out.put("table_count", 0);
        out.put("figure_count", 0);
        out.put("word_count", words);
        out.put("line_count", lines);
        return out;
    }

    private static String summary(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        int end = flat.indexOf(". ");
        String first = end > 0 && end < 300 ? flat.substring(0, end + 1) : flat.substring(0, Math.min(300, flat.length()));
        return first;
    }

    // ── Custom output ──────────────────────────────────────────────────────

    /** A blueprint to match, with its schema. */
    record Candidate(String arn, String name, String version, String stage, JsonNode schema) {
    }

    /** The custom output for an asset, or null when no blueprint matches. */
    ObjectNode customOutput(Asset asset, List<Candidate> blueprints) {
        if (blueprints.isEmpty() || !"DOCUMENT".equals(asset.modality())) {
            return null;
        }
        String text = asset.text();
        Candidate best = null;
        ObjectNode bestResult = null;
        double bestScore = -1;
        for (Candidate candidate : blueprints) {
            ObjectNode result = infer(candidate.schema(), text);
            double score = score(candidate.schema(), result, text);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                bestResult = result;
            }
        }
        if (best == null || blueprints.size() > 1 && bestScore <= 0) {
            return null;
        }
        ObjectNode out = mapper.createObjectNode();
        ObjectNode matched = out.putObject("matched_blueprint");
        matched.put("arn", best.arn());
        matched.put("name", best.name());
        matched.put("confidence", blueprints.size() == 1 ? 1.0 : Math.min(1.0, 0.5 + bestScore / 2));
        out.putObject("document_class").put("type", best.schema().path("class").asText(best.name()));
        out.putArray("split_document").addObject().putArray("page_indices").add(0);
        out.set("inference_result", bestResult);
        ArrayNode explainability = out.putArray("explainability_info");
        ObjectNode explained = explainability.addObject();
        bestResult.fields().forEachRemaining(field -> explained.putObject(field.getKey())
                .put("success", !field.getValue().isNull() && !field.getValue().asText().isEmpty())
                .put("confidence", field.getValue().isNull() ? 0.0 : 0.9).set("value", field.getValue()));
        return out;
    }

    /** The share of a blueprint's fields found in the text, plus whether its class names the text. */
    private double score(JsonNode schema, ObjectNode result, String text) {
        int found = 0;
        int total = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = result.fields(); it.hasNext(); ) {
            JsonNode value = it.next().getValue();
            total++;
            if (!value.isNull() && !value.asText().isEmpty()) {
                found++;
            }
        }
        double share = total == 0 ? 0 : (double) found / total;
        String documentClass = schema.path("class").asText("").toLowerCase(Locale.ROOT);
        boolean named = !documentClass.isEmpty() && text.toLowerCase(Locale.ROOT).contains(documentClass);
        return share + (named ? 0.5 : 0);
    }

    /** A blueprint's fields filled from the text. */
    ObjectNode infer(JsonNode schema, String text) {
        if (proxied()) {
            ObjectNode modelled = inferWithModel(schema, text);
            if (modelled != null) {
                return modelled;
            }
        }
        ObjectNode out = mapper.createObjectNode();
        JsonNode definitions = schema.path("definitions");
        fill(schema.path("properties"), definitions, text, out);
        return out;
    }

    private void fill(JsonNode properties, JsonNode definitions, String text, ObjectNode out) {
        properties.fields().forEachRemaining(field -> {
            JsonNode spec = field.getValue();
            if (spec.has("$ref")) {
                String ref = spec.path("$ref").asText();
                JsonNode target = definitions.path(ref.substring(ref.lastIndexOf('/') + 1));
                ObjectNode nested = out.putObject(field.getKey());
                fill(target.path("properties"), definitions, text, nested);
                return;
            }
            if ("object".equals(spec.path("type").asText()) && spec.has("properties")) {
                fill(spec.path("properties"), definitions, text, out.putObject(field.getKey()));
                return;
            }
            String value = lookup(field.getKey(), spec.path("instruction").asText(""), text);
            String type = spec.path("type").asText("string");
            if (value == null) {
                out.putNull(field.getKey());
            } else if ("array".equals(type)) {
                ArrayNode values = out.putArray(field.getKey());
                for (String part : value.split("\\s*[,;]\\s*")) {
                    if (!part.isEmpty()) {
                        values.add(part);
                    }
                }
            } else if (List.of("number", "integer").contains(type)) {
                String digits = value.replaceAll("[^0-9.\\-]", "");
                try {
                    if ("integer".equals(type)) {
                        out.put(field.getKey(), Long.parseLong(digits.contains(".")
                                ? digits.substring(0, digits.indexOf('.')) : digits));
                    } else {
                        out.put(field.getKey(), Double.parseDouble(digits));
                    }
                } catch (NumberFormatException e) {
                    out.putNull(field.getKey());
                }
            } else if ("boolean".equals(type)) {
                out.put(field.getKey(), value.matches("(?i)yes|true|y|1|x|checked"));
            } else {
                out.put(field.getKey(), value);
            }
        });
    }

    /** The value labelled by the field's name (or its instruction's words) in the text. */
    static String lookup(String field, String instruction, String text) {
        List<String> labels = new ArrayList<>();
        labels.add(field.replace('_', ' ').replace('-', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").strip());
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(instruction);
        while (m.find()) {
            String word = m.group().toLowerCase(Locale.ROOT);
            if (!STOP_WORDS.contains(word)) {
                words.add(word);
            }
        }
        for (int size = Math.min(3, words.size()); size >= 1; size--) {
            for (int i = 0; i + size <= words.size(); i++) {
                labels.add(String.join(" ", words.subList(i, i + size)));
            }
        }
        for (String label : labels) {
            if (label.isEmpty()) {
                continue;
            }
            String flexible = Pattern.quote(label).replace(" ", "\\E[\\s_-]*\\Q");
            Matcher colon = Pattern.compile("(?im)\\b" + flexible + "\\b\\s*(?:#|no\\.?)?\\s*[:=]\\s*(.+?)\\s*$")
                    .matcher(text);
            if (colon.find()) {
                return colon.group(1);
            }
            Matcher leading = Pattern.compile("(?im)^\\s*" + flexible + "\\b\\s*(?:#|no\\.?)?\\s+(\\S.*?)\\s*$")
                    .matcher(text);
            if (leading.find()) {
                return leading.group(1);
            }
        }
        return null;
    }

    private boolean proxied() {
        return runtime.isResolvable() && "proxy".equalsIgnoreCase(config.services().bedrockRuntime().backend());
    }

    /** Asks Bedrock Runtime's model to fill the fields as JSON; null when it doesn't answer with JSON. */
    private ObjectNode inferWithModel(JsonNode schema, String text) {
        try {
            ObjectNode request = mapper.createObjectNode();
            ObjectNode message = request.putArray("messages").addObject();
            message.put("role", "user");
            message.putArray("content").addObject().put("text", "Extract these fields from the document. Answer "
                    + "with one JSON object, keyed by field name, and null for a field the document doesn't give.\n\n"
                    + "Fields (JSON schema):\n" + schema.toString() + "\n\nDocument:\n" + text);
            ObjectNode response = runtime.get().buildConverseResponse("amazon.nova-lite-v1:0", request);
            String answer = response.path("output").path("message").path("content").path(0).path("text").asText("");
            int start = answer.indexOf('{');
            int end = answer.lastIndexOf('}');
            if (start < 0 || end < start) {
                return null;
            }
            JsonNode parsed = mapper.readTree(answer.substring(start, end + 1));
            return parsed.isObject() ? (ObjectNode) parsed : null;
        } catch (Exception e) {
            LOG.debugv("The Bedrock Runtime model couldn't fill the blueprint: {0}", e.getMessage());
            return null;
        }
    }
}
