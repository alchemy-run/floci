package io.github.hectorvent.floci.services.synthetics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.synthetics.model.Canary;
import io.github.hectorvent.floci.services.synthetics.model.CanaryRun;
import io.github.hectorvent.floci.services.synthetics.model.Group;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CloudWatch Synthetics restJson1 — canary and group lifecycle plus run
 * records. Tag APIs share {@code /tags/{arn}} via {@link TagHandler} using
 * ARN service {@code synthetics}.
 *
 * <p>StartCanary records an immediate PASSED run. A {@code rate(0 minute)}
 * schedule is a one-shot: the canary returns to {@code STOPPED} after that
 * run, matching AWS.
 */
@ApplicationScoped
public class SyntheticsService implements TagHandler {

    static final String SERVICE = "synthetics";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;
    private static final int MAX_GROUP_MEMBERS = 10;
    private static final String TOKEN_PREFIX = "synthetics:v1:";
    private static final Pattern CANARY_NAME = Pattern.compile("^[0-9a-z_-]{1,255}$");
    private static final Pattern GROUP_NAME = Pattern.compile("^[0-9a-zA-Z_-]{1,64}$");

    private final StorageBackend<String, Canary> canaries;
    private final StorageBackend<String, Group> groups;
    private final RegionResolver regionResolver;

