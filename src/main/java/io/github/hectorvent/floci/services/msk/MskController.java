package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.MskTopic;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon MSK restJson1. Public AWS paths are {@code /v1/clusters} and
 * {@code /api/v2/clusters}; {@link MskRoutingFilter} prefixes kafka-signed
 * requests so they do not collide with S3 or AppSync.
 */
@Path(MskRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MskController {

    private final MskService mskService;

    @Inject
    public MskController(MskService mskService) {
        this.mskService = mskService;
    }

    @POST
    @Path("/v1/clusters")
    public Response createCluster(Map<String, Object> request) {
        return handle(() -> {
            String clusterName = request == null ? null : (String) request.get("clusterName");
            String kafkaVersion = request == null ? null : (String) request.get("kafkaVersion");
            MskCluster cluster = mskService.createCluster(clusterName, kafkaVersion);
            return Response.ok(createResponse(cluster)).build();
        });
    }

    @POST
    @Path("/api/v2/clusters")
    public Response createClusterV2(Map<String, Object> request) {
        return handle(() -> {
            MskCluster cluster = mskService.createClusterV2(request == null ? Map.of() : request);
            return Response.ok(createResponse(cluster)).build();
        });
    }

    @GET
    @Path("/v1/clusters")
    @Consumes(MediaType.WILDCARD)
    public Response listClusters() {
        return handle(() -> Response.ok(Map.of("clusterInfoList", mskService.listClusters())).build());
    }

    @GET
    @Path("/api/v2/clusters")
    @Consumes(MediaType.WILDCARD)
    public Response listClustersV2(@QueryParam("clusterNameFilter") String clusterNameFilter,
                                   @QueryParam("clusterTypeFilter") String clusterTypeFilter) {
        return handle(() -> {
            List<Map<String, Object>> clusters = new ArrayList<>();
            for (MskCluster cluster : mskService.listClusters(clusterNameFilter, clusterTypeFilter)) {
                clusters.add(mskService.toClusterInfoV2(cluster));
            }
            return Response.ok(Map.of("clusterInfoList", clusters)).build();
        });
    }

    @GET
    @Path("/v1/clusters/{clusterArn:.+}/bootstrap-brokers")
    @Consumes(MediaType.WILDCARD)
    public Response getBootstrapBrokers(@PathParam("clusterArn") String clusterArn) {
        return handle(() -> Response.ok(mskService.bootstrapBrokersResponse(clusterArn)).build());
    }

    @GET
    @Path("/v1/clusters/{clusterArn:.+}/topics/{topicName}")
    @Consumes(MediaType.WILDCARD)
    public Response describeTopic(@PathParam("clusterArn") String clusterArn,
                                  @PathParam("topicName") String topicName) {
        return handle(() -> Response.ok(topicBody(mskService.describeTopic(clusterArn, topicName))).build());
    }

    @DELETE
    @Path("/v1/clusters/{clusterArn:.+}/topics/{topicName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteTopic(@PathParam("clusterArn") String clusterArn,
                                @PathParam("topicName") String topicName) {
        return handle(() -> Response.ok(topicBody(mskService.deleteTopic(clusterArn, topicName))).build());
    }

    @GET
    @Path("/v1/clusters/{clusterArn:.+}/topics")
    @Consumes(MediaType.WILDCARD)
    public Response listTopics(@PathParam("clusterArn") String clusterArn,
                               @QueryParam("topicNameFilter") String topicNameFilter) {
        return handle(() -> {
            List<Map<String, Object>> topics = new ArrayList<>();
            for (MskTopic topic : mskService.listTopics(clusterArn, topicNameFilter)) {
                topics.add(topicInfo(topic));
            }
            return Response.ok(Map.of("topics", topics)).build();
        });
    }

    @POST
    @Path("/v1/clusters/{clusterArn:.+}/topics")
    public Response createTopic(@PathParam("clusterArn") String clusterArn, Map<String, Object> request) {
        return handle(() -> {
            MskTopic topic = mskService.createTopic(clusterArn, request == null ? Map.of() : request);
            return Response.ok(topicBody(topic)).build();
        });
    }

    @GET
    @Path("/v1/clusters/{clusterArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response describeCluster(@PathParam("clusterArn") String clusterArn) {
        return handle(() -> Response.ok(Map.of("clusterInfo", mskService.describeCluster(clusterArn))).build());
    }

    @GET
    @Path("/api/v2/clusters/{clusterArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response describeClusterV2(@PathParam("clusterArn") String clusterArn) {
        return handle(() -> {
            MskCluster cluster = mskService.describeCluster(clusterArn);
            return Response.ok(Map.of("clusterInfo", mskService.toClusterInfoV2(cluster))).build();
        });
    }

    @DELETE
    @Path("/v1/clusters/{clusterArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteCluster(@PathParam("clusterArn") String clusterArn) {
        return handle(() -> {
            String arn = MskService.decode(clusterArn);
            mskService.deleteCluster(arn);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("clusterArn", arn);
            body.put("state", "DELETING");
            return Response.ok(body).build();
        });
    }

    @GET
    @Path("/v1/tags/{resourceArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response listTags(@PathParam("resourceArn") String resourceArn) {
        return handle(() -> Response.ok(Map.of("tags", mskService.listTags(resourceArn))).build());
    }

    @POST
    @Path("/v1/tags/{resourceArn:.+}")
    public Response tagResource(@PathParam("resourceArn") String resourceArn, Map<String, Object> request) {
        return handle(() -> {
            Object rawTags = request == null ? null : request.get("tags");
            Map<String, String> tags = new LinkedHashMap<>();
            if (rawTags instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        tags.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                }
            }
            mskService.tagResource(resourceArn, tags);
            return Response.ok(Map.of()).build();
        });
    }

    @DELETE
    @Path("/v1/tags/{resourceArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response untagResource(@PathParam("resourceArn") String resourceArn,
                                  @QueryParam("tagKeys") List<String> tagKeys) {
        return handle(() -> {
            mskService.untagResource(resourceArn, tagKeys);
            return Response.ok(Map.of()).build();
        });
    }

    private static Map<String, Object> createResponse(MskCluster cluster) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clusterArn", cluster.getClusterArn());
        body.put("clusterName", cluster.getClusterName());
        body.put("state", cluster.getState());
        body.put("clusterType", cluster.getClusterType());
        return body;
    }

    private static Map<String, Object> topicBody(MskTopic topic) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topicArn", topic.getTopicArn());
        body.put("topicName", topic.getTopicName());
        body.put("partitionCount", topic.getPartitionCount());
        body.put("replicationFactor", topic.getReplicationFactor());
        body.put("status", topic.getStatus());
        return body;
    }

    private static Map<String, Object> topicInfo(MskTopic topic) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topicArn", topic.getTopicArn());
        body.put("topicName", topic.getTopicName());
        body.put("partitionCount", topic.getPartitionCount());
        body.put("replicationFactor", topic.getReplicationFactor());
        return body;
    }

    private static Response handle(Handler handler) {
        try {
            return handler.handle();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", e.jsonType())
                    .entity(new AwsErrorResponse(e.jsonType(), e.getMessage()))
                    .build();
        }
    }

    @FunctionalInterface
    private interface Handler {
        Response handle();
    }
}
