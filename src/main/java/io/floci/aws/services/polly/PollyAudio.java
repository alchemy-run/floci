package io.floci.aws.services.polly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The audio and speech marks Floci returns for synthesized text. The audio is well-formed silence of
 * the length the text would take to speak (about 65 ms per character): MPEG Layer III frames for
 * {@code mp3}, 16-bit little-endian samples for {@code pcm}, 8-bit companded samples for
 * {@code mulaw} and {@code alaw}, and chained Ogg streams for {@code ogg_vorbis} and
 * {@code ogg_opus}. Speech marks time each word, sentence and viseme across that length.
 */
final class PollyAudio {
    static final int MILLIS_PER_CHARACTER = 65;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}'’-]+");
    private static final Pattern SENTENCE = Pattern.compile("[^.!?]+[.!?]*");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MARK = Pattern.compile("<mark\\s+name\\s*=\\s*\"([^\"]*)\"\\s*/>");
    private static final byte[] VORBIS = resource("polly/silence-22050.ogg");
    private static final byte[] OPUS = resource("polly/silence-48000.opus");

    private PollyAudio() {
    }

    static String contentType(String format) {
        return switch (format) {
            case "mp3" -> "audio/mpeg";
            case "ogg_vorbis", "ogg_opus" -> "audio/ogg";
            case "pcm" -> "audio/pcm";
            case "mulaw" -> "audio/mulaw";
            case "alaw" -> "audio/alaw";
            default -> "application/x-json-stream";
        };
    }

    static String extension(String format) {
        return switch (format) {
            case "mp3" -> "mp3";
            case "ogg_vorbis", "ogg_opus" -> "ogg";
            case "pcm", "mulaw", "alaw" -> "pcm";
            default -> "marks";
        };
    }

    static long durationMillis(String spokenText) {
        return Math.max(300, (long) spokenText.strip().length() * MILLIS_PER_CHARACTER);
    }

    /** Silence of {@code millis} in {@code format} at {@code sampleRate}. */
    static byte[] audio(String format, int sampleRate, long millis) {
        return switch (format) {
            case "mp3" -> mp3(sampleRate, millis);
            case "pcm" -> new byte[(int) (sampleRate * millis / 1000) * 2];
            case "mulaw" -> filled(sampleRate, millis, (byte) 0xFF);
            case "alaw" -> filled(sampleRate, millis, (byte) 0xD5);
            case "ogg_vorbis" -> chained(VORBIS, millis);
            case "ogg_opus" -> chained(OPUS, millis);
            default -> throw new IllegalArgumentException(format);
        };
    }

    private static byte[] filled(int sampleRate, long millis, byte value) {
        byte[] samples = new byte[(int) (sampleRate * millis / 1000)];
        Arrays.fill(samples, value);
        return samples;
    }

    /**
     * Mono MPEG-2 (16, 22.05, 24 kHz) or MPEG-2.5 (8 kHz) Layer III frames at 32 or 64 kbit/s whose
     * side information is all zero, which every decoder plays as silence. Padding bits are spread so
     * the stream keeps its exact bit rate.
     */
    private static byte[] mp3(int sampleRate, long millis) {
        boolean lowRate = sampleRate == 8000;
        int bitrate = lowRate ? 32_000 : 64_000;
        int versionBits = lowRate ? 0b00 : 0b10;
        int rateIndex = switch (sampleRate) {
            case 22050 -> 0b00;
            case 24000 -> 0b01;
            default -> 0b10;
        };
        int bitrateIndex = lowRate ? 4 : 8;
        int samplesPerFrame = 576;
        long frames = Math.max(1, (long) Math.ceil(millis / 1000.0 * sampleRate / samplesPerFrame));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        double exact = 72.0 * bitrate / sampleRate;
        double carried = 0;
        for (long i = 0; i < frames; i++) {
            carried += exact - Math.floor(exact);
            boolean padding = carried >= 1;
            if (padding) {
                carried -= 1;
            }
            int length = (int) Math.floor(exact) + (padding ? 1 : 0);
            byte[] frame = new byte[length];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) (0xE0 | (versionBits << 3) | (0b01 << 1) | 1);
            frame[2] = (byte) ((bitrateIndex << 4) | (rateIndex << 2) | (padding ? 0b10 : 0));
            frame[3] = (byte) 0xC0;
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    /**
     * Enough one-second copies of {@code clip} to cover {@code millis}, chained as RFC 3533 allows:
     * each copy is its own logical stream, so its pages get a fresh serial number and CRC.
     */
    private static byte[] chained(byte[] clip, long millis) {
        int copies = (int) Math.max(1, Math.ceil(millis / 1000.0));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int copy = 0; copy < copies; copy++) {
            byte[] pages = clip.clone();
            int offset = 0;
            while (offset + 27 <= pages.length && pages[offset] == 'O' && pages[offset + 1] == 'g') {
                int segments = pages[offset + 26] & 0xff;
                int length = 27 + segments;
                for (int s = 0; s < segments; s++) {
                    length += pages[offset + 27 + s] & 0xff;
                }
                int serial = 0x464C4F00 + copy;
                pages[offset + 14] = (byte) serial;
                pages[offset + 15] = (byte) (serial >>> 8);
                pages[offset + 16] = (byte) (serial >>> 16);
                pages[offset + 17] = (byte) (serial >>> 24);
                Arrays.fill(pages, offset + 22, offset + 26, (byte) 0);
                int crc = oggCrc(pages, offset, length);
                pages[offset + 22] = (byte) crc;
                pages[offset + 23] = (byte) (crc >>> 8);
                pages[offset + 24] = (byte) (crc >>> 16);
                pages[offset + 25] = (byte) (crc >>> 24);
                offset += length;
            }
            out.writeBytes(pages);
        }
        return out.toByteArray();
    }

    private static final int[] OGG_CRC = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            }
            OGG_CRC[i] = r;
        }
    }

    private static int oggCrc(byte[] data, int offset, int length) {
        int crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc = (crc << 8) ^ OGG_CRC[((crc >>> 24) & 0xff) ^ (data[i] & 0xff)];
        }
        return crc;
    }

    /**
     * Speech marks as JSON lines. Word and sentence marks carry the byte offsets of their text in the
     * request and a time proportional to where it falls in the spoken text; viseme marks follow each
     * word's first sound; ssml marks report each {@code <mark name="…"/>}.
     */
    static String speechMarks(String text, Set<String> types, ObjectMapper mapper) {
        List<ObjectNode> marks = new ArrayList<>();
        if (types.contains("sentence")) {
            Matcher sentences = SENTENCE.matcher(text);
            while (sentences.find()) {
                String sentence = sentences.group().strip();
                if (!sentence.isEmpty() && !sentence.startsWith("<")) {
                    int start = sentences.start() + sentences.group().indexOf(sentence);
                    marks.add(mark(mapper, text, start, start + sentence.length(), "sentence", sentence));
                }
            }
        }
        String plain = TAG.matcher(text).replaceAll(m -> " ".repeat(m.group().length()));
        Matcher words = WORD.matcher(plain);
        while (words.find()) {
            if (types.contains("word")) {
                marks.add(mark(mapper, text, words.start(), words.end(), "word", words.group()));
            }
            if (types.contains("viseme")) {
                ObjectNode viseme = mapper.createObjectNode();
                viseme.put("time", timeAt(words.start()));
                viseme.put("type", "viseme");
                viseme.put("value", viseme(words.group()));
                marks.add(viseme);
            }
        }
        if (types.contains("ssml")) {
            Matcher ssml = MARK.matcher(text);
            while (ssml.find()) {
                marks.add(mark(mapper, text, ssml.start(), ssml.end(), "ssml", ssml.group(1)));
            }
        }
        marks.sort((a, b) -> Long.compare(a.path("time").asLong(), b.path("time").asLong()));
        StringBuilder out = new StringBuilder();
        for (ObjectNode mark : marks) {
            out.append(mark).append('\n');
        }
        return out.toString();
    }

    private static ObjectNode mark(ObjectMapper mapper, String text, int start, int end, String type, String value) {
        ObjectNode mark = mapper.createObjectNode();
        mark.put("time", timeAt(start));
        mark.put("type", type);
        mark.put("start", text.substring(0, start).getBytes(StandardCharsets.UTF_8).length);
        mark.put("end", text.substring(0, end).getBytes(StandardCharsets.UTF_8).length);
        mark.put("value", value);
        return mark;
    }

    private static long timeAt(int offset) {
        return (long) offset * MILLIS_PER_CHARACTER;
    }

    private static String viseme(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.startsWith("th")) {
            return "T";
        }
        if (w.startsWith("sh") || w.startsWith("ch")) {
            return "S";
        }
        return switch (w.charAt(0)) {
            case 'p', 'b', 'm' -> "p";
            case 'f', 'v' -> "f";
            case 't', 'd', 'n', 'l' -> "t";
            case 's', 'z' -> "s";
            case 'k', 'g', 'c', 'q', 'x' -> "k";
            case 'r' -> "r";
            case 'a' -> "a";
            case 'e' -> "e";
            case 'i', 'y' -> "i";
            case 'o' -> "o";
            case 'u', 'w' -> "u";
            default -> "@";
        };
    }

    private static byte[] resource(String name) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing Polly audio resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
