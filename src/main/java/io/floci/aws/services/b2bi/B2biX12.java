package io.floci.aws.services.b2bi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * X12 the way B2B Data Interchange reads and writes it. A document parses into the service's JSON
 * representation ({@code interchanges} → {@code functional_groups} → {@code transactions} → {@code segments},
 * elements as {@code BEG_03}, composites as {@code REF_04_01}, each loop iteration as
 * {@code {"N1-4000_loop": [...]}}, empty elements left out) or its XML representation (an {@code ediroot}
 * document), with segments filed into the transaction set's loops. Either representation converts back to X12 with
 * the envelope's counts and control numbers recomputed. Loops come from the schemas vendored by
 * {@code tools/aws/build_b2bi_x12_schemas.py}; a transaction set without one parses with its segments unlooped.
 */
final class B2biX12 {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode SCHEMAS = loadSchemas();
    private static final Pattern ELEMENT_REF = Pattern.compile("^([A-Z][A-Z0-9]{1,2})[-_]?(\\d{1,2})$");

    static final List<String> ISA_FIELDS = List.of("ISA_01_AuthorizationQualifier", "ISA_02_AuthorizationInformation",
            "ISA_03_SecurityQualifier", "ISA_04_SecurityInformation", "ISA_05_SenderQualifier", "ISA_06_SenderId",
            "ISA_07_ReceiverQualifier", "ISA_08_ReceiverId", "ISA_09_Date", "ISA_10_Time", "ISA_11_StandardsId",
            "ISA_12_Version", "ISA_13_InterchangeControlNumber", "ISA_14_AcknowledgmentRequested",
            "ISA_15_TestIndicator");
    private static final int[] ISA_WIDTHS = {2, 10, 2, 10, 2, 15, 2, 15, 6, 4, 1, 5, 9, 1, 1};
    static final List<String> GS_FIELDS = List.of("GS_01_FunctionalIdentifierCode", "GS_02_ApplicationSenderCode",
            "GS_03_ApplicationReceiverCode", "GS_04_Date", "GS_05_Time", "GS_06_GroupControlNumber",
            "GS_07_ResponsibleAgencyCode", "GS_08_Version");
    static final List<String> ST_FIELDS = List.of("ST_01_TransactionSetIdentifierCode",
            "ST_02_TransactionSetControlNumber", "ST_03_ImplementationConventionReference");

    /** Transaction set titles, for the XML representation's {@code Name}, and their GS01 functional group codes. */
    private static final Map<String, String[]> SETS = Map.ofEntries(
            Map.entry("180", new String[] {"Return Merchandise Authorization and Notification", "AN"}),
            Map.entry("204", new String[] {"Motor Carrier Load Tender", "SM"}),
            Map.entry("210", new String[] {"Motor Carrier Freight Details and Invoice", "IM"}),
            Map.entry("211", new String[] {"Motor Carrier Bill of Lading", "BL"}),
            Map.entry("214", new String[] {"Transportation Carrier Shipment Status Message", "QM"}),
            Map.entry("753", new String[] {"Request for Routing Instructions", "GF"}),
            Map.entry("754", new String[] {"Routing Instructions", "GF"}),
            Map.entry("810", new String[] {"Invoice", "IN"}),
            Map.entry("812", new String[] {"Credit/Debit Adjustment", "CD"}),
            Map.entry("816", new String[] {"Organizational Relationships", "OR"}),
            Map.entry("820", new String[] {"Payment Order/Remittance Advice", "RA"}),
            Map.entry("824", new String[] {"Application Advice", "AG"}),
            Map.entry("830", new String[] {"Planning Schedule with Release Capability", "PS"}),
            Map.entry("832", new String[] {"Price/Sales Catalog", "SC"}),
            Map.entry("840", new String[] {"Request for Quotation", "RQ"}),
            Map.entry("843", new String[] {"Response to Request for Quotation", "RR"}),
            Map.entry("844", new String[] {"Product Transfer Account Adjustment", "CA"}),
            Map.entry("846", new String[] {"Inventory Inquiry/Advice", "IB"}),
            Map.entry("850", new String[] {"Purchase Order", "PO"}),
            Map.entry("852", new String[] {"Product Activity Data", "PD"}),
            Map.entry("855", new String[] {"Purchase Order Acknowledgment", "PR"}),
            Map.entry("856", new String[] {"Ship Notice/Manifest", "SH"}),
            Map.entry("860", new String[] {"Purchase Order Change Request - Buyer Initiated", "PC"}),
            Map.entry("861", new String[] {"Receiving Advice/Acceptance Certificate", "RC"}),
            Map.entry("862", new String[] {"Shipping Schedule", "SS"}),
            Map.entry("864", new String[] {"Text Message", "TX"}),
            Map.entry("865", new String[] {"Purchase Order Change Acknowledgment/Request - Seller Initiated", "CA"}),
            Map.entry("866", new String[] {"Production Sequence", "PP"}),
            Map.entry("867", new String[] {"Product Transfer and Resale Report", "PT"}),
            Map.entry("869", new String[] {"Order Status Inquiry", "RS"}),
            Map.entry("870", new String[] {"Order Status Report", "RS"}),
            Map.entry("875", new String[] {"Grocery Products Purchase Order", "OG"}),
            Map.entry("880", new String[] {"Grocery Products Invoice", "GP"}),
            Map.entry("940", new String[] {"Warehouse Shipping Order", "OW"}),
            Map.entry("943", new String[] {"Warehouse Stock Transfer Shipment Advice", "AR"}),
            Map.entry("944", new String[] {"Warehouse Stock Transfer Receipt Advice", "RE"}),
            Map.entry("945", new String[] {"Warehouse Shipping Advice", "SW"}),
            Map.entry("947", new String[] {"Warehouse Inventory Adjustment Advice", "AW"}),
            Map.entry("990", new String[] {"Response to a Load Tender", "GF"}),
            Map.entry("997", new String[] {"Functional Acknowledgment", "FA"}),
            Map.entry("999", new String[] {"Implementation Acknowledgment", "FA"}));

    private B2biX12() {
    }

