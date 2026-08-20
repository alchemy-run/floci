package io.github.hectorvent.floci.services.lambda.microvm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmImageRecord;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmImageVersionRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Customer MicroVM images: CRUD, versioning, tags, and the asynchronous local
 * build pipeline. State transitions mirror the AWS Lambda MicroVMs API:
 * CreateMicrovmImage returns CREATING and the image converges to CREATED
 * (version SUCCESSFUL/ACTIVE) or CREATE_FAILED as the background docker build
 * finishes; UpdateMicrovmImage does the same with UPDATING/UPDATED/UPDATE_FAILED
 * on a new version.
 */
@ApplicationScoped
public class MicrovmImageService {

    private static final Logger LOG = Logger.getLogger(MicrovmImageService.class);

    /** The single AWS-managed base image Floci advertises (name must contain "al2023"). */
    static final String MANAGED_BASE_IMAGE_NAME = "al2023-minimal";

    private final MicrovmImageStore imageStore;
    private final MicrovmStore microvmStore;
    private final MicrovmBuildService buildService;

    private final ExecutorService buildPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "microvm-image-build");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public MicrovmImageService(MicrovmImageStore imageStore, MicrovmStore microvmStore,
                               MicrovmBuildService buildService) {
        this.imageStore = imageStore;
        this.microvmStore = microvmStore;
        this.buildService = buildService;
    }

    // ──────────────────────────── managed base images ────────────────────────────

    public String managedBaseImageArn(String region) {
        return "arn:aws:lambda:" + region + ":aws:microvm-image/" + MANAGED_BASE_IMAGE_NAME;
    }

    public Map<String, Object> listManagedImages(String region) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("imageArn", managedBaseImageArn(region));
        item.put("createdAt", 0L);
        return Map.of("items", List.of(item));
    }

    public Map<String, Object> listManagedImageVersions(String region, String imageIdentifier) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("imageArn", imageIdentifier.startsWith("arn:")
                ? imageIdentifier
                : managedBaseImageArn(region));
        item.put("imageVersion", "1");
        item.put("createdAt", 0L);
        return Map.of("items", List.of(item));
    }

    // ──────────────────────────── image CRUD ────────────────────────────

    public Map<String, Object> createImage(String region, String accountId, Map<String, Object> request) {
        String name = requireString(request, "name");
        MicrovmImageRecord existing = imageStore.get(region, name).orElse(null);
        if (existing != null) {
            throw new AwsException("ConflictException",
                    "MicroVM image already exists: " + name, 409);
        }

        MicrovmImageRecord image = new MicrovmImageRecord();
        image.setRegion(region);
        image.setAccountId(accountId);
        image.setName(name);
        image.setImageArn("arn:aws:lambda:" + region + ":" + accountId + ":microvm-image/" + name);
        image.setState("CREATING");
        image.setCreatedAt(System.currentTimeMillis());
        Map<String, String> tags = stringMap(request.get("tags"));
        if (tags != null) {
            image.setTags(new HashMap<>(tags));
        }

        MicrovmImageVersionRecord version = newVersion(image, request);
        imageStore.save(image);
        scheduleBuild(region, name, version.getImageVersion(), false);
        return imageDetailResponse(image, version);
    }

    public Map<String, Object> updateImage(String region, String imageIdentifier, Map<String, Object> request) {
        MicrovmImageRecord image = requireImage(region, imageIdentifier);
        image.setState("UPDATING");
        image.setUpdatedAt(System.currentTimeMillis());
        MicrovmImageVersionRecord version = newVersion(image, request);
        imageStore.save(image);
        scheduleBuild(region, image.getName(), version.getImageVersion(), true);
        return imageDetailResponse(image, version);
    }

    public Map<String, Object> getImage(String region, String imageIdentifier) {
        return imageSummary(requireImage(region, imageIdentifier), true);
    }

    public Map<String, Object> listImages(String region, String nameFilter) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (MicrovmImageRecord image : imageStore.list(region)) {
            if (nameFilter != null && !nameFilter.isBlank()
                    && !image.getName().contains(nameFilter)) {
                continue;
            }
            items.add(imageSummary(image, false));
        }
        return Map.of("items", items);
    }

    public Map<String, Object> deleteImage(String region, String imageIdentifier) {
        MicrovmImageRecord image = requireImage(region, imageIdentifier);
        long active = microvmStore.list(region).stream()
                .filter(m -> image.getImageArn().equals(m.getImageArn()))
                .filter(m -> switch (m.getState()) {
                    case "PENDING", "RUNNING", "SUSPENDING", "SUSPENDED" -> true;
                    default -> false;
                })
                .count();
        if (active > 0) {
            // The Alchemy provider retries the delete on this exact message
            // while its terminated MicroVMs drain.
            throw new AwsException("ValidationException",
                    "Cannot delete MicroVM image " + image.getName() + ": it has "
                            + active + " running MicroVMs", 400);
        }
        for (MicrovmImageVersionRecord version : image.getVersions().values()) {
            if (version.getDockerImageTag() != null) {
                buildService.removeImage(version.getDockerImageTag());
            }
        }
        imageStore.delete(region, image.getName());
        LOG.infov("Deleted MicroVM image {0}", image.getImageArn());
        return Map.of("imageIdentifier", image.getImageArn(), "state", "DELETED");
    }

    // ──────────────────────────── versions & builds ────────────────────────────

    public Map<String, Object> getImageVersion(String region, String imageIdentifier, String imageVersion) {
        MicrovmImageRecord image = requireImage(region, imageIdentifier);
        MicrovmImageVersionRecord version = requireVersion(image, imageVersion);
        return versionResponse(image, version);
    }

    public Map<String, Object> listImageVersions(String region, String imageIdentifier) {
        MicrovmImageRecord image = requireImage(region, imageIdentifier);
        List<Map<String, Object>> items = new ArrayList<>();
        for (MicrovmImageVersionRecord version : image.getVersions().values()) {
            items.add(versionResponse(image, version));
        }
        return Map.of("items", items);
    }

    public Map<String, Object> updateImageVersion(String region, String imageIdentifier,
                                                  String imageVersion, Map<String, Object> request) {
        MicrovmImageRecord image = requireImage(region, imageIdentifier);
        MicrovmImageVersionRecord version = requireVersion(image, imageVersion);
        String status = requireString(request, "status");
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new AwsException("ValidationException", "Invalid version status: " + status, 400);
        }
        version.setStatus(status);
        version.setUpdatedAt(System.currentTimeMillis());
        recomputeLatestVersions(image);
        imageStore.save(image);
        return versionResponse(image, version);
    }

    public Map<String, Object> deleteImageVersion(String region, String imageIdentifier, String imageVersion) {
        MicrovmImageRecord image = requireImage(region, imageIdentifier);
        MicrovmImageVersionRecord version = requireVersion(image, imageVersion);
        if (version.getDockerImageTag() != null) {
            buildService.removeImage(version.getDockerImageTag());
        }
        version.setState("DELETED");
        version.setStatus("INACTIVE");
        version.setDockerImageTag(null);
        version.setUpdatedAt(System.currentTimeMillis());
        recomputeLatestVersions(image);
        imageStore.save(image);
        return Map.of(
                "imageIdentifier", image.getImageArn(),
                "imageVersion", imageVersion,
                "state", "DELETED");
    }

    public Map<String, Object> getImageBuild(String region, String imageIdentifier,
                                             String imageVersion, String buildId) {
        MicrovmImageRecord image = requireImage(region, imageIdentifier);
        MicrovmImageVersionRecord version = requireVersion(image, imageVersion);
        if (!buildId.equals(version.getBuildId())) {
            throw new AwsException("ResourceNotFoundException",
                    "Build not found: " + buildId, 404);
        }
        return buildSummary(image, version);
    }

    public Map<String, Object> listImageBuilds(String region, String imageIdentifier, String imageVersion) {
        MicrovmImageRecord image = requireImage(region, imageIdentifier);
        MicrovmImageVersionRecord version = requireVersion(image, imageVersion);
        List<Map<String, Object>> items = version.getBuildId() == null
                ? List.of()
                : List.of(buildSummary(image, version));
        return Map.of("items", items);
    }

    // ──────────────────────────── tags ────────────────────────────

    /** True when the ARN addresses a MicroVM image (vs a Lambda function etc.). */
    public boolean isMicrovmImageArn(String arn) {
        return arn != null && arn.contains(":microvm-image/");
    }

    public Map<String, String> listTags(String region, String imageArn) {
        return requireImage(region, imageArn).getTags();
    }

    public void tagResource(String region, String imageArn, Map<String, String> tags) {
        MicrovmImageRecord image = requireImage(region, imageArn);
        image.getTags().putAll(tags);
        imageStore.save(image);
    }

    public void untagResource(String region, String imageArn, List<String> tagKeys) {
        MicrovmImageRecord image = requireImage(region, imageArn);
        tagKeys.forEach(image.getTags()::remove);
        imageStore.save(image);
    }

    // ──────────────────────────── resolution helpers ────────────────────────────

    public MicrovmImageRecord requireImage(String region, String imageIdentifier) {
        return imageStore.resolve(region, imageIdentifier)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "MicroVM image not found: " + imageIdentifier, 404));
    }

    public MicrovmImageVersionRecord requireVersion(MicrovmImageRecord image, String imageVersion) {
        MicrovmImageVersionRecord version = image.getVersions().get(imageVersion);
        if (version == null || "DELETED".equals(version.getState())) {
            throw new AwsException("ResourceNotFoundException",
                    "MicroVM image version not found: " + image.getName() + "/" + imageVersion, 404);
        }
        return version;
    }

    // ──────────────────────────── build pipeline ────────────────────────────

    private MicrovmImageVersionRecord newVersion(MicrovmImageRecord image, Map<String, Object> request) {
        MicrovmImageVersionRecord version = new MicrovmImageVersionRecord();
        version.setImageVersion(String.valueOf(image.getNextVersionNumber()));
        image.setNextVersionNumber(image.getNextVersionNumber() + 1);
        version.setState("PENDING");
        version.setStatus("INACTIVE");
        version.setCreatedAt(System.currentTimeMillis());
        version.setBuildId(UUID.randomUUID().toString());
        version.setBuildState("PENDING");
        version.setArchitecture(hostArchitecture());
        // Required members of MicrovmImageBuildSummary; fixed locally.
        version.setChipset("GRAVITON");
        version.setChipsetGeneration("g4");

        Map<String, Object> config = new LinkedHashMap<>();
        for (String key : List.of("baseImageArn", "baseImageVersion", "buildRoleArn", "description",
                "codeArtifact", "logging", "egressNetworkConnectors", "cpuConfigurations",
                "resources", "additionalOsCapabilities", "hooks", "environmentVariables")) {
            Object value = request.get(key);
            if (value != null) {
                config.put(key, value);
            }
        }
        if (!config.containsKey("baseImageArn") || !config.containsKey("buildRoleArn")
                || !config.containsKey("codeArtifact")) {
            throw new AwsException("ValidationException",
                    "baseImageArn, buildRoleArn and codeArtifact are required", 400);
        }
        version.setConfig(config);
        image.getVersions().put(version.getImageVersion(), version);
        return version;
    }

    private void scheduleBuild(String region, String imageName, String imageVersion, boolean isUpdate) {
        buildPool.submit(() -> runBuild(region, imageName, imageVersion, isUpdate));
    }

    private void runBuild(String region, String imageName, String imageVersion, boolean isUpdate) {
        MicrovmImageRecord image = imageStore.get(region, imageName).orElse(null);
        if (image == null) {
            return; // deleted while queued
        }
        MicrovmImageVersionRecord version = image.getVersions().get(imageVersion);
        if (version == null) {
            return;
        }
        version.setState("IN_PROGRESS");
        version.setBuildState("IN_PROGRESS");
        imageStore.save(image);

        String artifactUri = artifactUri(version);
        String tag = dockerTag(region, imageName, imageVersion);
        try {
            buildService.build(artifactUri, tag);
            version.setDockerImageTag(tag);
            version.setState("SUCCESSFUL");
            version.setStatus("ACTIVE");
            version.setBuildState("SUCCESSFUL");
            version.setUpdatedAt(System.currentTimeMillis());
            image.setState(isUpdate ? "UPDATED" : "CREATED");
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.toString();
            LOG.errorv("MicroVM image build failed for {0} v{1}: {2}", imageName, imageVersion, reason);
            version.setState("FAILED");
            version.setStateReason(reason);
            version.setBuildState("FAILED");
            version.setBuildStateReason(reason);
            version.setUpdatedAt(System.currentTimeMillis());
            image.setState(isUpdate ? "UPDATE_FAILED" : "CREATE_FAILED");
        }
        recomputeLatestVersions(image);
        image.setUpdatedAt(System.currentTimeMillis());
        imageStore.save(image);
    }

    private static void recomputeLatestVersions(MicrovmImageRecord image) {
        String latestActive = null;
        String latestFailed = null;
        for (MicrovmImageVersionRecord version : image.getVersions().values()) {
            if ("SUCCESSFUL".equals(version.getState()) && "ACTIVE".equals(version.getStatus())) {
                latestActive = version.getImageVersion();
            } else if ("FAILED".equals(version.getState())) {
                latestFailed = version.getImageVersion();
            }
        }
        image.setLatestActiveImageVersion(latestActive);
        image.setLatestFailedImageVersion(latestFailed);
    }

    private static String artifactUri(MicrovmImageVersionRecord version) {
        Object codeArtifact = version.getConfig().get("codeArtifact");
        if (codeArtifact instanceof Map<?, ?> map && map.get("uri") instanceof String uri) {
            return uri;
        }
        throw new AwsException("ValidationException", "codeArtifact.uri is required", 400);
    }

    static String dockerTag(String region, String imageName, String imageVersion) {
        String sanitized = imageName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return "floci-microvm/" + region + "-" + sanitized + ":v" + imageVersion;
    }

    private static String hostArchitecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm") ? "ARM_64" : "X86_64";
    }

    // ──────────────────────────── response shapes ────────────────────────────

    /** GetMicrovmImageOutput / MicrovmImageSummary. */
    private static Map<String, Object> imageSummary(MicrovmImageRecord image, boolean includeTags) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imageArn", image.getImageArn());
        out.put("name", image.getName());
        out.put("state", image.getState());
        putIfPresent(out, "latestActiveImageVersion", image.getLatestActiveImageVersion());
        putIfPresent(out, "latestFailedImageVersion", image.getLatestFailedImageVersion());
        out.put("createdAt", epochSeconds(image.getCreatedAt()));
        if (includeTags && image.getTags() != null && !image.getTags().isEmpty()) {
            out.put("tags", image.getTags());
        }
        if (image.getUpdatedAt() != null) {
            out.put("updatedAt", epochSeconds(image.getUpdatedAt()));
        }
        return out;
    }

    /** CreateMicrovmImageResponse / UpdateMicrovmImageResponse (image + version config echo). */
    private static Map<String, Object> imageDetailResponse(MicrovmImageRecord image,
                                                           MicrovmImageVersionRecord version) {
        Map<String, Object> out = imageSummary(image, true);
        out.putAll(version.getConfig());
        // UpdateMicrovmImageResponse requires updatedAt; echo createdAt on a fresh create.
        out.putIfAbsent("updatedAt",
                epochSeconds(image.getUpdatedAt() != null ? image.getUpdatedAt() : image.getCreatedAt()));
        out.put("imageVersion", version.getImageVersion());
        return out;
    }

    /** GetMicrovmImageVersionOutput / MicrovmImageVersionSummary. */
    private static Map<String, Object> versionResponse(MicrovmImageRecord image,
                                                       MicrovmImageVersionRecord version) {
        Map<String, Object> out = new LinkedHashMap<>(version.getConfig());
        out.put("imageArn", image.getImageArn());
        out.put("imageVersion", version.getImageVersion());
        out.put("state", version.getState());
        out.put("status", version.getStatus());
        out.put("createdAt", epochSeconds(version.getCreatedAt()));
        if (version.getUpdatedAt() != null) {
            out.put("updatedAt", epochSeconds(version.getUpdatedAt()));
        }
        putIfPresent(out, "stateReason", version.getStateReason());
        return out;
    }

    /** GetMicrovmImageBuildOutput / MicrovmImageBuildSummary. */
    private static Map<String, Object> buildSummary(MicrovmImageRecord image,
                                                    MicrovmImageVersionRecord version) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imageArn", image.getImageArn());
        out.put("imageVersion", version.getImageVersion());
        out.put("buildId", version.getBuildId());
        out.put("buildState", version.getBuildState());
        out.put("architecture", version.getArchitecture());
        out.put("chipset", version.getChipset());
        out.put("chipsetGeneration", version.getChipsetGeneration());
        putIfPresent(out, "stateReason", version.getBuildStateReason());
        out.put("createdAt", epochSeconds(version.getCreatedAt()));
        return out;
    }

    private static long epochSeconds(long millis) {
        return millis / 1000;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String requireString(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new AwsException("ValidationException", key + " is required", 400);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, String>) value : null;
    }
}
