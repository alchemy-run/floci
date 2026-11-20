package io.floci.aws.services.eks;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Fetches the gVisor release that backs the Nitro Sandbox RuntimeClasses on an Auto Mode cluster.
 *
 * <p>gVisor runs each pod on its own user-space kernel (the Sentry), so a pod in a sandbox does
 * not share the host kernel with the others. Its systrap platform needs no KVM, so it works inside
 * Docker Desktop's VM as well as on Linux. The release tarball is downloaded once per version and
 * architecture, checked against its published SHA-512, cached under Floci's data path, and copied
 * into each cluster's k3s container, where containerd gets a {@code runsc} runtime for it.
 */
final class EksGvisorRuntime {

    private static final Logger LOG = Logger.getLogger(EksGvisorRuntime.class);

    /**
     * Where the release is unpacked in the k3s container: on containerd's PATH, and {@code runsc}
     * finds its {@code gvisor-bin} sidecars next to itself.
     */
    static final String INSTALL_DIR = "/bin";
    /** The containerd runtime, and the RuntimeClass handler, that runs pods under gVisor. */
    static final String HANDLER = "runsc";
    private static final String TARBALL = "gvisor.tar.bz2";

    /**
     * containerd 2 (k3s 1.31 and later) reads {@code config-v3.toml.tmpl}; containerd 1.7 reads
     * {@code config.toml.tmpl}. Each extends k3s's generated base config with the runsc runtime.
     */
    static final String CONTAINERD_V3_TEMPLATE_ENTRY = "agent/etc/containerd/config-v3.toml.tmpl";
    static final String CONTAINERD_V2_TEMPLATE_ENTRY = "agent/etc/containerd/config.toml.tmpl";
    static final String CONTAINERD_V3_TEMPLATE = """
            {{ template "base" . }}

            [plugins."io.containerd.cri.v1.runtime".containerd.runtimes.runsc]
              runtime_type = "io.containerd.runsc.v1"
            """;
    static final String CONTAINERD_V2_TEMPLATE = """
            {{ template "base" . }}

            [plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runsc]
              runtime_type = "io.containerd.runsc.v1"
            """;

    private final String releaseUrl;
    private final String version;
    private final Path cacheDir;
    private final HttpClient http;

    EksGvisorRuntime(String releaseUrl, String version, Path cacheDir) {
        this.releaseUrl = releaseUrl.endsWith("/") ? releaseUrl.substring(0, releaseUrl.length() - 1) : releaseUrl;
        this.version = version;
        this.cacheDir = cacheDir;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** gVisor names its release directories {@code x86_64} and {@code aarch64}. */
    static String releaseArch(String dockerArchitecture) {
        String arch = dockerArchitecture == null ? "" : dockerArchitecture.toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "aarch64", "arm64" -> "aarch64";
            case "x86_64", "amd64" -> "x86_64";
            default -> throw new IllegalArgumentException("gVisor has no release for architecture " + dockerArchitecture);
        };
    }

    /**
     * The release tarball for {@code arch}, downloading and verifying it on first use. A cached
     * tarball is trusted: it was verified when it was written, and written atomically.
     */
    synchronized Path tarball(String arch) throws IOException, InterruptedException {
        Path target = cacheDir.resolve(version).resolve(arch).resolve(TARBALL);
        if (Files.isRegularFile(target)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        String base = releaseUrl + "/" + version + "/" + arch + "/" + TARBALL;
        String expected = expectedSha512(fetchString(base + ".sha512"));
        Path partial = Files.createTempFile(target.getParent(), TARBALL, ".partial");
        try {
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofMinutes(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("GET " + base + " answered " + response.statusCode());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (InputStream body = response.body();
                 OutputStream out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[1 << 16];
                for (int read; (read = body.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("gVisor " + version + "/" + arch + " failed its SHA-512 check");
            }
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LOG.infov("Downloaded gVisor {0} ({1}) for EKS Nitro Sandbox RuntimeClasses", version, arch);
            return target;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** A {@code .sha512} file holds {@code <hex>  <file>}. */
    static String expectedSha512(String file) throws IOException {
        String hex = file.trim().split("\\s+")[0];
        if (!hex.matches("[0-9a-fA-F]{128}")) {
            throw new IOException("unexpected gVisor checksum file: " + file.trim());
        }
        return hex;
    }

    private String fetchString(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("GET " + url + " answered " + response.statusCode());
        }
        return response.body();
    }
}
