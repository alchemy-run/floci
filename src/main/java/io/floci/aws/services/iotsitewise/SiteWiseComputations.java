package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.aws.services.iotsitewise.SiteWiseStore.ACTION;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.COMPUTATION_MODEL;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.EXECUTION;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.invalid;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.meta;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.notFound;

/**
 * Computation models and the actions run on them: anomaly detection over an asset's input properties, trained from
 * their history and then run on a schedule, writing a JSON result to the result property on each sample. Training
 * resamples the inputs at the target sampling rate and learns each input's mean and spread; inference scores each
 * new sample by how far its inputs are from what training saw ({@code anomaly_score}), predicts an anomaly past three
 * standard deviations, and says how much each input contributed ({@code diagnostics}). The same engine runs the
 * Training, Inference and TrainingWithInference actions of an asset's {@code AWS/L4E_ANOMALY} composite model, whose
 * inputs its {@code AWS/L4E_ANOMALY_INPUT} attribute lists and whose results go to {@code AWS/L4E_ANOMALY_RESULT}.
 */
@ApplicationScoped
public class SiteWiseComputations {

    private static final Logger LOG = Logger.getLogger(SiteWiseComputations.class);
    static final String TRAINING = "AWS/ANOMALY_DETECTION_TRAINING";
    static final String INFERENCE = "AWS/ANOMALY_DETECTION_INFERENCE";
    static final String L4E_TRAINING = "AWS/L4E_ANOMALY_TRAINING";
    static final String L4E_INFERENCE = "AWS/L4E_ANOMALY_INFERENCE";
    static final String L4E_TRAINING_WITH_INFERENCE = "AWS/L4E_ANOMALY_TRAINING_WITH_INFERENCE";
    private static final Pattern REFERENCE = Pattern.compile("^\\$\\{([a-z][a-z0-9_]*)}$");
    private static final Set<String> UPLOAD_FREQUENCIES = Set.of("PT5M", "PT10M", "PT15M", "PT30M", "PT1H", "PT2H",
            "PT3H", "PT4H", "PT5H", "PT6H", "PT7H", "PT8H", "PT9H", "PT10H", "PT11H", "PT12H", "PT1D");

    private final SiteWiseStore store;
    private final ObjectMapper mapper;
    private final Instance<SiteWiseModels> models;
    private final Instance<SiteWiseAssets> assets;
    private final Instance<SiteWiseData> data;
    private ScheduledExecutorService scheduler;

    @Inject
    public SiteWiseComputations(SiteWiseStore store, Instance<SiteWiseModels> models, Instance<SiteWiseAssets> assets,
                                Instance<SiteWiseData> data) {
        this.store = store;
        this.mapper = store.mapper();
        this.models = models;
        this.assets = assets;
        this.data = data;
    }

    // ---------------------------------------------------------------- computation models

    ObjectNode requireModel(String account, String region, String id) {
        ObjectNode model = store.get(account, region, COMPUTATION_MODEL, id).orElse(null);
        model = model == null ? null : settle(account, region, model);
        if (model == null) {
            throw notFound("Computation model " + id + " not found.");
        }
        return model;
    }

    private ObjectNode settle(String account, String region, ObjectNode model) {
        long readyAt = meta(model).path("readyAt").asLong(0);
        if (readyAt == 0 || System.currentTimeMillis() < readyAt) {
            return model;
        }
        String id = model.get("computationModelId").asText();
        if ("DELETING".equals(model.path("computationModelStatus").path("state").asText())) {
            store.delete(account, region, COMPUTATION_MODEL, id);
            return null;
        }
        model.set("computationModelStatus", mapper.createObjectNode().put("state", "ACTIVE"));
        meta(model).remove("readyAt");
        store.put(account, region, COMPUTATION_MODEL, id, model);
        return model;
    }

    private void transition(ObjectNode model, String state) {
        model.set("computationModelStatus", mapper.createObjectNode().put("state", state));
        meta(model).put("readyAt", System.currentTimeMillis() + store.config().transitionMillis());
    }

    /** Checks a configuration's references against the data binding, and each bound property against its model. */
    private void checkDefinition(String account, String region, JsonNode configuration, JsonNode binding) {
        JsonNode anomaly = configuration.get("anomalyDetection");
        if (anomaly == null) {
            throw invalid("A computation model's configuration is anomalyDetection.");
        }
        for (String field : List.of("inputProperties", "resultProperty")) {
            Matcher matcher = REFERENCE.matcher(anomaly.path(field).asText());
            if (!matcher.matches()) {
                throw invalid(field + " is a ${variable} the data binding defines.");
            }
            JsonNode value = binding.get(matcher.group(1));
            if (value == null) {
                throw invalid("The data binding doesn't define " + matcher.group(1) + ".");
            }
            if ("resultProperty".equals(field) && value.has("list")) {
                throw invalid("The result property is one property, not a list.");
            }
        }
        binding.fields().forEachRemaining(entry -> checkBinding(account, region, entry.getValue()));
        String result = REFERENCE.matcher(anomaly.get("resultProperty").asText()).replaceAll("$1");
        JsonNode resultBinding = binding.get(result);
        ObjectNode resultProperty = boundProperty(account, region, resultBinding);
        if (resultProperty != null && !Set.of("STRING", "JSON").contains(resultProperty.path("dataType").asText())) {
            throw invalid("The result property must be a STRING or JSON property.");
        }
    }

