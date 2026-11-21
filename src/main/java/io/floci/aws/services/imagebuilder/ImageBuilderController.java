package io.floci.aws.services.imagebuilder;

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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The EC2 Image Builder REST API, routed here by {@link ImageBuilderRouteFilter}: operations at
 * {@code /<Operation>} with a JSON body ({@code PUT} and {@code POST}) or query parameters ({@code GET} and
 * {@code DELETE}), and the tag routes. Errors carry {@code message} and {@code x-amzn-ErrorType}.
 */
@Path(ImageBuilderRouteFilter.PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class ImageBuilderController {

    /** Image Builder's request constraints, reported as InvalidParameterValueException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/imagebuilder-request-shapes.json", "Image Builder request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return ImageBuilderStore.invalidParameter(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : "list".equals(type) || "map".equals(type)
                            ? "size" : "value";
                    return ImageBuilderStore.invalidParameter(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return ImageBuilderStore.invalidParameter(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return ImageBuilderStore.invalidParameter(path + " must be one of " + String.join(", ", values)
                            + ".");
                }

                @Override
                public AwsException required(String path) {
                    return new AwsException("InvalidRequestException", path + " is required.", 400);
                }
            });

    private final ImageBuilderService service;
    private final ImageBuilderImages images;
    private final ImageBuilderLifecycle lifecycle;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public ImageBuilderController(ImageBuilderService service, ImageBuilderImages images,
                                  ImageBuilderLifecycle lifecycle, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.images = images;
        this.lifecycle = lifecycle;
        this.regions = regions;
        this.mapper = mapper;
    }

    @PUT
    @Path("/CancelImageCreation")
    public Response cancelImageCreation(String body) {
        return call("CancelImageCreation", parse(body), in -> images.cancelImageCreation(in, region()));
    }

    @PUT
    @Path("/CancelLifecycleExecution")
    public Response cancelLifecycleExecution(String body) {
        return call("CancelLifecycleExecution", parse(body), in -> lifecycle.cancelLifecycleExecution(in, region()));
    }

    @PUT
    @Path("/CreateComponent")
    public Response createComponent(String body) {
        return call("CreateComponent", parse(body), in -> service.createComponent(in, region()));
    }

    @PUT
    @Path("/CreateContainerRecipe")
    public Response createContainerRecipe(String body) {
        return call("CreateContainerRecipe", parse(body), in -> service.createContainerRecipe(in, region()));
    }

    @PUT
    @Path("/CreateDistributionConfiguration")
    public Response createDistributionConfiguration(String body) {
        return call("CreateDistributionConfiguration", parse(body), in -> service.createDistributionConfiguration(in, region()));
    }

    @PUT
    @Path("/CreateImage")
    public Response createImage(String body) {
        return call("CreateImage", parse(body), in -> images.createImage(in, region()));
    }

    @PUT
    @Path("/CreateImagePipeline")
    public Response createImagePipeline(String body) {
        return call("CreateImagePipeline", parse(body), in -> service.createImagePipeline(in, region()));
    }

    @PUT
    @Path("/CreateImageRecipe")
    public Response createImageRecipe(String body) {
        return call("CreateImageRecipe", parse(body), in -> service.createImageRecipe(in, region()));
    }

    @PUT
    @Path("/CreateInfrastructureConfiguration")
    public Response createInfrastructureConfiguration(String body) {
        return call("CreateInfrastructureConfiguration", parse(body), in -> service.createInfrastructureConfiguration(in, region()));
    }

    @PUT
    @Path("/CreateLifecyclePolicy")
    public Response createLifecyclePolicy(String body) {
        return call("CreateLifecyclePolicy", parse(body), in -> service.createLifecyclePolicy(in, region()));
    }

    @PUT
    @Path("/CreateWorkflow")
    public Response createWorkflow(String body) {
        return call("CreateWorkflow", parse(body), in -> service.createWorkflow(in, region()));
    }

    @DELETE
    @Path("/DeleteComponent")
    public Response deleteComponent(@QueryParam("componentBuildVersionArn") String componentBuildVersionArn) {
        ObjectNode input = mapper.createObjectNode();
        if (componentBuildVersionArn != null) {
            input.put("componentBuildVersionArn", componentBuildVersionArn);
        }
        return call("DeleteComponent", input, in -> service.deleteComponent(in, region()));
    }

    @DELETE
    @Path("/DeleteContainerRecipe")
    public Response deleteContainerRecipe(@QueryParam("containerRecipeArn") String containerRecipeArn) {
        ObjectNode input = mapper.createObjectNode();
        if (containerRecipeArn != null) {
            input.put("containerRecipeArn", containerRecipeArn);
        }
        return call("DeleteContainerRecipe", input, in -> service.deleteContainerRecipe(in, region()));
    }

    @DELETE
    @Path("/DeleteDistributionConfiguration")
    public Response deleteDistributionConfiguration(@QueryParam("distributionConfigurationArn") String distributionConfigurationArn) {
        ObjectNode input = mapper.createObjectNode();
        if (distributionConfigurationArn != null) {
            input.put("distributionConfigurationArn", distributionConfigurationArn);
        }
        return call("DeleteDistributionConfiguration", input, in -> service.deleteDistributionConfiguration(in, region()));
    }

    @DELETE
    @Path("/DeleteImage")
    public Response deleteImage(@QueryParam("imageBuildVersionArn") String imageBuildVersionArn) {
        ObjectNode input = mapper.createObjectNode();
        if (imageBuildVersionArn != null) {
            input.put("imageBuildVersionArn", imageBuildVersionArn);
        }
        return call("DeleteImage", input, in -> images.deleteImage(in, region()));
    }

    @DELETE
    @Path("/DeleteImagePipeline")
    public Response deleteImagePipeline(@QueryParam("imagePipelineArn") String imagePipelineArn) {
        ObjectNode input = mapper.createObjectNode();
        if (imagePipelineArn != null) {
            input.put("imagePipelineArn", imagePipelineArn);
        }
        return call("DeleteImagePipeline", input, in -> service.deleteImagePipeline(in, region()));
    }

    @DELETE
    @Path("/DeleteImageRecipe")
    public Response deleteImageRecipe(@QueryParam("imageRecipeArn") String imageRecipeArn) {
        ObjectNode input = mapper.createObjectNode();
        if (imageRecipeArn != null) {
            input.put("imageRecipeArn", imageRecipeArn);
        }
        return call("DeleteImageRecipe", input, in -> service.deleteImageRecipe(in, region()));
    }

    @DELETE
    @Path("/DeleteInfrastructureConfiguration")
    public Response deleteInfrastructureConfiguration(@QueryParam("infrastructureConfigurationArn") String infrastructureConfigurationArn) {
        ObjectNode input = mapper.createObjectNode();
        if (infrastructureConfigurationArn != null) {
            input.put("infrastructureConfigurationArn", infrastructureConfigurationArn);
        }
        return call("DeleteInfrastructureConfiguration", input, in -> service.deleteInfrastructureConfiguration(in, region()));
    }

    @DELETE
    @Path("/DeleteLifecyclePolicy")
    public Response deleteLifecyclePolicy(@QueryParam("lifecyclePolicyArn") String lifecyclePolicyArn) {
        ObjectNode input = mapper.createObjectNode();
        if (lifecyclePolicyArn != null) {
            input.put("lifecyclePolicyArn", lifecyclePolicyArn);
        }
        return call("DeleteLifecyclePolicy", input, in -> service.deleteLifecyclePolicy(in, region()));
    }

    @DELETE
    @Path("/DeleteWorkflow")
    public Response deleteWorkflow(@QueryParam("workflowBuildVersionArn") String workflowBuildVersionArn) {
        ObjectNode input = mapper.createObjectNode();
        if (workflowBuildVersionArn != null) {
            input.put("workflowBuildVersionArn", workflowBuildVersionArn);
        }
        return call("DeleteWorkflow", input, in -> service.deleteWorkflow(in, region()));
    }

    @PUT
    @Path("/DistributeImage")
    public Response distributeImage(String body) {
        return call("DistributeImage", parse(body), in -> images.distributeImage(in, region()));
    }

    @GET
    @Path("/GetComponent")
    public Response getComponent(@QueryParam("componentBuildVersionArn") String componentBuildVersionArn) {
        ObjectNode input = mapper.createObjectNode();
        if (componentBuildVersionArn != null) {
            input.put("componentBuildVersionArn", componentBuildVersionArn);
        }
        return call("GetComponent", input, in -> service.getComponent(in, region()));
    }

    @GET
    @Path("/GetComponentPolicy")
    public Response getComponentPolicy(@QueryParam("componentArn") String componentArn) {
        ObjectNode input = mapper.createObjectNode();
        if (componentArn != null) {
            input.put("componentArn", componentArn);
        }
        return call("GetComponentPolicy", input, in -> service.getComponentPolicy(in, region()));
    }

    @GET
    @Path("/GetContainerRecipe")
    public Response getContainerRecipe(@QueryParam("containerRecipeArn") String containerRecipeArn) {
        ObjectNode input = mapper.createObjectNode();
        if (containerRecipeArn != null) {
            input.put("containerRecipeArn", containerRecipeArn);
        }
        return call("GetContainerRecipe", input, in -> service.getContainerRecipe(in, region()));
    }

    @GET
    @Path("/GetContainerRecipePolicy")
    public Response getContainerRecipePolicy(@QueryParam("containerRecipeArn") String containerRecipeArn) {
        ObjectNode input = mapper.createObjectNode();
        if (containerRecipeArn != null) {
            input.put("containerRecipeArn", containerRecipeArn);
        }
        return call("GetContainerRecipePolicy", input, in -> service.getContainerRecipePolicy(in, region()));
    }

    @GET
    @Path("/GetDistributionConfiguration")
    public Response getDistributionConfiguration(@QueryParam("distributionConfigurationArn") String distributionConfigurationArn) {
        ObjectNode input = mapper.createObjectNode();
        if (distributionConfigurationArn != null) {
            input.put("distributionConfigurationArn", distributionConfigurationArn);
        }
        return call("GetDistributionConfiguration", input, in -> service.getDistributionConfiguration(in, region()));
    }

    @GET
    @Path("/GetImage")
    public Response getImage(@QueryParam("imageBuildVersionArn") String imageBuildVersionArn) {
        ObjectNode input = mapper.createObjectNode();
        if (imageBuildVersionArn != null) {
            input.put("imageBuildVersionArn", imageBuildVersionArn);
        }
        return call("GetImage", input, in -> images.getImage(in, region()));
    }

    @GET
    @Path("/GetImagePipeline")
    public Response getImagePipeline(@QueryParam("imagePipelineArn") String imagePipelineArn) {
        ObjectNode input = mapper.createObjectNode();
        if (imagePipelineArn != null) {
            input.put("imagePipelineArn", imagePipelineArn);
        }
        return call("GetImagePipeline", input, in -> service.getImagePipeline(in, region()));
    }

    @GET
    @Path("/GetImagePolicy")
    public Response getImagePolicy(@QueryParam("imageArn") String imageArn) {
        ObjectNode input = mapper.createObjectNode();
        if (imageArn != null) {
            input.put("imageArn", imageArn);
        }
        return call("GetImagePolicy", input, in -> service.getImagePolicy(in, region()));
    }

    @GET
    @Path("/GetImageRecipe")
    public Response getImageRecipe(@QueryParam("imageRecipeArn") String imageRecipeArn) {
        ObjectNode input = mapper.createObjectNode();
        if (imageRecipeArn != null) {
            input.put("imageRecipeArn", imageRecipeArn);
        }
        return call("GetImageRecipe", input, in -> service.getImageRecipe(in, region()));
    }

    @GET
    @Path("/GetImageRecipePolicy")
    public Response getImageRecipePolicy(@QueryParam("imageRecipeArn") String imageRecipeArn) {
        ObjectNode input = mapper.createObjectNode();
        if (imageRecipeArn != null) {
            input.put("imageRecipeArn", imageRecipeArn);
        }
        return call("GetImageRecipePolicy", input, in -> service.getImageRecipePolicy(in, region()));
    }

    @GET
    @Path("/GetInfrastructureConfiguration")
    public Response getInfrastructureConfiguration(@QueryParam("infrastructureConfigurationArn") String infrastructureConfigurationArn) {
        ObjectNode input = mapper.createObjectNode();
        if (infrastructureConfigurationArn != null) {
            input.put("infrastructureConfigurationArn", infrastructureConfigurationArn);
        }
        return call("GetInfrastructureConfiguration", input, in -> service.getInfrastructureConfiguration(in, region()));
    }

    @GET
    @Path("/GetLifecycleExecution")
    public Response getLifecycleExecution(@QueryParam("lifecycleExecutionId") String lifecycleExecutionId) {
        ObjectNode input = mapper.createObjectNode();
        if (lifecycleExecutionId != null) {
            input.put("lifecycleExecutionId", lifecycleExecutionId);
        }
        return call("GetLifecycleExecution", input, in -> lifecycle.getLifecycleExecution(in, region()));
    }

    @GET
    @Path("/GetLifecyclePolicy")
    public Response getLifecyclePolicy(@QueryParam("lifecyclePolicyArn") String lifecyclePolicyArn) {
        ObjectNode input = mapper.createObjectNode();
        if (lifecyclePolicyArn != null) {
            input.put("lifecyclePolicyArn", lifecyclePolicyArn);
        }
        return call("GetLifecyclePolicy", input, in -> service.getLifecyclePolicy(in, region()));
    }

    @POST
    @Path("/GetMarketplaceResource")
    public Response getMarketplaceResource(String body) {
        return call("GetMarketplaceResource", parse(body), in -> service.getMarketplaceResource(in, region()));
    }

    @GET
    @Path("/GetWorkflow")
    public Response getWorkflow(@QueryParam("workflowBuildVersionArn") String workflowBuildVersionArn) {
        ObjectNode input = mapper.createObjectNode();
        if (workflowBuildVersionArn != null) {
            input.put("workflowBuildVersionArn", workflowBuildVersionArn);
        }
        return call("GetWorkflow", input, in -> service.getWorkflow(in, region()));
    }

    @GET
    @Path("/GetWorkflowExecution")
    public Response getWorkflowExecution(@QueryParam("workflowExecutionId") String workflowExecutionId) {
        ObjectNode input = mapper.createObjectNode();
        if (workflowExecutionId != null) {
            input.put("workflowExecutionId", workflowExecutionId);
        }
        return call("GetWorkflowExecution", input, in -> images.getWorkflowExecution(in, region()));
    }

    @GET
    @Path("/GetWorkflowStepExecution")
    public Response getWorkflowStepExecution(@QueryParam("stepExecutionId") String stepExecutionId) {
        ObjectNode input = mapper.createObjectNode();
        if (stepExecutionId != null) {
            input.put("stepExecutionId", stepExecutionId);
        }
        return call("GetWorkflowStepExecution", input, in -> images.getWorkflowStepExecution(in, region()));
    }

    @PUT
    @Path("/ImportComponent")
    public Response importComponent(String body) {
        return call("ImportComponent", parse(body), in -> service.importComponent(in, region()));
    }

    @PUT
    @Path("/ImportDiskImage")
    public Response importDiskImage(String body) {
        return call("ImportDiskImage", parse(body), in -> images.importDiskImage(in, region()));
    }

    @PUT
    @Path("/ImportVmImage")
    public Response importVmImage(String body) {
        return call("ImportVmImage", parse(body), in -> images.importVmImage(in, region()));
    }

    @POST
    @Path("/ListComponentBuildVersions")
    public Response listComponentBuildVersions(String body) {
        return call("ListComponentBuildVersions", parse(body), in -> service.listComponentBuildVersions(in, region()));
    }

    @POST
    @Path("/ListComponents")
    public Response listComponents(String body) {
        return call("ListComponents", parse(body), in -> service.listComponents(in, region()));
    }

    @POST
    @Path("/ListContainerRecipes")
    public Response listContainerRecipes(String body) {
        return call("ListContainerRecipes", parse(body), in -> service.listContainerRecipes(in, region()));
    }

    @POST
    @Path("/ListDistributionConfigurations")
    public Response listDistributionConfigurations(String body) {
        return call("ListDistributionConfigurations", parse(body), in -> service.listDistributionConfigurations(in, region()));
    }

    @POST
    @Path("/ListImageBuildVersions")
    public Response listImageBuildVersions(String body) {
        return call("ListImageBuildVersions", parse(body), in -> images.listImageBuildVersions(in, region()));
    }

    @POST
    @Path("/ListImagePackages")
    public Response listImagePackages(String body) {
        return call("ListImagePackages", parse(body), in -> images.listImagePackages(in, region()));
    }

    @POST
    @Path("/ListImagePipelineImages")
    public Response listImagePipelineImages(String body) {
        return call("ListImagePipelineImages", parse(body), in -> images.listImagePipelineImages(in, region()));
    }

    @POST
    @Path("/ListImagePipelines")
    public Response listImagePipelines(String body) {
        return call("ListImagePipelines", parse(body), in -> service.listImagePipelines(in, region()));
    }

    @POST
    @Path("/ListImageRecipes")
    public Response listImageRecipes(String body) {
        return call("ListImageRecipes", parse(body), in -> service.listImageRecipes(in, region()));
    }

    @POST
    @Path("/ListImageScanFindingAggregations")
    public Response listImageScanFindingAggregations(String body) {
        return call("ListImageScanFindingAggregations", parse(body), in -> images.listImageScanFindingAggregations(in, region()));
    }

    @POST
    @Path("/ListImageScanFindings")
    public Response listImageScanFindings(String body) {
        return call("ListImageScanFindings", parse(body), in -> images.listImageScanFindings(in, region()));
    }

    @POST
    @Path("/ListImages")
    public Response listImages(String body) {
        return call("ListImages", parse(body), in -> images.listImages(in, region()));
    }

    @POST
    @Path("/ListInfrastructureConfigurations")
    public Response listInfrastructureConfigurations(String body) {
        return call("ListInfrastructureConfigurations", parse(body), in -> service.listInfrastructureConfigurations(in, region()));
    }

    @POST
    @Path("/ListLifecycleExecutionResources")
    public Response listLifecycleExecutionResources(String body) {
        return call("ListLifecycleExecutionResources", parse(body), in -> lifecycle.listLifecycleExecutionResources(in, region()));
    }

    @POST
    @Path("/ListLifecycleExecutions")
    public Response listLifecycleExecutions(String body) {
        return call("ListLifecycleExecutions", parse(body), in -> lifecycle.listLifecycleExecutions(in, region()));
    }

    @POST
    @Path("/ListLifecyclePolicies")
    public Response listLifecyclePolicies(String body) {
        return call("ListLifecyclePolicies", parse(body), in -> service.listLifecyclePolicies(in, region()));
    }

    @POST
    @Path("/ListWaitingWorkflowSteps")
    public Response listWaitingWorkflowSteps(String body) {
        return call("ListWaitingWorkflowSteps", parse(body), in -> images.listWaitingWorkflowSteps(in, region()));
    }

    @POST
    @Path("/ListWorkflowBuildVersions")
    public Response listWorkflowBuildVersions(String body) {
        return call("ListWorkflowBuildVersions", parse(body), in -> service.listWorkflowBuildVersions(in, region()));
    }

    @POST
    @Path("/ListWorkflowExecutions")
    public Response listWorkflowExecutions(String body) {
        return call("ListWorkflowExecutions", parse(body), in -> images.listWorkflowExecutions(in, region()));
    }

    @POST
    @Path("/ListWorkflowStepExecutions")
    public Response listWorkflowStepExecutions(String body) {
        return call("ListWorkflowStepExecutions", parse(body), in -> images.listWorkflowStepExecutions(in, region()));
    }

    @POST
    @Path("/ListWorkflows")
    public Response listWorkflows(String body) {
        return call("ListWorkflows", parse(body), in -> service.listWorkflows(in, region()));
    }

    @PUT
    @Path("/PutComponentPolicy")
    public Response putComponentPolicy(String body) {
        return call("PutComponentPolicy", parse(body), in -> service.putComponentPolicy(in, region()));
    }

    @PUT
    @Path("/PutContainerRecipePolicy")
    public Response putContainerRecipePolicy(String body) {
        return call("PutContainerRecipePolicy", parse(body), in -> service.putContainerRecipePolicy(in, region()));
    }

    @PUT
    @Path("/PutImagePolicy")
    public Response putImagePolicy(String body) {
        return call("PutImagePolicy", parse(body), in -> service.putImagePolicy(in, region()));
    }

    @PUT
    @Path("/PutImageRecipePolicy")
    public Response putImageRecipePolicy(String body) {
        return call("PutImageRecipePolicy", parse(body), in -> service.putImageRecipePolicy(in, region()));
    }

    @PUT
    @Path("/RetryImage")
    public Response retryImage(String body) {
        return call("RetryImage", parse(body), in -> images.retryImage(in, region()));
    }

    @PUT
    @Path("/SendWorkflowStepAction")
    public Response sendWorkflowStepAction(String body) {
        return call("SendWorkflowStepAction", parse(body), in -> images.sendWorkflowStepAction(in, region()));
    }

    @PUT
    @Path("/StartImagePipelineExecution")
    public Response startImagePipelineExecution(String body) {
        return call("StartImagePipelineExecution", parse(body), in -> images.startImagePipelineExecution(in, region()));
    }

    @PUT
    @Path("/StartResourceStateUpdate")
    public Response startResourceStateUpdate(String body) {
        return call("StartResourceStateUpdate", parse(body), in -> lifecycle.startResourceStateUpdate(in, region()));
    }

    @PUT
    @Path("/UpdateDistributionConfiguration")
    public Response updateDistributionConfiguration(String body) {
        return call("UpdateDistributionConfiguration", parse(body), in -> service.updateDistributionConfiguration(in, region()));
    }

    @PUT
    @Path("/UpdateImagePipeline")
    public Response updateImagePipeline(String body) {
        return call("UpdateImagePipeline", parse(body), in -> service.updateImagePipeline(in, region()));
    }

    @PUT
    @Path("/UpdateInfrastructureConfiguration")
    public Response updateInfrastructureConfiguration(String body) {
        return call("UpdateInfrastructureConfiguration", parse(body), in -> service.updateInfrastructureConfiguration(in, region()));
    }

    @PUT
    @Path("/UpdateLifecyclePolicy")
    public Response updateLifecyclePolicy(String body) {
        return call("UpdateLifecyclePolicy", parse(body), in -> service.updateLifecyclePolicy(in, region()));
    }

    @GET
    @Path("/tags/{resourceArn: .+}")
    public Response listTagsForResource(@PathParam("resourceArn") String resourceArn) {
        return reply(() -> service.listTagsForResource(resourceArn, region()));
    }

    @POST
    @Path("/tags/{resourceArn: .+}")
    public Response tagResource(@PathParam("resourceArn") String resourceArn, String body) {
        return reply(() -> service.tagResource(resourceArn, parse(body), region()));
    }

    @DELETE
    @Path("/tags/{resourceArn: .+}")
    public Response untagResource(@PathParam("resourceArn") String resourceArn,
                                  @QueryParam("tagKeys") List<String> tagKeys) {
        return reply(() -> service.untagResource(resourceArn, tagKeys, region()));
    }

    private String region() {
        return regions.getRegion();
    }

    private Response call(String operation, JsonNode input, Function<JsonNode, ObjectNode> handler) {
        return reply(() -> {
            SHAPES.check(operation, input);
            return handler.apply(input);
        });
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw ImageBuilderStore.invalidRequest("The request body must be a JSON object.");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw ImageBuilderStore.invalidRequest("The request body is not valid JSON: " + e.getOriginalMessage());
        }
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
}
