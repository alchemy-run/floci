package io.floci.aws.services.keyspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.keyspaces.CqlParser.Assignment;
import io.floci.aws.services.keyspaces.CqlParser.Condition;
import io.floci.aws.services.keyspaces.CqlParser.Relation;
import io.floci.aws.services.keyspaces.CqlParser.Selector;
import io.floci.aws.services.keyspaces.CqlParser.Statement;
import io.floci.aws.services.keyspaces.CqlParser.Term;
import io.floci.aws.services.keyspaces.KeyspacesData.Schema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs CQL statements against Keyspaces tables, as the Keyspaces CQL endpoint does.
 *
 * <p>Reads filter partitions by their key ({@code =} or {@code IN}) or by token range, then clustering columns by
 * any comparison, and other columns only with {@code ALLOW FILTERING}; results come in token order, then clustering
 * order (reversed by {@code ORDER BY}), paged by the driver's page size. Writes need the whole primary key; they are
 * last-write-wins by write time (client timestamps need the table's client-side timestamps), expire by {@code TTL}
 * (which needs the table's TTL; its default TTL applies otherwise), and support counters, collection operations and
 * lightweight transactions ({@code IF NOT EXISTS}, {@code IF EXISTS}, {@code IF} conditions), returning
 * {@code [applied]}. Writes take {@code LOCAL_QUORUM} only and reads {@code ONE}, {@code LOCAL_ONE} or
 * {@code LOCAL_QUORUM}, as Keyspaces requires. Schema statements create keyspaces, tables (with Keyspaces'
 * {@code custom_properties}) and types through the control plane. The {@code system} and {@code system_schema}
 * tables drivers read at connect are served from the control plane.
 */
@ApplicationScoped
public class CqlExecutor {

    static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    static final int MAX_BATCH = 30;
    static final Set<Integer> READ_CONSISTENCY = Set.of(0x0001, 0x000A, 0x0006);
    static final int LOCAL_QUORUM = 0x0006;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong CLOCK = new AtomicLong();
    private static final long NODE = RANDOM.nextLong() & 0x0000ffffffffffffL | 0x0000010000000000L;

    private final KeyspacesData data;
    private final KeyspacesService service;
    private final KeyspacesStore store;
    private final CqlSystemTables system;
    private final Map<String, Prepared> prepared = new ConcurrentHashMap<>();

    @Inject
    public CqlExecutor(KeyspacesData data, KeyspacesService service, KeyspacesStore store, CqlSystemTables system) {
        this.data = data;
        this.service = service;
        this.store = store;
        this.system = system;
    }

    private ObjectMapper mapper() {
        return store.mapper();
    }

    // ---------------------------------------------------------------- model

    /** A connection's account, Region and current keyspace. */
    static final class Session {
        final String account;
        final String region;
        String keyspace;

        Session(String account, String region) {
            this.account = account;
            this.region = region;
        }
    }

    /** A result or bind column. */
    record Column(String keyspace, String table, String name, CqlType type) {
    }

    sealed interface Result permits VoidResult, Rows, SetKeyspace, SchemaChange {
    }

    record VoidResult() implements Result {
    }

    record Rows(List<Column> columns, List<List<byte[]>> rows, byte[] pagingState,
                Map<String, List<Map.Entry<String, CqlType>>> udts) implements Result {
    }

    record SetKeyspace(String keyspace) implements Result {
    }

    record SchemaChange(String change, String target, String keyspace, String name) implements Result {
    }

    /** Bound values, by position or by name. */
    record Binds(List<byte[]> values, Map<String, byte[]> named, List<String> markers) {
        static Binds none() {
            return new Binds(List.of(), Map.of(), List.of());
        }

        byte[] get(Term term) {
            if (named != null && !named.isEmpty()) {
                String name = term.text() != null ? term.text() : markers.get(term.bind());
                if (!named.containsKey(name)) {
                    throw CqlError.invalid("No value given for bind marker " + name + ".");
                }
                return named.get(name);
            }
            if (term.bind() >= values.size()) {
                throw CqlError.invalid("There were " + markers.size() + " markers(?) in CQL but " + values.size()
                        + " bound variables");
            }
            return values.get(term.bind());
        }
    }

    record Options(int consistency, int pageSize, byte[] pagingState, Long timestamp) {
        static Options defaults() {
            return new Options(LOCAL_QUORUM, -1, null, null);
        }
    }

    /** A prepared statement: its text, its bind variables and its result columns. */
    record Prepared(byte[] id, String cql, CqlParser.Parsed parsed, List<Column> variables, List<Column> results,
                    List<Integer> pkIndexes, String keyspace, Map<String, List<Map.Entry<String, CqlType>>> udts) {
    }

    // ---------------------------------------------------------------- entry points

    Result run(Session session, String cql, Binds binds, Options options) {
        CqlParser.Parsed parsed = parse(cql);
        Binds named = new Binds(binds.values(), binds.named(), parsed.binds());
        return execute(session, parsed.statement(), named, options);
    }

    private static CqlParser.Parsed parse(String cql) {
        try {
            return CqlParser.parse(cql);
        } catch (CqlParser.SyntaxError e) {
            throw new CqlError(CqlError.SYNTAX, e.getMessage());
        }
    }

    Prepared prepare(Session session, String cql) {
        CqlParser.Parsed parsed = parse(cql);
        byte[] id = md5(session.keyspace + "\u0000" + cql);
        List<Column> variables = new ArrayList<>(Map.of().size());
        for (int i = 0; i < parsed.binds().size(); i++) {
            variables.add(null);
        }
        List<Column> results = new ArrayList<>();
        List<Integer> pkIndexes = new ArrayList<>();
        Map<String, List<Map.Entry<String, CqlType>>> udts = new HashMap<>();
        describe(session, parsed.statement(), variables, results, pkIndexes, udts);
        for (int i = 0; i < variables.size(); i++) {
            if (variables.get(i) == null) {
                variables.set(i, new Column(session.keyspace == null ? "" : session.keyspace, "", "[value]",
                        CqlType.parse("blob", u -> true)));
            }
        }
        Prepared p = new Prepared(id, cql, parsed, variables, results, pkIndexes, session.keyspace, udts);
        prepared.put(HexFormat.of().formatHex(id), p);
        return p;
    }

    Prepared prepared(byte[] id) {
        Prepared p = prepared.get(HexFormat.of().formatHex(id));
        if (p == null) {
            throw new CqlError(CqlError.UNPREPARED, "Prepared query with ID " + HexFormat.of().formatHex(id)
                    + " not found", null, null, id);
        }
        return p;
    }

    Result execute(Session session, Prepared p, List<byte[]> values, Map<String, byte[]> named, Options options) {
        Binds binds = new Binds(values, named, p.parsed().binds());
        String previous = session.keyspace;
        if (session.keyspace == null) {
            session.keyspace = p.keyspace();
        }
        try {
            return execute(session, p.parsed().statement(), binds, options);
        } finally {
            if (previous == null && !(p.parsed().statement() instanceof CqlParser.Use)) {
                session.keyspace = previous;
            }
        }
    }

    private static byte[] md5(String text) {
        try {
            return MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Result execute(Session session, Statement statement, Binds binds, Options options) {
        try {
            return switch (statement) {
                case CqlParser.Use use -> use(session, use);
                case CqlParser.Select select -> {
                    if (!READ_CONSISTENCY.contains(options.consistency())) {
                        throw CqlError.invalid("Consistency level " + consistencyName(options.consistency())
                                + " is not supported for this operation. Supported consistency levels are: ONE, "
                                + "LOCAL_ONE, LOCAL_QUORUM");
                    }
                    yield select(session, select, binds, options);
                }
                case CqlParser.Insert insert -> {
                    writeConsistency(options);
                    yield write(session, List.of(insert), binds, options, null);
                }
                case CqlParser.Update update -> {
                    writeConsistency(options);
                    yield write(session, List.of(update), binds, options, null);
                }
                case CqlParser.Delete delete -> {
                    writeConsistency(options);
                    yield write(session, List.of(delete), binds, options, null);
                }
                case CqlParser.Batch batch -> {
                    writeConsistency(options);
                    if (batch.statements().size() > MAX_BATCH) {
                        throw CqlError.invalid("A batch can have at most " + MAX_BATCH + " statements.");
                    }
                    yield write(session, batch.statements(), binds, options, batch.timestamp());
                }
                default -> schema(session, statement, binds);
            };
        } catch (AwsException e) {
            throw toCql(e);
        } catch (IllegalArgumentException e) {
            throw CqlError.invalid(e.getMessage());
        }
    }

    private static CqlError toCql(AwsException e) {
        return switch (e.getErrorCode()) {
            case "ResourceNotFoundException" -> CqlError.invalid(e.getMessage());
            case "ConflictException" -> new CqlError(CqlError.CONFIG, e.getMessage());
            case "ServiceQuotaExceededException" -> new CqlError(CqlError.INVALID, e.getMessage());
            case "AccessDeniedException" -> new CqlError(CqlError.UNAUTHORIZED, e.getMessage());
            default -> CqlError.invalid(e.getMessage());
        };
    }

    private static void writeConsistency(Options options) {
        if (options.consistency() != LOCAL_QUORUM) {
            throw CqlError.invalid("Consistency level " + consistencyName(options.consistency())
                    + " is not supported for this operation. Supported consistency levels are: LOCAL_QUORUM");
        }
    }

    static String consistencyName(int code) {
        return switch (code) {
            case 0x0000 -> "ANY";
            case 0x0001 -> "ONE";
            case 0x0002 -> "TWO";
            case 0x0003 -> "THREE";
            case 0x0004 -> "QUORUM";
            case 0x0005 -> "ALL";
            case 0x0006 -> "LOCAL_QUORUM";
            case 0x0007 -> "EACH_QUORUM";
            case 0x0008 -> "SERIAL";
            case 0x0009 -> "LOCAL_SERIAL";
            case 0x000A -> "LOCAL_ONE";
            default -> String.valueOf(code);
        };
    }

    private Result use(Session session, CqlParser.Use use) {
        service.keyspace(session.account, session.region, use.keyspace());
        session.keyspace = use.keyspace();
        return new SetKeyspace(use.keyspace());
    }

    private String keyspaceOf(Session session, String keyspace) {
        String ks = keyspace != null ? keyspace : session.keyspace;
        if (ks == null) {
            throw CqlError.invalid("No keyspace has been specified. USE a keyspace, or explicitly specify "
                    + "keyspace.tablename");
        }
        return ks;
    }

    private Schema schemaOf(Session session, String keyspace, String table) {
        String ks = keyspaceOf(session, keyspace);
        try {
            return data.schema(session.account, session.region, ks, table);
        } catch (AwsException e) {
            throw CqlError.invalid("unconfigured table " + table);
        }
    }

    // ---------------------------------------------------------------- describing binds and results

    private void describe(Session session, Statement statement, List<Column> variables, List<Column> results,
                          List<Integer> pkIndexes, Map<String, List<Map.Entry<String, CqlType>>> udts) {
        switch (statement) {
            case CqlParser.Select s -> {
                String ks = keyspaceOf(session, s.keyspace());
                Map<String, CqlType> columns;
                List<String> pk;
                CqlSystemTables.Virtual virtual = system.table(session, ks, s.table());
                if (virtual != null) {
                    columns = virtual.columns();
                    pk = List.of();
                } else {
                    Schema schema = schemaOf(session, s.keyspace(), s.table());
                    columns = schema.columns;
                    pk = schema.partitionKeys;
                    udts.putAll(schema.udts);
                    results.addAll(selectColumns(s, schema.ordered(), columns, ks, s.table()));
                }
                if (virtual != null) {
                    results.addAll(selectColumns(s, new ArrayList<>(columns.keySet()), columns, ks, s.table()));
                }
                for (Relation r : s.where()) {
                    relationBinds(r, columns, variables, ks, s.table(), pk, pkIndexes);
                }
                bindAs(s.limit(), variables, ks, s.table(), "[limit]", "int");
                bindAs(s.perPartitionLimit(), variables, ks, s.table(), "[limit]", "int");
            }
            case CqlParser.Insert i -> {
                Schema schema = schemaOf(session, i.keyspace(), i.table());
                udts.putAll(schema.udts);
                if (i.json() != null) {
                    bindAs(i.json(), variables, schema.keyspace, schema.table, "[json]", "text");
                }
                for (int k = 0; k < i.columns().size(); k++) {
                    String column = i.columns().get(k);
                    Term term = i.values().get(k);
                    if (term.isBind()) {
                        variables.set(term.bind(), new Column(schema.keyspace, schema.table, column,
                                type(schema, column)));
                        int index = schema.partitionKeys.indexOf(column);
                        if (index >= 0) {
                            while (pkIndexes.size() <= index) {
                                pkIndexes.add(-1);
                            }
                            pkIndexes.set(index, term.bind());
                        }
                    }
                }
                bindAs(i.ttl(), variables, schema.keyspace, schema.table, "[ttl]", "int");
                bindAs(i.timestamp(), variables, schema.keyspace, schema.table, "[timestamp]", "bigint");
                if (i.ifNotExists()) {
                    results.add(new Column(schema.keyspace, schema.table, "[applied]", type("boolean")));
                }
            }
            case CqlParser.Update u -> {
                Schema schema = schemaOf(session, u.keyspace(), u.table());
                udts.putAll(schema.udts);
                bindAs(u.ttl(), variables, schema.keyspace, schema.table, "[ttl]", "int");
                bindAs(u.timestamp(), variables, schema.keyspace, schema.table, "[timestamp]", "bigint");
                for (Assignment a : u.assignments()) {
                    assignmentBinds(schema, a, variables);
                }
                for (Relation r : u.where()) {
                    relationBinds(r, schema.columns, variables, schema.keyspace, schema.table, schema.partitionKeys,
                            pkIndexes);
                }
                for (Condition c : u.conditions()) {
                    conditionBinds(schema, c, variables);
                }
                if (u.ifExists() || !u.conditions().isEmpty()) {
                    results.add(new Column(schema.keyspace, schema.table, "[applied]", type("boolean")));
                }
            }
            case CqlParser.Delete d -> {
                Schema schema = schemaOf(session, d.keyspace(), d.table());
                udts.putAll(schema.udts);
                bindAs(d.timestamp(), variables, schema.keyspace, schema.table, "[timestamp]", "bigint");
                for (Assignment a : d.targets()) {
                    if (a.key() != null && a.key().isBind()) {
                        CqlType t = type(schema, a.column());
                        variables.set(a.key().bind(), new Column(schema.keyspace, schema.table, "key(" + a.column()
                                + ")", "list".equals(t.name()) ? type("int") : t.args().get(0)));
                    }
                }
                for (Relation r : d.where()) {
                    relationBinds(r, schema.columns, variables, schema.keyspace, schema.table, schema.partitionKeys,
                            pkIndexes);
                }
                for (Condition c : d.conditions()) {
                    conditionBinds(schema, c, variables);
                }
                if (d.ifExists() || !d.conditions().isEmpty()) {
                    results.add(new Column(schema.keyspace, schema.table, "[applied]", type("boolean")));
                }
            }
            case CqlParser.Batch b -> {
                for (Statement s : b.statements()) {
                    describe(session, s, variables, new ArrayList<>(), new ArrayList<>(), udts);
                }
                bindAs(b.timestamp(), variables, "", "", "[timestamp]", "bigint");
            }
            default -> {
            }
        }
    }

    private static CqlType type(String name) {
        return CqlType.parse(name, u -> true);
    }

    private static CqlType type(Schema schema, String column) {
        CqlType type = schema.columns.get(column);
        if (type == null) {
            throw CqlError.invalid("Undefined column name " + column + " in table " + schema.keyspace + "."
                    + schema.table);
        }
        return type;
    }

    private static void bindAs(Term term, List<Column> variables, String ks, String table, String name,
                               String type) {
        if (term != null && term.isBind()) {
            variables.set(term.bind(), new Column(ks, table, name, type(type)));
        }
    }

    private static void relationBinds(Relation r, Map<String, CqlType> columns, List<Column> variables, String ks,
                                      String table, List<String> pk, List<Integer> pkIndexes) {
        if (r.token()) {
            bindAs(r.value(), variables, ks, table, "partition key token", "bigint");
            if (r.value() != null && "token".equals(r.value().kind())) {
                for (int k = 0; k < r.value().items().size(); k++) {
                    Term arg = r.value().items().get(k);
                    if (arg.isBind() && k < r.columns().size()) {
                        variables.set(arg.bind(), new Column(ks, table, r.columns().get(k),
                                columns.get(r.columns().get(k))));
                    }
                }
            }
            return;
        }
        if (r.columns().size() > 1) {
            List<CqlType> types = new ArrayList<>();
            for (String c : r.columns()) {
                types.add(columns.get(c));
            }
            CqlType tuple = new CqlType("tuple", types, true);
            if (r.value() != null && r.value().isBind()) {
                variables.set(r.value().bind(), new Column(ks, table, "(" + String.join(",", r.columns()) + ")",
                        "IN".equals(r.op()) ? new CqlType("list", List.of(tuple), false) : tuple));
            } else if (r.value() != null) {
                for (int k = 0; k < r.value().items().size(); k++) {
                    Term item = r.value().items().get(k);
                    if (item.isBind()) {
                        variables.set(item.bind(), new Column(ks, table, r.columns().get(k), types.get(k)));
                    }
                }
            }
            return;
        }
        String column = r.columns().get(0);
        CqlType type = columns.get(column);
        if (type == null) {
            throw CqlError.invalid("Undefined column name " + column);
        }
        CqlType valueType = switch (r.op()) {
            case "CONTAINS" -> "map".equals(type.name()) ? type.args().get(1) : type.args().get(0);
            case "CONTAINS KEY" -> type.args().get(0);
            default -> type;
        };
        if ("IN".equals(r.op())) {
            if (r.value() != null && r.value().isBind()) {
                variables.set(r.value().bind(), new Column(ks, table, "in(" + column + ")",
                        new CqlType("list", List.of(type), false)));
            } else {
                for (Term item : r.in()) {
                    if (item.isBind()) {
                        variables.set(item.bind(), new Column(ks, table, column, type));
                    }
                }
            }
        } else if (r.value() != null && r.value().isBind()) {
            variables.set(r.value().bind(), new Column(ks, table, column, valueType));
            int index = pk.indexOf(column);
            if (index >= 0 && "=".equals(r.op())) {
                while (pkIndexes.size() <= index) {
                    pkIndexes.add(-1);
                }
                pkIndexes.set(index, r.value().bind());
            }
        }
    }

    private static void assignmentBinds(Schema schema, Assignment a, List<Column> variables) {
        CqlType type = type(schema, a.column());
        if (a.key() != null && a.key().isBind()) {
            variables.set(a.key().bind(), new Column(schema.keyspace, schema.table, "key(" + a.column() + ")",
                    "list".equals(type.name()) ? type("int") : type.args().get(0)));
        }
        if (a.value() != null && a.value().isBind()) {
            CqlType valueType;
            if (a.key() != null) {
                valueType = "map".equals(type.name()) ? type.args().get(1) : type.args().get(0);
            } else if (a.field() != null) {
                valueType = schema.udt(type.name()).stream().filter(f -> f.getKey().equals(a.field())).findFirst()
                        .map(Map.Entry::getValue).orElseThrow(() -> CqlError.invalid("Unknown field " + a.field()));
            } else if ("-".equals(a.op()) && "map".equals(type.name())) {
                valueType = new CqlType("set", List.of(type.args().get(0)), false);
            } else if ("counter".equals(type.name())) {
                valueType = type("bigint");
            } else {
                valueType = type;
            }
            variables.set(a.value().bind(), new Column(schema.keyspace, schema.table, a.column(), valueType));
        }
    }

    private static void conditionBinds(Schema schema, Condition c, List<Column> variables) {
        CqlType type = type(schema, c.column());
        CqlType valueType = c.key() == null ? type : "map".equals(type.name()) ? type.args().get(1)
                : type.args().get(0);
        if (c.value() != null && c.value().isBind()) {
            variables.set(c.value().bind(), new Column(schema.keyspace, schema.table, c.column(), valueType));
        }
        if (c.in() != null) {
            for (Term t : c.in()) {
                if (t.isBind()) {
                    variables.set(t.bind(), new Column(schema.keyspace, schema.table, c.column(), valueType));
                }
            }
        }
    }

    private List<Column> selectColumns(CqlParser.Select s, List<String> ordered, Map<String, CqlType> columns,
                                       String ks, String table) {
        List<Column> out = new ArrayList<>();
        if (s.json()) {
            out.add(new Column(ks, table, "[json]", type("text")));
            return out;
        }
        for (Selector selector : s.selectors()) {
            switch (selector.kind()) {
                case "star" -> ordered.forEach(c -> out.add(new Column(ks, table, c, columns.get(c))));
                case "count" -> out.add(new Column(ks, table, selector.alias() != null ? selector.alias() : "count",
                        type("bigint")));
                case "writetime" -> out.add(new Column(ks, table, selector.alias() != null ? selector.alias()
                        : "writetime(" + selector.column() + ")", type("bigint")));
                case "ttl" -> out.add(new Column(ks, table, selector.alias() != null ? selector.alias()
                        : "ttl(" + selector.column() + ")", type("int")));
                case "function" -> out.add(new Column(ks, table, selector.alias() != null ? selector.alias()
                        : label(selector), functionType(selector, columns)));
                default -> {
                    CqlType t = columns.get(selector.column());
                    if (t == null) {
                        throw CqlError.invalid("Undefined column name " + selector.column());
                    }
                    out.add(new Column(ks, table, selector.alias() != null ? selector.alias() : selector.column(), t));
                }
            }
        }
        return out;
    }

    private static String label(Selector s) {
        if (!"function".equals(s.kind())) {
            return s.column();
        }
        List<String> args = new ArrayList<>();
        s.args().forEach(a -> args.add(label(a)));
        return "system." + s.column() + "(" + String.join(", ", args) + ")";
    }

    private static CqlType functionType(Selector s, Map<String, CqlType> columns) {
        return switch (s.column()) {
            case "token" -> type("bigint");
            case "totimestamp", "dateof", "currenttimestamp" -> type("timestamp");
            case "todate", "currentdate" -> type("date");
            case "tounixtimestamp", "unixtimestampof" -> type("bigint");
            case "tojson" -> type("text");
            case "now", "currenttimeuuid" -> type("timeuuid");
            case "uuid" -> type("uuid");
            default -> {
                if (s.column().endsWith("asblob")) {
                    yield type("blob");
                }
                if (s.column().startsWith("blobas")) {
                    yield type(s.column().substring(6));
                }
                throw CqlError.invalid("Unknown function " + s.column());
            }
        };
    }

    // ---------------------------------------------------------------- terms

    /** A term's stored value for a type; {@link #UNSET} for an unset bound value. */
    static final JsonNode UNSET = NODES.textNode("\u0000unset");

    JsonNode value(Term term, CqlType type, Binds binds, Map<String, List<Map.Entry<String, CqlType>>> udts) {
        switch (term.kind()) {
            case "bind" -> {
                byte[] bytes = binds.get(term);
                if (bytes == CqlCodec.UNSET) {
                    return UNSET;
                }
                return CqlCodec.decode(type, bytes, name -> udt(udts, name));
            }
            case "null" -> {
                return NODES.nullNode();
            }
            case "function" -> {
                return function(term, type, binds, udts);
            }
            case "list", "set", "tuple" -> {
                if ("map".equals(type.name()) && term.items().isEmpty()) {
                    return NODES.arrayNode();
                }
                if ("udt".equals(type.name()) || type.isUdt() && "set".equals(term.kind()) && term.items().isEmpty()) {
                    return NODES.objectNode();
                }
                ArrayNode out = NODES.arrayNode();
                for (int i = 0; i < term.items().size(); i++) {
                    CqlType element = "tuple".equals(type.name()) ? type.args().get(i) : type.args().isEmpty()
                            ? null : type.args().get(0);
                    if (element == null) {
                        throw CqlError.invalid("Invalid " + term.kind() + " literal for " + type);
                    }
                    out.add(value(term.items().get(i), element, binds, udts));
                }
                return "set".equals(type.name()) ? normalizeSet(type, out) : out;
            }
            case "map" -> {
                if (!"map".equals(type.name())) {
                    throw CqlError.invalid("Invalid map literal for " + type);
                }
                ArrayNode out = NODES.arrayNode();
                for (int i = 0; i < term.items().size(); i += 2) {
                    ArrayNode pair = out.addArray();
                    pair.add(value(term.items().get(i), type.args().get(0), binds, udts));
                    pair.add(value(term.items().get(i + 1), type.args().get(1), binds, udts));
                }
                return normalizeMap(type, out);
            }
            case "udt" -> {
                List<Map.Entry<String, CqlType>> fields = udt(udts, type.name());
                ObjectNode out = NODES.objectNode();
                for (Map.Entry<String, CqlType> field : fields) {
                    out.putNull(field.getKey());
                }
                for (int i = 0; i < term.names().size(); i++) {
                    String name = term.names().get(i);
                    CqlType fieldType = fields.stream().filter(f -> f.getKey().equals(name)).findFirst()
                            .map(Map.Entry::getValue).orElseThrow(() -> CqlError.invalid("Unknown field " + name
                                    + " in type " + type.name()));
                    out.set(name, value(term.items().get(i), fieldType, binds, udts));
                }
                return out;
            }
            default -> {
                return literal(term, type);
            }
        }
    }

    private static List<Map.Entry<String, CqlType>> udt(Map<String, List<Map.Entry<String, CqlType>>> udts,
                                                       String name) {
        List<Map.Entry<String, CqlType>> fields = udts.get(name);
        if (fields == null) {
            throw CqlError.invalid("Unknown type " + name);
        }
        return fields;
    }

    private static ArrayNode normalizeSet(CqlType type, ArrayNode items) {
        List<JsonNode> sorted = new ArrayList<>();
        items.forEach(sorted::add);
        sorted.sort((a, b) -> KeyspacesData.compare(type.args().get(0), a, b));
        ArrayNode out = NODES.arrayNode();
        JsonNode last = null;
        for (JsonNode item : sorted) {
            if (last == null || KeyspacesData.compare(type.args().get(0), last, item) != 0) {
                out.add(item);
            }
            last = item;
        }
        return out;
    }

    private static ArrayNode normalizeMap(CqlType type, ArrayNode pairs) {
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        for (JsonNode pair : pairs) {
            byKey.put(pair.get(0).toString(), pair);
        }
        List<JsonNode> sorted = new ArrayList<>(byKey.values());
        sorted.sort((a, b) -> KeyspacesData.compare(type.args().get(0), a.get(0), b.get(0)));
        ArrayNode out = NODES.arrayNode();
        sorted.forEach(out::add);
        return out;
    }

    private JsonNode function(Term term, CqlType type, Binds binds,
                              Map<String, List<Map.Entry<String, CqlType>>> udts) {
        String fn = term.text().toLowerCase(Locale.ROOT);
        switch (fn) {
            case "now", "currenttimeuuid" -> {
                return NODES.textNode(timeuuid(System.currentTimeMillis()).toString());
            }
            case "uuid" -> {
                return NODES.textNode(UUID.randomUUID().toString());
            }
            case "currenttimestamp" -> {
                return NODES.numberNode(System.currentTimeMillis());
            }
            case "currentdate" -> {
                return NODES.textNode(LocalDate.now(ZoneOffset.UTC).toString());
            }
            case "totimestamp", "todate", "tounixtimestamp" -> {
                JsonNode arg = value(term.items().get(0), type("timeuuid"), binds, udts);
                long millis = arg.isTextual() && arg.asText().contains("-") && arg.asText().length() == 36
                        ? (UUID.fromString(arg.asText()).timestamp() - 0x01B21DD213814000L) / 10_000 : arg.asLong();
                if ("todate".equals(fn)) {
                    return NODES.textNode(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            .toString());
                }
                return NODES.numberNode(millis);
            }
            case "mintimeuuid", "maxtimeuuid" -> {
                JsonNode arg = value(term.items().get(0), type("timestamp"), binds, udts);
                UUID base = timeuuid(arg.asLong());
                long lsb = "mintimeuuid".equals(fn) ? 0x8080808080808080L : 0x7f7f7f7f7f7f7f7fL;
                return NODES.textNode(new UUID(base.getMostSignificantBits(), lsb).toString());
            }
            default -> {
                if (fn.endsWith("asblob")) {
                    CqlType from = type(fn.substring(0, fn.length() - 6));
                    JsonNode v = value(term.items().get(0), from, binds, udts);
                    return NODES.textNode(Base64.getEncoder().encodeToString(CqlCodec.encode(from, v,
                            name -> udt(udts, name))));
                }
                if (fn.startsWith("blobas")) {
                    CqlType to = type(fn.substring(6));
                    JsonNode v = value(term.items().get(0), type("blob"), binds, udts);
                    return CqlCodec.decode(to, Base64.getDecoder().decode(v.asText()), name -> udt(udts, name));
                }
                throw CqlError.invalid("Unknown function " + term.text());
            }
        }
    }

    /** A version 1 UUID for a time, unique within this process. */
    static UUID timeuuid(long millis) {
        long ticks = millis * 10_000 + 0x01B21DD213814000L;
        long previous;
        long next;
        do {
            previous = CLOCK.get();
            next = Math.max(previous + 1, ticks);
        } while (!CLOCK.compareAndSet(previous, next));
        long msb = (next & 0xffffffffL) << 32 | (next >>> 32 & 0xffff) << 16 | 0x1000 | (next >>> 48 & 0x0fff);
        long lsb = 0x8000000000000000L | (RANDOM.nextInt(0x3fff) & 0x3fffL) << 48 | NODE;
        return new UUID(msb, lsb);
    }

    private static JsonNode literal(Term term, CqlType type) {
        String text = term.text();
        String kind = term.kind();
        try {
            switch (type.name()) {
                case "ascii", "text" -> {
                    require(kind, "string", type);
                    return NODES.textNode(text);
                }
                case "int", "smallint", "tinyint" -> {
                    require(kind, "integer", type);
                    long v = Long.parseLong(text);
                    long max = "int".equals(type.name()) ? Integer.MAX_VALUE : "smallint".equals(type.name())
                            ? Short.MAX_VALUE : Byte.MAX_VALUE;
                    if (v > max || v < -max - 1) {
                        throw CqlError.invalid("Invalid " + type.name() + " value " + text);
                    }
                    return NODES.numberNode(v);
                }
                case "bigint", "counter" -> {
                    require(kind, "integer", type);
                    return NODES.numberNode(Long.parseLong(text));
                }
                case "varint" -> {
                    require(kind, "integer", type);
                    return NODES.textNode(new BigInteger(text).toString());
                }
                case "float", "double" -> {
                    if (!"integer".equals(kind) && !"float".equals(kind)) {
                        throw CqlError.invalid("Invalid " + kind + " constant (" + text + ") for " + type.name());
                    }
                    return NODES.numberNode(Double.parseDouble(text));
                }
                case "decimal" -> {
                    if (!"integer".equals(kind) && !"float".equals(kind)) {
                        throw CqlError.invalid("Invalid " + kind + " constant (" + text + ") for decimal");
                    }
                    return NODES.textNode(new BigDecimal(text).toPlainString());
                }
                case "boolean" -> {
                    require(kind, "boolean", type);
                    return NODES.booleanNode(Boolean.parseBoolean(text));
                }
                case "uuid", "timeuuid" -> {
                    require(kind, "uuid", type);
                    UUID uuid = UUID.fromString(text);
                    if ("timeuuid".equals(type.name()) && uuid.version() != 1) {
                        throw CqlError.invalid("Unsupported UUID for timeuuid: " + text);
                    }
                    return NODES.textNode(uuid.toString());
                }
                case "blob" -> {
                    require(kind, "hex", type);
                    return NODES.textNode(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(text)));
                }
                case "timestamp" -> {
                    if ("integer".equals(kind)) {
                        return NODES.numberNode(Long.parseLong(text));
                    }
                    require(kind, "string", type);
                    return NODES.numberNode(timestamp(text));
                }
                case "date" -> {
                    if ("integer".equals(kind)) {
                        return NODES.textNode(LocalDate.ofEpochDay(Long.parseLong(text) - CqlCodec.DATE_EPOCH)
                                .toString());
                    }
                    require(kind, "string", type);
                    return NODES.textNode(LocalDate.parse(text).toString());
                }
                case "time" -> {
                    if ("integer".equals(kind)) {
                        return NODES.numberNode(Long.parseLong(text));
                    }
                    require(kind, "string", type);
                    return NODES.numberNode(LocalTime.parse(text).toNanoOfDay());
                }
                case "inet" -> {
                    require(kind, "string", type);
                    return NODES.textNode(text);
                }
                case "duration" -> {
                    if (!"duration".equals(kind) && !"string".equals(kind) && !"integer".equals(kind)) {
                        throw CqlError.invalid("Invalid duration " + text);
                    }
                    long[] parts = CqlDuration.parse(text);
                    return NODES.textNode(CqlDuration.format(parts[0], parts[1], parts[2]));
                }
                default -> throw CqlError.invalid("Invalid " + kind + " constant (" + text + ") for " + type);
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            throw CqlError.invalid("Invalid " + type.name() + " value " + text);
        }
    }

    private static void require(String kind, String wanted, CqlType type) {
        if (!kind.equals(wanted)) {
            throw CqlError.invalid("Invalid " + kind.toUpperCase(Locale.ROOT) + " constant for column of type "
                    + type);
        }
    }

    static long timestamp(String text) {
        String t = text.strip().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(t.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2")).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(t.endsWith("Z") ? t : t + "Z").toEpochMilli();
            } catch (DateTimeParseException e2) {
                return LocalDate.parse(t.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
        }
    }

    // ---------------------------------------------------------------- reads

    /** A row being read: its key values, cells and partition token. */
    private record Read(Map<String, JsonNode> values, Map<String, JsonNode> cells, long token, String partition,
                        boolean staticOnly) {
    }

    private Result select(Session session, CqlParser.Select s, Binds binds, Options options) {
        String ks = keyspaceOf(session, s.keyspace());
        CqlSystemTables.Virtual virtual = system.table(session, ks, s.table());
        if (virtual != null) {
            return selectVirtual(s, virtual, binds, options, ks);
        }
        Schema schema = schemaOf(session, s.keyspace(), s.table());
        List<Column> columns = selectColumns(s, schema.ordered(), schema.columns, ks, s.table());
        Map<String, List<JsonNode>> eq = new HashMap<>();
        List<Relation> filters = new ArrayList<>();
        Long tokenFrom = null;
        Long tokenTo = null;
        boolean tokenFromInclusive = true;
        boolean tokenToInclusive = true;
        for (Relation r : s.where()) {
            if (r.token()) {
                long token = tokenValue(r, schema, binds);
                switch (r.op()) {
                    case ">" -> {
                        tokenFrom = token;
                        tokenFromInclusive = false;
                    }
                    case ">=" -> tokenFrom = token;
                    case "<" -> {
                        tokenTo = token;
                        tokenToInclusive = false;
                    }
                    case "<=" -> tokenTo = token;
                    case "=" -> {
                        tokenFrom = token;
                        tokenTo = token;
                    }
                    default -> throw CqlError.invalid("Unsupported token relation " + r.op());
                }
                continue;
            }
            if (r.columns().size() == 1 && schema.partitionKeys.contains(r.columns().get(0))
                    && ("=".equals(r.op()) || "IN".equals(r.op()))) {
                eq.put(r.columns().get(0), relationValues(r, type(schema, r.columns().get(0)), binds, schema.udts));
                continue;
            }
            filters.add(r);
        }
        boolean fullPartition = eq.keySet().containsAll(schema.partitionKeys);
        boolean needsFiltering = !fullPartition && !filters.isEmpty() || filters.stream().anyMatch(r ->
                !r.columns().stream().allMatch(schema.clusteringKeys::contains))
                || !fullPartition && !eq.isEmpty();
        if (needsFiltering && !s.allowFiltering()) {
            throw CqlError.invalid("Cannot execute this query as it might involve data filtering and thus may "
                    + "have unpredictable performance. If you want to execute this query despite the performance "
                    + "unpredictability, use ALLOW FILTERING");
        }
        if (!s.order().isEmpty() && !fullPartition) {
            throw CqlError.invalid("ORDER BY is only supported when the partition key is restricted by an EQ or an "
                    + "IN.");
        }
        List<ObjectNode> rows;
        if (fullPartition) {
            rows = new ArrayList<>();
            for (ArrayNode pk : combinations(schema.partitionKeys, eq)) {
                rows.addAll(data.partition(schema, pk));
            }
        } else {
            rows = data.scan(schema);
        }
        List<Read> reads = merge(schema, rows);
        List<Read> kept = new ArrayList<>();
        for (Read read : reads) {
            if (tokenFrom != null && (tokenFromInclusive ? read.token() < tokenFrom : read.token() <= tokenFrom)) {
                continue;
            }
            if (tokenTo != null && (tokenToInclusive ? read.token() > tokenTo : read.token() >= tokenTo)) {
                continue;
            }
            if (!fullPartition && !eq.isEmpty() && !matchesEq(read, eq, schema)) {
                continue;
            }
            boolean ok = true;
            for (Relation r : filters) {
                if (read.staticOnly() && r.columns().stream().anyMatch(schema.clusteringKeys::contains)) {
                    ok = false;
                    break;
                }
                if (!matches(read, r, schema, binds)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                kept.add(read);
            }
        }
        boolean reversed = false;
        if (!s.order().isEmpty()) {
            CqlParser.Ordering first = s.order().get(0);
            int index = schema.clusteringKeys.indexOf(first.column());
            if (index < 0) {
                throw CqlError.invalid("Order by is currently only supported on the clustered columns of the "
                        + "PRIMARY KEY, got " + first.column());
            }
            reversed = first.descending() != schema.descending.get(index);
        }
        Comparator<Read> byPartition = Comparator.comparingLong(Read::token).thenComparing(Read::partition);
        Comparator<ObjectNode> clustering = KeyspacesData.clusteringOrder(schema);
        Map<Read, ObjectNode> back = new HashMap<>();
        for (int i = 0; i < reads.size(); i++) {
            back.put(reads.get(i), null);
        }
        boolean rev = reversed;
        kept.sort(byPartition.thenComparing((a, b) -> {
            int c = compareClustering(schema, a, b);
            return rev ? -c : c;
        }));
        if (s.distinct()) {
            List<Read> distinct = new ArrayList<>();
            String last = null;
            for (Read read : kept) {
                if (!read.partition().equals(last)) {
                    distinct.add(read);
                    last = read.partition();
                }
            }
            kept = distinct;
        }
        if (s.perPartitionLimit() != null) {
            int per = intValue(s.perPartitionLimit(), binds);
            List<Read> limited = new ArrayList<>();
            String last = null;
            int count = 0;
            for (Read read : kept) {
                count = read.partition().equals(last) ? count + 1 : 1;
                last = read.partition();
                if (count <= per) {
                    limited.add(read);
                }
            }
            kept = limited;
        }
        boolean count = s.selectors().stream().anyMatch(x -> "count".equals(x.kind()));
        if (s.limit() != null) {
            int limit = intValue(s.limit(), binds);
            if (limit <= 0) {
                throw CqlError.invalid("LIMIT must be strictly positive");
            }
            if (!count && kept.size() > limit) {
                kept = kept.subList(0, limit);
            }
        }
        if (count) {
            List<byte[]> row = new ArrayList<>();
            for (Column c : columns) {
                if ("bigint".equals(c.type().name()) && (c.name().equals("count") || s.selectors().get(
                        columns.indexOf(c)).kind().equals("count"))) {
                    row.add(CqlCodec.encode(c.type(), NODES.numberNode(kept.size()), schema::udt));
                } else {
                    row.add(kept.isEmpty() ? null : cell(schema, kept.get(0), s.selectors().get(columns.indexOf(c)),
                            c, binds));
                }
            }
            return new Rows(columns, List.of(row), null, schema.udts);
        }
        return page(columns, kept, options, read -> {
            if (s.json()) {
                return List.of(json(schema, read, s));
            }
            List<byte[]> row = new ArrayList<>();
            int i = 0;
            for (Selector selector : s.selectors()) {
                if ("star".equals(selector.kind())) {
                    for (String column : schema.ordered()) {
                        row.add(encodeValue(schema, schema.columns.get(column), valueOf(read, column)));
                    }
                } else {
                    row.add(cell(schema, read, selector, columns.get(i), binds));
                }
                i += "star".equals(selector.kind()) ? schema.ordered().size() : 1;
            }
            return row;
        }, schema.udts);
    }

    private static boolean matchesEq(Read read, Map<String, List<JsonNode>> eq, Schema schema) {
        for (Map.Entry<String, List<JsonNode>> e : eq.entrySet()) {
            CqlType t = schema.columns.get(e.getKey());
            JsonNode v = read.values().get(e.getKey());
            if (e.getValue().stream().noneMatch(w -> KeyspacesData.compare(t, v, w) == 0)) {
                return false;
            }
        }
        return true;
    }

    private interface RowEncoder {
        List<byte[]> encode(Read read);
    }

    private Rows page(List<Column> columns, List<Read> rows, Options options, RowEncoder encoder,
                      Map<String, List<Map.Entry<String, CqlType>>> udts) {
        int start = 0;
        if (options.pagingState() != null && options.pagingState().length > 0) {
            try {
                start = Integer.parseInt(new String(options.pagingState(), StandardCharsets.UTF_8));
            } catch (NumberFormatException e) {
                throw CqlError.invalid("Invalid paging state");
            }
        }
        int end = options.pageSize() > 0 ? Math.min(rows.size(), start + options.pageSize()) : rows.size();
        List<List<byte[]>> out = new ArrayList<>();
        for (Read read : rows.subList(Math.min(start, rows.size()), end)) {
            out.add(encoder.encode(read));
        }
        byte[] paging = end < rows.size() ? String.valueOf(end).getBytes(StandardCharsets.UTF_8) : null;
        return new Rows(columns, out, paging, udts);
    }

    /** Joins each partition's static cells onto its rows; a partition with only a static row reads as one row. */
    private List<Read> merge(Schema schema, List<ObjectNode> rows) {
        Map<String, ObjectNode> statics = new HashMap<>();
        Map<String, Boolean> hasRows = new HashMap<>();
        for (ObjectNode row : rows) {
            String partition = row.path("pk").toString();
            if (row.path("static").asBoolean(false)) {
                statics.put(partition, row);
            } else {
                hasRows.put(partition, true);
            }
        }
        List<Read> reads = new ArrayList<>();
        for (ObjectNode row : rows) {
            String partition = row.path("pk").toString();
            boolean isStatic = row.path("static").asBoolean(false);
            if (isStatic && (hasRows.containsKey(partition) || schema.clusteringKeys.isEmpty())) {
                continue;
            }
            Map<String, JsonNode> values = new LinkedHashMap<>();
            Map<String, JsonNode> cells = new LinkedHashMap<>();
            row.path("pk").fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue()));
            row.path("ck").fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue()));
            ObjectNode s = statics.get(partition);
            if (s != null) {
                s.path("cells").fields().forEachRemaining(e -> cells.put(e.getKey(), e.getValue()));
            }
            if (!isStatic) {
                row.path("cells").fields().forEachRemaining(e -> cells.put(e.getKey(), e.getValue()));
            }
            reads.add(new Read(values, cells, KeyspacesData.token(schema, row.path("pk")), partition, isStatic));
        }
        return reads;
    }

    private static JsonNode valueOf(Read read, String column) {
        if (read.values().containsKey(column)) {
            return read.values().get(column);
        }
        JsonNode cell = read.cells().get(column);
        return cell == null ? null : cell.get("v");
    }

    private static int compareClustering(Schema schema, Read a, Read b) {
        if (a.staticOnly() != b.staticOnly()) {
            return a.staticOnly() ? -1 : 1;
        }
        for (int i = 0; i < schema.clusteringKeys.size(); i++) {
            String column = schema.clusteringKeys.get(i);
            int c = KeyspacesData.compare(schema.columns.get(column), a.values().get(column), b.values().get(column));
            if (c != 0) {
                return schema.descending.get(i) ? -c : c;
            }
        }
        return 0;
    }

    private long tokenValue(Relation r, Schema schema, Binds binds) {
        if (!r.columns().equals(schema.partitionKeys)) {
            throw CqlError.invalid("The token function arguments must be the partition key columns in order.");
        }
        if ("token".equals(r.value().kind())) {
            byte[][] parts = new byte[schema.partitionKeys.size()][];
            for (int i = 0; i < parts.length; i++) {
                CqlType t = schema.columns.get(schema.partitionKeys.get(i));
                parts[i] = CqlCodec.encode(t, value(r.value().items().get(i), t, binds, schema.udts), schema::udt);
            }
            return CqlMurmur3.token(parts);
        }
        return value(r.value(), type("bigint"), binds, schema.udts).asLong();
    }

    private List<JsonNode> relationValues(Relation r, CqlType type, Binds binds,
                                          Map<String, List<Map.Entry<String, CqlType>>> udts) {
        List<JsonNode> values = new ArrayList<>();
        if ("IN".equals(r.op())) {
            if (r.value() != null) {
                JsonNode list = value(r.value(), new CqlType("list", List.of(type), false), binds, udts);
                list.forEach(values::add);
            } else {
                for (Term t : r.in()) {
                    values.add(value(t, type, binds, udts));
                }
            }
        } else {
            values.add(value(r.value(), type, binds, udts));
        }
        for (JsonNode v : values) {
            if (v == null || v.isNull() || v == UNSET) {
                throw CqlError.invalid("Invalid null value in condition for column " + r.columns().get(0));
            }
        }
        return values;
    }

    private static List<ArrayNode> combinations(List<String> columns, Map<String, List<JsonNode>> values) {
        List<ArrayNode> out = new ArrayList<>();
        out.add(NODES.arrayNode());
        for (String column : columns) {
            List<ArrayNode> next = new ArrayList<>();
            for (ArrayNode prefix : out) {
                for (JsonNode v : values.get(column)) {
                    ArrayNode copy = prefix.deepCopy();
                    copy.add(v);
                    next.add(copy);
                }
            }
            out = next;
        }
        return out;
    }

    private boolean matches(Read read, Relation r, Schema schema, Binds binds) {
        if (r.columns().size() > 1) {
            List<CqlType> types = new ArrayList<>();
            ArrayNode actual = NODES.arrayNode();
            for (String c : r.columns()) {
                types.add(type(schema, c));
                actual.add(valueOf(read, c));
            }
            CqlType tuple = new CqlType("tuple", types, true);
            List<JsonNode> wanted = new ArrayList<>();
            if ("IN".equals(r.op())) {
                if (r.value() != null) {
                    value(r.value(), new CqlType("list", List.of(tuple), false), binds, schema.udts)
                            .forEach(wanted::add);
                } else {
                    for (Term t : r.in()) {
                        wanted.add(value(t, tuple, binds, schema.udts));
                    }
                }
                return wanted.stream().anyMatch(w -> compareTuple(schema, r.columns(), actual, w) == 0);
            }
            int c = compareTuple(schema, r.columns(), actual, value(r.value(), tuple, binds, schema.udts));
            return compareOp(c, r.op());
        }
        String column = r.columns().get(0);
        CqlType type = type(schema, column);
        JsonNode actual = valueOf(read, column);
        switch (r.op()) {
            case "IN" -> {
                return relationValues(r, type, binds, schema.udts).stream().anyMatch(w ->
                        KeyspacesData.compare(type, actual, w) == 0);
            }
            case "CONTAINS" -> {
                if (actual == null) {
                    return false;
                }
                CqlType element = "map".equals(type.name()) ? type.args().get(1) : type.args().get(0);
                JsonNode wanted = value(r.value(), element, binds, schema.udts);
                for (JsonNode item : actual) {
                    JsonNode v = "map".equals(type.name()) ? item.get(1) : item;
                    if (KeyspacesData.compare(element, v, wanted) == 0) {
                        return true;
                    }
                }
                return false;
            }
            case "CONTAINS KEY" -> {
                if (actual == null) {
                    return false;
                }
                JsonNode wanted = value(r.value(), type.args().get(0), binds, schema.udts);
                for (JsonNode item : actual) {
                    if (KeyspacesData.compare(type.args().get(0), item.get(0), wanted) == 0) {
                        return true;
                    }
                }
                return false;
            }
            default -> {
                if (actual == null || actual.isNull()) {
                    return false;
                }
                int c = KeyspacesData.compare(type, actual, value(r.value(), type, binds, schema.udts));
                return compareOp(c, r.op());
            }
        }
    }

    private static int compareTuple(Schema schema, List<String> columns, JsonNode actual, JsonNode wanted) {
        for (int i = 0; i < columns.size(); i++) {
            int index = schema.clusteringKeys.indexOf(columns.get(i));
            int c = KeyspacesData.compare(schema.columns.get(columns.get(i)), actual.get(i), wanted.get(i));
            if (c != 0) {
                return index >= 0 && schema.descending.get(index) ? -c : c;
            }
        }
        return 0;
    }

    private static boolean compareOp(int c, String op) {
        return switch (op) {
            case "=" -> c == 0;
            case "!=" -> c != 0;
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            default -> throw CqlError.invalid("Unsupported operator " + op);
        };
    }

    private int intValue(Term term, Binds binds) {
        return value(term, type("int"), binds, Map.of()).asInt();
    }

    private byte[] encodeValue(Schema schema, CqlType type, JsonNode value) {
        return CqlCodec.encode(type, value, schema::udt);
    }

    private byte[] cell(Schema schema, Read read, Selector selector, Column column, Binds binds) {
        switch (selector.kind()) {
            case "writetime" -> {
                JsonNode cell = read.cells().get(selector.column());
                if (schema.isKey(selector.column())) {
                    throw CqlError.invalid("Cannot use selection function writeTime on PRIMARY KEY part "
                            + selector.column());
                }
                return cell == null ? null : CqlCodec.encode(column.type(), cell.get("wt"), schema::udt);
            }
            case "ttl" -> {
                JsonNode cell = read.cells().get(selector.column());
                if (schema.isKey(selector.column())) {
                    throw CqlError.invalid("Cannot use selection function ttl on PRIMARY KEY part "
                            + selector.column());
                }
                if (cell == null || !cell.hasNonNull("exp")) {
                    return null;
                }
                long left = cell.get("exp").asLong() - System.currentTimeMillis() / 1000;
                return CqlCodec.encode(column.type(), NODES.numberNode(Math.max(0, left)), schema::udt);
            }
            case "function" -> {
                return CqlCodec.encode(column.type(), selectFunction(schema, read, selector), schema::udt);
            }
            default -> {
                return CqlCodec.encode(column.type(), valueOf(read, selector.column()), schema::udt);
            }
        }
    }

    private JsonNode selectFunction(Schema schema, Read read, Selector selector) {
        String fn = selector.column();
        List<Selector> args = selector.args();
        return switch (fn) {
            case "token" -> NODES.numberNode(read.token());
            case "now", "currenttimeuuid" -> NODES.textNode(timeuuid(System.currentTimeMillis()).toString());
            case "uuid" -> NODES.textNode(UUID.randomUUID().toString());
            case "currenttimestamp" -> NODES.numberNode(System.currentTimeMillis());
            case "currentdate" -> NODES.textNode(LocalDate.now(ZoneOffset.UTC).toString());
            case "tojson" -> {
                CqlType t = schema.columns.get(args.get(0).column());
                yield NODES.textNode(toJson(schema, t, valueOf(read, args.get(0).column())).toString());
            }
            case "totimestamp", "dateof", "tounixtimestamp", "unixtimestampof", "todate" -> {
                JsonNode v = "function".equals(args.get(0).kind()) ? selectFunction(schema, read, args.get(0))
                        : valueOf(read, args.get(0).column());
                if (v == null || v.isNull()) {
                    yield NODES.nullNode();
                }
                long millis = v.isTextual() && v.asText().length() == 36
                        ? (UUID.fromString(v.asText()).timestamp() - 0x01B21DD213814000L) / 10_000
                        : v.isTextual() ? LocalDate.parse(v.asText()).atStartOfDay(ZoneOffset.UTC).toInstant()
                        .toEpochMilli() : v.asLong();
                yield "todate".equals(fn) ? NODES.textNode(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
                        .toLocalDate().toString()) : NODES.numberNode(millis);
            }
            default -> {
                if (fn.endsWith("asblob")) {
                    CqlType from = type(fn.substring(0, fn.length() - 6));
                    yield NODES.textNode(Base64.getEncoder().encodeToString(CqlCodec.encode(from,
                            valueOf(read, args.get(0).column()), schema::udt)));
                }
                if (fn.startsWith("blobas")) {
                    JsonNode v = valueOf(read, args.get(0).column());
                    yield v == null ? NODES.nullNode() : CqlCodec.decode(type(fn.substring(6)),
                            Base64.getDecoder().decode(v.asText()), schema::udt);
                }
                throw CqlError.invalid("Unknown function " + fn);
            }
        };
    }

    /** A stored value as CQL's JSON representation. */
    static JsonNode toJson(Schema schema, CqlType type, JsonNode value) {
        if (value == null || value.isNull()) {
            return NODES.nullNode();
        }
        switch (type.name()) {
            case "blob" -> {
                return NODES.textNode("0x" + HexFormat.of().formatHex(Base64.getDecoder().decode(value.asText())));
            }
            case "timestamp" -> {
                return NODES.textNode(Instant.ofEpochMilli(value.asLong()).toString().replace("T", " ")
                        .replace("Z", "Z"));
            }
            case "time" -> {
                return NODES.textNode(LocalTime.ofNanoOfDay(value.asLong()).toString());
            }
            case "varint" -> {
                return NODES.numberNode(new BigInteger(value.asText()));
            }
            case "decimal" -> {
                return NODES.numberNode(new BigDecimal(value.asText()));
            }
            case "list", "set", "tuple" -> {
                ArrayNode out = NODES.arrayNode();
                for (int i = 0; i < value.size(); i++) {
                    out.add(toJson(schema, "tuple".equals(type.name()) ? type.args().get(i) : type.args().get(0),
                            value.get(i)));
                }
                return out;
            }
            case "map" -> {
                ObjectNode out = NODES.objectNode();
                for (JsonNode pair : value) {
                    JsonNode key = toJson(schema, type.args().get(0), pair.get(0));
                    out.set(key.isTextual() ? key.asText() : key.toString(), toJson(schema, type.args().get(1),
                            pair.get(1)));
                }
                return out;
            }
            default -> {
                if (type.isUdt()) {
                    ObjectNode out = NODES.objectNode();
                    for (Map.Entry<String, CqlType> field : schema.udt(type.name())) {
                        out.set(field.getKey(), toJson(schema, field.getValue(), value.get(field.getKey())));
                    }
                    return out;
                }
                return value;
            }
        }
    }

    private byte[] json(Schema schema, Read read, CqlParser.Select s) {
        ObjectNode out = NODES.objectNode();
        for (Selector selector : s.selectors()) {
            if ("star".equals(selector.kind())) {
                for (String column : schema.ordered()) {
                    out.set(column, toJson(schema, schema.columns.get(column), valueOf(read, column)));
                }
            } else if ("column".equals(selector.kind())) {
                out.set(selector.alias() != null ? selector.alias() : selector.column(), toJson(schema,
                        schema.columns.get(selector.column()), valueOf(read, selector.column())));
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Result selectVirtual(CqlParser.Select s, CqlSystemTables.Virtual virtual, Binds binds, Options options,
                                 String ks) {
        List<Column> columns = selectColumns(s, new ArrayList<>(virtual.columns().keySet()), virtual.columns(), ks,
                s.table());
        List<Map<String, JsonNode>> rows = new ArrayList<>();
        for (Map<String, JsonNode> row : virtual.rows()) {
            boolean ok = true;
            for (Relation r : s.where()) {
                String column = r.columns().get(0);
                CqlType type = virtual.columns().get(column);
                if (type == null) {
                    throw CqlError.invalid("Undefined column name " + column);
                }
                List<JsonNode> wanted = relationValues(r, type, binds, Map.of());
                JsonNode actual = row.get(column);
                if ("=".equals(r.op()) || "IN".equals(r.op())) {
                    ok &= wanted.stream().anyMatch(w -> KeyspacesData.compare(type, actual, w) == 0);
                } else {
                    ok &= compareOp(KeyspacesData.compare(type, actual, wanted.get(0)), r.op());
                }
            }
            if (ok) {
                rows.add(row);
            }
        }
        if (s.limit() != null) {
            int limit = intValue(s.limit(), binds);
            if (rows.size() > limit) {
                rows = rows.subList(0, limit);
            }
        }
        if (s.selectors().stream().anyMatch(x -> "count".equals(x.kind()))) {
            return new Rows(columns, List.of(List.of(CqlCodec.encode(type("bigint"), NODES.numberNode(rows.size()),
                    n -> List.of()))), null, Map.of());
        }
        List<List<byte[]>> out = new ArrayList<>();
        for (Map<String, JsonNode> row : rows) {
            List<byte[]> encoded = new ArrayList<>();
            for (Selector selector : s.selectors()) {
                if ("star".equals(selector.kind())) {
                    virtual.columns().forEach((name, type) -> encoded.add(CqlCodec.encode(type, row.get(name),
                            n -> List.of())));
                } else {
                    CqlType type = virtual.columns().get(selector.column());
                    encoded.add(CqlCodec.encode(type, row.get(selector.column()), n -> List.of()));
                }
            }
            out.add(encoded);
        }
        int start = 0;
        if (options.pagingState() != null && options.pagingState().length > 0) {
            start = Integer.parseInt(new String(options.pagingState(), StandardCharsets.UTF_8));
        }
        int end = options.pageSize() > 0 ? Math.min(out.size(), start + options.pageSize()) : out.size();
        byte[] paging = end < out.size() ? String.valueOf(end).getBytes(StandardCharsets.UTF_8) : null;
        return new Rows(columns, out.subList(Math.min(start, out.size()), end), paging, Map.of());
    }

    // ---------------------------------------------------------------- writes

    /** One write of a statement: the row it targets and what it does to it. */
    private record Write(Schema schema, ArrayNode pk, ArrayNode ck, Statement statement, long timestamp,
                         Integer ttl, Binds binds) {
    }

    private Result write(Session session, List<Statement> statements, Binds binds, Options options,
                         Term batchTimestamp) {
        List<Binds> each = new ArrayList<>();
        statements.forEach(st -> each.add(binds));
        return write(session, statements, each, options, batchTimestamp);
    }

    /** Runs a batch whose statements each have their own bound values, as a protocol BATCH sends them. */
    Result batch(Session session, List<Statement> statements, List<Binds> binds, Options options) {
        writeConsistency(options);
        if (statements.size() > MAX_BATCH) {
            throw CqlError.invalid("A batch can have at most " + MAX_BATCH + " statements.");
        }
        try {
            return write(session, statements, binds, options, null);
        } catch (AwsException e) {
            throw toCql(e);
        } catch (IllegalArgumentException e) {
            throw CqlError.invalid(e.getMessage());
        }
    }

    private Result write(Session session, List<Statement> statements, List<Binds> bindsList, Options options,
                         Term batchTimestamp) {
        List<Write> writes = new ArrayList<>();
        boolean conditional = false;
        for (int index = 0; index < statements.size(); index++) {
            Statement statement = statements.get(index);
            writes.addAll(plan(session, statement, bindsList.get(index), options, batchTimestamp));
            conditional |= statement instanceof CqlParser.Insert i && i.ifNotExists()
                    || statement instanceof CqlParser.Update u && (u.ifExists() || !u.conditions().isEmpty())
                    || statement instanceof CqlParser.Delete d && (d.ifExists() || !d.conditions().isEmpty());
        }
        if (conditional) {
            if (statements.size() > 1) {
                Set<String> partitions = new LinkedHashSet<>();
                writes.forEach(w -> partitions.add(w.schema().table + w.pk().toString()));
                if (partitions.size() > 1) {
                    throw CqlError.invalid("Batch with conditions cannot span multiple tables or partitions");
                }
            }
            for (Write w : writes) {
                ObjectNode current = w.ck() == null && !(w.statement() instanceof CqlParser.Delete)
                        ? data.read(w.schema(), KeyspacesData.rowKey(w.schema(), w.pk(), null))
                        : w.ck() == null ? firstRow(w) : data.read(w.schema(), KeyspacesData.rowKey(w.schema(), w.pk(),
                        w.ck()));
                if (!condition(w, current, w.binds())) {
                    return notApplied(w, current);
                }
            }
        }
        for (Write w : writes) {
            apply(w, w.binds());
        }
        if (conditional) {
            Write w = writes.get(0);
            return new Rows(List.of(new Column(w.schema().keyspace, w.schema().table, "[applied]", type("boolean"))),
                    List.of(List.of(new byte[] {1})), null, Map.of());
        }
        return new VoidResult();
    }

    private ObjectNode firstRow(Write w) {
        List<ObjectNode> rows = data.partition(w.schema(), w.pk());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Result notApplied(Write w, ObjectNode current) {
        Schema schema = w.schema();
        List<Column> columns = new ArrayList<>();
        columns.add(new Column(schema.keyspace, schema.table, "[applied]", type("boolean")));
        List<byte[]> row = new ArrayList<>();
        row.add(new byte[] {0});
        if (current != null) {
            long now = System.currentTimeMillis() / 1000;
            for (String column : schema.ordered()) {
                JsonNode v = current.path("pk").has(column) ? current.path("pk").get(column)
                        : current.path("ck").has(column) ? current.path("ck").get(column)
                        : KeyspacesData.live(current.path("cells").get(column), now)
                        ? current.path("cells").get(column).get("v") : null;
                columns.add(new Column(schema.keyspace, schema.table, column, schema.columns.get(column)));
                row.add(CqlCodec.encode(schema.columns.get(column), v, schema::udt));
            }
        }
        return new Rows(columns, List.of(row), null, schema.udts);
    }

    private boolean condition(Write w, ObjectNode current, Binds binds) {
        long now = System.currentTimeMillis() / 1000;
        boolean exists = KeyspacesData.exists(current, now);
        return switch (w.statement()) {
            case CqlParser.Insert i -> !exists;
            case CqlParser.Update u -> u.ifExists() ? exists : conditions(w.schema(), u.conditions(), current, binds);
            case CqlParser.Delete d -> d.ifExists() ? exists : conditions(w.schema(), d.conditions(), current, binds);
            default -> true;
        };
    }

    private boolean conditions(Schema schema, List<Condition> conditions, ObjectNode current, Binds binds) {
        long now = System.currentTimeMillis() / 1000;
        for (Condition c : conditions) {
            CqlType type = type(schema, c.column());
            JsonNode cell = current == null ? null : current.path("cells").get(c.column());
            JsonNode actual = KeyspacesData.live(cell, now) ? cell.get("v") : null;
            CqlType valueType = type;
            if (c.key() != null && actual != null) {
                JsonNode key = value(c.key(), "list".equals(type.name()) ? type("int") : type.args().get(0), binds,
                        schema.udts);
                valueType = "map".equals(type.name()) ? type.args().get(1) : type.args().get(0);
                JsonNode found = null;
                if ("list".equals(type.name())) {
                    found = actual.get(key.asInt());
                } else {
                    for (JsonNode pair : actual) {
                        if (KeyspacesData.compare(type.args().get(0), pair.get(0), key) == 0) {
                            found = pair.get(1);
                        }
                    }
                }
                actual = found;
            }
            CqlType t = valueType;
            JsonNode a = actual;
            if ("IN".equals(c.op())) {
                boolean any = false;
                for (Term term : c.in()) {
                    JsonNode v = value(term, t, binds, schema.udts);
                    any |= (v.isNull() && a == null) || a != null && !v.isNull() && KeyspacesData.compare(t, a, v)
                            == 0;
                }
                if (!any) {
                    return false;
                }
                continue;
            }
            JsonNode wanted = value(c.value(), t, binds, schema.udts);
            if (wanted.isNull() || a == null) {
                boolean same = wanted.isNull() == (a == null);
                if ("=".equals(c.op()) ? !same : "!=".equals(c.op()) ? same : true) {
                    return false;
                }
                continue;
            }
            if (!compareOp(KeyspacesData.compare(t, a, wanted), c.op())) {
                return false;
            }
        }
        return true;
    }

    private List<Write> plan(Session session, Statement statement, Binds binds, Options options,
                             Term batchTimestamp) {
        String keyspace;
        String table;
        Term ttlTerm;
        Term tsTerm;
        List<Relation> where;
        switch (statement) {
            case CqlParser.Insert i -> {
                keyspace = i.keyspace();
                table = i.table();
                ttlTerm = i.ttl();
                tsTerm = i.timestamp();
                where = null;
            }
            case CqlParser.Update u -> {
                keyspace = u.keyspace();
                table = u.table();
                ttlTerm = u.ttl();
                tsTerm = u.timestamp();
                where = u.where();
            }
            case CqlParser.Delete d -> {
                keyspace = d.keyspace();
                table = d.table();
                ttlTerm = null;
                tsTerm = d.timestamp();
                where = d.where();
            }
            default -> throw CqlError.invalid("Unsupported statement in a write.");
        }
        Schema schema = schemaOf(session, keyspace, table);
        Term timestampTerm = tsTerm != null ? tsTerm : batchTimestamp;
        long timestamp;
        if (timestampTerm != null && !schema.clientTimestamps()) {
            throw CqlError.invalid("USING TIMESTAMP needs client-side timestamps enabled on table "
                    + schema.keyspace + "." + schema.table + ".");
        }
        // A driver's default timestamp only counts when the table takes client-side timestamps.
        if (schema.clientTimestamps() && (timestampTerm != null || options.timestamp() != null)) {
            timestamp = timestampTerm != null ? value(timestampTerm, type("bigint"), binds, schema.udts).asLong()
                    : options.timestamp();
        } else {
            timestamp = Math.max(System.currentTimeMillis() * 1000, CLOCK.get() / 10 + 1);
        }
        Integer ttl = null;
        if (ttlTerm != null) {
            ttl = value(ttlTerm, type("int"), binds, schema.udts).asInt();
            if (ttl < 0) {
                throw CqlError.invalid("A TTL must be greater or equal to 0, but was " + ttl);
            }
            if (ttl > 630_720_000) {
                throw CqlError.invalid("ttl is too large. requested (" + ttl + ") maximum (630720000)");
            }
        }
        if (ttl != null && ttl > 0 && !schema.ttlEnabled()) {
            throw CqlError.invalid("TTL is not enabled on table " + schema.keyspace + "." + schema.table + ".");
        }
        if (ttl == null && schema.defaultTtl() > 0) {
            ttl = schema.defaultTtl();
        }
        Map<String, List<JsonNode>> keys = new LinkedHashMap<>();
        if (statement instanceof CqlParser.Insert i) {
            Map<String, JsonNode> values = insertValues(schema, i, binds);
            for (String column : schema.partitionKeys) {
                keys.put(column, List.of(keyValue(schema, column, values.get(column))));
            }
            for (String column : schema.clusteringKeys) {
                keys.put(column, List.of(keyValue(schema, column, values.get(column))));
            }
        } else {
            for (Relation r : where) {
                if (r.token() || r.columns().size() != 1) {
                    throw CqlError.invalid("Only EQ and IN relations on the primary key are supported in writes.");
                }
                String column = r.columns().get(0);
                if (!schema.isKey(column)) {
                    throw CqlError.invalid("Non PRIMARY KEY columns found in where clause: " + column);
                }
                if (!"=".equals(r.op()) && !"IN".equals(r.op())) {
                    if (statement instanceof CqlParser.Delete && schema.clusteringKeys.contains(column)) {
                        keys.computeIfAbsent("\u0000range", k -> new ArrayList<>());
                        continue;
                    }
                    throw CqlError.invalid("Invalid operator " + r.op() + " for PRIMARY KEY part " + column);
                }
                keys.put(column, relationValues(r, type(schema, column), binds, schema.udts));
            }
            for (String column : schema.partitionKeys) {
                if (!keys.containsKey(column)) {
                    throw CqlError.invalid("Some partition key parts are missing: " + column);
                }
            }
        }
        boolean staticOnly = statement instanceof CqlParser.Update u && !u.assignments().isEmpty()
                && u.assignments().stream().allMatch(a -> schema.statics.contains(a.column()));
        boolean wholeRowsDelete = statement instanceof CqlParser.Delete d && d.targets().isEmpty();
        boolean clusteringGiven = schema.clusteringKeys.stream().allMatch(keys::containsKey);
        if (!clusteringGiven && !staticOnly && !wholeRowsDelete && !schema.clusteringKeys.isEmpty()) {
            if (statement instanceof CqlParser.Delete d && d.targets().stream().allMatch(a ->
                    schema.statics.contains(a.column()))) {
                staticOnly = true;
            } else {
                throw CqlError.invalid("Some clustering keys are missing: " + String.join(", ",
                        schema.clusteringKeys.stream().filter(c -> !keys.containsKey(c)).toList()));
            }
        }
        List<Write> writes = new ArrayList<>();
        Map<String, List<JsonNode>> pkValues = new LinkedHashMap<>();
        schema.partitionKeys.forEach(c -> pkValues.put(c, keys.get(c)));
        for (ArrayNode pk : combinations(schema.partitionKeys, pkValues)) {
            if (schema.clusteringKeys.isEmpty()) {
                writes.add(new Write(schema, pk, NODES.arrayNode(), statement, timestamp, ttl, binds));
            } else if (staticOnly || wholeRowsDelete && !clusteringGiven) {
                writes.add(new Write(schema, pk, null, statement, timestamp, ttl, binds));
            } else {
                Map<String, List<JsonNode>> ckValues = new LinkedHashMap<>();
                schema.clusteringKeys.forEach(c -> ckValues.put(c, keys.get(c)));
                for (ArrayNode ck : combinations(schema.clusteringKeys, ckValues)) {
                    writes.add(new Write(schema, pk, ck, statement, timestamp, ttl, binds));
                }
            }
        }
        return writes;
    }

    private JsonNode keyValue(Schema schema, String column, JsonNode value) {
        if (value == null || value.isNull() || value == UNSET) {
            throw CqlError.invalid("Invalid null value in condition for column " + column);
        }
        return value;
    }

    private Map<String, JsonNode> insertValues(Schema schema, CqlParser.Insert i, Binds binds) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        if (i.json() != null) {
            JsonNode json;
            try {
                json = mapper().readTree(value(i.json(), type("text"), binds, schema.udts).asText());
            } catch (IOException e) {
                throw CqlError.invalid("Could not decode JSON string: " + e.getMessage());
            }
            for (String column : schema.ordered()) {
                JsonNode v = json.get(column);
                values.put(column, v == null ? NODES.nullNode() : fromJson(schema, schema.columns.get(column), v));
            }
            return values;
        }
        for (int k = 0; k < i.columns().size(); k++) {
            String column = i.columns().get(k);
            values.put(column, value(i.values().get(k), type(schema, column), binds, schema.udts));
        }
        if ("counter".equals(schema.columns.values().stream().filter(t -> "counter".equals(t.name())).findAny()
                .map(CqlType::name).orElse(""))) {
            throw CqlError.invalid("INSERT statements are not allowed on counter tables, use UPDATE instead");
        }
        return values;
    }

    /** A JSON value (as {@code INSERT JSON} gives it) as a stored value. */
    static JsonNode fromJson(Schema schema, CqlType type, JsonNode v) {
        if (v == null || v.isNull()) {
            return NODES.nullNode();
        }
        switch (type.name()) {
            case "blob" -> {
                String hex = v.asText();
                return NODES.textNode(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(
                        hex.startsWith("0x") ? hex.substring(2) : hex)));
            }
            case "timestamp" -> {
                return NODES.numberNode(v.isNumber() ? v.asLong() : timestamp(v.asText()));
            }
            case "time" -> {
                return NODES.numberNode(v.isNumber() ? v.asLong() : LocalTime.parse(v.asText()).toNanoOfDay());
            }
            case "varint", "decimal", "uuid", "timeuuid", "inet", "date", "duration", "text", "ascii" -> {
                return NODES.textNode(v.asText());
            }
            case "list", "set", "tuple" -> {
                ArrayNode out = NODES.arrayNode();
                for (int i = 0; i < v.size(); i++) {
                    out.add(fromJson(schema, "tuple".equals(type.name()) ? type.args().get(i) : type.args().get(0),
                            v.get(i)));
                }
                return "set".equals(type.name()) ? normalizeSet(type, out) : out;
            }
            case "map" -> {
                ArrayNode out = NODES.arrayNode();
                v.fields().forEachRemaining(e -> {
                    ArrayNode pair = out.addArray();
                    pair.add(fromJson(schema, type.args().get(0), NODES.textNode(e.getKey()).asText().matches(
                            "-?\\d+(\\.\\d+)?") && !"text".equals(type.args().get(0).name())
                            ? CqlCodec.mapper().getNodeFactory().numberNode(new BigDecimal(e.getKey()))
                            : NODES.textNode(e.getKey())));
                    pair.add(fromJson(schema, type.args().get(1), e.getValue()));
                });
                return normalizeMap(type, out);
            }
            default -> {
                if (type.isUdt()) {
                    ObjectNode out = NODES.objectNode();
                    for (Map.Entry<String, CqlType> field : schema.udt(type.name())) {
                        out.set(field.getKey(), fromJson(schema, field.getValue(), v.get(field.getKey())));
                    }
                    return out;
                }
                return v;
            }
        }
    }

    private void apply(Write w, Binds binds) {
        Schema schema = w.schema();
        long ts = w.timestamp();
        Long exp = w.ttl() != null && w.ttl() > 0 ? System.currentTimeMillis() / 1000 + w.ttl() : null;
        switch (w.statement()) {
            case CqlParser.Insert i -> {
                Map<String, JsonNode> values = insertValues(schema, i, binds);
                data.mutate(schema, w.pk(), w.ck(), row -> {
                    ObjectNode marker = row.has("marker") ? (ObjectNode) row.get("marker") : null;
                    if (marker == null || marker.path("wt").asLong() <= ts) {
                        ObjectNode m = row.putObject("marker").put("wt", ts);
                        if (exp != null) {
                            m.put("exp", exp);
                        }
                    }
                    values.forEach((column, value) -> {
                        if (!schema.isKey(column) && value != UNSET) {
                            setCell(row, schema, column, value, ts, exp);
                        }
                    });
                    return row;
                });
                if (w.ck() != null && !schema.statics.isEmpty() && values.keySet().stream().anyMatch(
                        schema.statics::contains)) {
                    data.mutate(schema, w.pk(), null, row -> {
                        values.forEach((column, value) -> {
                            if (schema.statics.contains(column) && value != UNSET) {
                                setCell(row, schema, column, value, ts, exp);
                            }
                        });
                        return row;
                    });
                }
            }
            case CqlParser.Update u -> {
                List<Assignment> regular = u.assignments().stream().filter(a -> !schema.statics.contains(
                        a.column())).toList();
                List<Assignment> statics = u.assignments().stream().filter(a -> schema.statics.contains(
                        a.column())).toList();
                for (Assignment a : u.assignments()) {
                    if (schema.isKey(a.column())) {
                        throw CqlError.invalid("PRIMARY KEY part " + a.column() + " found in SET part");
                    }
                }
                if (!regular.isEmpty() && w.ck() != null) {
                    data.mutate(schema, w.pk(), w.ck(), row -> {
                        regular.forEach(a -> assign(row, schema, a, binds, ts, exp));
                        return row;
                    });
                }
                if (!statics.isEmpty()) {
                    data.mutate(schema, w.pk(), schema.clusteringKeys.isEmpty() ? w.ck() : null, row -> {
                        statics.forEach(a -> assign(row, schema, a, binds, ts, exp));
                        return row;
                    });
                }
            }
            case CqlParser.Delete d -> {
                if (d.targets().isEmpty()) {
                    if (w.ck() == null) {
                        List<ObjectNode> rows = data.partition(schema, w.pk());
                        for (ObjectNode row : rows) {
                            ArrayNode ck = row.path("static").asBoolean(false) ? null : ckOf(schema, row);
                            if (ck != null && !inRange(schema, row, d.where(), binds)) {
                                continue;
                            }
                            data.mutate(schema, w.pk(), ck, r -> null);
                        }
                    } else {
                        data.mutate(schema, w.pk(), w.ck(), r -> null);
                    }
                    return;
                }
                ArrayNode ck = d.targets().stream().allMatch(a -> schema.statics.contains(a.column()))
                        && !schema.clusteringKeys.isEmpty() ? null : w.ck();
                data.mutate(schema, w.pk(), ck, row -> {
                    for (Assignment a : d.targets()) {
                        if (schema.isKey(a.column())) {
                            throw CqlError.invalid("Invalid identifier " + a.column() + " for deletion (should not "
                                    + "be a PRIMARY KEY part)");
                        }
                        ObjectNode cells = (ObjectNode) row.get("cells");
                        JsonNode cell = cells.get(a.column());
                        if (cell != null && cell.path("wt").asLong() > ts) {
                            continue;
                        }
                        if (a.key() == null && a.field() == null) {
                            cells.remove(a.column());
                            continue;
                        }
                        if (cell == null) {
                            continue;
                        }
                        CqlType type = type(schema, a.column());
                        ObjectNode updated = (ObjectNode) cell.deepCopy();
                        if (a.field() != null) {
                            ((ObjectNode) updated.get("v")).putNull(a.field());
                        } else if ("list".equals(type.name())) {
                            int index = value(a.key(), type("int"), binds, schema.udts).asInt();
                            ArrayNode list = (ArrayNode) updated.get("v");
                            if (index < 0 || index >= list.size()) {
                                throw CqlError.invalid("Attempted to delete an element from a list which a size of "
                                        + list.size());
                            }
                            list.remove(index);
                        } else {
                            JsonNode key = value(a.key(), type.args().get(0), binds, schema.udts);
                            ArrayNode remaining = NODES.arrayNode();
                            for (JsonNode item : updated.get("v")) {
                                JsonNode k = "map".equals(type.name()) ? item.get(0) : item;
                                if (KeyspacesData.compare(type.args().get(0), k, key) != 0) {
                                    remaining.add(item);
                                }
                            }
                            updated.set("v", remaining);
                        }
                        updated.put("wt", ts);
                        cells.set(a.column(), updated);
                    }
                    return row;
                });
            }
            default -> {
            }
        }
    }

    private static ArrayNode ckOf(Schema schema, ObjectNode row) {
        ArrayNode ck = NODES.arrayNode();
        schema.clusteringKeys.forEach(c -> ck.add(row.path("ck").get(c)));
        return ck;
    }

    private boolean inRange(Schema schema, ObjectNode row, List<Relation> where, Binds binds) {
        for (Relation r : where) {
            String column = r.columns().get(0);
            if (!schema.clusteringKeys.contains(column)) {
                continue;
            }
            CqlType type = type(schema, column);
            JsonNode actual = row.path("ck").get(column);
            if ("IN".equals(r.op())) {
                if (relationValues(r, type, binds, schema.udts).stream().noneMatch(v -> KeyspacesData.compare(type,
                        actual, v) == 0)) {
                    return false;
                }
            } else if (!compareOp(KeyspacesData.compare(type, actual, value(r.value(), type, binds, schema.udts)),
                    r.op())) {
                return false;
            }
        }
        return true;
    }

    private static void setCell(ObjectNode row, Schema schema, String column, JsonNode value, long ts, Long exp) {
        ObjectNode cells = (ObjectNode) row.get("cells");
        JsonNode existing = cells.get(column);
        if (existing != null && existing.path("wt").asLong() > ts) {
            return;
        }
        if (value.isNull() || value.isArray() && value.isEmpty() && schema.columns.get(column).isCollection()) {
            cells.remove(column);
            return;
        }
        ObjectNode cell = cells.putObject(column);
        cell.set("v", value);
        cell.put("wt", ts);
        if (exp != null) {
            cell.put("exp", exp);
        }
    }

    private void assign(ObjectNode row, Schema schema, Assignment a, Binds binds, long ts, Long exp) {
        CqlType type = type(schema, a.column());
        ObjectNode cells = (ObjectNode) row.get("cells");
        JsonNode cell = cells.get(a.column());
        long now = System.currentTimeMillis() / 1000;
        JsonNode current = KeyspacesData.live(cell, now) ? cell.get("v") : null;
        if ("counter".equals(type.name())) {
            if ("=".equals(a.op())) {
                throw CqlError.invalid("Cannot set the value of counter column " + a.column() + " (counters can only "
                        + "be incremented/decremented, not set)");
            }
            long delta = value(a.value(), type("bigint"), binds, schema.udts).asLong();
            long base = current == null ? 0 : current.asLong();
            ObjectNode c = cells.putObject(a.column());
            c.put("v", "+".equals(a.op()) ? base + delta : base - delta).put("wt", ts);
            return;
        }
        if (a.field() != null) {
            List<Map.Entry<String, CqlType>> fields = schema.udt(type.name());
            CqlType fieldType = fields.stream().filter(f -> f.getKey().equals(a.field())).findFirst()
                    .map(Map.Entry::getValue).orElseThrow(() -> CqlError.invalid("Unknown field " + a.field()));
            ObjectNode udt = current == null ? NODES.objectNode() : (ObjectNode) current.deepCopy();
            if (current == null) {
                fields.forEach(f -> udt.putNull(f.getKey()));
            }
            udt.set(a.field(), value(a.value(), fieldType, binds, schema.udts));
            setCell(row, schema, a.column(), udt, ts, exp);
            return;
        }
        if (a.key() != null) {
            if ("list".equals(type.name())) {
                int index = value(a.key(), type("int"), binds, schema.udts).asInt();
                if (current == null || index < 0 || index >= current.size()) {
                    throw CqlError.invalid("List index " + index + " out of bound, list has size "
                            + (current == null ? 0 : current.size()));
                }
                ArrayNode list = (ArrayNode) current.deepCopy();
                JsonNode v = value(a.value(), type.args().get(0), binds, schema.udts);
                if (v.isNull()) {
                    list.remove(index);
                } else {
                    list.set(index, v);
                }
                setCell(row, schema, a.column(), list, ts, exp);
            } else if ("map".equals(type.name())) {
                JsonNode key = value(a.key(), type.args().get(0), binds, schema.udts);
                JsonNode v = value(a.value(), type.args().get(1), binds, schema.udts);
                ArrayNode map = NODES.arrayNode();
                if (current != null) {
                    for (JsonNode pair : current) {
                        if (KeyspacesData.compare(type.args().get(0), pair.get(0), key) != 0) {
                            map.add(pair);
                        }
                    }
                }
                if (!v.isNull()) {
                    ArrayNode pair = map.addArray();
                    pair.add(key);
                    pair.add(v);
                }
                setCell(row, schema, a.column(), normalizeMap(type, map), ts, exp);
            } else {
                throw CqlError.invalid("Invalid operation (" + a.column() + "[...] = ...) for non list/map column "
                        + a.column());
            }
            return;
        }
        if ("=".equals(a.op())) {
            JsonNode v = value(a.value(), type, binds, schema.udts);
            if (v != UNSET) {
                setCell(row, schema, a.column(), v, ts, exp);
            }
            return;
        }
        if (!type.isCollection() || type.frozen()) {
            throw CqlError.invalid("Invalid operation (" + a.column() + " = " + a.column() + " " + a.op()
                    + " ...) for non counter column " + a.column());
        }
        JsonNode operand = "-".equals(a.op()) && "map".equals(type.name())
                ? value(a.value(), new CqlType("set", List.of(type.args().get(0)), false), binds, schema.udts)
                : value(a.value(), type, binds, schema.udts);
        ArrayNode result = NODES.arrayNode();
        switch (type.name()) {
            case "list" -> {
                if ("+".equals(a.op())) {
                    if (a.prepend()) {
                        operand.forEach(result::add);
                        if (current != null) {
                            current.forEach(result::add);
                        }
                    } else {
                        if (current != null) {
                            current.forEach(result::add);
                        }
                        operand.forEach(result::add);
                    }
                } else if (current != null) {
                    for (JsonNode item : current) {
                        boolean removed = false;
                        for (JsonNode o : operand) {
                            removed |= KeyspacesData.compare(type.args().get(0), item, o) == 0;
                        }
                        if (!removed) {
                            result.add(item);
                        }
                    }
                }
                setCell(row, schema, a.column(), result, ts, exp);
            }
            case "set" -> {
                if (current != null) {
                    current.forEach(result::add);
                }
                if ("+".equals(a.op())) {
                    operand.forEach(result::add);
                    setCell(row, schema, a.column(), normalizeSet(type, result), ts, exp);
                } else {
                    ArrayNode kept = NODES.arrayNode();
                    for (JsonNode item : result) {
                        boolean removed = false;
                        for (JsonNode o : operand) {
                            removed |= KeyspacesData.compare(type.args().get(0), item, o) == 0;
                        }
                        if (!removed) {
                            kept.add(item);
                        }
                    }
                    setCell(row, schema, a.column(), kept, ts, exp);
                }
            }
            default -> {
                if (current != null) {
                    current.forEach(result::add);
                }
                if ("+".equals(a.op())) {
                    operand.forEach(result::add);
                    setCell(row, schema, a.column(), normalizeMap(type, result), ts, exp);
                } else {
                    ArrayNode kept = NODES.arrayNode();
                    for (JsonNode pair : result) {
                        boolean removed = false;
                        for (JsonNode o : operand) {
                            removed |= KeyspacesData.compare(type.args().get(0), pair.get(0), o) == 0;
                        }
                        if (!removed) {
                            kept.add(pair);
                        }
                    }
                    setCell(row, schema, a.column(), kept, ts, exp);
                }
            }
        }
    }

    // ---------------------------------------------------------------- schema statements

    private Result schema(Session session, Statement statement, Binds binds) {
        String account = session.account;
        String region = session.region;
        ObjectMapper m = mapper();
        switch (statement) {
            case CqlParser.CreateKeyspace c -> {
                ObjectNode in = m.createObjectNode().put("keyspaceName", c.name());
                in.set("replicationSpecification", replication(region, c.options()));
                if (c.options().get("tags") instanceof Map<?, ?> tags) {
                    in.set("tags", tags(tags));
                }
                try {
                    service.createKeyspace(in, region);
                } catch (AwsException e) {
                    if ("ConflictException".equals(e.getErrorCode())) {
                        if (c.ifNotExists()) {
                            return new VoidResult();
                        }
                        throw CqlError.alreadyExists("Keyspace " + c.name() + " already exists", c.name(), null);
                    }
                    throw e;
                }
                return new SchemaChange("CREATED", "KEYSPACE", c.name(), null);
            }
            case CqlParser.AlterKeyspace a -> {
                ObjectNode in = m.createObjectNode().put("keyspaceName", a.name());
                in.set("replicationSpecification", replication(region, a.options()));
                service.updateKeyspace(in, region);
                return new SchemaChange("UPDATED", "KEYSPACE", a.name(), null);
            }
            case CqlParser.DropKeyspace d -> {
                try {
                    service.deleteKeyspace(m.createObjectNode().put("keyspaceName", d.name()), region);
                } catch (AwsException e) {
                    if ("ResourceNotFoundException".equals(e.getErrorCode()) && d.ifExists()) {
                        return new VoidResult();
                    }
                    throw CqlError.invalid("Cannot drop non existing keyspace '" + d.name() + "'.");
                }
                if (d.name().equals(session.keyspace)) {
                    session.keyspace = null;
                }
                return new SchemaChange("DROPPED", "KEYSPACE", d.name(), null);
            }
            case CqlParser.CreateTable c -> {
                String ks = keyspaceOf(session, c.keyspace());
                ObjectNode in = m.createObjectNode().put("keyspaceName", ks).put("tableName", c.name());
                ObjectNode schema = in.putObject("schemaDefinition");
                ArrayNode all = schema.putArray("allColumns");
                ArrayNode statics = schema.putArray("staticColumns");
                for (CqlParser.ColumnDef column : c.columns()) {
                    all.addObject().put("name", column.name()).put("type", column.type());
                    if (column.isStatic()) {
                        statics.addObject().put("name", column.name());
                    }
                }
                ArrayNode pk = schema.putArray("partitionKeys");
                c.partitionKeys().forEach(k -> pk.addObject().put("name", k));
                ArrayNode ck = schema.putArray("clusteringKeys");
                for (String k : c.clusteringKeys()) {
                    ck.addObject().put("name", k).put("orderBy", c.clusteringOrder().getOrDefault(k, "ASC"));
                }
                for (String ordered : c.clusteringOrder().keySet()) {
                    if (!c.clusteringKeys().contains(ordered)) {
                        throw CqlError.invalid("Missing CLUSTERING ORDER for column " + ordered);
                    }
                }
                tableOptions(in, c.options(), true);
                try {
                    service.createTable(in, region);
                } catch (AwsException e) {
                    if ("ConflictException".equals(e.getErrorCode())) {
                        if (c.ifNotExists()) {
                            return new VoidResult();
                        }
                        throw CqlError.alreadyExists("Object " + ks + "." + c.name() + " already exists", ks,
                                c.name());
                    }
                    throw e;
                }
                return new SchemaChange("CREATED", "TABLE", ks, c.name());
            }
            case CqlParser.AlterTable a -> {
                String ks = keyspaceOf(session, a.keyspace());
                ObjectNode in = m.createObjectNode().put("keyspaceName", ks).put("tableName", a.name());
                if (!a.add().isEmpty()) {
                    ArrayNode add = in.putArray("addColumns");
                    a.add().forEach(c -> add.addObject().put("name", c.name()).put("type", c.type()));
                }
                tableOptions(in, a.options(), false);
                service.updateTable(in, region);
                return new SchemaChange("UPDATED", "TABLE", ks, a.name());
            }
            case CqlParser.DropTable d -> {
                String ks = keyspaceOf(session, d.keyspace());
                try {
                    service.deleteTable(m.createObjectNode().put("keyspaceName", ks).put("tableName", d.name()),
                            region);
                } catch (AwsException e) {
                    if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                        if (d.ifExists()) {
                            return new VoidResult();
                        }
                        throw CqlError.invalid("unconfigured table " + d.name());
                    }
                    throw e;
                }
                return new SchemaChange("DROPPED", "TABLE", ks, d.name());
            }
            case CqlParser.CreateType c -> {
                String ks = keyspaceOf(session, c.keyspace());
                ObjectNode in = m.createObjectNode().put("keyspaceName", ks).put("typeName", c.name());
                ArrayNode fields = in.putArray("fieldDefinitions");
                c.fields().forEach(f -> fields.addObject().put("name", f.name()).put("type", f.type()));
                try {
                    service.createType(in, region);
                } catch (AwsException e) {
                    if ("ConflictException".equals(e.getErrorCode())) {
                        if (c.ifNotExists()) {
                            return new VoidResult();
                        }
                        throw CqlError.invalid("A user type of name " + ks + "." + c.name() + " already exists");
                    }
                    throw e;
                }
                return new SchemaChange("CREATED", "TYPE", ks, c.name());
            }
            case CqlParser.DropType d -> {
                String ks = keyspaceOf(session, d.keyspace());
                try {
                    service.deleteType(m.createObjectNode().put("keyspaceName", ks).put("typeName", d.name()),
                            region);
                } catch (AwsException e) {
                    if ("ResourceNotFoundException".equals(e.getErrorCode()) && d.ifExists()) {
                        return new VoidResult();
                    }
                    throw e;
                }
                return new SchemaChange("DROPPED", "TYPE", ks, d.name());
            }
            case CqlParser.Truncate t -> throw CqlError.invalid("TRUNCATE is not supported by Amazon Keyspaces; "
                    + "delete the rows, or drop and re-create the table.");
            default -> throw CqlError.invalid("Unsupported statement.");
        }
    }

    private ObjectNode replication(String region, Map<String, Object> options) {
        ObjectNode spec = mapper().createObjectNode();
        Object replication = options.get("replication");
        if (!(replication instanceof Map<?, ?> map)) {
            spec.put("replicationStrategy", "SINGLE_REGION");
            return spec;
        }
        String cls = String.valueOf(map.get("class"));
        if (cls.endsWith("NetworkTopologyStrategy")) {
            ArrayNode regions = mapper().createArrayNode();
            for (Object key : map.keySet()) {
                if (!"class".equals(key)) {
                    regions.add(String.valueOf(key));
                }
            }
            if (regions.size() > 1) {
                spec.put("replicationStrategy", "MULTI_REGION").set("regionList", regions);
                return spec;
            }
        } else if (!cls.endsWith("SingleRegionStrategy") && !cls.endsWith("SimpleStrategy")) {
            throw CqlError.invalid("Unsupported replication class " + cls + "; Keyspaces supports "
                    + "SingleRegionStrategy and NetworkTopologyStrategy.");
        }
        spec.put("replicationStrategy", "SINGLE_REGION");
        return spec;
    }

    private ArrayNode tags(Map<?, ?> tags) {
        ArrayNode out = mapper().createArrayNode();
        tags.forEach((k, v) -> out.addObject().put("key", String.valueOf(k)).put("value", String.valueOf(v)));
        return out;
    }

    /** Keyspaces' table options: {@code default_time_to_live}, {@code comment}, {@code tags}, {@code custom_properties}. */
    @SuppressWarnings("unchecked")
    private void tableOptions(ObjectNode in, Map<String, Object> options, boolean creating) {
        for (Map.Entry<String, Object> option : options.entrySet()) {
            Object v = option.getValue();
            switch (option.getKey()) {
                case "default_time_to_live" -> in.put("defaultTimeToLive", Integer.parseInt(String.valueOf(v)));
                case "comment" -> in.putObject("comment").put("message", String.valueOf(v));
                case "tags" -> in.set("tags", tags((Map<?, ?>) v));
                case "custom_properties" -> {
                    Map<String, Object> props = (Map<String, Object>) v;
                    for (Map.Entry<String, Object> prop : props.entrySet()) {
                        Map<String, Object> p = prop.getValue() instanceof Map<?, ?> pm ? (Map<String, Object>) pm
                                : Map.of();
                        switch (prop.getKey()) {
                            case "capacity_mode" -> {
                                ObjectNode c = in.putObject("capacitySpecification");
                                String mode = String.valueOf(p.getOrDefault("throughput_mode", "PAY_PER_REQUEST"))
                                        .toUpperCase(Locale.ROOT);
                                c.put("throughputMode", mode);
                                if (p.containsKey("read_capacity_units")) {
                                    c.put("readCapacityUnits", Long.parseLong(String.valueOf(p.get(
                                            "read_capacity_units"))));
                                }
                                if (p.containsKey("write_capacity_units")) {
                                    c.put("writeCapacityUnits", Long.parseLong(String.valueOf(p.get(
                                            "write_capacity_units"))));
                                }
                            }
                            case "point_in_time_recovery" -> in.putObject("pointInTimeRecovery").put("status",
                                    String.valueOf(p.get("status")).toUpperCase(Locale.ROOT));
                            case "encryption_specification" -> {
                                ObjectNode e = in.putObject("encryptionSpecification");
                                e.put("type", String.valueOf(p.get("encryption_type")).toUpperCase(Locale.ROOT));
                                if (p.containsKey("kms_key_identifier")) {
                                    e.put("kmsKeyIdentifier", String.valueOf(p.get("kms_key_identifier")));
                                }
                            }
                            case "ttl" -> in.putObject("ttl").put("status", "ENABLED");
                            case "client_side_timestamps" -> in.putObject("clientSideTimestamps").put("status",
                                    "ENABLED");
                            case "cdc_specification" -> {
                                ObjectNode cdc = in.putObject("cdcSpecification");
                                cdc.put("status", String.valueOf(p.getOrDefault("status", "enabled"))
                                        .toUpperCase(Locale.ROOT));
                                if (p.containsKey("view_type")) {
                                    cdc.put("viewType", String.valueOf(p.get("view_type")).toUpperCase(Locale.ROOT));
                                }
                            }
                            default -> {
                            }
                        }
                    }
                }
                default -> {
                    // Cassandra options Keyspaces accepts and ignores (compaction, caching and the rest).
                }
            }
        }
        if (!creating) {
            in.remove("tags");
        }
    }
}
