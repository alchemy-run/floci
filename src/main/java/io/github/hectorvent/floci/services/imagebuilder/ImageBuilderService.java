package io.github.hectorvent.floci.services.imagebuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.imagebuilder.model.Component;
import io.github.hectorvent.floci.services.imagebuilder.model.DistributionConfiguration;
import io.github.hectorvent.floci.services.imagebuilder.model.ImageBuild;
import io.github.hectorvent.floci.services.imagebuilder.model.ImagePipeline;
import io.github.hectorvent.floci.services.imagebuilder.model.ImageRecipe;
import io.github.hectorvent.floci.services.imagebuilder.model.InfrastructureConfiguration;
import io.github.hectorvent.floci.services.imagebuilder.model.WorkflowRun;
import io.github.hectorvent.floci.services.imagebuilder.model.WorkflowStep;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * EC2 Image Builder restJson1 — components, recipes, infrastructure,
 * pipelines, and emulated image builds.
 *
 * <p>Pipeline executions mint an in-memory image build in {@code BUILDING}
 * that {@code CancelImageCreation} moves to {@code CANCELLED}. Tag APIs share
 * {@code /tags/{arn}} via {@link TagHandler} using ARN service
 * {@code imagebuilder}.
 */
@ApplicationScoped
public class ImageBuilderService implements TagHandler {

    static final String SERVICE = "imagebuilder";
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final Set<String> PLATFORMS = Set.of("Linux", "Windows", "macOS");
    private static final Set<String> PIPELINE_STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> DELETABLE_IMAGE_STATES = Set.of("CANCELLED", "FAILED", "AVAILABLE", "DEPRECATED");

