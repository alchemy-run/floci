package io.github.hectorvent.floci.services.ecrpublic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecrpublic.model.CatalogData;
import io.github.hectorvent.floci.services.ecrpublic.model.Repository;
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
 * AWS JSON 1.1 dispatcher for the {@code SpencerFrontendService} target prefix
 * (Amazon ECR Public).
 */
@ApplicationScoped
public class EcrPublicJsonHandler {


    private final EcrPublicService service;
    private final ObjectMapper objectMapper;

    @Inject
    public EcrPublicJsonHandler(EcrPublicService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, @SuppressWarnings("unused") String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        return switch (action) {
            case "CreateRepository" -> handleCreateRepository(body);
            case "DescribeRepositories" -> handleDescribeRepositories(body);
            case "DeleteRepository" -> handleDeleteRepository(body);
            case "GetRepositoryCatalogData" -> handleGetRepositoryCatalogData(body);
            case "PutRepositoryCatalogData" -> handlePutRepositoryCatalogData(body);
            case "SetRepositoryPolicy" -> handleSetRepositoryPolicy(body);
            case "GetRepositoryPolicy" -> handleGetRepositoryPolicy(body);
            case "DeleteRepositoryPolicy" -> handleDeleteRepositoryPolicy(body);
            case "TagResource" -> handleTagResource(body);
            case "UntagResource" -> handleUntagResource(body);
            case "ListTagsForResource" -> handleListTagsForResource(body);
            case "GetAuthorizationToken" -> handleGetAuthorizationToken();
            case "DescribeRegistries" -> handleDescribeRegistries();
            case "GetRegistryCatalogData" -> handleGetRegistryCatalogData();
            case "PutRegistryCatalogData" -> handlePutRegistryCatalogData(body);
            case "InitiateLayerUpload" -> handleInitiateLayerUpload(body);
            case "UploadLayerPart" -> handleUploadLayerPart(body);
            case "CompleteLayerUpload" -> handleCompleteLayerUpload(body);
            case "PutImage" -> handlePutImage(body);
            case "BatchCheckLayerAvailability" -> handleBatchCheckLayerAvailability(body);
            case "BatchDeleteImage" -> handleBatchDeleteImage(body);
            case "DescribeImages" -> handleDescribeImages(body);
            case "DescribeImageTags" -> handleDescribeImageTags(body);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateRepository(JsonNode request) {
        String repositoryName = text(request, "repositoryName");
        CatalogData catalog = parseCatalog(request.path("catalogData"));
        Map<String, String> tags = parseTags(request.path("tags"));
        Repository repo = service.createRepository(repositoryName, catalog, tags);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("repository", buildRepository(repo));
        if (repo.getCatalogData() != null) {
            response.set("catalogData", buildCatalog(repo.getCatalogData()));
        }
        return Response.ok(response).build();
    }

    private Response handleDescribeRepositories(JsonNode request) {
        List<String> names = parseStringList(request.path("repositoryNames"));
        String registryId = text(request, "registryId");
        List<Repository> repos = service.describeRepositories(names, registryId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (Repository repo : repos) {
            arr.add(buildRepository(repo));
        }
        response.set("repositories", arr);
        return Response.ok(response).build();
    }

    private Response handleDeleteRepository(JsonNode request) {
        Repository repo = service.deleteRepository(
                text(request, "repositoryName"),
                text(request, "registryId"),
                request.path("force").asBoolean(false));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("repository", buildRepository(repo));
        return Response.ok(response).build();
    }

    private Response handleGetRepositoryCatalogData(JsonNode request) {
        CatalogData catalog = service.getRepositoryCatalogData(
                text(request, "repositoryName"),
                text(request, "registryId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("catalogData", buildCatalog(catalog));
        return Response.ok(response).build();
    }

    private Response handlePutRepositoryCatalogData(JsonNode request) {
        CatalogData catalog = service.putRepositoryCatalogData(
                text(request, "repositoryName"),
                text(request, "registryId"),
                parseCatalog(request.path("catalogData")));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("catalogData", buildCatalog(catalog));
        return Response.ok(response).build();
    }

    private Response handleSetRepositoryPolicy(JsonNode request) {
        Repository repo = service.setRepositoryPolicy(
                text(request, "repositoryName"),
                text(request, "registryId"),
                text(request, "policyText"));
        return policyResponse(repo);
    }

    private Response handleGetRepositoryPolicy(JsonNode request) {
        return policyResponse(service.getRepositoryPolicy(
                text(request, "repositoryName"),
                text(request, "registryId")));
    }

    private Response handleDeleteRepositoryPolicy(JsonNode request) {
        Repository repo = service.deleteRepositoryPolicy(
                text(request, "repositoryName"),
                text(request, "registryId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", repo.getRegistryId());
        response.put("repositoryName", repo.getRepositoryName());
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request) {
        String resourceArn = text(request, "resourceArn");
        service.tagResource(repoNameFromArn(resourceArn), parseTags(request.path("tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request) {
        String resourceArn = text(request, "resourceArn");
        service.untagResource(repoNameFromArn(resourceArn), parseStringList(request.path("tagKeys")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetAuthorizationToken() {
        EcrPublicService.AuthorizationToken token = service.getAuthorizationToken();
        ObjectNode data = objectMapper.createObjectNode();
        data.put("authorizationToken", token.authorizationToken());
        data.put("expiresAt", token.expiresAt().getEpochSecond());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("authorizationData", data);
        return Response.ok(response).build();
    }

    private Response handleDescribeRegistries() {
        EcrPublicService.Registry registry = service.describeRegistry();
        ObjectNode n = objectMapper.createObjectNode();
        n.put("registryId", registry.registryId());
        n.put("registryArn", registry.registryArn());
        n.put("registryUri", registry.registryUri());
        n.put("verified", registry.verified());
        ArrayNode aliases = objectMapper.createArrayNode();
        for (EcrPublicService.RegistryAlias alias : registry.aliases()) {
            ObjectNode a = objectMapper.createObjectNode();
            a.put("name", alias.name());
            a.put("status", alias.status());
            a.put("primaryRegistryAlias", alias.primaryRegistryAlias());
            a.put("defaultRegistryAlias", alias.defaultRegistryAlias());
            aliases.add(a);
        }
        n.set("aliases", aliases);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode registries = objectMapper.createArrayNode();
        registries.add(n);
        response.set("registries", registries);
        return Response.ok(response).build();
    }

    private Response handleGetRegistryCatalogData() {
        ObjectNode catalog = objectMapper.createObjectNode();
        String displayName = service.getRegistryDisplayName();
        if (displayName != null) {
            catalog.put("displayName", displayName);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("registryCatalogData", catalog);
        return Response.ok(response).build();
    }

    private Response handlePutRegistryCatalogData(JsonNode request) {
        String stored = service.putRegistryDisplayName(text(request, "displayName"));
        ObjectNode catalog = objectMapper.createObjectNode();
        if (stored != null) {
            catalog.put("displayName", stored);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("registryCatalogData", catalog);
        return Response.ok(response).build();
    }

    private Response handleInitiateLayerUpload(JsonNode request) {
        EcrPublicService.InitiateLayerUploadResult result = service.initiateLayerUpload(
                text(request, "repositoryName"), text(request, "registryId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("uploadId", result.uploadId());
        response.put("partSize", result.partSize());
        return Response.ok(response).build();
    }

    private Response handleUploadLayerPart(JsonNode request) {
        EcrPublicService.UploadLayerPartResult result = service.uploadLayerPart(
                text(request, "repositoryName"),
                text(request, "registryId"),
                text(request, "uploadId"),
                request.path("partFirstByte").asLong(0),
                request.path("partLastByte").asLong(0),
                decodeBlob(request.get("layerPartBlob")));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", result.registryId());
        response.put("repositoryName", result.repositoryName());
        response.put("uploadId", result.uploadId());
        response.put("lastByteReceived", result.lastByteReceived());
        return Response.ok(response).build();
    }

    private Response handleCompleteLayerUpload(JsonNode request) {
        EcrPublicService.CompleteLayerUploadResult result = service.completeLayerUpload(
                text(request, "repositoryName"),
                text(request, "registryId"),
                text(request, "uploadId"),
                parseStringList(request.path("layerDigests")));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", result.registryId());
        response.put("repositoryName", result.repositoryName());
        response.put("uploadId", result.uploadId());
        response.put("layerDigest", result.layerDigest());
        return Response.ok(response).build();
    }

    private Response handlePutImage(JsonNode request) {
        EcrPublicService.PutImageResult img = service.putImage(
                text(request, "repositoryName"),
                text(request, "registryId"),
                request.path("imageManifest").asText(null),
                text(request, "imageManifestMediaType"),
                text(request, "imageTag"),
                text(request, "imageDigest"));
        ObjectNode image = objectMapper.createObjectNode();
        image.put("registryId", img.registryId());
        image.put("repositoryName", img.repositoryName());
        ObjectNode imageId = objectMapper.createObjectNode();
        if (img.imageTag() != null) {
            imageId.put("imageTag", img.imageTag());
        }
        imageId.put("imageDigest", img.imageDigest());
        image.set("imageId", imageId);
        image.put("imageManifest", img.imageManifest());
        image.put("imageManifestMediaType", img.imageManifestMediaType());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("image", image);
        return Response.ok(response).build();
    }

    private Response handleBatchCheckLayerAvailability(JsonNode request) {
        EcrPublicService.LayerAvailabilityResult result = service.batchCheckLayerAvailability(
                text(request, "repositoryName"),
                text(request, "registryId"),
                parseStringList(request.path("layerDigests")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode layers = objectMapper.createArrayNode();
        for (EcrPublicService.LayerInfo layer : result.layers()) {
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
        for (EcrPublicService.LayerFailure f : result.failures()) {
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

    private Response handleBatchDeleteImage(JsonNode request) {
        List<EcrPublicService.ImageId> ids = new ArrayList<>();
        JsonNode imageIds = request.path("imageIds");
        if (imageIds.isArray()) {
            imageIds.forEach(e -> ids.add(new EcrPublicService.ImageId(
                    text(e, "imageTag"), text(e, "imageDigest"))));
        }
        EcrPublicService.BatchDeleteImageResult result = service.batchDeleteImage(
                text(request, "repositoryName"), text(request, "registryId"), ids);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode deleted = objectMapper.createArrayNode();
        for (EcrPublicService.ImageId id : result.imageIds()) {
            deleted.add(buildImageId(id));
        }
        response.set("imageIds", deleted);
        ArrayNode failures = objectMapper.createArrayNode();
        for (EcrPublicService.ImageFailure f : result.failures()) {
            ObjectNode n = objectMapper.createObjectNode();
            n.set("imageId", buildImageId(f.imageId()));
            n.put("failureCode", f.failureCode());
            n.put("failureReason", f.failureReason());
            failures.add(n);
        }
        response.set("failures", failures);
        return Response.ok(response).build();
    }

    private Response handleDescribeImages(JsonNode request) {
        List<EcrPublicService.ImageDetail> details = service.describeImages(
                text(request, "repositoryName"), text(request, "registryId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (EcrPublicService.ImageDetail d : details) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("registryId", d.registryId());
            n.put("repositoryName", d.repositoryName());
            n.put("imageDigest", d.imageDigest());
            ArrayNode tags = objectMapper.createArrayNode();
            for (String tag : d.imageTags()) {
                tags.add(tag);
            }
            n.set("imageTags", tags);
            n.put("imageSizeInBytes", d.imageSizeInBytes());
            if (d.imagePushedAt() != null) {
                n.put("imagePushedAt", d.imagePushedAt().getEpochSecond());
            }
            if (d.imageManifestMediaType() != null) {
                n.put("imageManifestMediaType", d.imageManifestMediaType());
            }
            arr.add(n);
        }
        response.set("imageDetails", arr);
        return Response.ok(response).build();
    }

    private Response handleDescribeImageTags(JsonNode request) {
        List<EcrPublicService.ImageTagDetail> tags = service.describeImageTags(
                text(request, "repositoryName"), text(request, "registryId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (EcrPublicService.ImageTagDetail t : tags) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("imageTag", t.imageTag());
            if (t.createdAt() != null) {
                n.put("createdAt", t.createdAt().getEpochSecond());
            }
            ObjectNode detail = objectMapper.createObjectNode();
            detail.put("imageDigest", t.imageDigest());
            detail.put("imageSizeInBytes", t.imageSizeInBytes());
            if (t.imageManifestMediaType() != null) {
                detail.put("imageManifestMediaType", t.imageManifestMediaType());
            }
            n.set("imageDetail", detail);
            arr.add(n);
        }
        response.set("imageTagDetails", arr);
        return Response.ok(response).build();
    }

    private ObjectNode buildImageId(EcrPublicService.ImageId id) {
        ObjectNode n = objectMapper.createObjectNode();
        if (id.imageTag() != null) {
            n.put("imageTag", id.imageTag());
        }
        if (id.imageDigest() != null) {
            n.put("imageDigest", id.imageDigest());
        }
        return n;
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

    private Response handleListTagsForResource(JsonNode request) {
        String resourceArn = text(request, "resourceArn");
        Map<String, String> tags = service.listTagsForResource(repoNameFromArn(resourceArn));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
            arr.add(tag);
        }
        response.set("tags", arr);
        return Response.ok(response).build();
    }

    private Response policyResponse(Repository repo) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("registryId", repo.getRegistryId());
        response.put("repositoryName", repo.getRepositoryName());
        if (repo.getRepositoryPolicyText() != null) {
            response.put("policyText", repo.getRepositoryPolicyText());
        }
        return Response.ok(response).build();
    }

    private ObjectNode buildRepository(Repository repo) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("repositoryArn", repo.getRepositoryArn());
        node.put("registryId", repo.getRegistryId());
        node.put("repositoryName", repo.getRepositoryName());
        node.put("repositoryUri", repo.getRepositoryUri());
        if (repo.getCreatedAt() != null) {
            node.put("createdAt", repo.getCreatedAt().getEpochSecond());
        }
        return node;
    }

    private ObjectNode buildCatalog(CatalogData catalog) {
        ObjectNode node = objectMapper.createObjectNode();
        if (catalog == null) {
            return node;
        }
        if (catalog.getDescription() != null) {
            node.put("description", catalog.getDescription());
        }
        if (catalog.getArchitectures() != null && !catalog.getArchitectures().isEmpty()) {
            ArrayNode arr = objectMapper.createArrayNode();
            catalog.getArchitectures().forEach(arr::add);
            node.set("architectures", arr);
        }
        if (catalog.getOperatingSystems() != null && !catalog.getOperatingSystems().isEmpty()) {
            ArrayNode arr = objectMapper.createArrayNode();
            catalog.getOperatingSystems().forEach(arr::add);
            node.set("operatingSystems", arr);
        }
        if (catalog.getAboutText() != null) {
            node.put("aboutText", catalog.getAboutText());
        }
        if (catalog.getUsageText() != null) {
            node.put("usageText", catalog.getUsageText());
        }
        if (catalog.getLogoUrl() != null) {
            node.put("logoUrl", catalog.getLogoUrl());
        }
        node.put("marketplaceCertified", catalog.isMarketplaceCertified());
        return node;
    }

    private CatalogData parseCatalog(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return null;
        }
        CatalogData catalog = new CatalogData();
        String description = text(node, "description");
        if (description != null) {
            catalog.setDescription(description);
        }
        catalog.setArchitectures(parseStringList(node.path("architectures")));
        catalog.setOperatingSystems(parseStringList(node.path("operatingSystems")));
        String about = text(node, "aboutText");
        if (about != null) {
            catalog.setAboutText(about);
        }
        String usage = text(node, "usageText");
        if (usage != null) {
            catalog.setUsageText(usage);
        }
        return catalog;
    }

    private static String repoNameFromArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("InvalidParameterException", "resourceArn must not be empty", 400);
        }
        int idx = arn.indexOf(":repository/");
        if (idx < 0) {
            throw new AwsException("InvalidParameterException", "Invalid repository ARN: " + arn, 400);
        }
        return arn.substring(idx + ":repository/".length());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private static List<String> parseStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return out;
        }
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Map<String, String> parseTags(JsonNode node) {
        Map<String, String> tags = new HashMap<>();
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return tags;
        }
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
