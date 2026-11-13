package io.floci.aws.services.medicalimaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * DICOM Part 10 files and data sets as HealthImaging imports them: the file meta information, then the data set in its
 * transfer syntax (implicit or explicit VR, little or big endian, deflated), with nested sequences and native or
 * encapsulated pixel data. Attributes are named by the DICOM data dictionary ({@code dicom-dictionary.tsv}), which also
 * gives implicit VR elements their VR.
 */
final class MedicalImagingDicom {

    static final String IMPLICIT_LE = "1.2.840.10008.1.2";
    static final String EXPLICIT_LE = "1.2.840.10008.1.2.1";
    static final String DEFLATED = "1.2.840.10008.1.2.1.99";
    static final String EXPLICIT_BE = "1.2.840.10008.1.2.2";
    static final String RLE = "1.2.840.10008.1.2.5";
    static final int PIXEL_DATA = 0x7FE00010;
    static final int ITEM = 0xFFFEE000;
    static final int ITEM_END = 0xFFFEE00D;
    static final int SEQUENCE_END = 0xFFFEE0DD;
    private static final long UNDEFINED = 0xFFFFFFFFL;
    private static final Set<String> LONG_VRS = Set.of("OB", "OD", "OF", "OL", "OV", "OW", "SQ", "SV", "UC", "UN", "UR",
            "UT", "UV");
    private static final Set<String> BINARY_VRS = Set.of("OB", "OD", "OF", "OL", "OV", "OW", "UN");

    private MedicalImagingDicom() {
    }

    /** A file that isn't DICOM, or a data set that can't be read. */
    static final class DicomException extends Exception {
        DicomException(String message) {
            super(message);
        }
    }

    record Entry(int tag, String vr, String vm, String keyword) {
    }

    /** The DICOM data dictionary: each attribute's VR and keyword, by tag. */
    static final class Dictionary {
        private static volatile Dictionary instance;
        final Map<Integer, Entry> byTag = new HashMap<>();
        final Map<String, Entry> byKeyword = new HashMap<>();

        static Dictionary get() {
            Dictionary loaded = instance;
            if (loaded == null) {
                synchronized (Dictionary.class) {
                    if (instance == null) {
                        instance = load();
                    }
                    loaded = instance;
                }
            }
            return loaded;
        }

        private static Dictionary load() {
            Dictionary dictionary = new Dictionary();
            try (InputStream in = MedicalImagingDicom.class.getClassLoader().getResourceAsStream(
                    "aws/medical-imaging/dicom-dictionary.tsv")) {
                if (in == null) {
                    throw new IllegalStateException("The DICOM dictionary is missing.");
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#") || line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split("\t");
                    Entry entry = new Entry(Integer.parseUnsignedInt(parts[0], 16), parts[1], parts[2], parts[3]);
                    dictionary.byTag.put(entry.tag(), entry);
                    dictionary.byKeyword.put(entry.keyword(), entry);
                }
            } catch (IOException e) {
                throw new IllegalStateException("The DICOM dictionary couldn't be read.", e);
            }
            return dictionary;
        }

        String vr(int tag) {
            if ((tag & 0xFFFF) == 0) {
                return "UL";
            }
            Entry entry = byTag.get(tag);
            return entry == null ? "UN" : entry.vr();
        }

        String keyword(int tag) {
            Entry entry = byTag.get(tag);
            return entry == null ? null : entry.keyword();
        }

        Integer tag(String keyword) {
            Entry entry = byKeyword.get(keyword);
            return entry == null ? null : entry.tag();
        }
    }

    /** A data element: its VR and value, its items when a sequence, or its fragments when encapsulated. */
    static final class Element {
        final int tag;
        final String vr;
        final byte[] value;
        final List<Map<Integer, Element>> items;
        final List<byte[]> fragments;
        final List<Long> offsets;

        Element(int tag, String vr, byte[] value, List<Map<Integer, Element>> items, List<byte[]> fragments,
                List<Long> offsets) {
            this.tag = tag;
            this.vr = vr;
            this.value = value;
            this.items = items;
            this.fragments = fragments;
            this.offsets = offsets;
        }
    }

    /** A parsed file: its transfer syntax, byte order, file meta information and data set. */
    static final class Parsed {
        final String transferSyntax;
        final boolean bigEndian;
        final Map<Integer, Element> meta;
        final Map<Integer, Element> dataset;

