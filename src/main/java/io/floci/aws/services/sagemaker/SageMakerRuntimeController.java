package io.floci.aws.services.sagemaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsEventStreamEncoder;
import io.floci.aws.core.common.AwsEventStreamWriter;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.EndpointConfigResource;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.inject.Instance;
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
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The SageMaker Runtime data plane: {@code InvokeEndpoint}, {@code InvokeEndpointAsync} and
 * {@code InvokeEndpointWithResponseStream}, each forwarded to the endpoint's model container on
 * {@code /invocations}.
 * <p>
 * An async invocation reads its input from {@code InputLocation}, runs while the request waits,
 * writes the container's answer to {@code <S3OutputPath>/<InferenceId>.out} (or a failure to
 * {@code <S3FailurePath>/<InferenceId>-error.out}) and notifies the configured SNS topics, then
 * answers 202 as AWS does. A streamed invocation relays the container's response body as
 * {@code PayloadPart} events as it arrives.
 */
@Path("/endpoints")
public class SageMakerRuntimeController {
    private static final Logger LOG = Logger.getLogger(SageMakerRuntimeController.class);
    private static final String CUSTOM_ATTRIBUTES = "X-Amzn-SageMaker-Custom-Attributes";
    private static final String INVOKED_VARIANT = "x-Amzn-Invoked-Production-Variant";

    private final SageMakerService service;
    private final RegionResolver regionResolver;
    private final Instance<S3Service> s3;
    private final Instance<SnsService> sns;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Inject
    public SageMakerRuntimeController(SageMakerService service, RegionResolver regionResolver,
                                      Instance<S3Service> s3, Instance<SnsService> sns, ObjectMapper mapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.s3 = s3;
        this.sns = sns;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @POST
    @Path("/{endpointName}/invocations")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.WILDCARD)
    public Response invoke(@PathParam("endpointName") String endpointName,
                           @HeaderParam("Content-Type") String contentType,
                           @HeaderParam("Accept") String accept,
                           @HeaderParam(CUSTOM_ATTRIBUTES) String customAttributes,
                           @Context HttpHeaders headers,
                           byte[] body) {
        String region = regionResolver.resolveRegion(headers);
        Object target = inService(region, endpointName);
        if (target instanceof Response error) {
            return error;
        }
        EndpointResource ep = (EndpointResource) target;
        try {
            HttpResponse<byte[]> response = httpClient.send(containerRequest(ep, body, contentType, accept,
                    customAttributes), HttpResponse.BodyHandlers.ofByteArray());
            // SageMaker Runtime maps model-container 5xx responses to ModelError (HTTP 424).
            if (response.statusCode() >= 500) {
                return modelError(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
            }
            Response.ResponseBuilder out = Response.status(response.statusCode()).entity(response.body())
                    .header(INVOKED_VARIANT, variantName(ep));
            response.headers().firstValue("Content-Type").ifPresent(out::type);
            response.headers().firstValue(CUSTOM_ATTRIBUTES).ifPresent(value -> out.header(CUSTOM_ATTRIBUTES, value));
            return out.build();
        } catch (Exception e) {
            LOG.warnv("SageMaker endpoint invocation failed for {0}: {1}", endpointName, e.getMessage());
            return Response.status(424).type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("__type", "ModelError", "message", String.valueOf(e.getMessage()))).build();
        }
    }

