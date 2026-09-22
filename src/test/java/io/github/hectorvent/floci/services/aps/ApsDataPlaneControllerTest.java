package io.github.hectorvent.floci.services.aps;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApsDataPlaneControllerTest {

    private static final String WORKSPACE = "ws-11111111-2222-3333-4444-555555555555";
    private static final String ARN = "arn:aws:aps:us-east-1:000000000000:workspace/" + WORKSPACE;
    private ApsService service;
    private ApsDataPlaneController controller;
    private HttpHeaders headers;
    private UriInfo uri;
    private MultivaluedHashMap<String, String> values;

    @BeforeEach
    void setUp() {
        service = mock(ApsService.class);
        ApsDataPlaneAuth auth = mock(ApsDataPlaneAuth.class);
        when(auth.verify(anyString(), any(URI.class), anyMap(), any(byte[].class)))
                .thenReturn(new CredentialScope("test", "20260922", "us-east-1", "aps"));
        PrometheusWorkspace workspace = new PrometheusWorkspace();
        workspace.setWorkspaceId(WORKSPACE);
        workspace.setArn(ARN);
        when(service.describeWorkspace("us-east-1", WORKSPACE)).thenReturn(workspace);
        controller = new ApsDataPlaneController(service, auth);
        headers = mock(HttpHeaders.class);
        values = new MultivaluedHashMap<>();
        values.putSingle("Authorization", "signed-request");
        when(headers.getRequestHeaders()).thenReturn(values);
        uri = mock(UriInfo.class);
    }

    @Test
    void forwardsSnappyBytesAndPreservesBackendErrors() throws Exception {
        byte[] bytes = {0, 2, -1, 8, 10};
        values.putSingle("Content-Type", "application/x-protobuf");
        values.putSingle("Content-Encoding", "snappy");
        values.putSingle("X-Prometheus-Remote-Write-Version", "0.1.0");
        when(uri.getRequestUri()).thenReturn(URI.create("https://" + WORKSPACE
                + ".localhost.floci.io:4566/workspaces/" + WORKSPACE + "/api/v1/remote_write"));
        byte[] error = "out of order sample".getBytes(StandardCharsets.UTF_8);
        when(service.forward(eq("us-east-1"), eq(WORKSPACE), eq("POST"), eq("/api/v1/write"),
                isNull(), anyMap(), any(byte[].class)))
                .thenReturn(new ApsPrometheusBackend.BackendResponse(400, "text/plain", error));
        try (Response response = controller.post(WORKSPACE, "remote_write", headers, uri,
                new ByteArrayInputStream(bytes))) {
            assertEquals(400, response.getStatus());
            assertArrayEquals(error, (byte[]) response.getEntity());
        }
        ArgumentCaptor<byte[]> captured = ArgumentCaptor.forClass(byte[].class);
        verify(service).forward(eq("us-east-1"), eq(WORKSPACE), eq("POST"), eq("/api/v1/write"),
                isNull(), argThat(h -> "snappy".equals(h.get("content-encoding"))
                        && "application/x-protobuf".equals(h.get("content-type"))), captured.capture());
        assertArrayEquals(bytes, captured.getValue());
    }

    @Test
    void forwardsFormBodyAndRepeatedQueryParametersUnchanged() throws Exception {
        byte[] form = "match%5B%5D=up&match%5B%5D=down".getBytes(StandardCharsets.UTF_8);
        String rawQuery = "start=1&end=2&match%5B%5D=x&match%5B%5D=y";
        values.putSingle("Content-Type", "application/x-www-form-urlencoded");
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost:4566/workspaces/" + WORKSPACE
                + "/api/v1/series?" + rawQuery));
        when(service.forward(eq("us-east-1"), eq(WORKSPACE), eq("POST"), eq("/api/v1/series"),
                eq(rawQuery), anyMap(), any(byte[].class)))
                .thenReturn(new ApsPrometheusBackend.BackendResponse(200, "application/json", new byte[0]));
        try (Response response = controller.post(WORKSPACE, "series", headers, uri, new ByteArrayInputStream(form))) {
            assertEquals(200, response.getStatus());
        }
        verify(service).forward(eq("us-east-1"), eq(WORKSPACE), eq("POST"), eq("/api/v1/series"),
                eq(rawQuery), anyMap(), aryEq(form));
    }

    @Test
    void restrictsBackendSurfaceToAmpOperations() {
        assertEquals("aps:QueryMetrics", ApsDataPlaneController.action("POST", "query"));
        assertEquals("aps:QueryMetrics", ApsDataPlaneController.action("GET", "query_range"));
        assertEquals("aps:GetLabels", ApsDataPlaneController.action("GET", "label/__name__/values"));
        assertEquals("aps:GetMetricMetadata", ApsDataPlaneController.action("GET", "metadata"));
        assertThrows(AwsException.class, () -> ApsDataPlaneController.action("POST", "admin/tsdb/delete_series"));
        assertThrows(AwsException.class, () -> ApsDataPlaneController.action("GET", "remote_write"));
        verifyNoInteractions(service);
    }
}
