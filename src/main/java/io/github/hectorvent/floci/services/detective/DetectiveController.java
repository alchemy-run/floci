package io.github.hectorvent.floci.services.detective;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.detective.model.DetectiveOrg.Administrator;
import io.github.hectorvent.floci.services.detective.model.Graph;
import io.github.hectorvent.floci.services.detective.model.Investigation;
import io.github.hectorvent.floci.services.detective.model.Member;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Amazon Detective restJson1.
 *
 * <p>Literal {@code /graph}, {@code /graphs/list}, {@code /invitation},
 * {@code /investigations/*} and {@code /orgs/*} paths take JAX-RS precedence over
 * S3's {@code /{bucket}} catch-all. Tag APIs share {@code /tags/{arn}} and are
 * dispatched by {@code SharedTagsController}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DetectiveController {

    private final DetectiveService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public DetectiveController(
            DetectiveService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/graph")
    public Response createGraph(@Context HttpHeaders headers, String body) {
        Graph graph = service.createGraph(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("GraphArn", graph.getArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/removal")
    public Response deleteGraph(@Context HttpHeaders headers, String body) {
        service.deleteGraph(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/graphs/list")
    @Consumes(MediaType.WILDCARD)
    public Response listGraphs(@Context HttpHeaders headers, String body) {
        List<Graph> graphs = service.listGraphs(regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("GraphList");
        for (Graph graph : graphs) {
            ObjectNode node = list.addObject();
            node.put("Arn", graph.getArn());
            if (graph.getCreatedTime() != null) {
                node.put("CreatedTime", graph.getCreatedTime());
            }
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/members/list")
    public Response listMembers(@Context HttpHeaders headers, String body) {
        List<Member> members = service.listMembers(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("MemberDetails");
        for (Member member : members) {
            list.add(toMember(member));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/members/get")
    public Response getMembers(@Context HttpHeaders headers, String body) {
        DetectiveService.GetMembersResult result =
                service.getMembers(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode members = response.putArray("MemberDetails");
        for (Member member : result.members()) {
            members.add(toMember(member));
        }
        putUnprocessed(response, result.unprocessed());
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/members")
    public Response createMembers(@Context HttpHeaders headers, String body) {
        DetectiveService.GetMembersResult result =
                service.createMembers(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode members = response.putArray("Members");
        for (Member member : result.members()) {
            members.add(toMember(member));
        }
        putUnprocessed(response, result.unprocessed());
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/members/removal")
    public Response deleteMembers(@Context HttpHeaders headers, String body) {
        DetectiveService.DeleteMembersResult result =
                service.deleteMembers(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode ids = response.putArray("AccountIds");
        for (String accountId : result.accountIds()) {
            ids.add(accountId);
        }
        putUnprocessed(response, result.unprocessed());
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/member/monitoringstate")
    public Response startMonitoringMember(@Context HttpHeaders headers, String body) {
        service.startMonitoringMember(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/graph/datasources/list")
    public Response listDatasourcePackages(@Context HttpHeaders headers, String body) {
        Map<String, DetectiveService.DatasourcePackageIngestDetail> packages =
                service.listDatasourcePackages(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode details = response.putObject("DatasourcePackages");
        for (Map.Entry<String, DetectiveService.DatasourcePackageIngestDetail> entry : packages.entrySet()) {
            ObjectNode detail = details.putObject(entry.getKey());
            detail.put("DatasourcePackageIngestState", entry.getValue().ingestState());
            if (entry.getValue().startedAt() != null) {
                ObjectNode change = detail.putObject("LastIngestStateChange");
                ObjectNode started = change.putObject(entry.getValue().ingestState());
                started.put("Timestamp", entry.getValue().startedAt());
            }
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/datasources/update")
    public Response updateDatasourcePackages(@Context HttpHeaders headers, String body) {
        service.updateDatasourcePackages(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/graph/datasources/get")
    public Response batchGetGraphMemberDatasources(@Context HttpHeaders headers, String body) {
        DetectiveService.MemberDatasourcesResult result =
                service.batchGetGraphMemberDatasources(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("MemberDatasources");
        putUnprocessed(response, result.unprocessed());
        return Response.ok(response).build();
    }

    @POST
    @Path("/membership/datasources/get")
    public Response batchGetMembershipDatasources(@Context HttpHeaders headers, String body) {
        DetectiveService.MembershipDatasourcesResult result =
                service.batchGetMembershipDatasources(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("MembershipDatasources");
        ArrayNode unprocessed = response.putArray("UnprocessedGraphs");
        for (DetectiveService.UnprocessedGraph graph : result.unprocessed()) {
            ObjectNode node = unprocessed.addObject();
            node.put("GraphArn", graph.graphArn());
            node.put("Reason", graph.reason());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/investigations/listInvestigations")
    public Response listInvestigations(@Context HttpHeaders headers, String body) {
        List<Investigation> investigations =
                service.listInvestigations(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("InvestigationDetails");
        for (Investigation investigation : investigations) {
            list.add(toInvestigationSummary(investigation));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/investigations/getInvestigation")
    public Response getInvestigation(@Context HttpHeaders headers, String body) {
        Investigation investigation = service.getInvestigation(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(toInvestigation(investigation)).build();
    }

    @POST
    @Path("/investigations/startInvestigation")
    public Response startInvestigation(@Context HttpHeaders headers, String body) {
        Investigation investigation = service.startInvestigation(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("InvestigationId", investigation.getInvestigationId());
        return Response.ok(response).build();
    }

    @POST
    @Path("/investigations/updateInvestigationState")
    public Response updateInvestigationState(@Context HttpHeaders headers, String body) {
        service.updateInvestigationState(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/investigations/listIndicators")
    public Response listIndicators(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        Graph graph = service.requireGraph(regionResolver.resolveRegion(headers), text(request, "GraphArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("GraphArn", graph.getArn());
        if (request.hasNonNull("InvestigationId")) {
            response.put("InvestigationId", request.get("InvestigationId").asText());
        }
        response.putArray("Indicators");
        return Response.ok(response).build();
    }

    @POST
    @Path("/invitations/list")
    @Consumes(MediaType.WILDCARD)
    public Response listInvitations(@Context HttpHeaders headers, String body) {
        List<Member> invitations = service.listInvitations(regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Invitations");
        for (Member invitation : invitations) {
            list.add(toMember(invitation));
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/invitation")
    public Response acceptInvitation(@Context HttpHeaders headers, String body) {
        service.acceptInvitation(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/invitation/removal")
    public Response rejectInvitation(@Context HttpHeaders headers, String body) {
        service.rejectInvitation(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/membership/removal")
    public Response disassociateMembership(@Context HttpHeaders headers, String body) {
        service.disassociateMembership(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/orgs/describeOrganizationConfiguration")
    public Response describeOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        boolean autoEnable = service.describeOrganizationConfiguration(
                regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AutoEnable", autoEnable);
        return Response.ok(response).build();
    }

    @POST
    @Path("/orgs/updateOrganizationConfiguration")
    public Response updateOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        service.updateOrganizationConfiguration(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/orgs/adminAccountslist")
    @Consumes(MediaType.WILDCARD)
    public Response listOrganizationAdminAccounts(@Context HttpHeaders headers, String body) {
        List<Administrator> administrators =
                service.listOrganizationAdminAccounts(regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Administrators");
        for (Administrator administrator : administrators) {
            ObjectNode node = list.addObject();
            if (administrator.getAccountId() != null) {
                node.put("AccountId", administrator.getAccountId());
            }
            if (administrator.getGraphArn() != null) {
                node.put("GraphArn", administrator.getGraphArn());
            }
            if (administrator.getDelegationTime() != null) {
                node.put("DelegationTime", administrator.getDelegationTime());
            }
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/orgs/enableAdminAccount")
    public Response enableOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        service.enableOrganizationAdminAccount(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/orgs/disableAdminAccount")
    @Consumes(MediaType.WILDCARD)
    public Response disableOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        service.disableOrganizationAdminAccount(regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode toMember(Member member) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "AccountId", member.getAccountId());
        put(node, "EmailAddress", member.getEmailAddress());
        put(node, "GraphArn", member.getGraphArn());
        put(node, "AdministratorId", member.getAdministratorId());
        put(node, "MasterId", member.getAdministratorId());
        put(node, "Status", member.getStatus());
        put(node, "InvitationType", member.getInvitationType());
        put(node, "InvitedTime", member.getInvitedTime());
        put(node, "UpdatedTime", member.getUpdatedTime());
        put(node, "DisabledReason", member.getDisabledReason());
        return node;
    }

    private ObjectNode toInvestigationSummary(Investigation investigation) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "InvestigationId", investigation.getInvestigationId());
        put(node, "Severity", investigation.getSeverity());
        put(node, "Status", investigation.getStatus());
        put(node, "State", investigation.getState());
        put(node, "CreatedTime", investigation.getCreatedTime());
        put(node, "EntityArn", investigation.getEntityArn());
        put(node, "EntityType", investigation.getEntityType());
        return node;
    }

    private ObjectNode toInvestigation(Investigation investigation) {
        ObjectNode node = toInvestigationSummary(investigation);
        put(node, "GraphArn", investigation.getGraphArn());
        put(node, "ScopeStartTime", investigation.getScopeStartTime());
        put(node, "ScopeEndTime", investigation.getScopeEndTime());
        return node;
    }

    private void putUnprocessed(ObjectNode response, List<DetectiveService.Unprocessed> unprocessed) {
        ArrayNode array = response.putArray("UnprocessedAccounts");
        for (DetectiveService.Unprocessed item : unprocessed) {
            ObjectNode node = array.addObject();
            node.put("AccountId", item.accountId());
            node.put("Reason", item.reason());
        }
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asText();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }
}
