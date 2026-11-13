package io.floci.aws.services.imagebuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import static io.floci.aws.services.imagebuilder.ImageBuilderStore.idempotency;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.invalidParameter;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.key;

/**
 * Image Builder's versioned ARNs ({@code component/<name>/<version>[/<build>]}, with {@code x} wildcards in a
 * version) and the client tokens that make its create and start requests idempotent.
 */
final class ImageBuilderRefs {

    private ImageBuilderRefs() {
    }

    /**
     * A parsed Image Builder ARN: its owner account, Region, kind, the workflow type segment for a workflow, the
     * name, and the version and build when it has them.
     */
    record Ref(String account, String region, String kind, String type, String name, String version, String build) {

        boolean amazon() {
            return ImageBuilderManaged.OWNER_ACCOUNT.equals(account);
        }

        boolean wildcard() {
            return version != null && version.contains("x");
        }

        /** The stored resource prefix of this name: {@code kind[/type]/name/}. */
        String namePrefix() {
            return kind + (type == null ? "" : "/" + type) + "/" + name + "/";
        }
    }

    static Ref parse(String arn, String kind) {
        AwsArnUtils.Arn parsed = ImageBuilderStore.parse(arn);
        if (parsed == null) {
            throw invalidParameter("The value supplied for parameter '" + kind + "Arn' is not a valid ARN: " + arn);
        }
        String[] parts = parsed.resource().split("/");
        if (parts.length < 2 || !parts[0].equals(kind)) {
            throw invalidParameter("The ARN " + arn + " is not an Image Builder " + kind + " ARN.");
        }
        int at = 1;
        String type = null;
        if ("workflow".equals(kind)) {
            if (parts.length < 3) {
                throw invalidParameter("The ARN " + arn + " is not a workflow ARN.");
            }
            type = parts[1];
            at = 2;
        }
        String name = parts[at];
        String version = parts.length > at + 1 ? parts[at + 1] : null;
        String build = parts.length > at + 2 ? parts[at + 2] : null;
        return new Ref(parsed.accountId(), parsed.region(), kind, type, name, version, build);
    }

    /** Whether a version matches a pattern whose parts may be {@code x}. */
    static boolean matches(String pattern, String version) {
        if (pattern == null) {
            return true;
        }
        String[] want = pattern.split("\\.");
        String[] have = version.split("\\.");
        if (want.length != have.length) {
            return false;
        }
        for (int i = 0; i < want.length; i++) {
            if (!"x".equals(want[i]) && !want[i].equals(have[i])) {
                return false;
            }
        }
        return true;
    }

    /** Orders semantic versions numerically, part by part. */
    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long left = i < x.length ? parseLong(x[i]) : 0;
            long right = i < y.length ? parseLong(y[i]) : 0;
            if (left != right) {
                return Long.compare(left, right);
            }
        }
        return 0;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A semantic version of three non-negative integers, or InvalidVersionNumberException. */
    static String semanticVersion(String version) {
        if (version == null || !version.matches("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")) {
            throw ImageBuilderStore.invalidVersion("The semantic version " + version
                    + " is not valid. Use <major>.<minor>.<patch> with non-negative integers.");
        }
        return version;
    }

    /**
     * Runs a create or start request once for its client token: a repeated request with the same token and
     * parameters gets the first response, and one with other parameters IdempotentParameterMismatchException.
     */
    static ObjectNode idempotent(ImageBuilderStore store, String region, String operation, JsonNode input,
                                 Supplier<ObjectNode> call) {
        String token = input.path("clientToken").asText("");
        if (token.isEmpty()) {
            return call.get();
        }
        ObjectNode request = input.deepCopy();
        request.remove("clientToken");
        String fingerprint = sha256(request.toString());
        String tokenKey = key(region, "client-token/" + operation + "/" + token);
        ObjectNode seen = store.get(tokenKey).orElse(null);
        if (seen != null) {
            if (!fingerprint.equals(seen.path("fingerprint").asText())) {
                throw idempotency("The client token " + token + " was used with other parameters.");
            }
            return (ObjectNode) seen.path("response").deepCopy();
        }
        ObjectNode response = call.get();
        ObjectNode record = store.record("client-token", "client-token/" + operation + "/" + token);
        record.put("fingerprint", fingerprint);
        record.set("response", response.deepCopy());
        store.put(tokenKey, record);
        return response;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The values of a list filter ({@code [{name, values}]}) named {@code name}, or null when it is absent. */
    static List<String> filter(JsonNode filters, String name) {
        for (JsonNode filter : filters) {
            if (name.equals(filter.path("name").asText())) {
                List<String> values = new ArrayList<>();
                filter.path("values").forEach(value -> values.add(value.asText()));
                return values;
            }
        }
        return null;
    }
}
