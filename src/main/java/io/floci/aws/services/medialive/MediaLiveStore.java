package io.floci.aws.services.medialive;

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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * MediaLive records in every account, keyed {@code <region>/<kind>/<id>}: inputs, input security groups, channels
 * (with their schedules and alerts), multiplexes and their programs, reservations, workflow monitor templates and
 * signal maps, and MediaLive Anywhere clusters, nodes, networks, SDI sources and channel placement groups. A
 * record's {@code _} member holds what no response shows. Records hold member names; the dispatcher renames them to
 * the wire's.
 */
@ApplicationScoped
public class MediaLiveStore implements Resettable {

    static final String SERVICE = "medialive";
    static final String INPUT = "input";
    static final String SECURITY_GROUP = "inputsecuritygroup";
    static final String CHANNEL = "channel";
    static final String MULTIPLEX = "multiplex";
    static final String PROGRAM = "multiplexprogram";
    static final String RESERVATION = "reservation";
    static final String ACCOUNT = "account";
    static final String CLIENT_TOKEN = "clienttoken";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public MediaLiveStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("medialive", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.MediaLiveServiceConfig config() {
        return config.services().mediaLive();
    }

    /** The base URL Floci is reached at. */
    String endpoint() {
        return config.effectiveBaseUrl().replaceAll("/+$", "");
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
        return getForAccount(account(), region, kind, id);
    }

    Optional<ObjectNode> getForAccount(String account, String region, String kind, String id) {
        return storage.getForAccount(account, key(region, kind, id));
    }

    void put(String region, String kind, String id, ObjectNode record) {
        putForAccount(account(), region, kind, id, record);
    }

    void putForAccount(String account, String region, String kind, String id, ObjectNode record) {
        meta(record).put("id", id).put("account", account).put("region", region);
        storage.putForAccount(account, key(region, kind, id), record);
    }

    void delete(String region, String kind, String id) {
        deleteForAccount(account(), region, kind, id);
    }

    void deleteForAccount(String account, String region, String kind, String id) {
        storage.deleteForAccount(account, key(region, kind, id));
    }

    List<ObjectNode> list(String region, String kind) {
        return listForAccount(account(), region, kind);
    }

    List<ObjectNode> listForAccount(String account, String region, String kind) {
        String start = key(region, kind, "");
        List<ObjectNode> found = new ArrayList<>(storage.scanForAccount(account, k -> k.startsWith(start)));
        found.sort(Comparator.comparingLong(record -> record.path("_").path("seq").asLong()));
        return found;
    }

    /** The records of a kind in every account and Region. */
    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> everywhere(String kind) {
        String marker = "/" + kind + "/";
        return storage.scanAllAccountEntries(k -> k.contains(marker));
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

    static String random(int length, String alphabet) {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < length; i++) {
            id.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return id.toString();
    }

    /** A MediaLive identifier: seven digits, unique among the kind's records in the Region. */
    String numericId(String region, String kind) {
        for (int attempt = 0; attempt < 50; attempt++) {
            String id = String.valueOf(1000000 + RANDOM.nextInt(9000000));
            if (get(region, kind, id).isEmpty()) {
                return id;
            }
        }
        throw new AwsException("InternalServerErrorException", "No identifier was free.", 500);
    }

    /** A workflow monitor identifier: a prefix and 17 lower-case letters and digits. */
    static String prefixedId(String prefix) {
        return prefix + random(17, "abcdefghijklmnopqrstuvwxyz0123456789");
    }

    static double epoch() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    static String iso(Instant instant) {
        return instant.toString();
    }

    String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account(), resource).toString();
    }


    // ---------------------------------------------------------------- errors

    static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("NotFoundException", message, 404);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    static AwsException forbidden(String message) {
        return new AwsException("ForbiddenException", message, 403);
    }

    static AwsException tooMany(String message) {
        return new AwsException("TooManyRequestsException", message, 429);
    }

    static AwsException unprocessable(String message) {
        return new AwsException("UnprocessableEntityException", message, 422);
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
        if (tags == null || !tags.isObject()) {
            return;
        }
        ObjectNode map = tags(record);
        tags.properties().forEach(tag -> {
            if (tag.getKey().toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw badRequest("Tag keys can't start with aws:.");
            }
            map.put(tag.getKey(), tag.getValue().asText(""));
        });
        if (map.size() > 50) {
            throw badRequest("A resource can have at most 50 tags.");
        }
    }

    // ---------------------------------------------------------------- client tokens

    ObjectNode repeated(String region, String operation, JsonNode in) {
        String token = in.path("ClientToken").asText(null);
        if (token == null) {
            return null;
        }
        ObjectNode earlier = get(region, CLIENT_TOKEN, operation + "/" + token).orElse(null);
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

    void remember(String region, String operation, JsonNode in, ObjectNode response) {
        String token = in.path("ClientToken").asText(null);
        if (token == null) {
            return;
        }
        ObjectNode request = in.deepCopy();
        request.remove("ClientToken");
        ObjectNode entry = record();
        entry.set("request", request);
        entry.set("response", response.deepCopy());
        put(region, CLIENT_TOKEN, operation + "/" + token, entry);
    }

    // ---------------------------------------------------------------- lists

    /** A page of items by {@code NextToken} and {@code MaxResults}. */
    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name, int defaultSize) {
        String token = in.path("NextToken").asText(null);
        int size = in.hasNonNull("MaxResults") && in.get("MaxResults").asInt() > 0 ? in.get("MaxResults").asInt()
                : defaultSize;
        int start = 0;
        if (token != null && !token.isBlank()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw badRequest("The NextToken is not valid.");
            }
            if (start < 0 || start > items.size()) {
                throw badRequest("The NextToken is not valid.");
            }
        }
        int end = Math.min(items.size(), start + size);
        ArrayNode array = out.putArray(name);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
    }

    static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }
}
