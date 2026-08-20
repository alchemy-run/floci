package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsEventStreamEncoder;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * Lambda operations that live on API-version prefixes other than 2015-03-31:
 * {@code GetAccountSettings} (2016-08-19) and {@code InvokeWithResponseStream}
 * (2021-11-15).
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class LambdaExtendedController {

    static final String EVENT_STREAM_TYPE = "application/vnd.amazon.eventstream";

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;

    @Inject
    public LambdaExtendedController(LambdaService lambdaService, RegionResolver regionResolver) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/2016-08-19/account-settings")
    public Response getAccountSettings(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(lambdaService.getAccountSettings(region)).build();
    }

    @POST
    @Path("/2021-11-15/functions/{functionName}/response-streaming-invocations")
    @Consumes(MediaType.WILDCARD)
    @Produces(EVENT_STREAM_TYPE)
    public Response invokeWithResponseStream(@Context HttpHeaders headers,
                                             @PathParam("functionName") String functionName,
                                             @QueryParam("Qualifier") String qualifier,
                                             byte[] payload) {
        String region = regionResolver.resolveRegion(headers);
        String invocationTypeHeader = headers.getHeaderString("X-Amz-Invocation-Type");
        InvocationType type = InvocationType.parse(invocationTypeHeader);
        String target = qualifier == null || qualifier.isBlank()
                ? functionName
                : functionName + ":" + qualifier;
        InvokeResult result = lambdaService.invoke(region, target, payload, type);
        try {
            byte[] body = encodeInvokeStream(result);
            return Response.ok(body)
                    .type(EVENT_STREAM_TYPE)
                    .header("X-Amz-Executed-Version",
                            result.getExecutedVersion() != null ? result.getExecutedVersion() : "$LATEST")
                    .header("X-Amz-Request-Id", result.getRequestId())
                    .build();
        } catch (Exception e) {
            throw new AwsException("ServiceException",
                    "Failed to encode InvokeWithResponseStream: " + e.getMessage(), 500);
        }
    }

    static byte[] encodeInvokeStream(InvokeResult result) throws Exception {
        byte[] payload = result.getPayload() != null ? result.getPayload() : new byte[0];
        LinkedHashMap<String, String> chunkHeaders = new LinkedHashMap<>();
        chunkHeaders.put(":message-type", "event");
        chunkHeaders.put(":event-type", "PayloadChunk");
        chunkHeaders.put(":content-type", "application/octet-stream");
        byte[] chunk = AwsEventStreamEncoder.encodeMessage(chunkHeaders, payload);

        LinkedHashMap<String, String> completeHeaders = new LinkedHashMap<>();
        completeHeaders.put(":message-type", "event");
        completeHeaders.put(":event-type", "InvokeComplete");
        completeHeaders.put(":content-type", "application/json");
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        if (result.getFunctionError() != null) {
            json.append("\"ErrorCode\":\"").append(escape(result.getFunctionError())).append("\"");
            first = false;
        }
        if (result.getLogResult() != null) {
            if (!first) {
                json.append(",");
            }
            json.append("\"LogResult\":\"").append(escape(result.getLogResult())).append("\"");
        }
        json.append("}");
        byte[] complete = AwsEventStreamEncoder.encodeMessage(
                completeHeaders, json.toString().getBytes(StandardCharsets.UTF_8));

        byte[] body = new byte[chunk.length + complete.length];
        System.arraycopy(chunk, 0, body, 0, chunk.length);
        System.arraycopy(complete, 0, body, chunk.length, complete.length);
        return body;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
