package io.github.hectorvent.floci.services.resourceexplorer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.resourceexplorer.model.ExplorerIndex;
import io.github.hectorvent.floci.services.resourceexplorer.model.ExplorerView;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * AWS Resource Explorer 2 (Smithy restJson1).
 *
 * <p>{@link ResourceExplorerRoutingFilter} prefixes requests signed for
 * {@code resource-explorer-2} so paths such as {@code /CreateIndex} do not
 * collide with S3 Vectors. Tag APIs share {@code /tags/{arn}}.
 */
@Path(ResourceExplorerRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ResourceExplorerController {

    private final ResourceExplorerService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public ResourceExplorerController(
            ResourceExplorerService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/CreateIndex")
    @Consumes(MediaType.WILDCARD)
    public Response createIndex(@Context HttpHeaders headers, String body) {
        ExplorerIndex index = service.createIndex(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", index.getArn());
        response.put("State", index.getState());
        if (index.getCreatedAt() != null) {
            response.put("CreatedAt", index.getCreatedAt());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/GetIndex")
    @Consumes(MediaType.WILDCARD)
    public Response getIndex(@Context HttpHeaders headers, String body) {
        return Response.ok(indexNode(service.getIndex(region(headers)))).build();
    }

    @POST
    @Path("/DeleteIndex")
    public Response deleteIndex(@Context HttpHeaders headers, String body) {
        ExplorerIndex index = service.deleteIndex(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", index.getArn());
        response.put("State", "DELETED");
        if (index.getLastUpdatedAt() != null) {
            response.put("LastUpdatedAt", index.getLastUpdatedAt());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/UpdateIndexType")
    public Response updateIndexType(@Context HttpHeaders headers, String body) {
        ExplorerIndex index = service.updateIndexType(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", index.getArn());
        response.put("Type", index.getType());
        response.put("State", index.getState());
        if (index.getLastUpdatedAt() != null) {
            response.put("LastUpdatedAt", index.getLastUpdatedAt());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/CreateView")
    public Response createView(@Context HttpHeaders headers, String body) {
        ExplorerView view = service.createView(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("View", viewNode(view));
        return Response.ok(response).build();
    }

    @POST
    @Path("/GetView")
    public Response getView(@Context HttpHeaders headers, String body) {
        ExplorerView view = service.getView(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("View", viewNode(view));
        response.set("Tags", tagsNode(view.getTags()));
        return Response.ok(response).build();
    }

    @POST
    @Path("/UpdateView")
    public Response updateView(@Context HttpHeaders headers, String body) {
        ExplorerView view = service.updateView(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("View", viewNode(view));
        return Response.ok(response).build();
    }

    @POST
    @Path("/DeleteView")
    public Response deleteView(@Context HttpHeaders headers, String body) {
        ExplorerView view = service.deleteView(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ViewArn", view.getViewArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/ListViews")
    @Consumes(MediaType.WILDCARD)
    public Response listViews(@Context HttpHeaders headers, String body) {
        ResourceExplorerService.Page<String> page = service.listViews(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode views = response.putArray("Views");
        for (String arn : page.items()) {
            views.add(arn);
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/Search")
    public Response search(@Context HttpHeaders headers, String body) {
        return Response.ok(searchNode(service.search(region(headers), parse(body)))).build();
    }

    @POST
    @Path("/ListResources")
    @Consumes(MediaType.WILDCARD)
    public Response listResources(@Context HttpHeaders headers, String body) {
        ResourceExplorerService.SearchResult result = service.listResources(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Resources");
        if (result.viewArn() != null) {
            response.put("ViewArn", result.viewArn());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/ListSupportedResourceTypes")
    @Consumes(MediaType.WILDCARD)
    public Response listSupportedResourceTypes(String body) {
        ResourceExplorerService.Page<ResourceExplorerService.SupportedType> page =
                service.listSupportedResourceTypes(parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode types = response.putArray("ResourceTypes");
        for (ResourceExplorerService.SupportedType type : page.items()) {
            ObjectNode item = types.addObject();
            item.put("Service", type.service());
            item.put("ResourceType", type.resourceType());
            ArrayNode cfn = item.putArray("CFNResourceTypes");
            for (String cfnType : type.cfnResourceTypes()) {
                cfn.add(cfnType);
            }
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private ObjectNode indexNode(ExplorerIndex index) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", index.getArn());
        node.put("Type", index.getType());
        node.put("State", index.getState());
        if (index.getCreatedAt() != null) {
            node.put("CreatedAt", index.getCreatedAt());
        }
        if (index.getLastUpdatedAt() != null) {
            node.put("LastUpdatedAt", index.getLastUpdatedAt());
        }
        node.set("Tags", tagsNode(index.getTags()));
        return node;
    }

    private ObjectNode viewNode(ExplorerView view) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ViewArn", view.getViewArn());
        node.put("ViewName", view.getViewName());
        if (view.getOwner() != null) {
            node.put("Owner", view.getOwner());
        }
        if (view.getLastUpdatedAt() != null) {
            node.put("LastUpdatedAt", view.getLastUpdatedAt());
        }
        if (view.getScope() != null) {
            node.put("Scope", view.getScope());
        }
        List<String> included = view.getIncludedProperties();
        if (included != null && !included.isEmpty()) {
            ArrayNode properties = node.putArray("IncludedProperties");
            for (String name : included) {
                properties.addObject().put("Name", name);
            }
        }
        if (view.getFilterString() != null) {
            ObjectNode filters = node.putObject("Filters");
            filters.put("FilterString", view.getFilterString());
        }
        return node;
    }

    private ObjectNode searchNode(ResourceExplorerService.SearchResult result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.putArray("Resources");
        if (result.viewArn() != null) {
            node.put("ViewArn", result.viewArn());
        }
        ObjectNode count = node.putObject("Count");
        count.put("TotalResources", result.totalResources());
        count.put("Complete", result.complete());
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
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }
}
