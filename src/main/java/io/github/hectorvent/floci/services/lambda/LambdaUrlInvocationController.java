package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaUrlConfig;
import io.github.hectorvent.floci.services.lambda.model.StreamingPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import jakarta.ws.rs.core.StreamingOutput;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Handles Lambda Function URL invocations.
 *
 * Supports host-based routing if possible, but also path-based routing:
 * /lambda-url/{urlId}/{proxy: .*}
 */
@Path("/lambda-url/{urlId}")
@Produces(MediaType.WILDCARD)
@Consumes(MediaType.WILDCARD)
public class LambdaUrlInvocationController {

    private static final Logger LOG = Logger.getLogger(LambdaUrlInvocationController.class);

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaUrlInvocationController(LambdaService lambdaService, RegionResolver regionResolver,
                                         ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/{proxy: .*}")
    public Response handleGet(@PathParam("urlId") String urlId, @PathParam("proxy") String proxy,
                              @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return invoke("GET", urlId, proxy, headers, uriInfo, null);
    }

    @POST
    @Path("/{proxy: .*}")
    public Response handlePost(@PathParam("urlId") String urlId, @PathParam("proxy") String proxy,
                               @Context HttpHeaders headers, @Context UriInfo uriInfo, byte[] body) {
        return invoke("POST", urlId, proxy, headers, uriInfo, body);
    }

    @PUT
    @Path("/{proxy: .*}")
    public Response handlePut(@PathParam("urlId") String urlId, @PathParam("proxy") String proxy,
                              @Context HttpHeaders headers, @Context UriInfo uriInfo, byte[] body) {
        return invoke("PUT", urlId, proxy, headers, uriInfo, body);
    }

    @DELETE
    @Path("/{proxy: .*}")
    public Response handleDelete(@PathParam("urlId") String urlId, @PathParam("proxy") String proxy,
                                 @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return invoke("DELETE", urlId, proxy, headers, uriInfo, null);
    }

    @PATCH
    @Path("/{proxy: .*}")
    public Response handlePatch(@PathParam("urlId") String urlId, @PathParam("proxy") String proxy,
                                @Context HttpHeaders headers, @Context UriInfo uriInfo, byte[] body) {
        return invoke("PATCH", urlId, proxy, headers, uriInfo, body);
    }

    private Response invoke(String method, String urlId, String proxy, HttpHeaders headers, UriInfo uriInfo, byte[] body) {
        Object target = lambdaService.getTargetByUrlId(urlId);
        String functionName;
        String region;
        LambdaUrlConfig urlConfig;

        if (target instanceof LambdaAlias alias) {
            functionName = alias.getFunctionName();
            region = AwsArnUtils.parse(alias.getAliasArn()).region();
            urlConfig = alias.getUrlConfig();
        } else if (target instanceof LambdaFunction fn) {
            functionName = fn.getFunctionName();
            region = AwsArnUtils.parse(fn.getFunctionArn()).region();
            urlConfig = fn.getUrlConfig();
        } else {
            return Response.status(404).entity(jsonMessage("Function URL not found")).type(MediaType.APPLICATION_JSON).build();
        }

        String requestId = UUID.randomUUID().toString();
        String event = buildEvent(method, urlId, proxy, headers, uriInfo, body, requestId, region);

        LOG.infov("Lambda URL invocation: {0} {1} -> {2} (region: {3})", method, urlId, functionName, region);

        boolean responseStream = urlConfig != null && "RESPONSE_STREAM".equals(urlConfig.getInvokeMode());
        try {
            InvokeResult result = lambdaService.invoke(region, functionName, event.getBytes(), InvocationType.RequestResponse);
            if (result.isStreaming()) {
                return buildStreamedResponse(result, responseStream);
            }
            return buildResponse(result);
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus()).entity(e.getMessage()).build();
        }
    }

    private String buildEvent(String method, String urlId, String proxy, HttpHeaders headers, UriInfo uriInfo, byte[] body, String requestId, String region) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", "2.0");
        root.put("routeKey", "$default");
        String rawPath = "/" + (proxy != null ? proxy : "");
        root.put("rawPath", rawPath);
        root.put("rawQueryString", uriInfo.getRequestUri().getRawQuery() != null ? uriInfo.getRequestUri().getRawQuery() : "");

        ObjectNode headersNode = root.putObject("headers");
        headers.getRequestHeaders().forEach((k, v) -> headersNode.put(k.toLowerCase(), String.join(",", v)));

        ObjectNode queryParams = root.putObject("queryStringParameters");
        uriInfo.getQueryParameters().forEach((k, v) -> queryParams.put(k, String.join(",", v)));

        ObjectNode ctx = root.putObject("requestContext");
        ctx.put("accountId", regionResolver.getAccountId());
        ctx.put("apiId", urlId);
        ctx.put("domainName", urlId + ".lambda-url." + region + ".localhost");
        ctx.put("domainPrefix", urlId);
        ctx.put("requestId", requestId);
        ctx.put("routeKey", "$default");
        ctx.put("stage", "$default");
        ctx.put("time", DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z").withZone(ZoneOffset.UTC).format(Instant.now()));
        ctx.put("timeEpoch", System.currentTimeMillis());

        ObjectNode httpNode = ctx.putObject("http");
        httpNode.put("method", method);
        httpNode.put("path", rawPath);
        httpNode.put("protocol", "HTTP/1.1");
        httpNode.put("sourceIp", "127.0.0.1");
        httpNode.put("userAgent", headers.getHeaderString("user-agent"));

        if (body != null && body.length > 0) {
            root.put("body", new String(body));
            root.put("isBase64Encoded", false);
        } else {
            root.putNull("body");
            root.put("isBase64Encoded", false);
        }

        return root.toString();
    }

