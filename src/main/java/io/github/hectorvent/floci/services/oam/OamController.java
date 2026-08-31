package io.github.hectorvent.floci.services.oam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.oam.model.OamLink;
import io.github.hectorvent.floci.services.oam.model.OamSink;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * CloudWatch Observability Access Manager (Smithy restJson1).
 *
 * <p>Literal {@code /CreateSink}, {@code /GetSink}, {@code /ListAttachedLinks} and
 * peer paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all. Tag APIs
 * share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OamController {

    private final OamService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public OamController(OamService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/CreateSink")
    public Response createSink(@Context HttpHeaders headers, String body) {
        OamSink sink = service.createSink(region(headers), parse(body));
        return Response.ok(sinkNode(sink, true)).build();
    }

    @POST
    @Path("/GetSink")
    public Response getSink(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        OamSink sink = service.getSink(region(headers), request);
        boolean includeTags = request.path("IncludeTags").asBoolean(false);
        return Response.ok(sinkNode(sink, includeTags)).build();
    }

    @POST
    @Path("/DeleteSink")
    public Response deleteSink(@Context HttpHeaders headers, String body) {
        service.deleteSink(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/ListSinks")
    @Consumes(MediaType.WILDCARD)
    public Response listSinks(@Context HttpHeaders headers, String body) {
        OamService.Page<OamSink> page = service.listSinks(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Items");
        for (OamSink sink : page.items()) {
            ObjectNode item = items.addObject();
            item.put("Arn", sink.getArn());
            item.put("Id", sink.getId());
            item.put("Name", sink.getName());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/GetSinkPolicy")
    public Response getSinkPolicy(@Context HttpHeaders headers, String body) {
        return Response.ok(policyNode(service.getSinkPolicy(region(headers), parse(body)))).build();
    }

    @POST
    @Path("/PutSinkPolicy")
    public Response putSinkPolicy(@Context HttpHeaders headers, String body) {
        return Response.ok(policyNode(service.putSinkPolicy(region(headers), parse(body)))).build();
    }

    @POST
    @Path("/ListAttachedLinks")
    public Response listAttachedLinks(@Context HttpHeaders headers, String body) {
        OamService.Page<OamLink> page = service.listAttachedLinks(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Items");
        for (OamLink link : page.items()) {
            ObjectNode item = items.addObject();
            if (link.getLabel() != null) {
                item.put("Label", link.getLabel());
            }
            item.put("LinkArn", link.getArn());
            ArrayNode types = item.putArray("ResourceTypes");
            for (String type : link.getResourceTypes()) {
                types.add(type);
            }
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/CreateLink")
    public Response createLink(@Context HttpHeaders headers, String body) {
        return Response.ok(linkNode(service.createLink(region(headers), parse(body)), true)).build();
    }

    @POST
    @Path("/GetLink")
    public Response getLink(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        OamLink link = service.getLink(region(headers), request);
        boolean includeTags = request.path("IncludeTags").asBoolean(false);
        return Response.ok(linkNode(link, includeTags)).build();
    }

    @POST
    @Path("/DeleteLink")
    public Response deleteLink(@Context HttpHeaders headers, String body) {
        service.deleteLink(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/ListLinks")
    @Consumes(MediaType.WILDCARD)
    public Response listLinks(@Context HttpHeaders headers, String body) {
        OamService.Page<OamLink> page = service.listLinks(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Items");
        for (OamLink link : page.items()) {
            ObjectNode item = items.addObject();
            item.put("Arn", link.getArn());
            item.put("Id", link.getId());
            if (link.getLabel() != null) {
                item.put("Label", link.getLabel());
            }
            ArrayNode types = item.putArray("ResourceTypes");
            for (String type : link.getResourceTypes()) {
                types.add(type);
            }
            item.put("SinkArn", link.getSinkArn());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/UpdateLink")
    public Response updateLink(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        OamLink link = service.updateLink(region(headers), request);
        boolean includeTags = request.path("IncludeTags").asBoolean(false);
        return Response.ok(linkNode(link, includeTags)).build();
    }

    private ObjectNode sinkNode(OamSink sink, boolean includeTags) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", sink.getArn());
        node.put("Id", sink.getId());
        node.put("Name", sink.getName());
        if (includeTags) {
            node.set("Tags", tagsNode(sink.getTags()));
        }
        return node;
    }

    private ObjectNode policyNode(OamSink sink) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("SinkArn", sink.getArn());
        node.put("SinkId", sink.getId());
        if (sink.getPolicy() != null) {
            node.put("Policy", sink.getPolicy());
        }
        return node;
    }

    private ObjectNode linkNode(OamLink link, boolean includeTags) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", link.getArn());
        node.put("Id", link.getId());
        if (link.getLabel() != null) {
            node.put("Label", link.getLabel());
        }
        if (link.getLabelTemplate() != null) {
            node.put("LabelTemplate", link.getLabelTemplate());
        }
        ArrayNode types = node.putArray("ResourceTypes");
        for (String type : link.getResourceTypes()) {
            types.add(type);
        }
        node.put("SinkArn", link.getSinkArn());
        if (includeTags) {
            node.set("Tags", tagsNode(link.getTags()));
        }
        if (link.getLinkConfiguration() != null) {
            node.set("LinkConfiguration", link.getLinkConfiguration());
        }
        return node;
    }

    private ObjectNode tagsNode(Map<String, String> tags) {
        ObjectNode node = objectMapper.createObjectNode();
        if (tags != null) {
            tags.forEach(node::put);
        }
        return node;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("InvalidParameterException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterException", "Request body is not valid JSON.", 400);
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }
}
