package io.github.hectorvent.floci.services.ce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ResourceUsageEnumerator;
import io.github.hectorvent.floci.core.common.UsageLine;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ce.model.AnomalyMonitor;
import io.github.hectorvent.floci.services.ce.model.AnomalySubscription;
import io.github.hectorvent.floci.services.ce.model.CostCategoryDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * AWS Cost Explorer (`ce:*`) emulation.
 * <p>
 * Synthesizes cost and usage from Floci's own resource state, multiplied by
 * the AWS Pricing snapshot served by {@code services/pricing}. Discovery of
 * which services contribute to cost is handled by CDI: every
 * {@code @ApplicationScoped} bean implementing
 * {@link ResourceUsageEnumerator} is auto-injected here, so adding a new
 * Floci service with cost reporting needs no change to this class.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_Operations_AWS_Cost_Explorer_Service.html">AWS Cost Explorer API</a>
 */
@ApplicationScoped
public class CostExplorerService {

    private static final Logger LOG = Logger.getLogger(CostExplorerService.class);
    private static final DateTimeFormatter EFFECTIVE_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final ObjectMapper objectMapper;
    private final Instance<ResourceUsageEnumerator> enumerators;
    private final PricingRateLookup rateLookup;
    private final GroupAggregator aggregator;
    private final double monthlyCreditUsd;
    private final StorageBackend<String, AnomalyMonitor> monitorStore;
    private final StorageBackend<String, CostCategoryDefinition> categoryStore;
    private final StorageBackend<String, AnomalySubscription> subscriptionStore;
    private final RegionResolver regionResolver;

    @Inject
    public CostExplorerService(ObjectMapper objectMapper,
                                Instance<ResourceUsageEnumerator> enumerators,
                                PricingRateLookup rateLookup,
                                EmulatorConfig config,
                                StorageFactory storageFactory,
                                RegionResolver regionResolver) {
        this(objectMapper, enumerators, rateLookup, config.services().ce().creditUsdMonthly(),
                storageFactory.create("ce", "ce-anomaly-monitors.json",
                        new TypeReference<Map<String, AnomalyMonitor>>() {}),
                storageFactory.create("ce", "ce-cost-categories.json",
                        new TypeReference<Map<String, CostCategoryDefinition>>() {}),
                storageFactory.create("ce", "ce-anomaly-subscriptions.json",
                        new TypeReference<Map<String, AnomalySubscription>>() {}),
                regionResolver);
    }

    CostExplorerService(ObjectMapper objectMapper,
                         Instance<ResourceUsageEnumerator> enumerators,
                         PricingRateLookup rateLookup,
                         double monthlyCreditUsd,
                         StorageBackend<String, AnomalyMonitor> monitorStore,
                         StorageBackend<String, CostCategoryDefinition> categoryStore,
                         StorageBackend<String, AnomalySubscription> subscriptionStore,
                         RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.enumerators = enumerators;
        this.rateLookup = rateLookup;
        this.aggregator = new GroupAggregator(objectMapper);
        this.monthlyCreditUsd = monthlyCreditUsd;
        this.monitorStore = monitorStore;
        this.categoryStore = categoryStore;
        this.subscriptionStore = subscriptionStore;
        this.regionResolver = regionResolver;
    }

    public ObjectNode getCostAndUsage(JsonNode request, String defaultRegion) {
        return runCostAndUsage(request, defaultRegion);
    }

    /**
     * Returns the same shape as {@link #getCostAndUsage} for now. Floci's
     * resource-level data is already surfaced through the {@code RESOURCE_ID}
     * dimension, so a caller that wants resource breakdown can issue
     * {@code GetCostAndUsage} with {@code GroupBy=[{Type:DIMENSION,Key:RESOURCE_ID}]}.
     * A separate emit path that returns inline resource attributions can land
     * later if a consumer needs it.
     */
    public ObjectNode getCostAndUsageWithResources(JsonNode request, String defaultRegion) {
        return runCostAndUsage(request, defaultRegion);
    }

    private ObjectNode runCostAndUsage(JsonNode request, String defaultRegion) {
        TimeWindow window = parseTimeWindow(request);
        TimeBucketing.Granularity granularity = TimeBucketing.parseGranularity(
                request.path("Granularity").asText(null));
        Set<String> metrics = GroupAggregator.parseMetrics(request.path("Metrics"));
        if (metrics.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'Metrics' failed to satisfy constraint: Member must contain at least 1 element.", 400);
        }
        List<GroupAggregator.GroupBy> groupBys = GroupAggregator.parseGroupBy(request.path("GroupBy"));
        JsonNode filter = request.has("Filter") ? request.get("Filter") : null;

        List<UsageLine> all = collectLines(window.start(), window.end(), defaultRegion);
        // Apply filter once across the full window so the same set is reused
        // per bucket (lines are emitted per request scope, no cross-bucket leakage).
        List<UsageLine> filtered = new ArrayList<>();
        for (UsageLine line : all) {
            if (FilterExpressionEvaluator.matches(filter, line)) {
                filtered.add(line);
            }
        }

        CostSynthesizer synthesizer = new CostSynthesizer(rateLookup);
        if (monthlyCreditUsd > 0) {
            CreditLineEmitter creditEmitter = new CreditLineEmitter(monthlyCreditUsd);
            List<UsageLine> credits = creditEmitter.emit(filtered.stream(), synthesizer).toList();
            for (UsageLine credit : credits) {
                if (FilterExpressionEvaluator.matches(filter, credit)) {
                    filtered.add(credit);
                }
            }
        }

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode resultsByTime = response.putArray("ResultsByTime");
        for (TimeBucketing.Bucket bucket : TimeBucketing.split(window.start(), window.end(), granularity)) {
            List<UsageLine> bucketLines = new ArrayList<>();
            for (UsageLine line : filtered) {
                if (overlaps(line, bucket)) {
                    bucketLines.add(scaleToBucket(line, bucket));
                }
            }
            resultsByTime.add(aggregator.buildResultByTime(bucket.start(), bucket.end(),
                    bucketLines, groupBys, metrics, synthesizer));
        }
        ArrayNode groupDefinitions = response.putArray("GroupDefinitions");
        for (GroupAggregator.GroupBy gb : groupBys) {
            ObjectNode def = groupDefinitions.addObject();
            def.put("Type", gb.type());
            def.put("Key", gb.key());
        }
        response.putArray("DimensionValueAttributes");
        return response;
    }

