package io.github.hectorvent.floci.services.macie2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.macie2.model.ClassificationJob;
import io.github.hectorvent.floci.services.macie2.model.MacieAllowList;
import io.github.hectorvent.floci.services.macie2.model.MacieCustomDataIdentifier;
import io.github.hectorvent.floci.services.macie2.model.MacieFinding;
import io.github.hectorvent.floci.services.macie2.model.MacieFindingsFilter;
import io.github.hectorvent.floci.services.macie2.model.MacieSession;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Amazon Macie2 restJson1.
 *
 * <p>Literal {@code /macie}, {@code /allow-lists}, {@code /custom-data-identifiers}
 * and {@code /findingsfilters} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} catch-all. Tag APIs share {@code /tags/{arn}} and are
 * dispatched by {@code SharedTagsController}.
 */
@Path(Macie2RoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class Macie2Controller {

    private final Macie2Service service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public Macie2Controller(
            Macie2Service service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/macie")
    @Consumes(MediaType.WILDCARD)
    public Response getMacieSession(@Context HttpHeaders headers) {
        return Response.ok(toSession(service.getMacieSession(region(headers)))).build();
    }

    @POST
    @Path("/macie")
    @Consumes(MediaType.WILDCARD)
    public Response enableMacie(@Context HttpHeaders headers, String body) {
        service.enableMacie(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PATCH
    @Path("/macie")
    @Consumes(MediaType.WILDCARD)
    public Response updateMacieSession(@Context HttpHeaders headers, String body) {
        service.updateMacieSession(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/macie")
    @Consumes(MediaType.WILDCARD)
    public Response disableMacie(@Context HttpHeaders headers) {
        service.disableMacie(region(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/jobs")
    public Response createClassificationJob(@Context HttpHeaders headers, String body) {
        ClassificationJob job = service.createClassificationJob(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobId", job.getJobId());
        response.put("jobArn", job.getJobArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/jobs/{jobId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeClassificationJob(@PathParam("jobId") String jobId) {
        return Response.ok(toJob(service.describeClassificationJob(jobId))).build();
    }

    @PATCH
    @Path("/jobs/{jobId}")
    public Response updateClassificationJob(
            @Context HttpHeaders headers, @PathParam("jobId") String jobId, String body) {
        service.updateClassificationJob(region(headers), jobId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/allow-lists")
    @Consumes(MediaType.WILDCARD)
    public Response listAllowLists(@Context HttpHeaders headers) {
        List<MacieAllowList> lists = service.listAllowLists(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("allowLists");
        for (MacieAllowList allowList : lists) {
            items.add(toAllowListSummary(allowList));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/allow-lists")
    @Consumes(MediaType.WILDCARD)
    public Response createAllowList(@Context HttpHeaders headers, String body) {
        MacieAllowList allowList = service.createAllowList(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", allowList.getArn());
        response.put("id", allowList.getId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/allow-lists/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getAllowList(@Context HttpHeaders headers, @PathParam("id") String id) {
        return Response.ok(toAllowList(service.getAllowList(region(headers), id))).build();
    }

    @PUT
    @Path("/allow-lists/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response updateAllowList(@Context HttpHeaders headers, @PathParam("id") String id, String body) {
        MacieAllowList allowList = service.updateAllowList(region(headers), id, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", allowList.getArn());
        response.put("id", allowList.getId());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/allow-lists/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAllowList(@Context HttpHeaders headers, @PathParam("id") String id) {
        service.deleteAllowList(region(headers), id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/custom-data-identifiers")
    @Consumes(MediaType.WILDCARD)
    public Response createCustomDataIdentifier(@Context HttpHeaders headers, String body) {
        MacieCustomDataIdentifier identifier =
                service.createCustomDataIdentifier(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("customDataIdentifierId", identifier.getId());
        return Response.ok(response).build();
    }

    @POST
    @Path("/custom-data-identifiers/list")
    @Consumes(MediaType.WILDCARD)
    public Response listCustomDataIdentifiers(@Context HttpHeaders headers, String body) {
        parse(body);
        List<MacieCustomDataIdentifier> identifiers = service.listCustomDataIdentifiers(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (MacieCustomDataIdentifier identifier : identifiers) {
            items.add(toIdentifierSummary(identifier));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/custom-data-identifiers/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getCustomDataIdentifier(@Context HttpHeaders headers, @PathParam("id") String id) {
        return Response.ok(toIdentifier(service.getCustomDataIdentifier(region(headers), id))).build();
    }

    @DELETE
    @Path("/custom-data-identifiers/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteCustomDataIdentifier(@Context HttpHeaders headers, @PathParam("id") String id) {
        service.deleteCustomDataIdentifier(region(headers), id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/findingsfilters")
    @Consumes(MediaType.WILDCARD)
    public Response listFindingsFilters(@Context HttpHeaders headers) {
        List<MacieFindingsFilter> filters = service.listFindingsFilters(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("findingsFilterListItems");
        for (MacieFindingsFilter filter : filters) {
            items.add(toFilterSummary(filter));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/findingsfilters")
    @Consumes(MediaType.WILDCARD)
    public Response createFindingsFilter(@Context HttpHeaders headers, String body) {
        MacieFindingsFilter filter = service.createFindingsFilter(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", filter.getArn());
        response.put("id", filter.getId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/findingsfilters/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getFindingsFilter(@Context HttpHeaders headers, @PathParam("id") String id) {
        return Response.ok(toFilter(service.getFindingsFilter(region(headers), id))).build();
    }

    @PATCH
    @Path("/findingsfilters/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response updateFindingsFilter(
            @Context HttpHeaders headers, @PathParam("id") String id, String body) {
        MacieFindingsFilter filter = service.updateFindingsFilter(region(headers), id, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", filter.getArn());
        response.put("id", filter.getId());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/findingsfilters/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteFindingsFilter(@Context HttpHeaders headers, @PathParam("id") String id) {
        service.deleteFindingsFilter(region(headers), id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/findings/sample")
    @Consumes(MediaType.WILDCARD)
    public Response createSampleFindings(@Context HttpHeaders headers, String body) {
        service.createSampleFindings(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/findings")
    @Consumes(MediaType.WILDCARD)
    public Response listFindings(@Context HttpHeaders headers, String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode findingIds = response.putArray("findingIds");
        service.listFindingIds(region(headers)).forEach(findingIds::add);
        return Response.ok(response).build();
    }

    @POST
    @Path("/findings/describe")
    @Consumes(MediaType.WILDCARD)
    public Response getFindings(@Context HttpHeaders headers, String body) {
        List<MacieFinding> findings = service.getFindings(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("findings");
        for (MacieFinding finding : findings) {
            list.add(toFinding(finding));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/findings/statistics")
    @Consumes(MediaType.WILDCARD)
    public Response getFindingStatistics(@Context HttpHeaders headers, String body) {
        Map<String, Long> counts = service.findingStatistics(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode groups = response.putArray("countsByGroup");
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            ObjectNode group = groups.addObject();
            group.put("groupKey", entry.getKey());
            group.put("count", entry.getValue());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/datasources/s3/statistics")
    @Consumes(MediaType.WILDCARD)
    public Response getBucketStatistics(@Context HttpHeaders headers, String body) {
        parse(body);
        service.requireEnabledSession(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("bucketCount", 0);
        return Response.ok(response).build();
    }

    @POST
    @Path("/datasources/search-resources")
    @Consumes(MediaType.WILDCARD)
    public Response searchResources(@Context HttpHeaders headers, String body) {
        parse(body);
        service.requireEnabledSession(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("matchingResources");
        return Response.ok(response).build();
    }

    @POST
    @Path("/managed-data-identifiers/list")
    @Consumes(MediaType.WILDCARD)
    public Response listManagedDataIdentifiers(@Context HttpHeaders headers, String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (Macie2Service.ManagedIdentifier identifier : service.listManagedDataIdentifiers(region(headers))) {
            ObjectNode item = items.addObject();
            item.put("id", identifier.id());
            item.put("category", identifier.category());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/custom-data-identifiers/test")
    @Consumes(MediaType.WILDCARD)
    public Response testCustomDataIdentifier(@Context HttpHeaders headers, String body) {
        int matchCount = service.testCustomDataIdentifier(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("matchCount", matchCount);
        return Response.ok(response).build();
    }

    @POST
    @Path("/jobs/list")
    @Consumes(MediaType.WILDCARD)
    public Response listClassificationJobs(@Context HttpHeaders headers, String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (ClassificationJob job : service.listClassificationJobs(region(headers))) {
            items.add(toJob(job));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/classification-export-configuration")
    @Consumes(MediaType.WILDCARD)
    public Response getClassificationExportConfiguration(@Context HttpHeaders headers) {
        service.requireEnabledSession(region(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/usage")
    @Consumes(MediaType.WILDCARD)
    public Response getUsageTotals(@Context HttpHeaders headers) {
        service.requireEnabledSession(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("usageTotals");
        return Response.ok(response).build();
    }

    @GET
    @Path("/automated-discovery/configuration")
    @Consumes(MediaType.WILDCARD)
    public Response getAutomatedDiscoveryConfiguration(@Context HttpHeaders headers) {
        MacieSession session = service.requireEnabledSession(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", session.getAutomatedDiscoveryStatus());
        return Response.ok(response).build();
    }

    @GET
    @Path("/classification-scopes")
    @Consumes(MediaType.WILDCARD)
    public Response listClassificationScopes(@Context HttpHeaders headers) {
        service.requireEnabledSession(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("classificationScopes");
        return Response.ok(response).build();
    }

    @GET
    @Path("/reveal-configuration")
    @Consumes(MediaType.WILDCARD)
    public Response getRevealConfiguration(@Context HttpHeaders headers) {
        MacieSession session = service.requireEnabledSession(region(headers));
        if (!session.isRevealConfigured()) {
            throw Macie2Service.accessDenied(
                    "Amazon Macie isn't configured to retrieve occurrences of sensitive data.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("configuration").put("status", "DISABLED");
        return Response.ok(response).build();
    }

    @GET
    @Path("/administrator")
    @Consumes(MediaType.WILDCARD)
    public Response getAdministratorAccount(@Context HttpHeaders headers) {
        service.requireEnabledSession(region(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/invitations/count")
    @Consumes(MediaType.WILDCARD)
    public Response getInvitationsCount(@Context HttpHeaders headers) {
        service.requireEnabledSession(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("invitationsCount", 0);
        return Response.ok(response).build();
    }

    @GET
    @Path("/invitations")
    @Consumes(MediaType.WILDCARD)
    public Response listInvitations(@Context HttpHeaders headers) {
        service.requireEnabledSession(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("invitations");
        return Response.ok(response).build();
    }

    @GET
    @Path("/members")
    @Consumes(MediaType.WILDCARD)
    public Response listMembers(@Context HttpHeaders headers) {
        service.requireEnabledSession(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("members");
        return Response.ok(response).build();
    }

    @GET
    @Path("/admin")
    @Consumes(MediaType.WILDCARD)
    public Response listOrganizationAdminAccounts(@Context HttpHeaders headers) {
        service.requireEnabledSession(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("adminAccounts");
        return Response.ok(response).build();
    }

    @GET
    @Path("/admin/configuration")
    @Consumes(MediaType.WILDCARD)
    public Response describeOrganizationConfiguration(@Context HttpHeaders headers) {
        service.requireEnabledSession(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("autoEnable", false);
        response.put("maxAccountLimitReached", false);
        return Response.ok(response).build();
    }

    private ObjectNode toFinding(MacieFinding finding) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", finding.getId());
        node.put("type", finding.getType());
        node.put("sample", finding.isSample());
        node.put("accountId", finding.getAccountId());
        node.put("region", finding.getRegion());
        node.put("archived", finding.isArchived());
        node.put("category", finding.getCategory());
        node.put("schemaVersion", "2017-10-01");
        node.put("title", finding.getTitle());
        node.put("description", finding.getDescription());
        node.put("createdAt", finding.getCreatedAt());
        node.put("updatedAt", finding.getUpdatedAt());
        ObjectNode severity = node.putObject("severity");
        severity.put("description", finding.getSeverityDescription());
        severity.put("score", finding.getSeverityScore());
        return node;
    }

    private ObjectNode toSession(MacieSession session) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "createdAt", session.getCreatedAt());
        put(node, "findingPublishingFrequency", session.getFindingPublishingFrequency());
        put(node, "serviceRole", session.getServiceRole());
        put(node, "status", session.getStatus());
        put(node, "updatedAt", session.getUpdatedAt());
        return node;
    }

    private ObjectNode toJob(ClassificationJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "createdAt", job.getCreatedAt());
        put(node, "description", job.getDescription());
        if (job.getInitialRun() != null) {
            node.put("initialRun", job.getInitialRun());
        }
        put(node, "jobArn", job.getJobArn());
        put(node, "jobId", job.getJobId());
        put(node, "jobStatus", job.getJobStatus());
        put(node, "jobType", job.getJobType());
        put(node, "managedDataIdentifierSelector", job.getManagedDataIdentifierSelector());
        put(node, "name", job.getName());
        if (job.getSamplingPercentage() != null) {
            node.put("samplingPercentage", job.getSamplingPercentage());
        }
        if (!job.getS3JobDefinition().isEmpty()) {
            node.set("s3JobDefinition", objectMapper.valueToTree(job.getS3JobDefinition()));
        }
        putTags(node, job.getTags());
        return node;
    }

    private ObjectNode toAllowList(MacieAllowList allowList) {
        ObjectNode node = toAllowListSummary(allowList);
        node.set("criteria", objectMapper.valueToTree(allowList.getCriteria()));
        ObjectNode status = node.putObject("status");
        status.put("code", allowList.getStatusCode() == null ? "OK" : allowList.getStatusCode());
        putTags(node, allowList.getTags());
        return node;
    }

    private ObjectNode toAllowListSummary(MacieAllowList allowList) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "arn", allowList.getArn());
        put(node, "createdAt", allowList.getCreatedAt());
        put(node, "description", allowList.getDescription());
        put(node, "id", allowList.getId());
        put(node, "name", allowList.getName());
        put(node, "updatedAt", allowList.getUpdatedAt());
        return node;
    }

    private ObjectNode toIdentifier(MacieCustomDataIdentifier identifier) {
        ObjectNode node = toIdentifierSummary(identifier);
        node.put("deleted", identifier.isDeleted());
        put(node, "regex", identifier.getRegex());
        if (identifier.getMaximumMatchDistance() != null) {
            node.put("maximumMatchDistance", identifier.getMaximumMatchDistance());
        }
        if (!identifier.getKeywords().isEmpty()) {
            ArrayNode keywords = node.putArray("keywords");
            identifier.getKeywords().forEach(keywords::add);
        }
        if (!identifier.getIgnoreWords().isEmpty()) {
            ArrayNode ignoreWords = node.putArray("ignoreWords");
            identifier.getIgnoreWords().forEach(ignoreWords::add);
        }
        if (!identifier.getSeverityLevels().isEmpty()) {
            node.set("severityLevels", objectMapper.valueToTree(identifier.getSeverityLevels()));
        }
        putTags(node, identifier.getTags());
        return node;
    }

    private ObjectNode toIdentifierSummary(MacieCustomDataIdentifier identifier) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "arn", identifier.getArn());
        put(node, "createdAt", identifier.getCreatedAt());
        put(node, "description", identifier.getDescription());
        put(node, "id", identifier.getId());
        put(node, "name", identifier.getName());
        return node;
    }

    private ObjectNode toFilter(MacieFindingsFilter filter) {
        ObjectNode node = toFilterSummary(filter);
        put(node, "description", filter.getDescription());
        if (filter.getPosition() != null) {
            node.put("position", filter.getPosition());
        }
        node.set("findingCriteria", objectMapper.valueToTree(filter.getFindingCriteria()));
        return node;
    }

    private ObjectNode toFilterSummary(MacieFindingsFilter filter) {
        ObjectNode node = objectMapper.createObjectNode();
        put(node, "action", filter.getAction());
        put(node, "arn", filter.getArn());
        put(node, "id", filter.getId());
        put(node, "name", filter.getName());
        putTags(node, filter.getTags());
        return node;
    }

    private void putTags(ObjectNode node, Map<String, String> tags) {
        ObjectNode tagsNode = node.putObject("tags");
        if (tags != null) {
            tags.forEach(tagsNode::put);
        }
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
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
