package io.floci.aws.services.personalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Personalize filter expressions: {@code INCLUDE|EXCLUDE ItemID|ActionID WHERE <conditions> [IF <user condition>]},
 * clauses joined by {@code |}. A condition names a dataset's field ({@code Items.GENRE}, {@code Interactions.EVENT_TYPE},
 * {@code Users.AGE}, {@code CurrentItem.GENRE}) and compares it with {@code IN}, {@code NOT IN}, {@code =},
 * {@code <}, {@code >}, {@code <=} or {@code >=} to literals or {@code $PARAMETER}s; conditions join with {@code AND}
 * or {@code OR}. An interaction condition matches the items the user interacted with; a clause whose parameters have no
 * value is skipped, as Personalize skips it.
 */
final class PersonalizeFilters {

    private static final Set<String> DATASETS = Set.of("ITEMS", "INTERACTIONS", "USERS", "CURRENTITEM", "ACTIONS",
            "ACTION_INTERACTIONS", "ACTIONINTERACTIONS");

    /** One comparison: {@code dataset.field op values}. */
    record Condition(String dataset, String field, String op, List<String> values) {
    }

    /** One {@code INCLUDE|EXCLUDE} clause. */
    record Clause(boolean include, String target, List<Condition> conditions, List<String> joins,
                  Condition userCondition) {
    }

    /** What a filter is evaluated against: the catalog, the user and their interactions. */
    record Context(Map<String, ObjectNode> items, ObjectNode user, List<ObjectNode> interactions, ObjectNode current,
                   Map<String, List<String>> values) {
    }

    private PersonalizeFilters() {
    }

