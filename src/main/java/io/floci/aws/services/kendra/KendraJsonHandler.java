package io.floci.aws.services.kendra;

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

/** JSON 1.1 dispatch for Amazon Kendra ({@code X-Amz-Target: AWSKendraFrontendService.<Action>}). */
@ApplicationScoped
public class KendraJsonHandler {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load("aws/kendra-request-shapes.json",
            "Kendra request shapes", new RequestConstraintCheck.Messages() {
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

    private final KendraIndexes indexes;
    private final KendraDocuments documents;
    private final KendraContent content;
    private final KendraQueries queries;
    private final KendraSources sources;

    @Inject
    public KendraJsonHandler(KendraIndexes indexes, KendraDocuments documents, KendraContent content,
                             KendraQueries queries, KendraSources sources) {
        this.indexes = indexes;
        this.documents = documents;
        this.content = content;
        this.queries = queries;
        this.sources = sources;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return KendraStore.validation("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "AssociateEntitiesToExperience" -> content.associateEntitiesToExperience(request, region);
            case "AssociatePersonasToEntities" -> content.associatePersonasToEntities(request, region);
            case "BatchDeleteDocument" -> documents.batchDeleteDocument(request, region);
            case "BatchDeleteFeaturedResultsSet" -> content.batchDeleteFeaturedResultsSet(request, region);
            case "BatchGetDocumentStatus" -> documents.batchGetDocumentStatus(request, region);
            case "BatchPutDocument" -> documents.batchPutDocument(request, region);
            case "ClearQuerySuggestions" -> queries.clearQuerySuggestions(request, region);
            case "CreateAccessControlConfiguration" -> content.createAccessControlConfiguration(request, region);
            case "CreateDataSource" -> sources.createDataSource(request, region);
            case "CreateExperience" -> content.createExperience(request, region);
            case "CreateFaq" -> content.createFaq(request, region);
            case "CreateFeaturedResultsSet" -> content.createFeaturedResultsSet(request, region);
            case "CreateIndex" -> indexes.createIndex(request, region);
            case "CreateQuerySuggestionsBlockList" -> content.createQuerySuggestionsBlockList(request, region);
            case "CreateThesaurus" -> content.createThesaurus(request, region);
            case "DeleteAccessControlConfiguration" -> content.deleteAccessControlConfiguration(request, region);
            case "DeleteDataSource" -> sources.deleteDataSource(request, region);
            case "DeleteExperience" -> content.deleteExperience(request, region);
            case "DeleteFaq" -> content.deleteFaq(request, region);
            case "DeleteIndex" -> indexes.deleteIndex(request, region);
            case "DeletePrincipalMapping" -> content.deletePrincipalMapping(request, region);
            case "DeleteQuerySuggestionsBlockList" -> content.deleteQuerySuggestionsBlockList(request, region);
            case "DeleteThesaurus" -> content.deleteThesaurus(request, region);
            case "DescribeAccessControlConfiguration" -> content.describeAccessControlConfiguration(request, region);
            case "DescribeDataSource" -> sources.describeDataSource(request, region);
            case "DescribeExperience" -> content.describeExperience(request, region);
            case "DescribeFaq" -> content.describeFaq(request, region);
            case "DescribeFeaturedResultsSet" -> content.describeFeaturedResultsSet(request, region);
            case "DescribeIndex" -> indexes.describeIndex(request, region);
            case "DescribePrincipalMapping" -> content.describePrincipalMapping(request, region);
            case "DescribeQuerySuggestionsBlockList" -> content.describeQuerySuggestionsBlockList(request, region);
            case "DescribeQuerySuggestionsConfig" -> queries.describeQuerySuggestionsConfig(request, region);
            case "DescribeThesaurus" -> content.describeThesaurus(request, region);
            case "DisassociateEntitiesFromExperience" -> content.disassociateEntitiesFromExperience(request, region);
            case "DisassociatePersonasFromEntities" -> content.disassociatePersonasFromEntities(request, region);
            case "GetQuerySuggestions" -> queries.getQuerySuggestions(request, region);
            case "GetSnapshots" -> queries.getSnapshots(request, region);
            case "ListAccessControlConfigurations" -> content.listAccessControlConfigurations(request, region);
            case "ListDataSourceSyncJobs" -> sources.listDataSourceSyncJobs(request, region);
            case "ListDataSources" -> sources.listDataSources(request, region);
            case "ListEntityPersonas" -> content.listEntityPersonas(request, region);
            case "ListExperienceEntities" -> content.listExperienceEntities(request, region);
            case "ListExperiences" -> content.listExperiences(request, region);
            case "ListFaqs" -> content.listFaqs(request, region);
            case "ListFeaturedResultsSets" -> content.listFeaturedResultsSets(request, region);
            case "ListGroupsOlderThanOrderingId" -> content.listGroupsOlderThanOrderingId(request, region);
            case "ListIndices" -> indexes.listIndices(request, region);
            case "ListQuerySuggestionsBlockLists" -> content.listQuerySuggestionsBlockLists(request, region);
            case "ListTagsForResource" -> indexes.listTagsForResource(request, region);
            case "ListThesauri" -> content.listThesauri(request, region);
            case "PutPrincipalMapping" -> content.putPrincipalMapping(request, region);
            case "Query" -> queries.query(request, region);
            case "Retrieve" -> queries.retrieve(request, region);
            case "StartDataSourceSyncJob" -> sources.startDataSourceSyncJob(request, region);
            case "StopDataSourceSyncJob" -> sources.stopDataSourceSyncJob(request, region);
            case "SubmitFeedback" -> queries.submitFeedback(request, region);
            case "TagResource" -> indexes.tagResource(request, region);
            case "UntagResource" -> indexes.untagResource(request, region);
            case "UpdateAccessControlConfiguration" -> content.updateAccessControlConfiguration(request, region);
            case "UpdateDataSource" -> sources.updateDataSource(request, region);
            case "UpdateExperience" -> content.updateExperience(request, region);
            case "UpdateFeaturedResultsSet" -> content.updateFeaturedResultsSet(request, region);
            case "UpdateIndex" -> indexes.updateIndex(request, region);
            case "UpdateQuerySuggestionsBlockList" -> content.updateQuerySuggestionsBlockList(request, region);
            case "UpdateQuerySuggestionsConfig" -> queries.updateQuerySuggestionsConfig(request, region);
            case "UpdateThesaurus" -> content.updateThesaurus(request, region);
            default -> null;
        };
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: AWSKendraFrontendService." + action)).build();
        }
        return Response.ok(result).build();
    }
}
