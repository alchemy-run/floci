package io.github.hectorvent.floci.services.b2bi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Minimal X12 850 parser/serializer used by B2BI TestParsing / TestConversion. */
final class B2biX12 {

    static final String SAMPLE_ISA = String.join("\n",
            "ISA*00*          *00*          *ZZ*SENDERID       *ZZ*RECEIVERID     *210101*1253*U*00401*000000001*0*T*>~",
            "GS*PO*SENDERID*RECEIVERID*20210101*1253*1*X*004010~",
            "ST*850*0001~",
            "BEG*00*SA*XX-1234**20210101~",
            "SE*4*0001~",
            "GE*1*1~",
            "IEA*1*000000001~",
            "");

    private B2biX12() {
    }

    static ObjectNode parse(String edi, JsonNode ediType, ObjectMapper mapper) {
        String document = edi == null ? "" : edi;
        ObjectNode root = mapper.createObjectNode();
        root.put("_edi", document);

        List<List<String>> segments = splitSegments(document);
        ArrayNode segmentNodes = root.putArray("segments");
        String transactionSet = "850";
        for (List<String> segment : segments) {
            ArrayNode row = segmentNodes.addArray();
            for (String element : segment) {
                row.add(element);
            }
            if (!segment.isEmpty() && "ST".equals(segment.get(0)) && segment.size() > 1) {
                transactionSet = segment.get(1);
            }
        }
        root.put("transactionSet", transactionSet);

        ObjectNode x12 = root.putObject("x12");
        JsonNode details = ediType == null ? null : ediType.get("x12Details");
        if (details == null && ediType != null) {
            details = ediType.get("x12");
        }
        String ts = text(details, "transactionSet");
        String version = text(details, "version");
        x12.put("transactionSet", ts != null ? ts : "X12_" + transactionSet);
        x12.put("version", version != null ? version : "VERSION_4010");
        return root;
    }

    static String toX12(String json, JsonNode target, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return SAMPLE_ISA;
        }
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode edi = node.get("_edi");
            if (edi != null && edi.isTextual() && edi.asText().startsWith("ISA")) {
                return edi.asText();
            }
            if (node.has("segments") && node.get("segments").isArray()) {
                return joinSegments(node.get("segments"));
            }
        } catch (Exception ignored) {
            // Fall through to a stub interchange so conversion still yields ISA.
        }
        String transactionSet = "850";
        JsonNode details = target == null ? null : firstNonNull(target.get("x12"),
                target.has("formatDetails") ? target.get("formatDetails").get("x12") : null);
        String ts = text(details, "transactionSet");
        if (ts != null && ts.startsWith("X12_")) {
            transactionSet = ts.substring("X12_".length());
        } else if (ts != null && !ts.isBlank()) {
            transactionSet = ts;
        }
        return SAMPLE_ISA.replace("ST*850*", "ST*" + transactionSet + "*");
    }

    private static List<List<String>> splitSegments(String edi) {
        List<List<String>> segments = new ArrayList<>();
        String normalized = edi.replace("\r\n", "\n").replace('\r', '\n');
        String[] raw = normalized.contains("~") ? normalized.split("~") : normalized.split("\n");
        for (String piece : raw) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] elements = trimmed.split("\\*", -1);
            List<String> row = new ArrayList<>(elements.length);
            for (String element : elements) {
                row.add(element);
            }
            segments.add(row);
        }
        return segments;
    }

    private static String joinSegments(JsonNode segments) {
        StringBuilder out = new StringBuilder();
        for (JsonNode segment : segments) {
            if (!segment.isArray() || segment.isEmpty()) {
                continue;
            }
            for (int i = 0; i < segment.size(); i++) {
                if (i > 0) {
                    out.append('*');
                }
                out.append(segment.get(i).asText());
            }
            out.append("~\n");
        }
        return out.toString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static JsonNode firstNonNull(JsonNode a, JsonNode b) {
        return a != null && !a.isNull() && !a.isMissingNode() ? a : b;
    }
}
