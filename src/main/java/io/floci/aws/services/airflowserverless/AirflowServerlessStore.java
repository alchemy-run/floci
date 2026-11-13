package io.floci.aws.services.airflowserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MWAA Serverless records in every account, keyed {@code <region>/<kind>/<path>}: workflows, their versions, runs
 * and task instances. A record's {@code _} member holds what no response shows: its tags, order and owning account.
 */
@ApplicationScoped
public class AirflowServerlessStore implements Resettable {

    static final String SERVICE = "airflow-serverless";
    static final String WORKFLOW = "workflow";
    static final String VERSION = "version";
    static final String RUN = "run";
    static final String TASK = "task";
    static final String CLIENT_TOKEN = "clienttoken";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public AirflowServerlessStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("airflowserverless", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.AirflowServerlessServiceConfig config() {
        return config.services().airflowServerless();
    }

    EmulatorConfig emulator() {
        return config;
    }

    String account() {
        return storage.accountId();
    }

    @Override
    public void clear() {
        storage.clear();
    }

    static String key(String region, String kind, String path) {
        return region + "/" + kind + "/" + path;
    }

    Optional<ObjectNode> get(String region, String kind, String path) {
        return storage.getForAccount(account(), key(region, kind, path));
    }

    Optional<ObjectNode> getForAccount(String account, String region, String kind, String path) {
        return storage.getForAccount(account, key(region, kind, path));
    }

    void put(String region, String kind, String path, ObjectNode record) {
        putForAccount(account(), region, kind, path, record);
    }

    void putForAccount(String account, String region, String kind, String path, ObjectNode record) {
        meta(record).put("path", path).put("account", account).put("region", region);
        storage.putForAccount(account, key(region, kind, path), record);
    }

    void deleteForAccount(String account, String region, String kind, String path) {
        storage.deleteForAccount(account, key(region, kind, path));
    }

    /** The records of a kind whose path starts with {@code prefix}, oldest first. */
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

    static double epoch() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    // ---------------------------------------------------------------- errors

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message, String id, String type) {
        return new AwsException("ResourceNotFoundException", message, 404, Map.of("ResourceId", id,
                "ResourceType", type));
    }

    static AwsException conflict(String message, String id, String type) {
        return new AwsException("ConflictException", message, 409, Map.of("ResourceId", id,
                "ResourceType", type));
    }

    static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 402, Map.of("ServiceCode",
                "airflow-serverless"));
    }

    // ---------------------------------------------------------------- tags

    static ObjectNode tags(ObjectNode record) {
        ObjectNode meta = meta(record);
        if (!meta.has("tags")) {
            meta.putObject("tags");
        }
        return (ObjectNode) meta.get("tags");
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
            throw conflict("The client token " + token + " was used with different parameters.", token,
                    "ClientToken");
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

    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name) {
        String token = in.path("NextToken").asText(null);
        int max = in.hasNonNull("MaxResults") && in.get("MaxResults").asInt() > 0 ? in.get("MaxResults").asInt()
                : 100;
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
        int end = Math.min(items.size(), start + max);
        ArrayNode array = out.putArray(name);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