    private void checkBinding(String account, String region, JsonNode value) {
        if (value.has("list")) {
            if (value.get("list").isEmpty()) {
                throw invalid("A data binding list isn't empty.");
            }
            value.get("list").forEach(v -> checkBinding(account, region, v));
            return;
        }
        if (boundProperty(account, region, value) == null) {
            throw invalid("A data binding value is an assetModelProperty or an assetProperty.");
        }
    }

    /** The asset model property, or asset property, a binding value names. */
    private ObjectNode boundProperty(String account, String region, JsonNode value) {
        if (value.has("assetModelProperty")) {
            JsonNode bound = value.get("assetModelProperty");
            ObjectNode model = models.get().require(account, region, bound.get("assetModelId").asText());
            for (ObjectNode property : SiteWiseModels.allProperties(model)) {
                if (property.get("id").asText().equals(bound.get("propertyId").asText())) {
                    return property;
                }
            }
            throw invalid("Asset model " + bound.get("assetModelId").asText() + " has no property "
                    + bound.get("propertyId").asText() + ".");
        }
        if (value.has("assetProperty")) {
            JsonNode bound = value.get("assetProperty");
            ObjectNode asset = assets.get().require(account, region, bound.get("assetId").asText());
            try {
                return assets.get().property(asset, bound.get("propertyId").asText());
            } catch (AwsException e) {
                throw invalid(e.getMessage());
            }
        }
        return null;
    }

