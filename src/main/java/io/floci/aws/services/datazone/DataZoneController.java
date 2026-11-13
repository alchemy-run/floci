package io.floci.aws.services.datazone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RestJsonDispatcher;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;

/**
 * The Amazon DataZone REST API, routed here by {@link DataZoneRouteFilter}. Requests are matched to operations by
 * DataZone's HTTP bindings ({@link RestJsonDispatcher}) and run by the domain, environment, catalog, subscription
 * and ingestion services.
 */
@Path(DataZoneRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class DataZoneController {

    /** DataZone's request constraints, worded as its deserializer words them. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/datazone-request-shapes.json", "DataZone request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
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

    private final DataZoneService core;
    private final DataZoneEnvironments environments;
    private final DataZoneCatalog catalog;
    private final DataZoneSubscriptions subscriptions;
    private final DataZoneIngestion ingestion;
    private final DataZoneGovernance governance;
    private final DataZoneNotebooks notebooks;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public DataZoneController(DataZoneService core, DataZoneEnvironments environments, DataZoneCatalog catalog,
                              DataZoneSubscriptions subscriptions, DataZoneIngestion ingestion,
                              DataZoneGovernance governance, DataZoneNotebooks notebooks, RegionResolver regions,
                              ObjectMapper mapper) {
        this.core = core;
        this.environments = environments;
        this.catalog = catalog;
        this.subscriptions = subscriptions;
        this.ingestion = ingestion;
        this.governance = governance;
        this.notebooks = notebooks;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "datazone");
    }

    @GET
    @Path("{path: .*}")
    public Response get(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("GET", headers, uri, body);
    }

    @POST
    @Path("{path: .*}")
    public Response post(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("POST", headers, uri, body);
    }

    @PUT
    @Path("{path: .*}")
    public Response put(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PUT", headers, uri, body);
    }

    @PATCH
    @Path("{path: .*}")
    public Response patch(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PATCH", headers, uri, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("DELETE", headers, uri, body);
    }

    private Response dispatch(String method, HttpHeaders headers, UriInfo uri, byte[] body) {
        String path = uri.getRequestUri().getRawPath().substring(DataZoneRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return new AwsException("ValidationException", "1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint, 400);
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "CreateDomain" -> core.createDomain(input, region);
            case "GetDomain" -> core.getDomain(input, region);
            case "UpdateDomain" -> core.updateDomain(input, region);
            case "DeleteDomain" -> core.deleteDomain(input, region);
            case "ListDomains" -> core.listDomains(input, region);
            case "CreateDomainUnit" -> core.createDomainUnit(input, region);
            case "GetDomainUnit" -> core.getDomainUnit(input, region);
            case "UpdateDomainUnit" -> core.updateDomainUnit(input, region);
            case "DeleteDomainUnit" -> core.deleteDomainUnit(input, region);
            case "ListDomainUnitsForParent" -> core.listDomainUnitsForParent(input, region);
            case "CreateUserProfile" -> core.createUserProfile(input, region);
            case "GetUserProfile" -> core.getUserProfile(input, region);
            case "UpdateUserProfile" -> core.updateUserProfile(input, region);
            case "SearchUserProfiles" -> core.searchUserProfiles(input, region);
            case "GetIamPortalLoginUrl" -> core.getIamPortalLoginUrl(input, region);
            case "CreateProject" -> core.createProject(input, region);
            case "GetProject" -> core.getProject(input, region);
            case "UpdateProject" -> core.updateProject(input, region);
            case "DeleteProject" -> core.deleteProject(input, region);
            case "ListProjects" -> core.listProjects(input, region);
            case "CreateProjectMembership" -> core.createProjectMembership(input, region);
            case "DeleteProjectMembership" -> core.deleteProjectMembership(input, region);
            case "ListProjectMemberships" -> core.listProjectMemberships(input, region);
            case "ListNotifications" -> core.listNotifications(input, region);
            case "ListTagsForResource" -> core.listTagsForResource(input, region);
            case "TagResource" -> core.tagResource(input, region);
            case "UntagResource" -> core.untagResource(input, region);
            case "ListEnvironmentBlueprints" -> environments.listEnvironmentBlueprints(input, region);
            case "GetEnvironmentBlueprint" -> environments.getEnvironmentBlueprint(input, region);
            case "CreateEnvironmentBlueprint" -> environments.createEnvironmentBlueprint(input, region);
            case "UpdateEnvironmentBlueprint" -> environments.updateEnvironmentBlueprint(input, region);
            case "DeleteEnvironmentBlueprint" -> environments.deleteEnvironmentBlueprint(input, region);
            case "PutEnvironmentBlueprintConfiguration" ->
                    environments.putEnvironmentBlueprintConfiguration(input, region);
            case "GetEnvironmentBlueprintConfiguration" ->
                    environments.getEnvironmentBlueprintConfiguration(input, region);
            case "DeleteEnvironmentBlueprintConfiguration" ->
                    environments.deleteEnvironmentBlueprintConfiguration(input, region);
            case "ListEnvironmentBlueprintConfigurations" ->
                    environments.listEnvironmentBlueprintConfigurations(input, region);
            case "CreateEnvironmentProfile" -> environments.createEnvironmentProfile(input, region);
            case "GetEnvironmentProfile" -> environments.getEnvironmentProfile(input, region);
            case "UpdateEnvironmentProfile" -> environments.updateEnvironmentProfile(input, region);
            case "DeleteEnvironmentProfile" -> environments.deleteEnvironmentProfile(input, region);
            case "ListEnvironmentProfiles" -> environments.listEnvironmentProfiles(input, region);
            case "CreateEnvironment" -> environments.createEnvironment(input, region);
            case "GetEnvironment" -> environments.getEnvironment(input, region);
            case "UpdateEnvironment" -> environments.updateEnvironment(input, region);
            case "DeleteEnvironment" -> environments.deleteEnvironment(input, region);
            case "ListEnvironments" -> environments.listEnvironments(input, region);
            case "GetEnvironmentCredentials" -> environments.getEnvironmentCredentials(input, region);
            case "CreateEnvironmentAction" -> environments.createEnvironmentAction(input, region);
            case "GetEnvironmentAction" -> environments.getEnvironmentAction(input, region);
            case "UpdateEnvironmentAction" -> environments.updateEnvironmentAction(input, region);
            case "DeleteEnvironmentAction" -> environments.deleteEnvironmentAction(input, region);
            case "ListEnvironmentActions" -> environments.listEnvironmentActions(input, region);
            case "CreateSubscriptionTarget" -> environments.createSubscriptionTarget(input, region);
            case "GetSubscriptionTarget" -> environments.getSubscriptionTarget(input, region);
            case "UpdateSubscriptionTarget" -> environments.updateSubscriptionTarget(input, region);
            case "DeleteSubscriptionTarget" -> environments.deleteSubscriptionTarget(input, region);
            case "ListSubscriptionTargets" -> environments.listSubscriptionTargets(input, region);
            case "CreateFormType" -> catalog.createFormType(input, region);
            case "GetFormType" -> catalog.getFormType(input, region);
            case "DeleteFormType" -> catalog.deleteFormType(input, region);
            case "CreateAssetType" -> catalog.createAssetType(input, region);
            case "GetAssetType" -> catalog.getAssetType(input, region);
            case "DeleteAssetType" -> catalog.deleteAssetType(input, region);
            case "CreateAsset" -> catalog.createAsset(input, region);
            case "GetAsset" -> catalog.getAsset(input, region);
            case "CreateAssetRevision" -> catalog.createAssetRevision(input, region);
            case "ListAssetRevisions" -> catalog.listAssetRevisions(input, region);
            case "DeleteAsset" -> catalog.deleteAsset(input, region);
            case "AcceptPredictions" -> catalog.acceptPredictions(input, region);
            case "RejectPredictions" -> catalog.rejectPredictions(input, region);
            case "CreateListingChangeSet" -> catalog.createListingChangeSet(input, region);
            case "GetListing" -> catalog.getListing(input, region);
            case "DeleteListing" -> catalog.deleteListing(input, region);
            case "Search" -> catalog.search(input, region);
            case "SearchListings" -> catalog.searchListings(input, region);
            case "SearchTypes" -> catalog.searchTypes(input, region);
            case "CreateGlossary" -> catalog.createGlossary(input, region);
            case "GetGlossary" -> catalog.getGlossary(input, region);
            case "UpdateGlossary" -> catalog.updateGlossary(input, region);
            case "DeleteGlossary" -> catalog.deleteGlossary(input, region);
            case "CreateGlossaryTerm" -> catalog.createGlossaryTerm(input, region);
            case "GetGlossaryTerm" -> catalog.getGlossaryTerm(input, region);
            case "UpdateGlossaryTerm" -> catalog.updateGlossaryTerm(input, region);
            case "DeleteGlossaryTerm" -> catalog.deleteGlossaryTerm(input, region);
            case "PostTimeSeriesDataPoints" -> catalog.postTimeSeriesDataPoints(input, region);
            case "ListTimeSeriesDataPoints" -> catalog.listTimeSeriesDataPoints(input, region);
            case "GetTimeSeriesDataPoint" -> catalog.getTimeSeriesDataPoint(input, region);
            case "DeleteTimeSeriesDataPoints" -> catalog.deleteTimeSeriesDataPoints(input, region);
            case "CreateSubscriptionRequest" -> subscriptions.createSubscriptionRequest(input, region);
            case "GetSubscriptionRequestDetails" -> subscriptions.getSubscriptionRequestDetails(input, region);
            case "UpdateSubscriptionRequest" -> subscriptions.updateSubscriptionRequest(input, region);
            case "DeleteSubscriptionRequest" -> subscriptions.deleteSubscriptionRequest(input, region);
            case "AcceptSubscriptionRequest" -> subscriptions.acceptSubscriptionRequest(input, region);
            case "RejectSubscriptionRequest" -> subscriptions.rejectSubscriptionRequest(input, region);
            case "ListSubscriptionRequests" -> subscriptions.listSubscriptionRequests(input, region);
            case "GetSubscription" -> subscriptions.getSubscription(input, region);
            case "ListSubscriptions" -> subscriptions.listSubscriptions(input, region);
            case "CancelSubscription" -> subscriptions.cancelSubscription(input, region);
            case "RevokeSubscription" -> subscriptions.revokeSubscription(input, region);
            case "CreateSubscriptionGrant" -> subscriptions.createSubscriptionGrant(input, region);
            case "GetSubscriptionGrant" -> subscriptions.getSubscriptionGrant(input, region);
            case "DeleteSubscriptionGrant" -> subscriptions.deleteSubscriptionGrant(input, region);
            case "ListSubscriptionGrants" -> subscriptions.listSubscriptionGrants(input, region);
            case "CreateDataSource" -> ingestion.createDataSource(input, region);
            case "GetDataSource" -> ingestion.getDataSource(input, region);
            case "UpdateDataSource" -> ingestion.updateDataSource(input, region);
            case "DeleteDataSource" -> ingestion.deleteDataSource(input, region);
            case "ListDataSources" -> ingestion.listDataSources(input, region);
            case "StartDataSourceRun" -> ingestion.startDataSourceRun(input, region);
            case "GetDataSourceRun" -> ingestion.getDataSourceRun(input, region);
            case "ListDataSourceRuns" -> ingestion.listDataSourceRuns(input, region);
            case "ListDataSourceRunActivities" -> ingestion.listDataSourceRunActivities(input, region);
            case "StartMetadataGenerationRun" -> ingestion.startMetadataGenerationRun(input, region);
            case "GetMetadataGenerationRun" -> ingestion.getMetadataGenerationRun(input, region);
            case "CancelMetadataGenerationRun" -> ingestion.cancelMetadataGenerationRun(input, region);
            case "ListMetadataGenerationRuns" -> ingestion.listMetadataGenerationRuns(input, region);
            case "PostLineageEvent" -> ingestion.postLineageEvent(input, region);
            case "GetLineageNode" -> ingestion.getLineageNode(input, region);
            case "ListLineageNodeHistory" -> ingestion.listLineageNodeHistory(input, region);
            case "GetLineageEvent" -> ingestion.getLineageEvent(input, region);
            case "ListLineageEvents" -> ingestion.listLineageEvents(input, region);
            case "AddEntityOwner" -> governance.addEntityOwner(input, region);
            case "RemoveEntityOwner" -> governance.removeEntityOwner(input, region);
            case "ListEntityOwners" -> governance.listEntityOwners(input, region);
            case "UpdateRootDomainUnitOwner" -> governance.updateRootDomainUnitOwner(input, region);
            case "AddPolicyGrant" -> governance.addPolicyGrant(input, region);
            case "RemovePolicyGrant" -> governance.removePolicyGrant(input, region);
            case "ListPolicyGrants" -> governance.listPolicyGrants(input, region);
            case "CreateGroupProfile" -> governance.createGroupProfile(input, region);
            case "GetGroupProfile" -> governance.getGroupProfile(input, region);
            case "UpdateGroupProfile" -> governance.updateGroupProfile(input, region);
            case "SearchGroupProfiles" -> governance.searchGroupProfiles(input, region);
            case "CreateRule" -> governance.createRule(input, region);
            case "GetRule" -> governance.getRule(input, region);
            case "UpdateRule" -> governance.updateRule(input, region);
            case "DeleteRule" -> governance.deleteRule(input, region);
            case "ListRules" -> governance.listRules(input, region);
            case "CreateDesignation" -> governance.createDesignation(input, region);
            case "GetDesignation" -> governance.getDesignation(input, region);
            case "UpdateDesignation" -> governance.updateDesignation(input, region);
            case "DeleteDesignation" -> governance.deleteDesignation(input, region);
            case "ListDesignations" -> governance.listDesignations(input, region);
            case "CreateProjectProfile" -> governance.createProjectProfile(input, region);
            case "GetProjectProfile" -> governance.getProjectProfile(input, region);
            case "UpdateProjectProfile" -> governance.updateProjectProfile(input, region);
            case "DeleteProjectProfile" -> governance.deleteProjectProfile(input, region);
            case "ListProjectProfiles" -> governance.listProjectProfiles(input, region);
            case "CreateAccountPool" -> governance.createAccountPool(input, region);
            case "GetAccountPool" -> governance.getAccountPool(input, region);
            case "UpdateAccountPool" -> governance.updateAccountPool(input, region);
            case "DeleteAccountPool" -> governance.deleteAccountPool(input, region);
            case "ListAccountPools" -> governance.listAccountPools(input, region);
            case "ListAccountsInAccountPool" -> governance.listAccountsInAccountPool(input, region);
            case "CreateConnection" -> governance.createConnection(input, region);
            case "GetConnection" -> governance.getConnection(input, region);
            case "UpdateConnection" -> governance.updateConnection(input, region);
            case "DeleteConnection" -> governance.deleteConnection(input, region);
            case "ListConnections" -> governance.listConnections(input, region);
            case "CreateDataProduct" -> governance.createDataProduct(input, region);
            case "CreateDataProductRevision" -> governance.createDataProductRevision(input, region);
            case "GetDataProduct" -> governance.getDataProduct(input, region);
            case "DeleteDataProduct" -> governance.deleteDataProduct(input, region);
            case "ListDataProductRevisions" -> governance.listDataProductRevisions(input, region);
            case "CreateAssetFilter" -> governance.createAssetFilter(input, region);
            case "GetAssetFilter" -> governance.getAssetFilter(input, region);
            case "UpdateAssetFilter" -> governance.updateAssetFilter(input, region);
            case "DeleteAssetFilter" -> governance.deleteAssetFilter(input, region);
            case "ListAssetFilters" -> governance.listAssetFilters(input, region);
            case "AssociateGovernedTerms" -> governance.associateGovernedTerms(input, region);
            case "DisassociateGovernedTerms" -> governance.disassociateGovernedTerms(input, region);
            case "BatchPutAttributesMetadata" -> governance.batchPutAttributesMetadata(input, region);
            case "BatchGetAttributesMetadata" -> governance.batchGetAttributesMetadata(input, region);
            case "AssociateEnvironmentRole" -> governance.associateEnvironmentRole(input, region);
            case "DisassociateEnvironmentRole" -> governance.disassociateEnvironmentRole(input, region);
            case "PutDataExportConfiguration" -> governance.putDataExportConfiguration(input, region);
            case "GetDataExportConfiguration" -> governance.getDataExportConfiguration(input, region);
            case "DeleteDataExportConfiguration" -> governance.deleteDataExportConfiguration(input, region);
            case "QueryGraph" -> governance.queryGraph(input, region);
            case "DeleteLineageEvent" -> governance.deleteLineageEvent(input, region);
            case "GetJobRun" -> governance.getJobRun(input, region);
            case "ListJobRuns" -> governance.listJobRuns(input, region);
            case "UpdateSubscriptionGrantStatus" -> governance.updateSubscriptionGrantStatus(input, region);
            case "CreateNotebook" -> notebooks.createNotebook(input, region);
            case "GetNotebook" -> notebooks.getNotebook(input, region);
            case "UpdateNotebook" -> notebooks.updateNotebook(input, region);
            case "DeleteNotebook" -> notebooks.deleteNotebook(input, region);
            case "ListNotebooks" -> notebooks.listNotebooks(input, region);
            case "StartNotebookImport" -> notebooks.startNotebookImport(input, region);
            case "StartNotebookSync" -> notebooks.startNotebookSync(input, region);
            case "StartNotebookExport" -> notebooks.startNotebookExport(input, region);
            case "GetNotebookExport" -> notebooks.getNotebookExport(input, region);
            case "StartNotebookRun" -> notebooks.startNotebookRun(input, region);
            case "GetNotebookRun" -> notebooks.getNotebookRun(input, region);
            case "StopNotebookRun" -> notebooks.stopNotebookRun(input, region);
            case "ListNotebookRuns" -> notebooks.listNotebookRuns(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
