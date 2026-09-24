package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;

/**
 * Serves DB instance and cluster endpoints on the ports their callers configured.
 *
 * <p>AWS gives every instance and cluster its own DNS name, so any number of them listen on the
 * same port (5432, 3306, ...). Floci advertises the same shape under the local wildcard domain,
 * which resolves to the host's loopback outside Docker and to Floci inside its containers, so all
 * of those names arrive at one listener per port. This router owns those listeners and relays each
 * connection to the resource's own auth proxy on its internal port:
 *
 * <ul>
 *   <li>With one resource on a port, the connection is relayed untouched, whatever the engine.</li>
 *   <li>With several, the resource is chosen by the TLS server name: PostgreSQL's SSLRequest is
 *       answered here and the ClientHello's SNI is read without terminating TLS, then the whole
 *       exchange is replayed to the selected proxy, which runs TLS and authentication exactly as
 *       it does on a direct connection. Direct TLS (PostgreSQL 17 {@code sslnegotiation=direct})
 *       is routed the same way.</li>
 *   <li>A connection that names no host (plaintext, or TLS to an IP literal) goes to the only
 *       resource of the protocol it speaks. When several such resources share the port it is
 *       refused with an engine-level error rather than handed to an arbitrary database. MySQL
 *       clients wait for the server greeting before sending anything, so two MySQL-family
 *       resources on one port cannot be told apart.</li>
 * </ul>
 */
final class RdsEndpointRouter {

    private static final Logger LOG = Logger.getLogger(RdsEndpointRouter.class);

    static final int POSTGRES_SSL_REQUEST = 80877103;
    static final int POSTGRES_GSSENC_REQUEST = 80877104;
    static final int POSTGRES_PROTOCOL_3 = 196608;
    private static final int TLS_HANDSHAKE_RECORD = 0x16;
    /** A SQL Server client opens with a TDS PRELOGIN packet. */
    private static final int TDS_PRELOGIN = 0x12;
    /** How long to wait for a client that speaks first before assuming a MySQL client. */
    private static final int SERVER_FIRST_PEEK_MILLIS = 1000;

    record Route(String key, Set<String> hostnames, int targetPort, DatabaseEngine engine) {}

    private final int handshakeTimeoutMillis;
    private final int backendConnectTimeoutMillis;
    private final int maxConnections;
    private final Map<Integer, Listener> listeners = new HashMap<>();
    private final Map<String, Integer> portByKey = new HashMap<>();

    RdsEndpointRouter(int handshakeTimeoutMillis, int backendConnectTimeoutMillis, int maxConnections) {
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        this.backendConnectTimeoutMillis = backendConnectTimeoutMillis;
        this.maxConnections = maxConnections;
    }

    /**
     * Routes {@code hostnames} on {@code listenPort} to the proxy on {@code targetPort}, replacing
     * whatever {@code key} was routed before. Returns false when the port cannot be bound on this
     * host (another process, or another Floci listener that binds it directly, holds it); the
     * resource stays reachable on its internal port and the next registration retries the bind.
     */
    synchronized boolean register(String key, int listenPort, Collection<String> hostnames,
                                  int targetPort, DatabaseEngine engine) {
        Integer previousPort = portByKey.get(key);
        if (previousPort != null && previousPort != listenPort) {
            unregister(key);
        }
        if (listenPort <= 0 || targetPort <= 0 || listenPort == targetPort) {
            // The proxy itself listens on the advertised port; there is nothing to route.
            unregister(key);
            return true;
        }
        Set<String> names = new LinkedHashSet<>();
        for (String hostname : hostnames) {
            String normalized = TlsClientHello.normalizeHost(hostname);
            if (normalized != null) {
                names.add(normalized);
            }
        }
        Listener listener = listeners.get(listenPort);
        if (listener == null) {
            listener = new Listener(listenPort);
            try {
                listener.start();
            } catch (IOException e) {
                LOG.warnv("RDS endpoint port {0} is unavailable on this host ({1}); {2} stays reachable "
                                + "only on its internal proxy port {3}",
                        String.valueOf(listenPort), e.getMessage(), key, String.valueOf(targetPort));
                return false;
            }
            listeners.put(listenPort, listener);
        }
        listener.routes.put(key, new Route(key, Set.copyOf(names), targetPort, engine));
        portByKey.put(key, listenPort);
        return true;
    }

    synchronized void unregister(String key) {
        Integer port = portByKey.remove(key);
        if (port == null) {
            return;
        }
        Listener listener = listeners.get(port);
        if (listener == null) {
            return;
        }
        listener.routes.remove(key);
        if (listener.routes.isEmpty()) {
            listeners.remove(port);
            listener.stop();
        }
    }

    synchronized boolean isListening(int port) {
        return listeners.containsKey(port);
    }

    synchronized void closeAll() {
        listeners.values().forEach(Listener::stop);
        listeners.clear();
        portByKey.clear();
    }

