package io.github.hectorvent.floci.services.datasync;

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
import io.github.hectorvent.floci.services.datasync.model.DataSyncLocation;
import io.github.hectorvent.floci.services.datasync.model.DataSyncTask;
import io.github.hectorvent.floci.services.datasync.model.DataSyncTaskExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local AWS DataSync stub. Locations and tasks are metadata-only; task
 * executions transition immediately into {@code TRANSFERRING} so throttle
 * and cancel round-trips work without moving objects.
 *
 * @see <a href="https://docs.aws.amazon.com/datasync/latest/userguide/API_Operations.html">DataSync API</a>
 */
@ApplicationScoped
public class DataSyncService implements Resettable {

    private static final Set<String> THROTTLE_STATES = Set.of(
            "QUEUED", "LAUNCHING", "PREPARING", "TRANSFERRING", "VERIFYING");
    private static final Set<String> CANCEL_STATES = Set.of(
            "QUEUED", "LAUNCHING", "PREPARING", "TRANSFERRING", "VERIFYING");

    private final StorageBackend<String, DataSyncLocation> locations;
    private final StorageBackend<String, DataSyncTask> tasks;
    private final StorageBackend<String, DataSyncTaskExecution> executions;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public DataSyncService(StorageFactory factory, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.locations = factory.create("datasync", "datasync-locations.json",
                new TypeReference<Map<String, DataSyncLocation>>() {});
        this.tasks = factory.create("datasync", "datasync-tasks.json",
                new TypeReference<Map<String, DataSyncTask>>() {});
        this.executions = factory.create("datasync", "datasync-executions.json",
                new TypeReference<Map<String, DataSyncTaskExecution>>() {});
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        locations.clear();
        tasks.clear();
        executions.clear();
    }

    public ObjectNode listLocations(JsonNode request) {
        List<DataSyncLocation> all = new ArrayList<>(locations.values());
        all.sort((a, b) -> a.getLocationArn().compareTo(b.getLocationArn()));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Locations");
        for (DataSyncLocation location : paginate(all, request, DataSyncLocation::getLocationArn, response)) {
            ObjectNode entry = list.addObject();
            entry.put("LocationArn", location.getLocationArn());
            entry.put("LocationUri", location.getLocationUri());
        }
        return response;
    }

    public ObjectNode createLocationS3(JsonNode request, String region) {
        String bucketArn = requireText(request, "S3BucketArn");
        JsonNode s3Config = request.path("S3Config");
        String roleArn = s3Config.path("BucketAccessRoleArn").asText(null);
        if (roleArn == null || roleArn.isBlank()) {
            throw invalid("S3Config.BucketAccessRoleArn is required.");
        }
        String subdirectory = normalizeSubdirectory(textOrNull(request, "Subdirectory"));
        String bucket = bucketNameOf(bucketArn);
        String locationId = hexId("loc-");
        String arn = regionResolver.buildArn("datasync", region, "location/" + locationId);

        DataSyncLocation location = new DataSyncLocation();
        location.setLocationArn(arn);
        location.setLocationUri("s3://" + bucket + subdirectory);
        location.setKind("S3");
        location.setSubdirectory(subdirectory);
        location.setS3BucketArn(bucketArn);
        location.setS3StorageClass(textOr(request, "S3StorageClass", "STANDARD"));
        location.setBucketAccessRoleArn(roleArn);
        location.setAgentArns(stringList(request.path("AgentArns")));
        location.setCreationTime(now());
        applyTags(request, location.getTags());
        locations.put(arn, location);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("LocationArn", arn);
        return response;
    }

