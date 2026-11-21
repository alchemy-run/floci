package io.floci.aws.services.bedrockdataautomation;

import io.floci.aws.core.common.PdfText;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * What Bedrock Data Automation reads from an asset: its modality, from its bytes and name, and, for documents, the
 * text of each page. Text documents (plain text, Markdown, CSV, HTML) are one page; PDFs are read page by page by
 * {@link PdfText}.
 */
final class BdaDocuments {

    private BdaDocuments() {
    }

    /** {@code DOCUMENT}, {@code IMAGE}, {@code AUDIO} or {@code VIDEO}; null when the asset is none of them. */
    static String modality(byte[] bytes, String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (starts(bytes, "%PDF") || lower.matches(".*\\.(pdf|txt|md|csv|html?|json|xml|docx?)$")) {
            return "DOCUMENT";
        }
        if (starts(bytes, "\u0089PNG") || bytes.length > 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                || lower.matches(".*\\.(png|jpe?g|gif|webp|tiff?|bmp)$")) {
            return "IMAGE";
        }
        if (starts(bytes, "ID3") || starts(bytes, "RIFF") && contains(bytes, "WAVE") || starts(bytes, "fLaC")
                || starts(bytes, "OggS") || lower.matches(".*\\.(mp3|wav|flac|ogg|m4a|amr|webm)$")) {
            return lower.endsWith(".webm") ? "VIDEO" : "AUDIO";
        }
        if (contains(bytes, "ftyp") || lower.matches(".*\\.(mp4|mov|mkv|avi)$")) {
            return "VIDEO";
        }
        return isText(bytes) ? "DOCUMENT" : null;
    }

    /** The text of each page of a document. */
    static List<String> pages(byte[] bytes) {
        if (!starts(bytes, "%PDF")) {
            return List.of(new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").strip());
        }
        return PdfText.pages(bytes);
    }

    private static boolean starts(byte[] bytes, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (bytes[i] != p[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(byte[] bytes, String marker) {
        return new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.ISO_8859_1).contains(marker);
    }

    private static boolean isText(byte[] bytes) {
        int sample = Math.min(bytes.length, 4096);
        for (int i = 0; i < sample; i++) {
            int b = bytes[i] & 0xff;
            if (b < 0x09 || b > 0x0d && b < 0x20 && b != 0x1b) {
                return false;
            }
        }
        return sample > 0;
    }
}
