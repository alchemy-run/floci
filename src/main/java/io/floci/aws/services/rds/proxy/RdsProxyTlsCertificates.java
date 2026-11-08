package io.floci.aws.services.rds.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.CertificateMetadata;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.config.FlociVersion;
import io.floci.aws.config.ServerCertificateRenewal;
import io.floci.aws.services.acm.CertificateGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Owns the RDS auth proxy's in-memory server certificate signed by Floci's shared root CA.
 * The server certificate's SANs cover every advertised Postgres/MySQL host.
 *
 * <p>The shared CA persists at {@code {persistent-path}/tls/floci-root-ca.{crt,key}}. The
 * {@code rds-ca.crt} compatibility copy preserves existing client paths, and
 * {@code rds-ca.metadata.json} records the current SAN list. A client trusts the shared CA once
 * (e.g. {@code sslmode=verify-full sslrootcert=<path>}) to keep working across every local
 * database and every restart, including when advertised hosts change.
 */
@ApplicationScoped
public class RdsProxyTlsCertificates {

    private static final Logger LOG = Logger.getLogger(RdsProxyTlsCertificates.class);

    private static final List<String> DEFAULT_SANS = List.of("localhost", "127.0.0.1");
    private static final String METADATA_NAME = "rds-ca.metadata.json";
    /**
     * The account-and-Region label of a routed endpoint name ({@code endpoint-domain}): RDS
     * {@code c<11 hex>} under an instance, {@code cluster-} or {@code cluster-ro-}, and Redshift's
     * 12 hex digits. AWS covers each with one wildcard, and so does Floci: listing every name
     * instead grew the certificate past OpenSSL's 100 KB limit after enough databases.
     */
    private static final Pattern ROUTED_TOKEN = Pattern.compile("(cluster-(ro-)?)?c[0-9a-f]{11}|[0-9a-f]{12}");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final FlociCertificateAuthority certificateAuthority;
    private final ServerCertificateRenewal renewal;
    private final Object lock = new Object();

    private volatile ServerCertificate current;

    private record ServerCertificate(ServerCertificateRenewal.Leaf leaf, Set<String> sans) {
    }

    @Inject
    public RdsProxyTlsCertificates(FlociCertificateAuthority certificateAuthority, CertificateGenerator certificateGenerator) {
        this(certificateAuthority, certificateGenerator, Clock.systemUTC());
    }

    RdsProxyTlsCertificates(FlociCertificateAuthority certificateAuthority, CertificateGenerator certificateGenerator,
                            Clock clock) {
        this.certificateAuthority = certificateAuthority;
        this.renewal = new ServerCertificateRenewal(certificateAuthority, certificateGenerator, clock);
    }

    /**
     * Ensures {@code host} is covered by the server certificate, issuing a new leaf when needed.
     * The persisted CA is unchanged, so existing client trust stores continue to work.
     */
    public void ensureHost(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        host = coveringName(host);
        ensureInitialized();
        if (current.sans().contains(host)) {
            return;
        }
        synchronized (lock) {
            if (current.sans().contains(host)) {
                return;
            }
            Set<String> updated = new LinkedHashSet<>(current.sans());
            updated.add(host);
            regenerate(updated);
        }
    }

    /**
     * Returns the current TLS server context. Handlers should call this per-handshake rather than
     * caching it themselves; {@link #ensureHost(String)} can swap it out at any time.
     */
    public SSLContext sslContext() {
        ensureInitialized();
        return current.leaf().context();
    }

    private void ensureInitialized() {
        if (!needsRenewal()) {
            return;
        }
        synchronized (lock) {
            if (!needsRenewal()) {
                return;
            }
            if (current != null) {
                regenerate(current.sans());
                return;
            }
            Path tlsDir = tlsDir();
            Set<String> sans = new LinkedHashSet<>(DEFAULT_SANS);
            Path metadataFile = tlsDir.resolve(METADATA_NAME);
            if (Files.exists(metadataFile)) {
                try {
                    readMetadata(metadataFile).forEach(saved -> sans.add(coveringName(saved)));
                } catch (IOException e) {
                    LOG.warnv(e, "RDS proxy TLS: invalid SAN metadata at {0}; keeping the CA and using default hosts",
                            metadataFile);
                }
            }
            refreshLegacyCaFiles();
            regenerate(sans);
            logTrustHint(certificateAuthority.certificatePath());
        }
    }

    private void refreshLegacyCaFiles() {
        Path temporary = null;
        try {
            Path tlsDir = tlsDir();
            temporary = Files.createTempFile(tlsDir, "rds-ca-", ".tmp");
            Files.writeString(temporary, certificateAuthority.caPem());
            // Replace the path itself, never a legacy symlink's target or a shared inode.
            Files.move(temporary, tlsDir.resolve("rds-ca.crt"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(tlsDir.resolve("rds-ca.key"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to refresh RDS proxy TLS CA compatibility files", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    LOG.warnv(e, "RDS proxy TLS: could not remove temporary CA file {0}", temporary);
                }
            }
        }
    }

    /**
     * The SAN that covers {@code host}: {@code *.<token>.<region>.<domain>} for a routed endpoint
     * name, the name itself otherwise. A wildcard covers exactly one label, the resource's id.
     */
    static String coveringName(String host) {
        String[] labels = host.split("\\.");
        if (labels.length >= 5 && !labels[0].equals("*") && ROUTED_TOKEN.matcher(labels[1]).matches()) {
            return "*" + host.substring(labels[0].length());
        }
        return host;
    }

    private void regenerate(Set<String> sans) {
        try {
            List<String> sanList = new ArrayList<>(sans);
            ServerCertificateRenewal.Leaf leaf = renewal.issue(sanList);
            writeMetadata(tlsDir().resolve(METADATA_NAME), sanList);
            current = new ServerCertificate(leaf, Set.copyOf(sans));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RDS proxy TLS certificate", e);
        }
    }

    private boolean needsRenewal() {
        ServerCertificate certificate = current;
        return renewal.due(certificate == null ? null : certificate.leaf());
    }

    private Path tlsDir() {
        return certificateAuthority.certificatePath().getParent();
    }

    private void logTrustHint(Path certFile) {
        LOG.infov("RDS proxy TLS: for sslmode=verify-full set PGSSLROOTCERT={0}",
                certFile.toAbsolutePath());
    }

    private List<String> readMetadata(Path metadataFile) throws IOException {
        CertificateMetadata metadata = OBJECT_MAPPER.readValue(metadataFile.toFile(), CertificateMetadata.class);
        List<String> hostnames = metadata != null ? metadata.getHostnames() : null;
        if (hostnames == null || hostnames.isEmpty()
                || hostnames.stream().anyMatch(host -> host == null || host.isBlank())) {
            throw new IOException("RDS proxy TLS metadata file has invalid hostnames: " + metadataFile);
        }
        return hostnames;
    }

    private void writeMetadata(Path metadataFile, List<String> sans) throws IOException {
        CertificateMetadata metadata = CertificateMetadata.create(sans, FlociVersion.current());
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
        Files.writeString(metadataFile, json);
    }
}
