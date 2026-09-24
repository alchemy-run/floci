package io.github.hectorvent.floci.services.aps;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Path("/workspaces/{workspaceId}/api/v1/{operation: .+}")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.WILDCARD)
public class ApsDataPlaneController {

    static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    private final ApsService service;
    private final ApsDataPlaneAuth auth;

    @Inject
    public ApsDataPlaneController(ApsService service, ApsDataPlaneAuth auth) {
        this.service = service;
        this.auth = auth;
    }

    @GET
    public Response get(@PathParam("workspaceId") String workspaceId,
                        @PathParam("operation") String operation,
                        @Context HttpHeaders headers, @Context UriInfo uri, InputStream body) throws IOException {
        return forward("GET", workspaceId, operation, headers, uri, body);
    }

    @POST
    public Response post(@PathParam("workspaceId") String workspaceId,
                         @PathParam("operation") String operation,
                         @Context HttpHeaders headers, @Context UriInfo uri, InputStream body) throws IOException {
        return forward("POST", workspaceId, operation, headers, uri, body);
    }

    private Response forward(String method, String workspaceId, String operation, HttpHeaders headers,
                             UriInfo uri, InputStream input) throws IOException {
        byte[] body = input == null ? new byte[0] : input.readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new AwsException("ValidationException", "AMP requests must not exceed 16 MiB", 413);
        }
        Map<String, String> requestHeaders = new HashMap<>();
        headers.getRequestHeaders().forEach((name, values) ->
                requestHeaders.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        URI requestUri = uri.getRequestUri();
        requestHeaders.putIfAbsent("host", requestUri.getRawAuthority());
        CredentialScope scope = auth.verify(method, requestUri, requestHeaders, body);
        String action = action(method, operation);
        PrometheusWorkspace workspace = service.describeWorkspace(scope.region(), workspaceId);
        auth.authorize(scope, requestHeaders.get("authorization"), workspace.getArn(), action);
        String backendPath = "remote_write".equals(operation) ? "/api/v1/write"
                : requestUri.getRawPath().substring(requestUri.getRawPath().indexOf("/api/v1/"));
        ApsPrometheusBackend.BackendResponse response = service.forward(scope.region(), workspaceId, method,
                backendPath, requestUri.getRawQuery(), requestHeaders, body);
        return Response.status(response.status()).type(response.contentType()).entity(response.body()).build();
    }

    static String action(String method, String operation) {
        if ("remote_write".equals(operation)) {
            if (!"POST".equals(method)) {
                throw new AwsException("ValidationException", "RemoteWrite requires POST", 405);
            }
            return "aps:RemoteWrite";
        }
        return switch (operation) {
            case "query", "query_range" -> "aps:QueryMetrics";
            case "labels" -> "aps:GetLabels";
            case "series" -> "aps:GetSeries";
            case "metadata" -> "aps:GetMetricMetadata";
            default -> {
                if (operation.matches("label/[^/]+/values")) {
                    yield "aps:GetLabels";
                }
                throw new AwsException("ResourceNotFoundException", "Unsupported Prometheus operation", 404);
            }
        };
    }
}
