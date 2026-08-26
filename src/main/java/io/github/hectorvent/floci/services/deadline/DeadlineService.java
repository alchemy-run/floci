package io.github.hectorvent.floci.services.deadline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.deadline.model.DeadlineAggregation;
import io.github.hectorvent.floci.services.deadline.model.DeadlineBudget;
import io.github.hectorvent.floci.services.deadline.model.DeadlineBudget.BudgetAction;
import io.github.hectorvent.floci.services.deadline.model.DeadlineFarm;
import io.github.hectorvent.floci.services.deadline.model.DeadlineJob;
import io.github.hectorvent.floci.services.deadline.model.DeadlineJob.DeadlineStep;
import io.github.hectorvent.floci.services.deadline.model.DeadlineJob.DeadlineTask;
import io.github.hectorvent.floci.services.deadline.model.DeadlineQueue;
import io.github.hectorvent.floci.services.deadline.model.DeadlineStorageProfile;
import io.github.hectorvent.floci.services.deadline.model.DeadlineStorageProfile.FileSystemLocation;
import io.github.hectorvent.floci.services.deadline.model.Monitor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * AWS Deadline Cloud restJson1 — farms, queues, jobs, steps, tasks, search,
 * and sessions-statistics aggregation. Jobs stay READY; no workers run.
 */
@ApplicationScoped
public class DeadlineService {

    static final String SERVICE = "deadline";
    private static final String CREATED_BY = "floci";
    private static final double DEFAULT_COST_SCALE = 1.0;
    private static final Duration MAX_BUDGET_WINDOW = Duration.ofDays(120);