    private static JsonNode loadSchemas() {
        try (InputStream in = B2biX12.class.getClassLoader().getResourceAsStream("aws/b2bi-x12-schemas.json")) {
            return in == null ? MAPPER.createObjectNode() : MAPPER.readTree(in).path("schemas");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the vendored B2BI X12 schemas", e);
        }
    }

    /** The loop schema of a transaction set ({@code X12_850}) in a version ({@code VERSION_4010}), if vendored. */
    static Optional<JsonNode> schema(String transactionSet, String version) {
        JsonNode sets = SCHEMAS.path(transactionSet);
        JsonNode schema = sets.path(version == null ? "" : version);
        if (schema.isMissingNode() && version != null && version.startsWith("VERSION_5010")) {
            schema = sets.path("VERSION_5010");
        }
        if (schema.isMissingNode() && version != null && version.startsWith("VERSION_40")) {
            schema = sets.path("VERSION_4010");
        }
        return schema.isArray() ? Optional.of(schema) : Optional.empty();
    }

    static String setNumber(String transactionSet) {
        return transactionSet.startsWith("X12_") ? transactionSet.substring(4) : transactionSet;
    }

    static String functionalGroup(String number) {
        return SETS.containsKey(number) ? SETS.get(number)[1] : "PO";
    }

    // ── Tokenizing ─────────────────────────────────────────────────────────

    record Delimiters(char element, char component, char segment) {
        static Delimiters standard() {
            return new Delimiters('*', '>', '~');
        }
    }

    record Segment(String id, List<String> elements) {
        String element(int position) {
            return position <= elements.size() ? elements.get(position - 1) : "";
        }
    }

    record Tokens(Delimiters delimiters, List<Segment> segments) {
    }

    static AwsException unparseable(String reason) {
        return new AwsException("ValidationException", "The EDI file could not be parsed. " + reason, 400);
    }

    private static String[] split(String text, char separator) {
        return text.split(Pattern.quote(String.valueOf(separator)), -1);
    }

    /** Splits an X12 document into segments, with the delimiters its ISA segment declares. */
    static Tokens tokenize(String edi) {
        String text = edi.startsWith("﻿") ? edi.substring(1) : edi;
        text = text.stripLeading();
        if (!text.startsWith("ISA") || text.length() < 106) {
            throw unparseable("The file doesn't start with an ISA interchange header.");
        }
        char element = text.charAt(3);
        int sixteenth = 0;
        int index = -1;
        for (int i = 0; i < text.length() && sixteenth < 16; i++) {
            if (text.charAt(i) == element) {
                sixteenth++;
                index = i;
            }
        }
        if (sixteenth < 16 || index + 2 >= text.length()) {
            throw unparseable("The ISA interchange header doesn't have its 16 elements.");
        }
        Delimiters delimiters = new Delimiters(element, text.charAt(index + 1), text.charAt(index + 2));
        List<Segment> segments = new ArrayList<>();
        for (String raw : split(text, delimiters.segment())) {
            String trimmed = raw.replaceAll("^\\s+", "").replaceAll("[\\r\\n]+$", "");
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = split(trimmed, element);
            segments.add(new Segment(parts[0].trim(), new ArrayList<>(List.of(parts).subList(1, parts.length))));
        }
        return new Tokens(delimiters, segments);
    }

    // ── Parsing ────────────────────────────────────────────────────────────

    /** A parsed document: its JSON representation, one document per transaction, and what validation found. */
    record Parsed(ObjectNode document, List<ObjectNode> splits, List<String> messages, Delimiters delimiters,
                  List<Transaction> transactions) {
    }

    /** One transaction of a parsed document, with its envelope, for acknowledgments. */
    record Transaction(ObjectNode interchange, ObjectNode group, ObjectNode transaction, List<String> errors) {
    }

