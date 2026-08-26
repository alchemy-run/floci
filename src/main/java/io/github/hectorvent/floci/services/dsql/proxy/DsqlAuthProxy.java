package io.github.hectorvent.floci.services.dsql.proxy;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * TCP auth proxy for Aurora DSQL. Listens on the public Postgres port (5432)
 * and authenticates IAM tokens before bridging to the backend container.
 */
public class DsqlAuthProxy {

    private static final Logger LOG = Logger.getLogger(DsqlAuthProxy.class);
    /** Bound so a dead postgres container cannot hang Lambda {@code sslmode=require} connects. */
    private static final int BACKEND_CONNECT_TIMEOUT_MS = 5_000;

    private volatile String backendHost;
    private volatile int backendPort;
    private final String masterUsername;
    private final String masterPassword;
    private final String dbName;
    private final DsqlSigV4Validator sigV4;

    private volatile boolean running;
    private ServerSocket serverSocket;

    public DsqlAuthProxy(String backendHost, int backendPort,
                         String masterUsername, String masterPassword, String dbName,
                         DsqlSigV4Validator sigV4) {
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.masterUsername = masterUsername;
        this.masterPassword = masterPassword;
        this.dbName = dbName;
        this.sigV4 = sigV4;
    }

    public void start(int proxyPort) throws IOException {
        serverSocket = new ServerSocket(proxyPort);
        running = true;
        Thread.ofVirtual().name("dsql-proxy-accept").start(this::acceptLoop);
        LOG.infov("DSQL proxy started on port {0} → {1}:{2}",
                String.valueOf(serverSocket.getLocalPort()), backendHost, String.valueOf(backendPort));
    }

    /**
     * Retarget the backend after the shared Postgres container is up. The
     * listener is bound first (often against a dummy {@code 127.0.0.1:1}) so
     * Lambda {@code sslmode=require} to {@code host-gateway:5432} fails in
     * milliseconds instead of hanging until the function timeout.
     */
    public void setBackend(String backendHost, int backendPort) {
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        LOG.infov("DSQL proxy backend is now {0}:{1}", backendHost, String.valueOf(backendPort));
    }

    public int localPort() {
        return serverSocket.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.warnv("Error closing DSQL proxy server socket: {0}", e.getMessage());
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().name("dsql-proxy-conn").start(() -> handleConnection(client));
            } catch (IOException e) {
                if (running) {
                    LOG.warnv("Accept error for DSQL proxy: {0}", e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket client) {
        Socket backend = null;
        try {
            client.setTcpNoDelay(true);
            backend = new Socket();
            backend.connect(new InetSocketAddress(backendHost, backendPort), BACKEND_CONNECT_TIMEOUT_MS);
            backend.setTcpNoDelay(true);
            DsqlPostgresProtocolHandler.handleAuth(
                    client, backend, masterUsername, masterPassword, dbName,
                    true, sigV4, (user, password) -> false);
        } catch (Exception e) {
            LOG.debugv("DSQL connection error: {0}", e.getMessage());
            DsqlPostgresProtocolHandler.closeQuietly(client);
            if (backend != null) {
                DsqlPostgresProtocolHandler.closeQuietly(backend);
            }
        }
    }
}
