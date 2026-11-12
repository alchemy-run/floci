package io.floci.aws.services.licensemanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * License Manager records in every account, keyed {@code <region>/<kind>/<id>}. Licenses, grants, tokens and
 * consumptions are global (their ARNs name no Region) and are kept under {@link #GLOBAL}; license configurations, asset
 * groups, rulesets, report generators, conversion tasks and service settings are regional. A record's {@code _}
 * member holds what no response shows: its tags, order and version history.
 */
@ApplicationScoped
public class LicenseManagerStore implements Resettable {

    static final String SERVICE = "license-manager";
    static final String GLOBAL = "global";

    static final String CONFIGURATION = "configuration";
    static final String ASSOCIATION = "association";
    static final String FAILURE = "failure";
    static final String SETTINGS = "settings";
    static final String REPORT_GENERATOR = "reportgenerator";
    static final String CONVERSION = "conversion";
    static final String ASSET_GROUP = "assetgroup";
    static final String RULESET = "ruleset";
    static final String LICENSE = "license";
    static final String GRANT = "grant";
    static final String TOKEN = "token";
    static final String CONSUMPTION = "consumption";
    static final String CLIENT_TOKEN = "clienttoken";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public LicenseManagerStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("licensemanager", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.LicenseManagerServiceConfig config() {
        return config.services().licenseManager();
    }

    String account() {
        return storage.accountId();
    }

    @Override
    public void clear() {
        storage.clear();
    }

    static String key(String region, String kind, String id) {
        return region + "/" + kind + "/" + id;
    }

    Optional<ObjectNode> get(String region, String kind, String id) {
        return storage.getForAccount(account(), key(region, kind, id));
    }

    Optional<ObjectNode> getForAccount(String account, String region, String kind, String id) {
        return storage.getForAccount(account, key(region, kind, id));
    }

    void put(String region, String kind, String id, ObjectNode record) {
        putForAccount(account(), region, kind, id, record);
    }

    void putForAccount(String account, String region, String kind, String id, ObjectNode record) {
        meta(record).put("id", id).put("account", account);
        storage.putForAccount(account, key(region, kind, id), record);
    }

    void delete(String region, String kind, String id) {
        storage.deleteForAccount(account(), key(region, kind, id));
    }

    void deleteForAccount(String account, String region, String kind, String id) {
        storage.deleteForAccount(account, key(region, kind, id));
    }

    /** The records of a kind in this account, oldest first. */
    List<ObjectNode> list(String region, String kind) {
        String prefix = region + "/" + kind + "/";
        List<ObjectNode> found = new ArrayList<>(storage.scanForAccount(account(), k -> k.startsWith(prefix)));
        found.sort(Comparator.comparingLong(record -> record.path("_").path("seq").asLong()));
        return found;
    }

    /** The records of a kind in every account, oldest first. */
    List<ObjectNode> everywhere(String region, String kind) {
        String marker = region + "/" + kind + "/";
        List<ObjectNode> found = new ArrayList<>();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : storage.scanAllAccountEntries(
                k -> k.contains(marker))) {
            found.add(entry.value());
        }
        found.sort(Comparator.comparingLong(record -> record.path("_").path("seq").asLong()));
        return found;
    }

    ObjectNode record() {
        ObjectNode record = mapper.createObjectNode();
        record.putObject("_").put("seq", System.nanoTime());
        return record;
    }

    static ObjectNode meta(ObjectNode record) {
        if (!record.has("_")) {
            record.putObject("_");
        }
        return (ObjectNode) record.get("_");
    }

    static ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_");
        return view;
    }

    static String hex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    static double epoch() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    static String now() {
        return Instant.now().toString();
    }

    String regionalArn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account(), resource).toString();
    }

    /** A license's, grant's or token's ARN: global, in the partition of the caller's Region. */
    String globalArn(String region, String resource) {
        return AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), SERVICE, account(), resource).toString();
    }

    /** The account and resource of a License Manager ARN, or empty when it isn't one. */
    static Optional<AwsArnUtils.Arn> parse(String arn) {
        if (arn == null) {
            return Optional.empty();
        }
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            return SERVICE.equals(parsed.service()) ? Optional.of(parsed) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- errors

    static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 400);
    }

    static AwsException authorization(String message) {
        return new AwsException("AuthorizationException", message, 400);
    }

    static AwsException limit(String message) {
        return new AwsException("ResourceLimitExceededException", message, 400);
    }

    // ---------------------------------------------------------------- tags

    static ObjectNode tags(ObjectNode record) {
        ObjectNode meta = meta(record);
        if (!meta.has("tags")) {
            meta.putObject("tags");
        }
        return (ObjectNode) meta.get("tags");
    }

    static void addTags(ObjectNode record, JsonNode tags) {
        ObjectNode map = tags(record);
        for (JsonNode tag : tags) {
            String key = tag.path("Key").asText("");
            if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw invalid("Tag keys can't start with aws:.");
            }
            map.put(key, tag.path("Value").asText(""));
        }
        if (map.size() > 50) {
            throw invalid("A resource can have at most 50 tags.");
        }
    }

    ArrayNode tagList(ObjectNode record) {
        ArrayNode list = mapper.createArrayNode();
        tags(record).fields().forEachRemaining(e -> list.addObject().put("Key", e.getKey())
                .put("Value", e.getValue().asText()));
        return list;
    }

    // ---------------------------------------------------------------- client tokens

    /** An earlier call's response for the same client token and operation, or null. */
    ObjectNode repeated(String operation, JsonNode in) {
        String token = in.path("ClientToken").asText(null);
        if (token == null) {
            return null;
        }
        ObjectNode earlier = get(GLOBAL, CLIENT_TOKEN, operation + "/" + token).orElse(null);
        if (earlier == null) {
            return null;
        }
        ObjectNode request = in.deepCopy();
        request.remove("ClientToken");
        if (!earlier.path("request").equals(request)) {
            throw conflict("The client token " + token + " was used with different parameters.");
        }
        return earlier.path("response").deepCopy();
    }

    void remember(String operation, JsonNode in, ObjectNode response) {
        String token = in.path("ClientToken").asText(null);
        if (token == null) {
            return;
        }
        ObjectNode request = in.deepCopy();
        request.remove("ClientToken");
        ObjectNode entry = record();
        entry.set("request", request);
        entry.set("response", response.deepCopy());
        put(GLOBAL, CLIENT_TOKEN, operation + "/" + token, entry);
    }

    // ---------------------------------------------------------------- lists

    /** A page of items: {@code NextToken} is the base64 index of the first item not yet returned. */
    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name) {
        String token = in.path("NextToken").asText(null);
        int max = in.hasNonNull("MaxResults") && in.get("MaxResults").asInt() > 0 ? in.get("MaxResults").asInt()
                : 100;
        int start = 0;
        if (token != null && !token.isBlank()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw invalid("The NextToken is not valid.");
            }
            if (start < 0 || start > items.size()) {
                throw invalid("The NextToken is not valid.");
            }
        }
        int end = Math.min(items.size(), start + max);
        ArrayNode array = out.putArray(name);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Whether an item passes {@code Filters} ({@code Name}, {@code Values}): each filter's name must be one the
     * operation supports, and the item's value for it one of the values.
     */
    static boolean filtered(JsonNode filters, Map<String, Function<JsonNode, String>> fields, JsonNode item) {
        for (JsonNode filter : filters) {
            String name = filter.path("Name").asText();
            Function<JsonNode, String> field = fields.get(name);
            if (field == null) {
                throw invalid("The filter " + name + " is not supported; use one of " + fields.keySet() + ".");
            }
            String value = field.apply(item);
            boolean any = false;
            for (JsonNode wanted : filter.path("Values")) {
                any |= wanted.asText().equalsIgnoreCase(value == null ? "" : value);
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    static boolean any(JsonNode list, Predicate<String> test) {
        for (JsonNode value : list) {
            if (test.test(value.asText())) {
                return true;
            }
        }
        return false;
    }
}
