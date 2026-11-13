package io.floci.aws.services.imagebuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Image Builder resources of every account, keyed {@code <region>/<kind>/<resource>}: the resource part of the
 * ARN, so a component build version is {@code component/<name>/<version>/<build>}. A record's {@code _} member holds
 * what no response shows.
 */
@ApplicationScoped
public class ImageBuilderStore implements Resettable {

    static final String SERVICE = "imagebuilder";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public ImageBuilderStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create(SERVICE, "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.ImageBuilderServiceConfig config() {
        return config.services().imagebuilder();
    }

    String account() {
        return storage.accountId();
    }

    @Override
    public void clear() {
        storage.clear();
    }

    static String key(String region, String resource) {
        return region + "/" + resource;
    }

    Optional<ObjectNode> get(String account, String key) {
        return storage.getForAccount(account, key);
    }

    Optional<ObjectNode> get(String key) {
        return get(account(), key);
    }

    void put(String account, String key, ObjectNode record) {
        storage.putForAccount(account, key, record);
    }

    void put(String key, ObjectNode record) {
        put(account(), key, record);
    }

    void delete(String account, String key) {
        storage.deleteForAccount(account, key);
    }

    void delete(String key) {
        delete(account(), key);
    }

    /** The records under a key prefix, oldest first. */
    List<ObjectNode> list(String account, String prefix) {
        List<ObjectNode> found = new ArrayList<>(storage.scanForAccount(account, key -> key.startsWith(prefix)));
        found.sort((a, b) -> Long.compare(a.path("_").path("seq").asLong(), b.path("_").path("seq").asLong()));
        return found;
    }

    List<ObjectNode> list(String prefix) {
        return list(account(), prefix);
    }

    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> everywhere(Predicate<String> key) {
        return storage.scanAllAccountEntries(key);
    }

    /** A new record with its hidden members. */
    ObjectNode record(String kind, String resource) {
        ObjectNode record = mapper.createObjectNode();
        record.putObject("_").put("kind", kind).put("resource", resource).put("seq", System.nanoTime());
        return record;
    }

    static ObjectNode meta(ObjectNode record) {
        return (ObjectNode) record.get("_");
    }

    ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_");
        return view;
    }

    String arn(String region, String resource) {
        return arn(account(), region, resource);
    }

    static String arn(String account, String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, resource).toString();
    }

    /** The lower-case, hyphenated form of a name that ARNs carry. */
    static String arnName(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9-_.]", "-");
    }

    /** The resource part of an Image Builder ARN, its account and Region, or null when it is not one. */
    static AwsArnUtils.Arn parse(String arn) {
        if (arn == null || !AwsArnUtils.isArnFor(arn, SERVICE)) {
            return null;
        }
        return AwsArnUtils.parse(arn);
    }

    static String now() {
        return Instant.now().toString();
    }

    static String requestId() {
        return UUID.randomUUID().toString();
    }

    // ---------------------------------------------------------------- tags

    ObjectNode tags(JsonNode given, ObjectNode existing) {
        ObjectNode tags = existing == null ? mapper.createObjectNode() : existing.deepCopy();
        if (given != null && given.isObject()) {
            given.fields().forEachRemaining(entry -> {
                if (entry.getKey().isEmpty() || entry.getKey().length() > 128) {
                    throw invalidParameter("Tag keys are 1 to 128 characters.");
                }
                tags.put(entry.getKey(), entry.getValue().asText());
            });
        }
        if (tags.size() > 50) {
            throw invalidParameter("A resource has at most 50 tags.");
        }
        return tags;
    }

    // ---------------------------------------------------------------- paging

    ObjectNode page(List<? extends JsonNode> items, JsonNode input, String field) {
        int limit = input.hasNonNull("maxResults") ? input.get("maxResults").asInt() : 25;
        if (limit < 1 || limit > 25) {
            throw invalidParameter("maxResults is 1 to 25.");
        }
        int start = 0;
        String token = input.path("nextToken").asText(null);
        if (token != null && !token.isEmpty()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new AwsException("InvalidPaginationTokenException", "The nextToken is not valid.", 400);
            }
        }
        ObjectNode response = mapper.createObjectNode().put("requestId", requestId());
        ArrayNode list = response.putArray(field);
        int end = Math.min(items.size(), start + limit);
        for (int i = start; i < end; i++) {
            list.add(items.get(i));
        }
        if (end < items.size()) {
            response.put("nextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Integer.toString(end).getBytes(StandardCharsets.UTF_8)));
        }
        return response;
    }

    // ---------------------------------------------------------------- errors

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException invalidParameter(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    static AwsException invalidRequest(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    static AwsException invalidVersion(String message) {
        return new AwsException("InvalidVersionNumberException", message, 400);
    }

    static AwsException alreadyExists(String message) {
        return new AwsException("ResourceAlreadyExistsException", message, 400);
    }

    static AwsException inUse(String message) {
        return new AwsException("ResourceInUseException", message, 400);
    }

    static AwsException dependency(String message) {
        return new AwsException("ResourceDependencyException", message, 400);
    }

    static AwsException idempotency(String message) {
        return new AwsException("IdempotentParameterMismatchException", message, 400);
    }
}
