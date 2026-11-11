package io.floci.aws.services.entityresolution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.aws.services.entityresolution.EntityResolutionStore.invalid;

/**
 * How Entity Resolution decides that records are the same entity: each record's fields are normalized by their
 * schema attribute type and gathered under their match keys, then compared by the workflow's resolution technique,
 * and the records each comparison links are grouped transitively into one match.
 *
 * <ul>
 *   <li>Rule matching compares the match keys a rule names, the first rule that links two groups naming the
 *       match. One-to-one compares a key with the same key; many-to-many with every key of the same type.</li>
 *   <li>Rule conditions are expressions over match keys: {@code EXACT(k)}, {@code EXACTMANYTOMANY(k)},
 *       {@code k = k}, {@code LEVENSHTEIN(k, distance)}, {@code COSINE(k, similarity)} and {@code SOUNDEX(k)},
 *       joined by {@code AND} and {@code OR} with parentheses.</li>
 *   <li>Machine-learning matching, which floci approximates, links records whose shared keys are similar on
 *       average (Jaro-Winkler) by at least {@value #ML_THRESHOLD}, reporting that average as its confidence.</li>
 * </ul>
 */
final class EntityResolutionMatcher {

    static final double ML_THRESHOLD = 0.85;

    private EntityResolutionMatcher() {
    }

    /** A schema field: its name, attribute type, match key and group. */
    record Field(String name, String type, String matchKey, String groupName, boolean hashed) {
    }

    /** A record of an input source: its unique id, its source and its attributes by field name. */
    record Rec(String sourceArn, String id, Map<String, String> attributes) {
    }

    /** A match: its records, the rule that matched them (null for one record), and its confidence (ML only). */
    record Group(List<Rec> records, String rule, Double confidence) {
    }

    /** The fields of a schema mapping's {@code mappedInputFields}. */
    static List<Field> fields(JsonNode mappedInputFields) {
        List<Field> fields = new ArrayList<>();
        for (JsonNode field : mappedInputFields) {
            fields.add(new Field(field.path("fieldName").asText(), field.path("type").asText(),
                    field.path("matchKey").asText(null), field.path("groupName").asText(null),
                    field.path("hashed").asBoolean(false)));
        }
        return fields;
    }

    /** The field that holds a record's unique id. */
    static String uniqueIdField(List<Field> fields) {
        for (Field field : fields) {
            if ("UNIQUE_ID".equals(field.type())) {
                return field.name();
            }
        }
        return null;
    }

