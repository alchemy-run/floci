package io.github.hectorvent.floci.services.entityresolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.entityresolution.model.IdMappingJob;
import io.github.hectorvent.floci.services.entityresolution.model.IdMappingWorkflow;
import io.github.hectorvent.floci.services.entityresolution.model.IdNamespace;
import io.github.hectorvent.floci.services.entityresolution.model.MatchingJob;
import io.github.hectorvent.floci.services.entityresolution.model.MatchingWorkflow;
import io.github.hectorvent.floci.services.entityresolution.model.SchemaMapping;
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

import java.util.Map;

/**
 * AWS Entity Resolution restJson1.
 *
 * <p>Literal {@code /schemas}, {@code /matchingworkflows}, {@code /idnamespaces}
 * and {@code /idmappingworkflows} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} catch-all. Tag APIs share {@code /tags/{arn}} and are
 * dispatched by {@code SharedTagsController}. Requests are signed as
 * {@code entityresolution}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EntityResolutionController {

    private final EntityResolutionService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public EntityResolutionController(
            EntityResolutionService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/schemas")
    public Response createSchemaMapping(@Context HttpHeaders headers, String body) {
        SchemaMapping mapping = service.createSchemaMapping(region(headers), parse(body));
        return Response.ok(toSchemaCreate(mapping)).build();
    }

    @GET
    @Path("/schemas")
    @Consumes(MediaType.WILDCARD)
    public Response listSchemaMappings(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        EntityResolutionService.Page<SchemaMapping> page =
                service.listSchemaMappings(region(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("schemaList");
        for (SchemaMapping mapping : page.items()) {
            list.add(toSchemaSummary(mapping, region(headers)));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/schemas/{schemaName}")
    @Consumes(MediaType.WILDCARD)
    public Response getSchemaMapping(@Context HttpHeaders headers, @PathParam("schemaName") String schemaName) {
        String region = region(headers);
        SchemaMapping mapping = service.getSchemaMapping(region, schemaName);
        return Response.ok(toSchemaGet(mapping, region)).build();
    }

    @PUT
    @Path("/schemas/{schemaName}")
    public Response updateSchemaMapping(
            @Context HttpHeaders headers, @PathParam("schemaName") String schemaName, String body) {
        SchemaMapping mapping = service.updateSchemaMapping(region(headers), schemaName, parse(body));
        return Response.ok(toSchemaCreate(mapping)).build();
    }

    @DELETE
    @Path("/schemas/{schemaName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteSchemaMapping(@Context HttpHeaders headers, @PathParam("schemaName") String schemaName) {
        service.deleteSchemaMapping(region(headers), schemaName);
        return Response.ok(message("Schema mapping deleted.")).build();
    }

    @POST
    @Path("/matchingworkflows")
    public Response createMatchingWorkflow(@Context HttpHeaders headers, String body) {
        return Response.ok(toMatchingCreate(service.createMatchingWorkflow(region(headers), parse(body)))).build();
    }

    @GET
    @Path("/matchingworkflows")
    @Consumes(MediaType.WILDCARD)
    public Response listMatchingWorkflows(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        EntityResolutionService.Page<MatchingWorkflow> page =
                service.listMatchingWorkflows(region(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("workflowSummaries");
        for (MatchingWorkflow workflow : page.items()) {
            list.add(toMatchingSummary(workflow));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/matchingworkflows/{workflowName}")
    @Consumes(MediaType.WILDCARD)
    public Response getMatchingWorkflow(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName) {
        return Response.ok(toMatchingGet(service.getMatchingWorkflow(region(headers), workflowName))).build();
    }

    @PUT
    @Path("/matchingworkflows/{workflowName}")
    public Response updateMatchingWorkflow(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName, String body) {
        return Response.ok(toMatchingCreate(service.updateMatchingWorkflow(region(headers), workflowName, parse(body))))
                .build();
    }

    @DELETE
    @Path("/matchingworkflows/{workflowName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteMatchingWorkflow(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName) {
        service.deleteMatchingWorkflow(region(headers), workflowName);
        return Response.ok(message("Matching workflow deleted.")).build();
    }

    @GET
    @Path("/matchingworkflows/{workflowName}/jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listMatchingJobs(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode jobs = response.putArray("jobs");
        for (MatchingJob job : service.listMatchingJobs(region(headers), workflowName)) {
            jobs.add(toJobSummary(job));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/matchingworkflows/{workflowName}/jobs")
    @Consumes(MediaType.WILDCARD)
    public Response startMatchingJob(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName) {
        MatchingJob job = service.startMatchingJob(region(headers), workflowName);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobId", job.getJobId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/matchingworkflows/{workflowName}/jobs/{jobId}")
    @Consumes(MediaType.WILDCARD)
    public Response getMatchingJob(
            @Context HttpHeaders headers,
            @PathParam("workflowName") String workflowName,
            @PathParam("jobId") String jobId) {
        return Response.ok(toMatchingJob(service.getMatchingJob(region(headers), workflowName, jobId))).build();
    }

    @POST
    @Path("/matchingworkflows/{workflowName}/matches")
    public Response getMatchId(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName, String body) {
        return Response.ok(service.getMatchId(region(headers), workflowName, parse(body))).build();
    }

    @POST
    @Path("/matchingworkflows/{workflowName}/generateMatches")
    public Response generateMatchId(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName, String body) {
        return Response.ok(service.generateMatchId(region(headers), workflowName, parse(body))).build();
    }

    @DELETE
    @Path("/matchingworkflows/{workflowName}/uniqueids")
    @Consumes(MediaType.WILDCARD)
    public Response batchDeleteUniqueId(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName, String body) {
        return Response.ok(service.batchDeleteUniqueId(region(headers), workflowName, headers, parse(body))).build();
    }

    @POST
    @Path("/idnamespaces")
    public Response createIdNamespace(@Context HttpHeaders headers, String body) {
        return Response.ok(toNamespace(service.createIdNamespace(region(headers), parse(body)), false)).build();
    }

    @GET
    @Path("/idnamespaces")
    @Consumes(MediaType.WILDCARD)
    public Response listIdNamespaces(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        EntityResolutionService.Page<IdNamespace> page =
                service.listIdNamespaces(region(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("idNamespaceSummaries");
        for (IdNamespace namespace : page.items()) {
            list.add(toNamespaceSummary(namespace));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/idnamespaces/{idNamespaceName}")
    @Consumes(MediaType.WILDCARD)
    public Response getIdNamespace(
            @Context HttpHeaders headers, @PathParam("idNamespaceName") String idNamespaceName) {
        return Response.ok(toNamespace(service.getIdNamespace(region(headers), idNamespaceName), true)).build();
    }

    @PUT
    @Path("/idnamespaces/{idNamespaceName}")
    public Response updateIdNamespace(
            @Context HttpHeaders headers, @PathParam("idNamespaceName") String idNamespaceName, String body) {
        return Response.ok(toNamespace(service.updateIdNamespace(region(headers), idNamespaceName, parse(body)), false))
                .build();
    }

    @DELETE
    @Path("/idnamespaces/{idNamespaceName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteIdNamespace(
            @Context HttpHeaders headers, @PathParam("idNamespaceName") String idNamespaceName) {
        service.deleteIdNamespace(region(headers), idNamespaceName);
        return Response.ok(message("ID namespace deleted.")).build();
    }

    @POST
    @Path("/idmappingworkflows")
    public Response createIdMappingWorkflow(@Context HttpHeaders headers, String body) {
        return Response.ok(toIdMappingCreate(service.createIdMappingWorkflow(region(headers), parse(body)))).build();
    }

    @GET
    @Path("/idmappingworkflows")
    @Consumes(MediaType.WILDCARD)
    public Response listIdMappingWorkflows(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        EntityResolutionService.Page<IdMappingWorkflow> page =
                service.listIdMappingWorkflows(region(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("workflowSummaries");
        for (IdMappingWorkflow workflow : page.items()) {
            list.add(toIdMappingSummary(workflow));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/idmappingworkflows/{workflowName}")
    @Consumes(MediaType.WILDCARD)
    public Response getIdMappingWorkflow(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName) {
        return Response.ok(toIdMappingGet(service.getIdMappingWorkflow(region(headers), workflowName))).build();
    }

    @PUT
    @Path("/idmappingworkflows/{workflowName}")
    public Response updateIdMappingWorkflow(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName, String body) {
        return Response.ok(
                        toIdMappingCreate(service.updateIdMappingWorkflow(region(headers), workflowName, parse(body))))
                .build();
    }

    @DELETE
    @Path("/idmappingworkflows/{workflowName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteIdMappingWorkflow(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName) {
        service.deleteIdMappingWorkflow(region(headers), workflowName);
        return Response.ok(message("ID mapping workflow deleted.")).build();
    }

    @GET
    @Path("/idmappingworkflows/{workflowName}/jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listIdMappingJobs(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode jobs = response.putArray("jobs");
        for (IdMappingJob job : service.listIdMappingJobs(region(headers), workflowName)) {
            jobs.add(toIdMappingJobSummary(job));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/idmappingworkflows/{workflowName}/jobs")
    @Consumes(MediaType.WILDCARD)
    public Response startIdMappingJob(
            @Context HttpHeaders headers, @PathParam("workflowName") String workflowName, String body) {
        IdMappingJob job = service.startIdMappingJob(region(headers), workflowName, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobId", job.getJobId());
        if (job.getOutputSourceConfig() != null) {
            response.set("outputSourceConfig", job.getOutputSourceConfig());
        }
        if (job.getJobType() != null) {
            response.put("jobType", job.getJobType());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/idmappingworkflows/{workflowName}/jobs/{jobId}")
    @Consumes(MediaType.WILDCARD)
    public Response getIdMappingJob(
            @Context HttpHeaders headers,
            @PathParam("workflowName") String workflowName,
            @PathParam("jobId") String jobId) {
        return Response.ok(toIdMappingJob(service.getIdMappingJob(region(headers), workflowName, jobId))).build();
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
                throw EntityResolutionService.validation("Request body must be a JSON object.");
            }
            return request;
        } catch (io.github.hectorvent.floci.core.common.AwsException e) {
            throw e;
        } catch (Exception e) {
            throw EntityResolutionService.validation("Request body is not valid JSON.");
        }
    }

    private ObjectNode toSchemaCreate(SchemaMapping mapping) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schemaName", mapping.getSchemaName());
        node.put("schemaArn", mapping.getSchemaArn());
        putOptional(node, "description", mapping.getDescription());
        set(node, "mappedInputFields", mapping.getMappedInputFields());
        return node;
    }

    private ObjectNode toSchemaGet(SchemaMapping mapping, String region) {
        ObjectNode node = toSchemaCreate(mapping);
        node.put("createdAt", mapping.getCreatedAt());
        node.put("updatedAt", mapping.getUpdatedAt());
        node.put("hasWorkflows", service.schemaHasWorkflows(region, mapping.getSchemaName()));
        putTags(node, mapping.getTags());
        return node;
    }

    private ObjectNode toSchemaSummary(SchemaMapping mapping, String region) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schemaName", mapping.getSchemaName());
        node.put("schemaArn", mapping.getSchemaArn());
        node.put("createdAt", mapping.getCreatedAt());
        node.put("updatedAt", mapping.getUpdatedAt());
        node.put("hasWorkflows", service.schemaHasWorkflows(region, mapping.getSchemaName()));
        return node;
    }

    private ObjectNode toMatchingCreate(MatchingWorkflow workflow) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workflowName", workflow.getWorkflowName());
        node.put("workflowArn", workflow.getWorkflowArn());
        putOptional(node, "description", workflow.getDescription());
        set(node, "inputSourceConfig", workflow.getInputSourceConfig());
        set(node, "outputSourceConfig", workflow.getOutputSourceConfig());
        set(node, "resolutionTechniques", workflow.getResolutionTechniques());
        set(node, "incrementalRunConfig", workflow.getIncrementalRunConfig());
        node.put("roleArn", workflow.getRoleArn());
        return node;
    }

    private ObjectNode toMatchingGet(MatchingWorkflow workflow) {
        ObjectNode node = toMatchingCreate(workflow);
        node.put("createdAt", workflow.getCreatedAt());
        node.put("updatedAt", workflow.getUpdatedAt());
        putTags(node, workflow.getTags());
        return node;
    }

    private ObjectNode toMatchingSummary(MatchingWorkflow workflow) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workflowName", workflow.getWorkflowName());
        node.put("workflowArn", workflow.getWorkflowArn());
        node.put("createdAt", workflow.getCreatedAt());
        node.put("updatedAt", workflow.getUpdatedAt());
        String resolutionType = "RULE_MATCHING";
        JsonNode techniques = workflow.getResolutionTechniques();
        if (techniques != null && techniques.hasNonNull("resolutionType")) {
            resolutionType = techniques.get("resolutionType").asText();
        }
        node.put("resolutionType", resolutionType);
        return node;
    }

    private ObjectNode toMatchingJob(MatchingJob job) {
        ObjectNode node = toJobSummary(job);
        set(node, "metrics", job.getMetrics());
        set(node, "errorDetails", job.getErrorDetails());
        set(node, "outputSourceConfig", job.getOutputSourceConfig());
        return node;
    }

    private ObjectNode toJobSummary(MatchingJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jobId", job.getJobId());
        node.put("status", job.getStatus());
        node.put("startTime", job.getStartTime());
        if (job.getEndTime() != null) {
            node.put("endTime", job.getEndTime());
        }
        return node;
    }

    private ObjectNode toNamespace(IdNamespace namespace, boolean includeTimestamps) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("idNamespaceName", namespace.getIdNamespaceName());
        node.put("idNamespaceArn", namespace.getIdNamespaceArn());
        putOptional(node, "description", namespace.getDescription());
        set(node, "inputSourceConfig", namespace.getInputSourceConfig());
        set(node, "idMappingWorkflowProperties", namespace.getIdMappingWorkflowProperties());
        node.put("type", namespace.getType());
        putOptional(node, "roleArn", namespace.getRoleArn());
        if (includeTimestamps) {
            node.put("createdAt", namespace.getCreatedAt());
            node.put("updatedAt", namespace.getUpdatedAt());
            putTags(node, namespace.getTags());
        }
        return node;
    }

    private ObjectNode toNamespaceSummary(IdNamespace namespace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("idNamespaceName", namespace.getIdNamespaceName());
        node.put("idNamespaceArn", namespace.getIdNamespaceArn());
        putOptional(node, "description", namespace.getDescription());
        node.put("type", namespace.getType());
        node.put("createdAt", namespace.getCreatedAt());
        node.put("updatedAt", namespace.getUpdatedAt());
        JsonNode properties = namespace.getIdMappingWorkflowProperties();
        if (properties != null && properties.isArray()) {
            ArrayNode metadata = node.putArray("idMappingWorkflowProperties");
            for (JsonNode property : properties) {
                ObjectNode item = metadata.addObject();
                if (property != null && property.hasNonNull("idMappingType")) {
                    item.put("idMappingType", property.get("idMappingType").asText());
                }
            }
        }
        return node;
    }

    private ObjectNode toIdMappingCreate(IdMappingWorkflow workflow) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workflowName", workflow.getWorkflowName());
        node.put("workflowArn", workflow.getWorkflowArn());
        putOptional(node, "description", workflow.getDescription());
        set(node, "inputSourceConfig", workflow.getInputSourceConfig());
        set(node, "outputSourceConfig", workflow.getOutputSourceConfig());
        set(node, "idMappingTechniques", workflow.getIdMappingTechniques());
        set(node, "incrementalRunConfig", workflow.getIncrementalRunConfig());
        putOptional(node, "roleArn", workflow.getRoleArn());
        return node;
    }

    private ObjectNode toIdMappingGet(IdMappingWorkflow workflow) {
        ObjectNode node = toIdMappingCreate(workflow);
        node.put("createdAt", workflow.getCreatedAt());
        node.put("updatedAt", workflow.getUpdatedAt());
        putTags(node, workflow.getTags());
        return node;
    }

    private ObjectNode toIdMappingSummary(IdMappingWorkflow workflow) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workflowName", workflow.getWorkflowName());
        node.put("workflowArn", workflow.getWorkflowArn());
        node.put("createdAt", workflow.getCreatedAt());
        node.put("updatedAt", workflow.getUpdatedAt());
        return node;
    }

    private ObjectNode toIdMappingJob(IdMappingJob job) {
        ObjectNode node = toIdMappingJobSummary(job);
        set(node, "metrics", job.getMetrics());
        set(node, "errorDetails", job.getErrorDetails());
        set(node, "outputSourceConfig", job.getOutputSourceConfig());
        putOptional(node, "jobType", job.getJobType());
        return node;
    }

    private ObjectNode toIdMappingJobSummary(IdMappingJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jobId", job.getJobId());
        node.put("status", job.getStatus());
        node.put("startTime", job.getStartTime());
        if (job.getEndTime() != null) {
            node.put("endTime", job.getEndTime());
        }
        return node;
    }

    private ObjectNode message(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("message", message);
        return node;
    }

    private void set(ObjectNode parent, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            parent.set(field, value);
        }
    }

    private static void putOptional(ObjectNode parent, String field, String value) {
        if (value != null) {
            parent.put(field, value);
        }
    }

    private static void putTags(ObjectNode parent, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        ObjectNode node = parent.putObject("tags");
        tags.forEach(node::put);
    }
}
