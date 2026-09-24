package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * The SASL/IAM listener of Amazon MSK: TLS on port 9098, where a client authenticates with an
 * IAM credential ({@code AWS_MSK_IAM} or {@code OAUTHBEARER}) before it reaches a broker.
 *
 * <p>One listener serves every cluster, as on AWS where each broker has its own hostname: the TLS
 * server name the client sends selects the cluster, and the certificate presented for it is
 * issued by Floci's local CA for exactly that hostname. After the SASL exchange succeeds the
 * connection is relayed to the broker's own listener, which advertises the same hostname, so
 * every broker connection a client opens comes back through here.
 */
@ApplicationScoped
public class MskIamGateway {

    private static final Logger LOG = Logger.getLogger(MskIamGateway.class);

    /** The port MSK serves SASL/IAM on. */
    public static final int SASL_IAM_PORT = 9098;

    private static final long RELAY_JOIN_TIMEOUT_MILLIS = 1_000;
    private static final int BACKEND_CONNECT_TIMEOUT_MILLIS = 5_000;
    /**
     * Floci's documented local-development key pair, honoured by its other SigV4 token
     * verifiers (see {@code SigV4RequestValidator}).
     */
    private static final String LEGACY_ACCESS_KEY_ID = "test";
    private static final String LEGACY_SECRET_KEY = "test";

    /** Resolves the {@code host:port} of the broker listener behind a broker hostname. */
    @FunctionalInterface
    public interface BackendResolver {
        Optional<String> backendFor(String brokerHost);
    }

    private record KeyMaterial(X509Certificate[] chain, PrivateKey key) {}

    private final FlociCertificateAuthority certificateAuthority;
    private final KafkaSaslServer saslServer;
    private final int port;
    private final CertificateGenerator certificateParser = new CertificateGenerator();
    private final Map<String, KeyMaterial> keyMaterialByHost = new ConcurrentHashMap<>();

    private volatile BackendResolver resolver = host -> Optional.empty();
    private volatile boolean running;
    private SSLServerSocket serverSocket;

    @Inject
    public MskIamGateway(FlociCertificateAuthority certificateAuthority, IamService iamService,
                         ObjectMapper objectMapper) {
        this(certificateAuthority,
                new MskIamAuthenticator(secretLookup(iamService), objectMapper, Clock.systemUTC()),
                SASL_IAM_PORT);
    }

    MskIamGateway(FlociCertificateAuthority certificateAuthority, MskIamAuthenticator authenticator, int port) {
        this.certificateAuthority = certificateAuthority;
        this.saslServer = new KafkaSaslServer(authenticator);
        this.port = port;
    }

    static MskIamAuthenticator.SecretLookup secretLookup(IamService iamService) {
        return (accessKeyId, sessionToken) -> LEGACY_ACCESS_KEY_ID.equals(accessKeyId)
                ? Optional.of(LEGACY_SECRET_KEY)
                : iamService.findSecretKey(accessKeyId, sessionToken);
    }

    /**
     * Starts the listener on first use. A port that cannot be bound is logged and leaves the
     * clusters without a reachable SASL/IAM endpoint rather than failing the API call.
     *
     * @return whether the listener is running
     */
    public synchronized boolean ensureStarted(BackendResolver backendResolver) {
        this.resolver = backendResolver;
        if (running) {
            return true;
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(new KeyManager[] {new SniKeyManager()}, null, null);
            serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(port);
            running = true;
            Thread.ofVirtual().name("msk-iam-accept").start(this::acceptLoop);
            LOG.infov("MSK SASL/IAM listener started on port {0}", Integer.toString(serverSocket.getLocalPort()));
            return true;
        } catch (Exception e) {
            LOG.warnv("MSK SASL/IAM listener could not start on port {0}: {1}", Integer.toString(port), e.getMessage());
            return false;
        }
    }

