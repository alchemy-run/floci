package io.github.hectorvent.floci.services.databrew;

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
import io.github.hectorvent.floci.services.databrew.model.Dataset;
import io.github.hectorvent.floci.services.databrew.model.Job;
import io.github.hectorvent.floci.services.databrew.model.JobRun;
import io.github.hectorvent.floci.services.databrew.model.Project;
import io.github.hectorvent.floci.services.databrew.model.Recipe;
import io.github.hectorvent.floci.services.databrew.model.RecipePublishedVersion;
import io.github.hectorvent.floci.services.databrew.model.Ruleset;
import io.github.hectorvent.floci.services.databrew.model.Schedule;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AWS Glue DataBrew restJson1 — datasets, recipes, projects, jobs, rulesets, and schedules.
 *
 * <p>{@code CreateProject} samples the dataset source object the way live DataBrew
 * does (it reads the interactive sample). Missing S3 objects fail with
 * {@code ValidationException}. Tag APIs share {@code /tags/{arn}} via
 * {@link TagHandler} using ARN service {@code databrew} and PascalCase {@code Tags}.
 */
@ApplicationScoped
public class DataBrewService implements TagHandler {

    static final String SERVICE = "databrew";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;
    private static final String TOKEN_PREFIX = "databrew:v1:";
    private static final Set<String> SAMPLE_TYPES = Set.of("FIRST_N", "LAST_N", "RANDOM");
    private static final Set<String> RESOURCE_TYPES =
            Set.of("dataset", "recipe", "project", "job", "ruleset", "schedule");
    private static final Set<String> STOPPABLE_RUNS = Set.of("STARTING", "RUNNING");
    private static final Set<String> ACTIVE_RUNS = Set.of("STARTING", "RUNNING", "STOPPING");

    private final StorageBackend<String, Dataset> datasets;
    private final StorageBackend<String, Recipe> recipes;
    private final StorageBackend<String, Project> projects;
    private final StorageBackend<String, Job> jobs;
    private final StorageBackend<String, Ruleset> rulesets;
    private final StorageBackend<String, Schedule> schedules;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    @Inject
    public DataBrewService(
            StorageFactory storageFactory,
            RegionResolver regionResolver,
            S3Service s3Service,
            ObjectMapper objectMapper) {
        this(storageFactory.create("databrew", "databrew-datasets.json",
                        new TypeReference<Map<String, Dataset>>() {
                        }),
                storageFactory.create("databrew", "databrew-recipes.json",
                        new TypeReference<Map<String, Recipe>>() {
                        }),
                storageFactory.create("databrew", "databrew-projects.json",
                        new TypeReference<Map<String, Project>>() {
                        }),
                storageFactory.create("databrew", "databrew-jobs.json",
                        new TypeReference<Map<String, Job>>() {
                        }),
                storageFactory.create("databrew", "databrew-rulesets.json",
                        new TypeReference<Map<String, Ruleset>>() {
                        }),
                storageFactory.create("databrew", "databrew-schedules.json",
                        new TypeReference<Map<String, Schedule>>() {
                        }),
                regionResolver, s3Service, objectMapper);
    }

    DataBrewService(
            StorageBackend<String, Dataset> datasets,
            StorageBackend<String, Recipe> recipes,
            StorageBackend<String, Project> projects,
            StorageBackend<String, Job> jobs,
            StorageBackend<String, Ruleset> rulesets,
            StorageBackend<String, Schedule> schedules,
            RegionResolver regionResolver,
            S3Service s3Service,
            ObjectMapper objectMapper) {
        this.datasets = datasets;
        this.recipes = recipes;
        this.projects = projects;
        this.jobs = jobs;
        this.rulesets = rulesets;
        this.schedules = schedules;
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    // ── Datasets ────────────────────────────────────────────────────────────

    public synchronized Dataset createDataset(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateName(name, "Name");
        JsonNode input = requireObjectField(request, "Input");
        String key = storageKey(region, name);
        if (datasets.get(key).isPresent()) {
            throw conflict("The dataset " + name + " already exists.");
        }
        long now = nowSeconds();
        Dataset dataset = new Dataset();
        dataset.setName(name);
        dataset.setResourceArn(arn(region, "dataset/" + name));
        dataset.setFormat(optionalText(request, "Format"));
        dataset.setFormatOptions(copy(request.get("FormatOptions")));
        dataset.setInput(input.deepCopy());
        dataset.setPathOptions(copy(request.get("PathOptions")));
        dataset.setSource(sourceOf(input));
        dataset.setCreateDate(now);
        dataset.setLastModifiedDate(now);
        dataset.setTags(readTags(request.get("Tags")));
        datasets.put(key, dataset);
        return dataset;
    }

    public Dataset describeDataset(String region, String name) {
        return requireDataset(region, name);
    }

    public synchronized Dataset updateDataset(String region, String name, JsonNode request) {
        requireObject(request, "Request body");
        Dataset dataset = requireDataset(region, name);
        JsonNode input = requireObjectField(request, "Input");
        dataset.setFormat(optionalText(request, "Format"));
        dataset.setFormatOptions(copy(request.get("FormatOptions")));
        dataset.setInput(input.deepCopy());
        dataset.setPathOptions(copy(request.get("PathOptions")));
        dataset.setSource(sourceOf(input));
        dataset.setLastModifiedDate(nowSeconds());
        datasets.put(storageKey(region, name), dataset);
        return dataset;
    }

    public synchronized void deleteDataset(String region, String name) {
        requireDataset(region, name);
        for (Project project : projectsIn(region)) {
            if (name.equals(project.getDatasetName())) {
                throw conflict("The dataset " + name + " is being used by project " + project.getName() + ".");
            }
        }
        for (Job job : jobsIn(region)) {
            if (name.equals(job.getDatasetName())) {
                throw conflict("The dataset " + name + " is being used by job " + job.getName() + ".");
            }
        }
        datasets.delete(storageKey(region, name));
    }

    public Page<Dataset> listDatasets(String region, String maxResults, String nextToken) {
        List<Dataset> items = datasetsIn(region);
        items.sort(Comparator.comparing(Dataset::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResults, nextToken);
    }

    public ObjectNode toDescribe(Dataset dataset) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("CreateDate", dataset.getCreateDate());
        out.put("Name", dataset.getName());
        if (dataset.getFormat() != null) {
            out.put("Format", dataset.getFormat());
        }
        if (dataset.getFormatOptions() != null) {
            out.set("FormatOptions", dataset.getFormatOptions());
        }
        if (dataset.getInput() != null) {
            out.set("Input", dataset.getInput());
        }
        out.put("LastModifiedDate", dataset.getLastModifiedDate());
        if (dataset.getSource() != null) {
            out.put("Source", dataset.getSource());
        }
        if (dataset.getPathOptions() != null) {
            out.set("PathOptions", dataset.getPathOptions());
        }
        Map<String, String> tags = dataset.getTags();
        if (tags != null && !tags.isEmpty()) {
            ObjectNode tagsNode = out.putObject("Tags");
            tags.forEach(tagsNode::put);
        }
        if (dataset.getResourceArn() != null) {
            out.put("ResourceArn", dataset.getResourceArn());
        }
        return out;
    }

    public ObjectNode toSummary(Dataset dataset) {
        return toDescribe(dataset);
    }

    public ObjectNode toDataset(Dataset dataset) {
        return toDescribe(dataset);
    }

    public List<Dataset> listDatasets(String region) {
        return listDatasets(region, null, null).items();
    }

    public ObjectNode nameOnly(String name) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Name", name);
        return out;
    }