    public ObjectNode describeLocationS3(JsonNode request) {
        DataSyncLocation location = requireLocation(requireText(request, "LocationArn"));
        if (!"S3".equals(location.getKind())) {
            throw invalid("Location " + location.getLocationArn() + " is not an S3 location.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("LocationArn", location.getLocationArn());
        response.put("LocationUri", location.getLocationUri());
        response.put("S3StorageClass", location.getS3StorageClass());
        response.put("CreationTime", location.getCreationTime());
        ObjectNode s3Config = response.putObject("S3Config");
        s3Config.put("BucketAccessRoleArn", location.getBucketAccessRoleArn());
        if (location.getAgentArns() != null && !location.getAgentArns().isEmpty()) {
            ArrayNode agents = response.putArray("AgentArns");
            location.getAgentArns().forEach(agents::add);
        }
        return response;
    }

    public ObjectNode createLocationEfs(JsonNode request, String region) {
        String fsArn = requireText(request, "EfsFilesystemArn");
        JsonNode ec2 = request.path("Ec2Config");
        String subnetArn = ec2.path("SubnetArn").asText(null);
        if (subnetArn == null || subnetArn.isBlank()) {
            throw invalid("Ec2Config.SubnetArn is required.");
        }
        List<String> securityGroups = stringList(ec2.path("SecurityGroupArns"));
        if (securityGroups.isEmpty()) {
            throw invalid("Ec2Config.SecurityGroupArns is required.");
        }
        String subdirectory = normalizeSubdirectory(textOrNull(request, "Subdirectory"));
        String fsId = lastSegment(fsArn);
        String locationId = hexId("loc-");
        String arn = regionResolver.buildArn("datasync", region, "location/" + locationId);

        DataSyncLocation location = new DataSyncLocation();
        location.setLocationArn(arn);
        location.setLocationUri("efs://" + fsId + subdirectory);
        location.setKind("EFS");
        location.setSubdirectory(subdirectory);
        location.setEfsFilesystemArn(fsArn);
        location.setSubnetArn(subnetArn);
        location.setSecurityGroupArns(securityGroups);
        location.setAccessPointArn(textOrNull(request, "AccessPointArn"));
        location.setFileSystemAccessRoleArn(textOrNull(request, "FileSystemAccessRoleArn"));
        location.setInTransitEncryption(textOrNull(request, "InTransitEncryption"));
        location.setCreationTime(now());
        applyTags(request, location.getTags());
        locations.put(arn, location);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("LocationArn", arn);
        return response;
    }

    public ObjectNode describeLocationEfs(JsonNode request) {
        DataSyncLocation location = requireLocation(requireText(request, "LocationArn"));
        if (!"EFS".equals(location.getKind())) {
            throw invalid("Location " + location.getLocationArn() + " is not an EFS location.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("LocationArn", location.getLocationArn());
        response.put("LocationUri", location.getLocationUri());
        response.put("CreationTime", location.getCreationTime());
        ObjectNode ec2 = response.putObject("Ec2Config");
        ec2.put("SubnetArn", location.getSubnetArn());
        ArrayNode sgs = ec2.putArray("SecurityGroupArns");
        location.getSecurityGroupArns().forEach(sgs::add);
        putIfPresent(response, "AccessPointArn", location.getAccessPointArn());
        putIfPresent(response, "FileSystemAccessRoleArn", location.getFileSystemAccessRoleArn());
        putIfPresent(response, "InTransitEncryption", location.getInTransitEncryption());
        return response;
    }

    public ObjectNode deleteLocation(JsonNode request) {
        String arn = requireText(request, "LocationArn");
        requireLocation(arn);
        boolean inUse = tasks.values().stream().anyMatch(t ->
                arn.equals(t.getSourceLocationArn()) || arn.equals(t.getDestinationLocationArn()));
        if (inUse) {
            throw invalid("Location " + arn + " is currently in use.");
        }
        locations.delete(arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTasks(JsonNode request) {
        List<DataSyncTask> all = new ArrayList<>(tasks.values());
        all.sort((a, b) -> a.getTaskArn().compareTo(b.getTaskArn()));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Tasks");
        for (DataSyncTask task : paginate(all, request, DataSyncTask::getTaskArn, response)) {
            ObjectNode entry = list.addObject();
            entry.put("TaskArn", task.getTaskArn());
            entry.put("Status", task.getStatus());
            if (task.getName() != null) {
                entry.put("Name", task.getName());
            }
            entry.put("TaskMode", task.getTaskMode());
        }
        return response;
    }

    public ObjectNode createTask(JsonNode request, String region) {
        String source = requireText(request, "SourceLocationArn");
        String dest = requireText(request, "DestinationLocationArn");
        requireLocation(source);
        requireLocation(dest);
        String taskId = hexId("task-");
        String arn = regionResolver.buildArn("datasync", region, "task/" + taskId);

        DataSyncTask task = new DataSyncTask();
        task.setTaskArn(arn);
        task.setName(textOrNull(request, "Name"));
        task.setStatus("AVAILABLE");
        task.setSourceLocationArn(source);
        task.setDestinationLocationArn(dest);
        task.setCloudWatchLogGroupArn(textOrNull(request, "CloudWatchLogGroupArn"));
        task.setTaskMode(textOr(request, "TaskMode", "BASIC"));
        task.setOptions(objectMap(request.path("Options")));
        task.setExcludes(objectMapList(request.path("Excludes")));
        task.setIncludes(objectMapList(request.path("Includes")));
        task.setSchedule(objectMap(request.path("Schedule")));
        task.setCreationTime(now());
        applyTags(request, task.getTags());
        tasks.put(arn, task);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("TaskArn", arn);
        return response;
    }

    public ObjectNode describeTask(JsonNode request) {
        DataSyncTask task = requireTask(requireText(request, "TaskArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("TaskArn", task.getTaskArn());
        response.put("Status", task.getStatus());
        putIfPresent(response, "Name", task.getName());
        putIfPresent(response, "CurrentTaskExecutionArn", task.getCurrentTaskExecutionArn());
        response.put("SourceLocationArn", task.getSourceLocationArn());
        response.put("DestinationLocationArn", task.getDestinationLocationArn());
        putIfPresent(response, "CloudWatchLogGroupArn", task.getCloudWatchLogGroupArn());
        response.put("TaskMode", task.getTaskMode());
        response.put("CreationTime", task.getCreationTime());
        setIfPresent(response, "Options", task.getOptions());
        setListIfPresent(response, "Excludes", task.getExcludes());
        setListIfPresent(response, "Includes", task.getIncludes());
        setIfPresent(response, "Schedule", task.getSchedule());
        return response;
    }

    public ObjectNode updateTask(JsonNode request) {
        DataSyncTask task = requireTask(requireText(request, "TaskArn"));
        if (request.hasNonNull("Name")) {
            task.setName(request.get("Name").asText());
        }
        if (request.hasNonNull("CloudWatchLogGroupArn")) {
            task.setCloudWatchLogGroupArn(request.get("CloudWatchLogGroupArn").asText());
        }
        if (request.has("Options") && !request.get("Options").isNull()) {
            task.setOptions(objectMap(request.get("Options")));
        }
        if (request.has("Excludes") && !request.get("Excludes").isNull()) {
            task.setExcludes(objectMapList(request.get("Excludes")));
        }
        if (request.has("Includes") && !request.get("Includes").isNull()) {
            task.setIncludes(objectMapList(request.get("Includes")));
        }
        if (request.has("Schedule") && !request.get("Schedule").isNull()) {
            task.setSchedule(objectMap(request.get("Schedule")));
        }
        tasks.put(task.getTaskArn(), task);
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteTask(JsonNode request) {
        String arn = requireText(request, "TaskArn");
        requireTask(arn);
        for (DataSyncTaskExecution execution : executions.scan(k -> k.startsWith(arn + "/"))) {
            executions.delete(execution.getTaskExecutionArn());
        }
        tasks.delete(arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode startTaskExecution(JsonNode request) {
        DataSyncTask task = requireTask(requireText(request, "TaskArn"));
        String execId = hexId("exec-");
        String execArn = task.getTaskArn() + "/execution/" + execId;
        long started = now();

        DataSyncTaskExecution execution = new DataSyncTaskExecution();
        execution.setTaskExecutionArn(execArn);
        execution.setTaskArn(task.getTaskArn());
        execution.setStatus("TRANSFERRING");
        execution.setTaskMode(task.getTaskMode());
        execution.setBytesTransferred(0);
        Map<String, Object> options = task.getOptions() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(task.getOptions());
        Map<String, Object> override = objectMap(request.path("OverrideOptions"));
        if (override != null) {
            options.putAll(override);
        }
        execution.setOptions(options.isEmpty() ? null : options);
        execution.setStartTime(started);
        applyTags(request, execution.getTags());
        executions.put(execArn, execution);

        task.setStatus("RUNNING");
        task.setCurrentTaskExecutionArn(execArn);
        tasks.put(task.getTaskArn(), task);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("TaskExecutionArn", execArn);
        return response;
    }

    public ObjectNode listTaskExecutions(JsonNode request) {
        String taskArn = textOrNull(request, "TaskArn");
        List<DataSyncTaskExecution> all = new ArrayList<>(
                taskArn == null
                        ? executions.values()
                        : executions.scan(k -> k.startsWith(taskArn + "/")));
        all.sort((a, b) -> a.getTaskExecutionArn().compareTo(b.getTaskExecutionArn()));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("TaskExecutions");
        for (DataSyncTaskExecution execution : paginate(
                all, request, DataSyncTaskExecution::getTaskExecutionArn, response)) {
            ObjectNode entry = list.addObject();
            entry.put("TaskExecutionArn", execution.getTaskExecutionArn());
            entry.put("Status", execution.getStatus());
            entry.put("TaskMode", execution.getTaskMode());
        }
        return response;
    }

    public ObjectNode describeTaskExecution(JsonNode request) {
        DataSyncTaskExecution execution = requireExecution(requireText(request, "TaskExecutionArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("TaskExecutionArn", execution.getTaskExecutionArn());
        response.put("Status", execution.getStatus());
        response.put("TaskMode", execution.getTaskMode());
        response.put("BytesTransferred", execution.getBytesTransferred());
        response.put("StartTime", execution.getStartTime());
        if (execution.getEndTime() != null) {
            response.put("EndTime", execution.getEndTime());
        }
        setIfPresent(response, "Options", execution.getOptions());
        return response;
    }

    public ObjectNode updateTaskExecution(JsonNode request) {
        DataSyncTaskExecution execution = requireExecution(requireText(request, "TaskExecutionArn"));
        if (!THROTTLE_STATES.contains(execution.getStatus())) {
            throw invalid("Cannot update a task execution in " + execution.getStatus() + " status.");
        }
        JsonNode optionsNode = request.path("Options");
        if (optionsNode.isMissingNode() || optionsNode.isNull() || !optionsNode.isObject()) {
            throw invalid("Options is required.");
        }
        Map<String, Object> options = execution.getOptions() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(execution.getOptions());
        options.putAll(objectMap(optionsNode));
        execution.setOptions(options);
        executions.put(execution.getTaskExecutionArn(), execution);
        return objectMapper.createObjectNode();
    }

    public ObjectNode cancelTaskExecution(JsonNode request) {
        DataSyncTaskExecution execution = requireExecution(requireText(request, "TaskExecutionArn"));
        if (!CANCEL_STATES.contains(execution.getStatus())) {
            throw invalid("Cannot cancel a task execution in " + execution.getStatus() + " status.");
        }
        execution.setStatus("CANCELLING");
        execution.setEndTime(now());
        executions.put(execution.getTaskExecutionArn(), execution);

        DataSyncTask task = tasks.get(execution.getTaskArn()).orElse(null);
        if (task != null && execution.getTaskExecutionArn().equals(task.getCurrentTaskExecutionArn())) {
            task.setStatus("AVAILABLE");
            task.setCurrentTaskExecutionArn(null);
            tasks.put(task.getTaskArn(), task);
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        String arn = requireText(request, "ResourceArn");
        Map<String, String> tags = tagsFor(arn);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Tags");
        tags.forEach((k, v) -> {
            ObjectNode tag = list.addObject();
            tag.put("Key", k);
            tag.put("Value", v);
        });
        return response;
    }

    public ObjectNode tagResource(JsonNode request) {
        String arn = requireText(request, "ResourceArn");
        Map<String, String> tags = mutableTagsFor(arn);
        applyTags(request, tags);
        persistTags(arn, tags);
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        String arn = requireText(request, "ResourceArn");
        Map<String, String> tags = mutableTagsFor(arn);
        JsonNode keys = request.path("Keys");
        if (keys.isArray()) {
            keys.forEach(n -> tags.remove(n.asText()));
        }
        persistTags(arn, tags);
        return objectMapper.createObjectNode();
    }

    private DataSyncLocation requireLocation(String arn) {
        return locations.get(arn).orElseThrow(() ->
                invalid("Location " + arn + " is not found."));
    }

    private DataSyncTask requireTask(String arn) {
        return tasks.get(arn).orElseThrow(() ->
                invalid("Task " + arn + " is not found."));
    }

    private DataSyncTaskExecution requireExecution(String arn) {
        return executions.get(arn).orElseThrow(() ->
                invalid("Task execution " + arn + " is not found."));
    }

    private Map<String, String> tagsFor(String arn) {
        DataSyncLocation location = locations.get(arn).orElse(null);
        if (location != null) {
            return location.getTags();
        }
        DataSyncTask task = tasks.get(arn).orElse(null);
        if (task != null) {
            return task.getTags();
        }
        DataSyncTaskExecution execution = executions.get(arn).orElse(null);
        if (execution != null) {
            return execution.getTags();
        }
        throw invalid("Resource " + arn + " is not found.");
    }

    private Map<String, String> mutableTagsFor(String arn) {
        return tagsFor(arn);
    }

    private void persistTags(String arn, Map<String, String> tags) {
        DataSyncLocation location = locations.get(arn).orElse(null);
        if (location != null) {
            location.setTags(tags);
            locations.put(arn, location);
            return;
        }
        DataSyncTask task = tasks.get(arn).orElse(null);
        if (task != null) {
            task.setTags(tags);
            tasks.put(arn, task);
            return;
        }
        DataSyncTaskExecution execution = executions.get(arn).orElse(null);
        if (execution != null) {
            execution.setTags(tags);
            executions.put(arn, execution);
        }
    }

    private void applyTags(JsonNode request, Map<String, String> tags) {
        JsonNode node = request.path("Tags");
        if (!node.isArray()) {
            return;
        }
        node.forEach(t -> {
            String key = t.path("Key").asText(null);
            if (key != null) {
                tags.put(key, t.path("Value").asText(""));
            }
        });
    }

    private <T> List<T> paginate(List<T> all, JsonNode request,
                                 java.util.function.Function<T, String> tokenOf,
                                 ObjectNode response) {
        String nextToken = textOrNull(request, "NextToken");
        int maxResults = request.path("MaxResults").asInt(100);
        if (maxResults <= 0) {
            maxResults = 100;
        }
        int start = 0;
        if (nextToken != null) {
            for (int i = 0; i < all.size(); i++) {
                if (nextToken.equals(tokenOf.apply(all.get(i)))) {
                    start = i;
                    break;
                }
            }
        }
        int end = Math.min(all.size(), start + maxResults);
        List<T> page = all.subList(start, end);
        if (end < all.size()) {
            response.put("NextToken", tokenOf.apply(all.get(end)));
        }
        return page;
    }

    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return null;
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private List<Map<String, Object>> objectMapList(JsonNode node) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isObject()) {
                    list.add(objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {}));
                }
            });
        }
        return list;
    }

    private List<String> stringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> list.add(n.asText()));
        }
        return list;
    }

    private void setIfPresent(ObjectNode response, String field, Map<String, Object> value) {
        if (value != null && !value.isEmpty()) {
            response.set(field, objectMapper.valueToTree(value));
        }
    }

    private void setListIfPresent(ObjectNode response, String field, List<Map<String, Object>> value) {
        if (value != null && !value.isEmpty()) {
            response.set(field, objectMapper.valueToTree(value));
        }
    }

    private static void putIfPresent(ObjectNode response, String field, String value) {
        if (value != null && !value.isEmpty()) {
            response.put(field, value);
        }
    }

    private static String requireText(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            throw invalid(field + " is required.");
        }
        return node.asText();
    }

    private static String textOrNull(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isEmpty() ? null : text;
    }

    private static String textOr(JsonNode request, String field, String fallback) {
        String text = textOrNull(request, field);
        return text == null ? fallback : text;
    }

    private static String normalizeSubdirectory(String subdirectory) {
        if (subdirectory == null || subdirectory.isBlank()) {
            return "/";
        }
        return subdirectory.startsWith("/") ? subdirectory : "/" + subdirectory;
    }

    private static String bucketNameOf(String bucketArn) {
        int idx = bucketArn.indexOf(":::");
        if (idx < 0) {
            throw invalid("S3BucketArn is invalid.");
        }
        String name = bucketArn.substring(idx + 3);
        if (name.isBlank()) {
            throw invalid("S3BucketArn is invalid.");
        }
        return name;
    }

    private static String lastSegment(String arn) {
        int idx = arn.lastIndexOf('/');
        return idx < 0 ? arn : arn.substring(idx + 1);
    }

    private static String hexId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
