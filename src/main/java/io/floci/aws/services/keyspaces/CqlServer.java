package io.floci.aws.services.keyspaces;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.tls.ReloadingKeyManager;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.ServiceSpecificCredential;
import io.floci.aws.services.keyspaces.CqlCodec.In;
import io.floci.aws.services.keyspaces.CqlCodec.Out;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon Keyspaces' CQL endpoint: the Cassandra native protocol (v3 and v4) on port 9142 (TLS when Floci serves
 * TLS, as Keyspaces requires). Clients authenticate as Keyspaces requires, with a service-specific credential of an
 * IAM user for {@code cassandra.amazonaws.com} ({@code PasswordAuthenticator}) or with the SigV4 authentication
 * plugin's challenge, and then run CQL in the Region Floci serves by default. Statements run on worker threads; a
 * connection may have many requests in flight, each answered on its stream.
 */
@ApplicationScoped
public class CqlServer {

    private static final Logger LOG = Logger.getLogger(CqlServer.class);
    static final int STARTUP = 0x01;
    static final int READY = 0x02;
    static final int AUTHENTICATE = 0x03;
    static final int OPTIONS = 0x05;
    static final int SUPPORTED = 0x06;
    static final int QUERY = 0x07;
    static final int RESULT = 0x08;
    static final int PREPARE = 0x09;
    static final int EXECUTE = 0x0A;
    static final int REGISTER = 0x0B;
    static final int BATCH = 0x0D;
    static final int AUTH_CHALLENGE = 0x0E;
    static final int AUTH_RESPONSE = 0x0F;
    static final int AUTH_SUCCESS = 0x10;
    static final int ERROR = 0x00;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmulatorConfig config;
    private final Vertx vertx;
    private final CqlExecutor executor;
    private final Instance<IamService> iam;
    private final Instance<TlsConfigurationRegistry> tlsRegistry;
    private NetServer server;

    @Inject
    public CqlServer(EmulatorConfig config, Vertx vertx, CqlExecutor executor, Instance<IamService> iam,
                     Instance<TlsConfigurationRegistry> tlsRegistry) {
        this.config = config;
        this.vertx = vertx;
        this.executor = executor;
        this.iam = iam;
        this.tlsRegistry = tlsRegistry;
    }

    void onStart(@Observes StartupEvent ignored) {
        EmulatorConfig.KeyspacesServiceConfig keyspaces = config.services().keyspaces();
        if (!keyspaces.enabled() || keyspaces.cqlPort() <= 0) {
            return;
        }
        NetServerOptions options = new NetServerOptions().setHost(keyspaces.cqlHost()).setPort(keyspaces.cqlPort());
        if (config.tls().enabled() && tlsRegistry.isResolvable()) {
            TlsConfiguration tls = tlsRegistry.get().getDefault().orElse(null);
            if (tls != null) {
                ReloadingKeyManager manager = new ReloadingKeyManager(ReloadingKeyManager.keyManagerOf(vertx,
                        tls.getKeyStoreOptions()));
                options.setSsl(true).setKeyCertOptions(KeyCertOptions.wrap(manager));
            }
        }
        server = vertx.createNetServer(options);
        server.connectHandler(socket -> new Connection(socket).start());
        server.listen().onComplete(ar -> {
            if (ar.succeeded()) {
                LOG.infov("Keyspaces CQL endpoint listening on {0}:{1}{2}", keyspaces.cqlHost(),
                        String.valueOf(keyspaces.cqlPort()), options.isSsl() ? " (TLS)" : "");
            } else {
                LOG.warnv("Keyspaces CQL endpoint not started on port {0}: {1}", String.valueOf(keyspaces.cqlPort()),
                        ar.cause().getMessage());
            }
        });
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (server != null) {
            server.close();
        }
    }

    /** One client connection: its protocol version, authentication and session. */
    private final class Connection {
        private final NetSocket socket;
        private Buffer pending = Buffer.buffer();
        private int version = 4;
        private boolean authenticated;
        private String nonce;
        private CqlExecutor.Session session;

        Connection(NetSocket socket) {
            this.socket = socket;
        }

        void start() {
            socket.handler(this::receive);
            socket.exceptionHandler(e -> socket.close());
        }

