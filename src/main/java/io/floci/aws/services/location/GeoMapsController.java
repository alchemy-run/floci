package io.floci.aws.services.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.Set;

/**
 * Amazon Location's Maps API (signing name {@code geo-maps}): style descriptors, glyphs, sprites,
 * tiles and static map images, built by {@link LocationMapAssets}.
 */
@Path("/")
@Produces(MediaType.WILDCARD)
public class GeoMapsController {
    private static final Set<String> STYLES = Set.of("Standard", "Monochrome", "Hybrid", "Satellite");
    private static final Set<String> TILESETS = Set.of("vector.basemap", "raster.satellite", "raster.dem");

    private final ObjectMapper mapper;

    @Inject
    public GeoMapsController(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @GET
    @Path("/v2/styles/{Style}/descriptor")
    public Response getStyleDescriptor(@Context UriInfo uri, @PathParam("Style") String style,
                                       @QueryParam("color-scheme") String colorScheme) {
        check("GetStyleDescriptor", "Style", style, "ColorScheme", colorScheme);
        String base = LocationController.base(uri);
        boolean raster = "Satellite".equals(style);
        String scheme = colorScheme == null ? "Light" : colorScheme;
        byte[] descriptor = LocationMapAssets.styleDescriptor(style,
                base + "/v2/tiles/" + (raster ? "raster.satellite" : "vector.basemap") + "/{z}/{x}/{y}", raster,
                base + "/v2/glyphs/{fontstack}/{range}",
                base + "/v2/styles/" + style + "/" + scheme + "/Default/sprites/sprites", mapper);
        return LocationController.blob(descriptor, "application/json");
    }

    @GET
    @Path("/v2/glyphs/{FontStack}/{FontUnicodeRange}")
    public Response getGlyphs(@PathParam("FontStack") String fontStack, @PathParam("FontUnicodeRange") String range) {
        check("GetGlyphs", "FontStack", fontStack, "FontUnicodeRange", range);
        if (!range.matches("\\d+-\\d+(\\.pbf)?")) {
            throw LocationService.validation("FontUnicodeRange must be a range such as 0-255.");
        }
        return LocationController.blob(LocationMapAssets.glyphs(fontStack, range.replace(".pbf", "")),
                "application/x-protobuf");
    }

    @GET
    @Path("/v2/styles/{Style}/{ColorScheme}/{Variant}/sprites/{FileName}")
    public Response getSprites(@PathParam("Style") String style, @PathParam("ColorScheme") String colorScheme,
                               @PathParam("Variant") String variant, @PathParam("FileName") String file) {
        check("GetSprites", "Style", style, "ColorScheme", colorScheme, "Variant", variant, "FileName", file);
        return file.endsWith(".json") ? LocationController.blob(LocationMapAssets.spriteIndex(), "application/json")
                : LocationController.blob(LocationMapAssets.png(file.contains("@2x") ? 2 : 1,
                file.contains("@2x") ? 2 : 1, 0), "image/png");
    }

    @GET
    @Path("/v2/tiles/{Tileset}/{Z}/{X}/{Y}")
    public Response getTile(@PathParam("Tileset") String tileset, @PathParam("Z") String z, @PathParam("X") String x,
                            @PathParam("Y") String y) {
        check("GetTile", "Tileset", tileset, "Z", z, "X", x, "Y", y);
        if (!TILESETS.contains(tileset)) {
            throw LocationService.validation("Tileset must be one of " + TILESETS + ".");
        }
        LocationController.checkTile(z, x, y);
        Response response = tileset.startsWith("raster")
                ? LocationController.blob(LocationMapAssets.png(256, 256, "raster.dem".equals(tileset)
                ? 0x808080ff : 0x6b8e4eff), "image/png")
                : LocationController.blob(LocationMapAssets.vectorTile(), "application/vnd.mapbox-vector-tile");
        return Response.fromResponse(response).header("x-amz-geo-pricing-bucket", "Bucket1").build();
    }

    @GET
    @Path("/v2/static/{FileName}")
    public Response getStaticMap(@PathParam("FileName") String file, @QueryParam("width") Integer width,
                                 @QueryParam("height") Integer height, @QueryParam("center") String center,
                                 @QueryParam("zoom") Float zoom, @QueryParam("bounding-box") String bbox,
                                 @QueryParam("bounded-positions") String boundedPositions,
                                 @QueryParam("color-scheme") String colorScheme, @QueryParam("style") String style) {
        check("GetStaticMap", "FileName", file, "Width", width, "Height", height);
        if (width == null || height == null) {
            throw LocationService.validation("width and height are required.");
        }
        if (width < 64 || width > 1400 || height < 64 || height > 1400) {
            throw LocationService.validation("width and height must be between 64 and 1400 pixels.");
        }
        int framing = (center != null ? 1 : 0) + (bbox != null ? 1 : 0) + (boundedPositions != null ? 1 : 0);
        if (framing != 1) {
            throw LocationService.validation("Specify one of center (with zoom or radius), bounding-box or "
                    + "bounded-positions.");
        }
        if (center != null && !center.matches("-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?")) {
            throw LocationService.validation("center must be longitude,latitude.");
        }
        if (!file.matches("map(@2x)?")) {
            throw LocationService.validation("FileName must be map or map@2x.");
        }
        int scale = file.contains("@2x") ? 2 : 1;
        byte[] image = LocationMapAssets.staticMap(width * scale, height * scale, "Dark".equals(colorScheme));
        return Response.ok(image, "image/png").header("x-amz-geo-pricing-bucket", "Bucket1")
                .header("Cache-Control", "max-age=86400").build();
    }

    private static void check(String operation, Object... members) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i + 1 < members.length; i += 2) {
            Object value = members[i + 1];
            if (value instanceof Integer number) {
                request.put((String) members[i], number);
            } else if (value != null) {
                request.put((String) members[i], String.valueOf(value));
            }
        }
        LocationRequestShapes.check("geo-maps", operation, request);
        if ("GetStyleDescriptor".equals(operation) && !STYLES.contains(request.path("Style").asText())) {
            throw LocationService.validation("Style must be one of " + STYLES + ".");
        }
    }
}
