package io.github.hectorvent.floci.services.personalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Amazon Personalize ({@code AmazonPersonalize.*}).
 *
 * @see <a href="https://docs.aws.amazon.com/personalize/latest/dg/API_Operations.html">Personalize API</a>
 */
@ApplicationScoped
public class PersonalizeJsonHandler {

    private static final Logger LOG = Logger.getLogger(PersonalizeJsonHandler.class);

    private final PersonalizeService service;
    private final ObjectMapper objectMapper;

    @Inject
    public PersonalizeJsonHandler(PersonalizeService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Personalize action: {0}", action);
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        return switch (action) {
            case "CreateSchema" -> ok(service.createSchema(body, region));
            case "DescribeSchema" -> ok(service.describeSchema(body));
            case "DeleteSchema" -> ok(service.deleteSchema(body));
            case "ListSchemas" -> ok(service.listSchemas());
            case "CreateDatasetGroup" -> ok(service.createDatasetGroup(body, region));
            case "DescribeDatasetGroup" -> ok(service.describeDatasetGroup(body));
            case "DeleteDatasetGroup" -> ok(service.deleteDatasetGroup(body));
            case "ListDatasetGroups" -> ok(service.listDatasetGroups());
            case "CreateDataset" -> ok(service.createDataset(body, region));
            case "DescribeDataset" -> ok(service.describeDataset(body));
            case "DeleteDataset" -> ok(service.deleteDataset(body));
            case "ListDatasets" -> ok(service.listDatasets(body));
            case "CreateEventTracker" -> ok(service.createEventTracker(body, region));
            case "DescribeEventTracker" -> ok(service.describeEventTracker(body));
            case "DeleteEventTracker" -> ok(service.deleteEventTracker(body));
            case "ListEventTrackers" -> ok(service.listEventTrackers(body));
            case "ListFilters" -> ok(service.listFilters(body));
            case "ListSolutions" -> ok(service.listSolutions(body));
            case "ListCampaigns" -> ok(service.listCampaigns(body));
            case "DeleteFilter" -> ok(service.deleteFilter(body));
            case "DeleteSolution" -> ok(service.deleteSolution(body));
            case "DeleteCampaign" -> ok(service.deleteCampaign(body));
            case "ListTagsForResource" -> ok(service.listTagsForResource(body));
            case "TagResource" -> ok(service.tagResource(body));
            case "UntagResource" -> ok(service.untagResource(body));
            case "CreateDatasetImportJob" -> ok(service.createDatasetImportJob(body, region));
            case "DescribeDatasetImportJob" -> ok(service.describeDatasetImportJob(body));
            case "CreateSolution" -> ok(service.createSolution(body, region));
            case "CreateSolutionVersion" -> ok(service.createSolutionVersion(body, region));
            case "DescribeSolutionVersion" -> ok(service.describeSolutionVersion(body));
            case "GetSolutionMetrics" -> ok(service.getSolutionMetrics(body));
            case "CreateCampaign" -> ok(service.createCampaign(body, region));
            case "UpdateCampaign" -> ok(service.updateCampaign(body));
            case "DescribeCampaign" -> ok(service.describeCampaign(body));
            case "CreateBatchInferenceJob" -> ok(service.createBatchInferenceJob(body, region));
            case "DescribeBatchInferenceJob" -> ok(service.describeBatchInferenceJob(body));
            default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                    "AmazonPersonalize." + action);
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
