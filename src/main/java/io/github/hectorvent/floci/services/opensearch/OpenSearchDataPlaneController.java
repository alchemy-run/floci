package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A domain's search/index REST API. Reached host-style through
 * {@link OpenSearchDataPlaneRoutingFilter} at the endpoint DescribeDomain reports. Each request
 * is authenticated and authorized by {@link OpenSearchDataPlaneAuth} and then forwarded to the
 * domain's backing OpenSearch or Elasticsearch container.
 */
@Path(OpenSearchDataPlaneController.BASE)
@Produces(MediaType.WILDCARD)
@Consumes(MediaType.WILDCARD)
public class OpenSearchDataPlaneController {

    static final String BASE_PREFIX = "/_floci/opensearch-domain/";
    static final String BASE = BASE_PREFIX + "{region}/{domainName}";

    private static final Logger LOG = Logger.getLogger(OpenSearchDataPlaneController.class);
    static final int MAX_REQUEST_BYTES = 100 * 1024 * 1024;
    private static final Duration FORWARD_TIMEOUT = Duration.ofSeconds(60);

    /** Hop-by-hop headers and the front end's own signing headers, never forwarded to the engine. */
    static final Set<String> SKIPPED_REQUEST_HEADERS = Set.of(
            "host", "connection", "content-length", "transfer-encoding", "upgrade", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "expect", "accept-encoding",
            "authorization", "x-amz-date", "x-amz-security-token", "x-amz-content-sha256");
    static final Set<String> SKIPPED_RESPONSE_HEADERS = Set.of(
            "connection", "content-length", "transfer-encoding", "upgrade", "keep-alive",
            "proxy-authenticate", "te", "trailer");

    private final OpenSearchService service;
    private final OpenSearchDataPlaneAuth auth;
    private final ObjectMapper objectMapper;
    // HTTP/1.1 pinned: the default attempts an h2c upgrade on plaintext targets.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Inject
    public OpenSearchDataPlaneController(OpenSearchService service, OpenSearchDataPlaneAuth auth,
                                         ObjectMapper objectMapper) {
        this.service = service;
        this.auth = auth;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("{path: .*}")
    public Response get(@PathParam("region") String region, @PathParam("domainName") String domainName,
                        @Context HttpHeaders headers, @Context UriInfo uriInfo, InputStream body) throws IOException {
        return forward("GET", region, domainName, headers, uriInfo, body);
    }

    @HEAD
    @Path("{path: .*}")
    public Response head(@PathParam("region") String region, @PathParam("domainName") String domainName,
                         @Context HttpHeaders headers, @Context UriInfo uriInfo) throws IOException {
        return forward("HEAD", region, domainName, headers, uriInfo, null);
    }

    @POST
    @Path("{path: .*}")
    public Response post(@PathParam("region") String region, @PathParam("domainName") String domainName,
                         @Context HttpHeaders headers, @Context UriInfo uriInfo, InputStream body) throws IOException {
        return forward("POST", region, domainName, headers, uriInfo, body);
    }

    @PUT
    @Path("{path: .*}")
    public Response put(@PathParam("region") String region, @PathParam("domainName") String domainName,
                        @Context HttpHeaders headers, @Context UriInfo uriInfo, InputStream body) throws IOException {
        return forward("PUT", region, domainName, headers, uriInfo, body);
    }

    @PATCH
    @Path("{path: .*}")
    public Response patch(@PathParam("region") String region, @PathParam("domainName") String domainName,
                          @Context HttpHeaders headers, @Context UriInfo uriInfo, InputStream body) throws IOException {
        return forward("PATCH", region, domainName, headers, uriInfo, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@PathParam("region") String region, @PathParam("domainName") String domainName,
                           @Context HttpHeaders headers, @Context UriInfo uriInfo, InputStream body) throws IOException {
        return forward("DELETE", region, domainName, headers, uriInfo, body);
    }

    private Response forward(String method, String region, String domainName, HttpHeaders headers,
                             UriInfo uriInfo, InputStream input) throws IOException {
        byte[] body = input == null ? new byte[0] : input.readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            return message(413, "Request size exceeded " + MAX_REQUEST_BYTES + " bytes");
        }

        Domain domain;
        try {
            domain = service.describeDomain(domainName);
        } catch (AwsException e) {
            return message(404, "Domain not found: " + domainName);
        }
        if (domain.isDeleted() || domain.getArn() == null
                || !region.equals(AwsArnUtils.parse(domain.getArn()).region())) {
            return message(404, "Domain not found: " + domainName);
        }

        URI requestUri = uriInfo.getRequestUri();
        String rawPath = clientPath(requestUri.getRawPath(), region, domainName);
        String rawQuery = requestUri.getRawQuery();
        Map<String, String> requestHeaders = new HashMap<>();
        headers.getRequestHeaders().forEach((name, values) ->
                requestHeaders.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));

        try {
            auth.authorize(method, rawPath, rawQuery, requestHeaders, body,
                    domain.getArn(), domain.getAccessPolicies());
        } catch (OpenSearchDataPlaneAuth.Denied e) {
            return message(e.status(), e.getMessage());
        }

        String backend = domain.getEndpoint();
        if (backend == null || backend.isBlank()) {
            return message(503, "Domain " + domainName + " has no running search engine");
        }
        String target = backend.replaceAll("/+$", "") + rawPath
                + (rawQuery != null && !rawQuery.isEmpty() ? "?" + rawQuery : "");

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .timeout(FORWARD_TIMEOUT)
                .method(method, body.length > 0
                        ? HttpRequest.BodyPublishers.ofByteArray(body)
                        : HttpRequest.BodyPublishers.noBody());
        requestHeaders.forEach((name, value) -> {
            if (!SKIPPED_REQUEST_HEADERS.contains(name)) {
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException ignored) {
                    // a header the JDK client manages itself
                }
            }
        });

        try {
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
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
            return message(504, "Request to domain " + domainName + " was interrupted");
        } catch (IOException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            LOG.warnv("OpenSearch data-plane forward failed for {0} {1}: {2}", method, target, reason);
            return message(502, "Domain " + domainName + " is unreachable: " + reason);
        }
    }

    /** The path the client requested, recovered from the proxy path the routing filter built. */
    static String clientPath(String proxyRawPath, String region, String domainName) {
        String prefix = BASE_PREFIX + region + "/" + domainName;
        String path = proxyRawPath != null && proxyRawPath.startsWith(prefix)
                ? proxyRawPath.substring(prefix.length())
                : "";
        return path.isEmpty() ? "/" : path;
    }

    private Response message(int status, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Message", message);
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(node.toString()).build();
    }
}
