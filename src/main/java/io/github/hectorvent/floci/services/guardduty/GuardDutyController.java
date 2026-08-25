package io.github.hectorvent.floci.services.guardduty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.guardduty.model.Detector;
import io.github.hectorvent.floci.services.guardduty.model.Filter;
import io.github.hectorvent.floci.services.guardduty.model.Finding;
import io.github.hectorvent.floci.services.guardduty.model.IpSet;
import io.github.hectorvent.floci.services.guardduty.model.ThreatIntelSet;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Amazon GuardDuty restJson1.
 *
 * <p>Literal {@code /detector}, {@code /invitation}, {@code /admin} and
 * {@code /organization/statistics} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} catch-all. Tag APIs share {@code /tags/{arn}} and are
 * dispatched by {@code SharedTagsController}.
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

    @GET
    @Path("/detector")
    @Consumes(MediaType.WILDCARD)
    public Response listDetectors(@Context HttpHeaders headers) {
        List<String> ids = service.listDetectorIds(regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode detectorIds = response.putArray("detectorIds");
        ids.forEach(detectorIds::add);
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector")
    public Response createDetector(@Context HttpHeaders headers, String body) {
        Detector detector = service.createDetector(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("detectorId", detector.getDetectorId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector/{detectorId}")
    @Consumes(MediaType.WILDCARD)
    public Response getDetector(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        Detector detector = service.getDetector(regionResolver.resolveRegion(headers), detectorId);
        return Response.ok(toDetector(detector)).build();
    }

    @POST
    @Path("/detector/{detectorId}")
    public Response updateDetector(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.updateDetector(regionResolver.resolveRegion(headers), detectorId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/detector/{detectorId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDetector(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        service.deleteDetector(regionResolver.resolveRegion(headers), detectorId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/detector/{detectorId}/filter")
    @Consumes(MediaType.WILDCARD)
    public Response listFilters(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        List<String> names = service.listFilterNames(regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode filterNames = response.putArray("filterNames");
        names.forEach(filterNames::add);
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/filter")
    public Response createFilter(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        Filter filter = service.createFilter(regionResolver.resolveRegion(headers), detectorId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("name", filter.getName());
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector/{detectorId}/filter/{filterName}")
    @Consumes(MediaType.WILDCARD)
    public Response getFilter(
            @Context HttpHeaders headers,
            @PathParam("detectorId") String detectorId,
            @PathParam("filterName") String filterName) {
        Filter filter = service.getFilter(regionResolver.resolveRegion(headers), detectorId, filterName);
        return Response.ok(toFilter(filter)).build();
    }

    @POST
    @Path("/detector/{detectorId}/filter/{filterName}")
    public Response updateFilter(
            @Context HttpHeaders headers,
            @PathParam("detectorId") String detectorId,
            @PathParam("filterName") String filterName,
            String body) {
        Filter filter = service.updateFilter(
                regionResolver.resolveRegion(headers), detectorId, filterName, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("name", filter.getName());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/detector/{detectorId}/filter/{filterName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteFilter(
            @Context HttpHeaders headers,
            @PathParam("detectorId") String detectorId,
            @PathParam("filterName") String filterName) {
        service.deleteFilter(regionResolver.resolveRegion(headers), detectorId, filterName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/detector/{detectorId}/ipset")
    @Consumes(MediaType.WILDCARD)
    public Response listIpSets(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        List<String> ids = service.listIpSetIds(regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode ipSetIds = response.putArray("ipSetIds");
        ids.forEach(ipSetIds::add);
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/ipset")
    public Response createIpSet(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        IpSet ipSet = service.createIpSet(regionResolver.resolveRegion(headers), detectorId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ipSetId", ipSet.getIpSetId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector/{detectorId}/ipset/{ipSetId}")
    @Consumes(MediaType.WILDCARD)
    public Response getIpSet(
            @Context HttpHeaders headers,
            @PathParam("detectorId") String detectorId,
            @PathParam("ipSetId") String ipSetId) {
        IpSet ipSet = service.getIpSet(regionResolver.resolveRegion(headers), detectorId, ipSetId);
        return Response.ok(toIpSet(ipSet)).build();
    }

    @POST
    @Path("/detector/{detectorId}/ipset/{ipSetId}")
    public Response updateIpSet(
            @Context HttpHeaders headers,
            @PathParam("detectorId") String detectorId,
            @PathParam("ipSetId") String ipSetId,
            String body) {
        service.updateIpSet(regionResolver.resolveRegion(headers), detectorId, ipSetId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/detector/{detectorId}/ipset/{ipSetId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteIpSet(
            @Context HttpHeaders headers,
            @PathParam("detectorId") String detectorId,
            @PathParam("ipSetId") String ipSetId) {
        service.deleteIpSet(regionResolver.resolveRegion(headers), detectorId, ipSetId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/detector/{detectorId}/threatintelset")
    @Consumes(MediaType.WILDCARD)
    public Response listThreatIntelSets(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        List<String> ids = service.listThreatIntelSetIds(regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode threatIntelSetIds = response.putArray("threatIntelSetIds");
        ids.forEach(threatIntelSetIds::add);
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/threatintelset")
    public Response createThreatIntelSet(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        ThreatIntelSet set = service.createThreatIntelSet(
                regionResolver.resolveRegion(headers), detectorId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("threatIntelSetId", set.getThreatIntelSetId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector/{detectorId}/threatintelset/{threatIntelSetId}")
    @Consumes(MediaType.WILDCARD)
    public Response getThreatIntelSet(
            @Context HttpHeaders headers,
            @PathParam("detectorId") String detectorId,
            @PathParam("threatIntelSetId") String threatIntelSetId) {
        ThreatIntelSet set = service.getThreatIntelSet(
                regionResolver.resolveRegion(headers), detectorId, threatIntelSetId);
        return Response.ok(toThreatIntelSet(set)).build();
    }

    @POST
    @Path("/detector/{detectorId}/threatintelset/{threatIntelSetId}")
    public Response updateThreatIntelSet(
            @Context HttpHeaders headers,
            @PathParam("detectorId") String detectorId,
            @PathParam("threatIntelSetId") String threatIntelSetId,
            String body) {
        service.updateThreatIntelSet(
                regionResolver.resolveRegion(headers), detectorId, threatIntelSetId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/detector/{detectorId}/threatintelset/{threatIntelSetId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteThreatIntelSet(
            @Context HttpHeaders headers,
            @PathParam("detectorId") String detectorId,
            @PathParam("threatIntelSetId") String threatIntelSetId) {
        service.deleteThreatIntelSet(regionResolver.resolveRegion(headers), detectorId, threatIntelSetId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings/create")
    public Response createSampleFindings(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.createSampleFindings(regionResolver.resolveRegion(headers), detectorId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings")
    public Response listFindings(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        List<Finding> findings = service.listFindings(regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode ids = response.putArray("findingIds");
        for (Finding finding : findings) {
            ids.add(finding.getId());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings/get")
    public Response getFindings(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        List<Finding> findings = service.getFindings(regionResolver.resolveRegion(headers), detectorId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("findings");
        for (Finding finding : findings) {
            list.add(toFinding(finding));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings/statistics")
    public Response getFindingsStatistics(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        Map<String, Integer> counts =
                service.findingsCountBySeverity(regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode statistics = response.putObject("findingStatistics");
        ObjectNode bySeverity = statistics.putObject("countBySeverity");
        counts.forEach(bySeverity::put);
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings/archive")
    public Response archiveFindings(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.archiveFindings(regionResolver.resolveRegion(headers), detectorId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/detector/{detectorId}/findings/unarchive")
    public Response unarchiveFindings(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.unarchiveFindings(regionResolver.resolveRegion(headers), detectorId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/detector/{detectorId}/member")
    @Consumes(MediaType.WILDCARD)
    public Response listMembers(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        service.requireDetector(regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("members");
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector/{detectorId}/administrator")
    @Consumes(MediaType.WILDCARD)
    public Response getAdministratorAccount(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        service.requireDetector(regionResolver.resolveRegion(headers), detectorId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/detector/{detectorId}/malware-scan-settings")
    @Consumes(MediaType.WILDCARD)
    public Response getMalwareScanSettings(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        Detector detector = service.requireDetector(regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ebsSnapshotPreservation", detector.getEbsSnapshotPreservation());
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/usage/statistics")
    public Response getUsageStatistics(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.requireDetector(regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("usageStatistics").putArray("sumByDataSource");
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/coverage")
    public Response listCoverage(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.requireDetector(regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("resources");
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/freeTrial/daysRemaining")
    public Response getRemainingFreeTrialDays(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.requireDetector(regionResolver.resolveRegion(headers), detectorId);
        JsonNode request = parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode accounts = response.putArray("accounts");
        JsonNode ids = request.get("accountIds");
        if (ids != null && ids.isArray()) {
            for (JsonNode item : ids) {
                if (item != null && item.isTextual() && !item.asText().isBlank()) {
                    ObjectNode account = accounts.addObject();
                    account.put("accountId", item.asText());
                    ObjectNode feature = account.putArray("features").addObject();
                    feature.put("name", "FLOW_LOGS");
                    feature.put("freeTrialDaysRemaining", 30);
                }
            }
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/investigation/list")
    public Response listInvestigations(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.requireDetector(regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("investigations");
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector/{detectorId}/admin")
    @Consumes(MediaType.WILDCARD)
    public Response describeOrganizationConfiguration(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        Detector detector = service.requireDetector(regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("autoEnable", detector.isAutoEnable());
        response.put("memberAccountLimitReached", false);
        return Response.ok(response).build();
    }

    @GET
    @Path("/invitation/count")
    @Consumes(MediaType.WILDCARD)
    public Response getInvitationsCount(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("invitationsCount", 0);
        return Response.ok(response).build();
    }

    @GET
    @Path("/invitation")
    @Consumes(MediaType.WILDCARD)
    public Response listInvitations(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("invitations");
        return Response.ok(response).build();
    }

    @GET
    @Path("/admin")
    @Consumes(MediaType.WILDCARD)
    public Response listOrganizationAdminAccounts(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("adminAccounts");
        return Response.ok(response).build();
    }

    @GET
    @Path("/organization/statistics")
    @Consumes(MediaType.WILDCARD)
    public Response getOrganizationStatistics(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode details = response.putObject("organizationDetails");
        ObjectNode statistics = details.putObject("organizationStatistics");
        statistics.put("totalAccountsCount", 1);
        statistics.put("memberAccountsCount", 0);
        statistics.put("activeAccountsCount", 0);
        statistics.put("enabledAccountsCount", 1);
        return Response.ok(response).build();
    }

    private ObjectNode toDetector(Detector detector) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "createdAt", detector.getCreatedAt());
        put(node, "findingPublishingFrequency", detector.getFindingPublishingFrequency());
        put(node, "serviceRole", detector.getServiceRole());
        put(node, "status", detector.getStatus());
        put(node, "updatedAt", detector.getUpdatedAt());
        ObjectNode tags = node.putObject("tags");
        if (detector.getTags() != null) {
            detector.getTags().forEach(tags::put);
        }
        return node;
    }

    private ObjectNode toFilter(Filter filter) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "name", filter.getName());
        put(node, "description", filter.getDescription());
        put(node, "action", filter.getAction());
        if (filter.getRank() != null) {
            node.put("rank", filter.getRank());
        }
        if (filter.getFindingCriteria() != null && !filter.getFindingCriteria().isEmpty()) {
            node.set("findingCriteria", objectMapper.valueToTree(filter.getFindingCriteria()));
        } else {
            node.putObject("findingCriteria");
        }
        ObjectNode tags = node.putObject("tags");
        filter.getTags().forEach(tags::put);
        return node;
    }

    private ObjectNode toIpSet(IpSet ipSet) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "name", ipSet.getName());
        put(node, "format", ipSet.getFormat());
        put(node, "location", ipSet.getLocation());
        put(node, "status", ipSet.getStatus());
        put(node, "expectedBucketOwner", ipSet.getExpectedBucketOwner());
        ObjectNode tags = node.putObject("tags");
        ipSet.getTags().forEach(tags::put);
        return node;
    }

    private ObjectNode toThreatIntelSet(ThreatIntelSet set) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "name", set.getName());
        put(node, "format", set.getFormat());
        put(node, "location", set.getLocation());
        put(node, "status", set.getStatus());
        put(node, "expectedBucketOwner", set.getExpectedBucketOwner());
        ObjectNode tags = node.putObject("tags");
        set.getTags().forEach(tags::put);
        return node;
    }

    private ObjectNode toFinding(Finding finding) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "accountId", finding.getAccountId());
        if (finding.getDetectorId() != null && finding.getId() != null) {
            node.put("arn", "arn:aws:guardduty:" + finding.getRegion() + ":" + finding.getAccountId()
                    + ":detector/" + finding.getDetectorId() + "/finding/" + finding.getId());
        }
        put(node, "createdAt", finding.getCreatedAt());
        put(node, "id", finding.getId());
        node.put("partition", "aws");
        put(node, "region", finding.getRegion());
        node.putObject("resource").put("resourceType", "Instance");
        node.put("schemaVersion", "2.0");
        ObjectNode serviceNode = node.putObject("service");
        serviceNode.put("archived", finding.isArchived());
        serviceNode.put("count", 1);
        put(serviceNode, "detectorId", finding.getDetectorId());
        put(serviceNode, "eventFirstSeen", finding.getCreatedAt());
        put(serviceNode, "eventLastSeen", finding.getUpdatedAt());
        serviceNode.put("serviceName", "guardduty");
        node.put("severity", finding.getSeverity());
        put(node, "title", finding.getTitle());
        put(node, "type", finding.getType());
        put(node, "updatedAt", finding.getUpdatedAt());
        return node;
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
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
}
