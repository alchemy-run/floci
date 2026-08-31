package io.github.hectorvent.floci.services.frauddetector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.frauddetector.model.Detector;
import io.github.hectorvent.floci.services.frauddetector.model.DetectorVersion;
import io.github.hectorvent.floci.services.frauddetector.model.EventType;
import io.github.hectorvent.floci.services.frauddetector.model.FraudList;
import io.github.hectorvent.floci.services.frauddetector.model.NamedResource;
import io.github.hectorvent.floci.services.frauddetector.model.PredictionRecord;
import io.github.hectorvent.floci.services.frauddetector.model.Rule;
import io.github.hectorvent.floci.services.frauddetector.model.StoredEvent;
import io.github.hectorvent.floci.services.frauddetector.model.Variable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * JSON 1.1 handler for Amazon Fraud Detector. Dispatched from
 * {@code AwsJson11Controller} under the {@code AWSHawksNestServiceFacade.} target prefix.
 */
@ApplicationScoped
public class FraudDetectorJsonHandler {

    private final FraudDetectorService service;
    private final ObjectMapper objectMapper;

    @Inject
    public FraudDetectorJsonHandler(FraudDetectorService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "GetDetectors" -> getDetectors(region, body);
                case "PutDetector" -> {
                    service.putDetector(region, body);
                    yield ok();
                }
                case "DeleteDetector" -> {
                    service.deleteDetector(region, body);
                    yield ok();
                }
                case "GetDetectorVersion" -> getDetectorVersion(region, body);
                case "CreateDetectorVersion" -> createDetectorVersion(region, body);
                case "UpdateDetectorVersionStatus" -> {
                    service.updateDetectorVersionStatus(region, body);
                    yield ok();
                }
                case "DeleteDetectorVersion" -> {
                    service.deleteDetectorVersion(region, body);
                    yield ok();
                }
                case "CreateRule" -> createRule(region, body);
                case "GetRules" -> getRules(region, body);
                case "DeleteRule" -> {
                    service.deleteRule(region, body);
                    yield ok();
                }
                case "GetEntityTypes" -> namedList("entityTypes",
                        service.getEntityTypes(region, body));
                case "PutEntityType" -> {
                    service.putEntityType(region, body);
                    yield ok();
                }
                case "DeleteEntityType" -> {
                    service.deleteEntityType(region, body);
                    yield ok();
                }
                case "GetLabels" -> namedList("labels", service.getLabels(region, body));
                case "PutLabel" -> {
                    service.putLabel(region, body);
                    yield ok();
                }
                case "DeleteLabel" -> {
                    service.deleteLabel(region, body);
                    yield ok();
                }
                case "GetOutcomes" -> namedList("outcomes", service.getOutcomes(region, body));
                case "PutOutcome" -> {
                    service.putOutcome(region, body);
                    yield ok();
                }
                case "DeleteOutcome" -> {
                    service.deleteOutcome(region, body);
                    yield ok();
                }
                case "GetEventTypes" -> getEventTypes(region, body);
                case "PutEventType" -> {
                    service.putEventType(region, body);
                    yield ok();
                }
                case "DeleteEventType" -> {
                    service.deleteEventType(region, body);
                    yield ok();
                }
                case "GetVariables" -> getVariables(region, body);
                case "CreateVariable" -> {
                    service.createVariable(region, body);
                    yield ok();
                }
                case "UpdateVariable" -> {
                    service.updateVariable(region, body);
                    yield ok();
                }
                case "DeleteVariable" -> {
                    service.deleteVariable(region, body);
                    yield ok();
                }
                case "GetListsMetadata" -> getListsMetadata(region, body);
                case "GetListElements" -> getListElements(region, body);
                case "CreateList" -> {
                    service.createList(region, body);
                    yield ok();
                }
                case "UpdateList" -> {
                    service.updateList(region, body);
                    yield ok();
                }
                case "DeleteList" -> {
                    service.deleteList(region, body);
                    yield ok();
                }
                case "GetEvent" -> getEvent(region, body);
                case "SendEvent" -> {
                    service.sendEvent(region, body);
                    yield ok();
                }
                case "UpdateEventLabel" -> {
                    service.updateEventLabel(region, body);
                    yield ok();
                }
                case "DeleteEvent" -> {
                    service.deleteEvent(region, body);
                    yield ok();
                }
                case "DeleteEventsByEventType" -> status(service.deleteEventsByEventType(region, body));
                case "GetDeleteEventsByEventTypeStatus" ->
                        status(service.getDeleteEventsByEventTypeStatus(region, body));
                case "GetEventPrediction" -> getEventPrediction(region, body);
                case "ListEventPredictions" -> listEventPredictions(region, body);
                case "GetEventPredictionMetadata" -> getEventPredictionMetadata(region, body);
                case "TagResource" -> {
                    service.tagResource(region, body);
                    yield ok();
                }
                case "UntagResource" -> {
                    service.untagResource(region, body);
                    yield ok();
                }
                case "ListTagsForResource" -> listTags(region, body);
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                        FraudDetectorService.TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response getDetectors(String region, JsonNode body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("detectors");
        for (Detector detector : service.getDetectors(region, body)) {
            list.add(toDetector(detector));
        }
        return Response.ok(response).build();
    }

    private Response getDetectorVersion(String region, JsonNode body) {
        return Response.ok(toDetectorVersion(service.getDetectorVersion(region, body))).build();
    }

    private Response createDetectorVersion(String region, JsonNode body) {
        DetectorVersion version = service.createDetectorVersion(region, body);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("detectorId", version.getDetectorId());
        response.put("detectorVersionId", version.getDetectorVersionId());
        response.put("status", version.getStatus());
        return Response.ok(response).build();
    }

    private Response createRule(String region, JsonNode body) {
        Rule rule = service.createRule(region, body);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode ref = response.putObject("rule");
        ref.put("detectorId", rule.getDetectorId());
        ref.put("ruleId", rule.getRuleId());
        ref.put("ruleVersion", rule.getRuleVersion());
        return Response.ok(response).build();
    }

    private Response getRules(String region, JsonNode body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ruleDetails");
        for (Rule rule : service.getRules(region, body)) {
            list.add(toRuleDetail(rule));
        }
        return Response.ok(response).build();
    }

    private Response namedList(String field, List<NamedResource> resources) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray(field);
        for (NamedResource resource : resources) {
            list.add(toNamed(resource));
        }
        return Response.ok(response).build();
    }

