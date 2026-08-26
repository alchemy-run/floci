package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsEventStreamEncoder;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;

/**
 * Amazon SageMaker Runtime restJson1 ({@code runtime.sagemaker}). Signed as
 * {@code sagemaker}. Paths are rewritten by {@link SageMakerRuntimeRoutingFilter}.
 */
@Path(SageMakerRuntimeRoutingFilter.INTERNAL_PREFIX)
public class SageMakerRuntimeController {

    private static final String EVENT_STREAM = "application/vnd.amazon.eventstream";

    private final SageMakerService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SageMakerRuntimeController(SageMakerService service, RegionResolver regionResolver,
                                      ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/endpoints/{endpointName}/invocations")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.WILDCARD)
    public Response invokeEndpoint(@Context HttpHeaders headers,
                                   @PathParam("endpointName") String endpointName,
                                   byte[] body) {
        try {
            String region = regionResolver.resolveRegion(headers);
            String contentType = headers.getHeaderString("Content-Type");
            SageMakerService.InvokeEndpointResult result =
                    service.invokeEndpoint(region, endpointName, body, contentType);
            return Response.ok(result.body())
                    .type(result.contentType())
                    .header("Content-Type", result.contentType())
                    .header("x-Amzn-Invoked-Production-Variant", result.invokedProductionVariant())
                    .build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @POST
    @Path("/endpoints/{endpointName}/async-invocations")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response invokeEndpointAsync(@Context HttpHeaders headers,
                                        @PathParam("endpointName") String endpointName,
                                        @HeaderParam("X-Amzn-SageMaker-InputLocation") String inputLocation,
                                        @HeaderParam("X-Amzn-SageMaker-Inference-Id") String inferenceId) {
        try {
            String region = regionResolver.resolveRegion(headers);
            SageMakerService.InvokeEndpointAsyncResult result =
                    service.invokeEndpointAsync(region, endpointName, inputLocation, inferenceId);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("InferenceId", result.inferenceId());
            return Response.accepted(body)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-SageMaker-OutputLocation", result.outputLocation())
                    .header("X-Amzn-SageMaker-FailureLocation", result.failureLocation())
                    .build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @POST
    @Path("/endpoints/{endpointName}/invocations-response-stream")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.WILDCARD)
    public Response invokeEndpointWithResponseStream(@Context HttpHeaders headers,
                                                     @PathParam("endpointName") String endpointName,
                                                     byte[] body) {
        try {
            String region = regionResolver.resolveRegion(headers);
            String contentType = headers.getHeaderString("Content-Type");
            SageMakerService.InvokeEndpointResult result =
                    service.invokeEndpoint(region, endpointName, body, contentType);
            return Response.ok(encodePayloadPart(result.body()))
                    .type(EVENT_STREAM)
                    .header("X-Amzn-SageMaker-Content-Type", result.contentType())
                    .header("x-Amzn-Invoked-Production-Variant", result.invokedProductionVariant())
                    .build();
        } catch (AwsException e) {
            return error(e);
        } catch (Exception e) {
            return error(new AwsException("InternalFailure",
                    "Failed to encode InvokeEndpointWithResponseStream: " + e.getMessage(), 500));
        }
    }

    private static byte[] encodePayloadPart(byte[] payload) throws Exception {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put(":message-type", "event");
        headers.put(":event-type", "PayloadPart");
        headers.put(":content-type", "application/octet-stream");
        return AwsEventStreamEncoder.encodeMessage(headers, payload == null ? new byte[0] : payload);
    }

    private Response error(AwsException exception) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", exception.jsonType());
        node.put("Message", exception.getMessage());
        node.put("message", exception.getMessage());
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(node)
                .build();
    }
}
