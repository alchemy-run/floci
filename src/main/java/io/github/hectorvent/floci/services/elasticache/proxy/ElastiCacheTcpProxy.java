package io.github.hectorvent.floci.services.elasticache.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/** Transparent TCP relay for cache engines that do not require Redis auth. */
final class ElastiCacheTcpProxy {

    private final String backendHost;
    private final int backendPort;
    private volatile boolean running;
    private ServerSocket serverSocket;

    ElastiCacheTcpProxy(String backendHost, int backendPort) {
        this.backendHost = backendHost;
        this.backendPort = backendPort;
    }

    void start(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        Thread.ofVirtual().start(this::acceptLoop);
    }

    void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
            // Closing an already-closed listener is harmless.
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().start(() -> bridge(client));
            } catch (IOException ignored) {
                // Expected while stopping; the loop condition prevents a restart.
            }
        }
    }

    private void bridge(Socket client) {
        try (client; Socket backend = new Socket(backendHost, backendPort)) {
            Thread inbound = Thread.ofPlatform().daemon(true)
                    .start(() -> relay(client, backend));
            Thread outbound = Thread.ofPlatform().daemon(true)
                    .start(() -> relay(backend, client));
            inbound.join();
            outbound.join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Closing either side ends the relay.
        }
    }

    private static void relay(Socket from, Socket to) {
        try {
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1;) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException ignored) {
            // Socket closure ends either relay.
        }
    }
}
