package io.github.hectorvent.floci.services.amp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.amp.model.AlertManagerDefinition;
import io.github.hectorvent.floci.services.amp.model.AmpWorkspace;
import io.github.hectorvent.floci.services.amp.model.RuleGroupsNamespace;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
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
import jakarta.ws.rs.core.UriInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * AMP rule-groups namespaces and Alertmanager definition (restJson1).
 *
 * <p>Kept as a sibling of {@link AmpController} so those more-specific paths do not
 * collide with workspace/logging/policy routes while other agents extend AmpController.
 */
@Path("/workspaces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AmpRuleController {

    private final AmpService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public AmpRuleController(AmpService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Consumes(MediaType.WILDCARD)
    public Response listWorkspaces(
            @Context HttpHeaders headers,
            @QueryParam("alias") String alias,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        List<AmpWorkspace> workspaces = service.listWorkspaces(regionResolver.resolveRegion(headers), alias);
        int offset = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                offset = Math.max(0, Integer.parseInt(nextToken));
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationException", "Invalid nextToken: " + nextToken, 400);
            }
        }
        int limit = maxResults == null || maxResults <= 0 ? workspaces.size() : maxResults;
        int end = Math.min(workspaces.size(), offset + limit);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("workspaces");
        for (int i = offset; i < end; i++) {
            list.add(service.workspaceSummary(workspaces.get(i)));
        }
        if (end < workspaces.size()) {
            response.put("nextToken", Integer.toString(end));
        }
        return Response.ok(response).build();
    }

    @POST
    public Response createWorkspace(@Context HttpHeaders headers, String body) {
        AmpWorkspace workspace = service.createWorkspace(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(service.createWorkspaceResponse(workspace)).build();
    }

    @GET
    @Path("/{workspaceId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeWorkspace(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        AmpWorkspace workspace = service.describeWorkspace(regionResolver.resolveRegion(headers), workspaceId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("workspace", service.workspaceDescription(workspace));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/{workspaceId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteWorkspace(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        service.deleteWorkspace(regionResolver.resolveRegion(headers), workspaceId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/{workspaceId}/alias")
    public Response updateWorkspaceAlias(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        service.updateWorkspaceAlias(regionResolver.resolveRegion(headers), workspaceId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/{workspaceId}/configuration")
    @Consumes(MediaType.WILDCARD)
    public Response describeWorkspaceConfiguration(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        return Response.ok(service.describeWorkspaceConfiguration(
                        regionResolver.resolveRegion(headers), workspaceId))
                .build();
    }

    @PATCH
    @Path("/{workspaceId}/configuration")
    public Response updateWorkspaceConfiguration(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        return Response.ok(service.updateWorkspaceConfiguration(
                        regionResolver.resolveRegion(headers), workspaceId, parse(body)))
                .build();
    }

    @POST
    @Path("/{workspaceId}/logging")
    public Response createLoggingConfiguration(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        return Response.ok(service.createWorkspaceLoggingConfiguration(
                        regionResolver.resolveRegion(headers), workspaceId, parse(body)))
                .build();
    }

    @GET
    @Path("/{workspaceId}/logging")
    @Consumes(MediaType.WILDCARD)
    public Response describeLoggingConfiguration(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        return Response.ok(service.describeWorkspaceLoggingConfiguration(
                        regionResolver.resolveRegion(headers), workspaceId))
                .build();
    }

    @PUT
    @Path("/{workspaceId}/logging")
    public Response updateLoggingConfiguration(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        return Response.ok(service.updateWorkspaceLoggingConfiguration(
                        regionResolver.resolveRegion(headers), workspaceId, parse(body)))
                .build();
    }

    @DELETE
    @Path("/{workspaceId}/logging")
    @Consumes(MediaType.WILDCARD)
    public Response deleteLoggingConfiguration(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        service.deleteWorkspaceLoggingConfiguration(regionResolver.resolveRegion(headers), workspaceId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/{workspaceId}/logging/query")
    public Response createQueryLoggingConfiguration(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        return Response.ok(service.createQueryLoggingConfiguration(
                        regionResolver.resolveRegion(headers), workspaceId, parse(body)))
                .build();
    }

    @GET
    @Path("/{workspaceId}/logging/query")
    @Consumes(MediaType.WILDCARD)
    public Response describeQueryLoggingConfiguration(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        return Response.ok(service.describeQueryLoggingConfiguration(
                        regionResolver.resolveRegion(headers), workspaceId))
                .build();
    }

    @PUT
    @Path("/{workspaceId}/logging/query")
    public Response updateQueryLoggingConfiguration(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        return Response.ok(service.updateQueryLoggingConfiguration(
                        regionResolver.resolveRegion(headers), workspaceId, parse(body)))
                .build();
    }

    @DELETE
    @Path("/{workspaceId}/logging/query")
    @Consumes(MediaType.WILDCARD)
    public Response deleteQueryLoggingConfiguration(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        service.deleteQueryLoggingConfiguration(regionResolver.resolveRegion(headers), workspaceId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/{workspaceId}/policy")
    public Response putResourcePolicy(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        return Response.ok(service.putResourcePolicy(
                        regionResolver.resolveRegion(headers), workspaceId, parse(body)))
                .build();
    }

    @GET
    @Path("/{workspaceId}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response describeResourcePolicy(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        return Response.ok(service.describeResourcePolicy(
                        regionResolver.resolveRegion(headers), workspaceId))
                .build();
    }

    @DELETE
    @Path("/{workspaceId}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response deleteResourcePolicy(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        service.deleteResourcePolicy(regionResolver.resolveRegion(headers), workspaceId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/{workspaceId}/anomalydetectors")
    public Response createAnomalyDetector(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        return Response.ok(service.createAnomalyDetector(
                        regionResolver.resolveRegion(headers), workspaceId, parse(body)))
                .build();
    }

    @GET
    @Path("/{workspaceId}/anomalydetectors")
    @Consumes(MediaType.WILDCARD)
    public Response listAnomalyDetectors(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @QueryParam("alias") String alias) {
        return Response.ok(service.listAnomalyDetectors(
                        regionResolver.resolveRegion(headers), workspaceId, alias))
                .build();
    }

    @GET
    @Path("/{workspaceId}/anomalydetectors/{anomalyDetectorId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeAnomalyDetector(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("anomalyDetectorId") String anomalyDetectorId) {
        return Response.ok(service.describeAnomalyDetector(
                        regionResolver.resolveRegion(headers), workspaceId, anomalyDetectorId))
                .build();
    }

    @PUT
    @Path("/{workspaceId}/anomalydetectors/{anomalyDetectorId}")
    public Response putAnomalyDetector(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("anomalyDetectorId") String anomalyDetectorId,
            String body) {
        return Response.ok(service.putAnomalyDetector(
                        regionResolver.resolveRegion(headers), workspaceId, anomalyDetectorId, parse(body)))
                .build();
    }

    @DELETE
    @Path("/{workspaceId}/anomalydetectors/{anomalyDetectorId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAnomalyDetector(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("anomalyDetectorId") String anomalyDetectorId) {
        service.deleteAnomalyDetector(
                regionResolver.resolveRegion(headers), workspaceId, anomalyDetectorId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/{workspaceId}/api/v1/remote_write")
    @Consumes({MediaType.WILDCARD})
    public Response remoteWrite(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @HeaderParam("Content-Encoding") String encoding,
            byte[] body) {
        service.remoteWrite(regionResolver.resolveRegion(headers), workspaceId, body, encoding);
        return Response.ok().build();
    }

    @POST
    @Path("/{workspaceId}/api/v1/query")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response queryPost(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @FormParam("query") String query,
            @FormParam("time") String time) {
        return Response.ok(service.instantQuery(
                        regionResolver.resolveRegion(headers), workspaceId, query, time))
                .build();
    }

    @GET
    @Path("/{workspaceId}/api/v1/query")
    @Consumes(MediaType.WILDCARD)
    public Response queryGet(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @QueryParam("query") String query,
            @QueryParam("time") String time) {
        return Response.ok(service.instantQuery(
                        regionResolver.resolveRegion(headers), workspaceId, query, time))
                .build();
    }

    @POST
    @Path("/{workspaceId}/api/v1/query_range")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response queryRangePost(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @FormParam("query") String query,
            @FormParam("start") String start,
            @FormParam("end") String end,
            @FormParam("step") String step) {
        return Response.ok(service.rangeQuery(
                        regionResolver.resolveRegion(headers), workspaceId, query, start, end, step))
                .build();
    }

    @GET
    @Path("/{workspaceId}/api/v1/query_range")
    @Consumes(MediaType.WILDCARD)
    public Response queryRangeGet(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @QueryParam("query") String query,
            @QueryParam("start") String start,
            @QueryParam("end") String end,
            @QueryParam("step") String step) {
        return Response.ok(service.rangeQuery(
                        regionResolver.resolveRegion(headers), workspaceId, query, start, end, step))
                .build();
    }

    @GET
    @Path("/{workspaceId}/api/v1/labels")
    @Consumes(MediaType.WILDCARD)
    public Response labels(
            @Context HttpHeaders headers,
            @Context UriInfo uriInfo,
            @PathParam("workspaceId") String workspaceId) {
        return Response.ok(service.labelNames(
                        regionResolver.resolveRegion(headers), workspaceId, matchers(uriInfo)))
                .build();
    }

    @GET
    @Path("/{workspaceId}/api/v1/label/{label}/values")
    @Consumes(MediaType.WILDCARD)
    public Response labelValues(
            @Context HttpHeaders headers,
            @Context UriInfo uriInfo,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("label") String label) {
        return Response.ok(service.labelValues(
                        regionResolver.resolveRegion(headers), workspaceId, label, matchers(uriInfo)))
                .build();
    }

    @GET
    @Path("/{workspaceId}/api/v1/series")
    @Consumes(MediaType.WILDCARD)
    public Response series(
            @Context HttpHeaders headers,
            @Context UriInfo uriInfo,
            @PathParam("workspaceId") String workspaceId) {
        return Response.ok(service.series(
                        regionResolver.resolveRegion(headers), workspaceId, matchers(uriInfo)))
                .build();
    }

    @GET
    @Path("/{workspaceId}/api/v1/metadata")
    @Consumes(MediaType.WILDCARD)
    public Response metadata(@Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        service.describeWorkspace(regionResolver.resolveRegion(headers), workspaceId);
        return Response.ok(service.metadata()).build();
    }

    @POST
    @Path("/{workspaceId}/rulegroupsnamespaces")
    public Response createRuleGroupsNamespace(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        RuleGroupsNamespace ns = service.createRuleGroupsNamespace(
                regionResolver.resolveRegion(headers), workspaceId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("name", ns.getName());
        response.put("arn", ns.getArn());
        ObjectNode status = response.putObject("status");
        status.put("statusCode", ns.getStatusCode());
        if (ns.getTags() != null && !ns.getTags().isEmpty()) {
            ObjectNode tags = response.putObject("tags");
            ns.getTags().forEach(tags::put);
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/{workspaceId}/rulegroupsnamespaces/{name}")
    public Response describeRuleGroupsNamespace(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("name") String name) {
        RuleGroupsNamespace ns = service.describeRuleGroupsNamespace(
                regionResolver.resolveRegion(headers), workspaceId, name);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", ns.getArn());
        node.put("name", ns.getName());
        ObjectNode status = node.putObject("status");
        status.put("statusCode", ns.getStatusCode());
        if (ns.getDataBase64() != null) {
            node.put("data", ns.getDataBase64());
        }
        node.put("createdAt", ns.getCreatedAt());
        node.put("modifiedAt", ns.getModifiedAt());
        if (ns.getTags() != null && !ns.getTags().isEmpty()) {
            ObjectNode tags = node.putObject("tags");
            ns.getTags().forEach(tags::put);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ruleGroupsNamespace", node);
        return Response.ok(response).build();
    }

    @PUT
    @Path("/{workspaceId}/rulegroupsnamespaces/{name}")
    public Response putRuleGroupsNamespace(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("name") String name,
            String body) {
        RuleGroupsNamespace ns = service.putRuleGroupsNamespace(
                regionResolver.resolveRegion(headers), workspaceId, name, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("name", ns.getName());
        response.put("arn", ns.getArn());
        ObjectNode status = response.putObject("status");
        status.put("statusCode", ns.getStatusCode());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/{workspaceId}/rulegroupsnamespaces/{name}")
    public Response deleteRuleGroupsNamespace(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("name") String name) {
        service.deleteRuleGroupsNamespace(regionResolver.resolveRegion(headers), workspaceId, name);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/{workspaceId}/alertmanager/definition")
    public Response createAlertManagerDefinition(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        AlertManagerDefinition def = service.createAlertManagerDefinition(
                regionResolver.resolveRegion(headers), workspaceId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode status = response.putObject("status");
        status.put("statusCode", def.getStatusCode());
        return Response.ok(response).build();
    }

    @GET
    @Path("/{workspaceId}/alertmanager/definition")
    public Response describeAlertManagerDefinition(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        AlertManagerDefinition def = service.describeAlertManagerDefinition(
                regionResolver.resolveRegion(headers), workspaceId);
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode status = node.putObject("status");
        status.put("statusCode", def.getStatusCode());
        if (def.getDataBase64() != null) {
            node.put("data", def.getDataBase64());
        }
        node.put("createdAt", def.getCreatedAt());
        node.put("modifiedAt", def.getModifiedAt());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("alertManagerDefinition", node);
        return Response.ok(response).build();
    }

    @PUT
    @Path("/{workspaceId}/alertmanager/definition")
    public Response putAlertManagerDefinition(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        AlertManagerDefinition def = service.putAlertManagerDefinition(
                regionResolver.resolveRegion(headers), workspaceId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode status = response.putObject("status");
        status.put("statusCode", def.getStatusCode());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/{workspaceId}/alertmanager/definition")
    public Response deleteAlertManagerDefinition(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        service.deleteAlertManagerDefinition(regionResolver.resolveRegion(headers), workspaceId);
        return Response.ok(objectMapper.createObjectNode()).build();
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

    private static List<String> matchers(UriInfo uriInfo) {
        List<String> values = new ArrayList<>();
        List<String> bracketed = uriInfo.getQueryParameters().get("match[]");
        if (bracketed != null) {
            values.addAll(bracketed);
        }
        List<String> plain = uriInfo.getQueryParameters().get("match");
        if (plain != null) {
            values.addAll(plain);
        }
        return values;
    }
}
