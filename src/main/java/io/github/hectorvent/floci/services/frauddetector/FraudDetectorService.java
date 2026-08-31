package io.github.hectorvent.floci.services.frauddetector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amazon Fraud Detector JSON 1.1 ({@code AWSHawksNestServiceFacade.*}).
 *
 * <p>GetDetectors / GetEvent / UpdateList surface typed
 * {@code ResourceNotFoundException} for missing identifiers, matching the
 * live AWS error the Alchemy suite probes.
 */
@ApplicationScoped
public class FraudDetectorService implements Resettable {

    static final String SERVICE = "frauddetector";
    static final String TARGET_PREFIX = "AWSHawksNestServiceFacade.";
    private static final Pattern CLAUSE =
            Pattern.compile("^\\$([A-Za-z0-9_-]+)\\s*(==|!=)\\s*\"(.*)\"$");

    private final StorageBackend<String, Detector> detectors;
    private final StorageBackend<String, NamedResource> entityTypes;
    private final StorageBackend<String, EventType> eventTypes;
    private final StorageBackend<String, NamedResource> labels;
    private final StorageBackend<String, NamedResource> outcomes;
    private final StorageBackend<String, Variable> variables;
    private final StorageBackend<String, FraudList> lists;
    private final StorageBackend<String, StoredEvent> events;
    private final StorageBackend<String, PredictionRecord> predictions;
    private final StorageBackend<String, String> purgeJobs;
    private final RegionResolver regionResolver;

