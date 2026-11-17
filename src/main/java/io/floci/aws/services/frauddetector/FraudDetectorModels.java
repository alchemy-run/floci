package io.floci.aws.services.frauddetector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fraud Detector's models: a model version learns, for each of its variables, the log-odds of fraud for each value
 * it saw (numbers fall into quantile bins). An event's score is the fraud probability scaled to 0–1000, and each
 * variable's log-odds is its share of the explanation.
 */
final class FraudDetectorModels {

    private static final int BINS = 4;

    private FraudDetectorModels() {
    }

    /** What a model version learned. Serialized onto the model version record. */
    record Weights(double prior, List<String> variables, Map<String, List<Double>> cuts,
                   Map<String, Map<String, Double>> logOdds) {
    }

    /** One event's score and each variable's log-odds contribution. */
    record Score(double value, Map<String, Double> contributions) {

        ObjectNode explanations(ObjectMapper mapper) {
            ObjectNode out = mapper.createObjectNode();
            ArrayNode impacts = out.putArray("variableImpactExplanations");
            double max = contributions.values().stream().mapToDouble(Math::abs).max().orElse(0);
            contributions.forEach((name, impact) -> impacts.addObject().put("eventVariableName", name)
                    .put("relativeImpact", String.valueOf(max == 0 ? 0 : Math.round(5 * Math.abs(impact) / max)))
                    .put("logOddsImpact", impact));
            out.putArray("aggregatedVariablesImpactExplanations");
            return out;
        }
    }

    /** A trained model and how it does on its own training data. */
    record Trained(Weights weights, double auc, List<double[]> points, Map<String, Double> importance,
                   int fraud, int legit) {

        ObjectNode result(ObjectMapper mapper) {
            ObjectNode out = mapper.createObjectNode();
            ObjectNode validation = out.putObject("dataValidationMetrics");
            validation.putArray("fileLevelMessages").addObject().put("title", "Training data")
                    .put("content", fraud + " fraud and " + legit + " legitimate events were used.")
                    .put("type", "INFO");
            validation.putArray("fieldLevelMessages");
            ObjectNode metrics = out.putObject("trainingMetrics").put("auc", auc);
            ArrayNode dataPoints = metrics.putArray("metricDataPoints");
            for (double[] point : points) {
                dataPoints.addObject().put("fpr", point[0]).put("precision", point[1]).put("tpr", point[2])
                        .put("threshold", point[3]);
            }
            out.set("variableImportanceMetrics", importance(mapper));
            return out;
        }

        ObjectNode resultV2(ObjectMapper mapper, String modelType) {
            ObjectNode out = mapper.createObjectNode();
            out.set("dataValidationMetrics", result(mapper).get("dataValidationMetrics"));
            ObjectNode metrics = out.putObject("trainingMetricsV2");
            if ("ACCOUNT_TAKEOVER_INSIGHTS".equals(modelType)) {
                ObjectNode ati = metrics.putObject("ati");
                ArrayNode dataPoints = ati.putArray("metricDataPoints");
                for (double[] point : points) {
                    dataPoints.addObject().put("cr", point[0]).put("adr", point[2]).put("threshold", point[3])
                            .put("atodr", point[1]);
                }
                ati.putObject("modelPerformance").put("asi", auc);
            } else {
                ObjectNode values = metrics.putObject("TRANSACTION_FRAUD_INSIGHTS".equals(modelType) ? "tfi"
                        : "ofi");
                ArrayNode dataPoints = values.putArray("metricDataPoints");
                for (double[] point : points) {
                    dataPoints.addObject().put("fpr", point[0]).put("precision", point[1]).put("tpr", point[2])
                            .put("threshold", point[3]);
                }
                ObjectNode performance = values.putObject("modelPerformance").put("auc", auc);
                performance.putObject("uncertaintyRange").put("lowerBoundValue", Math.max(0, auc - 0.05))
                        .put("upperBoundValue", Math.min(1, auc + 0.05));
            }
            out.set("variableImportanceMetrics", importance(mapper));
            ObjectNode aggregated = out.putObject("aggregatedVariablesImportanceMetrics");
            aggregated.putArray("logOddsMetrics");
            return out;
        }

        private ObjectNode importance(ObjectMapper mapper) {
            ObjectNode out = mapper.createObjectNode();
            ArrayNode metrics = out.putArray("logOddsMetrics");
            importance.forEach((name, value) -> metrics.addObject().put("variableName", name)
                    .put("variableType", "CATEGORICAL").put("variableImportance", value));
            return out;
        }
    }