    // ── Recipes ─────────────────────────────────────────────────────────────

    public synchronized Recipe createRecipe(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateName(name, "Name");
        JsonNode steps = requireArrayField(request, "Steps");
        String key = storageKey(region, name);
        if (recipes.get(key).isPresent()) {
            throw conflict("The recipe " + name + " already exists.");
        }
        long now = nowSeconds();
        Recipe recipe = new Recipe();
        recipe.setName(name);
        recipe.setResourceArn(arn(region, "recipe/" + name));
        recipe.setDescription(optionalText(request, "Description"));
        recipe.setSteps(steps.deepCopy());
        recipe.setCreateDate(now);
        recipe.setLastModifiedDate(now);
        recipe.setTags(readTags(request.get("Tags")));
        recipes.put(key, recipe);
        return recipe;
    }

    public Recipe describeRecipe(String region, String name, String recipeVersion) {
        Recipe recipe = requireRecipe(region, name);
        String version = recipeVersion == null || recipeVersion.isBlank()
                ? Recipe.LATEST_WORKING
                : recipeVersion;
        if (Recipe.LATEST_WORKING.equals(version)) {
            return recipe;
        }
        if (Recipe.LATEST_PUBLISHED.equals(version)) {
            RecipePublishedVersion latest = latestPublished(recipe);
            if (latest == null) {
                throw notFound("recipe", name);
            }
            return publishedView(recipe, latest);
        }
        RecipePublishedVersion published = findPublished(recipe, version);
        if (published == null) {
            throw notFound("recipe", name);
        }
        return publishedView(recipe, published);
    }

    public synchronized Recipe updateRecipe(String region, String name, JsonNode request) {
        requireObject(request, "Request body");
        Recipe recipe = requireRecipe(region, name);
        if (request.has("Description")) {
            recipe.setDescription(optionalText(request, "Description"));
        }
        if (request.has("Steps")) {
            recipe.setSteps(requireArrayField(request, "Steps").deepCopy());
        }
        recipe.setLastModifiedDate(nowSeconds());
        recipes.put(storageKey(region, name), recipe);
        return recipe;
    }

    public synchronized Recipe publishRecipe(String region, String name, JsonNode request) {
        Recipe recipe = requireRecipe(region, name);
        if (request != null && request.has("Description")) {
            recipe.setDescription(optionalText(request, "Description"));
        }
        RecipePublishedVersion published = new RecipePublishedVersion();
        published.setRecipeVersion(nextPublishedVersion(recipe));
        published.setDescription(recipe.getDescription());
        published.setSteps(recipe.getSteps() == null ? null : recipe.getSteps().deepCopy());
        published.setPublishedDate(nowSeconds());
        recipe.getPublished().add(published);
        recipe.setLastModifiedDate(nowSeconds());
        recipes.put(storageKey(region, name), recipe);
        return recipe;
    }

    public Page<Recipe> listRecipes(String region, String recipeVersion, String maxResults, String nextToken) {
        List<Recipe> items = new ArrayList<>();
        boolean working = Recipe.LATEST_WORKING.equals(recipeVersion);
        for (Recipe recipe : recipesIn(region)) {
            if (working) {
                items.add(recipe);
            } else {
                RecipePublishedVersion latest = latestPublished(recipe);
                if (latest != null) {
                    items.add(publishedView(recipe, latest));
                }
            }
        }
        items.sort(Comparator.comparing(Recipe::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResults, nextToken);
    }

    public Page<Recipe> listRecipeVersions(String region, String name, String maxResults, String nextToken) {
        Recipe recipe = requireRecipe(region, name);
        List<Recipe> items = new ArrayList<>();
        for (RecipePublishedVersion published : recipe.getPublished()) {
            items.add(publishedView(recipe, published));
        }
        items.sort(Comparator.comparing(Recipe::getName).thenComparing(
                r -> r.getPublished().isEmpty() ? "" : r.getPublished().get(0).getRecipeVersion()));
        return page(items, maxResults, nextToken);
    }

    public synchronized void batchDeleteRecipeVersion(String region, String name, JsonNode request) {
        Recipe recipe = requireRecipe(region, name);
        requireObject(request, "Request body");
        JsonNode versions = requireArrayField(request, "RecipeVersions");
        List<String> toRemove = new ArrayList<>();
        for (JsonNode node : versions) {
            if (node != null && node.isTextual()) {
                toRemove.add(node.asText());
            }
        }
        recipe.getPublished().removeIf(published -> toRemove.contains(published.getRecipeVersion()));
        if (toRemove.contains(Recipe.LATEST_WORKING) && recipe.getPublished().isEmpty()) {
            assertRecipeUnused(region, name);
            recipes.delete(storageKey(region, name));
            return;
        }
        recipe.setLastModifiedDate(nowSeconds());
        recipes.put(storageKey(region, name), recipe);
    }

    public synchronized void deleteRecipeVersion(String region, String name, String recipeVersion) {
        Recipe recipe = requireRecipe(region, name);
        if (Recipe.LATEST_WORKING.equals(recipeVersion)) {
            assertRecipeUnused(region, name);
            if (recipe.getPublished().isEmpty()) {
                recipes.delete(storageKey(region, name));
                return;
            }
            recipe.setSteps(objectMapper.createArrayNode());
            recipe.setDescription(null);
            recipe.setLastModifiedDate(nowSeconds());
            recipes.put(storageKey(region, name), recipe);
            return;
        }
        boolean removed = recipe.getPublished().removeIf(p -> recipeVersion.equals(p.getRecipeVersion()));
        if (!removed) {
            throw notFound("recipe", name);
        }
        recipe.setLastModifiedDate(nowSeconds());
        recipes.put(storageKey(region, name), recipe);
    }

    public ObjectNode toDescribeRecipe(Recipe recipe, String recipeVersion) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Name", recipe.getName());
        if (recipe.getDescription() != null) {
            out.put("Description", recipe.getDescription());
        }
        if (recipe.getSteps() != null) {
            out.set("Steps", recipe.getSteps());
        }
        out.put("RecipeVersion", resolveWireVersion(recipe, recipeVersion));
        out.put("CreateDate", recipe.getCreateDate());
        out.put("LastModifiedDate", recipe.getLastModifiedDate());
        if (recipe.getResourceArn() != null) {
            out.put("ResourceArn", recipe.getResourceArn());
        }
        ObjectNode tagsNode = out.putObject("Tags");
        Map<String, String> tags = recipe.getTags();
        if (tags != null) {
            tags.forEach(tagsNode::put);
        }
        return out;
    }