        private void receive(Buffer data) {
            pending.appendBuffer(data);
            while (pending.length() >= 9) {
                int length = pending.getInt(5);
                if (length < 0 || length > 256 * 1024 * 1024) {
                    socket.close();
                    return;
                }
                if (pending.length() < 9 + length) {
                    return;
                }
                int requested = pending.getByte(0) & 0x7f;
                int flags = pending.getByte(1) & 0xff;
                short stream = pending.getShort(2);
                int opcode = pending.getByte(4) & 0xff;
                byte[] body = pending.getBytes(9, 9 + length);
                pending = pending.getBuffer(9 + length, pending.length());
                if (requested < 3 || requested > 4) {
                    Out out = new Out().i(CqlError.PROTOCOL).string("Invalid or unsupported protocol version ("
                            + requested + "); supported versions are (3/v3, 4/v4)");
                    send(stream, ERROR, out.toBytes(), requested < 3 ? requested : 4);
                    continue;
                }
                version = requested;
                if ((flags & 0x01) != 0) {
                    send(stream, ERROR, new Out().i(CqlError.PROTOCOL).string("Compression is not supported")
                            .toBytes(), version);
                    continue;
                }
                vertx.<byte[][]>executeBlocking(() -> handle(opcode, body), false).onComplete(ar -> {
                    if (ar.succeeded()) {
                        byte[][] reply = ar.result();
                        send(stream, reply[0][0] & 0xff, reply[1], version);
                    } else {
                        Throwable e = ar.cause();
                        CqlError error = e instanceof CqlError ce ? ce : new CqlError(CqlError.SERVER,
                                String.valueOf(e.getMessage()));
                        if (!(e instanceof CqlError)) {
                            LOG.warnf(e, "Keyspaces CQL request failed");
                        }
                        send(stream, ERROR, error(error), version);
                    }
                });
            }
        }

        private void send(short stream, int opcode, byte[] body, int frameVersion) {
            Buffer frame = Buffer.buffer(9 + body.length);
            frame.appendByte((byte) (0x80 | frameVersion)).appendByte((byte) 0).appendShort(stream)
                    .appendByte((byte) opcode).appendInt(body.length).appendBytes(body);
            socket.write(frame);
        }

        private byte[][] reply(int opcode, byte[] body) {
            return new byte[][] {{(byte) opcode}, body};
        }

        private byte[][] handle(int opcode, byte[] body) {
            In in = new In(body);
            switch (opcode) {
                case OPTIONS -> {
                    Map<String, List<String>> supported = new LinkedHashMap<>();
                    supported.put("CQL_VERSION", List.of("3.4.4"));
                    supported.put("COMPRESSION", List.of());
                    supported.put("PROTOCOL_VERSIONS", List.of("3/v3", "4/v4"));
                    return reply(SUPPORTED, new Out().stringMultimap(supported).toBytes());
                }
                case STARTUP -> {
                    Map<String, String> options = in.stringMap();
                    if (options.containsKey("COMPRESSION")) {
                        throw new CqlError(CqlError.PROTOCOL, "Compression " + options.get("COMPRESSION")
                                + " is not supported");
                    }
                    return reply(AUTHENTICATE, new Out().string("org.apache.cassandra.auth.PasswordAuthenticator")
                            .toBytes());
                }
                case AUTH_RESPONSE -> {
                    return authenticate(in.bytes());
                }
                case REGISTER -> {
                    return reply(READY, new byte[0]);
                }
                default -> {
                }
            }
            if (!authenticated) {
                throw new CqlError(CqlError.PROTOCOL, "The connection has not authenticated.");
            }
            return RequestScopes.callAs(session.account, session.region, () -> request(opcode, in));
        }

