package io.floci.aws.services.medicalimaging;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** DICOM Part 10 files for tests: explicit or implicit VR little endian, with native or encapsulated pixel data. */
final class DicomFiles {

    private static final Set<String> LONG_VRS = Set.of("OB", "OD", "OF", "OL", "OV", "OW", "SQ", "UC", "UN", "UR",
            "UT");

    private DicomFiles() {
    }

    /** An element to write: its tag, VR and value bytes. */
    record Value(String vr, byte[] bytes) {
    }

    static Value text(String vr, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length % 2 == 1) {
            byte[] padded = new byte[bytes.length + 1];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            padded[bytes.length] = (byte) ("UI".equals(vr) ? 0 : ' ');
            bytes = padded;
        }
        return new Value(vr, bytes);
    }

    static Value us(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            buffer.putShort((short) value);
        }
        return new Value("US", buffer.array());
    }

    /** The attributes of an instance of a study and series, by tag. */
    static Map<Integer, Value> instance(String patientId, String patientName, String study, String series, String sop,
                                        String modality) {
        Map<Integer, Value> elements = new TreeMap<>(Integer::compareUnsigned);
        elements.put(0x00080016, text("UI", "1.2.840.10008.5.1.4.1.1.7"));
        elements.put(0x00080018, text("UI", sop));
        elements.put(0x00080020, text("DA", "20260101"));
        elements.put(0x00080030, text("TM", "083000"));
        elements.put(0x00080050, text("SH", "ACC-" + patientId));
        elements.put(0x00080060, text("CS", modality));
        elements.put(0x00081030, text("LO", "Floci study"));
        elements.put(0x00100010, text("PN", patientName));
        elements.put(0x00100020, text("LO", patientId));
        elements.put(0x00100040, text("CS", "O"));
        elements.put(0x0020000D, text("UI", study));
        elements.put(0x0020000E, text("UI", series));
        elements.put(0x00200010, text("SH", "S1"));
        elements.put(0x00200011, text("IS", "7"));
        elements.put(0x00200013, text("IS", "1"));
        return elements;
    }

    /** Adds native image pixel attributes and data to an instance. */
    static void pixels(Map<Integer, Value> elements, int rows, int columns, int samples, int bits, boolean signed,
                       int frames, byte[] data) {
        elements.put(0x00280002, us(samples));
        elements.put(0x00280004, text("CS", samples == 3 ? "RGB" : "MONOCHROME2"));
        if (samples == 3) {
            elements.put(0x00280006, us(0));
        }
        if (frames > 1) {
            elements.put(0x00280008, text("IS", String.valueOf(frames)));
        }
        elements.put(0x00280010, us(rows));
        elements.put(0x00280011, us(columns));
        elements.put(0x00280100, us(bits));
        elements.put(0x00280101, us(bits));
        elements.put(0x00280102, us(bits - 1));
        elements.put(0x00280103, us(signed ? 1 : 0));
        elements.put(MedicalImagingDicom.PIXEL_DATA, new Value(bits > 8 ? "OW" : "OB", data));
    }

    static byte[] write(Map<Integer, Value> elements, String syntax) {
        return write(elements, syntax, null);
    }

    /** A Part 10 file; with fragments, its pixel data is encapsulated one fragment per frame. */
    static byte[] write(Map<Integer, Value> elements, String syntax, List<byte[]> fragments) {
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        element(meta, 0x00020001, new Value("OB", new byte[] {0, 1}), true);
        element(meta, 0x00020002, elements.get(0x00080016), true);
        element(meta, 0x00020003, elements.get(0x00080018), true);
        element(meta, 0x00020010, text("UI", syntax), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[128]);
        out.writeBytes("DICM".getBytes(StandardCharsets.US_ASCII));
        element(out, 0x00020000, new Value("UL", ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(meta.size()).array()), true);
        out.writeBytes(meta.toByteArray());
        boolean explicit = !MedicalImagingDicom.IMPLICIT_LE.equals(syntax);
        for (Map.Entry<Integer, Value> entry : elements.entrySet()) {
            if (entry.getKey() == MedicalImagingDicom.PIXEL_DATA && fragments != null) {
                tag(out, MedicalImagingDicom.PIXEL_DATA);
                out.writeBytes("OB".getBytes(StandardCharsets.US_ASCII));
                out.writeBytes(new byte[] {0, 0});
                out.writeBytes(new byte[] {-1, -1, -1, -1});
                tag(out, MedicalImagingDicom.ITEM);
                out.writeBytes(new byte[4]);
                for (byte[] fragment : fragments) {
                    byte[] even = fragment.length % 2 == 0 ? fragment : Arrays.copyOf(fragment,
                            fragment.length + 1);
                    tag(out, MedicalImagingDicom.ITEM);
                    out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(even.length).array());
                    out.writeBytes(even);
                }
                tag(out, MedicalImagingDicom.SEQUENCE_END);
                out.writeBytes(new byte[4]);
                continue;
            }
            element(out, entry.getKey(), entry.getValue(), explicit);
        }
        return out.toByteArray();
    }

    private static void tag(ByteArrayOutputStream out, int tag) {
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort((short) (tag >>> 16))
                .putShort((short) tag).array());
    }

    private static void element(ByteArrayOutputStream out, int tag, Value value, boolean explicit) {
        tag(out, tag);
        if (explicit) {
            out.writeBytes(value.vr().getBytes(StandardCharsets.US_ASCII));
            if (LONG_VRS.contains(value.vr())) {
                out.writeBytes(new byte[] {0, 0});
                out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.bytes().length)
                        .array());
            } else {
                out.writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(
                        (short) value.bytes().length).array());
            }
        } else {
            out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.bytes().length).array());
        }
        out.writeBytes(value.bytes());
    }
}
