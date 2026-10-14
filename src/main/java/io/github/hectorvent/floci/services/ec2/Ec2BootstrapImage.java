package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import io.quarkus.arc.ClientProxy;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Prepares only stock catalog images, outside the guest's security-group namespace. */
final class Ec2BootstrapImage {
    private static final ObjectMapper JSON = new ObjectMapper();

    // Exact catalog references and their Docker Hub spellings, not arbitrary minimal-runtime images.
    static final Map<String, String> STOCK_IMAGES = Map.of(
            "amazonlinux:2", "public.ecr.aws/amazonlinux/amazonlinux:2",
            "amazonlinux:2023", "public.ecr.aws/amazonlinux/amazonlinux:2023",
            "ubuntu:20.04", "public.ecr.aws/docker/library/ubuntu:20.04",
            "ubuntu:22.04", "public.ecr.aws/docker/library/ubuntu:22.04",
            "ubuntu:24.04", "public.ecr.aws/docker/library/ubuntu:24.04",
            "debian:12", "public.ecr.aws/docker/library/debian:12",
            "alpine:latest", "public.ecr.aws/docker/library/alpine:latest");

    // Floci-owned generated recipe; all package downloads happen at Docker build time.
    static final String RECIPE = """
            FROM %s%s
            RUN set -eu; \\
                if command -v apt-get >/dev/null 2>&1; then \\
                  apt-get update -qq; \\
                  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl ca-certificates openssh-server openssh-client iproute2 python3; \\
                  rm -rf /var/lib/apt/lists/*; \\
                elif command -v dnf >/dev/null 2>&1; then \\
                  dnf install -y --allowerasing curl ca-certificates openssh-server openssh-clients iproute python3; \\
                  dnf clean all; \\
                elif command -v yum >/dev/null 2>&1; then \\
                  yum install -y curl ca-certificates openssh-server openssh-clients iproute python3; \\
                  yum clean all; \\
                elif command -v apk >/dev/null 2>&1; then \\
                  apk add --no-cache curl ca-certificates openssh iproute2 python3; \\
                else exit 1; fi; \\
                rm -f /etc/ssh/ssh_host_*; \\
                command -v curl; command -v sshd; command -v scp; command -v ip; command -v python3; \\
                test -s /etc/ssl/certs/ca-certificates.crt || test -s /etc/pki/tls/certs/ca-bundle.crt
            """;

    private final DockerClient docker;
    private final ContainerBuilder builder;
    private final ContainerLifecycleManager lifecycle;
    private final ImageCacheService imageCache;

    Ec2BootstrapImage(DockerClient docker, ContainerBuilder builder, ContainerLifecycleManager lifecycle,
                      ImageCacheService imageCache) {
        this.docker = docker;
        this.builder = builder;
        this.lifecycle = lifecycle;
        this.imageCache = imageCache;
    }

    static boolean supports(ResolvedAmiImage image) {
        return !image.systemd() && !image.cloudInit()
                && (STOCK_IMAGES.containsKey(image.dockerImage()) || STOCK_IMAGES.containsValue(image.dockerImage()));
    }

    ResolvedAmiImage prepare(ResolvedAmiImage image) {
        return supports(image) ? prepareStockImage(image) : image;
    }

    private synchronized ResolvedAmiImage prepareStockImage(ResolvedAmiImage image) {
        if (image.dockerPlatform() != null && !image.dockerPlatform().isBlank()
                && !image.dockerPlatform().matches("[a-z0-9_-]+/[a-z0-9_-]+(?:/[a-z0-9_.-]+)?")) {
            throw new IllegalArgumentException("Invalid stock EC2 image platform");
        }
        String source = resolveSource(image);
        String target = builder.resolveImage("floci/ec2-bootstrap:" + cacheKey(source, image.dockerPlatform(), RECIPE));
        try {
            docker.inspectImageCmd(target).exec();
        } catch (NotFoundException missing) {
            build(source, target, image.dockerPlatform());
        }
        return new ResolvedAmiImage(target, image.guestRuntime(), image.cloudInit(), image.dockerPlatform());
    }

