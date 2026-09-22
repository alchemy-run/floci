package io.github.hectorvent.floci.services.detective;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.detective.model.DetectiveMember;
import io.github.hectorvent.floci.services.detective.model.DetectiveState;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DetectiveController {
    private final DetectiveService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public DetectiveController(DetectiveService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/orgs/adminAccountslist")
    public Response listOrganizationAdminAccounts(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        validatePageRequest(request);
        DetectiveState state = service.state(region(headers));
        var response = objectMapper.createObjectNode();
        var administrators = response.putArray("Administrators");
        if (state.getAdminAccountId() != null) {
            administrators.addObject().put("AccountId", state.getAdminAccountId());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/orgs/enableAdminAccount")
    public Response enableOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        service.enableAdmin(region(headers), regionResolver.getAccountId(),
                parse(body).path("AccountId").asText(null));
        return Response.ok().build();
    }

    @POST
    @Path("/graph")
    public Response createGraph(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode value = request.get("Tags");
        if (value != null && !value.isNull()) {
            if (!value.isObject()) {
                throw new AwsException("ValidationException", "Tags must be an object.", 400);
            }
            value.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw new AwsException("ValidationException", "Tag values must be strings.", 400);
                }
                tags.put(entry.getKey(), entry.getValue().textValue());
            });
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("GraphArn", service.createGraph(region(headers), tags));
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/removal")
    public Response deleteGraph(@Context HttpHeaders headers, String body) {
        service.deleteGraph(region(headers), parse(body).path("GraphArn").asText(null));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/graphs/list")
    public Response listGraphs(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = parse(body);
        validatePageRequest(request);
        if (request.hasNonNull("NextToken")) {
            throw new AwsException("ValidationException", "NextToken is invalid.", 400);
        }
        DetectiveState state = service.state(region);
        ObjectNode response = objectMapper.createObjectNode();
        if (state.isGraph()) {
            ObjectNode graph = response.putArray("GraphList").addObject().put("Arn", state.getGraphArn());
            if (state.getCreatedTime() != null) {
                graph.put("CreatedTime", state.getCreatedTime());
            }
        } else {
            response.putArray("GraphList");
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/orgs/describeOrganizationConfiguration")
    public Response describeOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = parse(body);
        service.requireGraphArn(region, request.path("GraphArn").asText(null));
        DetectiveState state = service.requireGraph(region);
        var response = objectMapper.createObjectNode();
        response.put("AutoEnable", state.isAutoEnable());
        return Response.ok(response).build();
    }

    @POST
    @Path("/orgs/updateOrganizationConfiguration")
    public Response updateOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        Boolean autoEnable = null;
        if (request.has("AutoEnable") && !request.get("AutoEnable").isNull()) {
            if (!request.get("AutoEnable").isBoolean()) {
                throw new AwsException("ValidationException", "AutoEnable must be a boolean.", 400);
            }
            autoEnable = request.get("AutoEnable").booleanValue();
        }
        service.updateOrganizationConfiguration(region(headers), request.path("GraphArn").asText(null), autoEnable);
        return Response.ok().build();
    }

    @POST
    @Path("/graph/members/list")
    public Response listMembers(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = parse(body);
        List<DetectiveMember> all = service.listMembers(region, request.path("GraphArn").asText(null));
        Integer maxResults = integer(request, "MaxResults");
        int limit = maxResults == null ? 200 : maxResults;
        if (limit < 1 || limit > 200) {
            throw new AwsException("ValidationException", "MaxResults must be between 1 and 200.", 400);
        }
        int offset = offset(request.path("NextToken").asText(null), all.size());
        int end = Math.min(all.size(), offset + limit);
        var response = objectMapper.createObjectNode();
        var members = response.putArray("MemberDetails");
        for (DetectiveMember member : all.subList(offset, end)) {
            members.add(memberNode(region, member));
        }
        if (end < all.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/members/get")
    public Response getMembers(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = parse(body);
        List<String> accounts = accountIds(request);
        Map<String, DetectiveMember> found = service.getMembers(region, request.path("GraphArn").asText(null), accounts);
        ObjectNode response = objectMapper.createObjectNode();
        var members = response.putArray("MemberDetails");
        var unprocessed = response.putArray("UnprocessedAccounts");
        for (String account : accounts) {
            DetectiveMember member = found.get(account);
            if (member == null) {
                unprocessed.addObject().put("AccountId", account).put("Reason", "Member account not found.");
            } else {
                members.add(memberNode(region, member));
            }
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/invitations/list")
    public Response listInvitations(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        validatePageRequest(request);
        var all = service.listInvitations(region(headers));
        Integer limit = integer(request, "MaxResults");
        int start = offset(request.path("NextToken").asText(null), all.size());
        int end = Math.min(all.size(), start + (limit == null ? 200 : limit));
        ObjectNode response = objectMapper.createObjectNode();
        var invitations = response.putArray("Invitations");
        for (var invitation : all.subList(start, end)) {
            invitations.add(memberNode(invitation.graphArn(), invitation.administratorId(), invitation.member()));
        }
        if (end < all.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/investigations/listInvestigations")
    public Response listInvestigations(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        service.requireGraphArn(region(headers), request.path("GraphArn").asText(null));
        validatePageRequest(request);
        if (request.hasNonNull("NextToken")) {
            throw new AwsException("ValidationException", "NextToken is invalid.", 400);
        }
        if (request.hasNonNull("FilterCriteria") || request.hasNonNull("SortCriteria")) {
            throw DetectiveService.unsupported("investigation filtering and sorting");
        }
        ObjectNode response = objectMapper.createObjectNode();
        // Local event collection does not produce investigation analyses.
        response.putArray("InvestigationDetails");
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/datasources/list")
    public Response listDatasourcePackages(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        service.requireGraphArn(region(headers), request.path("GraphArn").asText(null));
        validatePageRequest(request);
        if (request.hasNonNull("NextToken")) {
            throw new AwsException("ValidationException", "NextToken is invalid.", 400);
        }
        return Response.ok(service.listDatasourcePackages(region(headers), request.path("GraphArn").asText(null)))
                .build();
    }

    @POST
    @Path("/graph/datasources/update")
    public Response updateDatasourcePackages(@Context HttpHeaders headers, String body) {
        service.requireGraphArn(region(headers), parse(body).path("GraphArn").asText(null));
        throw DetectiveService.unsupported("optional datasource ingestion and configuration");
    }

    @POST
    @Path("/investigations/startInvestigation")
    public Response startInvestigation(@Context HttpHeaders headers, String body) {
        service.requireGraphArn(region(headers), parse(body).path("GraphArn").asText(null));
        throw DetectiveService.unsupported("investigation analysis");
    }

    @POST
    @Path("/investigations/getInvestigation")
    public Response getInvestigation(@Context HttpHeaders headers, String body) {
        return missingInvestigation(headers, body);
    }

    @POST
    @Path("/investigations/listIndicators")
    public Response listIndicators(@Context HttpHeaders headers, String body) {
        return missingInvestigation(headers, body);
    }

    @POST
    @Path("/investigations/updateInvestigationState")
    public Response updateInvestigationState(@Context HttpHeaders headers, String body) {
        return missingInvestigation(headers, body);
    }

    private Response missingInvestigation(HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        service.requireGraphArn(region(headers), request.path("GraphArn").asText(null));
        if (!request.path("InvestigationId").isTextual() || request.path("InvestigationId").asText().isBlank()) {
            throw new AwsException("ValidationException", "InvestigationId is required.", 400);
        }
        throw new AwsException("ResourceNotFoundException", "Investigation not found.", 404);
    }

    private static List<String> accountIds(JsonNode request) {
        JsonNode accounts = request.get("AccountIds");
        if (accounts == null || !accounts.isArray() || accounts.isEmpty() || accounts.size() > 50) {
            throw new AwsException("ValidationException", "AccountIds must contain between 1 and 50 accounts.", 400);
        }
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode account : accounts) {
            if (!account.isTextual()) {
                throw new AwsException("ValidationException", "AccountIds must contain strings.", 400);
            }
            result.add(account.textValue());
        }
        return result;
    }

    @POST
    @Path("/graph/members")
    public Response createMembers(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = parse(body);
        String graphArn = request.path("GraphArn").asText(null);
        JsonNode accounts = request.get("Accounts");
        if (accounts == null || !accounts.isArray() || accounts.isEmpty() || accounts.size() > 50) {
            throw new AwsException("ValidationException", "Accounts must contain between 1 and 50 members.", 400);
        }
        List<String> accountIds = new java.util.ArrayList<>(accounts.size());
        for (JsonNode account : accounts) {
            accountIds.add(account.path("AccountId").asText(null));
        }
        service.validateCreateMembers(region, graphArn, accountIds);

        var response = objectMapper.createObjectNode();
        var members = response.putArray("Members");
        var unprocessed = response.putArray("UnprocessedAccounts");
        for (JsonNode account : accounts) {
            String accountId = account.path("AccountId").asText(null);
            try {
                DetectiveMember member = service.createMember(region, graphArn,
                        accountId, account.path("EmailAddress").asText(null));
                members.add(memberNode(region, member));
            } catch (AwsException e) {
                if (!"ConflictException".equals(e.getErrorCode())) {
                    throw e;
                }
                unprocessed.addObject()
                        .put("AccountId", accountId)
                        .put("Reason", "The account is already a member of the behavior graph.");
            }
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/member/monitoringstate")
    public Response startMonitoringMember(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        service.startMonitoring(region(headers), request.path("AccountId").asText(null),
                request.path("GraphArn").asText(null));
        return Response.ok().build();
    }

    private ObjectNode memberNode(String region, DetectiveMember member) {
        return memberNode(service.graphArn(region), regionResolver.getAccountId(), member);
    }

    private ObjectNode memberNode(String graphArn, String administratorId, DetectiveMember member) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AccountId", member.getAccountId());
        if (member.getEmailAddress() != null) {
            node.put("EmailAddress", member.getEmailAddress());
        }
        node.put("Status", member.getStatus());
        node.put("GraphArn", graphArn);
        node.put("AdministratorId", administratorId);
        node.put("InvitationType", member.getInvitationType());
        if (member.getInvitedTime() != null) {
            node.put("InvitedTime", member.getInvitedTime());
        }
        return node;
    }

    private static Integer integer(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new AwsException("ValidationException", field + " must be an integer.", 400);
        }
        return value.intValue();
    }

    private static int offset(String nextToken, int size) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(nextToken);
            if (value < 0 || value > size) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", "NextToken is invalid.", 400);
        }
    }

    private static void validatePageRequest(JsonNode request) {
        Integer maxResults = integer(request, "MaxResults");
        if (maxResults != null && (maxResults < 1 || maxResults > 200)) {
            throw new AwsException("ValidationException", "MaxResults must be between 1 and 200.", 400);
        }
        JsonNode token = request.get("NextToken");
        if (token != null && !token.isNull()) {
            if (!token.isTextual() || token.textValue().isEmpty() || token.textValue().length() > 1024) {
                throw new AwsException("ValidationException", "NextToken is invalid.", 400);
            }
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode parse(String body) {
        JsonNode request;
        try {
            request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
        if (request == null || !request.isObject()) {
            throw new AwsException("ValidationException", "Request must be a JSON object.", 400);
        }
        return request;
    }
}
