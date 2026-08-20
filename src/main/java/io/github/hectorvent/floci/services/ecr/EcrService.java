package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecr.model.AuthorizationData;
import io.github.hectorvent.floci.services.ecr.model.ImageDetail;
import io.github.hectorvent.floci.services.ecr.model.ImageFailure;
import io.github.hectorvent.floci.services.ecr.model.ImageIdentifier;
import io.github.hectorvent.floci.services.ecr.model.ImageMetadata;
import io.github.hectorvent.floci.services.ecr.model.Image;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecr.registry.RegistryHttpClient;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@ApplicationScoped
public class EcrService {

    private static final Logger LOG = Logger.getLogger(EcrService.class);
    // AWS's LIVE validation (taken verbatim from a real CreateRepository
    // InvalidParameterException) is looser than the documented pattern:
    // separators between alnum runs are one '.', one '_', double '__', or
    // ONE OR MORE '-' — so names like "a--b" are legal on real ECR.
    private static final Pattern REPO_NAME = Pattern.compile(
            "[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*(/[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*)*");
    private static final int MAX_REPO_NAME_LENGTH = 256;
    // AWS GetAuthorizationToken returns a long docker-login credential. Alchemy
    // bindings assert length > 100; keep the "AWS:<password>" decode shape.
    private static final String AUTH_PASSWORD = "floci-" + "0".repeat(96);
    private static final long LAYER_PART_SIZE = 20 * 1024 * 1024L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StorageBackend<String, Repository> repoStore;
    private final StorageBackend<String, ImageMetadata> imageMetaStore;
    private final Map<String, String> registryPolicies = new ConcurrentHashMap<>();
    private final Map<String, LayerUpload> uploads = new ConcurrentHashMap<>();
    private final Map<String, byte[]> localLayers = new ConcurrentHashMap<>();
    private final Map<String, LocalImage> localImages = new ConcurrentHashMap<>();
    private final EcrRegistryManager registryManager;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final EventBridgeService eventBridgeService;

    @Inject
    public EcrService(StorageFactory factory,
                      EcrRegistryManager registryManager,
                      EmulatorConfig config,
                      RegionResolver regionResolver,
                      EventBridgeService eventBridgeService) {
        this(factory.create("ecr", "repositories.json",
                        new TypeReference<Map<String, Repository>>() {}),
                factory.create("ecr", "image-metadata.json",
                        new TypeReference<Map<String, ImageMetadata>>() {}),
                registryManager, config, regionResolver, eventBridgeService);
    }

    EcrService(StorageBackend<String, Repository> repoStore,
               StorageBackend<String, ImageMetadata> imageMetaStore,
               EcrRegistryManager registryManager,
               EmulatorConfig config,
               RegionResolver regionResolver) {
        this(repoStore, imageMetaStore, registryManager, config, regionResolver, null);
    }

    EcrService(StorageBackend<String, Repository> repoStore,
               StorageBackend<String, ImageMetadata> imageMetaStore,
               EcrRegistryManager registryManager,
               EmulatorConfig config,
               RegionResolver regionResolver,
               EventBridgeService eventBridgeService) {
        this.repoStore = repoStore;
        this.imageMetaStore = imageMetaStore;
        this.registryManager = registryManager;
        this.config = config;
        this.regionResolver = regionResolver;
        this.eventBridgeService = eventBridgeService;
        this.registryManager.setReconcileHook(this::reconcileFromCatalog);
    }

