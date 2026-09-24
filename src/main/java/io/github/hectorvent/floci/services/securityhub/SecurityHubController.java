package io.github.hectorvent.floci.services.securityhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubAssociation;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubState;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SecurityHubController {
    private final SecurityHubService securityHubService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SecurityHubController(SecurityHubService securityHubService,
                                 RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.securityHubService = securityHubService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/organization/admin")
    public Response listOrganizationAdminAccounts(@Context HttpHeaders headers,
                                                  @QueryParam("Feature") String feature,
                                                  @QueryParam("MaxResults") String maxResultsValue,
                                                  @QueryParam("NextToken") String nextToken) {
        Integer maxResults = Pagination.parseMaxResults(maxResultsValue, "InvalidInputException");
        if (maxResults != null && maxResults > 10) {
            throw new AwsException("InvalidInputException", "MaxResults must be between 1 and 10.", 400);
        }
        if (nextToken != null && !nextToken.isBlank()) {
            throw new AwsException("InvalidInputException", "NextToken is invalid.", 400);
        }
        SecurityHubState state = securityHubService.organizationAdminState(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        var accounts = response.putArray("AdminAccounts");
        String requestedFeature = securityHubService.normalizeFeature(feature);
        if (state.getAdminAccountId() != null && requestedFeature.equals(state.getAdminFeature())) {
            accounts.addObject()
                    .put("AccountId", state.getAdminAccountId())
                    .put("Status", "ENABLED");
        }
        response.put("Feature", requestedFeature);
        return Response.ok(response).build();
    }

    @POST
    @Path("/organization/admin/enable")
    public Response enableOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        SecurityHubState state = securityHubService.enableOrganizationAdminAccount(
                region(headers), request.path("AdminAccountId").asText(null), request.path("Feature").asText(null));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AdminAccountId", state.getAdminAccountId());
        response.put("Feature", state.getAdminFeature());
        return Response.ok(response).build();
    }

    @GET
    @Path("/accounts")
    public Response describeHub(@Context HttpHeaders headers) {
        String region = region(headers);
        securityHubService.requireEnabled(region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("HubArn", securityHubService.hubArn(region));
        SecurityHubState state = securityHubService.state(region);
        response.put("AutoEnableControls", state.isAutoEnableControls());
        response.put("ControlFindingGenerator", state.getControlFindingGenerator());
        return Response.ok(response).build();
    }

    @POST
    @Path("/accounts")
    public Response enableSecurityHub(@Context HttpHeaders headers, String body) {
        securityHubService.enableSecurityHub(region(headers), readTree(body));
        return empty();
    }

    @DELETE
    @Path("/accounts")
    public Response disableSecurityHub(@Context HttpHeaders headers) {
        securityHubService.disableSecurityHub(region(headers));
        return empty();
    }

    @PATCH
    @Path("/accounts")
    public Response updateSecurityHubConfiguration(@Context HttpHeaders headers, String body) {
        securityHubService.updateSecurityHubConfiguration(region(headers), readTree(body));
        return empty();
    }

    @GET
    @Path("/findingAggregator/list")
    public Response listFindingAggregators(@Context HttpHeaders headers,
                                           @QueryParam("MaxResults") String maxResultsValue,
                                           @QueryParam("NextToken") String nextToken) {
        Integer maxResults = Pagination.parseMaxResults(maxResultsValue, "InvalidInputException");
        if (maxResults != null && (maxResults < 1 || maxResults > 100)) {
            throw new AwsException("InvalidInputException", "MaxResults must be between 1 and 100.", 400);
        }
        if (nextToken != null && !nextToken.isBlank()) {
            throw new AwsException("InvalidInputException", "NextToken is invalid.", 400);
        }
        SecurityHubState state = securityHubService.state(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        var items = response.putArray("FindingAggregators");
        if (state.getAggregatorArn() != null) {
            items.addObject().put("FindingAggregatorArn", state.getAggregatorArn());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/findingAggregator/create")
    public Response createFindingAggregator(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        return aggregator(securityHubService.createFindingAggregator(region, readTree(body)), region);
    }

    @GET
    @Path("/findingAggregator/get/{findingAggregatorArn: .+}")
    public Response getFindingAggregator(@Context HttpHeaders headers,
                                         @PathParam("findingAggregatorArn") String findingAggregatorArn) {
        String region = region(headers);
        return aggregator(securityHubService.getFindingAggregator(region, findingAggregatorArn), region);
    }

    @PATCH
    @Path("/findingAggregator/update")
    public Response updateFindingAggregator(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        SecurityHubState state = securityHubService.updateFindingAggregator(region, readTree(body));
        return aggregator(state, region);
    }

    @GET
    @Path("/organization/configuration")
    public Response describeOrganizationConfiguration(@Context HttpHeaders headers) {
        SecurityHubState state = securityHubService.organizationConfiguration(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AutoEnable", state.isAutoEnable());
        response.put("AutoEnableStandards", state.getAutoEnableStandards());
        response.put("MemberAccountLimitReached", false);
        ObjectNode configuration = response.putObject("OrganizationConfiguration");
        configuration.put("ConfigurationType", state.getOrganizationConfigurationType());
        configuration.put("Status", state.getOrganizationConfigurationStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/organization/configuration")
    public Response updateOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        securityHubService.updateOrganizationConfiguration(region(headers), readTree(body));
        return empty();
    }

    @GET
    @Path("/configurationPolicy/list")
    public Response listConfigurationPolicies(@Context HttpHeaders headers,
                                              @QueryParam("MaxResults") String maxResultsValue,
                                              @QueryParam("NextToken") String nextToken) {
        String region = region(headers);
        Integer maxResults = Pagination.parseMaxResults(maxResultsValue, "InvalidInputException");
        PaginatedResult<java.util.Map.Entry<String, JsonNode>> page =
                securityHubService.policyPage(region, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        var items = response.putArray("ConfigurationPolicySummaries");
        page.items().forEach(entry -> items.add(policySummary(region, entry.getKey(), entry.getValue())));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/configurationPolicy/create")
    public Response createConfigurationPolicy(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = readTree(body);
        String id = securityHubService.createConfigurationPolicy(region, request);
        return Response.ok(policyDocument(region, id, request)).build();
    }

    @GET
    @Path("/configurationPolicy/get/{id: .+}")
    public Response getConfigurationPolicy(@Context HttpHeaders headers, @PathParam("id") String id) {
        String region = region(headers);
        return Response.ok(policyDocument(region, id, securityHubService.getConfigurationPolicy(region, id))).build();
    }

    @PATCH
    @Path("/configurationPolicy/{id: .+}")
    public Response updateConfigurationPolicy(@Context HttpHeaders headers, @PathParam("id") String id, String body) {
        String region = region(headers);
        return Response.ok(policyDocument(region, id,
                securityHubService.updateConfigurationPolicy(region, id, readTree(body)))).build();
    }

    @POST
    @Path("/configurationPolicyAssociation/get")
    public Response getConfigurationPolicyAssociation(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = readTree(body);
        return Response.ok(association(securityHubService.association(region, request))).build();
    }

    @POST
    @Path("/configurationPolicyAssociation/associate")
    public Response startConfigurationPolicyAssociation(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = readTree(body);
        return Response.ok(association(securityHubService.associate(region, request))).build();
    }

    @POST
    @Path("/configurationPolicyAssociation/disassociate")
    public Response startConfigurationPolicyDisassociation(@Context HttpHeaders headers, String body) {
        securityHubService.disassociate(region(headers), readTree(body));
        return empty();
    }

    @POST
    @Path("/configurationPolicyAssociation/list")
    public Response listConfigurationPolicyAssociations(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        PaginatedResult<SecurityHubAssociation> page = securityHubService.associationPage(region, readTree(body));
        ObjectNode response = objectMapper.createObjectNode();
        var items = response.putArray("ConfigurationPolicyAssociationSummaries");
        page.items().forEach(entry -> items.add(association(entry)));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/actionTargets/get")
    public Response describeActionTargets(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.describeActionTargets(region(headers), readTree(body))).build();
    }

    @POST
    @Path("/actionTargets")
    public Response createActionTarget(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.createActionTarget(region(headers), readTree(body))).build();
    }

    @PATCH
    @Path("/actionTargets/{arn: .+}")
    public Response updateActionTarget(@Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        securityHubService.updateActionTarget(region(headers), arn, readTree(body));
        return empty();
    }

    @DELETE
    @Path("/actionTargets/{arn: .+}")
    public Response deleteActionTarget(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return Response.ok(securityHubService.deleteActionTarget(region(headers), arn)).build();
    }

    @POST
    @Path("/insights/get")
    public Response getInsights(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.getInsights(region(headers), readTree(body))).build();
    }

    @POST
    @Path("/insights")
    public Response createInsight(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.createInsight(region(headers), readTree(body))).build();
    }

    @PATCH
    @Path("/insights/{arn: .+}")
    public Response updateInsight(@Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        securityHubService.updateInsight(region(headers), arn, readTree(body));
        return empty();
    }

    @DELETE
    @Path("/insights/{arn: .+}")
    public Response deleteInsight(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return Response.ok(securityHubService.deleteInsight(region(headers), arn)).build();
    }

    @POST
    @Path("/automationrules/create")
    public Response createAutomationRule(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.createAutomationRule(region(headers), readTree(body))).build();
    }

    @GET
    @Path("/automationrules/list")
    public Response listAutomationRules(@Context HttpHeaders headers, @QueryParam("MaxResults") String maxResults,
                                        @QueryParam("NextToken") String nextToken) {
        return Response.ok(securityHubService.listAutomationRules(region(headers), pageRequest(maxResults, nextToken))).build();
    }

    @POST
    @Path("/automationrules/get")
    public Response batchGetAutomationRules(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.batchAutomationRules(region(headers), readTree(body), "get")).build();
    }

    @PATCH
    @Path("/automationrules/update")
    public Response batchUpdateAutomationRules(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.batchAutomationRules(region(headers), readTree(body), "update")).build();
    }

    @POST
    @Path("/automationrules/delete")
    public Response batchDeleteAutomationRules(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.batchAutomationRules(region(headers), readTree(body), "delete")).build();
    }

    @DELETE
    @Path("/findingAggregator/delete/{arn: .+}")
    public Response deleteFindingAggregator(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        securityHubService.deleteFindingAggregator(region(headers), arn);
        return empty();
    }

    @POST
    @Path("/_securityhub/findings/import")
    public Response batchImportFindings(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.batchImportFindings(region(headers), readTree(body))).build();
    }

    @POST
    @Path("/_securityhub/findings")
    public Response getFindings(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.getFindings(region(headers), readTree(body))).build();
    }

    @PATCH
    @Path("/_securityhub/findings/batchupdate")
    public Response batchUpdateFindings(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.batchUpdateFindings(region(headers), readTree(body))).build();
    }

    @POST
    @Path("/findingHistory/get")
    public Response getFindingHistory(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.getFindingHistory(region(headers), readTree(body))).build();
    }

    @GET
    @Path("/products")
    public Response describeProducts(@Context HttpHeaders headers, @QueryParam("MaxResults") String maxResults,
                                     @QueryParam("NextToken") String nextToken, @QueryParam("ProductArn") String arn) {
        ObjectNode request = pageRequest(maxResults, nextToken);
        if (arn != null) {
            request.put("ProductArn", arn);
        }
        return Response.ok(securityHubService.describeProducts(region(headers), request)).build();
    }

    @GET
    @Path("/standards")
    public Response describeStandards(@Context HttpHeaders headers, @QueryParam("MaxResults") String maxResults,
                                      @QueryParam("NextToken") String nextToken) {
        return Response.ok(securityHubService.describeStandards(region(headers), pageRequest(maxResults, nextToken))).build();
    }

    @POST
    @Path("/standards/get")
    public Response getEnabledStandards(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.getEnabledStandards(region(headers), readTree(body))).build();
    }

    @POST
    @Path("/standards/register")
    public Response batchEnableStandards(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.batchEnableStandards(region(headers), readTree(body))).build();
    }

    @POST
    @Path("/standards/deregister")
    public Response batchDisableStandards(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.batchDisableStandards(region(headers), readTree(body))).build();
    }

    @GET
    @Path("/securityControls/definitions")
    public Response listSecurityControlDefinitions(@Context HttpHeaders headers, @QueryParam("MaxResults") String maxResults,
            @QueryParam("NextToken") String nextToken, @QueryParam("StandardsArn") String arn) {
        ObjectNode request = pageRequest(maxResults, nextToken);
        if (arn != null) {
            request.put("StandardsArn", arn);
        }
        return Response.ok(securityHubService.listSecurityControlDefinitions(region(headers), request)).build();
    }

    @GET
    @Path("/securityControl/definition")
    public Response getSecurityControlDefinition(@QueryParam("SecurityControlId") String id) {
        return Response.ok(securityHubService.getSecurityControlDefinition(id)).build();
    }

    @GET
    @Path("/productSubscriptions")
    public Response listEnabledProductsForImport(@Context HttpHeaders headers, @QueryParam("MaxResults") String maxResults,
                                                  @QueryParam("NextToken") String nextToken) {
        return Response.ok(securityHubService.listEnabledProductsForImport(region(headers), pageRequest(maxResults, nextToken)))
                .build();
    }

    @POST
    @Path("/productSubscriptions")
    public Response enableImportFindingsForProduct(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.enableImportFindingsForProduct(region(headers), readTree(body))).build();
    }

    @DELETE
    @Path("/productSubscriptions/{arn: .+}")
    public Response disableImportFindingsForProduct(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        securityHubService.disableImportFindingsForProduct(region(headers), arn);
        return empty();
    }

    @GET
    @Path("/_securityhub/members")
    public Response listMembers(@Context HttpHeaders headers, @QueryParam("MaxResults") String maxResults,
                               @QueryParam("NextToken") String nextToken, @QueryParam("OnlyAssociated") String associated) {
        ObjectNode request = pageRequest(maxResults, nextToken);
        if (associated != null) {
            if (!"true".equals(associated) && !"false".equals(associated)) {
                throw new AwsException("InvalidInputException", "OnlyAssociated must be a boolean.", 400);
            }
            request.put("OnlyAssociated", Boolean.parseBoolean(associated));
        }
        return Response.ok(securityHubService.listMembers(region(headers), request)).build();
    }

    @POST
    @Path("/_securityhub/members")
    public Response createMembers(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.createMembers(region(headers), readTree(body))).build();
    }

    @POST
    @Path("/_securityhub/members/get")
    public Response getMembers(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.memberBatch(region(headers), readTree(body), false)).build();
    }

    @POST
    @Path("/_securityhub/members/delete")
    public Response deleteMembers(@Context HttpHeaders headers, String body) {
        return Response.ok(securityHubService.memberBatch(region(headers), readTree(body), true)).build();
    }

    @GET
    @Path("/_securityhub/administrator")
    public Response getAdministratorAccount(@Context HttpHeaders headers) {
        return Response.ok(securityHubService.getAdministratorAccount(region(headers))).build();
    }

    @GET
    @Path("/_securityhub/invitations")
    public Response listInvitations(@Context HttpHeaders headers, @QueryParam("MaxResults") String maxResults,
                                   @QueryParam("NextToken") String nextToken) {
        return Response.ok(securityHubService.listInvitations(region(headers), pageRequest(maxResults, nextToken))).build();
    }

    @GET
    @Path("/_securityhub/invitations/count")
    public Response getInvitationsCount(@Context HttpHeaders headers) {
        return Response.ok(securityHubService.getInvitationsCount(region(headers))).build();
    }

    private ObjectNode pageRequest(String maxResults, String nextToken) {
        ObjectNode request = objectMapper.createObjectNode();
        Integer maximum = Pagination.parseMaxResults(maxResults, "InvalidInputException");
        if (maximum != null) {
            request.put("MaxResults", maximum);
        }
        if (nextToken != null) {
            request.put("NextToken", nextToken);
        }
        return request;
    }

    private Response aggregator(SecurityHubState state, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FindingAggregatorArn", state.getAggregatorArn());
        response.put("FindingAggregationRegion", region);
        response.put("RegionLinkingMode", state.getRegionLinkingMode());
        if (state.getRegions() != null) {
            response.set("Regions", state.getRegions());
        }
        return Response.ok(response).build();
    }

    private ObjectNode policySummary(String region, String id, JsonNode policy) {
        int slash = id == null ? -1 : id.lastIndexOf('/');
        String policyId = slash >= 0 ? id.substring(slash + 1) : id;
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", securityHubService.policyArn(region, policyId));
        response.put("Id", policyId);
        response.put("Name", policy.path("Name").asText());
        if (policy.hasNonNull("Description")) {
            response.put("Description", policy.path("Description").asText());
        }
        JsonNode securityHub = policy.path("ConfigurationPolicy").path("SecurityHub");
        if (securityHub.path("ServiceEnabled").isBoolean()) {
            response.put("ServiceEnabled", securityHub.path("ServiceEnabled").asBoolean());
        }
        response.put("UpdatedAt", policy.path("UpdatedAt").asText(Instant.now().toString()));
        return response;
    }

    private ObjectNode policyDocument(String region, String id, JsonNode policy) {
        ObjectNode response = policySummary(region, id, policy);
        if (policy.has("ConfigurationPolicy")) {
            response.set("ConfigurationPolicy", policy.get("ConfigurationPolicy"));
        }
        response.put("CreatedAt", policy.path("CreatedAt").asText(Instant.now().toString()));
        return response;
    }

    private ObjectNode association(SecurityHubAssociation association) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AssociationStatus", association.getStatus());
        if (association.getStatusMessage() != null) {
            response.put("AssociationStatusMessage", association.getStatusMessage());
        }
        response.put("AssociationType", "APPLIED");
        response.put("ConfigurationPolicyId", association.getPolicyId());
        response.put("TargetId", association.getTargetId());
        response.put("TargetType", association.getTargetType());
        response.put("UpdatedAt", association.getUpdatedAt());
        return response;
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Response empty() {
        return Response.ok().build();
    }

    private JsonNode readTree(String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            if (request == null || !request.isObject()) {
                throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
            }
            return request;
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
