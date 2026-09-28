package io.github.hectorvent.floci.services.docdb.proxy;

import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/**
 * The listener on one DocumentDB port, relaying each connection to the MongoDB container of the
 * cluster it is for.
 *
 * <p>On AWS every cluster listens on 27017 under its own hostname, so clusters share a listener
 * here and a connection is routed by the TLS server name the client sends, which drivers set to
 * the endpoint they connect to. A cluster whose parameter group leaves {@code tls} enabled, the
 * DocumentDB default, accepts TLS connections only: a client that opens with anything other than
 * a TLS handshake is disconnected, as a TLS-enabled cluster disconnects it. Plaintext carries no
 * server name, so a cluster with TLS disabled has a listener to itself.
 *
 * <p>TLS is terminated here, with the certificate of Floci's RDS certificate authority as
 * DocumentDB serves one issued by the Amazon RDS CA, and client messages pass through
 * {@link MongoAuthDatabaseRewriter} on their way to the container.
 */
public class DocDbProxy {

    static final int TLS_HANDSHAKE_RECORD = 0x16;
    private static final int TLS_CLIENT_HELLO = 0x01;
    private static final int TLS_SERVER_NAME_EXTENSION = 0x0000;
    private static final int TLS_MAX_RECORD_LENGTH = 16_384 + 2_048;
    private static final int FIRST_RECORD_TIMEOUT_MS = 30_000;
    private static final int BACKEND_CONNECT_TIMEOUT_MS = 5_000;
    private static final long RELAY_JOIN_TIMEOUT_MILLIS = 1_000;

    private static final Logger LOG = Logger.getLogger(DocDbProxy.class);

    /** Where one cluster's connections go; the backend is unknown until its container is up. */
    record Route(String clusterKey, boolean tlsRequired, Set<String> hostnames,
                 String backendHost, int backendPort) {

        boolean ready() {
            return backendHost != null;
        }
    }

    private final int port;
    private final Supplier<SSLContext> tlsContext;
    private final ConcurrentHashMap<String, Route> routes = new ConcurrentHashMap<>();
    private volatile boolean running;
    private ServerSocket serverSocket;

    DocDbProxy(int port, Supplier<SSLContext> tlsContext) {
        this.port = port;
        this.tlsContext = tlsContext;
    }

