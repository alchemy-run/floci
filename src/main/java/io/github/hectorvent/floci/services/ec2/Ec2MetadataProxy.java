package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;

import java.util.Map;
import java.util.Optional;

/**
 * Shared commands and network resolution helpers for installing and starting
 * the link-local IMDS proxy (169.254.169.254:80) inside containers.
 */
public final class Ec2MetadataProxy {

    static final String ENDPOINT = "http://169.254.169.254";
    static final String SCRIPT_PATH = "/var/lib/floci-imds-proxy.py";
    static final String CONFIG_PATH = "/var/lib/floci-imds-proxy.json";

    private Ec2MetadataProxy() {}

    public static String[] authenticatedInstallCommand() {
        String[] command = installCommand();
        command[2] = command[2].replace("socat", "python3");
        return command;
    }

    public static String authenticatedProxyScript() {
        return """
                import http.client
                import http.server
                import json

                CONFIG = '/var/lib/floci-imds-proxy.json'
                IDENTITY_HEADER = 'X-Floci-IMDS-Capability'
                FORWARDED_HEADERS = (
                    'x-aws-ec2-metadata-token',
                    'x-aws-ec2-metadata-token-ttl-seconds',
                    'x-forwarded-for',
                )

                class MetadataProxy(http.server.BaseHTTPRequestHandler):
                    def setup(self):
                        super().setup()
                        self.connection.settimeout(5)

                    def log_message(self, *args):
                        pass

                    def relay(self):
                        if not self.path.startswith('/latest/'):
                            self.send_error(404)
                            return
                        if self.headers.get('Transfer-Encoding') or self.headers.get('Content-Length', '0') != '0':
                            self.send_error(400)
                            return
                        connection = None
                        try:
                            with open(CONFIG) as source:
                                config = json.load(source)
                            # Allowlisting drops every caller-supplied identity header, including duplicates.
                            headers = {}
                            for name in FORWARDED_HEADERS:
                                values = self.headers.get_all(name, [])
                                if len(values) > 1:
                                    self.send_error(400)
                                    return
                                if values:
                                    headers[name] = values[0]
                            headers[IDENTITY_HEADER] = config['capability']
                            connection = http.client.HTTPConnection(config['host'], config['port'], timeout=5)
                            connection.request(self.command, self.path, headers=headers)
                            response = connection.getresponse()
                            body = response.read(10 * 1024 * 1024 + 1)
                            if len(body) > 10 * 1024 * 1024:
                                self.send_error(502)
                                return
                            self.send_response(response.status)
                            for name in ('content-type', 'x-aws-ec2-metadata-token-ttl-seconds'):
                                value = response.getheader(name)
                                if value is not None:
                                    self.send_header(name, value)
                            self.send_header('Content-Length', str(len(body)))
                            self.end_headers()
                            self.wfile.write(body)
                        except (OSError, ValueError, KeyError, http.client.HTTPException):
                            self.send_error(502, 'Metadata upstream unavailable')
                        finally:
                            if connection is not None:
                                connection.close()

                    do_GET = relay
                    do_PUT = relay

                if __name__ == '__main__':
                    http.server.HTTPServer(('169.254.169.254', 80), MetadataProxy).serve_forever()
                """;
    }

