package io.github.hectorvent.floci.services.ce;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
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

    @Inject
    public CostExplorerJsonHandler(CostExplorerService service) {
        this.service = service;
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
            case "GetCostCategories" -> Response.ok(service.getCostCategories(request)).build();
            case "CreateCostCategoryDefinition" -> Response.ok(service.createCostCategoryDefinition(request)).build();
            case "DescribeCostCategoryDefinition" -> Response.ok(service.describeCostCategoryDefinition(request)).build();
            case "ListCostCategoryDefinitions" -> Response.ok(service.listCostCategoryDefinitions(request)).build();
            case "UpdateCostCategoryDefinition" -> Response.ok(service.updateCostCategoryDefinition(request)).build();
            case "DeleteCostCategoryDefinition" -> Response.ok(service.deleteCostCategoryDefinition(request)).build();
            case "ListCostCategoryResourceAssociations" -> Response.ok(service.listCostCategoryResourceAssociations(request)).build();
            case "GetCostForecast" -> Response.ok(service.getCostForecast(request, region)).build();
            case "GetApproximateUsageRecords" -> Response.ok(service.getApproximateUsageRecords(request, region)).build();
            case "GetRightsizingRecommendation" -> Response.ok(service.getRightsizingRecommendation(request)).build();
            case "GetReservationPurchaseRecommendation" -> Response.ok(service.getReservationPurchaseRecommendation(request)).build();
            case "ListSavingsPlansPurchaseRecommendationGeneration" -> Response.ok(service.listSavingsPlansPurchaseRecommendationGeneration()).build();
            case "ListCommitmentPurchaseAnalyses" -> Response.ok(service.listCommitmentPurchaseAnalyses()).build();
            case "ListCostAllocationTags" -> Response.ok(service.listCostAllocationTags()).build();
            case "ListCostAllocationTagBackfillHistory" -> Response.ok(service.listCostAllocationTagBackfillHistory()).build();
            case "GetAnomalies" -> Response.ok(service.getAnomalies(request)).build();
            case "ProvideAnomalyFeedback" -> Response.ok(service.provideAnomalyFeedback(request)).build();
            case "CreateAnomalyMonitor" -> Response.ok(service.createAnomalyMonitor(request)).build();
            case "GetAnomalyMonitors" -> Response.ok(service.getAnomalyMonitors(request)).build();
            case "UpdateAnomalyMonitor" -> Response.ok(service.updateAnomalyMonitor(request)).build();
            case "DeleteAnomalyMonitor" -> Response.ok(service.deleteAnomalyMonitor(request)).build();
            case "CreateAnomalySubscription" -> Response.ok(service.createAnomalySubscription(request)).build();
            case "GetAnomalySubscriptions" -> Response.ok(service.getAnomalySubscriptions(request)).build();
            case "UpdateAnomalySubscription" -> Response.ok(service.updateAnomalySubscription(request)).build();
            case "DeleteAnomalySubscription" -> Response.ok(service.deleteAnomalySubscription(request)).build();
            case "ListTagsForResource" -> Response.ok(service.listTagsForResource(request)).build();
            case "TagResource" -> Response.ok(service.tagResource(request)).build();
            case "UntagResource" -> Response.ok(service.untagResource(request)).build();
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AWSInsightsIndexService." + action))
                    .build();
        };
    }
}
