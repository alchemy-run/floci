package io.floci.aws.services.ce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.UsageLine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.floci.aws.services.ce.CeRequests.integer;
import static io.floci.aws.services.ce.CeRequests.invalid;
import static io.floci.aws.services.ce.CeRequests.object;
import static io.floci.aws.services.ce.CeRequests.oneOf;
import static io.floci.aws.services.ce.CeRequests.page;
import static io.floci.aws.services.ce.CeRequests.present;
import static io.floci.aws.services.ce.CeRequests.strings;
import static io.floci.aws.services.ce.CeRequests.text;

/**
 * Cost Explorer analysis over the synthesized usage stream: cost and usage forecasts, approximate usage record
 * counts, and month-over-month comparisons with their cost drivers.
 * <p>
 * Floci derives usage from current resource state, so a forecast projects the trailing day's run rate forward, and
 * the prediction interval widens with the requested confidence level.
 */
@ApplicationScoped
public class CostAnalysisService {

    private static final Map<String, String> FORECAST_METRICS = Map.of(
            "BLENDED_COST", "BlendedCost",
            "UNBLENDED_COST", "UnblendedCost",
            "AMORTIZED_COST", "AmortizedCost",
            "NET_UNBLENDED_COST", "NetUnblendedCost",
            "NET_AMORTIZED_COST", "NetAmortizedCost",
            "USAGE_QUANTITY", "UsageQuantity",
            "NORMALIZED_USAGE_AMOUNT", "NormalizedUsageAmount");
    private static final Set<String> COMPARISON_METRICS = Set.of("BlendedCost", "UnblendedCost", "AmortizedCost",
            "NetUnblendedCost", "NetAmortizedCost", "UsageQuantity", "NormalizedUsageAmount");

    private final CostExplorerService costs;
    private final ObjectMapper mapper;

    @Inject
    public CostAnalysisService(CostExplorerService costs, ObjectMapper mapper) {
        this.costs = costs;
        this.mapper = mapper;
    }

    public ObjectNode getCostForecast(JsonNode request, String region) {
        String metric = oneOf(request, "Metric", FORECAST_METRICS.keySet(), null);
        return forecast(request, region, FORECAST_METRICS.get(metric), false);
    }

    public ObjectNode getUsageForecast(JsonNode request, String region) {
        String metric = oneOf(request, "Metric", Set.of("USAGE_QUANTITY", "NORMALIZED_USAGE_AMOUNT"), null);
        if (!filtersOnUsageType(request.path("Filter"))) {
            throw invalid("GetUsageForecast requires a Filter on the USAGE_TYPE or USAGE_TYPE_GROUP dimension.");
        }
        return forecast(request, region, FORECAST_METRICS.get(metric), true);
    }

    /**
     * Estimates how many usage records a Cost and Usage Report or data export would hold per service over the last
     * 30 days: one record per usage type and operation (and per resource for {@code RESOURCE}) per period.
     */
    public ObjectNode getApproximateUsageRecords(JsonNode request, String region) {
        String granularity = oneOf(request, "Granularity", Set.of("HOURLY", "DAILY", "MONTHLY"), null);
        String dimension = oneOf(request, "ApproximationDimension", Set.of("SERVICE", "RESOURCE"), null);
        if ("SERVICE".equals(dimension) && "DAILY".equals(granularity)) {
            throw invalid("The estimation data cannot be generated for your input combination.");
        }
        Set<String> services = present(request, "Services")
                ? new HashSet<>(strings(request, "Services", 100, 1024)) : Set.of();
        Instant end = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant start = end.minus(30, ChronoUnit.DAYS);
        long periods = switch (granularity) {
            case "HOURLY" -> 30L * 24;
            case "DAILY" -> 30L;
            default -> 1L;
        };
        Map<String, Set<String>> records = new TreeMap<>();
        for (UsageLine line : costs.lines(start, end, region)) {
            if (line.quantity() <= 0 || (!services.isEmpty() && !services.contains(line.service()))) {
                continue;
            }
            String key = line.usageType() + "|" + line.operation()
                    + ("RESOURCE".equals(dimension) ? "|" + line.resourceId() : "");
            records.computeIfAbsent(line.service(), service -> new HashSet<>()).add(key);
        }
        ObjectNode response = mapper.createObjectNode();
        ObjectNode byService = response.putObject("Services");
        long total = 0;
        for (Map.Entry<String, Set<String>> entry : records.entrySet()) {
            long count = entry.getValue().size() * periods;
            byService.put(entry.getKey(), count);
            total += count;
        }
        response.put("TotalRecords", total);
        ObjectNode lookback = response.putObject("LookbackPeriod");
        lookback.put("Start", date(start));
        lookback.put("End", date(end));
        return response;
    }