        private byte[][] authenticate(byte[] token) {
            String text = token == null ? "" : new String(token, StandardCharsets.UTF_8);
            if (text.startsWith("SigV4")) {
                nonce = HexFormat.of().formatHex(RANDOM.generateSeed(16));
                return reply(AUTH_CHALLENGE, new Out().bytes(("nonce=" + nonce).getBytes(StandardCharsets.UTF_8))
                        .toBytes());
            }
            if (nonce != null && text.contains("signature=")) {
                Map<String, String> fields = new LinkedHashMap<>();
                for (String part : text.split(",")) {
                    int eq = part.indexOf('=');
                    if (eq > 0) {
                        fields.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
                    }
                }
                if (!fields.containsKey("access_key") || fields.get("signature").isEmpty()) {
                    throw new CqlError(CqlError.BAD_CREDENTIALS, "The SigV4 response has no access key or signature.");
                }
                return success(config.defaultAccountId());
            }
            String[] parts = text.split("\u0000", -1);
            if (parts.length != 3) {
                throw new CqlError(CqlError.BAD_CREDENTIALS, "Authentication failed: malformed credentials.");
            }
            String user = parts[1];
            String password = parts[2];
            String account = config.defaultAccountId();
            boolean valid = RequestScopes.callAs(account, config.defaultRegion(), () -> {
                for (ServiceSpecificCredential credential : iam.get().listServiceSpecificCredentials(null,
                        "cassandra.amazonaws.com", true)) { // partition-literal: IAM's credential service name
                    if (user.equals(credential.getServiceUserName()) && password.equals(credential.getServicePassword())
                            && "Active".equals(iam.get().reportedStatus(credential))) {
                        return true;
                    }
                }
                return false;
            });
            if (!valid) {
                throw new CqlError(CqlError.BAD_CREDENTIALS, "Provided username " + user + " and/or password are "
                        + "incorrect");
            }
            return success(account);
        }

        private byte[][] success(String account) {
            authenticated = true;
            session = new CqlExecutor.Session(account, config.defaultRegion());
            return reply(AUTH_SUCCESS, new Out().bytes(null).toBytes());
        }

        private byte[][] request(int opcode, In in) {
            switch (opcode) {
                case QUERY -> {
                    String cql = in.longString();
                    Params params = params(in, null);
                    CqlExecutor.Result result = executor.run(session, cql, new CqlExecutor.Binds(params.values,
                            params.named, List.of()), params.options);
                    return reply(RESULT, result(result, params.skipMetadata));
                }
                case PREPARE -> {
                    String cql = in.longString();
                    CqlExecutor.Prepared prepared = executor.prepare(session, cql);
                    return reply(RESULT, prepared(prepared));
                }
                case EXECUTE -> {
                    byte[] id = in.shortBytes();
                    CqlExecutor.Prepared prepared = executor.prepared(id);
                    Params params = params(in, prepared);
                    CqlExecutor.Result result = executor.execute(session, prepared, params.values, params.named,
                            params.options);
                    return reply(RESULT, result(result, params.skipMetadata));
                }
                case BATCH -> {
                    return reply(RESULT, batch(in));
                }
                default -> throw new CqlError(CqlError.PROTOCOL, "Unknown opcode " + opcode);
            }
        }

        /** Query parameters: consistency, flags and what they announce. */
        private final class Params {
            List<byte[]> values = new ArrayList<>();
            Map<String, byte[]> named = new LinkedHashMap<>();
            boolean skipMetadata;
            CqlExecutor.Options options;
        }

        private Params params(In in, CqlExecutor.Prepared prepared) {
            Params p = new Params();
            int consistency = in.s();
            int flags = in.b();
            if ((flags & 0x01) != 0) {
                int n = in.s();
                for (int k = 0; k < n; k++) {
                    if ((flags & 0x40) != 0) {
                        p.named.put(in.string().toLowerCase(), in.bytes());
                    } else {
                        p.values.add(in.bytes());
                    }
                }
            }
            p.skipMetadata = (flags & 0x02) != 0;
            int pageSize = (flags & 0x04) != 0 ? in.i() : -1;
            byte[] paging = (flags & 0x08) != 0 ? in.bytes() : null;
            if ((flags & 0x10) != 0) {
                in.s();
            }
            Long timestamp = (flags & 0x20) != 0 ? in.l() : null;
            p.options = new CqlExecutor.Options(consistency, pageSize, paging, timestamp);
            return p;
        }

        private byte[] batch(In in) {
            in.b();
            int n = in.s();
            List<CqlParser.Statement> statements = new ArrayList<>();
            List<CqlExecutor.Binds> binds = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                int kind = in.b();
                CqlParser.Parsed parsed;
                if (kind == 0) {
                    try {
                        parsed = CqlParser.parse(in.longString());
                    } catch (CqlParser.SyntaxError e) {
                        throw new CqlError(CqlError.SYNTAX, e.getMessage());
                    }
                } else {
                    parsed = executor.prepared(in.shortBytes()).parsed();
                }
                int count = in.s();
                List<byte[]> own = new ArrayList<>();
                for (int v = 0; v < count; v++) {
                    own.add(in.bytes());
                }
                statements.add(parsed.statement());
                binds.add(new CqlExecutor.Binds(own, Map.of(), parsed.binds()));
            }
            int consistency = in.s();
            int flags = in.b();
            if ((flags & 0x10) != 0) {
                in.s();
            }
            Long timestamp = (flags & 0x20) != 0 ? in.l() : null;
            return result(executor.batch(session, statements, binds, new CqlExecutor.Options(consistency, -1, null,
                    timestamp)), false);
        }

