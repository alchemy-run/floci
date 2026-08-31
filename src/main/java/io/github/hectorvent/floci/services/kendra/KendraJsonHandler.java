package io.github.hectorvent.floci.services.kendra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Amazon Kendra. Dispatched from {@code AwsJson11Controller}
 * under the {@code AWSKendraFrontendService.} target prefix.
 *
 * @see <a href="https://docs.aws.amazon.com/kendra/latest/APIReference/API_Operations.html">Kendra API</a>
 */
@ApplicationScoped
public class KendraJsonHandler {

    private static final Logger LOG = Logger.getLogger(KendraJsonHandler.class);
    private static final String TARGET_PREFIX = "AWSKendraFrontendService.";

    private final KendraService service;
    private final ObjectMapper objectMapper;

    @Inject
    public KendraJsonHandler(KendraService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Kendra action: {0}", action);
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        return switch (action) {
            case "CreateIndex" -> ok(service.createIndex(body, region));
            case "DescribeIndex" -> ok(service.describeIndex(body));
            case "UpdateIndex" -> ok(service.updateIndex(body));
            case "DeleteIndex" -> ok(service.deleteIndex(body));
            case "ListIndices" -> ok(service.listIndices());
            case "CreateDataSource" -> ok(service.createDataSource(body, region));
            case "DescribeDataSource" -> ok(service.describeDataSource(body));
            case "UpdateDataSource" -> ok(service.updateDataSource(body));
            case "DeleteDataSource" -> ok(service.deleteDataSource(body));
            case "ListDataSources" -> ok(service.listDataSources(body));
            case "Query" -> ok(service.query(body));
            case "Retrieve" -> ok(service.retrieve(body));
            case "GetQuerySuggestions" -> ok(service.getQuerySuggestions(body));
            case "SubmitFeedback" -> ok(service.submitFeedback(body));
            case "BatchPutDocument" -> ok(service.batchPutDocument(body));
            case "BatchDeleteDocument" -> ok(service.batchDeleteDocument(body));
            case "BatchGetDocumentStatus" -> ok(service.batchGetDocumentStatus(body));
            case "GetSnapshots" -> ok(service.getSnapshots(body));
            case "PutPrincipalMapping" -> ok(service.putPrincipalMapping(body));
            case "DeletePrincipalMapping" -> ok(service.deletePrincipalMapping(body));
            case "DescribePrincipalMapping" -> ok(service.describePrincipalMapping(body));
            case "ListGroupsOlderThanOrderingId" -> ok(service.listGroupsOlderThanOrderingId(body));
            case "ClearQuerySuggestions" -> ok(service.clearQuerySuggestions(body));
            case "DescribeQuerySuggestionsConfig" -> ok(service.describeQuerySuggestionsConfig(body));
            case "UpdateQuerySuggestionsConfig" -> ok(service.updateQuerySuggestionsConfig(body));
            case "CreateAccessControlConfiguration" -> ok(service.createAccessControlConfiguration(body));
            case "DescribeAccessControlConfiguration" -> ok(service.describeAccessControlConfiguration(body));
            case "UpdateAccessControlConfiguration" -> ok(service.updateAccessControlConfiguration(body));
            case "DeleteAccessControlConfiguration" -> ok(service.deleteAccessControlConfiguration(body));
            case "ListAccessControlConfigurations" -> ok(service.listAccessControlConfigurations(body));
            case "StartDataSourceSyncJob" -> ok(service.startDataSourceSyncJob(body));
            case "StopDataSourceSyncJob" -> ok(service.stopDataSourceSyncJob(body));
            case "ListDataSourceSyncJobs" -> ok(service.listDataSourceSyncJobs(body));
            case "ListTagsForResource" -> ok(service.listTagsForResource(body));
            case "TagResource" -> ok(service.tagResource(body));
            case "UntagResource" -> ok(service.untagResource(body));
            default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
