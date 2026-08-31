package io.github.hectorvent.floci.services.mediaconvert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.function.Supplier;

/**
 * AWS Elemental MediaConvert restJson1 ({@code /2017-08-29/*}).
 *
 * <p>Literal versioned paths take JAX-RS precedence over S3's {@code /{bucket}}
 * catch-all. Requests are signed as {@code mediaconvert}.
 */
@Path("/2017-08-29")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MediaConvertController {

    private final MediaConvertService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public MediaConvertController(
            MediaConvertService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listJobs(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("order") String order,
            @QueryParam("queue") String queue,
            @QueryParam("status") String status) {
        return Response.ok(service.listJobs(region(headers), status, queue, order, maxResults)).build();
    }

    @POST
    @Path("/jobs")
    public Response createJob(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(
                service.toJobEnvelope(service.createJob(region(headers), request))).build());
    }

    @GET
    @Path("/jobs/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getJob(@Context HttpHeaders headers, @PathParam("id") String id) {
        return Response.ok(service.toJobEnvelope(service.getJob(region(headers), id))).build();
    }

    @DELETE
    @Path("/jobs/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response cancelJob(@Context HttpHeaders headers, @PathParam("id") String id) {
        service.cancelJob(region(headers), id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/search")
    @Consumes(MediaType.WILDCARD)
    public Response searchJobs(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("order") String order,
            @QueryParam("queue") String queue,
            @QueryParam("status") String status,
            @QueryParam("inputFile") String inputFile) {
        return Response.ok(service.searchJobs(
                region(headers), status, queue, inputFile, order, maxResults)).build();
    }

    @POST
    @Path("/probe")
    public Response probe(String body) {
        return Response.ok(service.probe(parse(body))).build();
    }

    @POST
    @Path("/jobsQueries")
    public Response startJobsQuery(@Context HttpHeaders headers, String body) {
        return Response.ok(service.startJobsQuery(region(headers), parse(body))).build();
    }

    @GET
    @Path("/jobsQueries/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getJobsQueryResults(@Context HttpHeaders headers, @PathParam("id") String id) {
        return Response.ok(service.toJobsQueryEnvelope(
                service.getJobsQueryResults(region(headers), id))).build();
    }

    @GET
    @Path("/queues")
    @Consumes(MediaType.WILDCARD)
    public Response listQueues(@Context HttpHeaders headers) {
        return Response.ok(service.listQueues(region(headers))).build();
    }

    @POST
    @Path("/queues")
    public Response createQueue(@Context HttpHeaders headers, String body) {
        return Response.ok(service.toQueueEnvelope(service.createQueue(region(headers), parse(body)))).build();
    }

    @GET
    @Path("/queues/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response getQueue(@Context HttpHeaders headers, @PathParam("name") String name) {
        return run(() -> Response.ok(service.toQueueEnvelope(service.getQueue(region(headers), name))).build());
    }

    @PUT
    @Path("/queues/{name}")
    public Response updateQueue(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        return Response.ok(service.toQueueEnvelope(
                service.updateQueue(region(headers), name, parse(body)))).build();
    }

    @DELETE
    @Path("/queues/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteQueue(@Context HttpHeaders headers, @PathParam("name") String name) {
        service.deleteQueue(region(headers), name);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/jobTemplates")
    @Consumes(MediaType.WILDCARD)
    public Response listJobTemplates(@Context HttpHeaders headers) {
        return Response.ok(service.listJobTemplates(region(headers))).build();
    }

    @POST
    @Path("/jobTemplates")
    public Response createJobTemplate(@Context HttpHeaders headers, String body) {
        return Response.ok(service.toJobTemplateEnvelope(
                service.createJobTemplate(region(headers), parse(body)))).build();
    }

    @GET
    @Path("/jobTemplates/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response getJobTemplate(@Context HttpHeaders headers, @PathParam("name") String name) {
        return run(() -> Response.ok(service.toJobTemplateEnvelope(
                service.getJobTemplate(region(headers), name))).build());
    }

    @PUT
    @Path("/jobTemplates/{name}")
    public Response updateJobTemplate(
            @Context HttpHeaders headers, @PathParam("name") String name, String body) {
        return Response.ok(service.toJobTemplateEnvelope(
                service.updateJobTemplate(region(headers), name, parse(body)))).build();
    }

    @DELETE
    @Path("/jobTemplates/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteJobTemplate(@Context HttpHeaders headers, @PathParam("name") String name) {
        service.deleteJobTemplate(region(headers), name);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/presets")
    @Consumes(MediaType.WILDCARD)
    public Response listPresets(@Context HttpHeaders headers) {
        return Response.ok(service.listPresets(region(headers))).build();
    }

    @POST
    @Path("/presets")
    public Response createPreset(@Context HttpHeaders headers, String body) {
        return Response.ok(service.toPresetEnvelope(service.createPreset(region(headers), parse(body)))).build();
    }

    @GET
    @Path("/presets/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response getPreset(@Context HttpHeaders headers, @PathParam("name") String name) {
        return run(() -> Response.ok(service.toPresetEnvelope(service.getPreset(region(headers), name))).build());
    }

    @PUT
    @Path("/presets/{name}")
    public Response updatePreset(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        return Response.ok(service.toPresetEnvelope(
                service.updatePreset(region(headers), name, parse(body)))).build();
    }

    @DELETE
    @Path("/presets/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response deletePreset(@Context HttpHeaders headers, @PathParam("name") String name) {
        service.deletePreset(region(headers), name);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/tags/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response listTags(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return Response.ok(service.listTags(region(headers), arn)).build();
    }

    @POST
    @Path("/tags")
    public Response tagResource(@Context HttpHeaders headers, String body) {
        service.tagResource(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/tags/{arn: .+}")
    public Response untagResource(@Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        service.untagResource(region(headers), arn, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
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
                throw new AwsException("BadRequestException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "Request body is not valid JSON.", 400);
        }
    }

    private Response handle(String body, Handler handler) {
        try {
            return handler.handle(parse(body));
        } catch (AwsException e) {
            return error(e);
        }
    }

    private Response run(Supplier<Response> action) {
        try {
            return action.get();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request);
    }
}
