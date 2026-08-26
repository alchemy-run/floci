package io.github.hectorvent.floci.services.timestream;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.dsql.proxy.DsqlTls;
import io.quarkus.vertx.http.HttpServer;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * TLS terminator used when the gateway itself is HTTP-only ({@code quarkus:dev}
 * without {@code FLOCI_TLS_ENABLED}).
 *
 * <p>Alchemy's Timestream client always prefixes {@code DescribeEndpoints}'
 * {@code Address} with {@code https://}. On a TLS-enabled gateway the echoed
 * Host already speaks HTTPS. On HTTP-only, this bean binds an ephemeral HTTPS
 * port, terminates TLS, and pipes plaintext to the gateway so the rewrite
 * still lands here.
 */
@ApplicationScoped
public class TimestreamDiscoveryTls {

    private static final Logger LOG = Logger.getLogger(TimestreamDiscoveryTls.class);
    private static final List<String> SANS = List.of(
            "localhost",
            "127.0.0.1",
            "::1",
            "host.docker.internal",
            "*.localhost",
            // Lambda AWS_ENDPOINT_URL is http://localhost.floci.io:4566 when
            // embedded DNS is on; Alchemy prefixes DescribeEndpoints' Address
            // with https://, so the cert must cover that hostname.
            "localhost.floci.io",
            "*.localhost.floci.io",
            "localhost.localstack.cloud",
            "*.localhost.localstack.cloud");

    private static final String CERT_CN = "floci-timestream-discovery";

    private final EmulatorConfig config;
    private final Vertx vertx;
    private final HttpServer gateway;
    private final CertificateGenerator certificates;
    private final DsqlTls dsqlTls;

    private NetServer server;
    private NetClient client;
    private int listenPort = -1;

    @Inject
    public TimestreamDiscoveryTls(EmulatorConfig config, Vertx vertx, HttpServer gateway,
                                  CertificateGenerator certificates, DsqlTls dsqlTls) {
        this.config = config;
        this.vertx = vertx;
        this.gateway = gateway;
        this.certificates = certificates;
        this.dsqlTls = dsqlTls;
    }

    TimestreamDiscoveryTls() {
        this.config = null;
        this.vertx = null;
        this.gateway = null;
        this.certificates = null;
        this.dsqlTls = null;
    }

    @PostConstruct
    void start() {
        if (config == null || config.tls().enabled()) {
            return;
        }
        try {
            Path tlsDir = Path.of(config.storage().persistentPath(), "tls");
            Path certFile = tlsDir.resolve("timestream-discovery.crt");
            Path keyFile = tlsDir.resolve("timestream-discovery.key");
            String certPem = null;
            String keyPem = null;
            if (Files.isReadable(certFile) && Files.isReadable(keyFile)) {
                certPem = Files.readString(certFile);
                keyPem = Files.readString(keyFile);
                if (!coversRequiredSans(certPem)) {
                    certPem = null;
                    keyPem = null;
                }
            }
            if (certPem == null) {
                CertificateGenerator.GeneratedCertificate generated =
                        certificates.generateSelfSignedCertificate(CERT_CN, SANS, KeyAlgorithm.RSA_2048);
                Files.createDirectories(tlsDir);
                certPem = generated.certificatePem();
                keyPem = generated.privateKeyPem();
                Files.writeString(certFile, certPem);
                Files.writeString(keyFile, keyPem);
            }
            shareCaWithLambda(certPem);
            PemKeyCertOptions pem = new PemKeyCertOptions()
                    .setCertValue(Buffer.buffer(certPem))
                    .setKeyValue(Buffer.buffer(keyPem));
            client = vertx.createNetClient();
            server = vertx.createNetServer(new NetServerOptions()
                    .setHost("0.0.0.0")
                    .setPort(0)
                    .setSsl(true)
                    .setUseAlpn(false)
                    .setKeyCertOptions(pem));
            int backend = gatewayPort();
            server.connectHandler(front -> {
                front.pause();
                client.connect(backend, "127.0.0.1").onComplete(ar -> {
                    if (!ar.succeeded()) {
                        LOG.debugv("Timestream discovery TLS: backend connect failed: {0}",
                                ar.cause().getMessage());
                        front.close();
                        return;
                    }
                    var back = ar.result();
                    front.pipeTo(back);
                    back.pipeTo(front);
                });
            });
            server.listen().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            listenPort = server.actualPort();
            LOG.infov("Timestream discovery TLS: https://0.0.0.0:{0} → HTTP {1}",
                    String.valueOf(listenPort), String.valueOf(backend));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Timestream discovery TLS: start interrupted");
        } catch (Exception e) {
            LOG.warnv("Timestream discovery TLS: failed to start ({0}); "
                    + "DescribeEndpoints will echo the inbound Host", e.getMessage());
            listenPort = -1;
        }
    }

    @PreDestroy
    void stop() {
        if (server != null) {
            server.close();
        }
        if (client != null) {
            client.close();
        }
    }

    /**
     * Address Alchemy should use after the {@code https://} rewrite. When the
     * gateway already speaks TLS, this is the inbound Host; otherwise the
     * hostname is kept and the port is replaced with this terminator.
     */
    public String httpsAddress(String inboundHost) {
        String address = inboundHost == null || inboundHost.isBlank()
                ? "localhost:" + defaultPort()
                : inboundHost.trim();
        if (address.startsWith("[") && address.contains("]")) {
            int end = address.indexOf(']');
            address = address.substring(1, end) + address.substring(end + 1);
        }
        if (listenPort <= 0) {
            return address;
        }
        return replacePort(address, listenPort);
    }

    int listenPort() {
        return listenPort;
    }

    private int gatewayPort() {
        int actual = gateway.getPort();
        return actual > 0 ? actual : defaultPort();
    }

    private int defaultPort() {
        return config != null ? config.port() : 4566;
    }

    /**
     * Lambda containers already receive the DSQL CA via {@code NODE_EXTRA_CA_CERTS}
     * even when gateway TLS is off. Append this terminator's CA so in-container
     * {@code https://host.docker.internal:&lt;port&gt;} discovery succeeds.
     */
    private void shareCaWithLambda(String certificatePem) {
        if (dsqlTls == null) {
            return;
        }
        Path bundle = dsqlTls.caCertPath().orElse(null);
        if (bundle == null) {
            return;
        }
        try {
            String existing = Files.readString(bundle);
            String pem = certificatePem.trim();
            if (existing.contains(pem)) {
                return;
            }
            Files.writeString(bundle, existing + "\n" + pem + "\n", StandardOpenOption.APPEND);
            LOG.infov("Timestream discovery TLS: appended CA to {0} for Lambda trust", bundle);
        } catch (Exception e) {
            LOG.warnv("Timestream discovery TLS: could not share CA with Lambda ({0})", e.getMessage());
        }
    }

    boolean coversRequiredSans(String certPem) {
        if (certificates == null || certPem == null || certPem.isBlank()) {
            return false;
        }
        try {
            X509Certificate cert = certificates.parseCertificate(certPem);
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) {
                return false;
            }
            Set<String> names = new HashSet<>();
            for (List<?> san : sans) {
                if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))) {
                    names.add(String.valueOf(san.get(1)));
                }
            }
            return names.contains("localhost.floci.io") && names.contains("host.docker.internal");
        } catch (Exception e) {
            return false;
        }
    }

    static String replacePort(String address, int port) {
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            String host = end >= 0 ? address.substring(0, end + 1) : address;
            return host + ":" + port;
        }
        int colon = address.lastIndexOf(':');
        // Bare IPv6 (already unbracketed, e.g. "::1" or "::1:4566") — keep host, swap port.
        if (colon > 0 && address.indexOf(':') != colon) {
            int last = address.lastIndexOf(':');
            String maybePort = address.substring(last + 1);
            if (maybePort.chars().allMatch(Character::isDigit)) {
                return address.substring(0, last) + ":" + port;
            }
            return address + ":" + port;
        }
        String host = colon > 0 ? address.substring(0, colon) : address;
        return host + ":" + port;
    }
}
