package io.floci.aws.services.personalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;

/** JSON 1.1 dispatch for Amazon Personalize ({@code X-Amz-Target: AmazonPersonalize.<Action>}). */
@ApplicationScoped
public class PersonalizeJsonHandler {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/personalize-request-shapes.json", "Personalize request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long", "float", "double")
                            .contains(type) ? "value" : "size";
                    return violation(path, min != null && size < min
                            ? "Member must have " + what + " greater than or equal to " + min
                            : "Member must have " + what + " less than or equal to " + max);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return violation(path, "Member must satisfy regular expression pattern: " + pattern);
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return violation(path, "Member must satisfy enum value set: [" + String.join(", ", values)
                            + "]");
                }

                @Override
                public AwsException required(String path) {
                    return violation(path, "Member must not be null");
                }
            });

    private final PersonalizeService service;

    @Inject
    public PersonalizeJsonHandler(PersonalizeService service) {
        this.service = service;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return PersonalizeStore.invalid("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "CreateBatchInferenceJob" -> service.createBatchInferenceJob(request, region);
            case "CreateBatchSegmentJob" -> service.createBatchSegmentJob(request, region);
            case "CreateCampaign" -> service.createCampaign(request, region);
            case "CreateDataDeletionJob" -> service.createDataDeletionJob(request, region);
            case "CreateDataset" -> service.createDataset(request, region);
            case "CreateDatasetExportJob" -> service.createDatasetExportJob(request, region);
            case "CreateDatasetGroup" -> service.createDatasetGroup(request, region);
            case "CreateDatasetImportJob" -> service.createDatasetImportJob(request, region);
            case "CreateEventTracker" -> service.createEventTracker(request, region);
            case "CreateFilter" -> service.createFilter(request, region);
            case "CreateMetricAttribution" -> service.createMetricAttribution(request, region);
            case "CreateRecommender" -> service.createRecommender(request, region);
            case "CreateSchema" -> service.createSchema(request, region);
            case "CreateSolution" -> service.createSolution(request, region);
            case "CreateSolutionVersion" -> service.createSolutionVersion(request, region);
            case "DeleteCampaign" -> service.deleteCampaign(request, region);
            case "DeleteDataset" -> service.deleteDataset(request, region);
            case "DeleteDatasetGroup" -> service.deleteDatasetGroup(request, region);
            case "DeleteEventTracker" -> service.deleteEventTracker(request, region);
            case "DeleteFilter" -> service.deleteFilter(request, region);
            case "DeleteMetricAttribution" -> service.deleteMetricAttribution(request, region);
            case "DeleteRecommender" -> service.deleteRecommender(request, region);
            case "DeleteSchema" -> service.deleteSchema(request, region);
            case "DeleteSolution" -> service.deleteSolution(request, region);
            case "DescribeAlgorithm" -> service.describeAlgorithm(request, region);
            case "DescribeBatchInferenceJob" -> service.describeBatchInferenceJob(request, region);
            case "DescribeBatchSegmentJob" -> service.describeBatchSegmentJob(request, region);
            case "DescribeCampaign" -> service.describeCampaign(request, region);
            case "DescribeDataDeletionJob" -> service.describeDataDeletionJob(request, region);
            case "DescribeDataset" -> service.describeDataset(request, region);
            case "DescribeDatasetExportJob" -> service.describeDatasetExportJob(request, region);
            case "DescribeDatasetGroup" -> service.describeDatasetGroup(request, region);
            case "DescribeDatasetImportJob" -> service.describeDatasetImportJob(request, region);
            case "DescribeEventTracker" -> service.describeEventTracker(request, region);
            case "DescribeFeatureTransformation" -> service.describeFeatureTransformation(request, region);
            case "DescribeFilter" -> service.describeFilter(request, region);
            case "DescribeMetricAttribution" -> service.describeMetricAttribution(request, region);
            case "DescribeRecipe" -> service.describeRecipe(request, region);
            case "DescribeRecommender" -> service.describeRecommender(request, region);
            case "DescribeSchema" -> service.describeSchema(request, region);
            case "DescribeSolution" -> service.describeSolution(request, region);
            case "DescribeSolutionVersion" -> service.describeSolutionVersion(request, region);
            case "GetSolutionMetrics" -> service.getSolutionMetrics(request, region);
            case "ListBatchInferenceJobs" -> service.listBatchInferenceJobs(request, region);
            case "ListBatchSegmentJobs" -> service.listBatchSegmentJobs(request, region);
            case "ListCampaigns" -> service.listCampaigns(request, region);
            case "ListDataDeletionJobs" -> service.listDataDeletionJobs(request, region);
            case "ListDatasetExportJobs" -> service.listDatasetExportJobs(request, region);
            case "ListDatasetGroups" -> service.listDatasetGroups(request, region);
            case "ListDatasetImportJobs" -> service.listDatasetImportJobs(request, region);
            case "ListDatasets" -> service.listDatasets(request, region);
            case "ListEventTrackers" -> service.listEventTrackers(request, region);
            case "ListFilters" -> service.listFilters(request, region);
            case "ListMetricAttributionMetrics" -> service.listMetricAttributionMetrics(request, region);
            case "ListMetricAttributions" -> service.listMetricAttributions(request, region);
            case "ListRecipes" -> service.listRecipes(request, region);
            case "ListRecommenders" -> service.listRecommenders(request, region);
            case "ListSchemas" -> service.listSchemas(request, region);
            case "ListSolutionVersions" -> service.listSolutionVersions(request, region);
            case "ListSolutions" -> service.listSolutions(request, region);
            case "ListTagsForResource" -> service.listTagsForResource(request, region);
            case "StartRecommender" -> service.startRecommender(request, region);
            case "StopRecommender" -> service.stopRecommender(request, region);
            case "StopSolutionVersionCreation" -> service.stopSolutionVersionCreation(request, region);
            case "TagResource" -> service.tagResource(request, region);
            case "UntagResource" -> service.untagResource(request, region);
            case "UpdateCampaign" -> service.updateCampaign(request, region);
            case "UpdateDataset" -> service.updateDataset(request, region);
            case "UpdateMetricAttribution" -> service.updateMetricAttribution(request, region);
            case "UpdateRecommender" -> service.updateRecommender(request, region);
            case "UpdateSolution" -> service.updateSolution(request, region);
            default -> null;
        };
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: AmazonPersonalize." + action)).build();
        }
        return Response.ok(result).build();
    }
}
