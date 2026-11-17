package io.floci.aws.services.frauddetector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.sagemaker.SageMakerService;
import io.floci.aws.services.sagemaker.model.SageMakerEntities;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Amazon Fraud Detector: entity types, labels, outcomes, variables, lists, event types, detectors with versioned
 * rules, models, external models, stored events and predictions.
 *
 * <p>A prediction runs for real. The detector version's model versions score the event, its external models are
 * invoked through their SageMaker endpoints, and its rules are evaluated in order in the DETECTORPL rule language
 * (see {@link FraudDetectorRules}) against the event's variables, the model scores and the account's lists. Each
 * prediction is kept for {@code ListEventPredictions} and {@code GetEventPredictionMetadata}, stored as an event
 * when the event type ingests events, and published to EventBridge when the event type has event orchestration.
 * Model versions train on the labeled events (stored, or a CSV file in S3) with per-value log-odds, and their score
 * is the fraud probability scaled to 0–1000. Batch import and batch prediction jobs read a CSV file from S3 and
 * write their results back to S3.
 */
@ApplicationScoped
public class FraudDetectorService implements Resettable {

    private static final Logger LOG = Logger.getLogger(FraudDetectorService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String SERVICE = "frauddetector";
    private static final long JOB_DELAY_MILLIS = 500;
    private static final int MAX_LIST_ELEMENT_LENGTH = 320;

    private final AccountAwareStorageBackend<ObjectNode> entityTypes;
    private final AccountAwareStorageBackend<ObjectNode> labels;
    private final AccountAwareStorageBackend<ObjectNode> outcomes;
    private final AccountAwareStorageBackend<ObjectNode> variables;
    private final AccountAwareStorageBackend<ObjectNode> lists;
    private final AccountAwareStorageBackend<ObjectNode> eventTypes;
    private final AccountAwareStorageBackend<ObjectNode> detectors;
    private final AccountAwareStorageBackend<ObjectNode> rules;
    private final AccountAwareStorageBackend<ObjectNode> detectorVersions;
    private final AccountAwareStorageBackend<ObjectNode> models;
    private final AccountAwareStorageBackend<ObjectNode> modelVersions;
    private final AccountAwareStorageBackend<ObjectNode> externalModels;
    private final AccountAwareStorageBackend<ObjectNode> events;
    private final AccountAwareStorageBackend<ObjectNode> predictions;
    private final AccountAwareStorageBackend<ObjectNode> batchImports;
    private final AccountAwareStorageBackend<ObjectNode> batchPredictions;
    private final AccountAwareStorageBackend<ObjectNode> settings;
    private final AccountAwareStorageBackend<ObjectNode> tags;
    private final ObjectMapper mapper;
    private final Provider<EventBridgeService> eventBridge;
    private final Provider<S3Service> s3;
    private final Provider<IamService> iam;
    private final Provider<KmsService> kms;
    private final Provider<SageMakerService> sageMaker;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "frauddetector-jobs");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public FraudDetectorService(StorageFactory storageFactory, ObjectMapper mapper,
                                Provider<EventBridgeService> eventBridge, Provider<S3Service> s3,
                                Provider<IamService> iam, Provider<KmsService> kms,
                                Provider<SageMakerService> sageMaker) {
        this.entityTypes = store(storageFactory, "entity-types");
        this.labels = store(storageFactory, "labels");
        this.outcomes = store(storageFactory, "outcomes");
        this.variables = store(storageFactory, "variables");
        this.lists = store(storageFactory, "lists");
        this.eventTypes = store(storageFactory, "event-types");
        this.detectors = store(storageFactory, "detectors");
        this.rules = store(storageFactory, "rules");
        this.detectorVersions = store(storageFactory, "detector-versions");
        this.models = store(storageFactory, "models");
        this.modelVersions = store(storageFactory, "model-versions");
        this.externalModels = store(storageFactory, "external-models");
        this.events = store(storageFactory, "events");
        this.predictions = store(storageFactory, "predictions");
        this.batchImports = store(storageFactory, "batch-imports");
        this.batchPredictions = store(storageFactory, "batch-predictions");
        this.settings = store(storageFactory, "settings");
        this.tags = store(storageFactory, "tags");
        this.mapper = mapper;
        this.eventBridge = eventBridge;
        this.s3 = s3;
        this.iam = iam;
        this.kms = kms;
        this.sageMaker = sageMaker;
    }

    private static AccountAwareStorageBackend<ObjectNode> store(StorageFactory factory, String name) {
        return factory.create("frauddetector", name + ".json", new TypeReference<Map<String, ObjectNode>>() {});
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    @Override
    public void clear() {
        for (AccountAwareStorageBackend<ObjectNode> store : List.of(entityTypes, labels, outcomes, variables, lists,
                eventTypes, detectors, rules, detectorVersions, models, modelVersions, externalModels, events,
                predictions, batchImports, batchPredictions, settings, tags)) {
            store.clear();
        }
    }

    public synchronized ObjectNode handle(String operation, JsonNode in, String region) {
        return switch (operation) {
            case "PutEntityType" -> putNamed(entityTypes, "entity-type", in, region);
            case "GetEntityTypes" -> getNamed(entityTypes, "entity-type", "entityTypes", 10, in, region);
            case "DeleteEntityType" -> deleteEntityType(in, region);
            case "PutLabel" -> putNamed(labels, "label", in, region);
            case "GetLabels" -> getNamed(labels, "label", "labels", 50, in, region);
            case "DeleteLabel" -> deleteLabel(in, region);
            case "PutOutcome" -> putNamed(outcomes, "outcome", in, region);
            case "GetOutcomes" -> getNamed(outcomes, "outcome", "outcomes", 100, in, region);
            case "DeleteOutcome" -> deleteOutcome(in, region);
            case "CreateVariable" -> createVariable(in, region);
            case "BatchCreateVariable" -> batchCreateVariable(in, region);
            case "GetVariables" -> getNamed(variables, "variable", "variables", 100, in, region);
            case "BatchGetVariable" -> batchGetVariable(in, region);
            case "UpdateVariable" -> updateVariable(in, region);
            case "DeleteVariable" -> deleteVariable(in, region);
            case "CreateList" -> createList(in, region);
            case "UpdateList" -> updateList(in, region);
            case "GetListElements" -> getListElements(in, region);
            case "GetListsMetadata" -> getListsMetadata(in, region);
            case "DeleteList" -> deleteList(in, region);
            case "PutEventType" -> putEventType(in, region);
            case "GetEventTypes" -> getEventTypes(in, region);
            case "DeleteEventType" -> deleteEventType(in, region);
            case "PutDetector" -> putDetector(in, region);
            case "GetDetectors" -> getNamed(detectors, "detector", "detectors", 10, in, region);
            case "DescribeDetector" -> describeDetector(in, region);
            case "DeleteDetector" -> deleteDetector(in, region);
            case "CreateRule" -> createRule(in, region);
            case "UpdateRuleVersion" -> updateRuleVersion(in, region);
            case "UpdateRuleMetadata" -> updateRuleMetadata(in, region);
            case "GetRules" -> getRules(in, region);
            case "DeleteRule" -> deleteRule(in, region);
            case "CreateDetectorVersion" -> createDetectorVersion(in, region);
            case "GetDetectorVersion" -> view(detectorVersion(region, text(in, "detectorId"),
                    text(in, "detectorVersionId")));
            case "UpdateDetectorVersion" -> updateDetectorVersion(in, region);
            case "UpdateDetectorVersionMetadata" -> updateDetectorVersionMetadata(in, region);
            case "UpdateDetectorVersionStatus" -> updateDetectorVersionStatus(in, region);
            case "DeleteDetectorVersion" -> deleteDetectorVersion(in, region);
            case "CreateModel" -> createModel(in, region);
            case "UpdateModel" -> updateModel(in, region);
            case "GetModels" -> getModels(in, region);
            case "DeleteModel" -> deleteModel(in, region);
            case "CreateModelVersion" -> createModelVersion(in, region);
            case "UpdateModelVersion" -> updateModelVersion(in, region);
            case "UpdateModelVersionStatus" -> updateModelVersionStatus(in, region);
            case "GetModelVersion" -> getModelVersion(in, region);
            case "DescribeModelVersions" -> describeModelVersions(in, region);
            case "DeleteModelVersion" -> deleteModelVersion(in, region);
            case "PutExternalModel" -> putExternalModel(in, region);
            case "GetExternalModels" -> getExternalModels(in, region);
            case "DeleteExternalModel" -> deleteExternalModel(in, region);
            case "SendEvent" -> sendEvent(in, region);
            case "GetEvent" -> getEvent(in, region);
            case "UpdateEventLabel" -> updateEventLabel(in, region);
            case "DeleteEvent" -> deleteEvent(in, region);
            case "DeleteEventsByEventType" -> deleteEventsByEventType(in, region);
            case "GetDeleteEventsByEventTypeStatus" -> getDeleteEventsByEventTypeStatus(in, region);
            case "GetEventPrediction" -> getEventPrediction(in, region);
            case "ListEventPredictions" -> listEventPredictions(in, region);
            case "GetEventPredictionMetadata" -> getEventPredictionMetadata(in, region);
            case "CreateBatchImportJob" -> createBatchImportJob(in, region);
            case "GetBatchImportJobs" -> getJobs(batchImports, "batch-import", "batchImports", in, region);
            case "CancelBatchImportJob" -> cancelJob(batchImports, "batch-import", in, region);
            case "DeleteBatchImportJob" -> deleteJob(batchImports, "batch-import", in, region);
            case "CreateBatchPredictionJob" -> createBatchPredictionJob(in, region);
            case "GetBatchPredictionJobs" -> getJobs(batchPredictions, "batch-prediction", "batchPredictions", in,
                    region);
            case "CancelBatchPredictionJob" -> cancelJob(batchPredictions, "batch-prediction", in, region);
            case "DeleteBatchPredictionJob" -> deleteJob(batchPredictions, "batch-prediction", in, region);
            case "GetKMSEncryptionKey" -> getKmsEncryptionKey(region);
            case "PutKMSEncryptionKey" -> putKmsEncryptionKey(in, region);
            case "TagResource" -> tagResource(in);
            case "UntagResource" -> untagResource(in);
            case "ListTagsForResource" -> listTagsForResource(in);
            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + operation + " is not supported.", 400);
        };
    }

