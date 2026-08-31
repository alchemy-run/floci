package io.github.hectorvent.floci.services.docdbelastic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.docdbelastic.model.Cluster;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon DocumentDB Elastic restJson1. Public AWS paths are {@code /cluster}
 * and {@code /clusters}; {@link DocDbElasticRoutingFilter} prefixes them so they
 * do not collide with S3's catch-all. Tag APIs share {@code /tags/{arn}} and are
 * dispatched by {@code SharedTagsController}. Clusters become ACTIVE immediately.
 */
@Path(DocDbElasticRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DocDbElasticController {

    private final DocDbElasticService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public DocDbElasticController(
            DocDbElasticService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/cluster")
    public Response createCluster(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        Cluster cluster = service.createCluster(region, parse(body));
        return Response.ok(service.toClusterEnvelope(cluster)).build();
    }

    @GET
    @Path("/clusters")
    @Consumes(MediaType.WILDCARD)
    public Response listClusters(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toListClusters(service.listClusters(region))).build();
    }

    @GET
    @Path("/cluster/{clusterArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getCluster(
            @Context HttpHeaders headers, @PathParam("clusterArn") String clusterArn) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toClusterEnvelope(service.getCluster(region, clusterArn))).build();
    }

    @PUT
    @Path("/cluster/{clusterArn:.+}")
    public Response updateCluster(
            @Context HttpHeaders headers,
            @PathParam("clusterArn") String clusterArn,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        Cluster cluster = service.updateCluster(region, clusterArn, parse(body));
        return Response.ok(service.toClusterEnvelope(cluster)).build();
    }

    @DELETE
    @Path("/cluster/{clusterArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteCluster(
            @Context HttpHeaders headers, @PathParam("clusterArn") String clusterArn) {
        String region = regionResolver.resolveRegion(headers);
        Cluster cluster = service.deleteCluster(region, clusterArn);
        return Response.ok(service.toClusterEnvelope(cluster)).build();
    }

    @POST
    @Path("/cluster/{clusterArn:.+}/start")
    @Consumes(MediaType.WILDCARD)
    public Response startCluster(
            @Context HttpHeaders headers, @PathParam("clusterArn") String clusterArn) {
        String region = regionResolver.resolveRegion(headers);
        Cluster cluster = service.startCluster(region, clusterArn);
        return Response.ok(service.toClusterEnvelope(cluster)).build();
    }

    @POST
    @Path("/cluster/{clusterArn:.+}/stop")
    @Consumes(MediaType.WILDCARD)
    public Response stopCluster(
            @Context HttpHeaders headers, @PathParam("clusterArn") String clusterArn) {
        String region = regionResolver.resolveRegion(headers);
        Cluster cluster = service.stopCluster(region, clusterArn);
        return Response.ok(service.toClusterEnvelope(cluster)).build();
    }

    @POST
    @Path("/cluster-snapshot")
    public Response createClusterSnapshot(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toSnapshotEnvelope(service.createClusterSnapshot(region, parse(body)))).build();
    }

    @GET
    @Path("/cluster-snapshots")
    @Consumes(MediaType.WILDCARD)
    public Response listClusterSnapshots(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toListSnapshots(service.listClusterSnapshots(region))).build();
    }

    @GET
    @Path("/cluster-snapshot/{snapshotArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getClusterSnapshot(
            @Context HttpHeaders headers, @PathParam("snapshotArn") String snapshotArn) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toSnapshotEnvelope(service.getClusterSnapshot(region, snapshotArn))).build();
    }

    @DELETE
    @Path("/cluster-snapshot/{snapshotArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteClusterSnapshot(
            @Context HttpHeaders headers, @PathParam("snapshotArn") String snapshotArn) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toSnapshotEnvelope(service.deleteClusterSnapshot(region, snapshotArn))).build();
    }

    @POST
    @Path("/cluster-snapshot/{snapshotArn:.+}/copy")
    public Response copyClusterSnapshot(
            @Context HttpHeaders headers,
            @PathParam("snapshotArn") String snapshotArn,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toSnapshotEnvelope(
                service.copyClusterSnapshot(region, snapshotArn, parse(body)))).build();
    }

    @POST
    @Path("/cluster-snapshot/{snapshotArn:.+}/restore")
    public Response restoreClusterFromSnapshot(
            @Context HttpHeaders headers,
            @PathParam("snapshotArn") String snapshotArn,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        Cluster cluster = service.restoreClusterFromSnapshot(region, snapshotArn, parse(body));
        return Response.ok(service.toClusterEnvelope(cluster)).build();
    }

    @GET
    @Path("/pending-actions")
    @Consumes(MediaType.WILDCARD)
    public Response listPendingMaintenanceActions() {
        return Response.ok(service.listPendingMaintenanceActions()).build();
    }

    @GET
    @Path("/pending-action/{resourceArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getPendingMaintenanceAction(
            @Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.getPendingMaintenanceAction(region, resourceArn)).build();
    }

    @POST
    @Path("/pending-action")
    public Response applyPendingMaintenanceAction(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.applyPendingMaintenanceAction(region, parse(body))).build();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException(
                        "ValidationException",
                        "Request body must be a JSON object.",
                        400,
                        java.util.Map.of("reason", "cannotParse"));
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException(
                    "ValidationException",
                    "Request body is not valid JSON.",
                    400,
                    java.util.Map.of("reason", "cannotParse"));
        }
    }
}
