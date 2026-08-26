package io.github.hectorvent.floci.services.xray;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * AWS X-Ray (Smithy restJson1).
 *
 * <p>{@link XRayRoutingFilter} prefixes requests signed for {@code xray} so
 * paths such as {@code /TagResource} do not collide with Kinesis Video.
 */
@Path(XRayRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class XRayController {

    private final XRayService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public XRayController(XRayService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/PutResourcePolicy")
    public Response putResourcePolicy(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::putResourcePolicy);
    }

    @POST
    @Path("/ListResourcePolicies")
    @Consumes(MediaType.WILDCARD)
    public Response listResourcePolicies(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::listResourcePolicies);
    }

    @POST
    @Path("/DeleteResourcePolicy")
    @Consumes(MediaType.WILDCARD)
    public Response deleteResourcePolicy(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::deleteResourcePolicy);
    }

    @POST
    @Path("/CreateGroup")
    public Response createGroup(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::createGroup);
    }

    @POST
    @Path("/GetGroup")
    @Consumes(MediaType.WILDCARD)
    public Response getGroup(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getGroup);
    }

    @POST
    @Path("/UpdateGroup")
    public Response updateGroup(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::updateGroup);
    }

    @POST
    @Path("/DeleteGroup")
    @Consumes(MediaType.WILDCARD)
    public Response deleteGroup(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::deleteGroup);
    }

    @POST
    @Path("/Groups")
    @Consumes(MediaType.WILDCARD)
    public Response getGroups(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getGroups);
    }

    @POST
    @Path("/CreateSamplingRule")
    public Response createSamplingRule(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::createSamplingRule);
    }

    @POST
    @Path("/GetSamplingRules")
    @Consumes(MediaType.WILDCARD)
    public Response getSamplingRules(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getSamplingRules);
    }

    @POST
    @Path("/UpdateSamplingRule")
    public Response updateSamplingRule(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::updateSamplingRule);
    }

    @POST
    @Path("/DeleteSamplingRule")
    public Response deleteSamplingRule(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::deleteSamplingRule);
    }

    @POST
    @Path("/ListTagsForResource")
    public Response listTagsForResource(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::listTagsForResource);
    }

    @POST
    @Path("/TagResource")
    public Response tagResource(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::tagResource);
    }

    @POST
    @Path("/UntagResource")
    public Response untagResource(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::untagResource);
    }

    @POST
    @Path("/TraceSegments")
    public Response putTraceSegments(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::putTraceSegments);
    }

    @POST
    @Path("/TraceSummaries")
    public Response getTraceSummaries(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getTraceSummaries);
    }

    @POST
    @Path("/Traces")
    public Response batchGetTraces(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::batchGetTraces);
    }

    @POST
    @Path("/TelemetryRecords")
    @Consumes(MediaType.WILDCARD)
    public Response putTelemetryRecords(String body) {
        return plain(body, service::putTelemetryRecords);
    }

    @POST
    @Path("/SamplingTargets")
    public Response getSamplingTargets(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getSamplingTargets);
    }

    @POST
    @Path("/SamplingStatisticSummaries")
    @Consumes(MediaType.WILDCARD)
    public Response getSamplingStatisticSummaries(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getSamplingStatisticSummaries);
    }

    @POST
    @Path("/ServiceGraph")
    public Response getServiceGraph(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getServiceGraph);
    }

    @POST
    @Path("/TraceGraph")
    public Response getTraceGraph(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getTraceGraph);
    }

    @POST
    @Path("/TimeSeriesServiceStatistics")
    public Response getTimeSeriesServiceStatistics(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getTimeSeriesServiceStatistics);
    }

    @POST
    @Path("/InsightSummaries")
    public Response getInsightSummaries(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getInsightSummaries);
    }

    @POST
    @Path("/Insight")
    public Response getInsight(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getInsight);
    }

    @POST
    @Path("/InsightEvents")
    public Response getInsightEvents(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getInsightEvents);
    }

    @POST
    @Path("/InsightImpactGraph")
    public Response getInsightImpactGraph(@Context HttpHeaders headers, String body) {
        return regional(headers, body, service::getInsightImpactGraph);
    }

    @POST
    @Path("/GetTraceSegmentDestination")
    @Consumes(MediaType.WILDCARD)
    public Response getTraceSegmentDestination(String body) {
        return run(() -> {
            parse(body);
            return Response.ok(service.getTraceSegmentDestination()).build();
        });
    }

    @POST
    @Path("/StartTraceRetrieval")
    public Response startTraceRetrieval(String body) {
        return plain(body, service::startTraceRetrieval);
    }

    @POST
    @Path("/ListRetrievedTraces")
    public Response listRetrievedTraces(String body) {
        return plain(body, service::listRetrievedTraces);
    }

    @POST
    @Path("/GetRetrievedTracesGraph")
    public Response getRetrievedTracesGraph(String body) {
        return plain(body, service::getRetrievedTracesGraph);
    }

    @POST
    @Path("/CancelTraceRetrieval")
    public Response cancelTraceRetrieval(String body) {
        return plain(body, service::cancelTraceRetrieval);
    }

    private Response regional(HttpHeaders headers, String body, BiFunction<String, JsonNode, ObjectNode> action) {
        return run(() -> Response.ok(action.apply(region(headers), parse(body))).build());
    }

    private Response plain(String body, Function<JsonNode, ObjectNode> action) {
        return run(() -> Response.ok(action.apply(parse(body))).build());
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("InvalidRequestException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", "Request body is not valid JSON.", 400);
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
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
}
