package io.github.hectorvent.floci.services.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Local Amazon Forecast stub. Datasets, dataset groups, import jobs,
 * predictors, forecasts, what-if resources, and the Forecast Query data
 * plane are in-memory. Training is instantaneous: created resources are
 * {@code ACTIVE} and {@code QueryForecast} returns a synthetic series.
 *
 * @see <a href="https://docs.aws.amazon.com/forecast/latest/dg/API_Operations.html">Forecast API</a>
 */
@ApplicationScoped
public class ForecastService implements Resettable {

    private static final Pattern NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,62}$");

    static final class Dataset {
        String arn;
        String name;
        String domain;
        String datasetType;
        String dataFrequency;
        JsonNode schema;
        JsonNode encryptionConfig;
        String status;
        long creationTime;
        long lastModificationTime;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    static final class DatasetGroup {
        String arn;
        String name;
        String domain;
        final List<String> datasetArns = new ArrayList<>();
        String status;
        long creationTime;
        long lastModificationTime;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    static final class NamedResource {
        String arn;
        String name;
        String parentArn;
        String status;
        long creationTime;
        long lastModificationTime;
        JsonNode request;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final ConcurrentHashMap<String, Dataset> datasets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DatasetGroup> groups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> importJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> predictors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> forecasts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> forecastExports = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> whatIfAnalyses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> whatIfForecasts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> whatIfExports = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> monitors = new ConcurrentHashMap<>();

    @Inject
    public ForecastService(ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        datasets.clear();
        groups.clear();
        importJobs.clear();
        predictors.clear();
        forecasts.clear();
        forecastExports.clear();
        whatIfAnalyses.clear();
        whatIfForecasts.clear();
        whatIfExports.clear();
        monitors.clear();
    }

    public ObjectNode createDataset(JsonNode request, String region) {
        String name = requireText(request, "DatasetName");
        validateName(name, "DatasetName");
        String domain = requireText(request, "Domain");
        String datasetType = requireText(request, "DatasetType");
        JsonNode schema = request.get("Schema");
        if (schema == null || schema.isNull() || !schema.isObject()) {
            throw invalid("Schema is required.");
        }
        String arn = regionResolver.buildArn("forecast", region, "dataset/" + name);
        if (datasets.containsKey(arn)) {
            throw alreadyExists("A dataset with the name " + name + " already exists.");
        }
        long now = nowSeconds();
        Dataset dataset = new Dataset();
        dataset.arn = arn;
        dataset.name = name;
        dataset.domain = domain;
        dataset.datasetType = datasetType;
        dataset.dataFrequency = textOrNull(request, "DataFrequency");
        dataset.schema = schema.deepCopy();
        JsonNode encryption = request.get("EncryptionConfig");
        if (encryption != null && encryption.isObject()) {
            dataset.encryptionConfig = encryption.deepCopy();
        }
        dataset.status = "ACTIVE";
        dataset.creationTime = now;
        dataset.lastModificationTime = now;
        dataset.tags.putAll(readTags(request));
        datasets.put(arn, dataset);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DatasetArn", arn);
        return response;
    }

    public ObjectNode describeDataset(JsonNode request) {
        Dataset dataset = requireDataset(requireText(request, "DatasetArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DatasetArn", dataset.arn);
        response.put("DatasetName", dataset.name);
        response.put("Domain", dataset.domain);
        response.put("DatasetType", dataset.datasetType);
        if (dataset.dataFrequency != null) {
            response.put("DataFrequency", dataset.dataFrequency);
        }
        if (dataset.schema != null) {
            response.set("Schema", dataset.schema.deepCopy());
        }
        if (dataset.encryptionConfig != null) {
            response.set("EncryptionConfig", dataset.encryptionConfig.deepCopy());
        }
        response.put("Status", dataset.status);
        response.put("CreationTime", dataset.creationTime);
        response.put("LastModificationTime", dataset.lastModificationTime);
        return response;
    }

    public ObjectNode deleteDataset(JsonNode request) {
        String arn = requireText(request, "DatasetArn");
        requireDataset(arn);
        for (DatasetGroup group : groups.values()) {
            if (group.datasetArns.contains(arn)) {
                throw inUse("Dataset " + arn + " is associated with a dataset group.");
            }
        }
        datasets.remove(arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDatasets() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Datasets");
        for (Dataset dataset : datasets.values()) {
            ObjectNode summary = list.addObject();
            summary.put("DatasetArn", dataset.arn);
            summary.put("DatasetName", dataset.name);
            summary.put("DatasetType", dataset.datasetType);
            summary.put("Domain", dataset.domain);
            summary.put("CreationTime", dataset.creationTime);
            summary.put("LastModificationTime", dataset.lastModificationTime);
        }
        return response;
    }

    public ObjectNode createDatasetGroup(JsonNode request, String region) {
        String name = requireText(request, "DatasetGroupName");
        validateName(name, "DatasetGroupName");
        String domain = requireText(request, "Domain");
        List<String> datasetArns = stringList(request.get("DatasetArns"));
        validateDatasetArns(datasetArns, domain);
        String arn = regionResolver.buildArn("forecast", region, "dataset-group/" + name);
        if (groups.containsKey(arn)) {
            throw alreadyExists("A dataset group with the name " + name + " already exists.");
        }
        long now = nowSeconds();
        DatasetGroup group = new DatasetGroup();
        group.arn = arn;
        group.name = name;
        group.domain = domain;
        group.datasetArns.addAll(datasetArns);
        group.status = "ACTIVE";
        group.creationTime = now;
        group.lastModificationTime = now;
        group.tags.putAll(readTags(request));
        groups.put(arn, group);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DatasetGroupArn", arn);
        return response;
    }

    public ObjectNode describeDatasetGroup(JsonNode request) {
        DatasetGroup group = requireGroup(requireText(request, "DatasetGroupArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DatasetGroupName", group.name);
        response.put("DatasetGroupArn", group.arn);
        ArrayNode arns = response.putArray("DatasetArns");
        for (String datasetArn : group.datasetArns) {
            arns.add(datasetArn);
        }
        response.put("Domain", group.domain);
        response.put("Status", group.status);
        response.put("CreationTime", group.creationTime);
        response.put("LastModificationTime", group.lastModificationTime);
        return response;
    }

    public ObjectNode updateDatasetGroup(JsonNode request) {
        DatasetGroup group = requireGroup(requireText(request, "DatasetGroupArn"));
        if (!request.has("DatasetArns") || !request.get("DatasetArns").isArray()) {
            throw invalid("DatasetArns is required.");
        }
        List<String> datasetArns = stringList(request.get("DatasetArns"));
        validateDatasetArns(datasetArns, group.domain);
        group.datasetArns.clear();
        group.datasetArns.addAll(datasetArns);
        group.lastModificationTime = nowSeconds();
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteDatasetGroup(JsonNode request) {
        String arn = requireText(request, "DatasetGroupArn");
        requireGroup(arn);
        groups.remove(arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDatasetGroups() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("DatasetGroups");
        for (DatasetGroup group : groups.values()) {
            ObjectNode summary = list.addObject();
            summary.put("DatasetGroupArn", group.arn);
            summary.put("DatasetGroupName", group.name);
            summary.put("CreationTime", group.creationTime);
            summary.put("LastModificationTime", group.lastModificationTime);
        }
        return response;
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        Map<String, String> tags = tagsFor(requireText(request, "ResourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        writeTags(response.putArray("Tags"), tags);
        return response;
    }

    public ObjectNode tagResource(JsonNode request) {
        Map<String, String> tags = tagsFor(requireText(request, "ResourceArn"));
        tags.putAll(readTags(request));
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        Map<String, String> tags = tagsFor(requireText(request, "ResourceArn"));
        for (String key : stringList(request.get("TagKeys"))) {
            tags.remove(key);
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode createDatasetImportJob(JsonNode request, String region) {
        String name = requireText(request, "DatasetImportJobName");
        validateName(name, "DatasetImportJobName");
        Dataset dataset = requireDataset(requireText(request, "DatasetArn"));
        requireObject(request, "DataSource");
        String arn = regionResolver.buildArn("forecast", region,
                "dataset-import-job/" + dataset.name + "/" + name);
        putNamed(importJobs, arn, name, dataset.arn, request);
        return arnResponse("DatasetImportJobArn", arn);
    }

    public ObjectNode describeDatasetImportJob(JsonNode request) {
        return describeNamed(requireNamed(importJobs, requireText(request, "DatasetImportJobArn")),
                "DatasetImportJobArn");
    }

    public ObjectNode createAutoPredictor(JsonNode request, String region) {
        String name = requireText(request, "PredictorName");
        validateName(name, "PredictorName");
        String parentArn = parentForPredictor(request);
        String arn = regionResolver.buildArn("forecast", region, "predictor/" + name);
        putNamed(predictors, arn, name, parentArn, request);
        return arnResponse("PredictorArn", arn);
    }

    public ObjectNode describeAutoPredictor(JsonNode request) {
        return describeNamed(requireNamed(predictors, requireText(request, "PredictorArn")),
                "PredictorArn");
    }

    public ObjectNode getAccuracyMetrics(JsonNode request) {
        requireNamed(predictors, requireText(request, "PredictorArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode results = response.putArray("PredictorEvaluationResults");
        ObjectNode result = results.addObject();
        result.put("AlgorithmArn", "arn:aws:forecast:::algorithm/CNN-QR");
        ArrayNode windows = result.putArray("TestWindows");
        ObjectNode window = windows.addObject();
        window.put("EvaluationType", "SUMMARY");
        window.put("ItemCount", 1);
        ObjectNode metrics = window.putObject("Metrics");
        metrics.put("RMSE", 0.1);
        metrics.put("AverageWeightedQuantileLoss", 0.05);
        ArrayNode errors = metrics.putArray("ErrorMetrics");
        ObjectNode error = errors.addObject();
        error.put("ForecastType", "0.5");
        error.put("WAPE", 0.1);
        error.put("RMSE", 0.1);
        error.put("MAPE", 0.1);
        error.put("MASE", 0.1);
        response.put("IsAutoPredictor", true);
        return response;
    }

    public ObjectNode createForecast(JsonNode request, String region) {
        String name = requireText(request, "ForecastName");
        validateName(name, "ForecastName");
        NamedResource predictor = requireNamed(predictors, requireText(request, "PredictorArn"));
        String arn = regionResolver.buildArn("forecast", region, "forecast/" + name);
        putNamed(forecasts, arn, name, predictor.arn, request);
        return arnResponse("ForecastArn", arn);
    }

    public ObjectNode describeForecast(JsonNode request) {
        return describeNamed(requireNamed(forecasts, requireText(request, "ForecastArn")),
                "ForecastArn");
    }

    public ObjectNode createForecastExportJob(JsonNode request, String region) {
        String name = requireText(request, "ForecastExportJobName");
        validateName(name, "ForecastExportJobName");
        NamedResource forecast = requireNamed(forecasts, requireText(request, "ForecastArn"));
        requireObject(request, "Destination");
        String arn = regionResolver.buildArn("forecast", region,
                "forecast-export-job/" + forecast.name + "/" + name);
        putNamed(forecastExports, arn, name, forecast.arn, request);
        return arnResponse("ForecastExportJobArn", arn);
    }

    public ObjectNode describeForecastExportJob(JsonNode request) {
        return describeNamed(requireNamed(forecastExports, requireText(request, "ForecastExportJobArn")),
                "ForecastExportJobArn");
    }

    public ObjectNode createWhatIfAnalysis(JsonNode request, String region) {
        String name = requireText(request, "WhatIfAnalysisName");
        validateName(name, "WhatIfAnalysisName");
        NamedResource forecast = requireNamed(forecasts, requireText(request, "ForecastArn"));
        String arn = regionResolver.buildArn("forecast", region, "what-if-analysis/" + name);
        putNamed(whatIfAnalyses, arn, name, forecast.arn, request);
        return arnResponse("WhatIfAnalysisArn", arn);
    }

    public ObjectNode describeWhatIfAnalysis(JsonNode request) {
        return describeNamed(requireNamed(whatIfAnalyses, requireText(request, "WhatIfAnalysisArn")),
                "WhatIfAnalysisArn");
    }

    public ObjectNode createWhatIfForecast(JsonNode request, String region) {
        String name = requireText(request, "WhatIfForecastName");
        validateName(name, "WhatIfForecastName");
        NamedResource analysis = requireNamed(whatIfAnalyses, requireText(request, "WhatIfAnalysisArn"));
        String arn = regionResolver.buildArn("forecast", region, "what-if-forecast/" + name);
        putNamed(whatIfForecasts, arn, name, analysis.arn, request);
        return arnResponse("WhatIfForecastArn", arn);
    }

    public ObjectNode describeWhatIfForecast(JsonNode request) {
        return describeNamed(requireNamed(whatIfForecasts, requireText(request, "WhatIfForecastArn")),
                "WhatIfForecastArn");
    }

    public ObjectNode createWhatIfForecastExport(JsonNode request, String region) {
        String name = requireText(request, "WhatIfForecastExportName");
        validateName(name, "WhatIfForecastExportName");
        List<String> forecastArns = stringList(request.get("WhatIfForecastArns"));
        if (forecastArns.isEmpty()) {
            throw invalid("WhatIfForecastArns is required.");
        }
        requireObject(request, "Destination");
        String parentArn = null;
        for (String forecastArn : forecastArns) {
            NamedResource whatIf = requireNamed(whatIfForecasts, forecastArn);
            if (parentArn == null) {
                parentArn = whatIf.arn;
            }
        }
        String arn = regionResolver.buildArn("forecast", region, "what-if-forecast-export/" + name);
        putNamed(whatIfExports, arn, name, parentArn, request);
        return arnResponse("WhatIfForecastExportArn", arn);
    }

    public ObjectNode describeWhatIfForecastExport(JsonNode request) {
        return describeNamed(requireNamed(whatIfExports, requireText(request, "WhatIfForecastExportArn")),
                "WhatIfForecastExportArn");
    }

    public ObjectNode stopResource(JsonNode request) {
        NamedResource resource = requireAnyNamed(requireText(request, "ResourceArn"));
        resource.status = "CREATE_STOPPED";
        resource.lastModificationTime = nowSeconds();
        return objectMapper.createObjectNode();
    }

    public ObjectNode resumeResource(JsonNode request) {
        NamedResource resource = requireAnyNamed(requireText(request, "ResourceArn"));
        resource.status = "ACTIVE";
        resource.lastModificationTime = nowSeconds();
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteResourceTree(JsonNode request) {
        String arn = requireText(request, "ResourceArn");
        if (datasets.containsKey(arn)) {
            removeChildren(importJobs, arn);
            datasets.remove(arn);
            return objectMapper.createObjectNode();
        }
        if (groups.containsKey(arn)) {
            removeChildren(predictors, arn);
            groups.remove(arn);
            return objectMapper.createObjectNode();
        }
        NamedResource resource = findNamed(arn);
        if (resource == null) {
            throw notFound(arn);
        }
        deleteNamedTree(arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode queryForecast(JsonNode request) {
        requireNamed(forecasts, requireText(request, "ForecastArn"));
        requireFilters(request);
        return stubPredictions();
    }

    public ObjectNode queryWhatIfForecast(JsonNode request) {
        requireNamed(whatIfForecasts, requireText(request, "WhatIfForecastArn"));
        requireFilters(request);
        return stubPredictions();
    }

    private Map<String, String> tagsFor(String arn) {
        Dataset dataset = datasets.get(arn);
        if (dataset != null) {
            return dataset.tags;
        }
        DatasetGroup group = groups.get(arn);
        if (group != null) {
            return group.tags;
        }
        NamedResource named = findNamed(arn);
        if (named != null) {
            return named.tags;
        }
        throw notFound(arn);
    }

    private Dataset requireDataset(String arn) {
        Dataset dataset = datasets.get(arn);
        if (dataset == null) {
            throw notFound(arn);
        }
        return dataset;
    }

    private DatasetGroup requireGroup(String arn) {
        DatasetGroup group = groups.get(arn);
        if (group == null) {
            throw notFound(arn);
        }
        return group;
    }

    private void validateDatasetArns(List<String> datasetArns, String domain) {
        for (String arn : datasetArns) {
            Dataset dataset = requireDataset(arn);
            if (!domain.equals(dataset.domain)) {
                throw invalid("Dataset " + arn + " has domain " + dataset.domain
                        + " which does not match dataset group domain " + domain + ".");
            }
        }
    }

    private static void validateName(String name, String field) {
        if (!NAME.matcher(name).matches()) {
            throw invalid(field + " must start with a letter and contain only letters, numbers, and underscores.");
        }
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode node = request == null ? null : request.get("Tags");
        if (node != null && node.isArray()) {
            for (JsonNode tag : node) {
                String key = textOrNull(tag, "Key");
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(""));
                }
            }
        }
        return tags;
    }

    private static void writeTags(ArrayNode list, Map<String, String> tags) {
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (!item.isNull()) {
                    values.add(item.asText());
                }
            }
        }
        return values;
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    private static AwsException alreadyExists(String message) {
        return new AwsException("ResourceAlreadyExistsException", message, 403);
    }

    private static AwsException inUse(String message) {
        return new AwsException("ResourceInUseException", message, 409);
    }

    private static AwsException notFound(String arn) {
        return new AwsException("ResourceNotFoundException",
                "No resource found with the arn " + arn, 404);
    }

    private String parentForPredictor(JsonNode request) {
        String reference = textOrNull(request, "ReferencePredictorArn");
        if (reference != null) {
            requireNamed(predictors, reference);
            return reference;
        }
        JsonNode dataConfig = request.get("DataConfig");
        if (dataConfig != null && dataConfig.isObject()) {
            String groupArn = textOrNull(dataConfig, "DatasetGroupArn");
            if (groupArn != null) {
                requireGroup(groupArn);
                return groupArn;
            }
        }
        throw invalid("DataConfig.DatasetGroupArn or ReferencePredictorArn is required.");
    }

    private void putNamed(ConcurrentHashMap<String, NamedResource> store, String arn, String name,
                          String parentArn, JsonNode request) {
        if (store.containsKey(arn)) {
            throw alreadyExists("A resource with the name " + name + " already exists.");
        }
        long now = nowSeconds();
        NamedResource resource = new NamedResource();
        resource.arn = arn;
        resource.name = name;
        resource.parentArn = parentArn;
        resource.status = "ACTIVE";
        resource.creationTime = now;
        resource.lastModificationTime = now;
        resource.request = request == null ? objectMapper.createObjectNode() : request.deepCopy();
        resource.tags.putAll(readTags(request));
        store.put(arn, resource);
    }

    private NamedResource requireNamed(ConcurrentHashMap<String, NamedResource> store, String arn) {
        NamedResource resource = store.get(arn);
        if (resource == null) {
            throw notFound(arn);
        }
        return resource;
    }

    private NamedResource requireAnyNamed(String arn) {
        NamedResource resource = findNamed(arn);
        if (resource == null) {
            throw notFound(arn);
        }
        return resource;
    }

    private NamedResource findNamed(String arn) {
        for (ConcurrentHashMap<String, NamedResource> store : namedStores()) {
            NamedResource resource = store.get(arn);
            if (resource != null) {
                return resource;
            }
        }
        return null;
    }

    private List<ConcurrentHashMap<String, NamedResource>> namedStores() {
        return List.of(importJobs, predictors, forecasts, forecastExports,
                whatIfAnalyses, whatIfForecasts, whatIfExports, monitors);
    }

    private void deleteNamedTree(String arn) {
        for (ConcurrentHashMap<String, NamedResource> store : namedStores()) {
            List<String> children = new ArrayList<>();
            for (NamedResource resource : store.values()) {
                if (arn.equals(resource.parentArn)) {
                    children.add(resource.arn);
                }
            }
            for (String child : children) {
                deleteNamedTree(child);
            }
        }
        for (ConcurrentHashMap<String, NamedResource> store : namedStores()) {
            store.remove(arn);
        }
    }

    private static void removeChildren(ConcurrentHashMap<String, NamedResource> store, String parentArn) {
        store.values().removeIf(resource -> parentArn.equals(resource.parentArn));
    }

    private ObjectNode describeNamed(NamedResource resource, String arnField) {
        ObjectNode response = objectMapper.createObjectNode();
        if (resource.request != null && resource.request.isObject()) {
            resource.request.fields().forEachRemaining(field -> {
                if (!"Tags".equals(field.getKey())) {
                    response.set(field.getKey(), field.getValue().deepCopy());
                }
            });
        }
        response.put(arnField, resource.arn);
        response.put("Status", resource.status);
        response.put("CreationTime", resource.creationTime);
        response.put("LastModificationTime", resource.lastModificationTime);
        return response;
    }

    private ObjectNode arnResponse(String field, String arn) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put(field, arn);
        return response;
    }

    private ObjectNode stubPredictions() {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode forecast = response.putObject("Forecast");
        ObjectNode predictions = forecast.putObject("Predictions");
        addSeries(predictions, "p10", 1.0);
        addSeries(predictions, "p50", 2.0);
        addSeries(predictions, "p90", 3.0);
        return response;
    }

    private void addSeries(ObjectNode predictions, String quantile, double value) {
        ArrayNode series = predictions.putArray(quantile);
        ObjectNode point = series.addObject();
        point.put("Timestamp", "2024-01-01T00:00:00Z");
        point.put("Value", value);
    }

    private static void requireFilters(JsonNode request) {
        JsonNode filters = request.get("Filters");
        if (filters == null || !filters.isObject() || filters.isEmpty()) {
            throw invalid("Filters is required.");
        }
    }

    private static void requireObject(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        if (value == null || !value.isObject()) {
            throw invalid(field + " is required.");
        }
    }
}
