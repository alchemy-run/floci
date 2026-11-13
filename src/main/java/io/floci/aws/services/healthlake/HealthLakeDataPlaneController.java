package io.floci.aws.services.healthlake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A data store's FHIR R4 API, at its endpoint. A request is authorized as the data store's identity provider
 * configuration says: signed with SigV4 for {@code healthlake} ({@code AWS_AUTH}), or carrying a bearer token its
 * identity provider Lambda authorizes ({@code SMART_ON_FHIR}). It is then forwarded to the data store's FHIR server.
 */
@Path(HealthLakeDataPlaneController.BASE_PREFIX + "{region}/{datastoreId}")
public class HealthLakeDataPlaneController {

    static final String BASE_PREFIX = "/_floci/healthlake/";
    private static final Logger LOG = Logger.getLogger(HealthLakeDataPlaneController.class);
    private static final int MAX_REQUEST_BYTES = 5 * 1024 * 1024;
    private static final Set<String> SKIPPED_REQUEST_HEADERS = Set.of("host", "connection", "content-length",
            "transfer-encoding", "upgrade", "keep-alive", "te", "trailer", "expect", "accept-encoding",
            "authorization", "x-amz-date", "x-amz-security-token", "x-amz-content-sha256");
    private static final Set<String> SKIPPED_RESPONSE_HEADERS = Set.of("connection", "content-length",
            "transfer-encoding", "upgrade", "keep-alive", "te", "trailer");

    private final HealthLakeService service;
    private final Instance<LambdaService> lambda;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Inject
    public HealthLakeDataPlaneController(HealthLakeService service, Instance<LambdaService> lambda,
                                         ObjectMapper mapper) {
        this.service = service;
        this.lambda = lambda;
        this.mapper = mapper;
    }

    @GET
    @Path("{path: .*}")
    public Response get(@PathParam("region") String region, @PathParam("datastoreId") String datastoreId,
                        @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return forward("GET", region, datastoreId, headers, uriInfo, null);
    }

    @POST
    @Path("{path: .*}")
    public Response post(@PathParam("region") String region, @PathParam("datastoreId") String datastoreId,
                         @Context HttpHeaders headers, @Context UriInfo uriInfo, InputStream body) {
        return forward("POST", region, datastoreId, headers, uriInfo, body);
    }

    @PUT
    @Path("{path: .*}")
    public Response put(@PathParam("region") String region, @PathParam("datastoreId") String datastoreId,
                        @Context HttpHeaders headers, @Context UriInfo uriInfo, InputStream body) {
        return forward("PUT", region, datastoreId, headers, uriInfo, body);
    }

    @PATCH
    @Path("{path: .*}")
    public Response patch(@PathParam("region") String region, @PathParam("datastoreId") String datastoreId,
                          @Context HttpHeaders headers, @Context UriInfo uriInfo, InputStream body) {
        return forward("PATCH", region, datastoreId, headers, uriInfo, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@PathParam("region") String region, @PathParam("datastoreId") String datastoreId,
                           @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return forward("DELETE", region, datastoreId, headers, uriInfo, null);
    }

    private Response forward(String method, String region, String datastoreId, HttpHeaders headers,
                             UriInfo uriInfo, InputStream input) {
        Optional<ObjectNode> owned = service.owned(region, datastoreId);
        if (owned.isEmpty() || !"ACTIVE".equals(owned.get().path("DatastoreStatus").asText())) {
            return outcome(404, "not-found", "Data store " + datastoreId + " was not found or is not ACTIVE.");
        }
        ObjectNode datastore = owned.get();
        String path = clientPath(uriInfo.getRequestUri().getRawPath(), region, datastoreId);
        String denied = authorize(datastore, method, path, headers);
        if (denied != null) {
            return outcome(403, "forbidden", denied);
        }
        Optional<HealthLakeFhirServers.Server> server = service.fhirServer(datastore.path("_account").asText(),
                region, datastoreId);
        if (server.isEmpty()) {
            return outcome(503, "transient", "Data store " + datastoreId + " has no FHIR server running: floci "
                    + "serves a data store's FHIR API from a HAPI FHIR container, which needs Docker.");
        }
        byte[] body;
        try {
            body = input == null ? new byte[0] : input.readNBytes(MAX_REQUEST_BYTES + 1);
        } catch (IOException e) {
            return outcome(400, "invalid", "Unable to read the request body: " + e.getMessage());
        }
        if (body.length > MAX_REQUEST_BYTES) {
            return outcome(413, "too-costly", "The request is larger than " + MAX_REQUEST_BYTES + " bytes.");
        }
        String query = uriInfo.getRequestUri().getRawQuery();
        String target = server.get().base() + path + (query != null ? "?" + query : "");
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
            return out.entity(response.body()).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return outcome(504, "timeout", "The request to data store " + datastoreId + " was interrupted.");
        } catch (IOException e) {
            LOG.warnv("HealthLake FHIR forward to {0} failed: {1}", target, e.getMessage());
            return outcome(502, "transient", "Data store " + datastoreId + " is unreachable: " + e.getMessage());
        }
    }

