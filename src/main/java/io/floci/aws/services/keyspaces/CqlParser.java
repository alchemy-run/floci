package io.floci.aws.services.keyspaces;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses the CQL Keyspaces accepts: {@code USE}, {@code SELECT}, {@code INSERT}, {@code UPDATE}, {@code DELETE},
 * {@code BEGIN BATCH}, and {@code CREATE}, {@code ALTER} and {@code DROP} of keyspaces, tables and types. Unquoted
 * identifiers are lowercased, as Cassandra does; bind markers are positional ({@code ?}) or named ({@code :name}).
 */
final class CqlParser {

    // ---------------------------------------------------------------- syntax tree

    /** A literal, bind marker, collection, tuple, user-defined type value, function call or null. */
    record Term(String kind, String text, List<Term> items, List<String> names, int bind) {
        static Term literal(String kind, String text) {
            return new Term(kind, text, List.of(), List.of(), -1);
        }

        boolean isBind() {
            return "bind".equals(kind);
        }
    }

    /** {@code column [op] term}; the column may be {@code token(...)} or a tuple of clustering columns. */
    record Relation(List<String> columns, boolean token, String op, Term value, List<Term> in) {
    }

    record Selector(String kind, String column, String alias, List<Selector> args) {
    }

    record Ordering(String column, boolean descending) {
    }

    record Assignment(String column, Term key, String op, Term value, boolean prepend, String field) {
    }

    record Condition(String column, Term key, String op, Term value, List<Term> in) {
    }

    sealed interface Statement permits Use, Select, Insert, Update, Delete, Batch, CreateKeyspace, AlterKeyspace,
            DropKeyspace, CreateTable, AlterTable, DropTable, CreateType, DropType, Truncate {
    }

    record Use(String keyspace) implements Statement {
    }

    record Select(String keyspace, String table, boolean json, boolean distinct, List<Selector> selectors,
                  List<Relation> where, List<Ordering> order, Term limit, Term perPartitionLimit,
                  boolean allowFiltering) implements Statement {
    }

    record Insert(String keyspace, String table, List<String> columns, List<Term> values, Term json,
                  boolean ifNotExists, Term ttl, Term timestamp) implements Statement {
    }

    record Update(String keyspace, String table, Term ttl, Term timestamp, List<Assignment> assignments,
                  List<Relation> where, boolean ifExists, List<Condition> conditions) implements Statement {
    }

    record Delete(String keyspace, String table, List<Assignment> targets, Term timestamp, List<Relation> where,
                  boolean ifExists, List<Condition> conditions) implements Statement {
    }

    record Batch(String type, List<Statement> statements, Term timestamp) implements Statement {
    }

    record CreateKeyspace(String name, boolean ifNotExists, Map<String, Object> options) implements Statement {
    }

    record AlterKeyspace(String name, Map<String, Object> options) implements Statement {
    }

    record DropKeyspace(String name, boolean ifExists) implements Statement {
    }

    /** A column definition: its name, type text and whether it's static. */
    record ColumnDef(String name, String type, boolean isStatic) {
    }

    record CreateTable(String keyspace, String name, boolean ifNotExists, List<ColumnDef> columns,
                       List<String> partitionKeys, List<String> clusteringKeys, Map<String, String> clusteringOrder,
                       Map<String, Object> options) implements Statement {
    }

    record AlterTable(String keyspace, String name, List<ColumnDef> add, Map<String, Object> options)
            implements Statement {
    }

    record DropTable(String keyspace, String name, boolean ifExists) implements Statement {
    }

    record CreateType(String keyspace, String name, boolean ifNotExists, List<ColumnDef> fields)
            implements Statement {
    }

    record DropType(String keyspace, String name, boolean ifExists) implements Statement {
    }

    record Truncate(String keyspace, String table) implements Statement {
    }

    /** A parsed statement and the names of its bind markers, in order (null for positional ones). */
    record Parsed(Statement statement, List<String> binds) {
    }

