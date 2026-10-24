package io.github.hectorvent.floci.services.kinesisanalytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import io.github.hectorvent.floci.services.kinesisanalytics.model.Snapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kinesisanalytics.model.ApplicationStatus;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Dispatches Kinesis Analytics V2 (Managed Service for Apache Flink) actions for the
 * {@code application/x-amz-json-1.1} protocol, routed here by {@code AwsJson11Controller}
 * on the {@code KinesisAnalytics_20180523.} target prefix.
 */
@ApplicationScoped
public class KinesisAnalyticsV2JsonHandler {

    private final KinesisAnalyticsV2Service service;
    private final ObjectMapper objectMapper;

    @Inject
    public KinesisAnalyticsV2JsonHandler(KinesisAnalyticsV2Service service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateApplication" -> handleCreateApplication(request, region);
            case "CreateApplicationPresignedUrl" -> handleCreateApplicationPresignedUrl(request, region);
            case "DescribeApplication" -> handleDescribeApplication(request, region);
            case "ListApplications" -> handleListApplications(request, region);
            case "StartApplication" -> handleStartApplication(request, region);
            case "StopApplication" -> handleStopApplication(request, region);
            case "UpdateApplication" -> handleUpdateApplication(request, region);
            case "DeleteApplication" -> handleDeleteApplication(request, region);
            case "TagResource" -> handleTagResource(request);
            case "UntagResource" -> handleUntagResource(request);
            case "ListTagsForResource" -> handleListTagsForResource(request);
            case "CreateApplicationSnapshot" -> handleCreateApplicationSnapshot(request, region);
            case "DescribeApplicationSnapshot" -> handleDescribeApplicationSnapshot(request, region);
            case "ListApplicationSnapshots" -> handleListApplicationSnapshots(request, region);
            case "DeleteApplicationSnapshot" -> handleDeleteApplicationSnapshot(request, region);
            case "RollbackApplication" -> handleRollback(request);
            case "DescribeApplicationOperation" -> handleDescribeOperation(request);
            case "ListApplicationOperations" -> handleListOperations(request);
            case "DescribeApplicationVersion" -> handleDescribeVersion(request);
            case "ListApplicationVersions" -> handleListVersions(request);
            case "DeleteApplicationCloudWatchLoggingOption" -> handleLoggingOption(request, true);
            case "AddApplicationCloudWatchLoggingOption" -> handleLoggingOption(request, false);
            case "UpdateApplicationMaintenanceConfiguration" -> handleMaintenance(request);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation",
                            "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateApplication(JsonNode request, String region) {
        String applicationName = request.path("ApplicationName").asText(null);
        String runtimeEnvironment = request.path("RuntimeEnvironment").asText(null);
        String serviceExecutionRole = request.path("ServiceExecutionRole").asText(null);
        String applicationDescription = request.path("ApplicationDescription").asText(null);
        String applicationMode = request.path("ApplicationMode").asText(null);

        // Application code (the Flink JAR in S3) + parallelism, from ApplicationConfiguration.
        JsonNode appConfig = request.path("ApplicationConfiguration");
        JsonNode s3 = appConfig.path("ApplicationCodeConfiguration").path("CodeContent")
                .path("S3ContentLocation");
        String codeBucket = bucketFromArn(s3.path("BucketARN").asText(null));
        String codeKey = s3.path("FileKey").asText(null);
        String codeVersion = s3.path("ObjectVersion").asText(null);
        int parallelism = appConfig.path("FlinkApplicationConfiguration")
                .path("ParallelismConfiguration").path("Parallelism").asInt(1);
        Map<String, Map<String, String>> environmentProperties =
                parsePropertyGroups(appConfig.path("EnvironmentProperties").path("PropertyGroups"));
        JsonNode snapshotsEnabledNode = appConfig.path("ApplicationSnapshotConfiguration").path("SnapshotsEnabled");
        Boolean snapshotsEnabled = snapshotsEnabledNode.isMissingNode() || snapshotsEnabledNode.isNull()
                ? null : snapshotsEnabledNode.asBoolean();

        FlinkApplication app = service.createApplication(applicationName, runtimeEnvironment,
                serviceExecutionRole, applicationDescription, applicationMode,
                codeBucket, codeKey, codeVersion, parallelism, parseTags(request.path("Tags")),
                environmentProperties, snapshotsEnabled, region);
        return applicationDetailResponse(app);
    }

