package io.github.hectorvent.floci.services.resourcegroups;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * AWS Resource Groups restJson1. Public AWS paths are rewritten onto
 * {@link ResourceGroupsRoutingFilter#INTERNAL_PREFIX} so they do not collide
 * with Backup/QuickSight {@code /resources} routes or S3 path-style URLs.
 */
@Path(ResourceGroupsRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ResourceGroupsController {

    private final ResourceGroupsService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public ResourceGroupsController(ResourceGroupsService service, ObjectMapper objectMapper,
                                    RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/groups")
    public Response createGroup(@Context HttpHeaders headers, String body) {
        return Response.ok(service.createGroup(region(headers), parse(body))).build();
    }

    @POST
    @Path("/delete-group")
    public Response deleteGroup(@Context HttpHeaders headers, String body) {
        return Response.ok(service.deleteGroup(region(headers), parse(body))).build();
    }

    @POST
    @Path("/get-group")
    public Response getGroup(@Context HttpHeaders headers, String body) {
        return Response.ok(service.getGroup(region(headers), parse(body))).build();
    }

    @POST
    @Path("/update-group")
    public Response updateGroup(@Context HttpHeaders headers, String body) {
        return Response.ok(service.updateGroup(region(headers), parse(body))).build();
    }

    @POST
    @Path("/get-group-query")
    public Response getGroupQuery(@Context HttpHeaders headers, String body) {
        return Response.ok(service.getGroupQuery(region(headers), parse(body))).build();
    }

    @POST
    @Path("/update-group-query")
    public Response updateGroupQuery(@Context HttpHeaders headers, String body) {
        return Response.ok(service.updateGroupQuery(region(headers), parse(body))).build();
    }

    @POST
    @Path("/get-group-configuration")
    public Response getGroupConfiguration(@Context HttpHeaders headers, String body) {
        return Response.ok(service.getGroupConfiguration(region(headers), parse(body))).build();
    }

    @POST
    @Path("/put-group-configuration")
    public Response putGroupConfiguration(@Context HttpHeaders headers, String body) {
        return Response.ok(service.putGroupConfiguration(region(headers), parse(body))).build();
    }

    @POST
    @Path("/groups-list")
    @Consumes(MediaType.WILDCARD)
    public Response listGroups(@Context HttpHeaders headers, String body) {
        return Response.ok(service.listGroups(region(headers), parse(body))).build();
    }

    @GET
    @Path("/resources/{arn: .+}/tags")
    @Consumes(MediaType.WILDCARD)
    public Response getTags(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return Response.ok(service.getTags(region(headers), decodeArn(arn))).build();
    }

    @PUT
    @Path("/resources/{arn: .+}/tags")
    public Response tag(@Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        return Response.ok(service.tag(region(headers), decodeArn(arn), parse(body))).build();
    }

    @PATCH
    @Path("/resources/{arn: .+}/tags")
    public Response untag(@Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        return Response.ok(service.untag(region(headers), decodeArn(arn), parse(body))).build();
    }

    @POST
    @Path("/list-group-resources")
    public Response listGroupResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.listGroupResources(region(headers), parse(body))).build();
    }

    @POST
    @Path("/group-resources")
    public Response groupResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.groupResources(region(headers), parse(body))).build();
    }

    @POST
    @Path("/ungroup-resources")
    public Response ungroupResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.ungroupResources(region(headers), parse(body))).build();
    }

    @POST
    @Path("/list-grouping-statuses")
    public Response listGroupingStatuses(@Context HttpHeaders headers, String body) {
        return Response.ok(service.listGroupingStatuses(region(headers), parse(body))).build();
    }

    @POST
    @Path("/resources/search")
    public Response searchResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.searchResources(region(headers), parse(body))).build();
    }

    @POST
    @Path("/get-account-settings")
    @Consumes(MediaType.WILDCARD)
    public Response getAccountSettings() {
        return Response.ok(service.getAccountSettings()).build();
    }

    @POST
    @Path("/update-account-settings")
    public Response updateAccountSettings(String body) {
        return Response.ok(service.updateAccountSettings(parse(body))).build();
    }

    @POST
    @Path("/list-tag-sync-tasks")
    public Response listTagSyncTasks(String body) {
        return Response.ok(service.listTagSyncTasks()).build();
    }

    @POST
    @Path("/start-tag-sync-task")
    public Response startTagSyncTask(@Context HttpHeaders headers, String body) {
        return Response.ok(service.startTagSyncTask(region(headers), parse(body))).build();
    }

    @POST
    @Path("/get-tag-sync-task")
    public Response getTagSyncTask(String body) {
        return Response.ok(service.getTagSyncTask(parse(body))).build();
    }

    @POST
    @Path("/cancel-tag-sync-task")
    public Response cancelTagSyncTask(String body) {
        return Response.ok(service.cancelTagSyncTask(parse(body))).build();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private static String decodeArn(String arn) {
        if (arn == null) {
            return null;
        }
        String decoded = arn;
        if (decoded.endsWith("/tags")) {
            decoded = decoded.substring(0, decoded.length() - "/tags".length());
        }
        try {
            return URLDecoder.decode(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return decoded;
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
