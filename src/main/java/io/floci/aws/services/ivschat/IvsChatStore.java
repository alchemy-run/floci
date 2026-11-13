package io.floci.aws.services.ivschat;

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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The IVS Chat rooms and logging configurations of every account, keyed {@code <region>/<kind>/<id>}, with the ARNs,
 * ids, paging, tags and errors the operations share. A record's {@code _} member holds what no response shows.
 */
@ApplicationScoped
public class IvsChatStore implements Resettable {

    static final String SERVICE = "ivschat";
    static final String ROOM = "room";
    static final String LOGGING = "logging-configuration";

    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public IvsChatStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create(SERVICE, "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.IvsChatServiceConfig config() {
        return config.services().ivschat();
    }

    String account() {
        return storage.accountId();
    }

    @Override
    public void clear() {
        storage.clear();
    }

    // ---------------------------------------------------------------- records

    static String key(String region, String kind, String id) {
        return region + "/" + kind + "/" + id;
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

    void delete(String key) {
        storage.deleteForAccount(account(), key);
    }

    List<ObjectNode> list(String account, String prefix) {
        List<ObjectNode> found = new ArrayList<>(storage.scanForAccount(account, key -> key.startsWith(prefix)));
        found.sort((a, b) -> Long.compare(a.path("_").path("seq").asLong(), b.path("_").path("seq").asLong()));
        return found;
    }

    List<ObjectNode> list(String prefix) {
        return list(account(), prefix);
    }

    /** A new record with its ARN, id and hidden members. */
    ObjectNode record(String region, String kind, String id) {
        ObjectNode record = mapper.createObjectNode();
        record.put("arn", arn(region, kind + "/" + id)).put("id", id);
        record.putObject("_").put("kind", kind).put("seq", System.nanoTime());
        return record;
    }

    String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account(), resource).toString();
    }

    /** The kind and id an IVS Chat ARN of this account and Region names, or null when it is not one of ours. */
    static String[] parse(String arn, String region, String account) {
        if (arn == null || !AwsArnUtils.isArnFor(arn, SERVICE)) {
            return null;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        int slash = parsed.resource().indexOf('/');
        if (slash < 0 || !region.equals(parsed.region()) || !account.equals(parsed.accountId())) {
            return null;
        }
        return new String[] {parsed.resource().substring(0, slash), parsed.resource().substring(slash + 1)};
    }

    /**
     * The record an identifier of a kind names, or ResourceNotFoundException. Rooms and logging configurations take
     * an ARN, or the id alone.
     */
    ObjectNode require(String identifier, String kind, String region) {
        if (identifier == null || identifier.isEmpty()) {
            throw validation("identifier is required.");
        }
        String id = identifier;
        if (AwsArnUtils.isArn(identifier)) {
            String[] parts = parse(identifier, region, account());
            if (parts == null || !kind.equals(parts[0])) {
                throw notFound(identifier.substring(identifier.lastIndexOf('/') + 1), kind);
            }
            id = parts[1];
        }
        String found = id;
        return get(key(region, kind, id)).orElseThrow(() -> notFound(found, kind));
    }

    static ObjectNode meta(ObjectNode record) {
        return (ObjectNode) record.get("_");
    }

    ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_");
        return view;
    }

    static String id(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
        }
        return out.toString();
    }

    static String now() {
        return Instant.now().toString();
    }

    // ---------------------------------------------------------------- tags

    /** Tags as IVS Chat keeps them: up to 50, keys 1 to 128 characters, none under {@code aws:}. */
    ObjectNode tags(JsonNode given, ObjectNode existing) {
        ObjectNode tags = existing == null ? mapper.createObjectNode() : existing.deepCopy();
        if (given != null && given.isObject()) {
            given.fields().forEachRemaining(entry -> {
                String tagKey = entry.getKey();
                if (tagKey.isEmpty() || tagKey.length() > 128 || tagKey.startsWith("aws:")) {
                    throw validation("Tag keys are 1 to 128 characters and do not begin with aws:.");
                }
                if (entry.getValue().asText().length() > 256) {
                    throw validation("Tag values are at most 256 characters.");
                }
                tags.put(tagKey, entry.getValue().asText());
            });
        }
        if (tags.size() > 50) {
            throw validation("A resource has at most 50 tags.");
        }
        return tags;
    }

    // ---------------------------------------------------------------- paging

    ObjectNode page(List<ObjectNode> items, JsonNode input, String field, int max) {
        int limit = input.hasNonNull("maxResults") ? input.get("maxResults").asInt() : max;
        int start = 0;
        String token = input.path("nextToken").asText(null);
        if (token != null && !token.isEmpty()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("nextToken is not valid.");
            }
        }
        ObjectNode response = mapper.createObjectNode();
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

    private static AwsException error(String code, String message, int status, Map<String, Object> members) {
        return new AwsException(code, message, status, members);
    }

    static AwsException validation(String message) {
        return error("ValidationException", message, 400, Map.of("reason", "FIELD_VALIDATION_FAILED"));
    }

    static AwsException notFound(String resourceId, String kind) {
        return error("ResourceNotFoundException", "Resource " + resourceId + " not found.", 404,
                resource(resourceId, kind));
    }

    static AwsException conflict(String message, String resourceId, String kind) {
        return error("ConflictException", message, 409, resource(resourceId, kind));
    }

    static AwsException quota(String message, String resourceId, String kind, int limit) {
        Map<String, Object> members = new LinkedHashMap<>(resource(resourceId, kind));
        members.put("limit", limit);
        return error("ServiceQuotaExceededException", message, 402, members);
    }

    static AwsException throttled(String message, String resourceId, int limit) {
        Map<String, Object> members = new LinkedHashMap<>(resource(resourceId, ROOM));
        members.put("limit", limit);
        return error("ThrottlingException", message, 429, members);
    }

    private static Map<String, Object> resource(String resourceId, String kind) {
        // The model's ResourceType has the one value, which the service reports for a logging configuration too.
        return Map.of("resourceId", resourceId == null ? "" : resourceId, "resourceType", "ROOM");
    }
}
