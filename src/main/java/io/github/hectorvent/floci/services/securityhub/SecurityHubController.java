package io.github.hectorvent.floci.services.securityhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.securityhub.model.Hub;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubActionTarget;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubAutomationRule;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubFindingAggregator;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubInsight;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * AWS Security Hub restJson1.
 *
 * <p>{@link SecurityHubRoutingFilter} prefixes SigV4 {@code securityhub} requests
 * so literal {@code /accounts}, {@code /findings} and {@code /members} paths
 * do not collide with S3's {@code /{bucket}} catch-all. Tag APIs share
 * {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 */
@Path(SecurityHubRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SecurityHubController {

    private final SecurityHubService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public SecurityHubController(
            SecurityHubService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/accounts")
    @Consumes(MediaType.WILDCARD)
    public Response describeHub(@Context HttpHeaders headers, @QueryParam("HubArn") String hubArn) {
        Hub hub = service.describeHub(region(headers));
        if (hubArn != null && !hubArn.isBlank() && !hub.getHubArn().equals(hubArn)) {
            throw SecurityHubService.invalidInput("HubArn is invalid.");
        }
        return Response.ok(toHub(hub)).build();
    }

    @POST
    @Path("/accounts")
    @Consumes(MediaType.WILDCARD)
    public Response enableSecurityHub(@Context HttpHeaders headers, String body) {
        service.enableSecurityHub(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PATCH
    @Path("/accounts")
    @Consumes(MediaType.WILDCARD)
    public Response updateSecurityHubConfiguration(@Context HttpHeaders headers, String body) {
        service.updateSecurityHubConfiguration(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/accounts")
    @Consumes(MediaType.WILDCARD)
    public Response disableSecurityHub(@Context HttpHeaders headers) {
        service.disableSecurityHub(region(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/findings/import")
    public Response batchImportFindings(@Context HttpHeaders headers, String body) {
        return Response.ok(service.batchImportFindings(region(headers), parse(body))).build();
    }

    @POST
    @Path("/findings")
    @Consumes(MediaType.WILDCARD)
    public Response getFindings(@Context HttpHeaders headers, String body) {
        return Response.ok(service.getFindings(region(headers), parse(body))).build();
    }

    @PATCH
    @Path("/findings/batchupdate")
    public Response batchUpdateFindings(@Context HttpHeaders headers, String body) {
        return Response.ok(service.batchUpdateFindings(region(headers), parse(body))).build();
    }

    @POST
    @Path("/findingHistory/get")
    public Response getFindingHistory(@Context HttpHeaders headers, String body) {
        return Response.ok(service.getFindingHistory(region(headers), parse(body))).build();
    }

    @GET
    @Path("/standards")
    @Consumes(MediaType.WILDCARD)
    public Response describeStandards(@Context HttpHeaders headers) {
        return Response.ok(service.describeStandards(region(headers))).build();
    }

    @POST
    @Path("/standards/get")
    @Consumes(MediaType.WILDCARD)
    public Response getEnabledStandards(@Context HttpHeaders headers, String body) {
        parse(body);
        return Response.ok(service.getEnabledStandards(region(headers))).build();
    }

    @GET
    @Path("/securityControls/definitions")
    @Consumes(MediaType.WILDCARD)
    public Response listSecurityControlDefinitions(
            @Context HttpHeaders headers, @QueryParam("MaxResults") Integer maxResults) {
        service.describeHub(region(headers));
        int limit = maxResults == null ? 100 : maxResults;
        return Response.ok(service.listSecurityControlDefinitions(limit)).build();
    }

    @GET
    @Path("/securityControl/definition")
    @Consumes(MediaType.WILDCARD)
    public Response getSecurityControlDefinition(
            @Context HttpHeaders headers, @QueryParam("SecurityControlId") String securityControlId) {
        service.describeHub(region(headers));
        return Response.ok(service.getSecurityControlDefinition(securityControlId)).build();
    }

    @GET
    @Path("/products")
    @Consumes(MediaType.WILDCARD)
    public Response describeProducts(@Context HttpHeaders headers) {
        String region = region(headers);
        service.describeHub(region);
        return Response.ok(service.describeProducts(region)).build();
    }

    @GET
    @Path("/productSubscriptions")
    @Consumes(MediaType.WILDCARD)
    public Response listEnabledProductsForImport(@Context HttpHeaders headers) {
        return Response.ok(service.listEnabledProductsForImport(region(headers))).build();
    }

    @POST
    @Path("/actionTargets")
    public Response createActionTarget(@Context HttpHeaders headers, String body) {
        SecurityHubActionTarget target = service.createActionTarget(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ActionTargetArn", target.getActionTargetArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/actionTargets/get")
    @Consumes(MediaType.WILDCARD)
    public Response describeActionTargets(@Context HttpHeaders headers, String body) {
        List<SecurityHubActionTarget> targets =
                service.describeActionTargets(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ActionTargets");
        for (SecurityHubActionTarget target : targets) {
            list.add(toActionTarget(target));
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/actionTargets/{arn:.+}")
    public Response updateActionTarget(
            @Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        service.updateActionTarget(region(headers), arn, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/actionTargets/{arn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteActionTarget(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ActionTargetArn", service.deleteActionTarget(region(headers), arn));
        return Response.ok(response).build();
    }

    @POST
    @Path("/insights")
    public Response createInsight(@Context HttpHeaders headers, String body) {
        SecurityHubInsight insight = service.createInsight(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("InsightArn", insight.getInsightArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/insights/get")
    @Consumes(MediaType.WILDCARD)
    public Response getInsights(@Context HttpHeaders headers, String body) {
        List<SecurityHubInsight> insights = service.getInsights(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Insights");
        for (SecurityHubInsight insight : insights) {
            list.add(toInsight(insight));
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/insights/{arn:.+}")
    public Response updateInsight(
            @Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        service.updateInsight(region(headers), arn, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/insights/{arn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteInsight(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("InsightArn", service.deleteInsight(region(headers), arn));
        return Response.ok(response).build();
    }

    @POST
    @Path("/automationrules/create")
    public Response createAutomationRule(@Context HttpHeaders headers, String body) {
        SecurityHubAutomationRule rule = service.createAutomationRule(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("RuleArn", rule.getRuleArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/automationrules/get")
    @Consumes(MediaType.WILDCARD)
    public Response batchGetAutomationRules(@Context HttpHeaders headers, String body) {
        SecurityHubService.BatchRulesResult result =
                service.batchGetAutomationRules(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode rules = response.putArray("Rules");
        for (SecurityHubAutomationRule rule : result.rules()) {
            rules.add(toRule(rule));
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/automationrules/update")
    public Response batchUpdateAutomationRules(@Context HttpHeaders headers, String body) {
        SecurityHubService.BatchRulesResult result =
                service.batchUpdateAutomationRules(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode processed = response.putArray("ProcessedAutomationRules");
        result.processed().forEach(processed::add);
        return Response.ok(response).build();
    }

    @POST
    @Path("/automationrules/delete")
    public Response batchDeleteAutomationRules(@Context HttpHeaders headers, String body) {
        SecurityHubService.BatchRulesResult result =
                service.batchDeleteAutomationRules(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode processed = response.putArray("ProcessedAutomationRules");
        result.processed().forEach(processed::add);
        return Response.ok(response).build();
    }

    @GET
    @Path("/automationrules/list")
    @Consumes(MediaType.WILDCARD)
    public Response listAutomationRules(@Context HttpHeaders headers) {
        List<SecurityHubAutomationRule> rules = service.listAutomationRules(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("AutomationRulesMetadata");
        for (SecurityHubAutomationRule rule : rules) {
            list.add(toRule(rule));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/findingAggregator/create")
    public Response createFindingAggregator(@Context HttpHeaders headers, String body) {
        return Response.ok(toAggregator(service.createFindingAggregator(region(headers), parse(body))))
                .build();
    }

    @GET
    @Path("/findingAggregator/get/{arn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getFindingAggregator(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return Response.ok(toAggregator(service.getFindingAggregator(region(headers), arn))).build();
    }

    @GET
    @Path("/findingAggregator/list")
    @Consumes(MediaType.WILDCARD)
    public Response listFindingAggregators(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("FindingAggregators");
        for (SecurityHubFindingAggregator aggregator : service.listFindingAggregators(region(headers))) {
            ObjectNode node = list.addObject();
            node.put("FindingAggregatorArn", aggregator.getFindingAggregatorArn());
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/findingAggregator/update")
    public Response updateFindingAggregator(@Context HttpHeaders headers, String body) {
        return Response.ok(toAggregator(service.updateFindingAggregator(region(headers), parse(body))))
                .build();
    }

    @DELETE
    @Path("/findingAggregator/delete/{arn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteFindingAggregator(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        service.deleteFindingAggregator(region(headers), arn);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/administrator")
    @Consumes(MediaType.WILDCARD)
    public Response getAdministratorAccount(@Context HttpHeaders headers) {
        return Response.ok(service.getAdministratorAccount(region(headers))).build();
    }

    @GET
    @Path("/members")
    @Consumes(MediaType.WILDCARD)
    public Response listMembers(@Context HttpHeaders headers) {
        return Response.ok(service.emptyList(region(headers), "Members")).build();
    }

    @GET
    @Path("/invitations")
    @Consumes(MediaType.WILDCARD)
    public Response listInvitations(@Context HttpHeaders headers) {
        return Response.ok(service.emptyList(region(headers), "Invitations")).build();
    }

    @GET
    @Path("/invitations/count")
    @Consumes(MediaType.WILDCARD)
    public Response getInvitationsCount(@Context HttpHeaders headers) {
        return Response.ok(service.getInvitationsCount(region(headers))).build();
    }

    @GET
    @Path("/organization/admin")
    @Consumes(MediaType.WILDCARD)
    public Response listOrganizationAdminAccounts(@Context HttpHeaders headers) {
        return Response.ok(service.listOrganizationAdminAccounts(region(headers))).build();
    }

    @GET
    @Path("/organization/configuration")
    @Consumes(MediaType.WILDCARD)
    public Response describeOrganizationConfiguration(@Context HttpHeaders headers) {
        return Response.ok(service.describeOrganizationConfiguration(region(headers))).build();
    }

    private ObjectNode toHub(Hub hub) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("HubArn", hub.getHubArn());
        node.put("SubscribedAt", hub.getSubscribedAt());
        node.put("AutoEnableControls", hub.isAutoEnableControls());
        node.put("ControlFindingGenerator", hub.getControlFindingGenerator());
        return node;
    }

    private ObjectNode toActionTarget(SecurityHubActionTarget target) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ActionTargetArn", target.getActionTargetArn());
        node.put("Name", target.getName());
        node.put("Description", target.getDescription());
        return node;
    }

    private ObjectNode toInsight(SecurityHubInsight insight) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("InsightArn", insight.getInsightArn());
        node.put("Name", insight.getName());
        node.put("GroupByAttribute", insight.getGroupByAttribute());
        node.set("Filters", objectMapper.valueToTree(insight.getFilters()));
        return node;
    }

    private ObjectNode toRule(SecurityHubAutomationRule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("RuleArn", rule.getRuleArn());
        node.put("RuleName", rule.getRuleName());
        node.put("Description", rule.getDescription());
        node.put("RuleOrder", rule.getRuleOrder());
        node.put("RuleStatus", rule.getRuleStatus());
        node.put("IsTerminal", rule.isTerminal());
        node.set("Criteria", objectMapper.valueToTree(rule.getCriteria()));
        node.set("Actions", objectMapper.valueToTree(rule.getActions()));
        if (rule.getCreatedAt() != null) {
            node.put("CreatedAt", rule.getCreatedAt());
        }
        if (rule.getUpdatedAt() != null) {
            node.put("UpdatedAt", rule.getUpdatedAt());
        }
        if (rule.getCreatedBy() != null) {
            node.put("CreatedBy", rule.getCreatedBy());
        }
        return node;
    }

    private ObjectNode toAggregator(SecurityHubFindingAggregator aggregator) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("FindingAggregatorArn", aggregator.getFindingAggregatorArn());
        node.put("FindingAggregationRegion", aggregator.getFindingAggregationRegion());
        node.put("RegionLinkingMode", aggregator.getRegionLinkingMode());
        ArrayNode regions = node.putArray("Regions");
        for (String value : aggregator.getRegions()) {
            regions.add(value);
        }
        return node;
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw SecurityHubService.invalidInput("Request body must be a JSON object.");
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw SecurityHubService.invalidInput("Request body is not valid JSON.");
        }
    }
}