    private Response handleCreateApplicationPresignedUrl(JsonNode request, String region) {
        String applicationName = request.path("ApplicationName").asText(null);
        String urlType = request.path("UrlType").asText(null);
        Long sessionExpirationDurationInSeconds = request.hasNonNull("SessionExpirationDurationInSeconds")
                ? request.path("SessionExpirationDurationInSeconds").asLong() : null;
        String url = service.createApplicationPresignedUrl(applicationName, urlType,
                sessionExpirationDurationInSeconds, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AuthorizedUrl", url);
        return Response.ok(response).build();
    }

    /** Extracts the bucket name from an S3 bucket ARN ({@code arn:aws:s3:::bucket}). */
    private static String bucketFromArn(String bucketArn) {
        if (bucketArn == null) {
            return null;
        }
        return AwsArnUtils.resourceIfArnFor(bucketArn, "s3").orElse(bucketArn);
    }

    private Response handleDescribeApplication(JsonNode request, String region) {
        String applicationName = request.path("ApplicationName").asText(null);
        return applicationDetailResponse(service.describeApplication(applicationName, region));
    }

    private Response handleListApplications(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("ApplicationSummaries");
        for (FlinkApplication app : service.listApplications(region)) {
            ObjectNode summary = summaries.addObject();
            summary.put("ApplicationName", app.getApplicationName());
            summary.put("ApplicationARN", app.getApplicationArn());
            summary.put("ApplicationStatus", app.getApplicationStatus().name());
            summary.put("ApplicationVersionId", app.getApplicationVersionId());
            summary.put("RuntimeEnvironment", app.getRuntimeEnvironment());
            if (app.getApplicationMode() != null) {
                summary.put("ApplicationMode", app.getApplicationMode());
            }
        }
        return Response.ok(response).build();
    }

    private Response handleStartApplication(JsonNode request, String region) {
        String applicationName = request.path("ApplicationName").asText(null);
        service.startApplication(applicationName, region);
        // AWS StartApplication returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleStopApplication(JsonNode request, String region) {
        String applicationName = request.path("ApplicationName").asText(null);
        service.stopApplication(applicationName, region);
        // AWS StopApplication returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUpdateApplication(JsonNode request, String region) {
        String applicationName = request.path("ApplicationName").asText(null);
        // CurrentApplicationVersionId gates the update (optimistic concurrency); the service rejects a
        // stale or missing value.
        Long currentVersionId = request.hasNonNull("CurrentApplicationVersionId")
                ? request.path("CurrentApplicationVersionId").asLong()
                : null;
        String serviceExecutionRole = request.path("ServiceExecutionRoleUpdate").asText(null);

        // ApplicationConfigurationUpdate.ApplicationCodeConfigurationUpdate.CodeContentUpdate
        // .S3ContentLocationUpdate: a new JAR location to redeploy in place.
        JsonNode appCfgUpdate = request.path("ApplicationConfigurationUpdate");
        JsonNode s3Update = appCfgUpdate.path("ApplicationCodeConfigurationUpdate")
                .path("CodeContentUpdate").path("S3ContentLocationUpdate");
        String codeBucket = bucketFromArn(s3Update.path("BucketARNUpdate").asText(null));
        String codeKey = s3Update.path("FileKeyUpdate").asText(null);
        String codeVersion = s3Update.path("ObjectVersionUpdate").asText(null);
        JsonNode parallelismUpdate = appCfgUpdate.path("FlinkApplicationConfigurationUpdate")
                .path("ParallelismConfigurationUpdate").path("ParallelismUpdate");
        Integer parallelism = parallelismUpdate.isMissingNode() || parallelismUpdate.isNull()
                ? null : parallelismUpdate.asInt();
        JsonNode snapshotsEnabledUpdate = appCfgUpdate.path("ApplicationSnapshotConfigurationUpdate")
                .path("SnapshotsEnabledUpdate");
        Boolean snapshotsEnabled = snapshotsEnabledUpdate.isMissingNode() || snapshotsEnabledUpdate.isNull()
                ? null : snapshotsEnabledUpdate.asBoolean();

        return applicationDetailResponse(service.updateApplication(applicationName, currentVersionId,
                serviceExecutionRole, codeBucket, codeKey, codeVersion, parallelism, snapshotsEnabled, region));
    }

    private Response handleDeleteApplication(JsonNode request, String region) {
        String applicationName = request.path("ApplicationName").asText(null);
        // CreateTimestamp is epoch seconds on the wire (possibly fractional); the service validates it
        // against the stored value.
        Instant createTimestamp = request.hasNonNull("CreateTimestamp")
                ? Instant.ofEpochMilli(Math.round(request.path("CreateTimestamp").asDouble() * 1000))
                : null;
        service.deleteApplication(applicationName, createTimestamp, region);
        // AWS DeleteApplication returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleCreateApplicationSnapshot(JsonNode request, String region) {
        String applicationName = request.path("ApplicationName").asText(null);
        String snapshotName = request.path("SnapshotName").asText(null);
        service.createApplicationSnapshot(applicationName, snapshotName, region);
        // AWS CreateApplicationSnapshot returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeApplicationSnapshot(JsonNode request, String region) {
        String applicationName = request.path("ApplicationName").asText(null);
        String snapshotName = request.path("SnapshotName").asText(null);
        Snapshot snapshot = service.describeApplicationSnapshot(applicationName, snapshotName, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SnapshotDetails", snapshotDetailNode(snapshot));
        return Response.ok(response).build();
    }

    private Response handleListApplicationSnapshots(JsonNode request, String region) {
        String applicationName = request.path("ApplicationName").asText(null);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("SnapshotSummaries");
        for (Snapshot snapshot : service.listApplicationSnapshots(applicationName, region)) {
            summaries.add(snapshotDetailNode(snapshot));
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteApplicationSnapshot(JsonNode request, String region) {
        String applicationName = request.path("ApplicationName").asText(null);
        String snapshotName = request.path("SnapshotName").asText(null);
        Instant snapshotCreationTimestamp = request.hasNonNull("SnapshotCreationTimestamp")
                ? Instant.ofEpochMilli(Math.round(request.path("SnapshotCreationTimestamp").asDouble() * 1000))
                : null;
        service.deleteApplicationSnapshot(applicationName, snapshotName, snapshotCreationTimestamp, region);
        // AWS DeleteApplicationSnapshot returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode snapshotDetailNode(Snapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("SnapshotName", snapshot.getSnapshotName());
        node.put("SnapshotStatus", snapshot.getSnapshotStatus().name());
        node.put("ApplicationVersionId", snapshot.getApplicationVersionId());
        if (snapshot.getSnapshotCreationTimestamp() != null) {
            node.put("SnapshotCreationTimestamp", snapshot.getSnapshotCreationTimestamp().toEpochMilli() / 1000.0);
        }
        if (snapshot.getRuntimeEnvironment() != null) {
            node.put("RuntimeEnvironment", snapshot.getRuntimeEnvironment());
        }
        return node;
    }

    private Response handleTagResource(JsonNode request) {
        String resourceArn = request.path("ResourceARN").asText(null);
        service.tagResource(resourceArn, parseTags(request.path("Tags")));
        // AWS TagResource returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request) {
        String resourceArn = request.path("ResourceARN").asText(null);
        List<String> tagKeys = new ArrayList<>();
        request.path("TagKeys").forEach(k -> tagKeys.add(k.asText()));
        service.untagResource(resourceArn, tagKeys);
        // AWS UntagResource returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode request) {
        String resourceArn = request.path("ResourceARN").asText(null);
        Map<String, String> tags = service.listTagsForResource(resourceArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", tagsNode(tags));
        return Response.ok(response).build();
    }

    /** {@code Tags} is a list of {@code {Key, Value}} objects on this API, not a string map. */
    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = tag.path("Key").asText(null);
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(null));
                }
            }
        }
        return tags;
    }

