package io.github.hectorvent.floci.services.ecrpublic;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecrpublic.model.CatalogData;
import io.github.hectorvent.floci.services.ecrpublic.model.Repository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Control plane for Amazon ECR Public. The registry is global (hosted in
 * {@code us-east-1}); ARNs omit the region segment.
 */
@ApplicationScoped
public class EcrPublicService implements Resettable {

    private static final Logger LOG = Logger.getLogger(EcrPublicService.class);
    private static final Pattern REPO_NAME = Pattern.compile(
            "[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*(/[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*)*");
    private static final int MAX_REPO_NAME_LENGTH = 205;
    private static final String AUTH_PASSWORD = "floci-" + "0".repeat(96);
    static final long LAYER_PART_SIZE = 20 * 1024 * 1024L;

    private final StorageBackend<String, Repository> repoStore;
    private final RegionResolver regionResolver;
    private final Map<String, String> registryDisplayNames = new ConcurrentHashMap<>();
    private final Map<String, LayerUpload> uploads = new ConcurrentHashMap<>();
    private final Map<String, byte[]> layers = new ConcurrentHashMap<>();
    private final Map<String, LocalImage> images = new ConcurrentHashMap<>();

    @Inject
    public EcrPublicService(StorageFactory factory, RegionResolver regionResolver) {
        this(factory.create("ecr-public", "ecr-public-repositories.json",
                        new TypeReference<Map<String, Repository>>() {}),
                regionResolver);
    }

    EcrPublicService(StorageBackend<String, Repository> repoStore, RegionResolver regionResolver) {
        this.repoStore = repoStore;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        repoStore.clear();
        registryDisplayNames.clear();
        uploads.clear();
        layers.clear();
        images.clear();
    }

    public Repository createRepository(String repositoryName,
                                       CatalogData catalogData,
                                       Map<String, String> tags) {
        validateRepoName(repositoryName);
        String account = regionResolver.getAccountId();
        if (repoStore.get(repositoryName).isPresent()) {
            throw alreadyExists(repositoryName, account);
        }

        Repository repo = new Repository();
        repo.setRepositoryName(repositoryName);
        repo.setRegistryId(account);
        repo.setRepositoryArn(arn(account, repositoryName));
        repo.setRepositoryUri(uri(account, repositoryName));
        repo.setCreatedAt(Instant.now());
        repo.setCatalogData(catalogData);
        if (tags != null) {
            repo.getTags().putAll(tags);
        }
        repoStore.put(repositoryName, repo);
        LOG.infov("Created ECR Public repository {0}/{1}", account, repositoryName);
        return repo;
    }

    public List<Repository> describeRepositories(List<String> repositoryNames, String registryId) {
        String account = effectiveAccount(registryId);
        if (repositoryNames == null || repositoryNames.isEmpty()) {
            return new ArrayList<>(repoStore.scan(k -> true));
        }
        List<Repository> out = new ArrayList<>();
        for (String name : repositoryNames) {
            out.add(requireRepo(name, account));
        }
        return out;
    }

    public Repository deleteRepository(String repositoryName, String registryId, boolean force) {
        Repository repo = requireRepo(repositoryName, effectiveAccount(registryId));
        String prefix = imagePrefix(repo);
        boolean hasImages = images.keySet().stream().anyMatch(k -> k.startsWith(prefix));
        if (hasImages && !force) {
            throw new AwsException("RepositoryNotEmptyException",
                    "The repository with name '" + repositoryName
                            + "' in registry with id '" + repo.getRegistryId()
                            + "' cannot be deleted because it still contains images", 400);
        }
        images.keySet().removeIf(k -> k.startsWith(prefix));
        layers.keySet().removeIf(k -> k.startsWith(prefix));
        repoStore.delete(repositoryName);
        LOG.infov("Deleted ECR Public repository {0} (force={1})", repositoryName, force);
        return repo;
    }

