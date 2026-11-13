package io.floci.aws.services.ce;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for AWS Cost Explorer operations.
 * Dispatches {@code X-Amz-Target: AWSInsightsIndexService.*} actions to
 * {@link CostExplorerService}.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_Operations_AWS_Cost_Explorer_Service.html">AWS Cost Explorer API</a>
 */
@ApplicationScoped
public class CostExplorerJsonHandler {

    private static final Logger LOG = Logger.getLogger(CostExplorerJsonHandler.class);

    private final CostExplorerService service;
    private final CostAnomalyService anomalies;
    private final CostCategoryService categories;
    private final CostAnalysisService analysis;
    private final CostRecommendationService recommendations;
    private final CostAllocationTagService allocationTags;

    @Inject
    public CostExplorerJsonHandler(CostExplorerService service, CostAnomalyService anomalies,
                                   CostCategoryService categories, CostAnalysisService analysis,
                                   CostRecommendationService recommendations,
                                   CostAllocationTagService allocationTags) {
        this.service = service;
        this.anomalies = anomalies;
        this.categories = categories;
        this.analysis = analysis;
        this.recommendations = recommendations;
        this.allocationTags = allocationTags;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("CostExplorer action: {0}", action);
        return switch (action) {
            case "GetCostAndUsage" -> Response.ok(service.getCostAndUsage(request, region)).build();
            case "GetCostAndUsageWithResources" -> Response.ok(service.getCostAndUsageWithResources(request, region)).build();
            case "GetDimensionValues" -> Response.ok(service.getDimensionValues(request, region)).build();
            case "GetTags" -> Response.ok(service.getTags(request, region)).build();
            case "GetReservationCoverage" -> Response.ok(service.getReservationCoverage()).build();
            case "GetReservationUtilization" -> Response.ok(service.getReservationUtilization()).build();
            case "GetSavingsPlansCoverage" -> Response.ok(service.getSavingsPlansCoverage()).build();
            case "GetSavingsPlansUtilization" -> Response.ok(service.getSavingsPlansUtilization()).build();
            case "GetSavingsPlansUtilizationDetails" ->
                    Response.ok(service.getSavingsPlansUtilizationDetails(request)).build();
            case "GetCostCategories" -> Response.ok(service.getCostCategories(request, region)).build();
            case "CreateCostCategoryDefinition" -> Response.ok(categories.create(request)).build();
            case "DescribeCostCategoryDefinition" -> Response.ok(categories.describe(request)).build();
            case "UpdateCostCategoryDefinition" -> Response.ok(categories.update(request)).build();
            case "DeleteCostCategoryDefinition" -> Response.ok(categories.delete(request)).build();
            case "ListCostCategoryDefinitions" -> Response.ok(categories.list(request)).build();
            case "ListCostCategoryResourceAssociations" ->
                    Response.ok(categories.listResourceAssociations(request)).build();
            case "GetCostForecast" -> Response.ok(analysis.getCostForecast(request, region)).build();
            case "GetUsageForecast" -> Response.ok(analysis.getUsageForecast(request, region)).build();
            case "GetApproximateUsageRecords" -> Response.ok(analysis.getApproximateUsageRecords(request, region)).build();
            case "GetCostAndUsageComparisons" -> Response.ok(analysis.getCostAndUsageComparisons(request, region)).build();
            case "GetCostComparisonDrivers" -> Response.ok(analysis.getCostComparisonDrivers(request, region)).build();
            case "GetReservationPurchaseRecommendation" ->
                    Response.ok(recommendations.getReservationPurchaseRecommendation(request, region)).build();
            case "GetSavingsPlansPurchaseRecommendation" ->
                    Response.ok(recommendations.getSavingsPlansPurchaseRecommendation(request, region)).build();
            case "GetSavingsPlanPurchaseRecommendationDetails" ->
                    Response.ok(recommendations.getSavingsPlanPurchaseRecommendationDetails(request)).build();
            case "GetRightsizingRecommendation" ->
                    Response.ok(recommendations.getRightsizingRecommendation(request, region)).build();
            case "StartSavingsPlansPurchaseRecommendationGeneration" ->
                    Response.ok(recommendations.startSavingsPlansPurchaseRecommendationGeneration()).build();
            case "ListSavingsPlansPurchaseRecommendationGeneration" ->
                    Response.ok(recommendations.listSavingsPlansPurchaseRecommendationGeneration(request)).build();
            case "StartCommitmentPurchaseAnalysis" ->
                    Response.ok(recommendations.startCommitmentPurchaseAnalysis(request, region)).build();
            case "GetCommitmentPurchaseAnalysis" ->
                    Response.ok(recommendations.getCommitmentPurchaseAnalysis(request)).build();
            case "ListCommitmentPurchaseAnalyses" ->
                    Response.ok(recommendations.listCommitmentPurchaseAnalyses(request)).build();
            case "ListCostAllocationTags" -> Response.ok(allocationTags.list(request, region)).build();
            case "UpdateCostAllocationTagsStatus" -> Response.ok(allocationTags.updateStatus(request, region)).build();
            case "StartCostAllocationTagBackfill" -> Response.ok(allocationTags.startBackfill(request)).build();
            case "ListCostAllocationTagBackfillHistory" -> Response.ok(allocationTags.listBackfills(request)).build();
            case "GetAnomalies" -> Response.ok(anomalies.getAnomalies(request)).build();
            case "ProvideAnomalyFeedback" -> Response.ok(anomalies.provideAnomalyFeedback(request)).build();
            case "CreateAnomalyMonitor" -> Response.ok(anomalies.createMonitor(request)).build();
            case "GetAnomalyMonitors" -> Response.ok(anomalies.getMonitors(request)).build();
            case "UpdateAnomalyMonitor" -> Response.ok(anomalies.updateMonitor(request)).build();
            case "DeleteAnomalyMonitor" -> {
                anomalies.deleteMonitor(request);
                yield Response.ok("{}").build();
            }
            case "CreateAnomalySubscription" -> Response.ok(anomalies.createSubscription(request)).build();
            case "GetAnomalySubscriptions" -> Response.ok(anomalies.getSubscriptions(request)).build();
            case "UpdateAnomalySubscription" -> Response.ok(anomalies.updateSubscription(request)).build();
            case "DeleteAnomalySubscription" -> {
                anomalies.deleteSubscription(request);
                yield Response.ok("{}").build();
            }
            case "ListTagsForResource" -> Response.ok(category(request)
                    ? categories.listTags(request) : anomalies.listTags(request)).build();
            case "TagResource" -> {
                if (category(request)) {
                    categories.tagResource(request);
                } else {
                    anomalies.tagResource(request);
                }
                yield Response.ok("{}").build();
            }
            case "UntagResource" -> {
                if (category(request)) {
                    categories.untagResource(request);
                } else {
                    anomalies.untagResource(request);
                }
                yield Response.ok("{}").build();
            }
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AWSInsightsIndexService." + action))
                    .build();
        };
    }

    private static boolean category(JsonNode request) {
        return CostCategoryService.owns(request.path("ResourceArn").asText(null));
    }
}