    // ================================================================ parsing

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"' || c == '\'') {
                int end = text.indexOf(c, i + 1);
                if (end < 0) {
                    throw PersonalizeStore.invalid("The filter expression has an unterminated string.");
                }
                tokens.add("\"" + text.substring(i + 1, end));
                i = end + 1;
            } else if ("(),|".indexOf(c) >= 0) {
                tokens.add(String.valueOf(c));
                i++;
            } else if ("<>=!".indexOf(c) >= 0) {
                int end = i + 1;
                if (end < text.length() && text.charAt(end) == '=') {
                    end++;
                }
                tokens.add(text.substring(i, end));
                i = end;
            } else {
                int end = i;
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))
                        && "(),|<>=!\"'".indexOf(text.charAt(end)) < 0) {
                    end++;
                }
                tokens.add(text.substring(i, end));
                i = end;
            }
        }
        return tokens;
    }

    /** Parses and checks a filter expression. */
    static List<Clause> parse(String expression) {
        List<String> tokens = tokens(expression);
        List<Clause> clauses = new ArrayList<>();
        int[] at = {0};
        while (at[0] < tokens.size()) {
            clauses.add(clause(tokens, at));
            if (at[0] < tokens.size()) {
                expect(tokens, at, "|");
            }
        }
        if (clauses.isEmpty()) {
            throw PersonalizeStore.invalid("The filter expression is empty.");
        }
        return clauses;
    }

    private static String next(List<String> tokens, int[] at) {
        if (at[0] >= tokens.size()) {
            throw PersonalizeStore.invalid("The filter expression ends early.");
        }
        return tokens.get(at[0]++);
    }

    private static void expect(List<String> tokens, int[] at, String word) {
        String token = next(tokens, at);
        if (!token.equalsIgnoreCase(word)) {
            throw PersonalizeStore.invalid("The filter expression has '" + token + "' where '" + word
                    + "' belongs.");
        }
    }

    private static boolean peek(List<String> tokens, int[] at, String word) {
        return at[0] < tokens.size() && tokens.get(at[0]).equalsIgnoreCase(word);
    }

    private static Clause clause(List<String> tokens, int[] at) {
        String action = next(tokens, at).toUpperCase(Locale.ROOT);
        if (!action.equals("INCLUDE") && !action.equals("EXCLUDE")) {
            throw PersonalizeStore.invalid("A filter clause starts with INCLUDE or EXCLUDE, not " + action + ".");
        }
        String target = next(tokens, at);
        if (!target.equalsIgnoreCase("ItemID") && !target.equalsIgnoreCase("ActionID")) {
            throw PersonalizeStore.invalid("A filter clause filters ItemID or ActionID, not " + target + ".");
        }
        expect(tokens, at, "WHERE");
        List<Condition> conditions = new ArrayList<>();
        List<String> joins = new ArrayList<>();
        conditions.add(condition(tokens, at));
        while (peek(tokens, at, "AND") || peek(tokens, at, "OR")) {
            joins.add(next(tokens, at).toUpperCase(Locale.ROOT));
            conditions.add(condition(tokens, at));
        }
        Condition user = null;
        if (peek(tokens, at, "IF")) {
            next(tokens, at);
            user = condition(tokens, at);
            if (!user.dataset().equals("USERS")) {
                throw PersonalizeStore.invalid("An IF condition tests a Users field.");
            }
        }
        String dataset = null;
        for (Condition condition : conditions) {
            if (condition.dataset().equals("USERS") || condition.dataset().equals("CURRENTITEM")) {
                continue;
            }
            if (dataset != null && !dataset.equals(condition.dataset())) {
                throw PersonalizeStore.invalid("A filter clause's conditions must test one dataset.");
            }
            dataset = condition.dataset();
        }
        return new Clause(action.equals("INCLUDE"), target.toUpperCase(Locale.ROOT), conditions, joins, user);
    }

    private static Condition condition(List<String> tokens, int[] at) {
        String reference = next(tokens, at);
        int dot = reference.indexOf('.');
        if (dot <= 0) {
            throw PersonalizeStore.invalid("The filter expression names " + reference
                    + " where a Dataset.FIELD belongs.");
        }
        String dataset = reference.substring(0, dot).toUpperCase(Locale.ROOT);
        if (!DATASETS.contains(dataset)) {
            throw PersonalizeStore.invalid("The filter expression names an unknown dataset " + dataset + ".");
        }
        if (dataset.equals("ACTIONINTERACTIONS")) {
            dataset = "ACTION_INTERACTIONS";
        }
        String field = reference.substring(dot + 1).toUpperCase(Locale.ROOT);
        String op = next(tokens, at).toUpperCase(Locale.ROOT);
        if (op.equals("NOT")) {
            expect(tokens, at, "IN");
            op = "NOT IN";
        }
        if (!Set.of("IN", "NOT IN", "=", "<", ">", "<=", ">=").contains(op)) {
            throw PersonalizeStore.invalid("The filter expression has an unknown operator " + op + ".");
        }
        List<String> values = new ArrayList<>();
        if (op.endsWith("IN")) {
            expect(tokens, at, "(");
            values.add(value(next(tokens, at)));
            while (peek(tokens, at, ",")) {
                next(tokens, at);
                values.add(value(next(tokens, at)));
            }
            expect(tokens, at, ")");
        } else {
            String operand = next(tokens, at);
            if (operand.contains(".") && !operand.startsWith("\"") && !operand.matches("-?[0-9.]+")) {
                values.add("@" + operand.toUpperCase(Locale.ROOT));
            } else {
                values.add(value(operand));
            }
        }
        return new Condition(dataset, field, op, values);
    }

    private static String value(String token) {
        if (token.startsWith("\"")) {
            return "=" + token.substring(1);
        }
        if (token.startsWith("$")) {
            return token.toUpperCase(Locale.ROOT);
        }
        if (token.matches("-?[0-9]+(\\.[0-9]+)?")) {
            return "=" + token;
        }
        throw PersonalizeStore.invalid("The filter expression has an unknown value " + token + ".");
    }

    /** The {@code $PARAMETER}s an expression uses. */
    static Set<String> parameters(List<Clause> clauses) {
        Set<String> names = new HashSet<>();
        for (Clause clause : clauses) {
            List<Condition> all = new ArrayList<>(clause.conditions());
            if (clause.userCondition() != null) {
                all.add(clause.userCondition());
            }
            all.forEach(condition -> condition.values().stream().filter(v -> v.startsWith("$"))
                    .forEach(v -> names.add(v.substring(1))));
        }
        return names;
    }

    // ================================================================ evaluation

    /** Parses {@code filterValues}: each a comma-separated list of quoted strings. */
    static Map<String, List<String>> values(JsonNode filterValues) {
        Map<String, List<String>> values = new HashMap<>();
        if (filterValues == null || !filterValues.isObject()) {
            return values;
        }
        filterValues.properties().forEach(entry -> {
            List<String> list = new ArrayList<>();
            for (String token : tokens(entry.getValue().asText())) {
                if (!token.equals(",")) {
                    list.add(token.startsWith("\"") ? token.substring(1) : token);
                }
            }
            if (list.size() > 100) {
                throw PersonalizeStore.invalid("A filter value can list at most 100 values.");
            }
            values.put(entry.getKey().toUpperCase(Locale.ROOT), list);
        });
        return values;
    }

    /** The values a condition compares with, or null when a parameter has none. */
    private static List<String> resolve(Condition condition, Context context) {
        List<String> resolved = new ArrayList<>();
        for (String value : condition.values()) {
            if (value.startsWith("$")) {
                List<String> given = context.values().get(value.substring(1));
                if (given == null) {
                    return null;
                }
                resolved.addAll(given);
            } else if (value.startsWith("@")) {
                String reference = value.substring(1);
                int dot = reference.indexOf('.');
                ObjectNode source = reference.startsWith("CURRENTITEM") ? context.current()
                        : reference.startsWith("USERS") ? context.user() : null;
                if (source == null) {
                    return List.of();
                }
                resolved.addAll(cell(source, reference.substring(dot + 1)));
            } else {
                resolved.add(value.substring(1));
            }
        }
        return resolved;
    }

    private static List<String> cell(ObjectNode row, String field) {
        if (row == null || !row.hasNonNull(field)) {
            return List.of();
        }
        return List.of(row.get(field).asText().split("\\|"));
    }

    private static boolean compare(List<String> actual, String op, List<String> values) {
        return switch (op) {
            case "IN", "=" -> actual.stream().anyMatch(values::contains);
            case "NOT IN" -> actual.stream().noneMatch(values::contains);
            default -> {
                if (actual.isEmpty() || values.isEmpty()) {
                    yield false;
                }
                try {
                    double left = Double.parseDouble(actual.get(0));
                    double right = Double.parseDouble(values.get(0));
                    yield switch (op) {
                        case "<" -> left < right;
                        case ">" -> left > right;
                        case "<=" -> left <= right;
                        default -> left >= right;
                    };
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
        };
    }

    private static boolean test(Condition condition, String candidate, Context context, List<String> values) {
        return switch (condition.dataset()) {
            case "ITEMS", "ACTIONS" -> compare(cell(context.items().get(candidate), condition.field()),
                    condition.op(), values);
            case "CURRENTITEM" -> compare(cell(context.current(), condition.field()), condition.op(), values);
            case "USERS" -> compare(cell(context.user(), condition.field()), condition.op(), values);
            default -> {
                String id = "ACTION_INTERACTIONS".equals(condition.dataset()) ? "ACTION_ID" : "ITEM_ID";
                yield context.interactions().stream().anyMatch(row -> row.path(id).asText().equals(candidate)
                        && compare(cell(row, condition.field()), condition.op(), values));
            }
        };
    }

    /** Whether a candidate survives every clause. */
    static Predicate<String> predicate(List<Clause> clauses, Context context) {
        return candidate -> {
            for (Clause clause : clauses) {
                if (clause.userCondition() != null) {
                    List<String> values = resolve(clause.userCondition(), context);
                    if (values == null || !test(clause.userCondition(), candidate, context, values)) {
                        continue;
                    }
                }
                Boolean matched = null;
                boolean skipped = false;
                for (int i = 0; i < clause.conditions().size(); i++) {
                    Condition condition = clause.conditions().get(i);
                    List<String> values = resolve(condition, context);
                    if (values == null) {
                        skipped = true;
                        break;
                    }
                    boolean result = test(condition, candidate, context, values);
                    matched = matched == null ? result
                            : "AND".equals(clause.joins().get(i - 1)) ? matched && result : matched || result;
                }
                if (skipped || matched == null) {
                    continue;
                }
                if (clause.include() != matched) {
                    return false;
                }
            }
            return true;
        };
    }
}