    public CatalogData getRepositoryCatalogData(String repositoryName, String registryId) {
        Repository repo = requireRepo(repositoryName, effectiveAccount(registryId));
        return repo.getCatalogData() == null ? new CatalogData() : repo.getCatalogData();
    }

    public CatalogData putRepositoryCatalogData(String repositoryName,
                                                String registryId,
                                                CatalogData catalogData) {
        Repository repo = requireRepo(repositoryName, effectiveAccount(registryId));
        repo.setCatalogData(catalogData == null ? new CatalogData() : catalogData);
        repoStore.put(repositoryName, repo);
        return repo.getCatalogData();
    }

    public Repository setRepositoryPolicy(String repositoryName, String registryId, String policyText) {
        Repository repo = requireRepo(repositoryName, effectiveAccount(registryId));
        repo.setRepositoryPolicyText(policyText);
        repoStore.put(repositoryName, repo);
        return repo;
    }

    public Repository getRepositoryPolicy(String repositoryName, String registryId) {
        Repository repo = requireRepo(repositoryName, effectiveAccount(registryId));
        if (repo.getRepositoryPolicyText() == null) {
            throw new AwsException("RepositoryPolicyNotFoundException",
                    "Repository policy does not exist for the repository with name '" + repositoryName + "'",
                    400);
        }
        return repo;
    }

    public Repository deleteRepositoryPolicy(String repositoryName, String registryId) {
        Repository repo = getRepositoryPolicy(repositoryName, registryId);
        repo.setRepositoryPolicyText(null);
        repoStore.put(repositoryName, repo);
        return repo;
    }

    public void tagResource(String repositoryName, Map<String, String> tags) {
        Repository repo = requireRepo(repositoryName, regionResolver.getAccountId());
        if (tags != null) {
            repo.getTags().putAll(tags);
        }
        repoStore.put(repositoryName, repo);
    }

    public void untagResource(String repositoryName, List<String> tagKeys) {
        Repository repo = requireRepo(repositoryName, regionResolver.getAccountId());
        if (tagKeys != null) {
            for (String key : tagKeys) {
                repo.getTags().remove(key);
            }
        }
        repoStore.put(repositoryName, repo);
    }

    public Map<String, String> listTagsForResource(String repositoryName) {
        return requireRepo(repositoryName, regionResolver.getAccountId()).getTags();
    }

    public AuthorizationToken getAuthorizationToken() {
        String token = Base64.getEncoder()
                .encodeToString(("AWS:" + AUTH_PASSWORD).getBytes(StandardCharsets.UTF_8));
        return new AuthorizationToken(token, Instant.now().plusSeconds(12 * 60 * 60));
    }

    public Registry describeRegistry() {
        String account = regionResolver.getAccountId();
        String alias = registryAlias(account);
        return new Registry(
                account,
                AwsArnUtils.Arn.of("ecr-public", "", account, "registry/" + account).toString(),
                "public.ecr.aws/" + alias,
                false,
                List.of(new RegistryAlias(alias, "ACTIVE", true, true)));
    }

    public String getRegistryDisplayName() {
        return registryDisplayNames.get(regionResolver.getAccountId());
    }

    public String putRegistryDisplayName(String displayName) {
        String account = regionResolver.getAccountId();
        if (displayName == null || displayName.isBlank()) {
            registryDisplayNames.remove(account);
            return null;
        }
        registryDisplayNames.put(account, displayName);
        return displayName;
    }

    public InitiateLayerUploadResult initiateLayerUpload(String repositoryName, String registryId) {
        Repository repo = requireRepo(repositoryName, effectiveAccount(registryId));
        String uploadId = UUID.randomUUID().toString();
        uploads.put(uploadId, new LayerUpload());
        return new InitiateLayerUploadResult(uploadId, LAYER_PART_SIZE);
    }

