package io.floci.aws.services.glacier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.core.storage.WriteProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Glacier vaults of every account, with their archives, multipart uploads, jobs and Region-wide settings, keyed
 * {@code <region>/<kind>/...}: a vault {@code vault/<name>}, an archive {@code archive/<vault>/<id>}, an upload
 * {@code upload/<vault>/<id>}, a job {@code job/<vault>/<id>}. Archive and part bytes are kept apart from the
 * records, base64-encoded, under {@code <region>/<archive or upload>/<vault>/<id>[/<offset>]}.
 */
@ApplicationScoped
public class GlacierStore implements Resettable {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final AccountAwareStorageBackend<ObjectNode> records;
    private final AccountAwareStorageBackend<String> blobs;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public GlacierStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.records = storageFactory.create("glacier", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.blobs = storageFactory.create("glacier", "data.json", new TypeReference<Map<String, String>>() {},
                WriteProfile.APPEND_HEAVY);
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    /** The account of the request's credentials. */
    String callerAccount() {
        return records.accountId();
    }

    long jobMillis() {
        return config.services().glacier().jobMillis();
    }

    long inventoryMillis() {
        return config.services().glacier().inventoryMillis();
    }

    boolean existingCustomer() {
        return config.services().glacier().existingCustomer();
    }

    // ---------------------------------------------------------------- records

    Optional<ObjectNode> get(String account, String key) {
        return records.getForAccount(account, key);
    }

    void put(String account, String key, ObjectNode record) {
        records.putForAccount(account, key, record);
    }

    void delete(String account, String key) {
        records.deleteForAccount(account, key);
    }

    /** The records under a key prefix, in the order of their {@code _seq} member. */
    List<ObjectNode> list(String account, String prefix) {
        List<ObjectNode> found = new ArrayList<>(records.scanForAccount(account, key -> key.startsWith(prefix)));
        found.sort(Comparator.comparingLong(record -> record.path("_seq").asLong()));
        return found;
    }

    // ---------------------------------------------------------------- bytes

    byte[] bytes(String account, String key) {
        return blobs.getForAccount(account, key).map(Base64.getDecoder()::decode).orElse(new byte[0]);
    }

    void putBytes(String account, String key, byte[] data) {
        blobs.putForAccount(account, key, Base64.getEncoder().encodeToString(data));
    }

    void deleteBytes(String account, String prefix) {
        for (String key : blobs.keysForAccount(account)) {
            if (!key.equals(prefix) && !key.startsWith(prefix + "/")) {
                continue;
            }
            blobs.deleteForAccount(account, key);
        }
    }

    @Override
    public void clear() {
        records.clear();
        blobs.clear();
    }

    // ---------------------------------------------------------------- shared

    static String time(Instant instant) {
        return TIME.format(instant);
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    static AwsException missing(String message) {
        return new AwsException("MissingParameterValueException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException limit(String message) {
        return new AwsException("LimitExceededException", message, 400);
    }
}