    @Inject
    public FraudDetectorService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(SERVICE, "frauddetector-detectors.json",
                        new TypeReference<Map<String, Detector>>() {}),
                storageFactory.create(SERVICE, "frauddetector-entity-types.json",
                        new TypeReference<Map<String, NamedResource>>() {}),
                storageFactory.create(SERVICE, "frauddetector-event-types.json",
                        new TypeReference<Map<String, EventType>>() {}),
                storageFactory.create(SERVICE, "frauddetector-labels.json",
                        new TypeReference<Map<String, NamedResource>>() {}),
                storageFactory.create(SERVICE, "frauddetector-outcomes.json",
                        new TypeReference<Map<String, NamedResource>>() {}),
                storageFactory.create(SERVICE, "frauddetector-variables.json",
                        new TypeReference<Map<String, Variable>>() {}),
                storageFactory.create(SERVICE, "frauddetector-lists.json",
                        new TypeReference<Map<String, FraudList>>() {}),
                storageFactory.create(SERVICE, "frauddetector-events.json",
                        new TypeReference<Map<String, StoredEvent>>() {}),
                storageFactory.create(SERVICE, "frauddetector-predictions.json",
                        new TypeReference<Map<String, PredictionRecord>>() {}),
                storageFactory.create(SERVICE, "frauddetector-purge.json",
                        new TypeReference<Map<String, String>>() {}),
                regionResolver);
    }

    FraudDetectorService(StorageBackend<String, Detector> detectors,
                         StorageBackend<String, NamedResource> entityTypes,
                         StorageBackend<String, EventType> eventTypes,
                         StorageBackend<String, NamedResource> labels,
                         StorageBackend<String, NamedResource> outcomes,
                         StorageBackend<String, Variable> variables,
                         StorageBackend<String, FraudList> lists,
                         StorageBackend<String, StoredEvent> events,
                         StorageBackend<String, PredictionRecord> predictions,
                         StorageBackend<String, String> purgeJobs,
                         RegionResolver regionResolver) {
        this.detectors = detectors;
        this.entityTypes = entityTypes;
        this.eventTypes = eventTypes;
        this.labels = labels;
        this.outcomes = outcomes;
        this.variables = variables;
        this.lists = lists;
        this.events = events;
        this.predictions = predictions;
        this.purgeJobs = purgeJobs;
        this.regionResolver = regionResolver;
    }

    public List<Detector> getDetectors(String region, JsonNode request) {
        String detectorId = text(request, "detectorId");
        if (detectorId != null && !detectorId.isBlank()) {
            return List.of(requireDetector(region, detectorId));
        }
        return inRegion(detectors, region);
    }

    public synchronized Detector putDetector(String region, JsonNode request) {
        String detectorId = requireText(request, "detectorId");
        String eventTypeName = requireText(request, "eventTypeName");
        String now = now();
        Detector detector = detectors.get(key(region, detectorId)).orElseGet(Detector::new);
        if (detector.getDetectorId() == null) {
            detector.setDetectorId(detectorId);
            detector.setCreatedTime(now);
            detector.setArn(arn(region, "detector/" + detectorId));
            detector.setRegion(region);
        }
        detector.setEventTypeName(eventTypeName);
        if (request.has("description") || request.has("Description")) {
            detector.setDescription(text(request, "description"));
        }
        detector.setLastUpdatedTime(now);
        mergeTags(detector.getTags(), request);
        detectors.put(key(region, detectorId), detector);
        return detector;
    }

    public synchronized void deleteDetector(String region, JsonNode request) {
        String detectorId = requireText(request, "detectorId");
        Detector detector = detectors.get(key(region, detectorId))
                .orElseThrow(() -> validation("Detector '" + detectorId + "' does not exist."));
        if (!detector.getVersions().isEmpty()) {
            throw validation("Detector '" + detectorId + "' has associated detector versions.");
        }
        detectors.delete(key(region, detectorId));
    }

    public DetectorVersion getDetectorVersion(String region, JsonNode request) {
        Detector detector = requireDetector(region, requireText(request, "detectorId"));
        String versionId = requireText(request, "detectorVersionId");
        return findVersion(detector, versionId)
                .orElseThrow(() -> notFound("detector version", versionId));
    }

    public synchronized DetectorVersion createDetectorVersion(String region, JsonNode request) {
        Detector detector = requireDetector(region, requireText(request, "detectorId"));
        String now = now();
        int next = detector.getVersions().stream()
                .mapToInt(v -> parseVersion(v.getDetectorVersionId()))
                .max()
                .orElse(0) + 1;
        String versionId = String.valueOf(next);
        DetectorVersion version = new DetectorVersion();
        version.setDetectorId(detector.getDetectorId());
        version.setDetectorVersionId(versionId);
        version.setDescription(text(request, "description"));
        version.setStatus("DRAFT");
        version.setRuleExecutionMode(text(request, "ruleExecutionMode") != null
                ? text(request, "ruleExecutionMode") : "FIRST_MATCHED");
        version.setArn(arn(region, "detector-version/" + detector.getDetectorId() + "/" + versionId));
        version.setCreatedTime(now);
        version.setLastUpdatedTime(now);
        version.setRules(resolveRuleRefs(detector, request.get("rules") != null
                ? request.get("rules") : request.get("Rules")));
        mergeTags(version.getTags(), request);
        detector.getVersions().add(version);
        detector.setLastUpdatedTime(now);
        detectors.put(key(region, detector.getDetectorId()), detector);
        return version;
    }

    public synchronized void updateDetectorVersionStatus(String region, JsonNode request) {
        Detector detector = requireDetector(region, requireText(request, "detectorId"));
        String versionId = requireText(request, "detectorVersionId");
        String status = requireText(request, "status");
        DetectorVersion version = findVersion(detector, versionId)
                .orElseThrow(() -> notFound("detector version", versionId));
        if ("ACTIVE".equals(status)) {
            for (DetectorVersion other : detector.getVersions()) {
                if ("ACTIVE".equals(other.getStatus())) {
                    other.setStatus("INACTIVE");
                }
            }
        }
        version.setStatus(status);
        version.setLastUpdatedTime(now());
        detectors.put(key(region, detector.getDetectorId()), detector);
    }

    public synchronized void deleteDetectorVersion(String region, JsonNode request) {
        Detector detector = requireDetector(region, requireText(request, "detectorId"));
        String versionId = requireText(request, "detectorVersionId");
        DetectorVersion version = findVersion(detector, versionId)
                .orElseThrow(() -> validation("Detector version '" + versionId + "' does not exist."));
        if ("ACTIVE".equals(version.getStatus())) {
            throw validation("An ACTIVE detector version cannot be deleted.");
        }
        detector.getVersions().removeIf(v -> versionId.equals(v.getDetectorVersionId()));
        detectors.put(key(region, detector.getDetectorId()), detector);
    }

    public synchronized Rule createRule(String region, JsonNode request) {
        Detector detector = requireDetector(region, requireText(request, "detectorId"));
        String ruleId = requireText(request, "ruleId");
        String now = now();
        int next = detector.getRules().stream()
                .filter(r -> ruleId.equals(r.getRuleId()))
                .mapToInt(r -> parseVersion(r.getRuleVersion()))
                .max()
                .orElse(0) + 1;
        Rule rule = new Rule();
        rule.setDetectorId(detector.getDetectorId());
        rule.setRuleId(ruleId);
        rule.setRuleVersion(String.valueOf(next));
        rule.setDescription(text(request, "description"));
        rule.setExpression(requireText(request, "expression"));
        rule.setLanguage(text(request, "language") != null ? text(request, "language") : "DETECTORPL");
        rule.setOutcomes(stringList(request.get("outcomes") != null
                ? request.get("outcomes") : request.get("Outcomes")));
        rule.setArn(arn(region, "rule/" + detector.getDetectorId() + "/" + ruleId + "/" + rule.getRuleVersion()));
        rule.setCreatedTime(now);
        rule.setLastUpdatedTime(now);
        mergeTags(rule.getTags(), request);
        detector.getRules().add(rule);
        detectors.put(key(region, detector.getDetectorId()), detector);
        return rule;
    }

    public List<Rule> getRules(String region, JsonNode request) {
        Detector detector = requireDetector(region, requireText(request, "detectorId"));
        String ruleId = text(request, "ruleId");
        String ruleVersion = text(request, "ruleVersion");
        List<Rule> matches = new ArrayList<>();
        for (Rule rule : detector.getRules()) {
            if (ruleId != null && !ruleId.equals(rule.getRuleId())) {
                continue;
            }
            if (ruleVersion != null && !ruleVersion.equals(rule.getRuleVersion())) {
                continue;
            }
            matches.add(rule);
        }
        if (ruleId != null && matches.isEmpty()) {
            throw notFound("rule", ruleId);
        }
        return matches;
    }

    public synchronized void deleteRule(String region, JsonNode request) {
        JsonNode ruleNode = request.get("rule") != null ? request.get("rule") : request.get("Rule");
        if (ruleNode == null || !ruleNode.isObject()) {
            throw validation("rule is a required parameter.");
        }
        Detector detector = requireDetector(region, requireText(ruleNode, "detectorId"));
        String ruleId = requireText(ruleNode, "ruleId");
        String ruleVersion = text(ruleNode, "ruleVersion");
        boolean removed = detector.getRules().removeIf(r ->
                ruleId.equals(r.getRuleId())
                        && (ruleVersion == null || ruleVersion.equals(r.getRuleVersion())));
        if (!removed) {
            throw validation("Rule '" + ruleId + "' does not exist.");
        }
        detectors.put(key(region, detector.getDetectorId()), detector);
    }

    public List<NamedResource> getEntityTypes(String region, JsonNode request) {
        return getNamed(entityTypes, region, text(request, "name"), "entity type");
    }

    public synchronized NamedResource putEntityType(String region, JsonNode request) {
        return putNamed(entityTypes, region, request, "entity-type");
    }

    public synchronized void deleteEntityType(String region, JsonNode request) {
        deleteNamed(entityTypes, region, requireText(request, "name"), "Entity type");
    }

    public List<NamedResource> getLabels(String region, JsonNode request) {
        return getNamed(labels, region, text(request, "name"), "label");
    }

    public synchronized NamedResource putLabel(String region, JsonNode request) {
        return putNamed(labels, region, request, "label");
    }

    public synchronized void deleteLabel(String region, JsonNode request) {
        deleteNamed(labels, region, requireText(request, "name"), "Label");
    }

    public List<NamedResource> getOutcomes(String region, JsonNode request) {
        return getNamed(outcomes, region, text(request, "name"), "outcome");
    }

    public synchronized NamedResource putOutcome(String region, JsonNode request) {
        return putNamed(outcomes, region, request, "outcome");
    }

    public synchronized void deleteOutcome(String region, JsonNode request) {
        deleteNamed(outcomes, region, requireText(request, "name"), "Outcome");
    }

    public List<EventType> getEventTypes(String region, JsonNode request) {
        String name = text(request, "name");
        if (name != null && !name.isBlank()) {
            return List.of(requireEventType(region, name));
        }
        return inRegion(eventTypes, region);
    }

    public synchronized EventType putEventType(String region, JsonNode request) {
        String name = requireText(request, "name");
        String now = now();
        EventType eventType = eventTypes.get(key(region, name)).orElseGet(EventType::new);
        if (eventType.getName() == null) {
            eventType.setName(name);
            eventType.setCreatedTime(now);
            eventType.setArn(arn(region, "event-type/" + name));
            eventType.setRegion(region);
        }
        if (request.has("description") || request.has("Description")) {
            eventType.setDescription(text(request, "description"));
        }
        JsonNode variables = first(request, "eventVariables", "EventVariables");
        if (variables != null) {
            eventType.setEventVariables(stringList(variables));
        }
        JsonNode eventLabels = first(request, "labels", "Labels");
        if (eventLabels != null) {
            eventType.setLabels(stringList(eventLabels));
        }
        JsonNode entityTypeNames = first(request, "entityTypes", "EntityTypes");
        if (entityTypeNames != null) {
            eventType.setEntityTypes(stringList(entityTypeNames));
        }
        String ingestion = text(request, "eventIngestion");
        if (ingestion != null) {
            eventType.setEventIngestion(ingestion);
        }
        JsonNode orchestration = first(request, "eventOrchestration", "EventOrchestration");
        if (orchestration != null) {
            JsonNode enabled = first(orchestration, "eventBridgeEnabled", "EventBridgeEnabled");
            if (enabled != null && !enabled.isNull()) {
                eventType.setEventBridgeEnabled(enabled.asBoolean());
            }
        }
        eventType.setLastUpdatedTime(now);
        mergeTags(eventType.getTags(), request);
        eventTypes.put(key(region, name), eventType);
        return eventType;
    }

    public synchronized void deleteEventType(String region, JsonNode request) {
        String name = requireText(request, "name");
        if (eventTypes.get(key(region, name)).isEmpty()) {
            throw validation("Event type '" + name + "' does not exist.");
        }
        eventTypes.delete(key(region, name));
    }

    public List<Variable> getVariables(String region, JsonNode request) {
        String name = text(request, "name");
        if (name != null && !name.isBlank()) {
            Variable variable = variables.get(key(region, name))
                    .orElseThrow(() -> notFound("variable", name));
            return List.of(variable);
        }
        return inRegion(variables, region);
    }

    public synchronized Variable createVariable(String region, JsonNode request) {
        String name = requireText(request, "name");
        if (variables.get(key(region, name)).isPresent()) {
            throw validation("Variable '" + name + "' already exists.");
        }
        String now = now();
        Variable variable = new Variable();
        variable.setName(name);
        variable.setDataType(requireText(request, "dataType"));
        variable.setDataSource(requireText(request, "dataSource"));
        variable.setDefaultValue(requireText(request, "defaultValue"));
        variable.setDescription(text(request, "description"));
        variable.setVariableType(text(request, "variableType"));
        variable.setArn(arn(region, "variable/" + name));
        variable.setCreatedTime(now);
        variable.setLastUpdatedTime(now);
        variable.setRegion(region);
        mergeTags(variable.getTags(), request);
        variables.put(key(region, name), variable);
        return variable;
    }

    public synchronized Variable updateVariable(String region, JsonNode request) {
        String name = requireText(request, "name");
        Variable variable = variables.get(key(region, name))
                .orElseThrow(() -> notFound("variable", name));
        if (request.has("defaultValue") || request.has("DefaultValue")) {
            variable.setDefaultValue(text(request, "defaultValue"));
        }
        if (request.has("description") || request.has("Description")) {
            variable.setDescription(text(request, "description"));
        }
        if (request.has("variableType") || request.has("VariableType")) {
            variable.setVariableType(text(request, "variableType"));
        }
        variable.setLastUpdatedTime(now());
        variables.put(key(region, name), variable);
        return variable;
    }

    public synchronized void deleteVariable(String region, JsonNode request) {
        String name = requireText(request, "name");
        if (variables.get(key(region, name)).isEmpty()) {
            throw validation("Variable '" + name + "' does not exist.");
        }
        variables.delete(key(region, name));
    }

    public List<FraudList> getListsMetadata(String region, JsonNode request) {
        String name = text(request, "name");
        if (name != null && !name.isBlank()) {
            return List.of(requireList(region, name));
        }
        return inRegion(lists, region);
    }

    public List<String> getListElements(String region, JsonNode request) {
        return new ArrayList<>(requireList(region, requireText(request, "name")).getElements());
    }

    public synchronized void createList(String region, JsonNode request) {
        String name = requireText(request, "name");
        if (lists.get(key(region, name)).isPresent()) {
            throw validation("List '" + name + "' already exists.");
        }
        String now = now();
        FraudList list = new FraudList();
        list.setName(name);
        list.setDescription(text(request, "description"));
        list.setVariableType(text(request, "variableType"));
        list.setArn(arn(region, "list/" + name));
        list.setCreatedTime(now);
        list.setUpdatedTime(now);
        list.setRegion(region);
        list.setElements(stringList(first(request, "elements", "Elements")));
        mergeTags(list.getTags(), request);
        lists.put(key(region, name), list);
    }

    public synchronized void updateList(String region, JsonNode request) {
        String name = requireText(request, "name");
        FraudList list = requireList(region, name);
        String mode = text(request, "updateMode");
        List<String> incoming = stringList(first(request, "elements", "Elements"));
        if (mode == null || "REPLACE".equals(mode)) {
            if (first(request, "elements", "Elements") != null) {
                list.setElements(incoming);
            }
        } else if ("APPEND".equals(mode)) {
            for (String element : incoming) {
                if (!list.getElements().contains(element)) {
                    list.getElements().add(element);
                }
            }
        } else if ("REMOVE".equals(mode)) {
            list.getElements().removeAll(incoming);
        }
        if (request.has("description") || request.has("Description")) {
            list.setDescription(text(request, "description"));
        }
        if (list.getVariableType() == null) {
            String variableType = text(request, "variableType");
            if (variableType != null) {
                list.setVariableType(variableType);
            }
        }
        list.setUpdatedTime(now());
        lists.put(key(region, name), list);
    }

    public synchronized void deleteList(String region, JsonNode request) {
        String name = requireText(request, "name");
        if (lists.get(key(region, name)).isEmpty()) {
            throw validation("List '" + name + "' does not exist.");
        }
        lists.delete(key(region, name));
    }

    public StoredEvent getEvent(String region, JsonNode request) {
        String eventTypeName = requireText(request, "eventTypeName");
        String eventId = requireText(request, "eventId");
        requireEventType(region, eventTypeName);
        return events.get(eventKey(region, eventTypeName, eventId))
                .orElseThrow(() -> notFound("event", eventId));
    }

    public synchronized void sendEvent(String region, JsonNode request) {
        String eventTypeName = requireText(request, "eventTypeName");
        String eventId = requireText(request, "eventId");
        requireEventType(region, eventTypeName);
        StoredEvent event = new StoredEvent();
        event.setEventId(eventId);
        event.setEventTypeName(eventTypeName);
        event.setEventTimestamp(requireText(request, "eventTimestamp"));
        event.setRegion(region);
        event.setEventVariables(stringMap(first(request, "eventVariables", "EventVariables")));
        event.setEntities(entityList(first(request, "entities", "Entities")));
        event.setCurrentLabel(text(request, "assignedLabel"));
        event.setLabelTimestamp(text(request, "labelTimestamp"));
        events.put(eventKey(region, eventTypeName, eventId), event);
    }

    public synchronized void updateEventLabel(String region, JsonNode request) {
        StoredEvent event = getEvent(region, request);
        event.setCurrentLabel(requireText(request, "assignedLabel"));
        event.setLabelTimestamp(requireText(request, "labelTimestamp"));
        events.put(eventKey(region, event.getEventTypeName(), event.getEventId()), event);
    }

    public synchronized void deleteEvent(String region, JsonNode request) {
        String eventTypeName = requireText(request, "eventTypeName");
        String eventId = requireText(request, "eventId");
        requireEventType(region, eventTypeName);
        events.delete(eventKey(region, eventTypeName, eventId));
    }

    public synchronized Map<String, String> deleteEventsByEventType(String region, JsonNode request) {
        String eventTypeName = requireText(request, "eventTypeName");
        requireEventType(region, eventTypeName);
        for (StoredEvent event : new ArrayList<>(events.values())) {
            if (region.equals(event.getRegion()) && eventTypeName.equals(event.getEventTypeName())) {
                events.delete(eventKey(region, eventTypeName, event.getEventId()));
            }
        }
        purgeJobs.put(key(region, eventTypeName), "COMPLETE");
        return Map.of("eventTypeName", eventTypeName, "eventsDeletionStatus", "COMPLETE");
    }

    public Map<String, String> getDeleteEventsByEventTypeStatus(String region, JsonNode request) {
        String eventTypeName = requireText(request, "eventTypeName");
        String status = purgeJobs.get(key(region, eventTypeName))
                .orElseThrow(() -> notFound("delete events job", eventTypeName));
        return Map.of("eventTypeName", eventTypeName, "eventsDeletionStatus", status);
    }

    public synchronized PredictionRecord getEventPrediction(String region, JsonNode request) {
        Detector detector = requireDetector(region, requireText(request, "detectorId"));
        String eventTypeName = requireText(request, "eventTypeName");
        String eventId = requireText(request, "eventId");
        String eventTimestamp = requireText(request, "eventTimestamp");
        String versionId = text(request, "detectorVersionId");
        DetectorVersion version = versionId != null
                ? findVersion(detector, versionId).orElseThrow(() -> notFound("detector version", versionId))
                : detector.getVersions().stream()
                        .filter(v -> "ACTIVE".equals(v.getStatus()))
                        .findFirst()
                        .orElseThrow(() -> new AwsException(
                                "ResourceUnavailableException",
                                "Detector '" + detector.getDetectorId() + "' has no ACTIVE version.",
                                409));
        Map<String, String> vars = stringMap(first(request, "eventVariables", "EventVariables"));
        List<Rule> matched = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        boolean firstMatch = !"ALL_MATCHED".equals(version.getRuleExecutionMode());
        for (Rule ref : version.getRules()) {
            Rule rule = findRule(detector, ref.getRuleId(), ref.getRuleVersion()).orElse(null);
            if (rule == null) {
                continue;
            }
            if (evaluate(rule.getExpression(), vars)) {
                matched.add(rule);
                outcomes.addAll(rule.getOutcomes());
                if (firstMatch) {
                    break;
                }
            }
        }
        String now = now();
        PredictionRecord record = new PredictionRecord();
        record.setEventId(eventId);
        record.setEventTypeName(eventTypeName);
        record.setEventTimestamp(eventTimestamp);
        record.setPredictionTimestamp(now);
        record.setDetectorId(detector.getDetectorId());
        record.setDetectorVersionId(version.getDetectorVersionId());
        record.setDetectorVersionStatus(version.getStatus());
        record.setRuleExecutionMode(version.getRuleExecutionMode());
        record.setRegion(region);
        record.setOutcomes(outcomes);
        record.setEvaluatedRules(matched);
        predictions.put(region + ":" + eventId + ":" + now, record);
        return record;
    }

    public List<PredictionRecord> listEventPredictions(String region, JsonNode request) {
        String eventId = filterValue(request, "eventId");
        String eventType = filterValue(request, "eventType");
        String detectorId = filterValue(request, "detectorId");
        String detectorVersionId = filterValue(request, "detectorVersionId");
        List<PredictionRecord> result = new ArrayList<>();
        for (PredictionRecord record : predictions.values()) {
            if (!region.equals(record.getRegion())) {
                continue;
            }
            if (eventId != null && !eventId.equals(record.getEventId())) {
                continue;
            }
            if (eventType != null && !eventType.equals(record.getEventTypeName())) {
                continue;
            }
            if (detectorId != null && !detectorId.equals(record.getDetectorId())) {
                continue;
            }
            if (detectorVersionId != null && !detectorVersionId.equals(record.getDetectorVersionId())) {
                continue;
            }
            result.add(record);
        }
        return result;
    }

    public PredictionRecord getEventPredictionMetadata(String region, JsonNode request) {
        String eventId = requireText(request, "eventId");
        String predictionTimestamp = requireText(request, "predictionTimestamp");
        return predictions.values().stream()
                .filter(r -> region.equals(r.getRegion())
                        && eventId.equals(r.getEventId())
                        && predictionTimestamp.equals(r.getPredictionTimestamp()))
                .findFirst()
                .orElseThrow(() -> notFound("event prediction", eventId));
    }

    public synchronized void tagResource(String region, JsonNode request) {
        String arn = requireArn(request);
        Map<String, String> tags = tagsForArn(region, arn);
        for (Map.Entry<String, String> entry : readTags(first(request, "tags", "Tags")).entrySet()) {
            tags.put(entry.getKey(), entry.getValue());
        }
    }

    public synchronized void untagResource(String region, JsonNode request) {
        String arn = requireArn(request);
        Map<String, String> tags = tagsForArn(region, arn);
        for (String key : stringList(first(request, "tagKeys", "TagKeys"))) {
            tags.remove(key);
        }
    }

    public Map<String, String> listTagsForResource(String region, JsonNode request) {
        return new LinkedHashMap<>(tagsForArn(region, requireArn(request)));
    }

    @Override
    public void clear() {
        detectors.clear();
        entityTypes.clear();
        eventTypes.clear();
        labels.clear();
        outcomes.clear();
        variables.clear();
        lists.clear();
        events.clear();
        predictions.clear();
        purgeJobs.clear();
    }

    Detector requireDetector(String region, String detectorId) {
        return detectors.get(key(region, detectorId))
                .orElseThrow(() -> notFound("detector", detectorId));
    }

    private EventType requireEventType(String region, String name) {
        return eventTypes.get(key(region, name))
                .orElseThrow(() -> notFound("event type", name));
    }

    private FraudList requireList(String region, String name) {
        return lists.get(key(region, name))
                .orElseThrow(() -> notFound("list", name));
    }

    private List<NamedResource> getNamed(StorageBackend<String, NamedResource> store,
                                         String region, String name, String kind) {
        if (name != null && !name.isBlank()) {
            NamedResource resource = store.get(key(region, name))
                    .orElseThrow(() -> notFound(kind, name));
            return List.of(resource);
        }
        return inRegion(store, region);
    }

    private NamedResource putNamed(StorageBackend<String, NamedResource> store,
                                   String region, JsonNode request, String resource) {
        String name = requireText(request, "name");
        String now = now();
        NamedResource item = store.get(key(region, name)).orElseGet(NamedResource::new);
        if (item.getName() == null) {
            item.setName(name);
            item.setCreatedTime(now);
            item.setArn(arn(region, resource + "/" + name));
            item.setRegion(region);
        }
        if (request.has("description") || request.has("Description")) {
            item.setDescription(text(request, "description"));
        }
        item.setLastUpdatedTime(now);
        mergeTags(item.getTags(), request);
        store.put(key(region, name), item);
        return item;
    }

    private void deleteNamed(StorageBackend<String, NamedResource> store,
                             String region, String name, String label) {
        if (store.get(key(region, name)).isEmpty()) {
            throw validation(label + " '" + name + "' does not exist.");
        }
        store.delete(key(region, name));
    }

    private Map<String, String> tagsForArn(String region, String arn) {
        for (Detector detector : inRegion(detectors, region)) {
            if (arn.equals(detector.getArn())) {
                detectors.put(key(region, detector.getDetectorId()), detector);
                return detector.getTags();
            }
            for (DetectorVersion version : detector.getVersions()) {
                if (arn.equals(version.getArn())) {
                    detectors.put(key(region, detector.getDetectorId()), detector);
                    return version.getTags();
                }
            }
            for (Rule rule : detector.getRules()) {
                if (arn.equals(rule.getArn())) {
                    detectors.put(key(region, detector.getDetectorId()), detector);
                    return rule.getTags();
                }
            }
        }
        for (NamedResource resource : inRegion(entityTypes, region)) {
            if (arn.equals(resource.getArn())) {
                entityTypes.put(key(region, resource.getName()), resource);
                return resource.getTags();
            }
        }
        for (EventType eventType : inRegion(eventTypes, region)) {
            if (arn.equals(eventType.getArn())) {
                eventTypes.put(key(region, eventType.getName()), eventType);
                return eventType.getTags();
            }
        }
        for (NamedResource resource : inRegion(labels, region)) {
            if (arn.equals(resource.getArn())) {
                labels.put(key(region, resource.getName()), resource);
                return resource.getTags();
            }
        }
        for (NamedResource resource : inRegion(outcomes, region)) {
            if (arn.equals(resource.getArn())) {
                outcomes.put(key(region, resource.getName()), resource);
                return resource.getTags();
            }
        }
        for (Variable variable : inRegion(variables, region)) {
            if (arn.equals(variable.getArn())) {
                variables.put(key(region, variable.getName()), variable);
                return variable.getTags();
            }
        }
        for (FraudList list : inRegion(lists, region)) {
            if (arn.equals(list.getArn())) {
                lists.put(key(region, list.getName()), list);
                return list.getTags();
            }
        }
        throw notFound("resource", arn);
    }

    private Optional<DetectorVersion> findVersion(Detector detector, String versionId) {
        return detector.getVersions().stream()
                .filter(v -> versionId.equals(v.getDetectorVersionId()))
                .findFirst();
    }

    private Optional<Rule> findRule(Detector detector, String ruleId, String ruleVersion) {
        return detector.getRules().stream()
                .filter(r -> ruleId.equals(r.getRuleId())
                        && (ruleVersion == null || ruleVersion.equals(r.getRuleVersion())))
                .reduce((a, b) -> parseVersion(b.getRuleVersion()) > parseVersion(a.getRuleVersion()) ? b : a);
    }

    private List<Rule> resolveRuleRefs(Detector detector, JsonNode rulesNode) {
        List<Rule> refs = new ArrayList<>();
        if (rulesNode == null || !rulesNode.isArray()) {
            throw validation("rules is a required parameter.");
        }
        for (JsonNode node : rulesNode) {
            String ruleId = requireText(node, "ruleId");
            String ruleVersion = text(node, "ruleVersion");
            Rule rule = findRule(detector, ruleId, ruleVersion)
                    .orElseThrow(() -> notFound("rule", ruleId));
            Rule ref = new Rule();
            ref.setDetectorId(detector.getDetectorId());
            ref.setRuleId(rule.getRuleId());
            ref.setRuleVersion(rule.getRuleVersion());
            refs.add(ref);
        }
        return refs;
    }

    static boolean evaluate(String expression, Map<String, String> variables) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String[] parts = expression.split("(?i)\\s+and\\s+");
        for (String part : parts) {
            if (!evaluateClause(part.trim(), variables)) {
                return false;
            }
        }
        return true;
    }

    private static boolean evaluateClause(String clause, Map<String, String> variables) {
        Matcher matcher = CLAUSE.matcher(clause);
        if (!matcher.matches()) {
            return false;
        }
        String actual = variables.getOrDefault(matcher.group(1), "");
        String expected = matcher.group(3);
        return "==".equals(matcher.group(2)) ? actual.equals(expected) : !actual.equals(expected);
    }

    private static String filterValue(JsonNode request, String field) {
        JsonNode node = first(request, field, Character.toUpperCase(field.charAt(0)) + field.substring(1));
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return text(node, "value");
    }

    private static <T> List<T> inRegion(StorageBackend<String, T> store, String region) {
        String prefix = region + ":";
        return store.scan(k -> k.startsWith(prefix));
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn(SERVICE, region, resource);
    }

    private static String key(String region, String id) {
        return region + ":" + id;
    }

    private static String eventKey(String region, String eventTypeName, String eventId) {
        return region + ":" + eventTypeName + ":" + eventId;
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static int parseVersion(String version) {
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            String alt = Character.isLowerCase(field.charAt(0))
                    ? Character.toUpperCase(field.charAt(0)) + field.substring(1)
                    : Character.toLowerCase(field.charAt(0)) + field.substring(1);
            value = node.get(alt);
        }
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    static String requireText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw validation(field + " is a required parameter.");
        }
        return value;
    }

    private static String requireArn(JsonNode request) {
        String arn = text(request, "resourceARN");
        if (arn == null) {
            throw validation("resourceARN is a required parameter.");
        }
        return arn;
    }

    private static JsonNode first(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item != null && item.isValueNode() && !item.isNull()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return values;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode value = node.get(name);
            if (value != null && value.isValueNode() && !value.isNull()) {
                values.put(name, value.asText());
            }
        }
        return values;
    }

    private static List<Map<String, String>> entityList(JsonNode node) {
        List<Map<String, String>> entities = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return entities;
        }
        for (JsonNode item : node) {
            Map<String, String> entity = new LinkedHashMap<>();
            String type = text(item, "entityType");
            String id = text(item, "entityId");
            if (type != null) {
                entity.put("entityType", type);
            }
            if (id != null) {
                entity.put("entityId", id);
            }
            entities.add(entity);
        }
        return entities;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || !node.isArray()) {
            return tags;
        }
        for (JsonNode tag : node) {
            String key = text(tag, "key");
            if (key == null) {
                key = text(tag, "Key");
            }
            if (key != null) {
                String value = text(tag, "value");
                if (value == null) {
                    value = text(tag, "Value");
                }
                tags.put(key, value != null ? value : "");
            }
        }
        return tags;
    }

    private static void mergeTags(Map<String, String> target, JsonNode request) {
        target.putAll(readTags(first(request, "tags", "Tags")));
    }

    static AwsException notFound(String kind, String id) {
        return new AwsException(
                "ResourceNotFoundException",
                kind + " '" + id + "' does not exist.",
                404);
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
