package io.floci.aws.services.keyspaces;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A CQL data type as Keyspaces accepts it in a schema: a native type, a collection ({@code list}, {@code set},
 * {@code map}), a {@code tuple}, a user-defined type of the keyspace, or any of those {@code frozen}.
 */
record CqlType(String name, List<CqlType> args, boolean frozen) {

    static final Set<String> NATIVE = Set.of("ascii", "bigint", "blob", "boolean", "counter", "date", "decimal",
            "double", "duration", "float", "inet", "int", "smallint", "text", "time", "timestamp", "timeuuid",
            "tinyint", "uuid", "varchar", "varint");
    static final Set<String> COLLECTIONS = Set.of("list", "set", "map");

    boolean isNative() {
        return NATIVE.contains(name);
    }

    boolean isCollection() {
        return COLLECTIONS.contains(name);
    }

    boolean isTuple() {
        return "tuple".equals(name);
    }

    boolean isUdt() {
        return !isNative() && !isCollection() && !isTuple();
    }

    /** Whether the type, or a type inside it, is a non-frozen collection or UDT. */
    boolean multiCell() {
        return !frozen && (isCollection() || isUdt());
    }

    /** The user-defined types the type names, at any depth. */
    void udts(List<String> out) {
        if (isUdt()) {
            out.add(name);
        }
        args.forEach(a -> a.udts(out));
    }

    @Override
    public String toString() {
        String inner = args.isEmpty() ? name : name + "<" + String.join(", ", args.stream().map(CqlType::toString)
                .toList()) + ">";
        return frozen ? "frozen<" + inner + ">" : inner;
    }

    /**
     * Parses a type, checking user-defined types exist. Nested collections and UDTs must be frozen, as Cassandra
     * requires.
     */
    static CqlType parse(String text, Predicate<String> udtExists) {
        int[] at = {0};
        String source = text.strip();
        CqlType type = parse(source, at, udtExists, false);
        skip(source, at);
        if (at[0] != source.length()) {
            throw KeyspacesStore.validation("The type " + text + " is not a valid CQL type.");
        }
        return type;
    }

    private static void skip(String s, int[] at) {
        while (at[0] < s.length() && Character.isWhitespace(s.charAt(at[0]))) {
            at[0]++;
        }
    }

    private static CqlType parse(String s, int[] at, Predicate<String> udtExists, boolean nested) {
        skip(s, at);
        int start = at[0];
        while (at[0] < s.length() && (Character.isLetterOrDigit(s.charAt(at[0])) || s.charAt(at[0]) == '_'
                || s.charAt(at[0]) == '"')) {
            at[0]++;
        }
        String name = s.substring(start, at[0]).replace("\"", "");
        if (name.isEmpty()) {
            throw KeyspacesStore.validation("The type " + s + " is not a valid CQL type.");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        skip(s, at);
        List<CqlType> args = new ArrayList<>();
        boolean hasArgs = at[0] < s.length() && s.charAt(at[0]) == '<';
        if (hasArgs) {
            at[0]++;
            boolean inside = "frozen".equals(lower);
            while (true) {
                args.add(parse(s, at, udtExists, !inside));
                skip(s, at);
                if (at[0] < s.length() && s.charAt(at[0]) == ',') {
                    at[0]++;
                    continue;
                }
                if (at[0] < s.length() && s.charAt(at[0]) == '>') {
                    at[0]++;
                    break;
                }
                throw KeyspacesStore.validation("The type " + s + " is not a valid CQL type.");
            }
        }
        if ("frozen".equals(lower)) {
            if (args.size() != 1) {
                throw KeyspacesStore.validation("frozen<> takes one type.");
            }
            CqlType inner = args.get(0);
            if (inner.isNative()) {
                throw KeyspacesStore.validation("frozen<> is only for collections, tuples and user-defined types.");
            }
            return new CqlType(inner.name, inner.args, true);
        }
        if ("varchar".equals(lower)) {
            lower = "text";
        }
        CqlType type;
        if (NATIVE.contains(lower)) {
            if (!args.isEmpty()) {
                throw KeyspacesStore.validation("The type " + name + " takes no parameters.");
            }
            type = new CqlType(lower, List.of(), false);
        } else if (COLLECTIONS.contains(lower) || "tuple".equals(lower)) {
            int wanted = "map".equals(lower) ? 2 : 1;
            if ("tuple".equals(lower) ? args.isEmpty() : args.size() != wanted) {
                throw KeyspacesStore.validation("The type " + lower + " takes " + ("tuple".equals(lower)
                        ? "at least one type." : wanted + " type" + (wanted > 1 ? "s." : ".")));
            }
            for (CqlType arg : args) {
                if ("counter".equals(arg.name)) {
                    throw KeyspacesStore.validation("Counters can't be inside collections or tuples.");
                }
            }
            type = new CqlType(lower, List.copyOf(args), "tuple".equals(lower));
        } else {
            if (!args.isEmpty()) {
                throw KeyspacesStore.validation("The type " + name + " takes no parameters.");
            }
            String udt = name.equals(lower) ? lower : name;
            if (!udtExists.test(udt)) {
                throw KeyspacesStore.validation("Unknown type " + name + ".");
            }
            type = new CqlType(udt, List.of(), false);
        }
        if (nested && type.multiCell()) {
            throw KeyspacesStore.validation("Non-frozen collections and user-defined types can't be nested; use "
                    + "frozen<" + type + ">.");
        }
        return type;
    }
}