    public ObjectNode getDimensionValues(JsonNode request, String defaultRegion) {
        TimeWindow window = parseTimeWindow(request);
        String dimension = request.path("Dimension").asText(null);
        if (dimension == null || dimension.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'Dimension' failed to satisfy constraint: Member must not be null.", 400);
        }
        String search = request.path("SearchString").asText(null);
        JsonNode filter = request.has("Filter") ? request.get("Filter") : null;

        Set<String> values = new TreeSet<>();
        for (UsageLine line : collectLines(window.start(), window.end(), defaultRegion)) {
            if (!FilterExpressionEvaluator.matches(filter, line)) {
                continue;
            }
            String v = FilterExpressionEvaluator.dimensionValue(dimension, line);
            if (v != null && !v.isEmpty() && (search == null || v.contains(search))) {
                values.add(v);
            }
        }

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("DimensionValues");
        for (String v : values) {
            ObjectNode entry = arr.addObject();
            entry.put("Value", v);
        }
        response.put("ReturnSize", values.size());
        response.put("TotalSize", values.size());
        return response;
    }

    public ObjectNode getTags(JsonNode request, String defaultRegion) {
        TimeWindow window = parseTimeWindow(request);
        String tagKey = request.path("TagKey").asText(null);
        String search = request.path("SearchString").asText(null);
        JsonNode filter = request.has("Filter") ? request.get("Filter") : null;

        Set<String> values = new TreeSet<>();
        Set<String> keys = new TreeSet<>();
        for (UsageLine line : collectLines(window.start(), window.end(), defaultRegion)) {
            if (!FilterExpressionEvaluator.matches(filter, line)) {
                continue;
            }
            if (line.tags() == null) {
                continue;
            }
            for (Map.Entry<String, String> tag : line.tags().entrySet()) {
                keys.add(tag.getKey());
                if (tagKey != null && !tagKey.isEmpty()) {
                    if (tagKey.equals(tag.getKey())
                            && (search == null || tag.getValue().contains(search))) {
                        values.add(tag.getValue());
                    }
                }
            }
        }

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Tags");
        Set<String> emit = (tagKey == null || tagKey.isEmpty()) ? keys : values;
        for (String v : emit) {
            arr.add(v);
        }
        response.put("ReturnSize", emit.size());
        response.put("TotalSize", emit.size());
        return response;
    }

