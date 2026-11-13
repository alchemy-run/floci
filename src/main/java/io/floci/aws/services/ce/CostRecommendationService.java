package io.floci.aws.services.ce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.UsageLine;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.Dimension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static io.floci.aws.services.ce.CeRequests.invalid;
import static io.floci.aws.services.ce.CeRequests.object;
import static io.floci.aws.services.ce.CeRequests.oneOf;
import static io.floci.aws.services.ce.CeRequests.page;
import static io.floci.aws.services.ce.CeRequests.present;
import static io.floci.aws.services.ce.CeRequests.strings;
import static io.floci.aws.services.ce.CeRequests.text;

/**
 * Commitment and rightsizing recommendations computed from the account's synthesized EC2 and Lambda usage.
 * <p>
 * Reserved Instance and Savings Plans recommendations size the commitment to the steady on-demand usage seen over
 * the lookback period and price it with the published discount for the term and payment option. Rightsizing reads
 * each running instance's {@code AWS/EC2 CPUUtilization} from CloudWatch: an instance whose peak stays at or below
 * 1% is idle and recommended for termination, one whose peak stays below 40% is recommended one size down in its
 * family, and an instance without metrics gets no recommendation, as on AWS.
 */
@ApplicationScoped
public class CostRecommendationService implements Resettable {

    static final String EC2_RESERVATION_SERVICE = "Amazon Elastic Compute Cloud - Compute";
    private static final Set<String> RESERVATION_SERVICES = Set.of(EC2_RESERVATION_SERVICE,
            "Amazon Relational Database Service", "Amazon Redshift", "Amazon ElastiCache",
            "Amazon Elasticsearch Service", "Amazon OpenSearch Service", "Amazon MemoryDB");
    private static final Set<String> TERMS = Set.of("ONE_YEAR", "THREE_YEARS");
    private static final Set<String> PAYMENT_OPTIONS = Set.of("NO_UPFRONT", "PARTIAL_UPFRONT", "ALL_UPFRONT");
    private static final Set<String> LOOKBACKS = Set.of("SEVEN_DAYS", "THIRTY_DAYS", "SIXTY_DAYS");
    private static final Set<String> SAVINGS_PLAN_TYPES = Set.of("COMPUTE_SP", "EC2_INSTANCE_SP", "SAGEMAKER_SP",
            "DATABASE_SP");
    private static final List<String> SIZES = List.of("nano", "micro", "small", "medium", "large", "xlarge",
            "2xlarge", "3xlarge", "4xlarge", "6xlarge", "8xlarge", "9xlarge", "10xlarge", "12xlarge", "16xlarge",
            "18xlarge", "24xlarge", "32xlarge", "48xlarge");
    private static final double HOURS_PER_MONTH = 730;

    private final CostExplorerService costs;
    private final CloudWatchMetricsService metrics;
    private final ObjectMapper mapper;
    private final AccountAwareStorageBackend<ObjectNode> generations;
    private final AccountAwareStorageBackend<ObjectNode> details;
    private final AccountAwareStorageBackend<ObjectNode> analyses;

    @Inject
    public CostRecommendationService(CostExplorerService costs, CloudWatchMetricsService metrics,
                                     ObjectMapper mapper, StorageFactory storageFactory) {
        this.costs = costs;
        this.metrics = metrics;
        this.mapper = mapper;
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.generations = storageFactory.create("ce", "recommendation-generations.json", type);
        this.details = storageFactory.create("ce", "savings-plans-recommendation-details.json", type);
        this.analyses = storageFactory.create("ce", "commitment-purchase-analyses.json", type);
    }

