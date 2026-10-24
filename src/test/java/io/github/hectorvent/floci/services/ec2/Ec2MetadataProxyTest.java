package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2MetadataProxyTest {
    @Test
    void authenticatedProxyUsesStandardPathsAndReplacesCallerIdentity(@TempDir Path directory) throws Exception {
        Path script = directory.resolve("proxy.py");
        Path config = directory.resolve("config.json");
        Path log = directory.resolve("proxy-test.log");
        Files.writeString(script, Ec2MetadataProxy.authenticatedProxyScript());
        String harness = """
                import importlib.util, sys, json, threading, http.client, http.server
                spec = importlib.util.spec_from_file_location('guest_proxy', sys.argv[1])
                proxy = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(proxy)
                proxy.CONFIG = sys.argv[2]
                received = []
                class Upstream(http.server.BaseHTTPRequestHandler):
                    def log_message(self, *args):
                        pass
                    def respond(self):
                        received.append((self.command, self.path, self.headers))
                        body = b'metadata-response'
                        self.send_response(200)
                        self.send_header('Content-Length', str(len(body)))
                        self.send_header('x-aws-ec2-metadata-token-ttl-seconds', '60')
                        self.end_headers()
                        self.wfile.write(body)
                    do_GET = respond
                    do_PUT = respond
                upstream = http.server.HTTPServer(('127.0.0.1', 0), Upstream)
                local = http.server.HTTPServer(('127.0.0.1', 0), proxy.MetadataProxy)
                for server in (upstream, local):
                    threading.Thread(target=server.serve_forever, daemon=True).start()
                def configure(capability):
                    with open(proxy.CONFIG, 'w') as target:
                        json.dump({'host': '127.0.0.1', 'port': upstream.server_port, 'capability': capability}, target)
                def request(method, path):
                    client = http.client.HTTPConnection('127.0.0.1', local.server_port, timeout=3)
                    try:
                        client.putrequest(method, path)
                        client.putheader('X-Floci-IMDS-Capability', 'another-guest')
                        client.putheader('x-floci-imds-capability', 'forged')
                        client.putheader('X-Floci-Instance-Id', 'i-other')
                        if method == 'PUT':
                            client.putheader('X-aws-ec2-metadata-token-ttl-seconds', '60')
                        else:
                            client.putheader('X-aws-ec2-metadata-token', 'guest-token')
                        client.endheaders()
                        response = client.getresponse()
                        assert response.status == 200, response.status
                        assert response.getheader('x-aws-ec2-metadata-token-ttl-seconds') == '60'
                        assert response.read() == b'metadata-response'
                    finally:
                        client.close()
                try:
                    configure('owned-capability')
                    request('PUT', '/latest/api/token')
                    method, path, headers = received[-1]
                    assert (method, path) == ('PUT', '/latest/api/token')
                    assert headers.get_all('X-Floci-IMDS-Capability') == ['owned-capability']
                    assert headers.get('X-Floci-Instance-Id') is None
                    assert headers.get('X-aws-ec2-metadata-token-ttl-seconds') == '60'
                    configure('rotated-capability')
                    request('GET', '/latest/meta-data/iam/security-credentials/role')
                    method, path, headers = received[-1]
                    assert (method, path) == ('GET', '/latest/meta-data/iam/security-credentials/role')
                    assert headers.get_all('X-Floci-IMDS-Capability') == ['rotated-capability']
                    assert headers.get('X-aws-ec2-metadata-token') == 'guest-token'
                finally:
                    for server in (local, upstream):
                        server.shutdown()
                        server.server_close()
                """;
        Process process = new ProcessBuilder("python3", "-c", harness, script.toString(), config.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "guest proxy regression timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void authenticatedBootstrapUsesPrivateConfigAndChecksTheExpectedInstance() {
        String install = Ec2MetadataProxy.authenticatedInstallCommand()[2];
        assertTrue(install.contains("command -v python3"));
        assertTrue(install.contains("dnf install -y --allowerasing iproute python3 curl ca-certificates"));
        String start = Ec2MetadataProxy.authenticatedStartCommand("i-owned")[2];
        assertTrue(start.contains("chmod 600 /var/lib/floci-imds-proxy.json.next"));
        assertTrue(start.contains("mv /var/lib/floci-imds-proxy.json.next /var/lib/floci-imds-proxy.json"));
        assertTrue(start.contains("nohup python3 /var/lib/floci-imds-proxy.py"));
        assertTrue(start.contains("http://169.254.169.254/latest/api/token"));
        assertTrue(start.contains("http://169.254.169.254/latest/meta-data/instance-id)\" = 'i-owned'"));
        assertTrue(start.contains("tr '\\000' ' ' </proc/$pid/cmdline"));
    }


    @Test
    void installCommandContainsSupportedPackageManagers() {
        String[] command = Ec2MetadataProxy.installCommand();
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        assertTrue(script.contains("command -v ip >/dev/null 2>&1 && command -v socat >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then exit 0; fi"));
        assertTrue(script.contains("apt-get install -y --no-install-recommends iproute2 socat curl ca-certificates"));
        assertTrue(script.contains("dnf install -y --allowerasing iproute socat curl ca-certificates"));
        assertTrue(script.contains("yum install -y iproute socat curl ca-certificates"));
        assertTrue(script.contains("apk add --no-cache iproute2 socat curl ca-certificates"));
    }

    @Test
    void startCommandAttachesAddressIdempotentlyAndTargetsHostAndPort() {
        String[] command = Ec2MetadataProxy.startCommand("10.0.0.1", 9169);
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        // Attaches address if missing
        assertTrue(script.contains("ip addr show dev lo | grep -q '169.254.169.254/32' || ip addr add 169.254.169.254/32 dev lo"));
        // Idempotent when pid file exists and process is alive
        assertTrue(script.contains("if [ -f /tmp/floci-imds-proxy.pid ] && kill -0 \"$(cat /tmp/floci-imds-proxy.pid)\" 2>/dev/null; then\n  exit 0\nfi"));
        // Targets configured Floci host and IMDS port
        assertTrue(script.contains("nohup socat TCP-LISTEN:80,bind=169.254.169.254,fork,reuseaddr TCP:10.0.0.1:9169 >/tmp/floci-imds-proxy.log 2>&1 &"));
        // Verifies through an IMDSv2 token even when tokenless access is refused.
        assertTrue(script.contains("-X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60'"));
        assertTrue(script.contains("http://169.254.169.254/latest/api/token"));
        assertTrue(script.contains("-H \"X-aws-ec2-metadata-token: $token\" http://169.254.169.254/latest/meta-data/instance-id"));
    }

    @Test
    void podIdentityStartCommandAttachesAddressIdempotentlyAndTargetsHostAndPort() {
        String[] command = Ec2MetadataProxy.podIdentityStartCommand("10.0.0.1", 4566);
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        assertTrue(script.contains("ip addr show dev lo | grep -q '169.254.170.23/32' || ip addr add 169.254.170.23/32 dev lo"));
        assertTrue(script.contains("if [ -f /tmp/floci-pod-identity-proxy.pid ] && kill -0 \"$(cat /tmp/floci-pod-identity-proxy.pid)\" 2>/dev/null; then\n  exit 0\nfi"));
        assertTrue(script.contains("nohup socat TCP-LISTEN:80,bind=169.254.170.23,fork,reuseaddr TCP:10.0.0.1:4566 >/tmp/floci-pod-identity-proxy.log 2>&1 &"));
        assertTrue(script.contains("http://169.254.170.23/v1/credentials"));
    }

    @Test
    void podIdentityStartCommandAttachesAddressIdempotentlyAndTargetsHostAndPort() {
        String[] command = Ec2MetadataProxy.podIdentityStartCommand("10.0.0.1", 4566);
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        assertTrue(script.contains("ip addr show dev lo | grep -q '169.254.170.23/32' || ip addr add 169.254.170.23/32 dev lo"));
        assertTrue(script.contains("if [ -f /tmp/floci-pod-identity-proxy.pid ] && kill -0 \"$(cat /tmp/floci-pod-identity-proxy.pid)\" 2>/dev/null; then\n  exit 0\nfi"));
        assertTrue(script.contains("nohup socat TCP-LISTEN:80,bind=169.254.170.23,fork,reuseaddr TCP:10.0.0.1:4566 >/tmp/floci-pod-identity-proxy.log 2>&1 &"));
        assertTrue(script.contains("http://169.254.170.23/v1/credentials"));
    }

    @Test
    void preferredMetadataSourceIpPrefersConfiguredNetworkOverBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.2");
        ContainerNetwork vpc = new ContainerNetwork();
        vpc.withIpv4Address("10.0.0.2");

        Optional<String> ip = Ec2MetadataProxy.preferredMetadataSourceIp(Map.of("bridge", bridge, "custom-net", vpc));
        assertTrue(ip.isPresent());
        assertEquals("10.0.0.2", ip.get());
    }

    @Test
    void preferredMetadataSourceIpFallsBackToBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.2");

        Optional<String> ip = Ec2MetadataProxy.preferredMetadataSourceIp(Map.of("bridge", bridge));
        assertTrue(ip.isPresent());
        assertEquals("172.17.0.2", ip.get());
    }

    @Test
    void preferredMetadataSourceIpReturnsEmptyWhenNoNetworks() {
        assertTrue(Ec2MetadataProxy.preferredMetadataSourceIp(null).isEmpty());
        assertTrue(Ec2MetadataProxy.preferredMetadataSourceIp(Map.of()).isEmpty());
    }

    @Test
    void installCommandClearsApplicationPathsBeforeThePackageManagerRuns(@TempDir Path directory)
            throws Exception {
        Path bin = Files.createDirectory(directory.resolve("bin"));
        Path record = directory.resolve("tools");
        Path arguments = directory.resolve("arguments");
        HelperShellFixture.writeTool(bin, "dnf", HelperShellFixture.checkedToolEnvironment()
                + "printf '%s\\n' \"$*\" > \"$TEST_ARGUMENTS\"\n");

        assertEquals(0, HelperShellFixture.run(Ec2MetadataProxy.installCommand(), directory,
                HelperShellFixture.applicationEnvironment(Map.of("PATH", bin.toString(),
                        "TEST_RECORD", record.toString(), "TEST_ARGUMENTS", arguments.toString()))),
                HelperShellFixture.output(directory));

        assertEquals("dnf\n", Files.readString(record));
        assertEquals("install -y --allowerasing iproute socat curl ca-certificates\n",
                Files.readString(arguments));
    }

    @Test
    void startCommandClearsApplicationPathsBeforeAddressProxyAndProbeTools(@TempDir Path directory)
            throws Exception {
        Path bin = Files.createDirectory(directory.resolve("bin"));
        Path record = directory.resolve("tools");
        Path proxyReady = directory.resolve("proxy-ready");
        HelperShellFixture.writeTool(bin, "ip", HelperShellFixture.checkedToolEnvironment()
                + "printf '127.0.0.1/32\\n'\n");
        HelperShellFixture.writeTool(bin, "socat", HelperShellFixture.checkedToolEnvironment()
                + ": > \"$TEST_PROXY_READY\"\n");
        HelperShellFixture.writeTool(bin, "curl", HelperShellFixture.checkedToolEnvironment()
                + "for attempt in 1 2 3 4 5 6 7 8 9 10; do\n"
                + "  [ -f \"$TEST_PROXY_READY\" ] && exit 0\n"
                + "  /bin/sleep 0.1\n"
                + "done\nexit 1\n");
        String name = "unit-" + UUID.randomUUID();
        try {
            String[] command = Ec2MetadataProxy.startCommand(
                    name, "127.0.0.1", 80, "127.0.0.1", 9169, "curl fixture && exit 0");
            assertEquals(0, HelperShellFixture.run(command, directory,
                    HelperShellFixture.applicationEnvironment(Map.of("PATH", bin + ":" + System.getenv("PATH"),
                            "TEST_RECORD", record.toString(), "TEST_PROXY_READY", proxyReady.toString()))),
                    HelperShellFixture.output(directory));
            assertEquals(Set.of("ip", "socat", "curl"), Set.copyOf(Files.readAllLines(record)));
        } finally {
            deleteProxyFiles(name);
        }
    }

    @Test
    void readinessProbeReadsTheInstanceIdWithATokenWhenTheInstanceRequiresOne(@TempDir Path directory)
            throws Exception {
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.start();
            requireTokens(fixture.instance());

            assertEquals(0, HelperShellFixture.run(probe(fixture.endpoint()), directory, Map.of()),
                    HelperShellFixture.output(directory));
        }
    }

    /** An image's proxy settings are for its application; the probe checks the link-local path itself. */
    @Test
    void readinessProbeIgnoresTheImagesHttpProxy(@TempDir Path directory) throws Exception {
        List<String> proxied = new CopyOnWriteArrayList<>();
        HttpServer proxy = failingProxy(proxied);
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.start();
            requireTokens(fixture.instance());

            int result = HelperShellFixture.run(probe(fixture.endpoint()), directory, proxyEnvironment(proxy));

            assertEquals(0, result, HelperShellFixture.output(directory));
            assertEquals(List.of(), proxied);
        } finally {
            proxy.stop(0);
        }
    }

    /**
     * The pod identity relay answers 400 without a pod token, which still proves socat relays. A
     * proxy that answered in its place would look the same to the status check, so the probe
     * must not go through one.
     */
    @Test
    void podIdentityProbeIgnoresTheImagesHttpProxy(@TempDir Path directory) throws Exception {
        List<String> proxied = new CopyOnWriteArrayList<>();
        AtomicInteger relayed = new AtomicInteger();
        HttpServer proxy = null;
        HttpServer relay = null;
        try {
            proxy = failingProxy(proxied);
            relay = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            relay.createContext("/v1/credentials", exchange -> {
                relayed.incrementAndGet();
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
            });
            relay.start();
            String[] probe = {"sh", "-c",
                    Ec2MetadataProxy.podIdentityProbeCommand("http://127.0.0.1:" + relay.getAddress().getPort())};

            int result = HelperShellFixture.run(probe, directory, proxyEnvironment(proxy));

            assertEquals(0, result, HelperShellFixture.output(directory));
            assertEquals(1, relayed.get(), HelperShellFixture.output(directory));
            assertEquals(List.of(), proxied);
        } finally {
            if (relay != null) {
                relay.stop(0);
            }
            if (proxy != null) {
                proxy.stop(0);
            }
        }
    }

    /**
     * Floci's IMDS issues a token for every valid request, so the refused and empty token answers
     * come from a stand-in that records what the probe sent.
     */
    @ParameterizedTest
    @CsvSource({"403, rejected", "200, ''"})
    void readinessProbeNeverFallsBackToATokenlessRead(int tokenStatus, String tokenBody, @TempDir Path directory)
            throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = metadataStandIn(requests, attempt -> tokenStatus, tokenBody);
        try {
            int result = HelperShellFixture.run(probe("http://127.0.0.1:" + server.getAddress().getPort()),
                    directory, Map.of());

            assertNotEquals(0, result, HelperShellFixture.output(directory));
            assertEquals(List.of("PUT token 60"), requests, HelperShellFixture.output(directory));
        } finally {
            server.stop(0);
        }
    }

    /**
     * The proxy's first probes run before socat listens, so a refused token has to end only that
     * attempt: the generated start script, under {@code set -eu}, retries until a token is issued.
     */
    @Test
    void startScriptRetriesTheTokenProbeUntilATokenIsIssued(@TempDir Path directory) throws Exception {
        Path bin = Files.createDirectory(directory.resolve("bin"));
        HelperShellFixture.writeTool(bin, "ip", "printf '127.0.0.1/32\\n'\n");
        HelperShellFixture.writeTool(bin, "socat", "exit 0\n");
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = metadataStandIn(requests, attempt -> attempt == 1 ? 403 : 200, "fixture-token");
        String name = "unit-" + UUID.randomUUID();
        try {
            String[] command = Ec2MetadataProxy.startCommand(name, "127.0.0.1", 80, "127.0.0.1", 9169,
                    Ec2MetadataProxy.imdsProbeCommand("http://127.0.0.1:" + server.getAddress().getPort()));

            int result = HelperShellFixture.run(command, directory,
                    Map.of("PATH", bin + ":" + System.getenv("PATH")));

            assertEquals(0, result, HelperShellFixture.output(directory));
            assertEquals(List.of("PUT token 60", "PUT token 60", "GET metadata fixture-token"), requests,
                    HelperShellFixture.output(directory));
        } finally {
            server.stop(0);
            deleteProxyFiles(name);
        }
    }

    /**
     * Answers token requests with the status {@code tokenStatus} picks for each attempt, serves the
     * instance ID to the issued token and, as an instance allowing IMDSv1 would, to a tokenless
     * read, and records what it was sent.
     */
    private static HttpServer metadataStandIn(List<String> requests, TokenStatus tokenStatus, String tokenBody)
            throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest/api/token", exchange -> {
            requests.add(exchange.getRequestMethod() + " token "
                    + exchange.getRequestHeaders().getFirst(Ec2MetadataTestFixture.TTL_HEADER));
            byte[] body = tokenBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(tokenStatus.forAttempt(tokenRequests.incrementAndGet()),
                    body.length == 0 ? -1 : body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/latest/meta-data/instance-id", exchange -> {
            String token = exchange.getRequestHeaders().getFirst(Ec2MetadataTestFixture.TOKEN_HEADER);
            requests.add(exchange.getRequestMethod() + " metadata " + token);
            byte[] body = "i-fixture".getBytes(StandardCharsets.UTF_8);
            boolean accepted = token == null || !tokenBody.isEmpty() && tokenBody.equals(token);
            exchange.sendResponseHeaders(accepted ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @FunctionalInterface
    private interface TokenStatus {
        int forAttempt(int attempt);
    }

    /** An HTTP proxy that records every request it is sent and answers each with 502. */
    private static HttpServer failingProxy(List<String> proxied) throws Exception {
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            proxied.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.sendResponseHeaders(502, -1);
            exchange.close();
        });
        proxy.start();
        return proxy;
    }

    /** The proxy settings an image could carry, with nothing exempted. */
    private static Map<String, String> proxyEnvironment(HttpServer proxy) {
        String proxyUrl = "http://127.0.0.1:" + proxy.getAddress().getPort();
        return Map.of("http_proxy", proxyUrl, "HTTP_PROXY", proxyUrl, "all_proxy", proxyUrl,
                "ALL_PROXY", proxyUrl, "no_proxy", "", "NO_PROXY", "");
    }

    private static String[] probe(String endpoint) {
        return new String[]{"sh", "-c", Ec2MetadataProxy.imdsProbeCommand(endpoint)};
    }

    private static void requireTokens(Instance instance) {
        LaunchTemplateData.MetadataOptions options = LaunchTemplateData.MetadataOptions.launchDefaults();
        options.setHttpTokens("required");
        instance.setMetadataOptions(options);
    }

    private static void deleteProxyFiles(String name) throws Exception {
        Files.deleteIfExists(Path.of("/tmp/floci-" + name + "-proxy.pid"));
        Files.deleteIfExists(Path.of("/tmp/floci-" + name + "-proxy.log"));
    }
}
