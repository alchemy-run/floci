package io.floci.aws.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A role's trust policy as IAM returns it: a list of one value is written as that value, for a statement's
 * actions and resources, its principals and its condition values. {@code ["sts:AssumeRole"]} reads back as
 * {@code "sts:AssumeRole"}. The stored document, which trust is evaluated against, is unchanged.
 */
final class TrustPolicyForm {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> LISTS = Set.of("Action", "NotAction", "Resource", "NotResource");
    private static final Set<String> PRINCIPALS = Set.of("Principal", "NotPrincipal");

    private TrustPolicyForm() {
    }

    /** The document in IAM's form, or the document itself when it isn't JSON. */
    static String of(String document) {
        if (document == null) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(document);
        } catch (JsonProcessingException e) {
            return document;
        }
        if (root == null || !root.isObject()) {
            return document;
        }
        JsonNode statements = root.get("Statement");
        if (statements != null && statements.isArray()) {
            statements.forEach(TrustPolicyForm::statement);
        } else if (statements != null && statements.isObject()) {
            statement(statements);
        }
        return root.toString();
    }

    private static void statement(JsonNode node) {
        if (!(node instanceof ObjectNode statement)) {
            return;
        }
        for (String field : LISTS) {
            collapse(statement, field);
        }
        for (String field : PRINCIPALS) {
            if (statement.get(field) instanceof ObjectNode principal) {
                fields(principal).forEach(key -> collapse(principal, key));
            }
        }
        if (statement.get("Condition") instanceof ObjectNode condition) {
            for (String operator : fields(condition)) {
                if (condition.get(operator) instanceof ObjectNode keys) {
                    fields(keys).forEach(key -> collapse(keys, key));
                }
            }
        }
    }

    private static void collapse(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.isArray() && value.size() == 1) {
            node.set(field, value.get(0));
        }
    }

    private static List<String> fields(ObjectNode node) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            out.add(entry.getKey());
        }
        return out;
    }
}