    private final StorageBackend<String, Component> components;
    private final StorageBackend<String, ImageRecipe> recipes;
    private final StorageBackend<String, InfrastructureConfiguration> infrastructures;
    private final StorageBackend<String, DistributionConfiguration> distributions;
    private final StorageBackend<String, ImagePipeline> pipelines;
    private final StorageBackend<String, ImageBuild> images;
    private final StorageBackend<String, WorkflowRun> workflows;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public ImageBuilderService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create("imagebuilder", "imagebuilder-components.json",
                        new TypeReference<Map<String, Component>>() {
                        }),
                storageFactory.create("imagebuilder", "imagebuilder-recipes.json",
                        new TypeReference<Map<String, ImageRecipe>>() {
                        }),
                storageFactory.create("imagebuilder", "imagebuilder-infrastructure.json",
                        new TypeReference<Map<String, InfrastructureConfiguration>>() {
                        }),
                storageFactory.create("imagebuilder", "imagebuilder-distributions.json",
                        new TypeReference<Map<String, DistributionConfiguration>>() {
                        }),
                storageFactory.create("imagebuilder", "imagebuilder-pipelines.json",
                        new TypeReference<Map<String, ImagePipeline>>() {
                        }),
                storageFactory.create("imagebuilder", "imagebuilder-images.json",
                        new TypeReference<Map<String, ImageBuild>>() {
                        }),
                storageFactory.create("imagebuilder", "imagebuilder-workflows.json",
                        new TypeReference<Map<String, WorkflowRun>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    ImageBuilderService(
            StorageBackend<String, Component> components,
            StorageBackend<String, ImageRecipe> recipes,
            StorageBackend<String, InfrastructureConfiguration> infrastructures,
            StorageBackend<String, DistributionConfiguration> distributions,
            StorageBackend<String, ImagePipeline> pipelines,
            StorageBackend<String, ImageBuild> images,
            StorageBackend<String, WorkflowRun> workflows,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.components = components;
        this.recipes = recipes;
        this.infrastructures = infrastructures;
        this.distributions = distributions;
        this.pipelines = pipelines;
        this.images = images;
        this.workflows = workflows;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    // ──────────────────────────── Components ────────────────────────────

    public synchronized Component createComponent(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        String version = requireText(request, "semanticVersion");
        validateSemver(version);
        String platform = requireEnum(request, "platform", PLATFORMS);
        String data = optionalText(request, "data");
        String uri = optionalText(request, "uri");
        if ((data == null) == (uri == null)) {
            throw invalidRequest("Specify exactly one of data or uri.");
        }
        String clientToken = optionalText(request, "clientToken");
        String arn = resourceArn(region, "component", name.toLowerCase(Locale.ROOT) + "/" + version + "/1");
        Optional<Component> existing = components.get(arn);
        if (existing.isPresent()) {
            Component found = existing.get();
            if (clientToken != null && clientToken.equals(found.getClientToken())) {
                return found;
            }
            throw alreadyExists("Component " + arn + " already exists.");
        }
        Component component = new Component();
        component.setArn(arn);
        component.setName(name);
        component.setVersion(version);
        component.setBuildVersion(1);
        component.setDescription(optionalText(request, "description"));
        component.setChangeDescription(optionalText(request, "changeDescription"));
        component.setPlatform(platform);
        component.setSupportedOsVersions(readStringList(request, "supportedOsVersions"));
        component.setData(data);
        component.setUri(uri);
        component.setKmsKeyId(optionalText(request, "kmsKeyId"));
        component.setType(inferComponentType(data));
        component.setStatus("ACTIVE");
        component.setOwner(regionResolver.getAccountId());
        component.setDateCreated(now());
        component.setClientToken(clientToken);
        component.setTags(readTags(request.get("tags")));
        components.put(arn, component);
        return component;
    }

    public Component getComponent(String region, String arn) {
        return requireComponent(arn);
    }

    public synchronized void deleteComponent(String region, String arn) {
        Component component = requireComponent(arn);
        for (ImageRecipe recipe : recipes.scan(key -> true)) {
            if (recipeReferencesComponent(recipe, component.getArn())) {
                throw dependency("The component is still referenced by image recipe " + recipe.getArn());
            }
        }
        components.delete(component.getArn());
    }

    public ObjectNode listComponents(String region, JsonNode request) {
        requireObject(request, "Request body");
        if (!ownerIsSelf(request)) {
            return listEnvelope("componentVersionList");
        }
        Map<String, Component> latest = new LinkedHashMap<>();
        for (Component component : sorted(components.scan(key -> true), Comparator.comparing(Component::getArn))) {
            latest.putIfAbsent(versionArn(component.getArn()), component);
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("componentVersionList");
        for (Component component : latest.values()) {
            ObjectNode item = list.addObject();
            item.put("arn", versionArn(component.getArn()));
            item.put("name", component.getName());
            item.put("version", component.getVersion());
            putOptional(item, "description", component.getDescription());
            putOptional(item, "platform", component.getPlatform());
            putOptional(item, "type", component.getType());
            putOptional(item, "owner", component.getOwner());
            putOptional(item, "dateCreated", component.getDateCreated());
        }
        return response;
    }

    public ObjectNode listComponentBuildVersions(String region, JsonNode request) {
        requireObject(request, "Request body");
        String versionArn = optionalText(request, "componentVersionArn");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("componentSummaryList");
        for (Component component : sorted(components.scan(key -> true), Comparator.comparing(Component::getArn))) {
            if (versionArn != null && !versionArn(component.getArn()).equals(versionArn)) {
                continue;
            }
            list.add(toComponentSummary(component));
        }
        return response;
    }

    // ──────────────────────────── Recipes ────────────────────────────

    public synchronized ImageRecipe createImageRecipe(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        String version = requireText(request, "semanticVersion");
        validateSemver(version);
        String parentImage = requireText(request, "parentImage");
        String clientToken = optionalText(request, "clientToken");
        String arn = resourceArn(region, "image-recipe", name.toLowerCase(Locale.ROOT) + "/" + version);
        Optional<ImageRecipe> existing = recipes.get(arn);
        if (existing.isPresent()) {
            ImageRecipe found = existing.get();
            if (clientToken != null && clientToken.equals(found.getClientToken())) {
                return found;
            }
            throw alreadyExists("Image recipe " + arn + " already exists.");
        }
        ImageRecipe recipe = new ImageRecipe();
        recipe.setArn(arn);
        recipe.setName(name);
        recipe.setVersion(version);
        recipe.setDescription(optionalText(request, "description"));
        recipe.setParentImage(parentImage);
        recipe.setWorkingDirectory(optionalText(request, "workingDirectory"));
        recipe.setComponents(copyNode(request.get("components")));
        recipe.setBlockDeviceMappings(copyNode(request.get("blockDeviceMappings")));
        recipe.setAdditionalInstanceConfiguration(copyNode(request.get("additionalInstanceConfiguration")));
        recipe.setAmiTags(copyNode(request.get("amiTags")));
        recipe.setPlatform(platformFromParent(parentImage));
        recipe.setOwner(regionResolver.getAccountId());
        recipe.setDateCreated(now());
        recipe.setClientToken(clientToken);
        recipe.setTags(readTags(request.get("tags")));
        recipes.put(arn, recipe);
        return recipe;
    }

    public ImageRecipe getImageRecipe(String region, String arn) {
        return requireRecipe(arn);
    }

    public synchronized void deleteImageRecipe(String region, String arn) {
        ImageRecipe recipe = requireRecipe(arn);
        for (ImagePipeline pipeline : pipelines.scan(key -> true)) {
            if (recipe.getArn().equals(pipeline.getImageRecipeArn())) {
                throw dependency("The image recipe is still referenced by image pipeline " + pipeline.getArn());
            }
        }
        recipes.delete(recipe.getArn());
    }

    public ObjectNode listImageRecipes(String region, JsonNode request) {
        requireObject(request, "Request body");
        if (!ownerIsSelf(request)) {
            return listEnvelope("imageRecipeSummaryList");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("imageRecipeSummaryList");
        for (ImageRecipe recipe : sorted(recipes.scan(key -> true), Comparator.comparing(ImageRecipe::getArn))) {
            ObjectNode item = list.addObject();
            item.put("arn", recipe.getArn());
            item.put("name", recipe.getName());
            putOptional(item, "platform", recipe.getPlatform());
            putOptional(item, "owner", recipe.getOwner());
            putOptional(item, "parentImage", recipe.getParentImage());
            putOptional(item, "dateCreated", recipe.getDateCreated());
            putTags(item, recipe.getTags());
        }
        return response;
    }

    // ──────────────────────────── Infrastructure ────────────────────────────

    public synchronized InfrastructureConfiguration createInfrastructureConfiguration(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        String instanceProfileName = requireText(request, "instanceProfileName");
        String clientToken = optionalText(request, "clientToken");
        String arn = resourceArn(region, "infrastructure-configuration", name.toLowerCase(Locale.ROOT));
        Optional<InfrastructureConfiguration> existing = infrastructures.get(arn);
        if (existing.isPresent()) {
            InfrastructureConfiguration found = existing.get();
            if (clientToken != null && clientToken.equals(found.getClientToken())) {
                return found;
            }
            throw alreadyExists("Infrastructure configuration " + arn + " already exists.");
        }
        InfrastructureConfiguration config = new InfrastructureConfiguration();
        config.setArn(arn);
        config.setName(name);
        applyInfrastructureFields(config, request);
        config.setInstanceProfileName(instanceProfileName);
        config.setDateCreated(now());
        config.setDateUpdated(config.getDateCreated());
        config.setClientToken(clientToken);
        config.setTags(readTags(request.get("tags")));
        infrastructures.put(arn, config);
        return config;
    }

    public InfrastructureConfiguration getInfrastructureConfiguration(String region, String arn) {
        return requireInfrastructure(arn);
    }

    public synchronized InfrastructureConfiguration updateInfrastructureConfiguration(String region, JsonNode request) {
        requireObject(request, "Request body");
        InfrastructureConfiguration config = requireInfrastructure(requireText(request, "infrastructureConfigurationArn"));
        applyInfrastructureFields(config, request);
        if (request.hasNonNull("instanceProfileName")) {
            config.setInstanceProfileName(requireText(request, "instanceProfileName"));
        }
        config.setDateUpdated(now());
        infrastructures.put(config.getArn(), config);
        return config;
    }

    public synchronized void deleteInfrastructureConfiguration(String region, String arn) {
        InfrastructureConfiguration config = requireInfrastructure(arn);
        for (ImagePipeline pipeline : pipelines.scan(key -> true)) {
            if (config.getArn().equals(pipeline.getInfrastructureConfigurationArn())) {
                throw dependency("The infrastructure configuration is still referenced by image pipeline "
                        + pipeline.getArn());
            }
        }
        infrastructures.delete(config.getArn());
    }

    public synchronized DistributionConfiguration createDistributionConfiguration(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        if (!request.has("distributions") || !request.get("distributions").isArray()) {
            throw invalidParameter("distributions must be an array.");
        }
        String clientToken = optionalText(request, "clientToken");
        String arn = resourceArn(region, "distribution-configuration", name.toLowerCase(Locale.ROOT));
        Optional<DistributionConfiguration> existing = distributions.get(arn);
        if (existing.isPresent()) {
            DistributionConfiguration found = existing.get();
            if (clientToken != null && clientToken.equals(found.getClientToken())) {
                return found;
            }
            throw alreadyExists("Distribution configuration " + arn + " already exists.");
        }
        DistributionConfiguration config = new DistributionConfiguration();
        config.setArn(arn);
        config.setName(name);
        config.setDescription(optionalText(request, "description"));
        config.setDistributions(copyNode(request.get("distributions")));
        config.setDateCreated(now());
        config.setDateUpdated(config.getDateCreated());
        config.setClientToken(clientToken);
        config.setTags(readTags(request.get("tags")));
        distributions.put(arn, config);
        return config;
    }

    public DistributionConfiguration getDistributionConfiguration(String region, String arn) {
        return requireDistribution(arn);
    }

    public synchronized DistributionConfiguration updateDistributionConfiguration(String region, JsonNode request) {
        requireObject(request, "Request body");
        DistributionConfiguration config = requireDistribution(requireText(request, "distributionConfigurationArn"));
        if (request.has("description")) {
            config.setDescription(optionalText(request, "description"));
        }
        if (request.has("distributions")) {
            if (!request.get("distributions").isArray()) {
                throw invalidParameter("distributions must be an array.");
            }
            config.setDistributions(copyNode(request.get("distributions")));
        }
        config.setDateUpdated(now());
        distributions.put(config.getArn(), config);
        return config;
    }

    public synchronized void deleteDistributionConfiguration(String region, String arn) {
        DistributionConfiguration config = requireDistribution(arn);
        for (ImagePipeline pipeline : pipelines.scan(key -> true)) {
            if (config.getArn().equals(pipeline.getDistributionConfigurationArn())) {
                throw dependency("The distribution configuration is still referenced by image pipeline "
                        + pipeline.getArn());
            }
        }
        distributions.delete(config.getArn());
    }

    public ObjectNode listDistributionConfigurations(String region, JsonNode request) {
        requireObject(request, "Request body");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("distributionConfigurationSummaryList");
        for (DistributionConfiguration config : sorted(
                distributions.scan(key -> true), Comparator.comparing(DistributionConfiguration::getArn))) {
            ObjectNode item = list.addObject();
            item.put("arn", config.getArn());
            item.put("name", config.getName());
            putOptional(item, "description", config.getDescription());
            putOptional(item, "dateCreated", config.getDateCreated());
            putTags(item, config.getTags());
        }
        return response;
    }

    public ObjectNode listInfrastructureConfigurations(String region, JsonNode request) {
        requireObject(request, "Request body");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("infrastructureConfigurationSummaryList");
        for (InfrastructureConfiguration config : sorted(
                infrastructures.scan(key -> true), Comparator.comparing(InfrastructureConfiguration::getArn))) {
            ObjectNode item = list.addObject();
            item.put("arn", config.getArn());
            item.put("name", config.getName());
            putOptional(item, "description", config.getDescription());
            putOptional(item, "instanceProfileName", config.getInstanceProfileName());
            putOptional(item, "dateCreated", config.getDateCreated());
            putTags(item, config.getTags());
        }
        return response;
    }

    // ──────────────────────────── Pipelines ────────────────────────────

    public synchronized ImagePipeline createImagePipeline(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        String infrastructureArn = requireText(request, "infrastructureConfigurationArn");
        requireInfrastructure(infrastructureArn);
        String recipeArn = optionalText(request, "imageRecipeArn");
        String containerRecipeArn = optionalText(request, "containerRecipeArn");
        if ((recipeArn == null) == (containerRecipeArn == null)) {
            throw invalidRequest("Specify exactly one of imageRecipeArn or containerRecipeArn.");
        }
        ImageRecipe recipe = recipeArn == null ? null : requireRecipe(recipeArn);
        String distributionArn = optionalText(request, "distributionConfigurationArn");
        if (distributionArn != null) {
            requireDistribution(distributionArn);
        }
        String clientToken = optionalText(request, "clientToken");
        String arn = resourceArn(region, "image-pipeline", name.toLowerCase(Locale.ROOT));
        Optional<ImagePipeline> existing = pipelines.get(arn);
        if (existing.isPresent()) {
            ImagePipeline found = existing.get();
            if (clientToken != null && clientToken.equals(found.getClientToken())) {
                return found;
            }
            throw alreadyExists("Image pipeline " + arn + " already exists.");
        }
        ImagePipeline pipeline = new ImagePipeline();
        pipeline.setArn(arn);
        pipeline.setName(name);
        applyPipelineFields(pipeline, request);
        pipeline.setInfrastructureConfigurationArn(infrastructureArn);
        pipeline.setPlatform(recipe == null ? "Linux" : recipe.getPlatform());
        pipeline.setDateCreated(now());
        pipeline.setDateUpdated(pipeline.getDateCreated());
        pipeline.setClientToken(clientToken);
        pipeline.setTags(readTags(request.get("tags")));
        pipelines.put(arn, pipeline);
        return pipeline;
    }

    public ImagePipeline getImagePipeline(String region, String arn) {
        return requirePipeline(arn);
    }

    public synchronized ImagePipeline updateImagePipeline(String region, JsonNode request) {
        requireObject(request, "Request body");
        ImagePipeline pipeline = requirePipeline(requireText(request, "imagePipelineArn"));
        if (request.hasNonNull("infrastructureConfigurationArn")) {
            requireInfrastructure(requireText(request, "infrastructureConfigurationArn"));
        }
        if (request.hasNonNull("imageRecipeArn")) {
            ImageRecipe recipe = requireRecipe(requireText(request, "imageRecipeArn"));
            pipeline.setPlatform(recipe.getPlatform());
        }
        if (request.hasNonNull("distributionConfigurationArn")) {
            requireDistribution(requireText(request, "distributionConfigurationArn"));
        }
        applyPipelineFields(pipeline, request);
        pipeline.setDateUpdated(now());
        pipelines.put(pipeline.getArn(), pipeline);
        return pipeline;
    }

    public synchronized void deleteImagePipeline(String region, String arn) {
        ImagePipeline pipeline = requirePipeline(arn);
        pipelines.delete(pipeline.getArn());
    }

    public ObjectNode listImagePipelines(String region, JsonNode request) {
        requireObject(request, "Request body");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("imagePipelineList");
        for (ImagePipeline pipeline : sorted(pipelines.scan(key -> true), Comparator.comparing(ImagePipeline::getArn))) {
            list.add(toPipeline(pipeline));
        }
        return response;
    }

    // ──────────────────────────── Images / builds ────────────────────────────

    public synchronized ImageBuild startImagePipelineExecution(String region, JsonNode request) {
        requireObject(request, "Request body");
        ImagePipeline pipeline = requirePipeline(requireText(request, "imagePipelineArn"));
        String clientToken = optionalText(request, "clientToken");
        if (clientToken != null) {
            for (ImageBuild existing : images.scan(key -> true)) {
                if (clientToken.equals(existing.getClientToken())) {
                    return existing;
                }
            }
        }
        ImageRecipe recipe = pipeline.getImageRecipeArn() == null
                ? null
                : requireRecipe(pipeline.getImageRecipeArn());
        String version = recipe == null ? "1.0.0" : recipe.getVersion();
        String name = pipeline.getName();
        int nextBuild = nextBuildNumber(name, version);
        String arn = resourceArn(region, "image",
                name.toLowerCase(Locale.ROOT) + "/" + version + "/" + nextBuild);
        String now = now();
        ImageBuild image = new ImageBuild();
        image.setArn(arn);
        image.setName(name);
        image.setVersion(version);
        image.setBuildVersion(nextBuild);
        image.setPlatform(pipeline.getPlatform());
        image.setStatus("BUILDING");
        image.setOwner(regionResolver.getAccountId());
        image.setSourcePipelineName(pipeline.getName());
        image.setSourcePipelineArn(pipeline.getArn());
        image.setImageRecipeArn(pipeline.getImageRecipeArn());
        image.setInfrastructureConfigurationArn(pipeline.getInfrastructureConfigurationArn());
        image.setDateCreated(now);
        image.setClientToken(clientToken);
        image.setTags(readTags(request.get("tags")));

        String workflowId = UUID.randomUUID().toString();
        String stepId = UUID.randomUUID().toString();
        String workflowArn = resourceArn(region, "workflow",
                "build-" + name.toLowerCase(Locale.ROOT) + "/1.0.0/1");
        WorkflowStep step = new WorkflowStep();
        step.setStepExecutionId(stepId);
        step.setWorkflowExecutionId(workflowId);
        step.setImageBuildVersionArn(arn);
        step.setWorkflowBuildVersionArn(workflowArn);
        step.setName("Build");
        step.setAction("ExecuteBash");
        step.setStatus("RUNNING");
        step.setStartTime(now);
        WorkflowRun run = new WorkflowRun();
        run.setWorkflowExecutionId(workflowId);
        run.setWorkflowBuildVersionArn(workflowArn);
        run.setImageBuildVersionArn(arn);
        run.setType("BUILD");
        run.setStatus("RUNNING");
        run.setStartTime(now);
        run.setSteps(List.of(step));
        image.setWorkflowExecutionId(workflowId);
        images.put(arn, image);
        workflows.put(workflowId, run);

        pipeline.setDateLastRun(now);
        pipeline.setLastRunStatus("BUILDING");
        pipelines.put(pipeline.getArn(), pipeline);
        return image;
    }

    public synchronized ImageBuild cancelImageCreation(String region, JsonNode request) {
        requireObject(request, "Request body");
        ImageBuild image = requireImage(requireText(request, "imageBuildVersionArn"));
        if ("CANCELLED".equals(image.getStatus())) {
            return image;
        }
        if (DELETABLE_IMAGE_STATES.contains(image.getStatus()) && !"BUILDING".equals(image.getStatus())
                && !"PENDING".equals(image.getStatus()) && !"CREATING".equals(image.getStatus())
                && !"TESTING".equals(image.getStatus())) {
            throw invalidRequest("Image " + image.getArn() + " is not in a cancellable state.");
        }
        String now = now();
        image.setStatus("CANCELLED");
        image.setReason("Cancelled by CancelImageCreation");
        images.put(image.getArn(), image);
        if (image.getWorkflowExecutionId() != null) {
            workflows.get(image.getWorkflowExecutionId()).ifPresent(run -> {
                run.setStatus("CANCELLED");
                run.setEndTime(now);
                for (WorkflowStep step : run.getSteps()) {
                    if ("RUNNING".equals(step.getStatus()) || "PENDING".equals(step.getStatus())) {
                        step.setStatus("CANCELLED");
                        step.setEndTime(now);
                    }
                }
                workflows.put(run.getWorkflowExecutionId(), run);
            });
        }
        return image;
    }

    public ImageBuild getImage(String region, String arn) {
        return requireImage(arn);
    }

    public synchronized void deleteImage(String region, String arn) {
        ImageBuild image = requireImage(arn);
        if (!DELETABLE_IMAGE_STATES.contains(image.getStatus())) {
            throw invalidRequest("Image " + image.getArn() + " is not in a deletable state.");
        }
        if (image.getWorkflowExecutionId() != null) {
            workflows.delete(image.getWorkflowExecutionId());
        }
        images.delete(image.getArn());
    }

    public ObjectNode listImages(String region, JsonNode request) {
        requireObject(request, "Request body");
        if (!ownerIsSelf(request)) {
            return listEnvelope("imageVersionList");
        }
        Map<String, ImageBuild> latest = new LinkedHashMap<>();
        for (ImageBuild image : sorted(images.scan(key -> true), Comparator.comparing(ImageBuild::getArn))) {
            latest.putIfAbsent(image.versionArn(), image);
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("imageVersionList");
        for (ImageBuild image : latest.values()) {
            ObjectNode item = list.addObject();
            item.put("arn", image.versionArn());
            item.put("name", image.getName());
            item.put("type", image.getType());
            item.put("version", image.getVersion());
            putOptional(item, "platform", image.getPlatform());
            putOptional(item, "owner", image.getOwner());
            putOptional(item, "dateCreated", image.getDateCreated());
            putOptional(item, "buildType", image.getBuildType());
            putOptional(item, "imageSource", image.getImageSource());
        }
        return response;
    }

    public ObjectNode listImagePipelineImages(String region, JsonNode request) {
        requireObject(request, "Request body");
        String pipelineArn = requireText(request, "imagePipelineArn");
        requirePipeline(pipelineArn);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("imageSummaryList");
        for (ImageBuild image : sorted(images.scan(key -> true), Comparator.comparing(ImageBuild::getArn))) {
            if (pipelineArn.equals(image.getSourcePipelineArn())) {
                list.add(toImageSummary(image));
            }
        }
        return response;
    }

    public ObjectNode listImageBuildVersions(String region, JsonNode request) {
        requireObject(request, "Request body");
        String versionArn = optionalText(request, "imageVersionArn");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("imageSummaryList");
        for (ImageBuild image : sorted(images.scan(key -> true), Comparator.comparing(ImageBuild::getArn))) {
            if (versionArn != null && !versionArn.equals(image.versionArn())) {
                continue;
            }
            list.add(toImageSummary(image));
        }
        return response;
    }

    public ObjectNode listImagePackages(String region, JsonNode request) {
        requireObject(request, "Request body");
        ImageBuild image = requireImage(requireText(request, "imageBuildVersionArn"));
        if (!"AVAILABLE".equals(image.getStatus())) {
            throw invalidRequest("The image must be in AVAILABLE state to list packages.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("imagePackageList");
        for (String pkg : image.getPackages()) {
            ObjectNode item = list.addObject();
            item.put("packageName", pkg);
        }
        return response;
    }

    public ObjectNode listImageScanFindings(String region, JsonNode request) {
        requireObject(request, "Request body");
        return listEnvelope("findings");
    }

    public ObjectNode listImageScanFindingAggregations(String region, JsonNode request) {
        requireObject(request, "Request body");
        return listEnvelope("responses");
    }

    public ObjectNode listWaitingWorkflowSteps(String region, JsonNode request) {
        requireObject(request, "Request body");
        return listEnvelope("steps");
    }

    public ObjectNode listWorkflowExecutions(String region, JsonNode request) {
        requireObject(request, "Request body");
        String imageArn = requireText(request, "imageBuildVersionArn");
        requireImage(imageArn);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("workflowExecutions");
        for (WorkflowRun run : workflows.scan(key -> true)) {
            if (imageArn.equals(run.getImageBuildVersionArn())) {
                list.add(toWorkflowExecution(run));
            }
        }
        response.put("imageBuildVersionArn", imageArn);
        return response;
    }

    public WorkflowRun getWorkflowExecution(String region, String id) {
        return requireWorkflow(id);
    }

    public WorkflowStep getWorkflowStepExecution(String region, String id) {
        return requireStep(id);
    }

    public ObjectNode listWorkflowStepExecutions(String region, JsonNode request) {
        requireObject(request, "Request body");
        WorkflowRun run = requireWorkflow(requireText(request, "workflowExecutionId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("steps");
        for (WorkflowStep step : run.getSteps()) {
            ObjectNode item = list.addObject();
            item.put("stepExecutionId", step.getStepExecutionId());
            putOptional(item, "name", step.getName());
            putOptional(item, "action", step.getAction());
            putOptional(item, "status", step.getStatus());
            putOptional(item, "startTime", step.getStartTime());
            putOptional(item, "endTime", step.getEndTime());
        }
        putOptional(response, "workflowBuildVersionArn", run.getWorkflowBuildVersionArn());
        putOptional(response, "workflowExecutionId", run.getWorkflowExecutionId());
        putOptional(response, "imageBuildVersionArn", run.getImageBuildVersionArn());
        return response;
    }

    public synchronized ImageBuild retryImage(String region, JsonNode request) {
        requireObject(request, "Request body");
        ImageBuild image = requireImage(requireText(request, "imageBuildVersionArn"));
        if (!"FAILED".equals(image.getStatus())) {
            throw invalidRequest("Only FAILED images can be retried.");
        }
        return image;
    }

    public synchronized ObjectNode sendWorkflowStepAction(String region, JsonNode request) {
        requireObject(request, "Request body");
        WorkflowStep step = requireStep(requireText(request, "stepExecutionId"));
        ImageBuild image = requireImage(requireText(request, "imageBuildVersionArn"));
        String action = requireText(request, "action");
        if (!"RESUME".equals(action) && !"STOP".equals(action)) {
            throw invalidRequest("action must be RESUME or STOP.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("stepExecutionId", step.getStepExecutionId());
        response.put("imageBuildVersionArn", image.getArn());
        putOptional(response, "clientToken", optionalText(request, "clientToken"));
        return response;
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(arn);
        Map<String, String> current = tagged.tags();
        if (tags != null) {
            current.putAll(tags);
        }
        persistTagged(tagged);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(arn);
        if (tagKeys != null) {
            tagKeys.forEach(tagged.tags()::remove);
        }
        persistTagged(tagged);
    }

    // ──────────────────────────── Serializers ────────────────────────────

    public ObjectNode toComponent(Component component) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", component.getArn());
        node.put("name", component.getName());
        node.put("version", component.getVersion());
        putOptional(node, "description", component.getDescription());
        putOptional(node, "changeDescription", component.getChangeDescription());
        putOptional(node, "type", component.getType());
        putOptional(node, "platform", component.getPlatform());
        if (component.getSupportedOsVersions() != null && !component.getSupportedOsVersions().isEmpty()) {
            ArrayNode versions = node.putArray("supportedOsVersions");
            component.getSupportedOsVersions().forEach(versions::add);
        }
        ObjectNode state = node.putObject("state");
        state.put("status", component.getStatus() == null ? "ACTIVE" : component.getStatus());
        putOptional(node, "owner", component.getOwner());
        putOptional(node, "data", component.getData());
        putOptional(node, "kmsKeyId", component.getKmsKeyId());
        putOptional(node, "dateCreated", component.getDateCreated());
        putTags(node, component.getTags());
        return node;
    }

    public ObjectNode toRecipe(ImageRecipe recipe) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", recipe.getArn());
        node.put("name", recipe.getName());
        node.put("version", recipe.getVersion());
        putOptional(node, "type", recipe.getType());
        putOptional(node, "description", recipe.getDescription());
        putOptional(node, "platform", recipe.getPlatform());
        putOptional(node, "owner", recipe.getOwner());
        putOptional(node, "parentImage", recipe.getParentImage());
        putOptional(node, "workingDirectory", recipe.getWorkingDirectory());
        putOptional(node, "dateCreated", recipe.getDateCreated());
        if (recipe.getComponents() != null && !recipe.getComponents().isNull()) {
            node.set("components", recipe.getComponents());
        }
        if (recipe.getBlockDeviceMappings() != null && !recipe.getBlockDeviceMappings().isNull()) {
            node.set("blockDeviceMappings", recipe.getBlockDeviceMappings());
        }
        if (recipe.getAdditionalInstanceConfiguration() != null
                && !recipe.getAdditionalInstanceConfiguration().isNull()) {
            node.set("additionalInstanceConfiguration", recipe.getAdditionalInstanceConfiguration());
        }
        if (recipe.getAmiTags() != null && !recipe.getAmiTags().isNull()) {
            node.set("amiTags", recipe.getAmiTags());
        }
        putTags(node, recipe.getTags());
        return node;
    }

    public ObjectNode toInfrastructure(InfrastructureConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", config.getArn());
        node.put("name", config.getName());
        putOptional(node, "description", config.getDescription());
        if (config.getInstanceTypes() != null && !config.getInstanceTypes().isEmpty()) {
            ArrayNode types = node.putArray("instanceTypes");
            config.getInstanceTypes().forEach(types::add);
        }
        putOptional(node, "instanceProfileName", config.getInstanceProfileName());
        if (config.getSecurityGroupIds() != null && !config.getSecurityGroupIds().isEmpty()) {
            ArrayNode groups = node.putArray("securityGroupIds");
            config.getSecurityGroupIds().forEach(groups::add);
        }
        putOptional(node, "subnetId", config.getSubnetId());
        putOptional(node, "keyPair", config.getKeyPair());
        node.put("terminateInstanceOnFailure", config.isTerminateInstanceOnFailure());
        putOptional(node, "snsTopicArn", config.getSnsTopicArn());
        putOptional(node, "dateCreated", config.getDateCreated());
        putOptional(node, "dateUpdated", config.getDateUpdated());
        if (config.getLogging() != null && !config.getLogging().isNull()) {
            node.set("logging", config.getLogging());
        }
        if (config.getResourceTags() != null && !config.getResourceTags().isNull()) {
            node.set("resourceTags", config.getResourceTags());
        }
        if (config.getInstanceMetadataOptions() != null && !config.getInstanceMetadataOptions().isNull()) {
            node.set("instanceMetadataOptions", config.getInstanceMetadataOptions());
        }
        if (config.getPlacement() != null && !config.getPlacement().isNull()) {
            node.set("placement", config.getPlacement());
        }
        putTags(node, config.getTags());
        return node;
    }

    public ObjectNode toDistribution(DistributionConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", config.getArn());
        node.put("name", config.getName());
        putOptional(node, "description", config.getDescription());
        putOptional(node, "dateCreated", config.getDateCreated());
        putOptional(node, "dateUpdated", config.getDateUpdated());
        if (config.getDistributions() != null && !config.getDistributions().isNull()) {
            node.set("distributions", config.getDistributions());
        }
        putTags(node, config.getTags());
        return node;
    }

    public ObjectNode toPipeline(ImagePipeline pipeline) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", pipeline.getArn());
        node.put("name", pipeline.getName());
        putOptional(node, "description", pipeline.getDescription());
        putOptional(node, "platform", pipeline.getPlatform());
        node.put("enhancedImageMetadataEnabled", pipeline.isEnhancedImageMetadataEnabled());
        putOptional(node, "imageRecipeArn", pipeline.getImageRecipeArn());
        putOptional(node, "containerRecipeArn", pipeline.getContainerRecipeArn());
        putOptional(node, "infrastructureConfigurationArn", pipeline.getInfrastructureConfigurationArn());
        putOptional(node, "distributionConfigurationArn", pipeline.getDistributionConfigurationArn());
        putOptional(node, "status", pipeline.getStatus());
        putOptional(node, "dateCreated", pipeline.getDateCreated());
        putOptional(node, "dateUpdated", pipeline.getDateUpdated());
        putOptional(node, "dateLastRun", pipeline.getDateLastRun());
        putOptional(node, "lastRunStatus", pipeline.getLastRunStatus());
        putOptional(node, "executionRole", pipeline.getExecutionRole());
        if (pipeline.getImageTestsConfiguration() != null && !pipeline.getImageTestsConfiguration().isNull()) {
            node.set("imageTestsConfiguration", pipeline.getImageTestsConfiguration());
        }
        if (pipeline.getSchedule() != null && !pipeline.getSchedule().isNull()) {
            node.set("schedule", pipeline.getSchedule());
        }
        if (pipeline.getImageScanningConfiguration() != null && !pipeline.getImageScanningConfiguration().isNull()) {
            node.set("imageScanningConfiguration", pipeline.getImageScanningConfiguration());
        }
        if (pipeline.getImageTags() != null && !pipeline.getImageTags().isNull()) {
            node.set("imageTags", pipeline.getImageTags());
        }
        if (pipeline.getWorkflows() != null && !pipeline.getWorkflows().isNull()) {
            node.set("workflows", pipeline.getWorkflows());
        }
        if (pipeline.getLoggingConfiguration() != null && !pipeline.getLoggingConfiguration().isNull()) {
            node.set("loggingConfiguration", pipeline.getLoggingConfiguration());
        }
        putTags(node, pipeline.getTags());
        return node;
    }

    public ObjectNode toImage(ImageBuild image) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", image.getArn());
        node.put("name", image.getName());
        node.put("version", image.getVersion());
        putOptional(node, "type", image.getType());
        putOptional(node, "platform", image.getPlatform());
        ObjectNode state = node.putObject("state");
        state.put("status", image.getStatus());
        putOptional(state, "reason", image.getReason());
        putOptional(node, "sourcePipelineName", image.getSourcePipelineName());
        putOptional(node, "sourcePipelineArn", image.getSourcePipelineArn());
        putOptional(node, "dateCreated", image.getDateCreated());
        putOptional(node, "buildType", image.getBuildType());
        putOptional(node, "imageSource", image.getImageSource());
        if (image.getImageRecipeArn() != null) {
            recipes.get(image.getImageRecipeArn()).ifPresent(recipe -> node.set("imageRecipe", toRecipe(recipe)));
        }
        if (image.getInfrastructureConfigurationArn() != null) {
            infrastructures.get(image.getInfrastructureConfigurationArn())
                    .ifPresent(config -> node.set("infrastructureConfiguration", toInfrastructure(config)));
        }
        putTags(node, image.getTags());
        return node;
    }

    public ObjectNode toWorkflowExecution(WorkflowRun run) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workflowExecutionId", run.getWorkflowExecutionId());
        putOptional(node, "workflowBuildVersionArn", run.getWorkflowBuildVersionArn());
        putOptional(node, "imageBuildVersionArn", run.getImageBuildVersionArn());
        putOptional(node, "type", run.getType());
        putOptional(node, "status", run.getStatus());
        putOptional(node, "message", run.getMessage());
        node.put("totalStepCount", run.getSteps().size());
        long succeeded = run.getSteps().stream().filter(step -> "COMPLETED".equals(step.getStatus())).count();
        long failed = run.getSteps().stream().filter(step -> "FAILED".equals(step.getStatus())).count();
        long skipped = run.getSteps().stream().filter(step -> "SKIPPED".equals(step.getStatus())).count();
        node.put("totalStepsSucceeded", succeeded);
        node.put("totalStepsFailed", failed);
        node.put("totalStepsSkipped", skipped);
        putOptional(node, "startTime", run.getStartTime());
        putOptional(node, "endTime", run.getEndTime());
        return node;
    }

    public ObjectNode toWorkflowStep(WorkflowStep step) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("stepExecutionId", step.getStepExecutionId());
        putOptional(node, "workflowExecutionId", step.getWorkflowExecutionId());
        putOptional(node, "imageBuildVersionArn", step.getImageBuildVersionArn());
        putOptional(node, "workflowBuildVersionArn", step.getWorkflowBuildVersionArn());
        putOptional(node, "name", step.getName());
        putOptional(node, "action", step.getAction());
        putOptional(node, "status", step.getStatus());
        putOptional(node, "startTime", step.getStartTime());
        putOptional(node, "endTime", step.getEndTime());
        return node;
    }

    // ──────────────────────────── Internals ────────────────────────────

    private ObjectNode toComponentSummary(Component component) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("arn", component.getArn());
        item.put("name", component.getName());
        item.put("version", component.getVersion());
        putOptional(item, "platform", component.getPlatform());
        ObjectNode state = item.putObject("state");
        state.put("status", component.getStatus() == null ? "ACTIVE" : component.getStatus());
        putOptional(item, "type", component.getType());
        putOptional(item, "owner", component.getOwner());
        putOptional(item, "description", component.getDescription());
        putOptional(item, "changeDescription", component.getChangeDescription());
        putOptional(item, "dateCreated", component.getDateCreated());
        putTags(item, component.getTags());
        return item;
    }

    private ObjectNode toImageSummary(ImageBuild image) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("arn", image.getArn());
        item.put("name", image.getName());
        item.put("type", image.getType());
        item.put("version", image.getVersion());
        putOptional(item, "platform", image.getPlatform());
        ObjectNode state = item.putObject("state");
        state.put("status", image.getStatus());
        putOptional(state, "reason", image.getReason());
        putOptional(item, "owner", image.getOwner());
        putOptional(item, "dateCreated", image.getDateCreated());
        putOptional(item, "buildType", image.getBuildType());
        putOptional(item, "imageSource", image.getImageSource());
        putTags(item, image.getTags());
        return item;
    }

    private void applyInfrastructureFields(InfrastructureConfiguration config, JsonNode request) {
        if (request.has("description")) {
            config.setDescription(optionalText(request, "description"));
        }
        if (request.has("instanceTypes")) {
            config.setInstanceTypes(readStringList(request, "instanceTypes"));
        }
        if (request.has("securityGroupIds")) {
            config.setSecurityGroupIds(readStringList(request, "securityGroupIds"));
        }
        if (request.has("subnetId")) {
            config.setSubnetId(optionalText(request, "subnetId"));
        }
        if (request.has("keyPair")) {
            config.setKeyPair(optionalText(request, "keyPair"));
        }
        if (request.has("terminateInstanceOnFailure") && !request.get("terminateInstanceOnFailure").isNull()) {
            config.setTerminateInstanceOnFailure(request.get("terminateInstanceOnFailure").asBoolean(true));
        }
        if (request.has("snsTopicArn")) {
            config.setSnsTopicArn(optionalText(request, "snsTopicArn"));
        }
        if (request.has("logging")) {
            config.setLogging(copyNode(request.get("logging")));
        }
        if (request.has("resourceTags")) {
            config.setResourceTags(copyNode(request.get("resourceTags")));
        }
        if (request.has("instanceMetadataOptions")) {
            config.setInstanceMetadataOptions(copyNode(request.get("instanceMetadataOptions")));
        }
        if (request.has("placement")) {
            config.setPlacement(copyNode(request.get("placement")));
        }
    }

    private void applyPipelineFields(ImagePipeline pipeline, JsonNode request) {
        if (request.has("description")) {
            pipeline.setDescription(optionalText(request, "description"));
        }
        if (request.has("imageRecipeArn")) {
            pipeline.setImageRecipeArn(optionalText(request, "imageRecipeArn"));
        }
        if (request.has("containerRecipeArn")) {
            pipeline.setContainerRecipeArn(optionalText(request, "containerRecipeArn"));
        }
        if (request.has("infrastructureConfigurationArn")) {
            pipeline.setInfrastructureConfigurationArn(optionalText(request, "infrastructureConfigurationArn"));
        }
        if (request.has("distributionConfigurationArn")) {
            pipeline.setDistributionConfigurationArn(optionalText(request, "distributionConfigurationArn"));
        }
        if (request.has("status")) {
            String status = optionalText(request, "status");
            pipeline.setStatus(status == null ? "ENABLED" : requireEnumValue("status", status, PIPELINE_STATUSES));
        } else if (pipeline.getStatus() == null) {
            pipeline.setStatus("ENABLED");
        }
        if (request.has("enhancedImageMetadataEnabled") && !request.get("enhancedImageMetadataEnabled").isNull()) {
            pipeline.setEnhancedImageMetadataEnabled(request.get("enhancedImageMetadataEnabled").asBoolean(true));
        }
        if (request.has("executionRole")) {
            pipeline.setExecutionRole(optionalText(request, "executionRole"));
        }
        if (request.has("imageTestsConfiguration")) {
            pipeline.setImageTestsConfiguration(copyNode(request.get("imageTestsConfiguration")));
        }
        if (request.has("schedule")) {
            pipeline.setSchedule(copyNode(request.get("schedule")));
        }
        if (request.has("imageScanningConfiguration")) {
            pipeline.setImageScanningConfiguration(copyNode(request.get("imageScanningConfiguration")));
        }
        if (request.has("imageTags")) {
            pipeline.setImageTags(copyNode(request.get("imageTags")));
        }
        if (request.has("workflows")) {
            pipeline.setWorkflows(copyNode(request.get("workflows")));
        }
        if (request.has("loggingConfiguration")) {
            pipeline.setLoggingConfiguration(copyNode(request.get("loggingConfiguration")));
        }
    }

    private Component requireComponent(String arn) {
        if (arn == null || arn.isBlank()) {
            throw invalidParameter("componentBuildVersionArn must be a string.");
        }
        return components.get(arn).orElseThrow(() -> notFound(arn));
    }

    private ImageRecipe requireRecipe(String arn) {
        if (arn == null || arn.isBlank()) {
            throw invalidParameter("imageRecipeArn must be a string.");
        }
        return recipes.get(arn).orElseThrow(() -> notFound(arn));
    }

    private InfrastructureConfiguration requireInfrastructure(String arn) {
        if (arn == null || arn.isBlank()) {
            throw invalidParameter("infrastructureConfigurationArn must be a string.");
        }
        return infrastructures.get(arn).orElseThrow(() -> notFound(arn));
    }

    private DistributionConfiguration requireDistribution(String arn) {
        if (arn == null || arn.isBlank()) {
            throw invalidParameter("distributionConfigurationArn must be a string.");
        }
        return distributions.get(arn).orElseThrow(() -> notFound(arn));
    }

    private ImagePipeline requirePipeline(String arn) {
        if (arn == null || arn.isBlank()) {
            throw invalidParameter("imagePipelineArn must be a string.");
        }
        return pipelines.get(arn).orElseThrow(() -> notFound(arn));
    }

    private ImageBuild requireImage(String arn) {
        if (arn == null || arn.isBlank()) {
            throw invalidParameter("imageBuildVersionArn must be a string.");
        }
        return images.get(arn).orElseThrow(() -> notFound(arn));
    }

    private WorkflowRun requireWorkflow(String id) {
        if (id == null || id.isBlank()) {
            throw invalidParameter("workflowExecutionId must be a string.");
        }
        return workflows.get(id).orElseThrow(() -> notFound(id));
    }

    private WorkflowStep requireStep(String id) {
        if (id == null || id.isBlank()) {
            throw invalidParameter("stepExecutionId must be a string.");
        }
        for (WorkflowRun run : workflows.scan(key -> true)) {
            for (WorkflowStep step : run.getSteps()) {
                if (id.equals(step.getStepExecutionId())) {
                    return step;
                }
            }
        }
        throw notFound(id);
    }

    private record Tagged(String arn, Map<String, String> tags, Object entity) {
    }

    private Tagged requireTagged(String arn) {
        Optional<Component> component = components.get(arn);
        if (component.isPresent()) {
            return new Tagged(arn, component.get().getTags(), component.get());
        }
        Optional<ImageRecipe> recipe = recipes.get(arn);
        if (recipe.isPresent()) {
            return new Tagged(arn, recipe.get().getTags(), recipe.get());
        }
        Optional<InfrastructureConfiguration> infra = infrastructures.get(arn);
        if (infra.isPresent()) {
            return new Tagged(arn, infra.get().getTags(), infra.get());
        }
        Optional<DistributionConfiguration> distribution = distributions.get(arn);
        if (distribution.isPresent()) {
            return new Tagged(arn, distribution.get().getTags(), distribution.get());
        }
        Optional<ImagePipeline> pipeline = pipelines.get(arn);
        if (pipeline.isPresent()) {
            return new Tagged(arn, pipeline.get().getTags(), pipeline.get());
        }
        Optional<ImageBuild> image = images.get(arn);
        if (image.isPresent()) {
            return new Tagged(arn, image.get().getTags(), image.get());
        }
        throw notFound(arn);
    }

    private void persistTagged(Tagged tagged) {
        if (tagged.entity() instanceof Component component) {
            components.put(component.getArn(), component);
        } else if (tagged.entity() instanceof ImageRecipe recipe) {
            recipes.put(recipe.getArn(), recipe);
        } else if (tagged.entity() instanceof InfrastructureConfiguration config) {
            infrastructures.put(config.getArn(), config);
        } else if (tagged.entity() instanceof DistributionConfiguration distribution) {
            distributions.put(distribution.getArn(), distribution);
        } else if (tagged.entity() instanceof ImagePipeline pipeline) {
            pipelines.put(pipeline.getArn(), pipeline);
        } else if (tagged.entity() instanceof ImageBuild image) {
            images.put(image.getArn(), image);
        }
    }

    private int nextBuildNumber(String name, String version) {
        int max = 0;
        String prefix = "/" + name.toLowerCase(Locale.ROOT) + "/" + version + "/";
        for (ImageBuild image : images.scan(key -> true)) {
            if (image.getArn() != null && image.getArn().contains(prefix)) {
                max = Math.max(max, image.getBuildVersion());
            }
        }
        return max + 1;
    }

    private String resourceArn(String region, String type, String path) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), type + "/" + path).toString();
    }

