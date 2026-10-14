package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.services.elasticache.container.ServerlessCacheContainerManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;

/**
 * The endpoint hostnames of ElastiCache serverless caches, shaped like AWS's:
 * {@code <name>-<6 chars>.serverless.<region code>.cache.amazonaws.com}, where the region code is
 * AWS's short form ({@code use1} for {@code us-east-1}, {@code apse2} for {@code ap-southeast-2}).
 * The six-character suffix is derived from the account, region and cache name, so a cache keeps
 * its hostname for as long as it exists. Floci's embedded DNS answers each name, for the
 * containers Floci launches, with the address of the cache's own engine container.
 *
 * <p>Valkey and Redis OSS caches listen on 6379 with a reader endpoint on 6380; Memcached caches
 * on 11211 with a reader endpoint on 11212. Every port is TLS-only, as on AWS.
 */
final class ServerlessCacheEndpoints {

    static final int RESP_PORT = ServerlessCacheContainerManager.RESP_PORT;
    static final int RESP_READER_PORT = ServerlessCacheContainerManager.RESP_READER_PORT;
    static final int MEMCACHED_PORT = ServerlessCacheContainerManager.MEMCACHED_PORT;
    static final int MEMCACHED_READER_PORT = ServerlessCacheContainerManager.MEMCACHED_READER_PORT;

    private static final int SUFFIX_LENGTH = 6;
    private static final int MAX_COMMON_NAME_LENGTH = 64;
    private static final String SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Map<String, String> DIRECTIONS = Map.of(
            "east", "e", "west", "w", "north", "n", "south", "s", "central", "c",
            "northeast", "ne", "northwest", "nw", "southeast", "se", "southwest", "sw");

    private ServerlessCacheEndpoints() {
    }

    static String address(String cacheName, String accountId, String region) {
        String domain = region.startsWith("cn-") ? "cache.amazonaws.com.cn" : "cache.amazonaws.com";
        return cacheName + "-" + suffix(cacheName, accountId, region)
                + ".serverless." + regionCode(region) + "." + domain;
    }

    /**
     * The subject common name of the certificate a cache serves. A common name is limited to 64
     * characters (RFC 5280 ub-common-name) and a cache hostname reaches 83 (a 40-character name
     * plus the suffix and domain), so a longer hostname is named by the wildcard for its parent
     * domain. Clients verify the hostname against the certificate's DNS subject alternative name,
     * which always carries the exact hostname.
     */
    static String certificateCommonName(String address) {
        if (address.length() <= MAX_COMMON_NAME_LENGTH) {
            return address;
        }
        return "*" + address.substring(address.indexOf('.'));
    }

    static int primaryPort(String engine) {
        return "memcached".equals(engine) ? MEMCACHED_PORT : RESP_PORT;
    }

    static int readerPort(String engine) {
        return "memcached".equals(engine) ? MEMCACHED_READER_PORT : RESP_READER_PORT;
    }

    /** {@code us-east-1} to {@code use1}, {@code us-gov-west-1} to {@code usgw1}. */
    static String regionCode(String region) {
        String[] parts = region.toLowerCase(Locale.ROOT).split("-");
        StringBuilder code = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.chars().allMatch(Character::isDigit)) {
                code.append(part);
            } else {
                code.append(DIRECTIONS.getOrDefault(part, part.substring(0, 1)));
            }
        }
        return code.toString();
    }

    static String suffix(String cacheName, String accountId, String region) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(
                    ("serverless-cache:" + accountId + ":" + region + ":" + cacheName)
                            .getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(SUFFIX_ALPHABET.charAt((digest[i] & 0xFF) % SUFFIX_ALPHABET.length()));
        }
        return suffix.toString();
    }
}
