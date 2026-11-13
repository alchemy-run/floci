package io.floci.aws.services.apprunner;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Forwards requests for an App Runner service to its container, as App Runner's load balancer does:
 * with the original host in {@code X-Forwarded-Host} and the scheme in {@code X-Forwarded-Proto}.
 * A host that names no running service answers 404.
 */
@Path("/_apprunner/proxy")
@Consumes(MediaType.WILDCARD)
public class AppRunnerProxyController {
    private static final Set<String> HOP_BY_HOP = Set.of("host", "connection", "content-length", "expect", "upgrade",
            "keep-alive", "proxy-connection", "te", "trailer", "transfer-encoding", "http2-settings");
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();

    private final AppRunnerService service;

    @Inject
    public AppRunnerProxyController(AppRunnerService service) {
        this.service = service;
    }

    @GET
    public Response get(@Context ContainerRequestContext request, @Context HttpHeaders headers) {
        return forward("GET", request, headers, null);
    }

    @HEAD
    public Response head(@Context ContainerRequestContext request, @Context HttpHeaders headers) {
        return forward("HEAD", request, headers, null);
    }

    @OPTIONS
    public Response options(@Context ContainerRequestContext request, @Context HttpHeaders headers) {
        return forward("OPTIONS", request, headers, null);
    }

    @DELETE
    public Response delete(@Context ContainerRequestContext request, @Context HttpHeaders headers) {
        return forward("DELETE", request, headers, null);
    }

    @POST
    public Response post(@Context ContainerRequestContext request, @Context HttpHeaders headers, byte[] body) {
        return forward("POST", request, headers, body);
    }

    @PUT
    public Response put(@Context ContainerRequestContext request, @Context HttpHeaders headers, byte[] body) {
        return forward("PUT", request, headers, body);
    }

    @PATCH
    public Response patch(@Context ContainerRequestContext request, @Context HttpHeaders headers, byte[] body) {
        return forward("PATCH", request, headers, body);
    }

    private Response forward(String method, ContainerRequestContext request, HttpHeaders headers, byte[] body) {
        String host = (String) request.getProperty(AppRunnerProxyFilter.HOST_PROPERTY);
        String path = (String) request.getProperty(AppRunnerProxyFilter.PATH_PROPERTY);
        if (host == null) {
            return Response.status(404).build();
        }
        Optional<AppRunnerRuntime.Running> target;
        try {
            target = service.target(host);
        } catch (RuntimeException e) {
            return Response.status(502).type("text/plain").entity("The App Runner service failed to start: "
                    + e.getMessage()).build();
        }
        if (target.isEmpty()) {
            return Response.status(404).type("text/plain").entity("404 Not Found").build();
        }
        HttpRequest.Builder outgoing = HttpRequest.newBuilder(URI.create("http://" + target.get().host() + ":"
                        + target.get().port() + path)).timeout(Duration.ofSeconds(120))
                .method(method, body == null || body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        for (Map.Entry<String, List<String>> header : headers.getRequestHeaders().entrySet()) {
            if (!HOP_BY_HOP.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                for (String value : header.getValue()) {
                    outgoing.header(header.getKey(), value);
                }
            }
        }
        outgoing.header("X-Forwarded-Host", host);
        outgoing.header("X-Forwarded-Proto", "https");
        HttpResponse<byte[]> response;
        try {
            response = CLIENT.send(outgoing.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            return Response.status(502).type("text/plain").entity("502 Bad Gateway").build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.status(504).build();
        }
        Response.ResponseBuilder out = Response.status(response.statusCode());
        response.headers().map().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT)) && !name.startsWith(":")) {
                values.forEach(value -> out.header(name, value));
            }
        });
        if (!"HEAD".equals(method)) {
            out.entity(response.body());
        }
        return out.build();
    }
}
