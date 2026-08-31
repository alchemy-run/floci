package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudtrail.model.AdvancedEventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.DataResource;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.InsightSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CloudTrailJsonHandler {

    private final CloudTrailService service;
    private final ObjectMapper mapper;

    @Inject
    public CloudTrailJsonHandler(CloudTrailService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateTrail" -> createTrail(request, region);
            case "DescribeTrails" -> describeTrails(request, region);
            case "DeleteTrail" -> deleteTrail(request, region);
            case "UpdateTrail" -> updateTrail(request, region);
            case "PutEventSelectors" -> putEventSelectors(request, region);
            case "GetEventSelectors" -> getEventSelectors(request, region);
            case "PutInsightSelectors" -> putInsightSelectors(request, region);
            case "GetInsightSelectors" -> getInsightSelectors(request, region);
            case "StartLogging" -> startLogging(request, region);
            case "StopLogging" -> stopLogging(request, region);
            case "GetTrail" -> getTrail(request, region);
            case "GetTrailStatus" -> getTrailStatus(request, region);
            case "AddTags" -> addTags(request, region);
            case "ListTags" -> listTags(request, region);
            case "RemoveTags" -> removeTags(request, region);
            case "LookupEvents" -> lookupEvents(request, region);
            case "ListPublicKeys" -> listPublicKeys();
            case "ListInsightsMetricData" -> listInsightsMetricData(request);
            case "ListInsightsData" -> listInsightsData();
            case "CreateEventDataStore" -> createEventDataStore(request, region);
            case "GetEventDataStore" -> getEventDataStore(request, region);
            case "ListEventDataStores" -> listEventDataStores(request, region);
            case "UpdateEventDataStore" -> updateEventDataStore(request, region);
            case "DeleteEventDataStore" -> deleteEventDataStore(request, region);
            case "RestoreEventDataStore" -> restoreEventDataStore(request, region);
            case "StartEventDataStoreIngestion" -> startEventDataStoreIngestion(request, region);
            case "StopEventDataStoreIngestion" -> stopEventDataStoreIngestion(request, region);
            case "StartQuery" -> startQuery(request, region);
            case "DescribeQuery" -> describeQuery(request, region);
            case "GetQueryResults" -> getQueryResults(request, region);
            case "ListQueries" -> listQueries(request, region);
            case "CancelQuery" -> cancelQuery(request, region);
            case "GenerateQuery" -> generateQuery(request, region);
            default -> throw new AwsException(
                    "InvalidAction", "Could not find operation " + action, 400);
        };
    }

    private Response createTrail(JsonNode req, String region) {
        String name = req.path("Name").asText(null);
        String s3BucketName = req.path("S3BucketName").asText(null);
        String s3KeyPrefix = req.has("S3KeyPrefix") ? req.path("S3KeyPrefix").asText(null) : null;
        String snsTopicArn = req.has("SnsTopicARN") ? req.path("SnsTopicARN").asText(null)
                : req.has("SnsTopicName") ? req.path("SnsTopicName").asText(null) : null;
        boolean includeGlobal = req.path("IncludeGlobalServiceEvents").asBoolean(true);
        boolean isMultiRegion = req.path("IsMultiRegionTrail").asBoolean(false);
        boolean enableLogFileValidation = req.path("EnableLogFileValidation").asBoolean(false);
        boolean isOrganizationTrail = req.path("IsOrganizationTrail").asBoolean(false);

        Trail trail = service.createTrail(region, name, s3BucketName, s3KeyPrefix, snsTopicArn,
                includeGlobal, isMultiRegion, enableLogFileValidation, isOrganizationTrail);
        Map<String, String> createTags = parseTagsList(req.path("TagsList"));
        if (!createTags.isEmpty()) {
            service.addTags(region, trail.trailArn(), createTags);
        }

        ObjectNode resp = mapper.createObjectNode();
        resp.put("Name", trail.name());
        resp.put("S3BucketName", trail.s3BucketName());
        if (trail.s3KeyPrefix() != null) resp.put("S3KeyPrefix", trail.s3KeyPrefix());
        if (trail.snsTopicArn() != null) {
            resp.put("SnsTopicARN", trail.snsTopicArn());
            resp.put("SnsTopicName", trail.snsTopicArn());
        }
        resp.put("IncludeGlobalServiceEvents", trail.includeGlobalServiceEvents());
        resp.put("IsMultiRegionTrail", trail.isMultiRegionTrail());
        resp.put("TrailARN", trail.trailArn());
        resp.put("LogFileValidationEnabled", trail.logFileValidationEnabled());
        resp.put("IsOrganizationTrail", trail.isOrganizationTrail());
        return Response.ok(resp).build();
    }

    private Response deleteTrail(JsonNode req, String region) {
        String name = req.path("Name").asText(null);
        service.deleteTrail(region, name);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response updateTrail(JsonNode req, String region) {
        String name = req.path("Name").asText(null);
        String s3BucketName = req.has("S3BucketName") ? req.path("S3BucketName").asText(null) : null;
        String s3KeyPrefix = req.has("S3KeyPrefix") ? req.path("S3KeyPrefix").asText(null) : null;
        String snsTopicName = req.has("SnsTopicARN") ? req.path("SnsTopicARN").asText(null)
                : req.has("SnsTopicName") ? req.path("SnsTopicName").asText(null) : null;
        Boolean includeGlobal = req.has("IncludeGlobalServiceEvents")
                ? req.path("IncludeGlobalServiceEvents").asBoolean() : null;
        Boolean isMultiRegion = req.has("IsMultiRegionTrail")
                ? req.path("IsMultiRegionTrail").asBoolean() : null;
        Boolean enableLogFileValidation = req.has("EnableLogFileValidation")
                ? req.path("EnableLogFileValidation").asBoolean() : null;
        Boolean isOrganizationTrail = req.has("IsOrganizationTrail")
                ? req.path("IsOrganizationTrail").asBoolean() : null;

        Trail trail = service.updateTrail(region, name, s3BucketName, s3KeyPrefix, snsTopicName,
                includeGlobal, isMultiRegion, enableLogFileValidation, isOrganizationTrail);

        ObjectNode resp = mapper.createObjectNode();
        resp.put("Name", trail.name());
        resp.put("S3BucketName", trail.s3BucketName());
        if (trail.s3KeyPrefix() != null) resp.put("S3KeyPrefix", trail.s3KeyPrefix());
        if (trail.snsTopicArn() != null) {
            resp.put("SnsTopicARN", trail.snsTopicArn());
            resp.put("SnsTopicName", trail.snsTopicArn());
        }
        resp.put("IncludeGlobalServiceEvents", trail.includeGlobalServiceEvents());
        resp.put("IsMultiRegionTrail", trail.isMultiRegionTrail());
        resp.put("TrailARN", trail.trailArn());
        resp.put("LogFileValidationEnabled", trail.logFileValidationEnabled());
        resp.put("IsOrganizationTrail", trail.isOrganizationTrail());
        return Response.ok(resp).build();
    }

    private Response describeTrails(JsonNode req, String region) {
        List<String> nameList = extractStringList(req, "trailNameList");
        List<Trail> trails = service.describeTrails(region, nameList);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("trailList", mapper.valueToTree(trails));
        return Response.ok(resp).build();
    }

    private Response putEventSelectors(JsonNode req, String region) {
        String trailName = req.path("TrailName").asText(null);
        ObjectNode resp = mapper.createObjectNode();
        Trail trail = firstTrail(service.describeTrails(region, List.of(trailName)));
        if (trail != null) {
            resp.put("TrailARN", trail.trailArn());
        }
        if (req.has("AdvancedEventSelectors") && req.path("AdvancedEventSelectors").isArray()) {
            List<AdvancedEventSelector> advanced = parseAdvancedSelectors(req.get("AdvancedEventSelectors"));
            List<AdvancedEventSelector> stored = service.putAdvancedEventSelectors(
                    region, trailName, advanced == null ? List.of() : advanced);
            resp.set("AdvancedEventSelectors", mapper.valueToTree(stored));
            return Response.ok(resp).build();
        }
        List<EventSelector> selectors = parseEventSelectors(req.path("EventSelectors"));
        List<EventSelector> stored = service.putEventSelectors(region, trailName, selectors);
        resp.set("EventSelectors", mapper.valueToTree(stored));
        return Response.ok(resp).build();
    }

    private Response getEventSelectors(JsonNode req, String region) {
        String trailName = req.path("TrailName").asText(null);
        List<AdvancedEventSelector> advanced = service.getAdvancedEventSelectors(region, trailName);
        ObjectNode resp = mapper.createObjectNode();
        Trail trail = firstTrail(service.describeTrails(region, List.of(trailName)));
        if (trail != null) {
            resp.put("TrailARN", trail.trailArn());
        }
        if (!advanced.isEmpty()) {
            resp.set("AdvancedEventSelectors", mapper.valueToTree(advanced));
        } else {
            resp.set("EventSelectors", mapper.valueToTree(service.getEventSelectors(region, trailName)));
        }
        return Response.ok(resp).build();
    }

    private Response putInsightSelectors(JsonNode req, String region) {
        String trailName = req.path("TrailName").asText(null);
        List<InsightSelector> stored = service.putInsightSelectors(
                region, trailName, parseInsightSelectors(req.get("InsightSelectors")));
        ObjectNode resp = mapper.createObjectNode();
        Trail trail = firstTrail(service.describeTrails(region, List.of(trailName)));
        if (trail != null) {
            resp.put("TrailARN", trail.trailArn());
        }
        resp.set("InsightSelectors", mapper.valueToTree(stored));
        return Response.ok(resp).build();
    }

    private Response getInsightSelectors(JsonNode req, String region) {
        String trailName = req.path("TrailName").asText(null);
        List<InsightSelector> selectors = service.getInsightSelectors(region, trailName);
        ObjectNode resp = mapper.createObjectNode();
        Trail trail = firstTrail(service.describeTrails(region, List.of(trailName)));
        if (trail != null) {
            resp.put("TrailARN", trail.trailArn());
        }
        resp.set("InsightSelectors", mapper.valueToTree(selectors));
        return Response.ok(resp).build();
    }

    private Response startLogging(JsonNode req, String region) {
        String trailName = req.path("Name").asText(null);
        service.startLogging(region, trailName);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response stopLogging(JsonNode req, String region) {
        String trailName = req.path("Name").asText(null);
        service.stopLogging(region, trailName);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response getTrail(JsonNode req, String region) {
        String name = req.path("Name").asText(null);
        Trail trail = service.getTrailOrThrow(region, name);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("Trail", mapper.valueToTree(trail));
        return Response.ok(resp).build();
    }

    private Response getTrailStatus(JsonNode req, String region) {
        String trailName = req.path("Name").asText(null);
        CloudTrailService.TrailStatus status = service.getTrailStatus(region, trailName);

        ObjectNode resp = mapper.createObjectNode();
        resp.put("IsLogging", status.logging());
        if (status.startLoggingTime() != null) {
            resp.put("StartLoggingTime", status.startLoggingTime() / 1000.0);
            resp.put("LatestDeliveryTime", status.startLoggingTime() / 1000.0);
        }
        if (status.stopLoggingTime() != null) {
            resp.put("StopLoggingTime", status.stopLoggingTime() / 1000.0);
        }
        return Response.ok(resp).build();
    }

    private Response addTags(JsonNode req, String region) {
        String resourceId = req.path("ResourceId").asText(null);
        service.addTags(region, resourceId, parseTagsList(req.path("TagsList")));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response removeTags(JsonNode req, String region) {
        String resourceId = req.path("ResourceId").asText(null);
        List<String> keys = new ArrayList<>();
        JsonNode tagsList = req.path("TagsList");
        if (tagsList.isArray()) {
            tagsList.forEach(n -> keys.add(n.path("Key").asText(null)));
        }
        service.removeTags(region, resourceId, keys);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response listTags(JsonNode req, String region) {
        List<String> ids = extractStringList(req, "ResourceIdList");
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode list = resp.putArray("ResourceTagList");
        for (CloudTrailService.ResourceTagSet set : service.listTags(region, ids)) {
            ObjectNode item = mapper.createObjectNode();
            item.put("ResourceId", set.resourceId());
            ArrayNode tags = item.putArray("TagsList");
            set.tags().forEach((k, v) -> {
                ObjectNode t = mapper.createObjectNode();
                t.put("Key", k);
                t.put("Value", v == null ? "" : v);
                tags.add(t);
            });
            list.add(item);
        }
        return Response.ok(resp).build();
    }

    private Response lookupEvents(JsonNode req, String region) {
        return Response.ok(service.lookupEvents(req)).build();
    }

    private Response listPublicKeys() {
        ObjectNode resp = mapper.createObjectNode();
        resp.putArray("PublicKeyList");
        return Response.ok(resp).build();
    }

    private Response listInsightsMetricData(JsonNode req) {
        ObjectNode resp = mapper.createObjectNode();
        if (req.has("EventSource")) {
            resp.put("EventSource", req.path("EventSource").asText());
        }
        if (req.has("EventName")) {
            resp.put("EventName", req.path("EventName").asText());
        }
        if (req.has("InsightType")) {
            resp.put("InsightType", req.path("InsightType").asText());
        }
        resp.putArray("Timestamps");
        resp.putArray("Values");
        return Response.ok(resp).build();
    }

    private Response listInsightsData() {
        ObjectNode resp = mapper.createObjectNode();
        resp.putArray("Events");
        return Response.ok(resp).build();
    }

    private Response createEventDataStore(JsonNode req, String region) {
        EventDataStoreEntry store = service.createEventDataStore(
                region,
                req.path("Name").asText(null),
                parseAdvancedSelectors(req.get("AdvancedEventSelectors")),
                req.has("MultiRegionEnabled") ? req.path("MultiRegionEnabled").asBoolean() : null,
                req.has("OrganizationEnabled") ? req.path("OrganizationEnabled").asBoolean() : null,
                req.has("RetentionPeriod") ? req.path("RetentionPeriod").asInt() : null,
                req.has("TerminationProtectionEnabled")
                        ? req.path("TerminationProtectionEnabled").asBoolean() : null,
                req.has("BillingMode") ? req.path("BillingMode").asText(null) : null,
                req.has("KmsKeyId") ? req.path("KmsKeyId").asText(null) : null,
                req.has("StartIngestion") ? req.path("StartIngestion").asBoolean() : null,
                parseTagsList(req.path("TagsList")));
        return Response.ok(storeToJson(store, true)).build();
    }

    private Response getEventDataStore(JsonNode req, String region) {
        EventDataStoreEntry store = service.getEventDataStore(region, req.path("EventDataStore").asText(null));
        return Response.ok(storeToJson(store, false)).build();
    }

    private Response listEventDataStores(JsonNode req, String region) {
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode list = resp.putArray("EventDataStores");
        for (EventDataStoreEntry store : service.listEventDataStores(region)) {
            list.add(storeToJson(store, false));
        }
        return Response.ok(resp).build();
    }

    private Response updateEventDataStore(JsonNode req, String region) {
        EventDataStoreEntry store = service.updateEventDataStore(
                region,
                req.path("EventDataStore").asText(null),
                req.has("Name") ? req.path("Name").asText(null) : null,
                req.has("AdvancedEventSelectors")
                        ? parseAdvancedSelectors(req.get("AdvancedEventSelectors")) : null,
                req.has("MultiRegionEnabled") ? req.path("MultiRegionEnabled").asBoolean() : null,
                req.has("OrganizationEnabled") ? req.path("OrganizationEnabled").asBoolean() : null,
                req.has("RetentionPeriod") ? req.path("RetentionPeriod").asInt() : null,
                req.has("TerminationProtectionEnabled")
                        ? req.path("TerminationProtectionEnabled").asBoolean() : null,
                req.has("BillingMode") ? req.path("BillingMode").asText(null) : null,
                req.has("KmsKeyId") ? req.path("KmsKeyId").asText(null) : null);
        return Response.ok(storeToJson(store, false)).build();
    }

    private Response deleteEventDataStore(JsonNode req, String region) {
        service.deleteEventDataStore(region, req.path("EventDataStore").asText(null));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response restoreEventDataStore(JsonNode req, String region) {
        EventDataStoreEntry store = service.restoreEventDataStore(
                region, req.path("EventDataStore").asText(null));
        return Response.ok(storeToJson(store, false)).build();
    }

    private Response startEventDataStoreIngestion(JsonNode req, String region) {
        service.startEventDataStoreIngestion(region, req.path("EventDataStore").asText(null));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response stopEventDataStoreIngestion(JsonNode req, String region) {
        service.stopEventDataStoreIngestion(region, req.path("EventDataStore").asText(null));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response startQuery(JsonNode req, String region) {
        LakeQuery query = service.startQuery(region, req.path("QueryStatement").asText(null));
        ObjectNode resp = mapper.createObjectNode();
        resp.put("QueryId", query.queryId());
        return Response.ok(resp).build();
    }

    private Response describeQuery(JsonNode req, String region) {
        LakeQuery query = service.describeQuery(req.path("QueryId").asText(null));
        ObjectNode resp = mapper.createObjectNode();
        resp.put("QueryId", query.queryId());
        resp.put("QueryString", query.queryStatement());
        resp.put("QueryStatus", query.status());
        ObjectNode stats = mapper.createObjectNode();
        stats.put("EventsMatched", 0);
        stats.put("EventsScanned", 0);
        stats.put("BytesScanned", 0);
        stats.put("ExecutionTimeInMillis", 0);
        stats.put("CreationTime", query.createdTimestamp() > 10_000_000_000L
                ? query.createdTimestamp() / 1000.0
                : query.createdTimestamp());
        resp.set("QueryStatistics", stats);
        if (query.prompt() != null) {
            resp.put("Prompt", query.prompt());
        }
        return Response.ok(resp).build();
    }

    private Response getQueryResults(JsonNode req, String region) {
        LakeQuery query = service.getQueryResults(req.path("QueryId").asText(null));
        ObjectNode resp = mapper.createObjectNode();
        resp.put("QueryStatus", query.status());
        ObjectNode stats = mapper.createObjectNode();
        stats.put("ResultsCount", 0);
        stats.put("TotalResultsCount", 0);
        stats.put("BytesScanned", 0);
        resp.set("QueryStatistics", stats);
        resp.putArray("QueryResultRows");
        return Response.ok(resp).build();
    }

    private Response listQueries(JsonNode req, String region) {
        String status = req.has("QueryStatus") ? req.path("QueryStatus").asText(null) : null;
        List<LakeQuery> queries = service.listQueries(region, req.path("EventDataStore").asText(null), status);
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode list = resp.putArray("Queries");
        for (LakeQuery q : queries) {
            ObjectNode item = mapper.createObjectNode();
            item.put("QueryId", q.queryId());
            item.put("QueryStatus", q.status());
            long created = q.createdTimestamp() > 10_000_000_000L
                    ? q.createdTimestamp() / 1000
                    : q.createdTimestamp();
            item.put("CreationTime", created);
            list.add(item);
        }
        return Response.ok(resp).build();
    }

    private Response cancelQuery(JsonNode req, String region) {
        LakeQuery query = service.cancelQuery(req.path("QueryId").asText(null));
        ObjectNode resp = mapper.createObjectNode();
        resp.put("QueryId", query.queryId());
        resp.put("QueryStatus", query.status());
        return Response.ok(resp).build();
    }

    private Response generateQuery(JsonNode req, String region) {
        List<String> stores = extractStringList(req, "EventDataStores");
        String statement = service.generateQuery(region, stores, req.path("Prompt").asText(null));
        ObjectNode resp = mapper.createObjectNode();
        resp.put("QueryStatement", statement);
        return Response.ok(resp).build();
    }

    private ObjectNode storeToJson(EventDataStoreEntry store, boolean includeTags) {
        ObjectNode n = mapper.createObjectNode();
        n.put("EventDataStoreArn", store.arn());
        n.put("Name", store.name());
        n.put("Status", store.status());
        n.set("AdvancedEventSelectors", mapper.valueToTree(store.advancedEventSelectors()));
        n.put("MultiRegionEnabled", store.multiRegionEnabled());
        n.put("OrganizationEnabled", store.organizationEnabled());
        n.put("RetentionPeriod", store.retentionPeriod());
        n.put("TerminationProtectionEnabled", store.terminationProtectionEnabled());
        n.put("CreatedTimestamp", store.createdTimestamp());
        n.put("UpdatedTimestamp", store.updatedTimestamp());
        if (store.kmsKeyId() != null) {
            n.put("KmsKeyId", store.kmsKeyId());
        }
        if (store.billingMode() != null) {
            n.put("BillingMode", store.billingMode());
        }
        if (includeTags) {
            ArrayNode tags = n.putArray("TagsList");
            store.tags().forEach((k, v) -> {
                ObjectNode t = mapper.createObjectNode();
                t.put("Key", k);
                t.put("Value", v == null ? "" : v);
                tags.add(t);
            });
        }
        return n;
    }

    private List<AdvancedEventSelector> parseAdvancedSelectors(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
            return null;
        }
        return mapper.convertValue(node, new TypeReference<List<AdvancedEventSelector>>() {});
    }

    private List<InsightSelector> parseInsightSelectors(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        return mapper.convertValue(node, new TypeReference<List<InsightSelector>>() {});
    }

    // --- Helpers ---

    private List<EventSelector> parseEventSelectors(JsonNode selectorsNode) {
        List<EventSelector> result = new ArrayList<>();
        if (selectorsNode == null || !selectorsNode.isArray()) return result;
        for (JsonNode sel : selectorsNode) {
            String readWriteType = sel.has("ReadWriteType") ? sel.path("ReadWriteType").asText() : "All";
            Boolean includeManagement = sel.has("IncludeManagementEvents")
                    ? sel.path("IncludeManagementEvents").asBoolean() : null;
            List<String> excludeManagement = extractStringList(sel, "ExcludeManagementEventSources");
            List<DataResource> dataResources = new ArrayList<>();
            if (sel.has("DataResources")) {
                for (JsonNode dr : sel.path("DataResources")) {
                    String type = dr.path("Type").asText(null);
                    List<String> values = extractStringList(dr, "Values");
                    dataResources.add(new DataResource(type, values));
                }
            }
            result.add(new EventSelector(readWriteType, includeManagement, dataResources,
                    excludeManagement.isEmpty() ? null : excludeManagement));
        }
        return result;
    }

    private static Trail firstTrail(List<Trail> trails) {
        return trails.isEmpty() ? null : trails.get(0);
    }

    private Map<String, String> parseTagsList(JsonNode tagsList) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsList != null && tagsList.isArray()) {
            for (JsonNode t : tagsList) {
                String key = t.path("Key").asText(null);
                if (key != null && !key.isEmpty()) {
                    tags.put(key, t.path("Value").asText(""));
                }
            }
        }
        return tags;
    }

    private List<String> extractStringList(JsonNode req, String fieldName) {
        List<String> result = new ArrayList<>();
        if (req != null && req.has(fieldName)) {
            req.path(fieldName).forEach(n -> result.add(n.asText()));
        }
        return result;
    }
}
