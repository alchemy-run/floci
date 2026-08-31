package io.github.hectorvent.floci.services.emrcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.emrcontainers.model.JobRun;
import io.github.hectorvent.floci.services.emrcontainers.model.JobTemplate;
import io.github.hectorvent.floci.services.emrcontainers.model.VirtualCluster;
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

import java.util.List;

/**
 * Amazon EMR on EKS restJson1. Paths are rewritten onto
 * {@link EmrContainersRoutingFilter#INTERNAL_PREFIX} so they do not match S3's
 * {@code /{bucket}} catch-all. Tag APIs share {@code /tags/{arn}} via TagHandler.
 */
@Path(EmrContainersRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EmrContainersController {

    private final EmrContainersService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public EmrContainersController(
            EmrContainersService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/jobtemplates")
    public Response createJobTemplate(@Context HttpHeaders headers, String body) {
        JobTemplate template = service.createJobTemplate(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", template.getId());
        response.put("name", template.getName());
        response.put("arn", template.getArn());
        if (template.getCreatedAt() != null) {
            response.put("createdAt", template.getCreatedAt());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/jobtemplates")
    @Consumes(MediaType.WILDCARD)
    public Response listJobTemplates(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        EmrContainersService.Page<JobTemplate> page = service.listJobTemplates(
                regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("templates", service.toPublicJobTemplates(page.items()));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/jobtemplates/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response describeJobTemplate(@Context HttpHeaders headers, @PathParam("id") String id) {
        JobTemplate template = service.describeJobTemplate(regionResolver.resolveRegion(headers), id);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("jobTemplate", service.toPublicJobTemplate(template));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/jobtemplates/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteJobTemplate(@Context HttpHeaders headers, @PathParam("id") String id) {
        service.deleteJobTemplate(regionResolver.resolveRegion(headers), id);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", id);
        return Response.ok(response).build();
    }

    @POST
    @Path("/virtualclusters")
    public Response createVirtualCluster(@Context HttpHeaders headers, String body) {
        VirtualCluster cluster = service.createVirtualCluster(
                regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", cluster.getId());
        response.put("name", cluster.getName());
        response.put("arn", cluster.getArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/virtualclusters")
    @Consumes(MediaType.WILDCARD)
    public Response listVirtualClusters(
            @Context HttpHeaders headers,
            @QueryParam("containerProviderId") String containerProviderId,
            @QueryParam("containerProviderType") String containerProviderType,
            @QueryParam("states") List<String> states,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        EmrContainersService.Page<VirtualCluster> page = service.listVirtualClusters(
                regionResolver.resolveRegion(headers),
                containerProviderId,
                containerProviderType,
                states,
                maxResults,
                nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("virtualClusters", service.toPublicClusters(page.items()));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/virtualclusters/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response describeVirtualCluster(@Context HttpHeaders headers, @PathParam("id") String id) {
        VirtualCluster cluster = service.describeVirtualCluster(
                regionResolver.resolveRegion(headers), id);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("virtualCluster", service.toPublicCluster(cluster));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/virtualclusters/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteVirtualCluster(@Context HttpHeaders headers, @PathParam("id") String id) {
        VirtualCluster cluster = service.deleteVirtualCluster(
                regionResolver.resolveRegion(headers), id);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", cluster.getId());
        return Response.ok(response).build();
    }

    @POST
    @Path("/virtualclusters/{virtualClusterId}/jobruns")
    public Response startJobRun(
            @Context HttpHeaders headers,
            @PathParam("virtualClusterId") String virtualClusterId,
            String body) {
        JobRun job = service.startJobRun(
                regionResolver.resolveRegion(headers), virtualClusterId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", job.getId());
        if (job.getName() != null) {
            response.put("name", job.getName());
        }
        response.put("arn", job.getArn());
        response.put("virtualClusterId", job.getVirtualClusterId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/virtualclusters/{virtualClusterId}/jobruns")
    @Consumes(MediaType.WILDCARD)
    public Response listJobRuns(
            @Context HttpHeaders headers,
            @PathParam("virtualClusterId") String virtualClusterId,
            @QueryParam("states") List<String> states,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        EmrContainersService.Page<JobRun> page = service.listJobRuns(
                regionResolver.resolveRegion(headers),
                virtualClusterId,
                states,
                maxResults,
                nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("jobRuns", service.toPublicJobRuns(page.items()));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/virtualclusters/{virtualClusterId}/jobruns/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response describeJobRun(
            @Context HttpHeaders headers,
            @PathParam("virtualClusterId") String virtualClusterId,
            @PathParam("id") String id) {
        JobRun job = service.describeJobRun(
                regionResolver.resolveRegion(headers), virtualClusterId, id);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("jobRun", service.toPublicJobRun(job));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/virtualclusters/{virtualClusterId}/jobruns/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response cancelJobRun(
            @Context HttpHeaders headers,
            @PathParam("virtualClusterId") String virtualClusterId,
            @PathParam("id") String id) {
        JobRun job = service.cancelJobRun(
                regionResolver.resolveRegion(headers), virtualClusterId, id);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", job.getId());
        response.put("virtualClusterId", job.getVirtualClusterId());
        return Response.ok(response).build();
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
