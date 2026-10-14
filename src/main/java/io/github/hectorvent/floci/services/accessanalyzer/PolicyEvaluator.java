package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;

/** Bounded, unconditional policy evaluation. Unsupported constructs never produce a PASS. */
final class PolicyEvaluator {
    private static final int STATE_LIMIT = 10_000;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private PolicyEvaluator() {
    }

    static ObjectNode validate(JsonNode request) {
        String type = policyType(request);
        ObjectNode response = JSON.createObjectNode();
        ArrayNode findings = response.putArray("findings");
        try {
            String resourceType = AccessAnalyzerService.text(request, "validatePolicyResourceType");
            if (resourceType != null && (!"RESOURCE_POLICY".equals(type) || !"AWS::S3::Bucket".equals(resourceType))) {
                throw invalid("Floci does not evaluate this validatePolicyResourceType.");
            }
            parse(AccessAnalyzerService.text(request, "policyDocument"), type);
            finding(findings, "WARNING", "FLOCI_LIMITED_VALIDATION",
                    "Floci checked unconditional policy syntax only. AWS service action catalogs, resource/action "
                            + "compatibility, and service-specific security recommendations were not evaluated.");
        } catch (AwsException error) {
            finding(findings, "ERROR", "FLOCI_POLICY_VALIDATION_ERROR", error.getMessage());
        }
        return response;
    }

    static ObjectNode checkNoNewAccess(JsonNode request) {
        requireIdentity(request);
        List<Statement> existing = parse(AccessAnalyzerService.text(request, "existingPolicyDocument"), "IDENTITY_POLICY");
        List<Statement> updated = parse(AccessAnalyzerService.text(request, "newPolicyDocument"), "IDENTITY_POLICY");
        List<Statement> all = new ArrayList<>(existing);
        all.addAll(updated);
        return result(!any(all, (actions, resources) ->
                grants(updated, actions, resources) && !grants(existing, actions, resources)));
    }

    static ObjectNode checkAccessNotGranted(JsonNode request) {
        requireIdentity(request);
        List<Statement> policy = parse(AccessAnalyzerService.text(request, "policyDocument"), "IDENTITY_POLICY");
        JsonNode access = request.get("access");
        if (access == null || !access.isArray() || access.isEmpty() || access.size() > 100) {
            throw invalid("access must contain 1-100 action/resource checks.");
        }
        List<Statement> checks = new ArrayList<>();
        for (JsonNode item : access) {
            fields(item, Set.of("actions", "resources"));
            if (!item.has("actions") && !item.has("resources")) {
                throw invalid("Each access check requires actions or resources.");
            }
            List<String> actions = item.has("actions") ? strings(item.get("actions"), "actions") : List.of("*");
            List<String> resources = item.has("resources") ? strings(item.get("resources"), "resources") : List.of("*");
            checks.add(statement(true, actions, resources));
        }
        List<Statement> all = new ArrayList<>(policy);
        all.addAll(checks);
        return result(!any(all, (actions, resources) ->
                grants(policy, actions, resources) && grants(checks, actions, resources)));
    }

    static ObjectNode checkNoPublicAccess(JsonNode request) {
        if (!"AWS::S3::Bucket".equals(AccessAnalyzerService.text(request, "resourceType"))) {
            throw invalid("Floci public-access checks support only AWS::S3::Bucket policies.");
        }
        List<Statement> publicStatements = parse(AccessAnalyzerService.text(request, "policyDocument"), "RESOURCE_POLICY");
        return result(!any(publicStatements, (actions, resources) -> grants(publicStatements, actions, resources)));
    }