        Parsed(String transferSyntax, boolean bigEndian, Map<Integer, Element> meta, Map<Integer, Element> dataset) {
            this.transferSyntax = transferSyntax;
            this.bigEndian = bigEndian;
            this.meta = meta;
            this.dataset = dataset;
        }

        Charset charset() {
            String set = MedicalImagingDicom.text(dataset, 0x00080005);
            return set != null && set.contains("ISO_IR 192") ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        }

        String text(int tag) {
            return MedicalImagingDicom.text(dataset, tag, charset());
        }

        Integer integer(int tag) {
            Element element = dataset.get(tag);
            if (element == null || element.value == null || element.value.length == 0) {
                return null;
            }
            Object value = MedicalImagingDicom.value(element, bigEndian, charset());
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number number) {
                return number.intValue();
            }
            try {
                String text = String.valueOf(value).split("\\\\")[0].trim();
                return text.isEmpty() ? null : (int) Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    static String text(Map<Integer, Element> dataset, int tag) {
        return text(dataset, tag, StandardCharsets.ISO_8859_1);
    }

    static String text(Map<Integer, Element> dataset, int tag, Charset charset) {
        Element element = dataset.get(tag);
        if (element == null || element.value == null) {
            return null;
        }
        return trim(new String(element.value, charset));
    }

    private static String trim(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\0')) {
            end--;
        }
        int start = 0;
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        return value.substring(start, end);
    }

    // ================================================================ reading

    static Parsed parse(byte[] file) throws DicomException {
        Map<Integer, Element> meta = new TreeMap<>();
        int position;
        String syntax;
        if (file.length >= 132 && file[128] == 'D' && file[129] == 'I' && file[130] == 'C' && file[131] == 'M') {
            Reader reader = new Reader(file, 132, file.length, true, false);
            while (reader.position + 4 <= reader.end && reader.peekGroup() == 0x0002) {
                Element element = reader.element();
                meta.put(element.tag, element);
            }
            position = reader.position;
            syntax = text(meta, 0x00020010);
            if (syntax == null || syntax.isEmpty()) {
                throw new DicomException("The file meta information has no transfer syntax.");
            }
        } else {
            if (file.length < 8) {
                throw new DicomException("The file is too short to be DICOM.");
            }
            int group = (file[0] & 0xFF) | (file[1] & 0xFF) << 8;
            if (group != 0x0008 && group != 0x0002) {
                throw new DicomException("The file has no DICM prefix and doesn't start with a DICOM data set.");
            }
            position = 0;
            syntax = IMPLICIT_LE;
        }
        byte[] body = file;
        int start = position;
        if (DEFLATED.equals(syntax)) {
            body = inflate(file, position);
            start = 0;
        }
        boolean explicit = !IMPLICIT_LE.equals(syntax);
        boolean big = EXPLICIT_BE.equals(syntax);
        Reader reader = new Reader(body, start, body.length, explicit, big);
        Map<Integer, Element> dataset = new TreeMap<>();
        while (reader.position + 8 <= reader.end) {
            Element element = reader.element();
            if (element == null) {
                break;
            }
            dataset.put(element.tag, element);
        }
        if (dataset.isEmpty()) {
            throw new DicomException("The file has no data set.");
        }
        return new Parsed(syntax, big, meta, dataset);
    }