    public UploadLayerPartResult uploadLayerPart(String repositoryName, String registryId, String uploadId,
                                                 long partFirstByte, long partLastByte, byte[] blob) {
        Repository repo = requireRepo(repositoryName, effectiveAccount(registryId));
        LayerUpload upload = uploads.get(uploadId);
        if (upload == null) {
            throw new AwsException("UploadNotFoundException",
                    "Upload id '" + uploadId + "' was not found", 400);
        }
        if (blob == null || blob.length == 0) {
            throw new AwsException("LayerPartTooSmallException", "Layer part is empty", 400);
        }
        long expectedLast = partFirstByte + blob.length - 1;
        if (partLastByte != expectedLast) {
            throw new AwsException("InvalidLayerPartException",
                    "partLastByte does not match the uploaded blob length", 400);
        }
        upload.write((int) partFirstByte, blob);
        return new UploadLayerPartResult(repo.getRegistryId(), repositoryName, uploadId, partLastByte);
    }

    public CompleteLayerUploadResult completeLayerUpload(String repositoryName, String registryId,
                                                         String uploadId, List<String> layerDigests) {
        Repository repo = requireRepo(repositoryName, effectiveAccount(registryId));
        LayerUpload upload = uploads.remove(uploadId);
        if (upload == null) {
            throw new AwsException("UploadNotFoundException",
                    "Upload id '" + uploadId + "' was not found", 400);
        }
        byte[] data = upload.toByteArray();
        if (data.length == 0) {
            throw new AwsException("EmptyUploadException", "Upload contained no layer data", 400);
        }
        String computed = sha256Digest(data);
        String digest = (layerDigests != null && !layerDigests.isEmpty() && layerDigests.get(0) != null)
                ? layerDigests.get(0) : computed;
        if (!computed.equals(digest)) {
            throw new AwsException("InvalidLayerException",
                    "Layer digest " + digest + " does not match uploaded bytes " + computed, 400);
        }
        String key = layerKey(repo, digest);
        if (layers.containsKey(key)) {
            throw new AwsException("LayerAlreadyExistsException",
                    "Layer with digest '" + digest + "' already exists", 400);
        }
        layers.put(key, data);
        return new CompleteLayerUploadResult(repo.getRegistryId(), repositoryName, uploadId, digest);
    }

    public PutImageResult putImage(String repositoryName, String registryId, String imageManifest,
                                   String mediaType, String imageTag, String imageDigest) {
        Repository repo = requireRepo(repositoryName, effectiveAccount(registryId));
        if (imageManifest == null || imageManifest.isBlank()) {
            throw new AwsException("InvalidParameterException", "imageManifest must not be empty", 400);
        }
        String digest = (imageDigest != null && !imageDigest.isBlank())
                ? imageDigest
                : sha256Digest(imageManifest.getBytes(StandardCharsets.UTF_8));
        String resolvedMedia = (mediaType == null || mediaType.isBlank())
                ? "application/vnd.docker.distribution.manifest.v2+json"
                : mediaType;
        LocalImage existingByTag = imageTag == null ? null : findImageByTag(repo, imageTag);
        if (existingByTag != null && !digest.equals(existingByTag.digest)) {
            throw new AwsException("ImageTagAlreadyExistsException",
                    "Image tag '" + imageTag + "' already exists", 400);
        }
        LocalImage existing = images.get(imageKey(repo, digest));
        if (existing != null && digest.equals(existing.digest)
                && (imageTag == null || imageTag.equals(existing.tag))) {
            throw new AwsException("ImageAlreadyExistsException",
                    "Image with imageId {imageTag:" + imageTag + ", imageDigest:" + digest + "} already exists", 400);
        }
        Instant now = Instant.now();
        images.put(imageKey(repo, digest), new LocalImage(digest, imageTag, imageManifest, resolvedMedia,
                imageManifest.getBytes(StandardCharsets.UTF_8).length, now));
        return new PutImageResult(repo.getRegistryId(), repositoryName, imageTag, digest, imageManifest, resolvedMedia);
    }

