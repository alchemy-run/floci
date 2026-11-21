package io.floci.aws.services.glacier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.s3.S3SelectQueries;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.sns.SnsService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.aws.services.glacier.GlacierStore.invalid;
import static io.floci.aws.services.glacier.GlacierStore.limit;
import static io.floci.aws.services.glacier.GlacierStore.missing;
import static io.floci.aws.services.glacier.GlacierStore.notFound;
import static io.floci.aws.services.glacier.GlacierStore.time;

/**
 * Amazon Glacier's vault API: vaults with their tags, access policies, notifications and vault locks; archives
 * uploaded whole or in parts and checked against their SHA-256 tree hashes; and jobs that retrieve an archive or a
 * range of it, take a vault's inventory, or run a select query over a CSV archive into S3, finishing a configured
 * time after they start and notifying SNS. A vault's inventory is taken a configured time after its archives last
 * changed; DescribeVault and inventory jobs report the last one, and only an inventoried empty vault can be deleted.
 *
 * <p>Glacier is closed to new customers; floci treats its accounts as existing customers, and with
 * {@code existing-customer: false} refuses every call as AWS refuses a new account.
 */
@ApplicationScoped
public class GlacierService {

    private static final Logger LOG = Logger.getLogger(GlacierService.class);
    private static final Pattern VAULT_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,255}");
    private static final Pattern ACCOUNT = Pattern.compile("\\d{12}");
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\*|\\d+)");
    private static final Pattern BYTE_RANGE = Pattern.compile("(\\d+)-(\\d+)");
    private static final Pattern RANGE_HEADER = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private static final Set<String> EVENTS = Set.of("ArchiveRetrievalCompleted", "InventoryRetrievalCompleted");
    private static final long MAX_PART = 4L * 1024 * 1024 * 1024;
    private static final long FREE_TIER_DAILY = 10L * 1024 * 1024 * 1024 / 30;
    private static final long DAY_MILLIS = 24L * 3600 * 1000;
    private static final int MAX_TAGS = 50;
    private static final int MAX_VAULTS = 1000;
    private static final int MAX_CAPACITY = 2;
    private static final IamPolicyEvaluator POLICIES = new IamPolicyEvaluator(new ObjectMapper());
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A response: its status, headers, and a JSON body, raw bytes, or nothing. */
    public record Reply(int status, Map<String, String> headers, Object body) {
        static Reply of(int status) {
            return new Reply(status, Map.of(), null);
        }
    }

    private final GlacierStore store;
    private final SnsService sns;
    private final S3Service s3;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "glacier-jobs");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public GlacierService(GlacierStore store, SnsService sns, S3Service s3) {
        this.store = store;
        this.sns = sns;
        this.s3 = s3;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ---------------------------------------------------------------- accounts and vaults

    /** The account a request's path names: {@code -} is the caller's own. */
    String account(String accountId) {
        if (!store.existingCustomer()) {
            throw new AwsException("NoLongerSupportedException", "This API is no longer supported for new accounts. "
                    + "Please use S3 Glacier storage classes instead.", 400);
        }
        if ("-".equals(accountId)) {
            return store.callerAccount();
        }
        if (accountId == null || !ACCOUNT.matcher(accountId).matches()) {
            throw invalid("Invalid account ID: " + accountId + ". The account ID must be '-' or a 12-digit AWS "
                    + "account ID.");
        }
        return accountId;
    }

    static String vaultArn(String region, String account, String vault) {
        return AwsArnUtils.Arn.of("glacier", region, account, "vaults/" + vault).toString();
    }

    private static String vaultKey(String region, String vault) {
        return region + "/vault/" + vault;
    }

    private static String childPrefix(String region, String kind, String vault) {
        return region + "/" + kind + "/" + vault + "/";
    }

    private ObjectNode vault(String account, String region, String vault) {
        if (vault == null || !VAULT_NAME.matcher(vault).matches()) {
            throw invalid("Invalid vault name: " + vault + ". A vault name is 1 to 255 characters: a-z, A-Z, 0-9, "
                    + "'_' (underscore), '-' (hyphen) and '.' (period).");
        }
        return store.get(account, vaultKey(region, vault)).orElseThrow(() -> notFound("Vault not found for ARN: "
                + vaultArn(region, account, vault)));
    }

    private void saveVault(String account, String region, ObjectNode vault) {
        store.put(account, vaultKey(region, vault.path("VaultName").asText()), vault);
    }

    public synchronized Reply createVault(String accountId, String region, String name) {
        String account = account(accountId);
        if (name == null || !VAULT_NAME.matcher(name).matches()) {
            throw invalid("Invalid vault name: " + name + ". A vault name is 1 to 255 characters: a-z, A-Z, 0-9, "
                    + "'_' (underscore), '-' (hyphen) and '.' (period).");
        }
        String location = "/" + account + "/vaults/" + name;
        if (store.get(account, vaultKey(region, name)).isPresent()) {
            return new Reply(201, Map.of("Location", location), null);
        }
        if (store.list(account, region + "/vault/").size() >= MAX_VAULTS) {
            throw limit("The maximum number of vaults has been reached for this Region: " + MAX_VAULTS + ".");
        }
        ObjectNode vault = store.mapper().createObjectNode();
        vault.put("VaultARN", vaultArn(region, account, name)).put("VaultName", name)
                .put("CreationDate", time(Instant.now())).put("_seq", System.nanoTime());
        vault.putObject("_tags");
        saveVault(account, region, vault);
        return new Reply(201, Map.of("Location", location), null);
    }

    public synchronized ObjectNode describeVault(String accountId, String region, String name) {
        String account = account(accountId);
        return vaultView(account, region, vault(account, region, name));
    }

    private ObjectNode vaultView(String account, String region, ObjectNode vault) {
        JsonNode inventory = inventory(account, region, vault);
        ObjectNode view = store.mapper().createObjectNode();
        view.put("VaultARN", vault.path("VaultARN").asText()).put("VaultName", vault.path("VaultName").asText())
                .put("CreationDate", vault.path("CreationDate").asText());
        if (inventory != null) {
            long size = 0;
            for (JsonNode archive : inventory.path("archives")) {
                size += archive.path("Size").asLong();
            }
            view.put("LastInventoryDate", inventory.path("date").asText())
                    .put("NumberOfArchives", inventory.path("archives").size()).put("SizeInBytes", size);
        } else {
            view.putNull("LastInventoryDate");
            view.put("NumberOfArchives", 0).put("SizeInBytes", 0);
        }
        return view;
    }

    /**
     * A vault's last inventory, taken {@code inventory-millis} after its archives last changed; null before the
     * first. The inventory lists the archives as they were then.
     */
    private JsonNode inventory(String account, String region, ObjectNode vault) {
        if (!vault.hasNonNull("_lastWrite")) {
            return vault.get("_inventory");
        }
        long written = vault.get("_lastWrite").asLong();
        JsonNode inventory = vault.get("_inventory");
        long due = written + store.inventoryMillis();
        boolean stale = inventory == null || inventory.path("taken").asLong() < written;
        if (stale && System.currentTimeMillis() >= due) {
            ObjectNode taken = store.mapper().createObjectNode();
            taken.put("date", time(Instant.ofEpochMilli(due))).put("taken", due);
            ArrayNode archives = taken.putArray("archives");
            for (ObjectNode archive : store.list(account, childPrefix(region, "archive",
                    vault.path("VaultName").asText()))) {
                ObjectNode entry = archives.addObject();
                for (String field : List.of("ArchiveId", "ArchiveDescription", "CreationDate", "Size",
                        "SHA256TreeHash")) {
                    entry.set(field, archive.get(field));
                }
            }
            vault.set("_inventory", taken);
            saveVault(account, region, vault);
            return taken;
        }
        return inventory;
    }

    public synchronized Reply deleteVault(String accountId, String region, String name) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        enforceLock(account, region, vault, "glacier:DeleteVault", Map.of());
        JsonNode inventory = inventory(account, region, vault);
        boolean pending = vault.hasNonNull("_lastWrite") && (inventory == null
                || inventory.path("taken").asLong() < vault.get("_lastWrite").asLong());
        if (pending || (inventory != null && !inventory.path("archives").isEmpty())) {
            throw invalid("Vault not empty or recently written to: " + vault.path("VaultARN").asText());
        }
        for (String kind : List.of("upload", "job")) {
            for (ObjectNode child : store.list(account, childPrefix(region, kind, name))) {
                store.delete(account, child.path("_key").asText());
                store.deleteBytes(account, child.path("_key").asText());
            }
        }
        store.delete(account, vaultKey(region, name));
        return Reply.of(204);
    }

    public synchronized ObjectNode listVaults(String accountId, String region, String marker, String limitText) {
        String account = account(accountId);
        int max = pageSize(limitText, 10, 1000);
        List<ObjectNode> vaults = new ArrayList<>(store.list(account, region + "/vault/"));
        vaults.sort((a, b) -> a.path("VaultName").asText().compareTo(b.path("VaultName").asText()));
        int start = 0;
        if (marker != null && !marker.isEmpty()) {
            start = -1;
            for (int i = 0; i < vaults.size(); i++) {
                if (vaults.get(i).path("VaultARN").asText().equals(marker)) {
                    start = i;
                }
            }
            if (start < 0) {
                throw invalid("Invalid marker: " + marker);
            }
        }
        ObjectNode response = store.mapper().createObjectNode();
        ArrayNode list = response.putArray("VaultList");
        int end = Math.min(vaults.size(), start + max);
        for (int i = start; i < end; i++) {
            list.add(vaultView(account, region, vaults.get(i)));
        }
        if (end < vaults.size()) {
            response.put("Marker", vaults.get(end).path("VaultARN").asText());
        } else {
            response.putNull("Marker");
        }
        return response;
    }

    // ---------------------------------------------------------------- tags

    public synchronized Reply addTags(String accountId, String region, String name, JsonNode body) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        ObjectNode tags = vault.withObject("/_tags");
        Map<String, String> merged = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> merged.put(entry.getKey(), entry.getValue().asText()));
        body.path("Tags").fields().forEachRemaining(entry -> {
            checkTag(entry.getKey(), entry.getValue().asText());
            merged.put(entry.getKey(), entry.getValue().asText());
        });
        if (merged.size() > MAX_TAGS) {
            throw limit("The maximum number of tags for a vault is " + MAX_TAGS + ".");
        }
        tags.removeAll();
        merged.forEach(tags::put);
        saveVault(account, region, vault);
        return Reply.of(204);
    }

    private static void checkTag(String key, String value) {
        if (key.isEmpty() || key.length() > 128) {
            throw invalid("A tag key is 1 to 128 characters: " + key);
        }
        if (value.length() > 256) {
            throw invalid("A tag value is at most 256 characters, the value of " + key + " is " + value.length()
                    + ".");
        }
        if (key.startsWith("aws:")) {
            throw invalid("Tag keys beginning with aws: are reserved: " + key);
        }
    }

    public synchronized Reply removeTags(String accountId, String region, String name, JsonNode body) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        ObjectNode tags = vault.withObject("/_tags");
        body.path("TagKeys").forEach(key -> tags.remove(key.asText()));
        saveVault(account, region, vault);
        return Reply.of(204);
    }

    public synchronized ObjectNode listTags(String accountId, String region, String name) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        ObjectNode response = store.mapper().createObjectNode();
        response.set("Tags", vault.path("_tags").deepCopy());
        return response;
    }

    // ---------------------------------------------------------------- access policy and notifications

    public synchronized Reply setAccessPolicy(String accountId, String region, String name, JsonNode body) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        enforceLock(account, region, vault, "glacier:SetVaultAccessPolicy", Map.of());
        String policy = body.path("Policy").asText(null);
        if (policy == null || policy.isEmpty()) {
            throw missing("Required parameter missing: policy.Policy");
        }
        checkPolicy(policy);
        vault.put("_accessPolicy", policy);
        saveVault(account, region, vault);
        return Reply.of(204);
    }

    private void checkPolicy(String policy) {
        if (policy.length() > 20 * 1024) {
            throw invalid("The policy is larger than 20 KB.");
        }
        JsonNode parsed;
        try {
            parsed = store.mapper().readTree(policy);
        } catch (JsonProcessingException e) {
            throw invalid("Policy is not valid JSON: " + e.getOriginalMessage());
        }
        if (parsed == null || !parsed.isObject() || !parsed.has("Statement")) {
            throw invalid("Policy must be a JSON object with a Statement.");
        }
        JsonNode statements = parsed.get("Statement");
        for (JsonNode statement : statements.isArray() ? statements : List.of(statements)) {
            String effect = statement.path("Effect").asText();
            if (!"Allow".equals(effect) && !"Deny".equals(effect)) {
                throw invalid("Each policy statement needs an Effect of Allow or Deny.");
            }
            if (!statement.has("Action") && !statement.has("NotAction")) {
                throw invalid("Each policy statement needs an Action.");
            }
        }
    }

    public synchronized ObjectNode getAccessPolicy(String accountId, String region, String name) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        if (!vault.hasNonNull("_accessPolicy")) {
            throw notFound("No vault access policy is set for: " + vault.path("VaultARN").asText());
        }
        ObjectNode response = store.mapper().createObjectNode();
        response.put("Policy", vault.get("_accessPolicy").asText());
        return response;
    }

    public synchronized Reply deleteAccessPolicy(String accountId, String region, String name) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        enforceLock(account, region, vault, "glacier:DeleteVaultAccessPolicy", Map.of());
        vault.remove("_accessPolicy");
        saveVault(account, region, vault);
        return Reply.of(204);
    }

    /** A vault's access policy, for IAM's resource-policy evaluation; empty when the vault has none. */
    synchronized Optional<String> accessPolicy(String account, String region, String name) {
        return store.get(account, vaultKey(region, name)).map(vault -> vault.path("_accessPolicy").asText(null));
    }

    public synchronized Reply setNotifications(String accountId, String region, String name, JsonNode body) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        String topic = body.path("SNSTopic").asText(null);
        if (topic == null || topic.isEmpty()) {
            throw missing("Required parameter missing: vaultNotificationConfig.SNSTopic");
        }
        if (!AwsArnUtils.isArnFor(topic, "sns")) {
            throw invalid("Invalid SNS topic ARN: " + topic);
        }
        if (!body.has("Events") || body.get("Events").isEmpty()) {
            throw missing("Required parameter missing: vaultNotificationConfig.Events");
        }
        for (JsonNode event : body.get("Events")) {
            if (!EVENTS.contains(event.asText())) {
                throw invalid("Invalid event: " + event.asText() + ". Events are ArchiveRetrievalCompleted and "
                        + "InventoryRetrievalCompleted.");
            }
        }
        ObjectNode config = store.mapper().createObjectNode();
        config.put("SNSTopic", topic);
        config.set("Events", body.get("Events").deepCopy());
        vault.set("_notifications", config);
        saveVault(account, region, vault);
        return Reply.of(204);
    }

    public synchronized ObjectNode getNotifications(String accountId, String region, String name) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        if (!vault.has("_notifications")) {
            throw notFound("No notification configuration is set for vault: " + vault.path("VaultARN").asText());
        }
        return vault.get("_notifications").deepCopy();
    }

    public synchronized Reply deleteNotifications(String accountId, String region, String name) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        vault.remove("_notifications");
        saveVault(account, region, vault);
        return Reply.of(204);
    }

    // ---------------------------------------------------------------- vault lock

    /** The vault's lock, with an in-progress one past its 24 hours gone. */
    private ObjectNode lock(String account, String region, ObjectNode vault) {
        JsonNode lock = vault.get("_lock");
        if (lock == null) {
            return null;
        }
        if ("InProgress".equals(lock.path("State").asText())
                && Instant.parse(lock.path("ExpirationDate").asText()).isBefore(Instant.now())) {
            vault.remove("_lock");
            saveVault(account, region, vault);
            return null;
        }
        return (ObjectNode) lock;
    }

    public synchronized Reply initiateLock(String accountId, String region, String name, JsonNode body) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        ObjectNode lock = lock(account, region, vault);
        if (lock != null) {
            throw invalid("Locked".equals(lock.path("State").asText())
                    ? "Vault is already locked: " + vault.path("VaultARN").asText()
                    : "A vault lock is already in progress for: " + vault.path("VaultARN").asText()
                    + ". Abort it before initiating another.");
        }
        String policy = body.path("Policy").asText(null);
        if (policy == null || policy.isEmpty()) {
            throw missing("Required parameter missing: policy.Policy");
        }
        checkPolicy(policy);
        String lockId = id(18);
        Instant now = Instant.now();
        ObjectNode created = vault.putObject("_lock");
        created.put("Policy", policy).put("State", "InProgress").put("LockId", lockId)
                .put("CreationDate", time(now)).put("ExpirationDate", time(now.plus(1, ChronoUnit.DAYS)));
        saveVault(account, region, vault);
        return new Reply(201, Map.of("x-amz-lock-id", lockId), null);
    }

    public synchronized ObjectNode getLock(String accountId, String region, String name) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        ObjectNode lock = lock(account, region, vault);
        if (lock == null) {
            throw notFound("No vault lock policy is set for: " + vault.path("VaultARN").asText());
        }
        ObjectNode response = store.mapper().createObjectNode();
        response.put("Policy", lock.path("Policy").asText()).put("State", lock.path("State").asText())
                .put("CreationDate", lock.path("CreationDate").asText());
        if ("InProgress".equals(lock.path("State").asText())) {
            response.put("ExpirationDate", lock.path("ExpirationDate").asText());
        } else {
            response.putNull("ExpirationDate");
        }
        return response;
    }

    public synchronized Reply completeLock(String accountId, String region, String name, String lockId) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        ObjectNode lock = lock(account, region, vault);
        if (lock == null) {
            throw invalid("No vault lock is in progress for: " + vault.path("VaultARN").asText());
        }
        if (!lock.path("LockId").asText().equals(lockId)) {
            throw invalid("Invalid lock ID: " + lockId);
        }
        lock.put("State", "Locked");
        saveVault(account, region, vault);
        return Reply.of(204);
    }

    public synchronized Reply abortLock(String accountId, String region, String name) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        ObjectNode lock = lock(account, region, vault);
        if (lock != null && "Locked".equals(lock.path("State").asText())) {
            throw invalid("The vault lock of " + vault.path("VaultARN").asText() + " is locked and cannot be "
                    + "aborted.");
        }
        vault.remove("_lock");
        saveVault(account, region, vault);
        return Reply.of(204);
    }

    /**
     * Refuses a call the vault's lock policy denies. A lock policy is in effect from its initiation, in progress
     * or locked, and holds whatever IAM allows.
     */
    private void enforceLock(String account, String region, ObjectNode vault, String action,
                             Map<String, List<String>> context) {
        ObjectNode lock = lock(account, region, vault);
        if (lock == null) {
            return;
        }
        String partition = AwsArnUtils.parse(vault.path("VaultARN").asText()).partition();
        String caller = AwsArnUtils.Arn.global(partition, "iam", store.callerAccount(), "root").toString();
        IamPolicyEvaluator.ResourcePolicyDecision decision = POLICIES.evaluateResourcePolicy(
                List.of(lock.path("Policy").asText()), caller, action, vault.path("VaultARN").asText(), context);
        if (decision == IamPolicyEvaluator.ResourcePolicyDecision.EXPLICIT_DENY) {
            throw new AwsException("AccessDeniedException", "The vault lock policy of "
                    + vault.path("VaultARN").asText() + " denies " + action + ".", 403);
        }
    }

    // ---------------------------------------------------------------- archives

    public synchronized Reply uploadArchive(String accountId, String region, String name, String description,
                                            String checksum, byte[] body) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        checkDescription(description);
        if (checksum == null || checksum.isEmpty()) {
            throw missing("Missing required header for this request: x-amz-sha256-tree-hash");
        }
        if (body.length > MAX_PART) {
            throw invalid("An archive uploaded in a single request is at most 4 GiB; upload it in parts.");
        }
        String hash = GlacierTreeHash.of(body);
        if (!hash.equalsIgnoreCase(checksum)) {
            throw invalid("Checksum mismatch: expected " + checksum + " but calculated " + hash);
        }
        String archiveId = createArchive(account, region, vault, description, body, hash);
        return new Reply(201, Map.of("Location", "/" + account + "/vaults/" + name + "/archives/" + archiveId,
                "x-amz-sha256-tree-hash", hash, "x-amz-archive-id", archiveId), null);
    }

    private String createArchive(String account, String region, ObjectNode vault, String description, byte[] body,
                                 String hash) {
        String archiveId = id(103);
        String key = childPrefix(region, "archive", vault.path("VaultName").asText()) + archiveId;
        ObjectNode archive = store.mapper().createObjectNode();
        archive.put("ArchiveId", archiveId).put("ArchiveDescription", description == null ? "" : description)
                .put("CreationDate", time(Instant.now())).put("Size", body.length).put("SHA256TreeHash", hash)
                .put("_seq", System.nanoTime()).put("_key", key);
        store.put(account, key, archive);
        store.putBytes(account, key, body);
        vault.put("_lastWrite", System.currentTimeMillis());
        saveVault(account, region, vault);
        return archiveId;
    }

    private static void checkDescription(String description) {
        if (description == null) {
            return;
        }
        if (description.length() > 1024) {
            throw invalid("The archive description is at most 1024 characters.");
        }
        for (char c : description.toCharArray()) {
            if (c < 32 || c > 126) {
                throw invalid("The archive description may contain only printable ASCII characters.");
            }
        }
    }

    public synchronized Reply deleteArchive(String accountId, String region, String name, String archiveId) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        String key = childPrefix(region, "archive", name) + archiveId;
        ObjectNode archive = store.get(account, key).orElseThrow(() -> notFound("Archive not found for ID: "
                + archiveId));
        long age = ChronoUnit.DAYS.between(Instant.parse(archive.path("CreationDate").asText()), Instant.now());
        enforceLock(account, region, vault, "glacier:DeleteArchive",
                Map.of("glacier:ArchiveAgeInDays", List.of(Long.toString(age))));
        store.delete(account, key);
        store.deleteBytes(account, key);
        vault.put("_lastWrite", System.currentTimeMillis());
        saveVault(account, region, vault);
        return Reply.of(204);
    }

    // ---------------------------------------------------------------- multipart uploads

    private ObjectNode upload(String account, String region, String vault, String uploadId) {
        String key = childPrefix(region, "upload", vault) + uploadId;
        ObjectNode upload = store.get(account, key).orElseThrow(() -> notFound("Multipart upload not found for "
                + "upload ID: " + uploadId));
        if (System.currentTimeMillis() - upload.path("_active").asLong() > DAY_MILLIS) {
            store.delete(account, key);
            store.deleteBytes(account, key);
            throw notFound("Multipart upload not found for upload ID: " + uploadId);
        }
        return upload;
    }

    public synchronized Reply initiateMultipartUpload(String accountId, String region, String name,
                                                      String description, String partSize) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        checkDescription(description);
        if (partSize == null || partSize.isEmpty()) {
            throw missing("Missing required header for this request: x-amz-part-size");
        }
        long size;
        try {
            size = Long.parseLong(partSize);
        } catch (NumberFormatException e) {
            size = -1;
        }
        if (size < GlacierTreeHash.MEBIBYTE || size > MAX_PART || Long.bitCount(size) != 1) {
            throw invalid("Invalid part size: " + partSize + ". Part size must not be null, must be a power of two "
                    + "and be between 1048576 and 4294967296 bytes.");
        }
        String uploadId = id(69);
        String key = childPrefix(region, "upload", name) + uploadId;
        ObjectNode upload = store.mapper().createObjectNode();
        upload.put("MultipartUploadId", uploadId).put("VaultARN", vault.path("VaultARN").asText())
                .put("ArchiveDescription", description == null ? "" : description).put("PartSizeInBytes", size)
                .put("CreationDate", time(Instant.now())).put("_seq", System.nanoTime()).put("_key", key)
                .put("_active", System.currentTimeMillis());
        upload.putObject("_parts");
        store.put(account, key, upload);
        return new Reply(201, Map.of("Location", "/" + account + "/vaults/" + name + "/multipart-uploads/" + uploadId,
                "x-amz-multipart-upload-id", uploadId), null);
    }

    public synchronized Reply uploadPart(String accountId, String region, String name, String uploadId,
                                         String range, String checksum, byte[] body) {
        String account = account(accountId);
        vault(account, region, name);
        ObjectNode upload = upload(account, region, name, uploadId);
        if (range == null || range.isEmpty()) {
            throw missing("Missing required header for this request: Content-Range");
        }
        Matcher matcher = CONTENT_RANGE.matcher(range.trim());
        if (!matcher.matches()) {
            throw invalid("Invalid Content-Range: " + range + ". Expected bytes <start>-<end>/*.");
        }
        long start = Long.parseLong(matcher.group(1));
        long end = Long.parseLong(matcher.group(2));
        long partSize = upload.path("PartSizeInBytes").asLong();
        if (end < start || start % partSize != 0 || end - start + 1 > partSize) {
            throw invalid("Invalid Content-Range: " + range + ". A part starts on a multiple of the part size, "
                    + partSize + ", and is at most that long.");
        }
        if (body.length != end - start + 1) {
            throw invalid("The Content-Range " + range + " is " + (end - start + 1) + " bytes, but the body is "
                    + body.length + ".");
        }
        if (checksum == null || checksum.isEmpty()) {
            throw missing("Missing required header for this request: x-amz-sha256-tree-hash");
        }
        String hash = GlacierTreeHash.of(body);
        if (!hash.equalsIgnoreCase(checksum)) {
            throw invalid("Checksum mismatch: expected " + checksum + " but calculated " + hash);
        }
        String key = upload.path("_key").asText();
        upload.withObject("/_parts").putObject(Long.toString(start)).put("end", end).put("hash", hash);
        upload.put("_active", System.currentTimeMillis());
        store.put(account, key, upload);
        store.putBytes(account, key + "/" + start, body);
        return new Reply(204, Map.of("x-amz-sha256-tree-hash", hash), null);
    }

    public synchronized Reply completeMultipartUpload(String accountId, String region, String name, String uploadId,
                                                      String archiveSize, String checksum) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        ObjectNode upload = upload(account, region, name, uploadId);
        if (archiveSize == null || archiveSize.isEmpty()) {
            throw missing("Missing required header for this request: x-amz-archive-size");
        }
        if (checksum == null || checksum.isEmpty()) {
            throw missing("Missing required header for this request: x-amz-sha256-tree-hash");
        }
        long size;
        try {
            size = Long.parseLong(archiveSize);
        } catch (NumberFormatException e) {
            throw invalid("Invalid archive size: " + archiveSize);
        }
        String key = upload.path("_key").asText();
        TreeMap<Long, JsonNode> parts = new TreeMap<>();
        upload.path("_parts").fields().forEachRemaining(entry -> parts.put(Long.parseLong(entry.getKey()),
                entry.getValue()));
        long expected = 0;
        for (Map.Entry<Long, JsonNode> part : parts.entrySet()) {
            if (part.getKey() != expected) {
                break;
            }
            expected = part.getValue().path("end").asLong() + 1;
        }
        if (expected != size || parts.isEmpty() && size != 0) {
            throw invalid("The uploaded parts do not cover the archive size " + size + ": parts cover bytes 0 to "
                    + (expected - 1) + " contiguously.");
        }
        byte[] archive = new byte[(int) size];
        for (Map.Entry<Long, JsonNode> part : parts.entrySet()) {
            if (part.getKey() >= size) {
                continue;
            }
            byte[] data = store.bytes(account, key + "/" + part.getKey());
            System.arraycopy(data, 0, archive, (int) (long) part.getKey(), data.length);
        }
        String hash = GlacierTreeHash.of(archive);
        if (!hash.equalsIgnoreCase(checksum)) {
            throw invalid("Checksum mismatch: expected " + checksum + " but calculated " + hash);
        }
        String archiveId = createArchive(account, region, vault, upload.path("ArchiveDescription").asText(), archive,
                hash);
        store.delete(account, key);
        store.deleteBytes(account, key);
        return new Reply(201, Map.of("Location", "/" + account + "/vaults/" + name + "/archives/" + archiveId,
                "x-amz-sha256-tree-hash", hash, "x-amz-archive-id", archiveId), null);
    }

    public synchronized Reply abortMultipartUpload(String accountId, String region, String name, String uploadId) {
        String account = account(accountId);
        vault(account, region, name);
        ObjectNode upload = upload(account, region, name, uploadId);
        store.delete(account, upload.path("_key").asText());
        store.deleteBytes(account, upload.path("_key").asText());
        return Reply.of(204);
    }

    public synchronized ObjectNode listParts(String accountId, String region, String name, String uploadId,
                                             String marker, String limitText) {
        String account = account(accountId);
        vault(account, region, name);
        ObjectNode upload = upload(account, region, name, uploadId);
        int max = pageSize(limitText, 50, 1000);
        TreeMap<Long, JsonNode> parts = new TreeMap<>();
        upload.path("_parts").fields().forEachRemaining(entry -> parts.put(Long.parseLong(entry.getKey()),
                entry.getValue()));
        ObjectNode response = store.mapper().createObjectNode();
        for (String field : List.of("MultipartUploadId", "VaultARN", "ArchiveDescription", "PartSizeInBytes",
                "CreationDate")) {
            response.set(field, upload.get(field));
        }
        long from = 0;
        if (marker != null && !marker.isEmpty()) {
            try {
                from = Long.parseLong(marker);
            } catch (NumberFormatException e) {
                throw invalid("Invalid marker: " + marker);
            }
        }
        ArrayNode list = response.putArray("Parts");
        String next = null;
        for (Map.Entry<Long, JsonNode> part : parts.tailMap(from, true).entrySet()) {
            if (list.size() == max) {
                next = Long.toString(part.getKey());
                break;
            }
            list.addObject().put("RangeInBytes", part.getKey() + "-" + part.getValue().path("end").asLong())
                    .put("SHA256TreeHash", part.getValue().path("hash").asText());
        }
        if (next != null) {
            response.put("Marker", next);
        } else {
            response.putNull("Marker");
        }
        return response;
    }

    public synchronized ObjectNode listMultipartUploads(String accountId, String region, String name, String marker,
                                                        String limitText) {
        String account = account(accountId);
        vault(account, region, name);
        int max = pageSize(limitText, 50, 1000);
        List<ObjectNode> uploads = new ArrayList<>();
        for (ObjectNode upload : store.list(account, childPrefix(region, "upload", name))) {
            if (System.currentTimeMillis() - upload.path("_active").asLong() <= DAY_MILLIS) {
                uploads.add(upload);
            }
        }
        return page(uploads, marker, max, "MultipartUploadId", "UploadsList", List.of("MultipartUploadId",
                "VaultARN", "ArchiveDescription", "PartSizeInBytes", "CreationDate"));
    }

    // ---------------------------------------------------------------- jobs

    public synchronized Reply initiateJob(String accountId, String region, String name, JsonNode parameters) {
        String account = account(accountId);
        ObjectNode vault = vault(account, region, name);
        String type = parameters.path("Type").asText(null);
        if (type == null || type.isEmpty()) {
            throw missing("Required parameter missing: jobParameters.Type");
        }
        String tier = parameters.path("Tier").asText("Standard");
        if (!Set.of("Expedited", "Standard", "Bulk").contains(tier)) {
            throw invalid("Invalid tier: " + tier + ". Tiers are Expedited, Standard and Bulk.");
        }
        String topic = parameters.path("SNSTopic").asText(null);
        if (topic != null && !AwsArnUtils.isArnFor(topic, "sns")) {
            throw invalid("Invalid SNS topic ARN: " + topic);
        }
        String description = parameters.path("Description").asText(null);
        checkDescription(description);
        String jobId = id(69);
        String key = childPrefix(region, "job", name) + jobId;
        ObjectNode job = store.mapper().createObjectNode();
        job.put("JobId", jobId);
        if (description != null) {
            job.put("JobDescription", description);
        } else {
            job.putNull("JobDescription");
        }
        job.put("VaultARN", vault.path("VaultARN").asText()).put("CreationDate", time(Instant.now()))
                .put("Completed", false).put("StatusCode", "InProgress").putNull("StatusMessage")
                .putNull("CompletionDate");
        if (topic != null) {
            job.put("SNSTopic", topic);
        } else {
            job.putNull("SNSTopic");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        switch (type) {
            case "archive-retrieval" -> archiveJob(account, region, name, parameters, job, tier);
            case "inventory-retrieval" -> inventoryJob(account, region, vault, parameters, job);
            case "select" -> {
                selectJob(account, region, name, parameters, job, tier);
                headers.put("x-amz-job-output-path", job.path("JobOutputPath").asText());
            }
            default -> throw invalid("Invalid job type: " + type + ". Types are archive-retrieval, "
                    + "inventory-retrieval and select.");
        }
        job.put("_seq", System.nanoTime()).put("_key", key).put("_region", region).put("_account", account);
        store.put(account, key, job);
        headers.put("Location", "/" + account + "/vaults/" + name + "/jobs/" + jobId);
        headers.put("x-amz-job-id", jobId);
        long delay = store.jobMillis();
        if (delay <= 0) {
            finishJob(account, key);
        } else {
            scheduler.schedule(() -> finishJob(account, key), delay, TimeUnit.MILLISECONDS);
        }
        return new Reply(202, headers, null);
    }

    private void archiveJob(String account, String region, String vault, JsonNode parameters, ObjectNode job,
                            String tier) {
        String archiveId = parameters.path("ArchiveId").asText(null);
        if (archiveId == null || archiveId.isEmpty()) {
            throw missing("Required parameter missing: jobParameters.ArchiveId");
        }
        ObjectNode archive = store.get(account, childPrefix(region, "archive", vault) + archiveId)
                .orElseThrow(() -> notFound("Archive not found for ID: " + archiveId));
        long size = archive.path("Size").asLong();
        long start = 0;
        long end = size - 1;
        String range = parameters.path("RetrievalByteRange").asText(null);
        if (range != null && !range.isEmpty()) {
            Matcher matcher = BYTE_RANGE.matcher(range);
            if (!matcher.matches()) {
                throw invalid("Invalid RetrievalByteRange: " + range + ". Expected <start>-<end>.");
            }
            start = Long.parseLong(matcher.group(1));
            end = Long.parseLong(matcher.group(2));
            if (end < start || end >= size || start % GlacierTreeHash.MEBIBYTE != 0
                    || ((end + 1) % GlacierTreeHash.MEBIBYTE != 0 && end != size - 1)) {
                throw invalid("Invalid RetrievalByteRange: " + range + ". The range starts on a megabyte boundary "
                        + "and ends one byte before one, or at the end of the archive, which is " + size
                        + " bytes.");
            }
        }
        long bytes = Math.max(0, end - start + 1);
        if ("Standard".equals(tier)) {
            enforceRetrievalPolicy(account, region, bytes);
        }
        job.put("Action", "ArchiveRetrieval").put("ArchiveId", archiveId).put("ArchiveSizeInBytes", size)
                .putNull("InventorySizeInBytes").put("ArchiveSHA256TreeHash", archive.path("SHA256TreeHash").asText())
                .put("RetrievalByteRange", start + "-" + end).put("Tier", tier).put("_bytes", bytes);
        if (start == 0 && end == size - 1) {
            job.put("SHA256TreeHash", archive.path("SHA256TreeHash").asText());
        } else if (GlacierTreeHash.aligned(start, end, size)) {
            byte[] data = store.bytes(account, archive.path("_key").asText());
            job.put("SHA256TreeHash", GlacierTreeHash.of(data, start, bytes));
        } else {
            job.putNull("SHA256TreeHash");
        }
    }

    private void inventoryJob(String account, String region, ObjectNode vault, JsonNode parameters, ObjectNode job) {
        if (inventory(account, region, vault) == null) {
            throw notFound("Inventory for vault " + vault.path("VaultARN").asText() + " is not yet available. A "
                    + "vault's inventory is taken after its first archive is uploaded.");
        }
        String format = parameters.path("Format").asText("JSON");
        if (!"JSON".equals(format) && !"CSV".equals(format)) {
            throw invalid("Invalid inventory format: " + format + ". Formats are JSON and CSV.");
        }
        ObjectNode retrieval = job.putObject("InventoryRetrievalParameters");
        retrieval.put("Format", format);
        JsonNode given = parameters.path("InventoryRetrievalParameters");
        for (String field : List.of("StartDate", "EndDate", "Limit", "Marker")) {
            if (given.hasNonNull(field)) {
                retrieval.put(field, given.get(field).asText());
            } else {
                retrieval.putNull(field);
            }
        }
        for (String field : List.of("StartDate", "EndDate")) {
            if (given.hasNonNull(field)) {
                try {
                    Instant.parse(given.get(field).asText());
                } catch (RuntimeException e) {
                    throw invalid("Invalid " + field + ": " + given.get(field).asText() + ". Expected an ISO 8601 "
                            + "date such as 2026-01-01T00:00:00Z.");
                }
            }
        }
        if (given.hasNonNull("Limit")) {
            int limit;
            try {
                limit = Integer.parseInt(given.get("Limit").asText());
            } catch (NumberFormatException e) {
                limit = 0;
            }
            if (limit < 1) {
                throw invalid("Invalid inventory limit: " + given.get("Limit").asText());
            }
        }
        job.put("Action", "InventoryRetrieval").putNull("ArchiveId").putNull("ArchiveSizeInBytes")
                .putNull("SHA256TreeHash").putNull("ArchiveSHA256TreeHash").putNull("RetrievalByteRange")
                .put("Tier", "Standard");
    }

    private void selectJob(String account, String region, String vault, JsonNode parameters, ObjectNode job,
                           String tier) {
        String archiveId = parameters.path("ArchiveId").asText(null);
        if (archiveId == null || archiveId.isEmpty()) {
            throw missing("Required parameter missing: jobParameters.ArchiveId");
        }
        ObjectNode archive = store.get(account, childPrefix(region, "archive", vault) + archiveId)
                .orElseThrow(() -> notFound("Archive not found for ID: " + archiveId));
        JsonNode select = parameters.path("SelectParameters");
        if (!"SQL".equals(select.path("ExpressionType").asText("SQL"))) {
            throw invalid("Invalid ExpressionType: " + select.path("ExpressionType").asText() + ". Only SQL is "
                    + "supported.");
        }
        if (!select.hasNonNull("Expression")) {
            throw missing("Required parameter missing: jobParameters.SelectParameters.Expression");
        }
        if (!select.path("InputSerialization").has("csv")) {
            throw invalid("Glacier Select reads CSV archives: give SelectParameters.InputSerialization.csv.");
        }
        S3SelectQueries.check(select.get("Expression").asText());
        JsonNode location = parameters.path("OutputLocation").path("S3");
        if (!location.hasNonNull("BucketName")) {
            throw missing("Required parameter missing: jobParameters.OutputLocation.S3.BucketName");
        }
        String prefix = location.path("Prefix").asText("");
        String path = (prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/") + job.path("JobId").asText();
        job.put("Action", "Select").put("ArchiveId", archiveId).put("ArchiveSizeInBytes", archive.path("Size")
                .asLong()).putNull("InventorySizeInBytes").put("ArchiveSHA256TreeHash",
                archive.path("SHA256TreeHash").asText()).putNull("SHA256TreeHash").putNull("RetrievalByteRange")
                .put("Tier", tier).put("JobOutputPath", path);
        job.set("SelectParameters", select.deepCopy());
        job.set("OutputLocation", parameters.path("OutputLocation").deepCopy());
    }

    /** Refuses a Standard retrieval the Region's data retrieval policy does not allow. */
    private void enforceRetrievalPolicy(String account, String region, long bytes) {
        JsonNode rule = retrievalPolicy(account, region).path("Rules").path(0);
        String strategy = rule.path("Strategy").asText("None");
        if ("None".equals(strategy)) {
            return;
        }
        List<ObjectNode> jobs = new ArrayList<>();
        for (ObjectNode job : store.list(account, region + "/job/")) {
            if ("ArchiveRetrieval".equals(job.path("Action").asText()) && "Standard".equals(job.path("Tier")
                    .asText())) {
                jobs.add(job);
            }
        }
        if ("FreeTier".equals(strategy)) {
            Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
            long used = 0;
            for (ObjectNode job : jobs) {
                if (!Instant.parse(job.path("CreationDate").asText()).isBefore(today)) {
                    used += job.path("_bytes").asLong();
                }
            }
            if (used + bytes > FREE_TIER_DAILY) {
                throw new AwsException("PolicyEnforcedException", "Retrieval of " + bytes + " bytes was denied by "
                        + "the current data retrieval policy: it would exceed today's free tier allowance of "
                        + FREE_TIER_DAILY + " bytes.", 400);
            }
            return;
        }
        long quota = rule.path("BytesPerHour").asLong();
        long rate = bytes / 4;
        for (ObjectNode job : jobs) {
            if (!job.path("Completed").asBoolean()) {
                rate += job.path("_bytes").asLong() / 4;
            }
        }
        if (rate > quota) {
            throw new AwsException("PolicyEnforcedException", "Retrieval of " + bytes + " bytes was denied by the "
                    + "current data retrieval policy: the in-progress retrievals would reach " + rate
                    + " bytes per hour, over its " + quota + ".", 400);
        }
    }

    /** Finishes a job: its output taken, its status Succeeded or Failed, and its notifications sent. */
    private void finishJob(String account, String key) {
        ObjectNode job;
        ObjectNode vault;
        synchronized (this) {
            job = store.get(account, key).orElse(null);
            if (job == null || job.path("Completed").asBoolean()) {
                return;
            }
            String region = job.path("_region").asText();
            String vaultName = key.substring((region + "/job/").length(), key.lastIndexOf('/'));
            vault = store.get(account, vaultKey(region, vaultName)).orElse(null);
            String failure = null;
            try {
                switch (job.path("Action").asText()) {
                    case "ArchiveRetrieval" -> {
                        ObjectNode archive = store.get(account, childPrefix(region, "archive", vaultName)
                                + job.path("ArchiveId").asText()).orElseThrow(() -> new IllegalStateException(
                                "The archive was deleted before the job finished."));
                        byte[] data = store.bytes(account, archive.path("_key").asText());
                        String[] range = job.path("RetrievalByteRange").asText().split("-");
                        int start = Integer.parseInt(range[0]);
                        int end = Integer.parseInt(range[1]);
                        byte[] output = new byte[Math.max(0, end - start + 1)];
                        System.arraycopy(data, start, output, 0, output.length);
                        store.putBytes(account, key, output);
                        job.put("_contentType", "application/octet-stream")
                                .put("_archiveDescription", archive.path("ArchiveDescription").asText());
                    }
                    case "InventoryRetrieval" -> {
                        byte[] output = inventoryOutput(account, region, vault, job);
                        store.putBytes(account, key, output);
                        job.put("InventorySizeInBytes", output.length);
                    }
                    case "Select" -> runSelect(account, region, vaultName, job);
                    default -> {
                    }
                }
            } catch (IllegalStateException | AwsException e) {
                failure = e.getMessage();
            }
            job.put("Completed", true).put("CompletionDate", time(Instant.now()))
                    .put("StatusCode", failure == null ? "Succeeded" : "Failed")
                    .put("StatusMessage", failure == null ? "Succeeded" : failure);
            store.put(account, key, job);
        }
        notify(job, vault);
    }

    private byte[] inventoryOutput(String account, String region, ObjectNode vault, ObjectNode job) {
        if (vault == null) {
            throw new IllegalStateException("The vault was deleted before the job finished.");
        }
        JsonNode inventory = inventory(account, region, vault);
        JsonNode parameters = job.path("InventoryRetrievalParameters");
        Instant start = parameters.hasNonNull("StartDate") ? Instant.parse(parameters.get("StartDate").asText())
                : null;
        Instant end = parameters.hasNonNull("EndDate") ? Instant.parse(parameters.get("EndDate").asText()) : null;
        int max = parameters.hasNonNull("Limit") ? Integer.parseInt(parameters.get("Limit").asText())
                : Integer.MAX_VALUE;
        String marker = parameters.path("Marker").asText(null);
        List<JsonNode> archives = new ArrayList<>();
        boolean started = marker == null;
        String next = null;
        for (JsonNode archive : inventory.path("archives")) {
            Instant created = Instant.parse(archive.path("CreationDate").asText());
            if ((start != null && created.isBefore(start)) || (end != null && !created.isBefore(end))) {
                continue;
            }
            if (!started) {
                started = archive.path("ArchiveId").asText().equals(marker);
                if (!started) {
                    continue;
                }
            }
            if (archives.size() == max) {
                next = archive.path("ArchiveId").asText();
                break;
            }
            archives.add(archive);
        }
        if (next != null) {
            ((ObjectNode) job.path("InventoryRetrievalParameters")).put("Marker", next);
        }
        if ("CSV".equals(parameters.path("Format").asText())) {
            StringBuilder csv = new StringBuilder("ArchiveId,ArchiveDescription,CreationDate,Size,SHA256TreeHash\n");
            for (JsonNode archive : archives) {
                csv.append(archive.path("ArchiveId").asText()).append(",\"")
                        .append(archive.path("ArchiveDescription").asText().replace("\"", "\"\"")).append("\",")
                        .append(archive.path("CreationDate").asText()).append(',')
                        .append(archive.path("Size").asLong()).append(',')
                        .append(archive.path("SHA256TreeHash").asText()).append('\n');
            }
            job.put("_contentType", "text/csv");
            return csv.toString().getBytes(StandardCharsets.UTF_8);
        }
        ObjectNode output = store.mapper().createObjectNode();
        output.put("VaultARN", vault.path("VaultARN").asText()).put("InventoryDate", inventory.path("date").asText());
        ArrayNode list = output.putArray("ArchiveList");
        archives.forEach(archive -> list.add(archive.deepCopy()));
        job.put("_contentType", "application/json");
        try {
            return store.mapper().writeValueAsBytes(output);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    /** Runs a select job's query over its CSV archive and writes the result and a manifest to S3. */
    private void runSelect(String account, String region, String vault, ObjectNode job) {
        ObjectNode archive = store.get(account, childPrefix(region, "archive", vault) + job.path("ArchiveId").asText())
                .orElseThrow(() -> new IllegalStateException("The archive was deleted before the job finished."));
        String csv = new String(store.bytes(account, archive.path("_key").asText()), StandardCharsets.UTF_8);
        JsonNode select = job.path("SelectParameters");
        String header = select.path("InputSerialization").path("csv").path("FileHeaderInfo").asText("NONE");
        List<String> records = S3SelectQueries.selectCsv(csv, select.path("Expression").asText(), header);
        String bucket = job.path("OutputLocation").path("S3").path("BucketName").asText();
        String path = job.path("JobOutputPath").asText();
        StringBuilder out = new StringBuilder();
        records.forEach(record -> out.append(record).append('\n'));
        String resultKey = path + "/results/" + id(12) + ".csv";
        try {
            s3.putObject(bucket, resultKey, out.toString().getBytes(StandardCharsets.UTF_8), "text/csv", Map.of());
            s3.putObject(bucket, path + "/job.txt", (job.path("JobId").asText() + "\n" + resultKey + "\n")
                    .getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        } catch (AwsException e) {
            throw new IllegalStateException("Unable to write the select output to s3://" + bucket + "/" + path + ": "
                    + e.getMessage());
        }
    }

    /** Publishes a finished job to its SNS topic and to the vault's, when the vault's events include it. */
    private void notify(ObjectNode job, ObjectNode vault) {
        Set<String> topics = new LinkedHashSet<>();
        if (job.hasNonNull("SNSTopic")) {
            topics.add(job.get("SNSTopic").asText());
        }
        if (vault != null && vault.has("_notifications")) {
            String event = "ArchiveRetrieval".equals(job.path("Action").asText()) ? "ArchiveRetrievalCompleted"
                    : "InventoryRetrieval".equals(job.path("Action").asText()) ? "InventoryRetrievalCompleted" : null;
            for (JsonNode configured : vault.path("_notifications").path("Events")) {
                if (configured.asText().equals(event)) {
                    topics.add(vault.path("_notifications").path("SNSTopic").asText());
                }
            }
        }
        String message;
        try {
            message = store.mapper().writeValueAsString(jobView(job));
        } catch (JsonProcessingException e) {
            return;
        }
        for (String topic : topics) {
            try {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(topic);
                sns.publish(topic, null, message, "Notification From Amazon Glacier", arn.region());
            } catch (RuntimeException e) {
                LOG.warnv("Glacier could not notify {0} of job {1}: {2}", topic, job.path("JobId").asText(),
                        e.getMessage());
            }
        }
    }

    private ObjectNode job(String account, String region, String vault, String jobId) {
        ObjectNode job = store.get(account, childPrefix(region, "job", vault) + jobId)
                .orElseThrow(() -> notFound("Job not found for ID: " + jobId));
        if (job.path("Completed").asBoolean() && Instant.parse(job.path("CompletionDate").asText())
                .plus(1, ChronoUnit.DAYS).isBefore(Instant.now())) {
            store.delete(account, job.path("_key").asText());
            store.deleteBytes(account, job.path("_key").asText());
            throw notFound("Job not found for ID: " + jobId);
        }
        return job;
    }

    private static ObjectNode jobView(ObjectNode job) {
        ObjectNode view = job.deepCopy();
        List<String> hidden = new ArrayList<>();
        view.fieldNames().forEachRemaining(field -> {
            if (field.startsWith("_")) {
                hidden.add(field);
            }
        });
        view.remove(hidden);
        return view;
    }

    public synchronized ObjectNode describeJob(String accountId, String region, String name, String jobId) {
        String account = account(accountId);
        vault(account, region, name);
        return jobView(job(account, region, name, jobId));
    }

    public synchronized ObjectNode listJobs(String accountId, String region, String name, String limitText,
                                            String marker, String statusCode, String completed) {
        String account = account(accountId);
        vault(account, region, name);
        int max = pageSize(limitText, 50, 50);
        if (statusCode != null && !Set.of("InProgress", "Succeeded", "Failed").contains(statusCode)) {
            throw invalid("Invalid statuscode: " + statusCode + ". Status codes are InProgress, Succeeded and Failed.");
        }
        if (completed != null && !Set.of("true", "false").contains(completed)) {
            throw invalid("Invalid completed: " + completed + ". Expected true or false.");
        }
        List<ObjectNode> jobs = new ArrayList<>();
        for (ObjectNode job : store.list(account, childPrefix(region, "job", name))) {
            try {
                job(account, region, name, job.path("JobId").asText());
            } catch (AwsException expired) {
                continue;
            }
            if (statusCode != null && !statusCode.equals(job.path("StatusCode").asText())) {
                continue;
            }
            if (completed != null && Boolean.parseBoolean(completed) != job.path("Completed").asBoolean()) {
                continue;
            }
            jobs.add(job);
        }
        List<ObjectNode> views = new ArrayList<>();
        jobs.forEach(job -> views.add(jobView(job)));
        ObjectNode page = page(views, marker, max, "JobId", "JobList", null);
        return page;
    }

    public synchronized Reply getJobOutput(String accountId, String region, String name, String jobId,
                                           String rangeHeader) {
        String account = account(accountId);
        vault(account, region, name);
        ObjectNode job = job(account, region, name, jobId);
        if (!"Succeeded".equals(job.path("StatusCode").asText()) || "Select".equals(job.path("Action").asText())) {
            throw invalid("Select".equals(job.path("Action").asText())
                    ? "The output of select job " + jobId + " is in S3 at " + job.path("JobOutputPath").asText() + "."
                    : "The job is not currently available for download: " + jobId);
        }
        byte[] output = store.bytes(account, job.path("_key").asText());
        long start = 0;
        long end = output.length - 1L;
        boolean partial = false;
        if (rangeHeader != null && !rangeHeader.isEmpty()) {
            Matcher matcher = RANGE_HEADER.matcher(rangeHeader.trim());
            if (!matcher.matches() || (matcher.group(1).isEmpty() && matcher.group(2).isEmpty())) {
                throw invalid("Invalid range: " + rangeHeader + ". Expected bytes=<start>-<end>.");
            }
            if (matcher.group(1).isEmpty()) {
                start = Math.max(0, output.length - Long.parseLong(matcher.group(2)));
            } else {
                start = Long.parseLong(matcher.group(1));
                if (!matcher.group(2).isEmpty()) {
                    end = Math.min(end, Long.parseLong(matcher.group(2)));
                }
            }
            if (start > end || start >= output.length) {
                throw invalid("The range " + rangeHeader + " is outside the job output of " + output.length
                        + " bytes.");
            }
            partial = true;
        }
        int length = (int) (end - start + 1);
        byte[] body = new byte[Math.max(0, length)];
        System.arraycopy(output, (int) start, body, 0, body.length);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Content-Type", job.path("_contentType").asText("application/octet-stream"));
        if ("ArchiveRetrieval".equals(job.path("Action").asText())) {
            headers.put("x-amz-archive-description", job.path("_archiveDescription").asText(""));
            long offset = Long.parseLong(job.path("RetrievalByteRange").asText().split("-")[0]);
            long archiveSize = job.path("ArchiveSizeInBytes").asLong();
            if (!partial && job.hasNonNull("SHA256TreeHash")) {
                headers.put("x-amz-sha256-tree-hash", job.get("SHA256TreeHash").asText());
            } else if (partial && GlacierTreeHash.aligned(offset + start, offset + end, archiveSize)) {
                headers.put("x-amz-sha256-tree-hash", GlacierTreeHash.of(body));
            }
        }
        if (partial) {
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + output.length);
        }
        return new Reply(partial ? 206 : 200, headers, body);
    }

    // ---------------------------------------------------------------- Region-wide settings

    private JsonNode retrievalPolicy(String account, String region) {
        return store.get(account, region + "/policy/data-retrieval").map(record -> (JsonNode) record.path("Policy"))
                .orElseGet(() -> {
                    ObjectNode policy = store.mapper().createObjectNode();
                    policy.putArray("Rules").addObject().put("Strategy", "None");
                    return policy;
                });
    }

    public synchronized ObjectNode getDataRetrievalPolicy(String accountId, String region) {
        String account = account(accountId);
        ObjectNode response = store.mapper().createObjectNode();
        response.set("Policy", retrievalPolicy(account, region).deepCopy());
        return response;
    }

    public synchronized Reply setDataRetrievalPolicy(String accountId, String region, JsonNode body) {
        String account = account(accountId);
        JsonNode rules = body.path("Policy").path("Rules");
        if (!rules.isArray() || rules.size() != 1) {
            throw invalid("A data retrieval policy has exactly one rule.");
        }
        JsonNode rule = rules.get(0);
        String strategy = rule.path("Strategy").asText(null);
        if (!Set.of("BytesPerHour", "FreeTier", "None").contains(String.valueOf(strategy))) {
            throw invalid("Invalid strategy: " + strategy + ". Strategies are BytesPerHour, FreeTier and None.");
        }
        ObjectNode stored = store.mapper().createObjectNode();
        ObjectNode savedRule = stored.putObject("Policy").putArray("Rules").addObject().put("Strategy", strategy);
        if ("BytesPerHour".equals(strategy)) {
            if (!rule.hasNonNull("BytesPerHour") || rule.get("BytesPerHour").asLong() < 1) {
                throw invalid("A BytesPerHour rule needs BytesPerHour of 1 or more.");
            }
            savedRule.put("BytesPerHour", rule.get("BytesPerHour").asLong());
        } else if (rule.hasNonNull("BytesPerHour")) {
            throw invalid("BytesPerHour is given only with the BytesPerHour strategy.");
        }
        store.put(account, region + "/policy/data-retrieval", stored);
        return Reply.of(204);
    }

    public synchronized ObjectNode listProvisionedCapacity(String accountId, String region) {
        String account = account(accountId);
        ObjectNode response = store.mapper().createObjectNode();
        ArrayNode list = response.putArray("ProvisionedCapacityList");
        for (ObjectNode unit : store.list(account, region + "/capacity/")) {
            list.addObject().put("CapacityId", unit.path("CapacityId").asText())
                    .put("StartDate", unit.path("StartDate").asText())
                    .put("ExpirationDate", unit.path("ExpirationDate").asText());
        }
        return response;
    }

    public synchronized Reply purchaseProvisionedCapacity(String accountId, String region) {
        String account = account(accountId);
        int active = 0;
        for (ObjectNode unit : store.list(account, region + "/capacity/")) {
            if (Instant.parse(unit.path("ExpirationDate").asText()).isAfter(Instant.now())) {
                active++;
            }
        }
        if (active >= MAX_CAPACITY) {
            throw limit("An account can have at most " + MAX_CAPACITY + " provisioned capacity units.");
        }
        String capacityId = id(18);
        Instant now = Instant.now();
        ObjectNode unit = store.mapper().createObjectNode();
        unit.put("CapacityId", capacityId).put("StartDate", time(now))
                .put("ExpirationDate", time(now.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()))
                .put("_seq", System.nanoTime());
        store.put(account, region + "/capacity/" + capacityId, unit);
        return new Reply(201, Map.of("x-amz-capacity-id", capacityId), null);
    }

    // ---------------------------------------------------------------- helpers

    private static int pageSize(String text, int defaultValue, int max) {
        if (text == null || text.isEmpty()) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            value = 0;
        }
        if (value < 1 || value > max) {
            throw invalid("Invalid limit: " + text + ". The limit is 1 to " + max + ".");
        }
        return value;
    }

    private ObjectNode page(List<ObjectNode> items, String marker, int max, String idField, String listField,
                            List<String> fields) {
        int start = 0;
        if (marker != null && !marker.isEmpty()) {
            start = -1;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).path(idField).asText().equals(marker)) {
                    start = i;
                }
            }
            if (start < 0) {
                throw invalid("Invalid marker: " + marker);
            }
        }
        ObjectNode response = store.mapper().createObjectNode();
        ArrayNode list = response.putArray(listField);
        int end = Math.min(items.size(), start + max);
        for (int i = start; i < end; i++) {
            if (fields == null) {
                list.add(items.get(i));
            } else {
                ObjectNode item = list.addObject();
                for (String field : fields) {
                    item.set(field, items.get(i).get(field));
                }
            }
        }
        if (end < items.size()) {
            response.put("Marker", items.get(end).path(idField).asText());
        } else {
            response.putNull("Marker");
        }
        return response;
    }

    /** A random identifier of {@code bytes} bytes, base64url-encoded without padding. */
    private static String id(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }
}
