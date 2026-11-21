package io.floci.aws.services.grafana;

import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The host a workspace's Grafana is reached at. AWS publishes {@code <id>.grafana-workspace.<region>.amazonaws.com};
 * floci publishes {@code <id>.grafana-workspace.<region>.<suffix>[:port]}, where the suffix is
 * {@code localhost.floci.io} when the gateway is addressed on loopback (wildcard DNS resolves it to 127.0.0.1 on the
 * host, and floci's embedded DNS resolves it to floci inside Lambda and task containers) and the gateway's host
 * otherwise. The gateway routes that host to {@link GrafanaDataPlaneController}, which forwards to the workspace's
 * Grafana.
 */
public final class GrafanaEndpoint {

    static final String SERVICE_LABEL = "grafana-workspace";
    private static final List<String> LOOPBACK_HOSTS = List.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Pattern WORKSPACE_ID = Pattern.compile("g-[0-9a-f]{10}");
    private static final String DEFAULT_BASE_URL = "http://localhost:4566";

    /** A workspace host resolved to the workspace it addresses. */
    public record Target(String workspaceId, String region) {
    }

    private GrafanaEndpoint() {
    }

    /** {@code <id>.grafana-workspace.<region>.<suffix>[:port]} for the gateway at {@code baseUrl}. */
    static String publicEndpoint(String workspaceId, String region, String baseUrl) {
        URI base = baseUri(baseUrl);
        String port = base.getPort() < 0 ? "" : ":" + base.getPort();
        return workspaceId + "." + SERVICE_LABEL + "." + region + "." + hostSuffix(baseUrl) + port;
    }

    /** The URL Grafana is told it is served at, so its links and redirects name the workspace endpoint. */
    static String rootUrl(String workspaceId, String region, String baseUrl) {
        String scheme = baseUri(baseUrl).getScheme();
        return (scheme == null ? "http" : scheme) + "://" + publicEndpoint(workspaceId, region, baseUrl) + "/";
    }

    private static String hostSuffix(String baseUrl) {
        String host = baseUri(baseUrl).getHost();
        if (host == null || host.isBlank() || LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return EmbeddedDnsServer.DEFAULT_SUFFIX;
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /** Parses {@code <id>.grafana-workspace.<region>.<suffix>[:port]}; AWS's own names are not claimed. */
    public static Optional<Target> parse(String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String hostname = host.strip().toLowerCase(Locale.ROOT);
        int colon = hostname.lastIndexOf(':');
        if (colon > 0 && hostname.indexOf(']') < colon) {
            hostname = hostname.substring(0, colon);
        }
        if (hostname.endsWith(".")) {
            hostname = hostname.substring(0, hostname.length() - 1);
        }
        String[] labels = hostname.split("\\.", -1);
        if (labels.length < 4 || !SERVICE_LABEL.equals(labels[1]) || !WORKSPACE_ID.matcher(labels[0]).matches()
                || !AwsRegions.isRegionId(labels[2])) {
            return Optional.empty();
        }
        // The workspace's own Region's AWS name belongs to the real service.
        if (hostname.endsWith("." + AwsRegions.dnsSuffixFor(labels[2]))) {
            return Optional.empty();
        }
        return Optional.of(new Target(labels[0], labels[2]));
    }

    private static URI baseUri(String baseUrl) {
        try {
            return URI.create(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl);
        } catch (IllegalArgumentException e) {
            return URI.create(DEFAULT_BASE_URL);
        }
    }
}
