package io.github.hectorvent.floci.services.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.schemas.model.CodeBinding;
import io.github.hectorvent.floci.services.schemas.model.Discoverer;
import io.github.hectorvent.floci.services.schemas.model.Registry;
import io.github.hectorvent.floci.services.schemas.model.Schema;
import io.github.hectorvent.floci.services.schemas.model.SchemaVersion;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * EventBridge Schema Registry (Smithy restJson1).
 *
 * <p>Literal {@code /v1/registries}, {@code /v1/discoverers}, and {@code /v1/policy}
 * paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all. Tag APIs share
 * {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SchemasController {

    private final SchemasService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public SchemasController(SchemasService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/v1/registries/name/{registryName}")
    public Response createRegistry(
            @Context HttpHeaders headers, @PathParam("registryName") String registryName, String body) {
        Registry registry = service.createRegistry(region(headers), registryName, parse(body));
        return Response.ok(registryNode(registry)).build();
    }

    @GET
    @Path("/v1/registries/name/{registryName}")
    @Consumes(MediaType.WILDCARD)
    public Response describeRegistry(
            @Context HttpHeaders headers, @PathParam("registryName") String registryName) {
        return Response.ok(registryNode(service.describeRegistry(region(headers), registryName))).build();
    }

    @PUT
    @Path("/v1/registries/name/{registryName}")
    public Response updateRegistry(
            @Context HttpHeaders headers, @PathParam("registryName") String registryName, String body) {
        Registry registry = service.updateRegistry(region(headers), registryName, parse(body));
        return Response.ok(registryNode(registry)).build();
    }

    @DELETE
    @Path("/v1/registries/name/{registryName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteRegistry(
            @Context HttpHeaders headers, @PathParam("registryName") String registryName) {
        service.deleteRegistry(region(headers), registryName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/v1/registries")
    @Consumes(MediaType.WILDCARD)
    public Response listRegistries(
            @Context HttpHeaders headers,
            @QueryParam("registryNamePrefix") String registryNamePrefix,
            @QueryParam("scope") String scope) {
        List<Registry> items = service.listRegistries(region(headers), registryNamePrefix, scope);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode registries = response.putArray("Registries");
        for (Registry registry : items) {
            ObjectNode item = registries.addObject();
            item.put("RegistryArn", registry.getRegistryArn());
            item.put("RegistryName", registry.getRegistryName());
            item.set("tags", tagsNode(registry.getTags()));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/policy")
    @Consumes(MediaType.WILDCARD)
    public Response getResourcePolicy(
            @Context HttpHeaders headers, @QueryParam("registryName") String registryName) {
        Registry registry = service.getResourcePolicy(region(headers), registryName);
        return Response.ok(policyNode(registry)).build();
    }

    @PUT
    @Path("/v1/policy")
    public Response putResourcePolicy(
            @Context HttpHeaders headers, @QueryParam("registryName") String registryName, String body) {
        Registry registry = service.putResourcePolicy(region(headers), registryName, parse(body));
        return Response.ok(policyNode(registry)).build();
    }

    @DELETE
    @Path("/v1/policy")
    @Consumes(MediaType.WILDCARD)
    public Response deleteResourcePolicy(
            @Context HttpHeaders headers, @QueryParam("registryName") String registryName) {
        service.deleteResourcePolicy(region(headers), registryName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/v1/registries/name/{registryName}/schemas/name/{schemaName}")
    public Response createSchema(
            @Context HttpHeaders headers,
            @PathParam("registryName") String registryName,
            @PathParam("schemaName") String schemaName,
            String body) {
        Schema schema = service.createSchema(region(headers), registryName, schemaName, parse(body));
        return Response.ok(schemaNode(schema, true)).build();
    }

    @GET
    @Path("/v1/registries/name/{registryName}/schemas/name/{schemaName}")
    @Consumes(MediaType.WILDCARD)
    public Response describeSchema(
            @Context HttpHeaders headers,
            @PathParam("registryName") String registryName,
            @PathParam("schemaName") String schemaName,
            @QueryParam("schemaVersion") String schemaVersion) {
        Schema schema = service.describeSchema(region(headers), registryName, schemaName, schemaVersion);
        return Response.ok(schemaNode(schema, true)).build();
    }

    @PUT
    @Path("/v1/registries/name/{registryName}/schemas/name/{schemaName}")
    public Response updateSchema(
            @Context HttpHeaders headers,
            @PathParam("registryName") String registryName,
            @PathParam("schemaName") String schemaName,
            String body) {
        Schema schema = service.updateSchema(region(headers), registryName, schemaName, parse(body));
        return Response.ok(schemaNode(schema, false)).build();
    }

    @DELETE
    @Path("/v1/registries/name/{registryName}/schemas/name/{schemaName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteSchema(
            @Context HttpHeaders headers,
            @PathParam("registryName") String registryName,
            @PathParam("schemaName") String schemaName) {
        service.deleteSchema(region(headers), registryName, schemaName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/v1/registries/name/{registryName}/schemas")
    @Consumes(MediaType.WILDCARD)
    public Response listSchemas(
            @Context HttpHeaders headers,
            @PathParam("registryName") String registryName,
            @QueryParam("schemaNamePrefix") String schemaNamePrefix) {
        List<Schema> items = service.listSchemas(region(headers), registryName, schemaNamePrefix);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode schemas = response.putArray("Schemas");
        for (Schema schema : items) {
            ObjectNode item = schemas.addObject();
            if (schema.getLastModified() != null) {
                item.put("LastModified", schema.getLastModified());
            }
            item.put("SchemaArn", schema.getSchemaArn());
            item.put("SchemaName", schema.getSchemaName());
            item.set("tags", tagsNode(schema.getTags()));
            item.put("VersionCount", schema.getVersions() == null ? 0 : schema.getVersions().size());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/registries/name/{registryName}/schemas/search")
    @Consumes(MediaType.WILDCARD)
    public Response searchSchemas(
            @Context HttpHeaders headers,
            @PathParam("registryName") String registryName,
            @QueryParam("keywords") String keywords) {
        List<Schema> items = service.searchSchemas(region(headers), registryName, keywords);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode schemas = response.putArray("Schemas");
        for (Schema schema : items) {
            ObjectNode item = schemas.addObject();
            item.put("RegistryName", schema.getRegistryName());
            item.put("SchemaArn", schema.getSchemaArn());
            item.put("SchemaName", schema.getSchemaName());
            ArrayNode versions = item.putArray("SchemaVersions");
            for (SchemaVersion version : schema.getVersions()) {
                ObjectNode v = versions.addObject();
                if (version.getCreatedDate() != null) {
                    v.put("CreatedDate", version.getCreatedDate());
                }
                v.put("SchemaVersion", version.getVersion());
                if (version.getType() != null) {
                    v.put("Type", version.getType());
                }
            }
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/registries/name/{registryName}/schemas/name/{schemaName}/versions")
    @Consumes(MediaType.WILDCARD)
    public Response listSchemaVersions(
            @Context HttpHeaders headers,
            @PathParam("registryName") String registryName,
            @PathParam("schemaName") String schemaName) {
        Schema schema = service.describeSchema(region(headers), registryName, schemaName, null);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode versions = response.putArray("SchemaVersions");
        for (SchemaVersion version : schema.getVersions()) {
            ObjectNode item = versions.addObject();
            item.put("SchemaArn", schema.getSchemaArn());
            item.put("SchemaName", schema.getSchemaName());
            item.put("SchemaVersion", version.getVersion());
            if (version.getType() != null) {
                item.put("Type", version.getType());
            }
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/registries/name/{registryName}/schemas/name/{schemaName}/export")
    @Consumes(MediaType.WILDCARD)
    public Response exportSchema(
            @Context HttpHeaders headers,
            @PathParam("registryName") String registryName,
            @PathParam("schemaName") String schemaName,
            @QueryParam("schemaVersion") String schemaVersion,
            @QueryParam("type") String type) {
        Schema schema = service.describeSchema(region(headers), registryName, schemaName, schemaVersion);
        service.exportSchema(region(headers), registryName, schemaName);
        SchemaVersion version = schema.latestVersion();
        ObjectNode response = objectMapper.createObjectNode();
        if (version != null && version.getContent() != null) {
            response.put("Content", version.getContent());
        }
        response.put("SchemaArn", schema.getSchemaArn());
        response.put("SchemaName", schema.getSchemaName());
        if (version != null) {
            response.put("SchemaVersion", version.getVersion());
            response.put("Type", type != null && !type.isBlank() ? type : version.getType());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/v1/registries/name/{registryName}/schemas/name/{schemaName}/language/{language}")
    @Consumes(MediaType.WILDCARD)
    public Response putCodeBinding(
            @Context HttpHeaders headers,
            @PathParam("registryName") String registryName,
            @PathParam("schemaName") String schemaName,
            @PathParam("language") String language,
            @QueryParam("schemaVersion") String schemaVersion) {
        CodeBinding binding = service.putCodeBinding(
                region(headers), registryName, schemaName, language, schemaVersion);
        return Response.ok(codeBindingNode(binding)).build();
    }

    @GET
    @Path("/v1/registries/name/{registryName}/schemas/name/{schemaName}/language/{language}")
    @Consumes(MediaType.WILDCARD)
    public Response describeCodeBinding(
            @Context HttpHeaders headers,
            @PathParam("registryName") String registryName,
            @PathParam("schemaName") String schemaName,
            @PathParam("language") String language,
            @QueryParam("schemaVersion") String schemaVersion) {
        CodeBinding binding = service.describeCodeBinding(
                region(headers), registryName, schemaName, language, schemaVersion);
        return Response.ok(codeBindingNode(binding)).build();
    }

    @GET
    @Path("/v1/registries/name/{registryName}/schemas/name/{schemaName}/language/{language}/source")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getCodeBindingSource(
            @Context HttpHeaders headers,
            @PathParam("registryName") String registryName,
            @PathParam("schemaName") String schemaName,
            @PathParam("language") String language,
            @QueryParam("schemaVersion") String schemaVersion) {
        byte[] source = service.getCodeBindingSource(
                region(headers), registryName, schemaName, language, schemaVersion);
        return Response.ok(source).type(MediaType.APPLICATION_OCTET_STREAM).build();
    }

    @POST
    @Path("/v1/discover")
    public Response getDiscoveredSchema(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        List<String> events = new ArrayList<>();
        JsonNode eventsNode = request.get("Events");
        if (eventsNode != null && eventsNode.isArray()) {
            for (JsonNode event : eventsNode) {
                events.add(event.isTextual() ? event.asText() : event.toString());
            }
        }
        String content = service.getDiscoveredSchema(text(request, "Type"), events);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Content", content);
        return Response.ok(response).build();
    }

    @POST
    @Path("/v1/discoverers")
    public Response createDiscoverer(@Context HttpHeaders headers, String body) {
        Discoverer discoverer = service.createDiscoverer(region(headers), parse(body));
        return Response.ok(discovererNode(discoverer)).build();
    }

    @GET
    @Path("/v1/discoverers")
    @Consumes(MediaType.WILDCARD)
    public Response listDiscoverers(
            @Context HttpHeaders headers,
            @QueryParam("discovererIdPrefix") String discovererIdPrefix,
            @QueryParam("sourceArnPrefix") String sourceArnPrefix) {
        List<Discoverer> items = service.listDiscoverers(region(headers), discovererIdPrefix, sourceArnPrefix);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode discoverers = response.putArray("Discoverers");
        for (Discoverer discoverer : items) {
            discoverers.add(discovererSummaryNode(discoverer));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/discoverers/id/{discovererId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeDiscoverer(
            @Context HttpHeaders headers, @PathParam("discovererId") String discovererId) {
        return Response.ok(discovererNode(service.describeDiscoverer(region(headers), discovererId))).build();
    }

    @PUT
    @Path("/v1/discoverers/id/{discovererId}")
    public Response updateDiscoverer(
            @Context HttpHeaders headers, @PathParam("discovererId") String discovererId, String body) {
        Discoverer discoverer = service.updateDiscoverer(region(headers), discovererId, parse(body));
        return Response.ok(discovererNode(discoverer)).build();
    }

    @DELETE
    @Path("/v1/discoverers/id/{discovererId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDiscoverer(
            @Context HttpHeaders headers, @PathParam("discovererId") String discovererId) {
        service.deleteDiscoverer(region(headers), discovererId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/v1/discoverers/id/{discovererId}/start")
    public Response startDiscoverer(
            @Context HttpHeaders headers, @PathParam("discovererId") String discovererId) {
        Discoverer discoverer = service.startDiscoverer(region(headers), discovererId);
        return Response.ok(discovererStateNode(discoverer)).build();
    }

    @POST
    @Path("/v1/discoverers/id/{discovererId}/stop")
    public Response stopDiscoverer(
            @Context HttpHeaders headers, @PathParam("discovererId") String discovererId) {
        Discoverer discoverer = service.stopDiscoverer(region(headers), discovererId);
        return Response.ok(discovererStateNode(discoverer)).build();
    }

    private ObjectNode registryNode(Registry registry) {
        ObjectNode node = objectMapper.createObjectNode();
        if (registry.getDescription() != null && !registry.getDescription().isBlank()) {
            node.put("Description", registry.getDescription());
        }
        node.put("RegistryArn", registry.getRegistryArn());
        node.put("RegistryName", registry.getRegistryName());
        node.set("tags", tagsNode(registry.getTags()));
        return node;
    }

    private ObjectNode schemaNode(Schema schema, boolean includeContent) {
        ObjectNode node = objectMapper.createObjectNode();
        SchemaVersion latest = schema.latestVersion();
        if (includeContent && latest != null && latest.getContent() != null) {
            node.put("Content", latest.getContent());
        }
        if (schema.getDescription() != null && !schema.getDescription().isBlank()) {
            node.put("Description", schema.getDescription());
        }
        if (schema.getLastModified() != null) {
            node.put("LastModified", schema.getLastModified());
        }
        node.put("SchemaArn", schema.getSchemaArn());
        node.put("SchemaName", schema.getSchemaName());
        if (latest != null) {
            node.put("SchemaVersion", latest.getVersion());
            if (latest.getType() != null) {
                node.put("Type", latest.getType());
            }
            if (latest.getCreatedDate() != null) {
                node.put("VersionCreatedDate", latest.getCreatedDate());
            }
        }
        node.set("tags", tagsNode(schema.getTags()));
        return node;
    }

    private ObjectNode policyNode(Registry registry) {
        ObjectNode node = objectMapper.createObjectNode();
        if (registry.getPolicy() != null) {
            node.put("Policy", registry.getPolicy());
        }
        if (registry.getPolicyRevisionId() != null) {
            node.put("RevisionId", registry.getPolicyRevisionId());
        }
        return node;
    }

    private ObjectNode discovererNode(Discoverer discoverer) {
        ObjectNode node = discovererSummaryNode(discoverer);
        if (discoverer.getDescription() != null) {
            node.put("Description", discoverer.getDescription());
        }
        return node;
    }

    private ObjectNode discovererSummaryNode(Discoverer discoverer) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("DiscovererId", discoverer.getDiscovererId());
        node.put("DiscovererArn", discoverer.getDiscovererArn());
        node.put("SourceArn", discoverer.getSourceArn());
        node.put("State", discoverer.getState());
        node.put("CrossAccount", discoverer.isCrossAccount());
        node.set("tags", tagsNode(discoverer.getTags()));
        return node;
    }

    private ObjectNode codeBindingNode(CodeBinding binding) {
        ObjectNode node = objectMapper.createObjectNode();
        if (binding.getCreationDate() != null) {
            node.put("CreationDate", binding.getCreationDate());
        }
        if (binding.getLastModified() != null) {
            node.put("LastModified", binding.getLastModified());
        }
        node.put("SchemaVersion", binding.getSchemaVersion());
        node.put("Status", binding.getStatus());
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.asText();
    }

    private ObjectNode discovererStateNode(Discoverer discoverer) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("DiscovererId", discoverer.getDiscovererId());
        node.put("State", discoverer.getState());
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
                throw new AwsException("BadRequestException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "Request body is not valid JSON.", 400);
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }
}
