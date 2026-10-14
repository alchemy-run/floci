package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The host a domain's search/index REST API is reached at.
 *
 * <p>AWS publishes {@code search-<name>-<id>.<region>.es.amazonaws.com} and serves it over HTTPS
 * with SigV4 ({@code es}) authorization. Floci publishes {@code <name>.<region>.es.<suffix>[:port]},
 * where the suffix is {@code localhost.floci.io} when the gateway is addressed on loopback
 * (wildcard DNS resolves it to 127.0.0.1 on the host, and Floci's embedded DNS resolves it to
 * Floci inside Lambda and task containers) and the configured gateway host otherwise. The
 * gateway routes that host to {@link OpenSearchDataPlaneController}, which authorizes the
 * request and forwards it to the domain's backing container.
 */
public final class OpenSearchDataPlaneEndpoint {

    static final String SERVICE_LABEL = "es";
    private static final List<String> LOOPBACK_HOSTS = List.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Pattern DOMAIN_NAME = Pattern.compile("[a-z][a-z0-9-]{2,27}");
    private static final String DEFAULT_BASE_URL = "http://localhost:4566";

    /** A data-plane host resolved to the domain it addresses. */
    public record Target(String domainName, String region) {}

    private OpenSearchDataPlaneEndpoint() {
    }

    /** {@code <domain>.<region>.es.<suffix>[:port]} for the gateway at {@code baseUrl}. */
    public static String publicEndpoint(String domainName, String region, String baseUrl) {
        URI base = baseUri(baseUrl);
        String port = base.getPort() < 0 ? "" : ":" + base.getPort();
        return domainName + "." + region + "." + SERVICE_LABEL + "." + hostSuffix(baseUrl) + port;
    }

    /** The wildcard server-certificate name covering every domain endpoint in {@code region}. */
    public static String certificateWildcard(String region, String baseUrl) {
        return "*." + region + "." + SERVICE_LABEL + "." + hostSuffix(baseUrl);
    }

    static String hostSuffix(String baseUrl) {
        String host = baseUri(baseUrl).getHost();
        if (host == null || host.isBlank() || LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return EmbeddedDnsServer.DEFAULT_SUFFIX;
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /**
     * Parses {@code <domain>.<region>.es.<suffix>[:port]}. Hosts under {@code amazonaws.com} are not
     * claimed: that name space belongs to the real service.
     */
    public static Optional<Target> parse(String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String hostname = stripPort(host.strip()).toLowerCase(Locale.ROOT);
        if (hostname.endsWith(".")) {
            hostname = hostname.substring(0, hostname.length() - 1);
        }
        String[] labels = hostname.split("\\.", -1);
        if (labels.length < 4 || !SERVICE_LABEL.equals(labels[2])) {
            return Optional.empty();
        }
        if (!DOMAIN_NAME.matcher(labels[0]).matches() || !AwsRegions.isRegionId(labels[1])) {
            return Optional.empty();
        }
        if (hostname.endsWith(".amazonaws.com") || hostname.endsWith(".amazonaws.com.cn")) {
            return Optional.empty();
        }
        return Optional.of(new Target(labels[0], labels[1]));
    }

    private static String stripPort(String host) {
        if (host.startsWith("[")) {
            return host;
        }
        int colon = host.lastIndexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }

    private static URI baseUri(String baseUrl) {
        try {
            return URI.create(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl);
        } catch (IllegalArgumentException e) {
            return URI.create(DEFAULT_BASE_URL);
        }
    }
}
