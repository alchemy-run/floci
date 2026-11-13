package io.floci.aws.services.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static io.floci.aws.services.forecast.ForecastStore.exists;
import static io.floci.aws.services.forecast.ForecastStore.inUse;
import static io.floci.aws.services.forecast.ForecastStore.invalid;
import static io.floci.aws.services.forecast.ForecastStore.meta;
import static io.floci.aws.services.forecast.ForecastStore.notFound;
import static io.floci.aws.services.forecast.ForecastStore.optional;
import static io.floci.aws.services.forecast.ForecastStore.text;
import static io.floci.aws.services.forecast.ForecastStore.view;

/**
 * Amazon Forecast: datasets and dataset groups, import jobs that read CSV from S3, predictors that train on the
 * imported target time series and are backtested, forecasts and what-if forecasts served by the query API, their
 * exports to S3, explainability, monitors, and tags. Each job runs CREATE_PENDING, CREATE_IN_PROGRESS and ACTIVE
 * (or CREATE_FAILED with a message), a transition apart; its work is done when it is created.
 *
 * <p>Amazon Forecast is closed to new customers; floci treats its accounts as existing customers, and with
 * {@code existing-customer: false} refuses every create as AWS refuses a new customer.
 */
@ApplicationScoped
public class ForecastService {

    private static final IamPolicyEvaluator POLICIES = new IamPolicyEvaluator(new ObjectMapper());
    private static final AssumeRolePolicyEvaluator TRUST = new AssumeRolePolicyEvaluator(new ObjectMapper(), POLICIES);
    private static final List<String> DEFAULT_TYPES = List.of("0.1", "0.5", "0.9");
    private static final DateTimeFormatter QUERY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Map<String, String[]> DOMAIN_FIELDS = Map.of(
            "CUSTOM", new String[] {"item_id", "target_value"},
            "RETAIL", new String[] {"item_id", "demand"},
            "INVENTORY_PLANNING", new String[] {"item_id", "demand"},
            "EC2_CAPACITY", new String[] {"instance_type", "number_of_instances"},
            "WORK_FORCE", new String[] {"workforce_type", "workforce_demand"},
            "WEB_TRAFFIC", new String[] {"item_id", "value"},
            "METRICS", new String[] {"metric_name", "metric_value"});

    private final ForecastStore store;
    private final IamService iam;
    private final S3Service s3;

    @Inject
    public ForecastService(ForecastStore store, IamService iam, S3Service s3) {
        this.store = store;
        this.iam = iam;
        this.s3 = s3;
    }

    private void requireCustomer() {
        if (!store.existingCustomer()) {
            throw new AwsException("AccessDeniedException", "Amazon Forecast is no longer available to new "
                    + "customers. Existing customers of Amazon Forecast can continue to use the service as normal.",
                    400);
        }
    }

    // ---------------------------------------------------------------- datasets

