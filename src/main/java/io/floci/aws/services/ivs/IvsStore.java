package io.floci.aws.services.ivs;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The IVS resources of every account, keyed {@code <region>/<kind>/<id>} (a channel {@code channel/<id>}, a stream
 * session {@code session/<channel id>/<stream id>}), with the ARNs, ids, paging, tags and errors IVS's operations
 * share. A record's {@code _} member holds what no response shows.
 */
@ApplicationScoped
public class IvsStore implements Resettable {

    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public IvsStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("ivs", "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig config() {
        return config;
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

    void delete(String account, String key) {
        storage.deleteForAccount(account, key);
    }

    List<ObjectNode> list(String account, String prefix) {
        List<ObjectNode> found = new ArrayList<>(storage.scanForAccount(account, key -> key.startsWith(prefix)));
        found.sort((a, b) -> Long.compare(a.path("_").path("seq").asLong(), b.path("_").path("seq").asLong()));
        return found;
    }

    List<ObjectNode> list(String prefix) {
        return list(account(), prefix);
    }

    /** Every record of every account under a key prefix, with the account it belongs to. */
    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> everywhere(Predicate<String> key) {
        return storage.scanAllAccountEntries(key);
    }

    /** A new record: its ARN under {@code arnField}, and its hidden members. */
    ObjectNode record(String region, String kind, String id, String arnField) {
        ObjectNode record = mapper.createObjectNode();
        record.put(arnField, arn(region, kind + "/" + id));
        record.putObject("_").put("kind", kind).put("id", id).put("seq", System.nanoTime());
        return record;
    }

    String arn(String region, String resource) {
        return arnFor(account(), region, resource);
    }

    static String arnFor(String account, String region, String resource) {
        return AwsArnUtils.Arn.of("ivs", region, account, resource).toString();
    }

    /** The kind and id an IVS ARN of this account and Region names, or null when it is not one of ours. */
    static String[] parse(String arn, String region, String account) {
        if (arn == null || !AwsArnUtils.isArnFor(arn, "ivs")) {
            return null;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        int slash = parsed.resource().indexOf('/');
        if (slash < 0 || !region.equals(parsed.region()) || !account.equals(parsed.accountId())) {
            return null;
        }
        return new String[] {parsed.resource().substring(0, slash), parsed.resource().substring(slash + 1)};
    }

    /** The record an ARN of a kind names, or ResourceNotFoundException. */
    ObjectNode require(String arn, String kind, String region) {
        if (arn == null || arn.isEmpty()) {
            throw validation("arn is required.");
        }
        String[] parts = parse(arn, region, account());
        if (parts == null || !kind.equals(parts[0])) {
            if (AwsArnUtils.isArnFor(arn, "ivs")) {
                throw notFound("Resource " + arn + " not found.");
            }
            throw validation("The ARN " + arn + " is not an IVS " + kind + " ARN.");
        }
        return get(key(region, kind, parts[1])).orElseThrow(() -> notFound("Resource " + arn + " not found."));
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

    /** Tags as IVS keeps them: up to 50, keys 1 to 128 characters, none under {@code aws:}. */
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
        if (limit < 1 || limit > max) {
            throw validation("maxResults is 1 to " + max + ".");
        }
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

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 402);
    }

    static AwsException notBroadcasting(String arn) {
        return new AwsException("ChannelNotBroadcasting", "Channel " + arn + " is not broadcasting.", 404);
    }

    static AwsException throttled(String message) {
        return new AwsException("ThrottlingException", message, 429);
    }
}
