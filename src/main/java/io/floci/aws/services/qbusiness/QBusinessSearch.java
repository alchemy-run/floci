package io.floci.aws.services.qbusiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * How Q Business reads and finds documents: the text of each content type (plain text, Markdown, CSV, JSON, XML,
 * HTML, RTF, PDF, Word, Excel and PowerPoint), passages of it, and BM25 ranking of passages for a query, with the
 * sentences of the best passages that answer it.
 */
final class QBusinessSearch {

    private static final Set<String> STOP = Set.of("a", "an", "and", "are", "as", "at", "be", "by", "can", "do",
            "does", "for", "from", "how", "i", "in", "is", "it", "me", "my", "of", "on", "or", "please", "tell",
            "that", "the", "this", "to", "was", "what", "when", "where", "which", "who", "why", "with", "you",
            "your", "about", "give", "show");
    private static final int PASSAGE_WORDS = 120;

    /** A passage of a document. */
    record Passage(String documentId, String title, String uri, String text, JsonNode attributes,
                   String dataSourceId) {
    }

    /** A passage with its score. */
    record Hit(Passage passage, double score) {
    }

    private QBusinessSearch() {
    }

    // ================================================================ text

    /** The text of a document's content. */
    static String text(byte[] content, String type) {
        String kind = type == null ? "PLAIN_TEXT" : type;
        try {
            return switch (kind) {
                case "HTML" -> html(new String(content, StandardCharsets.UTF_8));
                case "XML", "XSLT" -> tags(new String(content, StandardCharsets.UTF_8));
                case "JSON" -> json(new String(content, StandardCharsets.UTF_8));
                case "RTF" -> rtf(new String(content, StandardCharsets.UTF_8));
                case "PDF" -> pdf(content);
                case "MS_WORD" -> office(content, "word/document.xml", null);
                case "PPT" -> office(content, null, "ppt/slides/slide");
                case "MS_EXCEL" -> office(content, "xl/sharedStrings.xml", "xl/worksheets/sheet");
                case "CSV" -> new String(content, StandardCharsets.UTF_8).replace(',', ' ');
                default -> new String(content, StandardCharsets.UTF_8);
            };
        } catch (IOException | RuntimeException e) {
            throw QBusinessStore.validation("The document's " + kind + " content can't be read: " + e.getMessage());
        }
    }

    static String title(byte[] content, String type) {
        if ("HTML".equals(type)) {
            Matcher matcher = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(new String(content,
                    StandardCharsets.UTF_8));
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    private static String html(String html) {
        String body = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        return entities(tags(body));
    }

    private static String tags(String markup) {
        return markup.replaceAll("(?s)<[^>]+>", " ").replaceAll("[ \\t]+", " ");
    }

    private static String entities(String text) {
        return text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static String json(String json) throws IOException {
        StringBuilder out = new StringBuilder();
        collect(new ObjectMapper().readTree(json), out);
        return out.toString();
    }

    private static void collect(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                out.append(entry.getKey()).append(": ");
                collect(entry.getValue(), out);
            });
        } else if (node.isArray()) {
            node.forEach(item -> collect(item, out));
        } else {
            out.append(node.asText()).append(". ");
        }
    }

    private static String rtf(String rtf) {
        return rtf.replaceAll("\\\\'[0-9a-f]{2}", "").replaceAll("\\\\[a-z]+-?\\d* ?", "").replaceAll("[{}]", "");
    }

