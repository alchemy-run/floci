package io.github.hectorvent.floci.services.bedrockdataautomation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.Blueprint;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.DataAutomationLibrary;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.IngestionJobRecord;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.InvocationRecord;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.LibraryEntityRecord;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.ProjectRecord;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

import java.util.List;
import java.util.Map;

/**
 * Amazon Bedrock Data Automation restJson1 (service {@code bedrock-data-automation},
 * signing name {@code bedrock}).
 *
 * <p>Literal {@code /blueprints}, {@code /data-automation-libraries} and
 * {@code /data-automation-projects} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} catch-all. Tag APIs live on {@code /listTagsForResource},
 * {@code /tagResource} and {@code /untagResource}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockDataAutomationController {

    private final BedrockDataAutomationService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public BedrockDataAutomationController(
            BedrockDataAutomationService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @PUT
    @Path("/blueprints/")
    public Response createBlueprint(@Context HttpHeaders headers, String body) {
        Blueprint blueprint = service.createBlueprint(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("blueprint", toBlueprint(blueprint));
        return Response.ok(response).build();
    }

    @POST
    @Path("/blueprints/")
    @Consumes(MediaType.WILDCARD)
    public Response listBlueprints(@Context HttpHeaders headers, String body) {
        List<Blueprint> listed = service.listBlueprints(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("blueprints");
        for (Blueprint blueprint : listed) {
            summaries.add(toBlueprintSummary(blueprint));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/blueprints/{blueprintArn}/versions/")
    public Response createBlueprintVersion(
            @Context HttpHeaders headers, @PathParam("blueprintArn") String blueprintArn, String body) {
        Blueprint blueprint = service.createBlueprintVersion(
                regionResolver.resolveRegion(headers), blueprintArn, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("blueprint", toBlueprint(blueprint));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/blueprints/{blueprintArn}/copy-stage")
    public Response copyBlueprintStage(
            @Context HttpHeaders headers, @PathParam("blueprintArn") String blueprintArn, String body) {
        service.copyBlueprintStage(regionResolver.resolveRegion(headers), blueprintArn, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/blueprints/{blueprintArn: .+}/")
    @Consumes(MediaType.WILDCARD)
    public Response getBlueprint(
            @Context HttpHeaders headers, @PathParam("blueprintArn") String blueprintArn, String body) {
        Blueprint blueprint = service.getBlueprint(
                regionResolver.resolveRegion(headers), blueprintArn, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("blueprint", toBlueprint(blueprint));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/blueprints/{blueprintArn: .+}/")
    public Response updateBlueprint(
            @Context HttpHeaders headers, @PathParam("blueprintArn") String blueprintArn, String body) {
        Blueprint blueprint = service.updateBlueprint(
                regionResolver.resolveRegion(headers), blueprintArn, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("blueprint", toBlueprint(blueprint));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/blueprints/{blueprintArn: .+}/")
    @Consumes(MediaType.WILDCARD)
    public Response deleteBlueprint(
            @Context HttpHeaders headers,
            @PathParam("blueprintArn") String blueprintArn,
            @QueryParam("blueprintVersion") String blueprintVersion) {
        service.deleteBlueprint(regionResolver.resolveRegion(headers), blueprintArn, blueprintVersion);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/data-automation-libraries/")
    public Response createLibrary(@Context HttpHeaders headers, String body) {
        DataAutomationLibrary library = service.createLibrary(
                regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("libraryArn", library.getLibraryArn());
        response.put("status", library.getStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/data-automation-libraries/")
    @Consumes(MediaType.WILDCARD)
    public Response listLibraries(@Context HttpHeaders headers, String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode libraries = response.putArray("libraries");
        for (DataAutomationLibrary library : service.listLibraries(regionResolver.resolveRegion(headers))) {
            ObjectNode summary = libraries.addObject();
            summary.put("libraryArn", library.getLibraryArn());
            summary.put("libraryName", library.getLibraryName());
            summary.put("creationTime", library.getCreationTime());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/data-automation-libraries/{libraryArn}/library-ingestion-jobs/")
    @Consumes(MediaType.WILDCARD)
    public Response listIngestionJobs(@PathParam("libraryArn") String libraryArn, String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode jobs = response.putArray("jobs");
        for (IngestionJobRecord job : service.listIngestionJobs(libraryArn)) {
            ObjectNode summary = jobs.addObject();
            summary.put("jobArn", job.getJobArn());
            summary.put("jobStatus", job.getJobStatus());
            summary.put("entityType", job.getEntityType());
            summary.put("operationType", job.getOperationType());
            summary.put("creationTime", job.getCreationTime());
            if (job.getCompletionTime() != null) {
                summary.put("completionTime", job.getCompletionTime());
            }
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/data-automation-libraries/{libraryArn}/library-ingestion-jobs/")
    public Response invokeIngestion(
            @Context HttpHeaders headers, @PathParam("libraryArn") String libraryArn, String body) {
        IngestionJobRecord job = service.invokeIngestion(
                regionResolver.resolveRegion(headers), libraryArn, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobArn", job.getJobArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/data-automation-libraries/{libraryArn}/library-ingestion-jobs/{jobArn}")
    @Consumes(MediaType.WILDCARD)
    public Response getIngestionJob(@PathParam("jobArn") String jobArn, String body) {
        parse(body);
        IngestionJobRecord job = service.getIngestionJob(jobArn);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode node = response.putObject("job");
        node.put("jobArn", job.getJobArn());
        node.put("creationTime", job.getCreationTime());
        node.put("entityType", job.getEntityType());
        node.put("operationType", job.getOperationType());
        node.put("jobStatus", job.getJobStatus());
        node.putObject("outputConfiguration").put("s3Uri", job.getS3Uri());
        if (job.getCompletionTime() != null) {
            node.put("completionTime", job.getCompletionTime());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/data-automation-libraries/{libraryArn}/entityType/{entityType}/entities/")
    @Consumes(MediaType.WILDCARD)
    public Response listEntities(
            @PathParam("libraryArn") String libraryArn,
            @PathParam("entityType") String entityType,
            String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entities = response.putArray("entities");
        for (LibraryEntityRecord entity : service.listEntities(libraryArn, entityType)) {
            ObjectNode vocabulary = objectMapper.createObjectNode();
            vocabulary.put("entityId", entity.getEntityId());
            if (entity.getVocabulary() != null && entity.getVocabulary().has("language")) {
                vocabulary.set("language", entity.getVocabulary().get("language"));
            }
            vocabulary.put("lastModifiedTime", entity.getLastModifiedTime());
            ObjectNode item = entities.addObject();
            item.set("vocabulary", vocabulary);
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/data-automation-libraries/{libraryArn}/entityType/{entityType}/entities/{entityId}")
    @Consumes(MediaType.WILDCARD)
    public Response getEntity(
            @PathParam("libraryArn") String libraryArn,
            @PathParam("entityType") String entityType,
            @PathParam("entityId") String entityId,
            String body) {
        parse(body);
        LibraryEntityRecord entity = service.getEntity(libraryArn, entityType, entityId);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode vocabulary = entity.getVocabulary() == null
                ? objectMapper.createObjectNode()
                : entity.getVocabulary().deepCopy();
        if (vocabulary instanceof ObjectNode objectNode) {
            objectNode.put("entityId", entity.getEntityId());
            objectNode.put("lastModifiedTime", entity.getLastModifiedTime());
        }
        response.set("entity", objectMapper.createObjectNode().set("vocabulary", vocabulary));
        return Response.ok(response).build();
    }

    @POST
    @Path("/data-automation-libraries/{libraryArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getLibrary(@PathParam("libraryArn") String libraryArn) {
        DataAutomationLibrary library = service.getLibrary(libraryArn);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode node = response.putObject("library");
        node.put("libraryArn", library.getLibraryArn());
        node.put("creationTime", library.getCreationTime());
        node.put("libraryName", library.getLibraryName());
        if (library.getLibraryDescription() != null) {
            node.put("libraryDescription", library.getLibraryDescription());
        }
        node.put("status", library.getStatus());
        if (library.getKmsKeyId() != null) {
            node.put("kmsKeyId", library.getKmsKeyId());
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/data-automation-libraries/{libraryArn: .+}")
    public Response updateLibrary(@PathParam("libraryArn") String libraryArn, String body) {
        DataAutomationLibrary library = service.updateLibrary(libraryArn, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("libraryArn", library.getLibraryArn());
        response.put("status", library.getStatus());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/data-automation-libraries/{libraryArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteLibrary(@PathParam("libraryArn") String libraryArn) {
        DataAutomationLibrary library = service.deleteLibrary(libraryArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("libraryArn", library.getLibraryArn());
        response.put("status", library.getStatus());
        return Response.ok(response).build();
    }

    @PUT
    @Path("/data-automation-projects/")
    public Response createProject(@Context HttpHeaders headers, String body) {
        ProjectRecord project = service.createProject(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("projectArn", project.getProjectArn());
        response.put("projectStage", project.getProjectStage());
        response.put("status", project.getStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/data-automation-projects/")
    @Consumes(MediaType.WILDCARD)
    public Response listProjects(@Context HttpHeaders headers, String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode projects = response.putArray("projects");
        for (ProjectRecord project : service.listProjects(regionResolver.resolveRegion(headers))) {
            projects.add(toProjectSummary(project));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/data-automation-projects/{projectArn: .+}/")
    @Consumes(MediaType.WILDCARD)
    public Response getProject(@PathParam("projectArn") String projectArn, String body) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("project", toProject(service.getProject(projectArn, parse(body))));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/data-automation-projects/{projectArn: .+}/")
    public Response updateProject(@PathParam("projectArn") String projectArn, String body) {
        ProjectRecord project = service.updateProject(projectArn, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("projectArn", project.getProjectArn());
        response.put("projectStage", project.getProjectStage());
        response.put("status", project.getStatus());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/data-automation-projects/{projectArn: .+}/")
    @Consumes(MediaType.WILDCARD)
    public Response deleteProject(@PathParam("projectArn") String projectArn) {
        ProjectRecord project = service.deleteProject(projectArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("projectArn", project.getProjectArn());
        response.put("status", project.getStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/invokeBlueprintOptimizationAsync")
    public Response invokeOptimization(@Context HttpHeaders headers, String body) {
        InvocationRecord invocation = service.invokeOptimization(
                regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("invocationArn", invocation.getInvocationArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/getBlueprintOptimizationStatus/{invocationArn}")
    @Consumes(MediaType.WILDCARD)
    public Response getOptimizationStatus(@PathParam("invocationArn") String invocationArn, String body) {
        parse(body);
        InvocationRecord invocation = service.getOptimization(invocationArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", invocation.getStatus());
        if (invocation.getOutputS3Uri() != null) {
            response.putObject("outputConfiguration").putObject("s3Object").put("s3Uri", invocation.getOutputS3Uri());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/listTagsForResource")
    public Response listTagsForResource(String body) {
        JsonNode request = parse(body);
        Map<String, String> tags = service.listTags(BedrockDataAutomationService.resourceArn(request));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("tags");
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = list.addObject();
            tag.put("key", entry.getKey());
            tag.put("value", entry.getValue() == null ? "" : entry.getValue());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/tagResource")
    public Response tagResource(String body) {
        JsonNode request = parse(body);
        service.tagResource(BedrockDataAutomationService.resourceArn(request), request);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/untagResource")
    public Response untagResource(String body) {
        JsonNode request = parse(body);
        service.untagResource(BedrockDataAutomationService.resourceArn(request), request);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode toBlueprint(Blueprint blueprint) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("blueprintArn", blueprint.getBlueprintArn());
        node.put("schema", blueprint.getSchema());
        node.put("type", blueprint.getType());
        node.put("creationTime", blueprint.getCreationTime());
        node.put("lastModifiedTime", blueprint.getLastModifiedTime());
        node.put("blueprintName", blueprint.getBlueprintName());
        if (blueprint.getBlueprintVersion() != null) {
            node.put("blueprintVersion", blueprint.getBlueprintVersion());
        }
        if (blueprint.getBlueprintStage() != null) {
            node.put("blueprintStage", blueprint.getBlueprintStage());
        }
        if (blueprint.getKmsKeyId() != null) {
            node.put("kmsKeyId", blueprint.getKmsKeyId());
        }
        if (blueprint.getKmsEncryptionContext() != null && !blueprint.getKmsEncryptionContext().isEmpty()) {
            ObjectNode context = node.putObject("kmsEncryptionContext");
            blueprint.getKmsEncryptionContext().forEach(context::put);
        }
        return node;
    }

    private ObjectNode toProject(ProjectRecord project) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("projectArn", project.getProjectArn());
        node.put("creationTime", project.getCreationTime());
        node.put("lastModifiedTime", project.getLastModifiedTime());
        node.put("projectName", project.getProjectName());
        if (project.getProjectStage() != null) {
            node.put("projectStage", project.getProjectStage());
        }
        if (project.getProjectType() != null) {
            node.put("projectType", project.getProjectType());
        }
        if (project.getProjectDescription() != null) {
            node.put("projectDescription", project.getProjectDescription());
        }
        if (project.getStandardOutputConfiguration() != null) {
            node.set("standardOutputConfiguration", project.getStandardOutputConfiguration());
        }
        if (project.getCustomOutputConfiguration() != null) {
            node.set("customOutputConfiguration", project.getCustomOutputConfiguration());
        }
        if (project.getOverrideConfiguration() != null) {
            node.set("overrideConfiguration", project.getOverrideConfiguration());
        }
        if (project.getDataAutomationLibraryConfiguration() != null) {
            node.set("dataAutomationLibraryConfiguration", project.getDataAutomationLibraryConfiguration());
        }
        node.put("status", project.getStatus());
        if (project.getKmsKeyId() != null) {
            node.put("kmsKeyId", project.getKmsKeyId());
        }
        if (project.getKmsEncryptionContext() != null) {
            node.set("kmsEncryptionContext", project.getKmsEncryptionContext());
        }
        return node;
    }

    private ObjectNode toProjectSummary(ProjectRecord project) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("projectArn", project.getProjectArn());
        if (project.getProjectStage() != null) {
            node.put("projectStage", project.getProjectStage());
        }
        if (project.getProjectType() != null) {
            node.put("projectType", project.getProjectType());
        }
        if (project.getProjectName() != null) {
            node.put("projectName", project.getProjectName());
        }
        node.put("creationTime", project.getCreationTime());
        return node;
    }

    private ObjectNode toBlueprintSummary(Blueprint blueprint) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("blueprintArn", blueprint.getBlueprintArn());
        if (blueprint.getBlueprintVersion() != null) {
            node.put("blueprintVersion", blueprint.getBlueprintVersion());
        }
        if (blueprint.getBlueprintStage() != null) {
            node.put("blueprintStage", blueprint.getBlueprintStage());
        }
        if (blueprint.getBlueprintName() != null) {
            node.put("blueprintName", blueprint.getBlueprintName());
        }
        node.put("creationTime", blueprint.getCreationTime());
        if (blueprint.getLastModifiedTime() != null) {
            node.put("lastModifiedTime", blueprint.getLastModifiedTime());
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
}
