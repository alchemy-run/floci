package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.backup.model.BackupJob;
import io.github.hectorvent.floci.services.backup.model.BackupPlan;
import io.github.hectorvent.floci.services.backup.model.BackupRule;
import io.github.hectorvent.floci.services.backup.model.BackupSelection;
import io.github.hectorvent.floci.services.backup.model.BackupVault;
import io.github.hectorvent.floci.services.backup.model.Lifecycle;
import io.github.hectorvent.floci.services.backup.model.RecoveryPoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class BackupService {

    private static final Logger LOG = Logger.getLogger(BackupService.class);

    private static final List<String> SUPPORTED_RESOURCE_TYPES = List.of(
            "S3", "RDS", "DynamoDB", "EFS", "EC2", "EBS",
            "Aurora", "DocumentDB", "Neptune", "FSx", "VirtualMachine"
    );

    private final StorageBackend<String, BackupVault>     vaultStore;
    private final StorageBackend<String, BackupPlan>      planStore;
    private final StorageBackend<String, BackupSelection> selectionStore;
    private final StorageBackend<String, BackupJob>       jobStore;
    private final StorageBackend<String, RecoveryPoint>   recoveryStore;

    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final StorageBackend<String, Map<String, Object>> restoreJobStore;
    private final StorageBackend<String, Map<String, Object>> copyJobStore;

    @Inject
    public BackupService(StorageFactory storageFactory, RegionResolver regionResolver,
                         ObjectMapper objectMapper) {
        this.vaultStore     = storageFactory.create("backup", "backup-vaults.json",     new TypeReference<>() {});
        this.planStore      = storageFactory.create("backup", "backup-plans.json",      new TypeReference<>() {});
        this.selectionStore = storageFactory.create("backup", "backup-selections.json", new TypeReference<>() {});
        this.jobStore       = storageFactory.create("backup", "backup-jobs.json",       new TypeReference<>() {});
        this.recoveryStore  = storageFactory.create("backup", "backup-recovery-points.json", new TypeReference<>() {});
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.restoreJobStore = storageFactory.create("backup", "backup-restore-jobs.json", new TypeReference<>() {});
        this.copyJobStore = storageFactory.create("backup", "backup-copy-jobs.json", new TypeReference<>() {});
    }

    // ── Vault ──────────────────────────────────────────────────────────────────

    public BackupVault createBackupVault(String vaultName, String encryptionKeyArn,
                                         String creatorRequestId, Map<String, String> tags,
                                         String region) {
        String key = vaultKey(region, vaultName);
        if (vaultStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Backup vault already exists: " + vaultName, 400);
        }
        BackupVault vault = new BackupVault();
        vault.setBackupVaultName(vaultName);
        vault.setBackupVaultArn(regionResolver.buildArn("backup", region, "backup-vault:" + vaultName));
        vault.setEncryptionKeyArn(encryptionKeyArn);
        vault.setCreationDate(Instant.now().getEpochSecond());
        vault.setCreatorRequestId(creatorRequestId);
        vault.setNumberOfRecoveryPoints(0);
        vault.setTags(tags);
        vaultStore.put(key, vault);
        LOG.infov("Created backup vault {0} in {1}", vaultName, region);
        return vault;
    }

    public BackupVault describeBackupVault(String vaultName, String region) {
        return vaultStore.get(vaultKey(region, vaultName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup vault not found: " + vaultName, 404));
    }

    public void deleteBackupVault(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        if (vault.getNumberOfRecoveryPoints() > 0) {
            throw new AwsException("InvalidRequestException",
                    "Non-empty backup vault cannot be deleted: " + vaultName, 400);
        }
        vaultStore.delete(vaultKey(region, vaultName));
    }

    public List<BackupVault> listBackupVaults(String region) {
        String prefix = region + ":";
        return vaultStore.scan(k -> k.startsWith(prefix));
    }

    public String getVaultPolicy(String vaultName, String region) {
        String policy = describeBackupVault(vaultName, region).getAccessPolicy();
        if (policy == null) {
            throw new AwsException("ResourceNotFoundException", "No access policy configured for vault: " + vaultName, 400);
        }
        return policy;
    }

    public void putVaultPolicy(String vaultName, String policy, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        try {
            JsonNode document = objectMapper.readTree(policy == null ? "" : policy);
            if (document == null || !document.isObject() || !document.has("Statement")) {
                throw new AwsException("InvalidParameterValueException", "Policy must be a JSON policy document", 400);
            }
        } catch (IOException e) {
            throw new AwsException("InvalidParameterValueException", "Policy must be valid JSON", 400);
        }
        vault.setAccessPolicy(policy);
        vaultStore.put(vaultKey(region, vaultName), vault);
    }

    public void deleteVaultPolicy(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        vault.setAccessPolicy(null);
        vaultStore.put(vaultKey(region, vaultName), vault);
    }

    public Map<String, Object> getVaultNotifications(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        if (vault.getSnsTopicArn() == null) {
            throw new AwsException("ResourceNotFoundException", "No notification configuration for vault: " + vaultName, 400);
        }
        return Map.of("BackupVaultName", vaultName, "BackupVaultArn", vault.getBackupVaultArn(),
                "SNSTopicArn", vault.getSnsTopicArn(), "BackupVaultEvents", vault.getBackupVaultEvents());
    }

    public void putVaultNotifications(String vaultName, String topicArn, List<String> events, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        if (topicArn == null || !topicArn.startsWith("arn:") || events == null || events.isEmpty()) {
            throw new AwsException("InvalidParameterValueException", "SNSTopicArn and BackupVaultEvents are required", 400);
        }
        vault.setSnsTopicArn(topicArn);
        vault.setBackupVaultEvents(new ArrayList<>(events));
        vaultStore.put(vaultKey(region, vaultName), vault);
    }

    public void deleteVaultNotifications(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        vault.setSnsTopicArn(null);
        vault.setBackupVaultEvents(null);
        vaultStore.put(vaultKey(region, vaultName), vault);
    }

    // ── Plan ───────────────────────────────────────────────────────────────────

    public BackupPlan createBackupPlan(String planName, List<BackupRule> rules,
                                       String creatorRequestId, String region) {
        String planId = UUID.randomUUID().toString();
        BackupPlan plan = new BackupPlan();
        plan.setBackupPlanId(planId);
        plan.setBackupPlanArn(regionResolver.buildArn("backup", region, "backup-plan:" + planId));
        plan.setBackupPlanName(planName);
        plan.setCreationDate(Instant.now().getEpochSecond());
        plan.setVersionId(shortId());
        assignRuleIds(rules);
        plan.setRules(rules);
        planStore.put(planId, plan);
        return plan;
    }

    public BackupPlan getBackupPlan(String planId) {
        return planStore.get(planId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup plan not found: " + planId, 404));
    }

    public BackupPlan updateBackupPlan(String planId, String planName, List<BackupRule> rules) {
        BackupPlan plan = getBackupPlan(planId);
        if (planName != null) {
            plan.setBackupPlanName(planName);
        }
        assignRuleIds(rules);
        plan.setRules(rules);
        plan.setVersionId(shortId());
        planStore.put(planId, plan);
        return plan;
    }

    public void deleteBackupPlan(String planId) {
        getBackupPlan(planId);
        long selectionCount = selectionStore.scan(k -> true).stream()
                .filter(s -> planId.equals(s.getBackupPlanId()))
                .count();
        if (selectionCount > 0) {
            throw new AwsException("InvalidRequestException",
                    "Backup plan has active selections and cannot be deleted", 400);
        }
        planStore.delete(planId);
    }

    public List<BackupPlan> listBackupPlans() {
        return planStore.scan(k -> true);
    }

    // ── Selection ──────────────────────────────────────────────────────────────

    public BackupSelection createBackupSelection(String planId, String selectionName,
                                                  String iamRoleArn, List<String> resources,
                                                  List<String> notResources, String creatorRequestId) {
        getBackupPlan(planId);
        String selectionId = UUID.randomUUID().toString();
        BackupSelection selection = new BackupSelection();
        selection.setSelectionId(selectionId);
        selection.setSelectionName(selectionName);
        selection.setBackupPlanId(planId);
        selection.setIamRoleArn(iamRoleArn);
        selection.setResources(resources);
        selection.setNotResources(notResources);
        selection.setCreationDate(Instant.now().getEpochSecond());
        selection.setCreatorRequestId(creatorRequestId);
        selectionStore.put(selectionId, selection);
        return selection;
    }

    public BackupSelection getBackupSelection(String planId, String selectionId) {
        BackupSelection sel = selectionStore.get(selectionId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup selection not found: " + selectionId, 404));
        if (!planId.equals(sel.getBackupPlanId())) {
            throw new AwsException("ResourceNotFoundException", "Backup selection not found in plan: " + planId, 404);
        }
        return sel;
    }

    public void deleteBackupSelection(String planId, String selectionId) {
        getBackupSelection(planId, selectionId);
        selectionStore.delete(selectionId);
    }

    public List<BackupSelection> listBackupSelections(String planId) {
        getBackupPlan(planId);
        return selectionStore.scan(k -> true).stream()
                .filter(s -> planId.equals(s.getBackupPlanId()))
                .toList();
    }

    // ── Job ────────────────────────────────────────────────────────────────────

    public BackupJob startBackupJob(String vaultName, String resourceArn, String iamRoleArn,
                                     Lifecycle lifecycle, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        if (resourceArn == null || resourceArn.isBlank() || iamRoleArn == null || iamRoleArn.isBlank()) {
            throw new AwsException("MissingParameterValueException", "ResourceArn and IamRoleArn are required", 400);
        }

        String jobId = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();

        BackupJob job = new BackupJob();
        job.setBackupJobId(jobId);
        job.setBackupVaultName(vaultName);
        job.setBackupVaultArn(vault.getBackupVaultArn());
        job.setResourceArn(resourceArn);
        job.setResourceType(inferResourceType(resourceArn));
        job.setIamRoleArn(iamRoleArn);
        job.setState("FAILED");
        job.setStatusMessage("Backup execution is not supported by this emulator; no resource data was copied.");
        job.setCompletionDate(now);
        job.setPercentDone("0.0");
        job.setCreationDate(now);
        job.setStartBy(now + 3600L);
        job.setAccountId(regionResolver.getAccountId());
        jobStore.put(jobId, job);

        return job;
    }

    public BackupJob describeBackupJob(String jobId) {
        return jobStore.get(jobId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup job not found: " + jobId, 404));
    }

    public BackupJob describeBackupJob(String jobId, String region) {
        BackupJob job = describeBackupJob(jobId);
        if (!inRegion(job.getBackupVaultArn(), region)) {
            throw new AwsException("ResourceNotFoundException", "Backup job not found: " + jobId, 400);
        }
        return job;
    }

    public List<BackupJob> listBackupJobs(String region) {
        return jobStore.scan(k -> true).stream().filter(job -> inRegion(job.getBackupVaultArn(), region)).toList();
    }

    public void stopBackupJob(String jobId, String region) {
        BackupJob job = describeBackupJob(jobId, region);
        String state = job.getState();
        if ("COMPLETED".equals(state) || "ABORTED".equals(state) || "FAILED".equals(state)) {
            throw new AwsException("InvalidRequestException",
                    "Job cannot be stopped in state: " + state, 400);
        }
        job.setState("ABORTED");
        job.setStatusMessage("Job stop requested");
        job.setCompletionDate(Instant.now().getEpochSecond());
        jobStore.put(jobId, job);
    }

    // ── Recovery Point ─────────────────────────────────────────────────────────

    public RecoveryPoint describeRecoveryPoint(String vaultName, String recoveryPointArn, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        if (recoveryPointArn == null || recoveryPointArn.isBlank()) {
            throw new AwsException("MissingParameterValueException", "RecoveryPointArn is required", 400);
        }
        return recoveryStore.get(recoveryPointArn)
                .filter(rp -> vault.getBackupVaultArn().equals(rp.getBackupVaultArn()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Recovery point not found: " + recoveryPointArn, 404));
    }

    public List<RecoveryPoint> listRecoveryPointsByBackupVault(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        return recoveryStore.scan(k -> true).stream()
                .filter(rp -> vault.getBackupVaultArn().equals(rp.getBackupVaultArn()))
                .toList();
    }

    public void deleteRecoveryPoint(String vaultName, String recoveryPointArn, String region) {
        describeRecoveryPoint(vaultName, recoveryPointArn, region);
        recoveryStore.delete(recoveryPointArn);
        decrementVaultCount(vaultName, region);
    }

    public Map<String, Object> getRecoveryPointRestoreMetadata(String vaultName, String arn, String region) {
        RecoveryPoint point = describeRecoveryPoint(vaultName, arn, region);
        if (point.getRestoreMetadata() == null) {
            throw new AwsException("InvalidRequestException", "Restore metadata is unavailable for this recovery point", 400);
        }
        return Map.of("BackupVaultArn", point.getBackupVaultArn(), "RecoveryPointArn", arn,
                "ResourceType", point.getResourceType(), "RestoreMetadata", point.getRestoreMetadata());
    }

    public List<Map<String, Object>> listRecoveryPointsByResource(String resourceArn, String region) {
        return regionalRecoveryPoints(region).stream()
                .filter(point -> resourceArn.equals(point.getResourceArn()))
                .map(point -> {
                    Map<String, Object> result = asMap(point);
                    Object size = result.remove("BackupSizeInBytes");
                    if (size != null) {
                        result.put("BackupSizeBytes", size);
                    }
                    result.remove("RestoreMetadata");
                    return result;
                }).toList();
    }

    public List<Map<String, Object>> listProtectedResources(String region) {
        Map<String, RecoveryPoint> latest = new LinkedHashMap<>();
        for (RecoveryPoint point : regionalRecoveryPoints(region)) {
            if (!"COMPLETED".equals(point.getStatus()) || point.getResourceArn() == null) {
                continue;
            }
            latest.merge(point.getResourceArn(), point,
                    (left, right) -> left.getCreationDate() >= right.getCreationDate() ? left : right);
        }
        return latest.values().stream().map(point -> {
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("ResourceArn", point.getResourceArn());
            resource.put("ResourceType", point.getResourceType());
            resource.put("LastBackupTime", point.getCreationDate());
            resource.put("LastBackupVaultArn", point.getBackupVaultArn());
            resource.put("LastRecoveryPointArn", point.getRecoveryPointArn());
            return resource;
        }).toList();
    }

    public Map<String, Object> describeProtectedResource(String resourceArn, String region) {
        return listProtectedResources(region).stream()
                .filter(resource -> resourceArn.equals(resource.get("ResourceArn"))).findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Protected resource not found: " + resourceArn, 400));
    }

    private List<RecoveryPoint> regionalRecoveryPoints(String region) {
        return recoveryStore.scan(k -> true).stream()
                .filter(point -> inRegion(point.getBackupVaultArn(), region)).toList();
    }

    public Map<String, Object> describeRestoreJob(String id, String region) {
        return restoreJobStore.get(id).filter(job -> jobInRegion(job, region))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Restore job not found: " + id, 400));
    }

    public Map<String, Object> describeCopyJob(String id, String region) {
        return copyJobStore.get(id).filter(job -> jobInRegion(job, region))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Copy job not found: " + id, 400));
    }

    public List<Map<String, Object>> listRestoreJobs(String region) {
        return restoreJobStore.scan(k -> true).stream().filter(job -> jobInRegion(job, region)).toList();
    }

    public List<Map<String, Object>> listCopyJobs(String region) {
        return copyJobStore.scan(k -> true).stream().filter(job -> jobInRegion(job, region)).toList();
    }

    public Map<String, Object> getRestoreJobMetadata(String id, String region) {
        Map<String, Object> job = describeRestoreJob(id, region);
        if (!job.containsKey("Metadata")) {
            throw new AwsException("InvalidRequestException", "Restore job metadata is unavailable", 400);
        }
        return Map.of("RestoreJobId", id, "Metadata", job.get("Metadata"));
    }

    public void putRestoreValidationResult(String id, String status, String message, String region) {
        Map<String, Object> job = new LinkedHashMap<>(describeRestoreJob(id, region));
        if (!List.of("SUCCESSFUL", "FAILED", "TIMED_OUT", "VALIDATING").contains(status == null ? "" : status)) {
            throw new AwsException("InvalidParameterValueException", "Invalid validation status", 400);
        }
        JsonNode creator = objectMapper.valueToTree(job.get("CreatedBy"));
        if (creator == null || !creator.hasNonNull("RestoreTestingPlanArn") || !"COMPLETED".equals(job.get("Status"))) {
            throw new AwsException("InvalidRequestException", "Only completed restore testing jobs accept validation results", 400);
        }
        job.put("ValidationStatus", status);
        if (message != null) {
            job.put("ValidationStatusMessage", message);
        }
        restoreJobStore.put(id, job);
    }

    public Map<String, Object> startRestoreJob(String recoveryPointArn, String region) {
        if (recoveryPointArn == null || recoveryPointArn.isBlank()) {
            throw new AwsException("MissingParameterValueException", "RecoveryPointArn is required", 400);
        }
        recoveryStore.get(recoveryPointArn).filter(point -> inRegion(point.getBackupVaultArn(), region))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Recovery point not found: " + recoveryPointArn, 400));
        throw new AwsException("InvalidRequestException", "Restore execution is not supported by this emulator", 400);
    }

    public Map<String, Object> startCopyJob(String vaultName, String recoveryPointArn, String region) {
        describeRecoveryPoint(vaultName, recoveryPointArn, region);
        throw new AwsException("InvalidRequestException", "Copy execution is not supported by this emulator", 400);
    }

    private boolean jobInRegion(Map<String, Object> job, String region) {
        for (String field : List.of("BackupVaultArn", "SourceBackupVaultArn", "RecoveryPointArn")) {
            if (job.get(field) instanceof String arn) {
                return inRegion(arn, region);
            }
        }
        return region.equals(job.get("Region"));
    }

    private static boolean inRegion(String arn, String region) {
        return arn != null && arn.startsWith("arn:") && arn.split(":", 6).length == 6
                && region.equals(arn.split(":", 6)[3]);
    }

    public Map<String, Object> asMap(Object value) {
        return objectMapper.convertValue(value, new TypeReference<>() {});
    }

    public Map<String, Object> page(String listName, List<?> values, Map<String, String> query, String region) {
        List<Map<String, Object>> filtered = values.stream().map(this::asMap)
                .filter(value -> matches(value, query))
                .sorted(Comparator.comparing(value -> objectMapper.valueToTree(value).toString())).toList();
        int size = 1000;
        int offset = 0;
        String scope = regionResolver.getAccountId() + ":" + region + ":" + listName + ":";
        try {
            if (query.containsKey("maxResults")) {
                size = Integer.parseInt(query.get("maxResults"));
            }
            if (query.containsKey("nextToken")) {
                String decoded = new String(Base64.getUrlDecoder().decode(query.get("nextToken")), StandardCharsets.UTF_8);
                if (!decoded.startsWith(scope)) {
                    throw new IllegalArgumentException("Invalid token scope");
                }
                offset = Integer.parseInt(decoded.substring(scope.length()));
            }
            if (size < 1 || size > 1000 || offset < 0 || offset > filtered.size()) {
                throw new IllegalArgumentException("Invalid page bounds");
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValueException", "Invalid maxResults or nextToken", 400);
        }
        int end = Math.min(filtered.size(), offset + size);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> value : filtered.subList(offset, end)) {
            Map<String, Object> item = new LinkedHashMap<>(value);
            item.remove("Metadata");
            item.remove("RestoreMetadata");
            item.remove("Region");
            items.add(item);
        }
        result.put(listName, items);
        if (end < filtered.size()) {
            result.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((scope + end).getBytes(StandardCharsets.UTF_8)));
        }
        return result;
    }

    private boolean matches(Map<String, Object> value, Map<String, String> query) {
        for (Map.Entry<String, String> entry : query.entrySet()) {
            String key = entry.getKey();
            if (List.of("maxResults", "nextToken", "managedByAWSBackupOnly").contains(key)) {
                continue;
            }
            if (key.endsWith("Before") || key.endsWith("After")) {
                String dateField = key.startsWith("created") ? "CreationDate" : "CompletionDate";
                Object actual = value.get(dateField);
                double bound;
                try {
                    bound = Double.parseDouble(entry.getValue());
                } catch (NumberFormatException e) {
                    throw new AwsException("InvalidParameterValueException", "Invalid timestamp filter", 400);
                }
                if (!(actual instanceof Number number) || (key.endsWith("Before")
                        ? number.doubleValue() >= bound : number.doubleValue() <= bound)) {
                    return false;
                }
                continue;
            }
            String field = switch (key) {
                case "destinationVaultArn" -> "DestinationBackupVaultArn";
                case "backupVaultAccountId" -> "AccountId";
                default -> Character.toUpperCase(key.charAt(0)) + key.substring(1);
            };
            Object actual = "backupVaultAccountId".equals(key) ? regionResolver.getAccountId() : value.get(field);
            if ("backupPlanId".equals(key) || "restoreTestingPlanArn".equals(key)) {
                JsonNode createdBy = objectMapper.valueToTree(value.get("CreatedBy"));
                actual = createdBy == null ? null : createdBy.path(field).asText(null);
            }
            if ("accountId".equals(key) && "*".equals(entry.getValue())) {
                continue;
            }
            if (!entry.getValue().equals(actual == null ? null : actual.toString())) {
                return false;
            }
        }
        return true;
    }

    public void saveSelection(BackupSelection selection) {
        selectionStore.put(selection.getSelectionId(), selection);
    }

    // ── Tags ───────────────────────────────────────────────────────────────────

    public Map<String, String> listTags(String resourceArn) {
        return findTagsByArn(resourceArn);
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        applyTags(resourceArn, tags);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        removeTags(resourceArn, tagKeys);
    }

    // ── Supported resource types ───────────────────────────────────────────────

    public List<String> getSupportedResourceTypes() {
        return SUPPORTED_RESOURCE_TYPES;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void decrementVaultCount(String vaultName, String region) {
        vaultStore.get(vaultKey(region, vaultName)).ifPresent(vault -> {
            vault.setNumberOfRecoveryPoints(Math.max(0, vault.getNumberOfRecoveryPoints() - 1));
            vaultStore.put(vaultKey(region, vaultName), vault);
        });
    }

    private Map<String, String> findTagsByArn(String arn) {
        Optional<BackupVault> vault = vaultStore.scan(k -> true).stream()
                .filter(v -> arn.equals(v.getBackupVaultArn()))
                .findFirst();
        if (vault.isPresent()) {
            return vault.get().getTags();
        }
        Optional<BackupPlan> plan = planStore.scan(k -> true).stream()
                .filter(p -> arn.equals(p.getBackupPlanArn()))
                .findFirst();
        if (plan.isPresent()) {
            return plan.get().getTags();
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private void applyTags(String arn, Map<String, String> newTags) {
        Optional<BackupVault> vaultOpt = vaultStore.scan(k -> true).stream()
                .filter(v -> arn.equals(v.getBackupVaultArn()))
                .findFirst();
        if (vaultOpt.isPresent()) {
            BackupVault vault = vaultOpt.get();
            vault.getTags().putAll(newTags);
            vaultStore.put(vaultKey(vault), vault);
            return;
        }
        Optional<BackupPlan> planOpt = planStore.scan(k -> true).stream()
                .filter(p -> arn.equals(p.getBackupPlanArn())).findFirst();
        if (planOpt.isPresent()) {
            BackupPlan plan = planOpt.get();
            plan.getTags().putAll(newTags);
            planStore.put(plan.getBackupPlanId(), plan);
            return;
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private void removeTags(String arn, List<String> tagKeys) {
        Optional<BackupVault> vaultOpt = vaultStore.scan(k -> true).stream()
                .filter(v -> arn.equals(v.getBackupVaultArn()))
                .findFirst();
        if (vaultOpt.isPresent()) {
            BackupVault vault = vaultOpt.get();
            tagKeys.forEach(vault.getTags()::remove);
            vaultStore.put(vaultKey(vault), vault);
            return;
        }
        Optional<BackupPlan> planOpt = planStore.scan(k -> true).stream()
                .filter(p -> arn.equals(p.getBackupPlanArn())).findFirst();
        if (planOpt.isPresent()) {
            BackupPlan plan = planOpt.get();
            tagKeys.forEach(plan.getTags()::remove);
            planStore.put(plan.getBackupPlanId(), plan);
            return;
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private static void assignRuleIds(List<BackupRule> rules) {
        if (rules == null) {
            return;
        }
        for (BackupRule rule : rules) {
            if (rule.getRuleId() == null) {
                rule.setRuleId(UUID.randomUUID().toString());
            }
        }
    }

    private static String inferResourceType(String resourceArn) {
        if (resourceArn == null) {
            return null;
        }
        if (resourceArn.contains(":s3:::")) {
            return "S3";
        }
        if (resourceArn.contains(":rds:")) {
            return "RDS";
        }
        if (resourceArn.contains(":dynamodb:")) {
            return "DynamoDB";
        }
        if (resourceArn.contains(":ec2:")) {
            return "EC2";
        }
        if (resourceArn.contains(":elasticfilesystem:")) {
            return "EFS";
        }
        return null;
    }

    private static String vaultKey(String region, String vaultName) {
        return region + ":" + vaultName;
    }

    private static String vaultKey(BackupVault vault) {
        String region = AwsArnUtils.parse(vault.getBackupVaultArn()).region();
        return region + ":" + vault.getBackupVaultName();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