    public List<ObjectNode> listRecipes(String region, String recipeVersion) {
        List<ObjectNode> out = new ArrayList<>();
        for (Recipe recipe : listRecipes(region, recipeVersion, null, null).items()) {
            out.add(toDescribeRecipe(recipe, recipeVersion));
        }
        return out;
    }

    public List<ObjectNode> listRecipeVersions(String region, String name) {
        List<ObjectNode> out = new ArrayList<>();
        for (Recipe recipe : listRecipeVersions(region, name, null, null).items()) {
            String version = recipe.getPublished() == null || recipe.getPublished().isEmpty()
                    ? Recipe.LATEST_WORKING
                    : recipe.getPublished().get(0).getRecipeVersion();
            out.add(toDescribeRecipe(recipe, version));
        }
        return out;
    }

    /**
     * {@code LATEST_WORKING} listings keep that alias even after publish. Default
     * {@code ListRecipes} and {@code ListRecipeVersions} items are published views
     * whose single {@code published} entry is the wire version.
     */
    private static String resolveWireVersion(Recipe recipe, String recipeVersion) {
        if (Recipe.LATEST_WORKING.equals(recipeVersion)) {
            return Recipe.LATEST_WORKING;
        }
        if (recipeVersion == null || recipeVersion.isBlank() || Recipe.LATEST_PUBLISHED.equals(recipeVersion)) {
            if (recipe.getPublished() != null && !recipe.getPublished().isEmpty()) {
                return recipe.getPublished().get(recipe.getPublished().size() - 1).getRecipeVersion();
            }
            return Recipe.LATEST_WORKING;
        }
        return recipeVersion;
    }

    // ── Projects ────────────────────────────────────────────────────────────

    public synchronized Project createProject(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateName(name, "Name");
        String datasetName = requireText(request, "DatasetName");
        String recipeName = requireText(request, "RecipeName");
        String roleArn = requireText(request, "RoleArn");
        Dataset dataset = requireDataset(region, datasetName);
        requireRecipe(region, recipeName);
        validateSampleSource(dataset);
        String key = storageKey(region, name);
        if (projects.get(key).isPresent()) {
            throw conflict("The project " + name + " already exists.");
        }
        long now = nowSeconds();
        Project project = new Project();
        project.setName(name);
        project.setResourceArn(arn(region, "project/" + name));
        project.setDatasetName(datasetName);
        project.setRecipeName(recipeName);
        project.setRoleArn(roleArn);
        project.setSample(sampleOf(request.get("Sample")));
        project.setCreateDate(now);
        project.setLastModifiedDate(now);
        project.setTags(readTags(request.get("Tags")));
        projects.put(key, project);
        return project;
    }

    public Project describeProject(String region, String name) {
        return requireProject(region, name);
    }

    public synchronized Project updateProject(String region, String name, JsonNode request) {
        requireObject(request, "Request body");
        Project project = requireProject(region, name);
        project.setRoleArn(requireText(request, "RoleArn"));
        if (request.has("Sample") && !request.get("Sample").isNull()) {
            project.setSample(sampleOf(request.get("Sample")));
        }
        project.setLastModifiedDate(nowSeconds());
        projects.put(storageKey(region, name), project);
        return project;
    }

    public synchronized void deleteProject(String region, String name) {
        requireProject(region, name);
        projects.delete(storageKey(region, name));
    }

    public Page<Project> listProjects(String region, String maxResults, String nextToken) {
        List<Project> items = projectsIn(region);
        items.sort(Comparator.comparing(Project::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResults, nextToken);
    }

    public ObjectNode toProject(Project project) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Name", project.getName());
        if (project.getDatasetName() != null) {
            out.put("DatasetName", project.getDatasetName());
        }
        if (project.getRecipeName() != null) {
            out.put("RecipeName", project.getRecipeName());
        }
        if (project.getRoleArn() != null) {
            out.put("RoleArn", project.getRoleArn());
        }
        if (project.getSample() != null) {
            out.set("Sample", project.getSample());
        }
        if (project.getResourceArn() != null) {
            out.put("ResourceArn", project.getResourceArn());
        }
        if (project.getSessionStatus() != null) {
            out.put("SessionStatus", project.getSessionStatus());
        }
        if (project.getOpenDate() != null) {
            out.put("OpenDate", project.getOpenDate());
        }
        out.put("CreateDate", project.getCreateDate());
        out.put("LastModifiedDate", project.getLastModifiedDate());
        ObjectNode tagsNode = out.putObject("Tags");
        Map<String, String> tags = project.getTags();
        if (tags != null) {
            tags.forEach(tagsNode::put);
        }
        return out;
    }

    public synchronized Project startProjectSession(String region, String name, JsonNode request) {
        Project project = requireProject(region, name);
        boolean assumeControl = booleanOrDefault(request, "AssumeControl", false);
        if (project.getClientSessionId() != null
                && !"CLOSED".equals(project.getSessionStatus())
                && !assumeControl) {
            throw conflict("Project " + name + " already has an open session.");
        }
        long now = nowSeconds();
        project.setClientSessionId(UUID.randomUUID().toString());
        project.setSessionStatus("READY");
        project.setOpenDate(now);
        project.setLastModifiedDate(now);
        projects.put(storageKey(region, name), project);
        return project;
    }

