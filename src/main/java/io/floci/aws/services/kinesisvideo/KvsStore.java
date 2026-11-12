package io.floci.aws.services.kinesisvideo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kinesis Video Streams records in every account: streams and signaling channels keyed {@code <region>/<kind>/<name>},
 * and each stream's fragments keyed {@code <region>/<stream>/<fragment number>}, holding the fragment's Matroska
 * bytes. A record's {@code _} member holds its tags and other state no response shows.
 */
@ApplicationScoped
public class KvsStore implements Resettable {

    static final String SERVICE = "kinesisvideo";
    static final String STREAM = "stream";
    static final String CHANNEL = "channel";

    private final AccountAwareStorageBackend<ObjectNode> resources;
    private final AccountAwareStorageBackend<ObjectNode> fragments;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public KvsStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.resources = storageFactory.create(SERVICE, "resources.json", type);
        this.fragments = storageFactory.create(SERVICE, "fragments.json", type);
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig emulator() {
        return config;
    }

    EmulatorConfig.KinesisVideoServiceConfig config() {
        return config.services().kinesisVideo();
    }

    String account() {
        return resources.accountId();
    }

    @Override
    public void clear() {
        resources.clear();
        fragments.clear();
    }

    static String key(String region, String kind, String name) {
        return region + "/" + kind + "/" + name;
    }

    Optional<ObjectNode> get(String account, String region, String kind, String name) {
        return resources.getForAccount(account, key(region, kind, name));
    }

    void put(String account, String region, String kind, String name, ObjectNode record) {
        resources.putForAccount(account, key(region, kind, name), record);
    }

    void delete(String account, String region, String kind, String name) {
        resources.deleteForAccount(account, key(region, kind, name));
    }

    List<ObjectNode> list(String account, String region, String kind) {
        String prefix = key(region, kind, "");
        List<ObjectNode> found = new ArrayList<>(resources.scanForAccount(account, k -> k.startsWith(prefix)));
        found.sort(Comparator.comparing(r -> r.path(STREAM.equals(kind) ? "StreamName" : "ChannelName").asText()));
        return found;
    }

    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> everywhere(String kind) {
        String marker = "/" + kind + "/";
        return resources.scanAllAccountEntries(k -> k.contains(marker));
    }

    // ---------------------------------------------------------------- fragments

    static String fragmentKey(String region, String stream, String number) {
        return region + "/" + stream + "/" + number;
    }

    void putFragment(String account, String region, String stream, ObjectNode fragment) {
        fragments.putForAccount(account, fragmentKey(region, stream, fragment.path("FragmentNumber").asText()),
                fragment);
    }

    Optional<ObjectNode> fragment(String account, String region, String stream, String number) {
        return fragments.getForAccount(account, fragmentKey(region, stream, number));
    }

    /** A stream's fragments in arrival order. */
    List<ObjectNode> fragments(String account, String region, String stream) {
        String prefix = region + "/" + stream + "/";
        List<ObjectNode> found = new ArrayList<>(fragments.scanForAccount(account, k -> k.startsWith(prefix)));
        found.sort(Comparator.comparing(f -> f.path("FragmentNumber").asText(),
                Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())));
        return found;
    }

    void deleteFragment(String account, String region, String stream, String number) {
        fragments.deleteForAccount(account, fragmentKey(region, stream, number));
    }

    void deleteFragments(String account, String region, String stream) {
        for (ObjectNode fragment : fragments(account, region, stream)) {
            deleteFragment(account, region, stream, fragment.path("FragmentNumber").asText());
        }
    }

    // ---------------------------------------------------------------- helpers

    ObjectNode record() {
        ObjectNode record = mapper.createObjectNode();
        record.putObject("_");
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

    String arn(String account, String region, String kind, String name, long created) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, kind + "/" + name + "/" + created).toString();
    }

    static String text(JsonNode in, String name) {
        JsonNode value = in == null ? null : in.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidArgumentException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException inUse(String message) {
        return new AwsException("ResourceInUseException", message, 400);
    }

    static AwsException versionMismatch(String message) {
        return new AwsException("VersionMismatchException", message, 400);
    }
}