    private Response getEventTypes(String region, JsonNode body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("eventTypes");
        for (EventType eventType : service.getEventTypes(region, body)) {
            list.add(toEventType(eventType));
        }
        return Response.ok(response).build();
    }

    private Response getVariables(String region, JsonNode body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("variables");
        for (Variable variable : service.getVariables(region, body)) {
            list.add(toVariable(variable));
        }
        return Response.ok(response).build();
    }

    private Response getListsMetadata(String region, JsonNode body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("lists");
        for (FraudList fraudList : service.getListsMetadata(region, body)) {
            list.add(toList(fraudList));
        }
        return Response.ok(response).build();
    }

    private Response getListElements(String region, JsonNode body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("elements");
        for (String element : service.getListElements(region, body)) {
            list.add(element);
        }
        return Response.ok(response).build();
    }

    private Response getEvent(String region, JsonNode body) {
        StoredEvent event = service.getEvent(region, body);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("event", toEvent(event));
        return Response.ok(response).build();
    }

    private Response getEventPrediction(String region, JsonNode body) {
        PredictionRecord record = service.getEventPrediction(region, body);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("modelScores");
        response.putArray("externalModelOutputs");
        ArrayNode ruleResults = response.putArray("ruleResults");
        for (Rule rule : record.getEvaluatedRules()) {
            ObjectNode result = ruleResults.addObject();
            result.put("ruleId", rule.getRuleId());
            ArrayNode outcomes = result.putArray("outcomes");
            for (String outcome : rule.getOutcomes()) {
                outcomes.add(outcome);
            }
        }
        return Response.ok(response).build();
    }