    private ArrayNode tagsNode(Map<String, String> tags) {
        ArrayNode arr = objectMapper.createArrayNode();
        tags.forEach((k, v) -> {
            ObjectNode tag = arr.addObject();
            tag.put("Key", k);
            if (v != null) {
                tag.put("Value", v);
            }
        });
        return arr;
    }

    private Response applicationDetailResponse(FlinkApplication app) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ApplicationDetail", applicationDetailNode(app));
        return Response.ok(response).build();
    }

    private ObjectNode applicationDetailNode(FlinkApplication app) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("ApplicationARN", app.getApplicationArn());
        detail.put("ApplicationName", app.getApplicationName());
        if (app.getApplicationDescription() != null) {
            detail.put("ApplicationDescription", app.getApplicationDescription());
        }
        detail.put("RuntimeEnvironment", app.getRuntimeEnvironment());
        if (app.getServiceExecutionRole() != null) {
            detail.put("ServiceExecutionRole", app.getServiceExecutionRole());
        }
        detail.put("ApplicationStatus", app.getApplicationStatus().name());
        detail.put("ApplicationVersionId", app.getApplicationVersionId());
        if (app.getApplicationMode() != null) {
            detail.put("ApplicationMode", app.getApplicationMode());
        }
        if (app.getCreateTimestamp() != null) {
            detail.put("CreateTimestamp", app.getCreateTimestamp().toEpochMilli() / 1000.0);
        }
        if (app.getLastUpdateTimestamp() != null) {
            detail.put("LastUpdateTimestamp", app.getLastUpdateTimestamp().toEpochMilli() / 1000.0);
        }
        if (app.hasCode()) {
            detail.set("ApplicationConfigurationDescription", applicationConfigurationNode(app));
        }
        return detail;
    }

    private ObjectNode applicationConfigurationNode(FlinkApplication app) {
        ObjectNode config = objectMapper.createObjectNode();

        ObjectNode codeDesc = config.putObject("ApplicationCodeConfigurationDescription");
        codeDesc.put("CodeContentType", "ZIPFILE");
        ObjectNode s3Desc = codeDesc.putObject("CodeContentDescription")
                .putObject("S3ApplicationCodeLocationDescription");
        s3Desc.put("BucketARN", AwsArnUtils.Arn.global(AwsArnUtils.parse(app.getApplicationArn()).partition(),
                "s3", "", app.getCodeS3Bucket()).toString());
        s3Desc.put("FileKey", app.getCodeS3Key());
        if (app.getCodeS3ObjectVersion() != null) {
            s3Desc.put("ObjectVersion", app.getCodeS3ObjectVersion());
        }

        config.putObject("FlinkApplicationConfigurationDescription")
                .putObject("ParallelismConfigurationDescription")
                .put("Parallelism", app.getParallelism())
                .put("CurrentParallelism", app.getParallelism());

        if (!app.getEnvironmentProperties().isEmpty()) {
            ArrayNode groups = config.putObject("EnvironmentPropertyDescriptions")
                    .putArray("PropertyGroupDescriptions");
            app.getEnvironmentProperties().forEach((groupId, properties) -> {
                ObjectNode group = groups.addObject();
                group.put("PropertyGroupId", groupId);
                ObjectNode map = group.putObject("PropertyMap");
                properties.forEach(map::put);
            });
        }

        config.putObject("ApplicationSnapshotConfigurationDescription")
                .put("SnapshotsEnabled", app.isSnapshotsEnabled());

        return config;
    }

    /** {@code ApplicationConfiguration.EnvironmentProperties.PropertyGroups}: a list of
     *  {@code {PropertyGroupId, PropertyMap}} objects, keyed by PropertyGroupId internally since
     *  that's how a Flink app looks a group up via {@code KinesisAnalyticsRuntime.getApplicationProperties()}. */
    private Map<String, Map<String, String>> parsePropertyGroups(JsonNode propertyGroupsNode) {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        if (propertyGroupsNode != null && propertyGroupsNode.isArray()) {
            for (JsonNode group : propertyGroupsNode) {
                String groupId = group.path("PropertyGroupId").asText(null);
                if (groupId == null) {
                    continue;
                }
                Map<String, String> properties = new LinkedHashMap<>();
                group.path("PropertyMap").fields().forEachRemaining(
                        entry -> properties.put(entry.getKey(), entry.getValue().asText(null)));
                groups.put(groupId, properties);
            }
        }
        return groups;
    }

    private Response handleCreateApplication(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        String runtimeEnvironment = request.path("RuntimeEnvironment").asText(null);
        String serviceExecutionRole = request.path("ServiceExecutionRole").asText(null);
        String applicationDescription = request.path("ApplicationDescription").asText(null);
        String applicationMode = request.path("ApplicationMode").asText(null);

        // Application code (the Flink JAR in S3) + parallelism, from ApplicationConfiguration.
        JsonNode appConfig = request.path("ApplicationConfiguration");
        JsonNode s3 = appConfig.path("ApplicationCodeConfiguration").path("CodeContent")
                .path("S3ContentLocation");
        String codeBucket = bucketFromArn(s3.path("BucketARN").asText(null));
        String codeKey = s3.path("FileKey").asText(null);
        String codeVersion = s3.path("ObjectVersion").asText(null);
        int parallelism = appConfig.path("FlinkApplicationConfiguration")
                .path("ParallelismConfiguration").path("Parallelism").asInt(1);
        Map<String, Map<String, String>> environmentProperties =
                parsePropertyGroups(appConfig.path("EnvironmentProperties").path("PropertyGroups"));
        JsonNode snapshotsEnabledNode = appConfig.path("ApplicationSnapshotConfiguration").path("SnapshotsEnabled");
        Boolean snapshotsEnabled = snapshotsEnabledNode.isMissingNode() || snapshotsEnabledNode.isNull()
                ? null : snapshotsEnabledNode.asBoolean();

        FlinkApplication app = service.createApplication(applicationName, runtimeEnvironment,
                serviceExecutionRole, applicationDescription, applicationMode,
                codeBucket, codeKey, codeVersion, parallelism, parseTags(request.path("Tags")),
                environmentProperties, snapshotsEnabled, appConfig.path("FlinkApplicationConfiguration"),
                parseLoggingStreams(request.path("CloudWatchLoggingOptions")));
        return applicationDetailResponse(app);
    }

    private Response handleMaintenance(JsonNode request) {
        FlinkApplication app = service.updateMaintenance(request.path("ApplicationName").asText(null),
                request.path("ApplicationMaintenanceConfigurationUpdate")
                        .path("ApplicationMaintenanceWindowStartTimeUpdate").asText(null));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ApplicationARN", app.getApplicationArn());
        response.set("ApplicationMaintenanceConfigurationDescription", maintenanceNode(app));
        return Response.ok(response).build();
    }

    private Response handleLoggingOption(JsonNode request, boolean delete) {
        String name = request.path("ApplicationName").asText(null);
        Long version = optionalLong(request, "CurrentApplicationVersionId");
        String token = request.path("ConditionalToken").asText(null);
        FlinkApplication app = delete
                ? service.deleteLoggingOption(name, version, token, request.path("CloudWatchLoggingOptionId").asText(null))
                : service.addLoggingOption(name, version, token,
                        request.path("CloudWatchLoggingOption").path("LogStreamARN").asText(null));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ApplicationARN", app.getApplicationArn());
        response.put("ApplicationVersionId", app.getApplicationVersionId());
        response.set("CloudWatchLoggingOptionDescriptions", loggingOptionsNode(app));
        List<String> ids = new ArrayList<>(app.getOperations().keySet());
        response.put("OperationId", ids.getLast());
        return Response.ok(response).build();
    }

    private Response handleDescribeVersion(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ApplicationVersionDetail", applicationDetailNode(service.describeApplicationVersion(
                request.path("ApplicationName").asText(null), optionalLong(request, "ApplicationVersionId"))));
        return Response.ok(response).build();
    }

    private Response handleListVersions(JsonNode request) {
        String name = request.path("ApplicationName").asText(null);
        List<ObjectNode> versions = service.listApplicationVersions(name).stream().map(app ->
                objectMapper.createObjectNode().put("ApplicationVersionId", app.getApplicationVersionId())
                        .put("ApplicationStatus", app.getApplicationStatus().name())).toList();
        return page(request, "ApplicationVersionSummaries", versions, applicationScope(name) + ":versions");
    }

    private Response handleListOperations(JsonNode request) {
        String name = request.path("ApplicationName").asText(null);
        String operation = request.path("Operation").asText(null);
        String status = request.path("OperationStatus").asText(null);
        if (status != null && !List.of("IN_PROGRESS", "CANCELLED", "SUCCESSFUL", "FAILED").contains(status)) {
            throw new AwsException("InvalidArgumentException", "Invalid OperationStatus", 400);
        }
        List<ObjectNode> operations = service.listApplicationOperations(name, operation, status);
        operations.forEach(info -> info.remove("ApplicationVersionChangeDetails"));
        return page(request, "ApplicationOperationInfoList", operations,
                applicationScope(name) + ":operations:" + operation + ":" + status);
    }

    private Response handleDescribeOperation(JsonNode request) {
        ObjectNode operation = service.describeApplicationOperation(request.path("ApplicationName").asText(null),
                request.path("OperationId").asText(null));
        operation.remove("OperationId");
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ApplicationOperationInfoDetails", operation);
        return Response.ok(response).build();
    }

    private Response handleRollback(JsonNode request) {
        service.rollbackApplication(request.path("ApplicationName").asText(null),
                optionalLong(request, "CurrentApplicationVersionId"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private String applicationScope(String name) {
        FlinkApplication app = service.describeApplication(name);
        return app.getApplicationArn() + ":" + app.getCreateTimestamp();
    }

    private Response page(JsonNode request, String field, List<ObjectNode> values, String scope) {
        Long requested = optionalLong(request, "Limit");
        if (requested != null && (requested < 1 || requested > 50)) {
            throw new AwsException("InvalidArgumentException", "Limit must be between 1 and 50", 400);
        }
        int limit = requested == null ? 50 : requested.intValue();
        int start = 0;
        if (request.has("NextToken")) {
            try {
                String token = new String(Base64.getUrlDecoder().decode(request.path("NextToken").asText()),
                        StandardCharsets.UTF_8);
                String prefix = scope + "|";
                if (!token.startsWith(prefix)) {
                    throw new IllegalArgumentException("Token scope mismatch");
                }
                start = Integer.parseInt(token.substring(prefix.length()));
                if (start < 1 || start >= values.size()) {
                    throw new IllegalArgumentException("Token is out of range");
                }
            } catch (IllegalArgumentException invalid) {
                throw new AwsException("InvalidArgumentException", "Invalid NextToken", 400);
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        int end = Math.min(values.size(), start + limit);
        ArrayNode array = response.putArray(field);
        values.subList(start, end).forEach(array::add);
        if (end < values.size()) {
            response.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((scope + "|" + end).getBytes(StandardCharsets.UTF_8)));
        }
        return Response.ok(response).build();
    }

    private static Long optionalLong(JsonNode request, String field) {
        if (!request.hasNonNull(field)) {
            return null;
        }
        JsonNode value = request.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new AwsException("InvalidArgumentException", field + " must be an integer", 400);
        }
        return value.longValue();
    }

    private List<String> parseLoggingStreams(JsonNode options) {
        List<String> streams = new ArrayList<>();
        if (!options.isMissingNode() && !options.isArray()) {
            throw new AwsException("InvalidArgumentException", "CloudWatchLoggingOptions must be an array", 400);
        }
        options.forEach(option -> streams.add(option.path("LogStreamARN").asText(null)));
        return streams;
    }

    private Response handleCreateApplicationPresignedUrl(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        String urlType = request.path("UrlType").asText(null);
        Long sessionExpirationDurationInSeconds = request.hasNonNull("SessionExpirationDurationInSeconds")
                ? request.path("SessionExpirationDurationInSeconds").asLong() : null;
        String url = service.createApplicationPresignedUrl(applicationName, urlType,
                sessionExpirationDurationInSeconds);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AuthorizedUrl", url);
        return Response.ok(response).build();
    }

    private Response handleDescribeApplication(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        return applicationDetailResponse(service.describeApplication(applicationName));
    }

    private Response handleListApplications(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("ApplicationSummaries");
        for (FlinkApplication app : service.listApplications()) {
            ObjectNode summary = summaries.addObject();
            summary.put("ApplicationName", app.getApplicationName());
            summary.put("ApplicationARN", app.getApplicationArn());
            summary.put("ApplicationStatus", app.getApplicationStatus().name());
            summary.put("ApplicationVersionId", app.getApplicationVersionId());
            summary.put("RuntimeEnvironment", app.getRuntimeEnvironment());
            if (app.getApplicationMode() != null) {
                summary.put("ApplicationMode", app.getApplicationMode());
            }
        }
        return Response.ok(response).build();
    }

    private Response handleStartApplication(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        service.validateRestore(applicationName, request.path("RunConfiguration").path("ApplicationRestoreConfiguration"));
        service.startApplication(applicationName);
        // AWS StartApplication returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleStopApplication(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        service.stopApplication(applicationName);
        // AWS StopApplication returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUpdateApplication(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        // The service validates the version or conditional token before mutating configuration.
        Long currentVersionId = optionalLong(request, "CurrentApplicationVersionId");
        String serviceExecutionRole = request.path("ServiceExecutionRoleUpdate").asText(null);

        // ApplicationConfigurationUpdate.ApplicationCodeConfigurationUpdate.CodeContentUpdate
        // .S3ContentLocationUpdate: a new JAR location to redeploy in place.
        JsonNode appCfgUpdate = request.path("ApplicationConfigurationUpdate");
        JsonNode s3Update = appCfgUpdate.path("ApplicationCodeConfigurationUpdate")
                .path("CodeContentUpdate").path("S3ContentLocationUpdate");
        String codeBucket = bucketFromArn(s3Update.path("BucketARNUpdate").asText(null));
        String codeKey = s3Update.path("FileKeyUpdate").asText(null);
        String codeVersion = s3Update.path("ObjectVersionUpdate").asText(null);
        JsonNode parallelismUpdate = appCfgUpdate.path("FlinkApplicationConfigurationUpdate")
                .path("ParallelismConfigurationUpdate").path("ParallelismUpdate");
        Integer parallelism = parallelismUpdate.isMissingNode() || parallelismUpdate.isNull()
                ? null : parallelismUpdate.asInt();
        JsonNode snapshotsEnabledUpdate = appCfgUpdate.path("ApplicationSnapshotConfigurationUpdate")
                .path("SnapshotsEnabledUpdate");
        Boolean snapshotsEnabled = snapshotsEnabledUpdate.isMissingNode() || snapshotsEnabledUpdate.isNull()
                ? null : snapshotsEnabledUpdate.asBoolean();

        JsonNode propertyUpdates = appCfgUpdate.path("EnvironmentPropertyUpdates");
        if (!propertyUpdates.isMissingNode() && !propertyUpdates.path("PropertyGroups").isArray()) {
            throw new AwsException("InvalidArgumentException", "PropertyGroups must be an array", 400);
        }
        Map<String, Map<String, String>> properties = propertyUpdates.isMissingNode()
                ? null : parsePropertyGroups(propertyUpdates.path("PropertyGroups"));
        if (request.has("CloudWatchLoggingOptionUpdates") && !request.get("CloudWatchLoggingOptionUpdates").isArray()) {
            throw new AwsException("InvalidArgumentException", "CloudWatchLoggingOptionUpdates must be an array", 400);
        }
        Map<String, String> loggingUpdates = new LinkedHashMap<>();
        for (JsonNode option : request.path("CloudWatchLoggingOptionUpdates")) {
            String id = option.path("CloudWatchLoggingOptionId").asText(null);
            String stream = option.path("LogStreamARNUpdate").asText(null);
            if (id == null || stream == null || loggingUpdates.put(id, stream) != null) {
                throw new AwsException("InvalidArgumentException", "Invalid CloudWatchLoggingOptionUpdates", 400);
            }
        }
        FlinkApplication app = service.updateApplication(applicationName, currentVersionId,
                serviceExecutionRole, codeBucket, codeKey, codeVersion, parallelism, snapshotsEnabled,
                properties, appCfgUpdate.path("FlinkApplicationConfigurationUpdate"),
                request.path("RuntimeEnvironmentUpdate").asText(null),
                request.path("ConditionalToken").asText(null), loggingUpdates);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ApplicationDetail", applicationDetailNode(app));
        response.put("OperationId", new ArrayList<>(app.getOperations().keySet()).getLast());
        return Response.ok(response).build();
    }

    private Response handleDeleteApplication(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        // CreateTimestamp is epoch seconds on the wire (possibly fractional); the service validates it
        // against the stored value.
        Instant createTimestamp = request.hasNonNull("CreateTimestamp")
                ? Instant.ofEpochMilli(Math.round(request.path("CreateTimestamp").asDouble() * 1000))
                : null;
        service.deleteApplication(applicationName, createTimestamp);
        // AWS DeleteApplication returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleCreateApplicationSnapshot(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        String snapshotName = request.path("SnapshotName").asText(null);
        service.createApplicationSnapshot(applicationName, snapshotName);
        // AWS CreateApplicationSnapshot returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeApplicationSnapshot(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        String snapshotName = request.path("SnapshotName").asText(null);
        Snapshot snapshot = service.describeApplicationSnapshot(applicationName, snapshotName);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SnapshotDetails", snapshotDetailNode(snapshot));
        return Response.ok(response).build();
    }

    private Response handleListApplicationSnapshots(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("SnapshotSummaries");
        for (Snapshot snapshot : service.listApplicationSnapshots(applicationName)) {
            summaries.add(snapshotDetailNode(snapshot));
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteApplicationSnapshot(JsonNode request) {
        String applicationName = request.path("ApplicationName").asText(null);
        String snapshotName = request.path("SnapshotName").asText(null);
        Instant snapshotCreationTimestamp = request.hasNonNull("SnapshotCreationTimestamp")
                ? Instant.ofEpochMilli(Math.round(request.path("SnapshotCreationTimestamp").asDouble() * 1000))
                : null;
        service.deleteApplicationSnapshot(applicationName, snapshotName, snapshotCreationTimestamp);
        // AWS DeleteApplicationSnapshot returns an empty body.
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode maintenanceNode(FlinkApplication app) {
        String start = app.getMaintenanceWindowStartTime();
        return objectMapper.createObjectNode().put("ApplicationMaintenanceWindowStartTime", start)
                .put("ApplicationMaintenanceWindowEndTime", LocalTime.parse(start).plusHours(8)
                        .format(DateTimeFormatter.ofPattern("HH:mm")));
    }

    private ArrayNode loggingOptionsNode(FlinkApplication app) {
        ArrayNode options = objectMapper.createArrayNode();
        app.getCloudWatchLoggingOptions().forEach((id, stream) -> options.addObject()
                .put("CloudWatchLoggingOptionId", id).put("LogStreamARN", stream));
        return options;
    }
}
