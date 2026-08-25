package io.github.hectorvent.floci.services.dsql.proxy;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * TCP auth proxy for Aurora DSQL. Listens on the public Postgres port (5432)
 * and authenticates IAM tokens before bridging to the backend container.
 */
public class DsqlAuthProxy {

    private static final Logger LOG = Logger.getLogger(DsqlAuthProxy.class);

    private final String backendHost;
    private final int backendPort;
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
                String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
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
        try {
            client.setTcpNoDelay(true);
            Socket backend = new Socket(backendHost, backendPort);
            backend.setTcpNoDelay(true);
            DsqlPostgresProtocolHandler.handleAuth(
                    client, backend, masterUsername, masterPassword, dbName,
                    true, sigV4, (user, password) -> false);
        } catch (Exception e) {
            LOG.debugv("DSQL connection error: {0}", e.getMessage());
            DsqlPostgresProtocolHandler.closeQuietly(client);
        }
    }
}