    public synchronized int sendProjectSessionAction(String region, String name, JsonNode request) {
        Project project = requireProject(region, name);
        if (project.getClientSessionId() == null || !"READY".equals(project.getSessionStatus())) {
            throw conflict("Project " + name + " does not have a ready session.");
        }
        int actionId = project.getNextActionId();
        project.setNextActionId(actionId + 1);
        project.setLastModifiedDate(nowSeconds());
        projects.put(storageKey(region, name), project);
        return actionId;
    }

    // ── Jobs ────────────────────────────────────────────────────────────────

    public synchronized Job createProfileJob(String region, JsonNode request) {
        return createJob(region, request, "PROFILE");
    }

    public synchronized Job createRecipeJob(String region, JsonNode request) {
        return createJob(region, request, "RECIPE");
    }

    private Job createJob(String region, JsonNode request, String type) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateName(name, "Name");
        String roleArn = requireText(request, "RoleArn");
        String key = storageKey(region, name);
        if (jobs.get(key).isPresent()) {
            throw conflict("The job " + name + " already exists.");
        }
        if ("PROFILE".equals(type)) {
            requireDataset(region, requireText(request, "DatasetName"));
            requireObjectField(request, "OutputLocation");
        } else {
            String datasetName = optionalText(request, "DatasetName");
            String projectName = optionalText(request, "ProjectName");
            if (datasetName == null && projectName == null) {
                throw validation("Recipe jobs require DatasetName or ProjectName.");
            }
            if (datasetName != null) {
                requireDataset(region, datasetName);
            }
            if (projectName != null) {
                requireProject(region, projectName);
            }
            JsonNode recipeReference = request.get("RecipeReference");
            if (recipeReference != null && recipeReference.isObject()) {
                String recipeName = textOrNull(recipeReference, "Name");
                if (recipeName == null) {
                    throw validation("RecipeReference.Name is required.");
                }
                Recipe recipe = requireRecipe(region, recipeName);
                String version = textOrNull(recipeReference, "RecipeVersion");
                if (version == null || Recipe.LATEST_PUBLISHED.equals(version)) {
                    if (latestPublished(recipe) == null) {
                        throw validation("Recipe " + recipeName + " has no published version.");
                    }
                } else if (!Recipe.LATEST_WORKING.equals(version) && findPublished(recipe, version) == null) {
                    throw notFound("recipe", recipeName);
                }
            }
        }
        long now = nowSeconds();
        Job job = new Job();
        job.setName(name);
        job.setRegion(region);
        job.setAccountId(regionResolver.getAccountId());
        job.setResourceArn(arn(region, "job/" + name));
        job.setType(type);
        job.setRoleArn(roleArn);
        job.setDatasetName(optionalText(request, "DatasetName"));
        job.setProjectName(optionalText(request, "ProjectName"));
        job.setRecipeReference(copy(request.get("RecipeReference")));
        job.setOutputLocation(copy(request.get("OutputLocation")));
        job.setOutputs(copy(request.get("Outputs")));
        JsonNode jobSample = copy(request.get("JobSample"));
        if (jobSample == null && "PROFILE".equals(type)) {
            ObjectNode sample = objectMapper.createObjectNode();
            sample.put("Mode", "CUSTOM_ROWS");
            sample.put("Size", 20000);
            jobSample = sample;
        }
        job.setJobSample(jobSample);
        job.setProfileConfiguration(copy(request.get("Configuration")));
        job.setValidationConfigurations(copy(request.get("ValidationConfigurations")));
        job.setEncryptionKeyArn(optionalText(request, "EncryptionKeyArn"));
        job.setEncryptionMode(optionalText(request, "EncryptionMode"));
        String logSubscription = optionalText(request, "LogSubscription");
        job.setLogSubscription(logSubscription == null ? "ENABLE" : logSubscription);
        Integer maxCapacity = optionalInt(request, "MaxCapacity");
        job.setMaxCapacity(maxCapacity == null ? 5 : maxCapacity);
        Integer maxRetries = optionalInt(request, "MaxRetries");
        job.setMaxRetries(maxRetries == null ? 0 : maxRetries);
        Integer timeout = optionalInt(request, "Timeout");
        job.setTimeout(timeout == null ? 2880 : timeout);
        job.setTags(readTags(request.get("Tags")));
        job.setCreateDate(now);
        job.setLastModifiedDate(now);
        jobs.put(key, job);
        return job;
    }

    public Job describeJob(String region, String name) {
        return requireJob(region, name);
    }

    public synchronized Job updateProfileJob(String region, String name, JsonNode request) {
        return updateJob(region, name, request, "PROFILE");
    }

    public synchronized Job updateRecipeJob(String region, String name, JsonNode request) {
        return updateJob(region, name, request, "RECIPE");
    }

    private Job updateJob(String region, String name, JsonNode request, String type) {
        requireObject(request, "Request body");
        Job job = requireJob(region, name);
        if (!type.equals(job.getType())) {
            throw validation("Job " + name + " is a " + job.getType() + " job.");
        }
        if (request.has("RoleArn")) {
            job.setRoleArn(requireText(request, "RoleArn"));
        }
        if (request.has("OutputLocation")) {
            job.setOutputLocation(copy(request.get("OutputLocation")));
        }
        if (request.has("Outputs")) {
            job.setOutputs(copy(request.get("Outputs")));
        }
        if (request.has("JobSample")) {
            job.setJobSample(copy(request.get("JobSample")));
        }
        if (request.has("Configuration")) {
            job.setProfileConfiguration(copy(request.get("Configuration")));
        }
        if (request.has("ValidationConfigurations")) {
            job.setValidationConfigurations(copy(request.get("ValidationConfigurations")));
        }
        if (request.has("EncryptionKeyArn")) {
            job.setEncryptionKeyArn(textOrNull(request, "EncryptionKeyArn"));
        }
        if (request.has("EncryptionMode")) {
            job.setEncryptionMode(textOrNull(request, "EncryptionMode"));
        }
        if (request.has("LogSubscription")) {
            job.setLogSubscription(textOrNull(request, "LogSubscription"));
        }
        if (request.has("MaxCapacity")) {
            job.setMaxCapacity(optionalInt(request, "MaxCapacity"));
        }
        if (request.has("MaxRetries")) {
            job.setMaxRetries(optionalInt(request, "MaxRetries"));
        }
        if (request.has("Timeout")) {
            job.setTimeout(optionalInt(request, "Timeout"));
        }
        job.setLastModifiedDate(nowSeconds());
        jobs.put(storageKey(region, name), job);
        return job;
    }

    public synchronized void deleteJob(String region, String name) {
        Job job = requireJob(region, name);
        for (JobRun run : job.getRuns()) {
            if (ACTIVE_RUNS.contains(run.getState())) {
                throw conflict("Job " + name + " has an in-flight run and cannot be deleted.");
            }
        }
        jobs.delete(storageKey(region, name));
    }

    public List<Job> listJobs(String region, String datasetName, String projectName) {
        return jobsIn(region).stream()
                .filter(job -> datasetName == null || datasetName.equals(job.getDatasetName()))
                .filter(job -> projectName == null || projectName.equals(job.getProjectName()))
                .sorted(Comparator.comparing(Job::getName, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public synchronized JobRun startJobRun(String region, String name) {
        Job job = requireJob(region, name);
        String runId = "db_" + UUID.randomUUID().toString().replace("-", "");
        long now = nowSeconds();
        JobRun run = new JobRun();
        run.setJobName(name);
        run.setRunId(runId);
        run.setState("RUNNING");
        run.setDatasetName(job.getDatasetName());
        run.setRecipeReference(job.getRecipeReference());
        run.setOutputs(job.getOutputs());
        run.setJobSample(job.getJobSample());
        run.setAttempt(1);
        run.setStartedOn(now);
        job.getRuns().add(run);
        job.setLastModifiedDate(now);
        jobs.put(storageKey(region, name), job);
        return run;
    }

    public List<JobRun> listJobRuns(String region, String name) {
        Job job = requireJob(region, name);
        List<JobRun> runs = new ArrayList<>(job.getRuns());
        runs.sort(Comparator.comparingLong(JobRun::getStartedOn).reversed());
        return runs;
    }

    public JobRun describeJobRun(String region, String name, String runId) {
        Job job = requireJob(region, name);
        for (JobRun run : job.getRuns()) {
            if (runId.equals(run.getRunId())) {
                return run;
            }
        }
        throw notFound("job run", runId);
    }

    public ObjectNode startJobRunResponse(JobRun run) {
        ObjectNode out = objectMapper.createObjectNode();
        if (run != null && run.getRunId() != null) {
            out.put("RunId", run.getRunId());
        }
        return out;
    }

    public synchronized JobRun stopJobRun(String region, String name, String runId) {
        Job job = requireJob(region, name);
        JobRun run = describeJobRun(region, name, runId);
        if (!STOPPABLE_RUNS.contains(run.getState())) {
            throw validation("Job run " + runId + " is " + run.getState() + " and cannot be stopped.");
        }
        long now = nowSeconds();
        run.setState("STOPPED");
        run.setCompletedOn(now);
        job.setLastModifiedDate(now);
        jobs.put(storageKey(region, name), job);
        return run;
    }

    public ObjectNode toJob(Job job) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Name", job.getName());
        if (job.getType() != null) {
            out.put("Type", job.getType());
        }
        if (job.getDatasetName() != null) {
            out.put("DatasetName", job.getDatasetName());
        }
        if (job.getProjectName() != null) {
            out.put("ProjectName", job.getProjectName());
        }
        if (job.getRoleArn() != null) {
            out.put("RoleArn", job.getRoleArn());
        }
        if (job.getResourceArn() != null) {
            out.put("ResourceArn", job.getResourceArn());
        }
        if (job.getOutputLocation() != null) {
            out.set("OutputLocation", job.getOutputLocation());
        }
        if (job.getOutputs() != null) {
            out.set("Outputs", job.getOutputs());
        }
        if (job.getRecipeReference() != null) {
            out.set("RecipeReference", job.getRecipeReference());
        }
        if (job.getJobSample() != null) {
            out.set("JobSample", job.getJobSample());
        }
        if (job.getProfileConfiguration() != null) {
            out.set("ProfileConfiguration", job.getProfileConfiguration());
        }
        if (job.getValidationConfigurations() != null) {
            out.set("ValidationConfigurations", job.getValidationConfigurations());
        }
        if (job.getEncryptionKeyArn() != null) {
            out.put("EncryptionKeyArn", job.getEncryptionKeyArn());
        }
        if (job.getEncryptionMode() != null) {
            out.put("EncryptionMode", job.getEncryptionMode());
        }
        if (job.getLogSubscription() != null) {
            out.put("LogSubscription", job.getLogSubscription());
        }
        if (job.getMaxCapacity() != null) {
            out.put("MaxCapacity", job.getMaxCapacity());
        }
        if (job.getMaxRetries() != null) {
            out.put("MaxRetries", job.getMaxRetries());
        }
        if (job.getTimeout() != null) {
            out.put("Timeout", job.getTimeout());
        }
        out.put("CreateDate", job.getCreateDate());
        out.put("LastModifiedDate", job.getLastModifiedDate());
        ObjectNode tagsNode = out.putObject("Tags");
        Map<String, String> tags = job.getTags();
        if (tags != null) {
            tags.forEach(tagsNode::put);
        }
        return out;
    }

    public ObjectNode toJobRun(JobRun run) {
        ObjectNode out = objectMapper.createObjectNode();
        if (run.getRunId() != null) {
            out.put("RunId", run.getRunId());
        }
        if (run.getJobName() != null) {
            out.put("JobName", run.getJobName());
        }
        if (run.getState() != null) {
            out.put("State", run.getState());
        }
        if (run.getDatasetName() != null) {
            out.put("DatasetName", run.getDatasetName());
        }
        out.put("Attempt", run.getAttempt());
        out.put("StartedOn", run.getStartedOn());
        if (run.getCompletedOn() != null) {
            out.put("CompletedOn", run.getCompletedOn());
        }
        if (run.getRecipeReference() != null) {
            out.set("RecipeReference", run.getRecipeReference());
        }
        if (run.getOutputs() != null) {
            out.set("Outputs", run.getOutputs());
        }
        if (run.getJobSample() != null) {
            out.set("JobSample", run.getJobSample());
        }
        return out;
    }

    // ── Rulesets ────────────────────────────────────────────────────────────

    public synchronized Ruleset createRuleset(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateName(name, "Name");
        String targetArn = requireText(request, "TargetArn");
        JsonNode rules = requireArrayField(request, "Rules");
        String key = storageKey(region, name);
        if (rulesets.get(key).isPresent()) {
            throw conflict("The ruleset " + name + " already exists.");
        }
        long now = nowSeconds();
        Ruleset ruleset = new Ruleset();
        ruleset.setName(name);
        ruleset.setRegion(region);
        ruleset.setResourceArn(arn(region, "ruleset/" + name));
        ruleset.setDescription(optionalText(request, "Description"));
        ruleset.setTargetArn(targetArn);
        ruleset.setRules(rules.deepCopy());
        ruleset.setTags(readTags(request.get("Tags")));
        ruleset.setCreateDate(now);
        ruleset.setLastModifiedDate(now);
        rulesets.put(key, ruleset);
        return ruleset;
    }

    public Ruleset describeRuleset(String region, String name) {
        return requireRuleset(region, name);
    }

    public synchronized Ruleset updateRuleset(String region, String name, JsonNode request) {
        requireObject(request, "Request body");
        Ruleset ruleset = requireRuleset(region, name);
        if (request.has("Description")) {
            ruleset.setDescription(textOrNull(request, "Description"));
        }
        if (request.has("Rules")) {
            ruleset.setRules(requireArrayField(request, "Rules").deepCopy());
        }
        ruleset.setLastModifiedDate(nowSeconds());
        rulesets.put(storageKey(region, name), ruleset);
        return ruleset;
    }

    public synchronized Ruleset deleteRuleset(String region, String name) {
        Ruleset ruleset = requireRuleset(region, name);
        rulesets.delete(storageKey(region, name));
        return ruleset;
    }

    public Page<Ruleset> listRulesets(String region, String targetArn, String maxResults, String nextToken) {
        List<Ruleset> items = rulesetsIn(region);
        if (targetArn != null && !targetArn.isBlank()) {
            items.removeIf(ruleset -> !targetArn.equals(ruleset.getTargetArn()));
        }
        items.sort(Comparator.comparing(Ruleset::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResults, nextToken);
    }

    public List<Ruleset> listRulesets(String region, String targetArn) {
        return listRulesets(region, targetArn, null, null).items();
    }

    public ObjectNode toDescribe(Ruleset ruleset) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Name", ruleset.getName());
        if (ruleset.getDescription() != null) {
            out.put("Description", ruleset.getDescription());
        }
        if (ruleset.getTargetArn() != null) {
            out.put("TargetArn", ruleset.getTargetArn());
        }
        if (ruleset.getRules() != null) {
            out.set("Rules", ruleset.getRules());
        }
        out.put("CreateDate", ruleset.getCreateDate());
        out.put("LastModifiedDate", ruleset.getLastModifiedDate());
        if (ruleset.getResourceArn() != null) {
            out.put("ResourceArn", ruleset.getResourceArn());
        }
        Map<String, String> tags = ruleset.getTags();
        if (tags != null && !tags.isEmpty()) {
            ObjectNode tagsNode = out.putObject("Tags");
            tags.forEach(tagsNode::put);
        }
        return out;
    }

    public ObjectNode toSummary(Ruleset ruleset) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Name", ruleset.getName());
        if (ruleset.getDescription() != null) {
            out.put("Description", ruleset.getDescription());
        }
        if (ruleset.getTargetArn() != null) {
            out.put("TargetArn", ruleset.getTargetArn());
        }
        JsonNode rules = ruleset.getRules();
        out.put("RuleCount", rules != null && rules.isArray() ? rules.size() : 0);
        out.put("CreateDate", ruleset.getCreateDate());
        out.put("LastModifiedDate", ruleset.getLastModifiedDate());
        if (ruleset.getResourceArn() != null) {
            out.put("ResourceArn", ruleset.getResourceArn());
        }
        Map<String, String> tags = ruleset.getTags();
        if (tags != null && !tags.isEmpty()) {
            ObjectNode tagsNode = out.putObject("Tags");
            tags.forEach(tagsNode::put);
        }
        return out;
    }

    // ── Schedules ───────────────────────────────────────────────────────────

    public synchronized Schedule createSchedule(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateName(name, "Name");
        String cron = requireText(request, "CronExpression");
        String key = storageKey(region, name);
        if (schedules.get(key).isPresent()) {
            throw conflict("The schedule " + name + " already exists.");
        }
        long now = nowSeconds();
        Schedule schedule = new Schedule();
        schedule.setName(name);
        schedule.setRegion(region);
        schedule.setResourceArn(arn(region, "schedule/" + name));
        schedule.setCronExpression(cron);
        schedule.setJobNames(readStringArray(request.get("JobNames"), "JobNames"));
        schedule.setTags(readTags(request.get("Tags")));
        schedule.setCreateDate(now);
        schedule.setLastModifiedDate(now);
        schedules.put(key, schedule);
        return schedule;
    }

    public Schedule describeSchedule(String region, String name) {
        return requireSchedule(region, name);
    }

    public synchronized Schedule updateSchedule(String region, String name, JsonNode request) {
        requireObject(request, "Request body");
        Schedule schedule = requireSchedule(region, name);
        schedule.setCronExpression(requireText(request, "CronExpression"));
        if (request.has("JobNames")) {
            schedule.setJobNames(readStringArray(request.get("JobNames"), "JobNames"));
        }
        schedule.setLastModifiedDate(nowSeconds());
        schedules.put(storageKey(region, name), schedule);
        return schedule;
    }

    public synchronized void deleteSchedule(String region, String name) {
        requireSchedule(region, name);
        schedules.delete(storageKey(region, name));
    }

    public Page<Schedule> listSchedules(String region, String jobName, String maxResults, String nextToken) {
        List<Schedule> items = schedulesIn(region);
        if (jobName != null && !jobName.isBlank()) {
            items.removeIf(schedule -> !schedule.getJobNames().contains(jobName));
        }
        items.sort(Comparator.comparing(Schedule::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResults, nextToken);
    }

    public List<Schedule> listSchedules(String region, String jobName) {
        return listSchedules(region, jobName, null, null).items();
    }

    public ObjectNode toSchedule(Schedule schedule) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Name", schedule.getName());
        if (schedule.getCronExpression() != null) {
            out.put("CronExpression", schedule.getCronExpression());
        }
        List<String> jobNames = schedule.getJobNames();
        if (jobNames != null && !jobNames.isEmpty()) {
            ArrayNode names = out.putArray("JobNames");
            jobNames.forEach(names::add);
        }
        out.put("CreateDate", schedule.getCreateDate());
        out.put("LastModifiedDate", schedule.getLastModifiedDate());
        if (schedule.getResourceArn() != null) {
            out.put("ResourceArn", schedule.getResourceArn());
        }
        out.put("AccountId", regionResolver.getAccountId());
        Map<String, String> tags = schedule.getTags();
        if (tags != null && !tags.isEmpty()) {
            ObjectNode tagsNode = out.putObject("Tags");
            tags.forEach(tagsNode::put);
        }
        return out;
    }

    // ── Tags ────────────────────────────────────────────────────────────────

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(tagsFor(region, arn));
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        tagged.setTags(current);
        tagged.save();
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        tagged.setTags(current);
        tagged.save();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private void validateSampleSource(Dataset dataset) {
        if (s3Service == null || dataset.getInput() == null) {
            return;
        }
        JsonNode s3 = dataset.getInput().get("S3InputDefinition");
        if (s3 == null || s3.isNull() || !s3.isObject()) {
            return;
        }
        String bucket = textOrNull(s3, "Bucket");
        if (bucket == null) {
            throw validation("Input.S3InputDefinition.Bucket is required.");
        }
        String key = s3.path("Key").asText("");
        try {
            if (key.isEmpty() || key.endsWith("/")) {
                if (s3Service.listObjects(bucket, key.isEmpty() ? null : key, null, 1).isEmpty()) {
                    throw validation("Unable to sample the dataset source. The S3 location is empty.");
                }
            } else {
                s3Service.headObject(bucket, key);
            }
        } catch (AwsException e) {
            if ("ValidationException".equals(e.getErrorCode())) {
                throw e;
            }
            throw validation("Unable to sample the dataset source: " + e.getMessage());
        }
    }

    private JsonNode sampleOf(JsonNode sample) {
        if (sample == null || sample.isNull()) {
            ObjectNode defaults = objectMapper.createObjectNode();
            defaults.put("Type", "FIRST_N");
            defaults.put("Size", 500);
            return defaults;
        }
        if (!sample.isObject()) {
            throw validation("Sample must be a JSON object.");
        }
        String type = textOrNull(sample, "Type");
        if (type == null) {
            throw validation("Sample.Type is required.");
        }
        if (!SAMPLE_TYPES.contains(type)) {
            throw validation("Sample.Type must be FIRST_N, LAST_N, or RANDOM.");
        }
        ObjectNode copy = objectMapper.createObjectNode();
        copy.put("Type", type);
        int size = 500;
        if (sample.has("Size") && !sample.get("Size").isNull()) {
            if (!sample.get("Size").isNumber()) {
                throw validation("Sample.Size must be an integer between 1 and 5000.");
            }
            size = sample.get("Size").intValue();
            if (size < 1 || size > 5000) {
                throw validation("Sample.Size must be an integer between 1 and 5000.");
            }
        }
        copy.put("Size", size);
        return copy;
    }

    private Dataset requireDataset(String region, String name) {
        validateName(name, "Name");
        return datasets.get(storageKey(region, name)).orElseThrow(() -> notFound("dataset", name));
    }

    private Recipe requireRecipe(String region, String name) {
        validateName(name, "Name");
        return recipes.get(storageKey(region, name)).orElseThrow(() -> notFound("recipe", name));
    }

    private Project requireProject(String region, String name) {
        validateName(name, "Name");
        return projects.get(storageKey(region, name)).orElseThrow(() -> notFound("project", name));
    }

    private Job requireJob(String region, String name) {
        validateName(name, "Name");
        return jobs.get(storageKey(region, name)).orElseThrow(() -> notFound("job", name));
    }

    private Ruleset requireRuleset(String region, String name) {
        validateName(name, "Name");
        return rulesets.get(storageKey(region, name)).orElseThrow(() -> notFound("ruleset", name));
    }

    private Schedule requireSchedule(String region, String name) {
        validateName(name, "Name");
        return schedules.get(storageKey(region, name)).orElseThrow(() -> notFound("schedule", name));
    }

    private void assertRecipeUnused(String region, String name) {
        for (Project project : projectsIn(region)) {
            if (name.equals(project.getRecipeName())) {
                throw conflict("The recipe " + name + " is being used by project " + project.getName() + ".");
            }
        }
        for (Job job : jobsIn(region)) {
            JsonNode ref = job.getRecipeReference();
            if (ref != null && name.equals(textOrNull(ref, "Name"))) {
                throw conflict("The recipe " + name + " is being used by job " + job.getName() + ".");
            }
        }
    }

    private List<Dataset> datasetsIn(String region) {
        return new ArrayList<>(datasets.scan(key -> key.startsWith(region + "::")));
    }

    private List<Recipe> recipesIn(String region) {
        return new ArrayList<>(recipes.scan(key -> key.startsWith(region + "::")));
    }

    private List<Project> projectsIn(String region) {
        return new ArrayList<>(projects.scan(key -> key.startsWith(region + "::")));
    }

    private List<Job> jobsIn(String region) {
        return new ArrayList<>(jobs.scan(key -> key.startsWith(region + "::")));
    }

    private List<Ruleset> rulesetsIn(String region) {
        return new ArrayList<>(rulesets.scan(key -> key.startsWith(region + "::")));
    }

    private List<Schedule> schedulesIn(String region) {
        return new ArrayList<>(schedules.scan(key -> key.startsWith(region + "::")));
    }

    private Map<String, String> tagsFor(String region, String arn) {
        return requireTagged(region, arn).tags();
    }

    private Tagged requireTagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("ResourceArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw validation("ResourceArn is invalid.");
        }
        String resource = parsed.resource();
        int slash = resource == null ? -1 : resource.indexOf('/');
        if (slash <= 0 || slash == resource.length() - 1) {
            throw notFound("resource", arn);
        }
        String type = resource.substring(0, slash);
        String name = resource.substring(slash + 1);
        if (!RESOURCE_TYPES.contains(type)) {
            throw notFound("resource", arn);
        }
        String lookupRegion = parsed.region() == null || parsed.region().isEmpty() ? region : parsed.region();
        return switch (type) {
            case "dataset" -> {
                Dataset dataset = requireDataset(lookupRegion, name);
                yield new Tagged() {
                    @Override
                    public Map<String, String> tags() {
                        return dataset.getTags() == null ? Map.of() : dataset.getTags();
                    }

                    @Override
                    public void setTags(Map<String, String> tags) {
                        dataset.setTags(tags);
                        dataset.setLastModifiedDate(nowSeconds());
                    }

                    @Override
                    public void save() {
                        datasets.put(storageKey(lookupRegion, name), dataset);
                    }
                };
            }
            case "recipe" -> {
                Recipe recipe = requireRecipe(lookupRegion, name);
                yield new Tagged() {
                    @Override
                    public Map<String, String> tags() {
                        return recipe.getTags() == null ? Map.of() : recipe.getTags();
                    }

                    @Override
                    public void setTags(Map<String, String> tags) {
                        recipe.setTags(tags);
                        recipe.setLastModifiedDate(nowSeconds());
                    }

                    @Override
                    public void save() {
                        recipes.put(storageKey(lookupRegion, name), recipe);
                    }
                };
            }
            case "project" -> {
                Project project = requireProject(lookupRegion, name);
                yield new Tagged() {
                    @Override
                    public Map<String, String> tags() {
                        return project.getTags() == null ? Map.of() : project.getTags();
                    }

                    @Override
                    public void setTags(Map<String, String> tags) {
                        project.setTags(tags);
                        project.setLastModifiedDate(nowSeconds());
                    }

                    @Override
                    public void save() {
                        projects.put(storageKey(lookupRegion, name), project);
                    }
                };
            }
            case "job" -> {
                Job job = requireJob(lookupRegion, name);
                yield new Tagged() {
                    @Override
                    public Map<String, String> tags() {
                        return job.getTags() == null ? Map.of() : job.getTags();
                    }

                    @Override
                    public void setTags(Map<String, String> tags) {
                        job.setTags(tags);
                        job.setLastModifiedDate(nowSeconds());
                    }

                    @Override
                    public void save() {
                        jobs.put(storageKey(lookupRegion, name), job);
                    }
                };
            }
            case "ruleset" -> {
                Ruleset ruleset = requireRuleset(lookupRegion, name);
                yield new Tagged() {
                    @Override
                    public Map<String, String> tags() {
                        return ruleset.getTags() == null ? Map.of() : ruleset.getTags();
                    }

                    @Override
                    public void setTags(Map<String, String> tags) {
                        ruleset.setTags(tags);
                        ruleset.setLastModifiedDate(nowSeconds());
                    }

                    @Override
                    public void save() {
                        rulesets.put(storageKey(lookupRegion, name), ruleset);
                    }
                };
            }
            case "schedule" -> {
                Schedule schedule = requireSchedule(lookupRegion, name);
                yield new Tagged() {
                    @Override
                    public Map<String, String> tags() {
                        return schedule.getTags() == null ? Map.of() : schedule.getTags();
                    }

                    @Override
                    public void setTags(Map<String, String> tags) {
                        schedule.setTags(tags);
                        schedule.setLastModifiedDate(nowSeconds());
                    }

                    @Override
                    public void save() {
                        schedules.put(storageKey(lookupRegion, name), schedule);
                    }
                };
            }
            default -> throw notFound("resource", arn);
        };
    }

    private Recipe publishedView(Recipe recipe, RecipePublishedVersion published) {
        Recipe view = new Recipe();
        view.setName(recipe.getName());
        view.setResourceArn(recipe.getResourceArn());
        view.setDescription(published.getDescription());
        view.setSteps(published.getSteps());
        view.setCreateDate(recipe.getCreateDate());
        view.setLastModifiedDate(published.getPublishedDate());
        view.setTags(recipe.getTags());
        view.setPublished(List.of(published));
        view.setRecipeVersion(published.getRecipeVersion());
        return view;
    }

    private static RecipePublishedVersion latestPublished(Recipe recipe) {
        List<RecipePublishedVersion> published = recipe.getPublished();
        if (published == null || published.isEmpty()) {
            return null;
        }
        return published.get(published.size() - 1);
    }

    private static RecipePublishedVersion findPublished(Recipe recipe, String version) {
        for (RecipePublishedVersion published : recipe.getPublished()) {
            if (version.equals(published.getRecipeVersion())) {
                return published;
            }
        }
        return null;
    }

    private static String nextPublishedVersion(Recipe recipe) {
        return (recipe.getPublished().size() + 1) + ".0";
    }

    private static String sourceOf(JsonNode input) {
        if (input.hasNonNull("S3InputDefinition")) {
            return "S3";
        }
        if (input.hasNonNull("DataCatalogInputDefinition")) {
            return "DATA-CATALOG";
        }
        if (input.hasNonNull("DatabaseInputDefinition")) {
            return "DATABASE";
        }
        throw validation("Input must contain S3InputDefinition, DataCatalogInputDefinition, or DatabaseInputDefinition.");
    }

    private JsonNode copy(JsonNode node) {
        return node == null || node.isNull() ? null : node.deepCopy();
    }

    private <T> Page<T> page(List<T> items, String maxResults, String nextToken) {
        int limit = parseMaxResults(maxResults);
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + limit, items.size());
        String token = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), token);
    }

    private static int parseMaxResults(String maxResults) {
        if (maxResults == null || maxResults.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(maxResults);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw validation("maxResults must be between 1 and 100.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and 100.");
        }
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn(SERVICE, region, resource);
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static void validateName(String name, String field) {
        if (name == null || name.isBlank() || name.length() > 255) {
            throw validation(field + " must be between 1 and 255 characters.");
        }
    }

    private static void requireObject(JsonNode node, String label) {
        if (node == null || !node.isObject()) {
            throw validation(label + " must be a JSON object.");
        }
    }

    private static JsonNode requireObjectField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
        return value;
    }

    private static JsonNode requireArrayField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || !value.isArray()) {
            throw validation(field + " must be a JSON array.");
        }
        return value;
    }

    private static String requireText(JsonNode parent, String field) {
        String value = textOrNull(parent, field);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        return textOrNull(parent, field);
    }

    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static Integer optionalInt(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isNumber()) {
            throw validation(field + " must be a number.");
        }
        return value.intValue();
    }

    private static boolean booleanOrDefault(JsonNode parent, String field, boolean fallback) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return fallback;
        }
        JsonNode value = parent.get(field);
        if (!value.isBoolean()) {
            throw validation(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tags;
        }
        if (!node.isObject()) {
            throw validation("Tags must be an object.");
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && !entry.getValue().isNull()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }

    private static List<String> readStringArray(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isArray()) {
            throw validation(field + " must be a JSON array.");
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    static AwsException notFound(String type, String name) {
        return new AwsException("ResourceNotFoundException", "The " + type + " " + name + " wasn't found.", 404);
    }

    public record Page<T>(List<T> items, String nextToken) {
    }

    private interface Tagged {
        Map<String, String> tags();

        void setTags(Map<String, String> tags);

        void save();
    }
}
