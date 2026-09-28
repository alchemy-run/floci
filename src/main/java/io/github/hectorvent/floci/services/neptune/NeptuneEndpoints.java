package io.github.hectorvent.floci.services.neptune;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Neptune endpoint hostnames in the shape AWS gives them, with Floci's DNS suffix in place of
 * {@code amazonaws.com}.
 *
 * <p>AWS names a cluster {@code <id>.cluster-<hash>.<region>.neptune.amazonaws.com}, its reader
 * {@code <id>.cluster-ro-<hash>...} and an instance {@code <id>.<hash>.<region>.neptune...},
 * where the 12-character hash is fixed per account and region. The suffix is one Floci's DNS
 * already answers: {@code *.localhost.floci.io} is public wildcard DNS for 127.0.0.1 on the host,
 * and Floci's embedded DNS resolves it to Floci inside the containers it launches.
 */
final class NeptuneEndpoints {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int HASH_LENGTH = 12;

    private NeptuneEndpoints() {
    }

    static String cluster(String clusterId, String accountId, String region, String suffix) {
        return host(clusterId, "cluster-" + hash(accountId, region), region, suffix);
    }

    static String reader(String clusterId, String accountId, String region, String suffix) {
        return host(clusterId, "cluster-ro-" + hash(accountId, region), region, suffix);
    }

    static String instance(String instanceId, String accountId, String region, String suffix) {
        return host(instanceId, hash(accountId, region), region, suffix);
    }

    static String hash(String accountId, String region) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest((accountId + ":" + region).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        StringBuilder hash = new StringBuilder(HASH_LENGTH);
        for (int i = 0; i < HASH_LENGTH; i++) {
            hash.append(ALPHABET.charAt((digest[i] & 0xFF) % ALPHABET.length()));
        }
        return hash.toString();
    }

    private static String host(String id, String label, String region, String suffix) {
        return id.toLowerCase(Locale.ROOT) + "." + label + "." + region + ".neptune." + suffix;
    }
}
