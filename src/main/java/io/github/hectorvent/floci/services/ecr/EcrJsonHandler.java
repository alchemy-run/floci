package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.ecr.model.AuthorizationData;
import io.github.hectorvent.floci.services.ecr.model.Image;
import io.github.hectorvent.floci.services.ecr.model.ImageDetail;
import io.github.hectorvent.floci.services.ecr.model.ImageFailure;
import io.github.hectorvent.floci.services.ecr.model.ImageIdentifier;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * AWS JSON 1.1 dispatcher for the {@code AmazonEC2ContainerRegistry_V20150921}
 * target prefix.
 */
@ApplicationScoped
public class EcrJsonHandler {

    private final EcrService service;
    private final ObjectMapper objectMapper;

    @Inject
    public EcrJsonHandler(EcrService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateRepository" -> handleCreateRepository(request, region);
            case "DescribeRepositories" -> handleDescribeRepositories(request, region);
            case "BatchGetRepositoryScanningConfiguration" ->
                    handleBatchGetRepositoryScanningConfiguration(request, region);
            case "DeleteRepository" -> handleDeleteRepository(request, region);
            case "GetAuthorizationToken" -> handleGetAuthorizationToken(request);
            case "ListImages" -> handleListImages(request, region);
            case "DescribeImages" -> handleDescribeImages(request, region);
            case "BatchGetImage" -> handleBatchGetImage(request, region);
            case "BatchDeleteImage" -> handleBatchDeleteImage(request, region);
            case "PutImageTagMutability" -> handlePutImageTagMutability(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            case "PutLifecyclePolicy" -> handlePutLifecyclePolicy(request, region);
            case "GetLifecyclePolicy" -> handleGetLifecyclePolicy(request, region);
            case "DeleteLifecyclePolicy" -> handleDeleteLifecyclePolicy(request, region);
            case "SetRepositoryPolicy" -> handleSetRepositoryPolicy(request, region);
            case "GetRepositoryPolicy" -> handleGetRepositoryPolicy(request, region);
            case "DeleteRepositoryPolicy" -> handleDeleteRepositoryPolicy(request, region);
            case "PutImageScanningConfiguration" -> handlePutImageScanningConfiguration(request, region);
            case "PutRegistryPolicy" -> handlePutRegistryPolicy(request, region);
            case "GetRegistryPolicy" -> handleGetRegistryPolicy(request, region);
            case "DeleteRegistryPolicy" -> handleDeleteRegistryPolicy(request, region);
            case "InitiateLayerUpload" -> handleInitiateLayerUpload(request, region);
            case "UploadLayerPart" -> handleUploadLayerPart(request, region);
            case "CompleteLayerUpload" -> handleCompleteLayerUpload(request, region);
            case "PutImage" -> handlePutImage(request, region);
            case "GetDownloadUrlForLayer" -> handleGetDownloadUrlForLayer(request, region);
            case "BatchCheckLayerAvailability" -> handleBatchCheckLayerAvailability(request, region);
            case "StartImageScan" -> handleStartImageScan(request, region);
            case "DescribeImageScanFindings" -> handleDescribeImageScanFindings(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateRepository(JsonNode request, String region) {
        String repositoryName = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        String tagMutability = request.path("imageTagMutability").asText(null);
        Boolean scanOnPush = request.path("imageScanningConfiguration").has("scanOnPush")
                ? request.path("imageScanningConfiguration").path("scanOnPush").asBoolean()
                : null;
        String encType = request.path("encryptionConfiguration").path("encryptionType").asText(null);
        String kmsKey = request.path("encryptionConfiguration").path("kmsKey").asText(null);
        Map<String, String> tags = parseTags(request.path("tags"));

        Repository repo = service.createRepository(repositoryName, registryId, tagMutability,
                scanOnPush, encType, kmsKey, tags, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("repository", buildRepository(repo));
        return Response.ok(response).build();
    }

    private Response handleDescribeRepositories(JsonNode request, String region) {
        List<String> names = parseStringList(request.path("repositoryNames"));
        String registryId = request.path("registryId").asText(null);

        List<Repository> repos = service.describeRepositories(names, registryId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (Repository r : repos) {
            arr.add(buildRepository(r));
        }
        response.set("repositories", arr);
        return Response.ok(response).build();
    }

    private Response handleBatchGetRepositoryScanningConfiguration(JsonNode request, String region) {
        // Scanning configuration is not modeled. Resolve names via describeRepositories
        // and synthesize a wire-accurate RepositoryScanningConfiguration per repo:
        // scanFrequency derived from scanOnPush (AWS enum: SCAN_ON_PUSH|CONTINUOUS_SCAN|
        // MANUAL), with empty appliedScanFilters. Note: real AWS reports unknown repos
        // in the `failures` array; here describeRepositories throws
        // RepositoryNotFoundException, failing the whole batch instead.
        List<String> names = parseStringList(request.path("repositoryNames"));
        String registryId = request.path("registryId").asText(null);

        List<Repository> repos = service.describeRepositories(names, registryId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode configs = objectMapper.createArrayNode();
        for (Repository r : repos) {
            ObjectNode cfg = objectMapper.createObjectNode();
            cfg.put("repositoryArn", r.getRepositoryArn());
            cfg.put("repositoryName", r.getRepositoryName());
            cfg.put("scanOnPush", r.isScanOnPush());
            cfg.put("scanFrequency", r.isScanOnPush() ? "SCAN_ON_PUSH" : "MANUAL");
            cfg.set("appliedScanFilters", objectMapper.createArrayNode());
            configs.add(cfg);
        }
        response.set("scanningConfigurations", configs);
        response.set("failures", objectMapper.createArrayNode());
        return Response.ok(response).build();
    }

    private Response handleDeleteRepository(JsonNode request, String region) {
        String repositoryName = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        boolean force = request.path("force").asBoolean(false);

        Repository repo = service.deleteRepository(repositoryName, registryId, force, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("repository", buildRepository(repo));
        return Response.ok(response).build();
    }

    private Response handleGetAuthorizationToken(JsonNode request) {
        AuthorizationData data = service.getAuthorizationToken();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("authorizationToken", data.getAuthorizationToken());
        entry.put("expiresAt", data.getExpiresAt().getEpochSecond());
        entry.put("proxyEndpoint", data.getProxyEndpoint());
        arr.add(entry);
        response.set("authorizationData", arr);
        return Response.ok(response).build();
    }

    // ============================================================
    // Image inspection / batch operations
    // ============================================================

    private Response handleListImages(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        List<ImageIdentifier> ids = service.listImages(repo, registryId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (ImageIdentifier id : ids) {
            arr.add(buildImageIdentifier(id));
        }
        response.set("imageIds", arr);
        return Response.ok(response).build();
    }

    private Response handleDescribeImages(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        List<ImageIdentifier> requested = parseImageIds(request.path("imageIds"));

        EcrService.DescribeImagesResult result = service.describeImages(repo, requested, registryId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = objectMapper.createArrayNode();
        for (ImageDetail d : result.imageDetails()) {
            details.add(buildImageDetail(d));
        }
        response.set("imageDetails", details);
        ArrayNode failures = objectMapper.createArrayNode();
        for (ImageFailure f : result.failures()) {
            failures.add(buildImageFailure(f));
        }
        response.set("failures", failures);
        return Response.ok(response).build();
    }

    private Response handleBatchGetImage(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        List<ImageIdentifier> ids = parseImageIds(request.path("imageIds"));
        List<String> accepted = parseStringList(request.path("acceptedMediaTypes"));

        EcrService.BatchGetImageResult result = service.batchGetImage(repo, ids, accepted, registryId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode imgs = objectMapper.createArrayNode();
        for (Image img : result.images()) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("registryId", img.getRegistryId());
            n.put("repositoryName", img.getRepositoryName());
            n.set("imageId", buildImageIdentifier(img.getImageId()));
            if (img.getImageManifest() != null) {
                n.put("imageManifest", img.getImageManifest());
            }
            if (img.getImageManifestMediaType() != null) {
                n.put("imageManifestMediaType", img.getImageManifestMediaType());
            }
            imgs.add(n);
        }
        response.set("images", imgs);
        ArrayNode failures = objectMapper.createArrayNode();
        for (ImageFailure f : result.failures()) {
            failures.add(buildImageFailure(f));
        }
        response.set("failures", failures);
        return Response.ok(response).build();
    }

    private Response handleBatchDeleteImage(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        List<ImageIdentifier> ids = parseImageIds(request.path("imageIds"));

        EcrService.BatchDeleteImageResult result = service.batchDeleteImage(repo, ids, registryId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (ImageIdentifier id : result.imageIds()) {
            arr.add(buildImageIdentifier(id));
        }
        response.set("imageIds", arr);
        ArrayNode failures = objectMapper.createArrayNode();
        for (ImageFailure f : result.failures()) {
            failures.add(buildImageFailure(f));
        }
        response.set("failures", failures);
        return Response.ok(response).build();
    }

    // ============================================================
    // Tag mutability + resource tags
    // ============================================================

    private Response handlePutImageTagMutability(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        String mutability = request.path("imageTagMutability").asText(null);

        Repository updated = service.putImageTagMutability(repo, registryId, mutability, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", updated.getRegistryId());
        response.put("repositoryName", updated.getRepositoryName());
        response.put("imageTagMutability", updated.getImageTagMutability());
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String resourceArn = request.path("resourceArn").asText(null);
        String repoName = repoNameFromArn(resourceArn);
        Map<String, String> tags = parseTags(request.path("tags"));
        service.tagResource(repoName, accountFromArn(resourceArn), tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String resourceArn = request.path("resourceArn").asText(null);
        String repoName = repoNameFromArn(resourceArn);
        List<String> keys = parseStringList(request.path("tagKeys"));
        service.untagResource(repoName, accountFromArn(resourceArn), keys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode request, String region) {
        String resourceArn = request.path("resourceArn").asText(null);
        String repoName = repoNameFromArn(resourceArn);
        Map<String, String> tags = service.listTagsForResource(repoName, accountFromArn(resourceArn), region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (Map.Entry<String, String> e : tags.entrySet()) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("Key", e.getKey());
            entry.put("Value", e.getValue());
            arr.add(entry);
        }
        response.set("tags", arr);
        return Response.ok(response).build();
    }

    // ============================================================
    // Lifecycle + repository policies
    // ============================================================

    private Response handlePutLifecyclePolicy(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        String text = request.path("lifecyclePolicyText").asText(null);
        Repository updated = service.putLifecyclePolicy(repo, registryId, text, region);
        return lifecycleResponse(updated);
    }

    private Response handleGetLifecyclePolicy(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        Repository r = service.getLifecyclePolicy(repo, registryId, region);
        return lifecycleResponse(r);
    }

    private Response handleDeleteLifecyclePolicy(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        Repository r = service.deleteLifecyclePolicy(repo, registryId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", r.getRegistryId());
        response.put("repositoryName", r.getRepositoryName());
        return Response.ok(response).build();
    }

    private Response lifecycleResponse(Repository r) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", r.getRegistryId());
        response.put("repositoryName", r.getRepositoryName());
        if (r.getLifecyclePolicyText() != null) {
            response.put("lifecyclePolicyText", r.getLifecyclePolicyText());
        }
        return Response.ok(response).build();
    }

    private Response handleSetRepositoryPolicy(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        String text = request.path("policyText").asText(null);
        Repository updated = service.setRepositoryPolicy(repo, registryId, text, region);
        return repoPolicyResponse(updated);
    }

    private Response handleGetRepositoryPolicy(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        Repository r = service.getRepositoryPolicy(repo, registryId, region);
        return repoPolicyResponse(r);
    }

    private Response handleDeleteRepositoryPolicy(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        Repository r = service.deleteRepositoryPolicy(repo, registryId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", r.getRegistryId());
        response.put("repositoryName", r.getRepositoryName());
        return Response.ok(response).build();
    }

    private Response repoPolicyResponse(Repository r) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", r.getRegistryId());
        response.put("repositoryName", r.getRepositoryName());
        if (r.getRepositoryPolicyText() != null) {
            response.put("policyText", r.getRepositoryPolicyText());
        }
        return Response.ok(response).build();
    }

    private Response handlePutImageScanningConfiguration(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        Boolean scanOnPush = request.path("imageScanningConfiguration").has("scanOnPush")
                ? request.path("imageScanningConfiguration").path("scanOnPush").asBoolean()
                : null;
        Repository updated = service.putImageScanningConfiguration(repo, registryId, scanOnPush, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", updated.getRegistryId());
        response.put("repositoryName", updated.getRepositoryName());
        ObjectNode scanCfg = objectMapper.createObjectNode();
        scanCfg.put("scanOnPush", updated.isScanOnPush());
        response.set("imageScanningConfiguration", scanCfg);
        return Response.ok(response).build();
    }

    private Response handlePutRegistryPolicy(JsonNode request, String region) {
        String text = request.path("policyText").asText(null);
        EcrService.RegistryPolicyResult result = service.putRegistryPolicy(text, region);
        return registryPolicyResponse(result);
    }

    private Response handleGetRegistryPolicy(JsonNode request, String region) {
        return registryPolicyResponse(service.getRegistryPolicy(region));
    }

    private Response handleDeleteRegistryPolicy(JsonNode request, String region) {
        return registryPolicyResponse(service.deleteRegistryPolicy(region));
    }

    private Response registryPolicyResponse(EcrService.RegistryPolicyResult result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", result.registryId());
        if (result.policyText() != null) {
            response.put("policyText", result.policyText());
        }
        return Response.ok(response).build();
    }

    private Response handleInitiateLayerUpload(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        EcrService.InitiateLayerUploadResult result = service.initiateLayerUpload(repo, registryId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("uploadId", result.uploadId());
        response.put("partSize", result.partSize());
        return Response.ok(response).build();
    }

    private Response handleUploadLayerPart(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        String uploadId = request.path("uploadId").asText(null);
        long first = request.path("partFirstByte").asLong(0);
        long last = request.path("partLastByte").asLong(0);
        byte[] blob = decodeBlob(request.get("layerPartBlob"));
        EcrService.UploadLayerPartResult result =
                service.uploadLayerPart(repo, registryId, region, uploadId, first, last, blob);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", result.registryId());
        response.put("repositoryName", result.repositoryName());
        response.put("uploadId", result.uploadId());
        response.put("lastByteReceived", result.lastByteReceived());
        return Response.ok(response).build();
    }

    private Response handleCompleteLayerUpload(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        String uploadId = request.path("uploadId").asText(null);
        List<String> digests = parseStringList(request.path("layerDigests"));
        EcrService.CompleteLayerUploadResult result =
                service.completeLayerUpload(repo, registryId, region, uploadId, digests);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", result.registryId());
        response.put("repositoryName", result.repositoryName());
        response.put("uploadId", result.uploadId());
        response.put("layerDigest", result.layerDigest());
        return Response.ok(response).build();
    }

    private Response handlePutImage(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        String manifest = request.path("imageManifest").asText(null);
        String mediaType = request.path("imageManifestMediaType").asText(null);
        String tag = request.path("imageTag").asText(null);
        String digest = request.path("imageDigest").asText(null);
        Image img = service.putImage(repo, registryId, region, manifest, mediaType, tag, digest);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode n = objectMapper.createObjectNode();
        n.put("registryId", img.getRegistryId());
        n.put("repositoryName", img.getRepositoryName());
        n.set("imageId", buildImageIdentifier(img.getImageId()));
        if (img.getImageManifest() != null) {
            n.put("imageManifest", img.getImageManifest());
        }
        if (img.getImageManifestMediaType() != null) {
            n.put("imageManifestMediaType", img.getImageManifestMediaType());
        }
        response.set("image", n);
        return Response.ok(response).build();
    }

    private Response handleGetDownloadUrlForLayer(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        String digest = request.path("layerDigest").asText(null);
        EcrService.DownloadUrl result = service.getDownloadUrlForLayer(repo, registryId, region, digest);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("downloadUrl", result.downloadUrl());
        response.put("layerDigest", result.layerDigest());
        return Response.ok(response).build();
    }

    private Response handleBatchCheckLayerAvailability(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        List<String> digests = parseStringList(request.path("layerDigests"));
        EcrService.LayerAvailabilityResult result =
                service.batchCheckLayerAvailability(repo, registryId, region, digests);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode layers = objectMapper.createArrayNode();
        for (EcrService.LayerInfo layer : result.layers()) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("layerDigest", layer.layerDigest());
            n.put("layerAvailability", layer.layerAvailability());
            n.put("layerSize", layer.layerSize());
            if (layer.mediaType() != null) {
                n.put("mediaType", layer.mediaType());
            }
            layers.add(n);
        }
        response.set("layers", layers);
        ArrayNode failures = objectMapper.createArrayNode();
        for (EcrService.LayerFailureInfo f : result.failures()) {
            ObjectNode n = objectMapper.createObjectNode();
            if (f.layerDigest() != null) {
                n.put("layerDigest", f.layerDigest());
            }
            n.put("failureCode", f.failureCode());
            n.put("failureReason", f.failureReason());
            failures.add(n);
        }
        response.set("failures", failures);
        return Response.ok(response).build();
    }

    private Response handleStartImageScan(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        ImageIdentifier id = new ImageIdentifier(
                request.path("imageId").path("imageTag").asText(null),
                request.path("imageId").path("imageDigest").asText(null));
        service.startImageScan(repo, registryId, region, id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeImageScanFindings(JsonNode request, String region) {
        String repo = request.path("repositoryName").asText(null);
        String registryId = request.path("registryId").asText(null);
        ImageIdentifier id = new ImageIdentifier(
                request.path("imageId").path("imageTag").asText(null),
                request.path("imageId").path("imageDigest").asText(null));
        service.describeImageScanFindings(repo, registryId, region, id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private static byte[] decodeBlob(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new byte[0];
        }
        if (node.isBinary()) {
            try {
                return node.binaryValue();
            } catch (Exception e) {
                return new byte[0];
            }
        }
        String text = node.asText("");
        if (text.isEmpty()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(text);
    }

    // ============================================================
    // Builders / parsers
    // ============================================================

    private ObjectNode buildImageIdentifier(ImageIdentifier id) {
        ObjectNode n = objectMapper.createObjectNode();
        if (id.getImageTag() != null) n.put("imageTag", id.getImageTag());
        if (id.getImageDigest() != null) n.put("imageDigest", id.getImageDigest());
        return n;
    }

    private ObjectNode buildImageDetail(ImageDetail d) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("registryId", d.getRegistryId());
        n.put("repositoryName", d.getRepositoryName());
        if (d.getImageDigest() != null) n.put("imageDigest", d.getImageDigest());
        ArrayNode tags = objectMapper.createArrayNode();
        for (String t : d.getImageTags()) tags.add(t);
        n.set("imageTags", tags);
        n.put("imageSizeInBytes", d.getImageSizeInBytes());
        if (d.getImagePushedAt() != null) {
            n.put("imagePushedAt", d.getImagePushedAt().getEpochSecond());
        }
        if (d.getImageManifestMediaType() != null) {
            n.put("imageManifestMediaType", d.getImageManifestMediaType());
        }
        if (d.getArtifactMediaType() != null) {
            n.put("artifactMediaType", d.getArtifactMediaType());
        }
        return n;
    }

    private ObjectNode buildImageFailure(ImageFailure f) {
        ObjectNode n = objectMapper.createObjectNode();
        if (f.getImageId() != null) n.set("imageId", buildImageIdentifier(f.getImageId()));
        if (f.getFailureCode() != null) n.put("failureCode", f.getFailureCode());
        if (f.getFailureReason() != null) n.put("failureReason", f.getFailureReason());
        return n;
    }

    private static List<ImageIdentifier> parseImageIds(JsonNode node) {
        List<ImageIdentifier> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return out;
        }
        node.forEach(e -> out.add(new ImageIdentifier(
                e.path("imageTag").asText(null),
                e.path("imageDigest").asText(null))));
        return out;
    }

    private static String repoNameFromArn(String arn) {
        if (arn == null) return null;
        int idx = arn.indexOf(":repository/");
        return idx < 0 ? null : arn.substring(idx + ":repository/".length());
    }

    private static String accountFromArn(String arn) {
        return AwsArnUtils.accountOrDefault(arn, null);
    }

    private ObjectNode buildRepository(Repository repo) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("repositoryArn", repo.getRepositoryArn());
        n.put("registryId", repo.getRegistryId());
        n.put("repositoryName", repo.getRepositoryName());
        n.put("repositoryUri", repo.getRepositoryUri());
        if (repo.getCreatedAt() != null) {
            n.put("createdAt", repo.getCreatedAt().getEpochSecond());
        }
        n.put("imageTagMutability", repo.getImageTagMutability());

        ObjectNode scanCfg = objectMapper.createObjectNode();
        scanCfg.put("scanOnPush", repo.isScanOnPush());
        n.set("imageScanningConfiguration", scanCfg);

        ObjectNode enc = objectMapper.createObjectNode();
        enc.put("encryptionType", repo.getEncryptionType());
        if (repo.getKmsKey() != null) {
            enc.put("kmsKey", repo.getKmsKey());
        }
        n.set("encryptionConfiguration", enc);
        return n;
    }

    private static List<String> parseStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Map<String, String> parseTags(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return new HashMap<>();
        }
        Map<String, String> tags = new HashMap<>();
        Iterator<JsonNode> it = node.elements();
        while (it.hasNext()) {
            JsonNode entry = it.next();
            String key = entry.path("Key").asText(null);
            String value = entry.path("Value").asText("");
            if (key != null) {
                tags.put(key, value);
            }
        }
        return tags;
    }
}
