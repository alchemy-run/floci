package io.floci.aws.services.grafana;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A workspace's Grafana, at its endpoint: requests {@link GrafanaDataPlaneRoutingFilter} routes here are forwarded to
 * the workspace's container with their credentials (a service account token, an API key, a session cookie), which
 * Grafana itself checks.
 */
@Path(GrafanaDataPlaneController.BASE_PREFIX + "{region}/{workspaceId}")
public class GrafanaDataPlaneController {

    static final String BASE_PREFIX = "/_floci/grafana-workspace/";
    private static final Logger LOG = Logger.getLogger(GrafanaDataPlaneController.class);
    private static final int MAX_REQUEST_BYTES = 50 * 1024 * 1024;
    private static final Set<String> SKIPPED_REQUEST_HEADERS = Set.of("host", "connection", "content-length",
            "transfer-encoding", "upgrade", "keep-alive", "proxy-authenticate", "proxy-authorization", "te",
            "trailer", "expect", "accept-encoding");
    private static final Set<String> SKIPPED_RESPONSE_HEADERS = Set.of("connection", "content-length",
            "transfer-encoding", "upgrade", "keep-alive", "proxy-authenticate", "te", "trailer");

    private final GrafanaService service;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Inject
    public GrafanaDataPlaneController(GrafanaService service) {
        this.service = service;
    }

    @GET
    @Path("{path: .*}")
    public Response get(@PathParam("region") String region, @PathParam("workspaceId") String workspaceId,
                        @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return forward("GET", region, workspaceId, headers, uriInfo, null);
    }

    @HEAD
    @Path("{path: .*}")
    public Response head(@PathParam("region") String region, @PathParam("workspaceId") String workspaceId,
                         @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return forward("HEAD", region, workspaceId, headers, uriInfo, null);
    }

    @POST
    @Path("{path: .*}")
    public Response post(@PathParam("region") String region, @PathParam("workspaceId") String workspaceId,
                         @Context HttpHeaders headers, @Context UriInfo uriInfo, InputStream body) {
        return forward("POST", region, workspaceId, headers, uriInfo, body);
    }

    @PUT
    @Path("{path: .*}")
    public Response put(@PathParam("region") String region, @PathParam("workspaceId") String workspaceId,
                        @Context HttpHeaders headers, @Context UriInfo uriInfo, InputStream body) {
        return forward("PUT", region, workspaceId, headers, uriInfo, body);
    }

    @PATCH
    @Path("{path: .*}")
    public Response patch(@PathParam("region") String region, @PathParam("workspaceId") String workspaceId,
                          @Context HttpHeaders headers, @Context UriInfo uriInfo, InputStream body) {
        return forward("PATCH", region, workspaceId, headers, uriInfo, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@PathParam("region") String region, @PathParam("workspaceId") String workspaceId,
                           @Context HttpHeaders headers, @Context UriInfo uriInfo, InputStream body) {
        return forward("DELETE", region, workspaceId, headers, uriInfo, body);
    }

    private Response forward(String method, String region, String workspaceId, HttpHeaders headers,
                             UriInfo uriInfo, InputStream input) {
        Optional<String> owner = service.owner(region, workspaceId);
        if (owner.isEmpty()) {
            return text(404, "Workspace " + workspaceId + " not found");
        }
        Optional<GrafanaContainerManager.Endpoint> endpoint = service.dataPlane(owner.get(), region, workspaceId)
                .filter(found -> found != null);
        if (endpoint.isEmpty()) {
            return text(503, "Workspace " + workspaceId + " has no Grafana running");
        }
        byte[] body;
        try {
            body = input == null ? new byte[0] : input.readNBytes(MAX_REQUEST_BYTES + 1);
        } catch (IOException e) {
            return text(400, "Unable to read the request body: " + e.getMessage());
        }
        if (body.length > MAX_REQUEST_BYTES) {
            return text(413, "Request size exceeded " + MAX_REQUEST_BYTES + " bytes");
        }
        URI requestUri = uriInfo.getRequestUri();
        String prefix = BASE_PREFIX + region + "/" + workspaceId;
        String path = requestUri.getRawPath().startsWith(prefix) ? requestUri.getRawPath().substring(prefix.length())
                : "/";
        String target = endpoint.get().url() + (path.isEmpty() ? "/" : path)
                + (requestUri.getRawQuery() != null ? "?" + requestUri.getRawQuery() : "");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(60))
                .method(method, body.length > 0 ? HttpRequest.BodyPublishers.ofByteArray(body)
                        : HttpRequest.BodyPublishers.noBody());
        headers.getRequestHeaders().forEach((name, values) -> {
            if (!SKIPPED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                try {
                    request.header(name, String.join(",", values));
                } catch (IllegalArgumentException ignored) {
                    // a header the JDK client manages itself
                }
            }
        });
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            Response.ResponseBuilder out = Response.status(response.statusCode());
            response.headers().map().forEach((name, values) -> {
                if (!SKIPPED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT)) && !name.startsWith(":")) {
                    values.forEach(value -> out.header(name, value));
                }
            });
            if (!"HEAD".equals(method)) {
                out.entity(response.body());
            }
            return out.build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return text(504, "Request to workspace " + workspaceId + " was interrupted");
        } catch (IOException e) {
            LOG.warnv("Grafana data-plane forward to {0} failed: {1}", target, e.getMessage());
            return text(502, "Workspace " + workspaceId + " is unreachable: " + e.getMessage());
        }
    }

    private static Response text(int status, String message) {
        return Response.status(status).type(MediaType.TEXT_PLAIN).entity(message).build();
    }
}
