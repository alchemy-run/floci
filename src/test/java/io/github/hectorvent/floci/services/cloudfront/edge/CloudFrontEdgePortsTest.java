package io.github.hectorvent.floci.services.cloudfront.edge;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Timeout(60)
class CloudFrontEdgePortsTest {

    private static final int PORT = 9500;

    @Test
    void releaseWaitsForCloseAndSerializesTheNextBind() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch closing = new CountDownLatch(1);
        when(fixture.first().close()).thenAnswer(invocation -> {
            closing.countDown();
            return fixture.close().future();
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> released = CompletableFuture.runAsync(
                    () -> fixture.ports().release("first"), executor);
            try {
                assertTrue(closing.await(1, TimeUnit.SECONDS));
                CountDownLatch binding = new CountDownLatch(1);
                CompletableFuture<Integer> rebound = CompletableFuture.supplyAsync(() -> {
                    binding.countDown();
                    return fixture.ports().bind("second");
                }, executor);
                assertTrue(binding.await(1, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> released.get(100, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> rebound.get(100, TimeUnit.MILLISECONDS));
                verify(fixture.vertx(), times(1)).createHttpServer(any(HttpServerOptions.class));

                fixture.close().complete();
                released.get(1, TimeUnit.SECONDS);
                assertEquals(PORT, rebound.get(1, TimeUnit.SECONDS));
                assertNull(fixture.ports().portOf("first"));
                assertEquals(PORT, fixture.ports().portOf("second"));
            } finally {
                fixture.close().tryComplete();
            }
        }
    }

    @Test
    void timedOutCloseKeepsThePortReservedUntilItsEventualCompletion() {
        Fixture fixture = fixture();

        assertTimeout(Duration.ofSeconds(6), () -> fixture.ports().release("first"));

        assertFalse(fixture.close().future().isComplete());
        assertNull(fixture.ports().portOf("first"));
        assertNull(fixture.ports().bind("second"));
        verify(fixture.vertx(), times(1)).createHttpServer(any(HttpServerOptions.class));
        assertDoesNotThrow(() -> fixture.ports().release("first"));
        verify(fixture.first(), times(1)).close();

        fixture.close().complete();
        assertEquals(PORT, fixture.ports().bind("second"));
        fixture.ports().release("first");
        assertEquals(PORT, fixture.ports().portOf("second"));
        verify(fixture.second(), never()).close();
    }

    @Test
    void failedCloseKeepsThePortReservedWithoutFailingDeletion() {
        Fixture fixture = fixture();
        fixture.close().fail(new IllegalStateException("close failed"));

        assertDoesNotThrow(() -> fixture.ports().release("first"));

        assertNull(fixture.ports().portOf("first"));
        assertTrue(fixture.ports().assignments().isEmpty());
        assertNull(fixture.ports().bind("second"));
        verify(fixture.vertx(), times(1)).createHttpServer(any(HttpServerOptions.class));
    }

    @Test
    void synchronousCloseFailureKeepsThePortReservedWithoutFailingDeletion() {
        Fixture fixture = fixture();
        when(fixture.first().close()).thenThrow(new IllegalStateException("close failed"));

        assertDoesNotThrow(() -> fixture.ports().release("first"));

        assertNull(fixture.ports().portOf("first"));
        assertNull(fixture.ports().bind("second"));
        verify(fixture.vertx(), times(1)).createHttpServer(any(HttpServerOptions.class));
    }

    @Test
    void interruptedClosePreservesTheInterruptAndCleansUpWhenItEventuallyCompletes() {
        Fixture fixture = fixture();
        try {
            Thread.currentThread().interrupt();
            assertDoesNotThrow(() -> fixture.ports().release("first"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertNull(fixture.ports().bind("second"));
        verify(fixture.vertx(), times(1)).createHttpServer(any(HttpServerOptions.class));

        fixture.close().complete();
        assertEquals(PORT, fixture.ports().bind("second"));
    }

    @Test
    void eventLoopReleaseDoesNotBlockCloseCompletion() throws Exception {
        Fixture fixture = fixture();
        Vertx eventLoop = Vertx.vertx();
        CompletableFuture<Void> released = new CompletableFuture<>();
        try {
            eventLoop.runOnContext(ignored -> {
                try {
                    fixture.ports().release("first");
                    released.complete(null);
                } catch (Throwable error) {
                    released.completeExceptionally(error);
                }
            });
            released.get(1, TimeUnit.SECONDS);
            assertNull(fixture.ports().bind("second"));
            CompletableFuture<Void> completed = new CompletableFuture<>();
            eventLoop.runOnContext(ignored -> {
                fixture.close().complete();
                completed.complete(null);
            });
            completed.get(1, TimeUnit.SECONDS);
            assertEquals(PORT, fixture.ports().bind("second"));
        } finally {
            eventLoop.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void everyImmediateRebindServesTheNewGenerationOnARealVertxListener() throws Exception {
        Vertx vertx = Vertx.vertx();
        CloudFrontEdgePorts ports = null;
        try {
            HttpServer gatewayServer = vertx.createHttpServer(new HttpServerOptions()
                            .setHost("127.0.0.1").setPort(0))
                    .requestHandler(request -> request.response().end(request.uri() + "\n"))
                    .listen().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            // The gateway API and the actual Vert.x listener have the same simple name.
            io.quarkus.vertx.http.HttpServer gateway = mock(io.quarkus.vertx.http.HttpServer.class);
            when(gateway.getPort()).thenReturn(gatewayServer.actualPort());
            int port;
            try (ServerSocket reservation = new ServerSocket(0)) {
                port = reservation.getLocalPort();
            }
            ports = new CloudFrontEdgePorts(config(port), vertx, gateway);
            ports.init();
            assertEquals(port, ports.bind("generation-0"));

            for (int generation = 0; generation <= 30; generation++) {
                String distribution = "generation-" + generation;
                // Raw sockets cannot hide a refused connection behind an HTTP client's retries.
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
                    socket.setSoTimeout(2_000);
                    socket.getOutputStream().write(("GET /ready?generation=" + generation + " HTTP/1.1\r\n"
                            + "Host: localhost:" + port + "\r\nConnection: keep-alive\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    BufferedReader response = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    assertEquals("HTTP/1.1 200 OK", response.readLine(), distribution);
                    String header;
                    do {
                        header = response.readLine();
                        assertNotNull(header, distribution);
                    } while (!header.isEmpty());
                    assertEquals(CloudFrontEdgeRoutingFilter.EDGE_PREFIX + "/" + distribution
                            + "/ready?generation=" + generation, response.readLine());

                    ports.release(distribution);
                    if (generation < 30) {
                        Integer rebound = ports.bind("generation-" + (generation + 1));
                        assertEquals(port, rebound, "the same slot must be ready without retries or REST delays");
                    }
                }
            }
            assertTrue(ports.assignments().isEmpty());
        } finally {
            if (ports != null) {
                ports.shutdown();
            }
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static Fixture fixture() {
        Vertx vertx = mock(Vertx.class);
        HttpServer first = mock(HttpServer.class);
        HttpServer second = mock(HttpServer.class);
        Promise<Void> close = Promise.promise();
        when(vertx.createHttpServer(any(HttpServerOptions.class))).thenReturn(first, second);
        when(first.listen()).thenReturn(Future.succeededFuture(first));
        when(second.listen()).thenReturn(Future.succeededFuture(second));
        when(first.close()).thenReturn(close.future());
        CloudFrontEdgePorts ports = new CloudFrontEdgePorts(config(PORT), vertx, null);
        assertEquals(PORT, ports.bind("first"));
        return new Fixture(ports, vertx, first, second, close);
    }

    private static EmulatorConfig config(int port) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.CloudFrontServiceConfig cloudFront = mock(EmulatorConfig.CloudFrontServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        when(services.cloudfront()).thenReturn(cloudFront);
        when(cloudFront.edgePortsEnabled()).thenReturn(true);
        when(cloudFront.edgePorts()).thenReturn(Optional.of(Integer.toString(port)));
        return config;
    }

    private record Fixture(CloudFrontEdgePorts ports, Vertx vertx, HttpServer first, HttpServer second,
                           Promise<Void> close) {}
}