    /** The bound port, for tests that start the listener on an ephemeral port. */
    synchronized int localPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    @PreDestroy
    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                LOG.debugv("Error closing the MSK SASL/IAM listener: {0}", e.getMessage());
            }
            serverSocket = null;
        }
    }

    private void acceptLoop() {
        SSLServerSocket listening = serverSocket;
        while (running && listening != null && !listening.isClosed()) {
            try {
                Socket client = listening.accept();
                Thread.ofVirtual().name("msk-iam-conn").start(() -> handleConnection((SSLSocket) client));
            } catch (IOException e) {
                if (running) {
                    LOG.debugv("MSK SASL/IAM accept error: {0}", e.getMessage());
                }
            }
        }
    }

    private void handleConnection(SSLSocket client) {
        Socket backend = null;
        try {
            client.setTcpNoDelay(true);
            client.startHandshake();
            String brokerHost = requestedHost(client.getSession());
            Optional<String> backendAddress = brokerHost == null ? Optional.empty() : resolver.backendFor(brokerHost);
            if (backendAddress.isEmpty()) {
                LOG.debugv("MSK SASL/IAM connection for unknown broker {0} closed", brokerHost);
                closeQuietly(client);
                return;
            }
            Socket broker = connect(backendAddress.get());
            backend = broker;
            DataInputStream brokerIn = new DataInputStream(broker.getInputStream());
            OutputStream brokerOut = broker.getOutputStream();
            boolean authenticated = saslServer.authenticate(client.getInputStream(), client.getOutputStream(),
                    frame -> {
                        KafkaSaslServer.writeFrame(brokerOut, frame);
                        byte[] response = KafkaSaslServer.readFrame(brokerIn);
                        if (response == null) {
                            throw new EOFException("Broker closed the connection");
                        }
                        return response;
                    });
            if (!authenticated) {
                closeQuietly(client);
                closeQuietly(broker);
                return;
            }
            bridge(client, broker);
        } catch (Exception e) {
            LOG.debugv("MSK SASL/IAM connection error: {0}", e.getMessage());
            closeQuietly(client);
            if (backend != null) {
                closeQuietly(backend);
            }
        }
    }

    private static Socket connect(String address) throws IOException {
        int separator = address.lastIndexOf(':');
        String host = address.substring(0, separator);
        int backendPort = Integer.parseInt(address.substring(separator + 1));
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, backendPort), BACKEND_CONNECT_TIMEOUT_MILLIS);
        socket.setTcpNoDelay(true);
        return socket;
    }

    /** The server name the client asked for, lowercased; null when it sent none. */
    static String requestedHost(SSLSession session) {
        if (!(session instanceof ExtendedSSLSession extended)) {
            return null;
        }
        return extended.getRequestedServerNames().stream()
                .filter(SNIHostName.class::isInstance)
                .map(name -> ((SNIHostName) name).getAsciiName().toLowerCase(Locale.ROOT))
                .findFirst()
                .orElse(null);
    }

    /** A certificate for {@code host}, issued once by the local CA, when the host names a known broker. */
    private String aliasFor(String keyType, SSLSession handshakeSession) {
        if (!"RSA".equalsIgnoreCase(keyType)) {
            return null;
        }
        String host = requestedHost(handshakeSession);
        if (host == null || resolver.backendFor(host).isEmpty()) {
            return null;
        }
        keyMaterialByHost.computeIfAbsent(host, this::issue);
        return host;
    }

    private KeyMaterial issue(String host) {
        CertificateGenerator.GeneratedCertificate generated =
                certificateAuthority.issueServerCertificate(host, List.of(host), KeyAlgorithm.RSA_2048, null);
        X509Certificate leaf = certificateParser.parseCertificate(generated.certificatePem());
        PrivateKey key = certificateParser.parsePrivateKey(generated.privateKeyPem());
        return new KeyMaterial(new X509Certificate[] {leaf, certificateAuthority.certificate()}, key);
    }

    /** Presents, per TLS server name, the certificate issued for that broker hostname. */
    private final class SniKeyManager extends X509ExtendedKeyManager {

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return socket instanceof SSLSocket ssl ? aliasFor(keyType, ssl.getHandshakeSession()) : null;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return engine != null ? aliasFor(keyType, engine.getHandshakeSession()) : null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            KeyMaterial material = alias != null ? keyMaterialByHost.get(alias) : null;
            return material != null ? material.chain().clone() : null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            KeyMaterial material = alias != null ? keyMaterialByHost.get(alias) : null;
            return material != null ? material.key() : null;
        }
    }

    /**
     * Relays both directions until either side closes. Relay I/O runs on platform daemon threads,
     * as the ElastiCache auth proxy does, so a busy connection cannot starve the others.
     */
    private void bridge(Socket client, Socket broker) {
        CountDownLatch firstRelayDone = new CountDownLatch(1);
        Thread toBroker = Thread.ofPlatform().daemon(true).name("msk-iam-relay-c2b").start(() -> {
            try {
                relay(client, broker);
            } finally {
                firstRelayDone.countDown();
            }
        });
        Thread toClient = Thread.ofPlatform().daemon(true).name("msk-iam-relay-b2c").start(() -> {
            try {
                relay(broker, client);
            } finally {
                firstRelayDone.countDown();
            }
        });
        try {
            firstRelayDone.await();
            toBroker.join(RELAY_JOIN_TIMEOUT_MILLIS);
            toClient.join(RELAY_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(client);
            closeQuietly(broker);
        }
    }

    private static void relay(Socket from, Socket to) {
        byte[] buffer = new byte[16 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // Normal when either side closes the connection.
        } finally {
            if (!(to instanceof SSLSocket)) {
                try {
                    to.shutdownOutput();
                } catch (IOException ignored) {
                    // The bridge closes both sockets once both directions finish.
                }
            } else {
                closeQuietly(to);
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }
}
