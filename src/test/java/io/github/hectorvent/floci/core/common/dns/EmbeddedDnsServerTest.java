package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddedDnsServerTest {

    private EmbeddedDnsServer dns;

    @BeforeEach
    void setUp() {
        dns = new EmbeddedDnsServer(List.of("localhost.floci.io"));
    }

    @Test
    void sourceModeIsOptInAndDoesNotOpenAHostListenerByDefault() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        Vertx vertx = mock(Vertx.class);
        SourceNetworkHelper helper = mock(SourceNetworkHelper.class);
        EmbeddedDnsServer server = new EmbeddedDnsServer(config, mock(ContainerDetector.class), vertx, helper);
        assertTrue(server.getServerIp().isEmpty());
        verifyNoInteractions(vertx, helper);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 53, 1023, 65536})
    void sourceModeRejectsPrivilegedAndInvalidHostDnsPorts(int port) {
        EmulatorConfig config = sourceConfig();
        when(config.dns().sourcePort()).thenReturn(port);
        Vertx vertx = mock(Vertx.class);
        SourceNetworkHelper helper = mock(SourceNetworkHelper.class);
        assertThrows(IllegalArgumentException.class,
                () -> new EmbeddedDnsServer(config, mock(ContainerDetector.class), vertx, helper));
        verifyNoInteractions(vertx, helper);
    }

    @Test
    void sourceModeRequiresMainGatewayTlsAndNoHost443Listener() {
        EmulatorConfig config = sourceConfig();
        when(config.tls().awsHttpsPort()).thenReturn(443);
        Vertx vertx = mock(Vertx.class);
        SourceNetworkHelper helper = mock(SourceNetworkHelper.class);
        assertThrows(IllegalStateException.class,
                () -> new EmbeddedDnsServer(config, mock(ContainerDetector.class), vertx, helper));
        when(config.tls().awsHttpsPort()).thenReturn(0);
        when(config.tls().enabled()).thenReturn(false);
        assertThrows(IllegalStateException.class,
                () -> new EmbeddedDnsServer(config, mock(ContainerDetector.class), vertx, helper));
        verifyNoInteractions(vertx, helper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "::1", "host.docker.internal", "10.0.0.256", "224.0.0.1"})
    void sourceModeRejectsNonReachableIpv4Advertisements(String address) {
        assertThrows(IllegalArgumentException.class, () -> EmbeddedDnsServer.requireBridgeAddress(address));
    }

    @Test
    @Timeout(20)
    void sourceModeResolvesAwsAndSharedGatewayHostsToTheHelperWithoutIpv6Escape() throws Exception {
        Vertx vertx = Vertx.vertx();
        SourceNetworkHelper helper = mock(SourceNetworkHelper.class);
        when(helper.start(anyInt())).thenReturn("172.18.0.9");
        EmbeddedDnsServer server = null;
        try {
            server = new EmbeddedDnsServer(sourceConfig(), mock(ContainerDetector.class), vertx, helper);
            var portCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
            verify(helper).start(portCaptor.capture());
            int port = portCaptor.getValue();
            assertTrue(port >= 1024);
            assertEquals(Optional.of("172.18.0.9"), server.getServerIp());
            assertTrue(server.isSourceMode());
            for (String host : List.of("sync-states.us-east-1.amazonaws.com", "localhost.floci.io",
                    "bucket.localhost.floci.io")) {
                byte[] query = buildQuery(host, (short) 0x1234);
                byte[] response = query(port, query);
                assertEquals(1, ByteBuffer.wrap(response).getShort(6));
                assertArrayEquals(new byte[]{(byte) 172, 18, 0, 9},
                        java.util.Arrays.copyOfRange(response, response.length - 4, response.length));
                ByteBuffer.wrap(query).putShort(query.length - 4, (short) 28);
                response = query(port, query);
                assertEquals(0, ByteBuffer.wrap(response).getShort(6));
                assertEquals(0, response[3] & 0x0f);
            }
        } finally {
            if (server != null) {
                server.stop();
                assertTrue(server.getServerIp().isEmpty());
                verify(helper).stop();
            }
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(20)
    void sourceModeBindFailureIsFatalAndLeavesTheExistingListenerAlone() throws Exception {
        Vertx vertx = Vertx.vertx();
        SourceNetworkHelper helper = mock(SourceNetworkHelper.class);
        try (DatagramSocket occupied = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            EmulatorConfig config = sourceConfig();
            when(config.dns().sourcePort()).thenReturn(occupied.getLocalPort());
            assertThrows(IllegalStateException.class,
                    () -> new EmbeddedDnsServer(config, mock(ContainerDetector.class), vertx, helper));
            assertFalse(occupied.isClosed());
            verifyNoInteractions(helper);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(20)
    void sourceModeHelperFailureStopsTheOwnedHelper() throws Exception {
        Vertx vertx = Vertx.vertx();
        SourceNetworkHelper helper = mock(SourceNetworkHelper.class);
        when(helper.start(anyInt())).thenThrow(new IllegalStateException("bridge failed"));
        try {
            assertThrows(IllegalStateException.class,
                    () -> new EmbeddedDnsServer(sourceConfig(), mock(ContainerDetector.class), vertx, helper));
            verify(helper).stop();
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(20)
    void sourceModeAnswersBeforeValidationButAdvertisesOnlyAfterSuccess(boolean failProbe) throws Exception {
        Vertx vertx = Vertx.vertx();
        SourceNetworkHelper helper = mock(SourceNetworkHelper.class);
        AtomicInteger port = new AtomicInteger();
        AtomicReference<EmbeddedDnsServer> observed = new AtomicReference<>();
        when(helper.start(anyInt())).thenAnswer(invocation -> {
            port.set(invocation.getArgument(0));
            return "172.18.0.9";
        });
        doAnswer(invocation -> {
            byte[] request = SourceNetworkHelper.readinessQuery((short) 0x1234);
            byte[] response = query(port.get(), request);
            SourceNetworkHelper.validateDnsResponse(request, response, "172.18.0.9");
            assertTrue(observed.get().getServerIp().isEmpty());
            if (failProbe) {
                throw new IllegalStateException("DNS roundtrip failed");
            }
            return null;
        }).when(helper).awaitDns("172.18.0.9");
        try {
            Executable construct = () -> new EmbeddedDnsServer(
                    sourceConfig(), mock(ContainerDetector.class), vertx, helper) {
                @Override
                Optional<String> resolveARecord(String name, String myIp) {
                    observed.set(this);
                    return super.resolveARecord(name, myIp);
                }
            };
            if (failProbe) {
                IllegalStateException failure = assertThrows(IllegalStateException.class, construct);
                assertEquals("DNS roundtrip failed", failure.getCause().getMessage());
                assertTrue(observed.get().getServerIp().isEmpty());
                verify(helper).stop();
            } else {
                assertDoesNotThrow(construct);
                assertEquals(Optional.of("172.18.0.9"), observed.get().getServerIp());
            }
            verify(helper).awaitDns("172.18.0.9");
        } finally {
            if (observed.get() != null) {
                observed.get().stop();
            }
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void dockerModeKeepsItsEmbeddedDnsAndDoesNotStartASourceHelper() {
        EmulatorConfig config = sourceConfig();
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(true);
        Vertx vertx = mock(Vertx.class);
        io.vertx.core.datagram.DatagramSocket socket = mock(io.vertx.core.datagram.DatagramSocket.class);
        when(vertx.createDatagramSocket(any())).thenReturn(socket);
        when(socket.listen(53, "0.0.0.0")).thenReturn(io.vertx.core.Future.succeededFuture(socket));
        SourceNetworkHelper helper = mock(SourceNetworkHelper.class);
        EmbeddedDnsServer server = new EmbeddedDnsServer(config, detector, vertx, helper);
        assertFalse(server.isSourceMode());
        assertTrue(server.getServerIp().isPresent());
        verifyNoInteractions(helper);
        server.stop();
    }

    private EmulatorConfig sourceConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.dns().sourceEnabled()).thenReturn(true);
        when(config.port()).thenReturn(4566);
        when(config.tls().enabled()).thenReturn(true);
        when(config.tls().awsHttpsPort()).thenReturn(0);
        return config;
    }

    private byte[] query(int port, byte[] request) throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(2000);
            client.send(new DatagramPacket(request, request.length, InetAddress.getByName("127.0.0.1"), port));
            DatagramPacket reply = new DatagramPacket(new byte[4096], 4096);
            client.receive(reply);
            return java.util.Arrays.copyOf(reply.getData(), reply.getLength());
        }
    }

    // ── matchesSuffix — configured suffix ────────────────────────────────────

    @Test
    void matchesSuffix_exactMatch() {
        assertTrue(dns.matchesSuffix("localhost.floci.io"));
    }

    @Test
    void matchesSuffix_singleSubdomain() {
        assertTrue(dns.matchesSuffix("my-bucket.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_deeplyNested() {
        assertTrue(dns.matchesSuffix("deeply.nested.bucket.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_caseInsensitive() {
        assertTrue(dns.matchesSuffix("My-Bucket.Localhost.Floci.IO"));
    }

    @Test
    void matchesSuffix_noMatch() {
        assertFalse(dns.matchesSuffix("my-bucket.s3.amazonaws.com"));
    }

    @Test
    void matchesSuffix_partialSuffixNoMatch() {
        assertFalse(dns.matchesSuffix("floci.io"));
    }

    // bare *.floci.io (without localhost.) must NOT match — only *.localhost.floci.io is registered
    @Test
    void matchesSuffix_bareFlociIoSubdomainNoMatch() {
        assertFalse(dns.matchesSuffix("my-bucket.floci.io"));
    }

    @Test
    void matchesSuffix_bareS3FlociIoNoMatch() {
        assertFalse(dns.matchesSuffix("s3.floci.io"));
    }

    // bare *.localstack.cloud (without localhost.) must NOT match either
    @Test
    void matchesSuffix_bareLocalstackCloudNoMatch() {
        assertFalse(dns.matchesSuffix("my-bucket.localstack.cloud"));
    }

    @Test
    void matchesSuffix_nullAndEmpty() {
        assertFalse(dns.matchesSuffix(null));
        assertFalse(dns.matchesSuffix(""));
    }

    // ── EC2 private DNS names ────────────────────────────────────────────────

    @Test
    void resolveEc2PrivateDnsName_decodesAwsIpName() {
        assertEquals(
                "172.16.128.9",
                dns.resolveEc2PrivateDnsName("ip-172-16-128-9.ec2.internal").orElseThrow());
    }

    @Test
    void resolveEc2PrivateDnsName_isCaseInsensitive() {
        assertEquals(
                "10.42.32.17",
                dns.resolveEc2PrivateDnsName("IP-10-42-32-17.EC2.INTERNAL").orElseThrow());
    }

    @Test
    void resolveEc2PrivateDnsName_rejectsInvalidOctets() {
        assertTrue(dns.resolveEc2PrivateDnsName("ip-172-16-128-300.ec2.internal").isEmpty());
    }

    @Test
    void resolveARecord_syncStatesHostMapsToFloci() {
        assertEquals(
                "172.19.0.2",
                dns.resolveARecord("sync-states.us-east-1.amazonaws.com", "172.19.0.2").orElseThrow());
        assertEquals(
                "172.19.0.2",
                dns.resolveARecord("sync-states-fips.us-west-2.amazonaws.com", "172.19.0.2").orElseThrow());
        assertTrue(dns.resolveARecord("states.us-east-1.amazonaws.com", "172.19.0.2").isEmpty());
        assertTrue(dns.resolveARecord("my-bucket.s3.amazonaws.com", "172.19.0.2").isEmpty());
    }

    @Test
    void resolveARecord_appSyncHostsMapToFloci() {
        assertEquals(
                "172.19.0.2",
                dns.resolveARecord("747c6aee7d754b748a59438a17.appsync-api.us-east-1.amazonaws.com", "172.19.0.2")
                        .orElseThrow());
        assertEquals(
                "172.19.0.2",
                dns.resolveARecord("appsync.us-east-1.amazonaws.com", "172.19.0.2").orElseThrow());
        assertEquals(
                "172.19.0.2",
                dns.resolveARecord("appsync-fips.us-west-2.amazonaws.com", "172.19.0.2").orElseThrow());
        assertTrue(dns.resolveARecord("appsync-api.us-east-1.amazonaws.com", "172.19.0.2").isEmpty());
        assertTrue(dns.resolveARecord("not-appsync.us-east-1.amazonaws.com", "172.19.0.2").isEmpty());
    }

    @Test
    void resolveARecord_executeApiHostsMapToFloci() {
        assertEquals(
                "172.19.0.2",
                dns.resolveARecord("abc123xyz.execute-api.us-east-1.amazonaws.com", "172.19.0.2")
                        .orElseThrow());
        assertEquals(
                "172.19.0.2",
                dns.resolveARecord("abc123xyz.execute-api.us-west-2.amazonaws.com", "172.19.0.2")
                        .orElseThrow());
        assertTrue(dns.resolveARecord("execute-api.us-east-1.amazonaws.com", "172.19.0.2").isEmpty());
        assertTrue(dns.resolveARecord("abc123xyz.execute-api.amazonaws.com", "172.19.0.2").isEmpty());
    }

    @Test
    void resolveARecord_prefersEc2PrivateDnsAddressOverFlociWildcard() {
        assertEquals(
                "172.16.128.9",
                dns.resolveARecord("ip-172-16-128-9.ec2.internal", "172.16.128.5").orElseThrow());
    }

    // ── matchesSuffix — built-in emulator domains ─────────────────────────────

    @Test
    void matchesSuffix_localhostFlociIo_exact() {
        assertTrue(dns.matchesSuffix("localhost.floci.io"));
    }

    @Test
    void matchesSuffix_localhostFlociIo_subdomain() {
        assertTrue(dns.matchesSuffix("my-bucket.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_s3LocalhostFlociIo() {
        assertTrue(dns.matchesSuffix("s3.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_bucketS3LocalhostFlociIo() {
        assertTrue(dns.matchesSuffix("my-bucket.s3.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_localhostLocalstackCloud_exact() {
        assertTrue(dns.matchesSuffix("localhost.localstack.cloud"));
    }

    @Test
    void matchesSuffix_localhostLocalstackCloud_subdomain() {
        assertTrue(dns.matchesSuffix("my-bucket.localhost.localstack.cloud"));
    }

    @Test
    void matchesSuffix_s3LocalhostLocalstackCloud() {
        assertTrue(dns.matchesSuffix("s3.localhost.localstack.cloud"));
    }

    @Test
    void matchesSuffix_bucketS3LocalhostLocalstackCloud() {
        assertTrue(dns.matchesSuffix("my-bucket.s3.localhost.localstack.cloud"));
    }

    @Test
    void matchesSuffix_bucketS3RegionLocalstackCloud() {
        assertTrue(dns.matchesSuffix("my-bucket.s3.us-east-1.localhost.localstack.cloud"));
    }

    // ── readName ──────────────────────────────────────────────────────────────

    @Test
    void readName_simple() {
        // my-bucket.localhost.floci.io encoded as DNS labels
        byte[] encoded = encodeName("my-bucket.localhost.floci.io");
        ByteBuffer buf = ByteBuffer.wrap(encoded);
        assertEquals("my-bucket.localhost.floci.io", dns.readName(buf, encoded));
    }

    @Test
    void readName_singleLabel() {
        byte[] encoded = encodeName("floci");
        ByteBuffer buf = ByteBuffer.wrap(encoded);
        assertEquals("floci", dns.readName(buf, encoded));
    }

    @Test
    void readName_withCompressionPointer() {
        // Build a buffer where the name at offset 12 is "floci.io" and
        // a pointer at offset 0 points to it.
        byte[] data = new byte[20];
        // pointer at offset 0 → offset 4
        data[0] = (byte) 0xC0;
        data[1] = 0x04;
        // "floci.io" at offset 4
        byte[] name = encodeName("floci.io");
        System.arraycopy(name, 0, data, 4, name.length);

        ByteBuffer buf = ByteBuffer.wrap(data);
        assertEquals("floci.io", dns.readName(buf, data));
    }

    // ── buildAResponse ────────────────────────────────────────────────────────

    @Test
    void buildAResponse_hasCorrectTransactionId() {
        byte[] query = buildQuery("my-bucket.localhost.floci.io", (short) 0x1234);
        byte[] response = dns.buildAResponse(query, (short) 0x1234, 12, query.length, "172.19.0.2");
        short txId = ByteBuffer.wrap(response).getShort(0);
        assertEquals((short) 0x1234, txId);
    }

    @Test
    void buildAResponse_flagsIndicateResponse() {
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 1);
        byte[] response = dns.buildAResponse(query, (short) 1, 12, query.length, "10.0.0.1");
        short flags = ByteBuffer.wrap(response).getShort(2);
        assertTrue((flags & 0x8000) != 0, "QR bit must be set");
    }

    @Test
    void buildAResponse_answerCountIsOne() {
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 2);
        byte[] response = dns.buildAResponse(query, (short) 2, 12, query.length, "10.0.0.1");
        short ancount = ByteBuffer.wrap(response).getShort(6);
        assertEquals(1, ancount);
    }

    @Test
    void buildAResponse_ipAddressIsCorrect() {
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 3);
        byte[] response = dns.buildAResponse(query, (short) 3, 12, query.length, "172.19.0.42");
        // IP starts at offset: 12 (header) + questionLength + 2+2+2+4+2 = questionLength + 24
        int questionLength = query.length - 12;
        ByteBuffer resp = ByteBuffer.wrap(response);
        resp.position(12 + questionLength + 10); // skip header + question + name-ptr(2) + type(2) + class(2) + ttl(4)
        short rdlen = resp.getShort();
        assertEquals(4, rdlen);
        assertEquals((byte) 172, resp.get());
        assertEquals((byte) 19, resp.get());
        assertEquals((byte) 0, resp.get());
        assertEquals((byte) 42, resp.get());
    }

    // ── composeUpstreams — forwarder upstream ordering ────────────────────────

    @Test
    void composeUpstreams_resolvConfFirstThenFallbacks() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("192.168.65.7"), List.of("8.8.8.8", "8.8.4.4"));
        assertEquals(List.of("192.168.65.7", "8.8.8.8", "8.8.4.4"), upstreams);
    }

    @Test
    void composeUpstreams_usesDockerResolverBaselineWhenResolvConfEmpty() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of(), List.of("8.8.8.8"));
        // Docker's embedded resolver is the baseline, then the configured fallback.
        assertEquals(List.of("127.0.0.11", "8.8.8.8"), upstreams);
    }

    @Test
    void composeUpstreams_skipsLoopbackAndBlankEntries() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("127.0.0.1", "  ", "10.0.0.2"), List.of("", "8.8.8.8"));
        assertEquals(List.of("10.0.0.2", "8.8.8.8"), upstreams);
    }

    @Test
    void composeUpstreams_dedupesAcrossResolvConfAndFallbacks() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("8.8.8.8"), List.of("8.8.8.8", "8.8.4.4"));
        assertEquals(List.of("8.8.8.8", "8.8.4.4"), upstreams);
    }

    @Test
    void composeUpstreams_toleratesNullFallbacks() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(List.of("10.0.0.2"), null);
        assertEquals(List.of("10.0.0.2"), upstreams);
    }

    // ── forwarding — response buffer size (issue #1110 regression) ────────────

    @Test
    void forwardToUpstreams_returnsResponseLargerThan512BytesIntact() throws Exception {
        // Regression guard: a 512-byte receive buffer silently truncated EDNS0 responses from
        // CDN-backed public hosts, corrupting the answer forwarded back to the Lambda container.
        byte[] bigResponse = new byte[1500];
        for (int i = 0; i < bigResponse.length; i++) {
            bigResponse[i] = (byte) (i & 0xFF);
        }

        try (DatagramSocket responder = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            startResponder(responder, bigResponse);
            byte[] query = buildQuery("business-api.tiktok.com", (short) 0x1234);

            byte[] response = dns.forwardToUpstreams(
                    query, List.of("127.0.0.1"), responder.getLocalPort());

            assertEquals(bigResponse.length, response.length,
                    "response larger than 512 bytes must be forwarded without truncation");
            assertArrayEquals(bigResponse, response);
        }
    }

    /** Replies to the first datagram received with a fixed payload, on a daemon thread. */
    private void startResponder(DatagramSocket responder, byte[] responsePayload) {
        Thread t = new Thread(() -> {
            try {
                DatagramPacket req = new DatagramPacket(new byte[4096], 4096);
                responder.receive(req);
                responder.send(new DatagramPacket(
                        responsePayload, responsePayload.length, req.getAddress(), req.getPort()));
            } catch (Exception ignored) {
                // socket closed when the test completes
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private byte[] encodeName(String name) {
        String[] labels = name.split("\\.");
        int len = 1; // trailing zero
        for (String l : labels) len += 1 + l.length();
        byte[] buf = new byte[len];
        int pos = 0;
        for (String label : labels) {
            buf[pos++] = (byte) label.length();
            for (char c : label.toCharArray()) buf[pos++] = (byte) c;
        }
        buf[pos] = 0;
        return buf;
    }

    private byte[] buildQuery(String name, short txId) {
        byte[] encodedName = encodeName(name);
        // header(12) + name + type(2) + class(2)
        ByteBuffer buf = ByteBuffer.allocate(12 + encodedName.length + 4);
        buf.putShort(txId);
        buf.putShort((short) 0x0100); // standard query, RD=1
        buf.putShort((short) 1);       // qdcount
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.put(encodedName);
        buf.putShort((short) 1); // type A
        buf.putShort((short) 1); // class IN
        return buf.array();
    }
}
