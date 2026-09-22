package io.github.hectorvent.floci.services.macie2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.macie2.model.MacieMember;
import io.github.hectorvent.floci.services.macie2.model.MacieState;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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
public class MacieController {
    private final MacieService macieService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MacieController(MacieService macieService, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.macieService = macieService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public Response listAdminInternal(String region) {
        MacieState state = macieService.state(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode accounts = response.putArray("adminAccounts");
        if (state.getAdminAccountId() != null) {
            accounts.addObject().put("accountId", state.getAdminAccountId()).put("status", "ENABLED");
        }
        return Response.ok(response).build();
    }

    @POST @Path("/admin")
    public Response enableOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        macieService.enableOrganizationAdminAccount(
                region(headers), readTree(body).path("adminAccountId").asText(null));
        return empty();
    }

    @GET @Path("/macie")
    public Response getMacieSession(@Context HttpHeaders headers) {
        MacieState state = macieService.requireSession(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", state.getStatus());
        response.put("findingPublishingFrequency", state.getFindingPublishingFrequency());
        if (state.getCreatedAt() != null) {
            response.put("createdAt", state.getCreatedAt());
        }
        if (state.getUpdatedAt() != null) {
            response.put("updatedAt", state.getUpdatedAt());
        }
        response.put("serviceRole", "arn:aws:iam::" + regionResolver.getAccountId()
                + ":role/aws-service-role/macie.amazonaws.com/AWSServiceRoleForAmazonMacie");
        return Response.ok(response).build();
    }

    @POST @Path("/macie")
    public Response enableMacie(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        macieService.enableMacie(region(headers), optionalString(request, "status"),
                optionalString(request, "findingPublishingFrequency"));
        return empty();
    }

    @PATCH @Path("/macie")
    public Response updateMacieSession(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        macieService.updateMacieSession(region(headers), optionalString(request, "status"),
                optionalString(request, "findingPublishingFrequency"));
        return empty();
    }

    @DELETE @Path("/macie")
    public Response disableMacie(@Context HttpHeaders headers) {
        macieService.disableMacie(region(headers), regionResolver.getAccountId());
        return empty();
    }

    @POST @Path("/custom-data-identifiers/test")
    public Response testCustomDataIdentifier(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        Integer distance = null;
        JsonNode value = request.get("maximumMatchDistance");
        if (value != null && !value.isNull()) {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw new AwsException("ValidationException", "maximumMatchDistance must be an integer.", 400);
            }
            distance = value.intValue();
        }
        int count = macieService.testCustomDataIdentifier(region(headers), optionalString(request, "regex"),
                optionalString(request, "sampleText"), stringList(request, "keywords"),
                stringList(request, "ignoreWords"), distance);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("matchCount", count);
        return Response.ok(response).build();
    }

    private static java.util.List<String> stringList(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return java.util.List.of();
        }
        if (!value.isArray()) {
            throw new AwsException("ValidationException", field + " must be an array of strings.", 400);
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new AwsException("ValidationException", field + " must be an array of strings.", 400);
            }
            result.add(item.textValue());
        }
        return result;
    }

    @POST @Path("/members")
    public Response createMember(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        JsonNode account = request.get("account");
        if (account == null || !account.isObject()) {
            throw new AwsException(
                    "ValidationException", "account is required.", 400);
        }
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagsNode = request.get("tags");
        if (tagsNode != null && !tagsNode.isNull()) {
            if (!tagsNode.isObject()) {
                throw new AwsException(
                        "ValidationException", "tags must be an object.", 400);
            }
            tagsNode.fields().forEachRemaining(entry -> tags.put(
                    entry.getKey(), entry.getValue().isTextual() ? entry.getValue().asText() : null));
        }
        MacieMember member = macieService.createMember(
                region(headers),
                regionResolver.getAccountId(),
                account.path("accountId").asText(null),
                account.path("email").asText(null),
                tags);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", member.arn());
        return Response.ok(response).build();
    }

    @GET @Path("/members")
    public Response listMembers(@Context HttpHeaders headers,
                                @QueryParam("maxResults") String maxResults,
                                @QueryParam("nextToken") String nextToken,
                                @QueryParam("onlyAssociated") String onlyAssociated) {
        MacieService.Page<MacieMember> page = macieService.listMembers(
                region(headers), regionResolver.getAccountId(), maxResults, nextToken, onlyAssociated);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("members", objectMapper.valueToTree(page.items()));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET @Path("/administrator")
    public Response getAdministratorAccount(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        macieService.administrator(region(headers), regionResolver.getAccountId())
                .ifPresent(member -> response.set("administrator", invitationNode(member)));
        return Response.ok(response).build();
    }

    @GET @Path("/invitations")
    public Response listInvitations(@Context HttpHeaders headers,
                                    @QueryParam("maxResults") String maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        var page = macieService.listInvitations(region(headers), regionResolver.getAccountId(), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode invitations = response.putArray("invitations");
        page.items().forEach(member -> invitations.add(invitationNode(member)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET @Path("/invitations/count")
    public Response getInvitationsCount(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("invitationsCount", macieService.invitationsCount(region(headers), regionResolver.getAccountId()));
        return Response.ok(response).build();
    }

    private ObjectNode invitationNode(MacieMember member) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("accountId", member.administratorAccountId());
        node.put("relationshipStatus", member.relationshipStatus());
        if (member.invitedAt() != null) {
            node.put("invitedAt", member.invitedAt());
        }
        return node;
    }

    @POST @Path("/findings/sample")
    public Response createSampleFindings(@Context HttpHeaders headers, String body) {
        macieService.createSampleFindings(region(headers), regionResolver.getAccountId(), readTree(body));
        return empty();
    }

    @POST @Path("/findings")
    public Response listFindings(@Context HttpHeaders headers, String body) {
        return Response.ok(macieService.listFindings(region(headers), regionResolver.getAccountId(), readTree(body))).build();
    }

    @POST @Path("/findings/describe")
    public Response getFindings(@Context HttpHeaders headers, String body) {
        return Response.ok(macieService.getFindings(region(headers), regionResolver.getAccountId(), readTree(body))).build();
    }

    @POST @Path("/findings/statistics")
    public Response findingStatistics(@Context HttpHeaders headers, String body) {
        return Response.ok(macieService.findingStatistics(region(headers), regionResolver.getAccountId(), readTree(body))).build();
    }

    @POST @Path("/datasources/s3/statistics")
    public Response getBucketStatistics(@Context HttpHeaders headers, String body) {
        return Response.ok(macieService.bucketStatistics(region(headers), regionResolver.getAccountId(), readTree(body))).build();
    }

    @POST @Path("/datasources/search-resources")
    public Response searchResources(@Context HttpHeaders headers, String body) {
        return Response.ok(macieService.searchResources(region(headers), regionResolver.getAccountId(), readTree(body))).build();
    }

    @POST @Path("/allow-lists")
    public Response createAllowList(@Context HttpHeaders headers, String body) {
        return createResource(headers, "allow-list", body);
    }

    @GET @Path("/allow-lists/{id}")
    public Response getAllowList(@Context HttpHeaders headers, @PathParam("id") String id) {
        return getResource(headers, "allow-list", id);
    }

    @PUT @Path("/allow-lists/{id}")
    public Response updateAllowList(@Context HttpHeaders headers, @PathParam("id") String id, String body) {
        return updateResource(headers, "allow-list", id, body);
    }

    @DELETE @Path("/allow-lists/{id}")
    public Response deleteAllowList(@Context HttpHeaders headers, @PathParam("id") String id,
                                    @QueryParam("ignoreJobChecks") String ignoreJobChecks) {
        if (ignoreJobChecks != null && !List.of("true", "false").contains(ignoreJobChecks)) {
            throw new AwsException("ValidationException", "ignoreJobChecks must be true or false.", 400);
        }
        return deleteResource(headers, "allow-list", id);
    }

    @GET @Path("/allow-lists")
    public Response listAllowLists(@Context HttpHeaders headers, @QueryParam("maxResults") String maxResults,
                                   @QueryParam("nextToken") String nextToken) {
        return listResources(headers, "allow-list", "allowLists", maxResults, nextToken);
    }

    @POST @Path("/custom-data-identifiers")
    public Response createCustomDataIdentifier(@Context HttpHeaders headers, String body) {
        return createResource(headers, "custom-data-identifier", body);
    }

    @GET @Path("/custom-data-identifiers/{id}")
    public Response getCustomDataIdentifier(@Context HttpHeaders headers, @PathParam("id") String id) {
        return getResource(headers, "custom-data-identifier", id);
    }

    @DELETE @Path("/custom-data-identifiers/{id}")
    public Response deleteCustomDataIdentifier(@Context HttpHeaders headers, @PathParam("id") String id) {
        return deleteResource(headers, "custom-data-identifier", id);
    }

    @POST @Path("/custom-data-identifiers/list")
    public Response listCustomDataIdentifiers(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        return listResources(headers, "custom-data-identifier", "items", MacieService.limit(request),
                optionalString(request, "nextToken"));
    }

    @POST @Path("/custom-data-identifiers/get")
    public Response batchGetCustomDataIdentifiers(@Context HttpHeaders headers, String body) {
        return Response.ok(macieService.batchGetIdentifiers(region(headers), regionResolver.getAccountId(), readTree(body))).build();
    }

    @POST @Path("/findingsfilters")
    public Response createFindingsFilter(@Context HttpHeaders headers, String body) {
        return createResource(headers, "findings-filter", body);
    }

    @GET @Path("/findingsfilters/{id}")
    public Response getFindingsFilter(@Context HttpHeaders headers, @PathParam("id") String id) {
        return getResource(headers, "findings-filter", id);
    }

    @PATCH @Path("/findingsfilters/{id}")
    public Response updateFindingsFilter(@Context HttpHeaders headers, @PathParam("id") String id, String body) {
        return updateResource(headers, "findings-filter", id, body);
    }

    @DELETE @Path("/findingsfilters/{id}")
    public Response deleteFindingsFilter(@Context HttpHeaders headers, @PathParam("id") String id) {
        return deleteResource(headers, "findings-filter", id);
    }

    @GET @Path("/findingsfilters")
    public Response listFindingsFilters(@Context HttpHeaders headers, @QueryParam("maxResults") String maxResults,
                                        @QueryParam("nextToken") String nextToken) {
        return listResources(headers, "findings-filter", "findingsFilterListItems", maxResults, nextToken);
    }

    @POST @Path("/managed-data-identifiers/list")
    public Response listManagedDataIdentifiers(@Context HttpHeaders headers, String body) {
        return Response.ok(macieService.listManagedIdentifiers(region(headers), regionResolver.getAccountId(), readTree(body))).build();
    }

    @POST @Path("/jobs/list")
    public Response listClassificationJobs(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        if (request.has("filterCriteria") || request.has("sortCriteria")) {
            return unsupported(headers, "classification job filtering and sorting");
        }
        return listResources(headers, "classification-job", "items", MacieService.limit(request),
                optionalString(request, "nextToken"));
    }

    @POST @Path("/jobs")
    public Response createClassificationJob(@Context HttpHeaders headers, String body) {
        readTree(body);
        return unsupported(headers, "classification execution: S3 reading and evaluation are not implemented");
    }

    @GET @Path("/_macie2/jobs/{id}")
    public Response describeClassificationJob(@Context HttpHeaders headers, @PathParam("id") String id) {
        return getResource(headers, "classification-job", id);
    }

    @PATCH @Path("/jobs/{id}")
    public Response updateClassificationJob(@Context HttpHeaders headers, @PathParam("id") String id, String body) {
        readTree(body);
        macieService.getResource(region(headers), regionResolver.getAccountId(), "classification-job", id);
        return unsupported(headers, "classification execution");
    }

    @GET @Path("/classification-export-configuration")
    public Response getExportConfiguration(@Context HttpHeaders headers) {
        return configuration(headers, "export");
    }

    @PUT @Path("/classification-export-configuration")
    public Response putExportConfiguration(@Context HttpHeaders headers, String body) {
        macieService.putExportConfiguration(region(headers), regionResolver.getAccountId(), readTree(body));
        return configuration(headers, "export");
    }

    @GET @Path("/automated-discovery/configuration")
    public Response getAutomatedDiscoveryConfiguration(@Context HttpHeaders headers) {
        return configuration(headers, "discovery");
    }

    @PATCH @Path("/automated-discovery/configuration")
    public Response updateAutomatedDiscoveryConfiguration(@Context HttpHeaders headers, String body) {
        readTree(body);
        return unsupported(headers, "automated discovery execution");
    }

    @GET @Path("/classification-scopes")
    public Response listClassificationScopes(@Context HttpHeaders headers, @QueryParam("maxResults") String maxResults,
                                             @QueryParam("nextToken") String nextToken) {
        ObjectNode scope = macieService.configuration(region(headers), regionResolver.getAccountId(), "scope");
        scope.retain("id", "name");
        return Response.ok(MacieService.page(List.of(scope), "classificationScopes", maxResults, nextToken)).build();
    }

    @GET @Path("/classification-scopes/{id}")
    public Response getClassificationScope(@Context HttpHeaders headers, @PathParam("id") String id) {
        return Response.ok(macieService.classificationScope(region(headers), regionResolver.getAccountId(), id)).build();
    }

    @GET @Path("/reveal-configuration")
    public Response getRevealConfiguration(@Context HttpHeaders headers) {
        return configuration(headers, "reveal");
    }

    @PUT @Path("/reveal-configuration")
    public Response updateRevealConfiguration(@Context HttpHeaders headers, String body) {
        readTree(body);
        return unsupported(headers, "sensitive data retrieval");
    }

    @GET @Path("/usage")
    public Response getUsageTotals(@Context HttpHeaders headers, @QueryParam("timeRange") String timeRange) {
        if (timeRange != null && !List.of("MONTH_TO_DATE", "PAST_30_DAYS").contains(timeRange)) {
            throw new AwsException("ValidationException", "Invalid timeRange.", 400);
        }
        return Response.ok(macieService.usageTotals(region(headers), regionResolver.getAccountId())).build();
    }

    private Response createResource(HttpHeaders headers, String kind, String body) {
        return Response.ok(macieService.createResource(region(headers), regionResolver.getAccountId(), kind, readTree(body))).build();
    }

    private Response getResource(HttpHeaders headers, String kind, String id) {
        return Response.ok(macieService.getResource(region(headers), regionResolver.getAccountId(), kind, id)).build();
    }

    private Response updateResource(HttpHeaders headers, String kind, String id, String body) {
        return Response.ok(macieService.updateResource(region(headers), regionResolver.getAccountId(), kind, id, readTree(body))).build();
    }

    private Response deleteResource(HttpHeaders headers, String kind, String id) {
        macieService.deleteResource(region(headers), regionResolver.getAccountId(), kind, id);
        return empty();
    }

    private Response listResources(HttpHeaders headers, String kind, String key, String maxResults, String nextToken) {
        return Response.ok(macieService.listResources(region(headers), regionResolver.getAccountId(), kind, key,
                maxResults, nextToken)).build();
    }

    private Response configuration(HttpHeaders headers, String name) {
        return Response.ok(macieService.configuration(region(headers), regionResolver.getAccountId(), name)).build();
    }

    private Response unsupported(HttpHeaders headers, String operation) {
        macieService.requireSession(region(headers));
        throw new AwsException("ValidationException", "Floci does not support Macie " + operation + ".", 400);
    }

    @PATCH @Path("/admin/configuration")
    public Response updateOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        if (!request.has("autoEnable") || !request.get("autoEnable").isBoolean()) {
            throw new AwsException("ValidationException", "autoEnable is required.", 400);
        }
        macieService.updateOrganizationConfiguration(
                region(headers), regionResolver.getAccountId(), request.path("autoEnable").asBoolean());
        return empty();
    }

    @GET @Path("/admin/configuration")
    public Response describeOrganizationConfiguration(@Context HttpHeaders headers) {
        MacieState state = macieService.requireAdministratorSession(region(headers), regionResolver.getAccountId());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("autoEnable", state.isAutoEnable());
        return Response.ok(response).build();
    }

    private static String optionalString(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new AwsException("ValidationException", field + " must be a string.", 400);
        }
        return value.textValue();
    }

    private String region(HttpHeaders headers) { return regionResolver.resolveRegion(headers); }
    private Response empty() { return Response.ok(objectMapper.createObjectNode()).build(); }
    private JsonNode readTree(String body) {
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
