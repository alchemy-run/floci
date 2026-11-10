package io.floci.aws.services.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The map assets the Location map APIs serve, built locally rather than fetched from a map
 * provider: a MapLibre style descriptor whose source points at the map's own tile route, glyph
 * ranges as the Mapbox glyph PBF with no glyphs, sprite sheets (an empty index and a transparent
 * PNG), vector tiles in the Mapbox Vector Tile encoding with a {@code land} layer covering the
 * tile and a {@code water} layer outside it, raster tiles and static maps as solid PNGs.
 */
final class LocationMapAssets {
    static final int EXTENT = 4096;

    private LocationMapAssets() {
    }

    /** A MapLibre style with one vector (or raster) source served from {@code tileUrl}. */
    static byte[] styleDescriptor(String name, String tileUrl, boolean raster, String glyphsUrl, String spritesUrl,
                                  ObjectMapper mapper) {
        ObjectNode style = mapper.createObjectNode();
        style.put("version", 8);
        style.put("name", name);
        style.put("glyphs", glyphsUrl);
        style.put("sprite", spritesUrl);
        ObjectNode source = style.putObject("sources").putObject("floci");
        source.put("type", raster ? "raster" : "vector");
        source.putArray("tiles").add(tileUrl);
        source.put("minzoom", 0);
        source.put("maxzoom", raster ? 19 : 15);
        if (raster) {
            source.put("tileSize", 256);
        }
        ArrayNode layers = style.putArray("layers");
        ObjectNode background = layers.addObject();
        background.put("id", "background");
        background.put("type", "background");
        background.putObject("paint").put("background-color", "#f2efe9");
        if (raster) {
            ObjectNode imagery = layers.addObject();
            imagery.put("id", "imagery");
            imagery.put("type", "raster");
            imagery.put("source", "floci");
        } else {
            for (String[] layer : new String[][]{{"water", "#a5bfdd"}, {"land", "#f2efe9"}}) {
                ObjectNode fill = layers.addObject();
                fill.put("id", layer[0]);
                fill.put("type", "fill");
                fill.put("source", "floci");
                fill.put("source-layer", layer[0]);
                fill.putObject("paint").put("fill-color", layer[1]);
            }
        }
        try {
            return mapper.writeValueAsBytes(style);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A Mapbox glyph PBF for {@code fontStack} and {@code range} ({@code 0-255}) holding no glyphs. */
    static byte[] glyphs(String fontStack, String range) {
        ByteArrayOutputStream stack = new ByteArrayOutputStream();
        writeString(stack, 1, fontStack);
        writeString(stack, 2, range);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBytes(out, 1, stack.toByteArray());
        return out.toByteArray();
    }

    static byte[] spriteIndex() {
        return "{}".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A vector tile with a {@code land} polygon covering the tile and an empty {@code water}
     * layer, both at version 2 and a 4096 extent, so renderers have real features to draw.
     */
    static byte[] vectorTile() {
        ByteArrayOutputStream tile = new ByteArrayOutputStream();
        writeBytes(tile, 3, layer("land", true));
        writeBytes(tile, 3, layer("water", false));
        return tile.toByteArray();
    }

    private static byte[] layer(String name, boolean covered) {
        ByteArrayOutputStream layer = new ByteArrayOutputStream();
        writeVarintField(layer, 15, 2);
        writeString(layer, 1, name);
        if (covered) {
            ByteArrayOutputStream feature = new ByteArrayOutputStream();
            writeVarintField(feature, 1, 1);
            writeVarintField(feature, 3, 3);
            // MoveTo(0,0), LineTo three corners, ClosePath: a square covering the extent.
            int[] geometry = {command(1, 1), zigzag(0), zigzag(0),
                    command(2, 3), zigzag(EXTENT), zigzag(0), zigzag(0), zigzag(EXTENT), zigzag(-EXTENT), zigzag(0),
                    command(7, 1)};
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            for (int value : geometry) {
                writeVarint(packed, value);
            }
            writeBytes(feature, 4, packed.toByteArray());
            writeBytes(layer, 2, feature.toByteArray());
        }
        writeVarintField(layer, 5, EXTENT);
        return layer.toByteArray();
    }

    private static int command(int id, int count) {
        return (id & 0x7) | (count << 3);
    }

    private static int zigzag(int value) {
        return (value << 1) ^ (value >> 31);
    }

    // ── Protobuf ───────────────────────────────────────────────────────────

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void writeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, ((long) field << 3));
        writeVarint(out, value);
    }

    private static void writeBytes(ByteArrayOutputStream out, int field, byte[] bytes) {
        writeVarint(out, ((long) field << 3) | 2);
        writeVarint(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeString(ByteArrayOutputStream out, int field, String value) {
        writeBytes(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    // ── PNG ────────────────────────────────────────────────────────────────

    /** An 8-bit RGBA PNG of {@code width} x {@code height} filled with {@code rgba}. */
    static byte[] png(int width, int height, int rgba) {
        return png(width, height, (x, y) -> rgba);
    }

    /** Colors each pixel. */
    interface Painter {
        int rgba(int x, int y);
    }

    static byte[] png(int width, int height, Painter painter) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(height * (width * 4 + 1));
        for (int y = 0; y < height; y++) {
            raw.write(0);
            for (int x = 0; x < width; x++) {
                int color = painter.rgba(x, y);
                raw.write(color >>> 24);
                raw.write(color >>> 16);
                raw.write(color >>> 8);
                raw.write(color);
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(raw.toByteArray());
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteBuffer header = ByteBuffer.allocate(13).putInt(width).putInt(height);
        header.put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(png, "IHDR", header.array());
        chunk(png, "IDAT", compressed.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /**
     * A static map: the background color, a graticule every tenth of the frame, and a marker at
     * the center, so a client can tell the map was drawn for its request.
     */
    static byte[] staticMap(int width, int height, boolean dark) {
        int background = dark ? 0x1d2733ff : 0xf2efe9ff;
        int grid = dark ? 0x2e3b4aff : 0xd9d4c7ff;
        int marker = 0xe5484dff;
        int cx = width / 2;
        int cy = height / 2;
        int stepX = Math.max(1, width / 10);
        int stepY = Math.max(1, height / 10);
        return png(width, height, (x, y) -> {
            if (Math.abs(x - cx) <= 4 && Math.abs(y - cy) <= 4) {
                return marker;
            }
            return x % stepX == 0 || y % stepY == 0 ? grid : background;
        });
    }
}