    public ObjectNode getCostAndUsageComparisons(JsonNode request, String region) {
        Comparison comparison = compare(request, region, List.of());
        List<ObjectNode> rows = new ArrayList<>();
        for (Map.Entry<List<String>, double[]> entry : comparison.groups().entrySet()) {
            ObjectNode row = mapper.createObjectNode();
            row.set("CostAndUsageSelector", selector(comparison.groupBys(), entry.getKey()));
            row.set("Metrics", metrics(comparison.metric(), entry.getValue(), comparison.unit()));
            rows.add(row);
        }
        PaginatedResult<ObjectNode> paged = page(rows, row -> row.path("CostAndUsageSelector").toString(), request,
                "MaxResults", "NextPageToken", 2000, 2000);
        ObjectNode response = mapper.createObjectNode();
        response.putArray("CostAndUsageComparisons").addAll(paged.items());
        response.set("TotalCostAndUsage", metrics(comparison.metric(), comparison.total(), comparison.unit()));
        if (paged.nextToken() != null) {
            response.put("NextPageToken", paged.nextToken());
        }
        return response;
    }

    /**
     * Breaks each group's month-over-month change down by usage type: a usage type that appears only in the
     * comparison month is {@code NEW_USAGE}, one that disappears is {@code USAGE_DECREASE}, and one present in
     * both is {@code USAGE_CHANGE}.
     */
    public ObjectNode getCostComparisonDrivers(JsonNode request, String region) {
        Comparison comparison = compare(request, region,
                List.of(new GroupAggregator.GroupBy("DIMENSION", "SERVICE")));
        List<ObjectNode> rows = new ArrayList<>();
        for (Map.Entry<List<String>, double[]> entry : comparison.groups().entrySet()) {
            ObjectNode row = mapper.createObjectNode();
            row.set("CostSelector", selector(comparison.groupBys(), entry.getKey()));
            row.set("Metrics", metrics(comparison.metric(), entry.getValue(), comparison.unit()));
            ArrayNode drivers = row.putArray("CostDrivers");
            Map<String, double[]> byUsage = comparison.drivers().getOrDefault(entry.getKey(), Map.of());
            for (Map.Entry<String, double[]> driver : new TreeMap<>(byUsage).entrySet()) {
                double[] amounts = driver.getValue();
                if (Math.abs(amounts[1] - amounts[0]) < 1e-9) {
                    continue;
                }
                String type = amounts[0] == 0 ? "NEW_USAGE" : amounts[1] == 0 ? "USAGE_DECREASE" : "USAGE_CHANGE";
                drivers.addObject().put("Type", type).put("Name", driver.getKey())
                        .set("Metrics", metrics(comparison.metric(), amounts, comparison.unit()));
            }
            rows.add(row);
        }
        PaginatedResult<ObjectNode> paged = page(rows, row -> row.path("CostSelector").toString(), request,
                "MaxResults", "NextPageToken", 10, 10);
        ObjectNode response = mapper.createObjectNode();
        response.putArray("CostComparisonDrivers").addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextPageToken", paged.nextToken());
        }
        return response;
    }

    private ObjectNode forecast(JsonNode request, String region, String metric, boolean usage) {
        String granularity = oneOf(request, "Granularity", Set.of("DAILY", "MONTHLY"), null);
        int level = integer(request, "PredictionIntervalLevel", 51, 99, 80);
        ObjectNode period = object(request, "TimePeriod");
        Instant start = TimeBucketing.parseDate(text(period, "Start", 40), "TimePeriod.Start");
        Instant end = TimeBucketing.parseDate(text(period, "End", 40), "TimePeriod.End");
        Instant today = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        if (!end.isAfter(start)) {
            throw invalid("TimePeriod.End must be after TimePeriod.Start.");
        }
        if (start.isBefore(today)) {
            throw invalid("The forecast start date must not be in the past.");
        }
        long horizonDays = ChronoUnit.DAYS.between(today, end);
        if (horizonDays > ("DAILY".equals(granularity) ? 93 : 549)) {
            throw invalid("DAILY forecasts cover at most 3 months and MONTHLY forecasts at most 18 months.");
        }
        JsonNode filter = request.has("Filter") ? request.get("Filter") : null;
        Instant historyStart = today.minus(1, ChronoUnit.DAYS);
        FilterExpressionEvaluator.CategoryResolver resolver = costs.resolver(today);
        CostSynthesizer synthesizer = costs.synthesizer();
        double daily = 0;
        Set<String> units = new TreeSet<>();
        for (UsageLine line : costs.lines(historyStart, today, region)) {
            if (!FilterExpressionEvaluator.matches(filter, line, resolver)) {
                continue;
            }
            CostSynthesizer.MetricValue value = synthesizer.compute(line).get(metric);
            if (value != null && value.amount() != 0) {
                daily += value.amount();
                units.add(value.unit());
            }
        }
        if (usage && units.size() > 1) {
            throw new AwsException("UnresolvableUsageUnitException",
                    "The filter matches usage in more than one unit: " + String.join(", ", units) + ".", 400);
        }
        if (daily <= 0) {
            throw new AwsException("DataUnavailableException",
                    "Insufficient amount of historical data to generate forecast.", 400);
        }
        double spread = 0.05 * z(level);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode results = response.putArray("ForecastResultsByTime");
        double total = 0;
        for (TimeBucketing.Bucket bucket : TimeBucketing.split(start, end,
                TimeBucketing.Granularity.valueOf(granularity))) {
            double days = ChronoUnit.SECONDS.between(bucket.start(), bucket.end()) / 86400.0;
            double mean = daily * days;
            total += mean;
            ObjectNode result = results.addObject();
            ObjectNode time = result.putObject("TimePeriod");
            time.put("Start", date(bucket.start()));
            time.put("End", date(bucket.end()));
            result.put("MeanValue", GroupAggregator.formatAmount(mean));
            result.put("PredictionIntervalLowerBound", GroupAggregator.formatAmount(mean * (1 - spread)));
            result.put("PredictionIntervalUpperBound", GroupAggregator.formatAmount(mean * (1 + spread)));
        }
        ObjectNode totalNode = response.putObject("Total");
        totalNode.put("Amount", GroupAggregator.formatAmount(total));
        totalNode.put("Unit", usage ? units.iterator().next() : "USD");
        return response;
    }

    private Comparison compare(JsonNode request, String region, List<GroupAggregator.GroupBy> defaults) {
        String metric = oneOf(request, "MetricForComparison", COMPARISON_METRICS, null);
        Instant[] baseline = month(request, "BaselineTimePeriod");
        Instant[] comparison = month(request, "ComparisonTimePeriod");
        List<GroupAggregator.GroupBy> groupBys = GroupAggregator.parseGroupBy(request.path("GroupBy"));
        if (groupBys.size() > 2) {
            throw invalid("GroupBy accepts at most two groupings.");
        }
        if (groupBys.isEmpty()) {
            groupBys = defaults;
        }
        JsonNode filter = request.has("Filter") ? request.get("Filter") : null;
        Map<List<String>, double[]> groups = new LinkedHashMap<>();
        Map<List<String>, Map<String, double[]>> drivers = new HashMap<>();
        double[] total = new double[2];
        String unit = metric.endsWith("Cost") ? "USD" : null;
        CostSynthesizer synthesizer = costs.synthesizer();
        Instant[][] periods = {baseline, comparison};
        for (int index = 0; index < 2; index++) {
            FilterExpressionEvaluator.CategoryResolver resolver = costs.resolver(periods[index][1]);
            for (UsageLine line : costs.lines(periods[index][0], periods[index][1], region)) {
                if (!FilterExpressionEvaluator.matches(filter, line, resolver)) {
                    continue;
                }
                CostSynthesizer.MetricValue value = synthesizer.compute(line).get(metric);
                if (value == null || value.amount() == 0) {
                    continue;
                }
                if (unit == null) {
                    unit = value.unit();
                }
                total[index] += value.amount();
                if (!groupBys.isEmpty()) {
                    List<String> key = GroupAggregator.groupKey(groupBys, line, resolver);
                    groups.computeIfAbsent(key, k -> new double[2])[index] += value.amount();
                    drivers.computeIfAbsent(key, k -> new HashMap<>())
                            .computeIfAbsent(line.usageType(), k -> new double[2])[index] += value.amount();
                }
            }
        }
        return new Comparison(metric, unit == null ? "USD" : unit, groupBys, groups, drivers, total);
    }

    private static Instant[] month(JsonNode request, String field) {
        ObjectNode period = object(request, field);
        Instant start = TimeBucketing.parseDate(text(period, "Start", 40), field + ".Start");
        Instant end = TimeBucketing.parseDate(text(period, "End", 40), field + ".End");
        LocalDate first = start.atOffset(ZoneOffset.UTC).toLocalDate();
        if (first.getDayOfMonth() != 1 || !end.equals(first.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC))) {
            throw invalid(field + " must cover exactly one calendar month.");
        }
        LocalDate thisMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        if (first.isAfter(thisMonth) || first.isBefore(thisMonth.minusMonths(13))) {
            throw invalid(field + " must be within the last 13 months.");
        }
        return new Instant[] {start, end};
    }

    private ObjectNode selector(List<GroupAggregator.GroupBy> groupBys, List<String> key) {
        List<ObjectNode> parts = new ArrayList<>();
        for (int index = 0; index < groupBys.size(); index++) {
            GroupAggregator.GroupBy groupBy = groupBys.get(index);
            String value = key.get(index);
            String operator = switch (groupBy.type()) {
                case "TAG" -> "Tags";
                case "COST_CATEGORY" -> "CostCategories";
                default -> "Dimensions";
            };
            if (!"Dimensions".equals(operator)) {
                value = value.substring(value.indexOf('$') + 1);
            }
            ObjectNode part = mapper.createObjectNode();
            ObjectNode values = part.putObject(operator);
            values.put("Key", groupBy.key());
            values.putArray("Values").add(value);
            parts.add(part);
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        ObjectNode and = mapper.createObjectNode();
        and.putArray("And").addAll(parts);
        return and;
    }

    private ObjectNode metrics(String metric, double[] amounts, String unit) {
        ObjectNode metrics = mapper.createObjectNode();
        ObjectNode values = metrics.putObject(metric);
        values.put("BaselineTimePeriodAmount", GroupAggregator.formatAmount(amounts[0]));
        values.put("ComparisonTimePeriodAmount", GroupAggregator.formatAmount(amounts[1]));
        values.put("Difference", GroupAggregator.formatAmount(amounts[1] - amounts[0]));
        values.put("Unit", unit);
        return metrics;
    }

    private static boolean filtersOnUsageType(JsonNode expression) {
        if (expression == null || !expression.isObject()) {
            return false;
        }
        String key = expression.path("Dimensions").path("Key").asText("");
        if ("USAGE_TYPE".equals(key) || "USAGE_TYPE_GROUP".equals(key)) {
            return true;
        }
        for (JsonNode child : expression.path("And")) {
            if (filtersOnUsageType(child)) {
                return true;
            }
        }
        return false;
    }

    /** The two-sided normal quantile for a confidence level, by the Winitzki approximation of erf⁻¹. */
    private static double z(int level) {
        double x = level / 100.0;
        double a = 0.147;
        double ln = Math.log(1 - x * x);
        double first = 2 / (Math.PI * a) + ln / 2;
        return Math.sqrt(2) * Math.sqrt(Math.sqrt(first * first - ln / a) - first);
    }

    private static String date(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    private record Comparison(String metric, String unit, List<GroupAggregator.GroupBy> groupBys,
                              Map<List<String>, double[]> groups, Map<List<String>, Map<String, double[]>> drivers,
                              double[] total) {}
}
