package io.github.hectorvent.floci.services.quicksight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Amazon QuickSight restJson1. Public AWS paths are {@code /accounts/{id}/...}
 * and {@code /resources/{arn}/tags}; {@link QuickSightRoutingFilter} prefixes
 * them so they do not collide with Backup or S3.
 */
@Path(QuickSightRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QuickSightController {

    private final QuickSightService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public QuickSightController(
            QuickSightService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/accounts/{awsAccountId}/data-sources")
    public Response createDataSource(
            @Context HttpHeaders headers, @PathParam("awsAccountId") String awsAccountId, String body) {
        return run(() -> service.createDataSource(region(headers), awsAccountId, parse(body)), 202);
    }

    @GET
    @Path("/accounts/{awsAccountId}/data-sources")
    @Consumes(MediaType.WILDCARD)
    public Response listDataSources(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @QueryParam("max-results") String maxResults,
            @QueryParam("next-token") String nextToken) {
        return ok(() -> service.listDataSources(region(headers), awsAccountId, maxResults, nextToken));
    }

    @GET
    @Path("/accounts/{awsAccountId}/data-sources/{dataSourceId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeDataSource(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dataSourceId") String dataSourceId) {
        return ok(() -> service.describeDataSource(region(headers), dataSourceId));
    }

    @PUT
    @Path("/accounts/{awsAccountId}/data-sources/{dataSourceId}")
    public Response updateDataSource(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dataSourceId") String dataSourceId,
            String body) {
        return run(() -> service.updateDataSource(region(headers), dataSourceId, parse(body)), 202);
    }

    @DELETE
    @Path("/accounts/{awsAccountId}/data-sources/{dataSourceId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDataSource(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dataSourceId") String dataSourceId) {
        return ok(() -> service.deleteDataSource(region(headers), dataSourceId));
    }

    @POST
    @Path("/accounts/{awsAccountId}/data-sets")
    public Response createDataSet(
            @Context HttpHeaders headers, @PathParam("awsAccountId") String awsAccountId, String body) {
        return created(() -> service.createDataSet(region(headers), awsAccountId, parse(body)));
    }

    @GET
    @Path("/accounts/{awsAccountId}/data-sets/{dataSetId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeDataSet(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dataSetId") String dataSetId) {
        return ok(() -> service.describeDataSet(region(headers), dataSetId));
    }

    @PUT
    @Path("/accounts/{awsAccountId}/data-sets/{dataSetId}")
    public Response updateDataSet(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dataSetId") String dataSetId,
            String body) {
        return ok(() -> service.updateDataSet(region(headers), dataSetId, parse(body)));
    }

    @DELETE
    @Path("/accounts/{awsAccountId}/data-sets/{dataSetId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDataSet(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dataSetId") String dataSetId) {
        return ok(() -> service.deleteDataSet(region(headers), dataSetId));
    }

    @POST
    @Path("/accounts/{awsAccountId}/dashboards/{dashboardId}")
    public Response createDashboard(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dashboardId") String dashboardId,
            String body) {
        return created(() -> service.createDashboard(region(headers), awsAccountId, dashboardId, parse(body)));
    }

    @GET
    @Path("/accounts/{awsAccountId}/dashboards/{dashboardId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeDashboard(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dashboardId") String dashboardId) {
        return ok(() -> service.describeDashboard(region(headers), dashboardId));
    }

    @PUT
    @Path("/accounts/{awsAccountId}/dashboards/{dashboardId}")
    public Response updateDashboard(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dashboardId") String dashboardId,
            String body) {
        return ok(() -> service.updateDashboard(region(headers), dashboardId, parse(body)));
    }

    @DELETE
    @Path("/accounts/{awsAccountId}/dashboards/{dashboardId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDashboard(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dashboardId") String dashboardId) {
        return ok(() -> service.deleteDashboard(region(headers), dashboardId));
    }

    @PUT
    @Path("/accounts/{awsAccountId}/data-sets/{dataSetId}/ingestions/{ingestionId}")
    public Response createIngestion(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dataSetId") String dataSetId,
            @PathParam("ingestionId") String ingestionId,
            String body) {
        return created(() -> service.createIngestion(region(headers), dataSetId, ingestionId, parse(body)));
    }

    @GET
    @Path("/accounts/{awsAccountId}/data-sets/{dataSetId}/ingestions/{ingestionId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeIngestion(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dataSetId") String dataSetId,
            @PathParam("ingestionId") String ingestionId) {
        return ok(() -> service.describeIngestion(region(headers), dataSetId, ingestionId));
    }

    @DELETE
    @Path("/accounts/{awsAccountId}/data-sets/{dataSetId}/ingestions/{ingestionId}")
    @Consumes(MediaType.WILDCARD)
    public Response cancelIngestion(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dataSetId") String dataSetId,
            @PathParam("ingestionId") String ingestionId) {
        return ok(() -> service.cancelIngestion(region(headers), dataSetId, ingestionId));
    }

    @GET
    @Path("/accounts/{awsAccountId}/data-sets/{dataSetId}/ingestions")
    @Consumes(MediaType.WILDCARD)
    public Response listIngestions(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dataSetId") String dataSetId,
            @QueryParam("max-results") String maxResults,
            @QueryParam("next-token") String nextToken) {
        return ok(() -> service.listIngestions(region(headers), dataSetId, maxResults, nextToken));
    }

    @POST
    @Path("/accounts/{awsAccountId}/dashboards/{dashboardId}/snapshot-jobs")
    public Response startDashboardSnapshotJob(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dashboardId") String dashboardId,
            String body) {
        return created(() -> service.startDashboardSnapshotJob(
                region(headers), awsAccountId, dashboardId, parse(body)));
    }

    @GET
    @Path("/accounts/{awsAccountId}/dashboards/{dashboardId}/snapshot-jobs/{snapshotJobId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeDashboardSnapshotJob(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dashboardId") String dashboardId,
            @PathParam("snapshotJobId") String snapshotJobId) {
        return ok(() -> service.describeDashboardSnapshotJob(region(headers), dashboardId, snapshotJobId));
    }

    @GET
    @Path("/accounts/{awsAccountId}/dashboards/{dashboardId}/snapshot-jobs/{snapshotJobId}/result")
    @Consumes(MediaType.WILDCARD)
    public Response describeDashboardSnapshotJobResult(
            @Context HttpHeaders headers,
            @PathParam("awsAccountId") String awsAccountId,
            @PathParam("dashboardId") String dashboardId,
            @PathParam("snapshotJobId") String snapshotJobId) {
        return ok(() -> service.describeDashboardSnapshotJobResult(
                region(headers), dashboardId, snapshotJobId));
    }

    @POST
    @Path("/accounts/{awsAccountId}/embed-url/registered-user")
    public Response generateEmbedUrlForRegisteredUser(
            @Context HttpHeaders headers, @PathParam("awsAccountId") String awsAccountId, String body) {
        return ok(() -> service.generateEmbedUrlForRegisteredUser(
                region(headers), awsAccountId, parse(body)));
    }

    @POST
    @Path("/accounts/{awsAccountId}/embed-url/anonymous-user")
    public Response generateEmbedUrlForAnonymousUser(
            @Context HttpHeaders headers, @PathParam("awsAccountId") String awsAccountId, String body) {
        return ok(() -> service.generateEmbedUrlForAnonymousUser(
                region(headers), awsAccountId, parse(body)));
    }

    @GET
    @Path("/resources/{resourceArn: .+}/tags")
    @Consumes(MediaType.WILDCARD)
    public Response listTagsForResource(
            @Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn) {
        return ok(() -> service.listTagsForResource(region(headers), decodeArn(resourceArn)));
    }

    @POST
    @Path("/resources/{resourceArn: .+}/tags")
    public Response tagResource(
            @Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn, String body) {
        return ok(() -> service.tagResource(region(headers), decodeArn(resourceArn), parse(body)));
    }

    @DELETE
    @Path("/resources/{resourceArn: .+}/tags")
    @Consumes(MediaType.WILDCARD)
    public Response untagResource(
            @Context HttpHeaders headers,
            @PathParam("resourceArn") String resourceArn,
            @QueryParam("keys") List<String> keys) {
        return ok(() -> service.untagResource(region(headers), decodeArn(resourceArn), keys));
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Response ok(Supplier<ObjectNode> action) {
        return run(action, 200);
    }

    private Response created(Supplier<ObjectNode> action) {
        return run(action, 201);
    }

    private Response run(Supplier<ObjectNode> action, int status) {
        try {
            ObjectNode body = action.get();
            body.put("Status", status);
            return Response.status(status).entity(body).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private Response error(AwsException exception) {
        Object entity = exception.getExtendedData() != null
                ? extended(exception)
                : new AwsErrorResponse(exception.jsonType(), exception.getMessage());
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(entity)
                .build();
    }

    private ObjectNode extended(AwsException exception) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", exception.jsonType());
        node.put("message", exception.getMessage());
        for (Map.Entry<String, Object> entry : exception.getExtendedData().entrySet()) {
            node.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
        }
        return node;
    }

    private static String decodeArn(String resourceArn) {
        if (resourceArn == null) {
            return null;
        }
        String decoded = resourceArn;
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
                throw new AwsException("InvalidParameterValueException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", "Request body is not valid JSON.", 400);
        }
    }
}