    @Inject
    public SyntheticsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create(
                        SERVICE, "synthetics-canaries.json", new TypeReference<Map<String, Canary>>() {
                        }),
                storageFactory.create(
                        SERVICE, "synthetics-groups.json", new TypeReference<Map<String, Group>>() {
                        }),
                regionResolver);
    }

    SyntheticsService(
            StorageBackend<String, Canary> canaries,
            StorageBackend<String, Group> groups,
            RegionResolver regionResolver) {
        this.canaries = canaries;
        this.groups = groups;
        this.regionResolver = regionResolver;
    }

    public synchronized Canary createCanary(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateCanaryName(name);
        String key = canaryKey(region, name);
        if (canaries.get(key).isPresent()) {
            throw conflict("A canary with this name already exists.");
        }
        JsonNode code = requireObjectField(request, "Code");
        String handler = optionalText(code, "Handler");
        if (handler == null || handler.isBlank()) {
            handler = "index.handler";
        }
        String artifact = requireText(request, "ArtifactS3Location");
        if (!artifact.startsWith("s3://")) {
            artifact = "s3://" + artifact;
        }
        String roleArn = requireText(request, "ExecutionRoleArn");
        String runtime = requireText(request, "RuntimeVersion");
        JsonNode schedule = requireObjectField(request, "Schedule");
        String expression = requireText(schedule, "Expression");
        Long duration = optionalLong(schedule, "DurationInSeconds");

        long now = epochNow();
        String account = regionResolver.getAccountId();
        String id = UUID.randomUUID().toString();
        Canary canary = new Canary();
        canary.setId(id);
        canary.setName(name);
        canary.setHandler(handler);
        canary.setExecutionRoleArn(roleArn);
        canary.setRuntimeVersion(runtime);
        canary.setArtifactS3Location(artifact);
        canary.setScheduleExpression(expression);
        canary.setScheduleDurationInSeconds(duration);
        applyRunConfig(canary, request.get("RunConfig"));
        canary.setSuccessRetentionPeriodInDays(optionalInteger(request, "SuccessRetentionPeriodInDays"));
        canary.setFailureRetentionPeriodInDays(optionalInteger(request, "FailureRetentionPeriodInDays"));
        applyVpcConfig(canary, request.get("VpcConfig"));
        String cleanup = optionalText(request, "ProvisionedResourceCleanup");
        canary.setProvisionedResourceCleanup(cleanup == null ? "AUTOMATIC" : cleanup);
        canary.setState("READY");
        canary.setCreated(now);
        canary.setLastModified(now);
        canary.setEngineArn(
                "arn:aws:lambda:" + region + ":" + account + ":function:cwsyn-" + name + "-" + id);
        canary.setTags(readTags(request));
        canaries.put(key, canary);
        return canary;
    }

    public Canary getCanary(String region, String name) {
        return requireCanary(region, name);
    }

    public synchronized Canary updateCanary(String region, String name, JsonNode request) {
        Canary canary = requireCanary(region, name);
        requireObject(request, "Request body");
        if (request.has("Code") && request.get("Code").isObject()) {
            String handler = optionalText(request.get("Code"), "Handler");
            if (handler != null && !handler.isBlank()) {
                canary.setHandler(handler);
            }
        }
        if (request.has("ExecutionRoleArn")) {
            canary.setExecutionRoleArn(requireText(request, "ExecutionRoleArn"));
        }
        if (request.has("RuntimeVersion")) {
            canary.setRuntimeVersion(requireText(request, "RuntimeVersion"));
        }
        if (request.has("Schedule") && request.get("Schedule").isObject()) {
            JsonNode schedule = request.get("Schedule");
            if (schedule.has("Expression")) {
                canary.setScheduleExpression(requireText(schedule, "Expression"));
            }
            if (schedule.has("DurationInSeconds")) {
                canary.setScheduleDurationInSeconds(optionalLong(schedule, "DurationInSeconds"));
            }
        }
        if (request.has("RunConfig")) {
            applyRunConfig(canary, request.get("RunConfig"));
        }
        if (request.has("SuccessRetentionPeriodInDays")) {
            canary.setSuccessRetentionPeriodInDays(optionalInteger(request, "SuccessRetentionPeriodInDays"));
        }
        if (request.has("FailureRetentionPeriodInDays")) {
            canary.setFailureRetentionPeriodInDays(optionalInteger(request, "FailureRetentionPeriodInDays"));
        }
        if (request.has("VpcConfig")) {
            applyVpcConfig(canary, request.get("VpcConfig"));
        }
        if (request.has("ArtifactS3Location")) {
            String artifact = requireText(request, "ArtifactS3Location");
            if (!artifact.startsWith("s3://")) {
                artifact = "s3://" + artifact;
            }
            canary.setArtifactS3Location(artifact);
        }
        if (request.has("ProvisionedResourceCleanup")) {
            canary.setProvisionedResourceCleanup(requireText(request, "ProvisionedResourceCleanup"));
        }
        canary.setLastModified(epochNow());
        canaries.put(canaryKey(region, name), canary);
        return canary;
    }

    public synchronized void deleteCanary(String region, String name) {
        Canary canary = requireCanary(region, name);
        String canaryArn = canaryArn(region, regionResolver.getAccountId(), name);
        for (Group group : groups.values()) {
            if (group.getMembers().remove(canaryArn)) {
                group.setLastModifiedTime(epochNow());
                groups.put(group.getName(), group);
            }
        }
        canaries.delete(canaryKey(region, canary.getName()));
    }

    public Page<Canary> describeCanaries(String region, JsonNode request) {
        JsonNode body = emptyIfNull(request);
        int maxResults = parseMaxResults(body);
        String nextToken = optionalText(body, "NextToken");
        List<String> names = readStringList(body, "Names");
        List<Canary> items = canaries.scan(key -> key.startsWith(region + "::"));
        if (!names.isEmpty()) {
            items.removeIf(canary -> !names.contains(canary.getName()));
        }
        items.sort(Comparator.comparing(Canary::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResults, nextToken);
    }

    public Page<CanaryLastRun> describeCanariesLastRun(String region, JsonNode request) {
        JsonNode body = emptyIfNull(request);
        int maxResults = parseMaxResults(body);
        String nextToken = optionalText(body, "NextToken");
        List<String> names = readStringList(body, "Names");
        List<Canary> items = canaries.scan(key -> key.startsWith(region + "::"));
        if (!names.isEmpty()) {
            items.removeIf(canary -> !names.contains(canary.getName()));
        }
        items.sort(Comparator.comparing(Canary::getName, Comparator.nullsLast(String::compareTo)));
        List<CanaryLastRun> lastRuns = new ArrayList<>();
        for (Canary canary : items) {
            if (canary.getRuns() == null || canary.getRuns().isEmpty()) {
                continue;
            }
            lastRuns.add(new CanaryLastRun(canary.getName(), canary.getRuns().getFirst()));
        }
        return page(lastRuns, maxResults, nextToken);
    }

    public Page<CanaryRun> getCanaryRuns(String region, String name, JsonNode request) {
        Canary canary = requireCanary(region, name);
        JsonNode body = emptyIfNull(request);
        int maxResults = parseMaxResults(body);
        String nextToken = optionalText(body, "NextToken");
        List<CanaryRun> runs = new ArrayList<>(canary.getRuns());
        return page(runs, maxResults, nextToken);
    }

    public synchronized void startCanary(String region, String name) {
        Canary canary = requireCanary(region, name);
        String state = canary.getState();
        if ("RUNNING".equals(state) || "STARTING".equals(state)) {
            throw conflict("Canary " + name + " is already running.");
        }
        long now = epochNow();
        CanaryRun run = new CanaryRun();
        run.setId(UUID.randomUUID().toString());
        run.setName(name);
        run.setState("PASSED");
        run.setTestResult("PASSED");
        run.setStarted(now);
        run.setCompleted(now);
        run.setArtifactS3Location(canary.getArtifactS3Location());
        List<CanaryRun> runs = new ArrayList<>(canary.getRuns());
        runs.add(0, run);
        canary.setRuns(runs);
        canary.setLastStarted(now);
        canary.setLastModified(now);
        if (isOneShot(canary.getScheduleExpression())) {
            canary.setState("STOPPED");
            canary.setLastStopped(now);
        } else {
            canary.setState("RUNNING");
        }
        canaries.put(canaryKey(region, name), canary);
    }

    public synchronized void stopCanary(String region, String name) {
        Canary canary = requireCanary(region, name);
        String state = canary.getState();
        if (!"RUNNING".equals(state) && !"STARTING".equals(state)) {
            throw conflict("Canary " + name + " is not running.");
        }
        long now = epochNow();
        canary.setState("STOPPED");
        canary.setLastStopped(now);
        canary.setLastModified(now);
        canaries.put(canaryKey(region, name), canary);
    }

    public synchronized Group createGroup(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateGroupName(name);
        if (findGroup(name) != null) {
            throw conflict("A group with this name already exists.");
        }
        long now = epochNow();
        String id = UUID.randomUUID().toString();
        Group group = new Group();
        group.setId(id);
        group.setName(name);
        group.setArn(groupArn(region, regionResolver.getAccountId(), id));
        group.setCreatedTime(now);
        group.setLastModifiedTime(now);
        group.setTags(readTags(request));
        groups.put(name, group);
        return group;
    }

    public Group getGroup(String identifier) {
        Group group = findGroup(identifier);
        if (group == null) {
            throw resourceNotFound("group", identifier);
        }
        return group;
    }

    public synchronized void deleteGroup(String identifier) {
        Group group = getGroup(identifier);
        groups.delete(group.getName());
    }

    public Page<Group> listGroups(JsonNode request) {
        JsonNode body = emptyIfNull(request);
        int maxResults = parseMaxResults(body);
        String nextToken = optionalText(body, "NextToken");
        List<Group> items = new ArrayList<>(groups.values());
        items.sort(Comparator.comparing(Group::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResults, nextToken);
    }

    public synchronized void associateResource(String identifier, JsonNode request) {
        requireObject(request, "Request body");
        String resourceArn = requireText(request, "ResourceArn");
        Group group = getGroup(identifier);
        if (group.getMembers().contains(resourceArn)) {
            return;
        }
        if (group.getMembers().size() >= MAX_GROUP_MEMBERS) {
            throw new AwsException(
                    "ServiceQuotaExceededException",
                    "A group can contain at most " + MAX_GROUP_MEMBERS + " canaries.",
                    402);
        }
        List<String> members = new ArrayList<>(group.getMembers());
        members.add(resourceArn);
        group.setMembers(members);
        group.setLastModifiedTime(epochNow());
        groups.put(group.getName(), group);
    }

    public synchronized void disassociateResource(String identifier, JsonNode request) {
        requireObject(request, "Request body");
        String resourceArn = requireText(request, "ResourceArn");
        Group group = getGroup(identifier);
        if (!group.getMembers().remove(resourceArn)) {
            throw resourceNotFound("resource", resourceArn);
        }
        group.setLastModifiedTime(epochNow());
        groups.put(group.getName(), group);
    }

    public Page<String> listGroupResources(String identifier, JsonNode request) {
        Group group = getGroup(identifier);
        JsonNode body = emptyIfNull(request);
        int maxResults = parseMaxResults(body);
        String nextToken = optionalText(body, "NextToken");
        return page(new ArrayList<>(group.getMembers()), maxResults, nextToken);
    }

    public Page<Group> listAssociatedGroups(String resourceArn, JsonNode request) {
        JsonNode body = emptyIfNull(request);
        int maxResults = parseMaxResults(body);
        String nextToken = optionalText(body, "NextToken");
        List<Group> items = groups.values().stream()
                .filter(group -> group.getMembers().contains(resourceArn))
                .sorted(Comparator.comparing(Group::getName, Comparator.nullsLast(String::compareTo)))
                .toList();
        return page(new ArrayList<>(items), maxResults, nextToken);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(tagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = tagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        persistTags(region, tagged, current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = tagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        persistTags(region, tagged, current);
    }

    private void persistTags(String region, Tagged tagged, Map<String, String> tags) {
        if (tagged.canary() != null) {
            Canary canary = tagged.canary();
            canary.setTags(tags);
            canary.setLastModified(epochNow());
            canaries.put(canaryKey(region, canary.getName()), canary);
            return;
        }
        Group group = tagged.group();
        group.setTags(tags);
        group.setLastModifiedTime(epochNow());
        groups.put(group.getName(), group);
    }

    private Tagged tagged(String region, String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!SERVICE.equals(parsed.service())) {
                throw resourceNotFound("resource", arn);
            }
            String resource = parsed.resource();
            if (resource != null && resource.startsWith("canary:")) {
                String name = resource.substring("canary:".length());
                return new Tagged(requireCanary(region, name), null);
            }
            if (resource != null && resource.startsWith("group:")) {
                String id = resource.substring("group:".length());
                Group group = findGroup(id);
                if (group == null) {
                    throw resourceNotFound("group", arn);
                }
                return new Tagged(null, group);
            }
            throw resourceNotFound("resource", arn);
        } catch (IllegalArgumentException e) {
            throw resourceNotFound("resource", arn);
        }
    }

    private Canary requireCanary(String region, String name) {
        validateCanaryName(name);
        return canaries.get(canaryKey(region, name)).orElseThrow(() -> resourceNotFound("canary", name));
    }

    private Group findGroup(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        Group byName = groups.get(identifier).orElse(null);
        if (byName != null) {
            return byName;
        }
        for (Group group : groups.values()) {
            if (identifier.equals(group.getId()) || identifier.equals(group.getArn())) {
                return group;
            }
        }
        if (identifier.startsWith("arn:")) {
            try {
                AwsArnUtils.Arn parsed = AwsArnUtils.parse(identifier);
                String resource = parsed.resource();
                if (resource != null && resource.startsWith("group:")) {
                    String id = resource.substring("group:".length());
                    for (Group group : groups.values()) {
                        if (id.equals(group.getId()) || id.equals(group.getName())) {
                            return group;
                        }
                    }
                }
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void applyRunConfig(Canary canary, JsonNode runConfig) {
        if (runConfig == null || runConfig.isNull() || !runConfig.isObject()) {
            return;
        }
        if (runConfig.has("TimeoutInSeconds")) {
            canary.setTimeoutInSeconds(optionalInteger(runConfig, "TimeoutInSeconds"));
        }
        if (runConfig.has("MemoryInMB")) {
            canary.setMemoryInMB(optionalInteger(runConfig, "MemoryInMB"));
        }
        if (runConfig.has("ActiveTracing") && runConfig.get("ActiveTracing").isBoolean()) {
            canary.setActiveTracing(runConfig.get("ActiveTracing").booleanValue());
        }
        if (runConfig.has("EphemeralStorage")) {
            canary.setEphemeralStorage(optionalInteger(runConfig, "EphemeralStorage"));
        }
    }

    private static void applyVpcConfig(Canary canary, JsonNode vpc) {
        if (vpc == null || vpc.isNull() || !vpc.isObject()) {
            canary.setSubnetIds(List.of());
            canary.setSecurityGroupIds(List.of());
            return;
        }
        canary.setSubnetIds(readStringList(vpc, "SubnetIds"));
        canary.setSecurityGroupIds(readStringList(vpc, "SecurityGroupIds"));
    }

    private static boolean isOneShot(String expression) {
        if (expression == null) {
            return false;
        }
        String normalized = expression.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return normalized.startsWith("rate(0 ");
    }

    private static String canaryKey(String region, String name) {
        return region + "::" + name;
    }

    static String canaryArn(String region, String account, String name) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "canary:" + name).toString();
    }

    private static String groupArn(String region, String account, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "group:" + id).toString();
    }

    private static void validateCanaryName(String name) {
        if (name == null || !CANARY_NAME.matcher(name).matches()) {
            throw validation("Name must match ^[0-9a-z_-]{1,255}$.");
        }
    }

    private static void validateGroupName(String name) {
        if (name == null || !GROUP_NAME.matcher(name).matches()) {
            throw validation("Name must match ^[0-9a-zA-Z_-]{1,64}$.");
        }
    }

    private static long epochNow() {
        return Instant.now().getEpochSecond();
    }

    private static JsonNode emptyIfNull(JsonNode request) {
        return request == null || request.isNull() || request.isMissingNode() ? null : request;
    }

    private static Map<String, String> readTags(JsonNode request) {
        if (request == null || !request.has("Tags") || request.get("Tags").isNull()) {
            return new LinkedHashMap<>();
        }
        JsonNode tagsNode = request.get("Tags");
        if (!tagsNode.isObject() || tagsNode.size() > 50) {
            throw validation("Tags must be an object with at most 50 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (entry.getKey().isBlank() || value == null || !value.isTextual()) {
                throw validation("Tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), value.textValue());
        });
        return tags;
    }

    private static List<String> readStringList(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return List.of();
        }
        JsonNode array = parent.get(field);
        if (!array.isArray()) {
            throw validation(field + " must be an array of strings.");
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode value : array) {
            if (!value.isTextual()) {
                throw validation(field + " members must be strings.");
            }
            values.add(value.textValue());
        }
        return values;
    }

    private static JsonNode requireObjectField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
        return value;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        return value.isTextual() ? value.textValue() : null;
    }

    private static Integer optionalInteger(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isNumber()) {
            throw validation(field + " must be an integer.");
        }
        return value.intValue();
    }

    private static Long optionalLong(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isNumber()) {
            throw validation(field + " must be an integer.");
        }
        return value.longValue();
    }

    private static int parseMaxResults(JsonNode body) {
        if (body == null || !body.has("MaxResults") || body.get("MaxResults").isNull()) {
            return DEFAULT_MAX_RESULTS;
        }
        JsonNode value = body.get("MaxResults");
        if (!value.isNumber()) {
            throw validation("MaxResults must be an integer between 1 and 100.");
        }
        int parsed = value.intValue();
        if (parsed < 1 || parsed > MAX_RESULTS) {
            throw validation("MaxResults must be between 1 and 100.");
        }
        return parsed;
    }

    private static <T> Page<T> page(List<T> items, int maxResults, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), responseToken);
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("NextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw validation("NextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("NextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException resourceNotFound(String type, String id) {
        return new AwsException("ResourceNotFoundException", "The " + type + " " + id + " does not exist.", 404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record Page<T>(List<T> items, String nextToken) {
    }

    public record CanaryLastRun(String canaryName, CanaryRun lastRun) {
    }

    private record Tagged(Canary canary, Group group) {
        Map<String, String> tags() {
            return canary != null ? canary.getTags() : group.getTags();
        }
    }
}
