package io.floci.aws.services.healthlake;

import io.floci.aws.core.common.dns.EmbeddedDnsServer;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Where a data store's FHIR API is served. AWS publishes {@code https://healthlake.<region>.amazonaws.com/datastore/
 * <id>/r4/}; floci publishes {@code http://healthlake.<region>.<suffix>[:port]/datastore/<id>/r4/}, the suffix
 * {@code localhost.floci.io} when the gateway is addressed on loopback (wildcard DNS resolves it to 127.0.0.1, and
 * floci's embedded DNS to floci inside containers) and the gateway's host otherwise. Any request to a
 * {@code /datastore/<id>/r4} path is a data store's, whichever host it names.
 */
final class HealthLakeEndpoint {

    private static final List<String> LOOPBACK_HOSTS = List.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final String DEFAULT_BASE_URL = "http://localhost:4566";

    private HealthLakeEndpoint() {
    }

    static String publicEndpoint(String datastoreId, String region, String baseUrl) {
        URI base;
        try {
            base = URI.create(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl);
        } catch (IllegalArgumentException e) {
            base = URI.create(DEFAULT_BASE_URL);
        }
        String host = base.getHost();
        String suffix = host == null || host.isBlank() || LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                ? EmbeddedDnsServer.DEFAULT_SUFFIX : host.toLowerCase(Locale.ROOT);
        String port = base.getPort() < 0 ? "" : ":" + base.getPort();
        String scheme = base.getScheme() == null ? "http" : base.getScheme();
        return scheme + "://healthlake." + region + "." + suffix + port + "/datastore/" + datastoreId + "/r4/";
    }
}