    private static List<Statement> parse(String document, String type) {
        if (document == null || document.isBlank() || document.length() > 100_000) {
            throw invalid("policyDocument must contain 1-100000 characters.");
        }
        JsonNode policy;
        try {
            policy = JSON.readTree(document);
        } catch (JsonProcessingException error) {
            throw invalid("Policy is not valid JSON: " + error.getOriginalMessage());
        }
        fields(policy, Set.of("Version", "Id", "Statement"));
        if (policy.has("Version") && !Set.of("2008-10-17", "2012-10-17").contains(policy.path("Version").asText())) {
            throw invalid("Invalid policy Version.");
        }
        if (policy.has("Id") && !policy.get("Id").isTextual()) {
            throw invalid("Policy Id must be a string.");
        }
        JsonNode statements = policy.get("Statement");
        if (statements == null || (!statements.isArray() && !statements.isObject()) || statements.isEmpty()) {
            throw invalid("Statement must be an object or non-empty list.");
        }
        List<JsonNode> entries = new ArrayList<>();
        if (statements.isObject()) {
            entries.add(statements);
        } else {
            statements.forEach(entries::add);
        }
        if (entries.size() > 100) {
            throw invalid("Floci supports at most 100 statements per policy.");
        }
        List<Statement> result = new ArrayList<>();
        Set<String> sids = new HashSet<>();
        for (JsonNode entry : entries) {
            fields(entry, Set.of("Sid", "Effect", "Action", "Resource", "Principal"));
            String effect = AccessAnalyzerService.text(entry, "Effect");
            if (!"Allow".equals(effect) && !"Deny".equals(effect)) {
                throw invalid("Effect must be Allow or Deny.");
            }
            if (entry.has("Sid") && (!entry.get("Sid").isTextual()
                    || !sids.add(entry.get("Sid").asText()))) {
                throw invalid("Sid must be a unique string.");
            }
            Statement statement = statement("Allow".equals(effect), strings(entry.get("Action"), "Action"),
                    strings(entry.get("Resource"), "Resource"));
            if ("RESOURCE_POLICY".equals(type)) {
                if (publicPrincipal(entry.get("Principal"))) {
                    result.add(statement);
                }
            } else {
                if (entry.has("Principal")) {
                    throw invalid("Identity policies cannot contain Principal.");
                }
                result.add(statement);
            }
        }
        return result;
    }

    private static boolean publicPrincipal(JsonNode principal) {
        if (principal != null && principal.isTextual() && "*".equals(principal.textValue())) {
            return true;
        }
        fields(principal, Set.of("AWS"));
        List<String> values = strings(principal.get("AWS"), "Principal.AWS");
        boolean unrestricted = false;
        for (String value : values) {
            if ("*".equals(value)) {
                unrestricted = true;
            } else if (!value.matches("[0-9]{12}")
                    && !value.matches("arn:aws(?:-us-gov|-cn)?:iam::[0-9]{12}:(root|(?:user|role)/[A-Za-z0-9+=,.@_/-]+)")) {
                throw invalid("Unsupported Principal: only fixed AWS accounts/IAM principals and '*' are evaluated.");
            }
        }
        return unrestricted;
    }

    private static Statement statement(boolean allow, List<String> actions, List<String> resources) {
        for (String action : actions) {
            if (!action.equals("*") && !action.matches("[A-Za-z0-9-]+:[A-Za-z0-9*?]+")) {
                throw invalid("Invalid or unsupported action pattern: " + action);
            }
        }
        for (String resource : resources) {
            if (!resource.equals("*") && (!resource.matches("arn:[a-z0-9-]+:[a-z0-9-]+:[a-z0-9-]*:[0-9]*:.+")
                    || resource.contains("${"))) {
                throw invalid("Only '*' or ARNs with fixed partition/service/region/account are supported.");
            }
        }
        return new Statement(allow, actions.stream().map(action -> action.toLowerCase(Locale.ROOT)).toList(), resources);
    }

