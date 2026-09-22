package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // Verifies via link-local curl
        assertTrue(script.contains("curl -fsS --max-time 1 http://169.254.169.254/latest/meta-data/instance-id >/dev/null && exit 0"));
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
}
