package io.floci.aws.services.sdb;

import io.floci.aws.core.common.AwsException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The SimpleDB select language: {@code select output from domain [where expression] [order by attribute [asc|desc]]
 * [limit n]}. Output is {@code *}, {@code itemName()}, {@code count(*)} or a list of attributes. Comparisons are
 * lexicographic, as SimpleDB stores strings; a predicate on a multi-valued attribute holds when any value satisfies
 * it, and {@code every(attribute)} only when all do. Predicates combine with {@code and}, {@code or}, {@code not},
 * {@code intersection} and parentheses, and test {@code =}, {@code !=}, {@code <}, {@code <=}, {@code >},
 * {@code >=}, {@code like}, {@code not like}, {@code between}, {@code in}, {@code is null} and {@code is not null}.
 * A sort attribute must appear in a predicate.
 */
final class SimpleDbSelect {

    /** An item: its name and its attributes, each with its set of values. */
    record Item(String name, Map<String, Set<String>> attributes) {
    }

    /** A parsed select expression. */
    record Query(List<String> output, boolean all, boolean itemNameOnly, boolean count, String domain,
                 Predicate<Item> where, String sortAttribute, boolean descending, int limit) {
    }

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 2500;

    private final List<String> tokens;
    private int at;
    private final Set<String> predicateAttributes = new LinkedHashSet<>();

    private SimpleDbSelect(List<String> tokens) {
        this.tokens = tokens;
    }