    /** A syntax error, answered as a CQL {@code SyntaxError}. */
    static final class SyntaxError extends RuntimeException {
        SyntaxError(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- tokens

    private record Tok(String kind, String text, boolean quoted) {
    }

    private final List<Tok> toks = new ArrayList<>();
    private final List<String> binds = new ArrayList<>();
    private int at;

    private CqlParser(String cql) {
        tokenize(cql);
    }

    static Parsed parse(String cql) {
        CqlParser p = new CqlParser(cql);
        Statement statement = p.statement();
        p.accept(";");
        if (p.at < p.toks.size()) {
            throw new SyntaxError("line 1: unexpected input at '" + p.toks.get(p.at).text() + "'");
        }
        return new Parsed(statement, Collections.unmodifiableList(new ArrayList<>(p.binds)));
    }

    private void tokenize(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-'
                    || c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 2;
            } else if (c == '\'') {
                StringBuilder out = new StringBuilder();
                i++;
                while (true) {
                    if (i >= s.length()) {
                        throw new SyntaxError("Unterminated string literal");
                    }
                    if (s.charAt(i) == '\'') {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                            out.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    out.append(s.charAt(i++));
                }
                toks.add(new Tok("string", out.toString(), false));
            } else if (c == '$' && s.startsWith("$$", i)) {
                int end = s.indexOf("$$", i + 2);
                if (end < 0) {
                    throw new SyntaxError("Unterminated string literal");
                }
                toks.add(new Tok("string", s.substring(i + 2, end), false));
                i = end + 2;
            } else if (c == '"') {
                StringBuilder out = new StringBuilder();
                i++;
                while (true) {
                    if (i >= s.length()) {
                        throw new SyntaxError("Unterminated quoted identifier");
                    }
                    if (s.charAt(i) == '"') {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                            out.append('"');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    out.append(s.charAt(i++));
                }
                toks.add(new Tok("ident", out.toString(), true));
            } else if (c == '0' && i + 1 < s.length() && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
                int start = i + 2;
                i = start;
                while (i < s.length() && Character.digit(s.charAt(i), 16) >= 0) {
                    i++;
                }
                toks.add(new Tok("hex", s.substring(start, i), false));
            } else if (Character.isDigit(c) || c == '-' && i + 1 < s.length() && (Character.isDigit(s.charAt(i + 1))
                    || s.charAt(i + 1) == '.') && numberMayStart()) {
                int start = i;
                i++;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.'
                        || s.charAt(i) == '-' && isUuidLike(s, start, i)
                        || (s.charAt(i) == '+' || s.charAt(i) == '-') && (s.charAt(i - 1) == 'e'
                        || s.charAt(i - 1) == 'E') && !s.substring(start, i).matches(".*[a-df-zA-DF-Z].*"))) {
                    i++;
                }
                String text = s.substring(start, i);
                String kind;
                if (text.matches("-?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                    kind = "uuid";
                } else if (text.matches("-?\\d+")) {
                    kind = "integer";
                } else if (text.matches("-?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?")) {
                    kind = "float";
                } else if (text.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                    kind = "string";
                } else {
                    kind = "duration";
                }
                toks.add(new Tok(kind, text, false));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_'
                        || s.charAt(i) == '-' && isUuidLike(s, start, i))) {
                    i++;
                }
                String text = s.substring(start, i);
                if (text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                    toks.add(new Tok("uuid", text, false));
                } else {
                    toks.add(new Tok("ident", text, false));
                }
            } else if (c == '?') {
                toks.add(new Tok("?", "?", false));
                i++;
            } else if (c == ':' && i + 1 < s.length() && (Character.isLetter(s.charAt(i + 1))
                    || s.charAt(i + 1) == '"' || s.charAt(i + 1) == '_')) {
                i++;
                int start = i;
                if (s.charAt(i) == '"') {
                    int end = s.indexOf('"', i + 1);
                    toks.add(new Tok("named", s.substring(i + 1, end), false));
                    i = end + 1;
                } else {
                    while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                        i++;
                    }
                    toks.add(new Tok("named", s.substring(start, i).toLowerCase(Locale.ROOT), false));
                }
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (two.equals("<=") || two.equals(">=") || two.equals("!=") || two.equals("+=")
                        || two.equals("-=")) {
                    toks.add(new Tok("op", two, false));
                    i += 2;
                } else if ("()[]{},;.=<>+-*:".indexOf(c) >= 0) {
                    toks.add(new Tok("op", String.valueOf(c), false));
                    i++;
                } else {
                    throw new SyntaxError("line 1: unexpected character '" + c + "'");
                }
            }
        }
    }

    private static boolean isUuidLike(String s, int start, int at) {
        int end = Math.min(s.length(), start + 36);
        return end - start == 36 && s.substring(start, end).matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") && at < end;
    }

    private boolean numberMayStart() {
        if (toks.isEmpty()) {
            return true;
        }
        Tok last = toks.get(toks.size() - 1);
        return "op".equals(last.kind()) && !")".equals(last.text()) && !"]".equals(last.text())
                || "ident".equals(last.kind()) && isKeyword(last.text());
    }

    private static boolean isKeyword(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "values", "limit", "ttl", "timestamp", "and", "in", "using", "contains" -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- helpers

    private Tok peek() {
        return at < toks.size() ? toks.get(at) : new Tok("eof", "", false);
    }

    private Tok peek(int ahead) {
        return at + ahead < toks.size() ? toks.get(at + ahead) : new Tok("eof", "", false);
    }

    private boolean is(String word) {
        Tok t = peek();
        return ("ident".equals(t.kind()) && !t.quoted() || "op".equals(t.kind())) && t.text().equalsIgnoreCase(word);
    }

    private boolean isAt(int ahead, String word) {
        Tok t = peek(ahead);
        return ("ident".equals(t.kind()) && !t.quoted() || "op".equals(t.kind())) && t.text().equalsIgnoreCase(word);
    }

    private boolean accept(String word) {
        if (is(word)) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(String word) {
        if (!accept(word)) {
            throw new SyntaxError("line 1: mismatched input '" + peek().text() + "' expecting '" + word + "'");
        }
    }

    private String ident() {
        Tok t = peek();
        if (!"ident".equals(t.kind())) {
            throw new SyntaxError("line 1: no viable alternative at input '" + t.text() + "'");
        }
        at++;
        return t.quoted() ? t.text() : t.text().toLowerCase(Locale.ROOT);
    }

    /** {@code [keyspace.]name}; the keyspace is null when absent. */
    private String[] qualified() {
        String first = ident();
        if (accept(".")) {
            return new String[] {first, ident()};
        }
        return new String[] {null, first};
    }

    private boolean ifNotExists() {
        if (is("if") && isAt(1, "not")) {
            at += 2;
            expect("exists");
            return true;
        }
        return false;
    }

    private boolean ifExists() {
        if (is("if") && isAt(1, "exists")) {
            at += 2;
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- statements

    private Statement statement() {
        if (accept("use")) {
            return new Use(ident());
        }
        if (accept("select")) {
            return select();
        }
        if (accept("insert")) {
            return insert();
        }
        if (accept("update")) {
            return update();
        }
        if (accept("delete")) {
            return delete();
        }
        if (accept("begin")) {
            return batch();
        }
        if (accept("create")) {
            if (accept("keyspace") || accept("schema")) {
                boolean ine = ifNotExists();
                String name = ident();
                return new CreateKeyspace(name, ine, with());
            }
            if (accept("table") || accept("columnfamily")) {
                return createTable();
            }
            if (accept("type")) {
                boolean ine = ifNotExists();
                String[] name = qualified();
                return new CreateType(name[0], name[1], ine, fields());
            }
            throw new SyntaxError("line 1: Floci's Keyspaces doesn't support CREATE " + peek().text());
        }
        if (accept("alter")) {
            if (accept("keyspace") || accept("schema")) {
                String name = ident();
                return new AlterKeyspace(name, with());
            }
            if (accept("table") || accept("columnfamily")) {
                String[] name = qualified();
                List<ColumnDef> add = new ArrayList<>();
                Map<String, Object> options = new LinkedHashMap<>();
                if (accept("add")) {
                    boolean paren = accept("(");
                    do {
                        String column = ident();
                        add.add(new ColumnDef(column, type(), false));
                    } while (paren && accept(","));
                    if (paren) {
                        expect(")");
                    }
                } else if (is("with")) {
                    options = with();
                } else {
                    throw new SyntaxError("line 1: Keyspaces supports ALTER TABLE ... ADD and ALTER TABLE ... WITH");
                }
                return new AlterTable(name[0], name[1], add, options);
            }
            throw new SyntaxError("line 1: Floci's Keyspaces doesn't support ALTER " + peek().text());
        }
        if (accept("drop")) {
            if (accept("keyspace") || accept("schema")) {
                boolean ie = ifExists();
                return new DropKeyspace(ident(), ie);
            }
            if (accept("table") || accept("columnfamily")) {
                boolean ie = ifExists();
                String[] name = qualified();
                return new DropTable(name[0], name[1], ie);
            }
            if (accept("type")) {
                boolean ie = ifExists();
                String[] name = qualified();
                return new DropType(name[0], name[1], ie);
            }
            throw new SyntaxError("line 1: Floci's Keyspaces doesn't support DROP " + peek().text());
        }
        if (accept("truncate")) {
            accept("table");
            String[] name = qualified();
            return new Truncate(name[0], name[1]);
        }
        throw new SyntaxError("line 1: no viable alternative at input '" + peek().text() + "'");
    }

    private Select select() {
        boolean json = accept("json");
        boolean distinct = accept("distinct");
        List<Selector> selectors = new ArrayList<>();
        if (accept("*")) {
            selectors.add(new Selector("star", null, null, List.of()));
        } else {
            do {
                selectors.add(selector());
            } while (accept(","));
        }
        expect("from");
        String[] name = qualified();
        List<Relation> where = is("where") ? where() : List.of();
        List<Ordering> order = new ArrayList<>();
        if (accept("order")) {
            expect("by");
            do {
                String column = ident();
                boolean desc = accept("desc");
                if (!desc) {
                    accept("asc");
                }
                order.add(new Ordering(column, desc));
            } while (accept(","));
        }
        Term perPartition = null;
        if (is("per") && isAt(1, "partition")) {
            at += 2;
            expect("limit");
            perPartition = term();
        }
        Term limit = null;
        if (accept("limit")) {
            limit = term();
        }
        boolean allowFiltering = false;
        if (accept("allow")) {
            expect("filtering");
            allowFiltering = true;
        }
        return new Select(name[0], name[1], json, distinct, selectors, where, order, limit, perPartition,
                allowFiltering);
    }

    private Selector selector() {
        Selector s;
        if (is("count") && isAt(1, "(")) {
            at += 2;
            if (!accept("*")) {
                term();
            }
            expect(")");
            s = new Selector("count", null, null, List.of());
        } else if ((is("writetime") || is("ttl")) && isAt(1, "(")) {
            String kind = ident();
            expect("(");
            String column = ident();
            expect(")");
            s = new Selector(kind, column, null, List.of());
        } else if (peek().kind().equals("ident") && isAt(1, "(")) {
            String fn = ident();
            expect("(");
            List<Selector> args = new ArrayList<>();
            if (!is(")")) {
                do {
                    args.add(selector());
                } while (accept(","));
            }
            expect(")");
            s = new Selector("function", fn, null, args);
        } else {
            s = new Selector("column", ident(), null, List.of());
        }
        if (accept("as")) {
            return new Selector(s.kind(), s.column(), ident(), s.args());
        }
        return s;
    }

    private List<Relation> where() {
        expect("where");
        List<Relation> relations = new ArrayList<>();
        do {
            relations.add(relation());
        } while (accept("and"));
        return relations;
    }

    private Relation relation() {
        List<String> columns = new ArrayList<>();
        boolean token = false;
        if (is("token") && isAt(1, "(")) {
            at += 2;
            do {
                columns.add(ident());
            } while (accept(","));
            expect(")");
            token = true;
        } else if (accept("(")) {
            do {
                columns.add(ident());
            } while (accept(","));
            expect(")");
        } else {
            columns.add(ident());
        }
        String op;
        if (accept("in")) {
            if (is("?") || peek().kind().equals("named")) {
                return new Relation(columns, token, "IN", term(), null);
            }
            expect("(");
            List<Term> values = new ArrayList<>();
            if (!is(")")) {
                do {
                    values.add(columns.size() > 1 ? tupleOrTerm() : term());
                } while (accept(","));
            }
            expect(")");
            return new Relation(columns, token, "IN", null, values);
        }
        if (accept("contains")) {
            op = accept("key") ? "CONTAINS KEY" : "CONTAINS";
        } else if (is("=") || is("<") || is(">") || is("<=") || is(">=") || is("!=")) {
            op = peek().text();
            at++;
        } else {
            throw new SyntaxError("line 1: no viable alternative at input '" + peek().text() + "'");
        }
        Term value = columns.size() > 1 && !token ? tupleOrTerm() : term();
        if (token && is("token") && isAt(1, "(")) {
            at += 2;
            List<Term> args = new ArrayList<>();
            do {
                args.add(term());
            } while (accept(","));
            expect(")");
            value = new Term("token", null, args, List.of(), -1);
        }
        return new Relation(columns, token, op, value, null);
    }

    private Term tupleOrTerm() {
        if (accept("(")) {
            List<Term> items = new ArrayList<>();
            do {
                items.add(term());
            } while (accept(","));
            expect(")");
            return new Term("tuple", null, items, List.of(), -1);
        }
        return term();
    }

    private Term[] using() {
        Term ttl = null;
        Term ts = null;
        if (accept("using")) {
            do {
                if (accept("ttl")) {
                    ttl = term();
                } else if (accept("timestamp")) {
                    ts = term();
                } else {
                    throw new SyntaxError("line 1: expecting TTL or TIMESTAMP after USING");
                }
            } while (accept("and"));
        }
        return new Term[] {ttl, ts};
    }

    private Insert insert() {
        expect("into");
        String[] name = qualified();
        List<String> columns = new ArrayList<>();
        List<Term> values = new ArrayList<>();
        Term json = null;
        if (accept("json")) {
            json = term();
            if (accept("default")) {
                if (!accept("null")) {
                    expect("unset");
                }
            }
        } else {
            expect("(");
            do {
                columns.add(ident());
            } while (accept(","));
            expect(")");
            expect("values");
            expect("(");
            do {
                values.add(term());
            } while (accept(","));
            expect(")");
            if (columns.size() != values.size()) {
                throw new SyntaxError("Unmatched column names/values");
            }
        }
        boolean ine = ifNotExists();
        Term[] using = using();
        return new Insert(name[0], name[1], columns, values, json, ine, using[0], using[1]);
    }

    private Update update() {
        String[] name = qualified();
        Term[] using = using();
        expect("set");
        List<Assignment> assignments = new ArrayList<>();
        do {
            assignments.add(assignment());
        } while (accept(","));
        List<Relation> where = where();
        boolean ie = false;
        List<Condition> conditions = new ArrayList<>();
        if (is("if")) {
            if (ifExists()) {
                ie = true;
            } else {
                expect("if");
                conditions = conditions();
            }
        }
        return new Update(name[0], name[1], using[0], using[1], assignments, where, ie, conditions);
    }

    private Assignment assignment() {
        String column = ident();
        Term key = null;
        String field = null;
        if (accept("[")) {
            key = term();
            expect("]");
        } else if (accept(".")) {
            field = ident();
        }
        if (accept("+=")) {
            return new Assignment(column, key, "+", term(), false, field);
        }
        if (accept("-=")) {
            return new Assignment(column, key, "-", term(), false, field);
        }
        expect("=");
        if (peek().kind().equals("ident") && peek().text().equalsIgnoreCase(column) && (isAt(1, "+")
                || isAt(1, "-"))) {
            at++;
            String op = peek().text();
            at++;
            return new Assignment(column, key, op, term(), false, field);
        }
        Term value = term();
        if (accept("+")) {
            String self = ident();
            if (!self.equals(column)) {
                throw new SyntaxError("Only expressions of the form X = <value> + X are supported.");
            }
            return new Assignment(column, key, "+", value, true, field);
        }
        return new Assignment(column, key, "=", value, false, field);
    }

    private List<Condition> conditions() {
        List<Condition> conditions = new ArrayList<>();
        do {
            String column = ident();
            Term key = null;
            if (accept("[")) {
                key = term();
                expect("]");
            }
            if (accept("in")) {
                expect("(");
                List<Term> values = new ArrayList<>();
                do {
                    values.add(term());
                } while (accept(","));
                expect(")");
                conditions.add(new Condition(column, key, "IN", null, values));
                continue;
            }
            String op = peek().text();
            if (!List.of("=", "<", ">", "<=", ">=", "!=").contains(op)) {
                throw new SyntaxError("line 1: invalid condition operator " + op);
            }
            at++;
            conditions.add(new Condition(column, key, op, term(), null));
        } while (accept("and"));
        return conditions;
    }

    private Delete delete() {
        List<Assignment> targets = new ArrayList<>();
        if (!is("from")) {
            do {
                String column = ident();
                Term key = null;
                String field = null;
                if (accept("[")) {
                    key = term();
                    expect("]");
                } else if (accept(".")) {
                    field = ident();
                }
                targets.add(new Assignment(column, key, "delete", null, false, field));
            } while (accept(","));
        }
        expect("from");
        String[] name = qualified();
        Term[] using = using();
        List<Relation> where = where();
        boolean ie = false;
        List<Condition> conditions = new ArrayList<>();
        if (is("if")) {
            if (ifExists()) {
                ie = true;
            } else {
                expect("if");
                conditions = conditions();
            }
        }
        return new Delete(name[0], name[1], targets, using[1], where, ie, conditions);
    }

    private Batch batch() {
        String type = "LOGGED";
        if (accept("unlogged")) {
            type = "UNLOGGED";
        } else if (accept("counter")) {
            type = "COUNTER";
        } else {
            accept("logged");
        }
        expect("batch");
        Term[] using = using();
        List<Statement> statements = new ArrayList<>();
        while (!is("apply")) {
            Statement s = statement();
            if (!(s instanceof Insert || s instanceof Update || s instanceof Delete)) {
                throw new SyntaxError("Only INSERT, UPDATE and DELETE are allowed in a batch.");
            }
            statements.add(s);
            accept(";");
        }
        expect("apply");
        expect("batch");
        return new Batch(type, statements, using[1]);
    }

    private CreateTable createTable() {
        boolean ine = ifNotExists();
        String[] name = qualified();
        expect("(");
        List<ColumnDef> columns = new ArrayList<>();
        List<String> partition = new ArrayList<>();
        List<String> clustering = new ArrayList<>();
        do {
            if (is("primary") && isAt(1, "key")) {
                at += 2;
                expect("(");
                if (accept("(")) {
                    do {
                        partition.add(ident());
                    } while (accept(","));
                    expect(")");
                } else {
                    partition.add(ident());
                }
                while (accept(",")) {
                    clustering.add(ident());
                }
                expect(")");
                continue;
            }
            String column = ident();
            String type = type();
            boolean isStatic = accept("static");
            if (is("primary") && isAt(1, "key")) {
                at += 2;
                if (!partition.isEmpty()) {
                    throw new SyntaxError("Multiple PRIMARY KEYs specified");
                }
                partition.add(column);
            }
            columns.add(new ColumnDef(column, type, isStatic));
        } while (accept(","));
        expect(")");
        Map<String, String> order = new LinkedHashMap<>();
        Map<String, Object> options = new LinkedHashMap<>();
        if (accept("with")) {
            do {
                if (accept("clustering")) {
                    expect("order");
                    expect("by");
                    expect("(");
                    do {
                        String column = ident();
                        order.put(column, accept("desc") ? "DESC" : (accept("asc") ? "ASC" : "ASC"));
                    } while (accept(","));
                    expect(")");
                } else if (is("compact") && isAt(1, "storage")) {
                    throw new SyntaxError("Keyspaces doesn't support COMPACT STORAGE.");
                } else {
                    String option = ident();
                    expect("=");
                    options.put(option, optionValue());
                }
            } while (accept("and"));
        }
        if (partition.isEmpty()) {
            throw new SyntaxError("No PRIMARY KEY specified");
        }
        return new CreateTable(name[0], name[1], ine, columns, partition, clustering, order, options);
    }

    private List<ColumnDef> fields() {
        expect("(");
        List<ColumnDef> fields = new ArrayList<>();
        do {
            String field = ident();
            fields.add(new ColumnDef(field, type(), false));
        } while (accept(","));
        expect(")");
        return fields;
    }

    /** A type, as text: identifiers and angle brackets up to the next top-level comma or parenthesis. */
    private String type() {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        while (true) {
            Tok t = peek();
            if ("eof".equals(t.kind())) {
                break;
            }
            if ("op".equals(t.kind())) {
                if (t.text().equals("<")) {
                    depth++;
                } else if (t.text().equals(">")) {
                    depth--;
                } else if (depth == 0 && (t.text().equals(",") || t.text().equals(")"))) {
                    break;
                } else if (!t.text().equals(",") && !t.text().equals(".")) {
                    break;
                }
                out.append(t.text());
                at++;
                continue;
            }
            if (depth == 0 && out.length() > 0 && !out.toString().endsWith(".")) {
                break;
            }
            out.append(t.quoted() ? "\"" + t.text() + "\"" : t.text());
            at++;
        }
        if (out.length() == 0) {
            throw new SyntaxError("line 1: expecting a type at '" + peek().text() + "'");
        }
        return out.toString();
    }

    private Map<String, Object> with() {
        Map<String, Object> options = new LinkedHashMap<>();
        if (!accept("with")) {
            return options;
        }
        do {
            String option = ident();
            expect("=");
            options.put(option, optionValue());
        } while (accept("and"));
        return options;
    }

    private Object optionValue() {
        if (accept("{")) {
            Map<String, Object> map = new LinkedHashMap<>();
            if (!is("}")) {
                do {
                    Object key = optionValue();
                    expect(":");
                    map.put(String.valueOf(key), optionValue());
                } while (accept(","));
            }
            expect("}");
            return map;
        }
        if (accept("[")) {
            List<Object> list = new ArrayList<>();
            if (!is("]")) {
                do {
                    list.add(optionValue());
                } while (accept(","));
            }
            expect("]");
            return list;
        }
        Tok t = peek();
        at++;
        return t.text();
    }

    // ---------------------------------------------------------------- terms

    private Term term() {
        Tok t = peek();
        switch (t.kind()) {
            case "?" -> {
                at++;
                binds.add(null);
                return new Term("bind", null, List.of(), List.of(), binds.size() - 1);
            }
            case "named" -> {
                at++;
                binds.add(t.text());
                return new Term("bind", t.text(), List.of(), List.of(), binds.size() - 1);
            }
            case "string", "integer", "float", "uuid", "hex", "duration" -> {
                at++;
                return Term.literal(t.kind(), t.text());
            }
            case "ident" -> {
                String word = t.text().toLowerCase(Locale.ROOT);
                if (!t.quoted() && (word.equals("true") || word.equals("false"))) {
                    at++;
                    return Term.literal("boolean", word);
                }
                if (!t.quoted() && word.equals("null")) {
                    at++;
                    return Term.literal("null", null);
                }
                if (!t.quoted() && (word.equals("nan") || word.equals("infinity"))) {
                    at++;
                    return Term.literal("float", word.equals("nan") ? "NaN" : "Infinity");
                }
                if (isAt(1, "(")) {
                    String fn = ident();
                    expect("(");
                    List<Term> args = new ArrayList<>();
                    if (!is(")")) {
                        do {
                            args.add(term());
                        } while (accept(","));
                    }
                    expect(")");
                    return new Term("function", fn, args, List.of(), -1);
                }
                throw new SyntaxError("line 1: no viable alternative at input '" + t.text() + "'");
            }
            case "op" -> {
                if (t.text().equals("-") && ("integer".equals(peek(1).kind()) || "float".equals(peek(1).kind()))) {
                    at++;
                    Tok n = peek();
                    at++;
                    return Term.literal(n.kind(), "-" + n.text());
                }
                if (t.text().equals("[")) {
                    at++;
                    List<Term> items = new ArrayList<>();
                    if (!is("]")) {
                        do {
                            items.add(term());
                        } while (accept(","));
                    }
                    expect("]");
                    return new Term("list", null, items, List.of(), -1);
                }
                if (t.text().equals("(")) {
                    at++;
                    List<Term> items = new ArrayList<>();
                    do {
                        items.add(term());
                    } while (accept(","));
                    expect(")");
                    return new Term("tuple", null, items, List.of(), -1);
                }
                if (t.text().equals("{")) {
                    at++;
                    if (accept("}")) {
                        return new Term("set", null, List.of(), List.of(), -1);
                    }
                    if (peek().kind().equals("ident") && isAt(1, ":")) {
                        List<String> names = new ArrayList<>();
                        List<Term> items = new ArrayList<>();
                        do {
                            names.add(ident());
                            expect(":");
                            items.add(term());
                        } while (accept(","));
                        expect("}");
                        return new Term("udt", null, items, names, -1);
                    }
                    Term first = term();
                    if (accept(":")) {
                        List<Term> items = new ArrayList<>();
                        items.add(first);
                        items.add(term());
                        while (accept(",")) {
                            items.add(term());
                            expect(":");
                            items.add(term());
                        }
                        expect("}");
                        return new Term("map", null, items, List.of(), -1);
                    }
                    List<Term> items = new ArrayList<>();
                    items.add(first);
                    while (accept(",")) {
                        items.add(term());
                    }
                    expect("}");
                    return new Term("set", null, items, List.of(), -1);
                }
                throw new SyntaxError("line 1: no viable alternative at input '" + t.text() + "'");
            }
            default -> throw new SyntaxError("line 1: no viable alternative at input '" + t.text() + "'");
        }
    }
}
