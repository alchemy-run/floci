package io.floci.aws.services.medicalimaging;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Image frames imported from DICOM pixel data: native frames (16-bit signed, multi-frame RGB) and RLE frames stored as
 * HTJ2K lossless, a JPEG 2000 lossless data store's frames, and JPEG baseline frames stored as they are. Each frame
 * read back decodes to the original samples.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@Tag("docker")
class MedicalImagingDockerIntegrationTest {

    @Inject
    MedicalImagingCodecs codecs;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static byte[] samples16(int rows, int columns) {
        ByteBuffer buffer = ByteBuffer.allocate(rows * columns * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < rows * columns; i++) {
            buffer.putShort((short) ((i * 37) % 3000 - 1000));
        }
        return buffer.array();
    }

    private static byte[] samples8(int count, int seed) {
        byte[] data = new byte[count];
        for (int i = 0; i < count; i++) {
            data[i] = (byte) ((i * 7 + seed * 31) & 0xFF);
        }
        return data;
    }

    private static byte[] rle(byte[] plane) {
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        for (int i = 0; i < plane.length; i += 128) {
            int count = Math.min(128, plane.length - i);
            segment.write(count - 1);
            segment.write(plane, i, count);
        }
        if (segment.size() % 2 == 1) {
            segment.write(0);
        }
        ByteBuffer header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(1).putInt(64);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.writeBytes(header.array());
        frame.writeBytes(segment.toByteArray());
        return frame.toByteArray();
    }