    ObjectNode createComputationModel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateComputationModel", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.get("computationModelName").asText();
        for (ObjectNode other : store.list(account, region, COMPUTATION_MODEL)) {
            if (name.equals(other.get("computationModelName").asText())) {
                throw SiteWiseStore.exists("A computation model named " + name + " already exists.",
                        other.get("computationModelId").asText(), other.get("computationModelArn").asText());
            }
        }
        checkDefinition(account, region, in.get("computationModelConfiguration"),
                in.get("computationModelDataBinding"));
        String id = SiteWiseStore.uuid();
        double now = SiteWiseStore.now();
        ObjectNode model = store.record();
        model.put("computationModelId", id);
        model.put("computationModelArn", store.arn(account, region, COMPUTATION_MODEL, id));
        model.put("computationModelName", name);
        if (in.hasNonNull("computationModelDescription")) {
            model.put("computationModelDescription", in.get("computationModelDescription").asText());
        }
        model.set("computationModelConfiguration", in.get("computationModelConfiguration").deepCopy());
        model.set("computationModelDataBinding", in.get("computationModelDataBinding").deepCopy());
        model.put("computationModelCreationDate", now);
        model.put("computationModelLastUpdateDate", now);
        model.put("computationModelVersion", "1");
        ArrayNode actions = model.putArray("actionDefinitions");
        actions.addObject().put("actionDefinitionId", SiteWiseStore.uuid()).put("actionName", "AnomalyDetectionTraining")
                .put("actionType", TRAINING);
        actions.addObject().put("actionDefinitionId", SiteWiseStore.uuid())
                .put("actionName", "AnomalyDetectionInference").put("actionType", INFERENCE);
        meta(model).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        transition(model, "CREATING");
        store.put(account, region, COMPUTATION_MODEL, id, model);
        ObjectNode out = mapper.createObjectNode();
        out.put("computationModelId", id);
        out.put("computationModelArn", model.get("computationModelArn").asText());
        out.set("computationModelStatus", model.get("computationModelStatus").deepCopy());
        store.remember(account, region, "CreateComputationModel", in, out);
        return out;
    }

    ObjectNode describeComputationModel(ObjectNode in, String region) {
        return SiteWiseStore.view(requireModel(store.account(), region, in.get("computationModelId").asText()));
    }

    ObjectNode listComputationModels(ObjectNode in, String region) {
        String account = store.account();
        String type = in.path("computationModelType").asText(null);
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode stored : store.list(account, region, COMPUTATION_MODEL)) {
            ObjectNode model = settle(account, region, stored);
            if (model == null || type != null && !"ANOMALY_DETECTION".equals(type)) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            summary.put("id", model.get("computationModelId").asText());
            summary.put("arn", model.get("computationModelArn").asText());
            summary.put("name", model.get("computationModelName").asText());
            if (model.hasNonNull("computationModelDescription")) {
                summary.put("description", model.get("computationModelDescription").asText());
            }
            summary.put("type", "ANOMALY_DETECTION");
            summary.put("creationDate", model.get("computationModelCreationDate").asDouble());
            summary.put("lastUpdateDate", model.get("computationModelLastUpdateDate").asDouble());
            summary.set("status", model.get("computationModelStatus").deepCopy());
            summary.put("version", model.get("computationModelVersion").asText());
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "computationModelSummaries");
        return out;
    }

    ObjectNode updateComputationModel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = requireModel(account, region, in.get("computationModelId").asText());
        if (!"ACTIVE".equals(model.path("computationModelStatus").path("state").asText())) {
            throw SiteWiseStore.conflicting("Computation model " + model.get("computationModelId").asText()
                    + " isn't ACTIVE.", model.get("computationModelId").asText(),
                    model.get("computationModelArn").asText());
        }
        checkDefinition(account, region, in.get("computationModelConfiguration"),
                in.get("computationModelDataBinding"));
        model.put("computationModelName", in.get("computationModelName").asText());
        if (in.hasNonNull("computationModelDescription")) {
            model.put("computationModelDescription", in.get("computationModelDescription").asText());
        } else {
            model.remove("computationModelDescription");
        }
        model.set("computationModelConfiguration", in.get("computationModelConfiguration").deepCopy());
        model.set("computationModelDataBinding", in.get("computationModelDataBinding").deepCopy());
        model.put("computationModelVersion", String.valueOf(model.get("computationModelVersion").asInt() + 1));
        model.put("computationModelLastUpdateDate", SiteWiseStore.now());
        transition(model, "UPDATING");
        store.put(account, region, COMPUTATION_MODEL, model.get("computationModelId").asText(), model);
        ObjectNode out = mapper.createObjectNode();
        out.set("computationModelStatus", model.get("computationModelStatus").deepCopy());
        return out;
    }

    ObjectNode deleteComputationModel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = requireModel(account, region, in.get("computationModelId").asText());
        meta(model).remove("runs");
        transition(model, "DELETING");
        store.put(account, region, COMPUTATION_MODEL, model.get("computationModelId").asText(), model);
        ObjectNode out = mapper.createObjectNode();
        out.set("computationModelStatus", model.get("computationModelStatus").deepCopy());
        return out;
    }

    ObjectNode listComputationModelDataBindingUsages(ObjectNode in, String region) {
        String account = store.account();
        JsonNode filter = in.get("dataBindingValueFilter");
        Map<String, ObjectNode> usages = new LinkedHashMap<>();
        for (ObjectNode model : store.list(account, region, COMPUTATION_MODEL)) {
            List<JsonNode> values = new ArrayList<>();
            model.get("computationModelDataBinding").fields().forEachRemaining(e -> flatten(e.getValue(), values));
            for (JsonNode value : values) {
                if (!matches(filter, value)) {
                    continue;
                }
                String key = value.toString();
                ObjectNode usage = usages.computeIfAbsent(key, k -> {
                    ObjectNode created = mapper.createObjectNode();
                    created.putArray("computationModelIds");
                    created.putObject("matchedDataBinding").set("value", value.deepCopy());
                    return created;
                });
                ArrayNode ids = (ArrayNode) usage.get("computationModelIds");
                String id = model.get("computationModelId").asText();
                boolean present = false;
                for (JsonNode existing : ids) {
                    present |= id.equals(existing.asText());
                }
                if (!present) {
                    ids.add(id);
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(new ArrayList<>(usages.values()), in, 50, out, "dataBindingUsageSummaries");
        return out;
    }

    private static void flatten(JsonNode value, List<JsonNode> into) {
        if (value.has("list")) {
            value.get("list").forEach(v -> flatten(v, into));
        } else {
            into.add(value);
        }
    }

    private static boolean matches(JsonNode filter, JsonNode value) {
        JsonNode modelProperty = value.path("assetModelProperty");
        JsonNode assetProperty = value.path("assetProperty");
        if (filter.has("assetModel")) {
            return filter.get("assetModel").get("assetModelId").asText().equals(modelProperty.path("assetModelId")
                    .asText());
        }
        if (filter.has("assetModelProperty")) {
            return filter.get("assetModelProperty").equals(modelProperty);
        }
        if (filter.has("asset")) {
            return filter.get("asset").get("assetId").asText().equals(assetProperty.path("assetId").asText());
        }
        if (filter.has("assetProperty")) {
            return filter.get("assetProperty").equals(assetProperty);
        }
        return false;
    }

    ObjectNode listComputationModelResolveToResources(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = requireModel(account, region, in.get("computationModelId").asText());
        List<ObjectNode> summaries = new ArrayList<>();
        meta(model).path("runs").fieldNames().forEachRemaining(asset -> {
            if (!asset.isEmpty()) {
                summaries.add(mapper.createObjectNode().set("resolveTo", mapper.createObjectNode()
                        .put("assetId", asset)));
            }
        });
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "computationModelResolveToResourceSummaries");
        return out;
    }

    ObjectNode describeComputationModelExecutionSummary(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = requireModel(account, region, in.get("computationModelId").asText());
        String asset = in.path("resolveToResourceId").asText("");
        JsonNode run = meta(model).path("runs").path(asset);
        ObjectNode out = mapper.createObjectNode();
        out.put("computationModelId", model.get("computationModelId").asText());
        if (!asset.isEmpty()) {
            out.putObject("resolveTo").put("assetId", asset);
        }
        out.set("computationModelExecutionSummary", summary(run));
        return out;
    }

    private ObjectNode summary(JsonNode run) {
        ObjectNode summary = mapper.createObjectNode();
        if (run.has("model")) {
            summary.put("modelVersion", run.path("model").path("version").asText());
            summary.put("trainingDataStartTime", run.path("model").path("start").asText());
            summary.put("trainingDataEndTime", run.path("model").path("end").asText());
            summary.put("targetSamplingRate", run.path("model").path("rate").asText());
        }
        summary.put("inferenceTimerActive", run.path("inference").path("active").asBoolean(false) ? "TRUE" : "FALSE");
        if (run.has("retraining")) {
            summary.put("retrainingSchedulerActive", run.path("retraining").path("active").asBoolean(false)
                    ? "TRUE" : "FALSE");
        }
        return summary;
    }

    // ---------------------------------------------------------------- actions

    /** What an action runs on: its inputs, its result property and the asset, resolved from the target. */
    record Target(String kind, String id, String assetId, String version, List<String[]> inputs, String result,
                  String type) {
    }

    private Target target(String account, String region, JsonNode in) {
        JsonNode target = in.get("targetResource");
        String definitionId = in.get("actionDefinitionId").asText();
        String resolveTo = in.path("resolveTo").path("assetId").asText(null);
        if (target.hasNonNull("computationModelId")) {
            ObjectNode model = requireModel(account, region, target.get("computationModelId").asText());
            String type = null;
            for (JsonNode definition : model.get("actionDefinitions")) {
                if (definitionId.equals(definition.get("actionDefinitionId").asText())) {
                    type = definition.get("actionType").asText();
                }
            }
            if (type == null) {
                throw notFound("Action definition " + definitionId + " not found on computation model "
                        + model.get("computationModelId").asText() + ".");
            }
            JsonNode anomaly = model.get("computationModelConfiguration").get("anomalyDetection");
            JsonNode binding = model.get("computationModelDataBinding");
            List<JsonNode> inputs = new ArrayList<>();
            flatten(binding.get(REFERENCE.matcher(anomaly.get("inputProperties").asText()).replaceAll("$1")), inputs);
            JsonNode result = binding.get(REFERENCE.matcher(anomaly.get("resultProperty").asText()).replaceAll("$1"));
            List<String[]> resolved = new ArrayList<>();
            String assetId = resolveTo;
            for (JsonNode input : inputs) {
                resolved.add(resolve(account, region, input, resolveTo));
            }
            String[] resultProperty = resolve(account, region, result, resolveTo);
            if (assetId == null) {
                assetId = resultProperty[0];
            }
            return new Target("COMPUTATION_MODEL", model.get("computationModelId").asText(), assetId,
                    model.get("computationModelVersion").asText(), resolved, resultProperty[1], type);
        }
        if (!target.hasNonNull("assetId")) {
            throw invalid("The target resource is an asset or a computation model.");
        }
        ObjectNode asset = assets.get().require(account, region, target.get("assetId").asText());
        for (JsonNode composite : asset.path("assetCompositeModels")) {
            if (!"AWS/L4E_ANOMALY".equals(composite.path("type").asText())) {
                continue;
            }
            ObjectNode model = models.get().require(account, region, asset.get("assetModelId").asText());
            ObjectNode definitionComposite = models.get().composite(model, composite.get("id").asText());
            String type = null;
            for (JsonNode definition : definitionComposite.path("_actions")) {
                if (definitionId.equals(definition.get("actionDefinitionId").asText())) {
                    type = definition.get("actionType").asText();
                }
            }
            if (type == null) {
                continue;
            }
            String inputId = null;
            String resultId = null;
            for (JsonNode property : composite.path("properties")) {
                if ("AWS/L4E_ANOMALY_INPUT".equals(property.get("name").asText())) {
                    inputId = property.get("id").asText();
                } else if ("AWS/L4E_ANOMALY_RESULT".equals(property.get("name").asText())) {
                    resultId = property.get("id").asText();
                }
            }
            List<String[]> resolved = new ArrayList<>();
            List<JsonNode> values = data.get().points(account, region,
                    SiteWiseData.associatedId(asset.get("assetId").asText(), inputId));
            if (!values.isEmpty()) {
                try {
                    JsonNode listed = mapper.readTree(values.get(values.size() - 1).path("v").path("stringValue")
                            .asText("{}"));
                    listed.path("properties").forEach(p -> resolved.add(new String[] {asset.get("assetId").asText(),
                            p.asText()}));
                } catch (JsonProcessingException e) {
                    throw invalid("The AWS/L4E_ANOMALY_INPUT attribute isn't JSON.");
                }
            }
            if (resolved.isEmpty()) {
                throw invalid("The AWS/L4E_ANOMALY_INPUT attribute lists no properties.");
            }
            return new Target("ASSET", asset.get("assetId").asText(), asset.get("assetId").asText(),
                    model.get("assetModelVersion").asText("1"), resolved, resultId, type);
        }
        throw notFound("Action definition " + definitionId + " not found on asset " + asset.get("assetId").asText()
                + ".");
    }

    /** An asset ID and property ID for a binding value, on the resolved asset for asset model bindings. */
    private String[] resolve(String account, String region, JsonNode value, String assetId) {
        if (value.has("assetProperty")) {
            return new String[] {value.get("assetProperty").get("assetId").asText(),
                    value.get("assetProperty").get("propertyId").asText()};
        }
        JsonNode bound = value.get("assetModelProperty");
        if (assetId == null) {
            throw invalid("A computation model bound to asset model properties runs on an asset: set resolveTo.");
        }
        ObjectNode asset = assets.get().require(account, region, assetId);
        if (!bound.get("assetModelId").asText().equals(asset.get("assetModelId").asText())) {
            throw invalid("Asset " + assetId + " isn't of asset model " + bound.get("assetModelId").asText() + ".");
        }
        return new String[] {assetId, bound.get("propertyId").asText()};
    }

    ObjectNode executeAction(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "ExecuteAction", in);
        if (repeated != null) {
            return repeated;
        }
        Target target = target(account, region, in);
        JsonNode payload;
        try {
            String text = in.get("actionPayload").get("stringValue").asText();
            if (text.chars().anyMatch(c -> c == '\n' || c == '\r' || c == '\t')) {
                throw invalid("The action payload is a flat string, without newlines or tabs.");
            }
            payload = mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw invalid("The action payload isn't JSON: " + e.getOriginalMessage());
        }
        String actionId = SiteWiseStore.uuid();
        ObjectNode action = store.record();
        action.put("actionId", actionId);
        action.set("targetResource", in.get("targetResource").deepCopy());
        action.put("actionDefinitionId", in.get("actionDefinitionId").asText());
        action.set("actionPayload", in.get("actionPayload").deepCopy());
        action.put("executionTime", SiteWiseStore.now());
        if (in.has("resolveTo")) {
            action.set("resolveTo", in.get("resolveTo").deepCopy());
        }
        store.put(account, region, ACTION, actionId, action);
        ObjectNode execution = execution(account, region, target, in, target.type());
        meta(execution).put("actionId", actionId);
        store.put(account, region, EXECUTION, execution.get("executionId").asText(), execution);
        run(account, region, target, payload, execution);
        ObjectNode out = mapper.createObjectNode().put("actionId", actionId);
        store.remember(account, region, "ExecuteAction", in, out);
        return out;
    }

    private ObjectNode execution(String account, String region, Target target, JsonNode in, String actionType) {
        String id = SiteWiseStore.uuid();
        ObjectNode execution = store.record();
        execution.put("executionId", id);
        execution.put("actionType", actionType);
        execution.set("targetResource", in.get("targetResource").deepCopy());
        execution.put("targetResourceVersion", target.version());
        if (in.has("resolveTo")) {
            execution.set("resolveTo", in.get("resolveTo").deepCopy());
        }
        execution.put("executionStartTime", SiteWiseStore.now());
        execution.set("executionStatus", mapper.createObjectNode().put("state", "RUNNING"));
        execution.put("executionEntityVersion", "1");
        return execution;
    }

    private void finish(String account, String region, ObjectNode execution, String state,
                        Map<String, String> result, Map<String, String> details) {
        execution.put("executionEndTime", SiteWiseStore.now());
        execution.set("executionStatus", mapper.createObjectNode().put("state", state));
        ObjectNode resultNode = execution.putObject("executionResult");
        result.forEach((k, v) -> resultNode.put(k, clean(v)));
        ObjectNode detailNode = execution.putObject("executionDetails");
        details.forEach((k, v) -> detailNode.put(k, clean(v)));
        store.put(account, region, EXECUTION, execution.get("executionId").asText(), execution);
    }

    /** A value as executions show them, in the characters their maps allow. */
    private static String clean(String value) {
        String cleaned = value.replaceAll("[^a-zA-Z0-9 _\\-#$*!@]", " ").trim();
        return cleaned.isEmpty() ? "-" : cleaned;
    }

    /** The run state of a computation model on one asset, or of an asset's L4E composite model. */
    private ObjectNode runState(String account, String region, Target target) {
        if ("COMPUTATION_MODEL".equals(target.kind())) {
            ObjectNode model = requireModel(account, region, target.id());
            return (ObjectNode) meta(model).with("runs").with(target.assetId());
        }
        ObjectNode asset = assets.get().require(account, region, target.id());
        return (ObjectNode) meta(asset).with("anomaly");
    }

    private void saveRunState(String account, String region, Target target, ObjectNode state) {
        if ("COMPUTATION_MODEL".equals(target.kind())) {
            ObjectNode model = requireModel(account, region, target.id());
            ((ObjectNode) meta(model).with("runs")).set(target.assetId(), state);
            store.put(account, region, COMPUTATION_MODEL, target.id(), model);
        } else {
            ObjectNode asset = assets.get().require(account, region, target.id());
            meta(asset).set("anomaly", state);
            store.put(account, region, SiteWiseStore.ASSET, target.id(), asset);
        }
    }

    private void run(String account, String region, Target target, JsonNode payload, ObjectNode execution) {
        ObjectNode state = runState(account, region, target).deepCopy();
        state.put("kind", target.kind()).put("id", target.id()).put("assetId", target.assetId());
        try {
            switch (target.type()) {
                case TRAINING, L4E_TRAINING -> {
                    JsonNode training = payload.has("l4ETraining") ? payload.get("l4ETraining") : payload;
                    String mode = training.path("trainingMode").asText("TRAIN_MODEL");
                    if ("START_RETRAINING_SCHEDULER".equals(mode)) {
                        JsonNode retraining = training.path("retrainingConfiguration");
                        if (!Set.of("P180D", "P360D", "P540D", "P720D").contains(retraining.path("lookbackWindow")
                                .asText())) {
                            throw invalid("The lookback window is P180D, P360D, P540D or P720D.");
                        }
                        state.putObject("retraining").put("active", true).set("configuration", retraining.deepCopy());
                        finish(account, region, execution, "COMPLETED", Map.of("retrainingScheduler", "STARTED"),
                                Map.of());
                    } else if ("STOP_RETRAINING_SCHEDULER".equals(mode)) {
                        state.putObject("retraining").put("active", false);
                        finish(account, region, execution, "COMPLETED", Map.of("retrainingScheduler", "STOPPED"),
                                Map.of());
                    } else {
                        train(account, region, target, training, state, execution);
                    }
                }
                case INFERENCE, L4E_INFERENCE -> {
                    JsonNode inference = payload.has("l4EInference") ? payload.get("l4EInference") : payload;
                    inference(account, region, target, inference, state, execution);
                }
                case L4E_TRAINING_WITH_INFERENCE -> {
                    JsonNode both = payload.path("l4ETrainingWithInference");
                    if ("STOP".equals(both.path("trainingWithInferenceMode").asText())) {
                        inference(account, region, target, mapper.createObjectNode().put("inferenceMode", "STOP"),
                                state, execution);
                    } else {
                        ObjectNode training = both.path("trainingPayload").deepCopy();
                        if (both.has("targetSamplingRate")) {
                            training.set("targetSamplingRate", both.get("targetSamplingRate"));
                        }
                        train(account, region, target, training, state, execution);
                        ObjectNode inference = payload.path("inferencePayload").isObject()
                                ? payload.get("inferencePayload").deepCopy() : mapper.createObjectNode();
                        inference.put("inferenceMode", "START");
                        if (!inference.has("dataUploadFrequency")) {
                            inference.put("dataUploadFrequency", "PT5M");
                        }
                        startInference(account, region, target, inference, state);
                    }
                }
                default -> throw invalid("The action type " + target.type() + " isn't supported.");
            }
        } catch (AwsException e) {
            finish(account, region, execution, "FAILED", Map.of(), Map.of("error", e.getMessage()));
            throw e;
        } finally {
            saveRunState(account, region, target, state);
        }
    }

    private static long sampling(String rate) {
        try {
            long seconds = Duration.parse(rate).toSeconds();
            if (seconds < 1 || seconds > 3600) {
                throw invalid("The target sampling rate is between PT1S and PT1H.");
            }
            return seconds;
        } catch (DateTimeParseException e) {
            throw invalid("The target sampling rate " + rate + " isn't an ISO 8601 duration.");
        }
    }

    /** Each input's last GOOD value at or before each sampling time, from start to end. */
    private List<double[]> samples(String account, String region, Target target, long start, long end, long step) {
        List<List<JsonNode>> series = new ArrayList<>();
        for (String[] input : target.inputs()) {
            List<JsonNode> good = new ArrayList<>();
            for (JsonNode point : data.get().points(account, region, SiteWiseData.associatedId(input[0], input[1]))) {
                if ("GOOD".equals(point.path("q").asText("GOOD"))
                        && SiteWiseData.number(point.get("v")) instanceof Double) {
                    good.add(point);
                }
            }
            series.add(good);
        }
        List<double[]> rows = new ArrayList<>();
        for (long at = start; at <= end; at += step) {
            double[] row = new double[target.inputs().size() + 1];
            row[0] = at;
            boolean complete = true;
            for (int i = 0; i < series.size(); i++) {
                Double value = null;
                for (JsonNode point : series.get(i)) {
                    if (SiteWiseData.time(point) <= at && SiteWiseData.time(point) > at - Math.max(step, 60) * 10L) {
                        value = (Double) SiteWiseData.number(point.get("v"));
                    } else if (SiteWiseData.time(point) > at) {
                        break;
                    }
                }
                if (value == null) {
                    complete = false;
                    break;
                }
                row[i + 1] = value;
            }
            if (complete) {
                rows.add(row);
            }
        }
        return rows;
    }

    private void train(String account, String region, Target target, JsonNode training, ObjectNode state,
                       ObjectNode execution) {
        if (!training.has("exportDataStartTime") || !training.has("exportDataEndTime")) {
            throw invalid("Training needs exportDataStartTime and exportDataEndTime.");
        }
        long start = training.get("exportDataStartTime").asLong();
        long end = training.get("exportDataEndTime").asLong();
        if (start >= end) {
            throw invalid("The training data starts before it ends.");
        }
        String rate = training.path("targetSamplingRate").asText("PT1M");
        long step = sampling(rate);
        List<double[]> rows = samples(account, region, target, start, end, step);
        if (rows.size() < store.config().minTrainingSamples()) {
            finish(account, region, execution, "FAILED", Map.of(), Map.of("error", "Training found " + rows.size()
                    + " samples of every input between " + start + " and " + end + "; it needs at least "
                    + store.config().minTrainingSamples()));
            return;
        }
        int inputs = target.inputs().size();
        ArrayNode means = mapper.createArrayNode();
        ArrayNode deviations = mapper.createArrayNode();
        for (int i = 1; i <= inputs; i++) {
            double sum = 0;
            for (double[] row : rows) {
                sum += row[i];
            }
            double mean = sum / rows.size();
            double squares = 0;
            for (double[] row : rows) {
                squares += (row[i] - mean) * (row[i] - mean);
            }
            means.add(mean);
            deviations.add(Math.max(Math.sqrt(squares / Math.max(1, rows.size() - 1)), 1e-9));
        }
        int version = state.path("model").path("version").asInt(0) + 1;
        ObjectNode model = state.putObject("model");
        model.put("version", version);
        model.put("start", start);
        model.put("end", end);
        model.put("rate", rate);
        model.put("step", step);
        model.set("means", means);
        model.set("deviations", deviations);
        model.put("samples", rows.size());
        finish(account, region, execution, "COMPLETED", Map.of("modelVersion", String.valueOf(version)),
                Map.of("trainingSamples", String.valueOf(rows.size()), "trainingDataStartTime",
                        String.valueOf(start), "trainingDataEndTime", String.valueOf(end)));
    }

    private void inference(String account, String region, Target target, JsonNode inference, ObjectNode state,
                           ObjectNode execution) {
        String mode = inference.path("inferenceMode").asText();
        if ("STOP".equals(mode)) {
            state.with("inference").put("active", false);
            finish(account, region, execution, "COMPLETED", Map.of("inference", "STOPPED"), Map.of());
            return;
        }
        if (!"START".equals(mode)) {
            throw invalid("The inference mode is START or STOP.");
        }
        startInference(account, region, target, inference, state);
        finish(account, region, execution, "COMPLETED", Map.of("inference", "STARTED"),
                Map.of("dataUploadFrequency", inference.get("dataUploadFrequency").asText()));
    }

    private void startInference(String account, String region, Target target, JsonNode inference, ObjectNode state) {
        if (!state.has("model")) {
            throw invalid("Train the model before starting inference.");
        }
        String frequency = inference.path("dataUploadFrequency").asText(null);
        if (frequency == null || !UPLOAD_FREQUENCIES.contains(frequency)) {
            throw invalid("The data upload frequency is one of " + String.join(", ",
                    new TreeSet<>(UPLOAD_FREQUENCIES)) + ".");
        }
        int offset = inference.path("dataDelayOffsetInMinutes").asInt(0);
        if (offset < 0 || offset > 60) {
            throw invalid("The data delay offset is between 0 and 60 minutes.");
        }
        ObjectNode timer = state.putObject("inference");
        timer.put("active", true);
        timer.put("frequency", frequency);
        timer.put("offsetMinutes", offset);
        long now = System.currentTimeMillis() / 1000;
        timer.put("next", now);
        timer.put("through", now - Duration.parse(frequency).toSeconds());
        timer.put("resultPropertyId", target.result());
        ArrayNode inputs = timer.putArray("inputs");
        target.inputs().forEach(i -> inputs.addArray().add(i[0]).add(i[1]));
        timer.put("type", target.type());
        timer.put("version", target.version());
        startScheduler();
    }

    // ---------------------------------------------------------------- inference scheduler

    public synchronized void startScheduler() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "iotsitewise-inference");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void tick() {
        long now = System.currentTimeMillis() / 1000;
        List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> holders = new ArrayList<>(store.everywhere(
                key -> key.contains("/" + COMPUTATION_MODEL + "/") || key.contains("/" + SiteWiseStore.ASSET + "/")));
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> holder : holders) {
            String region = holder.key().substring(0, holder.key().indexOf('/'));
            List<ObjectNode> states = new ArrayList<>();
            if (holder.value().has("computationModelId")) {
                meta(holder.value()).path("runs").forEach(run -> states.add((ObjectNode) run));
            } else if (meta(holder.value()).has("anomaly")) {
                states.add((ObjectNode) meta(holder.value()).get("anomaly"));
            }
            for (ObjectNode state : states) {
                JsonNode timer = state.path("inference");
                if (!timer.path("active").asBoolean(false) || timer.path("next").asLong() > now) {
                    continue;
                }
                try {
                    RequestScopes.runAs(holder.accountId(), region, () -> infer(holder.accountId(), region, state,
                            now));
                } catch (RuntimeException e) {
                    LOG.debugv(e, "IoT SiteWise inference failed in {0}", region);
                }
            }
        }
    }

    /** One scheduled inference: scores each sample since the last run and writes the results. */
    private void infer(String account, String region, ObjectNode stored, long now) {
        ObjectNode state = stored.deepCopy();
        JsonNode timer = state.get("inference");
        long frequency = Duration.parse(timer.get("frequency").asText()).toSeconds();
        long through = now - timer.path("offsetMinutes").asLong(0) * 60;
        long from = timer.get("through").asLong();
        JsonNode model = state.get("model");
        long step = model.get("step").asLong();
        List<String[]> inputs = new ArrayList<>();
        timer.get("inputs").forEach(i -> inputs.add(new String[] {i.get(0).asText(), i.get(1).asText()}));
        Target target = new Target(state.get("kind").asText(), state.get("id").asText(), state.get("assetId").asText(),
                timer.path("version").asText("1"), inputs, timer.get("resultPropertyId").asText(),
                timer.get("type").asText());
        ObjectNode execution = execution(account, region, target, request(target), target.type());
        store.put(account, region, EXECUTION, execution.get("executionId").asText(), execution);
        long start = (from / step + 1) * step;
        List<double[]> rows = samples(account, region, target, start, through, step);
        ObjectNode asset = assets.get().find(account, region, target.assetId());
        int anomalies = 0;
        if (asset != null) {
            ObjectNode property = assets.get().property(asset, target.result());
            List<ObjectNode> points = new ArrayList<>();
            for (double[] row : rows) {
                ObjectNode result = score(row, model, inputs);
                anomalies += result.get("prediction").asInt();
                points.add(data.get().point((long) row[0], 0, "GOOD", mapper.createObjectNode()
                        .put("stringValue", result.toString())));
            }
            if (!points.isEmpty()) {
                data.get().record(account, region, asset, property, points);
            }
        }
        ((ObjectNode) state.get("inference")).put("through", through).put("next", now + frequency);
        stored.setAll(state);
        Target holder = target;
        saveRunState(account, region, holder, stored);
        finish(account, region, execution, "COMPLETED", Map.of("anomalies", String.valueOf(anomalies)),
                Map.of("samples", String.valueOf(rows.size()), "inferenceDataEndTime", String.valueOf(through)));
    }

    private ObjectNode request(Target target) {
        ObjectNode request = mapper.createObjectNode();
        if ("COMPUTATION_MODEL".equals(target.kind())) {
            request.putObject("targetResource").put("computationModelId", target.id());
            request.putObject("resolveTo").put("assetId", target.assetId());
        } else {
            request.putObject("targetResource").put("assetId", target.id());
        }
        return request;
    }

    /** A sample's anomaly result: how far its inputs are from training, and what each input contributed. */
    ObjectNode score(double[] row, JsonNode model, List<String[]> inputs) {
        double total = 0;
        double[] squares = new double[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            double z = (row[i + 1] - model.get("means").get(i).asDouble()) / model.get("deviations").get(i).asDouble();
            squares[i] = z * z;
            total += squares[i];
        }
        double rms = Math.sqrt(total / Math.max(1, inputs.size()));
        double score = 1 - Math.exp(-rms * rms / 18);
        boolean anomalous = rms > 3;
        ObjectNode result = mapper.createObjectNode();
        result.put("timestamp", Instant.ofEpochSecond((long) row[0]).toString());
        result.put("prediction", anomalous ? 1 : 0);
        result.put("prediction_reason", anomalous ? "ANOMALY_DETECTED" : "NO_ANOMALY_DETECTED");
        result.put("anomaly_score", Math.round(score * 10000) / 10000.0);
        ArrayNode diagnostics = result.putArray("diagnostics");
        for (int i = 0; i < inputs.size(); i++) {
            diagnostics.addObject().put("name", inputs.get(i)[1] + "\\" + inputs.get(i)[1])
                    .put("value", total == 0 ? 1.0 / inputs.size() : Math.round(squares[i] / total * 10000) / 10000.0);
        }
        return result;
    }

    // ---------------------------------------------------------------- reads

    ObjectNode describeAction(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode action = store.get(account, region, ACTION, in.get("actionId").asText())
                .orElseThrow(() -> notFound("Action " + in.get("actionId").asText() + " not found."));
        return SiteWiseStore.view(action);
    }

    ObjectNode listActions(ObjectNode in, String region) {
        String account = store.account();
        String type = in.get("targetResourceType").asText();
        String id = in.get("targetResourceId").asText();
        String resolveTo = in.path("resolveToResourceId").asText(null);
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode action : store.list(account, region, ACTION)) {
            JsonNode target = action.get("targetResource");
            String targetId = "ASSET".equals(type) ? target.path("assetId").asText(null)
                    : target.path("computationModelId").asText(null);
            if (!id.equals(targetId)) {
                continue;
            }
            if (resolveTo != null && !resolveTo.equals(action.path("resolveTo").path("assetId").asText(null))) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            summary.put("actionId", action.get("actionId").asText());
            summary.put("actionDefinitionId", action.get("actionDefinitionId").asText());
            summary.set("targetResource", target.deepCopy());
            if (action.has("resolveTo")) {
                summary.set("resolveTo", action.get("resolveTo").deepCopy());
            }
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "actionSummaries");
        if (!out.has("nextToken")) {
            out.put("nextToken", SiteWiseStore.token(summaries.size()));
        }
        return out;
    }

    ObjectNode describeExecution(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode execution = store.get(account, region, EXECUTION, in.get("executionId").asText())
                .orElseThrow(() -> notFound("Execution " + in.get("executionId").asText() + " not found."));
        return SiteWiseStore.view(execution);
    }

    ObjectNode listExecutions(ObjectNode in, String region) {
        String account = store.account();
        String type = in.get("targetResourceType").asText();
        String id = in.get("targetResourceId").asText();
        String resolveTo = in.path("resolveToResourceId").asText(null);
        String actionType = in.path("actionType").asText(null);
        List<ObjectNode> summaries = new ArrayList<>();
        List<ObjectNode> executions = store.list(account, region, EXECUTION);
        Collections.reverse(executions);
        for (ObjectNode execution : executions) {
            JsonNode target = execution.get("targetResource");
            String targetId = "ASSET".equals(type) ? target.path("assetId").asText(null)
                    : target.path("computationModelId").asText(null);
            if (!id.equals(targetId) || actionType != null && !actionType.equals(execution.path("actionType")
                    .asText())) {
                continue;
            }
            if (resolveTo != null && !resolveTo.equals(execution.path("resolveTo").path("assetId").asText(null))) {
                continue;
            }
            ObjectNode summary = SiteWiseStore.view(execution);
            summary.remove("executionResult");
            summary.remove("executionDetails");
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "executionSummaries");
        return out;
    }

    /** The action definitions an L4E anomaly composite model offers. */
    ArrayNode l4eActions() {
        ArrayNode actions = mapper.createArrayNode();
        Map<String, String> names = new LinkedHashMap<>();
        names.put("Training", L4E_TRAINING);
        names.put("Inference", L4E_INFERENCE);
        names.put("TrainingWithInference", L4E_TRAINING_WITH_INFERENCE);
        names.forEach((name, type) -> actions.addObject().put("actionDefinitionId", SiteWiseStore.uuid())
                .put("actionName", name).put("actionType", type));
        return actions;
    }
}