    /** The route a client's server name selects, or null. */
    static Route routeForHost(Collection<Route> routes, String serverName) {
        String host = TlsClientHello.normalizeHost(serverName);
        if (host == null) {
            return null;
        }
        for (Route route : routes) {
            if (route.hostnames().contains(host)) {
                return route;
            }
        }
        return null;
    }

    /** The one resource a port serves, or null when several share it. */
    static Route soleRoute(Collection<Route> routes) {
        return soleRoute(routes, engine -> true);
    }

    /** The one resource of the matching engines, or null when there are none or several. */
    static Route soleRoute(Collection<Route> routes, Predicate<DatabaseEngine> engines) {
        Route sole = null;
        for (Route route : routes) {
            if (!engines.test(route.engine())) {
                continue;
            }
            if (sole != null) {
                return null;
            }
            sole = route;
        }
        return sole;
    }

    private final class Listener {
        private final int port;
        private final Map<String, Route> routes = new ConcurrentHashMap<>();
        private final Semaphore permits = new Semaphore(Math.max(1, maxConnections));
        private volatile ServerSocket serverSocket;
        private volatile boolean running;

        Listener(int port) {
            this.port = port;
        }

        void start() throws IOException {
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(port));
            } catch (IOException e) {
                closeQuietly(socket);
                throw e;
            }
            serverSocket = socket;
            running = true;
            Thread.ofVirtual().name("rds-endpoint-accept-" + port).start(this::acceptLoop);
            LOG.infov("RDS endpoint listener started on port {0}", String.valueOf(port));
        }

        void stop() {
            running = false;
            closeQuietly(serverSocket);
            LOG.infov("RDS endpoint listener stopped on port {0}", String.valueOf(port));
        }

        private void acceptLoop() {
            while (running) {
                Socket client;
                try {
                    client = serverSocket.accept();
                } catch (IOException e) {
                    if (running) {
                        LOG.debugv("RDS endpoint accept error on port {0}: {1}",
                                String.valueOf(port), e.getMessage());
                    }
                    continue;
                }
                if (!permits.tryAcquire()) {
                    LOG.warnv("Refusing RDS endpoint connection on port {0}: connection limit reached",
                            String.valueOf(port));
                    closeQuietly(client);
                    continue;
                }
                Thread.ofVirtual().name("rds-endpoint-conn-" + port).start(() -> {
                    try {
                        handle(client, List.copyOf(routes.values()));
                    } finally {
                        permits.release();
                    }
                });
            }
        }

        private void handle(Socket client, List<Route> snapshot) {
            Socket backend = null;
            try {
                client.setTcpNoDelay(true);
                Route sole = soleRoute(snapshot);
                if (sole != null) {
                    backend = connect(sole);
                    TcpStreamBridge.relay(client, backend);
                    return;
                }
                backend = routeByServerName(client, snapshot);
                if (backend != null) {
                    client.setSoTimeout(0);
                    TcpStreamBridge.relay(client, backend);
                }
            } catch (IOException | RuntimeException e) {
                LOG.debugv("RDS endpoint connection on port {0} failed: {1}", String.valueOf(port), e.getMessage());
            } finally {
                closeQuietly(client);
                closeQuietly(backend);
            }
        }

        /**
         * Reads enough of the client's opening to pick a resource and returns a backend already
         * fed everything read so far. A host name always wins; without one, the connection goes
         * to the only resource of the protocol the client is speaking, if there is exactly one.
         */
        private Socket routeByServerName(Socket client, List<Route> snapshot) throws IOException {
            boolean serverFirstEngine = snapshot.stream().anyMatch(route -> isServerFirst(route.engine()));
            client.setSoTimeout(serverFirstEngine
                    ? Math.min(handshakeTimeoutMillis, SERVER_FIRST_PEEK_MILLIS)
                    : handshakeTimeoutMillis);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] opening;
            try {
                opening = readFully(in, 8);
            } catch (SocketTimeoutException e) {
                // Only a MySQL-family client waits for the server to speak first.
                Route mysql = soleRoute(snapshot, RdsEndpointRouter::isServerFirst);
                if (mysql != null) {
                    return connect(mysql);
                }
                if (serverFirstEngine) {
                    out.write(mysqlErrorPacket(ambiguousMessage()));
                    out.flush();
                }
                return null;
            }
            client.setSoTimeout(handshakeTimeoutMillis);
            if ((opening[0] & 0xFF) == TLS_HANDSHAKE_RECORD) {
                TlsClientHello.Result hello = TlsClientHello.read(in, opening);
                Route route = tlsRoute(snapshot, hello.serverName());
                if (route == null) {
                    return null;
                }
                Socket backend = connect(route);
                backend.getOutputStream().write(hello.consumed());
                backend.getOutputStream().flush();
                return backend;
            }
            int length = readInt(opening, 0);
            int code = readInt(opening, 4);
            if (length == 8 && code == POSTGRES_GSSENC_REQUEST) {
                // GSSAPI encryption is not offered; the client falls back to SSL or plaintext.
                out.write('N');
                out.flush();
                opening = readFully(in, 8);
                length = readInt(opening, 0);
                code = readInt(opening, 4);
            }
            if (length == 8 && code == POSTGRES_SSL_REQUEST) {
                out.write('S');
                out.flush();
                TlsClientHello.Result hello = TlsClientHello.read(in, new byte[0]);
                Route route = tlsRoute(snapshot, hello.serverName());
                if (route == null) {
                    return null;
                }
                Socket backend = connect(route);
                OutputStream backendOut = backend.getOutputStream();
                backendOut.write(opening);
                backendOut.flush();
                backend.setSoTimeout(handshakeTimeoutMillis);
                int answer = backend.getInputStream().read();
                if (answer != 'S') {
                    closeQuietly(backend);
                    return null;
                }
                backend.setSoTimeout(0);
                backendOut.write(hello.consumed());
                backendOut.flush();
                return backend;
            }
            if (code == POSTGRES_PROTOCOL_3) {
                Route postgres = soleRoute(snapshot, engine -> engine == DatabaseEngine.POSTGRES);
                if (postgres != null) {
                    Socket backend = connect(postgres);
                    backend.getOutputStream().write(opening);
                    backend.getOutputStream().flush();
                    return backend;
                }
                out.write(postgresErrorResponse(ambiguousMessage()));
                out.flush();
                return null;
            }
            if ((opening[0] & 0xFF) == TDS_PRELOGIN) {
                Route sqlServer = soleRoute(snapshot, engine -> engine == DatabaseEngine.SQLSERVER);
                if (sqlServer != null) {
                    Socket backend = connect(sqlServer);
                    backend.getOutputStream().write(opening);
                    backend.getOutputStream().flush();
                    return backend;
                }
            }
            return null;
        }

        /** The resource a TLS server name selects; without a name, the only PostgreSQL one. */
        private Route tlsRoute(List<Route> snapshot, String serverName) {
            Route route = serverName != null
                    ? routeForHost(snapshot, serverName)
                    : soleRoute(snapshot, engine -> engine == DatabaseEngine.POSTGRES);
            if (route == null) {
                LOG.debugv("RDS endpoint port {0}: no resource for TLS server name {1}",
                        String.valueOf(port), serverName);
            }
            return route;
        }

        private String ambiguousMessage() {
            return "Several DB instances share port " + port + " on this Floci host and this "
                    + "connection names none of them. Connect over TLS (for PostgreSQL, "
                    + "sslmode=require or stricter) so the endpoint's host name selects the "
                    + "database, or give each DB instance its own port.";
        }

        private Socket connect(Route route) throws IOException {
            Socket backend = new Socket();
            try {
                backend.connect(new InetSocketAddress("127.0.0.1", route.targetPort()), backendConnectTimeoutMillis);
                backend.setTcpNoDelay(true);
                return backend;
            } catch (IOException e) {
                closeQuietly(backend);
                throw e;
            }
        }
    }

    private static boolean isServerFirst(DatabaseEngine engine) {
        return engine == DatabaseEngine.MYSQL || engine == DatabaseEngine.MARIADB;
    }

    static byte[] postgresErrorResponse(String message) {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeField(fields, 'S', "FATAL");
        writeField(fields, 'V', "FATAL");
        writeField(fields, 'C', "08004");
        writeField(fields, 'M', message);
        fields.write(0);
        byte[] body = fields.toByteArray();
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write('E');
        writeInt(packet, body.length + 4);
        packet.writeBytes(body);
        return packet.toByteArray();
    }

    /** A MySQL ERR packet sent in place of the server greeting (ER_HANDSHAKE_ERROR). */
    static byte[] mysqlErrorPacket(String message) {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        int payloadLength = 1 + 2 + text.length;
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(payloadLength & 0xFF);
        packet.write((payloadLength >> 8) & 0xFF);
        packet.write((payloadLength >> 16) & 0xFF);
        packet.write(0);
        packet.write(0xFF);
        packet.write(1043 & 0xFF);
        packet.write((1043 >> 8) & 0xFF);
        packet.writeBytes(text);
        return packet.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, char type, String value) {
        out.write(type);
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        // DataInputStream adds no buffering, so nothing past these bytes is consumed.
        new DataInputStream(in).readFully(buffer);
        return buffer;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            LOG.debugv("Error closing RDS endpoint socket: {0}", e.getMessage());
        }
    }

    /** Hostnames routed on each port, for diagnostics and tests. */
    synchronized Map<Integer, List<Route>> routes() {
        Map<Integer, List<Route>> view = new HashMap<>();
        listeners.forEach((port, listener) -> view.put(port, new ArrayList<>(listener.routes.values())));
        return view;
    }
}