    // ---------- shared ----------

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, tags.accountId(), resource).toString();
    }

    private static String now() {
        return TIME.format(Instant.now());
    }

    private static String text(JsonNode in, String field) {
        JsonNode value = in == null ? null : in.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> out.add(item.asText()));
        }
        return out;
    }

    /** The record without its internal ({@code _}-prefixed) fields. */
    private ObjectNode view(ObjectNode record) {
        ObjectNode out = record.deepCopy();
        List<String> hidden = new ArrayList<>();
        out.fieldNames().forEachRemaining(name -> {
            if (name.startsWith("_")) {
                hidden.add(name);
            }
        });
        out.remove(hidden);
        return out;
    }

    private ObjectNode created(AccountAwareStorageBackend<ObjectNode> store, String arn, ObjectNode record,
                               JsonNode tagList) {
        String time = now();
        record.put("createdTime", time);
        record.put("lastUpdatedTime", time);
        record.put("arn", arn);
        store.put(arn, record);
        ObjectNode tagMap = mapper.createObjectNode();
        if (tagList != null && tagList.isArray()) {
            tagList.forEach(tag -> tagMap.put(tag.path("key").asText(), tag.path("value").asText()));
        }
        tags.put(arn, tagMap);
        return record;
    }

    private void removed(AccountAwareStorageBackend<ObjectNode> store, String arn) {
        store.delete(arn);
        tags.delete(arn);
    }

    private void touch(AccountAwareStorageBackend<ObjectNode> store, ObjectNode record) {
        record.put("lastUpdatedTime", now());
        store.put(record.path("arn").asText(), record);
    }

    /** Records of the store in the region, sorted by ARN. */
    private List<ObjectNode> inRegion(AccountAwareStorageBackend<ObjectNode> store, String region,
                                      Predicate<ObjectNode> filter) {
        String prefix = arn(region, "");
        return store.scan(key -> key.startsWith(prefix)).stream().filter(filter)
                .sorted(Comparator.comparing(record -> record.path("arn").asText())).toList();
    }

    /** One page of items into {@code out[field]}, with a {@code nextToken} when more remain. */
    private ObjectNode page(List<ObjectNode> items, JsonNode in, int defaultMax, String field, ObjectNode out) {
        int max = in.hasNonNull("maxResults") ? in.get("maxResults").asInt() : defaultMax;
        int start = 0;
        String token = text(in, "nextToken");
        if (token != null && !token.isEmpty()) {
            try {
                start = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw validation("The nextToken is invalid.");
            }
        }
        ArrayNode page = out.putArray(field);
        for (int i = start; i < Math.min(items.size(), start + max); i++) {
            page.add(view(items.get(i)));
        }
        if (start + max < items.size()) {
            out.put("nextToken", String.valueOf(start + max));
        }
        return out;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    // ---------- entity types, labels, outcomes ----------

    private ObjectNode putNamed(AccountAwareStorageBackend<ObjectNode> store, String kind, JsonNode in,
                                String region) {
        String name = text(in, "name");
        String arn = arn(region, kind + "/" + name);
        Optional<ObjectNode> existing = store.get(arn);
        if (existing.isPresent()) {
            ObjectNode record = existing.get();
            if (in.hasNonNull("description")) {
                record.put("description", text(in, "description"));
            } else {
                record.remove("description");
            }
            touch(store, record);
        } else {
            ObjectNode record = mapper.createObjectNode().put("name", name);
            if (in.hasNonNull("description")) {
                record.put("description", text(in, "description"));
            }
            created(store, arn, record, in.get("tags"));
        }
        return mapper.createObjectNode();
    }

    /** One named resource when {@code name} is given (ResourceNotFound when missing), otherwise a page of all. */
    private ObjectNode getNamed(AccountAwareStorageBackend<ObjectNode> store, String kind, String field,
                                int defaultMax, JsonNode in, String region) {
        String key = "detector".equals(kind) ? "detectorId" : "name";
        String name = text(in, key);
        ObjectNode out = mapper.createObjectNode();
        if (name != null) {
            ObjectNode record = store.get(arn(region, kind + "/" + name)).orElseThrow(() ->
                    notFound("The " + kind.replace('-', ' ') + " " + name + " does not exist."));
            out.putArray(field).add(view(record));
            return out;
        }
        return page(inRegion(store, region, record -> true), in, defaultMax, field, out);
    }

    private ObjectNode deleteEntityType(JsonNode in, String region) {
        String name = text(in, "name");
        for (ObjectNode eventType : inRegion(eventTypes, region, record -> true)) {
            if (strings(eventType.get("entityTypes")).contains(name)) {
                throw conflict("The entity type " + name + " is used by the event type "
                        + eventType.path("name").asText() + ".");
            }
        }
        removed(entityTypes, arn(region, "entity-type/" + name));
        return mapper.createObjectNode();
    }

    private ObjectNode deleteLabel(JsonNode in, String region) {
        String name = text(in, "name");
        for (ObjectNode eventType : inRegion(eventTypes, region, record -> true)) {
            if (strings(eventType.get("labels")).contains(name)) {
                throw conflict("The label " + name + " is used by the event type "
                        + eventType.path("name").asText() + ".");
            }
        }
        removed(labels, arn(region, "label/" + name));
        return mapper.createObjectNode();
    }

    private ObjectNode deleteOutcome(JsonNode in, String region) {
        String name = text(in, "name");
        for (ObjectNode rule : inRegion(rules, region, record -> true)) {
            if (strings(rule.get("outcomes")).contains(name)) {
                throw conflict("The outcome " + name + " is used by the rule " + rule.path("ruleId").asText()
                        + " of the detector " + rule.path("detectorId").asText() + ".");
            }
        }
        removed(outcomes, arn(region, "outcome/" + name));
        return mapper.createObjectNode();
    }

    // ---------- variables ----------

    private String variableArn(String region, String name) {
        return arn(region, "variable/" + name);
    }

    private Optional<ObjectNode> variable(String region, String name) {
        return variables.get(variableArn(region, name));
    }

    private ObjectNode createVariable(JsonNode in, String region) {
        String name = text(in, "name");
        String dataType = text(in, "dataType");
        String dataSource = text(in, "dataSource");
        String defaultValue = text(in, "defaultValue");
        if (!name.matches("[a-z_][a-z0-9_]{0,63}")) {
            throw validation("The variable name " + name + " must start with a lowercase letter or underscore and"
                    + " contain only lowercase letters, numbers and underscores.");
        }
        if ("MODEL_SCORE".equals(dataSource)) {
            throw validation("Variables with the data source MODEL_SCORE are created by activating a model"
                    + " version.");
        }
        if (variable(region, name).isPresent()) {
            throw validation("The variable " + name + " already exists.");
        }
        checkValue(dataType, defaultValue, "default value");
        ObjectNode record = mapper.createObjectNode().put("name", name).put("dataType", dataType)
                .put("dataSource", dataSource).put("defaultValue", defaultValue);
        if (in.hasNonNull("description")) {
            record.put("description", text(in, "description"));
        }
        if (in.hasNonNull("variableType")) {
            record.put("variableType", text(in, "variableType"));
        }
        created(variables, variableArn(region, name), record, in.get("tags"));
        return mapper.createObjectNode();
    }

    /** A value of the data type, or a validation error naming what it was for. */
    private static void checkValue(String dataType, String value, String what) {
        if (typed(dataType, value) == null && value != null) {
            throw validation("The " + what + " " + value + " is not a valid " + dataType + ".");
        }
    }

    /** The value as the rule language sees it: a number, a boolean or a string; null when it doesn't parse. */
    static Object typed(String dataType, String value) {
        if (value == null) {
            return null;
        }
        try {
            return switch (dataType == null ? "STRING" : dataType) {
                case "INTEGER" -> (double) Long.parseLong(value.trim());
                case "FLOAT" -> Double.parseDouble(value.trim());
                case "BOOLEAN" -> {
                    if (!"true".equalsIgnoreCase(value.trim()) && !"false".equalsIgnoreCase(value.trim())) {
                        yield null;
                    }
                    yield Boolean.parseBoolean(value.trim());
                }
                case "DATETIME" -> Instant.parse(value.trim()).toString();
                default -> value;
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            return null;
        }
    }

    private ObjectNode batchCreateVariable(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode errors = out.putArray("errors");
        for (JsonNode entry : in.path("variableEntries")) {
            ObjectNode request = entry.deepCopy();
            if (in.has("tags")) {
                request.set("tags", in.get("tags"));
            }
            try {
                createVariable(request, region);
            } catch (AwsException e) {
                errors.addObject().put("name", text(entry, "name")).put("code", e.getHttpStatus())
                        .put("message", e.getMessage());
            }
        }
        return out;
    }

    private ObjectNode batchGetVariable(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode found = out.putArray("variables");
        ArrayNode errors = out.putArray("errors");
        for (String name : strings(in.get("names"))) {
            Optional<ObjectNode> record = variable(region, name);
            if (record.isPresent()) {
                found.add(view(record.get()));
            } else {
                errors.addObject().put("name", name).put("code", 404)
                        .put("message", "The variable " + name + " does not exist.");
            }
        }
        return out;
    }

    private ObjectNode updateVariable(JsonNode in, String region) {
        String name = text(in, "name");
        ObjectNode record = variable(region, name).orElseThrow(() ->
                notFound("The variable " + name + " does not exist."));
        if (in.hasNonNull("defaultValue")) {
            checkValue(record.path("dataType").asText(), text(in, "defaultValue"), "default value");
            record.put("defaultValue", text(in, "defaultValue"));
        }
        if (in.hasNonNull("description")) {
            record.put("description", text(in, "description"));
        }
        if (in.hasNonNull("variableType")) {
            record.put("variableType", text(in, "variableType"));
        }
        touch(variables, record);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteVariable(JsonNode in, String region) {
        String name = text(in, "name");
        Optional<ObjectNode> record = variable(region, name);
        if (record.isEmpty()) {
            return mapper.createObjectNode();
        }
        if ("MODEL_SCORE".equals(record.get().path("dataSource").asText())) {
            throw conflict("The variable " + name + " is the output of a model; deactivate the model version"
                    + " instead.");
        }
        for (ObjectNode eventType : inRegion(eventTypes, region, r -> true)) {
            if (strings(eventType.get("eventVariables")).contains(name)) {
                throw conflict("The variable " + name + " is used by the event type "
                        + eventType.path("name").asText() + ".");
            }
        }
        for (ObjectNode rule : inRegion(rules, region, r -> true)) {
            if (strings(rule.get("_variables")).contains(name)) {
                throw conflict("The variable " + name + " is used by the rule " + rule.path("ruleId").asText()
                        + ".");
            }
        }
        removed(variables, variableArn(region, name));
        return mapper.createObjectNode();
    }

    // ---------- lists ----------

    private String listArn(String region, String name) {
        return arn(region, "list/" + name);
    }

    private ObjectNode list(String region, String name) {
        return lists.get(listArn(region, name)).orElseThrow(() ->
                notFound("The list " + name + " does not exist."));
    }

    private static void checkElements(List<String> elements) {
        for (String element : elements) {
            if (element.isEmpty() || element.length() > MAX_LIST_ELEMENT_LENGTH) {
                throw validation("List elements must be between 1 and " + MAX_LIST_ELEMENT_LENGTH
                        + " characters long.");
            }
        }
    }

    private ObjectNode createList(JsonNode in, String region) {
        String name = text(in, "name");
        if (lists.get(listArn(region, name)).isPresent()) {
            throw validation("The list " + name + " already exists.");
        }
        List<String> elements = strings(in.get("elements"));
        checkElements(elements);
        ObjectNode record = mapper.createObjectNode().put("name", name);
        if (in.hasNonNull("description")) {
            record.put("description", text(in, "description"));
        }
        if (in.hasNonNull("variableType")) {
            record.put("variableType", text(in, "variableType"));
        }
        ArrayNode stored = record.putArray("_elements");
        new LinkedHashSet<>(elements).forEach(stored::add);
        created(lists, listArn(region, name), record, in.get("tags"));
        record.put("updatedTime", record.path("lastUpdatedTime").asText());
        record.remove("lastUpdatedTime");
        lists.put(listArn(region, name), record);
        return mapper.createObjectNode();
    }

    private ObjectNode updateList(JsonNode in, String region) {
        String name = text(in, "name");
        ObjectNode record = list(region, name);
        String mode = in.hasNonNull("updateMode") ? text(in, "updateMode") : "REPLACE";
        if (in.has("elements")) {
            List<String> elements = strings(in.get("elements"));
            checkElements(elements);
            LinkedHashSet<String> current = new LinkedHashSet<>(strings(record.get("_elements")));
            switch (mode) {
                case "APPEND" -> current.addAll(elements);
                case "REMOVE" -> elements.forEach(current::remove);
                default -> {
                    current.clear();
                    current.addAll(elements);
                }
            }
            ArrayNode stored = record.putArray("_elements");
            current.forEach(stored::add);
        } else if (!"REPLACE".equals(mode)) {
            throw validation("The update mode " + mode + " needs elements.");
        }
        if (in.hasNonNull("description")) {
            record.put("description", text(in, "description"));
        }
        if (in.hasNonNull("variableType")) {
            String variableType = text(in, "variableType");
            if (record.hasNonNull("variableType") && !variableType.equals(record.get("variableType").asText())) {
                throw validation("The variable type of the list " + name + " is already "
                        + record.get("variableType").asText() + " and can't be changed.");
            }
            record.put("variableType", variableType);
        }
        record.put("updatedTime", now());
        lists.put(listArn(region, name), record);
        return mapper.createObjectNode();
    }

    private ObjectNode getListElements(JsonNode in, String region) {
        ObjectNode record = list(region, text(in, "name"));
        List<String> elements = strings(record.get("_elements"));
        int max = in.hasNonNull("maxResults") ? in.get("maxResults").asInt() : 5000;
        int start = in.hasNonNull("nextToken") && !text(in, "nextToken").isEmpty()
                ? Integer.parseInt(text(in, "nextToken")) : 0;
        ObjectNode out = mapper.createObjectNode();
        ArrayNode page = out.putArray("elements");
        elements.subList(Math.min(start, elements.size()), Math.min(elements.size(), start + max))
                .forEach(page::add);
        if (start + max < elements.size()) {
            out.put("nextToken", String.valueOf(start + max));
        }
        return out;
    }

    private ObjectNode getListsMetadata(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        String name = text(in, "name");
        if (name != null) {
            out.putArray("lists").add(view(list(region, name)));
            return out;
        }
        return page(inRegion(lists, region, r -> true), in, 50, "lists", out);
    }

    private ObjectNode deleteList(JsonNode in, String region) {
        String name = text(in, "name");
        for (ObjectNode rule : inRegion(rules, region, r -> true)) {
            if (strings(rule.get("_lists")).contains(name)) {
                throw conflict("The list " + name + " is used by the rule " + rule.path("ruleId").asText()
                        + " of the detector " + rule.path("detectorId").asText() + ".");
            }
        }
        removed(lists, listArn(region, name));
        return mapper.createObjectNode();
    }

    private Set<String> listElements(String region, String name) {
        return lists.get(listArn(region, name)).map(record -> (Set<String>) new HashSet<>(
                strings(record.get("_elements")))).orElse(Set.of());
    }

    // ---------- event types ----------

    private String eventTypeArn(String region, String name) {
        return arn(region, "event-type/" + name);
    }

    private ObjectNode eventType(String region, String name) {
        return eventTypes.get(eventTypeArn(region, name)).orElseThrow(() ->
                notFound("The event type " + name + " does not exist."));
    }

    private ObjectNode putEventType(JsonNode in, String region) {
        String name = text(in, "name");
        List<String> eventVariables = strings(in.get("eventVariables"));
        List<String> entityTypeNames = strings(in.get("entityTypes"));
        List<String> labelNames = strings(in.get("labels"));
        for (String variableName : eventVariables) {
            ObjectNode record = variable(region, variableName).orElseThrow(() ->
                    validation("The variable " + variableName + " does not exist."));
            if (!"EVENT".equals(record.path("dataSource").asText())) {
                throw validation("The variable " + variableName + " has the data source "
                        + record.path("dataSource").asText() + "; event types use EVENT variables.");
            }
        }
        for (String entityType : entityTypeNames) {
            if (entityTypes.get(arn(region, "entity-type/" + entityType)).isEmpty()) {
                throw validation("The entity type " + entityType + " does not exist.");
            }
        }
        for (String label : labelNames) {
            if (labels.get(arn(region, "label/" + label)).isEmpty()) {
                throw validation("The label " + label + " does not exist.");
            }
        }
        String arn = eventTypeArn(region, name);
        Optional<ObjectNode> existing = eventTypes.get(arn);
        ObjectNode record = existing.orElseGet(() -> mapper.createObjectNode().put("name", name));
        if (existing.isPresent()) {
            List<String> removedVariables = new ArrayList<>(strings(record.get("eventVariables")));
            removedVariables.removeAll(eventVariables);
            if (!removedVariables.isEmpty() && !inRegion(detectors, region, detector ->
                    name.equals(detector.path("eventTypeName").asText())).isEmpty()) {
                throw conflict("Variables can't be removed from the event type " + name
                        + " while detectors use it: " + removedVariables + ".");
            }
        }
        if (in.hasNonNull("description")) {
            record.put("description", text(in, "description"));
        } else {
            record.remove("description");
        }
        ArrayNode variableArray = record.putArray("eventVariables");
        eventVariables.forEach(variableArray::add);
        ArrayNode labelArray = record.putArray("labels");
        labelNames.forEach(labelArray::add);
        ArrayNode entityArray = record.putArray("entityTypes");
        entityTypeNames.forEach(entityArray::add);
        record.put("eventIngestion", in.hasNonNull("eventIngestion") ? text(in, "eventIngestion")
                : existing.map(r -> r.path("eventIngestion").asText()).orElse("ENABLED"));
        if (in.has("eventOrchestration") && !in.get("eventOrchestration").isNull()) {
            record.set("eventOrchestration", mapper.createObjectNode().put("eventBridgeEnabled",
                    in.path("eventOrchestration").path("eventBridgeEnabled").asBoolean()));
        }
        if (existing.isPresent()) {
            touch(eventTypes, record);
        } else {
            created(eventTypes, arn, record, in.get("tags"));
        }
        return mapper.createObjectNode();
    }

    private ObjectNode getEventTypes(JsonNode in, String region) {
        ObjectNode out = getNamed(eventTypes, "event-type", "eventTypes", 10, in, region);
        for (JsonNode eventType : out.path("eventTypes")) {
            ((ObjectNode) eventType).set("ingestedEventStatistics", statistics(region, eventType.path("name")
                    .asText()));
        }
        return out;
    }

    private ObjectNode statistics(String region, String eventTypeName) {
        List<ObjectNode> stored = storedEvents(region, eventTypeName);
        ObjectNode out = mapper.createObjectNode();
        out.put("numberOfEvents", stored.size());
        long bytes = 0;
        String least = null;
        String most = null;
        for (ObjectNode event : stored) {
            bytes += event.toString().getBytes(StandardCharsets.UTF_8).length;
            String at = event.path("eventTimestamp").asText();
            least = least == null || at.compareTo(least) < 0 ? at : least;
            most = most == null || at.compareTo(most) > 0 ? at : most;
        }
        out.put("eventDataSizeInBytes", bytes);
        if (least != null) {
            out.put("leastRecentEvent", least);
            out.put("mostRecentEvent", most);
        }
        out.put("lastUpdatedTime", now());
        return out;
    }

    private ObjectNode deleteEventType(JsonNode in, String region) {
        String name = text(in, "name");
        for (ObjectNode detector : inRegion(detectors, region, r -> true)) {
            if (name.equals(detector.path("eventTypeName").asText())) {
                throw conflict("The event type " + name + " is used by the detector "
                        + detector.path("detectorId").asText() + ".");
            }
        }
        for (ObjectNode model : inRegion(models, region, r -> true)) {
            if (name.equals(model.path("eventTypeName").asText())) {
                throw conflict("The event type " + name + " is used by the model "
                        + model.path("modelId").asText() + ".");
            }
        }
        for (ObjectNode event : storedEvents(region, name)) {
            events.delete(event.path("_key").asText());
        }
        removed(eventTypes, eventTypeArn(region, name));
        return mapper.createObjectNode();
    }

    // ---------- detectors ----------

    private String detectorArn(String region, String detectorId) {
        return arn(region, "detector/" + detectorId);
    }

    private ObjectNode detector(String region, String detectorId) {
        return detectors.get(detectorArn(region, detectorId)).orElseThrow(() ->
                notFound("The detector " + detectorId + " does not exist."));
    }

    private ObjectNode putDetector(JsonNode in, String region) {
        String detectorId = text(in, "detectorId");
        String eventTypeName = text(in, "eventTypeName");
        if (eventTypes.get(eventTypeArn(region, eventTypeName)).isEmpty()) {
            throw validation("The event type " + eventTypeName + " does not exist.");
        }
        String arn = detectorArn(region, detectorId);
        Optional<ObjectNode> existing = detectors.get(arn);
        if (existing.isPresent()) {
            ObjectNode record = existing.get();
            if (!eventTypeName.equals(record.path("eventTypeName").asText())
                    && !versionsOf(region, detectorId).isEmpty()) {
                throw conflict("The detector " + detectorId + " has versions; its event type can't change.");
            }
            record.put("eventTypeName", eventTypeName);
            if (in.hasNonNull("description")) {
                record.put("description", text(in, "description"));
            } else {
                record.remove("description");
            }
            touch(detectors, record);
        } else {
            ObjectNode record = mapper.createObjectNode().put("detectorId", detectorId)
                    .put("eventTypeName", eventTypeName);
            if (in.hasNonNull("description")) {
                record.put("description", text(in, "description"));
            }
            created(detectors, arn, record, in.get("tags"));
        }
        return mapper.createObjectNode();
    }

    private List<ObjectNode> versionsOf(String region, String detectorId) {
        String prefix = arn(region, "detector-version/" + detectorId + "/");
        return detectorVersions.scan(key -> key.startsWith(prefix)).stream()
                .sorted(Comparator.comparingInt(v -> v.path("detectorVersionId").asInt())).toList();
    }

    private ObjectNode describeDetector(JsonNode in, String region) {
        String detectorId = text(in, "detectorId");
        ObjectNode detector = detector(region, detectorId);
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode version : versionsOf(region, detectorId)) {
            ObjectNode summary = mapper.createObjectNode()
                    .put("detectorVersionId", version.path("detectorVersionId").asText())
                    .put("status", version.path("status").asText())
                    .put("lastUpdatedTime", version.path("lastUpdatedTime").asText());
            if (version.hasNonNull("description")) {
                summary.put("description", version.get("description").asText());
            }
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode().put("detectorId", detectorId)
                .put("arn", detector.path("arn").asText());
        return page(summaries, in, 2500, "detectorVersionSummaries", out);
    }

    private ObjectNode deleteDetector(JsonNode in, String region) {
        String detectorId = text(in, "detectorId");
        if (detectors.get(detectorArn(region, detectorId)).isEmpty()) {
            return mapper.createObjectNode();
        }
        if (!versionsOf(region, detectorId).isEmpty()) {
            throw conflict("The detector " + detectorId + " has detector versions; delete them first.");
        }
        if (!rulesOf(region, detectorId, null).isEmpty()) {
            throw conflict("The detector " + detectorId + " has rules; delete them first.");
        }
        removed(detectors, detectorArn(region, detectorId));
        return mapper.createObjectNode();
    }

    // ---------- rules ----------

    private String ruleArn(String region, String detectorId, String ruleId, String version) {
        return arn(region, "rule/" + detectorId + "/" + ruleId + "/" + version);
    }

    private List<ObjectNode> rulesOf(String region, String detectorId, String ruleId) {
        String prefix = arn(region, "rule/" + detectorId + "/" + (ruleId == null ? "" : ruleId + "/"));
        return rules.scan(key -> key.startsWith(prefix)).stream()
                .sorted(Comparator.comparing((ObjectNode r) -> r.path("ruleId").asText())
                        .thenComparingInt(r -> r.path("ruleVersion").asInt())).toList();
    }

    /** Parses and checks a rule's expression against the detector's event type, variables, lists and outcomes. */
    private FraudDetectorRules.Expression checkRule(String region, ObjectNode detector, String expression,
                                                    List<String> outcomeNames) {
        FraudDetectorRules.Expression parsed = FraudDetectorRules.parse(expression);
        ObjectNode eventType = eventType(region, detector.path("eventTypeName").asText());
        Set<String> eventVariables = new HashSet<>(strings(eventType.get("eventVariables")));
        for (String name : parsed.variables()) {
            ObjectNode record = variable(region, name).orElseThrow(() ->
                    validation("The rule expression uses the variable " + name + ", which does not exist."));
            if ("EVENT".equals(record.path("dataSource").asText()) && !eventVariables.contains(name)) {
                throw validation("The rule expression uses the variable " + name + ", which isn't part of the"
                        + " event type " + eventType.path("name").asText() + ".");
            }
        }
        for (String name : parsed.lists()) {
            if (lists.get(listArn(region, name)).isEmpty()) {
                throw validation("The rule expression uses the list " + name + ", which does not exist.");
            }
        }
        if (outcomeNames.isEmpty()) {
            throw validation("A rule needs at least one outcome.");
        }
        for (String outcome : outcomeNames) {
            if (outcomes.get(arn(region, "outcome/" + outcome)).isEmpty()) {
                throw validation("The outcome " + outcome + " does not exist.");
            }
        }
        return parsed;
    }

    private ObjectNode storeRule(String region, String detectorId, String ruleId, String version, JsonNode in,
                                 FraudDetectorRules.Expression parsed) {
        ObjectNode record = mapper.createObjectNode().put("ruleId", ruleId).put("detectorId", detectorId)
                .put("ruleVersion", version).put("expression", text(in, "expression"))
                .put("language", text(in, "language"));
        if (in.hasNonNull("description")) {
            record.put("description", text(in, "description"));
        }
        ArrayNode outcomeArray = record.putArray("outcomes");
        strings(in.get("outcomes")).forEach(outcomeArray::add);
        ArrayNode variableArray = record.putArray("_variables");
        parsed.variables().forEach(variableArray::add);
        ArrayNode listArray = record.putArray("_lists");
        parsed.lists().forEach(listArray::add);
        created(rules, ruleArn(region, detectorId, ruleId, version), record, in.get("tags"));
        ObjectNode out = mapper.createObjectNode();
        out.putObject("rule").put("detectorId", detectorId).put("ruleId", ruleId).put("ruleVersion", version);
        return out;
    }

    private ObjectNode createRule(JsonNode in, String region) {
        String detectorId = text(in, "detectorId");
        String ruleId = text(in, "ruleId");
        ObjectNode detector = detectors.get(detectorArn(region, detectorId)).orElseThrow(() ->
                validation("The detector " + detectorId + " does not exist."));
        if (!rulesOf(region, detectorId, ruleId).isEmpty()) {
            throw validation("The rule " + ruleId + " already exists for the detector " + detectorId
                    + "; use UpdateRuleVersion to change it.");
        }
        FraudDetectorRules.Expression parsed = checkRule(region, detector, text(in, "expression"),
                strings(in.get("outcomes")));
        return storeRule(region, detectorId, ruleId, "1", in, parsed);
    }

    private ObjectNode rule(String region, JsonNode ref) {
        String detectorId = text(ref, "detectorId");
        String ruleId = text(ref, "ruleId");
        String version = text(ref, "ruleVersion");
        return rules.get(ruleArn(region, detectorId, ruleId, version)).orElseThrow(() ->
                notFound("The rule " + ruleId + " version " + version + " of the detector " + detectorId
                        + " does not exist."));
    }

    private ObjectNode updateRuleVersion(JsonNode in, String region) {
        ObjectNode current = rule(region, in.path("rule"));
        String detectorId = current.path("detectorId").asText();
        String ruleId = current.path("ruleId").asText();
        FraudDetectorRules.Expression parsed = checkRule(region, detector(region, detectorId),
                text(in, "expression"), strings(in.get("outcomes")));
        int latest = rulesOf(region, detectorId, ruleId).stream().mapToInt(r -> r.path("ruleVersion").asInt())
                .max().orElse(0);
        return storeRule(region, detectorId, ruleId, String.valueOf(latest + 1), in, parsed);
    }

    private ObjectNode updateRuleMetadata(JsonNode in, String region) {
        ObjectNode record = rule(region, in.path("rule"));
        record.put("description", text(in, "description"));
        touch(rules, record);
        return mapper.createObjectNode();
    }

    private ObjectNode getRules(JsonNode in, String region) {
        String detectorId = text(in, "detectorId");
        detector(region, detectorId);
        String ruleId = text(in, "ruleId");
        String version = text(in, "ruleVersion");
        List<ObjectNode> found = rulesOf(region, detectorId, ruleId).stream()
                .filter(r -> version == null || version.equals(r.path("ruleVersion").asText())).toList();
        if ((ruleId != null || version != null) && found.isEmpty()) {
            throw notFound("The rule " + ruleId + (version == null ? "" : " version " + version)
                    + " of the detector " + detectorId + " does not exist.");
        }
        return page(found, in, 100, "ruleDetails", mapper.createObjectNode());
    }

    private ObjectNode deleteRule(JsonNode in, String region) {
        JsonNode ref = in.path("rule");
        String detectorId = text(ref, "detectorId");
        String ruleId = text(ref, "ruleId");
        String version = text(ref, "ruleVersion");
        String arn = ruleArn(region, detectorId, ruleId, version);
        if (rules.get(arn).isEmpty()) {
            return mapper.createObjectNode();
        }
        for (ObjectNode detectorVersion : versionsOf(region, detectorId)) {
            if ("DRAFT".equals(detectorVersion.path("status").asText())) {
                continue;
            }
            for (JsonNode used : detectorVersion.path("rules")) {
                if (ruleId.equals(used.path("ruleId").asText()) && version.equals(used.path("ruleVersion")
                        .asText())) {
                    throw conflict("The rule " + ruleId + " version " + version + " is used by the "
                            + detectorVersion.path("status").asText() + " detector version "
                            + detectorVersion.path("detectorVersionId").asText() + ".");
                }
            }
        }
        removed(rules, arn);
        return mapper.createObjectNode();
    }

    // ---------- detector versions ----------

    private String detectorVersionArn(String region, String detectorId, String versionId) {
        return arn(region, "detector-version/" + detectorId + "/" + versionId);
    }

    private ObjectNode detectorVersion(String region, String detectorId, String versionId) {
        detector(region, detectorId);
        return detectorVersions.get(detectorVersionArn(region, detectorId, versionId)).orElseThrow(() ->
                notFound("The detector version " + versionId + " of the detector " + detectorId
                        + " does not exist."));
    }

    /** Checks the version's rules, model versions and external models, and writes them onto the record. */
    private void setVersionContent(String region, String detectorId, ObjectNode record, JsonNode in) {
        ArrayNode ruleRefs = record.putArray("rules");
        for (JsonNode ref : in.path("rules")) {
            if (!detectorId.equals(text(ref, "detectorId"))) {
                throw validation("The rule " + text(ref, "ruleId") + " belongs to the detector "
                        + text(ref, "detectorId") + ", not " + detectorId + ".");
            }
            if (rules.get(ruleArn(region, detectorId, text(ref, "ruleId"), text(ref, "ruleVersion"))).isEmpty()) {
                throw validation("The rule " + text(ref, "ruleId") + " version " + text(ref, "ruleVersion")
                        + " does not exist.");
            }
            ruleRefs.addObject().put("detectorId", detectorId).put("ruleId", text(ref, "ruleId"))
                    .put("ruleVersion", text(ref, "ruleVersion"));
        }
        ArrayNode modelRefs = record.putArray("modelVersions");
        for (JsonNode ref : in.path("modelVersions")) {
            ObjectNode version = modelVersions.get(modelVersionArn(region, text(ref, "modelType"),
                    text(ref, "modelId"), text(ref, "modelVersionNumber"))).orElseThrow(() ->
                    validation("The model version " + text(ref, "modelId") + " "
                            + text(ref, "modelVersionNumber") + " does not exist."));
            if (!"ACTIVE".equals(version.path("status").asText())) {
                throw validation("The model version " + text(ref, "modelId") + " "
                        + text(ref, "modelVersionNumber") + " is " + version.path("status").asText()
                        + "; detector versions use ACTIVE model versions.");
            }
            modelRefs.addObject().put("modelId", text(ref, "modelId")).put("modelType", text(ref, "modelType"))
                    .put("modelVersionNumber", text(ref, "modelVersionNumber"))
                    .put("arn", version.path("arn").asText());
        }
        ArrayNode endpoints = record.putArray("externalModelEndpoints");
        for (String endpoint : strings(in.get("externalModelEndpoints"))) {
            ObjectNode model = externalModels.get(arn(region, "external-model/" + endpoint)).orElseThrow(() ->
                    validation("The external model " + endpoint + " does not exist."));
            if (!"ASSOCIATED".equals(model.path("modelEndpointStatus").asText())) {
                throw validation("The external model " + endpoint + " is DISSOCIATED.");
            }
            endpoints.add(endpoint);
        }
        record.put("ruleExecutionMode", in.hasNonNull("ruleExecutionMode") ? text(in, "ruleExecutionMode")
                : record.path("ruleExecutionMode").asText("FIRST_MATCHED"));
        if (in.hasNonNull("description")) {
            record.put("description", text(in, "description"));
        }
    }

    private ObjectNode createDetectorVersion(JsonNode in, String region) {
        String detectorId = text(in, "detectorId");
        detector(region, detectorId);
        int next = versionsOf(region, detectorId).stream().mapToInt(v -> v.path("detectorVersionId").asInt())
                .max().orElse(0) + 1;
        String versionId = String.valueOf(next);
        ObjectNode record = mapper.createObjectNode().put("detectorId", detectorId)
                .put("detectorVersionId", versionId).put("status", "DRAFT");
        setVersionContent(region, detectorId, record, in);
        created(detectorVersions, detectorVersionArn(region, detectorId, versionId), record, in.get("tags"));
        return mapper.createObjectNode().put("detectorId", detectorId).put("detectorVersionId", versionId)
                .put("status", "DRAFT");
    }

    private ObjectNode updateDetectorVersion(JsonNode in, String region) {
        String detectorId = text(in, "detectorId");
        ObjectNode record = detectorVersion(region, detectorId, text(in, "detectorVersionId"));
        if (!"DRAFT".equals(record.path("status").asText())) {
            throw conflict("Only DRAFT detector versions can be updated; version "
                    + record.path("detectorVersionId").asText() + " is " + record.path("status").asText() + ".");
        }
        setVersionContent(region, detectorId, record, in);
        touch(detectorVersions, record);
        return mapper.createObjectNode();
    }

    private ObjectNode updateDetectorVersionMetadata(JsonNode in, String region) {
        ObjectNode record = detectorVersions.get(detectorVersionArn(region, text(in, "detectorId"),
                text(in, "detectorVersionId"))).orElseThrow(() -> validation("The detector version "
                + text(in, "detectorVersionId") + " of the detector " + text(in, "detectorId")
                + " does not exist."));
        record.put("description", text(in, "description"));
        touch(detectorVersions, record);
        return mapper.createObjectNode();
    }

    private ObjectNode updateDetectorVersionStatus(JsonNode in, String region) {
        String detectorId = text(in, "detectorId");
        ObjectNode record = detectorVersion(region, detectorId, text(in, "detectorVersionId"));
        String from = record.path("status").asText();
        String to = text(in, "status");
        if (from.equals(to)) {
            return mapper.createObjectNode();
        }
        boolean allowed = ("DRAFT".equals(from) && "ACTIVE".equals(to))
                || ("ACTIVE".equals(from) && "INACTIVE".equals(to))
                || ("INACTIVE".equals(from) && "ACTIVE".equals(to));
        if (!allowed) {
            throw validation("A detector version can't move from " + from + " to " + to + ".");
        }
        if ("ACTIVE".equals(to)) {
            for (ObjectNode other : versionsOf(region, detectorId)) {
                if ("ACTIVE".equals(other.path("status").asText())) {
                    other.put("status", "INACTIVE");
                    touch(detectorVersions, other);
                }
            }
        }
        record.put("status", to);
        touch(detectorVersions, record);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteDetectorVersion(JsonNode in, String region) {
        String detectorId = text(in, "detectorId");
        detector(region, detectorId);
        String arn = detectorVersionArn(region, detectorId, text(in, "detectorVersionId"));
        Optional<ObjectNode> record = detectorVersions.get(arn);
        if (record.isEmpty()) {
            return mapper.createObjectNode();
        }
        if ("ACTIVE".equals(record.get().path("status").asText())) {
            throw conflict("The detector version " + text(in, "detectorVersionId")
                    + " is ACTIVE; deactivate it before deleting it.");
        }
        removed(detectorVersions, arn);
        return mapper.createObjectNode();
    }

    // ---------- models ----------

    private String modelArn(String region, String modelType, String modelId) {
        return arn(region, "model/" + modelType + "/" + modelId);
    }

    private String modelVersionArn(String region, String modelType, String modelId, String version) {
        return arn(region, "model-version/" + modelType + "/" + modelId + "/" + version);
    }

    private ObjectNode model(String region, String modelType, String modelId) {
        return models.get(modelArn(region, modelType, modelId)).orElseThrow(() ->
                notFound("The model " + modelId + " of type " + modelType + " does not exist."));
    }

    private List<ObjectNode> versionsOfModel(String region, String modelType, String modelId) {
        String prefix = arn(region, "model-version/" + modelType + "/" + modelId + "/");
        return modelVersions.scan(key -> key.startsWith(prefix)).stream()
                .sorted(Comparator.comparingDouble(v -> versionOrder(v.path("modelVersionNumber").asText())))
                .toList();
    }

    private static double versionOrder(String version) {
        String[] parts = version.split("\\.");
        return Integer.parseInt(parts[0]) * 1000.0 + (parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
    }

    private ObjectNode createModel(JsonNode in, String region) {
        String modelId = text(in, "modelId");
        String modelType = text(in, "modelType");
        String eventTypeName = text(in, "eventTypeName");
        if (eventTypes.get(eventTypeArn(region, eventTypeName)).isEmpty()) {
            throw validation("The event type " + eventTypeName + " does not exist.");
        }
        String arn = modelArn(region, modelType, modelId);
        if (models.get(arn).isPresent()) {
            throw validation("The model " + modelId + " of type " + modelType + " already exists.");
        }
        ObjectNode record = mapper.createObjectNode().put("modelId", modelId).put("modelType", modelType)
                .put("eventTypeName", eventTypeName);
        if (in.hasNonNull("description")) {
            record.put("description", text(in, "description"));
        }
        created(models, arn, record, in.get("tags"));
        return mapper.createObjectNode();
    }

    private ObjectNode updateModel(JsonNode in, String region) {
        ObjectNode record = model(region, text(in, "modelType"), text(in, "modelId"));
        if (in.hasNonNull("description")) {
            record.put("description", text(in, "description"));
        }
        touch(models, record);
        return mapper.createObjectNode();
    }

    private ObjectNode getModels(JsonNode in, String region) {
        String modelId = text(in, "modelId");
        String modelType = text(in, "modelType");
        List<ObjectNode> found = inRegion(models, region, m ->
                (modelId == null || modelId.equals(m.path("modelId").asText()))
                        && (modelType == null || modelType.equals(m.path("modelType").asText())));
        if (modelId != null && found.isEmpty()) {
            throw notFound("The model " + modelId + " does not exist.");
        }
        return page(found, in, 10, "models", mapper.createObjectNode());
    }

    private ObjectNode deleteModel(JsonNode in, String region) {
        String modelId = text(in, "modelId");
        String modelType = text(in, "modelType");
        if (models.get(modelArn(region, modelType, modelId)).isEmpty()) {
            return mapper.createObjectNode();
        }
        if (!versionsOfModel(region, modelType, modelId).isEmpty()) {
            throw conflict("The model " + modelId + " has model versions; delete them first.");
        }
        removed(models, modelArn(region, modelType, modelId));
        return mapper.createObjectNode();
    }

    private ObjectNode createModelVersion(JsonNode in, String region) {
        String modelId = text(in, "modelId");
        String modelType = text(in, "modelType");
        model(region, modelType, modelId);
        int major = versionsOfModel(region, modelType, modelId).stream()
                .mapToInt(v -> Integer.parseInt(v.path("modelVersionNumber").asText().split("\\.")[0]))
                .max().orElse(0) + 1;
        return startTraining(region, modelType, modelId, major + ".0", in, in);
    }

    private ObjectNode updateModelVersion(JsonNode in, String region) {
        String modelId = text(in, "modelId");
        String modelType = text(in, "modelType");
        String major = text(in, "majorVersionNumber");
        List<ObjectNode> ofMajor = versionsOfModel(region, modelType, modelId).stream()
                .filter(v -> v.path("modelVersionNumber").asText().startsWith(major + ".")).toList();
        if (ofMajor.isEmpty()) {
            throw notFound("The model " + modelId + " has no major version " + major + ".");
        }
        ObjectNode base = ofMajor.getLast();
        int minor = Integer.parseInt(base.path("modelVersionNumber").asText().split("\\.")[1]) + 1;
        ObjectNode request = base.deepCopy();
        for (String field : List.of("externalEventsDetail", "ingestedEventsDetail", "tags")) {
            if (in.has(field)) {
                request.set(field, in.get(field));
            }
        }
        return startTraining(region, modelType, modelId, major + "." + minor, request, in);
    }

    private ObjectNode startTraining(String region, String modelType, String modelId, String version,
                                     JsonNode spec, JsonNode in) {
        ObjectNode model = model(region, modelType, modelId);
        ObjectNode eventType = eventType(region, model.path("eventTypeName").asText());
        String source = text(spec, "trainingDataSource");
        JsonNode schema = spec.path("trainingDataSchema");
        Set<String> eventVariables = new HashSet<>(strings(eventType.get("eventVariables")));
        for (String name : strings(schema.get("modelVariables"))) {
            if (!eventVariables.contains(name)) {
                throw validation("The model variable " + name + " isn't part of the event type "
                        + eventType.path("name").asText() + ".");
            }
        }
        if ("EXTERNAL_EVENTS".equals(source) && !spec.has("externalEventsDetail")) {
            throw validation("EXTERNAL_EVENTS training needs externalEventsDetail.");
        }
        if ("INGESTED_EVENTS".equals(source) && !spec.has("ingestedEventsDetail")) {
            throw validation("INGESTED_EVENTS training needs ingestedEventsDetail.");
        }
        ObjectNode record = mapper.createObjectNode().put("modelId", modelId).put("modelType", modelType)
                .put("modelVersionNumber", version).put("trainingDataSource", source)
                .put("status", "TRAINING_IN_PROGRESS");
        record.set("trainingDataSchema", schema.deepCopy());
        for (String field : List.of("externalEventsDetail", "ingestedEventsDetail")) {
            if (spec.hasNonNull(field)) {
                record.set(field, spec.get(field).deepCopy());
            }
        }
        String arn = modelVersionArn(region, modelType, modelId, version);
        created(modelVersions, arn, record, in.get("tags"));
        String account = tags.accountId();
        timer.schedule(() -> RequestScopes.runAs(account, region, () -> train(region, arn)), JOB_DELAY_MILLIS,
                TimeUnit.MILLISECONDS);
        return mapper.createObjectNode().put("modelId", modelId).put("modelType", modelType)
                .put("modelVersionNumber", version).put("status", "TRAINING_IN_PROGRESS");
    }

    private synchronized void train(String region, String arn) {
        Optional<ObjectNode> found = modelVersions.get(arn);
        if (found.isEmpty() || !"TRAINING_IN_PROGRESS".equals(found.get().path("status").asText())) {
            return;
        }
        ObjectNode record = found.get();
        try {
            ObjectNode model = model(region, record.path("modelType").asText(), record.path("modelId").asText());
            List<ObjectNode> rows = trainingEvents(region, model.path("eventTypeName").asText(), record);
            FraudDetectorModels.Trained trained = FraudDetectorModels.train(rows,
                    strings(record.path("trainingDataSchema").get("modelVariables")),
                    record.path("trainingDataSchema").path("labelSchema"), variableTypes(region));
            record.set("_model", mapper.valueToTree(trained.weights()));
            record.set("trainingResult", trained.result(mapper));
            record.set("trainingResultV2", trained.resultV2(mapper, record.path("modelType").asText()));
            record.put("status", "TRAINING_COMPLETE");
        } catch (AwsException e) {
            record.put("status", "ERROR");
            ObjectNode result = record.putObject("trainingResult");
            result.putObject("dataValidationMetrics").putArray("fileLevelMessages").addObject()
                    .put("title", "Training failed").put("content", e.getMessage()).put("type", "ERROR");
        }
        touch(modelVersions, record);
    }

    private Map<String, String> variableTypes(String region) {
        Map<String, String> out = new LinkedHashMap<>();
        inRegion(variables, region, r -> true).forEach(v -> out.put(v.path("name").asText(),
                v.path("dataType").asText()));
        return out;
    }

    /** The labeled events a model version trains on: ingested events in its window, or its S3 CSV file. */
    private List<ObjectNode> trainingEvents(String region, String eventTypeName, ObjectNode version) {
        if ("INGESTED_EVENTS".equals(version.path("trainingDataSource").asText())) {
            JsonNode window = version.path("ingestedEventsDetail").path("ingestedEventsTimeWindow");
            String start = window.path("startTime").asText("");
            String end = window.path("endTime").asText("9999");
            List<ObjectNode> out = new ArrayList<>();
            for (ObjectNode event : storedEvents(region, eventTypeName)) {
                String at = event.path("eventTimestamp").asText();
                if (at.compareTo(start) >= 0 && at.compareTo(end) <= 0) {
                    ObjectNode row = mapper.createObjectNode();
                    event.path("eventVariables").fields().forEachRemaining(f -> row.put(f.getKey(),
                            f.getValue().asText()));
                    if (event.hasNonNull("currentLabel")) {
                        row.put("EVENT_LABEL", event.get("currentLabel").asText());
                    }
                    out.add(row);
                }
            }
            return out;
        }
        String location = version.path("externalEventsDetail").path("dataLocation").asText();
        List<ObjectNode> out = new ArrayList<>();
        for (Map<String, String> row : FraudDetectorCsv.parse(readS3(location))) {
            ObjectNode node = mapper.createObjectNode();
            row.forEach(node::put);
            out.add(node);
        }
        return out;
    }

    private ObjectNode modelVersion(String region, JsonNode in) {
        return modelVersions.get(modelVersionArn(region, text(in, "modelType"), text(in, "modelId"),
                text(in, "modelVersionNumber"))).orElseThrow(() -> notFound("The model version "
                + text(in, "modelId") + " " + text(in, "modelVersionNumber") + " does not exist."));
    }

    private ObjectNode updateModelVersionStatus(JsonNode in, String region) {
        ObjectNode record = modelVersion(region, in);
        String from = record.path("status").asText();
        String to = text(in, "status");
        String modelId = record.path("modelId").asText();
        String modelType = record.path("modelType").asText();
        switch (to) {
            case "ACTIVE" -> {
                if (!"TRAINING_COMPLETE".equals(from) && !"INACTIVE".equals(from)) {
                    throw validation("A model version that is " + from + " can't be activated.");
                }
                for (ObjectNode other : versionsOfModel(region, modelType, modelId)) {
                    if ("ACTIVE".equals(other.path("status").asText())) {
                        other.put("status", "INACTIVE");
                        touch(modelVersions, other);
                    }
                }
                ensureScoreVariable(region, modelId);
            }
            case "INACTIVE" -> {
                if (!"ACTIVE".equals(from)) {
                    throw validation("Only an ACTIVE model version can be deactivated; this one is " + from + ".");
                }
                checkUnused(region, record);
            }
            default -> {
                if (!"TRAINING_IN_PROGRESS".equals(from)) {
                    throw validation("Only training in progress can be cancelled; this version is " + from + ".");
                }
            }
        }
        record.put("status", to);
        touch(modelVersions, record);
        return mapper.createObjectNode();
    }

    /** Activating a model version creates its score variable, which rules read as {@code $<model>_insightscore}. */
    private void ensureScoreVariable(String region, String modelId) {
        String name = modelId + "_insightscore";
        if (variable(region, name).isEmpty()) {
            ObjectNode record = mapper.createObjectNode().put("name", name).put("dataType", "FLOAT")
                    .put("dataSource", "MODEL_SCORE").put("defaultValue", "0.0")
                    .put("variableType", "NUMERIC");
            created(variables, variableArn(region, name), record, null);
        }
    }

    private void checkUnused(String region, ObjectNode modelVersion) {
        for (ObjectNode detectorVersion : inRegion(detectorVersions, region, r -> true)) {
            if ("DRAFT".equals(detectorVersion.path("status").asText())) {
                continue;
            }
            for (JsonNode ref : detectorVersion.path("modelVersions")) {
                if (modelVersion.path("arn").asText().equals(ref.path("arn").asText())) {
                    throw conflict("The model version is used by the " + detectorVersion.path("status").asText()
                            + " detector version " + detectorVersion.path("detectorVersionId").asText()
                            + " of the detector " + detectorVersion.path("detectorId").asText() + ".");
                }
            }
        }
    }

    private ObjectNode getModelVersion(JsonNode in, String region) {
        ObjectNode out = view(modelVersion(region, in));
        out.remove(List.of("trainingResult", "trainingResultV2", "createdTime", "lastUpdatedTime"));
        return out;
    }

    private ObjectNode describeModelVersions(JsonNode in, String region) {
        String modelId = text(in, "modelId");
        String modelType = text(in, "modelType");
        String number = text(in, "modelVersionNumber");
        List<ObjectNode> found = inRegion(modelVersions, region, v ->
                (modelId == null || modelId.equals(v.path("modelId").asText()))
                        && (modelType == null || modelType.equals(v.path("modelType").asText()))
                        && (number == null || number.equals(v.path("modelVersionNumber").asText())));
        if (modelId != null && found.isEmpty()) {
            throw notFound("The model " + modelId + " has no matching model versions.");
        }
        return page(found, in, 10, "modelVersionDetails", mapper.createObjectNode());
    }

    private ObjectNode deleteModelVersion(JsonNode in, String region) {
        Optional<ObjectNode> record = modelVersions.get(modelVersionArn(region, text(in, "modelType"),
                text(in, "modelId"), text(in, "modelVersionNumber")));
        if (record.isEmpty()) {
            return mapper.createObjectNode();
        }
        if ("ACTIVE".equals(record.get().path("status").asText())) {
            throw conflict("The model version is ACTIVE; deactivate it before deleting it.");
        }
        checkUnused(region, record.get());
        removed(modelVersions, record.get().path("arn").asText());
        return mapper.createObjectNode();
    }

    // ---------- external models ----------

    private ObjectNode putExternalModel(JsonNode in, String region) {
        String endpoint = text(in, "modelEndpoint");
        String arn = arn(region, "external-model/" + endpoint);
        Optional<ObjectNode> existing = externalModels.get(arn);
        ObjectNode record = existing.orElseGet(() -> mapper.createObjectNode().put("modelEndpoint", endpoint));
        record.put("modelSource", text(in, "modelSource"));
        record.put("invokeModelEndpointRoleArn", text(in, "invokeModelEndpointRoleArn"));
        record.set("inputConfiguration", in.path("inputConfiguration").deepCopy());
        record.set("outputConfiguration", in.path("outputConfiguration").deepCopy());
        record.put("modelEndpointStatus", text(in, "modelEndpointStatus"));
        if (existing.isPresent()) {
            touch(externalModels, record);
        } else {
            created(externalModels, arn, record, in.get("tags"));
        }
        return mapper.createObjectNode();
    }

    private ObjectNode getExternalModels(JsonNode in, String region) {
        String endpoint = text(in, "modelEndpoint");
        ObjectNode out = mapper.createObjectNode();
        if (endpoint != null) {
            ObjectNode record = externalModels.get(arn(region, "external-model/" + endpoint)).orElseThrow(() ->
                    notFound("The external model " + endpoint + " does not exist."));
            out.putArray("externalModels").add(view(record));
            return out;
        }
        return page(inRegion(externalModels, region, r -> true), in, 10, "externalModels", out);
    }

    private ObjectNode deleteExternalModel(JsonNode in, String region) {
        String endpoint = text(in, "modelEndpoint");
        for (ObjectNode version : inRegion(detectorVersions, region, r -> true)) {
            if (!"DRAFT".equals(version.path("status").asText())
                    && strings(version.get("externalModelEndpoints")).contains(endpoint)) {
                throw conflict("The external model " + endpoint + " is used by the detector version "
                        + version.path("detectorVersionId").asText() + " of the detector "
                        + version.path("detectorId").asText() + ".");
            }
        }
        removed(externalModels, arn(region, "external-model/" + endpoint));
        return mapper.createObjectNode();
    }

    // ---------- stored events ----------

    private String eventKey(String region, String eventTypeName, String eventId) {
        return arn(region, "event/" + eventTypeName + "/" + eventId);
    }

    private List<ObjectNode> storedEvents(String region, String eventTypeName) {
        String prefix = arn(region, "event/" + eventTypeName + "/");
        return events.scan(key -> key.startsWith(prefix));
    }

    /** Checks the event's variables, entities and label against the event type. */
    private void checkEvent(ObjectNode eventType, JsonNode in) {
        Set<String> known = new HashSet<>(strings(eventType.get("eventVariables")));
        Iterator<String> names = in.path("eventVariables").fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!known.contains(name)) {
                throw validation("The variable " + name + " isn't part of the event type "
                        + eventType.path("name").asText() + ".");
            }
        }
        Set<String> entityTypeNames = new HashSet<>(strings(eventType.get("entityTypes")));
        for (JsonNode entity : in.path("entities")) {
            if (!entityTypeNames.contains(entity.path("entityType").asText())) {
                throw validation("The entity type " + entity.path("entityType").asText() + " isn't part of the"
                        + " event type " + eventType.path("name").asText() + ".");
            }
        }
        String label = text(in, "assignedLabel");
        if (label != null && !strings(eventType.get("labels")).contains(label)) {
            throw validation("The label " + label + " isn't part of the event type "
                    + eventType.path("name").asText() + ".");
        }
        try {
            Instant.parse(text(in, "eventTimestamp"));
        } catch (DateTimeParseException | NullPointerException e) {
            throw validation("The event timestamp " + text(in, "eventTimestamp") + " isn't an ISO 8601 time.");
        }
    }

    private void storeEvent(String region, String eventTypeName, JsonNode in) {
        ObjectNode event = mapper.createObjectNode().put("eventId", text(in, "eventId"))
                .put("eventTypeName", eventTypeName).put("eventTimestamp", text(in, "eventTimestamp"));
        event.set("eventVariables", in.path("eventVariables").deepCopy());
        event.set("entities", in.path("entities").deepCopy());
        if (in.hasNonNull("assignedLabel")) {
            event.put("currentLabel", text(in, "assignedLabel"));
            event.put("labelTimestamp", in.hasNonNull("labelTimestamp") ? text(in, "labelTimestamp") : now());
        }
        String key = eventKey(region, eventTypeName, text(in, "eventId"));
        event.put("_key", key);
        events.put(key, event);
    }

    private ObjectNode sendEvent(JsonNode in, String region) {
        String eventTypeName = text(in, "eventTypeName");
        ObjectNode eventType = eventType(region, eventTypeName);
        if (!"ENABLED".equals(eventType.path("eventIngestion").asText())) {
            throw validation("Event ingestion is DISABLED for the event type " + eventTypeName + ".");
        }
        checkEvent(eventType, in);
        if (events.get(eventKey(region, eventTypeName, text(in, "eventId"))).isPresent()) {
            throw conflict("The event " + text(in, "eventId") + " already exists.");
        }
        storeEvent(region, eventTypeName, in);
        return mapper.createObjectNode();
    }

    private ObjectNode storedEvent(String region, String eventTypeName, String eventId) {
        eventType(region, eventTypeName);
        return events.get(eventKey(region, eventTypeName, eventId)).orElseThrow(() ->
                notFound("The event " + eventId + " of the event type " + eventTypeName + " does not exist."));
    }

    private ObjectNode getEvent(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("event", view(storedEvent(region, text(in, "eventTypeName"), text(in, "eventId"))));
        return out;
    }

    private ObjectNode updateEventLabel(JsonNode in, String region) {
        String eventTypeName = text(in, "eventTypeName");
        ObjectNode event = storedEvent(region, eventTypeName, text(in, "eventId"));
        String label = text(in, "assignedLabel");
        if (!strings(eventType(region, eventTypeName).get("labels")).contains(label)) {
            throw validation("The label " + label + " isn't part of the event type " + eventTypeName + ".");
        }
        event.put("currentLabel", label);
        event.put("labelTimestamp", text(in, "labelTimestamp"));
        events.put(event.path("_key").asText(), event);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteEvent(JsonNode in, String region) {
        String eventTypeName = text(in, "eventTypeName");
        String eventId = text(in, "eventId");
        events.delete(eventKey(region, eventTypeName, eventId));
        if (in.path("deleteAuditHistory").asBoolean(false)) {
            String prefix = arn(region, "prediction/");
            for (ObjectNode prediction : predictions.scan(key -> key.startsWith(prefix))) {
                if (eventId.equals(prediction.path("eventId").asText())
                        && eventTypeName.equals(prediction.path("eventTypeName").asText())) {
                    predictions.delete(prediction.path("_key").asText());
                }
            }
        }
        return mapper.createObjectNode();
    }

    private ObjectNode deleteEventsByEventType(JsonNode in, String region) {
        String eventTypeName = text(in, "eventTypeName");
        eventType(region, eventTypeName);
        String key = arn(region, "events-deletion/" + eventTypeName);
        Optional<ObjectNode> current = settings.get(key);
        if (current.isPresent() && current.get().path("eventsDeletionStatus").asText().startsWith("IN_PROGRESS")) {
            throw conflict("Events of the event type " + eventTypeName + " are already being deleted.");
        }
        settings.put(key, mapper.createObjectNode().put("eventTypeName", eventTypeName)
                .put("eventsDeletionStatus", "IN_PROGRESS_INITIALIZING"));
        String account = tags.accountId();
        timer.schedule(() -> RequestScopes.runAs(account, region, () -> purgeEvents(region, eventTypeName, key)),
                JOB_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        return mapper.createObjectNode().put("eventTypeName", eventTypeName)
                .put("eventsDeletionStatus", "IN_PROGRESS_INITIALIZING");
    }

    private synchronized void purgeEvents(String region, String eventTypeName, String key) {
        for (ObjectNode event : storedEvents(region, eventTypeName)) {
            events.delete(event.path("_key").asText());
        }
        settings.put(key, mapper.createObjectNode().put("eventTypeName", eventTypeName)
                .put("eventsDeletionStatus", "COMPLETE"));
    }

    private ObjectNode getDeleteEventsByEventTypeStatus(JsonNode in, String region) {
        String eventTypeName = text(in, "eventTypeName");
        eventType(region, eventTypeName);
        ObjectNode status = settings.get(arn(region, "events-deletion/" + eventTypeName)).orElseThrow(() ->
                notFound("No events of the event type " + eventTypeName + " have been deleted."));
        return status.deepCopy();
    }

    // ---------- predictions ----------

    /** What a prediction evaluated: the variables it read, model and external model outputs, and rules. */
    private record Evaluation(ObjectNode result, ObjectNode metadata) {}

    private ObjectNode activeVersion(String region, String detectorId) {
        return versionsOf(region, detectorId).stream().filter(v -> "ACTIVE".equals(v.path("status").asText()))
                .findFirst().orElseThrow(() -> notFound("The detector " + detectorId
                        + " has no ACTIVE detector version."));
    }

    private Evaluation evaluate(String region, ObjectNode detector, ObjectNode version, JsonNode in,
                                String predictionTimestamp) {
        String eventTypeName = text(in, "eventTypeName");
        if (!detector.path("eventTypeName").asText().equals(eventTypeName)) {
            throw validation("The detector " + detector.path("detectorId").asText() + " evaluates "
                    + detector.path("eventTypeName").asText() + " events, not " + eventTypeName + ".");
        }
        ObjectNode eventType = eventType(region, eventTypeName);
        checkEvent(eventType, in);
        Map<String, Object> values = new LinkedHashMap<>();
        ArrayNode summaries = mapper.createArrayNode();
        Map<String, String> raw = new LinkedHashMap<>();
        for (String name : strings(eventType.get("eventVariables"))) {
            ObjectNode variable = variable(region, name).orElseThrow(() ->
                    validation("The variable " + name + " does not exist."));
            String dataType = variable.path("dataType").asText();
            String given = text(in.path("eventVariables"), name);
            Object value = typed(dataType, given);
            String source = "EVENT";
            if (value == null) {
                given = variable.path("defaultValue").asText();
                value = typed(dataType, given);
                source = "DEFAULT";
            }
            values.put(name, value);
            raw.put(name, given);
            summaries.addObject().put("name", name).put("value", given).put("source", source);
        }
        ObjectNode result = mapper.createObjectNode();
        ArrayNode modelScores = result.putArray("modelScores");
        ArrayNode evaluatedModels = mapper.createArrayNode();
        for (JsonNode ref : version.path("modelVersions")) {
            ObjectNode modelVersion = modelVersions.get(ref.path("arn").asText()).orElseThrow(() ->
                    new AwsException("ResourceUnavailableException", "The model version "
                            + ref.path("modelId").asText() + " " + ref.path("modelVersionNumber").asText()
                            + " is no longer available.", 400));
            FraudDetectorModels.Weights weights = mapper.convertValue(modelVersion.get("_model"),
                    FraudDetectorModels.Weights.class);
            FraudDetectorModels.Score score = FraudDetectorModels.score(weights, raw);
            String scoreName = ref.path("modelId").asText() + "_insightscore";
            values.put(scoreName, score.value());
            ObjectNode entry = modelScores.addObject();
            entry.set("modelVersion", ref.deepCopy());
            entry.putObject("scores").put(scoreName, score.value());
            ObjectNode evaluated = evaluatedModels.addObject().put("modelId", ref.path("modelId").asText())
                    .put("modelVersion", ref.path("modelVersionNumber").asText())
                    .put("modelType", ref.path("modelType").asText());
            ObjectNode evaluation = evaluated.putArray("evaluations").addObject()
                    .put("outputVariableName", scoreName).put("evaluationScore", String.valueOf(score.value()));
            evaluation.set("predictionExplanations", score.explanations(mapper));
        }
        ArrayNode externalOutputs = result.putArray("externalModelOutputs");
        ArrayNode evaluatedExternal = mapper.createArrayNode();
        for (String endpoint : strings(version.get("externalModelEndpoints"))) {
            ObjectNode model = externalModels.get(arn(region, "external-model/" + endpoint)).orElseThrow(() ->
                    new AwsException("ResourceUnavailableException", "The external model " + endpoint
                            + " is no longer available.", 400));
            Map<String, String> outputs = invokeExternal(region, model, raw);
            ObjectNode entry = externalOutputs.addObject();
            entry.putObject("externalModel").put("modelEndpoint", endpoint).put("modelSource", "SAGEMAKER");
            ObjectNode outputNode = entry.putObject("outputs");
            ObjectNode evaluated = evaluatedExternal.addObject().put("modelEndpoint", endpoint)
                    .put("useEventVariables", model.path("inputConfiguration").path("useEventVariables")
                            .asBoolean());
            ObjectNode inputs = evaluated.putObject("inputVariables");
            raw.forEach(inputs::put);
            ObjectNode outputVariables = evaluated.putObject("outputVariables");
            outputs.forEach((name, value) -> {
                outputNode.put(name, value);
                outputVariables.put(name, value);
                Optional<ObjectNode> variable = variable(region, name);
                values.put(name, typed(variable.map(v -> v.path("dataType").asText()).orElse("FLOAT"), value));
            });
        }
        FraudDetectorRules.Context context = new FraudDetectorRules.Context() {
            @Override
            public Object variable(String name) {
                if (values.containsKey(name)) {
                    return values.get(name);
                }
                return FraudDetectorService.this.variable(region, name).map(v ->
                        typed(v.path("dataType").asText(), v.path("defaultValue").asText())).orElse(null);
            }

            @Override
            public Set<String> list(String name) {
                return listElements(region, name);
            }
        };
        boolean firstMatched = !"ALL_MATCHED".equals(version.path("ruleExecutionMode").asText());
        ArrayNode ruleResults = result.putArray("ruleResults");
        ArrayNode evaluatedRules = mapper.createArrayNode();
        LinkedHashSet<String> allOutcomes = new LinkedHashSet<>();
        boolean stopped = false;
        for (JsonNode ref : version.path("rules")) {
            ObjectNode rule = rules.get(ruleArn(region, ref.path("detectorId").asText(), ref.path("ruleId")
                    .asText(), ref.path("ruleVersion").asText())).orElseThrow(() ->
                    new AwsException("ResourceUnavailableException", "The rule " + ref.path("ruleId").asText()
                            + " is no longer available.", 400));
            String expression = rule.path("expression").asText();
            ObjectNode evaluated = evaluatedRules.addObject().put("ruleId", rule.path("ruleId").asText())
                    .put("ruleVersion", rule.path("ruleVersion").asText()).put("expression", expression);
            evaluated.set("outcomes", rule.path("outcomes").deepCopy());
            if (stopped) {
                evaluated.put("evaluated", false).put("matched", false);
                continue;
            }
            FraudDetectorRules.Expression parsed = FraudDetectorRules.parse(expression);
            boolean matched = parsed.matches(context);
            evaluated.put("expressionWithValues", parsed.withValues(expression, context))
                    .put("evaluated", true).put("matched", matched);
            if (matched) {
                ObjectNode ruleResult = ruleResults.addObject().put("ruleId", rule.path("ruleId").asText());
                ruleResult.set("outcomes", rule.path("outcomes").deepCopy());
                allOutcomes.addAll(strings(rule.get("outcomes")));
                stopped = firstMatched;
            }
        }
        JsonNode entity = in.path("entities").path(0);
        ObjectNode metadata = mapper.createObjectNode().put("eventId", text(in, "eventId"))
                .put("eventTypeName", eventTypeName).put("entityId", entity.path("entityId").asText(null))
                .put("entityType", entity.path("entityType").asText(null))
                .put("eventTimestamp", text(in, "eventTimestamp"))
                .put("detectorId", detector.path("detectorId").asText())
                .put("detectorVersionId", version.path("detectorVersionId").asText())
                .put("detectorVersionStatus", version.path("status").asText())
                .put("ruleExecutionMode", version.path("ruleExecutionMode").asText())
                .put("predictionTimestamp", predictionTimestamp);
        metadata.set("eventVariables", summaries);
        metadata.set("rules", evaluatedRules);
        ArrayNode outcomeArray = metadata.putArray("outcomes");
        allOutcomes.forEach(outcomeArray::add);
        metadata.set("evaluatedModelVersions", evaluatedModels);
        metadata.set("evaluatedExternalModels", evaluatedExternal);
        return new Evaluation(result, metadata);
    }

    /** Invokes a SageMaker endpoint with the event's variables and maps its response onto output variables. */
    private Map<String, String> invokeExternal(String region, ObjectNode model, Map<String, String> raw) {
        String endpoint = model.path("modelEndpoint").asText();
        SageMakerEntities.EndpointResource target = sageMaker.get().endpoint(region, endpoint).orElseThrow(() ->
                new AwsException("ResourceUnavailableException", "The SageMaker endpoint " + endpoint
                        + " does not exist.", 400));
        if (!"InService".equals(target.endpointStatus) || target.invokeHost == null) {
            throw new AwsException("ResourceUnavailableException", "The SageMaker endpoint " + endpoint
                    + " is not InService.", 400);
        }
        JsonNode input = model.path("inputConfiguration");
        boolean json = "APPLICATION_JSON".equals(input.path("format").asText());
        String body;
        if (input.path("useEventVariables").asBoolean()) {
            body = json ? mapper.valueToTree(raw).toString() : String.join(",", raw.values());
        } else {
            String template = json ? input.path("jsonInputTemplate").asText() : input.path("csvInputTemplate")
                    .asText();
            body = template;
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                body = body.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }
        JsonNode output = model.path("outputConfiguration");
        boolean jsonOut = "APPLICATION_JSONLINES".equals(output.path("format").asText());
        String response;
        try {
            HttpResponse<String> reply = http.send(HttpRequest.newBuilder(URI.create("http://" + target.invokeHost
                            + ":" + target.invokePort + "/invocations")).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", json ? "application/json" : "text/csv")
                    .header("Accept", jsonOut ? "application/jsonlines" : "text/csv")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode() >= 400) {
                throw new AwsException("ResourceUnavailableException", "The SageMaker endpoint " + endpoint
                        + " returned " + reply.statusCode() + ": " + reply.body(), 400);
            }
            response = reply.body().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AwsException("ResourceUnavailableException", "The SageMaker endpoint " + endpoint
                    + " was interrupted.", 400);
        } catch (IOException e) {
            throw new AwsException("ResourceUnavailableException", "The SageMaker endpoint " + endpoint
                    + " can't be reached: " + e.getMessage(), 400);
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (jsonOut) {
            JsonNode parsed;
            try {
                parsed = mapper.readTree(response.lines().findFirst().orElse("{}"));
            } catch (IOException e) {
                throw new AwsException("ResourceUnavailableException", "The SageMaker endpoint " + endpoint
                        + " returned invalid JSON.", 400);
            }
            output.path("jsonKeyToVariableMap").fields().forEachRemaining(entry -> {
                JsonNode value = parsed.path(entry.getKey());
                if (!value.isMissingNode()) {
                    out.put(entry.getValue().asText(), value.asText());
                }
            });
        } else {
            String[] cells = response.lines().findFirst().orElse("").split(",");
            output.path("csvIndexToVariableMap").fields().forEachRemaining(entry -> {
                int index = Integer.parseInt(entry.getKey());
                if (index < cells.length) {
                    out.put(entry.getValue().asText(), cells[index].trim());
                }
            });
        }
        return out;
    }

    private ObjectNode getEventPrediction(JsonNode in, String region) {
        String detectorId = text(in, "detectorId");
        ObjectNode detector = detector(region, detectorId);
        ObjectNode version = in.hasNonNull("detectorVersionId")
                ? detectorVersion(region, detectorId, text(in, "detectorVersionId"))
                : activeVersion(region, detectorId);
        String predictionTimestamp = now();
        Evaluation evaluation = evaluate(region, detector, version, in, predictionTimestamp);
        String key = arn(region, "prediction/" + detectorId + "/" + text(in, "eventId") + "/"
                + predictionTimestamp);
        ObjectNode stored = evaluation.metadata().deepCopy();
        stored.put("_key", key);
        predictions.put(key, stored);
        ObjectNode eventType = eventType(region, text(in, "eventTypeName"));
        if ("ENABLED".equals(eventType.path("eventIngestion").asText())) {
            storeEvent(region, eventType.path("name").asText(), in);
        }
        if (eventType.path("eventOrchestration").path("eventBridgeEnabled").asBoolean(false)) {
            publish(region, detector, version, in, evaluation.result());
        }
        return evaluation.result();
    }

    private void publish(String region, ObjectNode detector, ObjectNode version, JsonNode in, ObjectNode result) {
        ObjectNode detail = mapper.createObjectNode().put("eventId", text(in, "eventId"))
                .put("eventTypeName", text(in, "eventTypeName"))
                .put("detectorId", detector.path("detectorId").asText())
                .put("detectorVersionId", version.path("detectorVersionId").asText())
                .put("eventTimestamp", text(in, "eventTimestamp"));
        detail.set("entities", in.path("entities").deepCopy());
        detail.set("eventVariables", in.path("eventVariables").deepCopy());
        detail.set("ruleResults", result.path("ruleResults").deepCopy());
        detail.set("modelScores", result.path("modelScores").deepCopy());
        try {
            eventBridge.get().publishServiceEvent("aws.frauddetector", "Event Prediction Result Returned",
                    detail.toString(), detector.path("arn").asText(), region);
        } catch (RuntimeException e) {
            LOG.warnv("Could not publish the Fraud Detector prediction to EventBridge: {0}", e.getMessage());
        }
    }

    private ObjectNode listEventPredictions(JsonNode in, String region) {
        String eventId = text(in.path("eventId"), "value");
        String eventTypeName = text(in.path("eventType"), "value");
        String detectorId = text(in.path("detectorId"), "value");
        String versionId = text(in.path("detectorVersionId"), "value");
        String start = text(in.path("predictionTimeRange"), "startTime");
        String end = text(in.path("predictionTimeRange"), "endTime");
        String prefix = arn(region, "prediction/");
        List<ObjectNode> found = predictions.scan(key -> key.startsWith(prefix)).stream().filter(p ->
                        (eventId == null || eventId.equals(p.path("eventId").asText()))
                                && (eventTypeName == null || eventTypeName.equals(p.path("eventTypeName").asText()))
                                && (detectorId == null || detectorId.equals(p.path("detectorId").asText()))
                                && (versionId == null || versionId.equals(p.path("detectorVersionId").asText()))
                                && (start == null || p.path("predictionTimestamp").asText().compareTo(start) >= 0)
                                && (end == null || p.path("predictionTimestamp").asText().compareTo(end) <= 0))
                .sorted(Comparator.comparing((ObjectNode p) -> p.path("predictionTimestamp").asText()).reversed())
                .map(p -> {
                    ObjectNode summary = mapper.createObjectNode();
                    for (String field : List.of("eventId", "eventTypeName", "eventTimestamp", "predictionTimestamp",
                            "detectorId", "detectorVersionId")) {
                        summary.put(field, p.path(field).asText());
                    }
                    return summary;
                }).toList();
        return page(found, in, 100, "eventPredictionSummaries", mapper.createObjectNode());
    }

    private ObjectNode getEventPredictionMetadata(JsonNode in, String region) {
        String key = arn(region, "prediction/" + text(in, "detectorId") + "/" + text(in, "eventId") + "/"
                + text(in, "predictionTimestamp"));
        ObjectNode prediction = predictions.get(key).orElseThrow(() -> notFound("No prediction for the event "
                + text(in, "eventId") + " was made at " + text(in, "predictionTimestamp") + "."));
        if (!Objects.equals(text(in, "detectorVersionId"), prediction.path("detectorVersionId").asText())
                || !Objects.equals(text(in, "eventTypeName"), prediction.path("eventTypeName").asText())) {
            throw notFound("No prediction for the event " + text(in, "eventId") + " matches the detector version "
                    + text(in, "detectorVersionId") + ".");
        }
        return view(prediction);
    }

    // ---------- batch jobs ----------

    private String readS3(String location) {
        if (!location.startsWith("s3://")) {
            throw validation("The location " + location + " isn't an S3 URI.");
        }
        String path = location.substring("s3://".length());
        int slash = path.indexOf('/');
        if (slash < 0) {
            throw validation("The location " + location + " has no object key.");
        }
        try {
            return new String(s3.get().getObject(path.substring(0, slash), path.substring(slash + 1)).getData(),
                    StandardCharsets.UTF_8);
        } catch (AwsException e) {
            throw validation("Can't read " + location + ": " + e.getMessage());
        }
    }

    private void writeS3(String location, String content) {
        String path = location.substring("s3://".length());
        int slash = path.indexOf('/');
        String bucket = slash < 0 ? path : path.substring(0, slash);
        String key = slash < 0 ? "" : path.substring(slash + 1);
        s3.get().putObject(bucket, key, content.getBytes(StandardCharsets.UTF_8), "text/csv", Map.of());
    }

    /** The job fails when its role doesn't exist, as Fraud Detector can't assume it. */
    private String roleProblem(String roleArn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(roleArn);
        String roleName = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        return iam.get().findRole(parsed.accountId(), roleName).isPresent() ? null
                : "Amazon Fraud Detector can't assume the role " + roleArn + ".";
    }

    private ObjectNode createBatchImportJob(JsonNode in, String region) {
        String jobId = text(in, "jobId");
        String eventTypeName = text(in, "eventTypeName");
        ObjectNode eventType = eventType(region, eventTypeName);
        if (!"ENABLED".equals(eventType.path("eventIngestion").asText())) {
            throw validation("Event ingestion is DISABLED for the event type " + eventTypeName + ".");
        }
        String arn = arn(region, "batch-import/" + jobId);
        if (batchImports.get(arn).isPresent()) {
            throw validation("The batch import job " + jobId + " already exists.");
        }
        ObjectNode record = mapper.createObjectNode().put("jobId", jobId).put("status", "IN_PROGRESS_INITIALIZING")
                .put("startTime", now()).put("inputPath", text(in, "inputPath"))
                .put("outputPath", text(in, "outputPath")).put("eventTypeName", eventTypeName)
                .put("iamRoleArn", text(in, "iamRoleArn"));
        created(batchImports, arn, record, in.get("tags"));
        record.remove(List.of("createdTime", "lastUpdatedTime"));
        batchImports.put(arn, record);
        String account = tags.accountId();
        timer.schedule(() -> RequestScopes.runAs(account, region, () -> runImport(region, arn)), JOB_DELAY_MILLIS,
                TimeUnit.MILLISECONDS);
        return mapper.createObjectNode();
    }

    private synchronized void runImport(String region, String arn) {
        Optional<ObjectNode> found = batchImports.get(arn);
        if (found.isEmpty() || !"IN_PROGRESS_INITIALIZING".equals(found.get().path("status").asText())) {
            return;
        }
        ObjectNode job = found.get();
        String problem = roleProblem(job.path("iamRoleArn").asText());
        if (problem != null) {
            finish(batchImports, job, "FAILED", problem);
            return;
        }
        try {
            String eventTypeName = job.path("eventTypeName").asText();
            ObjectNode eventType = eventType(region, eventTypeName);
            List<Map<String, String>> rows = FraudDetectorCsv.parse(readS3(job.path("inputPath").asText()));
            StringBuilder failures = new StringBuilder("EVENT_ID,ERROR\n");
            int failed = 0;
            for (Map<String, String> row : rows) {
                ObjectNode request = FraudDetectorCsv.eventRequest(mapper, row, eventType);
                try {
                    checkEvent(eventType, request);
                    storeEvent(region, eventTypeName, request);
                } catch (AwsException e) {
                    failed++;
                    failures.append(FraudDetectorCsv.cell(row.get("EVENT_ID"))).append(',')
                            .append(FraudDetectorCsv.cell(e.getMessage())).append('\n');
                }
            }
            job.put("totalRecordsCount", rows.size()).put("processedRecordsCount", rows.size() - failed)
                    .put("failedRecordsCount", failed);
            writeS3(job.path("outputPath").asText().replaceAll("/?$", "/") + job.path("jobId").asText()
                    + "_failed_records.csv", failures.toString());
            finish(batchImports, job, "COMPLETE", null);
        } catch (AwsException e) {
            finish(batchImports, job, "FAILED", e.getMessage());
        }
    }

    private void finish(AccountAwareStorageBackend<ObjectNode> store, ObjectNode job, String status,
                        String reason) {
        job.put("status", status).put("completionTime", now());
        if (reason != null) {
            job.put("failureReason", reason);
        }
        store.put(job.path("arn").asText(), job);
    }

    private ObjectNode createBatchPredictionJob(JsonNode in, String region) {
        String jobId = text(in, "jobId");
        String detectorId = text(in, "detectorName");
        ObjectNode detector = detector(region, detectorId);
        String eventTypeName = text(in, "eventTypeName");
        eventType(region, eventTypeName);
        if (!detector.path("eventTypeName").asText().equals(eventTypeName)) {
            throw validation("The detector " + detectorId + " evaluates " + detector.path("eventTypeName").asText()
                    + " events, not " + eventTypeName + ".");
        }
        ObjectNode version = in.hasNonNull("detectorVersion")
                ? detectorVersion(region, detectorId, text(in, "detectorVersion"))
                : activeVersion(region, detectorId);
        String arn = arn(region, "batch-prediction/" + jobId);
        if (batchPredictions.get(arn).isPresent()) {
            throw validation("The batch prediction job " + jobId + " already exists.");
        }
        ObjectNode record = mapper.createObjectNode().put("jobId", jobId).put("status", "IN_PROGRESS_INITIALIZING")
                .put("startTime", now()).put("inputPath", text(in, "inputPath"))
                .put("outputPath", text(in, "outputPath")).put("eventTypeName", eventTypeName)
                .put("detectorName", detectorId).put("detectorVersion", version.path("detectorVersionId").asText()
                        + ".0")
                .put("iamRoleArn", text(in, "iamRoleArn"));
        created(batchPredictions, arn, record, in.get("tags"));
        record.remove(List.of("createdTime", "lastUpdatedTime"));
        batchPredictions.put(arn, record);
        String account = tags.accountId();
        timer.schedule(() -> RequestScopes.runAs(account, region, () -> runPrediction(region, arn,
                version.path("detectorVersionId").asText())), JOB_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        return mapper.createObjectNode();
    }

    private synchronized void runPrediction(String region, String arn, String versionId) {
        Optional<ObjectNode> found = batchPredictions.get(arn);
        if (found.isEmpty() || !"IN_PROGRESS_INITIALIZING".equals(found.get().path("status").asText())) {
            return;
        }
        ObjectNode job = found.get();
        String problem = roleProblem(job.path("iamRoleArn").asText());
        if (problem != null) {
            finish(batchPredictions, job, "FAILED", problem);
            return;
        }
        try {
            String detectorId = job.path("detectorName").asText();
            ObjectNode detector = detector(region, detectorId);
            ObjectNode version = detectorVersion(region, detectorId, versionId);
            ObjectNode eventType = eventType(region, job.path("eventTypeName").asText());
            List<Map<String, String>> rows = FraudDetectorCsv.parse(readS3(job.path("inputPath").asText()));
            StringBuilder out = new StringBuilder();
            List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.getFirst().keySet());
            out.append(String.join(",", columns)).append(columns.isEmpty() ? "" : ",")
                    .append("DETECTOR_OUTCOMES,RULE_RESULTS,MODEL_SCORES,ERROR\n");
            int processed = 0;
            for (Map<String, String> row : rows) {
                for (String column : columns) {
                    out.append(FraudDetectorCsv.cell(row.get(column))).append(',');
                }
                ObjectNode request = FraudDetectorCsv.eventRequest(mapper, row, eventType);
                try {
                    Evaluation evaluation = evaluate(region, detector, version, request, now());
                    out.append(FraudDetectorCsv.cell(evaluation.metadata().path("outcomes").toString())).append(',')
                            .append(FraudDetectorCsv.cell(evaluation.result().path("ruleResults").toString()))
                            .append(',')
                            .append(FraudDetectorCsv.cell(evaluation.result().path("modelScores").toString()))
                            .append(",\n");
                    processed++;
                } catch (AwsException e) {
                    out.append(",,,").append(FraudDetectorCsv.cell(e.getMessage())).append('\n');
                }
                job.put("lastHeartbeatTime", now());
            }
            job.put("totalRecordsCount", rows.size()).put("processedRecordsCount", processed);
            writeS3(job.path("outputPath").asText().replaceAll("/?$", "/") + job.path("jobId").asText()
                    + "_output.csv", out.toString());
            finish(batchPredictions, job, "COMPLETE", null);
        } catch (AwsException e) {
            finish(batchPredictions, job, "FAILED", e.getMessage());
        }
    }

    private ObjectNode getJobs(AccountAwareStorageBackend<ObjectNode> store, String kind, String field,
                               JsonNode in, String region) {
        String jobId = text(in, "jobId");
        ObjectNode out = mapper.createObjectNode();
        if (jobId != null) {
            ObjectNode job = store.get(arn(region, kind + "/" + jobId)).orElseThrow(() ->
                    notFound("The " + kind.replace('-', ' ') + " job " + jobId + " does not exist."));
            out.putArray(field).add(view(job));
            return out;
        }
        return page(inRegion(store, region, r -> true), in, 50, field, out);
    }

    private ObjectNode cancelJob(AccountAwareStorageBackend<ObjectNode> store, String kind, JsonNode in,
                                 String region) {
        String jobId = text(in, "jobId");
        ObjectNode job = store.get(arn(region, kind + "/" + jobId)).orElseThrow(() ->
                notFound("The " + kind.replace('-', ' ') + " job " + jobId + " does not exist."));
        if (!job.path("status").asText().startsWith("IN_PROGRESS")) {
            throw validation("The job " + jobId + " is " + job.path("status").asText() + " and can't be cancelled.");
        }
        finish(store, job, "CANCELED", null);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteJob(AccountAwareStorageBackend<ObjectNode> store, String kind, JsonNode in,
                                 String region) {
        String jobId = text(in, "jobId");
        Optional<ObjectNode> job = store.get(arn(region, kind + "/" + jobId));
        if (job.isPresent() && job.get().path("status").asText().startsWith("IN_PROGRESS")) {
            throw validation("The job " + jobId + " is still running; cancel it first.");
        }
        removed(store, arn(region, kind + "/" + jobId));
        return mapper.createObjectNode();
    }

    // ---------- KMS ----------

    private ObjectNode getKmsEncryptionKey(String region) {
        String key = settings.get(arn(region, "kms-key")).map(s -> s.path("kmsEncryptionKeyArn").asText())
                .orElse("DEFAULT");
        ObjectNode out = mapper.createObjectNode();
        out.putObject("kmsKey").put("kmsEncryptionKeyArn", key);
        return out;
    }

    private ObjectNode putKmsEncryptionKey(JsonNode in, String region) {
        String keyArn = text(in, "kmsEncryptionKeyArn");
        if (!"DEFAULT".equals(keyArn)) {
            try {
                kms.get().describeKey(keyArn, AwsArnUtils.regionOrDefault(keyArn, region));
            } catch (AwsException e) {
                throw notFound("The KMS key " + keyArn + " does not exist.");
            }
        }
        settings.put(arn(region, "kms-key"), mapper.createObjectNode().put("kmsEncryptionKeyArn", keyArn));
        return mapper.createObjectNode();
    }

    // ---------- tags ----------

    private ObjectNode tagsOf(String resourceArn) {
        return tags.get(resourceArn).orElseThrow(() -> notFound("The resource " + resourceArn + " does not exist."));
    }

    private ObjectNode tagResource(JsonNode in) {
        String resourceArn = text(in, "resourceARN");
        ObjectNode current = tagsOf(resourceArn);
        in.path("tags").forEach(tag -> current.put(tag.path("key").asText(), tag.path("value").asText()));
        if (current.size() > 200) {
            throw validation("A resource can have at most 200 tags.");
        }
        tags.put(resourceArn, current);
        return mapper.createObjectNode();
    }

    private ObjectNode untagResource(JsonNode in) {
        String resourceArn = text(in, "resourceARN");
        ObjectNode current = tagsOf(resourceArn);
        current.remove(strings(in.get("tagKeys")));
        tags.put(resourceArn, current);
        return mapper.createObjectNode();
    }

    private ObjectNode listTagsForResource(JsonNode in) {
        ObjectNode current = tagsOf(text(in, "resourceARN"));
        List<ObjectNode> entries = new ArrayList<>();
        current.fields().forEachRemaining(entry -> entries.add(mapper.createObjectNode().put("key", entry.getKey())
                .put("value", entry.getValue().asText())));
        return page(entries, in, 50, "tags", mapper.createObjectNode());
    }
}