    public ObjectNode getReservationPurchaseRecommendation(JsonNode request, String region) {
        String service = oneOf(request, "Service", RESERVATION_SERVICES, null);
        String scope = oneOf(request, "AccountScope", Set.of("PAYER", "LINKED"), "PAYER");
        String lookback = oneOf(request, "LookbackPeriodInDays", LOOKBACKS, "SEVEN_DAYS");
        String term = oneOf(request, "TermInYears", TERMS, "ONE_YEAR");
        String payment = oneOf(request, "PaymentOption", PAYMENT_OPTIONS, "NO_UPFRONT");
        String offeringClass = oneOf(request.path("ServiceSpecification").path("EC2Specification"), "OfferingClass",
                Set.of("STANDARD", "CONVERTIBLE"), "STANDARD");
        String accountFilter = present(request, "AccountId") ? text(request, "AccountId", 12) : null;
        ObjectNode response = mapper.createObjectNode();
        ObjectNode metadata = response.putObject("Metadata");
        metadata.put("RecommendationId", UUID.randomUUID().toString());
        metadata.put("GenerationTimestamp", now());
        List<ObjectNode> recommendationDetails = new ArrayList<>();
        double savings = 0;
        double onDemand = 0;
        if (EC2_RESERVATION_SERVICE.equals(service)) {
            double fraction = reservedFraction(term, payment, offeringClass);
            for (InstanceUsage usage : instanceUsage(region, lookback)) {
                if (accountFilter != null && !accountFilter.equals(usage.accountId())) {
                    continue;
                }
                int count = usage.instances().size();
                double units = normalizationFactor(usage.instanceType()) * count;
                double monthlyOnDemand = usage.hourlyRate() * count * HOURS_PER_MONTH;
                double effectiveHourly = usage.hourlyRate() * fraction;
                double upfront = upfront(payment, effectiveHourly * count, term);
                double recurringMonthly = effectiveHourly * count * HOURS_PER_MONTH
                        - upfront / termMonths(term);
                double monthlySavings = monthlyOnDemand * (1 - fraction);
                savings += monthlySavings;
                onDemand += monthlyOnDemand;
                ObjectNode detail = mapper.createObjectNode();
                detail.put("AccountId", usage.accountId());
                ObjectNode instance = detail.putObject("InstanceDetails").putObject("EC2InstanceDetails");
                instance.put("Family", family(usage.instanceType()));
                instance.put("InstanceType", usage.instanceType());
                instance.put("Region", usage.region());
                instance.put("Platform", "Linux/UNIX");
                instance.put("Tenancy", "shared");
                instance.put("CurrentGeneration", true);
                instance.put("SizeFlexEligible", true);
                detail.put("RecommendedNumberOfInstancesToPurchase", Integer.toString(count));
                detail.put("RecommendedNormalizedUnitsToPurchase", amount(units));
                for (String statistic : List.of("Minimum", "Maximum", "Average")) {
                    detail.put(statistic + "NumberOfInstancesUsedPerHour", amount(count));
                    detail.put(statistic + "NormalizedUnitsUsedPerHour", amount(units));
                }
                detail.put("AverageUtilization", "100.0");
                detail.put("EstimatedBreakEvenInMonths", amount(monthlySavings > 0
                        ? upfront / monthlyOnDemand : 0));
                detail.put("CurrencyCode", "USD");
                detail.put("EstimatedMonthlySavingsAmount", amount(monthlySavings));
                detail.put("EstimatedMonthlySavingsPercentage", amount((1 - fraction) * 100));
                detail.put("EstimatedMonthlyOnDemandCost", amount(monthlyOnDemand));
                detail.put("EstimatedReservationCostForLookbackPeriod",
                        amount(effectiveHourly * count * 24 * lookbackDays(lookback)));
                detail.put("UpfrontCost", amount(upfront));
                detail.put("RecurringStandardMonthlyCost", amount(recurringMonthly));
                recommendationDetails.add(detail);
            }
        }
        PaginatedResult<ObjectNode> paged = page(recommendationDetails,
                detail -> detail.path("AccountId").asText() + "/" + detail.path("InstanceDetails").toString(),
                request, "PageSize", "NextPageToken", 100, 100000);
        ArrayNode recommendations = response.putArray("Recommendations");
        if (!recommendationDetails.isEmpty()) {
            ObjectNode recommendation = recommendations.addObject();
            recommendation.put("AccountScope", scope);
            recommendation.put("LookbackPeriodInDays", lookback);
            recommendation.put("TermInYears", term);
            recommendation.put("PaymentOption", payment);
            recommendation.putObject("ServiceSpecification").putObject("EC2Specification")
                    .put("OfferingClass", offeringClass);
            recommendation.putArray("RecommendationDetails").addAll(paged.items());
            ObjectNode summary = recommendation.putObject("RecommendationSummary");
            summary.put("TotalEstimatedMonthlySavingsAmount", amount(savings));
            summary.put("TotalEstimatedMonthlySavingsPercentage", amount(onDemand > 0 ? savings / onDemand * 100 : 0));
            summary.put("CurrencyCode", "USD");
        }
        if (paged.nextToken() != null) {
            response.put("NextPageToken", paged.nextToken());
        }
        return response;
    }