    /** Why a request may not reach the data store, or null when it may. */
    private String authorize(ObjectNode datastore, String method, String path, HttpHeaders headers) {
        JsonNode idp = datastore.path("IdentityProviderConfiguration");
        String strategy = idp.path("AuthorizationStrategy").asText("AWS_AUTH");
        String authorization = headers.getHeaderString("Authorization");
        if ("AWS_AUTH".equals(strategy)) {
            if (authorization == null || !authorization.startsWith("AWS4-HMAC-SHA256")
                    || !authorization.contains("/healthlake/aws4_request")) {
                return "The data store uses AWS_AUTH: sign the request with AWS Signature Version 4 for healthlake.";
            }
            return null;
        }
        if (authorization != null && authorization.startsWith("AWS4-HMAC-SHA256")) {
            return null;
        }
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "The data store uses SMART on FHIR: send the bearer token its identity provider issued.";
        }
        ObjectNode event = mapper.createObjectNode();
        event.put("datastoreEndpoint", datastore.path("DatastoreEndpoint").asText())
                .put("operationName", operation(method, path)).put("bearerToken", authorization.substring(7).trim());
        String lambdaArn = idp.path("IdpLambdaArn").asText();
        try {
            InvokeResult result = lambda.get().invoke(regionOf(lambdaArn), lambdaArn, mapper.writeValueAsBytes(event),
                    InvocationType.RequestResponse);
            if (result.getFunctionError() != null) {
                return "The identity provider Lambda failed: " + new String(result.getPayload(),
                        StandardCharsets.UTF_8);
            }
            JsonNode payload = mapper.readTree(result.getPayload());
            if (!payload.path("authPayload").path("isAuthorized").asBoolean(false)) {
                return "The identity provider did not authorize the token.";
            }
            long expires = payload.path("authPayload").path("exp").asLong(Long.MAX_VALUE);
            if (expires < System.currentTimeMillis() / 1000) {
                return "The token has expired.";
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return "The identity provider Lambda could not be invoked: " + e.getMessage();
        }
    }

    private static String regionOf(String arn) {
        return AwsArnUtils.parse(arn).region();
    }

    /** The FHIR interaction a request is, as HealthLake names it to an identity provider. */
    private static String operation(String method, String path) {
        String[] segments = path.replaceAll("^/+", "").split("/");
        boolean instance = segments.length >= 2 && !segments[1].startsWith("_");
        return switch (method) {
            case "GET" -> instance ? "Read" : "Search";
            case "POST" -> path.contains("_search") ? "Search" : "Create";
            case "PUT" -> "Update";
            case "PATCH" -> "Patch";
            case "DELETE" -> "Delete";
            default -> method;
        };
    }

    static String clientPath(String proxyPath, String region, String datastoreId) {
        String prefix = BASE_PREFIX + region + "/" + datastoreId;
        String path = proxyPath != null && proxyPath.startsWith(prefix) ? proxyPath.substring(prefix.length()) : "";
        return path.isEmpty() ? "/" : path;
    }

    private Response outcome(int status, String code, String message) {
        ObjectNode outcome = mapper.createObjectNode();
        outcome.put("resourceType", "OperationOutcome");
        outcome.putArray("issue").addObject().put("severity", "error").put("code", code)
                .put("diagnostics", message);
        return Response.status(status).type("application/fhir+json").entity(outcome.toString()).build();
    }
}
