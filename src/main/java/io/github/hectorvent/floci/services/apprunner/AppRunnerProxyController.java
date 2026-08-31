package io.github.hectorvent.floci.services.apprunner;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reverse-proxies App Runner virtual-host requests onto the service container.
 */
@Path(AppRunnerRoutingFilter.PROXY_PREFIX + "/{serviceId}")
@ApplicationScoped
public class AppRunnerProxyController {

    private static final Logger LOG = Logger.getLogger(AppRunnerProxyController.class);
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final AppRunnerContainerManager containers;

    @Inject
    public AppRunnerProxyController(AppRunnerContainerManager containers) {
        this.containers = containers;
    }

    @GET
    public Response getRoot(@PathParam("serviceId") String serviceId, @Context UriInfo uriInfo,
                            @Context HttpHeaders headers) {
        return proxy("GET", serviceId, "/", uriInfo, headers, null);
    }

    @GET
    @Path("{path:.*}")
    public Response get(@PathParam("serviceId") String serviceId, @PathParam("path") String path,
                        @Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return proxy("GET", serviceId, path, uriInfo, headers, null);
    }

    @HEAD
    @Path("{path:.*}")
    public Response head(@PathParam("serviceId") String serviceId, @PathParam("path") String path,
                         @Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return proxy("HEAD", serviceId, path, uriInfo, headers, null);
    }

    @OPTIONS
    @Path("{path:.*}")
    public Response options(@PathParam("serviceId") String serviceId, @PathParam("path") String path,
                            @Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return proxy("OPTIONS", serviceId, path, uriInfo, headers, null);
    }

    @POST
    @Path("{path:.*}")
    public Response post(@PathParam("serviceId") String serviceId, @PathParam("path") String path,
                         @Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
        return proxy("POST", serviceId, path, uriInfo, headers, body);
    }

    @PUT
    @Path("{path:.*}")
    public Response put(@PathParam("serviceId") String serviceId, @PathParam("path") String path,
                        @Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
        return proxy("PUT", serviceId, path, uriInfo, headers, body);
    }

    @PATCH
    @Path("{path:.*}")
    public Response patch(@PathParam("serviceId") String serviceId, @PathParam("path") String path,
                          @Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
        return proxy("PATCH", serviceId, path, uriInfo, headers, body);
    }

    @DELETE
    @Path("{path:.*}")
    public Response delete(@PathParam("serviceId") String serviceId, @PathParam("path") String path,
                           @Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return proxy("DELETE", serviceId, path, uriInfo, headers, null);
    }

    private Response proxy(String method, String serviceId, String path, UriInfo uriInfo,
                           HttpHeaders headers, byte[] body) {
        Optional<InetSocketAddress> endpoint = containers.endpoint(serviceId);
        if (endpoint.isEmpty()) {
            return Response.status(502).entity("App Runner service is not running").build();
        }
        InetSocketAddress address = endpoint.get();
        String suffix = path == null || path.isBlank() ? "/" : (path.startsWith("/") ? path : "/" + path);
        String query = uriInfo.getRequestUri().getRawQuery();
        String uri = "http://" + address.getHostString() + ":" + address.getPort() + suffix
                + (query == null || query.isBlank() ? "" : "?" + query);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
                    .timeout(Duration.ofSeconds(15));
            HttpRequest.BodyPublisher publisher = body == null || body.length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body);
            builder.method(method, publisher);
            headers.getRequestHeaders().forEach((name, values) -> {
                if (name != null && !HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                    for (String value : values) {
                        try {
                            builder.header(name, value);
                        } catch (IllegalArgumentException ignored) {
                            // Restricted hop-by-hop or malformed header.
                        }
                    }
                }
            });
            HttpResponse<byte[]> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Response.ResponseBuilder out = Response.status(response.statusCode());
            response.headers().map().forEach((name, values) -> {
                if (name != null && !HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                    for (String value : values) {
                        out.header(name, value);
                    }
                }
            });
            byte[] responseBody = response.body();
            if (responseBody != null && responseBody.length > 0 && !"HEAD".equals(method)) {
                out.entity(responseBody);
            }
            return out.build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.status(502).entity("App Runner proxy interrupted").build();
        } catch (Exception e) {
            LOG.debugv(e, "App Runner proxy failed for {0}", serviceId);
            return Response.status(502).entity("App Runner proxy failed: " + e.getMessage()).build();
        }
    }
}