    private static List<String> strings(JsonNode value, String field) {
        if (value != null && value.isTextual() && !value.textValue().isEmpty()) {
            return List.of(value.textValue());
        }
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 100) {
            throw invalid(field + " must be a string or a non-empty list of at most 100 strings.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isEmpty()) {
                throw invalid(field + " must contain non-empty strings.");
            }
            result.add(item.textValue());
        }
        return result;
    }

    private static void fields(JsonNode object, Set<String> supported) {
        if (object == null || !object.isObject()) {
            throw invalid("Policy/check elements must be JSON objects.");
        }
        object.fieldNames().forEachRemaining(field -> {
            if (!supported.contains(field)) {
                throw invalid("Unsupported policy/check element: " + field
                        + ". Conditions, NotAction, NotResource and NotPrincipal are not evaluated by Floci.");
            }
        });
    }

    private static String policyType(JsonNode request) {
        String type = AccessAnalyzerService.text(request, "policyType");
        if (!"IDENTITY_POLICY".equals(type) && !"RESOURCE_POLICY".equals(type)) {
            throw invalid("Floci supports IDENTITY_POLICY and RESOURCE_POLICY only.");
        }
        return type;
    }

    private static void requireIdentity(JsonNode request) {
        if (!"IDENTITY_POLICY".equals(policyType(request))) {
            throw invalid("Floci access and subset checks support IDENTITY_POLICY only.");
        }
    }

    private record Statement(boolean allow, List<String> actions, List<String> resources) {
        boolean matches(Set<String> actionMatches, Set<String> resourceMatches) {
            return actions.stream().anyMatch(actionMatches::contains) && resources.stream().anyMatch(resourceMatches::contains);
        }
    }

    private static boolean grants(List<Statement> statements, Set<String> actions, Set<String> resources) {
        boolean allowed = false;
        for (Statement statement : statements) {
            if (statement.matches(actions, resources)) {
                if (!statement.allow()) {
                    return false;
                }
                allowed = true;
            }
        }
        return allowed;
    }

    private static boolean any(List<Statement> statements, BiPredicate<Set<String>, Set<String>> predicate) {
        List<String> actions = statements.stream().flatMap(statement -> statement.actions().stream()).distinct().toList();
        List<String> resources = statements.stream().flatMap(statement -> statement.resources().stream()).distinct().toList();
        Set<Set<String>> actionClasses = matchClasses(actions);
        Set<Set<String>> resourceClasses = matchClasses(resources);
        if ((long) actionClasses.size() * resourceClasses.size() > STATE_LIMIT) {
            throw invalid("Policy analysis exceeds Floci's bounded evaluator capacity.");
        }
        for (Set<String> action : actionClasses) {
            for (Set<String> resource : resourceClasses) {
                if (predicate.test(action, resource)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Product of glob NFAs: each reachable match set represents a whole class of strings, not a sample.
    private static Set<Set<String>> matchClasses(List<String> patterns) {
        if (patterns.stream().mapToInt(String::length).sum() > STATE_LIMIT) {
            throw invalid("Policy patterns exceed Floci's bounded evaluator capacity.");
        }
        Set<Character> alphabet = new LinkedHashSet<>();
        for (String pattern : patterns) {
            for (char character : pattern.toCharArray()) {
                if (character != '*' && character != '?') {
                    alphabet.add(character);
                }
            }
        }
        char other = 0;
        while (alphabet.contains(other)) {
            other++;
        }
        alphabet.add(other);
        List<BitSet> initial = new ArrayList<>();
        for (String pattern : patterns) {
            BitSet state = new BitSet();
            state.set(0);
            initial.add(closure(pattern, state));
        }
        Set<List<BitSet>> seen = new HashSet<>();
        ArrayDeque<List<BitSet>> pending = new ArrayDeque<>();
        seen.add(initial);
        pending.add(initial);
        Set<Set<String>> classes = new HashSet<>();
        while (!pending.isEmpty()) {
            List<BitSet> current = pending.remove();
            Set<String> matches = new HashSet<>();
            for (int i = 0; i < patterns.size(); i++) {
                if (current.get(i).get(patterns.get(i).length())) {
                    matches.add(patterns.get(i));
                }
            }
            classes.add(Set.copyOf(matches));
            for (char character : alphabet) {
                List<BitSet> next = new ArrayList<>();
                for (int i = 0; i < patterns.size(); i++) {
                    String pattern = patterns.get(i);
                    BitSet state = current.get(i);
                    BitSet advanced = new BitSet();
                    for (int position = state.nextSetBit(0); position >= 0 && position < pattern.length();
                         position = state.nextSetBit(position + 1)) {
                        char expected = pattern.charAt(position);
                        if (expected == '*') {
                            advanced.set(position);
                        } else if (expected == '?' || expected == character) {
                            advanced.set(position + 1);
                        }
                    }
                    next.add(closure(pattern, advanced));
                }
                if (seen.add(next)) {
                    if ((long) seen.size() * alphabet.size() > STATE_LIMIT) {
                        throw invalid("Policy analysis exceeds Floci's bounded evaluator capacity.");
                    }
                    pending.add(next);
                }
            }
        }
        return classes;
    }

    private static BitSet closure(String pattern, BitSet state) {
        for (int position = state.nextSetBit(0); position >= 0 && position < pattern.length();
             position = state.nextSetBit(position + 1)) {
            if (pattern.charAt(position) == '*') {
                state.set(position + 1);
            }
        }
        return state;
    }

    private static ObjectNode result(boolean pass) {
        ObjectNode result = JSON.createObjectNode();
        result.put("result", pass ? "PASS" : "FAIL");
        result.put("message", pass ? "No prohibited access in the supported unconditional policy model."
                : "The policy grants access prohibited by this check.");
        result.putArray("reasons").addObject().put("description", result.path("message").asText());
        return result;
    }

    private static void finding(ArrayNode findings, String type, String code, String details) {
        ObjectNode finding = findings.addObject();
        finding.put("findingType", type);
        finding.put("issueCode", code);
        finding.put("findingDetails", details);
        finding.put("learnMoreLink", "https://docs.aws.amazon.com/IAM/latest/UserGuide/access-analyzer-policy-validation.html");
        finding.putArray("locations");
    }

    private static AwsException invalid(String message) {
        return AccessAnalyzerService.validation(message);
    }
}
