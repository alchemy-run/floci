package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * ElastiCache endpoint hostnames in the shape AWS gives them, with Floci's DNS suffix in place of
 * {@code amazonaws.com}.
 *
 * <p>AWS names a cluster-mode-disabled replication group's primary endpoint
 * {@code master.<group>.<hash>.<region-code>.cache.amazonaws.com} and a Memcached cluster's
 * configuration endpoint {@code <cluster>.<hash>.cfg.<region-code>.cache.amazonaws.com}, where the
 * six-character hash is fixed per account and region and the region code is the short form
 * ({@code use1} for us-east-1). Every group therefore has a name of its own, which is what lets
 * any number of them use the same port. {@code *.localhost.floci.io} is public wildcard DNS for
 * 127.0.0.1 on the host, and Floci's embedded DNS answers it inside the containers it launches.
 */
final class ElastiCacheEndpoints {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int HASH_LENGTH = 6;
    private static final Pattern IP_LITERAL = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");

    private ElastiCacheEndpoints() {
    }

    static String primary(String groupId, String accountId, String region, Optional<String> hostname) {
        String suffix = suffix(hostname);
        if (IP_LITERAL.matcher(suffix).matches()) {
            return suffix;
        }
        return "master." + groupId.toLowerCase(Locale.ROOT) + "." + hash(accountId, region) + "."
                + regionCode(region) + ".cache." + suffix;
    }

    static String memcachedConfiguration(String clusterId, String accountId, String region,
                                         Optional<String> hostname) {
        String suffix = suffix(hostname);
        if (IP_LITERAL.matcher(suffix).matches()) {
            return suffix;
        }
        return clusterId.toLowerCase(Locale.ROOT) + "." + hash(accountId, region) + ".cfg."
                + regionCode(region) + ".cache." + suffix;
    }

    /**
     * The configured Floci hostname, or the wildcard domain when none is set. A bare
     * {@code localhost} cannot carry a prefix, so it also means the wildcard domain.
     */
    static String suffix(Optional<String> hostname) {
        return hostname == null ? EmbeddedDnsServer.DEFAULT_SUFFIX
                : hostname.filter(h -> !h.isBlank() && !"localhost".equalsIgnoreCase(h))
                        .orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
    }

    /** AWS's short region code: {@code us-east-1} is {@code use1}, {@code ap-southeast-2} is {@code apse2}. */
    static String regionCode(String region) {
        String[] parts = region.toLowerCase(Locale.ROOT).split("-");
        if (parts.length < 3) {
            return region.toLowerCase(Locale.ROOT).replace("-", "");
        }
        StringBuilder code = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length - 1; i++) {
            code.append(directionCode(parts[i]));
        }
        return code.append(parts[parts.length - 1]).toString();
    }

    private static String directionCode(String word) {
        String code = word.replace("north", "n").replace("south", "s")
                .replace("east", "e").replace("west", "w").replace("central", "c");
        return code.length() <= 2 ? code : word;
    }

    static String hash(String accountId, String region) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(("elasticache:" + accountId + ":" + region).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        StringBuilder hash = new StringBuilder(HASH_LENGTH);
        for (int i = 0; i < HASH_LENGTH; i++) {
            hash.append(ALPHABET.charAt((digest[i] & 0xFF) % ALPHABET.length()));
        }
        return hash.toString();
    }
}