    private Response listEventPredictions(String region, JsonNode body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("eventPredictionSummaries");
        for (PredictionRecord record : service.listEventPredictions(region, body)) {
            ObjectNode summary = list.addObject();
            put(summary, "eventId", record.getEventId());
            put(summary, "eventTypeName", record.getEventTypeName());
            put(summary, "eventTimestamp", record.getEventTimestamp());
            put(summary, "predictionTimestamp", record.getPredictionTimestamp());
            put(summary, "detectorId", record.getDetectorId());
            put(summary, "detectorVersionId", record.getDetectorVersionId());
        }
        return Response.ok(response).build();
    }

    private Response getEventPredictionMetadata(String region, JsonNode body) {
        PredictionRecord record = service.getEventPredictionMetadata(region, body);
        ObjectNode response = objectMapper.createObjectNode();
        put(response, "eventId", record.getEventId());
        put(response, "eventTypeName", record.getEventTypeName());
        put(response, "eventTimestamp", record.getEventTimestamp());
        put(response, "predictionTimestamp", record.getPredictionTimestamp());
        put(response, "detectorId", record.getDetectorId());
        put(response, "detectorVersionId", record.getDetectorVersionId());
        put(response, "detectorVersionStatus", record.getDetectorVersionStatus());
        put(response, "ruleExecutionMode", record.getRuleExecutionMode());
        ArrayNode outcomes = response.putArray("outcomes");
        for (String outcome : record.getOutcomes()) {
            outcomes.add(outcome);
        }
        ArrayNode rules = response.putArray("rules");
        for (Rule rule : record.getEvaluatedRules()) {
            ObjectNode evaluated = rules.addObject();
            put(evaluated, "ruleId", rule.getRuleId());
            put(evaluated, "ruleVersion", rule.getRuleVersion());
            put(evaluated, "expression", rule.getExpression());
            evaluated.put("evaluated", true);
            evaluated.put("matched", true);
            ArrayNode ruleOutcomes = evaluated.putArray("outcomes");
            for (String outcome : rule.getOutcomes()) {
                ruleOutcomes.add(outcome);
            }
        }
        return Response.ok(response).build();
    }

    private Response listTags(String region, JsonNode body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("tags");
        for (Map.Entry<String, String> entry : service.listTagsForResource(region, body).entrySet()) {
            ObjectNode tag = list.addObject();
            tag.put("key", entry.getKey());
            tag.put("value", entry.getValue());
        }
        return Response.ok(response).build();
    }

    private Response status(Map<String, String> values) {
        ObjectNode response = objectMapper.createObjectNode();
        values.forEach(response::put);
        return Response.ok(response).build();
    }

