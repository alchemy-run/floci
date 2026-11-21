package io.floci.aws.services.resourcegroups;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.resourcegroups.model.ResourceGroup;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The AWS Resource Groups REST API, reached through {@link ResourceGroupsRouteFilter}. */
@Path(ResourceGroupsRouteFilter.INTERNAL_PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class ResourceGroupsController {
    private final ResourceGroupsService service;
    private final RegionResolver regions;
    private final RequestContext context;
    private final ObjectMapper mapper;

    @Inject
    public ResourceGroupsController(ResourceGroupsService service, RegionResolver regions,
                                    RequestContext context, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.context = context;
        this.mapper = mapper;
    }

    @POST
    @Path("/groups")
    public Response createGroup(@Context HttpHeaders headers, String body) {
        ResourceGroup group = service.create(regions.resolveRegion(headers), context.getAccountId(), parse(body));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("Group", service.groupMetadata(group));
        if (group.resourceQuery() != null) {
            response.put("ResourceQuery", query(group));
        }
        response.put("Tags", group.tags());
        if (group.hasConfiguration()) {
            response.put("GroupConfiguration", configuration(group));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/get-group")
    public Response getGroup(@Context HttpHeaders headers, String body) {
        return Response.ok(Map.of("Group", service.groupMetadata(
                service.get(regions.resolveRegion(headers), context.getAccountId(), parse(body))))).build();
    }

    @POST
    @Path("/update-group")
    public Response updateGroup(@Context HttpHeaders headers, String body) {
        return Response.ok(Map.of("Group", service.groupMetadata(
                service.update(regions.resolveRegion(headers), context.getAccountId(), parse(body))))).build();
    }

    @POST
    @Path("/delete-group")
    public Response deleteGroup(@Context HttpHeaders headers, String body) {
        return Response.ok(Map.of("Group", service.groupMetadata(
                service.delete(regions.resolveRegion(headers), context.getAccountId(), parse(body))))).build();
    }

    @POST
    @Path("/groups-list")
    public Response listGroups(@Context HttpHeaders headers, @QueryParam("maxResults") Integer maxResults,
                               @QueryParam("nextToken") String nextToken, String body) {
        return Response.ok(service.listGroups(regions.resolveRegion(headers), context.getAccountId(), parse(body),
                maxResults, nextToken)).build();
    }

    @POST
    @Path("/get-group-query")
    public Response getGroupQuery(@Context HttpHeaders headers, String body) {
        return groupQuery(service.getQuery(regions.resolveRegion(headers), context.getAccountId(), parse(body)));
    }

    @POST
    @Path("/update-group-query")
    public Response updateGroupQuery(@Context HttpHeaders headers, String body) {
        return groupQuery(service.updateQuery(regions.resolveRegion(headers), context.getAccountId(), parse(body)));
    }

    @POST
    @Path("/get-group-configuration")
    public Response getGroupConfiguration(@Context HttpHeaders headers, String body) {
        ResourceGroup group = service.getConfiguration(regions.resolveRegion(headers), context.getAccountId(),
                parse(body));
        return Response.ok(Map.of("GroupConfiguration", group.hasConfiguration() ? configuration(group) : Map.of()))
                .build();
    }

    @POST
    @Path("/put-group-configuration")
    public Response putGroupConfiguration(@Context HttpHeaders headers, String body) {
        service.putConfiguration(regions.resolveRegion(headers), context.getAccountId(), parse(body));
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/group-resources")
    public Response groupResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.add(regions.resolveRegion(headers), context.getAccountId(), parse(body))).build();
    }

    @POST
    @Path("/ungroup-resources")
    public Response ungroupResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.remove(regions.resolveRegion(headers), context.getAccountId(), parse(body)))
                .build();
    }

    @POST
    @Path("/list-group-resources")
    public Response listGroupResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.list(regions.resolveRegion(headers), context.getAccountId(), parse(body))).build();
    }

    @POST
    @Path("/resources/search")
    public Response searchResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.search(regions.resolveRegion(headers), context.getAccountId(), parse(body)))
                .build();
    }

    @GET
    @Path("/resources/{arn: .+}/tags")
    public Response getTags(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return Response.ok(Map.of("Arn", arn,
                "Tags", service.getTags(regions.resolveRegion(headers), context.getAccountId(), arn))).build();
    }

    @PUT
    @Path("/resources/{arn: .+}/tags")
    public Response tag(@Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        return Response.ok(Map.of("Arn", arn,
                "Tags", service.tag(regions.resolveRegion(headers), context.getAccountId(), arn, parse(body))))
                .build();
    }

    @PATCH
    @Path("/resources/{arn: .+}/tags")
    public Response untag(@Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        return Response.ok(Map.of("Arn", arn,
                "Keys", service.untag(regions.resolveRegion(headers), context.getAccountId(), arn, parse(body))))
                .build();
    }

    @POST
    @Path("/get-account-settings")
    public Response getAccountSettings() {
        return accountSettings(service.accountSettings(context.getAccountId()));
    }

    @POST
    @Path("/update-account-settings")
    public Response updateAccountSettings(String body) {
        return accountSettings(service.updateAccountSettings(context.getAccountId(), parse(body)));
    }

    @POST
    @Path("/list-grouping-statuses")
    public Response listGroupingStatuses(@Context HttpHeaders headers, String body) {
        service.listGroupingStatuses(regions.resolveRegion(headers), context.getAccountId(), parse(body));
        return Response.ok(Map.of("GroupingStatuses", List.of())).build();
    }

    @POST
    @Path("/start-tag-sync-task")
    public Response startTagSyncTask(@Context HttpHeaders headers, String body) {
        service.startTagSyncTask(regions.resolveRegion(headers), context.getAccountId(), parse(body));
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/get-tag-sync-task")
    public Response getTagSyncTask(String body) {
        service.tagSyncTask("GetTagSyncTask", parse(body));
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/cancel-tag-sync-task")
    public Response cancelTagSyncTask(String body) {
        service.tagSyncTask("CancelTagSyncTask", parse(body));
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/list-tag-sync-tasks")
    public Response listTagSyncTasks(String body) {
        return Response.ok(service.listTagSyncTasks(parse(body))).build();
    }

    private Response groupQuery(ResourceGroup group) {
        return Response.ok(Map.of("GroupQuery", Map.of("GroupName", group.name(), "ResourceQuery", query(group))))
                .build();
    }

    private static Map<String, Object> query(ResourceGroup group) {
        return Map.of("Type", group.resourceQuery().type(), "Query", group.resourceQuery().query());
    }

    private static Map<String, Object> configuration(ResourceGroup group) {
        return Map.of("Configuration", group.configuration(), "Status", "UPDATE_COMPLETE");
    }

    private static Response accountSettings(ResourceGroupsService.AccountSettings settings) {
        return Response.ok(Map.of("AccountSettings", Map.of(
                "GroupLifecycleEventsDesiredStatus", settings.desiredStatus(),
                "GroupLifecycleEventsStatus", settings.status()))).build();
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
            if (node == null || !node.isObject()) {
                throw new AwsException("BadRequestException", "The request must contain a JSON object.", 400);
            }
            return node;
        } catch (JsonProcessingException error) {
            throw new AwsException("BadRequestException", "The request must contain a JSON object.", 400);
        }
    }
}