    /**
     * Trains on labeled rows (variable columns plus {@code EVENT_LABEL}), mapping labels to fraud or legitimate
     * through the label schema. A model needs at least one fraud and one legitimate event.
     */
    static Trained train(List<ObjectNode> rows, List<String> variables, JsonNode labelSchema,
                         Map<String, String> dataTypes) {
        Set<String> fraudLabels = new HashSet<>();
        Set<String> legitLabels = new HashSet<>();
        labelSchema.path("labelMapper").fields().forEachRemaining(entry -> {
            Set<String> target = "FRAUD".equals(entry.getKey()) ? fraudLabels : legitLabels;
            entry.getValue().forEach(label -> target.add(label.asText()));
        });
        String unlabeled = labelSchema.path("unlabeledEventsTreatment").asText("IGNORE");
        List<ObjectNode> examples = new ArrayList<>();
        List<Boolean> isFraud = new ArrayList<>();
        for (ObjectNode row : rows) {
            String label = row.path("EVENT_LABEL").asText("");
            Boolean fraud = fraudLabels.contains(label) ? Boolean.TRUE : legitLabels.contains(label) ? Boolean.FALSE
                    : label.isEmpty() ? switch (unlabeled) {
                        case "FRAUD" -> Boolean.TRUE;
                        case "LEGIT", "AUTO" -> Boolean.FALSE;
                        default -> null;
                    } : null;
            if (fraud != null) {
                examples.add(row);
                isFraud.add(fraud);
            }
        }
        int fraud = (int) isFraud.stream().filter(b -> b).count();
        int legit = isFraud.size() - fraud;
        if (fraud == 0 || legit == 0) {
            throw new AwsException("ValidationException", "Training needs labeled fraud and legitimate events; "
                    + "found " + fraud + " fraud and " + legit + " legitimate.", 400);
        }
        Map<String, List<Double>> cuts = new LinkedHashMap<>();
        for (String variable : variables) {
            String type = dataTypes.getOrDefault(variable, "STRING");
            if ("INTEGER".equals(type) || "FLOAT".equals(type)) {
                TreeSet<Double> seen = new TreeSet<>();
                for (ObjectNode row : examples) {
                    Double value = number(row.path(variable).asText(null));
                    if (value != null) {
                        seen.add(value);
                    }
                }
                List<Double> sorted = new ArrayList<>(seen);
                List<Double> cut = new ArrayList<>();
                for (int i = 1; i < BINS && !sorted.isEmpty(); i++) {
                    double at = sorted.get(Math.min(sorted.size() - 1, i * sorted.size() / BINS));
                    if (cut.isEmpty() || cut.getLast() < at) {
                        cut.add(at);
                    }
                }
                cuts.put(variable, cut);
            }
        }
        double prior = Math.log((fraud + 1.0) / (legit + 1.0));
        Map<String, Map<String, Double>> logOdds = new LinkedHashMap<>();
        for (String variable : variables) {
            Map<String, int[]> counts = new HashMap<>();
            for (int i = 0; i < examples.size(); i++) {
                String bucket = bucket(cuts, variable, examples.get(i).path(variable).asText(""));
                counts.computeIfAbsent(bucket, b -> new int[2])[isFraud.get(i) ? 0 : 1]++;
            }
            Map<String, Double> odds = new LinkedHashMap<>();
            counts.forEach((bucket, count) -> odds.put(bucket,
                    Math.log((count[0] + 1.0) / (fraud + 2.0)) - Math.log((count[1] + 1.0) / (legit + 2.0))));
            logOdds.put(variable, odds);
        }
        Weights weights = new Weights(prior, List.copyOf(variables), cuts, logOdds);
        double[] scores = new double[examples.size()];
        Map<String, Double> importance = new LinkedHashMap<>();
        variables.forEach(v -> importance.put(v, 0.0));
        for (int i = 0; i < examples.size(); i++) {
            Map<String, String> raw = new HashMap<>();
            ObjectNode row = examples.get(i);
            variables.forEach(v -> raw.put(v, row.path(v).asText("")));
            Score score = score(weights, raw);
            scores[i] = score.value();
            score.contributions().forEach((name, value) -> importance.merge(name, Math.abs(value) / examples.size(),
                    Double::sum));
        }
        List<double[]> points = new ArrayList<>();
        for (int threshold = 0; threshold <= 1000; threshold += 100) {
            int tp = 0;
            int fp = 0;
            for (int i = 0; i < scores.length; i++) {
                if (scores[i] >= threshold) {
                    if (isFraud.get(i)) {
                        tp++;
                    } else {
                        fp++;
                    }
                }
            }
            points.add(new double[]{(double) fp / legit, tp + fp == 0 ? 1.0 : (double) tp / (tp + fp),
                    (double) tp / fraud, threshold});
        }
        double pairs = 0;
        for (int i = 0; i < scores.length; i++) {
            for (int j = 0; j < scores.length; j++) {
                if (isFraud.get(i) && !isFraud.get(j)) {
                    pairs += scores[i] > scores[j] ? 1 : scores[i] == scores[j] ? 0.5 : 0;
                }
            }
        }
        return new Trained(weights, pairs / ((double) fraud * legit), points, importance, fraud, legit);
    }

    /** Scores an event's raw variable values. */
    static Score score(Weights weights, Map<String, String> raw) {
        double logit = weights.prior();
        Map<String, Double> contributions = new LinkedHashMap<>();
        for (String variable : weights.variables()) {
            String bucket = bucket(weights.cuts(), variable, raw.getOrDefault(variable, ""));
            double impact = weights.logOdds().getOrDefault(variable, Map.of()).getOrDefault(bucket, 0.0);
            contributions.put(variable, impact);
            logit += impact;
        }
        double probability = 1 / (1 + Math.exp(-logit));
        return new Score(Math.round(probability * 1000), contributions);
    }

    private static String bucket(Map<String, List<Double>> cuts, String variable, String value) {
        List<Double> cut = cuts == null ? null : cuts.get(variable);
        if (cut == null) {
            return value;
        }
        Double number = number(value);
        if (number == null) {
            return "missing";
        }
        int bin = 0;
        while (bin < cut.size() && number >= cut.get(bin)) {
            bin++;
        }
        return "bin" + bin;
    }

    private static Double number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