    private ObjectNode toDetector(Detector detector) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "detectorId", detector.getDetectorId());
        put(node, "description", detector.getDescription());
        put(node, "eventTypeName", detector.getEventTypeName());
        put(node, "lastUpdatedTime", detector.getLastUpdatedTime());
        put(node, "createdTime", detector.getCreatedTime());
        put(node, "arn", detector.getArn());
        return node;
    }

    private ObjectNode toDetectorVersion(DetectorVersion version) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "detectorId", version.getDetectorId());
        put(node, "detectorVersionId", version.getDetectorVersionId());
        put(node, "description", version.getDescription());
        put(node, "status", version.getStatus());
        put(node, "ruleExecutionMode", version.getRuleExecutionMode());
        put(node, "lastUpdatedTime", version.getLastUpdatedTime());
        put(node, "createdTime", version.getCreatedTime());
        put(node, "arn", version.getArn());
        ArrayNode rules = node.putArray("rules");
        for (Rule rule : version.getRules()) {
            ObjectNode ref = rules.addObject();
            put(ref, "detectorId", rule.getDetectorId());
            put(ref, "ruleId", rule.getRuleId());
            put(ref, "ruleVersion", rule.getRuleVersion());
        }
        return node;
    }

    private ObjectNode toRuleDetail(Rule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "ruleId", rule.getRuleId());
        put(node, "description", rule.getDescription());
        put(node, "detectorId", rule.getDetectorId());
        put(node, "ruleVersion", rule.getRuleVersion());
        put(node, "expression", rule.getExpression());
        put(node, "language", rule.getLanguage());
        put(node, "lastUpdatedTime", rule.getLastUpdatedTime());
        put(node, "createdTime", rule.getCreatedTime());
        put(node, "arn", rule.getArn());
        ArrayNode outcomes = node.putArray("outcomes");
        for (String outcome : rule.getOutcomes()) {
            outcomes.add(outcome);
        }
        return node;
    }

    private ObjectNode toNamed(NamedResource resource) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "name", resource.getName());
        put(node, "description", resource.getDescription());
        put(node, "lastUpdatedTime", resource.getLastUpdatedTime());
        put(node, "createdTime", resource.getCreatedTime());
        put(node, "arn", resource.getArn());
        return node;
    }

    private ObjectNode toEventType(EventType eventType) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "name", eventType.getName());
        put(node, "description", eventType.getDescription());
        put(node, "eventIngestion", eventType.getEventIngestion());
        put(node, "lastUpdatedTime", eventType.getLastUpdatedTime());
        put(node, "createdTime", eventType.getCreatedTime());
        put(node, "arn", eventType.getArn());
        ArrayNode variables = node.putArray("eventVariables");
        for (String variable : eventType.getEventVariables()) {
            variables.add(variable);
        }
        ArrayNode labels = node.putArray("labels");
        for (String label : eventType.getLabels()) {
            labels.add(label);
        }
        ArrayNode entityTypes = node.putArray("entityTypes");
        for (String entityType : eventType.getEntityTypes()) {
            entityTypes.add(entityType);
        }
        if (eventType.getEventBridgeEnabled() != null) {
            ObjectNode orchestration = node.putObject("eventOrchestration");
            orchestration.put("eventBridgeEnabled", eventType.getEventBridgeEnabled());
        }
        return node;
    }

    private ObjectNode toVariable(Variable variable) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "name", variable.getName());
        put(node, "description", variable.getDescription());
        put(node, "dataType", variable.getDataType());
        put(node, "dataSource", variable.getDataSource());
        put(node, "defaultValue", variable.getDefaultValue());
        put(node, "variableType", variable.getVariableType());
        put(node, "lastUpdatedTime", variable.getLastUpdatedTime());
        put(node, "createdTime", variable.getCreatedTime());
        put(node, "arn", variable.getArn());
        return node;
    }

    private ObjectNode toList(FraudList list) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "name", list.getName());
        put(node, "description", list.getDescription());
        put(node, "variableType", list.getVariableType());
        put(node, "createdTime", list.getCreatedTime());
        put(node, "updatedTime", list.getUpdatedTime());
        put(node, "arn", list.getArn());
        return node;
    }

    private ObjectNode toEvent(StoredEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "eventId", event.getEventId());
        put(node, "eventTypeName", event.getEventTypeName());
        put(node, "eventTimestamp", event.getEventTimestamp());
        put(node, "currentLabel", event.getCurrentLabel());
        put(node, "labelTimestamp", event.getLabelTimestamp());
        ObjectNode variables = node.putObject("eventVariables");
        event.getEventVariables().forEach(variables::put);
        ArrayNode entities = node.putArray("entities");
        for (Map<String, String> entity : event.getEntities()) {
            ObjectNode item = entities.addObject();
            entity.forEach(item::put);
        }
        return node;
    }

    private Response ok() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