    static Query parse(String expression) {
        SimpleDbSelect parser = new SimpleDbSelect(tokenize(expression));
        return parser.query();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidQueryExpression", "The specified query expression syntax is not valid. "
                + message, 400);
    }

    // ---------- tokenizer ----------

    private static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'' || c == '"' || c == '`') {
                StringBuilder value = new StringBuilder().append(c);
                i++;
                while (true) {
                    if (i >= text.length()) {
                        throw invalid("Unterminated quoted value.");
                    }
                    char d = text.charAt(i);
                    if (d == c) {
                        if (i + 1 < text.length() && text.charAt(i + 1) == c) {
                            value.append(c);
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    value.append(d);
                    i++;
                }
                out.add(value.toString());
            } else if ("(),*".indexOf(c) >= 0) {
                out.add(String.valueOf(c));
                i++;
            } else if ("=<>!".indexOf(c) >= 0) {
                if (i + 1 < text.length() && text.charAt(i + 1) == '=') {
                    out.add(text.substring(i, i + 2));
                    i += 2;
                } else if (c == '<' && i + 1 < text.length() && text.charAt(i + 1) == '>') {
                    out.add("!=");
                    i += 2;
                } else {
                    out.add(String.valueOf(c));
                    i++;
                }
            } else {
                int start = i;
                while (i < text.length() && !Character.isWhitespace(text.charAt(i))
                        && "(),*=<>!'\"`".indexOf(text.charAt(i)) < 0) {
                    i++;
                }
                out.add(text.substring(start, i));
            }
        }
        return out;
    }

    private String peek() {
        return at < tokens.size() ? tokens.get(at) : null;
    }

    private boolean peekKeyword(String keyword) {
        String token = peek();
        return token != null && token.equalsIgnoreCase(keyword);
    }

    private String next() {
        if (at >= tokens.size()) {
            throw invalid("The expression ends too early.");
        }
        return tokens.get(at++);
    }

    private void expect(String keyword) {
        String token = next();
        if (!token.equalsIgnoreCase(keyword)) {
            throw invalid("Expected " + keyword + " but found " + token + ".");
        }
    }

    // ---------- grammar ----------

    private Query query() {
        expect("select");
        List<String> output = new ArrayList<>();
        boolean all = false;
        boolean itemNameOnly = false;
        boolean count = false;
        if ("*".equals(peek())) {
            next();
            all = true;
        } else if (peekKeyword("count")) {
            next();
            expect("(");
            expect("*");
            expect(")");
            count = true;
        } else {
            do {
                String name = name(next());
                if ("itemName".equalsIgnoreCase(name) && "(".equals(peek())) {
                    next();
                    expect(")");
                    itemNameOnly = true;
                } else {
                    output.add(name);
                }
            } while (",".equals(peek()) && next() != null);
            if (itemNameOnly && !output.isEmpty()) {
                throw invalid("itemName() can't be combined with attributes.");
            }
        }
        expect("from");
        String domain = name(next());
        Predicate<Item> where = item -> true;
        if (peekKeyword("where")) {
            next();
            where = orExpression();
        }
        String sort = null;
        boolean descending = false;
        if (peekKeyword("order")) {
            next();
            expect("by");
            sort = name(next());
            if ("itemName".equalsIgnoreCase(sort) && "(".equals(peek())) {
                next();
                expect(")");
                sort = "itemName()";
            }
            if (peekKeyword("asc") || peekKeyword("desc")) {
                descending = "desc".equalsIgnoreCase(next());
            }
            if (!"itemName()".equals(sort) && !predicateAttributes.contains(sort)) {
                throw new AwsException("InvalidSortExpression", "The sort attribute must be present in at least one "
                        + "of the predicates of the expression.", 400);
            }
        }
        int limit = DEFAULT_LIMIT;
        if (peekKeyword("limit")) {
            next();
            try {
                limit = Integer.parseInt(next());
            } catch (NumberFormatException e) {
                throw invalid("The limit must be a number.");
            }
            if (limit < 1 || limit > MAX_LIMIT) {
                throw invalid("The limit must be between 1 and " + MAX_LIMIT + ".");
            }
        }
        if (peek() != null) {
            throw invalid("Unexpected " + peek() + ".");
        }
        return new Query(output, all, itemNameOnly, count, domain, where, sort, descending, limit);
    }

    /** An identifier, without its backquotes when quoted. */
    private static String name(String token) {
        if (token.startsWith("`")) {
            return token.substring(1);
        }
        if (token.startsWith("'") || token.startsWith("\"")) {
            throw invalid("Expected a name but found the value " + token.substring(1) + ".");
        }
        return token;
    }

    private Predicate<Item> orExpression() {
        Predicate<Item> left = andExpression();
        while (peekKeyword("or")) {
            next();
            Predicate<Item> right = andExpression();
            Predicate<Item> current = left;
            left = item -> current.test(item) || right.test(item);
        }
        return left;
    }

    private Predicate<Item> andExpression() {
        Predicate<Item> left = notExpression();
        while (peekKeyword("and") || peekKeyword("intersection")) {
            next();
            Predicate<Item> right = notExpression();
            Predicate<Item> current = left;
            left = item -> current.test(item) && right.test(item);
        }
        return left;
    }

    private Predicate<Item> notExpression() {
        if (peekKeyword("not")) {
            next();
            Predicate<Item> inner = notExpression();
            return item -> !inner.test(item);
        }
        if ("(".equals(peek())) {
            next();
            Predicate<Item> inner = orExpression();
            expect(")");
            return inner;
        }
        return comparison();
    }

    private Predicate<Item> comparison() {
        boolean every = false;
        String attribute;
        String token = next();
        if ("every".equalsIgnoreCase(token) && "(".equals(peek())) {
            next();
            attribute = name(next());
            expect(")");
            every = true;
        } else {
            attribute = name(token);
            if ("itemName".equalsIgnoreCase(attribute) && "(".equals(peek())) {
                next();
                expect(")");
                attribute = "itemName()";
            }
        }
        predicateAttributes.add(attribute);
        String operator = next().toLowerCase(Locale.ROOT);
        Predicate<String> test;
        switch (operator) {
            case "=", "!=", "<", "<=", ">", ">=" -> {
                String value = value(next());
                test = switch (operator) {
                    case "=" -> v -> v.equals(value);
                    case "!=" -> v -> !v.equals(value);
                    case "<" -> v -> v.compareTo(value) < 0;
                    case "<=" -> v -> v.compareTo(value) <= 0;
                    case ">" -> v -> v.compareTo(value) > 0;
                    default -> v -> v.compareTo(value) >= 0;
                };
            }
            case "like" -> test = like(value(next()));
            case "not" -> {
                expect("like");
                Predicate<String> like = like(value(next()));
                test = v -> !like.test(v);
            }
            case "between" -> {
                String low = value(next());
                expect("and");
                String high = value(next());
                test = v -> v.compareTo(low) >= 0 && v.compareTo(high) <= 0;
            }
            case "in" -> {
                expect("(");
                Set<String> values = new LinkedHashSet<>();
                do {
                    values.add(value(next()));
                } while (",".equals(peek()) && next() != null);
                expect(")");
                test = values::contains;
            }
            case "is" -> {
                boolean negate = peekKeyword("not");
                if (negate) {
                    next();
                }
                expect("null");
                String name = attribute;
                return item -> negate == !values(item, name).isEmpty();
            }
            default -> throw invalid("Unknown operator " + operator + ".");
        }
        String name = attribute;
        boolean all = every;
        return item -> {
            Set<String> values = values(item, name);
            if (values.isEmpty()) {
                return false;
            }
            return all ? values.stream().allMatch(test) : values.stream().anyMatch(test);
        };
    }

    private static Set<String> values(Item item, String attribute) {
        if ("itemName()".equals(attribute)) {
            return Set.of(item.name());
        }
        return item.attributes().getOrDefault(attribute, Set.of());
    }

    private static String value(String token) {
        if (token.startsWith("'") || token.startsWith("\"")) {
            return token.substring(1);
        }
        throw invalid("Expected a quoted value but found " + token + ".");
    }

    /** {@code like}: {@code %} matches any run of characters, at the start, the end or both. */
    private static Predicate<String> like(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            regex.append(c == '%' ? ".*" : Pattern.quote(String.valueOf(c)));
        }
        Pattern compiled = Pattern.compile(regex.toString(), Pattern.DOTALL);
        return v -> compiled.matcher(v).matches();
    }
}