    public LayerAvailabilityResult batchCheckLayerAvailability(String repositoryName, String registryId,
                                                               List<String> layerDigests) {
        Repository repo = requireRepo(repositoryName, effectiveAccount(registryId));
        List<LayerInfo> available = new ArrayList<>();
        List<LayerFailure> failures = new ArrayList<>();
        if (layerDigests == null) {
            layerDigests = List.of();
        }
        for (String digest : layerDigests) {
            if (digest == null || digest.isBlank()) {
                failures.add(new LayerFailure(digest, "MissingLayerDigest", "layerDigest is required"));
                continue;
            }
            byte[] data = layers.get(layerKey(repo, digest));
            if (data != null) {
                available.add(new LayerInfo(digest, "AVAILABLE", data.length, null));
            } else {
                available.add(new LayerInfo(digest, "UNAVAILABLE", 0L, null));
            }
        }
        return new LayerAvailabilityResult(available, failures);
    }

    public BatchDeleteImageResult batchDeleteImage(String repositoryName, String registryId, List<ImageId> imageIds) {
        Repository repo = requireRepo(repositoryName, effectiveAccount(registryId));
        List<ImageId> deleted = new ArrayList<>();
        List<ImageFailure> failures = new ArrayList<>();
        if (imageIds == null) {
            imageIds = List.of();
        }
        for (ImageId id : imageIds) {
            LocalImage found = findImage(repo, id.imageTag(), id.imageDigest());
            if (found == null) {
                failures.add(new ImageFailure(id, "ImageNotFound", "Image not found"));
                continue;
            }
            images.remove(imageKey(repo, found.digest));
            deleted.add(new ImageId(found.tag, found.digest));
        }
        return new BatchDeleteImageResult(deleted, failures);
    }

    public List<ImageDetail> describeImages(String repositoryName, String registryId) {
        Repository repo = requireRepo(repositoryName, effectiveAccount(registryId));
        String prefix = imagePrefix(repo);
        List<ImageDetail> out = new ArrayList<>();
        for (Map.Entry<String, LocalImage> e : images.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            LocalImage img = e.getValue();
            List<String> tags = img.tag == null ? List.of() : List.of(img.tag);
            out.add(new ImageDetail(repo.getRegistryId(), repositoryName, img.digest, tags,
                    img.size, img.pushedAt, img.mediaType));
        }
        return out;
    }

    public List<ImageTagDetail> describeImageTags(String repositoryName, String registryId) {
        List<ImageTagDetail> out = new ArrayList<>();
        for (ImageDetail detail : describeImages(repositoryName, registryId)) {
            for (String tag : detail.imageTags()) {
                out.add(new ImageTagDetail(tag, detail.imagePushedAt(),
                        detail.imageDigest(), detail.imageSizeInBytes(), detail.imageManifestMediaType()));
            }
        }
        return out;
    }