    private final StorageBackend<String, DeadlineFarm> farms;
    private final StorageBackend<String, DeadlineQueue> queues;
    private final StorageBackend<String, DeadlineJob> jobs;
    private final StorageBackend<String, DeadlineAggregation> aggregations;
    private final StorageBackend<String, DeadlineStorageProfile> storageProfiles;
    private final StorageBackend<String, DeadlineBudget> budgets;
    private final StorageBackend<String, Monitor> monitors;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public DeadlineService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("deadline", "deadline-farms.json",
                        new TypeReference<Map<String, DeadlineFarm>>() {
                        }),
                storageFactory.create("deadline", "deadline-queues.json",
                        new TypeReference<Map<String, DeadlineQueue>>() {
                        }),
                storageFactory.create("deadline", "deadline-jobs.json",
                        new TypeReference<Map<String, DeadlineJob>>() {
                        }),
                storageFactory.create("deadline", "deadline-aggregations.json",
                        new TypeReference<Map<String, DeadlineAggregation>>() {
                        }),
                storageFactory.create("deadline", "deadline-storage-profiles.json",
                        new TypeReference<Map<String, DeadlineStorageProfile>>() {
                        }),
                storageFactory.create("deadline", "deadline-budgets.json",
                        new TypeReference<Map<String, DeadlineBudget>>() {
                        }),
                storageFactory.create("deadline", "deadline-monitors.json",
                        new TypeReference<Map<String, Monitor>>() {
                        }),
                regionResolver, objectMapper);
    }

    DeadlineService(
            StorageBackend<String, DeadlineFarm> farms,
            StorageBackend<String, DeadlineQueue> queues,
            StorageBackend<String, DeadlineJob> jobs,
            StorageBackend<String, DeadlineAggregation> aggregations,
            StorageBackend<String, DeadlineStorageProfile> storageProfiles,
            StorageBackend<String, DeadlineBudget> budgets,
            StorageBackend<String, Monitor> monitors,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.farms = farms;
        this.queues = queues;
        this.jobs = jobs;
        this.aggregations = aggregations;
        this.storageProfiles = storageProfiles;
        this.budgets = budgets;
        this.monitors = monitors;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized DeadlineFarm createFarm(String region, JsonNode request, String clientTokenHeader) {
        requireObject(request);
        String displayName = requireText(request, "displayName");
        String account = regionResolver.getAccountId();
        String clientToken = firstNonBlank(clientTokenHeader, optionalText(request, "clientToken"));
        if (clientToken != null) {
            for (DeadlineFarm existing : farms.scan(key -> key.startsWith(prefix(account, region)))) {
                if (clientToken.equals(existing.getClientToken())) {
                    return existing;
                }
            }
        }
        String now = now();
        DeadlineFarm farm = new DeadlineFarm();
        farm.setFarmId(id("farm"));
        farm.setDisplayName(displayName);
        farm.setDescription(optionalText(request, "description"));
        farm.setKmsKeyArn(optionalText(request, "kmsKeyArn"));
        farm.setCostScaleFactor(request.hasNonNull("costScaleFactor")
                ? request.get("costScaleFactor").asDouble() : DEFAULT_COST_SCALE);
        farm.setCreatedAt(now);
        farm.setCreatedBy(CREATED_BY);
        farm.setClientToken(clientToken);
        farm.setRegion(region);
        farm.setAccountId(account);
        farm.setTags(readTags(request));
        farms.put(farmKey(account, region, farm.getFarmId()), farm);
        return farm;
    }

    public DeadlineFarm getFarm(String region, String farmId) {
        return requireFarm(region, farmId);
    }

    public synchronized DeadlineFarm updateFarm(String region, String farmId, JsonNode request) {
        requireObject(request);
        DeadlineFarm farm = requireFarm(region, farmId);
        boolean changed = false;
        if (request.hasNonNull("displayName")) {
            farm.setDisplayName(requireText(request, "displayName"));
            changed = true;
        }
        if (request.has("description")) {
            farm.setDescription(textOrNull(request, "description"));
            changed = true;
        }
        if (request.hasNonNull("costScaleFactor")) {
            farm.setCostScaleFactor(request.get("costScaleFactor").asDouble());
            changed = true;
        }
        if (changed) {
            farm.setUpdatedAt(now());
            farm.setUpdatedBy(CREATED_BY);
            farms.put(farmKey(regionResolver.getAccountId(), region, farm.getFarmId()), farm);
        }
        return farm;
    }

    public synchronized void deleteFarm(String region, String farmId) {
        DeadlineFarm farm = requireFarm(region, farmId);
        String account = regionResolver.getAccountId();
        if (!queuesForFarm(account, region, farm.getFarmId()).isEmpty()
                || !storageProfilesForFarm(account, region, farm.getFarmId()).isEmpty()
                || !budgetsForFarm(account, region, farm.getFarmId()).isEmpty()) {
            throw new AwsException(
                    "ConflictException",
                    "This farm still contains some queues, fleets, storage profiles, or budgets.",
                    409,
                    Map.of(
                            "reason", "RESOURCE_IN_USE",
                            "resourceId", farmId,
                            "resourceType", "farm"));
        }
        farms.delete(farmKey(account, region, farm.getFarmId()));
    }

    public List<DeadlineFarm> listFarms(String region) {
        String account = regionResolver.getAccountId();
        List<DeadlineFarm> result = farms.scan(key -> key.startsWith(prefix(account, region)));
        result.sort(Comparator.comparing(DeadlineFarm::getDisplayName, Comparator.nullsLast(String::compareTo))
                .thenComparing(DeadlineFarm::getFarmId));
        return result;
    }

    public synchronized DeadlineQueue createQueue(String region, String farmId, JsonNode request,
                                                 String clientTokenHeader) {
        requireObject(request);
        DeadlineFarm farm = requireFarm(region, farmId);
        String displayName = requireText(request, "displayName");
        String account = regionResolver.getAccountId();
        String clientToken = firstNonBlank(clientTokenHeader, optionalText(request, "clientToken"));
        if (clientToken != null) {
            for (DeadlineQueue existing : queuesForFarm(account, region, farm.getFarmId())) {
                if (clientToken.equals(existing.getClientToken())) {
                    return existing;
                }
            }
        }
        String now = now();
        DeadlineQueue queue = new DeadlineQueue();
        queue.setFarmId(farm.getFarmId());
        queue.setQueueId(id("queue"));
        queue.setDisplayName(displayName);
        queue.setDescription(optionalText(request, "description"));
        queue.setStatus("IDLE");
        queue.setDefaultBudgetAction(request.hasNonNull("defaultBudgetAction")
                ? request.get("defaultBudgetAction").asText() : "NONE");
        queue.setJobAttachmentSettings(objectOrNull(request, "jobAttachmentSettings"));
        queue.setRoleArn(optionalText(request, "roleArn"));
        queue.setJobRunAsUser(objectOrNull(request, "jobRunAsUser"));
        queue.setRequiredFileSystemLocationNames(readStringList(request, "requiredFileSystemLocationNames"));
        queue.setAllowedStorageProfileIds(readStringList(request, "allowedStorageProfileIds"));
        queue.setSchedulingConfiguration(objectOrNull(request, "schedulingConfiguration"));
        queue.setCreatedAt(now);
        queue.setCreatedBy(CREATED_BY);
        queue.setClientToken(clientToken);
        queue.setRegion(region);
        queue.setAccountId(account);
        queue.setTags(readTags(request));
        queues.put(queueKey(account, region, farm.getFarmId(), queue.getQueueId()), queue);
        return queue;
    }

    public DeadlineQueue getQueue(String region, String farmId, String queueId) {
        requireFarm(region, farmId);
        return requireQueue(region, farmId, queueId);
    }

    public synchronized DeadlineQueue updateQueue(String region, String farmId, String queueId, JsonNode request) {
        requireObject(request);
        requireFarm(region, farmId);
        DeadlineQueue queue = requireQueue(region, farmId, queueId);
        boolean changed = false;
        if (request.hasNonNull("displayName")) {
            queue.setDisplayName(requireText(request, "displayName"));
            changed = true;
        }
        if (request.has("description")) {
            queue.setDescription(textOrNull(request, "description"));
            changed = true;
        }
        if (request.hasNonNull("defaultBudgetAction")) {
            queue.setDefaultBudgetAction(request.get("defaultBudgetAction").asText());
            changed = true;
        }
        if (request.has("jobAttachmentSettings")) {
            queue.setJobAttachmentSettings(objectOrNull(request, "jobAttachmentSettings"));
            changed = true;
        }
        if (request.has("roleArn")) {
            queue.setRoleArn(textOrNull(request, "roleArn"));
            changed = true;
        }
        if (request.has("jobRunAsUser")) {
            queue.setJobRunAsUser(objectOrNull(request, "jobRunAsUser"));
            changed = true;
        }
        if (request.has("schedulingConfiguration")) {
            queue.setSchedulingConfiguration(objectOrNull(request, "schedulingConfiguration"));
            changed = true;
        }
        List<String> profilesToAdd = readStringList(request, "allowedStorageProfileIdsToAdd");
        List<String> profilesToRemove = readStringList(request, "allowedStorageProfileIdsToRemove");
        if (!profilesToAdd.isEmpty() || !profilesToRemove.isEmpty()) {
            applyStringDelta(queue.getAllowedStorageProfileIds(), profilesToAdd, profilesToRemove);
            changed = true;
        }
        List<String> namesToAdd = readStringList(request, "requiredFileSystemLocationNamesToAdd");
        List<String> namesToRemove = readStringList(request, "requiredFileSystemLocationNamesToRemove");
        if (!namesToAdd.isEmpty() || !namesToRemove.isEmpty()) {
            applyStringDelta(queue.getRequiredFileSystemLocationNames(), namesToAdd, namesToRemove);
            changed = true;
        }
        if (changed) {
            queue.setUpdatedAt(now());
            queue.setUpdatedBy(CREATED_BY);
            queues.put(queueKey(regionResolver.getAccountId(), region, farmId, queueId), queue);
        }
        return queue;
    }

    public synchronized void deleteQueue(String region, String farmId, String queueId) {
        requireFarm(region, farmId);
        DeadlineQueue queue = requireQueue(region, farmId, queueId);
        String account = regionResolver.getAccountId();
        for (DeadlineJob job : jobsForQueue(account, region, farmId, queueId)) {
            jobs.delete(jobKey(account, region, farmId, queueId, job.getJobId()));
        }
        queues.delete(queueKey(account, region, farmId, queue.getQueueId()));
    }

    public List<DeadlineQueue> listQueues(String region, String farmId) {
        DeadlineFarm farm = requireFarm(region, farmId);
        List<DeadlineQueue> result = queuesForFarm(regionResolver.getAccountId(), region, farm.getFarmId());
        result.sort(Comparator.comparing(DeadlineQueue::getDisplayName, Comparator.nullsLast(String::compareTo))
                .thenComparing(DeadlineQueue::getQueueId));
        return result;
    }

    public synchronized DeadlineJob createJob(String region, String farmId, String queueId, JsonNode request,
                                             String clientTokenHeader) {
        requireObject(request);
        requireFarm(region, farmId);
        DeadlineQueue queue = requireQueue(region, farmId, queueId);
        if (!request.hasNonNull("priority")) {
            throw new AwsException("ValidationException", "priority is required", 400);
        }
        String account = regionResolver.getAccountId();
        String clientToken = firstNonBlank(clientTokenHeader, optionalText(request, "clientToken"));
        if (clientToken != null) {
            for (DeadlineJob existing : jobsForQueue(account, region, farmId, queueId)) {
                if (clientToken.equals(existing.getClientToken())) {
                    return existing;
                }
            }
        }
        String now = now();
        DeadlineJob job = new DeadlineJob();
        job.setFarmId(queue.getFarmId());
        job.setQueueId(queue.getQueueId());
        job.setJobId(id("job"));
        job.setPriority(request.get("priority").asInt());
        job.setLifecycleStatus("CREATE_COMPLETE");
        job.setLifecycleStatusMessage("Job created.");
        job.setTaskRunStatus("READY");
        job.setCreatedAt(now);
        job.setCreatedBy(CREATED_BY);
        job.setClientToken(clientToken);
        job.setTags(readTags(request));
        applyTemplate(job, request, now);
        jobs.put(jobKey(account, region, farmId, queueId, job.getJobId()), job);
        return job;
    }

    public DeadlineJob getJob(String region, String farmId, String queueId, String jobId) {
        requireFarm(region, farmId);
        requireQueue(region, farmId, queueId);
        return requireJob(region, farmId, queueId, jobId);
    }

    public List<DeadlineJob> listJobs(String region, String farmId, String queueId) {
        requireFarm(region, farmId);
        requireQueue(region, farmId, queueId);
        List<DeadlineJob> result = jobsForQueue(regionResolver.getAccountId(), region, farmId, queueId);
        result.sort(Comparator.comparing(DeadlineJob::getCreatedAt).thenComparing(DeadlineJob::getJobId));
        return result;
    }

    public synchronized DeadlineJob updateJob(String region, String farmId, String queueId, String jobId,
                                             JsonNode request) {
        requireObject(request);
        requireFarm(region, farmId);
        requireQueue(region, farmId, queueId);
        DeadlineJob job = requireJob(region, farmId, queueId, jobId);
        boolean changed = false;
        if (request.hasNonNull("priority")) {
            job.setPriority(request.get("priority").asInt());
            changed = true;
        }
        if (request.hasNonNull("name")) {
            job.setName(request.get("name").asText());
            changed = true;
        }
        if (request.has("description")) {
            job.setDescription(textOrNull(request, "description"));
            changed = true;
        }
        if (request.hasNonNull("targetTaskRunStatus")) {
            job.setTargetTaskRunStatus(request.get("targetTaskRunStatus").asText());
            changed = true;
        }
        if (changed) {
            job.setUpdatedAt(now());
            job.setUpdatedBy(CREATED_BY);
            job.setLifecycleStatus("UPDATE_SUCCEEDED");
            job.setLifecycleStatusMessage("Job updated.");
            jobs.put(jobKey(regionResolver.getAccountId(), region, farmId, queueId, jobId), job);
        }
        return job;
    }

    public List<DeadlineJob> searchJobs(String region, String farmId, JsonNode request) {
        requireFarm(region, farmId);
        List<String> queueIds = readStringList(request, "queueIds");
        String account = regionResolver.getAccountId();
        List<DeadlineJob> result = new ArrayList<>();
        for (DeadlineJob job : jobs.scan(key -> key.startsWith(prefix(account, region) + farmId + ":"))) {
            if (queueIds.isEmpty() || queueIds.contains(job.getQueueId())) {
                result.add(job);
            }
        }
        result.sort(Comparator.comparing(DeadlineJob::getCreatedAt).thenComparing(DeadlineJob::getJobId));
        return result;
    }

    public List<DeadlineStep> listSteps(String region, String farmId, String queueId, String jobId) {
        return requireJob(region, farmId, queueId, jobId).getSteps();
    }

    public DeadlineStep getStep(String region, String farmId, String queueId, String jobId, String stepId) {
        DeadlineJob job = requireJob(region, farmId, queueId, jobId);
        return requireStep(job, stepId);
    }

    public List<DeadlineTask> listTasks(String region, String farmId, String queueId, String jobId, String stepId) {
        DeadlineJob job = requireJob(region, farmId, queueId, jobId);
        return requireStep(job, stepId).getTasks();
    }

    public DeadlineTask getTask(String region, String farmId, String queueId, String jobId, String stepId,
                                String taskId) {
        DeadlineJob job = requireJob(region, farmId, queueId, jobId);
        DeadlineStep step = requireStep(job, stepId);
        return requireTask(job, step, taskId);
    }

    public synchronized void updateTask(String region, String farmId, String queueId, String jobId, String stepId,
                                        String taskId, JsonNode request) {
        requireObject(request);
        String target = requireText(request, "targetRunStatus");
        DeadlineJob job = requireJob(region, farmId, queueId, jobId);
        DeadlineStep step = requireStep(job, stepId);
        DeadlineTask task = requireTask(job, step, taskId);
        String now = now();
        task.setTargetRunStatus(target);
        task.setRunStatus(target);
        task.setUpdatedAt(now);
        task.setUpdatedBy(CREATED_BY);
        step.setTaskRunStatus(target);
        step.setTargetTaskRunStatus(target);
        step.setUpdatedAt(now);
        step.setUpdatedBy(CREATED_BY);
        jobs.put(jobKey(regionResolver.getAccountId(), region, farmId, queueId, jobId), job);
    }

    public synchronized void updateStep(String region, String farmId, String queueId, String jobId, String stepId,
                                        JsonNode request) {
        requireObject(request);
        String target = requireText(request, "targetTaskRunStatus");
        DeadlineJob job = requireJob(region, farmId, queueId, jobId);
        DeadlineStep step = requireStep(job, stepId);
        String now = now();
        step.setTargetTaskRunStatus(target);
        step.setTaskRunStatus(target);
        step.setUpdatedAt(now);
        step.setUpdatedBy(CREATED_BY);
        for (DeadlineTask task : step.getTasks()) {
            task.setTargetRunStatus(target);
            task.setRunStatus(target);
            task.setUpdatedAt(now);
            task.setUpdatedBy(CREATED_BY);
        }
        jobs.put(jobKey(regionResolver.getAccountId(), region, farmId, queueId, jobId), job);
    }

    public List<DeadlineStep> searchSteps(String region, String farmId, JsonNode request) {
        requireFarm(region, farmId);
        List<String> queueIds = readStringList(request, "queueIds");
        String jobId = optionalText(request, "jobId");
        List<DeadlineStep> result = new ArrayList<>();
        for (DeadlineJob job : jobsForFarm(region, farmId)) {
            if (!queueIds.isEmpty() && !queueIds.contains(job.getQueueId())) {
                continue;
            }
            if (jobId != null && !jobId.equals(job.getJobId())) {
                continue;
            }
            result.addAll(job.getSteps());
        }
        return result;
    }

    public record TaskHit(DeadlineJob job, DeadlineStep step, DeadlineTask task) {
    }

    public List<TaskHit> searchTasks(String region, String farmId, JsonNode request) {
        requireFarm(region, farmId);
        List<String> queueIds = readStringList(request, "queueIds");
        String jobId = optionalText(request, "jobId");
        List<TaskHit> result = new ArrayList<>();
        for (DeadlineJob job : jobsForFarm(region, farmId)) {
            if (!queueIds.isEmpty() && !queueIds.contains(job.getQueueId())) {
                continue;
            }
            if (jobId != null && !jobId.equals(job.getJobId())) {
                continue;
            }
            for (DeadlineStep step : job.getSteps()) {
                for (DeadlineTask task : step.getTasks()) {
                    result.add(new TaskHit(job, step, task));
                }
            }
        }
        return result;
    }

    public DeadlineAggregation startAggregation(String region, String farmId, JsonNode request) {
        requireObject(request);
        requireFarm(region, farmId);
        DeadlineAggregation aggregation = new DeadlineAggregation();
        aggregation.setAggregationId(id("aggregation"));
        aggregation.setFarmId(farmId);
        aggregation.setStatus("COMPLETED");
        aggregations.put(aggregation.getAggregationId(), aggregation);
        return aggregation;
    }

    public DeadlineAggregation getAggregation(String region, String farmId, String aggregationId) {
        requireFarm(region, farmId);
        return aggregations.get(aggregationId).orElseThrow(() ->
                notFound("SessionsStatisticsAggregation", aggregationId));
    }

    public synchronized DeadlineStorageProfile createStorageProfile(
            String region, String farmId, JsonNode request) {
        requireObject(request);
        DeadlineFarm farm = requireFarm(region, farmId);
        String account = regionResolver.getAccountId();
        String now = now();
        DeadlineStorageProfile profile = new DeadlineStorageProfile();
        profile.setFarmId(farm.getFarmId());
        profile.setStorageProfileId(id("sp"));
        profile.setDisplayName(requireText(request, "displayName"));
        profile.setOsFamily(requireText(request, "osFamily"));
        profile.setCreatedAt(now);
        profile.setCreatedBy(CREATED_BY);
        profile.setRegion(region);
        profile.setAccountId(account);
        profile.setFileSystemLocations(readLocations(request.get("fileSystemLocations")));
        storageProfiles.put(childKey(account, region, farm.getFarmId(), profile.getStorageProfileId()), profile);
        return profile;
    }

    public DeadlineStorageProfile getStorageProfile(String region, String farmId, String storageProfileId) {
        requireFarm(region, farmId);
        return requireStorageProfile(region, farmId, storageProfileId);
    }

    public synchronized DeadlineStorageProfile updateStorageProfile(
            String region, String farmId, String storageProfileId, JsonNode request) {
        requireObject(request);
        requireFarm(region, farmId);
        DeadlineStorageProfile profile = requireStorageProfile(region, farmId, storageProfileId);
        if (request.hasNonNull("displayName")) {
            profile.setDisplayName(requireText(request, "displayName"));
        }
        if (request.hasNonNull("osFamily")) {
            profile.setOsFamily(requireText(request, "osFamily"));
        }
        List<FileSystemLocation> locations = profile.getFileSystemLocations();
        for (FileSystemLocation location : readLocations(request.get("fileSystemLocationsToRemove"))) {
            locations.removeIf(existing -> existing.key().equals(location.key()));
        }
        for (FileSystemLocation location : readLocations(request.get("fileSystemLocationsToAdd"))) {
            if (locations.stream().noneMatch(existing -> existing.key().equals(location.key()))) {
                locations.add(location);
            }
        }
        profile.setUpdatedAt(now());
        profile.setUpdatedBy(CREATED_BY);
        storageProfiles.put(
                childKey(regionResolver.getAccountId(), region, farmId, profile.getStorageProfileId()), profile);
        return profile;
    }

    public synchronized void deleteStorageProfile(String region, String farmId, String storageProfileId) {
        requireFarm(region, farmId);
        DeadlineStorageProfile profile = requireStorageProfile(region, farmId, storageProfileId);
        String account = regionResolver.getAccountId();
        for (DeadlineQueue queue : queuesForFarm(account, region, farmId)) {
            if (queue.getAllowedStorageProfileIds().contains(profile.getStorageProfileId())) {
                throw new AwsException(
                        "ConflictException",
                        "Storage profile is still referenced by a queue.",
                        409,
                        Map.of(
                                "reason", "RESOURCE_IN_USE",
                                "resourceId", storageProfileId,
                                "resourceType", "storageProfile"));
            }
        }
        storageProfiles.delete(childKey(account, region, farmId, profile.getStorageProfileId()));
    }

    public List<DeadlineStorageProfile> listStorageProfiles(String region, String farmId) {
        DeadlineFarm farm = requireFarm(region, farmId);
        List<DeadlineStorageProfile> result =
                storageProfilesForFarm(regionResolver.getAccountId(), region, farm.getFarmId());
        result.sort(Comparator.comparing(
                DeadlineStorageProfile::getStorageProfileId, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    public synchronized DeadlineBudget createBudget(String region, String farmId, JsonNode request) {
        requireObject(request);
        DeadlineFarm farm = requireFarm(region, farmId);
        JsonNode usage = request.get("usageTrackingResource");
        requireObject(usage);
        String queueId = requireText(usage, "queueId");
        requireQueue(region, farmId, queueId);
        String account = regionResolver.getAccountId();
        String now = now();
        DeadlineBudget budget = new DeadlineBudget();
        budget.setFarmId(farm.getFarmId());
        budget.setBudgetId(id("budget"));
        budget.setQueueId(queueId);
        budget.setDisplayName(requireText(request, "displayName"));
        budget.setDescription(optionalText(request, "description"));
        if (!request.hasNonNull("approximateDollarLimit") || !request.get("approximateDollarLimit").isNumber()) {
            throw new AwsException("ValidationException", "approximateDollarLimit is required", 400);
        }
        budget.setApproximateDollarLimit(request.get("approximateDollarLimit").asDouble());
        budget.setCreatedAt(now);
        budget.setCreatedBy(CREATED_BY);
        budget.setRegion(region);
        budget.setAccountId(account);
        budget.setActions(readActions(request.get("actions")));
        applySchedule(budget, request.get("schedule"), now);
        budget.setTags(readTags(request));
        budgets.put(childKey(account, region, farm.getFarmId(), budget.getBudgetId()), budget);
        return budget;
    }

    public DeadlineBudget getBudget(String region, String farmId, String budgetId) {
        requireFarm(region, farmId);
        return requireBudget(region, farmId, budgetId);
    }

    public synchronized DeadlineBudget updateBudget(
            String region, String farmId, String budgetId, JsonNode request) {
        requireObject(request);
        requireFarm(region, farmId);
        DeadlineBudget budget = requireBudget(region, farmId, budgetId);
        if (request.hasNonNull("displayName")) {
            budget.setDisplayName(requireText(request, "displayName"));
        }
        if (request.has("description")) {
            budget.setDescription(textOrNull(request, "description"));
        }
        if (request.hasNonNull("status")) {
            budget.setStatus(requireText(request, "status"));
        }
        if (request.hasNonNull("approximateDollarLimit")) {
            budget.setApproximateDollarLimit(request.get("approximateDollarLimit").asDouble());
        }
        List<BudgetAction> actions = budget.getActions();
        for (BudgetAction action : readActions(request.get("actionsToRemove"))) {
            actions.removeIf(existing -> existing.key().equals(action.key()));
        }
        for (BudgetAction action : readActions(request.get("actionsToAdd"))) {
            if (actions.stream().noneMatch(existing -> existing.key().equals(action.key()))) {
                actions.add(action);
            }
        }
        if (request.has("schedule") && request.get("schedule") != null && !request.get("schedule").isNull()) {
            applySchedule(budget, request.get("schedule"), budget.getCreatedAt());
        }
        budget.setUpdatedAt(now());
        budget.setUpdatedBy(CREATED_BY);
        budgets.put(childKey(regionResolver.getAccountId(), region, farmId, budget.getBudgetId()), budget);
        return budget;
    }

    public synchronized void deleteBudget(String region, String farmId, String budgetId) {
        requireFarm(region, farmId);
        DeadlineBudget budget = requireBudget(region, farmId, budgetId);
        budgets.delete(childKey(regionResolver.getAccountId(), region, farmId, budget.getBudgetId()));
    }

    public List<DeadlineBudget> listBudgets(String region, String farmId) {
        DeadlineFarm farm = requireFarm(region, farmId);
        List<DeadlineBudget> result = budgetsForFarm(regionResolver.getAccountId(), region, farm.getFarmId());
        result.sort(Comparator.comparing(DeadlineBudget::getBudgetId, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    public Monitor getMonitor(String region, String monitorId) {
        String id = decode(monitorId);
        Monitor monitor = monitors.get(id).orElseThrow(() -> notFound("monitor", id));
        if (!region.equals(monitor.getRegion())) {
            throw notFound("monitor", id);
        }
        return monitor;
    }

    public List<Monitor> listMonitors(String region) {
        List<Monitor> result = new ArrayList<>();
        for (Monitor monitor : monitors.values()) {
            if (region.equals(monitor.getRegion())) {
                result.add(monitor);
            }
        }
        return result;
    }

    public Map<String, String> listTags(String region, String resourceArn) {
        return tagged(region, resourceArn).tags();
    }

    public synchronized void tagResource(String region, String resourceArn, JsonNode request) {
        requireObject(request);
        Tagged tagged = tagged(region, resourceArn);
        tagged.tags().putAll(readTags(request));
        tagged.save();
    }

    public synchronized void untagResource(String region, String resourceArn, List<String> tagKeys) {
        Tagged tagged = tagged(region, resourceArn);
        if (tagKeys != null) {
            for (String key : tagKeys) {
                tagged.tags().remove(key);
            }
        }
        tagged.save();
    }

    private void applyTemplate(DeadlineJob job, JsonNode request, String now) {
        String nameOverride = optionalText(request, "nameOverride");
        String templateText = optionalText(request, "template");
        JsonNode template = parseTemplate(templateText);
        if (nameOverride != null) {
            job.setName(nameOverride);
        } else if (template != null && template.hasNonNull("name")) {
            job.setName(template.get("name").asText());
        } else {
            job.setName("job");
        }
        if (request.has("descriptionOverride")) {
            job.setDescription(textOrNull(request, "descriptionOverride"));
        } else if (template != null && template.hasNonNull("description")) {
            job.setDescription(template.get("description").asText());
        }
        List<DeadlineStep> steps = new ArrayList<>();
        if (template != null && template.has("steps") && template.get("steps").isArray()) {
            for (JsonNode stepNode : template.get("steps")) {
                steps.add(stepFromTemplate(stepNode, now));
            }
        }
        if (steps.isEmpty()) {
            steps.add(stepFromTemplate(objectMapper.createObjectNode().put("name", "Step"), now));
        }
        job.setSteps(steps);
    }

    private DeadlineStep stepFromTemplate(JsonNode stepNode, String now) {
        DeadlineStep step = new DeadlineStep();
        step.setStepId(id("step"));
        step.setName(stepNode.hasNonNull("name") ? stepNode.get("name").asText() : "Step");
        step.setLifecycleStatus("CREATE_COMPLETE");
        step.setTaskRunStatus("READY");
        step.setCreatedAt(now);
        step.setCreatedBy(CREATED_BY);
        DeadlineTask task = new DeadlineTask();
        task.setTaskId(id("task"));
        task.setRunStatus("READY");
        task.setCreatedAt(now);
        task.setCreatedBy(CREATED_BY);
        step.setTasks(List.of(task));
        return step;
    }

    private JsonNode parseTemplate(String templateText) {
        if (templateText == null || templateText.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(templateText);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "template is not valid JSON", 400);
        }
    }

    private DeadlineFarm requireFarm(String region, String farmId) {
        String id = decode(farmId);
        return farms.get(farmKey(regionResolver.getAccountId(), region, id)).orElseThrow(() ->
                notFound("Farm", id));
    }

    private DeadlineQueue requireQueue(String region, String farmId, String queueId) {
        String farm = decode(farmId);
        String queue = decode(queueId);
        return queues.get(queueKey(regionResolver.getAccountId(), region, farm, queue)).orElseThrow(() ->
                notFound("Queue", queue));
    }

    private DeadlineJob requireJob(String region, String farmId, String queueId, String jobId) {
        String farm = decode(farmId);
        String queue = decode(queueId);
        String job = decode(jobId);
        return jobs.get(jobKey(regionResolver.getAccountId(), region, farm, queue, job)).orElseThrow(() ->
                notFound("Job", job));
    }

    private DeadlineStep requireStep(DeadlineJob job, String stepId) {
        String id = decode(stepId);
        return job.getSteps().stream()
                .filter(step -> id.equals(step.getStepId()))
                .findFirst()
                .orElseThrow(() -> notFound("Step", id));
    }

    private DeadlineTask requireTask(DeadlineJob job, DeadlineStep step, String taskId) {
        String id = decode(taskId);
        return step.getTasks().stream()
                .filter(task -> id.equals(task.getTaskId()))
                .findFirst()
                .orElseThrow(() -> notFound("Task", id));
    }

    private List<DeadlineQueue> queuesForFarm(String account, String region, String farmId) {
        String prefix = prefix(account, region) + farmId + ":";
        return queues.scan(key -> key.startsWith(prefix));
    }

    private List<DeadlineJob> jobsForQueue(String account, String region, String farmId, String queueId) {
        String prefix = jobKey(account, region, farmId, queueId, "");
        return jobs.scan(key -> key.startsWith(prefix));
    }

    private List<DeadlineJob> jobsForFarm(String region, String farmId) {
        String prefix = prefix(regionResolver.getAccountId(), region) + farmId + ":";
        return jobs.scan(key -> key.startsWith(prefix));
    }

    private Tagged tagged(String region, String resourceArn) {
        String arn = decode(resourceArn);
        String account = regionResolver.getAccountId();
        String expectedPrefix = "arn:aws:deadline:" + region + ":" + account + ":";
        if (!arn.startsWith(expectedPrefix)) {
            throw notFound("Resource", arn);
        }
        String resource = arn.substring(expectedPrefix.length());
        if (resource.startsWith("farm/") && resource.contains("/queue/")) {
            int queueAt = resource.indexOf("/queue/");
            String farmId = resource.substring("farm/".length(), queueAt);
            String queueId = resource.substring(queueAt + "/queue/".length());
            DeadlineQueue queue = requireQueue(region, farmId, queueId);
            return new Tagged(queue.getTags(), () ->
                    queues.put(queueKey(account, region, farmId, queueId), queue));
        }
        if (resource.startsWith("farm/") && resource.contains("/budget/")) {
            int budgetAt = resource.indexOf("/budget/");
            String farmId = resource.substring("farm/".length(), budgetAt);
            String budgetId = resource.substring(budgetAt + "/budget/".length());
            DeadlineBudget budget = requireBudget(region, farmId, budgetId);
            return new Tagged(budget.getTags(), () ->
                    budgets.put(childKey(account, region, farmId, budgetId), budget));
        }
        if (resource.startsWith("farm/")) {
            String farmId = resource.substring("farm/".length());
            DeadlineFarm farm = requireFarm(region, farmId);
            return new Tagged(farm.getTags(), () ->
                    farms.put(farmKey(account, region, farmId), farm));
        }
        if (resource.startsWith("monitor/")) {
            Monitor monitor = getMonitor(region, resource.substring("monitor/".length()));
            return new Tagged(monitor.getTags(), () -> monitors.put(monitor.getMonitorId(), monitor));
        }
        throw notFound("Resource", arn);
    }

    private record Tagged(Map<String, String> tags, Runnable persist) {
        void save() {
            persist.run();
        }
    }

    private static AwsException notFound(String type, String id) {
        return new AwsException(
                "ResourceNotFoundException",
                type + " " + id + " was not found.",
                404,
                Map.of("resourceId", id, "resourceType", type.toLowerCase(Locale.ROOT)));
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
        }
    }

    private static String requireText(JsonNode request, String field) {
        if (!request.hasNonNull(field) || !request.get(field).isTextual()) {
            throw new AwsException("ValidationException", field + " is required", 400);
        }
        String value = request.get(field).asText();
        if (value.isBlank()) {
            throw new AwsException("ValidationException", field + " is required", 400);
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        String value = request.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private static String textOrNull(JsonNode request, String field) {
        if (!request.has(field) || request.get(field).isNull()) {
            return null;
        }
        return request.get(field).asText();
    }

    private static JsonNode objectOrNull(JsonNode request, String field) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return null;
        }
        return request.get(field);
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (request == null || !request.has("tags") || !request.get("tags").isObject()) {
            return tags;
        }
        request.get("tags").fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && entry.getValue().isTextual()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }

    private static List<String> readStringList(JsonNode request, String field) {
        List<String> values = new ArrayList<>();
        if (request == null || !request.has(field) || !request.get(field).isArray()) {
            return values;
        }
        for (JsonNode node : request.get(field)) {
            if (node != null && node.isTextual()) {
                values.add(node.asText());
            }
        }
        return values;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String prefix(String account, String region) {
        return account + ":" + region + ":";
    }

    private DeadlineStorageProfile requireStorageProfile(String region, String farmId, String storageProfileId) {
        String farm = decode(farmId);
        String profileId = decode(storageProfileId);
        return storageProfiles.get(childKey(regionResolver.getAccountId(), region, farm, profileId))
                .orElseThrow(() -> notFound("storageProfile", profileId));
    }

    private DeadlineBudget requireBudget(String region, String farmId, String budgetId) {
        String farm = decode(farmId);
        String id = decode(budgetId);
        return budgets.get(childKey(regionResolver.getAccountId(), region, farm, id))
                .orElseThrow(() -> notFound("budget", id));
    }

    private List<DeadlineStorageProfile> storageProfilesForFarm(String account, String region, String farmId) {
        String prefix = prefix(account, region) + farmId + ":";
        return storageProfiles.scan(key -> key.startsWith(prefix));
    }

    private List<DeadlineBudget> budgetsForFarm(String account, String region, String farmId) {
        String prefix = prefix(account, region) + farmId + ":";
        return budgets.scan(key -> key.startsWith(prefix));
    }

    private static void applyStringDelta(List<String> current, List<String> add, List<String> remove) {
        current.removeAll(remove);
        for (String value : add) {
            if (!current.contains(value)) {
                current.add(value);
            }
        }
    }

    private static void applySchedule(DeadlineBudget budget, JsonNode schedule, String createdAt) {
        requireObject(schedule);
        JsonNode fixed = schedule.get("fixed");
        requireObject(fixed);
        Instant created = parseTime(createdAt);
        Instant start = parseTime(requireText(fixed, "startTime"));
        Instant end = parseTime(requireText(fixed, "endTime"));
        if (start.isBefore(created)) {
            start = created;
        }
        if (!end.isAfter(start)) {
            throw new AwsException("ValidationException", "schedule.fixed.endTime must be after startTime.", 400);
        }
        if (Duration.between(start, end).compareTo(MAX_BUDGET_WINDOW) > 0) {
            throw new AwsException("ValidationException", "Budget windows cannot exceed 120 days.", 400);
        }
        budget.setStartTime(start.truncatedTo(ChronoUnit.MILLIS).toString());
        budget.setEndTime(end.truncatedTo(ChronoUnit.MILLIS).toString());
    }

    private static Instant parseTime(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException nested) {
                throw new AwsException("ValidationException", "timestamp is not a valid date-time: " + value, 400);
            }
        }
    }

    private static List<FileSystemLocation> readLocations(JsonNode node) {
        List<FileSystemLocation> locations = new ArrayList<>();
        if (node == null || node.isNull()) {
            return locations;
        }
        if (!node.isArray()) {
            throw new AwsException("ValidationException", "fileSystemLocations must be an array.", 400);
        }
        for (JsonNode entry : node) {
            requireObject(entry);
            locations.add(new FileSystemLocation(
                    requireText(entry, "name"),
                    requireText(entry, "path"),
                    requireText(entry, "type")));
        }
        return locations;
    }

    private static List<BudgetAction> readActions(JsonNode node) {
        List<BudgetAction> actions = new ArrayList<>();
        if (node == null || node.isNull()) {
            return actions;
        }
        if (!node.isArray()) {
            throw new AwsException("ValidationException", "actions must be an array.", 400);
        }
        for (JsonNode entry : node) {
            requireObject(entry);
            double threshold = entry.hasNonNull("thresholdPercentage") && entry.get("thresholdPercentage").isNumber()
                    ? entry.get("thresholdPercentage").asDouble()
                    : 0d;
            actions.add(new BudgetAction(
                    requireText(entry, "type"),
                    threshold,
                    optionalText(entry, "description")));
        }
        return actions;
    }

    private static String farmKey(String account, String region, String farmId) {
        return prefix(account, region) + farmId;
    }

    private static String childKey(String account, String region, String farmId, String childId) {
        return prefix(account, region) + farmId + ":" + childId;
    }

    private static String queueKey(String account, String region, String farmId, String queueId) {
        return prefix(account, region) + farmId + ":" + queueId;
    }

    private static String jobKey(String account, String region, String farmId, String queueId, String jobId) {
        return prefix(account, region) + farmId + ":" + queueId + ":" + jobId;
    }
}
