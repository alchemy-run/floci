package io.github.hectorvent.floci.services.geomaps;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Amazon Location Maps v2 (geo-maps) data-plane stub.
 *
 * <p>Generates deterministic PNG / MapLibre / MVT / glyph payloads so SDK
 * clients and Alchemy runtime bindings can round-trip without calling AWS.
 *
 * @see <a href="https://docs.aws.amazon.com/location/latest/APIReference/API_Operations_Amazon_Location_Service_Maps_V2.html">Maps v2 API</a>
 */
@ApplicationScoped
public class GeoMapsService {

    static final String PRICING_BUCKET_MAPS = "Maps";
    static final String PRICING_BUCKET_TILES = "Tiles";
    static final String CACHE_CONTROL = "max-age=86400";
    static final String CONTENT_TYPE_PNG = "image/png";
    static final String CONTENT_TYPE_JPEG = "image/jpeg";
    static final String CONTENT_TYPE_JSON = "application/json";
    static final String CONTENT_TYPE_MVT = "application/vnd.mapbox-vector-tile";
    static final String CONTENT_TYPE_PBF = "application/octet-stream";
    static final String CONTENT_TYPE_SPRITE_JSON = "application/json";

    /** 1×1 PNG (transparent). */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** Minimal Mapbox Vector Tile (empty layers) — any non-empty body satisfies GetTile. */
    private static final byte[] MVT = new byte[] {0x1a, 0x00};

    /** Stub glyph PBF range. */
    private static final byte[] GLYPH_PBF = new byte[] {0x0a, 0x00, 0x12, 0x00};

    public record BinaryAsset(byte[] body, String contentType, String etag, String cacheControl, String pricingBucket) {
    }

    public BinaryAsset getStaticMap(String fileName, String center, String boundingBox, String boundedPositions,
            Integer width, Integer height, Double zoom, Double radius) {
        require(fileName, "FileName");
        if (width == null) {
            throw validation("Width is a required parameter.", "Width");
        }
        if (height == null) {
            throw validation("Height is a required parameter.", "Height");
        }
        if (width < 64 || width > 1024 || height < 64 || height > 1024) {
            throw validation("Width and Height must be between 64 and 1024.", "Width");
        }
        boolean hasCenter = notBlank(center);
        boolean hasBox = notBlank(boundingBox);
        boolean hasPositions = notBlank(boundedPositions);
        if (!hasCenter && !hasBox && !hasPositions) {
            throw validation(
                    "One of Center, BoundingBox, or BoundedPositions is required.",
                    "Center");
        }
        if (hasCenter && zoom == null && radius == null) {
            throw validation("Zoom or Radius is required when Center is provided.", "Zoom");
        }
        // AWS FileName is "map" or "map@2x"; the payload is always PNG.
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!"map".equals(lower) && !"map@2x".equals(lower) && !lower.endsWith(".png")
                && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
            throw validation("FileName must be map, map@2x, or an image file name.", "FileName");
        }
        String contentType = (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
                ? CONTENT_TYPE_JPEG
                : CONTENT_TYPE_PNG;
        return asset(PNG, contentType, PRICING_BUCKET_MAPS);
    }

    public BinaryAsset getTile(String tileset, String z, String x, String y) {
        require(tileset, "Tileset");
        require(z, "Z");
        require(x, "X");
        require(y, "Y");
        return asset(MVT, CONTENT_TYPE_MVT, PRICING_BUCKET_TILES);
    }

    public BinaryAsset getStyleDescriptor(String style) {
        require(style, "Style");
        String body = """
                {
                  "version": 8,
                  "name": "%s",
                  "sources": {
                    "aws": {
                      "type": "vector",
                      "tiles": ["https://maps.geo.us-east-1.amazonaws.com/v2/tiles/vector.basemap/{z}/{x}/{y}"]
                    }
                  },
                  "layers": [
                    {
                      "id": "background",
                      "type": "background",
                      "paint": { "background-color": "#f8f4f0" }
                    }
                  ],
                  "glyphs": "https://maps.geo.us-east-1.amazonaws.com/v2/glyphs/{fontstack}/{range}",
                  "sprite": "https://maps.geo.us-east-1.amazonaws.com/v2/styles/%s/Light/Default/sprites/sprites"
                }
                """.formatted(escape(style), escape(style));
        return asset(body.getBytes(StandardCharsets.UTF_8), CONTENT_TYPE_JSON, null);
    }

    public BinaryAsset getSprites(String style, String colorScheme, String variant, String fileName) {
        require(style, "Style");
        require(colorScheme, "ColorScheme");
        require(variant, "Variant");
        require(fileName, "FileName");
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            String json = """
                    {
                      "marker": { "width": 32, "height": 32, "x": 0, "y": 0, "pixelRatio": 1 }
                    }
                    """;
            return asset(json.getBytes(StandardCharsets.UTF_8), CONTENT_TYPE_SPRITE_JSON, null);
        }
        return asset(PNG, CONTENT_TYPE_PNG, null);
    }

    public BinaryAsset getGlyphs(String fontStack, String fontUnicodeRange) {
        require(fontStack, "FontStack");
        require(fontUnicodeRange, "FontUnicodeRange");
        if (!fontUnicodeRange.endsWith(".pbf")) {
            throw validation("FontUnicodeRange must end with .pbf.", "FontUnicodeRange");
        }
        return asset(GLYPH_PBF, CONTENT_TYPE_PBF, null);
    }

    private static BinaryAsset asset(byte[] body, String contentType, String pricingBucket) {
        return new BinaryAsset(body, contentType, etag(body), CACHE_CONTROL, pricingBucket);
    }

    private static String etag(byte[] body) {
        return "\"" + HexFormat.of().toHexDigits(java.util.Arrays.hashCode(body)) + "\"";
    }

    private static void require(String value, String field) {
        if (!notBlank(value)) {
            throw validation(field + " is a required parameter.", field);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static AwsException validation(String message, String field) {
        return new AwsException("ValidationException", message, 400,
                java.util.Map.of(
                        "Reason", "Missing",
                        "FieldList", java.util.List.of(
                                java.util.Map.of("Name", field, "Message", message))));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