    private static String office(byte[] content, String exact, String prefix) throws IOException {
        StringBuilder out = new StringBuilder();
        TreeMap<String, String> parts = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals(exact) || (prefix != null && name.startsWith(prefix) && name.endsWith(".xml"))) {
                    parts.put(name, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        if (parts.isEmpty()) {
            throw QBusinessStore.validation("The file isn't an Office Open XML document.");
        }
        for (String part : parts.values()) {
            out.append(entities(part.replaceAll("</w:p>|</a:p>|</si>|</row>", "\n").replaceAll("(?s)<[^>]+>", " ")))
                    .append('\n');
        }
        return out.toString().replaceAll("[ \\t]+", " ");
    }

    /** The text a PDF's content streams show, inflating FlateDecode streams. */
    private static String pdf(byte[] content) throws IOException {
        String raw = new String(content, StandardCharsets.ISO_8859_1);
        if (!raw.startsWith("%PDF")) {
            throw QBusinessStore.validation("The file isn't a PDF.");
        }
        StringBuilder out = new StringBuilder();
        Matcher stream = Pattern.compile("(?s)<<(.*?)>>\\s*stream\\r?\\n").matcher(raw);
        int at = 0;
        while (stream.find(at)) {
            int start = stream.end();
            int end = raw.indexOf("endstream", start);
            if (end < 0) {
                break;
            }
            byte[] data = raw.substring(start, end).getBytes(StandardCharsets.ISO_8859_1);
            String body = stream.group(1).contains("FlateDecode") ? inflate(data) : new String(data,
                    StandardCharsets.ISO_8859_1);
            Matcher text = Pattern.compile("\\(((?:\\\\.|[^\\\\)])*)\\)\\s*(?:Tj|'|\")|\\[((?:[^\\]])*)\\]\\s*TJ")
                    .matcher(body);
            while (text.find()) {
                if (text.group(1) != null) {
                    out.append(unescape(text.group(1)));
                } else {
                    Matcher piece = Pattern.compile("\\(((?:\\\\.|[^\\\\)])*)\\)").matcher(text.group(2));
                    while (piece.find()) {
                        out.append(unescape(piece.group(1)));
                    }
                }
                out.append(' ');
            }
            at = end;
        }
        return out.toString();
    }

    private static String inflate(byte[] data) {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
            }
        } catch (DataFormatException e) {
            return "";
        } finally {
            inflater.end();
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static String unescape(String text) {
        return text.replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\").replace("\\n", " ");
    }

    // ================================================================ passages

    static List<String> sentences(String text) {
        List<String> sentences = new ArrayList<>();
        for (String piece : text.replaceAll("\\s+", " ").trim().split("(?<=[.!?])\\s+|\\n")) {
            if (!piece.isBlank()) {
                sentences.add(piece.trim());
            }
        }
        return sentences;
    }

    /** A document's text in passages of about {@value #PASSAGE_WORDS} words, on sentence boundaries. */
    static List<String> passages(String text) {
        List<String> passages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int words = 0;
        for (String sentence : sentences(text)) {
            int count = sentence.split("\\s+").length;
            if (words > 0 && words + count > PASSAGE_WORDS) {
                passages.add(current.toString().trim());
                current.setLength(0);
                words = 0;
            }
            current.append(sentence).append(' ');
            words += count;
        }
        if (current.length() > 0) {
            passages.add(current.toString().trim());
        }
        return passages;
    }

    static List<String> terms(String text) {
        List<String> terms = new ArrayList<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() > 1 && !STOP.contains(token)) {
                terms.add(stem(token));
            }
        }
        return terms;
    }

    private static String stem(String token) {
        if (token.length() > 4 && token.endsWith("ies")) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.length() > 3 && token.endsWith("s") && !token.endsWith("ss")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    // ================================================================ ranking

    /** The passages that best match a query, by BM25. */
    static List<Hit> rank(String query, Collection<Passage> passages, int limit) {
        List<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        Map<Passage, Map<String, Integer>> frequencies = new LinkedHashMap<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalLength = 0;
        for (Passage passage : passages) {
            Map<String, Integer> counts = new HashMap<>();
            List<String> passageTerms = terms(passage.title() == null ? passage.text()
                    : passage.title() + " " + passage.text());
            passageTerms.forEach(term -> counts.merge(term, 1, Integer::sum));
            counts.keySet().forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
            frequencies.put(passage, counts);
            totalLength += passageTerms.size();
        }
        double average = passages.isEmpty() ? 1 : Math.max(1, totalLength / passages.size());
        int n = passages.size();
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<Passage, Map<String, Integer>> entry : frequencies.entrySet()) {
            int length = entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
            double score = 0;
            for (String term : new HashSet<>(queryTerms)) {
                int tf = entry.getValue().getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                int df = documentFrequency.get(term);
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * length / average));
            }
            if (score > 0) {
                hits.add(new Hit(entry.getKey(), score));
            }
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.subList(0, Math.min(limit, hits.size()));
    }

    /** How confident a hit is, relative to the best one and to how much of the query it covers. */
    static String confidence(Hit hit, String query) {
        Set<String> wanted = new HashSet<>(terms(query));
        Set<String> found = new HashSet<>(terms(hit.passage().text()));
        found.retainAll(wanted);
        double coverage = wanted.isEmpty() ? 0 : (double) found.size() / wanted.size();
        return coverage >= 0.9 ? "VERY_HIGH" : coverage >= 0.6 ? "HIGH" : coverage >= 0.35 ? "MEDIUM" : "LOW";
    }

    /** The sentences of the best passages that answer a query, at most {@code max}. */
    static List<String> answer(String query, List<Hit> hits, int max) {
        Set<String> wanted = new HashSet<>(terms(query));
        List<String[]> scored = new ArrayList<>();
        for (Hit hit : hits) {
            for (String sentence : sentences(hit.passage().text())) {
                Set<String> found = new HashSet<>(terms(sentence));
                found.retainAll(wanted);
                if (!found.isEmpty()) {
                    scored.add(new String[] {sentence, String.valueOf(found.size() + hit.score() / 100)});
                }
            }
        }
        scored.sort((a, b) -> Double.compare(Double.parseDouble(b[1]), Double.parseDouble(a[1])));
        List<String> best = new ArrayList<>();
        for (String[] sentence : scored) {
            if (!best.contains(sentence[0])) {
                best.add(sentence[0]);
            }
            if (best.size() >= max) {
                break;
            }
        }
        return best;
    }

    // ================================================================ attribute filters

    /** Whether a document's attributes pass an attribute filter. */
    static boolean matches(JsonNode filter, JsonNode attributes) {
        if (filter == null || filter.isMissingNode() || filter.isNull() || filter.isEmpty()) {
            return true;
        }
        if (filter.has("andAllFilters")) {
            for (JsonNode part : filter.get("andAllFilters")) {
                if (!matches(part, attributes)) {
                    return false;
                }
            }
            return true;
        }
        if (filter.has("orAllFilters")) {
            for (JsonNode part : filter.get("orAllFilters")) {
                if (matches(part, attributes)) {
                    return true;
                }
            }
            return false;
        }
        if (filter.has("notFilter")) {
            return !matches(filter.get("notFilter"), attributes);
        }
        String op = filter.fieldNames().next();
        JsonNode condition = filter.get(op);
        String name = condition.path("name").asText();
        JsonNode wanted = condition.path("value");
        JsonNode actual = null;
        for (JsonNode attribute : attributes) {
            if (name.equals(attribute.path("name").asText())) {
                actual = attribute.path("value");
            }
        }
        if (actual == null) {
            return false;
        }
        return switch (op) {
            case "equalsTo" -> value(actual).equals(value(wanted));
            case "containsAll" -> list(actual).containsAll(list(wanted));
            case "containsAny" -> list(wanted).stream().anyMatch(list(actual)::contains);
            default -> {
                int compare = compare(actual, wanted);
                yield switch (op) {
                    case "greaterThan" -> compare > 0;
                    case "greaterThanOrEquals" -> compare >= 0;
                    case "lessThan" -> compare < 0;
                    default -> compare <= 0;
                };
            }
        };
    }

    private static String value(JsonNode value) {
        if (value.has("stringValue")) {
            return value.get("stringValue").asText();
        }
        if (value.has("longValue")) {
            return value.get("longValue").asText();
        }
        if (value.has("dateValue")) {
            return value.get("dateValue").asText();
        }
        return list(value).toString();
    }

    private static List<String> list(JsonNode value) {
        List<String> list = new ArrayList<>();
        if (value.has("stringListValue")) {
            value.get("stringListValue").forEach(item -> list.add(item.asText()));
        } else {
            list.add(value(value));
        }
        return list;
    }

    private static int compare(JsonNode actual, JsonNode wanted) {
        double a = actual.has("longValue") ? actual.get("longValue").asDouble() : actual.path("dateValue").asDouble();
        double b = wanted.has("longValue") ? wanted.get("longValue").asDouble() : wanted.path("dateValue").asDouble();
        return Double.compare(a, b);
    }

    // ================================================================ access

    /**
     * Whether a user (with their groups) may read a document under its access configuration: a deny matching the
     * user wins; otherwise each access control must allow, joined by the configuration's member relation.
     */
    static boolean allowed(JsonNode access, String user, Set<String> groups) {
        if (access == null || access.isMissingNode() || !access.path("accessControls").isArray()
                || access.get("accessControls").isEmpty()) {
            return true;
        }
        if (user == null) {
            return false;
        }
        boolean and = "AND".equals(access.path("memberRelation").asText("OR"));
        Boolean result = null;
        for (JsonNode control : access.get("accessControls")) {
            boolean controlAnd = "AND".equals(control.path("memberRelation").asText("OR"));
            Boolean allowed = null;
            for (JsonNode principal : control.path("principals")) {
                boolean match;
                String effect;
                if (principal.has("user")) {
                    match = user.equalsIgnoreCase(principal.at("/user/id").asText());
                    effect = principal.at("/user/access").asText();
                } else {
                    match = groups.contains(principal.at("/group/name").asText());
                    effect = principal.at("/group/access").asText();
                }
                if (match && "DENY".equals(effect)) {
                    return false;
                }
                boolean grants = match && "ALLOW".equals(effect);
                allowed = allowed == null ? grants : controlAnd ? allowed && grants : allowed || grants;
            }
            boolean value = allowed != null && allowed;
            result = result == null ? value : and ? result && value : result || value;
        }
        return result != null && result;
    }
}