    @POST
    @Path("/{endpointName}/async-invocations")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response invokeAsync(@PathParam("endpointName") String endpointName,
                                @HeaderParam("X-Amzn-SageMaker-Content-Type") String contentType,
                                @HeaderParam("X-Amzn-SageMaker-Accept") String accept,
                                @HeaderParam(CUSTOM_ATTRIBUTES) String customAttributes,
                                @HeaderParam("X-Amzn-SageMaker-Inference-Id") String inferenceIdHeader,
                                @HeaderParam("X-Amzn-SageMaker-InputLocation") String inputLocation,
                                @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        if (inputLocation == null || !inputLocation.startsWith("s3://")) {
            return validation("InputLocation must be an s3:// URI.");
        }
        Object target = inService(region, endpointName);
        if (target instanceof Response error) {
            return error;
        }
        EndpointResource ep = (EndpointResource) target;
        Map<?, ?> async = asyncConfig(region, ep);
        if (async == null) {
            return validation("Endpoint " + endpointName + " is not configured for asynchronous inference.");
        }
        Map<?, ?> output = map(async.get("OutputConfig"));
        String outputPath = trimSlash(String.valueOf(output.get("S3OutputPath")));
        String failurePath = output.get("S3FailurePath") == null ? outputPath
                : trimSlash(String.valueOf(output.get("S3FailurePath")));
        String inferenceId = inferenceIdHeader == null || inferenceIdHeader.isBlank()
                ? UUID.randomUUID().toString() : inferenceIdHeader;
        String outputLocation = outputPath + "/" + inferenceId + ".out";
        String failureLocation = failurePath + "/" + inferenceId + "-error.out";

        byte[] input;
        try {
            S3Uri uri = S3Uri.parse(inputLocation);
            input = s3.get().getObject(uri.bucket(), uri.key()).getData();
        } catch (RuntimeException e) {
            return validation("Could not read the input at " + inputLocation + ": " + e.getMessage());
        }
        Map<?, ?> notification = map(output.get("NotificationConfig"));
        try {
            HttpResponse<byte[]> response = httpClient.send(containerRequest(ep, input, contentType, accept,
                    customAttributes), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                fail(region, failureLocation, notification, ep, inferenceId, inputLocation,
                        "Received server error (" + response.statusCode() + ") from primary with message \""
                                + new String(response.body(), StandardCharsets.UTF_8) + "\"");
            } else {
                write(outputLocation, response.body(), response.headers().firstValue("Content-Type").orElse(null));
                notify(region, notification.get("SuccessTopic"), ep, inferenceId, inputLocation,
                        Map.of("outputLocation", outputLocation), "Completed");
            }
        } catch (Exception e) {
            fail(region, failureLocation, notification, ep, inferenceId, inputLocation, String.valueOf(e.getMessage()));
        }
        return Response.status(202).entity(Map.of("InferenceId", inferenceId))
                .header("X-Amzn-SageMaker-OutputLocation", outputLocation)
                .header("X-Amzn-SageMaker-FailureLocation", failureLocation)
                .build();
    }

