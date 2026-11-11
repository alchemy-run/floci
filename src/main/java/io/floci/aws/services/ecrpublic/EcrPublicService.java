package io.floci.aws.services.ecrpublic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ecr.EcrJsonHandler;
import io.floci.aws.services.ecr.EcrService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Amazon ECR Public: an account's public registry, its alias and catalog data, and public repositories with their
 * gallery catalog data, policies and tags. ECR Public is one global registry an account owns; floci keeps it for
 * whichever Region a request is signed for, as AWS serves it from us-east-1 alone.
 *
 * <p>A public repository's images live in floci's ECR registry, in a repository of the same name under the storage
 * Region {@value #STORAGE_REGION} that no private-registry Region lists, so layer uploads, manifests, tags and
 * deletion behave as private ECR's do.
 */
@ApplicationScoped
public class EcrPublicService {

    /** The ECR storage Region public repositories' images are kept under. */
    public static final String STORAGE_REGION = EcrService.PUBLIC_STORAGE_REGION;
    static final String HOST = "public.ecr.aws"; // partition-literal: the one ECR Public host, in no partition
    private static final String PARTITION_LITERAL = "aws"; // partition-literal: ECR Public exists in the aws partition only
    private static final Set<String> ARCHITECTURES = Set.of("ARM", "ARM 64", "x86", "x86-64");
    private static final Set<String> OPERATING_SYSTEMS = Set.of("Linux", "Windows");
    private static final int MAX_LOGO_BYTES = 512 * 1024;

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EcrService ecr;
    private final EcrJsonHandler ecrHandler;
    private final EmulatorConfig config;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public EcrPublicService(StorageFactory storageFactory, ObjectMapper mapper, EcrService ecr,
                            EcrJsonHandler ecrHandler, EmulatorConfig config) {
        this.storage = storageFactory.create("ecr-public", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.ecr = ecr;
        this.ecrHandler = ecrHandler;
        this.config = config;
    }

    // ---------------------------------------------------------------- registry

    /** The account's public registry, created with a random default alias the first time it is asked for. */
    synchronized ObjectNode registry() {
        String account = account();
        Optional<ObjectNode> stored = storage.getForAccount(account, "registry");
        if (stored.isPresent()) {
            return stored.get();
        }
        StringBuilder alias = new StringBuilder().append((char) ('a' + random.nextInt(26)));
        for (int index = 0; index < 7; index++) {
            alias.append("abcdefghijklmnopqrstuvwxyz0123456789".charAt(random.nextInt(36)));
        }
        ObjectNode registry = mapper.createObjectNode();
        registry.put("registryId", account)
                .put("registryArn", arn(account, "registry/" + account))
                .put("registryUri", HOST + "/" + alias)
                .put("verified", false);
        registry.putArray("aliases").addObject().put("name", alias.toString()).put("status", "ACTIVE")
                .put("primaryRegistryAlias", true).put("defaultRegistryAlias", true);
        registry.putObject("_").putObject("catalogData");
        storage.putForAccount(account, "registry", registry);
        return registry;
    }

    public ObjectNode describeRegistries(JsonNode input) {
        List<ObjectNode> registries = List.of(view(registry()));
        return page(registries, input, "registries", 1000);
    }

    public ObjectNode getRegistryCatalogData(JsonNode input) {
        ObjectNode response = mapper.createObjectNode();
        response.set("registryCatalogData", registry().path("_").path("catalogData").deepCopy());
        return response;
    }

    public synchronized ObjectNode putRegistryCatalogData(JsonNode input) {
        ObjectNode registry = registry();
        ObjectNode catalog = ((ObjectNode) registry.get("_")).withObject("/catalogData");
        if (input.has("displayName")) {
            String name = input.path("displayName").asText();
            if (name.isEmpty()) {
                catalog.remove("displayName");
            } else {
                catalog.put("displayName", name);
            }
        }
        storage.putForAccount(account(), "registry", registry);
        return getRegistryCatalogData(input);
    }

    /** A registry credential for {@code docker login public.ecr.aws}, valid for twelve hours. */
    public ObjectNode getAuthorizationToken(JsonNode input) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getEncoder().encodeToString(("AWS:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(secret)).getBytes(StandardCharsets.UTF_8));
        ObjectNode response = mapper.createObjectNode();
        response.putObject("authorizationData").put("authorizationToken", token)
                .put("expiresAt", Instant.now().plusSeconds(12 * 3600).getEpochSecond());
        return response;
    }

    // ---------------------------------------------------------------- repositories

    public synchronized ObjectNode createRepository(JsonNode input) {
        String name = input.path("repositoryName").asText();
        String account = account();
        if (storage.getForAccount(account, key(name)).isPresent()) {
            throw new AwsException("RepositoryAlreadyExistsException", "The repository with name '" + name
                    + "' already exists in the registry with id '" + account + "'", 400);
        }
        Map<String, String> tags = tags(input.get("tags"), Map.of());
        ObjectNode catalog = catalogData(input.get("catalogData"), mapper.createObjectNode(), name);
        ObjectNode repository = mapper.createObjectNode();
        repository.put("repositoryArn", arn(account, "repository/" + name)).put("registryId", account)
                .put("repositoryName", name)
                .put("repositoryUri", registry().path("registryUri").asText() + "/" + name)
                .put("createdAt", Instant.now().toEpochMilli() / 1000.0);
        ObjectNode meta = repository.putObject("_");
        meta.set("catalogData", catalog);
        meta.set("tags", mapper.valueToTree(tags));
        try {
            ecr.createRepository(name, account, null, null, null, null, null, STORAGE_REGION);
        } catch (AwsException e) {
            if (!"RepositoryAlreadyExistsException".equals(e.getErrorCode())) {
                throw e;
            }
        }
        storage.putForAccount(account, key(name), repository);
        ObjectNode response = mapper.createObjectNode();
        response.set("repository", view(repository));
        response.set("catalogData", catalogView(catalog));
        return response;
    }

    public synchronized ObjectNode describeRepositories(JsonNode input) {
        registryId(input);
        List<ObjectNode> repositories = new ArrayList<>();
        if (input.has("repositoryNames") && !input.get("repositoryNames").isEmpty()) {
            if (input.hasNonNull("nextToken") || input.hasNonNull("maxResults")) {
                throw invalid("Pagination cannot be used with repositoryNames.");
            }
            for (JsonNode name : input.get("repositoryNames")) {
                repositories.add(view(repository(name.asText())));
            }
            ObjectNode response = mapper.createObjectNode();
            response.putArray("repositories").addAll(repositories);
            return response;
        }
        List<ObjectNode> stored = new ArrayList<>(storage.scanForAccount(account(),
                key -> key.startsWith("repository/")));
        stored.sort(Comparator.comparing(repository -> repository.path("repositoryName").asText()));
        stored.forEach(repository -> repositories.add(view(repository)));
        return page(repositories, input, "repositories", 1000);
    }

    /** Deletes a repository, and with {@code force} the images in it; without, a repository with images stays. */
    public synchronized ObjectNode deleteRepository(JsonNode input) {
        registryId(input);
        ObjectNode repository = repository(input.path("repositoryName").asText());
        String name = repository.path("repositoryName").asText();
        boolean force = input.path("force").asBoolean(false);
        try {
            ecr.deleteRepository(name, account(), force, STORAGE_REGION);
        } catch (AwsException e) {
            if (!"RepositoryNotFoundException".equals(e.getErrorCode())) {
                throw "RepositoryNotEmptyException".equals(e.getErrorCode())
                        ? new AwsException("RepositoryNotEmptyException", "The repository with name '" + name
                                + "' in registry with id '" + account() + "' cannot be deleted because it still "
                                + "contains images", 400)
                        : e;
            }
        }
        storage.deleteForAccount(account(), key(name));
        ObjectNode response = mapper.createObjectNode();
        response.set("repository", view(repository));
        return response;
    }

    public synchronized ObjectNode getRepositoryCatalogData(JsonNode input) {
        registryId(input);
        ObjectNode repository = repository(input.path("repositoryName").asText());
        ObjectNode response = mapper.createObjectNode();
        response.set("catalogData", catalogView((ObjectNode) repository.path("_").path("catalogData")));
        return response;
    }

    public synchronized ObjectNode putRepositoryCatalogData(JsonNode input) {
        registryId(input);
        ObjectNode repository = repository(input.path("repositoryName").asText());
        ObjectNode meta = (ObjectNode) repository.get("_");
        ObjectNode catalog = catalogData(input.get("catalogData"), (ObjectNode) meta.get("catalogData"),
                repository.path("repositoryName").asText());
        meta.set("catalogData", catalog);
        save(repository);
        ObjectNode response = mapper.createObjectNode();
        response.set("catalogData", catalogView(catalog));
        return response;
    }

    // ---------------------------------------------------------------- policies

    public synchronized ObjectNode setRepositoryPolicy(JsonNode input) {
        registryId(input);
        ObjectNode repository = repository(input.path("repositoryName").asText());
        String policy = input.path("policyText").asText();
        JsonNode document;
        try {
            document = mapper.readTree(policy);
        } catch (JsonProcessingException e) {
            throw invalid("Invalid parameter at 'PolicyText' failed to satisfy constraint: 'Invalid repository "
                    + "policy provided'");
        }
        if (document == null || !document.isObject() || !document.has("Statement")) {
            throw invalid("Invalid parameter at 'PolicyText' failed to satisfy constraint: 'Invalid repository "
                    + "policy provided'");
        }
        ((ObjectNode) repository.get("_")).put("policyText", policy);
        save(repository);
        return policyResponse(repository, policy);
    }

    public synchronized ObjectNode getRepositoryPolicy(JsonNode input) {
        registryId(input);
        ObjectNode repository = repository(input.path("repositoryName").asText());
        return policyResponse(repository, policyOf(repository));
    }

    public synchronized ObjectNode deleteRepositoryPolicy(JsonNode input) {
        registryId(input);
        ObjectNode repository = repository(input.path("repositoryName").asText());
        String policy = policyOf(repository);
        ((ObjectNode) repository.get("_")).remove("policyText");
        save(repository);
        return policyResponse(repository, policy);
    }

    private String policyOf(ObjectNode repository) {
        JsonNode policy = repository.path("_").get("policyText");
        if (policy == null) {
            throw new AwsException("RepositoryPolicyNotFoundException", "Repository policy does not exist for the "
                    + "repository with name '" + repository.path("repositoryName").asText()
                    + "' in the registry with id '" + account() + "'", 400);
        }
        return policy.asText();
    }

    private ObjectNode policyResponse(ObjectNode repository, String policy) {
        ObjectNode response = mapper.createObjectNode();
        response.put("registryId", account()).put("repositoryName", repository.path("repositoryName").asText())
                .put("policyText", policy);
        return response;
    }

    // ---------------------------------------------------------------- tags

    public synchronized ObjectNode listTagsForResource(JsonNode input) {
        ObjectNode repository = repositoryByArn(input.path("resourceArn").asText());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode tags = response.putArray("tags");
        repository.path("_").path("tags").fields().forEachRemaining(entry -> tags.addObject()
                .put("Key", entry.getKey()).put("Value", entry.getValue().asText()));
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode input) {
        ObjectNode repository = repositoryByArn(input.path("resourceArn").asText());
        ObjectNode meta = (ObjectNode) repository.get("_");
        meta.set("tags", mapper.valueToTree(tags(input.get("tags"), tagMap(meta.path("tags")))));
        save(repository);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode input) {
        ObjectNode repository = repositoryByArn(input.path("resourceArn").asText());
        ObjectNode tags = ((ObjectNode) repository.get("_")).withObject("/tags");
        for (JsonNode key : input.path("tagKeys")) {
            if (key.asText().toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw new AwsException("InvalidTagParameterException", "Tag keys beginning with aws: are reserved.",
                        400);
            }
            tags.remove(key.asText());
        }
        save(repository);
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- images

    /**
     * Runs an image or layer operation on the repository's images in floci's ECR registry, answering with only the
     * members ECR Public's shape for the operation has.
     */
    public ObjectNode images(String operation, JsonNode input) {
        String name = input.path("repositoryName").asText();
        synchronized (this) {
            registryId(input);
            repository(name);
        }
        ObjectNode request = ((ObjectNode) input).deepCopy();
        request.put("registryId", account());
        Response response = ecrHandler.handle(operation, request, STORAGE_REGION);
        JsonNode entity = response.getEntity() instanceof JsonNode node ? node
                : mapper.valueToTree(response.getEntity());
        ObjectNode output = entity.isObject() ? ((ObjectNode) entity).deepCopy() : mapper.createObjectNode();
        return switch (operation) {
            case "DescribeImages" -> describeImages(output);
            case "PutImage" -> {
                ObjectNode image = (ObjectNode) output.path("image");
                image.retain("registryId", "repositoryName", "imageId", "imageManifest", "imageManifestMediaType");
                yield output;
            }
            default -> output;
        };
    }

    private ObjectNode describeImages(ObjectNode output) {
        for (JsonNode detail : output.path("imageDetails")) {
            ((ObjectNode) detail).retain("registryId", "repositoryName", "imageDigest", "imageTags",
                    "imageSizeInBytes", "imagePushedAt", "imageManifestMediaType", "artifactMediaType");
        }
        return output;
    }

    /** The repository's tags, each with the image it names. */
    public ObjectNode describeImageTags(JsonNode input) {
        ObjectNode request = ((ObjectNode) input).deepCopy();
        request.remove(List.of("nextToken", "maxResults"));
        List<ObjectNode> details = new ArrayList<>();
        String nextToken = null;
        do {
            ObjectNode page = request.deepCopy();
            if (nextToken != null) {
                page.put("nextToken", nextToken);
            }
            ObjectNode images = images("DescribeImages", page);
            for (JsonNode image : images.path("imageDetails")) {
                for (JsonNode tag : image.path("imageTags")) {
                    ObjectNode detail = mapper.createObjectNode();
                    detail.put("imageTag", tag.asText());
                    detail.set("createdAt", image.path("imagePushedAt"));
                    ObjectNode imageDetail = ((ObjectNode) image).deepCopy();
                    imageDetail.retain("imageDigest", "imageSizeInBytes", "imagePushedAt", "imageManifestMediaType",
                            "artifactMediaType");
                    detail.set("imageDetail", imageDetail);
                    details.add(detail);
                }
            }
            nextToken = images.path("nextToken").asText(null);
        } while (nextToken != null);
        details.sort(Comparator.comparing(detail -> detail.path("imageTag").asText()));
        return page(details, input, "imageTagDetails", 1000);
    }

    // ---------------------------------------------------------------- logos

    /** A repository's logo, as its catalog data's {@code logoUrl} serves it. */
    public synchronized Optional<byte[]> logo(String account, String repositoryName) {
        return storage.getForAccount(account, key(repositoryName))
                .map(repository -> repository.path("_").path("catalogData").path("_logo").asText(null))
                .map(encoded -> Base64.getDecoder().decode(encoded));
    }

    // ---------------------------------------------------------------- catalog data

    /** Catalog data with the members the request sets applied over the current ones. */
    private ObjectNode catalogData(JsonNode input, ObjectNode current, String repositoryName) {
        ObjectNode catalog = current.deepCopy();
        if (input == null || input.isNull()) {
            return catalog;
        }
        for (String field : List.of("description", "aboutText", "usageText")) {
            if (input.has(field)) {
                catalog.put(field, input.get(field).asText());
            }
        }
        if (input.has("architectures")) {
            catalog.set("architectures", values(input.get("architectures"), ARCHITECTURES, "architectures"));
        }
        if (input.has("operatingSystems")) {
            catalog.set("operatingSystems", values(input.get("operatingSystems"), OPERATING_SYSTEMS,
                    "operatingSystems"));
        }
        if (input.hasNonNull("logoImageBlob")) {
            byte[] logo;
            try {
                logo = Base64.getDecoder().decode(input.get("logoImageBlob").asText());
            } catch (IllegalArgumentException e) {
                throw invalid("logoImageBlob must be base64-encoded.");
            }
            if (logo.length > MAX_LOGO_BYTES) {
                throw invalid("logoImageBlob must be 512 KB or smaller.");
            }
            String type = imageType(logo);
            catalog.put("_logo", Base64.getEncoder().encodeToString(logo));
            catalog.put("logoUrl", config.effectiveBaseUrl() + EcrPublicLogoController.PATH + "/" + account() + "/"
                    + repositoryName + "?v=" + UUID.randomUUID().toString().substring(0, 8) + "&type=" + type);
        }
        return catalog;
    }

    private static String imageType(byte[] logo) {
        if (logo.length > 8 && (logo[0] & 0xff) == 0x89 && logo[1] == 'P' && logo[2] == 'N' && logo[3] == 'G') {
            return "png";
        }
        if (logo.length > 3 && (logo[0] & 0xff) == 0xff && (logo[1] & 0xff) == 0xd8) {
            return "jpeg";
        }
        throw invalid("logoImageBlob must be a PNG or JPEG image.");
    }

    private ArrayNode values(JsonNode list, Set<String> allowed, String field) {
        ArrayNode values = mapper.createArrayNode();
        for (JsonNode value : list) {
            if (!allowed.contains(value.asText())) {
                throw invalid("Invalid parameter at '" + field + "' failed to satisfy constraint: 'Member must be "
                        + "one of " + String.join(", ", allowed.stream().sorted().toList()) + "'");
            }
            values.add(value.asText());
        }
        return values;
    }

    /** Catalog data as responses show it: always reporting whether it is Marketplace certified. */
    private ObjectNode catalogView(ObjectNode catalog) {
        ObjectNode view = catalog.deepCopy();
        view.remove("_logo");
        view.put("marketplaceCertified", false);
        return view;
    }

    // ---------------------------------------------------------------- lookups

    private ObjectNode repository(String name) {
        return storage.getForAccount(account(), key(name)).orElseThrow(() -> new AwsException(
                "RepositoryNotFoundException", "The repository with name '" + name
                        + "' does not exist in the registry with id '" + account() + "'", 400));
    }

    private ObjectNode repositoryByArn(String arn) {
        String prefix = arn(account(), "repository/");
        if (!arn.startsWith(prefix)) {
            if (!AwsArnUtils.isArn(arn) || !arn.contains(":ecr-public:")) {
                throw invalid("Invalid parameter at 'resourceArn' failed to satisfy constraint: 'Invalid ARN'");
            }
            throw new AwsException("RepositoryNotFoundException", "The repository with ARN '" + arn
                    + "' does not exist in the registry with id '" + account() + "'", 400);
        }
        return repository(arn.substring(prefix.length()));
    }

    private void registryId(JsonNode input) {
        String registryId = input.path("registryId").asText(null);
        if (registryId == null || registryId.equals(account())) {
            return;
        }
        for (JsonNode alias : registry().path("aliases")) {
            if (registryId.equals(alias.path("name").asText())) {
                return;
            }
        }
        throw new AwsException("RegistryNotFoundException", "The registry with id '" + registryId
                + "' does not exist", 400);
    }

    private void save(ObjectNode repository) {
        storage.putForAccount(account(), key(repository.path("repositoryName").asText()), repository);
    }

    // ---------------------------------------------------------------- input and output

    private Map<String, String> tags(JsonNode input, Map<String, String> existing) {
        Map<String, String> tags = new LinkedHashMap<>(existing);
        if (input != null) {
            for (JsonNode tag : input) {
                String key = tag.path("Key").asText("");
                if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                    throw new AwsException("InvalidTagParameterException",
                            "Tag keys beginning with aws: are reserved.", 400);
                }
                tags.put(key, tag.path("Value").asText(""));
            }
        }
        if (tags.size() > 50) {
            throw new AwsException("TooManyTagsException", "A repository can have at most 50 tags.", 400);
        }
        return tags;
    }

    private static Map<String, String> tagMap(JsonNode tags) {
        Map<String, String> map = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        return map;
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode input, String field, int max) {
        Integer maxResults = input.hasNonNull("maxResults") ? input.get("maxResults").asInt() : null;
        Map<ObjectNode, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            positions.put(items.get(index), index);
        }
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items,
                item -> String.format("%09d", positions.get(item)), maxResults == null ? max : maxResults,
                input.path("nextToken").asText(null), field,
                bad -> invalid("Invalid parameter at 'nextToken' failed to satisfy constraint: 'Invalid token'"));
        ObjectNode response = mapper.createObjectNode();
        response.putArray(field).addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("nextToken", paged.nextToken());
        }
        return response;
    }

    private static ObjectNode view(ObjectNode record) {
        ObjectNode copy = record.deepCopy();
        copy.remove("_");
        return copy;
    }

    private static String arn(String account, String resource) {
        return AwsArnUtils.Arn.global(PARTITION_LITERAL, "ecr-public", account, resource).toString();
    }

    private static String key(String name) {
        return "repository/" + name;
    }

    private String account() {
        return storage.accountId();
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