        private byte[] result(CqlExecutor.Result result, boolean skipMetadata) {
            Out out = new Out();
            switch (result) {
                case CqlExecutor.VoidResult v -> out.i(0x0001);
                case CqlExecutor.SetKeyspace s -> out.i(0x0003).string(s.keyspace());
                case CqlExecutor.SchemaChange c -> {
                    out.i(0x0005).string(c.change()).string(c.target()).string(c.keyspace());
                    if (c.name() != null) {
                        out.string(c.name());
                    }
                }
                case CqlExecutor.Rows rows -> {
                    out.i(0x0002);
                    metadata(out, rows.columns(), rows.pagingState(), skipMetadata, rows.udts());
                    out.i(rows.rows().size());
                    for (List<byte[]> row : rows.rows()) {
                        row.forEach(out::bytes);
                    }
                }
            }
            return out.toBytes();
        }

        private void metadata(Out out, List<CqlExecutor.Column> columns, byte[] paging, boolean skip,
                              Map<String, List<Map.Entry<String, CqlType>>> udts) {
            boolean global = !columns.isEmpty() && columns.stream().allMatch(c -> c.keyspace().equals(
                    columns.get(0).keyspace()) && c.table().equals(columns.get(0).table()));
            int flags = (global ? 0x0001 : 0) | (paging != null ? 0x0002 : 0) | (skip ? 0x0004 : 0);
            out.i(flags).i(columns.size());
            if (paging != null) {
                out.bytes(paging);
            }
            if (skip) {
                return;
            }
            if (global) {
                out.string(columns.get(0).keyspace()).string(columns.get(0).table());
            }
            for (CqlExecutor.Column c : columns) {
                if (!global) {
                    out.string(c.keyspace()).string(c.table());
                }
                out.string(c.name());
                CqlCodec.typeOption(out, c.type(), name -> udts.getOrDefault(name, List.of()), c.keyspace());
            }
        }

        private byte[] prepared(CqlExecutor.Prepared p) {
            Out out = new Out().i(0x0004).shortBytes(p.id());
            List<CqlExecutor.Column> vars = p.variables();
            boolean global = !vars.isEmpty() && vars.stream().allMatch(c -> c.keyspace().equals(vars.get(0)
                    .keyspace()) && c.table().equals(vars.get(0).table()));
            out.i(global ? 0x0001 : 0).i(vars.size());
            if (version >= 4) {
                List<Integer> pk = p.pkIndexes().stream().filter(i -> i >= 0).toList();
                boolean complete = !p.pkIndexes().isEmpty() && !p.pkIndexes().contains(-1);
                out.i(complete ? pk.size() : 0);
                if (complete) {
                    pk.forEach(i -> out.s(i));
                }
            }
            if (global) {
                out.string(vars.get(0).keyspace()).string(vars.get(0).table());
            }
            for (CqlExecutor.Column c : vars) {
                if (!global) {
                    out.string(c.keyspace()).string(c.table());
                }
                out.string(c.name());
                CqlCodec.typeOption(out, c.type(), name -> p.udts().getOrDefault(name, List.of()), c.keyspace());
            }
            if (p.results().isEmpty()) {
                out.i(0x0004).i(0);
            } else {
                metadata(out, p.results(), null, false, p.udts());
            }
            return out.toBytes();
        }

        private byte[] error(CqlError e) {
            Out out = new Out().i(e.code).string(String.valueOf(e.getMessage()));
            if (e.code == CqlError.ALREADY_EXISTS) {
                out.string(e.keyspace == null ? "" : e.keyspace).string(e.table == null ? "" : e.table);
            } else if (e.code == CqlError.UNPREPARED) {
                out.shortBytes(e.preparedId);
            } else if (e.code == CqlError.UNAVAILABLE) {
                out.s(CqlExecutor.LOCAL_QUORUM).i(3).i(0);
            }
            return out.toBytes();
        }
    }
}
