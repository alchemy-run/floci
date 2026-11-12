package io.floci.aws.services.opensearchserverless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenSearch Serverless policy documents: encryption policies (rules over {@code collection/<name>} patterns with an
 * AWS-owned or KMS key), network policies (public or VPC-endpoint access to collections and dashboards), data access
 * policies (principals granted permissions on collections and indexes) and retention policies. Resource patterns take
 * {@code *} wildcards.
 */
final class AossPolicies {

    static final Set<String> COLLECTION_PERMISSIONS = Set.of("aoss:CreateCollectionItems",
            "aoss:DeleteCollectionItems", "aoss:UpdateCollectionItems", "aoss:DescribeCollectionItems", "aoss:*");
    static final Set<String> INDEX_PERMISSIONS = Set.of("aoss:ReadDocument", "aoss:WriteDocument",
            "aoss:CreateIndex", "aoss:DeleteIndex", "aoss:UpdateIndex", "aoss:DescribeIndex", "aoss:*");
    private static final Pattern RETENTION = Pattern.compile("(\\d+)([hd])");

    private AossPolicies() {
    }

    static JsonNode parse(ObjectMapper mapper, String document) {
        try {
            return mapper.readTree(document);
        } catch (JsonProcessingException e) {
            throw AossStore.validation("The policy isn't valid JSON: " + e.getOriginalMessage());
        }
    }

