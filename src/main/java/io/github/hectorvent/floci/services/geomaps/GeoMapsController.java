package io.github.hectorvent.floci.services.geomaps;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/**
 * Amazon Location Maps v2 (Smithy restJson1).
 *
 * <p>Literal {@code /static}, {@code /tiles}, {@code /styles}, {@code /glyphs}
 * (and the {@code /v2/} prefix used by the regional maps.geo endpoint) take
 * JAX-RS precedence over S3's {@code /{bucket}/{key}} catch-all.
 *
 * <p>Responses are raw payloads (PNG / JPEG / MVT / PBF / MapLibre JSON) with
 * {@code x-amz-geo-pricing-bucket} on the operations that AWS bills that way.
 */
@Path("/")
public class GeoMapsController {

    private final GeoMapsService service;

    @Inject
    public GeoMapsController(GeoMapsService service) {
        this.service = service;
    }

    @GET
    @Path("/static/{fileName}")
    public Response getStaticMap(
            @PathParam("fileName") String fileName,
            @QueryParam("center") String center,
            @QueryParam("bounding-box") String boundingBox,
            @QueryParam("bounded-positions") String boundedPositions,
            @QueryParam("width") Integer width,
            @QueryParam("height") Integer height,
            @QueryParam("zoom") Double zoom,
            @QueryParam("radius") Double radius) {
        return binary(service.getStaticMap(
                fileName, center, boundingBox, boundedPositions, width, height, zoom, radius));
    }

    @GET
    @Path("/v2/static/{fileName}")
    public Response getStaticMapV2(
            @PathParam("fileName") String fileName,
            @QueryParam("center") String center,
            @QueryParam("bounding-box") String boundingBox,
            @QueryParam("bounded-positions") String boundedPositions,
            @QueryParam("width") Integer width,
            @QueryParam("height") Integer height,
            @QueryParam("zoom") Double zoom,
            @QueryParam("radius") Double radius) {
        return getStaticMap(fileName, center, boundingBox, boundedPositions, width, height, zoom, radius);
    }

    @GET
    @Path("/tiles/{tileset}/{z}/{x}/{y}")
    public Response getTile(
            @PathParam("tileset") String tileset,
            @PathParam("z") String z,
            @PathParam("x") String x,
            @PathParam("y") String y) {
        return binary(service.getTile(tileset, z, x, y));
    }

    @GET
    @Path("/v2/tiles/{tileset}/{z}/{x}/{y}")
    public Response getTileV2(
            @PathParam("tileset") String tileset,
            @PathParam("z") String z,
            @PathParam("x") String x,
            @PathParam("y") String y) {
        return getTile(tileset, z, x, y);
    }

    @GET
    @Path("/styles/{style}/descriptor")
    public Response getStyleDescriptor(@PathParam("style") String style) {
        return binary(service.getStyleDescriptor(style));
    }

    @GET
    @Path("/v2/styles/{style}/descriptor")
    public Response getStyleDescriptorV2(@PathParam("style") String style) {
        return getStyleDescriptor(style);
    }

    @GET
    @Path("/styles/{style}/{colorScheme}/{variant}/sprites/{fileName}")
    public Response getSprites(
            @PathParam("style") String style,
            @PathParam("colorScheme") String colorScheme,
            @PathParam("variant") String variant,
            @PathParam("fileName") String fileName) {
        return binary(service.getSprites(style, colorScheme, variant, fileName));
    }

    @GET
    @Path("/v2/styles/{style}/{colorScheme}/{variant}/sprites/{fileName}")
    public Response getSpritesV2(
            @PathParam("style") String style,
            @PathParam("colorScheme") String colorScheme,
            @PathParam("variant") String variant,
            @PathParam("fileName") String fileName) {
        return getSprites(style, colorScheme, variant, fileName);
    }

    @GET
    @Path("/glyphs/{fontStack}/{fontUnicodeRange}")
    public Response getGlyphs(
            @PathParam("fontStack") String fontStack,
            @PathParam("fontUnicodeRange") String fontUnicodeRange) {
        return binary(service.getGlyphs(fontStack, fontUnicodeRange));
    }

    @GET
    @Path("/v2/glyphs/{fontStack}/{fontUnicodeRange}")
    public Response getGlyphsV2(
            @PathParam("fontStack") String fontStack,
            @PathParam("fontUnicodeRange") String fontUnicodeRange) {
        return getGlyphs(fontStack, fontUnicodeRange);
    }

    private static Response binary(GeoMapsService.BinaryAsset asset) {
        Response.ResponseBuilder builder = Response.ok(asset.body())
                .type(asset.contentType())
                .header("Cache-Control", asset.cacheControl())
                .header("ETag", asset.etag());
        if (asset.pricingBucket() != null) {
            builder.header("x-amz-geo-pricing-bucket", asset.pricingBucket());
        }
        return builder.build();
    }
}
