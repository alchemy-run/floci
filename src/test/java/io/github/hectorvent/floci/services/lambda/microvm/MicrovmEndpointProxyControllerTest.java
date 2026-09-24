package io.github.hectorvent.floci.services.lambda.microvm;

import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmRecord;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The endpoint proxy in front of a freshly started MicroVM. Behind Docker's userland port proxy a
 * server that is not listening yet does not refuse the connection: the proxy accepts it and closes
 * it without a byte, which the JDK client reports as "HTTP/1.1 header parser received no bytes".
 */
class MicrovmEndpointProxyControllerTest {

    private ServerSocket server;
    private Thread serverThread;

    @AfterEach
    void stopServer() throws IOException {
        if (server != null) {
            server.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Test
    void connectionFailuresAreAlwaysRetryableButOtherTransportFailuresOnlyWhileBooting() {
        assertTrue(MicrovmEndpointProxyController.isRetryable(new ConnectException("refused"), false));
        assertTrue(MicrovmEndpointProxyController.isRetryable(new HttpConnectTimeoutException("slow"), false));
        assertTrue(MicrovmEndpointProxyController.isRetryable(
                new IOException("HTTP/1.1 header parser received no bytes"), true));
        assertTrue(MicrovmEndpointProxyController.isRetryable(new EOFException("closed"), true));
        assertFalse(MicrovmEndpointProxyController.isRetryable(
                new IOException("HTTP/1.1 header parser received no bytes"), false));
        assertFalse(MicrovmEndpointProxyController.isRetryable(new HttpTimeoutException("hung"), true));
        assertFalse(MicrovmEndpointProxyController.isRetryable(new IllegalStateException("bug"), true));
    }

    @Test
    void firstRequestToABootingVmWaitsForTheServerInsteadOfFailing() throws Exception {
        AtomicInteger connections = startServer(1);
        MicrovmRuntimeService runtime = runtime(false);
        MicrovmEndpointProxyController controller =
                new MicrovmEndpointProxyController(runtime, mock(MicrovmAuthTokenService.class));

        Response response = controller.post("mvm-booting", "__rpc__/hello", headers(), uriInfo(),
                "[\"world\"]".getBytes(StandardCharsets.UTF_8));

        assertEquals(200, response.getStatus());
        assertEquals("ok", new String((byte[]) response.getEntity(), StandardCharsets.UTF_8));
        assertEquals(2, connections.get());
        verify(runtime).markServed(any());
    }

    @Test
    void aVmThatAlreadyServedIsNotRetriedAfterAClosedConnection() throws Exception {
        AtomicInteger connections = startServer(Integer.MAX_VALUE);
        MicrovmRuntimeService runtime = runtime(true);
        MicrovmEndpointProxyController controller =
                new MicrovmEndpointProxyController(runtime, mock(MicrovmAuthTokenService.class));

        Response response = controller.post("mvm-booting", "__rpc__/hello", headers(), uriInfo(),
                "[\"world\"]".getBytes(StandardCharsets.UTF_8));

        assertEquals(502, response.getStatus());
        assertEquals(1, connections.get());
        verify(runtime, never()).markServed(any());
    }

    /** Closes the first {@code dropped} connections without a byte, then answers 200 "ok". */
    private AtomicInteger startServer(int dropped) throws IOException {
        server = new ServerSocket(0);
        AtomicInteger connections = new AtomicInteger();
        serverThread = Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    if (connections.incrementAndGet() <= dropped) {
                        continue;
                    }
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line = reader.readLine();
                    while (line != null && !line.isEmpty()) {
                        line = reader.readLine();
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n"
                            + "Connection: close\r\n\r\nok").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException expected) {
                    // The server socket was closed by the test's teardown.
                }
            }
        });
        return connections;
    }

    private MicrovmRuntimeService runtime(boolean served) {
        MicrovmRecord vm = new MicrovmRecord();
        vm.setMicrovmId("mvm-booting");
        vm.setContainerId("container-booting");
        vm.setState("RUNNING");
        vm.setPort(8080);
        MicrovmRuntimeService runtime = mock(MicrovmRuntimeService.class);
        when(runtime.findById("mvm-booting")).thenReturn(Optional.of(vm));
        when(runtime.hasServed(vm)).thenReturn(served);
        when(runtime.resolveVmEndpoint(vm))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("127.0.0.1", server.getLocalPort()));
        return runtime;
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString(MicrovmAuthTokenService.HEADER)).thenReturn("token");
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        return headers;
    }

    private static UriInfo uriInfo() {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri())
                .thenReturn(URI.create("http://localhost/_floci/microvm-endpoint/mvm-booting/__rpc__/hello"));
        return uriInfo;
    }
}