    @POST
    @Path("/{endpointName}/invocations-response-stream")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.WILDCARD)
    public Response invokeWithResponseStream(@PathParam("endpointName") String endpointName,
                                             @HeaderParam("Content-Type") String contentType,
                                             @HeaderParam("X-Amzn-SageMaker-Accept") String accept,
                                             @HeaderParam(CUSTOM_ATTRIBUTES) String customAttributes,
                                             @Context HttpHeaders headers,
                                             byte[] body) {
        String region = regionResolver.resolveRegion(headers);
        Object target = inService(region, endpointName);
        if (target instanceof Response error) {
            return error;
        }
        EndpointResource ep = (EndpointResource) target;
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(containerRequest(ep, body, contentType, accept, customAttributes),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            return Response.status(424).type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("__type", "ModelError", "message", String.valueOf(e.getMessage()))).build();
        }
        if (response.statusCode() >= 400) {
            try (InputStream in = response.body()) {
                return modelError(response.statusCode(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return modelError(response.statusCode(), String.valueOf(e.getMessage()));
            }
        }
        String bodyType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
        StreamingOutput stream = out -> relay(response.body(), out);
        return Response.ok(stream, "application/vnd.amazon.eventstream")
                .header(INVOKED_VARIANT, variantName(ep))
                .header("X-Amzn-SageMaker-Content-Type", bodyType)
                .build();
    }

    /** Each chunk the container writes becomes one {@code PayloadPart}; a broken stream ends in a ModelStreamError. */
    private void relay(InputStream body, OutputStream out) {
        byte[] buffer = new byte[8192];
        try (InputStream in = body) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                LinkedHashMap<String, String> frame = new LinkedHashMap<>();
                frame.put(":message-type", "event");
                frame.put(":event-type", "PayloadPart");
                frame.put(":content-type", "application/octet-stream");
                out.write(AwsEventStreamEncoder.encodeMessage(frame, Arrays.copyOf(buffer, read)));
                out.flush();
            }
        } catch (Exception e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("Message", String.valueOf(e.getMessage()));
            error.put("ErrorCode", "ModelStreamError");
            try {
                AwsEventStreamWriter.writeException(mapper, out, "ModelStreamError", error);
            } catch (RuntimeException ignored) {
                // The client is gone; nothing is left to tell it.
            }
        }
    }

    private HttpRequest containerRequest(EndpointResource ep, byte[] body, String contentType, String accept,
                                         String customAttributes) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://" + ep.invokeHost + ":" + ep.invokePort + "/invocations"))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (accept != null) {
            builder.header("Accept", accept);
        }
        if (customAttributes != null) {
            builder.header(CUSTOM_ATTRIBUTES, customAttributes);
        }
        return builder.build();
    }

    /** The endpoint, or the ValidationError response AWS gives for one that is missing or not in service. */
    private Object inService(String region, String endpointName) {
        EndpointResource ep = service.endpoint(region, endpointName).orElse(null);
        if (ep == null) {
            return validation("Endpoint " + endpointName + " of account " + regionResolver.getAccountId()
                    + " not found.");
        }
        if (!"InService".equals(ep.endpointStatus) || ep.invokeHost == null) {
            return validation("Endpoint " + endpointName + " is not InService.");
        }
        return ep;
    }

    private Map<?, ?> asyncConfig(String region, EndpointResource ep) {
        EndpointConfigResource config = service.endpointConfig(region, ep.endpointConfigName).orElse(null);
        return config == null ? null : (Map<?, ?>) config.settings.get("AsyncInferenceConfig");
    }

    private static String variantName(EndpointResource ep) {
        List<Map<String, Object>> variants = ep.productionVariants;
        Object name = variants == null || variants.isEmpty() ? null : variants.getFirst().get("VariantName");
        return name == null ? "AllTraffic" : String.valueOf(name);
    }

    private void fail(String region, String location, Map<?, ?> notification, EndpointResource ep, String inferenceId,
                      String inputLocation, String message) {
        try {
            write(location, message.getBytes(StandardCharsets.UTF_8), "text/plain");
        } catch (RuntimeException e) {
            LOG.warnv("Could not write the async inference failure {0}: {1}", location, e.getMessage());
        }
        notify(region, notification.get("ErrorTopic"), ep, inferenceId, inputLocation,
                Map.of("failureLocation", location, "failureReason", message), "Failed");
    }

    private void write(String location, byte[] data, String contentType) {
        S3Uri uri = S3Uri.parse(location);
        s3.get().putObject(uri.bucket(), uri.key(), data, contentType, Map.of());
    }

    /** The SNS message AWS sends when an async inference ends. */
    private void notify(String region, Object topic, EndpointResource ep, String inferenceId, String inputLocation,
                        Map<String, String> result, String status) {
        if (topic == null || !sns.isResolvable()) {
            return;
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("awsRegion", region);
        message.put("eventTime", Instant.now().toString());
        message.put("receivedTime", Instant.now().toString());
        message.put("invocationStatus", status);
        message.putObject("requestParameters").put("endpointName", ep.endpointName).put("inputLocation", inputLocation);
        message.putObject("responseParameters").put("contentType", "text/plain; charset=utf-8");
        message.put("inferenceId", inferenceId);
        ObjectNode response = (ObjectNode) message.get("responseParameters");
        result.forEach(response::put);
        try {
            sns.get().publish(String.valueOf(topic), null, message.toString(), null, region);
        } catch (RuntimeException e) {
            LOG.warnv("Could not notify {0} of async inference {1}: {2}", topic, inferenceId, e.getMessage());
        }
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String trimSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static Response modelError(int status, String originalMessage) {
        return Response.status(424).type(MediaType.APPLICATION_JSON).entity(Map.of(
                "__type", "ModelError",
                "Message", "Received server error (" + status + ") from primary with message \"" + originalMessage + "\"",
                "OriginalStatusCode", status,
                "OriginalMessage", originalMessage
        )).build();
    }

    private Response validation(String message) {
        return Response.status(400).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("__type", "ValidationError", "message", message)).build();
    }
}
