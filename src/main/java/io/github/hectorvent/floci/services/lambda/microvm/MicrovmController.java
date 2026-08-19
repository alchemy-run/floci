package io.github.hectorvent.floci.services.lambda.microvm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * AWS Lambda MicroVMs control plane — the {@code 2025-09-09} REST-JSON API
 * surface consumed by the distilled {@code lambda-microvms} SDK module:
 * customer image CRUD + versions + builds, the managed base-image catalog,
 * the MicroVM instance lifecycle, and endpoint auth tokens.
 */
@Path("/2025-09-09")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class MicrovmController {

    private final MicrovmImageService imageService;
    private final MicrovmRuntimeService runtimeService;
    private final MicrovmAuthTokenService tokenService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MicrovmController(MicrovmImageService imageService, MicrovmRuntimeService runtimeService,
                             MicrovmAuthTokenService tokenService, RegionResolver regionResolver,
                             ObjectMapper objectMapper) {
        this.imageService = imageService;
        this.runtimeService = runtimeService;
        this.tokenService = tokenService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── managed base images ────────────────────────────

    @GET
    @Path("/managed-microvm-images")
    public Response listManagedImages(@Context HttpHeaders headers) {
        return Response.ok(imageService.listManagedImages(region(headers))).build();
    }

    @GET
    @Path("/managed-microvm-images/{imageIdentifier}/versions")
    public Response listManagedImageVersions(@PathParam("imageIdentifier") String imageIdentifier,
                                             @Context HttpHeaders headers) {
        return Response.ok(imageService.listManagedImageVersions(region(headers), imageIdentifier)).build();
    }

    // ──────────────────────────── customer images ────────────────────────────

    @POST
    @Path("/microvm-images")
    public Response createImage(@Context HttpHeaders headers, String body) {
        return Response.ok(imageService.createImage(
                region(headers), regionResolver.getAccountId(), parseBody(body))).build();
    }

    @GET
    @Path("/microvm-images")
    public Response listImages(@Context HttpHeaders headers,
                               @QueryParam("nameFilter") String nameFilter) {
        return Response.ok(imageService.listImages(region(headers), nameFilter)).build();
    }

    @GET
    @Path("/microvm-images/{imageIdentifier}")
    public Response getImage(@PathParam("imageIdentifier") String imageIdentifier,
                             @Context HttpHeaders headers) {
        return Response.ok(imageService.getImage(region(headers), imageIdentifier)).build();
    }

    @PUT
    @Path("/microvm-images/{imageIdentifier}")
    public Response updateImage(@PathParam("imageIdentifier") String imageIdentifier,
                                @Context HttpHeaders headers, String body) {
        return Response.ok(imageService.updateImage(region(headers), imageIdentifier, parseBody(body))).build();
    }

    @DELETE
    @Path("/microvm-images/{imageIdentifier}")
    public Response deleteImage(@PathParam("imageIdentifier") String imageIdentifier,
                                @Context HttpHeaders headers) {
        return Response.ok(imageService.deleteImage(region(headers), imageIdentifier)).build();
    }

    // ──────────────────────────── image versions & builds ────────────────────────────

    @GET
    @Path("/microvm-images/{imageIdentifier}/versions")
    public Response listImageVersions(@PathParam("imageIdentifier") String imageIdentifier,
                                      @Context HttpHeaders headers) {
        return Response.ok(imageService.listImageVersions(region(headers), imageIdentifier)).build();
    }

    @GET
    @Path("/microvm-images/{imageIdentifier}/versions/{imageVersion}")
    public Response getImageVersion(@PathParam("imageIdentifier") String imageIdentifier,
                                    @PathParam("imageVersion") String imageVersion,
                                    @Context HttpHeaders headers) {
        return Response.ok(imageService.getImageVersion(region(headers), imageIdentifier, imageVersion)).build();
    }

    @PATCH
    @Path("/microvm-images/{imageIdentifier}/versions/{imageVersion}")
    public Response updateImageVersion(@PathParam("imageIdentifier") String imageIdentifier,
                                       @PathParam("imageVersion") String imageVersion,
                                       @Context HttpHeaders headers, String body) {
        return Response.ok(imageService.updateImageVersion(
                region(headers), imageIdentifier, imageVersion, parseBody(body))).build();
    }

    @DELETE
    @Path("/microvm-images/{imageIdentifier}/versions/{imageVersion}")
    public Response deleteImageVersion(@PathParam("imageIdentifier") String imageIdentifier,
                                       @PathParam("imageVersion") String imageVersion,
                                       @Context HttpHeaders headers) {
        return Response.ok(imageService.deleteImageVersion(region(headers), imageIdentifier, imageVersion)).build();
    }

    @GET
    @Path("/microvm-images/{imageIdentifier}/versions/{imageVersion}/builds")
    public Response listImageBuilds(@PathParam("imageIdentifier") String imageIdentifier,
                                    @PathParam("imageVersion") String imageVersion,
                                    @Context HttpHeaders headers) {
        return Response.ok(imageService.listImageBuilds(region(headers), imageIdentifier, imageVersion)).build();
    }

    @GET
    @Path("/microvm-images/{imageIdentifier}/versions/{imageVersion}/builds/{buildId}")
    public Response getImageBuild(@PathParam("imageIdentifier") String imageIdentifier,
                                  @PathParam("imageVersion") String imageVersion,
                                  @PathParam("buildId") String buildId,
                                  @Context HttpHeaders headers) {
        return Response.ok(imageService.getImageBuild(
                region(headers), imageIdentifier, imageVersion, buildId)).build();
    }

    // ──────────────────────────── microvm instances ────────────────────────────

    @POST
    @Path("/microvms")
    public Response runMicrovm(@Context HttpHeaders headers, String body) {
        return Response.ok(runtimeService.runMicrovm(
                region(headers), regionResolver.getAccountId(), parseBody(body))).build();
    }

    @GET
    @Path("/microvms")
    public Response listMicrovms(@Context HttpHeaders headers,
                                 @QueryParam("imageIdentifier") String imageIdentifier,
                                 @QueryParam("imageVersion") String imageVersion) {
        return Response.ok(runtimeService.listMicrovms(region(headers), imageIdentifier, imageVersion)).build();
    }

    @GET
    @Path("/microvms/{microvmIdentifier}")
    public Response getMicrovm(@PathParam("microvmIdentifier") String microvmIdentifier,
                               @Context HttpHeaders headers) {
        return Response.ok(runtimeService.getMicrovm(region(headers), microvmIdentifier)).build();
    }

    @DELETE
    @Path("/microvms/{microvmIdentifier}")
    public Response terminateMicrovm(@PathParam("microvmIdentifier") String microvmIdentifier,
                                     @Context HttpHeaders headers) {
        runtimeService.terminateMicrovm(region(headers), microvmIdentifier);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/microvms/{microvmIdentifier}/suspend")
    public Response suspendMicrovm(@PathParam("microvmIdentifier") String microvmIdentifier,
                                   @Context HttpHeaders headers) {
        runtimeService.suspendMicrovm(region(headers), microvmIdentifier);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/microvms/{microvmIdentifier}/resume")
    public Response resumeMicrovm(@PathParam("microvmIdentifier") String microvmIdentifier,
                                  @Context HttpHeaders headers) {
        runtimeService.resumeMicrovm(region(headers), microvmIdentifier);
        return Response.ok(Map.of()).build();
    }

    // ──────────────────────────── auth tokens ────────────────────────────

    @POST
    @Path("/microvms/{microvmIdentifier}/auth-token")
    public Response createAuthToken(@PathParam("microvmIdentifier") String microvmIdentifier,
                                    @Context HttpHeaders headers, String body) {
        Map<String, Object> request = parseBody(body);
        // 404 for unknown MicroVMs before minting anything.
        runtimeService.requireMicrovm(region(headers), microvmIdentifier);
        return Response.ok(tokenService.createToken(
                microvmIdentifier,
                asNumber(request.get("expirationInMinutes")),
                asListOfMaps(request.get("allowedPorts")))).build();
    }

    @POST
    @Path("/microvms/{microvmIdentifier}/shell-auth-token")
    public Response createShellAuthToken(@PathParam("microvmIdentifier") String microvmIdentifier,
                                         @Context HttpHeaders headers, String body) {
        Map<String, Object> request = parseBody(body);
        runtimeService.requireMicrovm(region(headers), microvmIdentifier);
        // Shell tokens are not port-scoped; grant all ports.
        return Response.ok(tokenService.createToken(
                microvmIdentifier,
                asNumber(request.get("expirationInMinutes")),
                List.of(Map.of("allPorts", Map.of())))).build();
    }

    // ──────────────────────────── helpers ────────────────────────────

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Map<String, Object> parseBody(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            return parsed;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Invalid request body: " + e.getMessage(), 400);
        }
    }

    private static Number asNumber(Object value) {
        return value instanceof Number n ? n : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : null;
    }
}
