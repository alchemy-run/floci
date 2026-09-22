package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class CloudTrailEventService {
    private final StorageBackend<String, ObjectNode> history;
    private final RegionResolver regions;
    private final ObjectMapper mapper;
    private final CloudTrailService trails;
    private final CloudTrailLakeService lake;
    private final EventBridgeService eventBridge;
    private final IamService iam;

    @Inject
    public CloudTrailEventService(StorageFactory factory, RegionResolver regions, ObjectMapper mapper,
                                  CloudTrailService trails, CloudTrailLakeService lake,
                                  EventBridgeService eventBridge, IamService iam) {
        history = factory.create("cloudtrail", "cloudtrail-event-history.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regions = regions;
        this.mapper = mapper;
        this.trails = trails;
        this.lake = lake;
        this.eventBridge = eventBridge;
        this.iam = iam;
    }

    public void record(String service, String operation, String region, String accessKey, boolean readOnly,
                       ObjectNode parameters, String sourceIp, String userAgent, String requestId,
                       String errorCode, String errorMessage) {
        ObjectNode record = mapper.createObjectNode();
        record.put("eventVersion", "1.11").put("eventID", UUID.randomUUID().toString())
                .put("eventTime", Instant.now().toString()).put("eventSource", service + ".amazonaws.com")
                .put("eventName", operation).put("awsRegion", region).put("eventType", "AwsApiCall")
                .put("eventCategory", "Management").put("managementEvent", true).put("readOnly", readOnly)
                .put("recipientAccountId", regions.getAccountId());
        if (sourceIp != null) record.put("sourceIPAddress", sourceIp);
        if (userAgent != null) record.put("userAgent", userAgent);
        if (requestId != null) record.put("requestID", requestId);
        ObjectNode identity = record.putObject("userIdentity");
        identity.put("accountId", regions.getAccountId());
        if (accessKey != null) identity.put("accessKeyId", accessKey);
        String arn = accessKey == null ? null : iam.resolveCallerArn(accessKey).orElse(null);
        if (arn != null) {
            identity.put("arn", arn).put("type", arn.contains(":assumed-role/") ? "AssumedRole" : "IAMUser");
            if (arn.contains(":user/")) identity.put("userName", arn.substring(arn.lastIndexOf('/') + 1));
        } else if ("test".equals(accessKey) || regions.getAccountId().equals(accessKey)) {
            identity.put("type", "Root").put("arn", "arn:aws:iam::" + regions.getAccountId() + ":root");
        } else {
            identity.put("type", "AWSAccount");
        }
        record.set("requestParameters", parameters.deepCopy());
        record.putNull("responseElements");
        if (errorCode != null) record.put("errorCode", errorCode);
        if (errorMessage != null) record.put("errorMessage", errorMessage);
        if ("s3".equals(service) && parameters.has("bucketName")) {
            record.putArray("resources").addObject().put("type", "AWS::S3::Bucket")
                    .put("ARN", "arn:aws:s3:::" + parameters.path("bucketName").asText())
                    .put("accountId", regions.getAccountId());
        }
        pruneHistory();
        history.put(region + ":" + record.path("eventID").asText(), record);
        lake.ingest(record);
        boolean selected = trails.recordManagementEvent(region, record);
        if (selected && !readOnly) {
            eventBridge.putEvents(List.of(Map.of(
                    "Source", "aws." + service,
                    "DetailType", "AWS API Call via CloudTrail",
                    "Detail", record.toString())), region);
        }
    }

    public ObjectNode lookup(JsonNode request, String region) {
        CloudTrailPages.validateTimeRange(request);
        if (request.has("EventCategory") && !"insight".equals(request.path("EventCategory").asText())) {
            throw new AwsException("InvalidEventCategoryException", "EventCategory must be insight when supplied.", 400);
        }
        String attribute = null;
        String expected = null;
        if (request.has("LookupAttributes")) {
            JsonNode attributes = request.path("LookupAttributes");
            if (!attributes.isArray() || attributes.size() > 1) throw invalidAttribute();
            if (!attributes.isEmpty()) {
                attribute = attributes.get(0).path("AttributeKey").asText();
                expected = attributes.get(0).path("AttributeValue").asText(null);
                if (!Set.of("EventId", "EventName", "ReadOnly", "Username", "ResourceType", "ResourceName",
                        "EventSource", "AccessKeyId").contains(attribute) || expected == null || expected.isEmpty()) {
                    throw invalidAttribute();
                }
            }
        }
        pruneHistory();
        double from = request.path("StartTime").asDouble(Double.NEGATIVE_INFINITY);
        double to = request.path("EndTime").asDouble(Double.POSITIVE_INFINITY);
        List<ObjectNode> rows = new ArrayList<>();
        if (!request.has("EventCategory")) {
            for (ObjectNode record : history.scan(k -> k.startsWith(region + ":"))) {
                double time = Instant.parse(record.path("eventTime").asText()).toEpochMilli() / 1000.0;
                if (time < from || time > to) continue;
                ObjectNode row = lookupRow(record, time);
                if (attribute == null || matches(row, attribute, expected)) rows.add(row);
            }
        }
        rows.sort(Comparator.<ObjectNode>comparingDouble(r -> r.path("EventTime").asDouble()).reversed()
                .thenComparing(r -> r.path("EventId").asText()));
        return CloudTrailPages.page(mapper, request, regions.getAccountId() + ":" + region + ":history",
                "Events", rows, 50, 50, "InvalidMaxResultsException");
    }

    private ObjectNode lookupRow(ObjectNode record, double time) {
        ObjectNode row = mapper.createObjectNode().put("EventId", record.path("eventID").asText())
                .put("EventName", record.path("eventName").asText()).put("EventSource", record.path("eventSource").asText())
                .put("EventTime", time).put("ReadOnly", record.path("readOnly").asText())
                .put("CloudTrailEvent", record.toString());
        JsonNode identity = record.path("userIdentity");
        if (identity.has("userName")) row.put("Username", identity.path("userName").asText());
        if (identity.has("accessKeyId")) row.put("AccessKeyId", identity.path("accessKeyId").asText());
        var resources = row.putArray("Resources");
        for (JsonNode resource : record.path("resources")) {
            String name = resource.path("ARN").asText();
            if ("AWS::S3::Bucket".equals(resource.path("type").asText())) name = name.substring("arn:aws:s3:::".length());
            resources.addObject().put("ResourceType", resource.path("type").asText()).put("ResourceName", name);
        }
        return row;
    }

    private boolean matches(ObjectNode row, String attribute, String expected) {
        if ("ResourceName".equals(attribute) || "ResourceType".equals(attribute)) {
            for (JsonNode resource : row.path("Resources")) {
                if (expected.equals(resource.path(attribute).asText())) return true;
            }
            return false;
        }
        return expected.equals(row.path(attribute).asText(null));
    }

    private void pruneHistory() {
        Instant cutoff = Instant.now().minusSeconds(90L * 86400);
        for (String key : history.keys()) {
            history.get(key).filter(e -> Instant.parse(e.path("eventTime").asText()).isBefore(cutoff))
                    .ifPresent(ignored -> history.delete(key));
        }
    }

    private static AwsException invalidAttribute() {
        return new AwsException("InvalidLookupAttributesException", "Provide at most one valid lookup attribute.", 400);
    }
}
