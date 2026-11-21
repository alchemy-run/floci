package io.floci.aws.core.common;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The text of a PDF, page by page, read from its content streams (uncompressed or {@code FlateDecode}): the strings
 * their {@code Tj}, {@code TJ}, {@code '} and {@code "} operators show, with a line break at each {@code Td},
 * {@code TD}, {@code T*} or {@code Tm} that moves down. Text a PDF draws only as an image, or in a font with a custom
 * encoding, isn't read: Floci has no OCR.
 */
public final class PdfText {
    private static final Pattern PAGE = Pattern.compile("/Type\\s*/Page(?![a-zA-Z])");
    private static final Pattern CONTENTS_REF = Pattern.compile("/Contents\\s*(\\[[^\\]]*]|\\d+\\s+\\d+\\s+R)");
    private static final Pattern REF = Pattern.compile("(\\d+)\\s+(\\d+)\\s+R");
    private static final Pattern OBJECT = Pattern.compile("(\\d+)\\s+(\\d+)\\s+obj\\b");

    private PdfText() {
    }

    /** Whether the bytes are a PDF. */
    public static boolean isPdf(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    /** The text of each page of a PDF; one empty page when none is found. */
    public static List<String> pages(byte[] bytes) {
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        List<String> pages = new ArrayList<>();
        Matcher objects = OBJECT.matcher(raw);
        List<int[]> spans = new ArrayList<>();
        while (objects.find()) {
            int end = raw.indexOf("endobj", objects.end());
            spans.add(new int[] {Integer.parseInt(objects.group(1)), objects.end(), end < 0 ? raw.length() : end});
        }
        for (int[] span : spans) {
            String body = raw.substring(span[1], span[2]);
            String dictionary = dictionaryOf(body);
            if (!PAGE.matcher(dictionary).find()) {
                continue;
            }
            Matcher contents = CONTENTS_REF.matcher(dictionary);
            StringBuilder text = new StringBuilder();
            if (contents.find()) {
                Matcher refs = REF.matcher(contents.group(1));
                while (refs.find()) {
                    int number = Integer.parseInt(refs.group(1));
                    for (int[] candidate : spans) {
                        if (candidate[0] == number) {
                            text.append(showText(streamOf(raw, candidate, bytes)));
                        }
                    }
                }
            }
            pages.add(text.toString().strip());
        }
        if (pages.isEmpty()) {
            pages.add("");
        }
        return pages;
    }

    private static String dictionaryOf(String body) {
        int stream = body.indexOf("stream");
        return stream < 0 ? body : body.substring(0, stream);
    }

    /** The decoded content of a stream object. */
    private static String streamOf(String raw, int[] span, byte[] bytes) {
        String body = raw.substring(span[1], span[2]);
        int start = body.indexOf("stream");
        int end = body.lastIndexOf("endstream");
        if (start < 0 || end < start) {
            return "";
        }
        int offset = span[1] + start + "stream".length();
        if (offset < raw.length() && raw.charAt(offset) == '\r') {
            offset++;
        }
        if (offset < raw.length() && raw.charAt(offset) == '\n') {
            offset++;
        }
        int stop = span[1] + end;
        byte[] data = new byte[Math.max(0, stop - offset)];
        System.arraycopy(bytes, offset, data, 0, data.length);
        if (body.substring(0, start).contains("/FlateDecode")) {
            data = inflate(data);
        }
        return new String(data, StandardCharsets.ISO_8859_1);
    }

    private static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, read);
            }
        } catch (DataFormatException e) {
            return out.toByteArray();
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /** The text a content stream shows, a line per downward move. */
    public static String showText(String content) {
        StringBuilder out = new StringBuilder();
        List<String> operands = new ArrayList<>();
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '(') {
                int[] end = new int[1];
                operands.add(literal(content, i, end));
                i = end[0];
            } else if (c == '<' && i + 1 < content.length() && content.charAt(i + 1) != '<') {
                int close = content.indexOf('>', i);
                operands.add(hex(content.substring(i + 1, close < 0 ? content.length() : close)));
                i = close < 0 ? content.length() : close + 1;
            } else if (c == '[') {
                int close = matchingBracket(content, i);
                StringBuilder joined = new StringBuilder();
                String inner = content.substring(i + 1, close);
                int j = 0;
                while (j < inner.length()) {
                    char d = inner.charAt(j);
                    if (d == '(') {
                        int[] end = new int[1];
                        joined.append(literal(inner, j, end));
                        j = end[0];
                    } else if (d == '<') {
                        int e = inner.indexOf('>', j);
                        joined.append(hex(inner.substring(j + 1, e < 0 ? inner.length() : e)));
                        j = e < 0 ? inner.length() : e + 1;
                    } else if (d == '-' || Character.isDigit(d) || d == '.') {
                        int k = j;
                        while (k < inner.length() && (inner.charAt(k) == '-' || inner.charAt(k) == '.'
                                || Character.isDigit(inner.charAt(k)))) {
                            k++;
                        }
                        try {
                            if (Double.parseDouble(inner.substring(j, k)) < -200) {
                                joined.append(' ');
                            }
                        } catch (NumberFormatException e) {
                            // Not a kerning adjustment.
                        }
                        j = Math.max(k, j + 1);
                    } else {
                        j++;
                    }
                }
                operands.add(joined.toString());
                i = close + 1;
            } else if (Character.isLetter(c) || c == '\'' || c == '"' || c == '*') {
                int k = i;
                while (k < content.length() && (Character.isLetter(content.charAt(k)) || content.charAt(k) == '*'
                        || content.charAt(k) == '\'' || content.charAt(k) == '"')) {
                    k++;
                }
                String operator = content.substring(i, k);
                switch (operator) {
                    case "Tj", "TJ" -> operands.stream().reduce((a, b) -> b).ifPresent(out::append);
                    case "'", "\"" -> {
                        newline(out);
                        operands.stream().reduce((a, b) -> b).ifPresent(out::append);
                    }
                    case "Td", "TD", "Tm" -> {
                        if (!operandsMoveSideways(content, i)) {
                            newline(out);
                        }
                    }
                    case "T*", "ET" -> newline(out);
                    default -> {
                    }
                }
                operands.clear();
                i = k;
            } else {
                i++;
            }
        }
        return out.toString().replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n");
    }

    /** Whether a {@code Td}-style move keeps the line (zero vertical offset), so it isn't a new line. */
    private static boolean operandsMoveSideways(String content, int operatorAt) {
        int start = Math.max(0, operatorAt - 40);
        String[] tokens = content.substring(start, operatorAt).trim().split("\\s+");
        if (tokens.length < 2) {
            return false;
        }
        try {
            return Double.parseDouble(tokens[tokens.length - 1]) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void newline(StringBuilder out) {
        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static int matchingBracket(String content, int open) {
        int depth = 0;
        for (int i = open; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '(') {
                int[] end = new int[1];
                literal(content, i, end);
                i = end[0] - 1;
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return i;
            }
        }
        return content.length() - 1;
    }

    /** A literal string starting at {@code open}, with escapes and balanced parentheses; sets its end. */
    private static String literal(String content, int open, int[] end) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        int i = open;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '\\' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b', 'f' -> {
                    }
                    case '(', ')', '\\' -> out.append(next);
                    default -> {
                        if (next >= '0' && next <= '7') {
                            int k = i + 1;
                            int value = 0;
                            while (k < content.length() && k < i + 4 && content.charAt(k) >= '0'
                                    && content.charAt(k) <= '7') {
                                value = value * 8 + (content.charAt(k) - '0');
                                k++;
                            }
                            out.append((char) value);
                            i = k;
                            continue;
                        }
                    }
                }
                i += 2;
                continue;
            }
            if (c == '(') {
                if (depth > 0) {
                    out.append(c);
                }
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    end[0] = i + 1;
                    return out.toString();
                }
                out.append(c);
            } else {
                out.append(c);
            }
            i++;
        }
        end[0] = content.length();
        return out.toString();
    }

    private static String hex(String digits) {
        String clean = digits.replaceAll("\\s", "");
        if (clean.length() % 2 == 1) {
            clean = clean + "0";
        }
        StringBuilder out = new StringBuilder();
        boolean twoByte = clean.length() >= 4 && clean.startsWith("00");
        for (int i = 0; i + 1 < clean.length(); i += twoByte ? 4 : 2) {
            try {
                int value = Integer.parseInt(clean.substring(i, Math.min(clean.length(), i + (twoByte ? 4 : 2))), 16);
                out.append((char) value);
            } catch (NumberFormatException e) {
                return out.toString();
            }
        }
        return out.toString();
    }
}
