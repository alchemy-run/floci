package io.github.hectorvent.floci.services.cloudfront.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontRequestRouter;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontServingController;
import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontFunction;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.quarkus.vertx.http.HttpServer;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import jakarta.annotation.PostConstruct;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The emulated CloudFront edge.
 *
 * <p>{@link CloudFrontEdgeRoutingFilter} maps
 * {@code {distributionId}.cloudfront.net} (and distribution aliases) onto
 * {@code /_floci/cloudfront/{distributionId}/...}. This resource then does what
 * the real edge does for a viewer request:
 *
 * <ol>
 *   <li>pick the cache behavior whose path pattern matches;</li>
 *   <li>build a CloudFront event object from the incoming request;</li>
 *   <li>run the behavior's {@code viewer-request} function in the emulated
 *       CloudFront Functions runtime;</li>
 *   <li>return the function's response if it produced one, otherwise forward
 *       the (possibly rewritten) request to the resolved origin — including an
 *       origin the function selected with {@code cf.updateRequestOrigin()};</li>
 *   <li>run the {@code viewer-response} function, if any, over the result.</li>
 * </ol>
 *
 * <p>Caching is deliberately not emulated: every request reaches the origin.
 * Neither is the viewer protocol policy — the emulator serves the request on
 * the scheme it arrived on instead of redirecting http to https, which would
 * bounce a local client at a real AWS hostname.
 */
@Path(CloudFrontEdgeRoutingFilter.EDGE_PREFIX)
@ApplicationScoped
public class CloudFrontEdgeController {

    private static final Logger LOG = Logger.getLogger(CloudFrontEdgeController.class);

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length", "host");

    private final CloudFrontService service;
    private final CloudFrontServingController servingController;
    private final CloudFrontFunctionRuntime runtime;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final ContainerDetector containerDetector;
    private final Vertx vertx;
    /** Quarkus's view of the running gateway — its actual, bound HTTP port. */
    private final HttpServer gateway;

    // Vert.x rather than java.net.http: the edge has to send the origin's own
    // Host header (a bucket's virtual host, a dev server's `localhost:<port>`
    // that Vite matches against `allowedHosts`) while connecting somewhere
    // else entirely, and java.net.http refuses to set `Host` at all.
    private HttpClient proxyClient;

    private volatile String loopbackReplacement;

    @Inject
    public CloudFrontEdgeController(CloudFrontService service,
                                    CloudFrontFunctionRuntime runtime,
                                    ObjectMapper mapper,
                                    EmulatorConfig config,
                                    ContainerDetector containerDetector,
                                    Vertx vertx,
                                    HttpServer gateway,
                                    CloudFrontServingController servingController) {
        this.service = service;
        this.servingController = servingController;
        this.runtime = runtime;
        this.mapper = mapper;
        this.config = config;
        this.containerDetector = containerDetector;
        this.vertx = vertx;
        this.gateway = gateway;
    }

    @PostConstruct
    void init() {
        proxyClient = vertx.createHttpClient(new HttpClientOptions()
                .setMaxPoolSize(50)
                .setConnectTimeout(10_000)
                .setKeepAlive(true)
                // The emulator's own certificates are self-signed.
                .setTrustAll(true)
                .setVerifyHost(false));
    }

    @GET
    @Path("/{distributionId:[^/]+}{path:.*}")
    public Response get(@PathParam("distributionId") String id, @PathParam("path") String path,
                        @Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return handle("GET", id, path, uriInfo, headers, null);
    }

    @HEAD
    @Path("/{distributionId:[^/]+}{path:.*}")
    public Response head(@PathParam("distributionId") String id, @PathParam("path") String path,
                         @Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return handle("HEAD", id, path, uriInfo, headers, null);
    }

    @OPTIONS
    @Path("/{distributionId:[^/]+}{path:.*}")
    public Response options(@PathParam("distributionId") String id, @PathParam("path") String path,
                            @Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return handle("OPTIONS", id, path, uriInfo, headers, null);
    }

