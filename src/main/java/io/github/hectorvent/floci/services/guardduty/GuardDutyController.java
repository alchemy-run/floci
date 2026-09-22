package io.github.hectorvent.floci.services.guardduty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SigV4CredentialScope;
import io.github.hectorvent.floci.services.guardduty.model.Detector;
import io.github.hectorvent.floci.services.guardduty.model.DetectorFeature;
import io.github.hectorvent.floci.services.guardduty.model.MemberAccount;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationFeature;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * GuardDuty restJson1 detector resources, findings, invitations, and organization configuration.
 *
 * <p>The literal {@code /detector} and {@code /admin} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} and {@code /{bucket}/{key}} template routes, so these routes win with no
 * extra routing wiring. Tag operations ({@code /tags/{arn}}) are served by
 * {@code SharedTagsController} via {@link GuardDutyTagHandler}.
 *
 * <p>GuardDuty reports every client error as {@code BadRequestException} (HTTP 400); the
 * Terraform AWS provider matches specific message texts to detect missing resources, so those
 * messages must not change.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GuardDutyController {

    private final GuardDutyService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public GuardDutyController(
            GuardDutyService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    private String region(HttpHeaders headers) {
        String authorization = headers.getHeaderString("Authorization");
        if (authorization != null && !"guardduty".equals(SigV4CredentialScope.serviceName(authorization).orElse(null))) {
            throw new AwsException("AuthorizationHeaderMalformed", "The credential scope must specify guardduty.", 400);
        }
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("BadRequestException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "Request body is not valid JSON.", 400);
        }
    }

    @POST
    @Path("/detector")
    public Response createDetector(@Context HttpHeaders headers, String body) {
        Detector detector = service.createDetector(
                region(headers), regionResolver.getAccountId(), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("detectorId", detector.getId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector")
    public Response listDetectors(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        GuardDutyService.Page<String> page = service.listDetectorIds(
                region(headers), regionResolver.getAccountId(), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode ids = response.putArray("detectorIds");
        page.items().forEach(ids::add);
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector/{detectorId}")
    public Response getDetector(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        Detector detector = service.getDetector(region(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("createdAt", detector.getCreatedAt());
        if (detector.getFindingPublishingFrequency() != null) {
            response.put("findingPublishingFrequency", detector.getFindingPublishingFrequency());
        }
        response.put("serviceRole", detector.getServiceRole());
        response.put("status", detector.getStatus());
        response.put("updatedAt", detector.getUpdatedAt());
        if (detector.getTags() != null && !detector.getTags().isEmpty()) {
            ObjectNode tags = response.putObject("tags");
            detector.getTags().forEach(tags::put);
        }
        if (detector.getFeatures() != null) {
            ArrayNode features = response.putArray("features");
            for (DetectorFeature feature : detector.getFeatures()) {
                features.add(objectMapper.valueToTree(feature));
            }
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}")
    public Response updateDetector(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.updateDetector(region(headers), detectorId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/detector/{detectorId}")
    public Response deleteDetector(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        service.deleteDetector(region(headers), detectorId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/detector/{detectorId}/filter")
    public Response createFilter(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        return createResource(headers, detectorId, "filter", "name", body);
    }

    @GET
    @Path("/detector/{detectorId}/filter")
    public Response listFilters(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                                @QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return listResources(headers, detectorId, "filter", "filterNames", maxResults, nextToken);
    }

    @GET
    @Path("/detector/{detectorId}/filter/{name}")
    public Response getFilter(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                              @PathParam("name") String name) {
        return Response.ok(service.getResource(region(headers), detectorId, "filter", name)).build();
    }

    @POST
    @Path("/detector/{detectorId}/filter/{name}")
    public Response updateFilter(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                                 @PathParam("name") String name, String body) {
        service.updateResource(region(headers), detectorId, "filter", name, parse(body));
        return Response.ok(objectMapper.createObjectNode().put("name", name)).build();
    }

    @DELETE
    @Path("/detector/{detectorId}/filter/{name}")
    public Response deleteFilter(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                                 @PathParam("name") String name) {
        return deleteResource(headers, detectorId, "filter", name);
    }

    @POST
    @Path("/detector/{detectorId}/ipset")
    public Response createIPSet(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        return createResource(headers, detectorId, "ipset", "ipSetId", body);
    }

    @GET
    @Path("/detector/{detectorId}/ipset")
    public Response listIPSets(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                               @QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return listResources(headers, detectorId, "ipset", "ipSetIds", maxResults, nextToken);
    }

    @GET
    @Path("/detector/{detectorId}/ipset/{id}")
    public Response getIPSet(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                             @PathParam("id") String id) {
        return Response.ok(service.getResource(region(headers), detectorId, "ipset", id)).build();
    }

    @POST
    @Path("/detector/{detectorId}/ipset/{id}")
    public Response updateIPSet(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                                @PathParam("id") String id, String body) {
        service.updateResource(region(headers), detectorId, "ipset", id, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/detector/{detectorId}/ipset/{id}")
    public Response deleteIPSet(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                                @PathParam("id") String id) {
        return deleteResource(headers, detectorId, "ipset", id);
    }

    @POST
    @Path("/detector/{detectorId}/threatintelset")
    public Response createThreatIntelSet(@Context HttpHeaders headers,
                                        @PathParam("detectorId") String detectorId, String body) {
        return createResource(headers, detectorId, "threatintelset", "threatIntelSetId", body);
    }

    @GET
    @Path("/detector/{detectorId}/threatintelset")
    public Response listThreatIntelSets(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                                        @QueryParam("maxResults") String maxResults,
                                        @QueryParam("nextToken") String nextToken) {
        return listResources(headers, detectorId, "threatintelset", "threatIntelSetIds", maxResults, nextToken);
    }

    @GET
    @Path("/detector/{detectorId}/threatintelset/{id}")
    public Response getThreatIntelSet(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                                      @PathParam("id") String id) {
        return Response.ok(service.getResource(
                region(headers), detectorId, "threatintelset", id)).build();
    }

    @POST
    @Path("/detector/{detectorId}/threatintelset/{id}")
    public Response updateThreatIntelSet(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                                         @PathParam("id") String id, String body) {
        service.updateResource(region(headers), detectorId, "threatintelset", id, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/detector/{detectorId}/threatintelset/{id}")
    public Response deleteThreatIntelSet(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                                         @PathParam("id") String id) {
        return deleteResource(headers, detectorId, "threatintelset", id);
    }

    private Response createResource(HttpHeaders headers, String detectorId, String kind, String field, String body) {
        String id = service.createResource(region(headers), detectorId, kind, parse(body));
        return Response.ok(objectMapper.createObjectNode().put(field, id)).build();
    }

    private Response listResources(HttpHeaders headers, String detectorId, String kind, String field,
                                   String maxResults, String nextToken) {
        GuardDutyService.Page<String> page = service.listResources(
                region(headers), detectorId, kind, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode ids = response.putArray(field);
        page.items().forEach(ids::add);
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response deleteResource(HttpHeaders headers, String detectorId, String kind, String id) {
        service.deleteResource(region(headers), detectorId, kind, id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings/create")
    public Response createSampleFindings(@Context HttpHeaders headers,
                                         @PathParam("detectorId") String detectorId, String body) {
        service.createSampleFindings(region(headers), detectorId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings")
    public Response listFindings(@Context HttpHeaders headers,
                                 @PathParam("detectorId") String detectorId, String body) {
        return Response.ok(service.listFindings(region(headers), detectorId, parse(body))).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings/get")
    public Response getFindings(@Context HttpHeaders headers,
                                @PathParam("detectorId") String detectorId, String body) {
        return Response.ok(service.getFindings(region(headers), detectorId, parse(body))).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings/statistics")
    public Response getFindingsStatistics(@Context HttpHeaders headers,
                                          @PathParam("detectorId") String detectorId, String body) {
        return Response.ok(service.getFindingsStatistics(
                region(headers), detectorId, parse(body))).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings/archive")
    public Response archiveFindings(@Context HttpHeaders headers,
                                    @PathParam("detectorId") String detectorId, String body) {
        service.updateFindings(region(headers), detectorId, parse(body), true);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings/unarchive")
    public Response unarchiveFindings(@Context HttpHeaders headers,
                                      @PathParam("detectorId") String detectorId, String body) {
        service.updateFindings(region(headers), detectorId, parse(body), false);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings/feedback")
    public Response updateFindingsFeedback(@Context HttpHeaders headers,
                                           @PathParam("detectorId") String detectorId, String body) {
        service.updateFindings(region(headers), detectorId, parse(body), null);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/detector/{detectorId}/usage/statistics")
    public Response getUsageStatistics(@Context HttpHeaders headers,
                                       @PathParam("detectorId") String detectorId, String body) {
        return Response.ok(service.getUsageStatistics(
                region(headers), detectorId, parse(body))).build();
    }

    @POST
    @Path("/detector/{detectorId}/coverage")
    public Response listCoverage(@Context HttpHeaders headers,
                                 @PathParam("detectorId") String detectorId, String body) {
        return Response.ok(service.listCoverage(region(headers), detectorId, parse(body))).build();
    }

    @POST
    @Path("/detector/{detectorId}/freeTrial/daysRemaining")
    public Response getRemainingFreeTrialDays(@Context HttpHeaders headers,
                                             @PathParam("detectorId") String detectorId, String body) {
        return Response.ok(service.getRemainingFreeTrialDays(
                region(headers), detectorId, parse(body))).build();
    }

    @POST
    @Path("/detector/{detectorId}/member/invite")
    public Response inviteMembers(@Context HttpHeaders headers,
                                  @PathParam("detectorId") String detectorId, String body) {
        return Response.ok(service.inviteMembers(region(headers), detectorId, parse(body))).build();
    }

    @GET
    @Path("/invitation")
    public Response listInvitations(@Context HttpHeaders headers, @QueryParam("maxResults") String maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        GuardDutyService.Page<MemberAccount> page = service.listInvitations(region(headers),
                regionResolver.getAccountId(), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode invitations = response.putArray("invitations");
        for (MemberAccount member : page.items()) {
            ObjectNode invitation = invitations.addObject();
            invitation.put("accountId", member.administratorId());
            invitation.put("invitationId", member.invitationId());
            invitation.put("relationshipStatus", member.relationshipStatus());
            invitation.put("invitedAt", member.invitedAt());
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/invitation/count")
    public Response getInvitationsCount(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("invitationsCount", service.getInvitationsCount(
                region(headers), regionResolver.getAccountId()));
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector/{detectorId}/member")
    public Response listMembers(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                                @QueryParam("maxResults") String maxResults,
                                @QueryParam("nextToken") String nextToken,
                                @QueryParam("onlyAssociated") String onlyAssociated) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode members = response.putArray("members");
        GuardDutyService.Page<MemberAccount> page = service.listMembers(
                region(headers), detectorId, maxResults, nextToken, onlyAssociated);
        for (MemberAccount member : page.items()) {
            ObjectNode node = members.addObject();
            node.put("accountId", member.accountId());
            node.put("administratorId", member.administratorId());
            node.put("masterId", member.administratorId());
            node.put("detectorId", member.detectorId());
            node.put("email", member.email());
            if (member.invitedAt() != null) {
                node.put("invitedAt", member.invitedAt());
            }
            node.put("relationshipStatus", member.relationshipStatus());
            node.put("updatedAt", member.updatedAt());
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }
    @POST
    @Path("/detector/{detectorId}/member")
    public Response createMembers(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.createMembers(region(headers), detectorId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("unprocessedAccounts");
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector/{detectorId}/admin")
    public Response describeOrganizationConfiguration(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        OrganizationConfiguration configuration = service.describeOrganizationConfiguration(
                region(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("autoEnable", Boolean.TRUE.equals(configuration.getAutoEnable()));
        response.put("memberAccountLimitReached", false);
        response.put("autoEnableOrganizationMembers", configuration.getAutoEnableOrganizationMembers());
        ArrayNode features = response.putArray("features");
        if (configuration.getFeatures() != null) {
            for (OrganizationFeature feature : configuration.getFeatures()) {
                features.add(objectMapper.valueToTree(feature));
            }
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/admin")
    public Response updateOrganizationConfiguration(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.updateOrganizationConfiguration(
                region(headers), detectorId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/admin/enable")
    public Response enableOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        service.enableOrganizationAdminAccount(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/admin/disable")
    public Response disableOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        service.disableOrganizationAdminAccount(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }
}
