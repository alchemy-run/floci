package io.floci.aws.services.mailmanager;

import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.TextBody;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.stream.Field;
import org.apache.james.mime4j.stream.MimeConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a received message: its headers, addresses, text and HTML bodies and whether it has attachments. */
final class MailManagerMime {

    private static final Pattern ANGLE = Pattern.compile("<([^>]+)>");

    private MailManagerMime() {
    }

    /** A parsed message. Header names are matched case-insensitively. */
    record Parsed(Map<String, List<String>> headers, String text, String html, boolean attachments,
                  boolean malformed) {
        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        List<String> all(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }

        /** The addresses a header lists, split on commas outside quotes. */
        List<String> addresses(String name) {
            List<String> addresses = new ArrayList<>();
            for (String value : all(name)) {
                StringBuilder current = new StringBuilder();
                boolean quoted = false;
                for (char c : value.toCharArray()) {
                    if (c == '"') {
                        quoted = !quoted;
                    }
                    if (c == ',' && !quoted) {
                        if (!current.toString().isBlank()) {
                            addresses.add(current.toString().trim());
                        }
                        current.setLength(0);
                    } else {
                        current.append(c);
                    }
                }
                if (!current.toString().isBlank()) {
                    addresses.add(current.toString().trim());
                }
            }
            return addresses;
        }
    }

    /** The address in {@code Name <address>}, or the value itself. */
    static String bare(String value) {
        Matcher matcher = ANGLE.matcher(value);
        return (matcher.find() ? matcher.group(1) : value).trim().toLowerCase(Locale.ROOT);
    }

    static Parsed parse(byte[] raw) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        try {
            DefaultMessageBuilder builder = new DefaultMessageBuilder();
            builder.setMimeEntityConfig(MimeConfig.PERMISSIVE);
            Message message = builder.parseMessage(new ByteArrayInputStream(raw));
            for (Field field : message.getHeader().getFields()) {
                headers.computeIfAbsent(field.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(field.getBody() == null ? "" : field.getBody().trim());
            }
            StringBuilder text = new StringBuilder();
            StringBuilder html = new StringBuilder();
            boolean[] attachments = {false};
            walk(message, text, html, attachments);
            boolean malformed = headers.isEmpty();
            return new Parsed(headers, text.isEmpty() ? null : text.toString(), html.isEmpty() ? null
                    : html.toString(), attachments[0], malformed);
        } catch (IOException | RuntimeException e) {
            return new Parsed(headers, new String(raw, StandardCharsets.UTF_8), null, false, true);
        }
    }

    private static void walk(Entity entity, StringBuilder text, StringBuilder html, boolean[] attachments)
            throws IOException {
        Body body = entity.getBody();
        if (body instanceof Multipart multipart) {
            for (Entity part : multipart.getBodyParts()) {
                walk(part, text, html, attachments);
            }
            return;
        }
        if (body instanceof Message nested) {
            walk(nested, text, html, attachments);
            return;
        }
        String disposition = entity.getDispositionType();
        if ("attachment".equalsIgnoreCase(disposition) || entity.getFilename() != null) {
            attachments[0] = true;
            return;
        }
        if (body instanceof TextBody textBody) {
            String content;
            try (Reader reader = textBody.getReader()) {
                StringBuilder out = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) > 0) {
                    out.append(buffer, 0, read);
                }
                content = out.toString();
            }
            if ("text/html".equalsIgnoreCase(entity.getMimeType())) {
                html.append(content);
            } else if (entity.getMimeType() == null || entity.getMimeType().toLowerCase(Locale.ROOT)
                    .startsWith("text/")) {
                text.append(content);
            }
        } else {
            attachments[0] = true;
        }
    }

    /** The message with a header added at the top. */
    static byte[] addHeader(byte[] raw, String name, String value) {
        byte[] header = (name + ": " + value + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[header.length + raw.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(raw, 0, out, header.length, raw.length);
        return out;
    }
}
