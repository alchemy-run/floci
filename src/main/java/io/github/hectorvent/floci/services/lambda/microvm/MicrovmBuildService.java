package io.github.hectorvent.floci.services.lambda.microvm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds a MicroVM image version into a runnable local Docker image.
 *
 * <p>Real AWS runs the Dockerfile server-side and snapshots the result with
 * Firecracker; Floci's local equivalent is a plain {@code docker build} of the
 * uploaded code artifact (a zip containing a Dockerfile + program files).
 * The AWS-managed base image ({@code public.ecr.aws/lambda/microvms:*}) is not
 * publicly pullable, so {@code FROM} lines referencing it are rewritten to a
 * locally-resolvable Amazon Linux 2023 image, which supports the same
 * {@code dnf} package installs the generated Dockerfiles rely on.
 */
@ApplicationScoped
public class MicrovmBuildService {

    private static final Logger LOG = Logger.getLogger(MicrovmBuildService.class);

    /** {@code FROM} images matching this prefix are rewritten to {@link #localBaseImage()}. */
    private static final String AWS_MICROVM_BASE_PREFIX = "public.ecr.aws/lambda/microvms";
    // ECR Public mirror of amazonlinux:2023 — same dnf surface as the AWS
    // MicroVM base, pullable without Docker Hub auth/rate limits.
    private static final String DEFAULT_LOCAL_BASE_IMAGE = "public.ecr.aws/amazonlinux/amazonlinux:2023";

    private static final Pattern S3_URI = Pattern.compile("^s3://([^/]+)/(.+)$");
    /**
     * Dev-mode artifact form: a pre-built local Docker image reference,
     * {@code docker://<ref>}. Alchemy's `alchemy dev` builds the image on the
     * HOST daemon itself (real BuildKit layer caching against the user's
     * actual build context), so Floci only has to validate the image exists
     * and run it — no S3 round-trip, no zip extraction, no server-side build.
     */
    private static final Pattern DOCKER_URI = Pattern.compile("^docker://(.+)$");
    private static final long BUILD_TIMEOUT_MINUTES = 15;
    /**
     * Fixed mtime applied to extracted zip entries. The classic docker
     * builder keys COPY-layer cache on file metadata; a fresh extract with
     * "now" mtimes invalidated every layer from the first COPY onward on
     * every build, so identical content never cached.
     */
    private static final java.nio.file.attribute.FileTime FIXED_MTIME =
            java.nio.file.attribute.FileTime.fromMillis(0L);

    private final S3Service s3Service;
    private final ContainerLifecycleManager lifecycleManager;

    @Inject
    public MicrovmBuildService(S3Service s3Service, ContainerLifecycleManager lifecycleManager) {
        this.s3Service = s3Service;
        this.lifecycleManager = lifecycleManager;
    }

    static String localBaseImage() {
        String override = System.getenv("FLOCI_MICROVM_BASE_IMAGE");
        return override != null && !override.isBlank() ? override : DEFAULT_LOCAL_BASE_IMAGE;
    }

    /**
     * Fetches the code artifact from S3, extracts it, rewrites the Dockerfile
     * base image, and runs {@code docker build}. Returns the tag the image was
     * built under. Throws with a human-readable message on any failure — the
     * caller records it as the version's {@code stateReason}.
     */
    public String build(String artifactUri, String imageTag) {
        String localRef = localImageRef(artifactUri);
        if (localRef != null) {
            return resolveLocalImage(localRef);
        }
        byte[] zipBytes = fetchArtifact(artifactUri);
        Path contextDir = null;
        try {
            contextDir = Files.createTempDirectory("floci-microvm-build-");
            extractZip(zipBytes, contextDir);
            Path dockerfile = contextDir.resolve("Dockerfile");
            if (!Files.exists(dockerfile)) {
                throw new IllegalStateException(
                        "Code artifact has no Dockerfile at its root: " + artifactUri);
            }
            rewriteBaseImage(dockerfile);

            LOG.infov("Building MicroVM image {0} from {1}", imageTag, artifactUri);
            String imageId = lifecycleManager.getDockerClient().buildImageCmd()
                    .withBaseDirectory(contextDir.toFile())
                    .withDockerfile(dockerfile.toFile())
                    .withTags(Set.of(imageTag))
                    .exec(new BuildImageResultCallback())
                    .awaitImageId(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            LOG.infov("Built MicroVM image {0} ({1})", imageTag, imageId);
            return imageTag;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare MicroVM build context: " + e.getMessage(), e);
        } finally {
            if (contextDir != null) {
                deleteRecursively(contextDir);
            }
        }
    }

    /**
     * The image reference of a {@code docker://} artifact URI, or
     * {@code null} for any other (or absent) artifact form.
     * Package-private for unit testing.
     */
    static String localImageRef(String artifactUri) {
        Matcher m = DOCKER_URI.matcher(artifactUri != null ? artifactUri : "");
        return m.matches() ? m.group(1) : null;
    }

    /**
     * A {@code docker://} artifact: the image was built on the host daemon by
     * the caller. Validate it exists and use its reference as the version's
     * tag — the run path is identical to a Floci-built image.
     */
    private String resolveLocalImage(String imageRef) {
        try {
            lifecycleManager.getDockerClient().inspectImageCmd(imageRef).exec();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Local Docker image not found for docker:// artifact: " + imageRef
                            + " (" + e.getMessage() + ")");
        }
        LOG.infov("Using pre-built local Docker image {0} as MicroVM image", imageRef);
        return imageRef;
    }

    /** Best-effort removal of a version's built Docker image tag. */
    public void removeImage(String imageTag) {
        try {
            lifecycleManager.getDockerClient().removeImageCmd(imageTag).withForce(true).exec();
        } catch (Exception e) {
            LOG.debugv("Could not remove MicroVM docker image {0}: {1}", imageTag, e.getMessage());
        }
    }

    private byte[] fetchArtifact(String artifactUri) {
        Matcher m = S3_URI.matcher(artifactUri != null ? artifactUri : "");
        if (!m.matches()) {
            throw new AwsException("ValidationException",
                    "codeArtifact.uri must be an s3:// URI, got: " + artifactUri, 400);
        }
        try {
            S3Object object = s3Service.getObject(m.group(1), m.group(2));
            return object.getData();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to fetch code artifact " + artifactUri + ": " + e.getMessage(), e);
        }
    }

    private static void extractZip(byte[] zipBytes, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = targetDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetDir)) {
                    throw new IOException("Zip entry escapes build context: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.write(target, zis.readAllBytes());
                    // Deterministic metadata so identical content produces
                    // docker layer-cache hits across rebuilds.
                    Files.setLastModifiedTime(target, FIXED_MTIME);
                }
                zis.closeEntry();
            }
        }
    }

    // Package-private for unit testing.
    static void rewriteBaseImage(Path dockerfile) throws IOException {
        List<String> lines = Files.readAllLines(dockerfile);
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.stripLeading();
            if (trimmed.regionMatches(true, 0, "FROM ", 0, 5)
                    && trimmed.substring(5).stripLeading().startsWith(AWS_MICROVM_BASE_PREFIX)) {
                // Preserve any `AS <stage>` alias after the image reference.
                String rest = trimmed.substring(5).stripLeading();
                int space = rest.indexOf(' ');
                String suffix = space >= 0 ? rest.substring(space) : "";
                lines.set(i, "FROM " + localBaseImage() + suffix);
                changed = true;
            }
        }
        if (changed) {
            Files.write(dockerfile, lines);
            LOG.debugv("Rewrote MicroVM base image to {0} in {1}", localBaseImage(), dockerfile);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