    private Response buildResponse(InvokeResult result) {
        if (result.getPayload() == null || result.getPayload().length == 0) {
            return Response.status(result.getStatusCode()).build();
        }
        try {
            JsonNode node = objectMapper.readTree(result.getPayload());
            if (node.isObject() && node.has("statusCode")) {
                int status = node.get("statusCode").asInt();
                Response.ResponseBuilder builder = Response.status(status);
                if (node.has("headers")) {
                    node.get("headers").fields().forEachRemaining(e -> builder.header(e.getKey(), e.getValue().asText()));
                }
                if (node.has("body")) {
                    String body = node.get("body").asText();
                    boolean isBase64 = node.path("isBase64Encoded").asBoolean(false);
                    byte[] bytes = isBase64 ? Base64.getDecoder().decode(body) : body.getBytes();
                    builder.entity(bytes);
                }
                return builder.build();
            } else {
                return Response.ok(result.getPayload()).type(MediaType.APPLICATION_JSON).build();
            }
        } catch (Exception e) {
            return Response.ok(result.getPayload()).build();
        }
    }

    /**
     * Builds the HTTP response for a streaming invocation result
     * (awslambda.streamifyResponse). With {@code InvokeMode=RESPONSE_STREAM} body
     * chunks pass through unbuffered (flushed as they arrive); with the default
     * BUFFERED mode the stream is drained and returned whole. Either way the
     * {@code application/vnd.awslambda.http-integration-response} prelude
     * (JSON metadata, an 8-NUL delimiter, then the raw body) is parsed off the
     * front when present.
     */
    private Response buildStreamedResponse(InvokeResult result, boolean passThrough) {
        StreamingPayload stream = result.getStream();
        boolean framed = stream.getContentType() != null
                && stream.getContentType().startsWith("application/vnd.awslambda.http-integration-response");

        int status = 200;
        ObjectNode prelude = null;
        byte[] initialBody = new byte[0];
        try {
            if (framed) {
                Prelude parsed = readPrelude(stream);
                prelude = parsed.metadata();
                initialBody = parsed.remainder();
                if (prelude.has("statusCode")) {
                    status = prelude.get("statusCode").asInt();
                }
            }
        } catch (Exception e) {
            LOG.warnv("Failed to parse streaming response prelude: {0}", e.getMessage());
            return Response.status(500).entity(jsonMessage("Malformed streaming response"))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        Response.ResponseBuilder builder = Response.status(status);
        if (prelude != null) {
            if (prelude.has("headers")) {
                prelude.get("headers").fields()
                        .forEachRemaining(e -> builder.header(e.getKey(), e.getValue().asText()));
            }
            if (prelude.has("cookies") && prelude.get("cookies").isArray()) {
                prelude.get("cookies").forEach(c -> builder.header("Set-Cookie", c.asText()));
            }
        } else if (stream.getContentType() != null) {
            builder.header("Content-Type", stream.getContentType());
        }

        if (passThrough) {
            byte[] head = initialBody;
            StreamingOutput out = output -> {
                try {
                    if (head.length > 0) {
                        output.write(head);
                        output.flush();
                    }
                    byte[] chunk;
                    while ((chunk = stream.next()) != null) {
                        output.write(chunk);
                        output.flush();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (TimeoutException e) {
                    LOG.warnv("Streaming response stalled: {0}", e.getMessage());
                }
            };
            return builder.entity(out).build();
        }

        try {
            byte[] rest = stream.drain();
            byte[] full = new byte[initialBody.length + rest.length];
            System.arraycopy(initialBody, 0, full, 0, initialBody.length);
            System.arraycopy(rest, 0, full, initialBody.length, rest.length);
            return builder.entity(full).build();
        } catch (Exception e) {
            LOG.warnv("Failed to drain streaming response: {0}", e.getMessage());
            return Response.status(500).entity(jsonMessage("Truncated streaming response"))
                    .type(MediaType.APPLICATION_JSON).build();
        }
    }

    record Prelude(ObjectNode metadata, byte[] remainder) {}

    /**
     * Reads chunks until the 8-NUL prelude delimiter is found (it may span chunk
     * boundaries), parses the JSON metadata before it, and returns any body bytes
     * already received after it.
     */
    Prelude readPrelude(StreamingPayload stream) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        while (true) {
            byte[] accumulated = buffer.toByteArray();
            int delimiter = indexOfDelimiter(accumulated);
            if (delimiter >= 0) {
                JsonNode metadata = objectMapper.readTree(accumulated, 0, delimiter);
                if (!(metadata instanceof ObjectNode metadataObject)) {
                    throw new IllegalStateException("Prelude metadata is not a JSON object");
                }
                byte[] remainder = java.util.Arrays.copyOfRange(
                        accumulated, delimiter + PRELUDE_DELIMITER_LENGTH, accumulated.length);
                return new Prelude(metadataObject, remainder);
            }
            byte[] chunk = stream.next();
            if (chunk == null) {
                throw new IllegalStateException("Stream ended before prelude delimiter");
            }
            buffer.write(chunk);
        }
    }

    private static final int PRELUDE_DELIMITER_LENGTH = 8;

    static int indexOfDelimiter(byte[] data) {
        int run = 0;
        for (int i = 0; i < data.length; i++) {
            run = data[i] == 0 ? run + 1 : 0;
            if (run == PRELUDE_DELIMITER_LENGTH) {
                return i - PRELUDE_DELIMITER_LENGTH + 1;
            }
        }
        return -1;
    }

    private String jsonMessage(String message) {
        return objectMapper.createObjectNode().put("message", message).toString();
    }
}