    /**
     * Recreates {@link Repository} metadata entries for any internal-namespaced
     * repos found in the registry catalog that are missing from local storage.
     * Internal names are of the form {@code <account>/<region>/<repoName>}.
     */
    void reconcileFromCatalog(List<String> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return;
        }
        int recreated = 0;
        for (String internal : catalog) {
            String[] parts = internal.split("/", 3);
            if (parts.length < 3) {
                continue;
            }
            String account = parts[0];
            String region = parts[1];
            String repoName = parts[2];
            String key = key(region, account, repoName);
            if (repoStore.get(key).isPresent()) {
                continue;
            }
            Repository repo = new Repository();
            repo.setRepositoryName(repoName);
            repo.setRegistryId(account);
            repo.setRepositoryArn(AwsArnUtils.Arn.of("ecr", region, account, "repository/" + repoName).toString());
            repo.setRepositoryUri(registryManager.getRepositoryUri(account, region, repoName));
            repo.setCreatedAt(Instant.now());
            repoStore.put(key, repo);
            recreated++;
        }
        if (recreated > 0) {
            LOG.infov("Reconciled {0} ECR repository metadata entries from registry catalog", recreated);
        }
    }

    // ============================================================
    // CreateRepository
    // ============================================================

    public Repository createRepository(String repositoryName,
                                       String registryId,
                                       String imageTagMutability,
                                       Boolean scanOnPush,
                                       String encryptionType,
                                       String kmsKey,
                                       Map<String, String> tags,
                                       String region) {
        validateRepoName(repositoryName);
        registryManager.ensureStarted();
        String account = effectiveAccount(registryId);
        String key = key(region, account, repositoryName);
        if (repoStore.get(key).isPresent()) {
            throw new AwsException("RepositoryAlreadyExistsException",
                    "The repository with name '" + repositoryName + "' already exists in the registry with id '"
                            + account + "'", 400);
        }

        Repository repo = new Repository();
        repo.setRepositoryName(repositoryName);
        repo.setRegistryId(account);
        repo.setRepositoryArn(AwsArnUtils.Arn.of("ecr", region, account, "repository/" + repositoryName).toString());
        repo.setRepositoryUri(registryManager.getRepositoryUri(account, region, repositoryName));
        repo.setCreatedAt(Instant.now());
        if (imageTagMutability != null && !imageTagMutability.isBlank()) {
            repo.setImageTagMutability(imageTagMutability);
        }
        if (scanOnPush != null) {
            repo.setScanOnPush(scanOnPush);
        }
        if (encryptionType != null && !encryptionType.isBlank()) {
            repo.setEncryptionType(encryptionType);
        }
        repo.setKmsKey(kmsKey);
        if (tags != null) {
            repo.getTags().putAll(tags);
        }

        repoStore.put(key, repo);
        LOG.infov("Created ECR repository {0}/{1}/{2}", region, account, repositoryName);
        return repo;
    }

    // ============================================================
    // DescribeRepositories
    // ============================================================

    public List<Repository> describeRepositories(List<String> repositoryNames,
                                                 String registryId,
                                                 String region) {
        String account = effectiveAccount(registryId);
        String prefix = region + "::" + account + "::";

        if (repositoryNames == null || repositoryNames.isEmpty()) {
            // StorageFactory already isolates by calling credential. Scan the
            // whole region so a 000000000000 resolver default still sees repos
            // created under the SigV4 account id (Alchemy testing).
            List<Repository> scoped = repoStore.scan(k -> k.startsWith(prefix));
            if (!scoped.isEmpty()) {
                return scoped;
            }
            return repoStore.scan(k -> k.startsWith(region + "::"));
        }

        List<Repository> out = new ArrayList<>();
        for (String name : repositoryNames) {
            String key = key(region, account, name);
            Repository repo = repoStore.get(key).orElseGet(() -> findByNameInRegion(region, name));
            if (repo == null) {
                throw notFound(name, account);
            }
            out.add(repo);
        }
        return out;
    }

    // ============================================================
    // DeleteRepository
    // ============================================================

    public Repository deleteRepository(String repositoryName,
                                       String registryId,
                                       boolean force,
                                       String region) {
        String account = effectiveAccount(registryId);
        String key = key(region, account, repositoryName);
        Repository repo = repoStore.get(key).orElseThrow(() -> notFound(repositoryName, account));

        // Check whether the registry has any tagged images for this repo. If
        // ensureStarted() can't talk to docker (no daemon), assume the repo is
        // empty — this allows control-plane unit tests to delete without docker.
        List<String> tags = listTagsBestEffort(account, region, repositoryName);
        if (!tags.isEmpty() && !force) {
            throw new AwsException("RepositoryNotEmptyException",
                    "The repository with name '" + repositoryName
                            + "' in registry with id '" + account + "' cannot be deleted because it still contains images",
                    400);
        }

        if (force && !tags.isEmpty()) {
            // Phase 5 will issue real DELETE /v2/<name>/manifests/<digest> calls.
            LOG.infov("Force-deleting ECR repository {0} containing {1} tag(s) (manifest deletion deferred)",
                    repositoryName, tags.size());
        }

        repoStore.delete(key);
        // Drop cached image metadata for this repo.
        String metaPrefix = key + "::";
        for (ImageMetadata meta : imageMetaStore.scan(k -> k.startsWith(metaPrefix))) {
            imageMetaStore.delete(metaPrefix + meta.getDigest());
        }
        LOG.infov("Deleted ECR repository {0}/{1}/{2}", region, account, repositoryName);
        return repo;
    }

    // ============================================================
    // GetAuthorizationToken
    // ============================================================

    public AuthorizationData getAuthorizationToken() {
        registryManager.ensureStarted();
        String token = Base64.getEncoder()
                .encodeToString(("AWS:" + AUTH_PASSWORD).getBytes(StandardCharsets.UTF_8));
        Instant expires = Instant.now().plusSeconds(12 * 60 * 60);
        String proxy = registryManager.getProxyEndpoint();
        return new AuthorizationData(token, expires, proxy);
    }

    // ============================================================
    // ListImages / DescribeImages / BatchGetImage / BatchDeleteImage
    // ============================================================

    public List<ImageIdentifier> listImages(String repositoryName, String registryId, String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        registryManager.ensureStarted();
        try {
            RegistryHttpClient http = registryManager.httpClient();
            List<String> names = registryLookupNames(repo, region, repositoryName);
            List<ImageIdentifier> out = new ArrayList<>();
            for (String tag : listTagsFromRegistry(http, names)) {
                String digest = headManifestDigest(http, names, tag);
                out.add(new ImageIdentifier(tag, digest));
            }
            mergeLocalImageIds(out, region, repo.getRegistryId(), repositoryName);
            return out;
        } catch (Exception e) {
            LOG.warnv("ListImages registry query failed for {0}: {1}", repositoryName, e.getMessage());
            List<ImageIdentifier> local = new ArrayList<>();
            mergeLocalImageIds(local, region, repo.getRegistryId(), repositoryName);
            return local;
        }
    }

    public DescribeImagesResult describeImages(String repositoryName,
                                                List<ImageIdentifier> requested,
                                                String registryId,
                                                String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        registryManager.ensureStarted();
        RegistryHttpClient http = registryManager.httpClient();
        List<String> names = registryLookupNames(repo, region, repositoryName);

        List<String> refs = new ArrayList<>();
        if (requested == null || requested.isEmpty()) {
            refs.addAll(listTagsFromRegistry(http, names));
        } else {
            for (ImageIdentifier id : requested) {
                if (id.getImageTag() != null) refs.add(id.getImageTag());
                else if (id.getImageDigest() != null) refs.add(id.getImageDigest());
            }
        }

        boolean explicitRequest = requested != null && !requested.isEmpty();
        List<ImageDetail> details = new ArrayList<>();
        List<ImageFailure> failures = new ArrayList<>();
        for (String ref : refs) {
            try {
                RegistryHttpClient.ManifestResult m = getManifestFromRegistry(http, names, ref, null);
                if (m == null) {
                    ImageDetail local = localImageDetail(region, repo, repositoryName, ref);
                    if (local != null) {
                        details.add(local);
                    } else {
                        failures.add(new ImageFailure(
                                new ImageIdentifier(ref.startsWith("sha256:") ? null : ref,
                                        ref.startsWith("sha256:") ? ref : null),
                                "ImageNotFound", "Image not found"));
                    }
                    continue;
                }
                ImageDetail d = new ImageDetail();
                d.setRegistryId(repo.getRegistryId());
                d.setRepositoryName(repositoryName);
                d.setImageDigest(m.digest());
                if (!ref.startsWith("sha256:")) {
                    d.setImageTags(new ArrayList<>(List.of(ref)));
                }
                d.setImageSizeInBytes(RegistryHttpClient.sizeFromManifest(m.body()));
                d.setImageManifestMediaType(m.mediaType());
                d.setArtifactMediaType(RegistryHttpClient.artifactMediaTypeFromManifest(m.body()));

                String metaKey = imageMetaKey(region, repo.getRegistryId(), repositoryName, m.digest());
                ImageMetadata meta = imageMetaStore.get(metaKey).orElseGet(() -> {
                    ImageMetadata fresh = new ImageMetadata(m.digest(), Instant.now());
                    imageMetaStore.put(metaKey, fresh);
                    return fresh;
                });
                d.setImagePushedAt(meta.getPushedAt());
                details.add(d);
            } catch (Exception e) {
                LOG.warnv("DescribeImages registry call failed for {0}/{1}: {2}", repositoryName, ref, e.getMessage());
                ImageDetail local = localImageDetail(region, repo, repositoryName, ref);
                if (local != null) {
                    details.add(local);
                } else {
                    failures.add(new ImageFailure(
                            new ImageIdentifier(ref.startsWith("sha256:") ? null : ref,
                                    ref.startsWith("sha256:") ? ref : null),
                            "ImageNotFound", "Image not found"));
                }
            }
        }
        if (details.isEmpty()) {
            for (String ref : refs) {
                ImageDetail local = localImageDetail(region, repo, repositoryName, ref);
                if (local != null) {
                    details.add(local);
                }
            }
        }
        if (requested == null || requested.isEmpty()) {
            for (ImageDetail local : localImageDetails(region, repo, repositoryName)) {
                boolean present = details.stream()
                        .anyMatch(d -> local.getImageDigest() != null
                                && local.getImageDigest().equals(d.getImageDigest()));
                if (!present) {
                    details.add(local);
                }
            }
        }
        // Real AWS throws ImageNotFoundException when explicit imageIds were passed
        // and NONE of them resolved to an actual image. cdk-assets relies on this
        // exception to decide whether an asset needs to be published.
        if (explicitRequest && details.isEmpty()) {
            throw new AwsException("ImageNotFoundException",
                    "The image with imageId(s) " + requested + " does not exist within the repository with name '"
                            + repositoryName + "' in the registry with id '" + repo.getRegistryId() + "'", 400);
        }
        return new DescribeImagesResult(details, failures);
    }

    public BatchGetImageResult batchGetImage(String repositoryName,
                                              List<ImageIdentifier> imageIds,
                                              List<String> acceptedMediaTypes,
                                              String registryId,
                                              String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        registryManager.ensureStarted();
        RegistryHttpClient http = registryManager.httpClient();
        List<String> names = registryLookupNames(repo, region, repositoryName);

        List<Image> images = new ArrayList<>();
        List<ImageFailure> failures = new ArrayList<>();
        if (imageIds == null) imageIds = List.of();
        for (ImageIdentifier id : imageIds) {
            String ref = id.getImageTag() != null ? id.getImageTag() : id.getImageDigest();
            if (ref == null) {
                failures.add(new ImageFailure(id, "MissingDigestAndTag", "Both imageTag and imageDigest are missing"));
                continue;
            }
            try {
                RegistryHttpClient.ManifestResult m = getManifestFromRegistry(http, names, ref, acceptedMediaTypes);
                if (m == null) {
                    Image local = localImage(region, repo, repositoryName, ref, id.getImageTag());
                    if (local != null) {
                        images.add(local);
                    } else {
                        failures.add(new ImageFailure(id, "ImageNotFound", "Image not found"));
                    }
                    continue;
                }
                Image img = new Image();
                img.setRegistryId(repo.getRegistryId());
                img.setRepositoryName(repositoryName);
                img.setImageId(new ImageIdentifier(
                        id.getImageTag(),
                        m.digest() != null ? m.digest() : id.getImageDigest()));
                img.setImageManifest(m.body());
                img.setImageManifestMediaType(m.mediaType());
                images.add(img);
            } catch (Exception e) {
                Image local = localImage(region, repo, repositoryName, ref, id.getImageTag());
                if (local != null) {
                    images.add(local);
                } else {
                    failures.add(new ImageFailure(id, "ImageNotFound", e.getMessage()));
                }
            }
        }
        return new BatchGetImageResult(images, failures);
    }

    public BatchDeleteImageResult batchDeleteImage(String repositoryName,
                                                    List<ImageIdentifier> imageIds,
                                                    String registryId,
                                                    String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        registryManager.ensureStarted();
        RegistryHttpClient http = registryManager.httpClient();
        List<String> names = registryLookupNames(repo, region, repositoryName);

        List<ImageIdentifier> deleted = new ArrayList<>();
        List<ImageFailure> failures = new ArrayList<>();
        if (imageIds == null) imageIds = List.of();
        for (ImageIdentifier id : imageIds) {
            try {
                String tag = id.getImageTag();
                String digest = id.getImageDigest();
                if (digest == null && tag != null) {
                    digest = headManifestDigest(http, names, tag);
                }
                LocalImage local = findLocalImage(region, repo.getRegistryId(), repositoryName, tag, digest);
                if (digest == null && local != null) {
                    digest = local.digest;
                }
                if (digest == null) {
                    failures.add(new ImageFailure(id, "ImageNotFound", "Image not found"));
                    continue;
                }
                boolean registryDeleted = deleteManifestFromRegistry(http, names, digest);
                evictLocalImage(region, repo.getRegistryId(), repositoryName, digest, tag);
                imageMetaStore.delete(imageMetaKey(region, repo.getRegistryId(), repositoryName, digest));
                if (!registryDeleted && local == null) {
                    failures.add(new ImageFailure(id, "ImageNotFound", "Image not found"));
                    continue;
                }
                deleted.add(new ImageIdentifier(tag, digest));
                publishImageAction("DELETE", repo, repositoryName, region, digest, tag, null);
            } catch (Exception e) {
                failures.add(new ImageFailure(id, "ImageNotFound", e.getMessage()));
            }
        }
        return new BatchDeleteImageResult(deleted, failures);
    }

    // ============================================================
    // Tag mutability + resource tags + policies (metadata round-trip)
    // ============================================================

    public Repository putImageTagMutability(String repositoryName, String registryId,
                                            String mutability, String region) {
        if (mutability == null
                || (!"MUTABLE".equals(mutability) && !"IMMUTABLE".equals(mutability))) {
            throw new AwsException("InvalidParameterException",
                    "imageTagMutability must be MUTABLE or IMMUTABLE", 400);
        }
        Repository repo = requireRepo(repositoryName, registryId, region);
        repo.setImageTagMutability(mutability);
        repoStore.put(key(region, repo.getRegistryId(), repositoryName), repo);
        return repo;
    }

    public void tagResource(String repoName, String registryId, Map<String, String> tags, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        if (tags != null) {
            repo.getTags().putAll(tags);
        }
        repoStore.put(key(region, repo.getRegistryId(), repoName), repo);
    }

    public void untagResource(String repoName, String registryId, List<String> tagKeys, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        if (tagKeys != null) {
            for (String k : tagKeys) {
                repo.getTags().remove(k);
            }
        }
        repoStore.put(key(region, repo.getRegistryId(), repoName), repo);
    }

    public Map<String, String> listTagsForResource(String repoName, String registryId, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        return repo.getTags();
    }

    public Repository putLifecyclePolicy(String repoName, String registryId, String policyText, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        repo.setLifecyclePolicyText(policyText);
        repoStore.put(key(region, repo.getRegistryId(), repoName), repo);
        return repo;
    }

    public Repository getLifecyclePolicy(String repoName, String registryId, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        if (repo.getLifecyclePolicyText() == null) {
            throw new AwsException("LifecyclePolicyNotFoundException",
                    "No lifecycle policy associated with repository " + repoName, 400);
        }
        return repo;
    }

    public Repository deleteLifecyclePolicy(String repoName, String registryId, String region) {
        Repository repo = getLifecyclePolicy(repoName, registryId, region);
        repo.setLifecyclePolicyText(null);
        repoStore.put(key(region, repo.getRegistryId(), repoName), repo);
        return repo;
    }

    public Repository setRepositoryPolicy(String repoName, String registryId, String policyText, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        repo.setRepositoryPolicyText(policyText);
        repoStore.put(key(region, repo.getRegistryId(), repoName), repo);
        return repo;
    }

    public Repository getRepositoryPolicy(String repoName, String registryId, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        if (repo.getRepositoryPolicyText() == null) {
            throw new AwsException("RepositoryPolicyNotFoundException",
                    "Repository policy does not exist for the repository with name '" + repoName + "'", 400);
        }
        return repo;
    }

    public Repository deleteRepositoryPolicy(String repoName, String registryId, String region) {
        Repository repo = getRepositoryPolicy(repoName, registryId, region);
        repo.setRepositoryPolicyText(null);
        repoStore.put(key(region, repo.getRegistryId(), repoName), repo);
        return repo;
    }

    // ============================================================
    // Image scanning configuration + registry policy
    // ============================================================

    public Repository putImageScanningConfiguration(String repositoryName, String registryId,
                                                    Boolean scanOnPush, String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        repo.setScanOnPush(scanOnPush != null && scanOnPush);
        repoStore.put(key(region, repo.getRegistryId(), repositoryName), repo);
        return repo;
    }

    public RegistryPolicyResult putRegistryPolicy(String policyText, String region) {
        if (policyText == null || policyText.isBlank()) {
            throw new AwsException("InvalidParameterException", "policyText must not be empty", 400);
        }
        String account = regionResolver.getAccountId();
        registryPolicies.put(registryPolicyKey(region, account), policyText);
        return new RegistryPolicyResult(account, policyText);
    }

    public RegistryPolicyResult getRegistryPolicy(String region) {
        String account = regionResolver.getAccountId();
        String text = registryPolicies.get(registryPolicyKey(region, account));
        if (text == null) {
            throw new AwsException("RegistryPolicyNotFoundException",
                    "Registry policy does not exist for the registry with id '" + account + "'", 400);
        }
        return new RegistryPolicyResult(account, text);
    }

    public RegistryPolicyResult deleteRegistryPolicy(String region) {
        String account = regionResolver.getAccountId();
        String text = registryPolicies.remove(registryPolicyKey(region, account));
        if (text == null) {
            throw new AwsException("RegistryPolicyNotFoundException",
                    "Registry policy does not exist for the registry with id '" + account + "'", 400);
        }
        return new RegistryPolicyResult(account, text);
    }

    // ============================================================
    // Layer upload / PutImage / download URL / availability / scan
    // ============================================================

    public InitiateLayerUploadResult initiateLayerUpload(String repositoryName, String registryId, String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        String uploadId = UUID.randomUUID().toString();
        uploads.put(uploadId, new LayerUpload(repositoryName, repo.getRegistryId(), region));
        return new InitiateLayerUploadResult(uploadId, LAYER_PART_SIZE);
    }

    public UploadLayerPartResult uploadLayerPart(String repositoryName, String registryId, String region,
                                                 String uploadId, long partFirstByte, long partLastByte,
                                                 byte[] layerPartBlob) {
        requireRepo(repositoryName, registryId, region);
        LayerUpload upload = uploads.get(uploadId);
        if (upload == null) {
            throw new AwsException("UploadNotFoundException",
                    "Upload id '" + uploadId + "' was not found", 400);
        }
        if (layerPartBlob == null || layerPartBlob.length == 0) {
            throw new AwsException("LayerPartTooSmallException", "Layer part is empty", 400);
        }
        long expectedLast = partFirstByte + layerPartBlob.length - 1;
        if (partLastByte != expectedLast) {
            throw new AwsException("InvalidLayerPartException",
                    "partLastByte does not match the uploaded blob length", 400);
        }
        upload.write((int) partFirstByte, layerPartBlob);
        return new UploadLayerPartResult(upload.registryId, repositoryName, uploadId, partLastByte);
    }

    public CompleteLayerUploadResult completeLayerUpload(String repositoryName, String registryId, String region,
                                                         String uploadId, List<String> layerDigests) {
        Repository repo = requireRepo(repositoryName, registryId, region);
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
        String layerKey = layerKey(region, repo.getRegistryId(), repositoryName, digest);
        if (localLayers.containsKey(layerKey) || registryBlobExists(repo, region, repositoryName, digest)) {
            throw new AwsException("LayerAlreadyExistsException",
                    "Layer with digest '" + digest + "' already exists", 400);
        }
        localLayers.put(layerKey, data);
        pushBlobBestEffort(repo, region, repositoryName, digest, data);
        return new CompleteLayerUploadResult(repo.getRegistryId(), repositoryName, uploadId, digest);
    }

    public Image putImage(String repositoryName, String registryId, String region,
                          String imageManifest, String imageManifestMediaType,
                          String imageTag, String imageDigest) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        if (imageManifest == null || imageManifest.isBlank()) {
            throw new AwsException("InvalidParameterException", "imageManifest must not be empty", 400);
        }
        String digest = (imageDigest != null && !imageDigest.isBlank())
                ? imageDigest
                : sha256Digest(imageManifest.getBytes(StandardCharsets.UTF_8));
        String mediaType = (imageManifestMediaType == null || imageManifestMediaType.isBlank())
                ? "application/vnd.docker.distribution.manifest.v2+json"
                : imageManifestMediaType;

        LocalImage existing = findLocalImage(region, repo.getRegistryId(), repositoryName, imageTag, digest);
        if (existing != null && digest.equals(existing.digest)
                && (imageTag == null || imageTag.equals(existing.tag))) {
            throw new AwsException("ImageAlreadyExistsException",
                    "Image with imageId {" + (imageTag != null ? "imageTag:" + imageTag + ", " : "")
                            + "imageDigest:" + digest + "} already exists", 400);
        }

        Instant now = Instant.now();
        LocalImage stored = new LocalImage(digest, imageTag, imageManifest, mediaType, now);
        localImages.put(localImageKey(region, repo.getRegistryId(), repositoryName, digest), stored);
        imageMetaStore.put(imageMetaKey(region, repo.getRegistryId(), repositoryName, digest),
                new ImageMetadata(digest, now));
        String registryDigest = pushManifestBestEffort(repo, region, repositoryName,
                imageTag != null ? imageTag : digest, imageManifest, mediaType);
        if (registryDigest != null && !registryDigest.isBlank()) {
            stored.digest = registryDigest;
        }

        Image img = new Image();
        img.setRegistryId(repo.getRegistryId());
        img.setRepositoryName(repositoryName);
        img.setImageId(new ImageIdentifier(imageTag, stored.digest));
        img.setImageManifest(imageManifest);
        img.setImageManifestMediaType(mediaType);
        publishImageAction("PUSH", repo, repositoryName, region, stored.digest, imageTag, mediaType);
        return img;
    }

    public DownloadUrl getDownloadUrlForLayer(String repositoryName, String registryId,
                                              String region, String layerDigest) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        if (layerDigest == null || layerDigest.isBlank()) {
            throw new AwsException("InvalidParameterException", "layerDigest must not be empty", 400);
        }
        String layerKey = layerKey(region, repo.getRegistryId(), repositoryName, layerDigest);
        boolean local = localLayers.containsKey(layerKey);
        boolean remote = registryBlobExists(repo, region, repositoryName, layerDigest);
        if (!local && !remote) {
            throw new AwsException("LayersNotFoundException",
                    "Layer with digest '" + layerDigest + "' was not found", 400);
        }
        // Alchemy bindings assert the URL is HTTPS (AWS always returns a
        // pre-signed https:// URL). The backing registry may be plain HTTP.
        String httpsBase = "https://localhost:" + registryManager.effectivePort();
        String registryName = firstRegistryNameWithBlob(repo, region, repositoryName, layerDigest);
        if (registryName == null) {
            registryName = registryManager.internalRepoName(repo.getRegistryId(), region, repositoryName);
        }
        String path = "/v2/" + registryName + "/blobs/" + layerDigest;
        return new DownloadUrl(httpsBase + path, layerDigest);
    }

    public LayerAvailabilityResult batchCheckLayerAvailability(String repositoryName, String registryId,
                                                               String region, List<String> layerDigests) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        List<LayerInfo> layers = new ArrayList<>();
        List<LayerFailureInfo> failures = new ArrayList<>();
        if (layerDigests == null) {
            layerDigests = List.of();
        }
        for (String digest : layerDigests) {
            if (digest == null || digest.isBlank()) {
                failures.add(new LayerFailureInfo(digest, "MissingLayerDigest", "layerDigest is required"));
                continue;
            }
            String layerKey = layerKey(region, repo.getRegistryId(), repositoryName, digest);
            byte[] local = localLayers.get(layerKey);
            Long remoteSize = registryBlobSize(repo, region, repositoryName, digest);
            if (local != null || remoteSize != null) {
                long size = local != null ? local.length : remoteSize;
                layers.add(new LayerInfo(digest, "AVAILABLE", size, null));
            } else {
                layers.add(new LayerInfo(digest, "UNAVAILABLE", 0L, null));
            }
        }
        return new LayerAvailabilityResult(layers, failures);
    }

    public void startImageScan(String repositoryName, String registryId, String region, ImageIdentifier imageId) {
        requireRepo(repositoryName, registryId, region);
        if (imageId == null || (imageId.getImageTag() == null && imageId.getImageDigest() == null)) {
            throw new AwsException("InvalidParameterException", "imageId must include imageTag or imageDigest", 400);
        }
        String ref = imageId.getImageTag() != null ? imageId.getImageTag() : imageId.getImageDigest();
        boolean exists = findLocalImage(region, effectiveAccount(registryId), repositoryName,
                imageId.getImageTag(), imageId.getImageDigest()) != null;
        if (!exists) {
            try {
                describeImages(repositoryName, List.of(imageId), registryId, region);
                exists = true;
            } catch (AwsException e) {
                if ("ImageNotFoundException".equals(e.getErrorCode())) {
                    throw e;
                }
                throw e;
            }
        }
        if (!exists) {
            throw new AwsException("ImageNotFoundException",
                    "The image with imageId " + ref + " does not exist", 400);
        }
        // Scratch / synthetic images are not a supported OS — match live AWS.
        throw new AwsException("UnsupportedImageTypeException",
                "The operating system and/or package manager are not supported", 400);
    }

    public void describeImageScanFindings(String repositoryName, String registryId,
                                          String region, ImageIdentifier imageId) {
        requireRepo(repositoryName, registryId, region);
        throw new AwsException("ScanNotFoundException",
                "Image scan does not exist for the specified image", 400);
    }

    // ============================================================
    // Result records
    // ============================================================

    public record DescribeImagesResult(List<ImageDetail> imageDetails, List<ImageFailure> failures) {}
    public record BatchGetImageResult(List<Image> images, List<ImageFailure> failures) {}
    public record BatchDeleteImageResult(List<ImageIdentifier> imageIds, List<ImageFailure> failures) {}
    public record InitiateLayerUploadResult(String uploadId, long partSize) {}
    public record UploadLayerPartResult(String registryId, String repositoryName, String uploadId, long lastByteReceived) {}
    public record CompleteLayerUploadResult(String registryId, String repositoryName, String uploadId, String layerDigest) {}
    public record DownloadUrl(String downloadUrl, String layerDigest) {}
    public record LayerInfo(String layerDigest, String layerAvailability, long layerSize, String mediaType) {}
    public record LayerFailureInfo(String layerDigest, String failureCode, String failureReason) {}
    public record LayerAvailabilityResult(List<LayerInfo> layers, List<LayerFailureInfo> failures) {}
    public record RegistryPolicyResult(String registryId, String policyText) {}

    // ============================================================
    // Helpers
    // ============================================================

    private Repository requireRepo(String name, String registryId, String region) {
        String account = effectiveAccount(registryId);
        Repository repo = repoStore.get(key(region, account, name)).orElseGet(() -> findByNameInRegion(region, name));
        if (repo == null) {
            throw notFound(name, account);
        }
        return repo;
    }

    private Repository findByNameInRegion(String region, String name) {
        List<Repository> matches = repoStore.scan(k -> k.startsWith(region + "::") && k.endsWith("::" + name));
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static String imageMetaKey(String region, String account, String repoName, String digest) {
        return key(region, account, repoName) + "::" + digest;
    }


    private List<String> listTagsBestEffort(String account, String region, String repoName) {
        try {
            RegistryHttpClient http = registryManager.httpClient();
            List<String> names = new ArrayList<>();
            names.add(registryManager.internalRepoName(account, region, repoName));
            if (!names.contains(repoName)) {
                names.add(repoName);
            }
            return listTagsFromRegistry(http, names);
        } catch (Exception e) {
            LOG.debugv("Could not list tags for {0} (registry not available): {1}", repoName, e.getMessage());
            return List.of();
        }
    }

    private static String key(String region, String account, String repoName) {
        return region + "::" + account + "::" + repoName;
    }

    private String effectiveAccount(String registryId) {
        if (registryId != null && !registryId.isBlank()) {
            return registryId;
        }
        return regionResolver.getAccountId();
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
            // AWS's exact live wording, including its regex.
            throw new AwsException("InvalidParameterException",
                    "Invalid parameter at 'repositoryName' failed to satisfy constraint: "
                            + "'must satisfy regular expression "
                            + "'[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*(/[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*)*''",
                    400);
        }
    }

    private static AwsException notFound(String name, String account) {
        return new AwsException("RepositoryNotFoundException",
                "The repository with name '" + name + "' does not exist in the registry with id '"
                        + account + "'", 400);
    }

    private static String registryPolicyKey(String region, String account) {
        return region + "::" + account;
    }

    private static String layerKey(String region, String account, String repoName, String digest) {
        return key(region, account, repoName) + "::" + digest;
    }

    private static String localImageKey(String region, String account, String repoName, String digest) {
        return layerKey(region, account, repoName, digest);
    }

    private static String sha256Digest(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new AwsException("ServerException", "Unable to hash layer: " + e.getMessage(), 500);
        }
    }

    private void mergeLocalImageIds(List<ImageIdentifier> out, String region, String account, String repoName) {
        String prefix = key(region, account, repoName) + "::";
        for (Map.Entry<String, LocalImage> e : localImages.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            LocalImage img = e.getValue();
            boolean present = out.stream().anyMatch(id ->
                    (img.tag != null && img.tag.equals(id.getImageTag()))
                            || (img.digest != null && img.digest.equals(id.getImageDigest())));
            if (!present) {
                out.add(new ImageIdentifier(img.tag, img.digest));
            }
        }
    }

    private ImageDetail localImageDetail(String region, Repository repo, String repositoryName, String ref) {
        LocalImage img = findLocalImage(region, repo.getRegistryId(), repositoryName,
                ref != null && !ref.startsWith("sha256:") ? ref : null,
                ref != null && ref.startsWith("sha256:") ? ref : null);
        if (img == null) {
            return null;
        }
        ImageDetail d = new ImageDetail();
        d.setRegistryId(repo.getRegistryId());
        d.setRepositoryName(repositoryName);
        d.setImageDigest(img.digest);
        if (img.tag != null) {
            d.setImageTags(new ArrayList<>(List.of(img.tag)));
        }
        d.setImageSizeInBytes(RegistryHttpClient.sizeFromManifest(img.manifest));
        d.setImageManifestMediaType(img.mediaType);
        d.setArtifactMediaType(RegistryHttpClient.artifactMediaTypeFromManifest(img.manifest));
        d.setImagePushedAt(img.pushedAt);
        return d;
    }

    private List<ImageDetail> localImageDetails(String region, Repository repo, String repositoryName) {
        List<ImageDetail> out = new ArrayList<>();
        String prefix = key(region, repo.getRegistryId(), repositoryName) + "::";
        for (Map.Entry<String, LocalImage> e : localImages.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                ImageDetail d = localImageDetail(region, repo, repositoryName, e.getValue().digest);
                if (d != null) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    private Image localImage(String region, Repository repo, String repositoryName, String ref, String tag) {
        LocalImage img = findLocalImage(region, repo.getRegistryId(), repositoryName,
                tag != null ? tag : (ref != null && !ref.startsWith("sha256:") ? ref : null),
                ref != null && ref.startsWith("sha256:") ? ref : null);
        if (img == null) {
            return null;
        }
        Image out = new Image();
        out.setRegistryId(repo.getRegistryId());
        out.setRepositoryName(repositoryName);
        out.setImageId(new ImageIdentifier(img.tag, img.digest));
        out.setImageManifest(img.manifest);
        out.setImageManifestMediaType(img.mediaType);
        return out;
    }

    private LocalImage findLocalImage(String region, String account, String repoName, String tag, String digest) {
        if (digest != null && !digest.isBlank()) {
            return localImages.get(localImageKey(region, account, repoName, digest));
        }
        if (tag != null && !tag.isBlank()) {
            String prefix = key(region, account, repoName) + "::";
            for (LocalImage img : localImages.values()) {
                if (tag.equals(img.tag)) {
                    // Only match images in this repo: keys are prefix+digest
                    String k = localImageKey(region, account, repoName, img.digest);
                    if (localImages.containsKey(k)) {
                        return img;
                    }
                }
            }
            for (Map.Entry<String, LocalImage> e : localImages.entrySet()) {
                if (e.getKey().startsWith(prefix) && tag.equals(e.getValue().tag)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private void evictLocalImage(String region, String account, String repoName, String digest, String tag) {
        if (digest != null) {
            localImages.remove(localImageKey(region, account, repoName, digest));
            localLayers.remove(layerKey(region, account, repoName, digest));
        }
        if (tag != null) {
            LocalImage img = findLocalImage(region, account, repoName, tag, null);
            if (img != null) {
                localImages.remove(localImageKey(region, account, repoName, img.digest));
            }
        }
    }

    private boolean registryBlobExists(Repository repo, String region, String repositoryName, String digest) {
        return registryBlobSize(repo, region, repositoryName, digest) != null;
    }

    private Long registryBlobSize(Repository repo, String region, String repositoryName, String digest) {
        try {
            registryManager.ensureStarted();
            RegistryHttpClient http = registryManager.httpClient();
            for (String name : registryLookupNames(repo, region, repositoryName)) {
                Long size = http.headBlob(name, digest);
                if (size != null) {
                    return size;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String firstRegistryNameWithBlob(Repository repo, String region, String repositoryName, String digest) {
        try {
            registryManager.ensureStarted();
            RegistryHttpClient http = registryManager.httpClient();
            for (String name : registryLookupNames(repo, region, repositoryName)) {
                if (http.headBlob(name, digest) != null) {
                    return name;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void pushBlobBestEffort(Repository repo, String region, String repositoryName,
                                    String digest, byte[] data) {
        try {
            registryManager.ensureStarted();
            String internal = registryManager.internalRepoName(repo.getRegistryId(), region, repositoryName);
            registryManager.httpClient().putBlob(internal, digest, data);
        } catch (Exception e) {
            LOG.debugv("Registry blob push skipped for {0}/{1}: {2}", repositoryName, digest, e.getMessage());
        }
    }

    private String pushManifestBestEffort(Repository repo, String region, String repositoryName,
                                          String reference, String manifest, String mediaType) {
        try {
            registryManager.ensureStarted();
            String internal = registryManager.internalRepoName(repo.getRegistryId(), region, repositoryName);
            return registryManager.httpClient().putManifest(internal, reference, manifest, mediaType);
        } catch (Exception e) {
            LOG.debugv("Registry manifest push skipped for {0}/{1}: {2}", repositoryName, reference, e.getMessage());
            return null;
        }
    }

    /**
     * Hostname-style {@code docker push} lands at {@code /v2/<repoName>/...};
     * path-style and PutImage best-effort writes use
     * {@code /v2/<account>/<region>/<repoName>/...}. Control-plane reads try both.
     */
    private List<String> registryLookupNames(Repository repo, String region, String repositoryName) {
        String internal = registryManager.internalRepoName(repo.getRegistryId(), region, repositoryName);
        if (internal.equals(repositoryName)) {
            return List.of(internal);
        }
        return List.of(internal, repositoryName);
    }

    private List<String> listTagsFromRegistry(RegistryHttpClient http, List<String> names) {
        List<String> tags = new ArrayList<>();
        for (String name : names) {
            try {
                for (String tag : http.listTags(name)) {
                    if (!tags.contains(tag)) {
                        tags.add(tag);
                    }
                }
            } catch (Exception e) {
                LOG.debugv("Registry tags/list {0} failed: {1}", name, e.getMessage());
            }
        }
        return tags;
    }

    private String headManifestDigest(RegistryHttpClient http, List<String> names, String reference) {
        for (String name : names) {
            try {
                String digest = http.headManifestDigest(name, reference, null);
                if (digest != null) {
                    return digest;
                }
            } catch (Exception e) {
                LOG.debugv("Registry HEAD manifest {0}/{1} failed: {2}", name, reference, e.getMessage());
            }
        }
        return null;
    }

    private RegistryHttpClient.ManifestResult getManifestFromRegistry(RegistryHttpClient http,
                                                                      List<String> names,
                                                                      String reference,
                                                                      List<String> acceptedMediaTypes) {
        for (String name : names) {
            try {
                RegistryHttpClient.ManifestResult manifest = http.getManifest(name, reference, acceptedMediaTypes);
                if (manifest != null) {
                    return manifest;
                }
            } catch (Exception e) {
                LOG.debugv("Registry GET manifest {0}/{1} failed: {2}", name, reference, e.getMessage());
            }
        }
        return null;
    }

    private boolean deleteManifestFromRegistry(RegistryHttpClient http, List<String> names, String digest) {
        boolean deleted = false;
        for (String name : names) {
            try {
                if (http.deleteManifest(name, digest)) {
                    deleted = true;
                }
            } catch (Exception e) {
                LOG.debugv("Registry DELETE manifest {0}/{1} failed: {2}", name, digest, e.getMessage());
            }
        }
        return deleted;
    }

    private void publishImageAction(String actionType, Repository repo, String repositoryName,
                                    String region, String digest, String tag, String mediaType) {
        if (eventBridgeService == null) {
            return;
        }
        try {
            ObjectNode detail = JSON.createObjectNode();
            detail.put("action-type", actionType);
            detail.put("result", "SUCCESS");
            detail.put("repository-name", repositoryName);
            if (digest != null && !digest.isBlank()) {
                detail.put("image-digest", digest);
            }
            if (tag != null && !tag.isBlank()) {
                detail.put("image-tag", tag);
            }
            if (mediaType != null && !mediaType.isBlank()) {
                detail.put("manifest-media-type", mediaType);
            }
            ArrayNode resources = JSON.createArrayNode();
            if (repo.getRepositoryArn() != null) {
                resources.add(repo.getRepositoryArn());
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("Source", "aws.ecr");
            entry.put("DetailType", "ECR Image Action");
            entry.put("Detail", JSON.writeValueAsString(detail));
            entry.put("Resources", resources);
            eventBridgeService.putEvents(List.of(entry), region);
        } catch (Exception e) {
            LOG.warnv("Failed to publish ECR Image Action to EventBridge: {0}", e.getMessage());
        }
    }

    private static final class LayerUpload {
        final String repositoryName;
        final String registryId;
        final String region;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        LayerUpload(String repositoryName, String registryId, String region) {
            this.repositoryName = repositoryName;
            this.registryId = registryId;
            this.region = region;
        }

        synchronized void write(int offset, byte[] part) {
            byte[] current = buffer.toByteArray();
            if (offset == current.length) {
                buffer.writeBytes(part);
                return;
            }
            int end = Math.max(current.length, offset + part.length);
            byte[] next = Arrays.copyOf(current, end);
            System.arraycopy(part, 0, next, offset, part.length);
            buffer.reset();
            buffer.writeBytes(next);
        }

        synchronized byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }

    private static final class LocalImage {
        String digest;
        final String tag;
        final String manifest;
        final String mediaType;
        final Instant pushedAt;

        LocalImage(String digest, String tag, String manifest, String mediaType, Instant pushedAt) {
            this.digest = digest;
            this.tag = tag;
            this.manifest = manifest;
            this.mediaType = mediaType;
            this.pushedAt = pushedAt;
        }
    }
}
