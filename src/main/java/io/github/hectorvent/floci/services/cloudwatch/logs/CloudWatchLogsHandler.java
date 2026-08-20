package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.Destination;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogGroup;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogStream;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.SubscriptionFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CloudWatchLogsHandler {

    private final CloudWatchLogsService logsService;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudWatchLogsHandler(CloudWatchLogsService logsService, ObjectMapper objectMapper) {
        this.logsService = logsService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateLogGroup" -> handleCreateLogGroup(request, region);
            case "DeleteLogGroup" -> handleDeleteLogGroup(request, region);
            case "DescribeLogGroups" -> handleDescribeLogGroups(request, region);
            case "CreateLogStream" -> handleCreateLogStream(request, region);
            case "DeleteLogStream" -> handleDeleteLogStream(request, region);
            case "DescribeLogStreams" -> handleDescribeLogStreams(request, region);
            case "PutLogEvents" -> handlePutLogEvents(request, region);
            case "GetLogEvents" -> handleGetLogEvents(request, region);
            case "FilterLogEvents" -> handleFilterLogEvents(request, region);
            case "PutRetentionPolicy" -> handlePutRetentionPolicy(request, region);
            case "PutLogGroupDeletionProtection" -> handlePutLogGroupDeletionProtection(request, region);
            case "DeleteRetentionPolicy" -> handleDeleteRetentionPolicy(request, region);
            case "TagLogGroup" -> handleTagLogGroup(request, region);
            case "UntagLogGroup" -> handleUntagLogGroup(request, region);
            case "ListTagsLogGroup" -> handleListTagsLogGroup(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "PutSubscriptionFilter" -> handlePutSubscriptionFilter(request, region);
            case "DescribeSubscriptionFilters" -> handleDescribeSubscriptionFilters(request, region);
            case "DeleteSubscriptionFilter" -> handleDeleteSubscriptionFilter(request, region);
            case "GetDataProtectionPolicy" -> handleGetDataProtectionPolicy(request, region);
            case "PutResourcePolicy" -> handlePutResourcePolicy(request, region);
            case "DescribeResourcePolicies" -> handleDescribeResourcePolicies(region);
            case "DeleteResourcePolicy" -> handleDeleteResourcePolicy(request, region);
            case "StartQuery" -> handleStartQuery(request, region);
            case "GetQueryResults" -> handleGetQueryResults(request, region);
            case "StopQuery" -> handleStopQuery(request, region);
            case "GetLogGroupFields" -> handleGetLogGroupFields(request, region);
            case "GetLogRecord" -> handleGetLogRecord(request, region);
            case "PutDestination" -> handlePutDestination(request, region);
            case "PutDestinationPolicy" -> handlePutDestinationPolicy(request, region);
            case "DescribeDestinations" -> handleDescribeDestinations(request, region);
            case "DeleteDestination" -> handleDeleteDestination(request, region);
            case "PutMetricFilter" -> handlePutMetricFilter(request, region);
            case "DescribeMetricFilters" -> handleDescribeMetricFilters(request, region);
            case "DeleteMetricFilter" -> handleDeleteMetricFilter(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handlePutResourcePolicy(JsonNode request, String region) {
        CloudWatchLogsService.ResourcePolicy policy = logsService.putResourcePolicy(
                request.path("policyName").asText(null),
                request.path("policyDocument").asText(null),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourcePolicy", resourcePolicyNode(policy));
        return Response.ok(response).build();
    }

    private Response handleDescribeResourcePolicies(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode policies = response.putArray("resourcePolicies");
        for (CloudWatchLogsService.ResourcePolicy policy : logsService.describeResourcePolicies(region)) {
            policies.add(resourcePolicyNode(policy));
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteResourcePolicy(JsonNode request, String region) {
        logsService.deleteResourcePolicy(request.path("policyName").asText(null), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode resourcePolicyNode(CloudWatchLogsService.ResourcePolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("policyName", policy.policyName());
        node.put("policyDocument", policy.policyDocument());
        node.put("lastUpdatedTime", policy.lastUpdatedTime());
        return node;
    }

    private Response handleCreateLogGroup(JsonNode request, String region) {
        String name = extractLogGroupNameFromArn(request.path("logGroupName").asText());
        Integer retentionInDays = request.has("retentionInDays")
                ? request.path("retentionInDays").asInt() : null;
        Map<String, String> tags = extractTags(request.path("tags"));
        String logGroupClass = textOrNull(request, "logGroupClass");
        Boolean deletionProtection = request.has("deletionProtectionEnabled")
                ? request.path("deletionProtectionEnabled").asBoolean() : null;
        String kmsKeyId = textOrNull(request, "kmsKeyId");
        logsService.createLogGroup(name, retentionInDays, tags, logGroupClass, deletionProtection, kmsKeyId, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteLogGroup(JsonNode request, String region) {
        String name = extractLogGroupNameFromArn(request.path("logGroupName").asText());
        logsService.deleteLogGroup(name, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeLogGroups(JsonNode request, String region) {
        String prefix = request.path("logGroupNamePrefix").asText(null);
        List<LogGroup> groups = logsService.describeLogGroups(prefix, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode groupsArray = objectMapper.createArrayNode();
        for (LogGroup g : groups) {
            ObjectNode node = objectMapper.createObjectNode();
            String arn = logsService.buildArn(g.getLogGroupName(), region);
            node.put("logGroupName", g.getLogGroupName());
            node.put("createdTime", g.getCreatedTime());
            node.put("arn", arn + ":*");
            node.put("logGroupArn", arn);
            node.put("logGroupClass", g.getLogGroupClass() != null ? g.getLogGroupClass() : "STANDARD");
            node.put("deletionProtectionEnabled", Boolean.TRUE.equals(g.getDeletionProtectionEnabled()));
            if (g.getRetentionInDays() != null) {
                node.put("retentionInDays", g.getRetentionInDays());
            }
            if (g.getKmsKeyId() != null) {
                node.put("kmsKeyId", g.getKmsKeyId());
            }
            node.put("storedBytes", 0);
            node.put("metricFilterCount", 0);
            groupsArray.add(node);
        }
        response.set("logGroups", groupsArray);
        return Response.ok(response).build();
    }

    private Response handleCreateLogStream(JsonNode request, String region) {
        String groupName = resolveLogGroupName(request);
        String streamName = resolveLogStreamName(request.path("logStreamName").asText());
        logsService.createLogStream(groupName, streamName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteLogStream(JsonNode request, String region) {
        String groupName = resolveLogGroupName(request);
        String streamName = resolveLogStreamName(request.path("logStreamName").asText());
        logsService.deleteLogStream(groupName, streamName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeLogStreams(JsonNode request, String region) {
        String groupName = resolveLogGroupName(request);
        String prefix = request.path("logStreamNamePrefix").asText(null);
        List<LogStream> streams = logsService.describeLogStreams(groupName, prefix, region);

        String logGroupArn = logsService.buildArn(groupName, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode streamsArray = objectMapper.createArrayNode();
        for (LogStream s : streams) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("logStreamName", s.getLogStreamName());
            node.put("arn", logGroupArn + ":log-stream:" + s.getLogStreamName());
            node.put("createdTime", s.getCreatedTime());
            node.put("lastIngestionTime", s.getLastIngestionTime());
            node.put("uploadSequenceToken", s.getUploadSequenceToken());
            node.put("storedBytes", s.getStoredBytes());
            if (s.getFirstEventTimestamp() != null) {
                node.put("firstEventTimestamp", s.getFirstEventTimestamp());
            }
            if (s.getLastEventTimestamp() != null) {
                node.put("lastEventTimestamp", s.getLastEventTimestamp());
            }
            streamsArray.add(node);
        }
        response.set("logStreams", streamsArray);
        return Response.ok(response).build();
    }

    private Response handlePutLogEvents(JsonNode request, String region) {
        String groupName = resolveLogGroupName(request);
        String streamName = request.path("logStreamName").asText();

        List<Map<String, Object>> events = new ArrayList<>();
        request.path("logEvents").forEach(evt -> {
            Map<String, Object> event = new HashMap<>();
            event.put("timestamp", evt.path("timestamp").asLong());
            event.put("message", evt.path("message").asText());
            events.add(event);
        });

        CloudWatchLogsService.PutLogEventsResult result =
                logsService.putLogEvents(groupName, streamName, events, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("nextSequenceToken", result.nextSequenceToken());
        if (result.tooNewLogEventStartIndex() != null) {
            ObjectNode rejected = objectMapper.createObjectNode();
            rejected.put("tooNewLogEventStartIndex", result.tooNewLogEventStartIndex());
            response.set("rejectedLogEventsInfo", rejected);
        }
        return Response.ok(response).build();
    }

    private Response handleGetLogEvents(JsonNode request, String region) {
        String groupName = resolveLogGroupName(request);
        String streamName = resolveLogStreamName(request.path("logStreamName").asText(null));
        Long startTime = request.has("startTime") ? request.path("startTime").asLong() : null;
        Long endTime = request.has("endTime") ? request.path("endTime").asLong() : null;
        int limit = request.path("limit").asInt(0);
        boolean startFromHead = request.path("startFromHead").asBoolean(false);
        String nextToken = request.has("nextToken") ? request.path("nextToken").asText(null) : null;

        CloudWatchLogsService.LogEventsResult result =
                logsService.getLogEvents(groupName, streamName, startTime, endTime, limit, startFromHead, nextToken, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("events", buildEventsArray(result.events()));
        response.put("nextForwardToken", result.nextForwardToken());
        response.put("nextBackwardToken", result.nextBackwardToken());
        return Response.ok(response).build();
    }

    private Response handleFilterLogEvents(JsonNode request, String region) {
        String groupName = resolveLogGroupName(request);
        Long startTime = request.has("startTime") ? request.path("startTime").asLong() : null;
        Long endTime = request.has("endTime") ? request.path("endTime").asLong() : null;
        String filterPattern = request.path("filterPattern").asText(null);
        int limit = request.path("limit").asInt(0);

        List<String> streamNames = new ArrayList<>();
        request.path("logStreamNames").forEach(n -> streamNames.add(resolveLogStreamName(n.asText(null))));

        CloudWatchLogsService.FilteredLogEventsResult result =
                logsService.filterLogEvents(groupName, streamNames, startTime, endTime, filterPattern, limit, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("events", buildEventsArray(result.events()));
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Logs Insights Queries ────────────────────────────

    private Response handleStartQuery(JsonNode request, String region) {
        // AWS requires exactly one of logGroupName / logGroupNames / logGroupIdentifiers.
        // The zero-selector case is enforced by the service (it throws when the resolved group
        // list is empty); here we reject the other invalid shape: more than one selector type.
        if (presentSelectorCount(request) > 1) {
            throw new AwsException("InvalidParameterException",
                    "Exactly one of logGroupName, logGroupNames, or logGroupIdentifiers may be specified.",
                    400);
        }

        List<String> logGroupNames = new ArrayList<>();
        request.path("logGroupNames").forEach(n -> logGroupNames.add(extractLogGroupNameFromArn(n.asText())));
        if (request.has("logGroupName")) {
            logGroupNames.add(extractLogGroupNameFromArn(request.path("logGroupName").asText()));
        }
        request.path("logGroupIdentifiers").forEach(n -> logGroupNames.add(extractLogGroupNameFromArn(n.asText())));

        long startTime = request.path("startTime").asLong();
        long endTime = request.path("endTime").asLong();
        String queryString = request.path("queryString").asText("");
        Integer limit = request.has("limit") ? request.path("limit").asInt() : null;

        String queryId = logsService.startQuery(logGroupNames, startTime, endTime, queryString, limit, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("queryId", queryId);
        return Response.ok(response).build();
    }

    /**
     * Counts how many of the three mutually exclusive StartQuery log-group selector <em>fields</em> the
     * request carries: {@code logGroupName}, {@code logGroupNames}, {@code logGroupIdentifiers}. A field
     * counts if it is present at all — even a blank string or empty array — using the same presence rule
     * ({@code has}) as the merge path below, so a serialized-but-empty selector cannot slip past the
     * mutual-exclusivity check. A single empty/blank selector yields no groups and is rejected downstream
     * by the service. (The AWS SDK omits unset list fields, so a well-formed single-selector request
     * still carries exactly one field.)
     */
    private int presentSelectorCount(JsonNode request) {
        int count = 0;
        if (request.has("logGroupName")) {
            count++;
        }
        if (request.has("logGroupNames")) {
            count++;
        }
        if (request.has("logGroupIdentifiers")) {
            count++;
        }
        return count;
    }

    private Response handleGetQueryResults(JsonNode request, String region) {
        String queryId = request.path("queryId").asText();
        CloudWatchLogsService.QueryState state = logsService.getQueryResults(queryId);

        ArrayNode results = objectMapper.createArrayNode();
        for (LinkedHashMap<String, String> row : state.rows()) {
            ArrayNode rowArray = objectMapper.createArrayNode();
            row.forEach((field, value) -> {
                ObjectNode fieldNode = objectMapper.createObjectNode();
                fieldNode.put("field", field);
                fieldNode.put("value", value);
                rowArray.add(fieldNode);
            });
            results.add(rowArray);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("results", results);
        response.put("status", state.status());
        ObjectNode statistics = objectMapper.createObjectNode();
        statistics.put("recordsMatched", (double) state.recordsMatched());
        statistics.put("recordsScanned", (double) state.recordsScanned());
        statistics.put("bytesScanned", 0.0);
        response.set("statistics", statistics);
        return Response.ok(response).build();
    }

    private Response handleStopQuery(JsonNode request, String region) {
        String queryId = request.path("queryId").asText();
        boolean success = logsService.stopQuery(queryId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("success", success);
        return Response.ok(response).build();
    }

    private Response handlePutRetentionPolicy(JsonNode request, String region) {
        String groupName = resolveLogGroupName(request);
        int days = request.path("retentionInDays").asInt();
        logsService.putRetentionPolicy(groupName, days, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handlePutLogGroupDeletionProtection(JsonNode request, String region) {
        // The op addresses the group by logGroupIdentifier (name or ARN).
        String groupName = resolveLogGroupName(request);
        boolean enabled = request.path("deletionProtectionEnabled").asBoolean(false);
        logsService.putLogGroupDeletionProtection(groupName, enabled, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteRetentionPolicy(JsonNode request, String region) {
        String groupName = resolveLogGroupName(request);
        logsService.deleteRetentionPolicy(groupName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleTagLogGroup(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        Map<String, String> tags = extractTags(request.path("tags"));
        logsService.tagLogGroup(groupName, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagLogGroup(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        List<String> tagKeys = new ArrayList<>();
        request.path("tags").forEach(k -> tagKeys.add(k.asText()));
        logsService.untagLogGroup(groupName, tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsLogGroup(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        Map<String, String> tags = logsService.listTagsLogGroup(groupName, region);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode tagsNode = objectMapper.createObjectNode();
        tags.forEach(tagsNode::put);
        response.set("tags", tagsNode);
        return Response.ok(response).build();
    }

    private Response handleListTagsForResource(JsonNode request, String region) {
        String resourceArn = request.path("resourceArn").asText();
        String groupName = extractLogGroupNameFromArn(resourceArn);
        Map<String, String> tags = logsService.listTagsLogGroup(groupName, region);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode tagsNode = objectMapper.createObjectNode();
        tags.forEach(tagsNode::put);
        response.set("tags", tagsNode);
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String resourceArn = request.path("resourceArn").asText();
        String groupName = extractLogGroupNameFromArn(resourceArn);
        Map<String, String> tags = extractTags(request.path("tags"));
        logsService.tagLogGroup(groupName, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String resourceArn = request.path("resourceArn").asText();
        String groupName = extractLogGroupNameFromArn(resourceArn);
        List<String> tagKeys = new ArrayList<>();
        request.path("tagKeys").forEach(k -> tagKeys.add(k.asText()));
        logsService.untagLogGroup(groupName, tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handlePutSubscriptionFilter(JsonNode request, String region) {
        String logGroupName = resolveLogGroupName(request);
        String filterName = request.path("filterName").asText();
        String filterPattern = request.path("filterPattern").asText();
        String destinationArn = request.path("destinationArn").asText();
        String roleArn = textOrNull(request, "roleArn");
        String distribution = request.has("distribution") ? request.path("distribution").asText(null) : null;

        logsService.putSubscriptionFilter(logGroupName, filterName, filterPattern, destinationArn, roleArn, distribution, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeSubscriptionFilters(JsonNode request, String region) {
        String logGroupName = resolveLogGroupName(request);
        String filterNamePrefix = request.path("filterNamePrefix").asText(null);
        String nextToken = request.has("nextToken") ? request.path("nextToken").asText(null) : null;
        int limit = request.path("limit").asInt(0);

        CloudWatchLogsService.DescribeSubscriptionFiltersResult result =
                logsService.describeSubscriptionFilters(logGroupName, filterNamePrefix, nextToken, limit, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode filtersArray = objectMapper.createArrayNode();
        for (SubscriptionFilter f : result.subscriptionFilters()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("filterName", f.getFilterName());
            node.put("logGroupName", f.getLogGroupName());
            node.put("filterPattern", f.getFilterPattern());
            node.put("destinationArn", f.getDestinationArn());
            if (f.getRoleArn() != null) {
                node.put("roleArn", f.getRoleArn());
            }
            if (f.getDistribution() != null) {
                node.put("distribution", f.getDistribution());
            }
            node.put("creationTime", f.getCreationTime());
            filtersArray.add(node);
        }
        response.set("subscriptionFilters", filtersArray);
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteSubscriptionFilter(JsonNode request, String region) {
        String logGroupName = resolveLogGroupName(request);
        String filterName = request.path("filterName").asText();
        logsService.deleteSubscriptionFilter(logGroupName, filterName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetLogGroupFields(JsonNode request, String region) {
        String groupName = resolveLogGroupName(request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode fields = response.putArray("logGroupFields");
        for (CloudWatchLogsService.LogGroupField field : logsService.getLogGroupFields(groupName, region)) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("name", field.name());
            node.put("percent", field.percent());
            fields.add(node);
        }
        return Response.ok(response).build();
    }

    private Response handleGetLogRecord(JsonNode request, String region) {
        String pointer = request.path("logRecordPointer").asText(null);
        Map<String, String> record = logsService.getLogRecord(pointer);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode recordNode = objectMapper.createObjectNode();
        record.forEach(recordNode::put);
        response.set("logRecord", recordNode);
        return Response.ok(response).build();
    }

    private Response handlePutDestination(JsonNode request, String region) {
        Destination dest = logsService.putDestination(
                textOrNull(request, "destinationName"),
                textOrNull(request, "targetArn"),
                textOrNull(request, "roleArn"),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("destination", destinationNode(dest));
        return Response.ok(response).build();
    }

    private Response handlePutDestinationPolicy(JsonNode request, String region) {
        logsService.putDestinationPolicy(
                textOrNull(request, "destinationName"),
                textOrNull(request, "accessPolicy"),
                region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeDestinations(JsonNode request, String region) {
        // Distilled/AWS Destinations still use the historical PascalCase prefix field.
        String prefix = textOrNull(request, "DestinationNamePrefix", "destinationNamePrefix");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode destinations = response.putArray("destinations");
        for (Destination dest : logsService.describeDestinations(prefix, region)) {
            destinations.add(destinationNode(dest));
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteDestination(JsonNode request, String region) {
        logsService.deleteDestination(textOrNull(request, "destinationName"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode destinationNode(Destination dest) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("destinationName", dest.getDestinationName());
        node.put("targetArn", dest.getTargetArn());
        node.put("roleArn", dest.getRoleArn());
        node.put("arn", dest.getArn());
        node.put("creationTime", dest.getCreationTime());
        if (dest.getAccessPolicy() != null) {
            node.put("accessPolicy", dest.getAccessPolicy());
        }
        return node;
    }

    private Response handlePutMetricFilter(JsonNode request, String region) {
        String logGroupName = resolveLogGroupName(request);
        String filterName = request.path("filterName").asText();
        String filterPattern = request.path("filterPattern").asText("");
        List<Map<String, Object>> transformations = new ArrayList<>();
        request.path("metricTransformations").forEach(node -> {
            Map<String, Object> t = new HashMap<>();
            t.put("metricName", node.path("metricName").asText());
            t.put("metricNamespace", node.path("metricNamespace").asText());
            t.put("metricValue", node.path("metricValue").asText());
            if (node.has("defaultValue")) {
                t.put("defaultValue", node.path("defaultValue").asDouble());
            }
            transformations.add(t);
        });
        logsService.putMetricFilter(logGroupName, filterName, filterPattern, transformations, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeMetricFilters(JsonNode request, String region) {
        String logGroupName = request.has("logGroupName") || request.has("logGroupIdentifier")
                ? resolveLogGroupName(request) : null;
        String filterNamePrefix = textOrNull(request, "filterNamePrefix");
        String nextToken = textOrNull(request, "nextToken");
        int limit = request.path("limit").asInt(0);
        CloudWatchLogsService.DescribeMetricFiltersResult result =
                logsService.describeMetricFilters(logGroupName, filterNamePrefix, nextToken, limit, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode filters = response.putArray("metricFilters");
        for (MetricFilter f : result.metricFilters()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("filterName", f.getFilterName());
            node.put("logGroupName", f.getLogGroupName());
            node.put("filterPattern", f.getFilterPattern());
            node.put("creationTime", f.getCreationTime());
            ArrayNode transforms = node.putArray("metricTransformations");
            for (Map<String, Object> t : f.getMetricTransformations()) {
                ObjectNode tn = objectMapper.createObjectNode();
                if (t.get("metricName") != null) {
                    tn.put("metricName", String.valueOf(t.get("metricName")));
                }
                if (t.get("metricNamespace") != null) {
                    tn.put("metricNamespace", String.valueOf(t.get("metricNamespace")));
                }
                if (t.get("metricValue") != null) {
                    tn.put("metricValue", String.valueOf(t.get("metricValue")));
                }
                if (t.get("defaultValue") instanceof Number n) {
                    tn.put("defaultValue", n.doubleValue());
                }
                transforms.add(tn);
            }
            filters.add(node);
        }
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteMetricFilter(JsonNode request, String region) {
        logsService.deleteMetricFilter(resolveLogGroupName(request), request.path("filterName").asText(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetDataProtectionPolicy(JsonNode request, String region) {
        // Data-protection policies are not modeled. Return HTTP 200 with the resolved
        // logGroupIdentifier and no policyDocument ("no policy set"). Real AWS returns
        // ResourceNotFoundException (HTTP 400) when the resource does not exist — the
        // 200-empty response is a deliberate Floci simplification for read-only callers.
        String identifier = resolveLogGroupName(request);
        ObjectNode response = objectMapper.createObjectNode();
        if (identifier != null) {
            response.put("logGroupIdentifier", identifier);
        }
        // policyDocument intentionally omitted -> no data-protection policy
        return Response.ok(response).build();
    }

    private String resolveLogGroupName(JsonNode request) {
        String name = request.path("logGroupName").asText(null);
        if (name == null || name.isBlank()) {
            name = request.path("logGroupIdentifier").asText(null);
        }
        return extractLogGroupNameFromArn(name);
    }

    private String resolveLogStreamName(String name) {
        if (name != null && name.contains(":log-stream:")) {
            return name.substring(name.indexOf(":log-stream:") + ":log-stream:".length());
        }
        return name;
    }

    private String extractLogGroupNameFromArn(String arn) {
        if (arn != null && arn.contains(":log-group:")) {
            String name = arn.substring(arn.indexOf(":log-group:") + ":log-group:".length());
            if (name.endsWith(":*")) {
                name = name.substring(0, name.length() - 2);
            }
            return name;
        }
        return arn;
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ArrayNode buildEventsArray(List<LogEvent> events) {
        ArrayNode array = objectMapper.createArrayNode();
        for (LogEvent e : events) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("eventId", e.getEventId());
            node.put("timestamp", e.getTimestamp());
            node.put("message", e.getMessage());
            node.put("ingestionTime", e.getIngestionTime());
            if (e.getLogStreamName() != null) {
                node.put("logStreamName", e.getLogStreamName());
            }
            array.add(node);
        }
        return array;
    }

    private String textOrNull(JsonNode request, String... names) {
        for (String name : names) {
            if (request.has(name) && !request.path(name).isNull()) {
                String value = request.path(name).asText(null);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private Map<String, String> extractTags(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        }
        return tags;
    }

}