    public synchronized ObjectNode getSavingsPlansPurchaseRecommendation(JsonNode request, String region) {
        String type = oneOf(request, "SavingsPlansType", SAVINGS_PLAN_TYPES, null);
        String term = oneOf(request, "TermInYears", TERMS, null);
        String payment = oneOf(request, "PaymentOption", PAYMENT_OPTIONS, null);
        String lookback = oneOf(request, "LookbackPeriodInDays", LOOKBACKS, null);
        String scope = oneOf(request, "AccountScope", Set.of("PAYER", "LINKED"), "PAYER");
        String generatedAt = latestGeneration().map(value -> value.path("GenerationCompletionTime").asText())
                .orElse(now());
        Map<String, double[]> spendByScope = new TreeMap<>();
        Map<String, String[]> scopeKeys = new LinkedHashMap<>();
        if ("COMPUTE_SP".equals(type) || "EC2_INSTANCE_SP".equals(type)) {
            for (InstanceUsage usage : instanceUsage(region, lookback)) {
                String family = "EC2_INSTANCE_SP".equals(type) ? family(usage.instanceType()) : "";
                String spRegion = "EC2_INSTANCE_SP".equals(type) ? usage.region() : "";
                String key = usage.accountId() + "|" + spRegion + "|" + family;
                spendByScope.computeIfAbsent(key, k -> new double[1])[0]
                        += usage.hourlyRate() * usage.instances().size();
                scopeKeys.put(key, new String[] {usage.accountId(), spRegion, family});
            }
        }
        if ("COMPUTE_SP".equals(type)) {
            addLambdaSpend(region, lookback, spendByScope, scopeKeys);
        }
        double fraction = savingsPlanFraction(type, term, payment);
        ObjectNode response = mapper.createObjectNode();
        ObjectNode metadata = response.putObject("Metadata");
        metadata.put("RecommendationId", latestGeneration().map(value -> value.path("RecommendationId").asText())
                .orElse(UUID.randomUUID().toString()));
        metadata.put("GenerationTimestamp", generatedAt);
        ObjectNode recommendation = response.putObject("SavingsPlansPurchaseRecommendation");
        recommendation.put("AccountScope", scope);
        recommendation.put("SavingsPlansType", type);
        recommendation.put("TermInYears", term);
        recommendation.put("PaymentOption", payment);
        recommendation.put("LookbackPeriodInDays", lookback);
        List<ObjectNode> rows = new ArrayList<>();
        double totalCommitment = 0;
        double totalOnDemand = 0;
        double totalUpfront = 0;
        for (Map.Entry<String, double[]> entry : spendByScope.entrySet()) {
            double hourlyOnDemand = entry.getValue()[0];
            if (hourlyOnDemand <= 0) {
                continue;
            }
            String[] keys = scopeKeys.get(entry.getKey());
            double commitment = hourlyOnDemand * fraction;
            double upfront = upfront(payment, commitment, term);
            double lookbackHours = lookbackDays(lookback) * 24.0;
            String detailId = UUID.randomUUID().toString();
            ObjectNode row = mapper.createObjectNode();
            ObjectNode spDetails = row.putObject("SavingsPlansDetails");
            if (!keys[1].isEmpty()) {
                spDetails.put("Region", keys[1]);
                spDetails.put("InstanceFamily", keys[2]);
            }
            spDetails.put("OfferingId", offeringId(type, term, payment, keys[1], keys[2]));
            row.put("AccountId", keys[0]);
            row.put("UpfrontCost", amount(upfront));
            row.put("EstimatedROI", amount((1 - fraction) / fraction * 100));
            row.put("CurrencyCode", "USD");
            row.put("EstimatedSPCost", amount(commitment * lookbackHours));
            row.put("EstimatedOnDemandCost", "0");
            row.put("EstimatedOnDemandCostWithCurrentCommitment", amount(hourlyOnDemand * lookbackHours));
            row.put("EstimatedSavingsAmount", amount((hourlyOnDemand - commitment) * lookbackHours));
            row.put("EstimatedSavingsPercentage", amount((1 - fraction) * 100));
            row.put("HourlyCommitmentToPurchase", amount(commitment));
            row.put("EstimatedAverageUtilization", "100.0");
            row.put("EstimatedMonthlySavingsAmount", amount((hourlyOnDemand - commitment) * HOURS_PER_MONTH));
            row.put("CurrentMinimumHourlyOnDemandSpend", amount(hourlyOnDemand));
            row.put("CurrentMaximumHourlyOnDemandSpend", amount(hourlyOnDemand));
            row.put("CurrentAverageHourlyOnDemandSpend", amount(hourlyOnDemand));
            row.put("RecommendationDetailId", detailId);
            rows.add(row);
            details.putForAccount(details.accountId(), detailId,
                    detailData(row, scope, type, term, payment, lookback, generatedAt, hourlyOnDemand, commitment));
            totalCommitment += commitment;
            totalOnDemand += hourlyOnDemand;
            totalUpfront += upfront;
        }
        PaginatedResult<ObjectNode> paged = page(rows, row -> row.path("RecommendationDetailId").asText(), request,
                "PageSize", "NextPageToken", 1000, 100000);
        recommendation.putArray("SavingsPlansPurchaseRecommendationDetails").addAll(paged.items());
        if (!rows.isEmpty()) {
            ObjectNode summary = recommendation.putObject("SavingsPlansPurchaseRecommendationSummary");
            double lookbackHours = lookbackDays(lookback) * 24.0;
            summary.put("EstimatedROI", amount((1 - fraction) / fraction * 100));
            summary.put("CurrencyCode", "USD");
            summary.put("EstimatedTotalCost", amount(totalCommitment * lookbackHours));
            summary.put("CurrentOnDemandSpend", amount(totalOnDemand * lookbackHours));
            summary.put("EstimatedSavingsAmount", amount((totalOnDemand - totalCommitment) * lookbackHours));
            summary.put("TotalRecommendationCount", Integer.toString(rows.size()));
            summary.put("DailyCommitmentToPurchase", amount(totalCommitment * 24));
            summary.put("HourlyCommitmentToPurchase", amount(totalCommitment));
            summary.put("EstimatedSavingsPercentage", amount((1 - fraction) * 100));
            summary.put("EstimatedMonthlySavingsAmount", amount((totalOnDemand - totalCommitment) * HOURS_PER_MONTH));
            summary.put("EstimatedOnDemandCostWithCurrentCommitment", amount(totalOnDemand * lookbackHours));
            summary.put("UpfrontCost", amount(totalUpfront));
        }
        if (paged.nextToken() != null) {
            response.put("NextPageToken", paged.nextToken());
        }
        return response;
    }