    private static byte[] inflate(byte[] data, int offset) throws DicomException {
        Inflater inflater = new Inflater(true);
        inflater.setInput(data, offset, data.length - offset);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
            }
        } catch (DataFormatException e) {
            throw new DicomException("The deflated data set can't be inflated: " + e.getMessage());
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    private static final class Reader {
        final byte[] data;
        int position;
        final int end;
        final boolean explicit;
        final boolean big;
        final Dictionary dictionary = Dictionary.get();

        Reader(byte[] data, int position, int end, boolean explicit, boolean big) {
            this.data = data;
            this.position = position;
            this.end = end;
            this.explicit = explicit;
            this.big = big;
        }

        int peekGroup() {
            return u16(position);
        }

        private void need(int count) throws DicomException {
            if (position + count > end || position + count < position) {
                throw new DicomException("The data set ends inside an element at byte " + position + ".");
            }
        }

        private int u16(int at) {
            int a = data[at] & 0xFF;
            int b = data[at + 1] & 0xFF;
            return big ? a << 8 | b : b << 8 | a;
        }

        private long u32(int at) {
            long a = data[at] & 0xFF;
            long b = data[at + 1] & 0xFF;
            long c = data[at + 2] & 0xFF;
            long d = data[at + 3] & 0xFF;
            return big ? a << 24 | b << 16 | c << 8 | d : d << 24 | c << 16 | b << 8 | a;
        }

        private int tag() throws DicomException {
            need(4);
            int group = u16(position);
            int element = u16(position + 2);
            position += 4;
            return group << 16 | element;
        }

        /** The next element, or null at an item or sequence delimiter. */
        Element element() throws DicomException {
            int tag = tag();
            if (tag == ITEM_END || tag == SEQUENCE_END) {
                need(4);
                position += 4;
                return null;
            }
            String vr;
            long length;
            // Elements in group 0002 are always explicit little endian; delimiters and items carry no VR.
            boolean explicitHere = explicit && tag != ITEM;
            if (explicitHere) {
                need(2);
                vr = new String(data, position, 2, StandardCharsets.US_ASCII);
                position += 2;
                if (!Character.isUpperCase(vr.charAt(0)) || !Character.isUpperCase(vr.charAt(1))) {
                    throw new DicomException("The element " + hex(tag) + " has no valid VR.");
                }
                if (LONG_VRS.contains(vr)) {
                    need(6);
                    position += 2;
                    length = u32(position);
                    position += 4;
                } else {
                    need(2);
                    length = u16(position);
                    position += 2;
                }
            } else {
                vr = dictionary.vr(tag);
                need(4);
                length = u32(position);
                position += 4;
            }
            if ("SQ".equals(vr) || length == UNDEFINED && "UN".equals(vr) && tag != PIXEL_DATA) {
                return new Element(tag, "SQ", null, items(length), null, null);
            }
            if (tag == PIXEL_DATA && length == UNDEFINED) {
                return encapsulated(tag, vr);
            }
            if (length == UNDEFINED) {
                throw new DicomException("The element " + hex(tag) + " has an undefined length.");
            }
            need((int) length);
            byte[] value = new byte[(int) length];
            System.arraycopy(data, position, value, 0, (int) length);
            position += (int) length;
            return new Element(tag, vr, value, null, null, null);
        }

        private List<Map<Integer, Element>> items(long length) throws DicomException {
            List<Map<Integer, Element>> items = new ArrayList<>();
            int stop = length == UNDEFINED ? end : (int) Math.min(end, position + length);
            while (position + 8 <= stop) {
                int tag = tag();
                long itemLength = u32(position);
                position += 4;
                if (tag == SEQUENCE_END) {
                    break;
                }
                if (tag != ITEM) {
                    throw new DicomException("A sequence holds " + hex(tag) + " where an item belongs.");
                }
                Map<Integer, Element> item = new TreeMap<>();
                int itemStop = itemLength == UNDEFINED ? stop : (int) Math.min(stop, position + itemLength);
                Reader nested = new Reader(data, position, itemStop, explicit, big);
                while (nested.position + 4 <= nested.end) {
                    Element element = nested.element();
                    if (element == null) {
                        break;
                    }
                    item.put(element.tag, element);
                }
                position = nested.position;
                items.add(item);
            }
            if (length != UNDEFINED) {
                position = stop;
            }
            return items;
        }

        private Element encapsulated(int tag, String vr) throws DicomException {
            List<byte[]> fragments = new ArrayList<>();
            List<Long> offsets = new ArrayList<>();
            byte[] table = null;
            long relative = 0;
            while (true) {
                int item = tag();
                need(4);
                long length = u32(position);
                position += 4;
                if (item == SEQUENCE_END) {
                    break;
                }
                if (item != ITEM) {
                    throw new DicomException("Encapsulated pixel data holds " + hex(item) + " where an item belongs.");
                }
                need((int) length);
                byte[] fragment = new byte[(int) length];
                System.arraycopy(data, position, fragment, 0, (int) length);
                position += (int) length;
                if (table == null) {
                    table = fragment;
                } else {
                    fragments.add(fragment);
                    offsets.add(relative);
                    relative += 8 + length;
                }
            }
            return new Element(tag, vr, table, null, fragments, offsets);
        }
    }

    static String hex(int tag) {
        return String.format(Locale.ROOT, "(%04X,%04X)", tag >>> 16, tag & 0xFFFF);
    }

    // ================================================================ values

    /** An element's value as HealthImaging's metadata shows it: numbers for binary numeric VRs, else text. */
    static Object value(Element element, boolean big, Charset charset) {
        byte[] bytes = element.value;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(big ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        List<Object> values = new ArrayList<>();
        switch (element.vr) {
            case "US" -> {
                while (buffer.remaining() >= 2) {
                    values.add(buffer.getShort() & 0xFFFF);
                }
            }
            case "SS" -> {
                while (buffer.remaining() >= 2) {
                    values.add((int) buffer.getShort());
                }
            }
            case "UL" -> {
                while (buffer.remaining() >= 4) {
                    values.add(buffer.getInt() & 0xFFFFFFFFL);
                }
            }
            case "SL" -> {
                while (buffer.remaining() >= 4) {
                    values.add(buffer.getInt());
                }
            }
            case "UV", "SV" -> {
                while (buffer.remaining() >= 8) {
                    values.add(buffer.getLong());
                }
            }
            case "FL" -> {
                while (buffer.remaining() >= 4) {
                    values.add((double) buffer.getFloat());
                }
            }
            case "FD" -> {
                while (buffer.remaining() >= 8) {
                    values.add(buffer.getDouble());
                }
            }
            case "AT" -> {
                while (buffer.remaining() >= 4) {
                    int group = buffer.getShort() & 0xFFFF;
                    int number = buffer.getShort() & 0xFFFF;
                    values.add(String.format(Locale.ROOT, "%04X%04X", group, number));
                }
            }
            default -> {
                return trim(new String(bytes, "UI".equals(element.vr) ? StandardCharsets.US_ASCII : charset));
            }
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        return values;
    }

    /** A data set's public attributes by keyword, as JSON, with sequences as arrays of items. */
    static ObjectNode json(ObjectMapper mapper, Map<Integer, Element> dataset, boolean big, Charset charset,
                           IntPredicate include) {
        ObjectNode out = mapper.createObjectNode();
        Dictionary dictionary = Dictionary.get();
        for (Element element : dataset.values()) {
            int group = element.tag >>> 16;
            if ((group & 1) == 1 || (element.tag & 0xFFFF) == 0 || group == 0x0002 || element.tag == PIXEL_DATA
                    || element.tag == 0x7FE00008 || element.tag == 0x7FE00009 || !include.test(element.tag)) {
                continue;
            }
            String keyword = dictionary.keyword(element.tag);
            if (keyword == null) {
                continue;
            }
            if (element.items != null) {
                ArrayNode items = out.putArray(keyword);
                for (Map<Integer, Element> item : element.items) {
                    items.add(json(mapper, item, big, charset, tag -> true));
                }
                continue;
            }
            if (element.value == null || BINARY_VRS.contains(element.vr)) {
                continue;
            }
            if (element.value.length == 0) {
                out.putNull(keyword);
                continue;
            }
            out.set(keyword, mapper.valueToTree(value(element, big, charset)));
        }
        return out;
    }

    // ================================================================ pixel data

    /** A frame's samples as stored natively: its size, layout and bytes (little endian, components interleaved). */
    record Pixels(int rows, int columns, int samples, int bitsAllocated, int bitsStored, boolean signed,
                  boolean planar, String photometric) {
        int frameBytes() {
            return rows * columns * samples * (bitsAllocated / 8);
        }
    }

    static Pixels pixels(Parsed parsed) throws DicomException {
        Integer rows = parsed.integer(0x00280010);
        Integer columns = parsed.integer(0x00280011);
        Integer allocated = parsed.integer(0x00280100);
        if (rows == null || columns == null || allocated == null) {
            throw new DicomException("The image has no Rows, Columns or BitsAllocated.");
        }
        Integer stored = parsed.integer(0x00280101);
        Integer samples = parsed.integer(0x00280002);
        Integer representation = parsed.integer(0x00280103);
        Integer planar = parsed.integer(0x00280006);
        return new Pixels(rows, columns, samples == null ? 1 : samples, allocated, stored == null ? allocated : stored,
                representation != null && representation == 1, planar != null && planar == 1,
                parsed.text(0x00280004));
    }

    static int frames(Parsed parsed) {
        Integer frames = parsed.integer(0x00280008);
        return frames == null || frames < 1 ? 1 : frames;
    }

    /** The pixel data's frames as stored: native frames, or each encapsulated frame's compressed bytes. */
    static List<byte[]> frameData(Parsed parsed) throws DicomException {
        Element pixels = parsed.dataset.get(PIXEL_DATA);
        if (pixels == null) {
            return List.of();
        }
        int count = frames(parsed);
        List<byte[]> frames = new ArrayList<>();
        if (pixels.fragments == null) {
            Pixels layout = pixels(parsed);
            int size = layout.frameBytes();
            if (layout.bitsAllocated() % 8 != 0 || size <= 0) {
                throw new DicomException("Floci imports pixel data of 8, 16 or 32 bits allocated per sample.");
            }
            if ((long) size * count > pixels.value.length) {
                throw new DicomException("The pixel data is shorter than its " + count + " frames.");
            }
            for (int i = 0; i < count; i++) {
                byte[] frame = new byte[size];
                System.arraycopy(pixels.value, i * size, frame, 0, size);
                if (parsed.bigEndian && layout.bitsAllocated() > 8) {
                    swap(frame, layout.bitsAllocated() / 8);
                }
                frames.add(frame);
            }
            return frames;
        }
        List<byte[]> fragments = pixels.fragments;
        if (fragments.size() == count) {
            return new ArrayList<>(fragments);
        }
        byte[] table = pixels.value;
        if (table != null && table.length >= 4 * count && count > 1) {
            ByteBuffer offsets = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
            long[] starts = new long[count];
            for (int i = 0; i < count; i++) {
                starts[i] = offsets.getInt() & 0xFFFFFFFFL;
            }
            for (int i = 0; i < count; i++) {
                long from = starts[i];
                long to = i + 1 < count ? starts[i + 1] : Long.MAX_VALUE;
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                for (int f = 0; f < fragments.size(); f++) {
                    long at = pixels.offsets.get(f);
                    if (at >= from && at < to) {
                        frame.writeBytes(fragments.get(f));
                    }
                }
                frames.add(frame.toByteArray());
            }
            return frames;
        }
        if (count == 1) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            fragments.forEach(frame::writeBytes);
            frames.add(frame.toByteArray());
            return frames;
        }
        throw new DicomException("The encapsulated pixel data's " + fragments.size() + " fragments don't map to its "
                + count + " frames.");
    }

    private static void swap(byte[] data, int width) {
        for (int i = 0; i + width <= data.length; i += width) {
            for (int a = i, b = i + width - 1; a < b; a++, b--) {
                byte t = data[a];
                data[a] = data[b];
                data[b] = t;
            }
        }
    }

    /** Decodes a DICOM RLE Lossless frame (PS3.5 Annex G) to native little-endian, interleaved samples. */
    static byte[] rleDecode(byte[] frame, Pixels layout) throws DicomException {
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        if (frame.length < 64) {
            throw new DicomException("The RLE frame has no header.");
        }
        int segments = header.getInt(0);
        int bytesPerSample = layout.bitsAllocated() / 8;
        if (segments != layout.samples() * bytesPerSample) {
            throw new DicomException("The RLE frame has " + segments + " segments, not " + layout.samples()
                    * bytesPerSample + ".");
        }
        int pixels = layout.rows() * layout.columns();
        byte[] out = new byte[pixels * layout.samples() * bytesPerSample];
        for (int s = 0; s < segments; s++) {
            int start = header.getInt(4 + s * 4);
            int stop = s + 1 < segments ? header.getInt(8 + s * 4) : frame.length;
            byte[] plane = new byte[pixels];
            int written = 0;
            int at = start;
            while (at < stop && written < pixels) {
                int n = frame[at++];
                if (n >= 0) {
                    int count = Math.min(n + 1, pixels - written);
                    System.arraycopy(frame, at, plane, written, count);
                    written += count;
                    at += n + 1;
                } else if (n != -128) {
                    int count = Math.min(-n + 1, pixels - written);
                    Arrays.fill(plane, written, written + count, frame[at++]);
                    written += count;
                }
            }
            int sample = s / bytesPerSample;
            // segments run most significant byte first
            int byteIndex = bytesPerSample - 1 - s % bytesPerSample;
            for (int p = 0; p < pixels; p++) {
                out[(p * layout.samples() + sample) * bytesPerSample + byteIndex] = plane[p];
            }
        }
        return out;
    }
}
