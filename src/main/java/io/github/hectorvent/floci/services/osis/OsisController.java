package io.github.hectorvent.floci.services.osis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.osis.model.OsisPipeline;
import io.github.hectorvent.floci.services.osis.model.OsisPipelineEndpoint;
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
import java.util.Map;

/**
 * Amazon OpenSearch Ingestion (OSIS) restJson1.
 *
 * <p>Literal {@code /2022-01-01/osis/...} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} catch-all.
 */
@Path("/2022-01-01/osis")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OsisController {

    private final OsisService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public OsisController(OsisService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/createPipeline")
    public Response createPipeline(@Context HttpHeaders headers, String body) {
        OsisPipeline pipeline = service.createPipeline(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Pipeline", pipelineNode(pipeline));
        return Response.ok(response).build();
    }

    @GET
    @Path("/getPipeline/{pipelineName}")
    @Consumes(MediaType.WILDCARD)
    public Response getPipeline(@Context HttpHeaders headers, @PathParam("pipelineName") String pipelineName) {
        OsisPipeline pipeline = service.getPipeline(region(headers), pipelineName);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Pipeline", pipelineNode(pipeline));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/updatePipeline/{pipelineName}")
    public Response updatePipeline(
            @Context HttpHeaders headers, @PathParam("pipelineName") String pipelineName, String body) {
        OsisPipeline pipeline = service.updatePipeline(region(headers), pipelineName, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Pipeline", pipelineNode(pipeline));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/deletePipeline/{pipelineName}")
    @Consumes(MediaType.WILDCARD)
    public Response deletePipeline(@Context HttpHeaders headers, @PathParam("pipelineName") String pipelineName) {
        service.deletePipeline(region(headers), pipelineName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/listPipelines")
    @Consumes(MediaType.WILDCARD)
    public Response listPipelines(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Pipelines");
        for (OsisPipeline pipeline : service.listPipelines(region(headers))) {
            list.add(pipelineSummary(pipeline));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/listTagsForResource")
    @Consumes(MediaType.WILDCARD)
    public Response listTagsForResource(@Context HttpHeaders headers, @QueryParam("arn") String arn) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tags = response.putArray("Tags");
        for (Map.Entry<String, String> tag : service.listTags(region(headers), arn)) {
            ObjectNode item = tags.addObject();
            item.put("Key", tag.getKey());
            item.put("Value", tag.getValue());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/tagResource")
    public Response tagResource(@Context HttpHeaders headers, @QueryParam("arn") String arn, String body) {
        service.tagResource(region(headers), arn, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/untagResource")
    public Response untagResource(@Context HttpHeaders headers, @QueryParam("arn") String arn, String body) {
        service.untagResource(region(headers), arn, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/resourcePolicy/{resourceArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getResourcePolicy(
            @Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn) {
        Map<String, String> policy = service.getResourcePolicy(region(headers), decode(resourceArn));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ResourceArn", policy.get("ResourceArn"));
        response.put("Policy", policy.get("Policy"));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/resourcePolicy/{resourceArn:.+}")
    public Response putResourcePolicy(
            @Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn, String body) {
        Map<String, String> policy = service.putResourcePolicy(region(headers), decode(resourceArn), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ResourceArn", policy.get("ResourceArn"));
        response.put("Policy", policy.get("Policy"));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/resourcePolicy/{resourceArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteResourcePolicy(
            @Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn) {
        service.deleteResourcePolicy(region(headers), decode(resourceArn));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/createPipelineEndpoint")
    public Response createPipelineEndpoint(@Context HttpHeaders headers, String body) {
        OsisPipelineEndpoint endpoint = service.createPipelineEndpoint(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("PipelineArn", endpoint.getPipelineArn());
        response.put("EndpointId", endpoint.getEndpointId());
        response.put("Status", endpoint.getStatus());
        response.put("VpcId", endpoint.getVpcId());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/deletePipelineEndpoint/{endpointId}")
    @Consumes(MediaType.WILDCARD)
    public Response deletePipelineEndpoint(
            @Context HttpHeaders headers, @PathParam("endpointId") String endpointId) {
        service.deletePipelineEndpoint(region(headers), endpointId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/listPipelineEndpoints")
    @Consumes(MediaType.WILDCARD)
    public Response listPipelineEndpoints(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("PipelineEndpoints");
        for (OsisPipelineEndpoint endpoint : service.listPipelineEndpoints(region(headers))) {
            list.add(endpointNode(endpoint));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/validatePipeline")
    public Response validatePipeline(String body) {
        return Response.ok(service.validatePipeline(parse(body))).build();
    }

    @POST
    @Path("/listPipelineBlueprints")
    @Consumes(MediaType.WILDCARD)
    public Response listPipelineBlueprints() {
        return Response.ok(service.listPipelineBlueprints()).build();
    }

    @GET
    @Path("/getPipelineBlueprint/{blueprintName}")
    @Consumes(MediaType.WILDCARD)
    public Response getPipelineBlueprint(
            @PathParam("blueprintName") String blueprintName,
            @QueryParam("format") String format) {
        return Response.ok(service.getPipelineBlueprint(blueprintName, format)).build();
    }

    @GET
    @Path("/listPipelineEndpointConnections")
    @Consumes(MediaType.WILDCARD)
    public Response listPipelineEndpointConnections(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        return Response.ok(service.listPipelineEndpointConnections(region(headers), maxResults, nextToken)).build();
    }

    private ObjectNode pipelineNode(OsisPipeline pipeline) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("PipelineName", pipeline.getPipelineName());
        node.put("PipelineArn", pipeline.getPipelineArn());
        node.put("MinUnits", pipeline.getMinUnits());
        node.put("MaxUnits", pipeline.getMaxUnits());
        node.put("Status", pipeline.getStatus());
        node.put("PipelineConfigurationBody", pipeline.getPipelineConfigurationBody());
        node.put("CreatedAt", pipeline.getCreatedAt());
        node.put("LastUpdatedAt", pipeline.getLastUpdatedAt());
        ArrayNode urls = node.putArray("IngestEndpointUrls");
        for (String url : pipeline.getIngestEndpointUrls()) {
            urls.add(url);
        }
        if (pipeline.getLogPublishingOptions() != null) {
            node.set("LogPublishingOptions", pipeline.getLogPublishingOptions());
        }
        if (pipeline.getVpcOptions() != null) {
            node.set("VpcOptions", pipeline.getVpcOptions());
        }
        if (pipeline.getBufferOptions() != null) {
            node.set("BufferOptions", pipeline.getBufferOptions());
        }
        if (pipeline.getEncryptionAtRestOptions() != null) {
            node.set("EncryptionAtRestOptions", pipeline.getEncryptionAtRestOptions());
        }
        if (pipeline.getPipelineRoleArn() != null) {
            node.put("PipelineRoleArn", pipeline.getPipelineRoleArn());
        }
        node.set("Tags", tagArray(pipeline.getTags()));
        return node;
    }

    private ObjectNode pipelineSummary(OsisPipeline pipeline) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Status", pipeline.getStatus());
        node.put("PipelineName", pipeline.getPipelineName());
        node.put("PipelineArn", pipeline.getPipelineArn());
        node.put("MinUnits", pipeline.getMinUnits());
        node.put("MaxUnits", pipeline.getMaxUnits());
        node.put("CreatedAt", pipeline.getCreatedAt());
        node.put("LastUpdatedAt", pipeline.getLastUpdatedAt());
        node.set("Tags", tagArray(pipeline.getTags()));
        return node;
    }

    private ObjectNode endpointNode(OsisPipelineEndpoint endpoint) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("PipelineArn", endpoint.getPipelineArn());
        node.put("EndpointId", endpoint.getEndpointId());
        node.put("Status", endpoint.getStatus());
        node.put("VpcId", endpoint.getVpcId());
        ObjectNode vpc = node.putObject("VpcOptions");
        ArrayNode subnets = vpc.putArray("SubnetIds");
        for (String id : endpoint.getSubnetIds()) {
            subnets.add(id);
        }
        ArrayNode groups = vpc.putArray("SecurityGroupIds");
        for (String id : endpoint.getSecurityGroupIds()) {
            groups.add(id);
        }
        if (endpoint.getIngestEndpointUrl() != null) {
            node.put("IngestEndpointUrl", endpoint.getIngestEndpointUrl());
        }
        return node;
    }

    private ArrayNode tagArray(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        if (tags != null) {
            tags.forEach((key, value) -> {
                ObjectNode item = array.addObject();
                item.put("Key", key);
                item.put("Value", value);
            });
        }
        return array;
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

    private static String decode(String value) {
        if (value == null || !value.contains("%")) {
            return value;
        }
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }
}
