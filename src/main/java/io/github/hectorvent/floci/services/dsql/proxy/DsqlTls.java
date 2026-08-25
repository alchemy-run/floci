package io.github.hectorvent.floci.services.dsql.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

/**
 * Self-signed CA + server certificate covering {@code *.dsql.{region}.on.aws}
 * so Lambda {@code sslmode=require} trusts the DSQL postgres proxy.
 */
@ApplicationScoped
public class DsqlTls {

    private static final Logger LOG = Logger.getLogger(DsqlTls.class);
    static final List<String> SANS = List.of(
            "localhost",
            "127.0.0.1",
            "*.dsql.us-east-1.on.aws",
            "*.dsql.us-east-2.on.aws",
            "*.dsql.us-west-1.on.aws",
            "*.dsql.us-west-2.on.aws",
            "*.dsql.eu-west-1.on.aws",
            "*.dsql.eu-central-1.on.aws",
            "*.dsql.ap-southeast-1.on.aws",
            "*.dsql.ap-northeast-1.on.aws");

    private final Path certPath;
    private final SSLContext sslContext;

    @Inject
    public DsqlTls(EmulatorConfig config, CertificateGenerator generator) {
        Path dir = Path.of(config.storage().persistentPath(), "tls");
        this.certPath = dir.resolve("dsql-ca.crt");
        Path keyPath = dir.resolve("dsql-ca.key");
        try {
            Files.createDirectories(dir);
            if (!Files.exists(certPath) || !Files.exists(keyPath)) {
                CertificateGenerator.GeneratedCertificate generated =
                        generator.generateSelfSignedCertificate("dsql", SANS, KeyAlgorithm.RSA_2048);
                Files.writeString(certPath, generated.certificatePem());
                Files.writeString(keyPath, generated.privateKeyPem());
                LOG.infov("Generated DSQL TLS certificate: {0}", certPath);
            }
            this.sslContext = sslContextFromPem(
                    Files.readString(certPath), Files.readString(keyPath), generator);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize DSQL TLS material", e);
        }
    }

    public SSLContext sslContext() {
        return sslContext;
    }

    public Optional<Path> caCertPath() {
        return Files.isReadable(certPath) ? Optional.of(certPath) : Optional.empty();
    }

    static SSLContext sslContextFromPem(String certPem, String keyPem, CertificateGenerator generator)
            throws Exception {
        X509Certificate certificate = generator.parseCertificate(certPem);
        PrivateKey privateKey = generator.parsePrivateKey(keyPem);
        char[] password = new char[0];
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry("floci-dsql", privateKey, password, new java.security.cert.Certificate[] {certificate});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, new SecureRandom());
        return context;
    }
}