    public ObjectNode getSavingsPlanPurchaseRecommendationDetails(JsonNode request) {
        String id = text(request, "RecommendationDetailId", 36);
        ObjectNode data = details.getForAccount(details.accountId(), id).orElseThrow(() ->
                new AwsException("DataUnavailableException", "No recommendation exists with that detail id.", 400));
        ObjectNode response = mapper.createObjectNode();
        response.put("RecommendationDetailId", id);
        response.set("RecommendationDetailData", data);
        return response;
    }

    public ObjectNode getRightsizingRecommendation(JsonNode request, String region) {
        if (!"AmazonEC2".equals(text(request, "Service", 1024))) {
            throw invalid("Rightsizing recommendations are only available for AmazonEC2.");
        }
        JsonNode configuration = request.path("Configuration");
        String target = oneOf(configuration, "RecommendationTarget", Set.of("SAME_INSTANCE_FAMILY",
                "CROSS_INSTANCE_FAMILY"), "SAME_INSTANCE_FAMILY");
        boolean benefits = !configuration.has("BenefitsConsidered") || configuration.get("BenefitsConsidered")
                .asBoolean(true);
        Instant end = Instant.now();
        Instant start = end.minus(14, ChronoUnit.DAYS);
        List<ObjectNode> recommendations = new ArrayList<>();
        double totalSavings = 0;
        double totalCost = 0;
        for (InstanceUsage usage : instanceUsage(region, "SEVEN_DAYS")) {
            for (String instanceId : usage.instances()) {
                List<CloudWatchMetricsService.Datapoint> points = metrics.getMetricStatistics("AWS/EC2",
                        "CPUUtilization", List.of(new Dimension("InstanceId", instanceId)), start, end, 86400,
                        List.of("Maximum", "Average"), null, usage.region());
                if (points.isEmpty()) {
                    continue;
                }
                double maxCpu = points.stream().mapToDouble(CloudWatchMetricsService.Datapoint::maximum).max()
                        .orElse(0);
                double monthly = usage.hourlyRate() * HOURS_PER_MONTH;
                String smaller = smallerSize(usage.instanceType());
                String type;
                if (maxCpu <= 1.0) {
                    type = "TERMINATE";
                } else if (maxCpu < 40.0 && smaller != null) {
                    type = "MODIFY";
                } else {
                    continue;
                }
                ObjectNode recommendation = mapper.createObjectNode();
                recommendation.put("AccountId", usage.accountId());
                ObjectNode current = recommendation.putObject("CurrentInstance");
                current.put("ResourceId", instanceId);
                current.put("InstanceName", instanceId);
                current.putArray("Tags");
                ObjectNode resourceDetails = current.putObject("ResourceDetails").putObject("EC2ResourceDetails");
                resourceDetails.put("HourlyOnDemandRate", amount(usage.hourlyRate()));
                resourceDetails.put("InstanceType", usage.instanceType());
                resourceDetails.put("Platform", "Linux/UNIX");
                resourceDetails.put("Region", usage.region());
                ObjectNode utilization = current.putObject("ResourceUtilization").putObject("EC2ResourceUtilization");
                utilization.put("MaxCpuUtilizationPercentage", amount(maxCpu));
                utilization.put("MaxMemoryUtilizationPercentage", "");
                utilization.put("MaxStorageUtilizationPercentage", "");
                current.put("ReservationCoveredHoursInLookbackPeriod", "0");
                current.put("SavingsPlansCoveredHoursInLookbackPeriod", "0");
                current.put("OnDemandHoursInLookbackPeriod", amount(14 * 24));
                current.put("TotalRunningHoursInLookbackPeriod", amount(14 * 24));
                current.put("MonthlyCost", amount(monthly));
                current.put("CurrencyCode", "USD");
                recommendation.put("RightsizingType", type);
                double savings;
                if ("TERMINATE".equals(type)) {
                    savings = monthly;
                    ObjectNode terminate = recommendation.putObject("TerminateRecommendationDetail");
                    terminate.put("EstimatedMonthlySavings", amount(savings));
                    terminate.put("CurrencyCode", "USD");
                } else {
                    double targetRate = usage.hourlyRate() / 2;
                    savings = monthly - targetRate * HOURS_PER_MONTH;
                    ObjectNode targetInstance = recommendation.putObject("ModifyRecommendationDetail")
                            .putArray("TargetInstances").addObject();
                    targetInstance.put("EstimatedMonthlyCost", amount(targetRate * HOURS_PER_MONTH));
                    targetInstance.put("EstimatedMonthlySavings", amount(savings));
                    targetInstance.put("CurrencyCode", "USD");
                    targetInstance.put("DefaultTargetInstance", true);
                    ObjectNode targetDetails = targetInstance.putObject("ResourceDetails")
                            .putObject("EC2ResourceDetails");
                    targetDetails.put("HourlyOnDemandRate", amount(targetRate));
                    targetDetails.put("InstanceType", smaller);
                    targetDetails.put("Platform", "Linux/UNIX");
                    targetDetails.put("Region", usage.region());
                    targetInstance.putObject("ExpectedResourceUtilization").putObject("EC2ResourceUtilization")
                            .put("MaxCpuUtilizationPercentage", amount(Math.min(100, maxCpu * 2)));
                    targetInstance.putArray("PlatformDifferences");
                }
                recommendation.putArray("FindingReasonCodes").add("CPU_OVER_PROVISIONED");
                recommendations.add(recommendation);
                totalSavings += savings;
                totalCost += monthly;
            }
        }
        recommendations.sort(Comparator.comparing(value -> value.path("CurrentInstance").path("ResourceId").asText()));
        PaginatedResult<ObjectNode> paged = page(recommendations,
                value -> value.path("CurrentInstance").path("ResourceId").asText(), request, "PageSize",
                "NextPageToken", 100, 100000);
        ObjectNode response = mapper.createObjectNode();
        ObjectNode metadata = response.putObject("Metadata");
        metadata.put("RecommendationId", UUID.randomUUID().toString());
        metadata.put("GenerationTimestamp", now());
        metadata.put("LookbackPeriodInDays", "FOURTEEN_DAYS");
        ObjectNode summary = response.putObject("Summary");
        summary.put("TotalRecommendationCount", Integer.toString(recommendations.size()));
        summary.put("EstimatedTotalMonthlySavingsAmount", amount(totalSavings));
        summary.put("SavingsCurrencyCode", "USD");
        summary.put("SavingsPercentage", amount(totalCost > 0 ? totalSavings / totalCost * 100 : 0));
        response.putArray("RightsizingRecommendations").addAll(paged.items());
        ObjectNode echoed = response.putObject("Configuration");
        echoed.put("RecommendationTarget", target);
        echoed.put("BenefitsConsidered", benefits);
        if (paged.nextToken() != null) {
            response.put("NextPageToken", paged.nextToken());
        }
        return response;
    }