    public synchronized ObjectNode createDataset(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "DatasetName");
        String resource = "dataset/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("A dataset with the name " + name + " already exists.");
        }
        String domain = text(input, "Domain");
        String type = text(input, "DatasetType");
        String frequency = optional(input, "DataFrequency");
        if (!"ITEM_METADATA".equals(type)) {
            if (frequency == null || !ForecastEngine.validFrequency(frequency)) {
                throw invalid("DataFrequency must be one of Y, M, W, D, H, 30min, 15min, 10min, 5min or 1min.");
            }
        }
        JsonNode attributes = input.path("Schema").path("Attributes");
        Set<String> names = new LinkedHashSet<>();
        attributes.forEach(attribute -> names.add(attribute.path("AttributeName").asText()));
        String[] fields = DOMAIN_FIELDS.get(domain);
        if (fields == null) {
            throw invalid("Domain " + domain + " is not valid.");
        }
        List<String> required = switch (type) {
            case "TARGET_TIME_SERIES" -> List.of(fields[0], "timestamp", fields[1]);
            case "RELATED_TIME_SERIES" -> List.of(fields[0], "timestamp");
            default -> List.of(fields[0]);
        };
        for (String field : required) {
            if (!names.contains(field)) {
                throw invalid("The schema of a " + domain + " " + type + " dataset must include " + field + ".");
            }
        }
        ObjectNode dataset = store.record(region, "dataset", resource, "DatasetArn");
        dataset.put("DatasetName", name).put("Domain", domain).put("DatasetType", type);
        if (frequency != null) {
            dataset.put("DataFrequency", frequency);
        }
        dataset.set("Schema", input.path("Schema").deepCopy());
        if (input.has("EncryptionConfig")) {
            dataset.set("EncryptionConfig", input.get("EncryptionConfig").deepCopy());
        }
        dataset.put("Status", "ACTIVE");
        meta(dataset).set("tags", tags(input.get("Tags")));
        store.save(region, dataset);
        return arnResponse("DatasetArn", dataset);
    }

    public synchronized ObjectNode describeDataset(JsonNode input, String region) {
        return view(require(region, text(input, "DatasetArn"), "dataset"));
    }

    public synchronized ObjectNode listDatasets(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode dataset : store.list(region, "dataset", record -> true)) {
            items.add(summary(dataset, "DatasetArn", "DatasetName", "DatasetType", "Domain"));
        }
        return store.page(items, input, "Datasets");
    }

    public synchronized ObjectNode deleteDataset(JsonNode input, String region) {
        ObjectNode dataset = require(region, text(input, "DatasetArn"), "dataset");
        String arn = dataset.path("DatasetArn").asText();
        for (ObjectNode job : store.list(region, "dataset-import-job", job -> arn.equals(job.path("DatasetArn")
                .asText()))) {
            if (job.path("Status").asText().startsWith("CREATE_") && !"CREATE_FAILED".equals(job.path("Status")
                    .asText()) && !"CREATE_STOPPED".equals(job.path("Status").asText())) {
                throw inUse("Dataset " + dataset.path("DatasetName").asText() + " has an import job in progress.");
            }
            store.delete(region, job);
        }
        store.chain(region, dataset, "DELETE_PENDING", "GONE");
        return store.mapper().createObjectNode();
    }

    // ---------------------------------------------------------------- dataset groups

    public synchronized ObjectNode createDatasetGroup(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "DatasetGroupName");
        String resource = "dataset-group/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("A dataset group with the name " + name + " already exists.");
        }
        String domain = text(input, "Domain");
        if (!DOMAIN_FIELDS.containsKey(domain)) {
            throw invalid("Domain " + domain + " is not valid.");
        }
        ObjectNode group = store.record(region, "dataset-group", resource, "DatasetGroupArn");
        group.put("DatasetGroupName", name);
        group.set("DatasetArns", groupDatasets(region, domain, input.path("DatasetArns")));
        group.put("Domain", domain).put("Status", "ACTIVE");
        meta(group).set("tags", tags(input.get("Tags")));
        store.save(region, group);
        return arnResponse("DatasetGroupArn", group);
    }

    public synchronized ObjectNode describeDatasetGroup(JsonNode input, String region) {
        return view(require(region, text(input, "DatasetGroupArn"), "dataset-group"));
    }

    public synchronized ObjectNode listDatasetGroups(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode group : store.list(region, "dataset-group", record -> true)) {
            items.add(summary(group, "DatasetGroupArn", "DatasetGroupName"));
        }
        return store.page(items, input, "DatasetGroups");
    }

    public synchronized ObjectNode updateDatasetGroup(JsonNode input, String region) {
        ObjectNode group = require(region, text(input, "DatasetGroupArn"), "dataset-group");
        group.set("DatasetArns", groupDatasets(region, group.path("Domain").asText(), input.path("DatasetArns")));
        group.put("Status", "ACTIVE");
        store.save(region, group);
        return store.mapper().createObjectNode();
    }

    public synchronized ObjectNode deleteDatasetGroup(JsonNode input, String region) {
        ObjectNode group = require(region, text(input, "DatasetGroupArn"), "dataset-group");
        store.chain(region, group, "DELETE_PENDING", "GONE");
        return store.mapper().createObjectNode();
    }

    /** A dataset group's datasets: ones that exist, of the group's domain, at most one of each type. */
    private ArrayNode groupDatasets(String region, String domain, JsonNode arns) {
        ArrayNode out = store.mapper().createArrayNode();
        Set<String> types = new LinkedHashSet<>();
        for (JsonNode arn : arns) {
            ObjectNode dataset = require(region, arn.asText(), "dataset");
            if (!domain.equals(dataset.path("Domain").asText())) {
                throw invalid("Dataset " + dataset.path("DatasetName").asText() + " is in the "
                        + dataset.path("Domain").asText() + " domain, not " + domain + ".");
            }
            if (!types.add(dataset.path("DatasetType").asText())) {
                throw invalid("A dataset group has at most one " + dataset.path("DatasetType").asText()
                        + " dataset.");
            }
            out.add(arn.asText());
        }
        return out;
    }

    // ---------------------------------------------------------------- import jobs

    /** Imports a dataset's data from CSV in S3, its columns in the schema's order; a FULL import replaces it. */
    public synchronized ObjectNode createDatasetImportJob(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "DatasetImportJobName");
        ObjectNode dataset = require(region, text(input, "DatasetArn"), "dataset");
        String resource = "dataset-import-job/" + dataset.path("DatasetName").asText() + "/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("An import job with the name " + name + " already exists.");
        }
        JsonNode s3Config = input.path("DataSource").path("S3Config");
        role(text(s3Config, "RoleArn"));
        String format = input.path("Format").asText("CSV");
        if (!"CSV".equals(format) && !"PARQUET".equals(format)) {
            throw invalid("Format must be CSV or PARQUET.");
        }
        ObjectNode job = store.record(region, "dataset-import-job", resource, "DatasetImportJobArn");
        String frequency = dataset.path("DataFrequency").asText("D");
        String timestampFormat = input.path("TimestampFormat").asText(Set.of("Y", "M", "W", "D").contains(frequency)
                ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm:ss");
        job.put("DatasetImportJobName", name).put("DatasetArn", dataset.path("DatasetArn").asText())
                .put("TimestampFormat", timestampFormat).put("Format", format)
                .put("ImportMode", input.path("ImportMode").asText("FULL"));
        for (String field : List.of("TimeZone", "UseGeolocationForTimeZone", "GeolocationFormat")) {
            if (input.has(field)) {
                job.set(field, input.get(field).deepCopy());
            }
        }
        job.set("DataSource", input.path("DataSource").deepCopy());
        meta(job).set("tags", tags(input.get("Tags")));
        String failure = null;
        try {
            if ("PARQUET".equals(format)) {
                throw new IllegalStateException("floci imports CSV data; PARQUET is not read.");
            }
            ArrayNode rows = readRows(text(s3Config, "Path"), dataset, timestampFormat, job);
            ObjectNode datasetMeta = meta(dataset);
            if ("INCREMENTAL".equals(job.path("ImportMode").asText()) && datasetMeta.has("rows")) {
                datasetMeta.withArray("/rows").addAll(rows);
            } else {
                datasetMeta.set("rows", rows);
            }
            datasetMeta.put("lastImport", job.path("DatasetImportJobArn").asText());
            store.save(region, dataset);
        } catch (IllegalStateException | IllegalArgumentException | AwsException e) {
            failure = e.getMessage();
        }
        finish(region, job, failure);
        return arnResponse("DatasetImportJobArn", job);
    }

    /** Reads every CSV object under a path into rows of the dataset's schema, with the import's statistics. */
    private ArrayNode readRows(String path, ObjectNode dataset, String timestampFormat, ObjectNode job) {
        if (!path.startsWith("s3://")) {
            throw new IllegalStateException("The path " + path + " is not an S3 path.");
        }
        String[] parts = path.substring(5).split("/", 2);
        String prefix = parts.length > 1 ? parts[1] : "";
        List<String> attributes = new ArrayList<>();
        List<String> types = new ArrayList<>();
        dataset.path("Schema").path("Attributes").forEach(attribute -> {
            attributes.add(attribute.path("AttributeName").asText());
            types.add(attribute.path("AttributeType").asText("string"));
        });
        List<S3Object> objects;
        try {
            objects = s3.listObjects(parts[0], prefix, null, 1000);
        } catch (AwsException e) {
            throw new IllegalStateException("Unable to read the S3 path " + path + ": " + e.getMessage());
        }
        ArrayNode rows = store.mapper().createArrayNode();
        long bytes = 0;
        Map<String, Set<String>> distinct = new TreeMap<>();
        Map<String, double[]> numeric = new TreeMap<>();
        Map<String, Integer> nulls = new TreeMap<>();
        for (S3Object object : objects) {
            if (object.getKey().endsWith("/")) {
                continue;
            }
            byte[] data = s3.getObject(parts[0], object.getKey()).getData();
            bytes += data == null ? 0 : data.length;
            String text = data == null ? "" : new String(data, StandardCharsets.UTF_8);
            for (String line : text.split("\\r?\\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.split(",", -1);
                if (values.length < attributes.size()) {
                    throw new IllegalStateException("A row has " + values.length + " columns, but the schema has "
                            + attributes.size() + ": " + line);
                }
                ObjectNode row = rows.addObject();
                for (int index = 0; index < attributes.size(); index++) {
                    String attribute = attributes.get(index);
                    String value = values[index].trim();
                    distinct.computeIfAbsent(attribute, key -> new LinkedHashSet<>()).add(value);
                    if (value.isEmpty()) {
                        nulls.merge(attribute, 1, Integer::sum);
                        continue;
                    }
                    switch (types.get(index)) {
                        case "timestamp" -> row.put(attribute, ForecastEngine.parse(value, timestampFormat)
                                .getEpochSecond());
                        case "float", "integer" -> {
                            double number;
                            try {
                                number = Double.parseDouble(value);
                            } catch (NumberFormatException e) {
                                throw new IllegalStateException("The value " + value + " of " + attribute
                                        + " is not a number.");
                            }
                            row.put(attribute, number);
                            double[] stats = numeric.computeIfAbsent(attribute, key -> new double[] {
                                Double.MAX_VALUE, -Double.MAX_VALUE, 0, 0, 0});
                            stats[0] = Math.min(stats[0], number);
                            stats[1] = Math.max(stats[1], number);
                            stats[2] += number;
                            stats[3] += number * number;
                            stats[4]++;
                        }
                        default -> row.put(attribute, value);
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("No data was found at " + path + ".");
        }
        ObjectNode statistics = job.putObject("FieldStatistics");
        for (String attribute : attributes) {
            ObjectNode stats = statistics.putObject(attribute);
            stats.put("Count", rows.size()).put("CountDistinct", distinct.getOrDefault(attribute, Set.of()).size())
                    .put("CountNull", nulls.getOrDefault(attribute, 0)).put("CountLong", rows.size())
                    .put("CountDistinctLong", distinct.getOrDefault(attribute, Set.of()).size())
                    .put("CountNullLong", nulls.getOrDefault(attribute, 0));
            double[] values = numeric.get(attribute);
            if (values != null && values[4] > 0) {
                double mean = values[2] / values[4];
                stats.put("Min", trim(values[0])).put("Max", trim(values[1])).put("Avg", mean)
                        .put("Stddev", Math.sqrt(Math.max(0, values[3] / values[4] - mean * mean))).put("CountNan", 0)
                        .put("CountNanLong", 0);
            }
        }
        job.put("DataSize", bytes / 1e9);
        return rows;
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    public synchronized ObjectNode describeDatasetImportJob(JsonNode input, String region) {
        return view(require(region, text(input, "DatasetImportJobArn"), "dataset-import-job"));
    }

    public synchronized ObjectNode listDatasetImportJobs(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode job : store.list(region, "dataset-import-job", record -> filtered(record, input))) {
            ObjectNode item = summary(job, "DatasetImportJobArn", "DatasetImportJobName", "DataSource", "Status",
                    "Message", "ImportMode");
            items.add(item);
        }
        return store.page(items, input, "DatasetImportJobs");
    }

    public synchronized ObjectNode deleteDatasetImportJob(JsonNode input, String region) {
        ObjectNode job = require(region, text(input, "DatasetImportJobArn"), "dataset-import-job");
        store.chain(region, job, "DELETE_PENDING", "GONE");
        return store.mapper().createObjectNode();
    }

    // ---------------------------------------------------------------- predictors

    public synchronized ObjectNode createAutoPredictor(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "PredictorName");
        ObjectNode reference = null;
        if (input.hasNonNull("ReferencePredictorArn")) {
            reference = require(region, input.get("ReferencePredictorArn").asText(), "predictor");
        }
        JsonNode dataConfig = reference != null ? reference.path("DataConfig") : input.path("DataConfig");
        int horizon = reference != null ? reference.path("ForecastHorizon").asInt()
                : input.path("ForecastHorizon").asInt(0);
        String frequency = reference != null ? reference.path("ForecastFrequency").asText()
                : text(input, "ForecastFrequency");
        ObjectNode predictor = newPredictor(region, name, text(dataConfig, "DatasetGroupArn"), horizon, frequency,
                reference != null ? reference.path("ForecastTypes") : input.path("ForecastTypes"),
                reference != null ? reference.path("ForecastDimensions") : input.path("ForecastDimensions"), true);
        predictor.set("DataConfig", dataConfig.deepCopy());
        predictor.put("OptimizationMetric", input.path("OptimizationMetric").asText(reference != null
                ? reference.path("OptimizationMetric").asText("AverageWeightedQuantileLoss")
                : "AverageWeightedQuantileLoss"));
        if (reference != null) {
            predictor.putObject("ReferencePredictorSummary").put("Arn", reference.path("PredictorArn").asText())
                    .put("State", "Active");
        }
        if (input.has("TimeAlignmentBoundary")) {
            predictor.set("TimeAlignmentBoundary", input.get("TimeAlignmentBoundary").deepCopy());
        }
        if (input.has("EncryptionConfig")) {
            predictor.set("EncryptionConfig", input.get("EncryptionConfig").deepCopy());
        }
        meta(predictor).set("tags", tags(input.get("Tags")));
        String failure = train(region, predictor);
        finish(region, predictor, failure);
        if (failure == null && input.path("ExplainPredictor").asBoolean(false)) {
            ObjectNode explain = store.mapper().createObjectNode();
            explain.put("ExplainabilityName", name + "_explainability")
                    .put("ResourceArn", predictor.path("PredictorArn").asText());
            explain.putObject("ExplainabilityConfig").put("TimeSeriesGranularity", "ALL")
                    .put("TimePointGranularity", "ALL");
            String arn = createExplainability(explain, region).path("ExplainabilityArn").asText();
            predictor.putObject("ExplainabilityInfo").put("ExplainabilityArn", arn).put("Status", "ACTIVE");
        }
        if (failure == null && input.has("MonitorConfig")) {
            ObjectNode monitor = store.mapper().createObjectNode();
            monitor.put("MonitorName", text(input.get("MonitorConfig"), "MonitorName"))
                    .put("ResourceArn", predictor.path("PredictorArn").asText());
            String arn = createMonitor(monitor, region).path("MonitorArn").asText();
            predictor.putObject("MonitorInfo").put("MonitorArn", arn).put("Status", "ACTIVE");
        }
        store.save(region, predictor);
        return arnResponse("PredictorArn", predictor);
    }

    public synchronized ObjectNode createPredictor(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "PredictorName");
        JsonNode featurization = input.path("FeaturizationConfig");
        ObjectNode predictor = newPredictor(region, name, text(input.path("InputDataConfig"), "DatasetGroupArn"),
                input.path("ForecastHorizon").asInt(0), text(featurization, "ForecastFrequency"),
                input.path("ForecastTypes"), featurization.path("ForecastDimensions"), false);
        boolean autoMl = input.path("PerformAutoML").asBoolean(false);
        String algorithm = optional(input, "AlgorithmArn");
        if (!autoMl && algorithm == null) {
            throw invalid("Give an AlgorithmArn or set PerformAutoML.");
        }
        predictor.put("AlgorithmArn", algorithm != null ? algorithm : "arn:aws:forecast:::algorithm/CNN-QR") // partition-literal: Forecast's algorithm ARNs are partition-less
                .put("PerformAutoML", autoMl).put("PerformHPO", input.path("PerformHPO").asBoolean(false));
        if (autoMl) {
            ArrayNode algorithms = predictor.putArray("AutoMLAlgorithmArns");
            for (String candidate : List.of("CNN-QR", "Deep_AR_Plus", "Prophet", "NPTS", "ARIMA", "ETS")) {
                algorithms.add("arn:aws:forecast:::algorithm/" + candidate); // partition-literal: algorithm ARNs
            }
            predictor.put("AutoMLOverrideStrategy", input.path("AutoMLOverrideStrategy").asText("AccuracyOptimized"));
        }
        for (String field : List.of("TrainingParameters", "EvaluationParameters", "HPOConfig", "InputDataConfig",
                "FeaturizationConfig", "EncryptionConfig")) {
            if (input.has(field)) {
                predictor.set(field, input.get(field).deepCopy());
            }
        }
        int windows = input.path("EvaluationParameters").path("NumberOfBacktestWindows").asInt(1);
        if (windows < 1 || windows > 5) {
            throw invalid("NumberOfBacktestWindows must be 1 to 5.");
        }
        predictor.put("OptimizationMetric", input.path("OptimizationMetric").asText("AverageWeightedQuantileLoss"));
        meta(predictor).set("tags", tags(input.get("Tags")));
        finish(region, predictor, train(region, predictor));
        return arnResponse("PredictorArn", predictor);
    }

    private ObjectNode newPredictor(String region, String name, String groupArn, int horizon, String frequency,
                                    JsonNode types, JsonNode dimensions, boolean auto) {
        String resource = "predictor/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("A predictor with the name " + name + " already exists.");
        }
        if (horizon < 1 || horizon > 500) {
            throw invalid("ForecastHorizon must be 1 to 500.");
        }
        if (!ForecastEngine.validFrequency(frequency)) {
            throw invalid("ForecastFrequency " + frequency + " is not valid.");
        }
        ObjectNode group = require(region, groupArn, "dataset-group");
        ObjectNode predictor = store.record(region, "predictor", resource, "PredictorArn");
        predictor.put("PredictorName", name).put("ForecastHorizon", horizon);
        ArrayNode forecastTypes = predictor.putArray("ForecastTypes");
        if (types == null || types.isMissingNode() || types.isEmpty()) {
            DEFAULT_TYPES.forEach(forecastTypes::add);
        } else {
            for (JsonNode type : types) {
                String value = type.asText();
                if (!"mean".equals(value)) {
                    double quantile;
                    try {
                        quantile = Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        throw invalid("ForecastTypes takes quantiles from 0.01 to 0.99, or mean.");
                    }
                    if (quantile < 0.01 || quantile > 0.99) {
                        throw invalid("ForecastTypes takes quantiles from 0.01 to 0.99, or mean.");
                    }
                }
                forecastTypes.add(value);
            }
        }
        predictor.put(auto ? "ForecastFrequency" : "DatasetGroupArn", auto ? frequency : groupArn);
        predictor.set("ForecastDimensions", dimensions == null || dimensions.isMissingNode()
                ? store.mapper().createArrayNode() : dimensions.deepCopy());
        meta(predictor).put("auto", auto).put("groupArn", group.path("DatasetGroupArn").asText())
                .put("frequency", frequency).put("domain", group.path("Domain").asText());
        return predictor;
    }

    /**
     * Trains a predictor on its dataset group's target time series: keeps the series and the backtest's accuracy;
     * returns why it failed, or null.
     */
    private String train(String region, ObjectNode predictor) {
        ObjectNode meta = meta(predictor);
        ObjectNode group = store.byArn(region, meta.path("groupArn").asText()).orElse(null);
        if (group == null) {
            return "The dataset group does not exist.";
        }
        ObjectNode target = null;
        for (JsonNode arn : group.path("DatasetArns")) {
            Optional<ObjectNode> dataset = store.byArn(region, arn.asText());
            if (dataset.isPresent() && "TARGET_TIME_SERIES".equals(dataset.get().path("DatasetType").asText())) {
                target = dataset.get();
            }
        }
        if (target == null || !meta(target).has("rows")) {
            throw invalid("The dataset group " + group.path("DatasetGroupName").asText() + " has no TARGET_TIME_SERIES"
                    + " dataset with imported data.");
        }
        ArrayNode imports = predictor.putArray("DatasetImportJobArns");
        imports.add(meta(target).path("lastImport").asText());
        String[] fields = DOMAIN_FIELDS.get(meta.path("domain").asText());
        List<String> keys = new ArrayList<>(List.of(fields[0]));
        predictor.path("ForecastDimensions").forEach(dimension -> keys.add(dimension.asText()));
        String frequency = meta.path("frequency").asText();
        Map<String, List<ForecastEngine.Point>> raw = new TreeMap<>();
        for (JsonNode row : meta(target).path("rows")) {
            if (!row.has("timestamp") || !row.has(fields[1])) {
                continue;
            }
            StringBuilder key = new StringBuilder();
            for (String field : keys) {
                key.append(key.length() == 0 ? "" : "|").append(row.path(field).asText(""));
            }
            raw.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(new ForecastEngine.Point(
                    Instant.ofEpochSecond(row.get("timestamp").asLong()), row.get(fields[1]).asDouble()));
        }
        int horizon = predictor.path("ForecastHorizon").asInt();
        List<String> types = new ArrayList<>();
        predictor.path("ForecastTypes").forEach(type -> types.add(type.asText()));
        ObjectNode series = meta.putObject("series");
        ForecastEngine.Accuracy accuracy = new ForecastEngine.Accuracy();
        for (Map.Entry<String, List<ForecastEngine.Point>> entry : raw.entrySet()) {
            List<ForecastEngine.Point> points = ForecastEngine.aggregate(entry.getValue(), frequency);
            ArrayNode out = series.putArray(entry.getKey());
            points.forEach(point -> out.addArray().add(point.time().getEpochSecond()).add(point.value()));
            if (points.size() > horizon) {
                List<ForecastEngine.Point> history = points.subList(0, points.size() - horizon);
                List<ForecastEngine.Point> holdout = points.subList(points.size() - horizon, points.size());
                accuracy.add(history, holdout, ForecastEngine.forecast(history, frequency, horizon, types), frequency);
            }
        }
        meta.set("keys", store.mapper().valueToTree(keys));
        meta.put("target", fields[1]);
        ObjectNode metrics = meta.putObject("metrics");
        metrics.put("RMSE", accuracy.rmse()).put("WAPE", accuracy.wape()).put("MAPE", accuracy.mape())
                .put("MASE", accuracy.mase()).put("items", accuracy.items);
        if (accuracy.start != null) {
            metrics.put("start", accuracy.start.getEpochSecond()).put("end", accuracy.end.getEpochSecond());
        }
        ObjectNode losses = metrics.putObject("losses");
        double total = 0;
        int quantiles = 0;
        for (String type : types) {
            if (!"mean".equals(type)) {
                double loss = accuracy.weightedQuantileLoss(type);
                losses.put(type, loss);
                total += loss;
                quantiles++;
            }
        }
        metrics.put("average", quantiles == 0 ? 0 : total / quantiles);
        return null;
    }

    public synchronized ObjectNode describeAutoPredictor(JsonNode input, String region) {
        ObjectNode predictor = require(region, text(input, "PredictorArn"), "predictor");
        if (!meta(predictor).path("auto").asBoolean(false)) {
            throw invalid("Predictor " + predictor.path("PredictorName").asText() + " is not an AutoPredictor; "
                    + "describe it with DescribePredictor.");
        }
        return view(predictor);
    }

    public synchronized ObjectNode describePredictor(JsonNode input, String region) {
        ObjectNode predictor = require(region, text(input, "PredictorArn"), "predictor");
        if (meta(predictor).path("auto").asBoolean(false)) {
            throw invalid("Predictor " + predictor.path("PredictorName").asText() + " is an AutoPredictor; describe "
                    + "it with DescribeAutoPredictor.");
        }
        ObjectNode response = view(predictor);
        response.put("IsAutoPredictor", false);
        return response;
    }

    public synchronized ObjectNode listPredictors(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode predictor : store.list(region, "predictor", record -> filtered(record, input))) {
            ObjectNode item = summary(predictor, "PredictorArn", "PredictorName", "ReferencePredictorSummary",
                    "Status", "Message");
            item.put("DatasetGroupArn", meta(predictor).path("groupArn").asText())
                    .put("IsAutoPredictor", meta(predictor).path("auto").asBoolean(false));
            items.add(item);
        }
        return store.page(items, input, "Predictors");
    }

    public synchronized ObjectNode getAccuracyMetrics(JsonNode input, String region) {
        ObjectNode predictor = require(region, text(input, "PredictorArn"), "predictor");
        if (!"ACTIVE".equals(predictor.path("Status").asText())) {
            throw inUse("Predictor " + predictor.path("PredictorName").asText() + " is "
                    + predictor.path("Status").asText() + ".");
        }
        JsonNode metrics = meta(predictor).path("metrics");
        ObjectNode response = store.mapper().createObjectNode();
        ObjectNode result = response.putArray("PredictorEvaluationResults").addObject();
        boolean auto = meta(predictor).path("auto").asBoolean(false);
        if (!auto) {
            result.put("AlgorithmArn", predictor.path("AlgorithmArn").asText());
        }
        ObjectNode window = result.putArray("TestWindows").addObject();
        if (metrics.has("start")) {
            window.put("TestWindowStart", metrics.path("start").asDouble()).put("TestWindowEnd",
                    metrics.path("end").asDouble());
        }
        window.put("ItemCount", metrics.path("items").asInt()).put("EvaluationType", "SUMMARY");
        ObjectNode values = window.putObject("Metrics");
        values.put("RMSE", metrics.path("RMSE").asDouble());
        ArrayNode losses = values.putArray("WeightedQuantileLosses");
        metrics.path("losses").fields().forEachRemaining(entry -> losses.addObject()
                .put("Quantile", Double.parseDouble(entry.getKey())).put("LossValue", entry.getValue().asDouble()));
        values.putArray("ErrorMetrics").addObject().put("ForecastType", "mean").put("WAPE", metrics.path("WAPE")
                .asDouble()).put("RMSE", metrics.path("RMSE").asDouble()).put("MASE", metrics.path("MASE").asDouble())
                .put("MAPE", metrics.path("MAPE").asDouble());
        values.put("AverageWeightedQuantileLoss", metrics.path("average").asDouble());
        response.put("IsAutoPredictor", auto).put("OptimizationMetric",
                predictor.path("OptimizationMetric").asText("AverageWeightedQuantileLoss"));
        if (predictor.has("AutoMLOverrideStrategy")) {
            response.put("AutoMLOverrideStrategy", predictor.path("AutoMLOverrideStrategy").asText());
        }
        return response;
    }

    public synchronized ObjectNode deletePredictor(JsonNode input, String region) {
        ObjectNode predictor = require(region, text(input, "PredictorArn"), "predictor");
        String arn = predictor.path("PredictorArn").asText();
        if (!store.list(region, "forecast", forecast -> arn.equals(forecast.path("PredictorArn").asText()))
                .isEmpty()) {
            throw inUse("Predictor " + predictor.path("PredictorName").asText() + " has forecasts; delete them first.");
        }
        store.chain(region, predictor, "DELETE_PENDING", "GONE");
        return store.mapper().createObjectNode();
    }

    public synchronized ObjectNode createPredictorBacktestExportJob(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "PredictorBacktestExportJobName");
        ObjectNode predictor = active(region, text(input, "PredictorArn"), "predictor");
        String resource = "predictor-backtest-export-job/" + predictor.path("PredictorName").asText() + "/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("A backtest export job with the name " + name + " already exists.");
        }
        JsonNode destination = input.path("Destination").path("S3Config");
        role(text(destination, "RoleArn"));
        ObjectNode job = store.record(region, "predictor-backtest-export-job", resource,
                "PredictorBacktestExportJobArn");
        job.put("PredictorBacktestExportJobName", name).put("PredictorArn", predictor.path("PredictorArn").asText())
                .put("Format", input.path("Format").asText("CSV"));
        job.set("Destination", input.path("Destination").deepCopy());
        meta(job).set("tags", tags(input.get("Tags")));
        String failure = null;
        try {
            List<List<String>> rows = new ArrayList<>();
            List<String> types = types(predictor);
            List<String> header = new ArrayList<>(keyNames(predictor));
            header.addAll(List.of("timestamp", meta(predictor).path("target").asText(), "backtestwindow_start_time"));
            types.forEach(type -> header.add(column(type)));
            rows.add(header);
            int horizon = predictor.path("ForecastHorizon").asInt();
            String frequency = meta(predictor).path("frequency").asText();
            meta(predictor).path("series").fields().forEachRemaining(entry -> {
                List<ForecastEngine.Point> points = points(entry.getValue());
                if (points.size() <= horizon) {
                    return;
                }
                List<ForecastEngine.Point> history = points.subList(0, points.size() - horizon);
                ForecastEngine.Prediction prediction = ForecastEngine.forecast(history, frequency, horizon, types);
                for (int i = 0; i < horizon; i++) {
                    List<String> row = new ArrayList<>(List.of(entry.getKey().split("\\|", -1)));
                    ForecastEngine.Point actual = points.get(points.size() - horizon + i);
                    row.add(QUERY_TIME.format(actual.time()));
                    row.add(Double.toString(actual.value()));
                    row.add(QUERY_TIME.format(points.get(points.size() - horizon).time()));
                    for (String type : types) {
                        row.add(Double.toString(prediction.values().get(type).get(i)));
                    }
                    rows.add(row);
                }
            });
            writeCsv(text(destination, "Path"), name, rows);
        } catch (IllegalStateException | AwsException e) {
            failure = e.getMessage();
        }
        finish(region, job, failure);
        return arnResponse("PredictorBacktestExportJobArn", job);
    }

    public synchronized ObjectNode describePredictorBacktestExportJob(JsonNode input, String region) {
        return view(require(region, text(input, "PredictorBacktestExportJobArn"), "predictor-backtest-export-job"));
    }

    public synchronized ObjectNode listPredictorBacktestExportJobs(JsonNode input, String region) {
        return listOf(region, "predictor-backtest-export-job", input, "PredictorBacktestExportJobs",
                "PredictorBacktestExportJobArn", "PredictorBacktestExportJobName", "Destination", "Status", "Message");
    }

    public synchronized ObjectNode deletePredictorBacktestExportJob(JsonNode input, String region) {
        return deleteOf(region, text(input, "PredictorBacktestExportJobArn"), "predictor-backtest-export-job");
    }

    // ---------------------------------------------------------------- forecasts

    public synchronized ObjectNode createForecast(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "ForecastName");
        ObjectNode predictor = active(region, text(input, "PredictorArn"), "predictor");
        String resource = "forecast/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("A forecast with the name " + name + " already exists.");
        }
        List<String> types = new ArrayList<>();
        if (input.has("ForecastTypes") && !input.get("ForecastTypes").isEmpty()) {
            input.get("ForecastTypes").forEach(type -> types.add(type.asText()));
            for (String type : types) {
                if (!types(predictor).contains(type)) {
                    throw invalid("Forecast type " + type + " is not one the predictor was trained for.");
                }
            }
        } else {
            types.addAll(types(predictor));
        }
        ObjectNode forecast = store.record(region, "forecast", resource, "ForecastArn");
        forecast.put("ForecastName", name);
        forecast.set("ForecastTypes", store.mapper().valueToTree(types));
        forecast.put("PredictorArn", predictor.path("PredictorArn").asText())
                .put("DatasetGroupArn", meta(predictor).path("groupArn").asText());
        Set<String> selected = null;
        if (input.has("TimeSeriesSelector")) {
            forecast.set("TimeSeriesSelector", input.get("TimeSeriesSelector").deepCopy());
            selected = selectedSeries(input.path("TimeSeriesSelector").path("TimeSeriesIdentifiers"));
        }
        meta(forecast).set("tags", tags(input.get("Tags")));
        meta(forecast).set("predictions", predictions(predictor, types, selected));
        meta(forecast).set("keys", meta(predictor).path("keys").deepCopy());
        finish(region, forecast, null);
        return arnResponse("ForecastArn", forecast);
    }

    /** A predictor's forecast of each of its series, or of the series selected. */
    private ObjectNode predictions(ObjectNode predictor, List<String> types, Set<String> selected) {
        ObjectNode out = store.mapper().createObjectNode();
        int horizon = predictor.path("ForecastHorizon").asInt();
        String frequency = meta(predictor).path("frequency").asText();
        meta(predictor).path("series").fields().forEachRemaining(entry -> {
            if (selected != null && !selected.contains(entry.getKey())) {
                return;
            }
            List<ForecastEngine.Point> points = points(entry.getValue());
            if (points.isEmpty()) {
                return;
            }
            ForecastEngine.Prediction prediction = ForecastEngine.forecast(points, frequency, horizon, types);
            ObjectNode series = out.putObject(entry.getKey());
            ArrayNode times = series.putArray("times");
            prediction.times().forEach(time -> times.add(time.getEpochSecond()));
            ObjectNode values = series.putObject("values");
            prediction.values().forEach((type, list) -> {
                ArrayNode array = values.putArray(type);
                list.forEach(array::add);
            });
        });
        return out;
    }

    /** The series a time series selector names: the item identifiers in its CSV in S3. */
    private Set<String> selectedSeries(JsonNode identifiers) {
        Set<String> selected = new LinkedHashSet<>();
        String path = identifiers.path("DataSource").path("S3Config").path("Path").asText(null);
        if (path == null || !path.startsWith("s3://")) {
            return null;
        }
        String[] parts = path.substring(5).split("/", 2);
        for (S3Object object : s3.listObjects(parts[0], parts.length > 1 ? parts[1] : "", null, 1000)) {
            byte[] data = s3.getObject(parts[0], object.getKey()).getData();
            for (String line : new String(data == null ? new byte[0] : data, StandardCharsets.UTF_8).split("\\r?\\n")) {
                if (!line.isBlank()) {
                    selected.add(String.join("|", line.trim().split(",", -1)));
                }
            }
        }
        return selected;
    }

    public synchronized ObjectNode describeForecast(JsonNode input, String region) {
        ObjectNode forecast = require(region, text(input, "ForecastArn"), "forecast");
        return view(forecast);
    }

    public synchronized ObjectNode listForecasts(JsonNode input, String region) {
        return listOf(region, "forecast", input, "Forecasts", "ForecastArn", "ForecastName", "PredictorArn",
                "DatasetGroupArn", "Status", "Message");
    }

    public synchronized ObjectNode deleteForecast(JsonNode input, String region) {
        ObjectNode forecast = require(region, text(input, "ForecastArn"), "forecast");
        String arn = forecast.path("ForecastArn").asText();
        if (!store.list(region, "what-if-analysis", analysis -> arn.equals(analysis.path("ForecastArn").asText()))
                .isEmpty()) {
            throw inUse("Forecast " + forecast.path("ForecastName").asText() + " has what-if analyses.");
        }
        store.chain(region, forecast, "DELETE_PENDING", "GONE");
        return store.mapper().createObjectNode();
    }

    public synchronized ObjectNode queryForecast(JsonNode input, String region) {
        return query(region, text(input, "ForecastArn"), "forecast", input);
    }

    public synchronized ObjectNode queryWhatIfForecast(JsonNode input, String region) {
        return query(region, text(input, "WhatIfForecastArn"), "what-if-forecast", input);
    }

    /** The predictions of an ACTIVE forecast or what-if forecast for the series its filters name, between dates. */
    ObjectNode query(String region, String arn, String kind, JsonNode input) {
        ObjectNode forecast = require(region, arn, kind);
        if (!"ACTIVE".equals(forecast.path("Status").asText())) {
            throw inUse("The forecast is " + forecast.path("Status").asText() + ".");
        }
        List<String> keys = new ArrayList<>();
        meta(forecast).path("keys").forEach(key -> keys.add(key.asText()));
        JsonNode filters = input.path("Filters");
        StringBuilder key = new StringBuilder();
        for (String field : keys) {
            if (!filters.hasNonNull(field)) {
                throw invalid("Filters must give " + String.join(", ", keys) + ".");
            }
            key.append(key.length() == 0 ? "" : "|").append(filters.get(field).asText());
        }
        JsonNode series = meta(forecast).path("predictions").path(key.toString());
        if (series.isMissingNode()) {
            throw invalid("The filters match no time series of the forecast.");
        }
        Instant start = input.hasNonNull("StartDate") ? Instant.parse(input.get("StartDate").asText() + "Z") : null;
        Instant end = input.hasNonNull("EndDate") ? Instant.parse(input.get("EndDate").asText() + "Z") : null;
        ObjectNode response = store.mapper().createObjectNode();
        ObjectNode predictions = response.putObject("Forecast").putObject("Predictions");
        series.path("values").fields().forEachRemaining(entry -> {
            ArrayNode out = predictions.putArray(column(entry.getKey()));
            for (int i = 0; i < series.path("times").size(); i++) {
                Instant time = Instant.ofEpochSecond(series.path("times").get(i).asLong());
                if ((start == null || !time.isBefore(start)) && (end == null || !time.isAfter(end))) {
                    out.addObject().put("Timestamp", QUERY_TIME.format(time)).put("Value",
                            entry.getValue().get(i).asDouble());
                }
            }
        });
        return response;
    }

    public synchronized ObjectNode createForecastExportJob(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "ForecastExportJobName");
        ObjectNode forecast = active(region, text(input, "ForecastArn"), "forecast");
        String resource = "forecast-export-job/" + forecast.path("ForecastName").asText() + "/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("A forecast export job with the name " + name + " already exists.");
        }
        JsonNode destination = input.path("Destination").path("S3Config");
        role(text(destination, "RoleArn"));
        ObjectNode job = store.record(region, "forecast-export-job", resource, "ForecastExportJobArn");
        job.put("ForecastExportJobName", name).put("ForecastArn", forecast.path("ForecastArn").asText())
                .put("Format", input.path("Format").asText("CSV"));
        job.set("Destination", input.path("Destination").deepCopy());
        meta(job).set("tags", tags(input.get("Tags")));
        finish(region, job, export(text(destination, "Path"), name, List.of(forecast)));
        return arnResponse("ForecastExportJobArn", job);
    }

    /** Writes forecasts' predictions as CSV; returns why it failed, or null. */
    private String export(String path, String name, List<ObjectNode> forecasts) {
        try {
            List<String> keys = new ArrayList<>();
            meta(forecasts.get(0)).path("keys").forEach(key -> keys.add(key.asText()));
            List<String> types = new ArrayList<>();
            forecasts.get(0).path("ForecastTypes").forEach(type -> types.add(type.asText()));
            List<List<String>> rows = new ArrayList<>();
            List<String> header = new ArrayList<>(keys);
            header.add("date");
            types.forEach(type -> header.add(column(type)));
            rows.add(header);
            for (ObjectNode forecast : forecasts) {
                meta(forecast).path("predictions").fields().forEachRemaining(entry -> {
                    JsonNode times = entry.getValue().path("times");
                    for (int i = 0; i < times.size(); i++) {
                        List<String> row = new ArrayList<>(List.of(entry.getKey().split("\\|", -1)));
                        row.add(QUERY_TIME.format(Instant.ofEpochSecond(times.get(i).asLong())) + "Z");
                        for (String type : types) {
                            row.add(Double.toString(entry.getValue().path("values").path(type).get(i).asDouble()));
                        }
                        rows.add(row);
                    }
                });
            }
            writeCsv(path, name, rows);
            return null;
        } catch (IllegalStateException | AwsException e) {
            return e.getMessage();
        }
    }

    public synchronized ObjectNode describeForecastExportJob(JsonNode input, String region) {
        return view(require(region, text(input, "ForecastExportJobArn"), "forecast-export-job"));
    }

    public synchronized ObjectNode listForecastExportJobs(JsonNode input, String region) {
        return listOf(region, "forecast-export-job", input, "ForecastExportJobs", "ForecastExportJobArn",
                "ForecastExportJobName", "Destination", "Status", "Message");
    }

    public synchronized ObjectNode deleteForecastExportJob(JsonNode input, String region) {
        return deleteOf(region, text(input, "ForecastExportJobArn"), "forecast-export-job");
    }

    // ---------------------------------------------------------------- explainability

    /**
     * Explains a predictor or forecast: the impact of each related time series and item metadata attribute. floci's
     * predictor forecasts from the target alone, so every attribute's impact is zero.
     */
    public synchronized ObjectNode createExplainability(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "ExplainabilityName");
        String resource = "explainability/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("An explainability with the name " + name + " already exists.");
        }
        String target = text(input, "ResourceArn");
        String kind = target.contains(":forecast/") ? "forecast" : "predictor";
        active(region, target, kind);
        JsonNode config = input.path("ExplainabilityConfig");
        if ("predictor".equals(kind) && (!"ALL".equals(config.path("TimeSeriesGranularity").asText())
                || !"ALL".equals(config.path("TimePointGranularity").asText()))) {
            throw invalid("A predictor's explainability takes TimeSeriesGranularity and TimePointGranularity ALL.");
        }
        ObjectNode explainability = store.record(region, "explainability", resource, "ExplainabilityArn");
        explainability.put("ExplainabilityName", name).put("ResourceArn", target)
                .put("EnableVisualization", input.path("EnableVisualization").asBoolean(false));
        for (String field : List.of("ExplainabilityConfig", "DataSource", "Schema", "StartDateTime", "EndDateTime")) {
            if (input.has(field)) {
                explainability.set(field, input.get(field).deepCopy());
            }
        }
        meta(explainability).set("tags", tags(input.get("Tags")));
        finish(region, explainability, null);
        return arnResponse("ExplainabilityArn", explainability);
    }

    public synchronized ObjectNode describeExplainability(JsonNode input, String region) {
        return view(require(region, text(input, "ExplainabilityArn"), "explainability"));
    }

    public synchronized ObjectNode listExplainabilities(JsonNode input, String region) {
        return listOf(region, "explainability", input, "Explainabilities", "ExplainabilityArn", "ExplainabilityName",
                "ResourceArn", "ExplainabilityConfig", "Status", "Message");
    }

    public synchronized ObjectNode deleteExplainability(JsonNode input, String region) {
        return deleteOf(region, text(input, "ExplainabilityArn"), "explainability");
    }

    public synchronized ObjectNode createExplainabilityExport(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "ExplainabilityExportName");
        ObjectNode explainability = active(region, text(input, "ExplainabilityArn"), "explainability");
        String resource = "explainability-export/" + explainability.path("ExplainabilityName").asText() + "/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("An explainability export with the name " + name + " already exists.");
        }
        JsonNode destination = input.path("Destination").path("S3Config");
        role(text(destination, "RoleArn"));
        ObjectNode export = store.record(region, "explainability-export", resource, "ExplainabilityExportArn");
        export.put("ExplainabilityExportName", name).put("ExplainabilityArn",
                explainability.path("ExplainabilityArn").asText()).put("Format", input.path("Format").asText("CSV"));
        export.set("Destination", input.path("Destination").deepCopy());
        meta(export).set("tags", tags(input.get("Tags")));
        String failure = null;
        try {
            writeCsv(text(destination, "Path"), name, List.of(List.of("attribute_name", "impact_score")));
        } catch (IllegalStateException | AwsException e) {
            failure = e.getMessage();
        }
        finish(region, export, failure);
        return arnResponse("ExplainabilityExportArn", export);
    }

    public synchronized ObjectNode describeExplainabilityExport(JsonNode input, String region) {
        return view(require(region, text(input, "ExplainabilityExportArn"), "explainability-export"));
    }

    public synchronized ObjectNode listExplainabilityExports(JsonNode input, String region) {
        return listOf(region, "explainability-export", input, "ExplainabilityExports", "ExplainabilityExportArn",
                "ExplainabilityExportName", "Destination", "Status", "Message");
    }

    public synchronized ObjectNode deleteExplainabilityExport(JsonNode input, String region) {
        return deleteOf(region, text(input, "ExplainabilityExportArn"), "explainability-export");
    }

    // ---------------------------------------------------------------- monitors

    /** Monitors an AutoPredictor, its baseline the predictor's backtest accuracy. */
    public synchronized ObjectNode createMonitor(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "MonitorName");
        String resource = "monitor/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("A monitor with the name " + name + " already exists.");
        }
        ObjectNode predictor = active(region, text(input, "ResourceArn"), "predictor");
        if (!meta(predictor).path("auto").asBoolean(false)) {
            throw invalid("Only an AutoPredictor can be monitored.");
        }
        ObjectNode monitor = store.record(region, "monitor", resource, "MonitorArn");
        monitor.put("MonitorName", name).put("ResourceArn", predictor.path("PredictorArn").asText());
        ArrayNode baseline = monitor.putObject("Baseline").putObject("PredictorBaseline").putArray("BaselineMetrics");
        JsonNode metrics = meta(predictor).path("metrics");
        for (String metric : List.of("WAPE", "RMSE", "MAPE", "MASE")) {
            baseline.addObject().put("Name", metric).put("Value", metrics.path(metric).asDouble());
        }
        baseline.addObject().put("Name", "AverageWeightedQuantileLoss").put("Value", metrics.path("average")
                .asDouble());
        meta(monitor).set("tags", tags(input.get("Tags")));
        finish(region, monitor, null);
        return arnResponse("MonitorArn", monitor);
    }

    public synchronized ObjectNode describeMonitor(JsonNode input, String region) {
        return view(require(region, text(input, "MonitorArn"), "monitor"));
    }

    public synchronized ObjectNode listMonitors(JsonNode input, String region) {
        return listOf(region, "monitor", input, "Monitors", "MonitorArn", "MonitorName", "ResourceArn", "Status");
    }

    /** A monitor's evaluations: one per import into its predictor's dataset group after the monitor began. */
    public synchronized ObjectNode listMonitorEvaluations(JsonNode input, String region) {
        ObjectNode monitor = require(region, text(input, "MonitorArn"), "monitor");
        List<ObjectNode> items = new ArrayList<>();
        meta(monitor).path("evaluations").forEach(evaluation -> items.add(((ObjectNode) evaluation).deepCopy()));
        return store.page(items, input, "PredictorMonitorEvaluations");
    }

    public synchronized ObjectNode deleteMonitor(JsonNode input, String region) {
        return deleteOf(region, text(input, "MonitorArn"), "monitor");
    }

    // ---------------------------------------------------------------- what-if

    public synchronized ObjectNode createWhatIfAnalysis(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "WhatIfAnalysisName");
        ObjectNode forecast = active(region, text(input, "ForecastArn"), "forecast");
        String resource = "what-if-analysis/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("A what-if analysis with the name " + name + " already exists.");
        }
        ObjectNode analysis = store.record(region, "what-if-analysis", resource, "WhatIfAnalysisArn");
        analysis.put("WhatIfAnalysisName", name).put("ForecastArn", forecast.path("ForecastArn").asText());
        if (input.has("TimeSeriesSelector")) {
            analysis.set("TimeSeriesSelector", input.get("TimeSeriesSelector").deepCopy());
        }
        meta(analysis).set("tags", tags(input.get("Tags")));
        finish(region, analysis, null);
        return arnResponse("WhatIfAnalysisArn", analysis);
    }

    public synchronized ObjectNode describeWhatIfAnalysis(JsonNode input, String region) {
        return view(require(region, text(input, "WhatIfAnalysisArn"), "what-if-analysis"));
    }

    public synchronized ObjectNode listWhatIfAnalyses(JsonNode input, String region) {
        return listOf(region, "what-if-analysis", input, "WhatIfAnalyses", "WhatIfAnalysisArn", "WhatIfAnalysisName",
                "ForecastArn", "Status", "Message");
    }

    public synchronized ObjectNode deleteWhatIfAnalysis(JsonNode input, String region) {
        ObjectNode analysis = require(region, text(input, "WhatIfAnalysisArn"), "what-if-analysis");
        String arn = analysis.path("WhatIfAnalysisArn").asText();
        if (!store.list(region, "what-if-forecast", forecast -> arn.equals(forecast.path("WhatIfAnalysisArn")
                .asText())).isEmpty()) {
            throw inUse("What-if analysis " + analysis.path("WhatIfAnalysisName").asText() + " has what-if forecasts.");
        }
        store.chain(region, analysis, "DELETE_PENDING", "GONE");
        return store.mapper().createObjectNode();
    }

    /**
     * Forecasts a scenario: the analysis's baseline forecast with its transformations of the target applied
     * (ADD, SUBTRACT, MULTIPLY, DIVIDE where the conditions on the item hold). floci's predictor forecasts from the
     * target alone, so transformations of related time series and replacement data do not change it.
     */
    public synchronized ObjectNode createWhatIfForecast(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "WhatIfForecastName");
        ObjectNode analysis = active(region, text(input, "WhatIfAnalysisArn"), "what-if-analysis");
        String resource = "what-if-forecast/" + analysis.path("WhatIfAnalysisName").asText() + "/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("A what-if forecast with the name " + name + " already exists.");
        }
        ObjectNode baseline = require(region, analysis.path("ForecastArn").asText(), "forecast");
        ObjectNode forecast = store.record(region, "what-if-forecast", resource, "WhatIfForecastArn");
        forecast.put("WhatIfForecastName", name).put("WhatIfAnalysisArn", analysis.path("WhatIfAnalysisArn").asText());
        forecast.set("ForecastTypes", baseline.path("ForecastTypes").deepCopy());
        for (String field : List.of("TimeSeriesTransformations", "TimeSeriesReplacementsDataSource")) {
            if (input.has(field)) {
                forecast.set(field, input.get(field).deepCopy());
            }
        }
        ObjectNode predictions = meta(baseline).path("predictions").deepCopy();
        List<String> keys = new ArrayList<>();
        meta(baseline).path("keys").forEach(key -> keys.add(key.asText()));
        String target = Optional.ofNullable(store.byArn(region, baseline.path("PredictorArn").asText()).orElse(null))
                .map(predictor -> meta(predictor).path("target").asText()).orElse("target_value");
        for (JsonNode transformation : input.path("TimeSeriesTransformations")) {
            JsonNode action = transformation.path("Action");
            if (!target.equals(action.path("AttributeName").asText())) {
                continue;
            }
            double value = action.path("Value").asDouble();
            predictions.fields().forEachRemaining(entry -> {
                if (!conditionsHold(transformation.path("TimeSeriesConditions"), keys, entry.getKey())) {
                    return;
                }
                entry.getValue().path("values").fields().forEachRemaining(type -> {
                    ArrayNode values = (ArrayNode) type.getValue();
                    for (int i = 0; i < values.size(); i++) {
                        double current = values.get(i).asDouble();
                        values.set(i, store.mapper().getNodeFactory().numberNode(switch (action.path("Operation")
                                .asText()) {
                            case "ADD" -> current + value;
                            case "SUBTRACT" -> current - value;
                            case "MULTIPLY" -> current * value;
                            case "DIVIDE" -> value == 0 ? current : current / value;
                            default -> current;
                        }));
                    }
                });
            });
        }
        meta(forecast).set("predictions", predictions);
        meta(forecast).set("keys", meta(baseline).path("keys").deepCopy());
        meta(forecast).set("tags", tags(input.get("Tags")));
        finish(region, forecast, null);
        return arnResponse("WhatIfForecastArn", forecast);
    }

    private static boolean conditionsHold(JsonNode conditions, List<String> keys, String series) {
        String[] values = series.split("\\|", -1);
        for (JsonNode condition : conditions) {
            int index = keys.indexOf(condition.path("AttributeName").asText());
            if (index < 0) {
                continue;
            }
            String actual = values[index];
            String expected = condition.path("AttributeValue").asText();
            boolean holds = switch (condition.path("Condition").asText()) {
                case "EQUALS" -> actual.equals(expected);
                case "NOT_EQUALS" -> !actual.equals(expected);
                case "LESS_THAN" -> actual.compareTo(expected) < 0;
                case "GREATER_THAN" -> actual.compareTo(expected) > 0;
                default -> true;
            };
            if (!holds) {
                return false;
            }
        }
        return true;
    }

    public synchronized ObjectNode describeWhatIfForecast(JsonNode input, String region) {
        return view(require(region, text(input, "WhatIfForecastArn"), "what-if-forecast"));
    }

    public synchronized ObjectNode listWhatIfForecasts(JsonNode input, String region) {
        return listOf(region, "what-if-forecast", input, "WhatIfForecasts", "WhatIfForecastArn", "WhatIfForecastName",
                "WhatIfAnalysisArn", "Status", "Message");
    }

    public synchronized ObjectNode deleteWhatIfForecast(JsonNode input, String region) {
        return deleteOf(region, text(input, "WhatIfForecastArn"), "what-if-forecast");
    }

    public synchronized ObjectNode createWhatIfForecastExport(JsonNode input, String region) {
        requireCustomer();
        String name = text(input, "WhatIfForecastExportName");
        String resource = "what-if-forecast-export/" + name;
        if (store.get(region, resource).isPresent()) {
            throw exists("A what-if forecast export with the name " + name + " already exists.");
        }
        List<ObjectNode> forecasts = new ArrayList<>();
        for (JsonNode arn : input.path("WhatIfForecastArns")) {
            forecasts.add(active(region, arn.asText(), "what-if-forecast"));
        }
        if (forecasts.isEmpty()) {
            throw invalid("WhatIfForecastArns is required.");
        }
        JsonNode destination = input.path("Destination").path("S3Config");
        role(text(destination, "RoleArn"));
        ObjectNode export = store.record(region, "what-if-forecast-export", resource, "WhatIfForecastExportArn");
        export.put("WhatIfForecastExportName", name).put("Format", input.path("Format").asText("CSV"));
        export.set("WhatIfForecastArns", input.path("WhatIfForecastArns").deepCopy());
        export.set("Destination", input.path("Destination").deepCopy());
        meta(export).set("tags", tags(input.get("Tags")));
        finish(region, export, export(text(destination, "Path"), name, forecasts));
        return arnResponse("WhatIfForecastExportArn", export);
    }

    public synchronized ObjectNode describeWhatIfForecastExport(JsonNode input, String region) {
        return view(require(region, text(input, "WhatIfForecastExportArn"), "what-if-forecast-export"));
    }

    public synchronized ObjectNode listWhatIfForecastExports(JsonNode input, String region) {
        return listOf(region, "what-if-forecast-export", input, "WhatIfForecastExports", "WhatIfForecastExportArn",
                "WhatIfForecastExportName", "WhatIfForecastArns", "Destination", "Status", "Message");
    }

    public synchronized ObjectNode deleteWhatIfForecastExport(JsonNode input, String region) {
        return deleteOf(region, text(input, "WhatIfForecastExportArn"), "what-if-forecast-export");
    }

    // ---------------------------------------------------------------- resource operations

    /** Stops a job still being created, or an active monitor. */
    public synchronized ObjectNode stopResource(JsonNode input, String region) {
        String arn = text(input, "ResourceArn");
        ObjectNode record = store.byArn(region, arn).orElseThrow(() -> notFound("The resource " + arn
                + " does not exist."));
        String status = record.path("Status").asText();
        if ("monitor".equals(meta(record).path("kind").asText())) {
            if (!"ACTIVE".equals(status)) {
                throw invalid("Monitor " + arn + " is " + status + ".");
            }
            store.chain(region, record, "ACTIVE_STOPPING", "ACTIVE_STOPPED");
        } else if ("CREATE_PENDING".equals(status) || "CREATE_IN_PROGRESS".equals(status)) {
            store.chain(region, record, "CREATE_STOPPING", "CREATE_STOPPED");
        } else {
            throw invalid("The resource " + arn + " is " + status + " and cannot be stopped.");
        }
        return store.mapper().createObjectNode();
    }

    public synchronized ObjectNode resumeResource(JsonNode input, String region) {
        String arn = text(input, "ResourceArn");
        ObjectNode record = store.byArn(region, arn).orElseThrow(() -> notFound("The resource " + arn
                + " does not exist."));
        if (!"monitor".equals(meta(record).path("kind").asText())
                || !"ACTIVE_STOPPED".equals(record.path("Status").asText())) {
            throw invalid("Only a stopped monitor can be resumed.");
        }
        store.chain(region, record, "ACTIVE");
        return store.mapper().createObjectNode();
    }

    /** Deletes a resource and every resource made from it, down the dataset group's tree. */
    public synchronized ObjectNode deleteResourceTree(JsonNode input, String region) {
        String arn = text(input, "ResourceArn");
        ObjectNode root = store.byArn(region, arn).orElseThrow(() -> notFound("The resource " + arn
                + " does not exist."));
        for (ObjectNode child : descendants(region, root)) {
            store.delete(region, child);
        }
        store.chain(region, root, "DELETE_PENDING", "GONE");
        return store.mapper().createObjectNode();
    }

    private List<ObjectNode> descendants(String region, ObjectNode root) {
        String kind = meta(root).path("kind").asText();
        String arn = root.path(meta(root).path("arnField").asText()).asText();
        List<ObjectNode> children = new ArrayList<>();
        switch (kind) {
            case "dataset" -> children.addAll(store.list(region, "dataset-import-job",
                    job -> arn.equals(job.path("DatasetArn").asText())));
            case "dataset-group" -> children.addAll(store.list(region, "predictor",
                    predictor -> arn.equals(meta(predictor).path("groupArn").asText())));
            case "predictor" -> {
                children.addAll(store.list(region, "forecast", forecast -> arn.equals(forecast.path("PredictorArn")
                        .asText())));
                children.addAll(store.list(region, "predictor-backtest-export-job", job -> arn.equals(job
                        .path("PredictorArn").asText())));
                children.addAll(store.list(region, "monitor", monitor -> arn.equals(monitor.path("ResourceArn")
                        .asText())));
                children.addAll(store.list(region, "explainability", explain -> arn.equals(explain
                        .path("ResourceArn").asText())));
            }
            case "forecast" -> {
                children.addAll(store.list(region, "forecast-export-job", job -> arn.equals(job.path("ForecastArn")
                        .asText())));
                children.addAll(store.list(region, "what-if-analysis", analysis -> arn.equals(analysis
                        .path("ForecastArn").asText())));
                children.addAll(store.list(region, "explainability", explain -> arn.equals(explain
                        .path("ResourceArn").asText())));
            }
            case "what-if-analysis" -> children.addAll(store.list(region, "what-if-forecast",
                    forecast -> arn.equals(forecast.path("WhatIfAnalysisArn").asText())));
            case "explainability" -> children.addAll(store.list(region, "explainability-export",
                    export -> arn.equals(export.path("ExplainabilityArn").asText())));
            default -> {
            }
        }
        List<ObjectNode> all = new ArrayList<>();
        for (ObjectNode child : children) {
            all.addAll(descendants(region, child));
            all.add(child);
        }
        return all;
    }

    // ---------------------------------------------------------------- tags

    public synchronized ObjectNode listTagsForResource(JsonNode input, String region) {
        String arn = text(input, "ResourceArn");
        ObjectNode record = store.byArn(region, arn).orElseThrow(() -> notFound("The resource " + arn
                + " does not exist."));
        ObjectNode response = store.mapper().createObjectNode();
        response.set("Tags", meta(record).path("tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode input, String region) {
        String arn = text(input, "ResourceArn");
        ObjectNode record = store.byArn(region, arn).orElseThrow(() -> notFound("The resource " + arn
                + " does not exist."));
        Map<String, String> merged = new LinkedHashMap<>();
        meta(record).path("tags").forEach(tag -> merged.put(tag.path("Key").asText(), tag.path("Value").asText()));
        input.path("Tags").forEach(tag -> merged.put(tag.path("Key").asText(), tag.path("Value").asText()));
        meta(record).set("tags", tagList(merged));
        store.save(region, record);
        return store.mapper().createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode input, String region) {
        String arn = text(input, "ResourceArn");
        ObjectNode record = store.byArn(region, arn).orElseThrow(() -> notFound("The resource " + arn
                + " does not exist."));
        Map<String, String> merged = new LinkedHashMap<>();
        meta(record).path("tags").forEach(tag -> merged.put(tag.path("Key").asText(), tag.path("Value").asText()));
        input.path("TagKeys").forEach(key -> merged.remove(key.asText()));
        meta(record).set("tags", tagList(merged));
        store.save(region, record);
        return store.mapper().createObjectNode();
    }

    private ArrayNode tags(JsonNode input) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (input != null) {
            input.forEach(tag -> merged.put(tag.path("Key").asText(), tag.path("Value").asText()));
        }
        return tagList(merged);
    }

    private ArrayNode tagList(Map<String, String> tags) {
        if (tags.size() > 200) {
            throw new AwsException("LimitExceededException", "A resource can have at most 200 tags.", 400);
        }
        for (String key : tags.keySet()) {
            if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw invalid("Tag keys beginning with aws: are reserved.");
            }
        }
        ArrayNode list = store.mapper().createArrayNode();
        tags.forEach((key, value) -> list.addObject().put("Key", key).put("Value", value));
        return list;
    }

    // ---------------------------------------------------------------- shared

    /** Finishes a job's creation: ACTIVE a transition later, or CREATE_FAILED with why. */
    private void finish(String region, ObjectNode record, String failure) {
        if (failure != null) {
            record.put("Message", failure);
            store.chain(region, record, "CREATE_PENDING", "CREATE_IN_PROGRESS", "CREATE_FAILED");
        } else {
            store.chain(region, record, "CREATE_PENDING", "CREATE_IN_PROGRESS", "ACTIVE");
        }
    }

    private ObjectNode require(String region, String arn, String kind) {
        ObjectNode record = store.byArn(region, arn).orElseThrow(() -> notFound("The resource " + arn
                + " does not exist."));
        if (!kind.equals(meta(record).path("kind").asText())) {
            throw invalid("The ARN " + arn + " is not a " + kind + " ARN.");
        }
        return record;
    }

    private ObjectNode active(String region, String arn, String kind) {
        ObjectNode record = require(region, arn, kind);
        if (!"ACTIVE".equals(record.path("Status").asText())) {
            throw inUse("The " + kind + " " + arn + " is " + record.path("Status").asText() + ", not ACTIVE.");
        }
        return record;
    }

    /** A role Forecast assumes to read or write S3: it must exist and trust {@code forecast.amazonaws.com}. */
    private void role(String arn) {
        IamRole role = null;
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            String name = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
            role = iam.findRole(parsed.accountId(), name).filter(found -> arn.equals(found.getArn())).orElse(null);
        } catch (IllegalArgumentException e) {
            role = null;
        }
        if (role == null || !TRUST.allowsService(role.getAssumeRolePolicyDocument(),
                ServicePrincipals.of("forecast"))) {
            throw invalid("Amazon Forecast is unable to assume the role " + arn + ". Make sure it exists and trusts "
                    + ServicePrincipals.of("forecast") + ".");
        }
    }

    private void writeCsv(String path, String name, List<List<String>> rows) {
        if (!path.startsWith("s3://")) {
            throw new IllegalStateException("The path " + path + " is not an S3 path.");
        }
        String[] parts = path.substring(5).split("/", 2);
        String prefix = parts.length > 1 && !parts[1].isEmpty() ? (parts[1].endsWith("/") ? parts[1] : parts[1] + "/")
                : "";
        StringBuilder csv = new StringBuilder();
        rows.forEach(row -> csv.append(String.join(",", row)).append('\n'));
        try {
            s3.putObject(parts[0], prefix + name + "_" + FILE_TIME.format(Instant.now()) + "_part0.csv",
                    csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv", Map.of());
        } catch (AwsException e) {
            throw new IllegalStateException("Unable to write to " + path + ": " + e.getMessage());
        }
    }

    private static List<ForecastEngine.Point> points(JsonNode series) {
        List<ForecastEngine.Point> points = new ArrayList<>();
        series.forEach(pair -> points.add(new ForecastEngine.Point(Instant.ofEpochSecond(pair.get(0).asLong()),
                pair.get(1).asDouble())));
        return points;
    }

    private static List<String> types(ObjectNode predictor) {
        List<String> types = new ArrayList<>();
        predictor.path("ForecastTypes").forEach(type -> types.add(type.asText()));
        return types;
    }

    private static List<String> keyNames(ObjectNode predictor) {
        List<String> keys = new ArrayList<>();
        meta(predictor).path("keys").forEach(key -> keys.add(key.asText()));
        return keys;
    }

    /** A forecast type's prediction key: {@code p10} for 0.1, {@code mean} for the mean. */
    static String column(String type) {
        if ("mean".equals(type)) {
            return "mean";
        }
        double quantile = Double.parseDouble(type) * 100;
        return "p" + (quantile == Math.rint(quantile) ? Long.toString((long) quantile) : Double.toString(quantile));
    }

    private ObjectNode arnResponse(String field, ObjectNode record) {
        ObjectNode response = store.mapper().createObjectNode();
        response.put(field, record.path(field).asText());
        return response;
    }

    private ObjectNode summary(ObjectNode record, String... fields) {
        ObjectNode item = store.mapper().createObjectNode();
        for (String field : fields) {
            if (record.has(field)) {
                item.set(field, record.get(field).deepCopy());
            }
        }
        item.put("CreationTime", record.path("CreationTime").asDouble())
                .put("LastModificationTime", record.path("LastModificationTime").asDouble());
        return item;
    }

    private ObjectNode listOf(String region, String kind, JsonNode input, String field, String... fields) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, kind, record -> filtered(record, input))) {
            items.add(summary(record, fields));
        }
        return store.page(items, input, field);
    }

    private ObjectNode deleteOf(String region, String arn, String kind) {
        ObjectNode record = require(region, arn, kind);
        store.chain(region, record, "DELETE_PENDING", "GONE");
        return store.mapper().createObjectNode();
    }

    /** Whether a record passes the request's {@code Filters}: each {@code Key IS|IS_NOT Value}. */
    private static boolean filtered(ObjectNode record, JsonNode input) {
        for (JsonNode filter : input.path("Filters")) {
            String key = filter.path("Key").asText();
            String actual = "DatasetGroupArn".equals(key) && !record.has(key)
                    ? meta(record).path("groupArn").asText() : record.path(key).asText();
            boolean matches = filter.path("Value").asText().equals(actual);
            if ("IS_NOT".equals(filter.path("Condition").asText()) == matches) {
                return false;
            }
        }
        return true;
    }
}