    private static String versionArn(String buildArn) {
        int slash = buildArn.lastIndexOf('/');
        return slash < 0 ? buildArn : buildArn.substring(0, slash);
    }

    private static boolean recipeReferencesComponent(ImageRecipe recipe, String componentArn) {
        JsonNode components = recipe.getComponents();
        if (components == null || !components.isArray()) {
            return false;
        }
        for (JsonNode item : components) {
            JsonNode arn = item.get("componentArn");
            if (arn != null && componentArn.equals(arn.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String inferComponentType(String data) {
        if (data != null && data.contains("name: test")) {
            return "TEST";
        }
        return "BUILD";
    }

    private static String platformFromParent(String parentImage) {
        if (parentImage == null) {
            return "Linux";
        }
        String lower = parentImage.toLowerCase(Locale.ROOT);
        if (lower.contains("windows")) {
            return "Windows";
        }
        if (lower.contains("macos") || lower.contains("mac-os")) {
            return "macOS";
        }
        return "Linux";
    }

    private JsonNode copyNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.deepCopy();
    }

    private ObjectNode listEnvelope(String field) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray(field);
        return response;
    }

    private void putTags(ObjectNode parent, Map<String, String> tags) {
        ObjectNode tagsNode = parent.putObject("tags");
        if (tags != null) {
            tags.forEach(tagsNode::put);
        }
    }

    private static void putOptional(ObjectNode parent, String field, String value) {
        if (value != null) {
            parent.put(field, value);
        }
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static boolean ownerIsSelf(JsonNode request) {
        String owner = optionalText(request, "owner");
        return owner == null || "Self".equals(owner);
    }

    private static <T> List<T> sorted(Iterable<T> items, Comparator<T> comparator) {
        List<T> list = new ArrayList<>();
        items.forEach(list::add);
        list.sort(comparator);
        return list;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw invalidParameter(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidParameter(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw invalidParameter(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static String requireEnum(JsonNode parent, String field, Set<String> allowed) {
        return requireEnumValue(field, requireText(parent, field), allowed);
    }

    private static String requireEnumValue(String field, String value, Set<String> allowed) {
        if (!allowed.contains(value)) {
            throw invalidParameter(field + " must be one of " + allowed + ".");
        }
        return value;
    }

    private static void validateSemver(String version) {
        if (!SEMVER.matcher(version).matches()) {
            throw new AwsException("InvalidVersionNumberException",
                    "semanticVersion must be major.minor.patch.", 400);
        }
    }

    private static List<String> readStringList(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return new ArrayList<>();
        }
        JsonNode value = parent.get(field);
        if (!value.isArray()) {
            throw invalidParameter(field + " must be an array of strings.");
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (item == null || !item.isTextual()) {
                throw invalidParameter(field + " must be an array of strings.");
            }
            items.add(item.textValue());
        }
        return items;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!tagsNode.isObject()) {
            throw invalidParameter("tags must be an object.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            if (valueNode == null || valueNode.isNull()) {
                return;
            }
            if (!valueNode.isTextual()) {
                throw invalidParameter("tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), valueNode.textValue());
        });
        return tags;
    }

    private static AwsException notFound(String arn) {
        return new AwsException("ResourceNotFoundException", "Resource: " + arn + " not found", 404);
    }

    private static AwsException alreadyExists(String message) {
        return new AwsException("ResourceAlreadyExistsException", message, 400);
    }

    private static AwsException dependency(String message) {
        return new AwsException("ResourceDependencyException", message, 400);
    }

    private static AwsException invalidRequest(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException invalidParameter(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
