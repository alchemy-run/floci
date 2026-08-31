package io.github.hectorvent.floci.services.mediaconvert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.mediaconvert.model.JobsQuery;
import io.github.hectorvent.floci.services.mediaconvert.model.MediaConvertJob;
import io.github.hectorvent.floci.services.mediaconvert.model.MediaConvertJobTemplate;
import io.github.hectorvent.floci.services.mediaconvert.model.MediaConvertPreset;
import io.github.hectorvent.floci.services.mediaconvert.model.MediaConvertQueue;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWS Elemental MediaConvert restJson1 — queues, presets, job templates, jobs, and tags.
 *
 * <p>Public paths are {@code /2017-08-29/queues}, {@code /2017-08-29/presets},
 * {@code /2017-08-29/jobTemplates}, {@code /2017-08-29/jobs} and
 * {@code /2017-08-29/tags/{arn}}. Requests are signed as {@code mediaconvert}.
 * Tag APIs live under the versioned prefix (not {@code /tags/{arn}}).
 */
@ApplicationScoped
public class MediaConvertService {

    static final String SERVICE = "mediaconvert";
    static final String DEFAULT_QUEUE = "Default";
    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\w-]+$");
    private static final Pattern ROLE_ARN =
            Pattern.compile("^arn:aws:iam::(\\d{12}):role/(.+)$");
    private static final Set<String> QUEUE_STATUSES = Set.of("ACTIVE", "PAUSED");
    private static final Set<String> PRICING_PLANS = Set.of("ON_DEMAND", "RESERVED");
    private static final Set<String> TERMINAL_JOBS = Set.of("COMPLETE", "ERROR", "CANCELED");

    private final StorageBackend<String, MediaConvertQueue> queues;
    private final StorageBackend<String, MediaConvertPreset> presets;
    private final StorageBackend<String, MediaConvertJobTemplate> templates;
    private final StorageBackend<String, MediaConvertJob> jobs;
    private final ConcurrentHashMap<String, JobsQuery> queries = new ConcurrentHashMap<>();
    private final RegionResolver regionResolver;
    private final IamService iamService;
    private final ObjectMapper objectMapper;

    @Inject
    Instance<S3Service> s3Services;

    @Inject
    public MediaConvertService(
            StorageFactory storageFactory,
            RegionResolver regionResolver,
            IamService iamService,
            ObjectMapper objectMapper) {
        this(
                storageFactory.create("mediaconvert", "mediaconvert-queues.json",
                        new TypeReference<Map<String, MediaConvertQueue>>() {
                        }),
                storageFactory.create("mediaconvert", "mediaconvert-presets.json",
                        new TypeReference<Map<String, MediaConvertPreset>>() {
                        }),
                storageFactory.create("mediaconvert", "mediaconvert-job-templates.json",
                        new TypeReference<Map<String, MediaConvertJobTemplate>>() {
                        }),
                storageFactory.create("mediaconvert", "mediaconvert-jobs.json",
                        new TypeReference<Map<String, MediaConvertJob>>() {
                        }),
                regionResolver,
                iamService,
                objectMapper);
    }

    MediaConvertService(RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                regionResolver,
                null,
                objectMapper);
    }

    MediaConvertService(
            StorageBackend<String, MediaConvertQueue> queues,
            StorageBackend<String, MediaConvertPreset> presets,
            StorageBackend<String, MediaConvertJobTemplate> templates,
            StorageBackend<String, MediaConvertJob> jobs,
            RegionResolver regionResolver,
            IamService iamService,
            ObjectMapper objectMapper) {
        this.queues = queues;
        this.presets = presets;
        this.templates = templates;
        this.jobs = jobs;
        this.regionResolver = regionResolver;
        this.iamService = iamService;
        this.objectMapper = objectMapper;
    }

    // ── Queues ──────────────────────────────────────────────────────────────

    public synchronized MediaConvertQueue createQueue(String region, JsonNode request) {
        requireObject(request);
        String name = requireName(request, "name");
        String key = queueKey(region, name);
        if (queues.get(key).isPresent() || DEFAULT_QUEUE.equals(name)) {
            throw conflict("A queue named " + name + " already exists.");
        }
        MediaConvertQueue queue = new MediaConvertQueue();
        long now = now();
        queue.setName(name);
        queue.setArn(arn(region, "queues/" + name));
        queue.setDescription(optionalText(request, "description"));
        queue.setPricingPlan(optionalEnum(request, "pricingPlan", PRICING_PLANS, "ON_DEMAND"));
        queue.setStatus(optionalEnum(request, "status", QUEUE_STATUSES, "ACTIVE"));
        queue.setType("CUSTOM");
        queue.setConcurrentJobs(optionalInt(request, "concurrentJobs"));
        queue.setReservationPlan(copyNode(request.get("reservationPlanSettings")));
        queue.setCreatedAt(now);
        queue.setLastUpdated(now);
        queue.setRegion(region);
        queue.setTags(readTags(request.get("tags")));
        queues.put(key, queue);
        return queue;
    }

    public MediaConvertQueue getQueue(String region, String name) {
        requireNameValue(name);
        ensureDefaultQueue(region);
        return queues.get(queueKey(region, name))
                .orElseThrow(() -> notFound("Queue '" + name + "' was not found."));
    }

    public synchronized MediaConvertQueue updateQueue(String region, String name, JsonNode request) {
        requireObject(request);
        MediaConvertQueue queue = getQueue(region, name);
        if (request.hasNonNull("description")) {
            queue.setDescription(optionalText(request, "description"));
        }
        if (request.hasNonNull("status")) {
            queue.setStatus(optionalEnum(request, "status", QUEUE_STATUSES, queue.getStatus()));
        }
        if (request.has("concurrentJobs") && !request.get("concurrentJobs").isNull()) {
            queue.setConcurrentJobs(optionalInt(request, "concurrentJobs"));
        }
        if (request.has("reservationPlanSettings")) {
            queue.setReservationPlan(copyNode(request.get("reservationPlanSettings")));
        }
        queue.setLastUpdated(now());
        queues.put(queueKey(region, name), queue);
        return queue;
    }

    public synchronized void deleteQueue(String region, String name) {
        requireNameValue(name);
        if (DEFAULT_QUEUE.equals(name)) {
            throw badRequest("You cannot delete the default queue.");
        }
        MediaConvertQueue queue = getQueue(region, name);
        if ("SYSTEM".equals(queue.getType())) {
            throw badRequest("You cannot delete a system queue.");
        }
        queues.delete(queueKey(region, name));
    }

    public ObjectNode listQueues(String region) {
        ensureDefaultQueue(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("queues");
        List<MediaConvertQueue> listed = queues.scan(key -> key.startsWith(region + ":"));
        listed.sort(Comparator.comparing(MediaConvertQueue::getName));
        for (MediaConvertQueue queue : listed) {
            items.add(toQueue(queue));
        }
        return response;
    }

    public ObjectNode toQueueEnvelope(MediaConvertQueue queue) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("queue", toQueue(queue));
        return response;
    }

    public ObjectNode toQueue(MediaConvertQueue queue) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", queue.getArn());
        node.put("name", queue.getName());
        putOptional(node, "description", queue.getDescription());
        node.put("pricingPlan", queue.getPricingPlan());
        node.put("status", queue.getStatus());
        node.put("type", queue.getType());
        if (queue.getConcurrentJobs() != null) {
            node.put("concurrentJobs", queue.getConcurrentJobs());
        }
        if (queue.getReservationPlan() != null) {
            node.set("reservationPlan", queue.getReservationPlan());
        }
        node.put("createdAt", queue.getCreatedAt());
        node.put("lastUpdated", queue.getLastUpdated());
        node.put("progressingJobsCount", 0);
        node.put("submittedJobsCount", 0);
        return node;
    }

    // ── Presets ─────────────────────────────────────────────────────────────

    public synchronized MediaConvertPreset createPreset(String region, JsonNode request) {
        requireObject(request);
        String name = requireName(request, "name");
        JsonNode settings = requireObjectField(request, "settings");
        String key = namedKey(region, name);
        if (presets.get(key).isPresent()) {
            throw conflict("A preset named " + name + " already exists.");
        }
        MediaConvertPreset preset = new MediaConvertPreset();
        long now = now();
        preset.setName(name);
        preset.setArn(arn(region, "presets/" + name));
        preset.setDescription(optionalText(request, "description"));
        preset.setCategory(optionalText(request, "category"));
        preset.setType("CUSTOM");
        preset.setSettings(copyNode(settings));
        preset.setCreatedAt(now);
        preset.setLastUpdated(now);
        preset.setRegion(region);
        preset.setTags(readTags(request.get("tags")));
        presets.put(key, preset);
        return preset;
    }

    public MediaConvertPreset getPreset(String region, String name) {
        requireNameValue(name);
        return presets.get(namedKey(region, name))
                .orElseThrow(() -> notFound("Preset '" + name + "' was not found."));
    }

    public synchronized MediaConvertPreset updatePreset(String region, String name, JsonNode request) {
        requireObject(request);
        MediaConvertPreset preset = getPreset(region, name);
        if (request.hasNonNull("description")) {
            preset.setDescription(optionalText(request, "description"));
        }
        if (request.hasNonNull("category")) {
            preset.setCategory(optionalText(request, "category"));
        }
        if (request.has("settings") && request.get("settings").isObject()) {
            preset.setSettings(copyNode(request.get("settings")));
        }
        preset.setLastUpdated(now());
        presets.put(namedKey(region, name), preset);
        return preset;
    }

    public synchronized void deletePreset(String region, String name) {
        getPreset(region, name);
        presets.delete(namedKey(region, name));
    }

    public ObjectNode listPresets(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("presets");
        List<MediaConvertPreset> listed = presets.scan(key -> key.startsWith(region + ":"));
        listed.sort(Comparator.comparing(MediaConvertPreset::getName));
        for (MediaConvertPreset preset : listed) {
            items.add(toPreset(preset));
        }
        return response;
    }

    public ObjectNode toPresetEnvelope(MediaConvertPreset preset) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("preset", toPreset(preset));
        return response;
    }

    public ObjectNode toPreset(MediaConvertPreset preset) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", preset.getArn());
        node.put("name", preset.getName());
        putOptional(node, "description", preset.getDescription());
        putOptional(node, "category", preset.getCategory());
        node.put("type", preset.getType());
        if (preset.getSettings() != null) {
            node.set("settings", preset.getSettings());
        }
        node.put("createdAt", preset.getCreatedAt());
        node.put("lastUpdated", preset.getLastUpdated());
        return node;
    }

    // ── Job templates ───────────────────────────────────────────────────────

    public synchronized MediaConvertJobTemplate createJobTemplate(String region, JsonNode request) {
        requireObject(request);
        String name = requireName(request, "name");
        JsonNode settings = requireObjectField(request, "settings");
        String key = namedKey(region, name);
        if (templates.get(key).isPresent()) {
            throw conflict("A job template named " + name + " already exists.");
        }
        MediaConvertJobTemplate template = new MediaConvertJobTemplate();
        long now = now();
        template.setName(name);
        template.setArn(arn(region, "jobTemplates/" + name));
        template.setDescription(optionalText(request, "description"));
        template.setCategory(optionalText(request, "category"));
        template.setType("CUSTOM");
        template.setQueue(optionalText(request, "queue"));
        template.setPriority(optionalInt(request, "priority"));
        template.setStatusUpdateInterval(optionalText(request, "statusUpdateInterval"));
        template.setAccelerationSettings(copyNode(request.get("accelerationSettings")));
        template.setHopDestinations(copyNode(request.get("hopDestinations")));
        template.setSettings(copyNode(settings));
        template.setCreatedAt(now);
        template.setLastUpdated(now);
        template.setRegion(region);
        template.setTags(readTags(request.get("tags")));
        templates.put(key, template);
        return template;
    }

    public MediaConvertJobTemplate getJobTemplate(String region, String name) {
        requireNameValue(name);
        return templates.get(namedKey(region, name))
                .orElseThrow(() -> notFound("Job template '" + name + "' was not found."));
    }

    public synchronized MediaConvertJobTemplate updateJobTemplate(
            String region, String name, JsonNode request) {
        requireObject(request);
        MediaConvertJobTemplate template = getJobTemplate(region, name);
        if (request.hasNonNull("description")) {
            template.setDescription(optionalText(request, "description"));
        }
        if (request.hasNonNull("category")) {
            template.setCategory(optionalText(request, "category"));
        }
        if (request.hasNonNull("queue")) {
            template.setQueue(optionalText(request, "queue"));
        }
        if (request.has("priority") && !request.get("priority").isNull()) {
            template.setPriority(optionalInt(request, "priority"));
        }
        if (request.hasNonNull("statusUpdateInterval")) {
            template.setStatusUpdateInterval(optionalText(request, "statusUpdateInterval"));
        }
        if (request.has("accelerationSettings")) {
            template.setAccelerationSettings(copyNode(request.get("accelerationSettings")));
        }
        if (request.has("hopDestinations")) {
            template.setHopDestinations(copyNode(request.get("hopDestinations")));
        }
        if (request.has("settings") && request.get("settings").isObject()) {
            template.setSettings(copyNode(request.get("settings")));
        }
        template.setLastUpdated(now());
        templates.put(namedKey(region, name), template);
        return template;
    }

    public synchronized void deleteJobTemplate(String region, String name) {
        getJobTemplate(region, name);
        templates.delete(namedKey(region, name));
    }

    public ObjectNode listJobTemplates(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("jobTemplates");
        List<MediaConvertJobTemplate> listed = templates.scan(key -> key.startsWith(region + ":"));
        listed.sort(Comparator.comparing(MediaConvertJobTemplate::getName));
        for (MediaConvertJobTemplate template : listed) {
            items.add(toJobTemplate(template));
        }
        return response;
    }

    public ObjectNode toJobTemplateEnvelope(MediaConvertJobTemplate template) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("jobTemplate", toJobTemplate(template));
        return response;
    }

    public ObjectNode toJobTemplate(MediaConvertJobTemplate template) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", template.getArn());
        node.put("name", template.getName());
        putOptional(node, "description", template.getDescription());
        putOptional(node, "category", template.getCategory());
        node.put("type", template.getType());
        putOptional(node, "queue", template.getQueue());
        if (template.getPriority() != null) {
            node.put("priority", template.getPriority());
        }
        putOptional(node, "statusUpdateInterval", template.getStatusUpdateInterval());
        if (template.getAccelerationSettings() != null) {
            node.set("accelerationSettings", template.getAccelerationSettings());
        }
        if (template.getHopDestinations() != null) {
            node.set("hopDestinations", template.getHopDestinations());
        }
        if (template.getSettings() != null) {
            node.set("settings", template.getSettings());
        }
        node.put("createdAt", template.getCreatedAt());
        node.put("lastUpdated", template.getLastUpdated());
        return node;
    }

    // ── Jobs ────────────────────────────────────────────────────────────────

    public synchronized MediaConvertJob createJob(String region, JsonNode request) {
        requireObject(request);
        String role = optionalText(request, "role");
        if (role == null) {
            throw badRequest("Role is required.");
        }
        requireExistingRole(role);
        JsonNode settings = request.get("settings");
        String jobTemplate = optionalText(request, "jobTemplate");
        String id = Instant.now().toEpochMilli() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
        MediaConvertJob job = new MediaConvertJob();
        job.setId(id);
        job.setArn(arn(region, "jobs/" + id));
        job.setRole(role);
        job.setQueue(optionalText(request, "queue") == null
                ? DEFAULT_QUEUE
                : optionalText(request, "queue"));
        job.setStatus("COMPLETE");
        job.setPriority(optionalInt(request, "priority"));
        job.setJobTemplate(jobTemplate);
        job.setSettings(copyNode(settings));
        job.setAccelerationSettings(copyNode(request.get("accelerationSettings")));
        job.setUserMetadata(copyNode(request.get("userMetadata")));
        job.setCreatedAt(now());
        job.setRegion(region);
        job.setTags(readTags(request.get("tags")));
        jobs.put(id, job);
        return job;
    }

    public MediaConvertJob createJob(String accountId, String region, JsonNode request) {
        return createJob(region, request);
    }

    public MediaConvertJob getJob(String regionOrAccount, String id) {
        if (id == null || id.isBlank()) {
            throw badRequest("Id is required.");
        }
        return jobs.get(id)
                .orElseThrow(() -> notFound("Job '" + id + "' was not found."));
    }

    public synchronized void cancelJob(String region, String id) {
        MediaConvertJob job = getJob(region, id);
        if (TERMINAL_JOBS.contains(job.getStatus())) {
            throw conflict("You cannot cancel a job that is already " + job.getStatus() + ".");
        }
        job.setStatus("CANCELED");
        jobs.put(id, job);
    }

    public ObjectNode listJobs(String region) {
        return listJobs(region, null, null, null, null);
    }

    public ObjectNode listJobs(
            String region, String status, String queue, String order, Integer maxResults) {
        return jobsPage(filterJobs(region, status, queue, null), order, maxResults);
    }

    public ObjectNode searchJobs(
            String region, String status, String queue, String inputFile, String order, Integer maxResults) {
        return jobsPage(filterJobs(region, status, queue, inputFile), order, maxResults);
    }

    public ObjectNode probe(JsonNode request) {
        requireObject(request);
        JsonNode files = request.get("inputFiles");
        if (files == null || !files.isArray() || files.isEmpty()) {
            throw badRequest("You must specify at least one input file.");
        }
        for (JsonNode file : files) {
            String fileUrl = optionalText(file, "fileUrl");
            if (fileUrl == null) {
                throw badRequest("Each input file must include fileUrl.");
            }
            requireS3Object(fileUrl);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("probeResults");
        return response;
    }

    public ObjectNode startJobsQuery(String region, JsonNode request) {
        if (request == null || request.isNull() || request.isMissingNode()) {
            request = objectMapper.createObjectNode();
        }
        requireObject(request);
        String status = firstFilter(request, "status");
        String queue = firstFilter(request, "queue");
        String inputFile = firstFilter(request, "fileInput");
        Integer maxResults = optionalInt(request, "maxResults");
        String order = optionalText(request, "order");
        List<MediaConvertJob> matches = filterJobs(region, status, queue, inputFile);
        JobsQuery query = new JobsQuery();
        query.setId(UUID.randomUUID().toString());
        query.setStatus("COMPLETE");
        query.setJobs(pageJobs(matches, order, maxResults));
        queries.put(query.getId(), query);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", query.getId());
        return response;
    }

    public JobsQuery startJobsQuery(String accountId, String region, JsonNode request) {
        ObjectNode started = startJobsQuery(region, request);
        return queries.get(started.get("id").asText());
    }

    public JobsQuery getJobsQueryResults(String regionOrAccount, String id) {
        return requireJobsQuery(id);
    }

    public ObjectNode toJobsQueryEnvelope(JobsQuery query) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", query.getStatus());
        ArrayNode items = response.putArray("jobs");
        for (MediaConvertJob job : query.getJobs()) {
            items.add(toJob(job));
        }
        return response;
    }

    public List<MediaConvertJob> listJobs(
            String accountId, String region, String status, String queue, String order, Integer maxResults) {
        return pageJobs(filterJobs(region, status, queue, null), order, maxResults);
    }

    private JobsQuery requireJobsQuery(String id) {
        if (id == null || id.isBlank()) {
            throw badRequest("Id is required.");
        }
        JobsQuery query = queries.get(id);
        if (query == null) {
            throw notFound("Jobs query '" + id + "' was not found.");
        }
        return query;
    }

    public ObjectNode toJobEnvelope(MediaConvertJob job) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("job", toJob(job));
        return response;
    }

    public ObjectNode toJob(MediaConvertJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", job.getId());
        node.put("arn", job.getArn());
        node.put("role", job.getRole());
        node.put("queue", job.getQueue());
        node.put("status", job.getStatus());
        if (job.getPriority() != null) {
            node.put("priority", job.getPriority());
        }
        putOptional(node, "jobTemplate", job.getJobTemplate());
        if (job.getSettings() != null) {
            node.set("settings", job.getSettings());
        }
        if (job.getAccelerationSettings() != null) {
            node.set("accelerationSettings", job.getAccelerationSettings());
        }
        if (job.getUserMetadata() != null) {
            node.set("userMetadata", job.getUserMetadata());
        }
        node.put("createdAt", job.getCreatedAt());
        ObjectNode timing = node.putObject("timing");
        timing.put("submitTime", job.getCreatedAt());
        return node;
    }

    // ── Tags ────────────────────────────────────────────────────────────────

    public synchronized ObjectNode listTags(String region, String arn) {
        Tagged tagged = requireTagged(region, arn);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode resourceTags = response.putObject("resourceTags");
        resourceTags.put("arn", tagged.arn());
        ObjectNode tags = resourceTags.putObject("tags");
        tagged.tags().forEach(tags::put);
        return response;
    }

    public synchronized void tagResource(String region, JsonNode request) {
        requireObject(request);
        String arn = optionalText(request, "arn");
        if (arn == null) {
            throw badRequest("Arn is required.");
        }
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> incoming = readTags(request.get("tags"));
        tagged.tags().putAll(incoming);
        persistTagged(tagged);
    }

    public synchronized void untagResource(String region, String arn, JsonNode request) {
        Tagged tagged = requireTagged(region, arn);
        if (request != null && request.has("tagKeys") && request.get("tagKeys").isArray()) {
            for (JsonNode key : request.get("tagKeys")) {
                if (key.isTextual()) {
                    tagged.tags().remove(key.textValue());
                }
            }
        }
        persistTagged(tagged);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private List<MediaConvertJob> filterJobs(String region, String status, String queue, String inputFile) {
        List<MediaConvertJob> listed = jobs.scan(key -> true);
        listed.removeIf(job -> !region.equals(job.getRegion()));
        if (status != null && !status.isBlank()) {
            listed.removeIf(job -> !status.equals(job.getStatus()));
        }
        if (queue != null && !queue.isBlank()) {
            String wanted = queue.contains("/") ? queue.substring(queue.lastIndexOf('/') + 1) : queue;
            listed.removeIf(job -> job.getQueue() == null || !job.getQueue().endsWith(wanted));
        }
        if (inputFile != null && !inputFile.isBlank()) {
            listed.removeIf(job -> job.getSettings() == null
                    || !job.getSettings().toString().contains(inputFile));
        }
        return listed;
    }

    private List<MediaConvertJob> pageJobs(List<MediaConvertJob> listed, String order, Integer maxResults) {
        Comparator<MediaConvertJob> comparator = Comparator.comparingLong(MediaConvertJob::getCreatedAt)
                .thenComparing(MediaConvertJob::getId);
        if (order == null || "DESCENDING".equalsIgnoreCase(order)) {
            comparator = comparator.reversed();
        }
        listed.sort(comparator);
        int limit = maxResults != null ? Math.min(Math.max(maxResults, 1), 20) : 20;
        if (listed.size() > limit) {
            return listed.subList(0, limit);
        }
        return listed;
    }

    private ObjectNode jobsPage(List<MediaConvertJob> listed, String order, Integer maxResults) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("jobs");
        for (MediaConvertJob job : pageJobs(listed, order, maxResults)) {
            items.add(toJob(job));
        }
        return response;
    }

    private void requireS3Object(String fileUrl) {
        if (!fileUrl.startsWith("s3://")) {
            throw notFound("Input file '" + fileUrl + "' not found.");
        }
        String remainder = fileUrl.substring("s3://".length());
        int slash = remainder.indexOf('/');
        if (slash <= 0 || slash == remainder.length() - 1) {
            throw notFound("Input file '" + fileUrl + "' not found.");
        }
        String bucket = remainder.substring(0, slash);
        String key = remainder.substring(slash + 1);
        if (s3Services == null || s3Services.isUnsatisfied()) {
            throw notFound("Input file '" + fileUrl + "' not found.");
        }
        try {
            if (!s3Services.get().objectExists(bucket, key)) {
                throw notFound("Input file '" + fileUrl + "' not found.");
            }
        } catch (AwsException e) {
            if ("NotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            throw notFound("Input file '" + fileUrl + "' not found.");
        }
    }

    private static String firstFilter(JsonNode request, String key) {
        JsonNode list = request.get("filterList");
        if (list == null || !list.isArray()) {
            return null;
        }
        for (JsonNode filter : list) {
            String filterKey = optionalText(filter, "key");
            if (key.equalsIgnoreCase(filterKey)) {
                JsonNode values = filter.get("values");
                if (values != null && values.isArray() && values.size() > 0 && values.get(0).isTextual()) {
                    return values.get(0).asText();
                }
            }
        }
        return null;
    }

    private void ensureDefaultQueue(String region) {
        String key = queueKey(region, DEFAULT_QUEUE);
        if (queues.get(key).isPresent()) {
            return;
        }
        MediaConvertQueue queue = new MediaConvertQueue();
        long now = now();
        queue.setName(DEFAULT_QUEUE);
        queue.setArn(arn(region, "queues/" + DEFAULT_QUEUE));
        queue.setDescription("System default queue");
        queue.setPricingPlan("ON_DEMAND");
        queue.setStatus("ACTIVE");
        queue.setType("SYSTEM");
        queue.setCreatedAt(now);
        queue.setLastUpdated(now);
        queue.setRegion(region);
        queues.put(key, queue);
    }

    private void requireExistingRole(String roleArn) {
        Matcher matcher = ROLE_ARN.matcher(roleArn);
        if (!matcher.matches()) {
            throw badRequest("Role must be an IAM role ARN.");
        }
        String accountId = matcher.group(1);
        String roleName = matcher.group(2);
        if (roleName.contains("/")) {
            roleName = roleName.substring(roleName.lastIndexOf('/') + 1);
        }
        if (iamService != null && iamService.findRole(accountId, roleName).isEmpty()) {
            throw badRequest("You must use the role ARN of an IAM role that you've set up.");
        }
    }

    private Tagged requireTagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw badRequest("Invalid ARN.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw badRequest("ARN is not a MediaConvert resource.");
        }
        String resource = parsed.resource();
        if (resource.startsWith("queues/")) {
            MediaConvertQueue queue = getQueue(region, resource.substring("queues/".length()));
            return new Tagged(queue.getArn(), queue.getTags(), "queue", queueKey(region, queue.getName()), queue);
        }
        if (resource.startsWith("presets/")) {
            MediaConvertPreset preset = getPreset(region, resource.substring("presets/".length()));
            return new Tagged(preset.getArn(), preset.getTags(), "preset", namedKey(region, preset.getName()), preset);
        }
        if (resource.startsWith("jobTemplates/")) {
            MediaConvertJobTemplate template =
                    getJobTemplate(region, resource.substring("jobTemplates/".length()));
            return new Tagged(template.getArn(), template.getTags(), "template",
                    namedKey(region, template.getName()), template);
        }
        if (resource.startsWith("jobs/")) {
            MediaConvertJob job = getJob(region, resource.substring("jobs/".length()));
            return new Tagged(job.getArn(), job.getTags(), "job", job.getId(), job);
        }
        throw notFound("Resource '" + arn + "' was not found.");
    }

    private void persistTagged(Tagged tagged) {
        switch (tagged.kind()) {
            case "queue" -> queues.put(tagged.key(), (MediaConvertQueue) tagged.resource());
            case "preset" -> presets.put(tagged.key(), (MediaConvertPreset) tagged.resource());
            case "template" -> templates.put(tagged.key(), (MediaConvertJobTemplate) tagged.resource());
            case "job" -> jobs.put(tagged.key(), (MediaConvertJob) tagged.resource());
            default -> throw badRequest("Unsupported resource type.");
        }
    }

    private record Tagged(String arn, Map<String, String> tags, String kind, String key, Object resource) {
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn(SERVICE, region, resource);
    }

    private static String queueKey(String region, String name) {
        return region + ":" + name;
    }

    private static String namedKey(String region, String name) {
        return region + ":" + name;
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw badRequest("Request body must be a JSON object.");
        }
    }

    private static JsonNode requireObjectField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw badRequest(capitalize(field) + " is required.");
        }
        return value;
    }

    private static String requireName(JsonNode parent, String field) {
        String name = optionalText(parent, field);
        requireNameValue(name);
        return name;
    }

    private static void requireNameValue(String name) {
        if (name == null || name.isBlank()) {
            throw badRequest("Name is required.");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw badRequest("Name must match ^[\\w-]+$.");
        }
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.textValue();
        return text == null || text.isBlank() ? null : text;
    }

    private static Integer optionalInt(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (value.isNumber()) {
            return value.intValue();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.textValue());
            } catch (NumberFormatException e) {
                throw badRequest(capitalize(field) + " must be a number.");
            }
        }
        return null;
    }

    private static String optionalEnum(JsonNode parent, String field, Set<String> allowed, String fallback) {
        String value = optionalText(parent, field);
        if (value == null) {
            return fallback;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw badRequest(capitalize(field) + " is invalid.");
        }
        return upper;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull() || !node.isObject()) {
            return tags;
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && entry.getValue().isTextual()) {
                tags.put(entry.getKey(), entry.getValue().textValue());
            }
        });
        return tags;
    }

    private JsonNode copyNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.deepCopy();
    }

    private static void putOptional(ObjectNode parent, String field, String value) {
        if (value != null) {
            parent.put(field, value);
        }
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    static AwsException notFound(String message) {
        return new AwsException("NotFoundException", message, 404);
    }

    static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }
}