    @POST
    @Path("/{distributionId:[^/]+}{path:.*}")
    public Response post(@PathParam("distributionId") String id, @PathParam("path") String path,
                         @Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
        return handle("POST", id, path, uriInfo, headers, body);
    }

    @PUT
    @Path("/{distributionId:[^/]+}{path:.*}")
    public Response put(@PathParam("distributionId") String id, @PathParam("path") String path,
                        @Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
        return handle("PUT", id, path, uriInfo, headers, body);
    }

    @PATCH
    @Path("/{distributionId:[^/]+}{path:.*}")
    public Response patch(@PathParam("distributionId") String id, @PathParam("path") String path,
                          @Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
        return handle("PATCH", id, path, uriInfo, headers, body);
    }

    @DELETE
    @Path("/{distributionId:[^/]+}{path:.*}")
    public Response delete(@PathParam("distributionId") String id, @PathParam("path") String path,
                           @Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
        return handle("DELETE", id, path, uriInfo, headers, body);
    }

    // ── Edge pipeline ─────────────────────────────────────────────────────────

    private Response handle(String method, String distributionId, String rawPath,
                            UriInfo uriInfo, HttpHeaders headers, byte[] body) {
        Distribution distribution;
        try {
            distribution = service.getDistribution(distributionId);
        } catch (AwsException e) {
            return edgeError(404, "The distribution does not exist.");
        }

        // Take the path off the raw request URI rather than the path param:
        // CloudFront hands the function the URI exactly as the viewer sent it,
        // percent-encoding and trailing slash included, and JAX-RS template
        // matching normalizes both away.
        String uri = rawEdgePath(uriInfo, distributionId, rawPath);
        String query = uriInfo.getRequestUri().getRawQuery();
        Response rejected = servingController.validateEdgeViewerRequest(distribution, uri, method);
        if (rejected != null) {
            return rejected;
        }

        String normalizedPath = CloudFrontRequestRouter.normalizePath(URI.create("http://viewer.invalid" + uri).getPath());
        CacheBehaviorView behavior = matchBehavior(distribution, normalizedPath);
        ObjectNode event = buildEvent(distribution, method, uri, query, headers);

        CloudFrontFunction viewerRequest = resolveFunction(behavior.functionArn("viewer-request"));
        CloudFrontFunctionRuntime.Execution execution = null;
        if (viewerRequest != null) {
            execution = runtime.execute(viewerRequest, event);
            if (!execution.ok()) {
                LOG.errorv("CloudFront Function {0} failed: {1}", viewerRequest.getName(), execution.error());
                return edgeError(502, "The CloudFront Function failed: " + execution.error());
            }
            execution.logs().forEach(log -> LOG.debugv("cloudfront-function: {0}", log));
            if (execution.isResponse()) {
                Response response = servingController.applyEdgeResponse(distribution, uri, method, headers,
                        uriInfo.getRequestUri().getScheme(), fromFunctionResponse(execution.output()), false);
                return viewerResponse(distribution, behavior, response, event);
            }
            event.set("request", execution.output());
        }

        JsonNode request = event.path("request");
        Origin origin = resolveOrigin(distribution, behavior,
                execution != null ? execution.origin() : null,
                execution != null ? execution.originId() : null);
        if (origin == null) {
            return edgeError(502, "No origin is configured for this cache behavior.");
        }

        try {
            Response response = forward(distribution, method, request, origin, body,
                    uriInfo.getRequestUri().getScheme());
            response = servingController.applyEdgeResponse(distribution, uri, method, headers,
                    uriInfo.getRequestUri().getScheme(), response, true);
            return viewerResponse(distribution, behavior, response, event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return edgeError(502, "Interrupted while forwarding to " + origin.getDomainName() + ".");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return edgeError(502, "The origin " + origin.getDomainName()
                    + " is not reachable: " + cause);
        }
    }

    private static String rawEdgePath(UriInfo uriInfo, String distributionId, String fallback) {
        String requestPath = uriInfo.getRequestUri().getRawPath();
        String prefix = CloudFrontEdgeRoutingFilter.EDGE_PREFIX + "/" + distributionId;
        String uri = requestPath != null && requestPath.startsWith(prefix)
                ? requestPath.substring(prefix.length())
                : (fallback == null ? "" : fallback);
        if (uri.isEmpty()) {
            return "/";
        }
        return uri.startsWith("/") ? uri : "/" + uri;
    }

    /** Run the viewer-response function, when the behavior has one. */
    private Response viewerResponse(Distribution distribution, CacheBehaviorView behavior,
                                    Response response, ObjectNode requestEvent) {
        CloudFrontFunction function = resolveFunction(behavior.functionArn("viewer-response"));
        if (function == null) {
            return response;
        }
        ObjectNode event = requestEvent.deepCopy();
        ((ObjectNode) event.get("context")).put("eventType", "viewer-response");
        ObjectNode responseNode = event.putObject("response");
        responseNode.put("statusCode", response.getStatus());
        responseNode.put("statusDescription", response.getStatusInfo().getReasonPhrase());
        ObjectNode responseHeaders = responseNode.putObject("headers");
        response.getStringHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                responseHeaders.putObject(name.toLowerCase(Locale.ROOT)).put("value", values.get(0));
            }
        });
        CloudFrontFunctionRuntime.Execution execution = runtime.execute(function, event);
        if (!execution.ok()) {
            LOG.errorv("CloudFront Function {0} failed: {1}", function.getName(), execution.error());
            return edgeError(502, "The CloudFront Function failed: " + execution.error());
        }
        JsonNode output = execution.output();
        Response.ResponseBuilder builder = Response.status(output.path("statusCode").asInt(response.getStatus()));
        output.path("headers").fields().forEachRemaining(entry ->
                builder.header(entry.getKey(), entry.getValue().path("value").asText("")));
        return builder.entity(response.getEntity()).build();
    }

    // ── Event object ──────────────────────────────────────────────────────────

    private ObjectNode buildEvent(Distribution distribution, String method, String uri,
                                  String query, HttpHeaders headers) {
        ObjectNode event = mapper.createObjectNode();
        event.put("version", "1.0");
        ObjectNode context = event.putObject("context");
        context.put("distributionDomainName", distribution.getDomainName());
        context.put("distributionId", distribution.getId());
        context.put("eventType", "viewer-request");
        context.put("requestId", UUID.randomUUID().toString());
        event.putObject("viewer").put("ip", "127.0.0.1");

        ObjectNode request = event.putObject("request");
        request.put("method", method);
        request.put("uri", uri);

        ObjectNode querystring = request.putObject("querystring");
        if (query != null && !query.isBlank()) {
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = decode(eq < 0 ? pair : pair.substring(0, eq));
                String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
                addMultiValue(querystring, key, value);
            }
        }

        ObjectNode requestHeaders = request.putObject("headers");
        ObjectNode cookies = request.putObject("cookies");
        headers.getRequestHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("cookie")) {
                for (String header : values) {
                    for (String cookie : header.split(";")) {
                        int eq = cookie.indexOf('=');
                        if (eq > 0) {
                            addMultiValue(cookies, cookie.substring(0, eq).trim(), cookie.substring(eq + 1).trim());
                        }
                    }
                }
                return;
            }
            for (String value : values) {
                addMultiValue(requestHeaders, lower, value);
            }
        });
        return event;
    }

    /** CloudFront models repeated keys as {@code {value, multiValue:[{value}...]}}. */
    private void addMultiValue(ObjectNode parent, String key, String value) {
        ObjectNode existing = (ObjectNode) parent.get(key);
        if (existing == null) {
            parent.putObject(key).put("value", value);
            return;
        }
        var multiValue = existing.has("multiValue")
                ? (com.fasterxml.jackson.databind.node.ArrayNode) existing.get("multiValue")
                : existing.putArray("multiValue");
        if (multiValue.isEmpty()) {
            multiValue.addObject().put("value", existing.path("value").asText(""));
        }
        multiValue.addObject().put("value", value);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    // ── Behaviors, functions and origins ──────────────────────────────────────

    /** A cache behavior plus its function associations, defaulted or path-matched. */
    private record CacheBehaviorView(String targetOriginId, List<Map<String, String>> functionAssociations) {
        String functionArn(String eventType) {
            if (functionAssociations == null) {
                return null;
            }
            for (Map<String, String> association : functionAssociations) {
                if (eventType.equals(association.get("EventType"))) {
                    return association.get("FunctionARN");
                }
            }
            return null;
        }
    }

    private CacheBehaviorView matchBehavior(Distribution distribution, String uri) {
        var config = distribution.getConfig();
        if (config != null && config.getCacheBehaviors() != null) {
            for (CacheBehavior behavior : config.getCacheBehaviors()) {
                if (matchesPattern(behavior.getPathPattern(), uri)) {
                    return new CacheBehaviorView(behavior.getTargetOriginId(), behavior.getFunctionAssociations());
                }
            }
        }
        var defaultBehavior = config != null ? config.getDefaultCacheBehavior() : null;
        return defaultBehavior == null
                ? new CacheBehaviorView(null, List.of())
                : new CacheBehaviorView(defaultBehavior.getTargetOriginId(),
                        defaultBehavior.getFunctionAssociations());
    }

    /** CloudFront path patterns support {@code *} and {@code ?} wildcards. */
    static boolean matchesPattern(String pattern, String uri) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        return CloudFrontRequestRouter.pathPatternMatches(pattern, uri);
    }

    private CloudFrontFunction resolveFunction(String functionArn) {
        if (functionArn == null || functionArn.isBlank()) {
            return null;
        }
        String name = functionArn.substring(functionArn.lastIndexOf('/') + 1);
        try {
            return service.describeFunction(name, "LIVE");
        } catch (AwsException e) {
            LOG.warnv("Cache behavior references CloudFront Function {0}, which is not published: {1}",
                    name, e.getMessage());
            return null;
        }
    }

    private Origin resolveOrigin(Distribution distribution, CacheBehaviorView behavior,
                                 JsonNode override, String selectedOriginId) {
        if (override != null && override.hasNonNull("domainName")) {
            Origin origin = new Origin();
            origin.setDomainName(override.path("domainName").asText());
            JsonNode custom = override.get("customOriginConfig");
            if (custom != null && !custom.isNull()) {
                Map<String, Object> customConfig = new LinkedHashMap<>();
                if (custom.hasNonNull("protocol")) {
                    customConfig.put("OriginProtocolPolicy", custom.path("protocol").asText() + "-only");
                }
                if (custom.hasNonNull("port")) {
                    customConfig.put("HTTPSPort", String.valueOf(custom.path("port").asInt()));
                    customConfig.put("HTTPPort", String.valueOf(custom.path("port").asInt()));
                }
                origin.setCustomOriginConfig(customConfig);
            }
            return origin;
        }
        var config = distribution.getConfig();
        if (config == null || config.getOrigins() == null) {
            return null;
        }
        String wanted = selectedOriginId != null ? selectedOriginId : behavior.targetOriginId();
        for (Origin origin : config.getOrigins()) {
            if (origin.getId() != null && origin.getId().equals(wanted)) {
                return origin;
            }
        }
        return config.getOrigins().isEmpty() ? null : config.getOrigins().get(0);
    }

    // ── Origin forwarding ─────────────────────────────────────────────────────

    private Response forward(Distribution distribution, String method, JsonNode request, Origin origin, byte[] body,
                             String viewerScheme) throws Exception {
        String domain = origin.getDomainName();
        String host = domain;
        int embeddedPort = -1;
        int colon = domain.lastIndexOf(':');
        if (colon > 0 && domain.substring(colon + 1).chars().allMatch(Character::isDigit)) {
            host = domain.substring(0, colon);
            embeddedPort = Integer.parseInt(domain.substring(colon + 1));
        }

        String scheme = originScheme(origin, embeddedPort, viewerScheme);
        int port = embeddedPort > 0 ? embeddedPort : originPort(origin, scheme);

        String uri = request.path("uri").asText("/");
        String forwardUri = CloudFrontRequestRouter.resolveForwardUri(origin.getOriginPath(), uri,
                distribution.getConfig().getDefaultRootObject());
        String query = serializeQuerystring(request.path("querystring"));
        Map<String, String> outboundHeaders = new LinkedHashMap<>();
        request.path("headers").fields().forEachRemaining(entry -> {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(name)) {
                outboundHeaders.put(name, entry.getValue().path("value").asText(""));
            }
        });
        String cookie = serializeCookies(request.path("cookies"));
        if (!cookie.isEmpty()) {
            outboundHeaders.put("cookie", cookie);
        }
        if (CloudFrontRequestRouter.isS3Origin(origin) && List.of("GET", "HEAD", "OPTIONS").contains(method)) {
            return servingController.serveEdgeS3Origin(distribution, origin, uri, method, outboundHeaders);
        }
        if (origin.getCustomHeaders() != null) {
            for (Map<String, String> header : origin.getCustomHeaders()) {
                outboundHeaders.put(header.get("HeaderName").toLowerCase(Locale.ROOT), header.get("HeaderValue"));
            }
        }

        // An origin that is itself an emulated AWS endpoint (an S3 bucket, a
        // Lambda function URL) is served by this same process: connect back to
        // the gateway while keeping the AWS Host, so the virtual-host filters
        // resolve it exactly as they would for a direct client.
        String targetHost;
        int targetPort;
        if (host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn")) {
            targetHost = "127.0.0.1";
            // The port it is really listening on, which is the configured one
            // everywhere except a test (quarkus.http.port=0 binds an ephemeral one).
            targetPort = gateway.getPort() > 0 ? gateway.getPort() : config.port();
            scheme = "http";
        } else {
            targetHost = reachableHost(host);
            targetPort = port;
            URI target = CloudFrontServingController.buildCustomOriginUri(
                    scheme, targetHost, targetPort, forwardUri, query.isEmpty() ? null : query.substring(1));
            outboundHeaders.put("host", domain);
            return servingController.forwardEdgeCustomOrigin(target, method, outboundHeaders, body);
        }

        RequestOptions options = new RequestOptions()
                .setMethod(HttpMethod.valueOf(method))
                // Connect here...
                .setServer(SocketAddress.inetSocketAddress(targetPort, targetHost))
                // ...but address the origin's own authority.
                .setHost(host)
                .setPort(port)
                .setURI(forwardUri + query)
                .setSsl("https".equals(scheme))
                .setTimeout(30_000);

        return proxyClient.request(options)
                .compose(originRequest -> {
                    outboundHeaders.forEach(originRequest::putHeader);
                    originRequest.putHeader("Host", domain);
                    return body != null && body.length > 0
                            ? originRequest.send(Buffer.buffer(body))
                            : originRequest.send();
                })
                .compose(originResponse -> {
                    int status = originResponse.statusCode();
                    List<Map.Entry<String, String>> headers = new ArrayList<>();
                    originResponse.headers().forEach(entry -> {
                        if (!HOP_BY_HOP.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                            headers.add(entry);
                        }
                    });
                    return originResponse.body().map(buffer -> {
                        Response.ResponseBuilder out = Response.status(status);
                        headers.forEach(entry -> out.header(entry.getKey(), entry.getValue()));
                        return out.entity(buffer.getBytes()).build();
                    });
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(35, TimeUnit.SECONDS);
    }

    /**
     * CloudFront takes the scheme from the origin's {@code customOriginConfig}.
     * An origin whose domain name carries an explicit port can only come from
     * the emulator (CloudFront rejects ports in {@code DomainName}), and always
     * means a local dev server, so it defaults to plain HTTP.
     */
    private String originScheme(Origin origin, int embeddedPort, String viewerScheme) {
        Map<String, Object> custom = origin.getCustomOriginConfig();
        if (custom != null && custom.get("OriginProtocolPolicy") != null) {
            String policy = String.valueOf(custom.get("OriginProtocolPolicy"));
            if ("match-viewer".equals(policy)) {
                return "https".equalsIgnoreCase(viewerScheme) ? "https" : "http";
            }
            return "http-only".equals(policy) ? "http" : "https";
        }
        return embeddedPort > 0 ? "http" : "https";
    }

    private int originPort(Origin origin, String scheme) {
        Map<String, Object> custom = origin.getCustomOriginConfig();
        String key = "http".equals(scheme) ? "HTTPPort" : "HTTPSPort";
        if (custom != null && custom.get(key) != null) {
            try {
                return Integer.parseInt(String.valueOf(custom.get(key)));
            } catch (NumberFormatException e) {
                LOG.debugv("Origin {0} has a non-numeric {1}", origin.getDomainName(), key);
            }
        }
        return "http".equals(scheme) ? 80 : 443;
    }

    /**
     * When floci itself runs in a container, a loopback origin means the
     * developer's machine, not the container.
     */
    private String reachableHost(String host) {
        if (!host.equals("localhost") && !host.equals("127.0.0.1") && !host.equals("::1")) {
            return host;
        }
        if (!containerDetector.isRunningInContainer()) {
            return host;
        }
        String replacement = loopbackReplacement;
        if (replacement == null) {
            replacement = resolveLoopbackReplacement();
            loopbackReplacement = replacement;
        }
        return replacement;
    }

    private String resolveLoopbackReplacement() {
        String configured = config.services().cloudfront().originLoopbackHost().orElse(null);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        for (String candidate : List.of("host.docker.internal", "172.17.0.1")) {
            try {
                InetAddress.getByName(candidate);
                LOG.infov("Loopback CloudFront origins resolve to {0} from inside the container", candidate);
                return candidate;
            } catch (Exception ignored) {
                LOG.debugv("Loopback replacement candidate {0} does not resolve", candidate);
            }
        }
        LOG.warn("Floci runs in a container but no host-gateway address resolves; "
                + "loopback CloudFront origins will not be reachable. Run the container with "
                + "--add-host=host.docker.internal:host-gateway or set "
                + "FLOCI_SERVICES_CLOUDFRONT_ORIGIN_LOOPBACK_HOST.");
        return "localhost";
    }

    private static String serializeQuerystring(JsonNode querystring) {
        StringBuilder sb = new StringBuilder();
        querystring.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            if (node.has("multiValue")) {
                node.get("multiValue").forEach(value -> append(sb, entry.getKey(), value.path("value").asText("")));
                return;
            }
            append(sb, entry.getKey(), node.path("value").asText(""));
        });
        return sb.isEmpty() ? "" : "?" + sb;
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (!sb.isEmpty()) {
            sb.append('&');
        }
        sb.append(java.net.URLEncoder.encode(key, StandardCharsets.UTF_8));
        if (!value.isEmpty()) {
            sb.append('=').append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }

    private static String serializeCookies(JsonNode cookies) {
        StringBuilder sb = new StringBuilder();
        cookies.fields().forEachRemaining(entry -> {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue().path("value").asText(""));
        });
        return sb.toString();
    }

    private Response fromFunctionResponse(JsonNode output) {
        Response.ResponseBuilder builder = Response.status(output.path("statusCode").asInt(200));
        output.path("headers").fields().forEachRemaining(entry ->
                builder.header(entry.getKey(), entry.getValue().path("value").asText("")));
        output.path("cookies").fields().forEachRemaining(entry ->
                builder.header("set-cookie", entry.getKey() + "=" + entry.getValue().path("value").asText("")));
        JsonNode body = output.get("body");
        if (body != null && body.hasNonNull("data")) {
            String data = body.path("data").asText("");
            builder.entity("base64".equals(body.path("encoding").asText("text"))
                    ? Base64.getDecoder().decode(data)
                    : data.getBytes(StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private Response edgeError(int status, String message) {
        return Response.status(status)
                .type(MediaType.TEXT_PLAIN)
                .header("x-cache", "Error from cloudfront")
                .entity(message)
                .build();
    }
}