    /** Starts a Savings Plans recommendation refresh; Floci computes recommendations on read, so it completes at once. */
    public synchronized ObjectNode startSavingsPlansPurchaseRecommendationGeneration() {
        String account = generations.accountId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        long today = generations.scanForAccount(account, key -> true).stream()
                .filter(value -> Instant.parse(value.path("GenerationStartedTime").asText())
                        .isAfter(now.minus(1, ChronoUnit.DAYS)))
                .count();
        if (today >= 3) {
            throw new AwsException("ServiceQuotaExceededException",
                    "You can generate Savings Plans recommendations at most 3 times a day.", 400);
        }
        String id = UUID.randomUUID().toString();
        ObjectNode generation = mapper.createObjectNode();
        generation.put("RecommendationId", id);
        generation.put("GenerationStatus", "SUCCEEDED");
        generation.put("GenerationStartedTime", now.toString());
        generation.put("GenerationCompletionTime", now.toString());
        generation.put("EstimatedCompletionTime", now.toString());
        generations.putForAccount(account, id, generation);
        ObjectNode response = mapper.createObjectNode();
        response.put("RecommendationId", id);
        response.put("GenerationStartedTime", now.toString());
        response.put("EstimatedCompletionTime", now.toString());
        return response;
    }

    public synchronized ObjectNode listSavingsPlansPurchaseRecommendationGeneration(JsonNode request) {
        String status = present(request, "GenerationStatus")
                ? oneOf(request, "GenerationStatus", Set.of("SUCCEEDED", "PROCESSING", "FAILED"), null) : null;
        Set<String> ids = present(request, "RecommendationIds")
                ? new HashSet<>(strings(request, "RecommendationIds", 100, 36)) : Set.of();
        List<ObjectNode> summaries = generations.scanForAccount(generations.accountId(), key -> true).stream()
                .filter(value -> status == null || status.equals(value.path("GenerationStatus").asText()))
                .filter(value -> ids.isEmpty() || ids.contains(value.path("RecommendationId").asText()))
                .sorted(Comparator.comparing((ObjectNode value) -> value.path("GenerationStartedTime").asText())
                        .reversed())
                .toList();
        PaginatedResult<ObjectNode> paged = page(summaries, value -> value.path("RecommendationId").asText(),
                request, "PageSize", "NextPageToken", 100, 6000);
        ObjectNode response = mapper.createObjectNode();
        response.putArray("GenerationSummaryList").addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextPageToken", paged.nextToken());
        }
        return response;
    }

    /**
     * Models adding or excluding Savings Plans against the lookback usage. A MAX_SAVINGS analysis sizes the
     * commitment to the steady compute spend; a CUSTOM_COMMITMENT analysis prices the commitments given.
     */
    public synchronized ObjectNode startCommitmentPurchaseAnalysis(JsonNode request, String region) {
        ObjectNode configuration = object(request, "CommitmentPurchaseAnalysisConfiguration");
        ObjectNode analysis = object(configuration, "SavingsPlansPurchaseAnalysisConfiguration");
        oneOf(analysis, "AccountScope", Set.of("PAYER", "LINKED"), "PAYER");
        String type = oneOf(analysis, "AnalysisType", Set.of("MAX_SAVINGS", "CUSTOM_COMMITMENT"), null);
        ObjectNode lookback = object(analysis, "LookBackTimePeriod");
        Instant start = TimeBucketing.parseDate(text(lookback, "Start", 40), "LookBackTimePeriod.Start");
        Instant end = TimeBucketing.parseDate(text(lookback, "End", 40), "LookBackTimePeriod.End");
        if (!end.isAfter(start) || ChronoUnit.DAYS.between(start, end) > 60) {
            throw invalid("LookBackTimePeriod must span between 1 and 60 days.");
        }
        JsonNode toAdd = analysis.path("SavingsPlansToAdd");
        if (!toAdd.isArray() || toAdd.isEmpty()) {
            throw invalid("SavingsPlansToAdd must contain at least one Savings Plan.");
        }
        double customCommitment = 0;
        for (JsonNode plan : toAdd) {
            oneOf(plan, "SavingsPlansType", SAVINGS_PLAN_TYPES, null);
            oneOf(plan, "TermInYears", TERMS, null);
            oneOf(plan, "PaymentOption", PAYMENT_OPTIONS, null);
            if ("CUSTOM_COMMITMENT".equals(type)) {
                if (!plan.path("SavingsPlansCommitment").isNumber()) {
                    throw invalid("CUSTOM_COMMITMENT analyses require a SavingsPlansCommitment for each plan.");
                }
                customCommitment += plan.path("SavingsPlansCommitment").asDouble();
            }
        }
        String account = analyses.accountId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String id = UUID.randomUUID().toString();
        JsonNode first = toAdd.get(0);
        double fraction = savingsPlanFraction(first.path("SavingsPlansType").asText(),
                first.path("TermInYears").asText(), first.path("PaymentOption").asText());
        double hourlyOnDemand = 0;
        double lookbackHours = ChronoUnit.HOURS.between(start, end);
        for (UsageLine line : costs.lines(start, end, region)) {
            if ("AmazonEC2".equals(line.service()) || "AWSLambda".equals(line.service())) {
                hourlyOnDemand += costs.synthesizer().compute(line).get("UnblendedCost").amount() / lookbackHours;
            }
        }
        double commitment = "CUSTOM_COMMITMENT".equals(type) ? customCommitment : hourlyOnDemand * fraction;
        double coveredOnDemand = Math.min(hourlyOnDemand, fraction > 0 ? commitment / fraction : 0);
        double estimatedHourly = commitment + (hourlyOnDemand - coveredOnDemand);
        ObjectNode result = mapper.createObjectNode();
        result.put("AnalysisId", id);
        result.put("AnalysisStatus", "SUCCEEDED");
        result.put("AnalysisStartedTime", now.toString());
        result.put("AnalysisCompletionTime", now.toString());
        result.put("EstimatedCompletionTime", now.toString());
        result.set("CommitmentPurchaseAnalysisConfiguration", configuration.deepCopy());
        ObjectNode analysisDetails = result.putObject("AnalysisDetails")
                .putObject("SavingsPlansPurchaseAnalysisDetails");
        analysisDetails.put("CurrencyCode", "USD");
        analysisDetails.put("LookbackPeriodInHours", Long.toString((long) lookbackHours));
        analysisDetails.put("CurrentAverageCoverage", "0");
        analysisDetails.put("CurrentAverageHourlyOnDemandSpend", amount(hourlyOnDemand));
        analysisDetails.put("CurrentMaximumHourlyOnDemandSpend", amount(hourlyOnDemand));
        analysisDetails.put("CurrentMinimumHourlyOnDemandSpend", amount(hourlyOnDemand));
        analysisDetails.put("CurrentOnDemandSpend", amount(hourlyOnDemand * lookbackHours));
        analysisDetails.put("ExistingHourlyCommitment", "0");
        analysisDetails.put("HourlyCommitmentToPurchase", amount(commitment));
        analysisDetails.put("EstimatedAverageCoverage", amount(hourlyOnDemand > 0
                ? coveredOnDemand / hourlyOnDemand * 100 : 0));
        analysisDetails.put("EstimatedAverageUtilization", amount(commitment > 0
                ? Math.min(100, coveredOnDemand * fraction / commitment * 100) : 0));
        analysisDetails.put("EstimatedMonthlySavingsAmount",
                amount((hourlyOnDemand - estimatedHourly) * HOURS_PER_MONTH));
        analysisDetails.put("EstimatedOnDemandCost", amount((hourlyOnDemand - coveredOnDemand) * lookbackHours));
        analysisDetails.put("EstimatedOnDemandCostWithCurrentCommitment", amount(hourlyOnDemand * lookbackHours));
        analysisDetails.put("EstimatedSavingsAmount", amount((hourlyOnDemand - estimatedHourly) * lookbackHours));
        analysisDetails.put("EstimatedSavingsPercentage", amount(hourlyOnDemand > 0
                ? (hourlyOnDemand - estimatedHourly) / hourlyOnDemand * 100 : 0));
        analysisDetails.put("EstimatedCommitmentCost", amount(commitment * lookbackHours));
        analysisDetails.put("EstimatedROI", amount(commitment > 0
                ? (hourlyOnDemand - estimatedHourly) / commitment * 100 : 0));
        analysisDetails.put("LatestUsageTimestamp", end.toString());
        analysisDetails.put("UpfrontCost", "0");
        analysisDetails.putArray("MetricsOverLookbackPeriod");
        analyses.putForAccount(account, id, result);
        ObjectNode response = mapper.createObjectNode();
        response.put("AnalysisId", id);
        response.put("AnalysisStartedTime", now.toString());
        response.put("EstimatedCompletionTime", now.toString());
        return response;
    }

    public ObjectNode getCommitmentPurchaseAnalysis(JsonNode request) {
        String id = text(request, "AnalysisId", 36);
        return analyses.getForAccount(analyses.accountId(), id).orElseThrow(() ->
                new AwsException("AnalysisNotFoundException", "The analysis " + id + " does not exist.", 400));
    }

    public ObjectNode listCommitmentPurchaseAnalyses(JsonNode request) {
        String status = present(request, "AnalysisStatus")
                ? oneOf(request, "AnalysisStatus", Set.of("SUCCEEDED", "PROCESSING", "FAILED"), null) : null;
        Set<String> ids = present(request, "AnalysisIds")
                ? new HashSet<>(strings(request, "AnalysisIds", 600, 36)) : Set.of();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode analysis : analyses.scanForAccount(analyses.accountId(), key -> true)) {
            if ((status != null && !status.equals(analysis.path("AnalysisStatus").asText()))
                    || (!ids.isEmpty() && !ids.contains(analysis.path("AnalysisId").asText()))) {
                continue;
            }
            ObjectNode summary = analysis.deepCopy();
            summary.remove("AnalysisDetails");
            summaries.add(summary);
        }
        summaries.sort(Comparator.comparing((ObjectNode value) -> value.path("AnalysisStartedTime").asText())
                .reversed());
        PaginatedResult<ObjectNode> paged = page(summaries, value -> value.path("AnalysisId").asText(), request,
                "PageSize", "NextPageToken", 20, 600);
        ObjectNode response = mapper.createObjectNode();
        response.putArray("AnalysisSummaryList").addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextPageToken", paged.nextToken());
        }
        return response;
    }

    @Override
    public synchronized void clear() {
        generations.clear();
        details.clear();
        analyses.clear();
    }

    private record InstanceUsage(String accountId, String region, String instanceType, double hourlyRate,
                                 List<String> instances) {}

    /** Running EC2 instances in the lookback window, grouped by account, region and instance type. */
    private List<InstanceUsage> instanceUsage(String region, String lookback) {
        Instant end = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant start = end.minus(lookbackDays(lookback), ChronoUnit.DAYS);
        CostSynthesizer synthesizer = costs.synthesizer();
        Map<String, InstanceUsage> grouped = new TreeMap<>();
        double hours = ChronoUnit.HOURS.between(start, end);
        for (UsageLine line : costs.lines(start, end, region)) {
            if (!"AmazonEC2".equals(line.service()) || line.resourceId() == null || line.quantity() <= 0
                    || line.usageType() == null || !line.usageType().startsWith("BoxUsage:")) {
                continue;
            }
            double rate = synthesizer.compute(line).get("UnblendedCost").amount() / line.quantity();
            if (rate <= 0 || line.quantity() < hours - 0.01) {
                continue;
            }
            String type = line.usageType().substring("BoxUsage:".length());
            String key = line.linkedAccountId() + "|" + line.region() + "|" + type;
            grouped.computeIfAbsent(key, k -> new InstanceUsage(line.linkedAccountId(), line.region(), type, rate,
                    new ArrayList<>())).instances().add(line.resourceId());
        }
        return new ArrayList<>(grouped.values());
    }

    private void addLambdaSpend(String region, String lookback, Map<String, double[]> spend,
                                Map<String, String[]> keys) {
        Instant end = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant start = end.minus(lookbackDays(lookback), ChronoUnit.DAYS);
        double hours = ChronoUnit.HOURS.between(start, end);
        CostSynthesizer synthesizer = costs.synthesizer();
        for (UsageLine line : costs.lines(start, end, region)) {
            if (!"AWSLambda".equals(line.service())) {
                continue;
            }
            double cost = synthesizer.compute(line).get("UnblendedCost").amount();
            if (cost <= 0) {
                continue;
            }
            String key = line.linkedAccountId() + "||";
            spend.computeIfAbsent(key, k -> new double[1])[0] += cost / hours;
            keys.put(key, new String[] {line.linkedAccountId(), "", ""});
        }
    }

    private ObjectNode detailData(ObjectNode row, String scope, String type, String term, String payment,
                                  String lookback, String generatedAt, double hourlyOnDemand, double commitment) {
        ObjectNode data = mapper.createObjectNode();
        data.put("AccountScope", scope);
        data.put("LookbackPeriodInDays", lookback);
        data.put("SavingsPlansType", type);
        data.put("TermInYears", term);
        data.put("PaymentOption", payment);
        data.put("AccountId", row.path("AccountId").asText());
        data.put("CurrencyCode", "USD");
        JsonNode spDetails = row.path("SavingsPlansDetails");
        if (spDetails.has("InstanceFamily")) {
            data.put("InstanceFamily", spDetails.path("InstanceFamily").asText());
            data.put("Region", spDetails.path("Region").asText());
        }
        data.put("OfferingId", spDetails.path("OfferingId").asText());
        data.put("GenerationTimestamp", generatedAt);
        data.put("LatestUsageTimestamp", LocalDate.now(ZoneOffset.UTC).atStartOfDay()
                .toInstant(ZoneOffset.UTC).toString());
        for (String field : List.of("CurrentAverageHourlyOnDemandSpend", "CurrentMaximumHourlyOnDemandSpend",
                "CurrentMinimumHourlyOnDemandSpend", "EstimatedAverageUtilization", "EstimatedMonthlySavingsAmount",
                "EstimatedOnDemandCost", "EstimatedOnDemandCostWithCurrentCommitment", "EstimatedROI",
                "EstimatedSPCost", "EstimatedSavingsAmount", "EstimatedSavingsPercentage",
                "HourlyCommitmentToPurchase", "UpfrontCost")) {
            data.put(field, row.path(field).asText());
        }
        data.put("ExistingHourlyCommitment", "0");
        data.put("CurrentAverageCoverage", "0");
        data.put("EstimatedAverageCoverage", "100.0");
        ArrayNode overLookback = data.putArray("MetricsOverLookbackPeriod");
        Instant end = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        for (int day = lookbackDays(lookback); day > 0; day--) {
            ObjectNode point = overLookback.addObject();
            point.put("StartTime", end.minus(day, ChronoUnit.DAYS).toString());
            point.put("EstimatedOnDemandCost", "0");
            point.put("CurrentCoverage", "0");
            point.put("EstimatedCoverage", "100.0");
            point.put("EstimatedNewCommitmentUtilization", "100.0");
            point.put("CurrentOnDemandSpend", amount(hourlyOnDemand * 24));
            point.put("EstimatedSPCost", amount(commitment * 24));
        }
        return data;
    }

    private Optional<ObjectNode> latestGeneration() {
        return generations.scanForAccount(generations.accountId(), key -> true).stream()
                .filter(value -> "SUCCEEDED".equals(value.path("GenerationStatus").asText()))
                .max(Comparator.comparing(value -> value.path("GenerationCompletionTime").asText()));
    }

    /** The share of the on-demand rate a Standard or Convertible Reserved Instance costs, by term and payment. */
    private static double reservedFraction(String term, String payment, String offeringClass) {
        double fraction = "ONE_YEAR".equals(term) ? 0.64 : 0.45;
        fraction -= switch (payment) {
            case "PARTIAL_UPFRONT" -> 0.03;
            case "ALL_UPFRONT" -> 0.05;
            default -> 0;
        };
        return "CONVERTIBLE".equals(offeringClass) ? fraction + 0.1 : fraction;
    }

    /** The share of the on-demand rate a Savings Plan commitment costs, by plan type, term and payment. */
    private static double savingsPlanFraction(String type, String term, String payment) {
        boolean oneYear = "ONE_YEAR".equals(term);
        double fraction = switch (type) {
            case "EC2_INSTANCE_SP" -> oneYear ? 0.66 : 0.47;
            case "SAGEMAKER_SP" -> oneYear ? 0.70 : 0.50;
            case "DATABASE_SP" -> oneYear ? 0.75 : 0.60;
            default -> oneYear ? 0.72 : 0.52;
        };
        return fraction - switch (payment) {
            case "PARTIAL_UPFRONT" -> 0.02;
            case "ALL_UPFRONT" -> 0.04;
            default -> 0;
        };
    }

    private static double upfront(String payment, double hourly, String term) {
        double termHours = termMonths(term) * HOURS_PER_MONTH;
        return switch (payment) {
            case "ALL_UPFRONT" -> hourly * termHours;
            case "PARTIAL_UPFRONT" -> hourly * termHours / 2;
            default -> 0;
        };
    }

    private static int termMonths(String term) {
        return "ONE_YEAR".equals(term) ? 12 : 36;
    }

    private static int lookbackDays(String lookback) {
        return switch (lookback) {
            case "THIRTY_DAYS" -> 30;
            case "SIXTY_DAYS" -> 60;
            default -> 7;
        };
    }

    private static String family(String instanceType) {
        int dot = instanceType.indexOf('.');
        return dot < 0 ? instanceType : instanceType.substring(0, dot);
    }

    private static String smallerSize(String instanceType) {
        int dot = instanceType.indexOf('.');
        if (dot < 0) {
            return null;
        }
        int index = SIZES.indexOf(instanceType.substring(dot + 1));
        return index > 0 ? instanceType.substring(0, dot + 1) + SIZES.get(index - 1) : null;
    }

    /** EC2 normalization factors: {@code small} is 1 unit and each size up doubles, as AWS defines them. */
    private static double normalizationFactor(String instanceType) {
        String size = instanceType.substring(instanceType.indexOf('.') + 1);
        return switch (size) {
            case "nano" -> 0.25;
            case "micro" -> 0.5;
            case "small" -> 1;
            case "medium" -> 2;
            case "large" -> 4;
            case "xlarge" -> 8;
            default -> size.endsWith("xlarge") && size.length() > 6
                    ? 8 * Double.parseDouble(size.substring(0, size.length() - 6)) : 8;
        };
    }

    private static String offeringId(String type, String term, String payment, String region, String family) {
        return UUID.nameUUIDFromBytes((type + term + payment + region + family).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String amount(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