    private String resolveSource(ResolvedAmiImage image) {
        ContainerSpec spec = builder.newContainer(image.dockerImage())
                .withNetworkMode("none")
                .withCmd(List.of("true"))
                .withLabels(Map.of("floci.ec2-bootstrap-source", "true"))
                .build();
        String containerId = lifecycle.create(spec, image.dockerPlatform());
        try {
            SelectedImage selected = inspectSource(containerId, image.dockerPlatform());
            String source = selected.digest();
            if (source == null || !source.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalStateException("Docker did not resolve a stock EC2 image to an immutable ID");
            }
            if (selected.manifest()) {
                String reference = builder.resolveImage(image.dockerImage());
                int tag = reference.lastIndexOf(':');
                String repository = tag > reference.lastIndexOf('/') ? reference.substring(0, tag) : reference;
                // A manifest inside an index is not taggable until it has its own repository reference.
                imageCache.ensureImageExists(repository + "@" + source, image.dockerPlatform());
            }
            return source;
        } finally {
            lifecycle.removeIfExistsStrict(containerId);
        }
    }

    private record SelectedImage(String digest, boolean manifest) {}

    private SelectedImage inspectSource(String containerId, String requestedPlatform) {
        if (!(ClientProxy.unwrap(docker) instanceof DockerClientImpl client)) {
            throw new IllegalStateException("Docker client does not expose its shared HTTP transport");
        }
        // The SDK's versioned inspection omits the selected manifest and can report the host image ID.
        DockerHttpClient.Request request = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.GET)
                .path("/containers/" + containerId + "/json")
                .build();
        try (DockerHttpClient.Response response = client.getHttpClient().execute(request)) {
            if (response.getStatusCode() != 200) {
                throw new IllegalStateException("Docker container inspection failed with HTTP " + response.getStatusCode());
            }
            JsonNode inspection = JSON.readTree(response.getBody());
            if (inspection == null || !inspection.isObject()) {
                throw new IllegalStateException("Docker returned invalid container inspection data");
            }
            JsonNode descriptor = inspection.get("ImageManifestDescriptor");
            if (descriptor == null) {
                // Classic image stores have no descriptor and their Image field identifies a single platform.
                return new SelectedImage(inspection.path("Image").textValue(), false);
            }
            if (!descriptor.isObject()) {
                throw new IllegalStateException("Docker returned an invalid selected image manifest descriptor");
            }
            JsonNode platform = descriptor.path("platform");
            String os = platform.path("os").textValue();
            String architecture = platform.path("architecture").textValue();
            JsonNode variant = platform.get("variant");
            if (os == null || !os.matches("[a-z0-9_-]+")
                    || architecture == null || !architecture.matches("[a-z0-9_-]+")
                    || (variant != null && (!variant.isTextual() || !variant.textValue().matches("[a-z0-9_.-]+")))) {
                throw new IllegalStateException("Docker returned an invalid selected image platform");
            }
            if (requestedPlatform != null && !requestedPlatform.isBlank()) {
                String[] requested = requestedPlatform.split("/");
                if (!requested[0].equals(os) || !requested[1].equals(architecture)
                        || (requested.length == 3 && (variant == null || !requested[2].equals(variant.textValue())))) {
                    throw new IllegalStateException("Docker selected an image that does not match platform " + requestedPlatform);
                }
            }
            return new SelectedImage(descriptor.path("digest").textValue(), true);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect the stock EC2 bootstrap source image", e);
        }
    }

    private void build(String source, String target, String platform) {
        // BuildKit requires a named FROM reference, pinned here to the already-resolved local ID.
        String sourceRepository = builder.resolveImage("floci/ec2-bootstrap-source");
        String sourceTag = source.substring("sha256:".length());
        docker.tagImageCmd(source, sourceRepository, sourceTag).exec();
        String platformOption = platform == null || platform.isBlank() ? "" : "--platform=" + platform + " ";
        byte[] content = RECIPE.formatted(platformOption, sourceRepository + ":" + sourceTag)
                .getBytes(StandardCharsets.UTF_8);
        try {
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
                TarArchiveEntry entry = new TarArchiveEntry("Dockerfile");
                entry.setSize(content.length);
                entry.setMode(0644);
                entry.setModTime(0L);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            try (BuildImageResultCallback callback = new BuildImageResultCallback();
                 BuildImageCmd command = docker.buildImageCmd(new ByteArrayInputStream(archive.toByteArray()))) {
                command.withTags(Set.of(target)).withPull(false).withRemove(true);
                if (platform != null && !platform.isBlank()) {
                    command.withPlatform(platform);
                }
                String built = command.exec(callback).awaitImageId(2, TimeUnit.MINUTES);
                if (built == null || built.isBlank()) {
                    throw new IllegalStateException("Docker did not build the stock EC2 bootstrap image");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare the stock EC2 bootstrap image", e);
        }
    }

    static String cacheKey(String source, String platform, String recipe) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((source + "\n" + platform + "\n" + recipe).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