    private static byte[] jpeg(int rows, int columns) throws IOException {
        BufferedImage image = new BufferedImage(columns, rows, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                image.getRaster().setSample(x, y, 0, (x * 5 + y * 3) & 0xFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    private static byte[] frame(String datastore, String imageSet, String frameId) {
        return MedicalImagingIntegrationTest.mi().body("{\"imageFrameId\":\"" + frameId + "\"}")
                .post("/datastore/" + datastore + "/imageSet/" + imageSet + "/getImageFrame").then().statusCode(200)
                .contentType("application/octet-stream").extract().asByteArray();
    }

    /** Decodes stored frames with the codec the transfer syntax names, to raw little-endian planar samples. */
    private List<byte[]> decode(List<byte[]> frames, String syntax) {
        return decode(frames, syntax, ".yuv");
    }

    private List<byte[]> decode(List<byte[]> frames, String syntax, String raw) {
        Map<String, byte[]> inputs = new LinkedHashMap<>();
        StringBuilder script = new StringBuilder("set -e; ");
        for (int i = 0; i < frames.size(); i++) {
            inputs.put("f" + i + ".j2c", frames.get(i));
            script.append(MedicalImagingImports.HTJ2K.equals(syntax) ? "ojph_expand -i f" + i + ".j2c -o o" + i
                    + raw + " >/dev/null; " : "opj_decompress -quiet -i f" + i + ".j2c -o o" + i + ".rawl; ");
        }
        Map<String, byte[]> out = codecs.run(inputs, script.toString());
        String extension = MedicalImagingImports.HTJ2K.equals(syntax) ? raw : ".rawl";
        return IntStream.range(0, frames.size()).mapToObj(i -> out.get("o" + i + extension))
                .toList();
    }

    private record Imported(String datastore, String imageSet, JsonNode instance) {
    }

    private Imported importOne(String name, String format, byte[] file) {
        MedicalImagingIntegrationTest.bucket("mi-" + name);
        MedicalImagingIntegrationTest.put("mi-" + name, "in/i.dcm", file);
        String role = MedicalImagingIntegrationTest.role("mi-" + name, "medical-imaging.amazonaws.com");
        String datastore = MedicalImagingIntegrationTest.datastore(name, format);
        String job = MedicalImagingIntegrationTest.importJob(datastore, role, "mi-" + name, "in");
        String manifest = MedicalImagingIntegrationTest.get("mi-" + name, "out/" + job + "/job-output-manifest.json");
        JsonNode found = MedicalImagingIntegrationTest.search(datastore, null);
        assertEquals(1, found.path("imageSetsMetadataSummaries").size(), manifest);
        String imageSet = found.path("imageSetsMetadataSummaries").path(0).path("imageSetId").asText();
        JsonNode doc = MedicalImagingIntegrationTest.metadata(datastore, imageSet, "1");
        JsonNode instance = doc.path("Study").path("Series").elements().next().path("Instances").elements().next();
        return new Imported(datastore, imageSet, instance);
    }

    @Test
    void nativeSixteenBitSignedFramesAreStoredAsHtj2k() {
        int rows = 48;
        int columns = 64;
        byte[] pixels = samples16(rows, columns);
        Map<Integer, DicomFiles.Value> elements = DicomFiles.instance("D1", "Doe^D1", "1.2.3.100", "1.2.3.100.1",
                "1.2.3.100.1.1", "CT");
        DicomFiles.pixels(elements, rows, columns, 1, 16, true, 1, pixels);
        Imported imported = importOne("ct", null, DicomFiles.write(elements, MedicalImagingDicom.IMPLICIT_LE));
        assertEquals(MedicalImagingImports.HTJ2K, imported.instance().path("StoredTransferSyntaxUID").asText());
        JsonNode frameInfo = imported.instance().path("ImageFrames").path(0);
        assertEquals(-1000, frameInfo.path("MinPixelValue").asInt(), frameInfo.toString());
        JsonNode checksums = frameInfo.path("PixelDataChecksumFromBaseToFullResolution");
        JsonNode full = checksums.path(checksums.size() - 1);
        assertEquals(columns, full.path("Width").asInt());
        assertEquals(rows, full.path("Height").asInt());
        CRC32 crc = new CRC32();
        crc.update(pixels);
        assertEquals(crc.getValue(), full.path("Checksum").asLong());
        byte[] stored = frame(imported.datastore(), imported.imageSet(), frameInfo.path("ID").asText());
        assertArrayEquals(pixels, decode(List.of(stored), MedicalImagingImports.HTJ2K).get(0));
    }

    @Test
    void multiFrameRgbAndRleFramesDecodeToTheirSamples() {
        int rows = 16;
        int columns = 20;
        byte[] first = samples8(rows * columns * 3, 1);
        byte[] second = samples8(rows * columns * 3, 2);
        byte[] both = new byte[first.length * 2];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        Map<Integer, DicomFiles.Value> rgb = DicomFiles.instance("D2", "Doe^D2", "1.2.3.200", "1.2.3.200.1",
                "1.2.3.200.1.1", "XC");
        DicomFiles.pixels(rgb, rows, columns, 3, 8, false, 2, both);
        Imported imported = importOne("rgb", null, DicomFiles.write(rgb, MedicalImagingDicom.EXPLICIT_LE));
        JsonNode frames = imported.instance().path("ImageFrames");
        assertEquals(2, frames.size());
        MedicalImagingDicom.Pixels layout = new MedicalImagingDicom.Pixels(rows, columns, 3, 8, 8, false, false,
                "RGB");
        List<byte[]> decoded = decode(List.of(frame(imported.datastore(), imported.imageSet(), frames.path(0)
                .path("ID").asText()), frame(imported.datastore(), imported.imageSet(), frames.path(1).path("ID")
                .asText())), MedicalImagingImports.HTJ2K, ".yuv");
        assertArrayEquals(MedicalImagingImports.planar(first, layout), decoded.get(0));
        assertArrayEquals(MedicalImagingImports.planar(second, layout), decoded.get(1));

        byte[] grey = samples8(rows * columns, 3);
        Map<Integer, DicomFiles.Value> encapsulated = DicomFiles.instance("D3", "Doe^D3", "1.2.3.300", "1.2.3.300.1",
                "1.2.3.300.1.1", "MR");
        DicomFiles.pixels(encapsulated, rows, columns, 1, 8, false, 1, new byte[0]);
        Imported rle = importOne("rle", null, DicomFiles.write(encapsulated, MedicalImagingDicom.RLE,
                List.of(rle(grey))));
        byte[] stored = frame(rle.datastore(), rle.imageSet(), rle.instance().path("ImageFrames").path(0).path("ID")
                .asText());
        assertArrayEquals(grey, decode(List.of(stored), MedicalImagingImports.HTJ2K).get(0));
    }

    @Test
    void jpeg2000DataStoresAndJpegBaselineFrames() throws IOException {
        int rows = 24;
        int columns = 32;
        byte[] pixels = samples16(rows, columns);
        Map<Integer, DicomFiles.Value> elements = DicomFiles.instance("D4", "Doe^D4", "1.2.3.400", "1.2.3.400.1",
                "1.2.3.400.1.1", "CT");
        DicomFiles.pixels(elements, rows, columns, 1, 16, true, 1, pixels);
        Imported j2k = importOne("j2k", "JPEG_2000_LOSSLESS", DicomFiles.write(elements,
                MedicalImagingDicom.EXPLICIT_LE));
        assertEquals(MedicalImagingImports.J2K_LOSSLESS, j2k.instance().path("StoredTransferSyntaxUID").asText());
        byte[] stored = frame(j2k.datastore(), j2k.imageSet(), j2k.instance().path("ImageFrames").path(0).path("ID")
                .asText());
        assertArrayEquals(pixels, decode(List.of(stored), MedicalImagingImports.J2K_LOSSLESS).get(0));

        byte[] jpeg = jpeg(rows, columns);
        Map<Integer, DicomFiles.Value> baseline = DicomFiles.instance("D5", "Doe^D5", "1.2.3.500", "1.2.3.500.1",
                "1.2.3.500.1.1", "XC");
        DicomFiles.pixels(baseline, rows, columns, 1, 8, false, 1, new byte[0]);
        Imported asIs = importOne("jpeg", null, DicomFiles.write(baseline, "1.2.840.10008.1.2.4.50",
                List.of(jpeg)));
        assertEquals("1.2.840.10008.1.2.4.50", asIs.instance().path("StoredTransferSyntaxUID").asText());
        byte[] frame = frame(asIs.datastore(), asIs.imageSet(), asIs.instance().path("ImageFrames").path(0)
                .path("ID").asText());
        byte[] even = jpeg.length % 2 == 0 ? jpeg : Arrays.copyOf(jpeg, jpeg.length + 1);
        assertArrayEquals(even, frame);
    }
}
