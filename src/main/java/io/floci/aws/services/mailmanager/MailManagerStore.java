package io.floci.aws.services.mailmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.docker.ContainerReachableUrls;
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
import java.util.UUID;

/**
 * Mail Manager records in every account, keyed {@code <region>/<kind>/<id>}: rule sets, traffic policies, relays,
 * ingress points, address lists and their members and import jobs, archives and their messages, searches and
 * exports, add-on subscriptions and instances, and files behind presigned URLs. A record's {@code _} member holds
 * what no response shows: its tags, order and owning account.
 */
@ApplicationScoped
public class MailManagerStore implements Resettable {

    static final String SERVICE = "ses";
    static final String RULE_SET = "ruleset";
    static final String TRAFFIC_POLICY = "trafficpolicy";
    static final String RELAY = "relay";
    static final String INGRESS_POINT = "ingresspoint";
    static final String ADDRESS_LIST = "addresslist";
    static final String MEMBER = "member";
    static final String IMPORT_JOB = "importjob";
    static final String ARCHIVE = "archive";
    static final String MESSAGE = "message";
    static final String SEARCH = "search";
    static final String EXPORT = "export";
    static final String SUBSCRIPTION = "addonsubscription";
    static final String INSTANCE = "addoninstance";
    static final String FILE = "file";
    static final String CLIENT_TOKEN = "clienttoken";
    static final String FILES_PATH = "/_floci/mailmanager/files/";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public MailManagerStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("mailmanager", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.MailManagerServiceConfig config() {
        return config.services().mailManager();
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

    List<ObjectNode> list(String region, String kind, String prefix) {
        return listForAccount(account(), region, kind, prefix);
    }

    List<ObjectNode> listForAccount(String account, String region, String kind, String prefix) {
        String start = key(region, kind, prefix);
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

    /** A Mail Manager identifier: a type prefix and 26 lower-case letters and digits. */
    static String id(String prefix) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder id = new StringBuilder(prefix);
        for (int i = 0; i < 26; i++) {
            id.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return id.toString();
    }

    static String hex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    static double epoch() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account(), "mailmanager-" + resource).toString();
    }

    String arnForAccount(String account, String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "mailmanager-" + resource).toString();
    }

    /** The resource id an id-or-ARN names: the ARN's last path segment, or the value itself. */
    static String idOf(String idOrArn) {
        if (idOrArn != null && AwsArnUtils.isArn(idOrArn)) {
            return idOrArn.substring(idOrArn.lastIndexOf('/') + 1);
        }
        return idOrArn;
    }

    // ---------------------------------------------------------------- errors

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 400);
    }

    static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 400);
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
                throw validation("Tag keys can't start with aws:.");
            }
            map.put(key, tag.path("Value").asText(""));
        }
        if (map.size() > 200) {
            throw validation("A resource can have at most 200 tags.");
        }
    }

    ArrayNode tagList(ObjectNode record) {
        ArrayNode list = mapper.createArrayNode();
        tags(record).fields().forEachRemaining(e -> list.addObject().put("Key", e.getKey())
                .put("Value", e.getValue().asText()));
        return list;
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

    /** A page of items by {@code NextToken} and {@code PageSize}. */
    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name, int defaultSize) {
        String token = in.path("NextToken").asText(null);
        int size = in.hasNonNull("PageSize") && in.get("PageSize").asInt() > 0 ? in.get("PageSize").asInt()
                : defaultSize;
        int start = 0;
        if (token != null && !token.isBlank()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The NextToken is not valid.");
            }
            if (start < 0 || start > items.size()) {
                throw validation("The NextToken is not valid.");
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

    // ---------------------------------------------------------------- files

    /** The base URL Floci is reached at, named so containers reach it too. */
    String endpoint() {
        String base = config.effectiveBaseUrl().replaceAll("/+$", "");
        return base.replaceFirst("(?i)^((?:https?)://)(localhost|127\\.0\\.0\\.1|\\[::1\\])(?=[:/]|$)",
                "$1" + ContainerReachableUrls.FLOCI_DNS_HOST);
    }

    /** Keeps a file for download and returns its URL. */
    String putFile(byte[] content, String contentType) {
        String token = UUID.randomUUID().toString();
        ObjectNode file = record();
        file.put("content", Base64.getEncoder().encodeToString(content));
        file.put("contentType", contentType);
        put("files", FILE, token, file);
        return endpoint() + FILES_PATH + token;
    }

    /** Reserves a URL a file can be uploaded to; {@code token} names it. */
    String reserveUpload(String token) {
        ObjectNode file = record();
        file.put("upload", true);
        put("files", FILE, token, file);
        return endpoint() + FILES_PATH + token;
    }

    Optional<AccountAwareStorageBackend.AccountEntry<ObjectNode>> file(String token) {
        String suffix = "files/" + FILE + "/" + token;
        return storage.scanAllAccountEntries(k -> k.endsWith(suffix)).stream().findFirst();
    }

    Optional<byte[]> uploaded(String token) {
        return get("files", FILE, token).filter(f -> f.hasNonNull("content"))
                .map(f -> Base64.getDecoder().decode(f.get("content").asText()));
    }
}
