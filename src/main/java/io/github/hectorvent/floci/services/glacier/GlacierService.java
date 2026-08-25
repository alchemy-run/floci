package io.github.hectorvent.floci.services.glacier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glacier.model.GlacierArchive;
import io.github.hectorvent.floci.services.glacier.model.GlacierJob;
import io.github.hectorvent.floci.services.glacier.model.GlacierMultipartUpload;
import io.github.hectorvent.floci.services.glacier.model.GlacierVault;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon S3 Glacier restJson1 — vaults, archives, jobs, and multipart uploads.
 *
 * <p>A missing vault surfaces {@code ResourceNotFoundException} (404) so Alchemy
 * binding probes and DescribeVault wait-until-gone match live AWS.
 */
@ApplicationScoped
public class GlacierService implements Resettable {

    static final String SERVICE = "glacier";
    private static final Pattern VAULT_NAME = Pattern.compile("[a-zA-Z0-9._-]{1,255}");
    private static final long MIN_PART_SIZE = 1_048_576L;
    private static final long MAX_PART_SIZE = 4L * 1024 * 1024 * 1024;

    private final StorageBackend<String, GlacierVault> vaults;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public GlacierService(StorageFactory factory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.vaults = factory.create("glacier", "glacier-vaults.json",
                new TypeReference<Map<String, GlacierVault>>() {
                });
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    GlacierService(StorageBackend<String, GlacierVault> vaults, RegionResolver regionResolver,
                   ObjectMapper objectMapper) {
        this.vaults = vaults;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        vaults.clear();
    }

    public synchronized ObjectNode createVault(String region, String vaultName) {
        validateVaultName(vaultName);
        String key = key(region, vaultName);
        GlacierVault existing = vaults.get(key).orElse(null);
        if (existing == null) {
            GlacierVault vault = new GlacierVault();
            vault.setVaultName(vaultName);
            vault.setAccountId(regionResolver.getAccountId());
            vault.setRegion(region);
            vault.setVaultArn(vaultArn(region, vaultName));
            vault.setCreationDate(Instant.now().toString());
            vaults.put(key, vault);
            existing = vault;
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("location", "/" + existing.getAccountId() + "/vaults/" + vaultName);
        response.put("vaultArn", existing.getVaultArn());
        return response;
    }

    public synchronized ObjectNode describeVault(String region, String vaultName) {
        GlacierVault vault = requireVault(region, vaultName);
        return toVaultNode(vault);
    }

    public synchronized void deleteVault(String region, String vaultName) {
        GlacierVault vault = vaults.get(key(region, vaultName)).orElse(null);
        if (vault == null) {
            return;
        }
        if ("Locked".equals(vault.getLockState())) {
            throw glacierError("InvalidParameterValueException",
                    "Vault has a completed lock and cannot be deleted.", 400);
        }
        if (vault.getNumberOfArchives() > 0 || !vault.getArchives().isEmpty()) {
            throw glacierError("InvalidParameterValueException",
                    "Vault not empty.", 400);
        }
        vaults.delete(key(region, vaultName));
    }

    public synchronized ObjectNode listVaults(String region) {
        ArrayNode list = objectMapper.createArrayNode();
        for (GlacierVault vault : vaults.values()) {
            if (region.equals(vault.getRegion())) {
                list.add(toVaultNode(vault));
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("VaultList", list);
        return response;
    }

    public synchronized ObjectNode listJobs(String region, String vaultName, String statuscode, String completed) {
        GlacierVault vault = requireVault(region, vaultName);
        ArrayNode jobs = objectMapper.createArrayNode();
        for (GlacierJob job : vault.getJobs().values()) {
            if (statuscode != null && !statuscode.isBlank() && !statuscode.equals(job.getStatusCode())) {
                continue;
            }
            if (completed != null && !completed.isBlank()) {
                boolean wantCompleted = Boolean.parseBoolean(completed);
                if (job.isCompleted() != wantCompleted) {
                    continue;
                }
            }
            jobs.add(toJobNode(job, vault));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("JobList", jobs);
        return response;
    }

    public synchronized ObjectNode describeJob(String region, String vaultName, String jobId) {
        GlacierVault vault = requireVault(region, vaultName);
        GlacierJob job = vault.getJobs().get(jobId);
        if (job == null) {
            throw glacierError("ResourceNotFoundException", "Job not found: " + jobId, 404);
        }
        return toJobNode(job, vault);
    }

    public synchronized GlacierJob getJob(String region, String vaultName, String jobId) {
        GlacierVault vault = requireVault(region, vaultName);
        GlacierJob job = vault.getJobs().get(jobId);
        if (job == null) {
            throw glacierError("ResourceNotFoundException", "Job not found: " + jobId, 404);
        }
        return job;
    }

    public synchronized ObjectNode initiateJob(String region, String vaultName, JsonNode body) {
        GlacierVault vault = requireVault(region, vaultName);
        String type = textOrNull(body, "Type");
        if (type == null && body != null && body.has("JobParameters")) {
            type = textOrNull(body.get("JobParameters"), "Type");
        }
        if (type == null) {
            type = textOrNull(body, "type");
        }
        if ("inventory-retrieval".equalsIgnoreCase(type) && vault.getLastInventoryDate() == null) {
            throw glacierError("InvalidParameterValueException",
                    "The vault inventory is not available yet.", 400);
        }
        GlacierJob job = new GlacierJob();
        job.setJobId(newId());
        job.setAction(type == null ? "InventoryRetrieval" : type);
        job.setCreationDate(Instant.now().toString());
        job.setCompleted(true);
        job.setStatusCode("Succeeded");
        job.setStatusMessage("Succeeded");
        job.setCompletionDate(job.getCreationDate());
        job.setJobOutput("[]");
        vault.getJobs().put(job.getJobId(), job);
        vaults.put(key(region, vaultName), vault);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobId", job.getJobId());
        response.put("location", "/" + vault.getAccountId() + "/vaults/" + vaultName + "/jobs/" + job.getJobId());
        return response;
    }

    public synchronized ObjectNode uploadArchive(String region, String vaultName, String description,
                                                 String checksum, byte[] body) {
        GlacierVault vault = requireVault(region, vaultName);
        byte[] payload = body == null ? new byte[0] : body;
        if (checksum == null || checksum.isBlank()) {
            throw glacierError("MissingParameterValueException",
                    "The SHA256 tree hash checksum is required.", 400);
        }
        String actual = sha256Hex(payload);
        if (!checksum.equalsIgnoreCase(actual)) {
            throw glacierError("InvalidParameterValueException",
                    "Checksum mismatch.", 400);
        }
        GlacierArchive archive = new GlacierArchive();
        archive.setArchiveId(newId());
        archive.setDescription(description);
        archive.setChecksum(actual);
        archive.setSizeInBytes(payload.length);
        archive.setCreationDate(Instant.now().toString());
        archive.setBody(payload);
        vault.getArchives().put(archive.getArchiveId(), archive);
        vault.setNumberOfArchives(vault.getArchives().size());
        vault.setSizeInBytes(vault.getSizeInBytes() + payload.length);
        vaults.put(key(region, vaultName), vault);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("archiveId", archive.getArchiveId());
        response.put("checksum", actual);
        response.put("location", "/" + vault.getAccountId() + "/vaults/" + vaultName
                + "/archives/" + archive.getArchiveId());
        return response;
    }

    public synchronized void deleteArchive(String region, String vaultName, String archiveId) {
        GlacierVault vault = requireVault(region, vaultName);
        GlacierArchive archive = vault.getArchives().remove(archiveId);
        if (archive == null) {
            throw glacierError("ResourceNotFoundException", "Archive not found: " + archiveId, 404);
        }
        vault.setNumberOfArchives(vault.getArchives().size());
        vault.setSizeInBytes(Math.max(0, vault.getSizeInBytes() - archive.getSizeInBytes()));
        vaults.put(key(region, vaultName), vault);
    }

    public synchronized ObjectNode listMultipartUploads(String region, String vaultName) {
        GlacierVault vault = requireVault(region, vaultName);
        ArrayNode uploads = objectMapper.createArrayNode();
        for (GlacierMultipartUpload upload : vault.getUploads().values()) {
            uploads.add(toUploadNode(upload, vault));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("UploadsList", uploads);
        return response;
    }

    public synchronized ObjectNode initiateMultipartUpload(String region, String vaultName,
                                                           String description, String partSize) {
        GlacierVault vault = requireVault(region, vaultName);
        long size = parsePartSize(partSize);
        GlacierMultipartUpload upload = new GlacierMultipartUpload();
        upload.setUploadId(newId());
        upload.setArchiveDescription(description);
        upload.setPartSizeInBytes(size);
        upload.setCreationDate(Instant.now().toString());
        vault.getUploads().put(upload.getUploadId(), upload);
        vaults.put(key(region, vaultName), vault);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("uploadId", upload.getUploadId());
        response.put("location", "/" + vault.getAccountId() + "/vaults/" + vaultName
                + "/multipart-uploads/" + upload.getUploadId());
        return response;
    }

    public synchronized ObjectNode listParts(String region, String vaultName, String uploadId) {
        GlacierVault vault = requireVault(region, vaultName);
        GlacierMultipartUpload upload = requireUpload(vault, uploadId);
        ArrayNode parts = objectMapper.createArrayNode();
        for (GlacierMultipartUpload.GlacierPart part : upload.getParts()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("RangeInBytes", part.getRangeInBytes());
            node.put("SHA256TreeHash", part.getSha256TreeHash());
            parts.add(node);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("MultipartUploadId", upload.getUploadId());
        response.put("VaultARN", vault.getVaultArn());
        response.put("ArchiveDescription", upload.getArchiveDescription());
        response.put("PartSizeInBytes", upload.getPartSizeInBytes());
        response.put("CreationDate", upload.getCreationDate());
        response.set("Parts", parts);
        return response;
    }

    public synchronized ObjectNode uploadMultipartPart(String region, String vaultName, String uploadId,
                                                       String range, String checksum, byte[] body) {
        GlacierVault vault = requireVault(region, vaultName);
        GlacierMultipartUpload upload = requireUpload(vault, uploadId);
        byte[] payload = body == null ? new byte[0] : body;
        String hash = checksum == null || checksum.isBlank() ? sha256Hex(payload) : checksum;
        upload.getParts().add(new GlacierMultipartUpload.GlacierPart(
                range == null ? "bytes 0-" + Math.max(0, payload.length - 1) + "/*" : range,
                hash));
        vaults.put(key(region, vaultName), vault);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("checksum", hash);
        return response;
    }

    public synchronized ObjectNode completeMultipartUpload(String region, String vaultName, String uploadId) {
        GlacierVault vault = requireVault(region, vaultName);
        GlacierMultipartUpload upload = requireUpload(vault, uploadId);
        vault.getUploads().remove(uploadId);
        GlacierArchive archive = new GlacierArchive();
        archive.setArchiveId(newId());
        archive.setDescription(upload.getArchiveDescription());
        archive.setChecksum("0".repeat(64));
        archive.setSizeInBytes(0);
        archive.setCreationDate(Instant.now().toString());
        vault.getArchives().put(archive.getArchiveId(), archive);
        vault.setNumberOfArchives(vault.getArchives().size());
        vaults.put(key(region, vaultName), vault);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("archiveId", archive.getArchiveId());
        response.put("checksum", archive.getChecksum());
        response.put("location", "/" + vault.getAccountId() + "/vaults/" + vaultName
                + "/archives/" + archive.getArchiveId());
        return response;
    }

    public synchronized void abortMultipartUpload(String region, String vaultName, String uploadId) {
        GlacierVault vault = requireVault(region, vaultName);
        requireUpload(vault, uploadId);
        vault.getUploads().remove(uploadId);
        vaults.put(key(region, vaultName), vault);
    }

    public synchronized ObjectNode listTags(String region, String vaultName) {
        GlacierVault vault = requireVault(region, vaultName);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode tags = response.putObject("Tags");
        vault.getTags().forEach(tags::put);
        return response;
    }

    public synchronized void addTags(String region, String vaultName, JsonNode body) {
        GlacierVault vault = requireVault(region, vaultName);
        JsonNode tags = body == null ? null : body.get("Tags");
        if (tags != null && tags.isObject()) {
            tags.fields().forEachRemaining(entry -> vault.getTags().put(entry.getKey(), entry.getValue().asText()));
        }
        vaults.put(key(region, vaultName), vault);
    }

    public synchronized void removeTags(String region, String vaultName, JsonNode body) {
        GlacierVault vault = requireVault(region, vaultName);
        JsonNode keys = body == null ? null : body.get("TagKeys");
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                vault.getTags().remove(key.asText());
            }
        }
        vaults.put(key(region, vaultName), vault);
    }

    public synchronized ObjectNode getVaultNotifications(String region, String vaultName) {
        GlacierVault vault = requireVault(region, vaultName);
        if (vault.getNotificationSnsTopic() == null) {
            throw glacierError("ResourceNotFoundException",
                    "No notification configuration found.", 404);
        }
        ObjectNode config = objectMapper.createObjectNode();
        config.put("SNSTopic", vault.getNotificationSnsTopic());
        ArrayNode events = config.putArray("Events");
        for (String event : vault.getNotificationEvents()) {
            events.add(event);
        }
        // Distilled restJson1 Object.assigns the body onto the output struct, so
        // keep AWS's payload fields and also fill the HttpPayload member name.
        ObjectNode response = config.deepCopy();
        response.set("vaultNotificationConfig", config);
        return response;
    }

    public synchronized void setVaultNotifications(String region, String vaultName, JsonNode body) {
        GlacierVault vault = requireVault(region, vaultName);
        JsonNode config = body == null ? null : (body.has("vaultNotificationConfig")
                ? body.get("vaultNotificationConfig") : body);
        if (config == null || config.isNull()) {
            throw glacierError("MissingParameterValueException",
                    "Notification configuration is required.", 400);
        }
        vault.setNotificationSnsTopic(textOrNull(config, "SNSTopic"));
        vault.getNotificationEvents().clear();
        JsonNode events = config.get("Events");
        if (events != null && events.isArray()) {
            for (JsonNode event : events) {
                vault.getNotificationEvents().add(event.asText());
            }
        }
        vaults.put(key(region, vaultName), vault);
    }

    public synchronized void deleteVaultNotifications(String region, String vaultName) {
        GlacierVault vault = requireVault(region, vaultName);
        vault.setNotificationSnsTopic(null);
        vault.setNotificationEvents(List.of());
        vaults.put(key(region, vaultName), vault);
    }

    public synchronized ObjectNode getVaultAccessPolicy(String region, String vaultName) {
        GlacierVault vault = requireVault(region, vaultName);
        if (vault.getAccessPolicy() == null) {
            throw glacierError("ResourceNotFoundException",
                    "No vault access policy found.", 404);
        }
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("Policy", vault.getAccessPolicy());
        ObjectNode response = policy.deepCopy();
        response.set("policy", policy);
        return response;
    }

    public synchronized void setVaultAccessPolicy(String region, String vaultName, JsonNode body) {
        GlacierVault vault = requireVault(region, vaultName);
        JsonNode policy = body == null ? null : (body.has("policy") ? body.get("policy") : body);
        String document = null;
        if (policy != null && policy.has("Policy")) {
            JsonNode value = policy.get("Policy");
            document = value.isTextual() ? value.asText() : value.toString();
        } else if (policy != null && policy.isTextual()) {
            document = policy.asText();
        } else if (body != null && !body.isNull()) {
            document = body.toString();
        }
        if (document == null) {
            throw glacierError("MissingParameterValueException", "Policy is required.", 400);
        }
        vault.setAccessPolicy(document);
        vaults.put(key(region, vaultName), vault);
    }

    public synchronized void deleteVaultAccessPolicy(String region, String vaultName) {
        GlacierVault vault = requireVault(region, vaultName);
        vault.setAccessPolicy(null);
        vaults.put(key(region, vaultName), vault);
    }

    public synchronized ObjectNode initiateVaultLock(String region, String vaultName, JsonNode body) {
        GlacierVault vault = requireVault(region, vaultName);
        if ("Locked".equals(vault.getLockState())) {
            throw glacierError("InvalidParameterValueException",
                    "Vault lock is already complete.", 400);
        }
        if (vault.getLockState() != null) {
            throw glacierError("InvalidParameterValueException",
                    "Vault lock is already in progress.", 400);
        }
        String document = policyDocument(body);
        if (document == null || document.isBlank()) {
            throw glacierError("MissingParameterValueException", "Policy is required.", 400);
        }
        Instant now = Instant.now();
        vault.setLockPolicy(document);
        vault.setLockState("InProgress");
        vault.setLockId(newId());
        vault.setLockCreationDate(now.toString());
        vault.setLockExpirationDate(now.plus(24, ChronoUnit.HOURS).toString());
        vaults.put(key(region, vaultName), vault);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("lockId", vault.getLockId());
        return response;
    }

    public synchronized ObjectNode getVaultLock(String region, String vaultName) {
        GlacierVault vault = requireVault(region, vaultName);
        if (vault.getLockState() == null) {
            throw glacierError("ResourceNotFoundException",
                    "No vault lock found.", 404);
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Policy", vault.getLockPolicy());
        node.put("State", vault.getLockState());
        if (vault.getLockCreationDate() != null) {
            node.put("CreationDate", vault.getLockCreationDate());
        }
        if (vault.getLockExpirationDate() != null) {
            node.put("ExpirationDate", vault.getLockExpirationDate());
        }
        return node;
    }

    public synchronized void abortVaultLock(String region, String vaultName) {
        GlacierVault vault = requireVault(region, vaultName);
        if (vault.getLockState() == null) {
            throw glacierError("ResourceNotFoundException",
                    "No vault lock found.", 404);
        }
        if ("Locked".equals(vault.getLockState())) {
            throw glacierError("InvalidParameterValueException",
                    "Vault lock is complete and cannot be aborted.", 400);
        }
        vault.setLockPolicy(null);
        vault.setLockState(null);
        vault.setLockId(null);
        vault.setLockCreationDate(null);
        vault.setLockExpirationDate(null);
        vaults.put(key(region, vaultName), vault);
    }

    public synchronized void completeVaultLock(String region, String vaultName, String lockId) {
        GlacierVault vault = requireVault(region, vaultName);
        if (vault.getLockState() == null) {
            throw glacierError("ResourceNotFoundException",
                    "No vault lock found.", 404);
        }
        if (lockId == null || !lockId.equals(vault.getLockId())) {
            throw glacierError("InvalidParameterValueException",
                    "Lock id does not match.", 400);
        }
        vault.setLockState("Locked");
        vault.setLockExpirationDate(null);
        vaults.put(key(region, vaultName), vault);
    }

    private GlacierVault requireVault(String region, String vaultName) {
        return vaults.get(key(region, vaultName)).orElseThrow(() ->
                glacierError("ResourceNotFoundException",
                        "Vault not found for vault name: " + vaultName, 404));
    }

    private GlacierMultipartUpload requireUpload(GlacierVault vault, String uploadId) {
        GlacierMultipartUpload upload = vault.getUploads().get(uploadId);
        if (upload == null) {
            throw glacierError("ResourceNotFoundException", "Upload not found: " + uploadId, 404);
        }
        return upload;
    }

    private void validateVaultName(String vaultName) {
        if (vaultName == null || vaultName.isBlank()) {
            throw glacierError("MissingParameterValueException", "Vault name is required.", 400);
        }
        if (!VAULT_NAME.matcher(vaultName).matches()) {
            throw glacierError("InvalidParameterValueException",
                    "Invalid vault name: " + vaultName, 400);
        }
    }

    private long parsePartSize(String partSize) {
        if (partSize == null || partSize.isBlank()) {
            throw glacierError("MissingParameterValueException", "Part size is required.", 400);
        }
        long size;
        try {
            size = Long.parseLong(partSize);
        } catch (NumberFormatException e) {
            throw glacierError("InvalidParameterValueException", "Invalid part size: " + partSize, 400);
        }
        if (size < MIN_PART_SIZE || size > MAX_PART_SIZE || (size & (size - 1)) != 0) {
            throw glacierError("InvalidParameterValueException",
                    "Part size must be a power of two megabytes between 1 MB and 4 GB.", 400);
        }
        return size;
    }

    private ObjectNode toVaultNode(GlacierVault vault) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("VaultARN", vault.getVaultArn());
        node.put("VaultName", vault.getVaultName());
        node.put("CreationDate", vault.getCreationDate());
        if (vault.getLastInventoryDate() != null) {
            node.put("LastInventoryDate", vault.getLastInventoryDate());
        }
        node.put("NumberOfArchives", vault.getNumberOfArchives());
        node.put("SizeInBytes", vault.getSizeInBytes());
        return node;
    }

    private ObjectNode toJobNode(GlacierJob job, GlacierVault vault) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("JobId", job.getJobId());
        node.put("Action", job.getAction());
        node.put("VaultARN", vault.getVaultArn());
        node.put("CreationDate", job.getCreationDate());
        node.put("Completed", job.isCompleted());
        node.put("StatusCode", job.getStatusCode());
        node.put("StatusMessage", job.getStatusMessage());
        if (job.getCompletionDate() != null) {
            node.put("CompletionDate", job.getCompletionDate());
        }
        if (job.getArchiveId() != null) {
            node.put("ArchiveId", job.getArchiveId());
        }
        return node;
    }

    private ObjectNode toUploadNode(GlacierMultipartUpload upload, GlacierVault vault) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("MultipartUploadId", upload.getUploadId());
        node.put("VaultARN", vault.getVaultArn());
        node.put("ArchiveDescription", upload.getArchiveDescription());
        node.put("PartSizeInBytes", upload.getPartSizeInBytes());
        node.put("CreationDate", upload.getCreationDate());
        return node;
    }

    private String vaultArn(String region, String vaultName) {
        return "arn:aws:glacier:" + region + ":" + regionResolver.getAccountId() + ":vaults/" + vaultName;
    }

    private static String key(String region, String vaultName) {
        return region + ":" + vaultName;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    static String sha256Hex(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body == null ? new byte[0] : body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String policyDocument(JsonNode body) {
        JsonNode policy = body == null ? null : (body.has("policy") ? body.get("policy") : body);
        if (policy != null && policy.has("Policy")) {
            JsonNode value = policy.get("Policy");
            return value.isTextual() ? value.asText() : value.toString();
        }
        if (policy != null && policy.isTextual()) {
            return policy.asText();
        }
        return null;
    }

    static AwsException glacierError(String code, String message, int status) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("code", code);
        extra.put("type", status >= 500 ? "Server" : "Client");
        return new AwsException(code, message, status, extra);
    }

    static byte[] utf8(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }
}
