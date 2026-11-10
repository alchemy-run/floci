package io.floci.aws.services.sagemaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.EndpointConfigResource;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The three runtime invocations against a stand-in model container on a local HTTP server. */
class SageMakerRuntimeControllerTest {
    private static final String REGION = "us-east-1";

    private HttpServer container;
    private SageMakerService service;
    private S3Service s3;
    private SnsService sns;
    private SageMakerRuntimeController controller;
    private final HttpHeaders headers = mock(HttpHeaders.class);
    private final EndpointResource endpoint = new EndpointResource();
    private final EndpointConfigResource config = new EndpointConfigResource();
    private volatile int status = 200;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        container = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        container.createContext("/invocations", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            byte[] answer = ("echo:" + new String(request, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(status, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        container.start();

        endpoint.endpointName = "ep";
        endpoint.endpointConfigName = "cfg";
        endpoint.endpointStatus = "InService";
        endpoint.invokeHost = "127.0.0.1";
        endpoint.invokePort = container.getAddress().getPort();
        endpoint.productionVariants = List.of(Map.of("VariantName", "blue"));
        config.endpointConfigName = "cfg";

        service = mock(SageMakerService.class);
        when(service.endpoint(REGION, "ep")).thenReturn(Optional.of(endpoint));
        when(service.endpoint(REGION, "missing")).thenReturn(Optional.empty());
        when(service.endpointConfig(REGION, "cfg")).thenReturn(Optional.of(config));
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.resolveRegion(any())).thenReturn(REGION);
        when(regions.getAccountId()).thenReturn("000000000000");
        s3 = mock(S3Service.class);
        sns = mock(SnsService.class);
        Instance<S3Service> s3Instance = mock(Instance.class);
        when(s3Instance.get()).thenReturn(s3);
        Instance<SnsService> snsInstance = mock(Instance.class);
        when(snsInstance.get()).thenReturn(sns);
        when(snsInstance.isResolvable()).thenReturn(true);
        controller = new SageMakerRuntimeController(service, regions, s3Instance, snsInstance, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        container.stop(0);
    }

    @Test
    void invokeForwardsToTheContainerAndNamesTheVariant() {
        Response response = controller.invoke("ep", "text/plain", null, "trace=1", headers,
                "hi".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, response.getStatus());
        assertEquals("echo:hi", new String((byte[]) response.getEntity(), StandardCharsets.UTF_8));
        assertEquals("blue", response.getHeaderString("x-Amzn-Invoked-Production-Variant"));
    }

    @Test
    void aMissingEndpointIsAValidationErrorForEveryInvocation() {
        assertEquals(400, controller.invoke("missing", null, null, null, headers, new byte[0]).getStatus());
        assertEquals(400, controller.invokeAsync("missing", null, null, null, null, "s3://b/in.json", headers)
                .getStatus());
        Response stream = controller.invokeWithResponseStream("missing", null, null, null, headers, new byte[0]);
        assertEquals(400, stream.getStatus());
        assertEquals("ValidationError", ((Map<?, ?>) stream.getEntity()).get("__type"));
    }

    @Test
    void aContainerFailureIsAModelError() {
        status = 500;
        assertEquals(424, controller.invoke("ep", null, null, null, headers, new byte[0]).getStatus());
        assertEquals(424, controller.invokeWithResponseStream("ep", null, null, null, headers, new byte[0])
                .getStatus());
    }

    @Test
    void asyncNeedsAnAsyncEndpointConfig() {
        Response response = controller.invokeAsync("ep", null, null, null, null, "s3://in/request.json", headers);
        assertEquals(400, response.getStatus());
        assertTrue(String.valueOf(((Map<?, ?>) response.getEntity()).get("message")).contains("asynchronous"));
    }

    @Test
    void asyncReadsTheInputWritesTheAnswerAndNotifies() {
        config.settings.put("AsyncInferenceConfig", Map.of("OutputConfig", Map.of(
                "S3OutputPath", "s3://out/results/",
                "NotificationConfig", Map.of("SuccessTopic", "arn:aws:sns:us-east-1:000000000000:done"))));
        S3Object input = mock(S3Object.class);
        when(input.getData()).thenReturn("payload".getBytes(StandardCharsets.UTF_8));
        when(s3.getObject("in", "request.json")).thenReturn(input);

        Response response = controller.invokeAsync("ep", "text/plain", null, null, "inf-1", "s3://in/request.json",
                headers);

        assertEquals(202, response.getStatus());
        assertEquals("s3://out/results/inf-1.out", response.getHeaderString("X-Amzn-SageMaker-OutputLocation"));
        assertEquals("s3://out/results/inf-1-error.out", response.getHeaderString("X-Amzn-SageMaker-FailureLocation"));
        verify(s3).putObject(eq("out"), eq("results/inf-1.out"), eq("echo:payload".getBytes(StandardCharsets.UTF_8)),
                eq("text/plain"), anyMap());
        verify(sns).publish(eq("arn:aws:sns:us-east-1:000000000000:done"), isNull(), contains("\"Completed\""),
                isNull(), eq(REGION));
    }

    @Test
    void aFailedAsyncInvocationWritesTheFailureInstead() {
        status = 500;
        config.settings.put("AsyncInferenceConfig", Map.of("OutputConfig", Map.of(
                "S3OutputPath", "s3://out/results", "S3FailurePath", "s3://out/failures")));
        S3Object input = mock(S3Object.class);
        when(input.getData()).thenReturn(new byte[0]);
        when(s3.getObject("in", "request.json")).thenReturn(input);

        assertEquals(202, controller.invokeAsync("ep", null, null, null, "inf-2", "s3://in/request.json", headers)
                .getStatus());

        verify(s3).putObject(eq("out"), eq("failures/inf-2-error.out"), any(byte[].class), eq("text/plain"), anyMap());
        verify(s3, never()).putObject(eq("out"), eq("results/inf-2.out"), any(byte[].class), anyString(), anyMap());
    }

    @Test
    void aStreamedInvocationRelaysTheBodyAsPayloadParts() throws Exception {
        Response response = controller.invokeWithResponseStream("ep", "text/plain", null, null, headers,
                "streamed".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, response.getStatus());
        assertEquals("text/plain", response.getHeaderString("X-Amzn-SageMaker-Content-Type"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ((StreamingOutput) response.getEntity()).write(out);
        String frames = out.toString(StandardCharsets.ISO_8859_1);
        assertTrue(frames.contains("PayloadPart"), frames);
        assertTrue(frames.contains("echo:streamed"), frames);
    }
}