    void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        Thread.ofVirtual().name("docdb-proxy-accept-" + port).start(this::acceptLoop);
        LOG.infov("DocumentDB listener started on port {0}", String.valueOf(serverSocket.getLocalPort()));
    }

    void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.warnv("Error closing DocumentDB listener on port {0}: {1}", String.valueOf(port), e.getMessage());
        }
    }

    void putRoute(Route route) {
        routes.put(route.clusterKey(), route);
    }

    Route route(String clusterKey) {
        return routes.get(clusterKey);
    }

    void removeRoute(String clusterKey) {
        routes.remove(clusterKey);
    }

    boolean hasRoutes() {
        return !routes.isEmpty();
    }

    /** Whether a cluster with these TLS settings can be told apart from the ones already here. */
    boolean canShareWith(boolean tlsRequired) {
        return tlsRequired && routes.values().stream().allMatch(Route::tlsRequired);
    }

    /** The route a connection takes, from its TLS server name, or null when it is refused. */
    Route select(boolean tls, String serverName) {
        List<Route> all = List.copyOf(routes.values());
        Route chosen = null;
        if (tls && serverName != null) {
            String name = serverName.toLowerCase(Locale.ROOT);
            chosen = all.stream().filter(r -> r.hostnames().contains(name)).findFirst().orElse(null);
        }
        if (chosen == null && all.size() == 1) {
            // One cluster on the port: a client that names no host, or an address, means it
            chosen = all.getFirst();
        }
        // A cluster speaks TLS exactly when its tls parameter is enabled
        if (chosen == null || !chosen.ready() || chosen.tlsRequired() != tls) {
            return null;
        }
        return chosen;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().name("docdb-proxy-conn-" + port).start(() -> relay(client));
            } catch (IOException e) {
                if (running) {
                    LOG.warnv("Accept error on DocumentDB port {0}: {1}", String.valueOf(port), e.getMessage());
                }
            }
        }
    }

    private void relay(Socket client) {
        Socket backend = null;
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(FIRST_RECORD_TIMEOUT_MS);
            InputStream in = client.getInputStream();
            int first = in.read();
            if (first < 0) {
                closeQuietly(client);
                return;
            }
            boolean tls = first == TLS_HANDSHAKE_RECORD;
            byte[] opening = tls ? readRestOfRecord(first, in) : new byte[] {(byte) first};
            Route route = select(tls, tls ? serverName(opening) : null);
            if (route == null) {
                LOG.debugv("Closing a DocumentDB connection on port {0} that no cluster accepts (tls: {1})",
                        String.valueOf(port), tls);
                closeQuietly(client);
                return;
            }
            Socket clientSide = client;
            InputStream clientIn;
            if (tls) {
                SSLSocket secured = (SSLSocket) tlsContext.get().getSocketFactory()
                        .createSocket(client, new ByteArrayInputStream(opening), true);
                secured.setUseClientMode(false);
                secured.startHandshake();
                clientSide = secured;
                clientIn = secured.getInputStream();
            } else {
                clientIn = new SequenceInputStream(new ByteArrayInputStream(opening), in);
            }
            clientSide.setSoTimeout(0);
            backend = new Socket();
            backend.setTcpNoDelay(true);
            backend.connect(new InetSocketAddress(route.backendHost(), route.backendPort()),
                    BACKEND_CONNECT_TIMEOUT_MS);
            bridge(clientSide, clientIn, backend);
        } catch (IOException | RuntimeException e) {
            LOG.debugv("DocumentDB connection on port {0} failed: {1}", String.valueOf(port), e.getMessage());
            closeQuietly(client);
            if (backend != null) {
                closeQuietly(backend);
            }
        }
    }

    /** The whole first TLS record, header included, so it can be read and then passed on. */
    private static byte[] readRestOfRecord(int first, InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] header = new byte[4];
        data.readFully(header);
        int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (length > TLS_MAX_RECORD_LENGTH) {
            throw new IOException("TLS record of " + length + " bytes");
        }
        ByteArrayOutputStream record = new ByteArrayOutputStream(5 + length);
        record.write(first);
        record.writeBytes(header);
        record.writeBytes(data.readNBytes(length));
        if (record.size() != 5 + length) {
            throw new IOException("Connection closed inside the first TLS record");
        }
        return record.toByteArray();
    }

    /** The {@code server_name} of a ClientHello record, or null when it names none. */
    static String serverName(byte[] record) {
        try {
            int p = 5;
            if (record.length < 9 || (record[p] & 0xFF) != TLS_CLIENT_HELLO) {
                return null;
            }
            p += 4 + 2 + 32;
            p += 1 + (record[p] & 0xFF);
            p += 2 + u16(record, p);
            p += 1 + (record[p] & 0xFF);
            int extensionsEnd = p + 2 + u16(record, p);
            p += 2;
            while (p + 4 <= extensionsEnd && p + 4 <= record.length) {
                int type = u16(record, p);
                int length = u16(record, p + 2);
                p += 4;
                if (type == TLS_SERVER_NAME_EXTENSION) {
                    int q = p + 2;
                    int listEnd = q + u16(record, p);
                    while (q + 3 <= listEnd) {
                        int nameType = record[q] & 0xFF;
                        int nameLength = u16(record, q + 1);
                        if (nameType == 0) {
                            return new String(record, q + 3, nameLength, StandardCharsets.US_ASCII);
                        }
                        q += 3 + nameLength;
                    }
                    return null;
                }
                p += length;
            }
            return null;
        } catch (IndexOutOfBoundsException malformed) {
            return null;
        }
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private void bridge(Socket client, InputStream clientIn, Socket backend) {
        CountDownLatch firstRelayDone = new CountDownLatch(1);
        Thread clientToBackend = Thread.ofPlatform().daemon(true).name("docdb-relay-c2b-" + port)
                .start(() -> {
                    try {
                        MongoAuthDatabaseRewriter.relay(clientIn, backend.getOutputStream());
                    } catch (IOException ignored) {
                        // Normal when either side closes the connection.
                    } finally {
                        firstRelayDone.countDown();
                    }
                });
        Thread backendToClient = Thread.ofPlatform().daemon(true).name("docdb-relay-b2c-" + port)
                .start(() -> {
                    try {
                        pipe(backend.getInputStream(), client.getOutputStream());
                    } catch (IOException ignored) {
                        // Normal when either side closes the connection.
                    } finally {
                        firstRelayDone.countDown();
                    }
                });
        try {
            firstRelayDone.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // A MongoDB connection is over once either side is done with it.
            closeQuietly(client);
            closeQuietly(backend);
        }
        try {
            clientToBackend.join(RELAY_JOIN_TIMEOUT_MILLIS);
            backendToClient.join(RELAY_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // The peer may already have closed the socket.
        }
    }
}
