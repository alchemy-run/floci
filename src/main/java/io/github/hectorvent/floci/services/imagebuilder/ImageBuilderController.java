package io.github.hectorvent.floci.services.imagebuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.imagebuilder.model.Component;
import io.github.hectorvent.floci.services.imagebuilder.model.DistributionConfiguration;
import io.github.hectorvent.floci.services.imagebuilder.model.ImageBuild;
import io.github.hectorvent.floci.services.imagebuilder.model.ImagePipeline;
import io.github.hectorvent.floci.services.imagebuilder.model.ImageRecipe;
import io.github.hectorvent.floci.services.imagebuilder.model.InfrastructureConfiguration;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * EC2 Image Builder restJson1.
 *
 * <p>Literal {@code /CreateComponent}, {@code /GetImagePipeline} and peer
 * paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all. Tag
 * APIs share {@code /tags/{arn}} and are dispatched by
 * {@code SharedTagsController}. Requests are signed as {@code imagebuilder}.
 * GET query params carry ARNs; PUT/POST bodies are JSON.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ImageBuilderController {

    private final ImageBuilderService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public ImageBuilderController(
            ImageBuilderService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @PUT
    @Path("/CreateComponent")
    public Response createComponent(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Component component = service.createComponent(region(headers), request);
            ObjectNode response = envelope();
            response.put("componentBuildVersionArn", component.getArn());
            putOptional(response, "clientToken", component.getClientToken());
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/GetComponent")
    @Consumes(MediaType.WILDCARD)
    public Response getComponent(
            @Context HttpHeaders headers, @QueryParam("componentBuildVersionArn") String arn) {
        return run(() -> {
            ObjectNode response = envelope();
            response.set("component", service.toComponent(service.getComponent(region(headers), arn)));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/DeleteComponent")
    @Consumes(MediaType.WILDCARD)
    public Response deleteComponent(
            @Context HttpHeaders headers, @QueryParam("componentBuildVersionArn") String arn) {
        return run(() -> {
            service.deleteComponent(region(headers), arn);
            return Response.ok(envelope()).build();
        });
    }

    @POST
    @Path("/ListComponents")
    @Consumes(MediaType.WILDCARD)
    public Response listComponents(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(service.listComponents(region(headers), request)).build());
    }

    @POST
    @Path("/ListComponentBuildVersions")
    @Consumes(MediaType.WILDCARD)
    public Response listComponentBuildVersions(@Context HttpHeaders headers, String body) {
        return handle(body, request ->
                Response.ok(service.listComponentBuildVersions(region(headers), request)).build());
    }

    @PUT
    @Path("/CreateImageRecipe")
    public Response createImageRecipe(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ImageRecipe recipe = service.createImageRecipe(region(headers), request);
            ObjectNode response = envelope();
            response.put("imageRecipeArn", recipe.getArn());
            putOptional(response, "clientToken", recipe.getClientToken());
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/GetImageRecipe")
    @Consumes(MediaType.WILDCARD)
    public Response getImageRecipe(
            @Context HttpHeaders headers, @QueryParam("imageRecipeArn") String arn) {
        return run(() -> {
            ObjectNode response = envelope();
            response.set("imageRecipe", service.toRecipe(service.getImageRecipe(region(headers), arn)));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/DeleteImageRecipe")
    @Consumes(MediaType.WILDCARD)
    public Response deleteImageRecipe(
            @Context HttpHeaders headers, @QueryParam("imageRecipeArn") String arn) {
        return run(() -> {
            service.deleteImageRecipe(region(headers), arn);
            return Response.ok(envelope()).build();
        });
    }

    @POST
    @Path("/ListImageRecipes")
    @Consumes(MediaType.WILDCARD)
    public Response listImageRecipes(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(service.listImageRecipes(region(headers), request)).build());
    }

    @PUT
    @Path("/CreateInfrastructureConfiguration")
    public Response createInfrastructureConfiguration(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            InfrastructureConfiguration config =
                    service.createInfrastructureConfiguration(region(headers), request);
            ObjectNode response = envelope();
            response.put("infrastructureConfigurationArn", config.getArn());
            putOptional(response, "clientToken", config.getClientToken());
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/GetInfrastructureConfiguration")
    @Consumes(MediaType.WILDCARD)
    public Response getInfrastructureConfiguration(
            @Context HttpHeaders headers, @QueryParam("infrastructureConfigurationArn") String arn) {
        return run(() -> {
            ObjectNode response = envelope();
            response.set("infrastructureConfiguration",
                    service.toInfrastructure(service.getInfrastructureConfiguration(region(headers), arn)));
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/UpdateInfrastructureConfiguration")
    public Response updateInfrastructureConfiguration(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            InfrastructureConfiguration config =
                    service.updateInfrastructureConfiguration(region(headers), request);
            ObjectNode response = envelope();
            response.put("infrastructureConfigurationArn", config.getArn());
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/DeleteInfrastructureConfiguration")
    @Consumes(MediaType.WILDCARD)
    public Response deleteInfrastructureConfiguration(
            @Context HttpHeaders headers, @QueryParam("infrastructureConfigurationArn") String arn) {
        return run(() -> {
            service.deleteInfrastructureConfiguration(region(headers), arn);
            return Response.ok(envelope()).build();
        });
    }

    @POST
    @Path("/ListInfrastructureConfigurations")
    @Consumes(MediaType.WILDCARD)
    public Response listInfrastructureConfigurations(@Context HttpHeaders headers, String body) {
        return handle(body, request ->
                Response.ok(service.listInfrastructureConfigurations(region(headers), request)).build());
    }

    @PUT
    @Path("/CreateDistributionConfiguration")
    public Response createDistributionConfiguration(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            DistributionConfiguration config =
                    service.createDistributionConfiguration(region(headers), request);
            ObjectNode response = envelope();
            response.put("distributionConfigurationArn", config.getArn());
            putOptional(response, "clientToken", config.getClientToken());
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/GetDistributionConfiguration")
    @Consumes(MediaType.WILDCARD)
    public Response getDistributionConfiguration(
            @Context HttpHeaders headers, @QueryParam("distributionConfigurationArn") String arn) {
        return run(() -> {
            ObjectNode response = envelope();
            response.set("distributionConfiguration",
                    service.toDistribution(service.getDistributionConfiguration(region(headers), arn)));
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/UpdateDistributionConfiguration")
    public Response updateDistributionConfiguration(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            DistributionConfiguration config =
                    service.updateDistributionConfiguration(region(headers), request);
            ObjectNode response = envelope();
            response.put("distributionConfigurationArn", config.getArn());
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/DeleteDistributionConfiguration")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDistributionConfiguration(
            @Context HttpHeaders headers, @QueryParam("distributionConfigurationArn") String arn) {
        return run(() -> {
            service.deleteDistributionConfiguration(region(headers), arn);
            return Response.ok(envelope()).build();
        });
    }

    @POST
    @Path("/ListDistributionConfigurations")
    @Consumes(MediaType.WILDCARD)
    public Response listDistributionConfigurations(@Context HttpHeaders headers, String body) {
        return handle(body, request ->
                Response.ok(service.listDistributionConfigurations(region(headers), request)).build());
    }

    @PUT
    @Path("/CreateImagePipeline")
    public Response createImagePipeline(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ImagePipeline pipeline = service.createImagePipeline(region(headers), request);
            ObjectNode response = envelope();
            response.put("imagePipelineArn", pipeline.getArn());
            putOptional(response, "clientToken", pipeline.getClientToken());
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/GetImagePipeline")
    @Consumes(MediaType.WILDCARD)
    public Response getImagePipeline(
            @Context HttpHeaders headers, @QueryParam("imagePipelineArn") String arn) {
        return run(() -> {
            ObjectNode response = envelope();
            response.set("imagePipeline", service.toPipeline(service.getImagePipeline(region(headers), arn)));
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/UpdateImagePipeline")
    public Response updateImagePipeline(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ImagePipeline pipeline = service.updateImagePipeline(region(headers), request);
            ObjectNode response = envelope();
            response.put("imagePipelineArn", pipeline.getArn());
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/DeleteImagePipeline")
    @Consumes(MediaType.WILDCARD)
    public Response deleteImagePipeline(
            @Context HttpHeaders headers, @QueryParam("imagePipelineArn") String arn) {
        return run(() -> {
            service.deleteImagePipeline(region(headers), arn);
            return Response.ok(envelope()).build();
        });
    }

    @POST
    @Path("/ListImagePipelines")
    @Consumes(MediaType.WILDCARD)
    public Response listImagePipelines(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(service.listImagePipelines(region(headers), request)).build());
    }

    @PUT
    @Path("/StartImagePipelineExecution")
    public Response startImagePipelineExecution(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ImageBuild image = service.startImagePipelineExecution(region(headers), request);
            ObjectNode response = envelope();
            response.put("imageBuildVersionArn", image.getArn());
            putOptional(response, "clientToken", image.getClientToken());
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/CancelImageCreation")
    public Response cancelImageCreation(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ImageBuild image = service.cancelImageCreation(region(headers), request);
            ObjectNode response = envelope();
            response.put("imageBuildVersionArn", image.getArn());
            putOptional(response, "clientToken", optionalText(request, "clientToken"));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/GetImage")
    @Consumes(MediaType.WILDCARD)
    public Response getImage(
            @Context HttpHeaders headers, @QueryParam("imageBuildVersionArn") String arn) {
        return run(() -> {
            ObjectNode response = envelope();
            response.set("image", service.toImage(service.getImage(region(headers), arn)));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/DeleteImage")
    @Consumes(MediaType.WILDCARD)
    public Response deleteImage(
            @Context HttpHeaders headers, @QueryParam("imageBuildVersionArn") String arn) {
        return run(() -> {
            service.deleteImage(region(headers), arn);
            return Response.ok(envelope()).build();
        });
    }

    @POST
    @Path("/ListImages")
    @Consumes(MediaType.WILDCARD)
    public Response listImages(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(service.listImages(region(headers), request)).build());
    }

    @POST
    @Path("/ListImagePipelineImages")
    @Consumes(MediaType.WILDCARD)
    public Response listImagePipelineImages(@Context HttpHeaders headers, String body) {
        return handle(body, request ->
                Response.ok(service.listImagePipelineImages(region(headers), request)).build());
    }

    @POST
    @Path("/ListImageBuildVersions")
    @Consumes(MediaType.WILDCARD)
    public Response listImageBuildVersions(@Context HttpHeaders headers, String body) {
        return handle(body, request ->
                Response.ok(service.listImageBuildVersions(region(headers), request)).build());
    }

    @POST
    @Path("/ListImagePackages")
    @Consumes(MediaType.WILDCARD)
    public Response listImagePackages(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(service.listImagePackages(region(headers), request)).build());
    }

    @POST
    @Path("/ListImageScanFindings")
    @Consumes(MediaType.WILDCARD)
    public Response listImageScanFindings(@Context HttpHeaders headers, String body) {
        return handle(body, request ->
                Response.ok(service.listImageScanFindings(region(headers), request)).build());
    }

    @POST
    @Path("/ListImageScanFindingAggregations")
    @Consumes(MediaType.WILDCARD)
    public Response listImageScanFindingAggregations(@Context HttpHeaders headers, String body) {
        return handle(body, request ->
                Response.ok(service.listImageScanFindingAggregations(region(headers), request)).build());
    }

    @POST
    @Path("/ListWaitingWorkflowSteps")
    @Consumes(MediaType.WILDCARD)
    public Response listWaitingWorkflowSteps(@Context HttpHeaders headers, String body) {
        return handle(body, request ->
                Response.ok(service.listWaitingWorkflowSteps(region(headers), request)).build());
    }

    @POST
    @Path("/ListWorkflowExecutions")
    @Consumes(MediaType.WILDCARD)
    public Response listWorkflowExecutions(@Context HttpHeaders headers, String body) {
        return handle(body, request ->
                Response.ok(service.listWorkflowExecutions(region(headers), request)).build());
    }

    @GET
    @Path("/GetWorkflowExecution")
    @Consumes(MediaType.WILDCARD)
    public Response getWorkflowExecution(
            @Context HttpHeaders headers, @QueryParam("workflowExecutionId") String id) {
        return run(() -> Response.ok(service.toWorkflowExecution(
                service.getWorkflowExecution(region(headers), id))).build());
    }

    @GET
    @Path("/GetWorkflowStepExecution")
    @Consumes(MediaType.WILDCARD)
    public Response getWorkflowStepExecution(
            @Context HttpHeaders headers, @QueryParam("stepExecutionId") String id) {
        return run(() -> Response.ok(service.toWorkflowStep(
                service.getWorkflowStepExecution(region(headers), id))).build());
    }

    @POST
    @Path("/ListWorkflowStepExecutions")
    @Consumes(MediaType.WILDCARD)
    public Response listWorkflowStepExecutions(@Context HttpHeaders headers, String body) {
        return handle(body, request ->
                Response.ok(service.listWorkflowStepExecutions(region(headers), request)).build());
    }

    @PUT
    @Path("/RetryImage")
    public Response retryImage(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ImageBuild image = service.retryImage(region(headers), request);
            ObjectNode response = envelope();
            response.put("imageBuildVersionArn", image.getArn());
            putOptional(response, "clientToken", optionalText(request, "clientToken"));
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/SendWorkflowStepAction")
    public Response sendWorkflowStepAction(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(service.sendWorkflowStepAction(region(headers), request)).build());
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private ObjectNode envelope() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("requestId", UUID.randomUUID().toString());
        return response;
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

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull() || !parent.get(field).isTextual()) {
            return null;
        }
        String text = parent.get(field).textValue();
        return text == null || text.isBlank() ? null : text;
    }

    private static void putOptional(ObjectNode parent, String field, String value) {
        if (value != null) {
            parent.put(field, value);
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
