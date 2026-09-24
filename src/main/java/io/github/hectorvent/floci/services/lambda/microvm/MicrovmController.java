package io.github.hectorvent.floci.services.lambda.microvm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/** MicroVM suspend/resume and endpoint token routes, sharing the Docker-backed control plane. */
@Path("/2025-09-09")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class MicrovmController {

    private final MicrovmRuntimeService runtimeService;
    private final MicrovmAuthTokenService tokenService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MicrovmController(MicrovmRuntimeService runtimeService,
                             MicrovmAuthTokenService tokenService, RegionResolver regionResolver,
                             ObjectMapper objectMapper) {
        this.runtimeService = runtimeService;
        this.tokenService = tokenService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
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
