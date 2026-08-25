package io.github.hectorvent.floci.services.emrcontainers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emrcontainers.model.JobRun;
import io.github.hectorvent.floci.services.emrcontainers.model.JobTemplate;
import io.github.hectorvent.floci.services.emrcontainers.model.VirtualCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon EMR on EKS ({@code emr-containers}) restJson1 — job templates, virtual
 * clusters, and the job-run data plane the virtual-cluster lifecycle depends on.
 *
 * <p>Describe of a missing virtual cluster is {@code ResourceNotFoundException}
 * at HTTP 400 (AWS's wire status). Delete of a missing or already-terminated
 * cluster is {@code ValidationException}. Job-run ops addressed at a missing
 * virtual cluster are {@code ValidationException}, except {@code ListJobRuns}
 * which succeeds with an empty page.
 */
@ApplicationScoped
public class EmrContainersService implements TagHandler {

    static final String SERVICE = "emr-containers";

    private static final Pattern NAME_PATTERN = Pattern.compile("[.\\-_/#A-Za-z0-9]+");
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;
    private static final String TOKEN_PREFIX = "emrc:v1:";
    private static final Set<String> LIVE_STATES = Set.of("RUNNING", "ARRESTED");
    private static final Set<String> CANCELLABLE = Set.of(
            "PENDING", "SUBMITTED", "RUNNING", "CANCEL_PENDING");

    private final StorageBackend<String, VirtualCluster> clusters;
    private final StorageBackend<String, JobRun> jobRuns;
    private final StorageBackend<String, JobTemplate> templates;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public EmrContainersService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create("emrcontainers", "emr-containers-virtual-clusters.json",
                        new TypeReference<Map<String, VirtualCluster>>() {
                        }),
                storageFactory.create("emrcontainers", "emr-containers-job-runs.json",
                        new TypeReference<Map<String, JobRun>>() {
                        }),
                storageFactory.create("emrcontainers", "emr-containers-job-templates.json",
                        new TypeReference<Map<String, JobTemplate>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    EmrContainersService(
            StorageBackend<String, VirtualCluster> clusters,
            StorageBackend<String, JobRun> jobRuns,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this(clusters, jobRuns, new InMemoryStorage<>(), regionResolver, objectMapper);
    }

    EmrContainersService(
            StorageBackend<String, VirtualCluster> clusters,
            StorageBackend<String, JobRun> jobRuns,
            StorageBackend<String, JobTemplate> templates,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.clusters = clusters;
        this.jobRuns = jobRuns;
        this.templates = templates;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized VirtualCluster createVirtualCluster(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        String clientToken = optionalText(request, "clientToken");
        if (clientToken != null && !clientToken.isBlank()) {
            VirtualCluster existing = findByClientToken(region, clientToken);
            if (existing != null) {
                return existing;
            }
        }
        JsonNode providerNode = request.get("containerProvider");
        if (providerNode == null || !providerNode.isObject()) {
            throw validation("containerProvider is a required field.");
        }
        Map<String, Object> containerProvider = readContainerProvider(providerNode);
        if (findLiveByName(region, name) != null) {
            throw validation("A virtual cluster named " + name + " already exists.");
        }

        String id = newId(25);
        String now = timestamp();
        VirtualCluster cluster = new VirtualCluster();
        cluster.setId(id);
        cluster.setName(name);
        cluster.setArn(arn(region, id));
        cluster.setState("RUNNING");
        cluster.setContainerProvider(containerProvider);
        cluster.setCreatedAt(now);
        cluster.setTags(readTags(request));
        cluster.setSecurityConfigurationId(optionalText(request, "securityConfigurationId"));
        cluster.setClientToken(clientToken);
        cluster.setRegion(region);
        clusters.put(storageKey(region, id), cluster);
        return cluster;
    }

    public VirtualCluster describeVirtualCluster(String region, String id) {
        requireId(id);
        VirtualCluster cluster = clusters.get(storageKey(region, id)).orElse(null);
        if (cluster == null) {
            throw notFound("Virtual cluster " + id + " does not exist.");
        }
        return cluster;
    }

    public synchronized VirtualCluster deleteVirtualCluster(String region, String id) {
        requireId(id);
        String key = storageKey(region, id);
        VirtualCluster cluster = clusters.get(key).orElse(null);
        if (cluster == null || !LIVE_STATES.contains(cluster.getState())) {
            throw validation("Virtual cluster " + id + " is not in a valid state to be deleted.");
        }
        cluster.setState("TERMINATED");
        clusters.put(key, cluster);
        return cluster;
    }

    public Page<VirtualCluster> listVirtualClusters(
            String region,
            String containerProviderId,
            String containerProviderType,
            List<String> states,
            Integer maxResults,
            String nextToken) {
        int limit = parseMaxResults(maxResults);
        List<String> wantedStates = flatten(states);
        List<VirtualCluster> matches = new ArrayList<>();
        for (VirtualCluster cluster : clusters.scan(key -> key.startsWith(region + "::"))) {
            if (containerProviderId != null && !containerProviderId.isBlank()) {
                Object id = cluster.getContainerProvider().get("id");
                if (id == null || !containerProviderId.equals(String.valueOf(id))) {
                    continue;
                }
            }
            if (containerProviderType != null && !containerProviderType.isBlank()) {
                Object type = cluster.getContainerProvider().get("type");
                if (type == null || !containerProviderType.equals(String.valueOf(type))) {
                    continue;
                }
            }
            if (!wantedStates.isEmpty() && !wantedStates.contains(cluster.getState())) {
                continue;
            }
            matches.add(cluster);
        }
        matches.sort(Comparator.comparing(VirtualCluster::getCreatedAt)
                .thenComparing(VirtualCluster::getId));
        return page(matches, limit, nextToken);
    }

    public synchronized JobTemplate createJobTemplate(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        String clientToken = requireText(request, "clientToken");
        JobTemplate existing = findTemplateByClientToken(region, clientToken);
        if (existing != null) {
            return existing;
        }
        JsonNode dataNode = request.get("jobTemplateData");
        requireObject(dataNode, "jobTemplateData");
        if (!dataNode.hasNonNull("executionRoleArn") || dataNode.get("executionRoleArn").asText().isBlank()) {
            throw validation("jobTemplateData.executionRoleArn is a required field.");
        }
        if (!dataNode.hasNonNull("releaseLabel") || dataNode.get("releaseLabel").asText().isBlank()) {
            throw validation("jobTemplateData.releaseLabel is a required field.");
        }
        JsonNode driver = dataNode.get("jobDriver");
        requireObject(driver, "jobDriver");
        if (!driver.hasNonNull("sparkSubmitJobDriver") && !driver.hasNonNull("sparkSqlJobDriver")) {
            throw validation("jobDriver must contain sparkSubmitJobDriver or sparkSqlJobDriver.");
        }

        String id = newId(26);
        String now = timestamp();
        JobTemplate template = new JobTemplate();
        template.setId(id);
        template.setName(name);
        template.setArn(jobTemplateArn(region, id));
        template.setCreatedAt(now);
        template.setCreatedBy("arn:aws:iam::" + regionResolver.getAccountId() + ":root");
        template.setTags(readTags(request));
        template.setJobTemplateData(objectMapper.convertValue(dataNode, new TypeReference<>() {
        }));
        template.setKmsKeyArn(optionalText(request, "kmsKeyArn"));
        template.setClientToken(clientToken);
        template.setRegion(region);
        templates.put(storageKey(region, id), template);
        return template;
    }

    public JobTemplate describeJobTemplate(String region, String id) {
        requireId(id);
        return templates.get(storageKey(region, id)).orElseThrow(
                () -> notFound("Job template " + id + " does not exist."));
    }

    public synchronized JobTemplate deleteJobTemplate(String region, String id) {
        requireId(id);
        String key = storageKey(region, id);
        JobTemplate template = templates.get(key).orElseThrow(
                () -> validation("Job template " + id + " does not exist."));
        templates.delete(key);
        return template;
    }

    public Page<JobTemplate> listJobTemplates(String region, Integer maxResults, String nextToken) {
        int limit = parseMaxResults(maxResults);
        List<JobTemplate> matches = new ArrayList<>(templates.scan(key -> key.startsWith(region + "::")));
        matches.sort(Comparator.comparing(JobTemplate::getCreatedAt, Comparator.nullsLast(String::compareTo))
                .thenComparing(JobTemplate::getId, Comparator.nullsLast(String::compareTo)));
        return page(matches, limit, nextToken);
    }

    public synchronized JobRun startJobRun(String region, String virtualClusterId, JsonNode request) {
        requireId(virtualClusterId);
        requireLive(region, virtualClusterId);
        requireObject(request, "Request body");
        String clientToken = optionalText(request, "clientToken");
        if (clientToken != null && !clientToken.isBlank()) {
            JobRun existing = findJobByClientToken(region, virtualClusterId, clientToken);
            if (existing != null) {
                return existing;
            }
        }
        String id = newId(19);
        String now = timestamp();
        JobRun job = new JobRun();
        job.setId(id);
        job.setName(optionalText(request, "name"));
        job.setVirtualClusterId(virtualClusterId);
        job.setArn(jobArn(region, virtualClusterId, id));
        job.setState("COMPLETED");
        job.setClientToken(clientToken);
        job.setExecutionRoleArn(optionalText(request, "executionRoleArn"));
        job.setReleaseLabel(optionalText(request, "releaseLabel"));
        job.setCreatedAt(now);
        job.setFinishedAt(now);
        job.setTags(readTags(request));
        job.setRegion(region);
        jobRuns.put(jobKey(region, virtualClusterId, id), job);
        return job;
    }

    public JobRun describeJobRun(String region, String virtualClusterId, String id) {
        requireId(virtualClusterId);
        requireId(id);
        if (clusters.get(storageKey(region, virtualClusterId)).isEmpty()) {
            throw validation("Virtual cluster " + virtualClusterId + " does not exist.");
        }
        return jobRuns.get(jobKey(region, virtualClusterId, id)).orElseThrow(
                () -> notFound("Job run " + id + " does not exist."));
    }

    public synchronized JobRun cancelJobRun(String region, String virtualClusterId, String id) {
        requireId(virtualClusterId);
        requireId(id);
        if (clusters.get(storageKey(region, virtualClusterId)).isEmpty()) {
            throw validation("Virtual cluster " + virtualClusterId + " does not exist.");
        }
        String key = jobKey(region, virtualClusterId, id);
        JobRun job = jobRuns.get(key).orElse(null);
        if (job == null || !CANCELLABLE.contains(job.getState())) {
            throw validation("Job run " + id + " is not in a valid state to be cancelled.");
        }
        job.setState("CANCELLED");
        job.setFinishedAt(timestamp());
        jobRuns.put(key, job);
        return job;
    }

    public Page<JobRun> listJobRuns(
            String region, String virtualClusterId, List<String> states, Integer maxResults, String nextToken) {
        requireId(virtualClusterId);
        int limit = parseMaxResults(maxResults);
        List<String> wantedStates = flatten(states);
        List<JobRun> matches = new ArrayList<>();
        String prefix = region + "::" + virtualClusterId + "::";
        for (JobRun job : jobRuns.scan(key -> key.startsWith(prefix))) {
            if (!wantedStates.isEmpty() && !wantedStates.contains(job.getState())) {
                continue;
            }
            matches.add(job);
        }
        matches.sort(Comparator.comparing(JobRun::getCreatedAt).thenComparing(JobRun::getId));
        return page(matches, limit, nextToken);
    }

    ObjectNode toPublicCluster(VirtualCluster cluster) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", cluster.getId());
        node.put("name", cluster.getName());
        node.put("arn", cluster.getArn());
        node.put("state", cluster.getState());
        node.set("containerProvider", objectMapper.valueToTree(cluster.getContainerProvider()));
        if (cluster.getCreatedAt() != null) {
            node.put("createdAt", cluster.getCreatedAt());
        }
        if (cluster.getTags() != null && !cluster.getTags().isEmpty()) {
            node.set("tags", objectMapper.valueToTree(cluster.getTags()));
        }
        if (cluster.getSecurityConfigurationId() != null) {
            node.put("securityConfigurationId", cluster.getSecurityConfigurationId());
        }
        return node;
    }

    ObjectNode toPublicJobRun(JobRun job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", job.getId());
        if (job.getName() != null) {
            node.put("name", job.getName());
        }
        node.put("virtualClusterId", job.getVirtualClusterId());
        node.put("arn", job.getArn());
        node.put("state", job.getState());
        if (job.getExecutionRoleArn() != null) {
            node.put("executionRoleArn", job.getExecutionRoleArn());
        }
        if (job.getReleaseLabel() != null) {
            node.put("releaseLabel", job.getReleaseLabel());
        }
        if (job.getCreatedAt() != null) {
            node.put("createdAt", job.getCreatedAt());
        }
        if (job.getFinishedAt() != null) {
            node.put("finishedAt", job.getFinishedAt());
        }
        if (job.getTags() != null && !job.getTags().isEmpty()) {
            node.set("tags", objectMapper.valueToTree(job.getTags()));
        }
        return node;
    }

    ArrayNode toPublicClusters(List<VirtualCluster> clusters) {
        ArrayNode array = objectMapper.createArrayNode();
        for (VirtualCluster cluster : clusters) {
            array.add(toPublicCluster(cluster));
        }
        return array;
    }

    ArrayNode toPublicJobRuns(List<JobRun> jobs) {
        ArrayNode array = objectMapper.createArrayNode();
        for (JobRun job : jobs) {
            array.add(toPublicJobRun(job));
        }
        return array;
    }

    ObjectNode toPublicJobTemplate(JobTemplate template) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", template.getName());
        node.put("id", template.getId());
        node.put("arn", template.getArn());
        if (template.getCreatedAt() != null) {
            node.put("createdAt", template.getCreatedAt());
        }
        if (template.getCreatedBy() != null) {
            node.put("createdBy", template.getCreatedBy());
        }
        if (template.getTags() != null && !template.getTags().isEmpty()) {
            node.set("tags", objectMapper.valueToTree(template.getTags()));
        }
        if (template.getJobTemplateData() != null) {
            node.set("jobTemplateData", objectMapper.valueToTree(template.getJobTemplateData()));
        }
        if (template.getKmsKeyArn() != null) {
            node.put("kmsKeyArn", template.getKmsKeyArn());
        }
        return node;
    }

    ArrayNode toPublicJobTemplates(List<JobTemplate> items) {
        ArrayNode array = objectMapper.createArrayNode();
        for (JobTemplate template : items) {
            array.add(toPublicJobTemplate(template));
        }
        return array;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return new LinkedHashMap<>(requireByArn(region, arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        VirtualCluster cluster = requireByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(cluster.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        cluster.setTags(current);
        clusters.put(storageKey(region, cluster.getId()), cluster);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        VirtualCluster cluster = requireByArn(region, arn);
        if (tagKeys != null) {
            tagKeys.forEach(cluster.getTags()::remove);
        }
        clusters.put(storageKey(region, cluster.getId()), cluster);
    }

    private VirtualCluster requireByArn(String region, String arn) {
        String id = virtualClusterIdFromArn(arn);
        return describeVirtualCluster(region, id);
    }

    static String virtualClusterIdFromArn(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!SERVICE.equals(parsed.service())) {
                throw validation("Invalid resource ARN.");
            }
            String resource = parsed.resource();
            String prefix = "/virtualclusters/";
            if (resource == null || !resource.startsWith(prefix)) {
                throw validation("Invalid resource ARN.");
            }
            String id = resource.substring(prefix.length());
            int slash = id.indexOf('/');
            if (slash >= 0) {
                id = id.substring(0, slash);
            }
            if (id.isBlank()) {
                throw validation("Invalid resource ARN.");
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw validation("Invalid resource ARN.");
        }
    }

    private VirtualCluster requireLive(String region, String id) {
        VirtualCluster cluster = describeVirtualCluster(region, id);
        if (!LIVE_STATES.contains(cluster.getState())) {
            throw validation("Virtual cluster " + id + " is not in a valid state.");
        }
        return cluster;
    }

    private VirtualCluster findLiveByName(String region, String name) {
        for (VirtualCluster cluster : clusters.scan(key -> key.startsWith(region + "::"))) {
            if (name.equals(cluster.getName()) && LIVE_STATES.contains(cluster.getState())) {
                return cluster;
            }
        }
        return null;
    }

    private VirtualCluster findByClientToken(String region, String clientToken) {
        for (VirtualCluster cluster : clusters.scan(key -> key.startsWith(region + "::"))) {
            if (clientToken.equals(cluster.getClientToken())) {
                return cluster;
            }
        }
        return null;
    }

    private JobTemplate findTemplateByClientToken(String region, String clientToken) {
        for (JobTemplate template : templates.scan(key -> key.startsWith(region + "::"))) {
            if (clientToken.equals(template.getClientToken())) {
                return template;
            }
        }
        return null;
    }

    private JobRun findJobByClientToken(String region, String virtualClusterId, String clientToken) {
        String prefix = region + "::" + virtualClusterId + "::";
        for (JobRun job : jobRuns.scan(key -> key.startsWith(prefix))) {
            if (clientToken.equals(job.getClientToken())) {
                return job;
            }
        }
        return null;
    }

    private Map<String, Object> readContainerProvider(JsonNode node) {
        Map<String, Object> provider = objectMapper.convertValue(node, new TypeReference<>() {
        });
        Object type = provider.get("type");
        if (type == null || String.valueOf(type).isBlank()) {
            provider.put("type", "EKS");
        }
        Object id = provider.get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            throw validation("containerProvider.id is a required field.");
        }
        return provider;
    }

    private Map<String, String> readTags(JsonNode request) {
        if (request == null || !request.has("tags") || request.get("tags").isNull()) {
            return new LinkedHashMap<>();
        }
        JsonNode tagsNode = request.get("tags");
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw validation("tags values must be strings.");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        return tags;
    }

    private static List<String> flatten(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private static <T> Page<T> page(List<T> items, int maxResults, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String token = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), token);
    }

    private static int parseMaxResults(Integer value) {
        if (value == null) {
            return DEFAULT_MAX_RESULTS;
        }
        if (value < 1 || value > MAX_RESULTS) {
            throw validation("maxResults must be between 1 and 100.");
        }
        return value;
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static void validateName(String name) {
        if (name.length() < 1 || name.length() > 64 || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("name must match [.\\-_/#A-Za-z0-9]+ and contain at most 64 characters.");
        }
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 64) {
            throw validation("id is invalid.");
        }
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " is a required field.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private String arn(String region, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), "/virtualclusters/" + id)
                .toString();
    }

    private String jobTemplateArn(String region, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), "/jobtemplates/" + id)
                .toString();
    }

    private String jobArn(String region, String virtualClusterId, String id) {
        return AwsArnUtils.Arn.of(
                        SERVICE,
                        region,
                        regionResolver.getAccountId(),
                        "/virtualclusters/" + virtualClusterId + "/jobruns/" + id)
                .toString();
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String jobKey(String region, String virtualClusterId, String id) {
        return region + "::" + virtualClusterId + "::" + id;
    }

    private static String newId(int length) {
        String hex = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        return hex.substring(0, length);
    }

    private static String timestamp() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static AwsException notFound(String message) {
        // Distilled maps ResourceNotFoundException to HTTP 400 for this API.
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