    public static String[] authenticatedStartCommand(String instanceId) {
        if (instanceId == null || !instanceId.matches("i-[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid EC2 instance ID");
        }
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                "ip addr show dev lo | grep -q '169.254.169.254/32' || ip addr add 169.254.169.254/32 dev lo",
                "chmod 600 /var/lib/floci-imds-proxy.json.next",
                "mv /var/lib/floci-imds-proxy.json.next " + CONFIG_PATH,
                // Upgrade only the old Floci relay, never an unrelated process that reused its PID.
                "if [ -f /tmp/floci-imds-proxy.pid ]; then",
                "  old=$(cat /tmp/floci-imds-proxy.pid)",
                "  if [ -r /proc/$old/cmdline ] && tr '\\000' ' ' </proc/$old/cmdline | grep -q '^socat TCP-LISTEN:80,bind=169.254.169.254,fork,reuseaddr TCP:'; then kill \"$old\"; fi",
                "fi",
                "pid=$(cat /var/lib/floci-imds-proxy.pid 2>/dev/null || true)",
                "if [ -z \"$pid\" ] || ! [ -r /proc/$pid/cmdline ] || ! tr '\\000' ' ' </proc/$pid/cmdline | grep -q '^python3 /var/lib/floci-imds-proxy.py'; then",
                "  nohup python3 " + SCRIPT_PATH + " >/var/log/floci-imds-proxy.log 2>&1 &",
                "  echo $! >/var/lib/floci-imds-proxy.pid",
                "fi",
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do",
                "  token=$(curl --noproxy '*' -fsS --max-time 1 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' " + ENDPOINT + "/latest/api/token) || token=''",
                "  if [ -n \"$token\" ] && [ \"$(curl --noproxy '*' -fsS --max-time 1 -H \"X-aws-ec2-metadata-token: $token\" " + ENDPOINT + "/latest/meta-data/instance-id)\" = '" + instanceId + "' ]; then exit 0; fi",
                "  sleep 1",
                "done",
                "exit 1")};
    }

    public static String[] installCommand() {
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                "if command -v ip >/dev/null 2>&1 && command -v socat >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then exit 0; fi",
                "if command -v apt-get >/dev/null 2>&1; then",
                "  apt-get update -qq >/dev/null",
                "  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends iproute2 socat curl ca-certificates >/dev/null",
                "elif command -v dnf >/dev/null 2>&1; then",
                // --allowerasing lets dnf swap the curl-minimal that
                // public.ecr.aws/amazonlinux/amazonlinux:2023 ships by default for the full
                // curl package this proxy needs. Without it, dnf aborts the whole transaction
                // on a curl/curl-minimal conflict and iproute+socat never install either, even
                // though neither of them conflicts with anything.
                "  dnf install -y --allowerasing iproute socat curl ca-certificates >/dev/null",
                // Same gap as the sshd probe: Amazon Linux 2 has only yum, so on an instance
                // launched from ami-amazonlinux2 this chain reached its else branch and exited 1
                // with "No supported package manager found for IMDS proxy dependencies",
                // leaving the instance without a link-local IMDS endpoint.
                "elif command -v yum >/dev/null 2>&1; then",
                "  yum install -y iproute socat curl ca-certificates >/dev/null",
                "elif command -v apk >/dev/null 2>&1; then",
                "  apk add --no-cache iproute2 socat curl ca-certificates >/dev/null",
                "else",
                "  echo 'No supported package manager found for IMDS proxy dependencies' >&2",
                "  exit 1",
                "fi")};
    }

    public static String[] startCommand(String flociHost, int imdsPort) {
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                "ip addr show dev lo | grep -q '169.254.169.254/32' || ip addr add 169.254.169.254/32 dev lo",
                "if [ -f /tmp/floci-imds-proxy.pid ] && kill -0 \"$(cat /tmp/floci-imds-proxy.pid)\" 2>/dev/null; then",
                "  exit 0",
                "fi",
                "nohup socat TCP-LISTEN:80,bind=169.254.169.254,fork,reuseaddr TCP:" + flociHost + ":" + imdsPort + " >/tmp/floci-imds-proxy.log 2>&1 &",
                "echo $! > /tmp/floci-imds-proxy.pid",
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do",
                "  curl -fsS --max-time 1 http://169.254.169.254/latest/meta-data/instance-id >/dev/null && exit 0",
                "  sleep 1",
                "done",
                "cat /tmp/floci-imds-proxy.log >&2 || true",
                "exit 1")};
    }

    public static Optional<String> preferredMetadataSourceIp(Map<String, ContainerNetwork> networks) {
        if (networks == null || networks.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> configuredNetworkIp = networks.entrySet().stream()
                .filter(entry -> !"bridge".equals(entry.getKey()))
                .map(Map.Entry::getValue)
                .map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst();
        if (configuredNetworkIp.isPresent()) {
            return configuredNetworkIp;
        }
        ContainerNetwork bridge = networks.get("bridge");
        if (bridge != null && bridge.getIpAddress() != null && !bridge.getIpAddress().isBlank()) {
            return Optional.of(bridge.getIpAddress());
        }
        return networks.values().stream()
                .map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst();
    }
}