    private Repository requireRepo(String repositoryName, String account) {
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "Repository name must not be empty", 400);
        }
        return repoStore.get(repositoryName).orElseThrow(() -> notFound(repositoryName, account));
    }

    private String effectiveAccount(String registryId) {
        if (registryId != null && !registryId.isBlank()) {
            return registryId;
        }
        return regionResolver.getAccountId();
    }

    private static String arn(String account, String repositoryName) {
        return AwsArnUtils.Arn.of("ecr-public", "", account, "repository/" + repositoryName).toString();
    }

    private static String uri(String account, String repositoryName) {
        return "public.ecr.aws/" + account + "/" + repositoryName;
    }

    private static AwsException alreadyExists(String name, String account) {
        return new AwsException("RepositoryAlreadyExistsException",
                "The repository with name '" + name + "' already exists in the registry with id '"
                        + account + "'", 400);
    }

    private static AwsException notFound(String name, String account) {
        return new AwsException("RepositoryNotFoundException",
                "The repository with name '" + name + "' does not exist in the registry with id '"
                        + account + "'", 400);
    }

    private static void validateRepoName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "Repository name must not be empty", 400);
        }
        if (name.length() > MAX_REPO_NAME_LENGTH) {
            throw new AwsException("InvalidParameterException",
                    "Repository name exceeds " + MAX_REPO_NAME_LENGTH + " characters", 400);
        }
        if (!REPO_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterException",
                    "Invalid parameter at 'repositoryName' failed to satisfy constraint: "
                            + "'must satisfy regular expression "
                            + "'[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*(/[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*)*''",
                    400);
        }
    }

    static String registryAlias(String account) {
        return account;
    }

    private static String imagePrefix(Repository repo) {
        return repo.getRegistryId() + "::" + repo.getRepositoryName() + "::";
    }

    private static String imageKey(Repository repo, String digest) {
        return imagePrefix(repo) + digest;
    }

    private static String layerKey(Repository repo, String digest) {
        return imageKey(repo, digest);
    }

    private LocalImage findImage(Repository repo, String tag, String digest) {
        if (digest != null && !digest.isBlank()) {
            return images.get(imageKey(repo, digest));
        }
        return findImageByTag(repo, tag);
    }

    private LocalImage findImageByTag(Repository repo, String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String prefix = imagePrefix(repo);
        for (Map.Entry<String, LocalImage> e : images.entrySet()) {
            if (e.getKey().startsWith(prefix) && tag.equals(e.getValue().tag)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String sha256Digest(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new AwsException("ServerException", "Unable to hash layer: " + e.getMessage(), 500);
        }
    }

    public record AuthorizationToken(String authorizationToken, Instant expiresAt) {}

    public record Registry(String registryId, String registryArn, String registryUri, boolean verified,
                           List<RegistryAlias> aliases) {}

    public record RegistryAlias(String name, String status, boolean primaryRegistryAlias,
                                boolean defaultRegistryAlias) {}

    public record InitiateLayerUploadResult(String uploadId, long partSize) {}

    public record UploadLayerPartResult(String registryId, String repositoryName, String uploadId,
                                        long lastByteReceived) {}

    public record CompleteLayerUploadResult(String registryId, String repositoryName, String uploadId,
                                            String layerDigest) {}

    public record PutImageResult(String registryId, String repositoryName, String imageTag, String imageDigest,
                                 String imageManifest, String imageManifestMediaType) {}

    public record LayerInfo(String layerDigest, String layerAvailability, long layerSize, String mediaType) {}

    public record LayerFailure(String layerDigest, String failureCode, String failureReason) {}

    public record LayerAvailabilityResult(List<LayerInfo> layers, List<LayerFailure> failures) {}

    public record ImageId(String imageTag, String imageDigest) {}

    public record ImageFailure(ImageId imageId, String failureCode, String failureReason) {}

    public record BatchDeleteImageResult(List<ImageId> imageIds, List<ImageFailure> failures) {}

    public record ImageDetail(String registryId, String repositoryName, String imageDigest, List<String> imageTags,
                              long imageSizeInBytes, Instant imagePushedAt, String imageManifestMediaType) {}

    public record ImageTagDetail(String imageTag, Instant createdAt, String imageDigest, long imageSizeInBytes,
                                 String imageManifestMediaType) {}

    private static final class LayerUpload {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private synchronized void write(int offset, byte[] part) {
            if (offset != buffer.size()) {
                throw new AwsException("InvalidLayerPartException",
                        "Layer parts must be uploaded sequentially", 400);
            }
            buffer.writeBytes(part);
        }

        private synchronized byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }

    private static final class LocalImage {
        private final String digest;
        private final String tag;
        private final String manifest;
        private final String mediaType;
        private final long size;
        private final Instant pushedAt;

        private LocalImage(String digest, String tag, String manifest, String mediaType, long size, Instant pushedAt) {
            this.digest = digest;
            this.tag = tag;
            this.manifest = manifest;
            this.mediaType = mediaType;
            this.size = size;
            this.pushedAt = pushedAt;
        }
    }
}
