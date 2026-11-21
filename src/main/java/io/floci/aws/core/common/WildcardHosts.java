package io.floci.aws.core.common;

import io.floci.aws.core.common.dns.EmbeddedDnsServer;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a resource can be given its own host name ({@code <id>.<service>.<region>.localhost.floci.io}) on
 * Floci's endpoint. That works when clients reach Floci on a loopback address or on {@code localhost.floci.io},
 * whose every subdomain resolves to the same address, and the endpoint has no path of its own.
 */
public final class WildcardHosts {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private WildcardHosts() {
    }

    public static boolean available(String baseUrl) {
        return available(URI.create(baseUrl));
    }

    public static boolean available(URI base) {
        String host = base.getHost();
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        boolean resolves = LOOPBACK.contains(lower) || lower.equals(EmbeddedDnsServer.DEFAULT_SUFFIX);
        String path = base.getPath();
        return resolves && (path == null || path.isEmpty() || "/".equals(path));
    }
}