    /**
     * Parses an X12 document of the given transaction set and version. A document of another transaction set or
     * version, or one that isn't X12, fails as AWS fails it; envelope and structure problems, and the transformer's
     * validation rules, are validation messages.
     */
    static Parsed parse(String edi, String transactionSet, String version, List<JsonNode> rules) {
        Tokens tokens = tokenize(edi);
        String number = setNumber(transactionSet);
        JsonNode schema = schema(transactionSet, version).orElse(null);
        List<String> messages = new ArrayList<>();
        if (schema == null) {
            messages.add("Floci has no loop schema for " + transactionSet + " " + version
                    + ", so its segments are listed without loops.");
        }
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode interchanges = root.putArray("interchanges");
        List<ObjectNode> splits = new ArrayList<>();
        List<Transaction> transactions = new ArrayList<>();
        List<Segment> segments = tokens.segments();
        ObjectNode interchange = null;
        ObjectNode group = null;
        int groupCount = 0;
        int transactionCount = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            switch (segment.id()) {
                case "ISA" -> {
                    interchange = interchanges.addObject();
                    for (int f = 0; f < ISA_FIELDS.size(); f++) {
                        interchange.put(ISA_FIELDS.get(f), segment.element(f + 1));
                    }
                    interchange.putArray("functional_groups");
                    groupCount = 0;
                }
                case "GS" -> {
                    if (interchange == null) {
                        throw unparseable("A GS functional group header comes before any ISA header.");
                    }
                    group = interchange.withArray("functional_groups").addObject();
                    for (int f = 0; f < GS_FIELDS.size(); f++) {
                        group.put(GS_FIELDS.get(f), segment.element(f + 1));
                    }
                    group.putArray("transactions");
                    transactionCount = 0;
                    groupCount++;
                }
                case "ST" -> {
                    if (group == null) {
                        throw unparseable("An ST transaction set header comes before any GS header.");
                    }
                    if (!number.equals(segment.element(1))) {
                        throw unparseable("EDI transaction set does not match " + number);
                    }
                    String declared = group.path("GS_08_Version").asText("");
                    if (!versionMatches(declared, version)) {
                        throw unparseable("EDI version " + declared + " does not match " + version);
                    }
                    int end = i + 1;
                    while (end < segments.size() && !"SE".equals(segments.get(end).id())) {
                        end++;
                    }
                    if (end >= segments.size()) {
                        throw unparseable("Transaction " + segment.element(2) + " has no SE trailer.");
                    }
                    ObjectNode transaction = group.withArray("transactions").addObject();
                    transaction.put(ST_FIELDS.get(0), segment.element(1));
                    transaction.put(ST_FIELDS.get(1), segment.element(2));
                    if (!segment.element(3).isEmpty()) {
                        transaction.put(ST_FIELDS.get(2), segment.element(3));
                    }
                    List<String> errors = new ArrayList<>();
                    List<Segment> body = segments.subList(i + 1, end);
                    file(schema, body, transaction.putArray("segments"), tokens.delimiters(), errors, number);
                    Segment trailer = segments.get(end);
                    int count = end - i + 1;
                    if (!String.valueOf(count).equals(trailer.element(1).replaceFirst("^0+(?=\\d)", ""))) {
                        errors.add("SE01 of transaction " + segment.element(2) + " is " + trailer.element(1)
                                + ", but the transaction has " + count + " segments.");
                    }
                    if (!segment.element(2).equals(trailer.element(2))) {
                        errors.add("SE02 " + trailer.element(2) + " doesn't match ST02 " + segment.element(2) + ".");
                    }
                    for (JsonNode rule : rules) {
                        validateRule(rule, body, errors);
                    }
                    messages.addAll(errors);
                    transactions.add(new Transaction(interchange, group, transaction, errors));
                    ObjectNode split = MAPPER.createObjectNode();
                    ObjectNode splitInterchange = split.putArray("interchanges").addObject();
                    copyHeader(interchange, splitInterchange, ISA_FIELDS);
                    ObjectNode splitGroup = splitInterchange.putArray("functional_groups").addObject();
                    copyHeader(group, splitGroup, GS_FIELDS);
                    splitGroup.putArray("transactions").add(transaction.deepCopy());
                    splits.add(split);
                    transactionCount++;
                    i = end;
                }
                case "GE" -> {
                    if (group == null) {
                        throw unparseable("A GE trailer comes before any GS header.");
                    }
                    if (!String.valueOf(transactionCount).equals(segment.element(1))) {
                        messages.add("GE01 is " + segment.element(1) + ", but the functional group has "
                                + transactionCount + " transactions.");
                    }
                    if (!group.path("GS_06_GroupControlNumber").asText().equals(segment.element(2))) {
                        messages.add("GE02 " + segment.element(2) + " doesn't match GS06 "
                                + group.path("GS_06_GroupControlNumber").asText() + ".");
                    }
                    group = null;
                }
                case "IEA" -> {
                    if (interchange == null) {
                        throw unparseable("An IEA trailer comes before any ISA header.");
                    }
                    if (!String.valueOf(groupCount).equals(segment.element(1))) {
                        messages.add("IEA01 is " + segment.element(1) + ", but the interchange has " + groupCount
                                + " functional groups.");
                    }
                    if (!interchange.path("ISA_13_InterchangeControlNumber").asText().equals(segment.element(2))) {
                        messages.add("IEA02 " + segment.element(2) + " doesn't match ISA13 "
                                + interchange.path("ISA_13_InterchangeControlNumber").asText() + ".");
                    }
                    interchange = null;
                }
                case "TA1" -> {
                    // An interchange acknowledgment travels in the envelope, outside any transaction.
                }
                default -> messages.add("Segment " + segment.id() + " is outside any transaction set.");
            }
        }
        if (transactions.isEmpty()) {
            throw unparseable("The file has no " + number + " transaction set.");
        }
        return new Parsed(root, splits, messages, tokens.delimiters(), transactions);
    }

    private static boolean versionMatches(String gs08, String version) {
        if (version == null || gs08.isEmpty()) {
            return true;
        }
        return gs08.startsWith("00" + version.replace("VERSION_", "").replace("_HIPAA", ""));
    }

    private static void copyHeader(ObjectNode from, ObjectNode to, List<String> fields) {
        for (String field : fields) {
            if (from.has(field)) {
                to.set(field, from.get(field));
            }
        }
    }

    /** Files a transaction's segments into the schema's loops, or lists them unlooped without a schema. */
    private static void file(JsonNode schema, List<Segment> body, ArrayNode out, Delimiters delimiters,
                             List<String> errors, String number) {
        if (schema == null) {
            body.forEach(s -> out.add(segmentJson(s, null, delimiters)));
            return;
        }
        int i = 0;
        while (i < body.size()) {
            int next = fill(schema, body, i, out, 0, delimiters);
            if (next == i) {
                Segment unknown = body.get(i);
                errors.add("Segment " + unknown.id() + " (segment " + (i + 2) + " of the transaction) isn't part "
                        + "of transaction set " + number + " at that position.");
                out.add(segmentJson(unknown, null, delimiters));
                i++;
            } else {
                i = next;
            }
        }
    }

    /**
     * Files segments against a body of nodes from position {@code from} on: a loop body passes 1, its trigger
     * segment already filed, so meeting the trigger again ends this iteration. Returns the index of the first
     * segment that doesn't belong here, for an enclosing level to place.
     */
    private static int fill(JsonNode body, List<Segment> segments, int i, ArrayNode out, int from,
                            Delimiters delimiters) {
        int position = from;
        boolean inLoop = from > 0;
        while (i < segments.size()) {
            Segment segment = segments.get(i);
            int found = find(body, position, segment.id());
            if (found < 0 || inLoop && found == 0) {
                return i;
            }
            JsonNode node = body.get(found);
            if (node.isObject()) {
                ArrayNode iteration = MAPPER.createArrayNode();
                JsonNode children = node.path("children");
                iteration.add(segmentJson(segment, children.get(0), delimiters));
                i = fill(children, segments, i + 1, iteration, 1, delimiters);
                out.addObject().set(node.path("loop").asText() + "_loop", iteration);
            } else {
                out.add(segmentJson(segment, node, delimiters));
                i++;
            }
            position = found;
        }
        return i;
    }

    /** The index of the node at or after {@code from} that a segment with this id is, or starts. */
    private static int find(JsonNode body, int from, String id) {
        for (int n = from; n < body.size(); n++) {
            JsonNode node = body.get(n);
            String nodeId = node.isObject() ? node.path("children").get(0).get(0).asText() : node.get(0).asText();
            if (id.equals(nodeId)) {
                return n;
            }
        }
        return -1;
    }

    private static ObjectNode segmentJson(Segment segment, JsonNode node, Delimiters delimiters) {
        ObjectNode out = MAPPER.createObjectNode();
        JsonNode composites = node != null && node.size() > 3 ? node.get(3) : MAPPER.createObjectNode();
        for (int e = 1; e <= segment.elements().size(); e++) {
            String value = segment.element(e);
            if (value.isEmpty()) {
                continue;
            }
            String key = segment.id() + "_" + two(e);
            if (composites.has(String.valueOf(e)) || value.indexOf(delimiters.component()) >= 0) {
                ObjectNode parts = out.putObject(key);
                String[] components = split(value, delimiters.component());
                for (int c = 0; c < components.length; c++) {
                    if (!components[c].isEmpty()) {
                        parts.put(key + "_" + two(c + 1), components[c]);
                    }
                }
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    private static String two(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    /** Applies one of a transformer's X12 validation rules (element length or requirement) to a transaction. */
    private static void validateRule(JsonNode rule, List<Segment> body, List<String> errors) {
        JsonNode length = rule.path("elementLengthValidationRule");
        if (length.isObject()) {
            String[] element = elementRef(length.path("elementId").asText());
            for (Segment segment : body) {
                if (element != null && segment.id().equals(element[0])) {
                    String value = segment.element(Integer.parseInt(element[1]));
                    int min = length.path("minLength").asInt();
                    int max = length.path("maxLength").asInt();
                    if (!value.isEmpty() && (value.length() < min || value.length() > max)) {
                        errors.add("Element " + element[0] + element[1] + " is " + value.length() + " characters, "
                                + "outside " + min + " to " + max + ".");
                    }
                }
            }
        }
        JsonNode requirement = rule.path("elementRequirementValidationRule");
        if (requirement.isObject() && "MANDATORY".equals(requirement.path("requirement").asText())) {
            String[] element = elementRef(requirement.path("elementPosition").asText());
            for (Segment segment : body) {
                if (element != null && segment.id().equals(element[0])
                        && segment.element(Integer.parseInt(element[1])).isEmpty()) {
                    errors.add("Mandatory element " + element[0] + element[1] + " is missing.");
                }
            }
        }
    }

    /** {@code BEG03}, {@code BEG-03} or {@code BEG_03} as segment id and position. */
    private static String[] elementRef(String ref) {
        Matcher m = ELEMENT_REF.matcher(ref.trim());
        return m.matches() ? new String[] {m.group(1), m.group(2)} : null;
    }

    // ── XML representation ─────────────────────────────────────────────────

    /** The {@code ediroot} XML representation of a parsed JSON document. */
    static String toXml(JsonNode document) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ediroot>\n");
        for (JsonNode interchange : document.path("interchanges")) {
            xml.append("    <interchange Standard=\"ANSI X.12\"");
            attr(xml, "AuthorizationQual", interchange, "ISA_01_AuthorizationQualifier");
            attr(xml, "Authorization", interchange, "ISA_02_AuthorizationInformation");
            attr(xml, "SecurityQual", interchange, "ISA_03_SecurityQualifier");
            attr(xml, "Security", interchange, "ISA_04_SecurityInformation");
            attr(xml, "Date", interchange, "ISA_09_Date");
            attr(xml, "Time", interchange, "ISA_10_Time");
            attr(xml, "StandardsId", interchange, "ISA_11_StandardsId");
            attr(xml, "Version", interchange, "ISA_12_Version");
            attr(xml, "Control", interchange, "ISA_13_InterchangeControlNumber");
            attr(xml, "AckRequest", interchange, "ISA_14_AcknowledgmentRequested");
            attr(xml, "TestIndicator", interchange, "ISA_15_TestIndicator");
            xml.append(">\n        <sender>\n            <address");
            attr(xml, "Id", interchange, "ISA_06_SenderId");
            attr(xml, "Qual", interchange, "ISA_05_SenderQualifier");
            xml.append("/>\n        </sender>\n        <receiver>\n            <address");
            attr(xml, "Id", interchange, "ISA_08_ReceiverId");
            attr(xml, "Qual", interchange, "ISA_07_ReceiverQualifier");
            xml.append("/>\n        </receiver>\n");
            for (JsonNode group : interchange.path("functional_groups")) {
                xml.append("        <group");
                attr(xml, "GroupType", group, "GS_01_FunctionalIdentifierCode");
                attr(xml, "ApplSender", group, "GS_02_ApplicationSenderCode");
                attr(xml, "ApplReceiver", group, "GS_03_ApplicationReceiverCode");
                attr(xml, "Date", group, "GS_04_Date");
                attr(xml, "Time", group, "GS_05_Time");
                attr(xml, "Control", group, "GS_06_GroupControlNumber");
                attr(xml, "StandardCode", group, "GS_07_ResponsibleAgencyCode");
                attr(xml, "StandardVersion", group, "GS_08_Version");
                xml.append(">\n");
                for (JsonNode transaction : group.path("transactions")) {
                    String type = transaction.path(ST_FIELDS.get(0)).asText();
                    xml.append("            <transaction");
                    attr(xml, "DocType", transaction, ST_FIELDS.get(0));
                    if (SETS.containsKey(type)) {
                        xml.append(" Name=\"").append(escape(SETS.get(type)[0])).append('"');
                    }
                    attr(xml, "Control", transaction, ST_FIELDS.get(1));
                    attr(xml, "ImplementationReference", transaction, ST_FIELDS.get(2));
                    xml.append(">\n");
                    segmentsXml(transaction.path("segments"), xml, "                ");
                    xml.append("            </transaction>\n");
                }
                xml.append("        </group>\n");
            }
            xml.append("    </interchange>\n");
        }
        return xml.append("</ediroot>").toString();
    }

    private static void segmentsXml(JsonNode segments, StringBuilder xml, String indent) {
        for (JsonNode segment : segments) {
            Iterator<Map.Entry<String, JsonNode>> fields = segment.fields();
            if (!fields.hasNext()) {
                continue;
            }
            Map.Entry<String, JsonNode> first = fields.next();
            if (first.getKey().endsWith("_loop")) {
                String id = first.getKey().substring(0, first.getKey().length() - "_loop".length());
                xml.append(indent).append("<loop Id=\"").append(escape(id)).append("\">\n");
                segmentsXml(first.getValue(), xml, indent + "    ");
                xml.append(indent).append("</loop>\n");
                continue;
            }
            String id = first.getKey().substring(0, first.getKey().indexOf('_'));
            xml.append(indent).append("<segment Id=\"").append(escape(id)).append("\">\n");
            Iterator<Map.Entry<String, JsonNode>> elements = segment.fields();
            while (elements.hasNext()) {
                Map.Entry<String, JsonNode> element = elements.next();
                String elementId = id + element.getKey().substring(id.length() + 1);
                if (element.getValue().isObject()) {
                    xml.append(indent).append("    <element Id=\"").append(escape(elementId))
                            .append("\" Composite=\"yes\">\n");
                    Iterator<Map.Entry<String, JsonNode>> parts = element.getValue().fields();
                    while (parts.hasNext()) {
                        Map.Entry<String, JsonNode> part = parts.next();
                        int sequence = Integer.parseInt(part.getKey().substring(part.getKey().lastIndexOf('_') + 1));
                        xml.append(indent).append("        <subelement Sequence=\"").append(sequence).append("\">")
                                .append(escape(part.getValue().asText())).append("</subelement>\n");
                    }
                    xml.append(indent).append("    </element>\n");
                } else {
                    xml.append(indent).append("    <element Id=\"").append(escape(elementId)).append("\">")
                            .append(escape(element.getValue().asText())).append("</element>\n");
                }
            }
            xml.append(indent).append("</segment>\n");
        }
    }

    private static void attr(StringBuilder xml, String name, JsonNode from, String field) {
        if (from.hasNonNull(field)) {
            xml.append(' ').append(name).append("=\"").append(escape(from.path(field).asText())).append('"');
        }
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Parses XML with external entities and DOCTYPEs refused. */
    static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AwsException("ValidationException", "The input isn't well-formed XML: " + e.getMessage(), 400);
        }
    }

    /** Reads an {@code ediroot} XML document back into the JSON representation. */
    static ObjectNode fromXml(String xml) {
        Element root = parseXml(xml).getDocumentElement();
        if (!"ediroot".equals(root.getTagName())) {
            throw new AwsException("ValidationException", "The XML input isn't B2BI's X12 representation: its root "
                    + "element is " + root.getTagName() + ", not ediroot.", 400);
        }
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode interchanges = out.putArray("interchanges");
        for (Element interchange : children(root, "interchange")) {
            ObjectNode isa = interchanges.addObject();
            put(isa, "ISA_01_AuthorizationQualifier", interchange.getAttribute("AuthorizationQual"));
            put(isa, "ISA_02_AuthorizationInformation", interchange.getAttribute("Authorization"));
            put(isa, "ISA_03_SecurityQualifier", interchange.getAttribute("SecurityQual"));
            put(isa, "ISA_04_SecurityInformation", interchange.getAttribute("Security"));
            Element sender = first(children(interchange, "sender"), "address");
            Element receiver = first(children(interchange, "receiver"), "address");
            put(isa, "ISA_05_SenderQualifier", sender == null ? "" : sender.getAttribute("Qual"));
            put(isa, "ISA_06_SenderId", sender == null ? "" : sender.getAttribute("Id"));
            put(isa, "ISA_07_ReceiverQualifier", receiver == null ? "" : receiver.getAttribute("Qual"));
            put(isa, "ISA_08_ReceiverId", receiver == null ? "" : receiver.getAttribute("Id"));
            put(isa, "ISA_09_Date", interchange.getAttribute("Date"));
            put(isa, "ISA_10_Time", interchange.getAttribute("Time"));
            put(isa, "ISA_11_StandardsId", interchange.getAttribute("StandardsId"));
            put(isa, "ISA_12_Version", interchange.getAttribute("Version"));
            put(isa, "ISA_13_InterchangeControlNumber", interchange.getAttribute("Control"));
            put(isa, "ISA_14_AcknowledgmentRequested", interchange.getAttribute("AckRequest"));
            put(isa, "ISA_15_TestIndicator", interchange.getAttribute("TestIndicator"));
            ArrayNode groups = isa.putArray("functional_groups");
            for (Element group : children(interchange, "group")) {
                ObjectNode gs = groups.addObject();
                put(gs, "GS_01_FunctionalIdentifierCode", group.getAttribute("GroupType"));
                put(gs, "GS_02_ApplicationSenderCode", group.getAttribute("ApplSender"));
                put(gs, "GS_03_ApplicationReceiverCode", group.getAttribute("ApplReceiver"));
                put(gs, "GS_04_Date", group.getAttribute("Date"));
                put(gs, "GS_05_Time", group.getAttribute("Time"));
                put(gs, "GS_06_GroupControlNumber", group.getAttribute("Control"));
                put(gs, "GS_07_ResponsibleAgencyCode", group.getAttribute("StandardCode"));
                put(gs, "GS_08_Version", group.getAttribute("StandardVersion"));
                ArrayNode transactions = gs.putArray("transactions");
                for (Element transaction : children(group, "transaction")) {
                    ObjectNode st = transactions.addObject();
                    put(st, ST_FIELDS.get(0), transaction.getAttribute("DocType"));
                    put(st, ST_FIELDS.get(1), transaction.getAttribute("Control"));
                    if (!transaction.getAttribute("ImplementationReference").isEmpty()) {
                        put(st, ST_FIELDS.get(2), transaction.getAttribute("ImplementationReference"));
                    }
                    st.set("segments", segmentsFromXml(transaction));
                }
            }
        }
        return out;
    }

    private static ArrayNode segmentsFromXml(Element parent) {
        ArrayNode out = MAPPER.createArrayNode();
        NodeList nodes = parent.getChildNodes();
        for (int n = 0; n < nodes.getLength(); n++) {
            if (!(nodes.item(n) instanceof Element child)) {
                continue;
            }
            if ("loop".equals(child.getTagName())) {
                out.addObject().set(child.getAttribute("Id") + "_loop", segmentsFromXml(child));
            } else if ("segment".equals(child.getTagName())) {
                String id = child.getAttribute("Id");
                ObjectNode segment = out.addObject();
                for (Element element : children(child, "element")) {
                    String key = id + "_" + element.getAttribute("Id").substring(id.length());
                    List<Element> parts = children(element, "subelement");
                    if (parts.isEmpty()) {
                        segment.put(key, element.getTextContent());
                    } else {
                        ObjectNode composite = segment.putObject(key);
                        for (Element part : parts) {
                            composite.put(key + "_" + two(Integer.parseInt(part.getAttribute("Sequence"))),
                                    part.getTextContent());
                        }
                    }
                }
            }
        }
        return out;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int n = 0; n < nodes.getLength(); n++) {
            Node node = nodes.item(n);
            if (node instanceof Element element && tag.equals(element.getTagName())) {
                out.add(element);
            }
        }
        return out;
    }

    private static Element first(List<Element> parents, String tag) {
        if (parents.isEmpty()) {
            return null;
        }
        List<Element> found = children(parents.get(0), tag);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void put(ObjectNode node, String field, String value) {
        node.put(field, value == null ? "" : value);
    }

    // ── Writing X12 ────────────────────────────────────────────────────────

    /** The envelope and formatting an outbound conversion writes with: a partnership's outbound EDI options. */
    record Envelope(JsonNode interchangeHeaders, JsonNode groupHeaders, Delimiters delimiters, String lineTerminator,
                    String wrapBy, int lineLength, String gs05TimeFormat, Long interchangeControlNumber,
                    Long groupControlNumber, Long transactionControlNumber) {
        static Envelope standard() {
            return new Envelope(MAPPER.createObjectNode(), MAPPER.createObjectNode(), Delimiters.standard(), "\n",
                    "SEGMENT", 80, null, null, null, null);
        }

        Envelope withDelimiters(Delimiters d) {
            return new Envelope(interchangeHeaders, groupHeaders, d, lineTerminator, wrapBy, lineLength, gs05TimeFormat,
                    interchangeControlNumber, groupControlNumber, transactionControlNumber);
        }
    }

    /**
     * Writes the JSON representation as X12 of the given transaction set. Counts in SE, GE and IEA are recomputed;
     * the envelope's headers, delimiters, control numbers and wrapping apply over what the document carries.
     */
    static String write(JsonNode document, String transactionSet, String version, Envelope envelope) {
        if (!document.path("interchanges").isArray() || document.path("interchanges").isEmpty()) {
            throw new AwsException("ValidationException", "The input isn't B2BI's JSON representation of X12: it has "
                    + "no interchanges.", 400);
        }
        Delimiters d = envelope.delimiters();
        List<String> segments = new ArrayList<>();
        String number = transactionSet == null ? null : setNumber(transactionSet);
        long interchangeNumber = envelope.interchangeControlNumber() == null ? -1
                : envelope.interchangeControlNumber();
        long groupNumber = envelope.groupControlNumber() == null ? -1 : envelope.groupControlNumber();
        long transactionNumber = envelope.transactionControlNumber() == null ? -1
                : envelope.transactionControlNumber();
        for (JsonNode interchange : document.path("interchanges")) {
            JsonNode headers = envelope.interchangeHeaders();
            String[] isa = new String[15];
            for (int f = 0; f < ISA_FIELDS.size(); f++) {
                isa[f] = interchange.path(ISA_FIELDS.get(f)).asText(defaultIsa(f, version));
            }
            override(isa, 4, headers, "senderIdQualifier");
            override(isa, 5, headers, "senderId");
            override(isa, 6, headers, "receiverIdQualifier");
            override(isa, 7, headers, "receiverId");
            override(isa, 13, headers, "acknowledgmentRequestedCode");
            override(isa, 14, headers, "usageIndicatorCode");
            if (headers.hasNonNull("repetitionSeparator") && version != null && !version.startsWith("VERSION_40")) {
                isa[10] = headers.path("repetitionSeparator").asText();
            }
            if (interchangeNumber >= 0) {
                isa[12] = String.valueOf(interchangeNumber++);
            }
            segments.add(isaSegment(isa, d));
            int groups = 0;
            for (JsonNode group : interchange.path("functional_groups")) {
                groups++;
                String[] gs = new String[8];
                for (int f = 0; f < GS_FIELDS.size(); f++) {
                    gs[f] = group.path(GS_FIELDS.get(f)).asText("");
                }
                override(gs, 1, envelope.groupHeaders(), "applicationSenderCode");
                override(gs, 2, envelope.groupHeaders(), "applicationReceiverCode");
                override(gs, 6, envelope.groupHeaders(), "responsibleAgencyCode");
                if (groupNumber >= 0) {
                    gs[5] = String.valueOf(groupNumber++);
                }
                if (envelope.gs05TimeFormat() != null) {
                    gs[4] = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern(switch (
                            envelope.gs05TimeFormat()) {
                        case "HHMMSS" -> "HHmmss";
                        case "HHMMSSDD" -> "HHmmssSS";
                        default -> "HHmm";
                    }));
                }
                segments.add(join("GS", List.of(gs), d));
                int transactions = 0;
                for (JsonNode transaction : group.path("transactions")) {
                    String type = transaction.path(ST_FIELDS.get(0)).asText();
                    if (number != null && !number.equals(type)) {
                        throw new AwsException("ValidationException", "The input's transaction set " + type
                                + " doesn't match the target " + number + ".", 400);
                    }
                    transactions++;
                    String control = transaction.path(ST_FIELDS.get(1)).asText("0001");
                    if (transactionNumber >= 0) {
                        control = String.format("%04d", transactionNumber++);
                    }
                    List<String> st = new ArrayList<>(List.of(type, control));
                    if (transaction.hasNonNull(ST_FIELDS.get(2))) {
                        st.add(transaction.path(ST_FIELDS.get(2)).asText());
                    }
                    int start = segments.size();
                    segments.add(join("ST", st, d));
                    writeSegments(transaction.path("segments"), segments, d);
                    segments.add(join("SE", List.of(String.valueOf(segments.size() - start + 1), control), d));
                }
                segments.add(join("GE", List.of(String.valueOf(transactions), gs[5]), d));
            }
            segments.add(join("IEA", List.of(String.valueOf(groups), fixed(isa[12], 12)), d));
        }
        return wrap(segments, envelope);
    }

    private static String isaSegment(String[] isa, Delimiters d) {
        StringBuilder out = new StringBuilder("ISA");
        for (int f = 0; f < 15; f++) {
            out.append(d.element()).append(fixed(isa[f], f));
        }
        return out.append(d.element()).append(d.component()).toString();
    }

    private static String defaultIsa(int field, String version) {
        boolean v40 = version == null || version.startsWith("VERSION_40");
        return switch (field) {
            case 0, 2 -> "00";
            case 1, 3 -> " ".repeat(10);
            case 10 -> v40 ? "U" : "^";
            case 11 -> v40 ? "00401" : "00501";
            case 13 -> "0";
            case 14 -> "P";
            default -> "";
        };
    }

    private static void override(String[] fields, int index, JsonNode headers, String name) {
        if (headers != null && headers.hasNonNull(name)) {
            fields[index] = headers.path(name).asText();
        }
    }

    private static String fixed(String value, int field) {
        int width = ISA_WIDTHS[field];
        if (field == 12) {
            String digits = value.trim();
            return digits.length() >= width ? digits.substring(digits.length() - width)
                    : "0".repeat(width - digits.length()) + digits;
        }
        return value.length() >= width ? value.substring(0, width) : value + " ".repeat(width - value.length());
    }

    private static String join(String id, List<String> elements, Delimiters d) {
        List<String> trimmed = new ArrayList<>(elements);
        while (!trimmed.isEmpty() && trimmed.get(trimmed.size() - 1).isEmpty()) {
            trimmed.remove(trimmed.size() - 1);
        }
        StringBuilder out = new StringBuilder(id);
        trimmed.forEach(e -> out.append(d.element()).append(e));
        return out.toString();
    }

    private static void writeSegments(JsonNode list, List<String> segments, Delimiters d) {
        for (JsonNode segment : list) {
            Iterator<Map.Entry<String, JsonNode>> fields = segment.fields();
            if (!fields.hasNext()) {
                continue;
            }
            Map.Entry<String, JsonNode> first = fields.next();
            if (first.getKey().endsWith("_loop")) {
                writeSegments(first.getValue(), segments, d);
                continue;
            }
            int underscore = first.getKey().indexOf('_');
            if (underscore < 1) {
                throw new AwsException("ValidationException", "The member " + first.getKey() + " doesn't name a "
                        + "segment element (SEG_NN).", 400);
            }
            String id = first.getKey().substring(0, underscore);
            TreeMap<Integer, String> elements = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> all = segment.fields();
            while (all.hasNext()) {
                Map.Entry<String, JsonNode> element = all.next();
                int position = position(element.getKey(), id);
                if (element.getValue().isObject()) {
                    TreeMap<Integer, String> parts = new TreeMap<>();
                    element.getValue().fields().forEachRemaining(p -> parts.put(position(p.getKey(),
                            element.getKey()), p.getValue().asText()));
                    StringBuilder composite = new StringBuilder();
                    int last = parts.isEmpty() ? 0 : parts.lastKey();
                    for (int c = 1; c <= last; c++) {
                        if (c > 1) {
                            composite.append(d.component());
                        }
                        composite.append(parts.getOrDefault(c, ""));
                    }
                    elements.put(position, composite.toString());
                } else {
                    elements.put(position, element.getValue().asText());
                }
            }
            List<String> ordered = new ArrayList<>();
            int last = elements.isEmpty() ? 0 : elements.lastKey();
            for (int e = 1; e <= last; e++) {
                ordered.add(elements.getOrDefault(e, ""));
            }
            segments.add(join(id, ordered, d));
        }
    }

    private static int position(String key, String prefix) {
        if (!key.startsWith(prefix + "_")) {
            throw new AwsException("ValidationException", "The member " + key + " doesn't belong to " + prefix
                    + ".", 400);
        }
        String rest = key.substring(prefix.length() + 1);
        int underscore = rest.indexOf('_');
        try {
            return Integer.parseInt(underscore < 0 ? rest : rest.substring(0, underscore));
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", "The member " + key + " doesn't name a position ("
                    + prefix + "_NN).", 400);
        }
    }

    private static String wrap(List<String> segments, Envelope envelope) {
        char terminator = envelope.delimiters().segment();
        String lineTerminator = envelope.lineTerminator();
        StringBuilder out = new StringBuilder();
        switch (envelope.wrapBy()) {
            case "ONE_LINE" -> segments.forEach(s -> out.append(s).append(terminator));
            case "LINE_LENGTH" -> {
                StringBuilder all = new StringBuilder();
                segments.forEach(s -> all.append(s).append(terminator));
                for (int i = 0; i < all.length(); i += envelope.lineLength()) {
                    out.append(all, i, Math.min(all.length(), i + envelope.lineLength())).append(lineTerminator);
                }
            }
            default -> segments.forEach(s -> out.append(s).append(terminator).append(lineTerminator));
        }
        return out.toString();
    }

    // ── Starter templates ──────────────────────────────────────────────────

    /** The service's starter template for a transaction set: its full structure, every value a placeholder. */
    static ObjectNode starter(String transactionSet, String version) {
        JsonNode schema = schema(transactionSet, version).orElseThrow(() -> new AwsException("ValidationException",
                "Floci has no X12 schema for " + transactionSet + " " + version + "; pass outputSampleLocation to "
                        + "build the starter template from a sample document instead.", 400));
        boolean v5010 = version.startsWith("VERSION_5010");
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode interchange = root.putArray("interchanges").addObject();
        String[] isa = {"00", "          ", "00", "          ", "ZZ", "04000          ", "ZZ", "58401          ",
            "090122", "0519", v5010 ? " " : "U", v5010 ? "00501" : "00401", "000015712", "0", "P"};
        for (int f = 0; f < ISA_FIELDS.size(); f++) {
            interchange.put(ISA_FIELDS.get(f), isa[f]);
        }
        ObjectNode group = interchange.putArray("functional_groups").addObject();
        String number = setNumber(transactionSet);
        String[] gs = {functionalGroup(number), "11111", "22222", "20090122", "0519", "000001", "X",
            v5010 ? "005010" : "004010"};
        for (int f = 0; f < GS_FIELDS.size(); f++) {
            group.put(GS_FIELDS.get(f), gs[f]);
        }
        ObjectNode transaction = group.putArray("transactions").addObject();
        transaction.put(ST_FIELDS.get(0), number);
        transaction.put(ST_FIELDS.get(1), "000001");
        transaction.set("segments", placeholders(schema));
        return root;
    }

    private static ArrayNode placeholders(JsonNode body) {
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode node : body) {
            if (node.isObject()) {
                out.addObject().set(node.path("loop").asText() + "_loop", placeholders(node.path("children")));
                continue;
            }
            String id = node.get(0).asText();
            int count = node.get(1).asInt();
            JsonNode composites = node.size() > 3 ? node.get(3) : MAPPER.createObjectNode();
            for (int repeat = 0; repeat < node.get(2).asInt(); repeat++) {
                ObjectNode segment = out.addObject();
                for (int e = 1; e <= count; e++) {
                    String key = id + "_" + two(e);
                    if (composites.has(String.valueOf(e))) {
                        ObjectNode parts = segment.putObject(key);
                        for (int c = 1; c <= composites.path(String.valueOf(e)).asInt(); c++) {
                            parts.put(key + "_" + two(c), "PLACEHOLDER");
                        }
                    } else {
                        segment.put(key, "PLACEHOLDER");
                    }
                }
            }
        }
        return out;
    }

    // ── Acknowledgments ────────────────────────────────────────────────────

    /**
     * A functional acknowledgment of a parsed document: a 997 for 40x0 versions and a 999 for 5010, with AK1 and AK9
     * for the group and, unless {@code withoutTransactionLoop}, AK2 with AK5 (IK5 in a 999) for each transaction.
     */
    static String functionalAck(Parsed parsed, String version, boolean withoutTransactionLoop, long control) {
        boolean v5010 = version.startsWith("VERSION_5010");
        Delimiters d = parsed.delimiters();
        ObjectNode group = parsed.transactions().get(0).group();
        List<String> segments = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        segments.add(isaSegment(replyIsa(parsed, control, now), d));
        segments.add(join("GS", List.of("FA", group.path(GS_FIELDS.get(2)).asText(),
                group.path(GS_FIELDS.get(1)).asText(), now.format(DateTimeFormatter.ofPattern("yyyyMMdd")),
                now.format(DateTimeFormatter.ofPattern("HHmm")), String.valueOf(control), "X",
                group.path(GS_FIELDS.get(7)).asText()), d));
        int start = segments.size();
        segments.add(join("ST", List.of(v5010 ? "999" : "997", "0001"), d));
        segments.add(join("AK1", List.of(group.path(GS_FIELDS.get(0)).asText(),
                group.path(GS_FIELDS.get(5)).asText()), d));
        int accepted = 0;
        for (Transaction transaction : parsed.transactions()) {
            boolean ok = transaction.errors().isEmpty();
            accepted += ok ? 1 : 0;
            if (!withoutTransactionLoop) {
                segments.add(join("AK2", List.of(transaction.transaction().path(ST_FIELDS.get(0)).asText(),
                        transaction.transaction().path(ST_FIELDS.get(1)).asText()), d));
                segments.add(join(v5010 ? "IK5" : "AK5", ok ? List.of("A") : List.of("R", "5"), d));
            }
        }
        int total = parsed.transactions().size();
        String status = accepted == total ? "A" : accepted == 0 ? "R" : "P";
        segments.add(join("AK9", List.of(status, String.valueOf(total), String.valueOf(total),
                String.valueOf(accepted)), d));
        segments.add(join("SE", List.of(String.valueOf(segments.size() - start + 1), "0001"), d));
        segments.add(join("GE", List.of("1", String.valueOf(control)), d));
        segments.add(join("IEA", List.of("1", fixed(String.valueOf(control), 12)), d));
        return wrap(segments, Envelope.standard().withDelimiters(d));
    }

    /** A TA1 interchange acknowledgment of a parsed document's first interchange. */
    static String technicalAck(Parsed parsed, long control) {
        Delimiters d = parsed.delimiters();
        ObjectNode first = parsed.transactions().get(0).interchange();
        List<String> segments = new ArrayList<>();
        segments.add(isaSegment(replyIsa(parsed, control, LocalDateTime.now(ZoneOffset.UTC)), d));
        segments.add(join("TA1", List.of(first.path(ISA_FIELDS.get(12)).asText(),
                first.path(ISA_FIELDS.get(8)).asText(), first.path(ISA_FIELDS.get(9)).asText(), "A", "000"), d));
        segments.add(join("IEA", List.of("0", fixed(String.valueOf(control), 12)), d));
        return wrap(segments, Envelope.standard().withDelimiters(d));
    }

    /** The ISA of a reply: sender and receiver swapped, a new control number, no acknowledgment requested. */
    private static String[] replyIsa(Parsed parsed, long control, LocalDateTime now) {
        ObjectNode first = parsed.transactions().get(0).interchange();
        String[] isa = new String[15];
        for (int f = 0; f < 15; f++) {
            isa[f] = first.path(ISA_FIELDS.get(f)).asText();
        }
        String qualifier = isa[4];
        String sender = isa[5];
        isa[4] = isa[6];
        isa[5] = isa[7];
        isa[6] = qualifier;
        isa[7] = sender;
        isa[8] = now.format(DateTimeFormatter.ofPattern("yyMMdd"));
        isa[9] = now.format(DateTimeFormatter.ofPattern("HHmm"));
        isa[12] = String.valueOf(control);
        isa[13] = "0";
        return isa;
    }
}