    /** Whether a resource matches a pattern with {@code *} wildcards. */
    static boolean matches(String pattern, String resource) {
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString()).matcher(resource).matches();
    }

    private static boolean any(JsonNode patterns, String resource) {
        for (JsonNode pattern : patterns) {
            if (matches(pattern.asText(), resource)) {
                return true;
            }
        }
        return false;
    }

    private static void rules(JsonNode rules, Set<String> types, String kind) {
        if (!rules.isArray() || rules.isEmpty()) {
            throw AossStore.validation("The " + kind + " policy needs Rules.");
        }
        for (JsonNode rule : rules) {
            String type = rule.path("ResourceType").asText("");
            if (!types.contains(type)) {
                throw AossStore.validation("The " + kind + " policy's ResourceType must be one of " + types + ".");
            }
            JsonNode resources = rule.path("Resource");
            if (!resources.isArray() || resources.isEmpty()) {
                throw AossStore.validation("Each " + kind + " policy rule needs a Resource list.");
            }
            for (JsonNode resource : resources) {
                if (!resource.asText().startsWith(type + "/")) {
                    throw AossStore.validation("The resource " + resource.asText() + " must start with " + type
                            + "/.");
                }
            }
        }
    }

    static void checkEncryption(JsonNode policy) {
        if (!policy.isObject()) {
            throw AossStore.validation("An encryption policy is a JSON object.");
        }
        rules(policy.path("Rules"), Set.of("collection"), "encryption");
        boolean owned = policy.path("AWSOwnedKey").asBoolean(false);
        boolean kms = policy.hasNonNull("KmsARN");
        if (owned == kms) {
            throw AossStore.validation("An encryption policy sets either AWSOwnedKey to true or a KmsARN.");
        }
    }

    static void checkNetwork(JsonNode policy) {
        if (!policy.isArray() || policy.isEmpty()) {
            throw AossStore.validation("A network policy is a JSON list of statements.");
        }
        for (JsonNode statement : policy) {
            rules(statement.path("Rules"), Set.of("collection", "dashboard"), "network");
            boolean open = statement.path("AllowFromPublic").asBoolean(false);
            boolean sources = statement.path("SourceVPCEs").size() > 0 || statement.path("SourceServices").size() > 0;
            if (!open && !sources) {
                throw AossStore.validation("A network policy statement allows public access or names SourceVPCEs "
                        + "or SourceServices.");
            }
        }
    }

    static void checkData(JsonNode policy) {
        if (!policy.isArray() || policy.isEmpty()) {
            throw AossStore.validation("A data access policy is a JSON list of statements.");
        }
        for (JsonNode statement : policy) {
            rules(statement.path("Rules"), Set.of("collection", "index", "model"), "data access");
            for (JsonNode rule : statement.path("Rules")) {
                Set<String> allowed = "collection".equals(rule.path("ResourceType").asText()) ? COLLECTION_PERMISSIONS
                        : INDEX_PERMISSIONS;
                if (!rule.path("Permission").isArray() || rule.path("Permission").isEmpty()) {
                    throw AossStore.validation("Each data access rule needs a Permission list.");
                }
                for (JsonNode permission : rule.path("Permission")) {
                    if (!allowed.contains(permission.asText())) {
                        throw AossStore.validation("The permission " + permission.asText() + " doesn't apply to "
                                + rule.path("ResourceType").asText() + " resources.");
                    }
                }
            }
            if (!statement.path("Principal").isArray() || statement.path("Principal").isEmpty()) {
                throw AossStore.validation("Each data access statement needs a Principal list.");
            }
        }
    }

    static void checkRetention(JsonNode policy) {
        if (!policy.isObject()) {
            throw AossStore.validation("A retention policy is a JSON object.");
        }
        rules(policy.path("Rules"), Set.of("index"), "retention");
        for (JsonNode rule : policy.path("Rules")) {
            boolean none = rule.path("NoMinIndexRetention").asBoolean(false);
            String retention = rule.path("MinIndexRetention").asText(null);
            if (none == (retention != null)) {
                throw AossStore.validation("Each retention rule sets MinIndexRetention or NoMinIndexRetention.");
            }
            if (retention != null) {
                long hours = hours(retention);
                if (hours < 24 || hours > 3650L * 24) {
                    throw AossStore.validation("MinIndexRetention must be from 24h to 3650d.");
                }
            }
        }
    }

    static long hours(String retention) {
        Matcher matcher = RETENTION.matcher(retention);
        if (!matcher.matches()) {
            throw AossStore.validation("MinIndexRetention is a number of hours or days, like 24h or 30d.");
        }
        long value = Long.parseLong(matcher.group(1));
        return "d".equals(matcher.group(2)) ? value * 24 : value;
    }

    /** Whether an encryption policy covers a collection. */
    static boolean encrypts(JsonNode policy, String collection) {
        for (JsonNode rule : policy.path("Rules")) {
            if (any(rule.path("Resource"), "collection/" + collection)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a network policy statement reaches a collection's endpoint. */
    static boolean reaches(JsonNode policy, String collection) {
        for (JsonNode statement : policy) {
            for (JsonNode rule : statement.path("Rules")) {
                if ("collection".equals(rule.path("ResourceType").asText())
                        && any(rule.path("Resource"), "collection/" + collection)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a data access policy grants a principal a permission on a resource. */
    static boolean grants(JsonNode policy, List<String> principals, String type, String resource, String permission) {
        for (JsonNode statement : policy) {
            boolean principal = false;
            for (JsonNode entry : statement.path("Principal")) {
                principal |= principals.contains(entry.asText());
            }
            if (!principal) {
                continue;
            }
            for (JsonNode rule : statement.path("Rules")) {
                if (!type.equals(rule.path("ResourceType").asText()) || !any(rule.path("Resource"), resource)) {
                    continue;
                }
                for (JsonNode granted : rule.path("Permission")) {
                    if (granted.asText().equals(permission) || "aoss:*".equals(granted.asText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The retention a policy's most specific matching rule sets for an index, or null when none matches. */
    static JsonNode retention(JsonNode policy, String index) {
        JsonNode best = null;
        int bestLength = -1;
        for (JsonNode rule : policy.path("Rules")) {
            for (JsonNode pattern : rule.path("Resource")) {
                if (matches(pattern.asText(), index) && pattern.asText().length() > bestLength) {
                    best = rule;
                    bestLength = pattern.asText().length();
                }
            }
        }
        return best;
    }
}
