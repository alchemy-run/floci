package io.floci.aws.services.internetmonitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Internet Monitor REST API, routed here by {@link InternetMonitorRouteFilter}: monitors, health events, internet
 * events and queries under {@code /v20210603}, and the tag routes. Errors carry {@code message} and
 * {@code x-amzn-ErrorType}.
 */
@Path(InternetMonitorRouteFilter.PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class InternetMonitorController {

    /** Internet Monitor's request constraints, reported as ValidationException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/internetmonitor-request-shapes.json", "Internet Monitor request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return InternetMonitorStore.validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : "list".equals(type) || "map".equals(type)
                            ? "size" : "value";
                    return InternetMonitorStore.validation(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return InternetMonitorStore.validation(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return InternetMonitorStore.validation(path + " must be one of " + String.join(", ", values)
                            + ".");
                }

                @Override
                public AwsException required(String path) {
                    return InternetMonitorStore.validation(path + " is required.");
                }
            });

    private static final String MONITORS = "/v20210603/Monitors";

    private final InternetMonitorService service;
    private final InternetMonitorQueries queries;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public InternetMonitorController(InternetMonitorService service, InternetMonitorQueries queries,
                                     RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.queries = queries;
        this.regions = regions;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- monitors

    @POST
    @Path(MONITORS)
    public Response createMonitor(String body) {
        return call("CreateMonitor", body, in -> { }, in -> service.createMonitor(in, region()));
    }

    @GET
    @Path(MONITORS)
    public Response listMonitors(@QueryParam("NextToken") String token, @QueryParam("MaxResults") String maxResults,
                                 @QueryParam("MonitorStatus") String status,
                                 @QueryParam("IncludeLinkedAccounts") String includeLinked) {
        ObjectNode in = input().put("NextToken", token).put("MonitorStatus", status);
        integer(in, "MaxResults", maxResults);
        bool(in, "IncludeLinkedAccounts", includeLinked);
        return call("ListMonitors", in, () -> service.listMonitors(token, number(in, "MaxResults"), status,
                region()));
    }

    @GET
    @Path(MONITORS + "/{MonitorName}")
    public Response getMonitor(@PathParam("MonitorName") String name,
                               @QueryParam("LinkedAccountId") String linkedAccount) {
        ObjectNode in = input().put("MonitorName", name).put("LinkedAccountId", linkedAccount);
        return call("GetMonitor", in, () -> service.getMonitor(name, linkedAccount, region()));
    }

    @PATCH
    @Path(MONITORS + "/{MonitorName}")
    public Response updateMonitor(@PathParam("MonitorName") String name, String body) {
        return call("UpdateMonitor", body, in -> in.put("MonitorName", name), in -> service.updateMonitor(in,
                region()));
    }

    @DELETE
    @Path(MONITORS + "/{MonitorName}")
    public Response deleteMonitor(@PathParam("MonitorName") String name) {
        return call("DeleteMonitor", input().put("MonitorName", name), () -> service.deleteMonitor(name, region()));
    }

    // ---------------------------------------------------------------- health events

    @GET
    @Path(MONITORS + "/{MonitorName}/HealthEvents")
    public Response listHealthEvents(@PathParam("MonitorName") String name, @QueryParam("StartTime") String start,
                                     @QueryParam("EndTime") String end, @QueryParam("NextToken") String token,
                                     @QueryParam("MaxResults") String maxResults,
                                     @QueryParam("EventStatus") String status,
                                     @QueryParam("LinkedAccountId") String linkedAccount) {
        ObjectNode in = input().put("MonitorName", name).put("StartTime", start).put("EndTime", end)
                .put("NextToken", token).put("EventStatus", status).put("LinkedAccountId", linkedAccount);
        integer(in, "MaxResults", maxResults);
        return call("ListHealthEvents", in, () -> service.listHealthEvents(name,
                InternetMonitorStore.instant(in.get("StartTime"), "StartTime"),
                InternetMonitorStore.instant(in.get("EndTime"), "EndTime"), token, number(in, "MaxResults"), status,
                linkedAccount, region()));
    }

    @GET
    @Path(MONITORS + "/{MonitorName}/HealthEvents/{EventId:.+}")
    public Response getHealthEvent(@PathParam("MonitorName") String name, @PathParam("EventId") String eventId,
                                   @QueryParam("LinkedAccountId") String linkedAccount) {
        ObjectNode in = input().put("MonitorName", name).put("EventId", eventId)
                .put("LinkedAccountId", linkedAccount);
        return call("GetHealthEvent", in, () -> service.getHealthEvent(name, eventId, linkedAccount, region()));
    }

    // ---------------------------------------------------------------- internet events

    @GET
    @Path("/v20210603/InternetEvents")
    public Response listInternetEvents(@QueryParam("NextToken") String token,
                                       @QueryParam("InternetEventMaxResults") String maxResults,
                                       @QueryParam("StartTime") String start, @QueryParam("EndTime") String end,
                                       @QueryParam("EventStatus") String status,
                                       @QueryParam("EventType") String type) {
        ObjectNode in = input().put("NextToken", token).put("StartTime", start).put("EndTime", end)
                .put("EventStatus", status).put("EventType", type);
        integer(in, "MaxResults", maxResults);
        return call("ListInternetEvents", in, () -> service.listInternetEvents(
                InternetMonitorStore.instant(in.get("StartTime"), "StartTime"),
                InternetMonitorStore.instant(in.get("EndTime"), "EndTime"), status, type, token,
                number(in, "MaxResults"), region()));
    }

    @GET
    @Path("/v20210603/InternetEvents/{EventId}")
    public Response getInternetEvent(@PathParam("EventId") String eventId) {
        return call("GetInternetEvent", input().put("EventId", eventId),
                () -> service.getInternetEvent(eventId, region()));
    }

    // ---------------------------------------------------------------- queries

    @POST
    @Path(MONITORS + "/{MonitorName}/Queries")
    public Response startQuery(@PathParam("MonitorName") String name, String body) {
        return call("StartQuery", body, in -> in.put("MonitorName", name), in -> queries.startQuery(in, region()));
    }

    @GET
    @Path(MONITORS + "/{MonitorName}/Queries/{QueryId}/Status")
    public Response getQueryStatus(@PathParam("MonitorName") String name, @PathParam("QueryId") String queryId) {
        return call("GetQueryStatus", input().put("MonitorName", name).put("QueryId", queryId),
                () -> queries.getQueryStatus(name, queryId, region()));
    }

    @GET
    @Path(MONITORS + "/{MonitorName}/Queries/{QueryId}/Results")
    public Response getQueryResults(@PathParam("MonitorName") String name, @PathParam("QueryId") String queryId,
                                    @QueryParam("NextToken") String token,
                                    @QueryParam("MaxResults") String maxResults) {
        ObjectNode in = input().put("MonitorName", name).put("QueryId", queryId).put("NextToken", token);
        integer(in, "MaxResults", maxResults);
        return call("GetQueryResults", in, () -> queries.getQueryResults(name, queryId, token,
                number(in, "MaxResults"), region()));
    }

    @DELETE
    @Path(MONITORS + "/{MonitorName}/Queries/{QueryId}")
    public Response stopQuery(@PathParam("MonitorName") String name, @PathParam("QueryId") String queryId) {
        return call("StopQuery", input().put("MonitorName", name).put("QueryId", queryId),
                () -> queries.stopQuery(name, queryId, region()));
    }

    // ---------------------------------------------------------------- tags

    @GET
    @Path("/tags/{ResourceArn:.+}")
    public Response listTagsForResource(@PathParam("ResourceArn") String arn) {
        return call("ListTagsForResource", input().put("ResourceArn", arn), () -> service.listTags(arn));
    }

    @POST
    @Path("/tags/{ResourceArn:.+}")
    public Response tagResource(@PathParam("ResourceArn") String arn, String body) {
        return call("TagResource", body, in -> in.put("ResourceArn", arn),
                in -> service.tagResource(arn, in.path("Tags")));
    }

    @DELETE
    @Path("/tags/{ResourceArn:.+}")
    public Response untagResource(@PathParam("ResourceArn") String arn, @QueryParam("tagKeys") List<String> keys) {
        ObjectNode in = input().put("ResourceArn", arn);
        keys.forEach(in.withArray("TagKeys")::add);
        return call("UntagResource", in, () -> service.untagResource(arn, keys));
    }

    // ---------------------------------------------------------------- plumbing

    private String region() {
        return regions.getRegion();
    }

    private ObjectNode input() {
        return mapper.createObjectNode();
    }

    private static void integer(ObjectNode in, String name, String value) {
        if (value == null) {
            return;
        }
        try {
            in.put(name, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            in.put(name, value);
        }
    }

    private static void bool(ObjectNode in, String name, String value) {
        if (value != null) {
            in.put(name, Boolean.parseBoolean(value));
        }
    }

    private static Integer number(ObjectNode in, String name) {
        return in.hasNonNull(name) && in.get(name).isInt() ? in.get(name).asInt() : null;
    }

    private Response call(String operation, String body, Consumer<ObjectNode> pathMembers,
                          Function<JsonNode, ObjectNode> handler) {
        return reply(() -> {
            JsonNode parsed = parse(body);
            ObjectNode in = (ObjectNode) parsed;
            pathMembers.accept(in);
            check(operation, in);
            return handler.apply(in);
        });
    }

    private Response call(String operation, ObjectNode input, Supplier<ObjectNode> handler) {
        return reply(() -> {
            check(operation, input);
            return handler.get();
        });
    }

    /** Checks the members a request carries; absent query parameters are null in {@code input}, and dropped. */
    private static void check(String operation, ObjectNode input) {
        ObjectNode present = input.deepCopy();
        List<String> absent = new ArrayList<>();
        present.fields().forEachRemaining(field -> {
            if (field.getValue().isNull()) {
                absent.add(field.getKey());
            }
        });
        present.remove(absent);
        SHAPES.check(operation, present);
    }

    private Response reply(Supplier<ObjectNode> call) {
        try {
            return Response.ok(call.get().toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (AwsException e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("message", e.getMessage());
            return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                    .header("x-amzn-ErrorType", e.getErrorCode()).entity(error.toString()).build();
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw InternetMonitorStore.validation("The request body must be a JSON object.");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw InternetMonitorStore.validation("The request body is not valid JSON: " + e.getOriginalMessage());
        }
    }
}