    public ObjectNode getReservationCoverage() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("CoveragesByTime");
        ObjectNode total = response.putObject("Total");
        ObjectNode coverageHours = total.putObject("CoverageHours");
        coverageHours.put("OnDemandHours", "0");
        coverageHours.put("ReservedHours", "0");
        coverageHours.put("TotalRunningHours", "0");
        coverageHours.put("CoverageHoursPercentage", "0");
        return response;
    }

    public ObjectNode getReservationUtilization() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("UtilizationsByTime");
        ObjectNode total = response.putObject("Total");
        total.put("UtilizationPercentage", "0");
        total.put("UtilizationPercentageInUnits", "0");
        total.put("PurchasedHours", "0");
        total.put("PurchasedUnits", "0");
        total.put("TotalActualHours", "0");
        total.put("TotalActualUnits", "0");
        total.put("UnusedHours", "0");
        total.put("UnusedUnits", "0");
        total.put("OnDemandCostOfRIHoursUsed", "0");
        total.put("NetRISavings", "0");
        total.put("TotalPotentialRISavings", "0");
        total.put("AmortizedUpfrontFee", "0");
        total.put("AmortizedRecurringFee", "0");
        total.put("TotalAmortizedFee", "0");
        total.put("RICostForUnusedHours", "0");
        total.put("RealizedSavings", "0");
        total.put("UnrealizedSavings", "0");
        return response;
    }

    public ObjectNode getSavingsPlansCoverage() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("SavingsPlansCoverages");
        return response;
    }

    public ObjectNode getSavingsPlansUtilization() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("SavingsPlansUtilizationsByTime");
        ObjectNode total = response.putObject("Total");
        ObjectNode utilization = total.putObject("Utilization");
        utilization.put("TotalCommitment", "0");
        utilization.put("UsedCommitment", "0");
        utilization.put("UnusedCommitment", "0");
        utilization.put("UtilizationPercentage", "0");
        return response;
    }

    public ObjectNode getCostCategories(JsonNode request) {
        String wanted = textOrNull(request, "CostCategoryName");
        Set<String> names = new TreeSet<>();
        Set<String> values = new TreeSet<>();
        for (CostCategoryDefinition category : categoryStore.scan(key -> true)) {
            if (category.getEffectiveEnd() != null) {
                continue;
            }
            names.add(category.getName());
            if (wanted != null && !wanted.equals(category.getName())) {
                continue;
            }
            JsonNode rules = category.getRules();
            if (rules != null && rules.isArray()) {
                for (JsonNode rule : rules) {
                    String value = rule.path("Value").asText(null);
                    if (value != null && !value.isEmpty()) {
                        values.add(value);
                    }
                }
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode nameArr = response.putArray("CostCategoryNames");
        ArrayNode valueArr = response.putArray("CostCategoryValues");
        if (wanted == null) {
            for (String name : names) {
                nameArr.add(name);
            }
            response.put("ReturnSize", names.size());
            response.put("TotalSize", names.size());
        } else {
            for (String value : values) {
                valueArr.add(value);
            }
            response.put("ReturnSize", values.size());
            response.put("TotalSize", values.size());
        }
        return response;
    }

    /**
     * {@code CreateCostCategoryDefinition} — persists a new cost category and
     * returns its ARN and effective start.
     */
    public ObjectNode createCostCategoryDefinition(JsonNode request) {
        String name = requiredText(request, "Name");
        if (name.length() > 50) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'Name' failed to satisfy constraint: Member must have length less than or equal to 50.", 400);
        }
        String ruleVersion = requiredText(request, "RuleVersion");
        JsonNode rules = requireRules(request);
        if (findActiveByName(name) != null) {
            throw new AwsException("ValidationException",
                    "Cost category name already exists: " + name, 400);
        }

        String effectiveStart = textOrNull(request, "EffectiveStart");
        if (effectiveStart == null || effectiveStart.isEmpty()) {
            effectiveStart = firstOfCurrentMonth();
        }

        String arn = AwsArnUtils.Arn.of("ce", "", regionResolver.getAccountId(),
                "costcategory/" + UUID.randomUUID()).toString();
        CostCategoryDefinition category = new CostCategoryDefinition();
        category.setCostCategoryArn(arn);
        category.setName(name);
        category.setRuleVersion(ruleVersion);
        category.setRules(rules.deepCopy());
        JsonNode split = request.get("SplitChargeRules");
        if (split != null && split.isArray()) {
            category.setSplitChargeRules(split.deepCopy());
        }
        category.setDefaultValue(textOrNull(request, "DefaultValue"));
        category.setEffectiveStart(effectiveStart);
        category.setResourceTags(parseResourceTags(request.path("ResourceTags")));
        categoryStore.put(arn, category);
        LOG.infov("Created cost category {0} ({1})", name, arn);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CostCategoryArn", arn);
        response.put("EffectiveStart", effectiveStart);
        return response;
    }

    /**
     * {@code DescribeCostCategoryDefinition} — returns the full definition for an ARN.
     */
    public ObjectNode describeCostCategoryDefinition(JsonNode request) {
        CostCategoryDefinition category = requireCategory(textOrNull(request, "CostCategoryArn"), "CostCategoryArn");
        ObjectNode response = objectMapper.createObjectNode();
        response.set("CostCategory", serializeCategory(category));
        return response;
    }

    /**
     * {@code ListCostCategoryDefinitions} — currently-effective definitions for the account.
     */
    public ObjectNode listCostCategoryDefinitions(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode refs = response.putArray("CostCategoryReferences");
        for (CostCategoryDefinition category : categoryStore.scan(key -> true)) {
            if (category.getEffectiveEnd() == null) {
                refs.add(serializeReference(category));
            }
        }
        return response;
    }

    /**
     * {@code UpdateCostCategoryDefinition} — replaces rules / default / split-charge
     * in place. Name is immutable (callers replace the resource instead).
     */
    public ObjectNode updateCostCategoryDefinition(JsonNode request) {
        CostCategoryDefinition category = requireCategory(textOrNull(request, "CostCategoryArn"), "CostCategoryArn");
        category.setRuleVersion(requiredText(request, "RuleVersion"));
        category.setRules(requireRules(request).deepCopy());
        JsonNode split = request.get("SplitChargeRules");
        if (split != null && split.isArray()) {
            category.setSplitChargeRules(split.deepCopy());
        } else {
            category.setSplitChargeRules(null);
        }
        category.setDefaultValue(textOrNull(request, "DefaultValue"));
        String effectiveStart = textOrNull(request, "EffectiveStart");
        if (effectiveStart != null && !effectiveStart.isEmpty()) {
            category.setEffectiveStart(effectiveStart);
        }
        categoryStore.put(category.getCostCategoryArn(), category);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CostCategoryArn", category.getCostCategoryArn());
        response.put("EffectiveStart", category.getEffectiveStart());
        return response;
    }

    /**
     * {@code DeleteCostCategoryDefinition} — removes the definition. Idempotent
     * not-found is a {@code ResourceNotFoundException} so callers can catch it.
     */
    public ObjectNode deleteCostCategoryDefinition(JsonNode request) {
        CostCategoryDefinition category = requireCategory(textOrNull(request, "CostCategoryArn"), "CostCategoryArn");
        String effectiveEnd = firstOfCurrentMonth();
        categoryStore.delete(category.getCostCategoryArn());
        LOG.infov("Deleted cost category {0}", category.getCostCategoryArn());

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CostCategoryArn", category.getCostCategoryArn());
        response.put("EffectiveEnd", effectiveEnd);
        return response;
    }

    public ObjectNode getCostForecast(JsonNode request, String defaultRegion) {
        String metric = requiredText(request, "Metric");
        String ceMetric = switch (metric) {
            case "UNBLENDED_COST" -> "UnblendedCost";
            case "BLENDED_COST" -> "BlendedCost";
            case "AMORTIZED_COST" -> "AmortizedCost";
            case "NET_UNBLENDED_COST" -> "NetUnblendedCost";
            case "NET_AMORTIZED_COST" -> "NetAmortizedCost";
            case "USAGE_QUANTITY" -> "UsageQuantity";
            case "NORMALIZED_USAGE_AMOUNT" -> "NormalizedUsageAmount";
            default -> metric;
        };
        ObjectNode usageRequest = objectMapper.createObjectNode();
        if (request.has("TimePeriod")) {
            usageRequest.set("TimePeriod", request.get("TimePeriod"));
        }
        usageRequest.put("Granularity", request.path("Granularity").asText("MONTHLY"));
        usageRequest.putArray("Metrics").add(ceMetric);
        if (request.has("Filter")) {
            usageRequest.set("Filter", request.get("Filter"));
        }
        ObjectNode usage = runCostAndUsage(usageRequest, defaultRegion);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode forecast = response.putArray("ForecastResultsByTime");
        double totalAmount = 0;
        String unit = "USD";
        JsonNode results = usage.path("ResultsByTime");
        if (results.isArray()) {
            for (JsonNode bucket : results) {
                ObjectNode entry = forecast.addObject();
                entry.set("TimePeriod", bucket.get("TimePeriod"));
                JsonNode amountNode = bucket.path("Total").path(ceMetric);
                String amount = amountNode.path("Amount").asText("0");
                if (amountNode.hasNonNull("Unit") && !amountNode.get("Unit").asText().isEmpty()) {
                    unit = amountNode.get("Unit").asText();
                }
                entry.put("MeanValue", amount);
                entry.put("PredictionIntervalLowerBound", amount);
                entry.put("PredictionIntervalUpperBound", amount);
                try {
                    totalAmount += Double.parseDouble(amount);
                } catch (NumberFormatException ignored) {
                    // keep running total at last good parse
                }
            }
        }
        ObjectNode total = response.putObject("Total");
        total.put("Amount", GroupAggregator.formatAmount(totalAmount));
        total.put("Unit", unit);
        return response;
    }

    public ObjectNode getApproximateUsageRecords(JsonNode request, String defaultRegion) {
        if (textOrNull(request, "Granularity") == null
                || textOrNull(request, "ApproximationDimension") == null) {
            throw new AwsException("ValidationException",
                    "Granularity and ApproximationDimension are required.", 400);
        }
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(14));
        Map<String, Long> byService = new TreeMap<>();
        long total = 0;
        for (UsageLine line : collectLines(start, end, defaultRegion)) {
            byService.merge(line.service(), 1L, Long::sum);
            total += 1;
        }
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode services = response.putObject("Services");
        for (Map.Entry<String, Long> entry : byService.entrySet()) {
            services.put(entry.getKey(), entry.getValue());
        }
        response.put("TotalRecords", total);
        ObjectNode lookback = response.putObject("LookbackPeriod");
        lookback.put("Start", start.toString());
        lookback.put("End", end.toString());
        return response;
    }

    public ObjectNode getRightsizingRecommendation(JsonNode request) {
        requiredText(request, "Service");
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode summary = response.putObject("Summary");
        summary.put("TotalRecommendationCount", "0");
        summary.put("EstimatedTotalMonthlySavingsAmount", "0");
        summary.put("SavingsCurrencyCode", "USD");
        summary.put("SavingsPercentage", "0");
        response.putArray("RightsizingRecommendations");
        return response;
    }

    public ObjectNode getReservationPurchaseRecommendation(JsonNode request) {
        requiredText(request, "Service");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Recommendations");
        return response;
    }

    public ObjectNode listSavingsPlansPurchaseRecommendationGeneration() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("GenerationSummaryList");
        return response;
    }

    public ObjectNode listCommitmentPurchaseAnalyses() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("AnalysisSummaryList");
        return response;
    }

    public ObjectNode listCostAllocationTags() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("CostAllocationTags");
        return response;
    }

    public ObjectNode listCostAllocationTagBackfillHistory() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("BackfillRequests");
        return response;
    }

    public ObjectNode getAnomalies(JsonNode request) {
        JsonNode interval = request.get("DateInterval");
        if (interval == null || !interval.isObject() || interval.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'DateInterval' failed to satisfy constraint: Member must not be null.", 400);
        }
        if (textOrNull(interval, "StartDate") == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'DateInterval.StartDate' failed to satisfy constraint: Member must not be null.", 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Anomalies");
        return response;
    }

    public ObjectNode provideAnomalyFeedback(JsonNode request) {
        requiredText(request, "AnomalyId");
        requiredText(request, "Feedback");
        throw new AwsException("ValidationException",
                "Feedback is submitted for an invalid anomaly", 400);
    }

    public ObjectNode listCostCategoryResourceAssociations(JsonNode request) {
        String arn = textOrNull(request, "CostCategoryArn");
        if (arn != null) {
            requireCategory(arn, "CostCategoryArn");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("CostCategoryResourceAssociations");
        return response;
    }

    private CostCategoryDefinition requireCategory(String arn, String field) {
        if (arn == null || arn.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at '" + field + "' failed to satisfy constraint: Member must not be null.", 400);
        }
        return categoryStore.get(arn).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException",
                "No cost category found for ARN: " + arn, 400));
    }

    private CostCategoryDefinition findActiveByName(String name) {
        for (CostCategoryDefinition category : categoryStore.scan(key -> true)) {
            if (name.equals(category.getName()) && category.getEffectiveEnd() == null) {
                return category;
            }
        }
        return null;
    }

    private JsonNode requireRules(JsonNode request) {
        JsonNode rules = request.get("Rules");
        if (rules == null || !rules.isArray() || rules.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'Rules' failed to satisfy constraint: Member must have length greater than or equal to 1.", 400);
        }
        return rules;
    }

    private ObjectNode serializeCategory(CostCategoryDefinition category) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("CostCategoryArn", category.getCostCategoryArn());
        out.put("EffectiveStart", category.getEffectiveStart());
        if (category.getEffectiveEnd() != null) {
            out.put("EffectiveEnd", category.getEffectiveEnd());
        }
        out.put("Name", category.getName());
        out.put("RuleVersion", category.getRuleVersion());
        if (category.getRules() != null) {
            out.set("Rules", category.getRules());
        } else {
            out.putArray("Rules");
        }
        if (category.getSplitChargeRules() != null && category.getSplitChargeRules().isArray()
                && !category.getSplitChargeRules().isEmpty()) {
            out.set("SplitChargeRules", category.getSplitChargeRules());
        }
        if (category.getDefaultValue() != null) {
            out.put("DefaultValue", category.getDefaultValue());
        }
        ArrayNode status = out.putArray("ProcessingStatus");
        ObjectNode applied = status.addObject();
        applied.put("Component", "COST_EXPLORER");
        applied.put("Status", "APPLIED");
        return out;
    }

    private ObjectNode serializeReference(CostCategoryDefinition category) {
        ObjectNode ref = objectMapper.createObjectNode();
        ref.put("CostCategoryArn", category.getCostCategoryArn());
        ref.put("Name", category.getName());
        ref.put("EffectiveStart", category.getEffectiveStart());
        if (category.getEffectiveEnd() != null) {
            ref.put("EffectiveEnd", category.getEffectiveEnd());
        }
        JsonNode rules = category.getRules();
        ref.put("NumberOfRules", rules == null || !rules.isArray() ? 0 : rules.size());
        ArrayNode values = ref.putArray("Values");
        if (rules != null && rules.isArray()) {
            for (JsonNode rule : rules) {
                String value = rule.path("Value").asText(null);
                if (value != null && !value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        if (category.getDefaultValue() != null) {
            ref.put("DefaultValue", category.getDefaultValue());
        }
        ArrayNode status = ref.putArray("ProcessingStatus");
        ObjectNode applied = status.addObject();
        applied.put("Component", "COST_EXPLORER");
        applied.put("Status", "APPLIED");
        return ref;
    }

    private static String firstOfCurrentMonth() {
        return YearMonth.now(ZoneOffset.UTC)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .format(EFFECTIVE_DATE);
    }

    public ObjectNode createAnomalyMonitor(JsonNode request) {
        JsonNode body = request.path("AnomalyMonitor");
        if (!body.isObject() || body.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'AnomalyMonitor' failed to satisfy constraint: Member must not be null.", 400);
        }
        String name = requiredText(body, "MonitorName");
        String type = requiredText(body, "MonitorType");
        if (!"DIMENSIONAL".equals(type) && !"CUSTOM".equals(type)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'AnomalyMonitor.MonitorType' failed to satisfy constraint: Member must satisfy enum value set: [DIMENSIONAL, CUSTOM].", 400);
        }
        String dimension = textOrNull(body, "MonitorDimension");
        JsonNode specification = body.get("MonitorSpecification");
        if ("DIMENSIONAL".equals(type)) {
            if (dimension == null || dimension.isEmpty()) {
                throw new AwsException("ValidationException",
                        "MonitorDimension is required when MonitorType is DIMENSIONAL.", 400);
            }
            if ("SERVICE".equals(dimension)) {
                for (AnomalyMonitor existing : monitorStore.values()) {
                    if ("DIMENSIONAL".equals(existing.getMonitorType())
                            && "SERVICE".equals(existing.getMonitorDimension())) {
                        throw new AwsException("LimitExceededException",
                                "You have exceeded the limit of 1 SERVICE dimensional monitor per account.", 400);
                    }
                }
            }
        } else if (specification == null || !specification.isObject() || specification.isEmpty()) {
            throw new AwsException("ValidationException",
                    "MonitorSpecification is required when MonitorType is CUSTOM.", 400);
        }
        if (findByName(name) != null) {
            throw new AwsException("ValidationException",
                    "You cannot create a monitor with the same monitor name as an existing monitor.", 400);
        }

        String now = Instant.now().toString();
        String arn = "arn:aws:ce::" + regionResolver.getAccountId()
                + ":anomalymonitor/" + UUID.randomUUID();
        AnomalyMonitor monitor = new AnomalyMonitor();
        monitor.setMonitorArn(arn);
        monitor.setMonitorName(name);
        monitor.setMonitorType(type);
        monitor.setMonitorDimension(dimension);
        if (specification != null && specification.isObject() && !specification.isEmpty()) {
            monitor.setMonitorSpecification(specification.deepCopy());
        }
        monitor.setCreationDate(now);
        monitor.setLastUpdatedDate(now);
        monitor.setDimensionalValueCount(0);
        Map<String, String> tags = parseResourceTags(request.path("ResourceTags"));
        if (!tags.isEmpty()) {
            monitor.getResourceTags().putAll(tags);
        }
        monitorStore.put(arn, monitor);
        LOG.infov("Created anomaly monitor {0}", arn);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("MonitorArn", arn);
        return response;
    }

    public ObjectNode getAnomalyMonitors(JsonNode request) {
        String nextPageToken = textOrNull(request, "NextPageToken");
        if (nextPageToken != null && !nextPageToken.isEmpty()) {
            throw new AwsException("InvalidNextTokenException",
                    "The pagination token is invalid. Try again without a pagination token.", 400);
        }
        List<String> arnFilter = stringList(request.path("MonitorArnList"));
        List<AnomalyMonitor> matches = new ArrayList<>();
        if (arnFilter.isEmpty()) {
            matches.addAll(monitorStore.values());
        } else {
            for (String arn : arnFilter) {
                // Unknown ARNs yield an empty slot, not UnknownMonitorException —
                // live AWS returns AnomalyMonitors=[] for a missing ARN.
                monitorStore.get(arn).ifPresent(matches::add);
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("AnomalyMonitors");
        for (AnomalyMonitor monitor : matches) {
            arr.add(serializeMonitor(monitor));
        }
        return response;
    }

    public ObjectNode updateAnomalyMonitor(JsonNode request) {
        String arn = requiredArn(request, "MonitorArn");
        AnomalyMonitor monitor = requireMonitor(arn);
        String name = textOrNull(request, "MonitorName");
        if (name != null && !name.isEmpty() && !name.equals(monitor.getMonitorName())) {
            AnomalyMonitor clash = findByName(name);
            if (clash != null && !arn.equals(clash.getMonitorArn())) {
                throw new AwsException("ValidationException",
                        "You cannot create a monitor with the same monitor name as an existing monitor.", 400);
            }
            monitor.setMonitorName(name);
            monitor.setLastUpdatedDate(Instant.now().toString());
            monitorStore.put(arn, monitor);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("MonitorArn", arn);
        return response;
    }

    public ObjectNode deleteAnomalyMonitor(JsonNode request) {
        String arn = requiredArn(request, "MonitorArn");
        requireMonitor(arn);
        monitorStore.delete(arn);
        LOG.infov("Deleted anomaly monitor {0}", arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode createAnomalySubscription(JsonNode request) {
        JsonNode body = request.path("AnomalySubscription");
        if (!body.isObject() || body.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'AnomalySubscription' failed to satisfy constraint: Member must not be null.", 400);
        }
        String name = requiredText(body, "SubscriptionName");
        if (findSubscriptionByName(name) != null) {
            throw new AwsException("ValidationException",
                    "You cannot create a subscription with the same subscription name as an existing subscription.", 400);
        }
        String frequency = requiredText(body, "Frequency");
        if (!"DAILY".equals(frequency) && !"WEEKLY".equals(frequency) && !"IMMEDIATE".equals(frequency)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'AnomalySubscription.Frequency' failed to satisfy constraint: Member must satisfy enum value set: [DAILY, IMMEDIATE, WEEKLY].", 400);
        }
        List<String> monitorArns = stringList(body.path("MonitorArnList"));
        if (monitorArns.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'AnomalySubscription.MonitorArnList' failed to satisfy constraint: Member must have length greater than or equal to 1.", 400);
        }
        for (String monitorArn : monitorArns) {
            requireMonitor(monitorArn);
        }
        List<AnomalySubscription.Subscriber> subscribers = parseSubscribers(body.path("Subscribers"));
        if (subscribers.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'AnomalySubscription.Subscribers' failed to satisfy constraint: Member must have length greater than or equal to 1.", 400);
        }

        String accountId = regionResolver.getAccountId();
        String arn = "arn:aws:ce::" + accountId + ":anomalysubscription/" + UUID.randomUUID();
        AnomalySubscription subscription = new AnomalySubscription();
        subscription.setSubscriptionArn(arn);
        subscription.setAccountId(accountId);
        subscription.setSubscriptionName(name);
        subscription.setFrequency(frequency);
        subscription.setMonitorArnList(new ArrayList<>(monitorArns));
        subscription.setSubscribers(subscribers);
        JsonNode thresholdExpression = body.get("ThresholdExpression");
        if (thresholdExpression != null && thresholdExpression.isObject() && !thresholdExpression.isEmpty()) {
            subscription.setThresholdExpression(thresholdExpression.deepCopy());
        }
        if (body.has("Threshold") && body.get("Threshold").isNumber()) {
            subscription.setThreshold(body.get("Threshold").asDouble());
        }
        Map<String, String> tags = parseResourceTags(request.path("ResourceTags"));
        if (!tags.isEmpty()) {
            subscription.getTags().putAll(tags);
        }
        subscriptionStore.put(arn, subscription);
        LOG.infov("Created anomaly subscription {0}", arn);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("SubscriptionArn", arn);
        return response;
    }

    public ObjectNode getAnomalySubscriptions(JsonNode request) {
        String nextPageToken = textOrNull(request, "NextPageToken");
        if (nextPageToken != null && !nextPageToken.isEmpty()) {
            throw new AwsException("InvalidNextTokenException",
                    "The pagination token is invalid. Try again without a pagination token.", 400);
        }
        List<String> arnFilter = stringList(request.path("SubscriptionArnList"));
        List<AnomalySubscription> matches = new ArrayList<>();
        if (arnFilter.isEmpty() && !request.path("SubscriptionArnList").isArray()) {
            matches.addAll(subscriptionStore.values());
        } else if (!arnFilter.isEmpty()) {
            for (String arn : arnFilter) {
                // Unknown ARNs yield an empty slot, not UnknownSubscriptionException —
                // live AWS returns AnomalySubscriptions=[] for a missing ARN.
                subscriptionStore.get(arn).ifPresent(matches::add);
            }
        }
        String monitorArn = textOrNull(request, "MonitorArn");
        if (monitorArn != null && !monitorArn.isEmpty()) {
            matches.removeIf(s -> !s.getMonitorArnList().contains(monitorArn));
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("AnomalySubscriptions");
        for (AnomalySubscription subscription : matches) {
            arr.add(serializeSubscription(subscription));
        }
        return response;
    }

    public ObjectNode updateAnomalySubscription(JsonNode request) {
        String arn = requiredArn(request, "SubscriptionArn");
        AnomalySubscription subscription = requireSubscription(arn);

        String name = textOrNull(request, "SubscriptionName");
        if (name != null && !name.isEmpty() && !name.equals(subscription.getSubscriptionName())) {
            AnomalySubscription clash = findSubscriptionByName(name);
            if (clash != null && !arn.equals(clash.getSubscriptionArn())) {
                throw new AwsException("ValidationException",
                        "You cannot create a subscription with the same subscription name as an existing subscription.", 400);
            }
            subscription.setSubscriptionName(name);
        }
        String frequency = textOrNull(request, "Frequency");
        if (frequency != null && !frequency.isEmpty()) {
            if (!"DAILY".equals(frequency) && !"WEEKLY".equals(frequency) && !"IMMEDIATE".equals(frequency)) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value at 'Frequency' failed to satisfy constraint: Member must satisfy enum value set: [DAILY, IMMEDIATE, WEEKLY].", 400);
            }
            subscription.setFrequency(frequency);
        }
        if (request.path("MonitorArnList").isArray()) {
            List<String> monitorArns = stringList(request.path("MonitorArnList"));
            if (monitorArns.isEmpty()) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value at 'MonitorArnList' failed to satisfy constraint: Member must have length greater than or equal to 1.", 400);
            }
            for (String monitorArn : monitorArns) {
                requireMonitor(monitorArn);
            }
            subscription.setMonitorArnList(new ArrayList<>(monitorArns));
        }
        if (request.path("Subscribers").isArray()) {
            List<AnomalySubscription.Subscriber> subscribers = parseSubscribers(request.path("Subscribers"));
            if (subscribers.isEmpty()) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value at 'Subscribers' failed to satisfy constraint: Member must have length greater than or equal to 1.", 400);
            }
            subscription.setSubscribers(subscribers);
        }
        if (request.has("ThresholdExpression") && !request.get("ThresholdExpression").isNull()) {
            subscription.setThresholdExpression(request.get("ThresholdExpression").deepCopy());
        }
        if (request.has("Threshold") && request.get("Threshold").isNumber()) {
            subscription.setThreshold(request.get("Threshold").asDouble());
        }
        subscriptionStore.put(arn, subscription);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("SubscriptionArn", arn);
        return response;
    }

    public ObjectNode deleteAnomalySubscription(JsonNode request) {
        String arn = requiredArn(request, "SubscriptionArn");
        requireSubscription(arn);
        subscriptionStore.delete(arn);
        LOG.infov("Deleted anomaly subscription {0}", arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        String arn = requiredArn(request, "ResourceArn");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("ResourceTags");
        for (Map.Entry<String, String> entry : tagsForArn(arn).entrySet()) {
            ObjectNode tag = arr.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue() == null ? "" : entry.getValue());
        }
        return response;
    }

    public ObjectNode tagResource(JsonNode request) {
        String arn = requiredArn(request, "ResourceArn");
        Map<String, String> tags = mutableTagsForArn(arn);
        tags.putAll(parseResourceTags(request.path("ResourceTags")));
        persistTaggedResource(arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        String arn = requiredArn(request, "ResourceArn");
        Map<String, String> tags = mutableTagsForArn(arn);
        for (String key : stringList(request.path("ResourceTagKeys"))) {
            tags.remove(key);
        }
        persistTaggedResource(arn);
        return objectMapper.createObjectNode();
    }

    private List<UsageLine> collectLines(Instant start, Instant end, String region) {
        List<UsageLine> all = new ArrayList<>();
        for (ResourceUsageEnumerator enumerator : enumerators) {
            try {
                enumerator.enumerate(start, end, region).forEach(all::add);
            } catch (Exception e) {
                LOG.warnv(e, "Enumerator {0} failed", enumerator.getClass().getSimpleName());
            }
        }
        return all;
    }

    private TimeWindow parseTimeWindow(JsonNode request) {
        JsonNode tp = request.path("TimePeriod");
        if (!tp.isObject() || tp.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'TimePeriod' failed to satisfy constraint: Member must not be null.", 400);
        }
        Instant start = TimeBucketing.parseDate(tp.path("Start").asText(null), "TimePeriod.Start");
        Instant end = TimeBucketing.parseDate(tp.path("End").asText(null), "TimePeriod.End");
        return new TimeWindow(start, end);
    }

    private static boolean overlaps(UsageLine line, TimeBucketing.Bucket bucket) {
        return line.periodStart().isBefore(bucket.end()) && line.periodEnd().isAfter(bucket.start());
    }

    /**
     * Returns a copy of {@code line} with quantity scaled to the fraction of the
     * line's window that falls inside {@code bucket}. Keeps daily/hourly outputs
     * proportional when the request window doesn't align to the line's day.
     */
    private static UsageLine scaleToBucket(UsageLine line, TimeBucketing.Bucket bucket) {
        Instant overlapStart = line.periodStart().isAfter(bucket.start()) ? line.periodStart() : bucket.start();
        Instant overlapEnd = line.periodEnd().isBefore(bucket.end()) ? line.periodEnd() : bucket.end();
        long lineSeconds = Math.max(1, line.periodEnd().getEpochSecond() - line.periodStart().getEpochSecond());
        long overlapSeconds = Math.max(0, overlapEnd.getEpochSecond() - overlapStart.getEpochSecond());
        double factor = overlapSeconds / (double) lineSeconds;
        return new UsageLine(
                bucket.start(), bucket.end(),
                line.service(), line.region(), line.usageType(), line.operation(),
                line.recordType(), line.linkedAccountId(), line.resourceId(),
                line.tags(),
                line.quantity() * factor,
                line.usageUnit());
    }

    private record TimeWindow(Instant start, Instant end) {}

    private AnomalyMonitor requireMonitor(String arn) {
        rejectForeignAccount(arn);
        return monitorStore.get(arn).orElseThrow(() -> unknownMonitor());
    }

    private Map<String, String> tagsForArn(String arn) {
        return new LinkedHashMap<>(mutableTagsForArn(arn));
    }

    private Map<String, String> mutableTagsForArn(String arn) {
        rejectForeignAccount(arn);
        if (arn.contains(":costcategory/")) {
            CostCategoryDefinition category = requireCategory(arn, "ResourceArn");
            if (category.getResourceTags() == null) {
                category.setResourceTags(new LinkedHashMap<>());
            }
            return category.getResourceTags();
        }
        if (arn.contains(":anomalysubscription/")) {
            return requireSubscription(arn).getTags();
        }
        return requireTaggedMonitor(arn).getResourceTags();
    }

    private void persistTaggedResource(String arn) {
        if (arn.contains(":costcategory/")) {
            categoryStore.get(arn).ifPresent(category -> categoryStore.put(arn, category));
            return;
        }
        if (arn.contains(":anomalysubscription/")) {
            subscriptionStore.get(arn).ifPresent(subscription -> subscriptionStore.put(arn, subscription));
            return;
        }
        monitorStore.get(arn).ifPresent(monitor -> monitorStore.put(arn, monitor));
    }

    private AnomalyMonitor requireTaggedMonitor(String arn) {
        rejectForeignAccount(arn);
        return monitorStore.get(arn).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The specified resource does not exist.", 404));
    }

    private AnomalyMonitor findByName(String name) {
        for (AnomalyMonitor monitor : monitorStore.values()) {
            if (name.equals(monitor.getMonitorName())) {
                return monitor;
            }
        }
        return null;
    }

    private AnomalySubscription requireSubscription(String arn) {
        rejectForeignAccount(arn);
        return subscriptionStore.get(arn).orElseThrow(() -> unknownSubscription());
    }

    private AnomalySubscription findSubscriptionByName(String name) {
        for (AnomalySubscription subscription : subscriptionStore.values()) {
            if (name.equals(subscription.getSubscriptionName())) {
                return subscription;
            }
        }
        return null;
    }

    private static AwsException unknownSubscription() {
        return new AwsException("UnknownSubscriptionException",
                "The cost anomaly subscription does not exist for the account.", 404);
    }

    private List<AnomalySubscription.Subscriber> parseSubscribers(JsonNode node) {
        List<AnomalySubscription.Subscriber> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode entry : node) {
            String address = textOrNull(entry, "Address");
            String type = textOrNull(entry, "Type");
            if (address == null || address.isEmpty() || type == null || type.isEmpty()) {
                throw new AwsException("ValidationException",
                        "Each subscriber must include Address and Type.", 400);
            }
            if (!"EMAIL".equals(type) && !"SNS".equals(type)) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value at 'Subscribers.Type' failed to satisfy constraint: Member must satisfy enum value set: [EMAIL, SNS].", 400);
            }
            String status = textOrNull(entry, "Status");
            if (status == null || status.isEmpty()) {
                status = "CONFIRMED";
            }
            out.add(new AnomalySubscription.Subscriber(address, type, status));
        }
        return out;
    }

    private ObjectNode serializeSubscription(AnomalySubscription subscription) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("SubscriptionArn", subscription.getSubscriptionArn());
        if (subscription.getAccountId() != null) {
            out.put("AccountId", subscription.getAccountId());
        }
        out.put("SubscriptionName", subscription.getSubscriptionName());
        out.put("Frequency", subscription.getFrequency());
        ArrayNode monitors = out.putArray("MonitorArnList");
        for (String monitorArn : subscription.getMonitorArnList()) {
            monitors.add(monitorArn);
        }
        ArrayNode subscribers = out.putArray("Subscribers");
        for (AnomalySubscription.Subscriber subscriber : subscription.getSubscribers()) {
            ObjectNode node = subscribers.addObject();
            if (subscriber.getAddress() != null) {
                node.put("Address", subscriber.getAddress());
            }
            if (subscriber.getType() != null) {
                node.put("Type", subscriber.getType());
            }
            if (subscriber.getStatus() != null) {
                node.put("Status", subscriber.getStatus());
            }
        }
        if (subscription.getThreshold() != null) {
            out.put("Threshold", subscription.getThreshold());
        }
        if (subscription.getThresholdExpression() != null) {
            out.set("ThresholdExpression", subscription.getThresholdExpression());
        }
        return out;
    }

    private void rejectForeignAccount(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!parsed.accountId().isEmpty()
                    && !parsed.accountId().equals(regionResolver.getAccountId())) {
                throw new AwsException("AccessDeniedException",
                        "You do not have permission to access this resource.", 403);
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed ARNs fall through to the not-found path of the caller.
        }
    }

    private static AwsException unknownMonitor() {
        return new AwsException("UnknownMonitorException",
                "The cost anomaly monitor does not exist for the account.", 400);
    }

    private ObjectNode serializeMonitor(AnomalyMonitor monitor) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("MonitorArn", monitor.getMonitorArn());
        out.put("MonitorName", monitor.getMonitorName());
        out.put("MonitorType", monitor.getMonitorType());
        if (monitor.getMonitorDimension() != null) {
            out.put("MonitorDimension", monitor.getMonitorDimension());
        }
        if (monitor.getMonitorSpecification() != null) {
            out.set("MonitorSpecification", monitor.getMonitorSpecification());
        }
        if (monitor.getCreationDate() != null) {
            out.put("CreationDate", monitor.getCreationDate());
        }
        if (monitor.getLastUpdatedDate() != null) {
            out.put("LastUpdatedDate", monitor.getLastUpdatedDate());
        }
        out.put("DimensionalValueCount", monitor.getDimensionalValueCount());
        return out;
    }

    private static String requiredArn(JsonNode request, String field) {
        String arn = textOrNull(request, field);
        if (arn == null || arn.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at '" + field
                            + "' failed to satisfy constraint: Member must not be null.", 400);
        }
        return arn;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value == null || value.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at '" + field
                            + "' failed to satisfy constraint: Member must not be null.", 400);
        }
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode value : node) {
                result.add(value.asText());
            }
        }
        return result;
    }

    private static Map<String, String> parseResourceTags(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isArray()) {
            for (JsonNode entry : node) {
                String key = textOrNull(entry, "Key");
                String value = textOrNull(entry, "Value");
                if (key != null) {
                    out.put(key, value == null ? "" : value);
                }
            }
        }
        return out;
    }
}