    /** A value as normalization leaves it for its attribute type. */
    static String normalize(String type, String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (type.startsWith("PHONE")) {
            return trimmed.replaceAll("[^0-9]", "");
        }
        if (type.startsWith("NAME") || type.startsWith("ADDRESS")) {
            return trimmed.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").replaceAll("\\s+", " ");
        }
        if ("EMAIL_ADDRESS".equals(type)) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if ("UNIQUE_ID".equals(type)) {
            return trimmed;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /** A record's value for each match key: its fields under the key, normalized when asked, joined in order. */
    static Map<String, String> keys(Rec record, List<Field> fields, boolean normalize) {
        Map<String, List<String>> parts = new LinkedHashMap<>();
        for (Field field : fields) {
            if (field.matchKey() == null) {
                continue;
            }
            String value = record.attributes().get(field.name());
            if (normalize) {
                value = normalize(field.type(), value);
            }
            if (value != null && !value.isEmpty()) {
                parts.computeIfAbsent(field.matchKey(), key -> new ArrayList<>()).add(value);
            }
        }
        Map<String, String> keys = new LinkedHashMap<>();
        parts.forEach((key, values) -> keys.put(key, String.join(" ", values)));
        return keys;
    }

    /** The attribute type of each match key: its first field's. */
    static Map<String, String> keyTypes(List<Field> fields) {
        Map<String, String> types = new HashMap<>();
        for (Field field : fields) {
            if (field.matchKey() != null) {
                types.putIfAbsent(field.matchKey(), field.type().replaceFirst("_.*", ""));
            }
        }
        return types;
    }

    /** Checks that a resolution technique only names match keys the schemas define. */
    static void validate(JsonNode techniques, Set<String> matchKeys) {
        String type = techniques.path("resolutionType").asText();
        switch (type) {
            case "RULE_MATCHING" -> {
                JsonNode rules = techniques.path("ruleBasedProperties").path("rules");
                JsonNode conditions = techniques.path("ruleConditionProperties").path("rules");
                if (rules.isMissingNode() == conditions.isMissingNode()) {
                    throw invalid("RULE_MATCHING takes one of ruleBasedProperties and ruleConditionProperties.");
                }
                Set<String> names = new LinkedHashSet<>();
                for (JsonNode rule : rules.isMissingNode() ? conditions : rules) {
                    if (!names.add(rule.path("ruleName").asText())) {
                        throw invalid("Rule names must be unique: " + rule.path("ruleName").asText());
                    }
                }
                for (JsonNode rule : rules) {
                    for (JsonNode key : rule.path("matchingKeys")) {
                        if (!matchKeys.contains(key.asText())) {
                            throw invalid("Rule " + rule.path("ruleName").asText() + " uses the match key "
                                    + key.asText() + ", which no input schema defines.");
                        }
                    }
                }
                for (JsonNode rule : conditions) {
                    Condition.parse(rule.path("condition").asText()).keys().forEach(key -> {
                        if (!matchKeys.contains(key)) {
                            throw invalid("Rule " + rule.path("ruleName").asText() + " uses the match key " + key
                                    + ", which no input schema defines.");
                        }
                    });
                }
            }
            case "ML_MATCHING" -> {
                if (techniques.has("ruleBasedProperties") || techniques.has("ruleConditionProperties")) {
                    throw invalid("ML_MATCHING takes no rules.");
                }
            }
            case "PROVIDER" -> {
                if (!techniques.path("providerProperties").hasNonNull("providerServiceArn")) {
                    throw invalid("PROVIDER requires providerProperties.providerServiceArn.");
                }
            }
            default -> throw invalid("Unknown resolutionType " + type + ".");
        }
    }

    /** Groups records by a workflow's resolution technique. */
    static List<Group> match(List<Rec> records, List<Field> fields, JsonNode techniques, boolean normalize) {
        List<Map<String, String>> keys = new ArrayList<>();
        for (Rec record : records) {
            keys.add(keys(record, fields, normalize));
        }
        Map<String, String> keyTypes = keyTypes(fields);
        UnionFind groups = new UnionFind(records.size());
        String type = techniques.path("resolutionType").asText();
        if ("ML_MATCHING".equals(type)) {
            for (int left = 0; left < records.size(); left++) {
                for (int right = left + 1; right < records.size(); right++) {
                    Double score = similarity(keys.get(left), keys.get(right));
                    if (score != null && score >= ML_THRESHOLD) {
                        groups.union(left, right, "ML", score);
                    }
                }
            }
        } else if (techniques.has("ruleConditionProperties")) {
            for (JsonNode rule : techniques.path("ruleConditionProperties").path("rules")) {
                Condition condition = Condition.parse(rule.path("condition").asText());
                for (int left = 0; left < records.size(); left++) {
                    for (int right = left + 1; right < records.size(); right++) {
                        if (condition.test(keys.get(left), keys.get(right), keyTypes)) {
                            groups.union(left, right, rule.path("ruleName").asText(), null);
                        }
                    }
                }
            }
        } else {
            boolean manyToMany = "MANY_TO_MANY".equals(techniques.path("ruleBasedProperties")
                    .path("attributeMatchingModel").asText());
            for (JsonNode rule : techniques.path("ruleBasedProperties").path("rules")) {
                List<String> matching = new ArrayList<>();
                rule.path("matchingKeys").forEach(key -> matching.add(key.asText()));
                for (int left = 0; left < records.size(); left++) {
                    for (int right = left + 1; right < records.size(); right++) {
                        if (ruleMatches(matching, keys.get(left), keys.get(right), keyTypes, manyToMany)) {
                            groups.union(left, right, rule.path("ruleName").asText(), null);
                        }
                    }
                }
            }
        }
        Map<Integer, List<Rec>> members = new LinkedHashMap<>();
        for (int index = 0; index < records.size(); index++) {
            members.computeIfAbsent(groups.find(index), root -> new ArrayList<>()).add(records.get(index));
        }
        List<Group> result = new ArrayList<>();
        members.forEach((root, group) -> result.add(new Group(group, group.size() > 1 ? groups.rule(root) : null,
                group.size() > 1 ? groups.confidence(root) : null)));
        return result;
    }

    /** Whether two records agree on every key a rule names. */
    static boolean ruleMatches(List<String> matching, Map<String, String> left, Map<String, String> right,
                               Map<String, String> keyTypes, boolean manyToMany) {
        if (matching.isEmpty()) {
            return false;
        }
        for (String key : matching) {
            if (manyToMany) {
                if (!sharesValue(key, left, right, keyTypes)) {
                    return false;
                }
            } else {
                String value = left.get(key);
                if (value == null || !value.equals(right.get(key))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether a key's value of one record equals any value of the other's keys of the same type. */
    private static boolean sharesValue(String key, Map<String, String> left, Map<String, String> right,
                                       Map<String, String> keyTypes) {
        String type = keyTypes.get(key);
        Set<String> leftValues = new LinkedHashSet<>();
        Set<String> rightValues = new LinkedHashSet<>();
        keyTypes.forEach((other, otherType) -> {
            if (otherType.equals(type)) {
                if (left.get(other) != null) {
                    leftValues.add(left.get(other));
                }
                if (right.get(other) != null) {
                    rightValues.add(right.get(other));
                }
            }
        });
        leftValues.retainAll(rightValues);
        return !leftValues.isEmpty();
    }

    /** The average Jaro-Winkler similarity of the keys both records have, or null when they share none. */
    static Double similarity(Map<String, String> left, Map<String, String> right) {
        double total = 0;
        int shared = 0;
        for (Map.Entry<String, String> entry : left.entrySet()) {
            String other = right.get(entry.getKey());
            if (other != null) {
                total += jaroWinkler(entry.getValue(), other);
                shared++;
            }
        }
        return shared == 0 ? null : total / shared;
    }

    static double jaroWinkler(String a, String b) {
        if (a.equals(b)) {
            return 1;
        }
        int window = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] aMatched = new boolean[a.length()];
        boolean[] bMatched = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            for (int j = Math.max(0, i - window); j < Math.min(b.length(), i + window + 1); j++) {
                if (!bMatched[j] && a.charAt(i) == b.charAt(j)) {
                    aMatched[i] = true;
                    bMatched[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (aMatched[i]) {
                while (!bMatched[k]) {
                    k++;
                }
                if (a.charAt(i) != b.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
        }
        double m = matches;
        double jaro = (m / a.length() + m / b.length() + (m - transpositions / 2.0) / m) / 3;
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(a.length(), b.length())) && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** The cosine similarity of two values' character bigrams. */
    static double cosine(String a, String b) {
        Map<String, Integer> left = bigrams(a);
        Map<String, Integer> right = bigrams(b);
        double dot = 0;
        for (Map.Entry<String, Integer> entry : left.entrySet()) {
            dot += entry.getValue() * right.getOrDefault(entry.getKey(), 0);
        }
        double norm = Math.sqrt(left.values().stream().mapToDouble(v -> v * v).sum())
                * Math.sqrt(right.values().stream().mapToDouble(v -> v * v).sum());
        return norm == 0 ? (a.equals(b) ? 1 : 0) : dot / norm;
    }

    private static Map<String, Integer> bigrams(String value) {
        Map<String, Integer> grams = new HashMap<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            grams.merge(value.substring(i, i + 2), 1, Integer::sum);
        }
        return grams;
    }

    static String soundex(String value) {
        String letters = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        if (letters.isEmpty()) {
            return "";
        }
        String codes = "01230120022455012623010202";
        StringBuilder out = new StringBuilder().append(letters.charAt(0));
        char last = codes.charAt(letters.charAt(0) - 'A');
        for (int i = 1; i < letters.length() && out.length() < 4; i++) {
            char code = codes.charAt(letters.charAt(i) - 'A');
            if (code != '0' && code != last) {
                out.append(code);
            }
            if (letters.charAt(i) != 'H' && letters.charAt(i) != 'W') {
                last = code;
            }
        }
        while (out.length() < 4) {
            out.append('0');
        }
        return out.toString();
    }

    /** Disjoint sets of records, each remembering the earliest rule that linked it and its best confidence. */
    private static final class UnionFind {
        private final int[] parent;
        private final Map<Integer, String> rules = new HashMap<>();
        private final Map<Integer, Double> confidences = new HashMap<>();

        UnionFind(int size) {
            parent = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
        }

        int find(int index) {
            while (parent[index] != index) {
                parent[index] = parent[parent[index]];
                index = parent[index];
            }
            return index;
        }

        void union(int left, int right, String rule, Double confidence) {
            int a = find(left);
            int b = find(right);
            if (a == b) {
                return;
            }
            parent[b] = a;
            String kept = rules.containsKey(a) ? rules.get(a) : rules.containsKey(b) ? rules.get(b) : rule;
            rules.put(a, kept);
            if (confidence != null) {
                confidences.merge(a, confidence, Math::max);
                if (confidences.containsKey(b)) {
                    confidences.merge(a, confidences.get(b), Math::max);
                }
            }
        }

        String rule(int root) {
            return rules.get(root);
        }

        Double confidence(int root) {
            return confidences.get(root);
        }
    }

    /** A rule condition: an expression over match keys, parsed once. */
    interface Condition {

        boolean test(Map<String, String> left, Map<String, String> right, Map<String, String> keyTypes);

        Set<String> keys();

        static Condition parse(String text) {
            Parser parser = new Parser(text);
            Condition condition = parser.or();
            if (parser.position < parser.tokens.size()) {
                throw invalid("Unexpected '" + parser.tokens.get(parser.position) + "' in condition: " + text);
            }
            return condition;
        }
    }

    /** A recursive-descent parser for rule conditions. */
    private static final class Parser {
        private static final Pattern TOKEN = Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_.-]*|\\d+(?:\\.\\d+)?|[(),=])");
        private final String text;
        private final List<String> tokens = new ArrayList<>();
        private int position;

        Parser(String text) {
            this.text = text;
            Matcher matcher = TOKEN.matcher(text);
            int end = 0;
            while (matcher.find() && matcher.start() == end) {
                tokens.add(matcher.group(1));
                end = matcher.end();
            }
            if (!text.substring(end).isBlank()) {
                throw invalid("Unexpected text in condition: " + text);
            }
            if (tokens.isEmpty()) {
                throw invalid("A rule condition cannot be empty.");
            }
        }

        Condition or() {
            Condition left = and();
            while (peek("OR")) {
                position++;
                Condition first = left;
                Condition second = and();
                left = combine(first, second, false);
            }
            return left;
        }

        Condition and() {
            Condition left = term();
            while (peek("AND")) {
                position++;
                Condition first = left;
                Condition second = term();
                left = combine(first, second, true);
            }
            return left;
        }

        private static Condition combine(Condition first, Condition second, boolean all) {
            Set<String> keys = new LinkedHashSet<>(first.keys());
            keys.addAll(second.keys());
            return new Condition() {
                @Override
                public boolean test(Map<String, String> left, Map<String, String> right, Map<String, String> types) {
                    return all ? first.test(left, right, types) && second.test(left, right, types)
                            : first.test(left, right, types) || second.test(left, right, types);
                }

                @Override
                public Set<String> keys() {
                    return keys;
                }
            };
        }

        Condition term() {
            String token = next();
            if ("(".equals(token)) {
                Condition inner = or();
                expect(")");
                return inner;
            }
            if (peek("(")) {
                position++;
                String function = token.toUpperCase(Locale.ROOT);
                String key = next();
                String argument = null;
                if (peek(",")) {
                    position++;
                    argument = next();
                }
                expect(")");
                return function(function, key, argument);
            }
            if (peek("=")) {
                position++;
                String other = next();
                return exact(token, other);
            }
            throw invalid("Expected a function or a comparison at '" + token + "' in condition: " + text);
        }

        private Condition function(String function, String key, String argument) {
            return switch (function) {
                case "EXACT" -> exact(key, key);
                case "EXACTMANYTOMANY" -> leaf(key, (left, right, types) -> sharesValue(key, left, right, types));
                case "LEVENSHTEIN" -> {
                    int distance = number(argument, function).intValue();
                    yield leaf(key, (left, right, types) -> both(left, right, key)
                            && levenshtein(left.get(key), right.get(key)) <= distance);
                }
                case "COSINE" -> {
                    double threshold = number(argument, function);
                    yield leaf(key, (left, right, types) -> both(left, right, key)
                            && cosine(left.get(key), right.get(key)) >= threshold);
                }
                case "SOUNDEX" -> leaf(key, (left, right, types) -> both(left, right, key)
                        && soundex(left.get(key)).equals(soundex(right.get(key))));
                default -> throw invalid("Unknown function " + function + " in condition: " + text);
            };
        }

        private Double number(String argument, String function) {
            try {
                return Double.parseDouble(argument);
            } catch (NumberFormatException | NullPointerException e) {
                throw invalid(function + " takes a match key and a number, in condition: " + text);
            }
        }

        private static boolean both(Map<String, String> left, Map<String, String> right, String key) {
            return left.get(key) != null && right.get(key) != null;
        }

        private static Condition exact(String key, String other) {
            Set<String> keys = new LinkedHashSet<>(List.of(key, other));
            return new Condition() {
                @Override
                public boolean test(Map<String, String> left, Map<String, String> right, Map<String, String> types) {
                    return left.get(key) != null && left.get(key).equals(right.get(other));
                }

                @Override
                public Set<String> keys() {
                    return keys;
                }
            };
        }

        private interface Test {
            boolean test(Map<String, String> left, Map<String, String> right, Map<String, String> types);
        }

        private static Condition leaf(String key, Test test) {
            return new Condition() {
                @Override
                public boolean test(Map<String, String> left, Map<String, String> right, Map<String, String> types) {
                    return test.test(left, right, types);
                }

                @Override
                public Set<String> keys() {
                    return Set.of(key);
                }
            };
        }

        private boolean peek(String token) {
            return position < tokens.size() && tokens.get(position).equalsIgnoreCase(token);
        }

        private String next() {
            if (position >= tokens.size()) {
                throw invalid("Unexpected end of condition: " + text);
            }
            return tokens.get(position++);
        }

        private void expect(String token) {
            if (!token.equals(next())) {
                throw invalid("Expected '" + token + "' in condition: " + text);
            }
        }
    }
}
