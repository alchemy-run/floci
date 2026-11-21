package io.floci.aws.services.kendra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.PdfText;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The text Kendra indexes from a document of each content type. Office documents are read from their Open XML parts
 * (Word paragraphs, PowerPoint slides in order, Excel sheets as rows), PDFs by {@link PdfText}, HTML and XML without
 * their markup (an HTML document's {@code <title>} is its title), RTF without its control words, JSON as its string
 * values, CSV as rows, and Markdown without its syntax. Tables (CSV and Excel) keep their rows, which Kendra answers
 * table questions from. Legacy binary Office formats are read for their text runs only.
 */
final class KendraText {

    /** What a document says: its title when the format carries one, its text, and its rows when it's a table. */
    record Extracted(String title, String text, List<List<String>> table) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern BLOCK = Pattern.compile(
            "(?i)<\\s*/?\\s*(p|div|br|li|ul|ol|tr|table|h[1-6]|section|article|header|footer|blockquote|pre|dt|dd"
                    + "|title)\\b[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");
    private static final Pattern RTF_DESTINATION = Pattern.compile("\\\\(fonttbl|colortbl|stylesheet|info|pict"
            + "|header|footer|listtable|listoverridetable|rsidtbl|generator|xmlnstbl|themedata|datastore)\\b");
    private static final Map<String, String> ENTITIES = Map.of("amp", "&", "lt", "<", "gt", ">", "quot", "\"",
            "apos", "'", "nbsp", " ", "copy", "\u00a9", "reg", "\u00ae", "mdash", "\u2014", "ndash", "\u2013");

    private KendraText() {
    }

    /** The content type a file name implies, or null when Kendra can't index the file. */
    static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (ext) {
            case "pdf" -> "PDF";
            case "html", "htm" -> "HTML";
            case "doc", "docx" -> "MS_WORD";
            case "txt", "text", "log" -> "PLAIN_TEXT";
            case "ppt", "pptx" -> "PPT";
            case "rtf" -> "RTF";
            case "xml" -> "XML";
            case "xsl", "xslt" -> "XSLT";
            case "xls", "xlsx" -> "MS_EXCEL";
            case "csv" -> "CSV";
            case "json" -> "JSON";
            case "md", "markdown" -> "MD";
            default -> null;
        };
    }

    static Extracted extract(byte[] bytes, String contentType) {
        String type = contentType == null ? sniff(bytes) : contentType;
        return switch (type) {
            case "PDF" -> {
                if (!PdfText.isPdf(bytes)) {
                    throw new IllegalArgumentException("The document is not a valid PDF.");
                }
                yield new Extracted(null, String.join("\n\n", PdfText.pages(bytes)).strip(), null);
            }
            case "HTML" -> html(utf8(bytes));
            case "XML", "XSLT" -> new Extracted(null, collapse(decode(TAG.matcher(BLOCK.matcher(
                    stripCdataAndComments(utf8(bytes))).replaceAll("\n")).replaceAll(" "))), null);
            case "MS_WORD" -> word(bytes);
            case "PPT" -> slides(bytes);
            case "MS_EXCEL" -> excel(bytes);
            case "RTF" -> new Extracted(null, rtf(utf8(bytes)), null);
            case "CSV" -> {
                List<List<String>> rows = csv(utf8(bytes));
                yield new Extracted(null, rowsText(rows), rows);
            }
            case "JSON" -> json(bytes);
            case "MD" -> markdown(utf8(bytes));
            default -> new Extracted(null, utf8(bytes).replace("\r\n", "\n").strip(), null);
        };
    }

    /** The content type of bytes given without one. */
    static String sniff(byte[] bytes) {
        if (PdfText.isPdf(bytes)) {
            return "PDF";
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8).stripLeading()
                .toLowerCase(Locale.ROOT);
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            return "HTML";
        }
        if (head.startsWith("<?xml")) {
            return "XML";
        }
        if (head.startsWith("{\\rtf")) {
            return "RTF";
        }
        return "PLAIN_TEXT";
    }

    private static String utf8(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("\ufeff") ? text.substring(1) : text;
    }

    // ---------------------------------------------------------------- markup

    static Extracted html(String html) {
        String title = null;
        Matcher t = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (t.find()) {
            title = collapse(decode(TAG.matcher(t.group(1)).replaceAll(" ")));
        }
        String body = html.replaceAll("(?is)<(script|style|noscript|template|head)\\b.*?</\\1\\s*>", " ");
        body = stripCdataAndComments(body);
        body = BLOCK.matcher(body).replaceAll("\n");
        body = decode(TAG.matcher(body).replaceAll(" "));
        return new Extracted(title == null || title.isBlank() ? null : title, collapse(body), null);
    }

    /** The {@code href}s of an HTML page's links. */
    static List<String> links(String html) {
        List<String> links = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<a\\b[^>]*?href\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").matcher(html);
        while (m.find()) {
            String href = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            links.add(decode(href.strip()));
        }
        return links;
    }

    private static String stripCdataAndComments(String text) {
        return text.replaceAll("(?s)<!--.*?-->", " ").replaceAll("(?s)<!\\[CDATA\\[(.*?)]]>", "$1");
    }

    static String decode(String text) {
        Matcher m = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value;
            if (name.startsWith("#x") || name.startsWith("#X")) {
                value = codePoint(name.substring(2), 16);
            } else if (name.startsWith("#")) {
                value = codePoint(name.substring(1), 10);
            } else {
                value = ENTITIES.getOrDefault(name.toLowerCase(Locale.ROOT), m.group());
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String codePoint(String digits, int radix) {
        try {
            return new String(Character.toChars(Integer.parseInt(digits, radix)));
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** Runs of spaces as one, at most one blank line between paragraphs, no space at line ends. */
    static String collapse(String text) {
        return text.replace("\r", "").replaceAll("[ \\t\\u00a0]+", " ").replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n").strip();
    }

    private static Extracted markdown(String md) {
        String title = null;
        Matcher heading = Pattern.compile("(?m)^#{1,6}\\s+(.+)$").matcher(md);
        if (heading.find()) {
            title = heading.group(1).strip();
        }
        String text = md.replaceAll("(?m)^```.*$", "")
                .replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("(?m)^\\s*>\\s?", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "")
                .replaceAll("(?m)^\\s*\\d+\\.\\s+", "")
                .replaceAll("(\\*\\*|__|\\*|_|~~|`)", "")
                .replaceAll("(?m)^\\s*[-=*]{3,}\\s*$", "");
        return new Extracted(title, collapse(text), null);
    }

    private static Extracted json(byte[] bytes) {
        JsonNode node;
        try {
            node = JSON.readTree(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("The document is not valid JSON.");
        }
        StringBuilder out = new StringBuilder();
        strings(node, out);
        return new Extracted(null, collapse(out.toString()), null);
    }

    private static void strings(JsonNode node, StringBuilder out) {
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            out.append(node.asText()).append('\n');
        } else {
            node.forEach(child -> strings(child, out));
        }
    }

    // ---------------------------------------------------------------- tables

    static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString().strip());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString().strip());
                cell.setLength(0);
                if (row.stream().anyMatch(v -> !v.isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        row.add(cell.toString().strip());
        if (row.stream().anyMatch(v -> !v.isEmpty())) {
            rows.add(row);
        }
        return rows;
    }

    private static String rowsText(List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        rows.forEach(row -> out.append(String.join(" ", row)).append('\n'));
        return out.toString().strip();
    }

    // ---------------------------------------------------------------- office

    private static Map<String, String> zipParts(byte[] bytes, Pattern which) {
        Map<String, String> parts = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (which.matcher(entry.getName()).matches()) {
                    parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("The document is not a valid Office Open XML file.");
        }
        return parts;
    }

    private static boolean isZip(byte[] bytes) {
        return bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    private static String coreTitle(Map<String, String> parts) {
        String core = parts.get("docProps/core.xml");
        if (core == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?s)<dc:title>(.*?)</dc:title>").matcher(core);
        return m.find() && !m.group(1).isBlank() ? decode(m.group(1).strip()) : null;
    }

    private static Extracted word(byte[] bytes) {
        if (!isZip(bytes)) {
            return new Extracted(null, binaryText(bytes), null);
        }
        Map<String, String> parts = zipParts(bytes, Pattern.compile("word/document\\.xml|docProps/core\\.xml"));
        String doc = parts.get("word/document.xml");
        if (doc == null) {
            throw new IllegalArgumentException("The document has no word/document.xml part.");
        }
        return new Extracted(coreTitle(parts), paragraphs(doc, "w"), null);
    }

    private static String paragraphs(String xml, String ns) {
        StringBuilder out = new StringBuilder();
        Matcher p = Pattern.compile("(?s)<" + ns + ":p[ >].*?</" + ns + ":p>|<" + ns + ":p/>").matcher(xml);
        Pattern run = Pattern.compile("(?s)<" + ns + ":t(?:\\s[^>]*)?>(.*?)</" + ns + ":t>|<" + ns + ":tab/>|<"
                + ns + ":br/>");
        while (p.find()) {
            Matcher r = run.matcher(p.group());
            StringBuilder line = new StringBuilder();
            while (r.find()) {
                line.append(r.group(1) != null ? decode(r.group(1)) : r.group().contains("br") ? "\n" : "\t");
            }
            out.append(line).append('\n');
        }
        return collapse(out.toString());
    }

    private static Extracted slides(byte[] bytes) {
        if (!isZip(bytes)) {
            return new Extracted(null, binaryText(bytes), null);
        }
        Map<String, String> parts = zipParts(bytes, Pattern.compile("ppt/slides/slide\\d+\\.xml|docProps/core\\.xml"));
        TreeMap<Integer, String> ordered = new TreeMap<>();
        parts.forEach((name, xml) -> {
            Matcher n = Pattern.compile("slide(\\d+)\\.xml").matcher(name);
            if (n.find()) {
                ordered.put(Integer.parseInt(n.group(1)), paragraphs(xml, "a"));
            }
        });
        return new Extracted(coreTitle(parts), collapse(String.join("\n\n", ordered.values())), null);
    }

    private static Extracted excel(byte[] bytes) {
        if (!isZip(bytes)) {
            return new Extracted(null, binaryText(bytes), null);
        }
        Map<String, String> parts = zipParts(bytes,
                Pattern.compile("xl/sharedStrings\\.xml|xl/worksheets/sheet\\d+\\.xml|docProps/core\\.xml"));
        List<String> shared = new ArrayList<>();
        String sst = parts.get("xl/sharedStrings.xml");
        if (sst != null) {
            Matcher si = Pattern.compile("(?s)<si>(.*?)</si>").matcher(sst);
            while (si.find()) {
                StringBuilder s = new StringBuilder();
                Matcher t = Pattern.compile("(?s)<t(?:\\s[^>]*)?>(.*?)</t>").matcher(si.group(1));
                while (t.find()) {
                    s.append(decode(t.group(1)));
                }
                shared.add(s.toString());
            }
        }
        List<List<String>> rows = new ArrayList<>();
        TreeMap<Integer, String> sheets = new TreeMap<>();
        parts.forEach((name, xml) -> {
            Matcher n = Pattern.compile("sheet(\\d+)\\.xml").matcher(name);
            if (name.startsWith("xl/worksheets/") && n.find()) {
                sheets.put(Integer.parseInt(n.group(1)), xml);
            }
        });
        Pattern rowPattern = Pattern.compile("(?s)<row[^>]*>(.*?)</row>");
        Pattern cellPattern = Pattern.compile("(?s)<c\\b([^>]*?)(?:/>|>(.*?)</c>)");
        for (String xml : sheets.values()) {
            Matcher r = rowPattern.matcher(xml);
            while (r.find()) {
                List<String> row = new ArrayList<>();
                Matcher c = cellPattern.matcher(r.group(1));
                while (c.find()) {
                    String attrs = c.group(1);
                    String inner = c.group(2) == null ? "" : c.group(2);
                    Matcher ref = Pattern.compile("r=\"([A-Z]+)\\d+\"").matcher(attrs);
                    if (ref.find()) {
                        int column = column(ref.group(1));
                        while (row.size() < column) {
                            row.add("");
                        }
                    }
                    Matcher v = Pattern.compile("(?s)<v>(.*?)</v>").matcher(inner);
                    String value = v.find() ? decode(v.group(1)) : "";
                    if (attrs.contains("t=\"s\"")) {
                        try {
                            value = shared.get(Integer.parseInt(value.strip()));
                        } catch (RuntimeException e) {
                            value = "";
                        }
                    } else if (attrs.contains("t=\"inlineStr\"")) {
                        Matcher t = Pattern.compile("(?s)<t(?:\\s[^>]*)?>(.*?)</t>").matcher(inner);
                        value = t.find() ? decode(t.group(1)) : "";
                    }
                    row.add(value);
                }
                if (row.stream().anyMatch(cell -> !cell.isBlank())) {
                    rows.add(row);
                }
            }
        }
        return new Extracted(coreTitle(parts), rowsText(rows), rows);
    }

    private static int column(String letters) {
        int column = 0;
        for (char ch : letters.toCharArray()) {
            column = column * 26 + (ch - 'A' + 1);
        }
        return column - 1;
    }

    /** The readable text runs of a legacy binary document: UTF-16LE runs and ASCII runs of four or more. */
    private static String binaryText(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i + 1 < bytes.length; i += 2) {
            char c = (char) ((bytes[i] & 0xff) | (bytes[i + 1] & 0xff) << 8);
            if (c >= 0x20 && c < 0xfffe && !Character.isISOControl(c) && Character.isDefined(c)
                    && (Character.isLetterOrDigit(c) || " .,;:!?'\"()-/&%$".indexOf(c) >= 0)) {
                run.append(c);
            } else {
                if (run.length() >= 4) {
                    out.append(run).append('\n');
                }
                run.setLength(0);
            }
        }
        if (run.length() >= 4) {
            out.append(run);
        }
        if (out.isEmpty()) {
            Matcher ascii = Pattern.compile("[\\x20-\\x7e]{4,}")
                    .matcher(new String(bytes, StandardCharsets.ISO_8859_1));
            while (ascii.find()) {
                out.append(ascii.group()).append('\n');
            }
        }
        return collapse(out.toString());
    }

    // ---------------------------------------------------------------- rtf

    static String rtf(String rtf) {
        if (!rtf.stripLeading().startsWith("{\\rtf")) {
            throw new IllegalArgumentException("The document is not valid RTF.");
        }
        StringBuilder out = new StringBuilder();
        List<Boolean> skipStack = new ArrayList<>();
        boolean skip = false;
        int i = 0;
        while (i < rtf.length()) {
            char c = rtf.charAt(i);
            if (c == '{') {
                skipStack.add(skip);
                i++;
                if (rtf.startsWith("\\*", i)) {
                    skip = true;
                } else {
                    Matcher dest = RTF_DESTINATION.matcher(rtf).region(i, rtf.length());
                    if (dest.lookingAt()) {
                        skip = true;
                    }
                }
            } else if (c == '}') {
                skip = skipStack.isEmpty() ? false : skipStack.remove(skipStack.size() - 1);
                i++;
            } else if (c == '\\') {
                if (i + 1 >= rtf.length()) {
                    break;
                }
                char next = rtf.charAt(i + 1);
                if (next == '\\' || next == '{' || next == '}') {
                    if (!skip) {
                        out.append(next);
                    }
                    i += 2;
                } else if (next == '\'') {
                    if (!skip && i + 4 <= rtf.length()) {
                        try {
                            out.append((char) Integer.parseInt(rtf.substring(i + 2, i + 4), 16));
                        } catch (NumberFormatException e) {
                            // Not a hex escape.
                        }
                    }
                    i += 4;
                } else if (Character.isLetter(next)) {
                    int j = i + 1;
                    while (j < rtf.length() && Character.isLetter(rtf.charAt(j))) {
                        j++;
                    }
                    String word = rtf.substring(i + 1, j);
                    int k = j;
                    if (k < rtf.length() && (rtf.charAt(k) == '-' || Character.isDigit(rtf.charAt(k)))) {
                        k++;
                        while (k < rtf.length() && Character.isDigit(rtf.charAt(k))) {
                            k++;
                        }
                    }
                    String param = rtf.substring(j, k);
                    if (k < rtf.length() && rtf.charAt(k) == ' ') {
                        k++;
                    }
                    if (!skip) {
                        switch (word) {
                            case "par", "line", "sect", "page", "row" -> out.append('\n');
                            case "tab", "cell" -> out.append('\t');
                            case "u" -> {
                                try {
                                    int code = Integer.parseInt(param);
                                    out.append((char) (code < 0 ? code + 65536 : code));
                                    if (k < rtf.length() && rtf.charAt(k) != '\\' && rtf.charAt(k) != '{'
                                            && rtf.charAt(k) != '}') {
                                        k++;
                                    }
                                } catch (NumberFormatException e) {
                                    // A u control word without a code.
                                }
                            }
                            default -> {
                            }
                        }
                    }
                    i = k;
                } else {
                    i += 2;
                }
            } else {
                if (!skip && c != '\r' && c != '\n') {
                    out.append(c);
                }
                i++;
            }
        }
        return collapse(out.toString());
    }
}
