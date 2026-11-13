package io.floci.aws.services.databrew;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsCronDefinitions;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * AWS Glue DataBrew: datasets, versioned recipes, projects with interactive sessions, profile and recipe jobs that
 * run against the data, data quality rulesets, and cron schedules that start jobs.
 * <p>
 * A job run is {@code STARTING} and then {@code RUNNING} for {@code floci.services.databrew.run-startup-millis}
 * before it executes. A recipe job reads its dataset, applies the recipe ({@link DataBrewRecipes}) and writes each
 * output to S3; a profile job writes column statistics ({@link DataBrewProfiles}) and a data quality report for each
 * validation ruleset to its output location. Runs report {@code DataBrew Job State Change} events to EventBridge
 * and, with {@code LogSubscription} enabled, log to {@code /aws-glue-databrew/jobs/<job>}. A failed run is retried
 * up to {@code MaxRetries} times.
 */
@ApplicationScoped
public class DataBrewService implements Resettable {

    private static final Logger LOG = Logger.getLogger(DataBrewService.class);
    private static final String LATEST_WORKING = "LATEST_WORKING";
    private static final String LATEST_PUBLISHED = "LATEST_PUBLISHED";
    private static final Set<String> TERMINAL = Set.of("STOPPED", "SUCCEEDED", "FAILED", "TIMEOUT");
    private static final int MAX_RESOURCES = 1000;

    private final AccountAwareStorageBackend<ObjectNode> datasets;
    private final AccountAwareStorageBackend<ObjectNode> recipes;
    private final AccountAwareStorageBackend<ObjectNode> projects;
    private final AccountAwareStorageBackend<ObjectNode> jobs;
    private final AccountAwareStorageBackend<ObjectNode> runs;
    private final AccountAwareStorageBackend<ObjectNode> rulesets;
    private final AccountAwareStorageBackend<ObjectNode> schedules;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;
    private final DataBrewData data;
    private final Instance<CloudWatchLogsService> logs;
    private final Instance<EventBridgeService> events;
    private final long startupMillis;
    private final long transitionMillis;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "databrew-jobs");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public DataBrewService(StorageFactory storageFactory, ObjectMapper mapper, RegionResolver regionResolver,
                           Instance<S3Service> s3, Instance<GlueService> glue, Instance<FlociDuckClient> duck,
                           Instance<CloudWatchLogsService> logs, Instance<EventBridgeService> events,
                           EmulatorConfig config) {
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.datasets = storageFactory.create("databrew", "datasets.json", type);
        this.recipes = storageFactory.create("databrew", "recipes.json", type);
        this.projects = storageFactory.create("databrew", "projects.json", type);
        this.jobs = storageFactory.create("databrew", "jobs.json", type);
        this.runs = storageFactory.create("databrew", "job-runs.json", type);
        this.rulesets = storageFactory.create("databrew", "rulesets.json", type);
        this.schedules = storageFactory.create("databrew", "schedules.json", type);
        this.mapper = mapper;
        this.regionResolver = regionResolver;
        this.data = new DataBrewData(s3, glue, duck, mapper);
        this.logs = logs;
        this.events = events;
        this.startupMillis = config.services().databrew().runStartupMillis();
        this.transitionMillis = config.services().databrew().sessionStartupMillis();
        worker.scheduleAtFixedRate(this::runSchedules, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    // ---------------------------------------------------------------- datasets

    public synchronized ObjectNode createDataset(JsonNode request, String region) {
        String name = name(request, "Name", 255);
        conflict(datasets, region, name, "Dataset");
        quota(datasets, region, "datasets");
        ObjectNode dataset = mapper.createObjectNode();
        dataset.put("Name", name);
        applyDataset(dataset, request);
        stamp(dataset, true);
        dataset.put("ResourceArn", arn(region, "dataset", name));
        dataset.set("Tags", tags(request.path("Tags")));
        dataset.putObject("_").put("region", region);
        datasets.putForAccount(datasets.accountId(), key(region, name), dataset);
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode describeDataset(String name, String region) {
        return view(existing(datasets, region, name, "Dataset"));
    }

    public synchronized ObjectNode updateDataset(String name, JsonNode request, String region) {
        ObjectNode dataset = existing(datasets, region, name, "Dataset");
        for (String field : List.of("Format", "FormatOptions", "PathOptions")) {
            dataset.remove(field);
        }
        applyDataset(dataset, request);
        stamp(dataset, false);
        save(datasets, region, name, dataset);
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode deleteDataset(String name, String region) {
        existing(datasets, region, name, "Dataset");
        for (ObjectNode job : regional(jobs, region)) {
            if (name.equals(job.path("DatasetName").asText())) {
                throw conflict("Dataset " + name + " is used by job " + job.path("Name").asText() + ".");
            }
        }
        for (ObjectNode project : regional(projects, region)) {
            if (name.equals(project.path("DatasetName").asText())) {
                throw conflict("Dataset " + name + " is used by project " + project.path("Name").asText() + ".");
            }
        }
        datasets.deleteForAccount(datasets.accountId(), key(region, name));
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode listDatasets(JsonNode query, String region) {
        return page(regional(datasets, region).stream().map(this::view).toList(), query, "Datasets");
    }

    private void applyDataset(ObjectNode dataset, JsonNode request) {
        ObjectNode input = object(request, "Input");
        int sources = 0;
        String source = null;
        for (String field : List.of("S3InputDefinition", "DataCatalogInputDefinition", "DatabaseInputDefinition",
                "Metadata")) {
            if (input.hasNonNull(field)) {
                sources++;
                source = switch (field) {
                    case "S3InputDefinition" -> "S3";
                    case "DataCatalogInputDefinition" -> "DATA-CATALOG";
                    case "DatabaseInputDefinition" -> "DATABASE";
                    default -> "S3";
                };
            }
        }
        if (sources != 1) {
            throw invalid("Input must name exactly one of S3InputDefinition, DataCatalogInputDefinition, "
                    + "DatabaseInputDefinition or Metadata.");
        }
        if (input.has("S3InputDefinition")) {
            text(input.get("S3InputDefinition"), "Bucket", 63);
        }
        if (present(request, "Format")) {
            oneOf(request, "Format", Set.of("CSV", "JSON", "PARQUET", "EXCEL", "ORC"));
            dataset.put("Format", request.get("Format").asText());
        }
        if (present(request, "FormatOptions")) {
            JsonNode options = request.get("FormatOptions");
            String delimiter = options.path("Csv").path("Delimiter").asText(null);
            if (delimiter != null && delimiter.length() != 1) {
                throw invalid("FormatOptions.Csv.Delimiter must be a single character.");
            }
            dataset.set("FormatOptions", options.deepCopy());
        }
        if (present(request, "PathOptions")) {
            dataset.set("PathOptions", request.get("PathOptions").deepCopy());
        }
        dataset.set("Input", input.deepCopy());
        dataset.put("Source", source);
    }

    // ---------------------------------------------------------------- recipes

    public synchronized ObjectNode createRecipe(JsonNode request, String region) {
        String name = name(request, "Name", 255);
        conflict(recipes, region, name, "Recipe");
        quota(recipes, region, "recipes");
        ObjectNode recipe = mapper.createObjectNode();
        ObjectNode working = mapper.createObjectNode();
        working.put("Name", name);
        if (present(request, "Description")) {
            working.put("Description", text(request, "Description", 1024));
        }
        working.set("Steps", steps(request.path("Steps"), true));
        working.put("RecipeVersion", "0.1");
        stamp(working, true);
        working.put("ResourceArn", arn(region, "recipe", name));
        recipe.set("working", working);
        recipe.putObject("published");
        recipe.set("Tags", tags(request.path("Tags")));
        recipe.putObject("_").put("region", region);
        recipes.putForAccount(recipes.accountId(), key(region, name), recipe);
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode describeRecipe(String name, String version, String region) {
        ObjectNode recipe = existing(recipes, region, name, "Recipe");
        return recipeView(recipe, resolveVersion(recipe, version));
    }

    public synchronized ObjectNode updateRecipe(String name, JsonNode request, String region) {
        ObjectNode recipe = existing(recipes, region, name, "Recipe");
        ObjectNode working = (ObjectNode) recipe.get("working");
        if (present(request, "Description")) {
            working.put("Description", text(request, "Description", 1024));
        }
        if (request.has("Steps")) {
            working.set("Steps", steps(request.path("Steps"), false));
        }
        stamp(working, false);
        save(recipes, region, name, recipe);
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode publishRecipe(String name, JsonNode request, String region) {
        ObjectNode recipe = existing(recipes, region, name, "Recipe");
        ObjectNode working = (ObjectNode) recipe.get("working");
        ObjectNode published = (ObjectNode) recipe.get("published");
        if (published.size() >= 100) {
            throw new AwsException("ServiceQuotaExceededException", "A recipe may have at most 100 published versions.",
                    402);
        }
        int major = (int) Math.floor(Double.parseDouble(working.path("RecipeVersion").asText("0.1"))) + 1;
        String version = major + ".0";
        ObjectNode copy = working.deepCopy();
        copy.put("RecipeVersion", version);
        if (present(request, "Description")) {
            copy.put("Description", text(request, "Description", 1024));
        }
        copy.put("PublishedBy", caller());
        copy.put("PublishedDate", now());
        published.set(version, copy);
        working.put("RecipeVersion", major + ".1");
        save(recipes, region, name, recipe);
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode listRecipes(JsonNode query, String region) {
        String version = query.path("recipeVersion").asText(null);
        if (version != null && !LATEST_WORKING.equals(version) && !LATEST_PUBLISHED.equals(version)) {
            throw invalid("RecipeVersion must be LATEST_WORKING or LATEST_PUBLISHED.");
        }
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode recipe : regional(recipes, region)) {
            if (LATEST_WORKING.equals(version)) {
                result.add(recipeView(recipe, recipe.get("working")));
            } else {
                latestPublished(recipe).ifPresent(published -> result.add(recipeView(recipe, published)));
            }
        }
        return page(result, query, "Recipes");
    }

    public synchronized ObjectNode listRecipeVersions(JsonNode query, String region) {
        String name = query.path("name").asText(null);
        if (name == null || name.isEmpty()) {
            throw invalid("Name is required.");
        }
        ObjectNode recipe = recipes.getForAccount(recipes.accountId(), key(region, name)).orElse(null);
        List<ObjectNode> versions = new ArrayList<>();
        if (recipe != null) {
            List<JsonNode> published = new ArrayList<>();
            recipe.path("published").forEach(published::add);
            published.sort(Comparator.comparingDouble(value -> value.path("RecipeVersion").asDouble()));
            published.forEach(value -> versions.add(recipeView(recipe, value)));
        }
        return page(versions, query, "Recipes");
    }

    public synchronized ObjectNode deleteRecipeVersion(String name, String version, String region) {
        ObjectNode recipe = existing(recipes, region, name, "Recipe");
        deleteVersion(recipe, region, name, version);
        return mapper.createObjectNode().put("Name", name).put("RecipeVersion", version);
    }

    public synchronized ObjectNode batchDeleteRecipeVersion(String name, JsonNode request, String region) {
        ObjectNode recipe = existing(recipes, region, name, "Recipe");
        JsonNode versions = request.path("RecipeVersions");
        if (!versions.isArray() || versions.isEmpty() || versions.size() > 50) {
            throw invalid("RecipeVersions must list between 1 and 50 versions.");
        }
        ObjectNode response = mapper.createObjectNode().put("Name", name);
        ArrayNode errors = response.putArray("Errors");
        for (JsonNode value : versions) {
            String version = value.asText();
            try {
                if (LATEST_WORKING.equals(version)) {
                    throw invalid("LATEST_WORKING cannot be deleted in a batch; use DeleteRecipeVersion.");
                }
                deleteVersion(recipe, region, name, version);
            } catch (AwsException e) {
                errors.addObject().put("ErrorCode", Integer.toString(e.getHttpStatus()))
                        .put("ErrorMessage", e.getMessage()).put("RecipeVersion", version);
            }
        }
        return response;
    }

    private void deleteVersion(ObjectNode recipe, String region, String name, String version) {
        ObjectNode published = (ObjectNode) recipe.get("published");
        if (LATEST_WORKING.equals(version)) {
            if (!published.isEmpty()) {
                throw conflict("Recipe " + name + " still has published versions; delete them first.");
            }
            String project = recipe.path("working").path("ProjectName").asText(null);
            if (project != null && projects.getForAccount(projects.accountId(), key(region, project)).isPresent()) {
                throw conflict("Recipe " + name + " is used by project " + project + ".");
            }
            recipes.deleteForAccount(recipes.accountId(), key(region, name));
            return;
        }
        if (!published.has(version)) {
            throw notFound("Recipe " + name + " has no version " + version + ".");
        }
        for (ObjectNode job : regional(jobs, region)) {
            JsonNode reference = job.path("RecipeReference");
            if (name.equals(reference.path("Name").asText()) && version.equals(reference.path("RecipeVersion")
                    .asText())) {
                throw conflict("Recipe " + name + " version " + version + " is used by job "
                        + job.path("Name").asText() + ".");
            }
        }
        published.remove(version);
        save(recipes, region, name, recipe);
    }

    private JsonNode resolveVersion(ObjectNode recipe, String version) {
        if (version == null || version.isEmpty() || LATEST_PUBLISHED.equals(version)) {
            Optional<JsonNode> published = latestPublished(recipe);
            if (published.isPresent()) {
                return published.get();
            }
            if (LATEST_PUBLISHED.equals(version)) {
                throw notFound("Recipe " + recipe.path("working").path("Name").asText() + " has no published version.");
            }
            return recipe.get("working");
        }
        if (LATEST_WORKING.equals(version) || version.equals(recipe.path("working").path("RecipeVersion").asText())) {
            return recipe.get("working");
        }
        JsonNode published = recipe.path("published").get(version);
        if (published == null) {
            throw notFound("Recipe " + recipe.path("working").path("Name").asText() + " has no version " + version + ".");
        }
        return published;
    }

    private static Optional<JsonNode> latestPublished(ObjectNode recipe) {
        JsonNode best = null;
        for (JsonNode value : recipe.path("published")) {
            if (best == null || value.path("RecipeVersion").asDouble() > best.path("RecipeVersion").asDouble()) {
                best = value;
            }
        }
        return Optional.ofNullable(best);
    }

    private ObjectNode recipeView(ObjectNode recipe, JsonNode version) {
        ObjectNode view = version.deepCopy();
        view.set("Tags", recipe.path("Tags").deepCopy());
        String project = recipe.path("working").path("ProjectName").asText(null);
        if (project != null) {
            view.put("ProjectName", project);
        }
        return view;
    }

    private ArrayNode steps(JsonNode input, boolean required) {
        if (!input.isArray() && (required || !input.isMissingNode())) {
            throw invalid("Steps must be a list of recipe steps.");
        }
        for (JsonNode step : input) {
            String operation = text(step.path("Action"), "Operation", 128);
            if (!operation.matches("^[A-Z_]+$")) {
                throw invalid("Operation " + operation + " must contain only capital letters and underscores.");
            }
            for (JsonNode condition : step.path("ConditionExpressions")) {
                text(condition, "Condition", 128);
                text(condition, "TargetColumn", 1024);
            }
        }
        return (ArrayNode) input.deepCopy();
    }

    // ---------------------------------------------------------------- projects and sessions

    public synchronized ObjectNode createProject(JsonNode request, String region) {
        String name = name(request, "Name", 255);
        conflict(projects, region, name, "Project");
        quota(projects, region, "projects");
        String datasetName = text(request, "DatasetName", 255);
        if (datasets.getForAccount(datasets.accountId(), key(region, datasetName)).isEmpty()) {
            throw invalid("Dataset " + datasetName + " does not exist.");
        }
        String recipeName = text(request, "RecipeName", 255);
        ObjectNode recipe = recipes.getForAccount(recipes.accountId(), key(region, recipeName))
                .orElseThrow(() -> invalid("Recipe " + recipeName + " does not exist."));
        String owner = recipe.path("working").path("ProjectName").asText(null);
        if (owner != null && projects.getForAccount(projects.accountId(), key(region, owner)).isPresent()) {
            throw conflict("Recipe " + recipeName + " is already used by project " + owner + ".");
        }
        ObjectNode project = mapper.createObjectNode();
        project.put("Name", name);
        project.put("DatasetName", datasetName);
        project.put("RecipeName", recipeName);
        project.set("Sample", sample(request.path("Sample")));
        project.put("RoleArn", role(text(request, "RoleArn", 2048)));
        stamp(project, true);
        project.put("ResourceArn", arn(region, "project", name));
        project.set("Tags", tags(request.path("Tags")));
        project.putObject("_").put("region", region);
        projects.putForAccount(projects.accountId(), key(region, name), project);
        ((ObjectNode) recipe.get("working")).put("ProjectName", name);
        save(recipes, region, recipeName, recipe);
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode describeProject(String name, String region) {
        ObjectNode project = existing(projects, region, name, "Project");
        settleSession(project);
        ObjectNode view = view(project);
        JsonNode session = project.path("_").path("session");
        if (!session.isMissingNode()) {
            view.put("SessionStatus", session.path("status").asText());
            view.put("OpenedBy", session.path("openedBy").asText());
            view.put("OpenDate", session.path("openDate").asDouble());
        }
        return view;
    }

    public synchronized ObjectNode updateProject(String name, JsonNode request, String region) {
        ObjectNode project = existing(projects, region, name, "Project");
        if (request.has("Sample")) {
            project.set("Sample", sample(request.path("Sample")));
        }
        project.put("RoleArn", role(text(request, "RoleArn", 2048)));
        stamp(project, false);
        save(projects, region, name, project);
        return mapper.createObjectNode().put("Name", name).put("LastModifiedDate", project.path("LastModifiedDate")
                .asDouble());
    }

    public synchronized ObjectNode deleteProject(String name, String region) {
        ObjectNode project = existing(projects, region, name, "Project");
        for (ObjectNode job : regional(jobs, region)) {
            if (name.equals(job.path("ProjectName").asText())) {
                throw conflict("Project " + name + " is used by job " + job.path("Name").asText() + ".");
            }
        }
        projects.deleteForAccount(projects.accountId(), key(region, name));
        String recipeName = project.path("RecipeName").asText();
        recipes.getForAccount(recipes.accountId(), key(region, recipeName)).ifPresent(recipe -> {
            ((ObjectNode) recipe.get("working")).remove("ProjectName");
            save(recipes, region, recipeName, recipe);
        });
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode listProjects(JsonNode query, String region) {
        return page(regional(projects, region).stream().map(this::view).toList(), query, "Projects");
    }

    public synchronized ObjectNode startProjectSession(String name, JsonNode request, String region) {
        ObjectNode project = existing(projects, region, name, "Project");
        settleSession(project);
        JsonNode existing = project.path("_").path("session");
        if (!existing.isMissingNode() && !"TERMINATED".equals(existing.path("status").asText())
                && !request.path("AssumeControl").asBoolean(false)) {
            throw conflict("Project " + name + " already has an open session; set AssumeControl to take it over.");
        }
        String sessionId = HexFormat.of().formatHex(bytes(16));
        ObjectNode session = mapper.createObjectNode();
        session.put("id", sessionId);
        session.put("status", transitionMillis <= 0 ? "READY" : "INITIALIZING");
        session.put("readyAt", System.currentTimeMillis() + transitionMillis);
        session.put("openedBy", caller());
        session.put("openDate", now());
        session.put("actions", 0);
        ((ObjectNode) project.get("_")).set("session", session);
        save(projects, region, name, project);
        return mapper.createObjectNode().put("Name", name).put("ClientSessionId", sessionId);
    }

    public synchronized ObjectNode sendProjectSessionAction(String name, JsonNode request, String region) {
        ObjectNode project = existing(projects, region, name, "Project");
        settleSession(project);
        JsonNode session = project.path("_").path("session");
        if (session.isMissingNode() || "TERMINATED".equals(session.path("status").asText())) {
            throw conflict("Project " + name + " has no open session; call StartProjectSession first.");
        }
        if (present(request, "ClientSessionId") && !session.path("id").asText().equals(request.get("ClientSessionId")
                .asText())) {
            throw conflict("ClientSessionId does not match the project's open session.");
        }
        if (!"READY".equals(session.path("status").asText())) {
            throw conflict("The project session is " + session.path("status").asText() + "; try again shortly.");
        }
        ObjectNode recipe = existing(recipes, region, project.path("RecipeName").asText(), "Recipe");
        ArrayNode steps = (ArrayNode) recipe.path("working").path("Steps");
        boolean preview = request.path("Preview").asBoolean(false);
        Integer stepIndex = request.hasNonNull("StepIndex") ? request.get("StepIndex").asInt() : null;
        if (stepIndex != null && (stepIndex < 0 || stepIndex > steps.size())) {
            throw invalid("StepIndex must be between 0 and " + steps.size() + ".");
        }
        ObjectNode sessionNode = (ObjectNode) session;
        int actionId = sessionNode.path("actions").asInt() + 1;
        sessionNode.put("actions", actionId);
        ObjectNode response = mapper.createObjectNode().put("Name", name).put("ActionId", actionId);
        if (preview) {
            ArrayNode applied = mapper.createArrayNode();
            int upTo = stepIndex == null ? steps.size() : stepIndex;
            for (int index = 0; index < upTo; index++) {
                applied.add(steps.get(index));
            }
            if (request.has("RecipeStep")) {
                applied.add(steps(mapper.createArrayNode().add(request.get("RecipeStep")), true).get(0));
            }
            DataBrewTable sample;
            try {
                sample = sampleTable(project, region);
                sample = DataBrewRecipes.apply(sample, applied);
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                throw invalid(e.getMessage());
            }
            response.put("Result", frame(sample, request.path("ViewFrame")).toString());
        } else if (request.has("RecipeStep")) {
            JsonNode step = steps(mapper.createArrayNode().add(request.get("RecipeStep")), true).get(0);
            if (stepIndex == null) {
                steps.add(step);
            } else {
                steps.insert(stepIndex, step);
            }
            stamp((ObjectNode) recipe.get("working"), false);
            save(recipes, region, recipe.path("working").path("Name").asText(), recipe);
        } else if (stepIndex != null && stepIndex < steps.size()) {
            steps.remove(stepIndex.intValue());
            save(recipes, region, recipe.path("working").path("Name").asText(), recipe);
        } else {
            throw invalid("SendProjectSessionAction needs a RecipeStep, a StepIndex to remove, or Preview.");
        }
        save(projects, region, name, project);
        return response;
    }

    private void settleSession(ObjectNode project) {
        JsonNode session = project.path("_").path("session");
        if ("INITIALIZING".equals(session.path("status").asText())
                && System.currentTimeMillis() >= session.path("readyAt").asLong()) {
            ((ObjectNode) session).put("status", "READY");
        }
    }

    private DataBrewTable sampleTable(ObjectNode project, String region) {
        ObjectNode dataset = existing(datasets, region, project.path("DatasetName").asText(), "Dataset");
        DataBrewTable table = data.read(dataset, datasets.accountId());
        JsonNode sample = project.path("Sample");
        int size = sample.path("Size").asInt(500);
        List<Map<String, String>> rows = new ArrayList<>(table.rows);
        switch (sample.path("Type").asText("FIRST_N")) {
            case "LAST_N" -> rows = rows.subList(Math.max(0, rows.size() - size), rows.size());
            case "RANDOM" -> {
                Collections.shuffle(rows, new Random(project.path("Name").asText().hashCode()));
                rows = rows.subList(0, Math.min(size, rows.size()));
            }
            default -> rows = rows.subList(0, Math.min(size, rows.size()));
        }
        return new DataBrewTable(table.columns, new ArrayList<>(rows));
    }

    /** A preview's view frame: up to 20 columns from StartColumnIndex, without HiddenColumns, and a row range. */
    private ObjectNode frame(DataBrewTable table, JsonNode viewFrame) {
        int startColumn = viewFrame.path("StartColumnIndex").asInt(0);
        int columnRange = Math.min(20, viewFrame.path("ColumnRange").asInt(20));
        Set<String> hidden = new HashSet<>();
        viewFrame.path("HiddenColumns").forEach(column -> hidden.add(column.asText()));
        List<String> visible = table.columns.stream().filter(column -> !hidden.contains(column)).toList();
        List<String> columns = visible.subList(Math.min(startColumn, visible.size()),
                Math.min(visible.size(), startColumn + columnRange));
        int startRow = viewFrame.path("StartRowIndex").asInt(0);
        int rowRange = viewFrame.path("RowRange").asInt(table.rows.size());
        ObjectNode result = mapper.createObjectNode();
        ArrayNode columnNodes = result.putArray("columns");
        columns.forEach(column -> columnNodes.addObject().put("name", column));
        ArrayNode rows = result.putArray("rows");
        for (int index = startRow; index < Math.min(table.rows.size(), startRow + rowRange); index++) {
            ArrayNode row = rows.addArray();
            for (String column : columns) {
                String value = table.rows.get(index).get(column);
                if (value == null) {
                    row.addNull();
                } else {
                    row.add(value);
                }
            }
        }
        result.put("rowCount", table.rows.size());
        return result;
    }

    // ---------------------------------------------------------------- jobs

    public synchronized ObjectNode createProfileJob(JsonNode request, String region) {
        String name = name(request, "Name", 240);
        jobConflict(region, name);
        String datasetName = text(request, "DatasetName", 255);
        ObjectNode dataset = datasets.getForAccount(datasets.accountId(), key(region, datasetName))
                .orElseThrow(() -> notFound("Dataset " + datasetName + " does not exist."));
        ObjectNode job = mapper.createObjectNode();
        job.put("Name", name);
        job.put("Type", "PROFILE");
        job.put("DatasetName", datasetName);
        applyProfileJob(job, request, region, dataset);
        verifySource(dataset);
        stamp(job, true);
        job.put("ResourceArn", arn(region, "job", name));
        job.set("Tags", tags(request.path("Tags")));
        job.putObject("_").put("region", region);
        jobs.putForAccount(jobs.accountId(), key(region, name), job);
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode updateProfileJob(String name, JsonNode request, String region) {
        ObjectNode job = existing(jobs, region, name, "Job");
        if (!"PROFILE".equals(job.path("Type").asText())) {
            throw invalid("Job " + name + " is not a profile job.");
        }
        ObjectNode dataset = existing(datasets, region, job.path("DatasetName").asText(), "Dataset");
        for (String field : List.of("Configuration", "ValidationConfigurations", "EncryptionKeyArn", "EncryptionMode")) {
            job.remove(field);
        }
        applyProfileJob(job, request, region, dataset);
        stamp(job, false);
        save(jobs, region, name, job);
        return mapper.createObjectNode().put("Name", name);
    }

    private void applyProfileJob(ObjectNode job, JsonNode request, String region, JsonNode dataset) {
        applyJobSettings(job, request);
        ObjectNode location = object(request, "OutputLocation");
        text(location, "Bucket", 63);
        job.set("OutputLocation", location.deepCopy());
        if (present(request, "Configuration")) {
            job.set("Configuration", request.get("Configuration").deepCopy());
        }
        if (present(request, "ValidationConfigurations")) {
            for (JsonNode validation : request.get("ValidationConfigurations")) {
                String rulesetArn = text(validation, "RulesetArn", 2048);
                ObjectNode ruleset = rulesetByArn(region, rulesetArn)
                        .orElseThrow(() -> invalid("Ruleset " + rulesetArn + " does not exist."));
                if (!ruleset.path("TargetArn").asText().equals(dataset.path("ResourceArn").asText())) {
                    throw invalid("Ruleset " + rulesetArn + " does not target dataset "
                            + dataset.path("Name").asText() + ".");
                }
            }
            job.set("ValidationConfigurations", request.get("ValidationConfigurations").deepCopy());
        }
        JsonNode sample = request.path("JobSample");
        ObjectNode jobSample = mapper.createObjectNode();
        String mode = sample.hasNonNull("Mode") ? oneOf(sample, "Mode", Set.of("FULL_DATASET", "CUSTOM_ROWS"))
                : "CUSTOM_ROWS";
        jobSample.put("Mode", mode);
        if ("CUSTOM_ROWS".equals(mode)) {
            jobSample.put("Size", sample.path("Size").asLong(20000));
        }
        job.set("JobSample", jobSample);
    }

    public synchronized ObjectNode createRecipeJob(JsonNode request, String region) {
        String name = name(request, "Name", 240);
        jobConflict(region, name);
        ObjectNode job = mapper.createObjectNode();
        job.put("Name", name);
        job.put("Type", "RECIPE");
        ObjectNode dataset;
        if (present(request, "ProjectName")) {
            if (present(request, "DatasetName") || present(request, "RecipeReference")) {
                throw invalid("A recipe job takes either ProjectName, or DatasetName with RecipeReference.");
            }
            String projectName = text(request, "ProjectName", 255);
            ObjectNode project = projects.getForAccount(projects.accountId(), key(region, projectName))
                    .orElseThrow(() -> notFound("Project " + projectName + " does not exist."));
            job.put("ProjectName", projectName);
            dataset = existing(datasets, region, project.path("DatasetName").asText(), "Dataset");
        } else {
            String datasetName = text(request, "DatasetName", 255);
            dataset = datasets.getForAccount(datasets.accountId(), key(region, datasetName))
                    .orElseThrow(() -> notFound("Dataset " + datasetName + " does not exist."));
            job.put("DatasetName", datasetName);
            ObjectNode reference = object(request, "RecipeReference");
            String recipeName = text(reference, "Name", 255);
            ObjectNode recipe = recipes.getForAccount(recipes.accountId(), key(region, recipeName))
                    .orElseThrow(() -> notFound("Recipe " + recipeName + " does not exist."));
            String version = reference.hasNonNull("RecipeVersion") ? reference.get("RecipeVersion").asText()
                    : latestPublished(recipe).map(value -> value.path("RecipeVersion").asText())
                            .orElseThrow(() -> invalid("Recipe " + recipeName + " has no published version; publish it "
                                    + "or name a version."));
            boolean alias = LATEST_PUBLISHED.equals(version) || LATEST_WORKING.equals(version);
            if (LATEST_PUBLISHED.equals(version) && latestPublished(recipe).isEmpty()) {
                throw invalid("Recipe " + recipeName + " has no published version; publish it first.");
            }
            if (!alias && !recipe.path("published").has(version)) {
                throw invalid("Recipe " + recipeName + " has no published version " + version + ".");
            }
            job.putObject("RecipeReference").put("Name", recipeName).put("RecipeVersion", version);
        }
        applyRecipeJob(job, request);
        verifySource(dataset);
        stamp(job, true);
        job.put("ResourceArn", arn(region, "job", name));
        job.set("Tags", tags(request.path("Tags")));
        job.putObject("_").put("region", region);
        jobs.putForAccount(jobs.accountId(), key(region, name), job);
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode updateRecipeJob(String name, JsonNode request, String region) {
        ObjectNode job = existing(jobs, region, name, "Job");
        if (!"RECIPE".equals(job.path("Type").asText())) {
            throw invalid("Job " + name + " is not a recipe job.");
        }
        for (String field : List.of("Outputs", "DataCatalogOutputs", "DatabaseOutputs", "EncryptionKeyArn",
                "EncryptionMode")) {
            job.remove(field);
        }
        applyRecipeJob(job, request);
        stamp(job, false);
        save(jobs, region, name, job);
        return mapper.createObjectNode().put("Name", name);
    }

    private void applyRecipeJob(ObjectNode job, JsonNode request) {
        applyJobSettings(job, request);
        int outputs = 0;
        for (String field : List.of("Outputs", "DataCatalogOutputs", "DatabaseOutputs")) {
            JsonNode value = request.path(field);
            if (value.isArray() && !value.isEmpty()) {
                outputs += value.size();
                if ("Outputs".equals(field)) {
                    for (JsonNode output : value) {
                        text(output.path("Location"), "Bucket", 63);
                        if (output.hasNonNull("Format")) {
                            oneOf(output, "Format", Set.of("CSV", "JSON", "PARQUET", "GLUEPARQUET", "AVRO", "ORC",
                                    "XML", "TABLEAUHYPER"));
                        }
                    }
                }
                job.set(field, value.deepCopy());
            }
        }
        if (outputs == 0) {
            throw invalid("A recipe job needs at least one of Outputs, DataCatalogOutputs or DatabaseOutputs.");
        }
    }

    private void applyJobSettings(ObjectNode job, JsonNode request) {
        job.put("RoleArn", role(text(request, "RoleArn", 2048)));
        job.put("MaxCapacity", request.path("MaxCapacity").asInt(5));
        if (job.path("MaxCapacity").asInt() < 1) {
            throw invalid("MaxCapacity must be at least 1.");
        }
        job.put("MaxRetries", request.path("MaxRetries").asInt(0));
        job.put("Timeout", request.path("Timeout").asInt(2880));
        job.put("LogSubscription", request.hasNonNull("LogSubscription")
                ? oneOf(request, "LogSubscription", Set.of("ENABLE", "DISABLE")) : "ENABLE");
        if (present(request, "EncryptionMode")) {
            job.put("EncryptionMode", oneOf(request, "EncryptionMode", Set.of("SSE-KMS", "SSE-S3")));
            if ("SSE-KMS".equals(job.path("EncryptionMode").asText()) && !present(request, "EncryptionKeyArn")) {
                throw invalid("SSE-KMS encryption needs an EncryptionKeyArn.");
            }
        }
        if (present(request, "EncryptionKeyArn")) {
            job.put("EncryptionKeyArn", text(request, "EncryptionKeyArn", 2048));
        }
    }

    /** DataBrew checks at job creation that the dataset's source can be read. */
    private void verifySource(JsonNode dataset) {
        try {
            data.sources(dataset);
        } catch (IllegalArgumentException e) {
            throw invalid("The dataset " + dataset.path("Name").asText() + " cannot be read: " + e.getMessage());
        }
    }

    public synchronized ObjectNode describeJob(String name, String region) {
        return view(existing(jobs, region, name, "Job"));
    }

    public synchronized ObjectNode deleteJob(String name, String region) {
        existing(jobs, region, name, "Job");
        for (ObjectNode run : runsOf(region, name)) {
            if (!TERMINAL.contains(run.path("State").asText())) {
                throw conflict("Job " + name + " has a run in progress.");
            }
        }
        jobs.deleteForAccount(jobs.accountId(), key(region, name));
        for (ObjectNode run : runsOf(region, name)) {
            runs.deleteForAccount(runs.accountId(), key(region, name + "/" + run.path("RunId").asText()));
        }
        for (ObjectNode schedule : regional(schedules, region)) {
            ArrayNode names = (ArrayNode) schedule.path("JobNames");
            for (int index = names.size() - 1; index >= 0; index--) {
                if (name.equals(names.get(index).asText())) {
                    names.remove(index);
                    save(schedules, region, schedule.path("Name").asText(), schedule);
                }
            }
        }
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode listJobs(JsonNode query, String region) {
        String dataset = query.path("datasetName").asText(null);
        String project = query.path("projectName").asText(null);
        List<ObjectNode> result = regional(jobs, region).stream()
                .filter(job -> dataset == null || dataset.equals(job.path("DatasetName").asText()))
                .filter(job -> project == null || project.equals(job.path("ProjectName").asText()))
                .map(this::view).toList();
        return page(result, query, "Jobs");
    }

    // ---------------------------------------------------------------- job runs

    public synchronized ObjectNode startJobRun(String name, String region) {
        ObjectNode job = existing(jobs, region, name, "Job");
        String runId = "db_" + HexFormat.of().formatHex(bytes(32));
        startRun(job, region, runId, caller(), 0);
        return mapper.createObjectNode().put("RunId", runId);
    }

    private void startRun(ObjectNode job, String region, String runId, String startedBy, int attempt) {
        String name = job.path("Name").asText();
        ObjectNode run = mapper.createObjectNode();
        run.put("Attempt", attempt);
        run.put("DatasetName", datasetOf(job, region));
        run.put("JobName", name);
        run.put("RunId", runId);
        run.put("State", "STARTING");
        run.put("LogSubscription", job.path("LogSubscription").asText("ENABLE"));
        if ("ENABLE".equals(job.path("LogSubscription").asText("ENABLE"))) {
            run.put("LogGroupName", "/aws-glue-databrew/jobs/" + name);
        }
        for (String field : List.of("Outputs", "DataCatalogOutputs", "DatabaseOutputs", "RecipeReference", "JobSample",
                "ValidationConfigurations", "Configuration")) {
            if (job.has(field)) {
                run.set("Configuration".equals(field) ? "ProfileConfiguration" : field, job.get(field).deepCopy());
            }
        }
        run.put("StartedBy", startedBy);
        run.put("StartedOn", now());
        run.putObject("_").put("region", region).put("type", job.path("Type").asText());
        runs.putForAccount(runs.accountId(), key(region, name + "/" + runId), run);
        String account = runs.accountId();
        worker.schedule(() -> RequestScopes.runAs(account, region, () -> advance(region, name, runId, "RUNNING")),
                Math.max(1, startupMillis / 2), TimeUnit.MILLISECONDS);
        worker.schedule(() -> RequestScopes.runAs(account, region, () -> execute(region, name, runId)),
                Math.max(1, startupMillis), TimeUnit.MILLISECONDS);
    }

    public synchronized ObjectNode describeJobRun(String name, String runId, String region) {
        existing(jobs, region, name, "Job");
        ObjectNode run = runs.getForAccount(runs.accountId(), key(region, name + "/" + runId))
                .orElseThrow(() -> notFound("Job run " + runId + " of job " + name + " does not exist."));
        return view(run);
    }

    public synchronized ObjectNode listJobRuns(String name, JsonNode query, String region) {
        existing(jobs, region, name, "Job");
        List<ObjectNode> result = runsOf(region, name).stream()
                .sorted(Comparator.comparingDouble((ObjectNode run) -> run.path("StartedOn").asDouble()).reversed())
                .map(this::runSummary).toList();
        return page(result, query, "JobRuns");
    }

    public synchronized ObjectNode stopJobRun(String name, String runId, String region) {
        existing(jobs, region, name, "Job");
        String runKey = key(region, name + "/" + runId);
        ObjectNode run = runs.getForAccount(runs.accountId(), runKey)
                .orElseThrow(() -> notFound("Job run " + runId + " of job " + name + " does not exist."));
        String state = run.path("State").asText();
        if (!"STARTING".equals(state) && !"RUNNING".equals(state)) {
            throw invalid("Job run " + runId + " is " + state + " and cannot be stopped.");
        }
        run.put("State", "STOPPING");
        runs.putForAccount(runs.accountId(), runKey, run);
        String account = runs.accountId();
        worker.schedule(() -> RequestScopes.runAs(account, region, () -> finish(region, name, runId, "STOPPED",
                null)), Math.max(1, startupMillis / 2), TimeUnit.MILLISECONDS);
        return mapper.createObjectNode().put("RunId", runId);
    }

    private synchronized void advance(String region, String name, String runId, String state) {
        String runKey = key(region, name + "/" + runId);
        runs.getForAccount(runs.accountId(), runKey).ifPresent(run -> {
            if ("STARTING".equals(run.path("State").asText())) {
                run.put("State", state);
                runs.putForAccount(runs.accountId(), runKey, run);
                log(region, name, runId, run, "Job run " + runId + " is " + state);
            }
        });
    }

    private void execute(String region, String name, String runId) {
        ObjectNode run;
        ObjectNode job;
        ObjectNode dataset;
        JsonNode steps = null;
        List<ObjectNode> validationRulesets = new ArrayList<>();
        synchronized (this) {
            run = runs.getForAccount(runs.accountId(), key(region, name + "/" + runId)).orElse(null);
            job = jobs.getForAccount(jobs.accountId(), key(region, name)).orElse(null);
            if (run == null || TERMINAL.contains(run.path("State").asText()) || "STOPPING".equals(run.path("State")
                    .asText())) {
                return;
            }
            advance(region, name, runId, "RUNNING");
            if (job == null) {
                finish(region, name, runId, "FAILED", "The job was deleted.");
                return;
            }
            dataset = datasets.getForAccount(datasets.accountId(), key(region, run.path("DatasetName").asText()))
                    .orElse(null);
            if (dataset == null) {
                finish(region, name, runId, "FAILED", "Dataset " + run.path("DatasetName").asText()
                        + " does not exist.");
                return;
            }
            if ("RECIPE".equals(job.path("Type").asText())) {
                if (job.hasNonNull("ProjectName")) {
                    ObjectNode project = projects.getForAccount(projects.accountId(), key(region,
                            job.get("ProjectName").asText())).orElse(null);
                    ObjectNode recipe = project == null ? null : recipes.getForAccount(recipes.accountId(),
                            key(region, project.path("RecipeName").asText())).orElse(null);
                    steps = recipe == null ? null : recipe.path("working").path("Steps").deepCopy();
                } else {
                    JsonNode reference = job.path("RecipeReference");
                    ObjectNode recipe = recipes.getForAccount(recipes.accountId(), key(region,
                            reference.path("Name").asText())).orElse(null);
                    JsonNode version = null;
                    if (recipe != null) {
                        try {
                            version = resolveVersion(recipe, reference.path("RecipeVersion").asText());
                        } catch (AwsException e) {
                            version = null;
                        }
                    }
                    steps = version == null ? null : version.path("Steps").deepCopy();
                }
                if (steps == null) {
                    finish(region, name, runId, "FAILED", "The job's recipe no longer exists.");
                    return;
                }
            } else {
                for (JsonNode validation : job.path("ValidationConfigurations")) {
                    rulesetByArn(region, validation.path("RulesetArn").asText()).ifPresent(validationRulesets::add);
                }
            }
        }
        String account = runs.accountId();
        try {
            DataBrewTable table = data.read(dataset, account);
            Instant startedOn = Instant.ofEpochMilli((long) (run.path("StartedOn").asDouble() * 1000));
            if ("RECIPE".equals(job.path("Type").asText())) {
                DataBrewTable result = DataBrewRecipes.apply(table, steps);
                if (stopping(region, name, runId)) {
                    return;
                }
                List<String> written = new ArrayList<>();
                for (JsonNode output : job.path("Outputs")) {
                    written.addAll(data.write(result, output, name, startedOn, account));
                }
                for (JsonNode output : job.path("DataCatalogOutputs")) {
                    JsonNode location = output.path("S3Options").path("Location");
                    if (location.isMissingNode()) {
                        throw new IllegalArgumentException("Floci writes Data Catalog outputs to their S3Options "
                                + "location; this output has none.");
                    }
                    ObjectNode s3Output = mapper.createObjectNode();
                    s3Output.set("Location", location.deepCopy());
                    s3Output.put("Overwrite", output.path("Overwrite").asBoolean(false));
                    written.addAll(data.write(result, s3Output, name, startedOn, account));
                }
                if (job.path("DatabaseOutputs").size() > 0) {
                    throw new IllegalArgumentException("Floci does not write DataBrew database outputs.");
                }
                finish(region, name, runId, "SUCCEEDED", null);
                logLine(region, name, runId, "Wrote " + result.rows.size() + " rows to " + String.join(", ", written));
            } else {
                long size = job.path("JobSample").path("Size").asLong(20000);
                DataBrewTable sample = "FULL_DATASET".equals(job.path("JobSample").path("Mode").asText())
                        ? table : new DataBrewTable(table.columns, new ArrayList<>(table.rows.subList(0,
                                (int) Math.min(size, table.rows.size()))));
                ObjectNode profile = DataBrewProfiles.profile(mapper, sample, job.path("Configuration"));
                profile.put("datasetName", dataset.path("Name").asText());
                profile.put("jobName", name);
                profile.put("jobRunId", runId);
                JsonNode location = job.path("OutputLocation");
                String prefix = location.path("Key").asText("");
                prefix = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
                String base = prefix + dataset.path("Name").asText() + "_" + runId;
                if (stopping(region, name, runId)) {
                    return;
                }
                data.putJson(location.path("Bucket").asText(), base + ".json", profile);
                if (!validationRulesets.isEmpty()) {
                    ObjectNode report = mapper.createObjectNode();
                    report.put("datasetName", dataset.path("Name").asText());
                    report.put("jobName", name);
                    report.put("jobRunId", runId);
                    ArrayNode results = report.putArray("rulesetResults");
                    for (ObjectNode ruleset : validationRulesets) {
                        results.add(DataBrewProfiles.validate(mapper, sample, ruleset));
                    }
                    data.putJson(location.path("Bucket").asText(), base + "_dq-validation-report.json", report);
                }
                finish(region, name, runId, "SUCCEEDED", null);
            }
        } catch (RuntimeException e) {
            LOG.debugv(e, "DataBrew job {0} run {1} failed", name, runId);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (job.path("MaxRetries").asInt(0) > run.path("Attempt").asInt(0) && retry(region, name, runId, message)) {
                return;            } else {
                finish(region, name, runId, "FAILED", message);
            }
        }
    }

    /** Starts the next attempt of a failed run under the same run ID, as DataBrew retries a job. */
    private synchronized boolean retry(String region, String name, String runId, String error) {
        String runKey = key(region, name + "/" + runId);
        ObjectNode run = runs.getForAccount(runs.accountId(), runKey).orElse(null);
        if (run == null || !"RUNNING".equals(run.path("State").asText())) {
            return false;
        }
        int attempt = run.path("Attempt").asInt() + 1;
        run.put("Attempt", attempt);
        runs.putForAccount(runs.accountId(), runKey, run);
        log(region, name, runId, run, "Attempt " + (attempt - 1) + " failed: " + error + "; retrying");
        String account = runs.accountId();
        worker.schedule(() -> RequestScopes.runAs(account, region, () -> execute(region, name, runId)),
                Math.max(1, startupMillis / 2), TimeUnit.MILLISECONDS);
        return true;
    }

    private synchronized boolean stopping(String region, String name, String runId) {
        return runs.getForAccount(runs.accountId(), key(region, name + "/" + runId))
                .map(run -> !"RUNNING".equals(run.path("State").asText())).orElse(true);
    }

    private synchronized void finish(String region, String name, String runId, String state, String error) {
        String runKey = key(region, name + "/" + runId);
        ObjectNode run = runs.getForAccount(runs.accountId(), runKey).orElse(null);
        if (run == null || TERMINAL.contains(run.path("State").asText())) {
            return;
        }
        if ("STOPPED".equals(state) != "STOPPING".equals(run.path("State").asText())) {
            return;
        }
        double now = now();
        run.put("State", state);
        run.put("CompletedOn", now);
        run.put("ExecutionTime", Math.max(1, (int) Math.round(now - run.path("StartedOn").asDouble())));
        if (error != null) {
            run.put("ErrorMessage", error);
        }
        runs.putForAccount(runs.accountId(), runKey, run);
        log(region, name, runId, run, "Job run " + runId + " " + state + (error == null ? "" : ": " + error));
        if (events.isResolvable()) {
            ObjectNode detail = mapper.createObjectNode();
            detail.put("jobName", name);
            detail.put("severity", "SUCCEEDED".equals(state) ? "INFO" : "ERROR");
            detail.put("state", state);
            detail.put("jobRunId", runId);
            detail.put("message", error == null ? "Job run " + state.toLowerCase(Locale.ROOT) : error);
            try {
                events.get().publishServiceEvent("aws.databrew", "DataBrew Job State Change", detail.toString(),
                        arn(region, "job", name), region);
            } catch (RuntimeException e) {
                LOG.debugv("Could not publish a DataBrew job event: {0}", e.getMessage());
            }
        }
    }

    private void log(String region, String name, String runId, JsonNode run, String message) {
        if ("ENABLE".equals(run.path("LogSubscription").asText("ENABLE"))) {
            logLine(region, name, runId, message);
        }
    }

    private void logLine(String region, String name, String runId, String message) {
        if (!logs.isResolvable()) {
            return;
        }
        String group = "/aws-glue-databrew/jobs/" + name;
        try {
            try {
                logs.get().createLogGroup(group, null, Map.of(), region);
            } catch (AwsException e) {
                // The group already exists.
            }
            try {
                logs.get().createLogStream(group, runId, region);
            } catch (AwsException e) {
                // The stream already exists.
            }
            logs.get().putLogEvents(group, runId, List.of(Map.of("timestamp", Instant.now().toEpochMilli(),
                    "message", message)), region);
        } catch (RuntimeException e) {
            LOG.debugv("Could not log a DataBrew job run: {0}", e.getMessage());
        }
    }

    private List<ObjectNode> runsOf(String region, String name) {
        String prefix = key(region, name + "/");
        return runs.scanForAccount(runs.accountId(), key -> key.startsWith(prefix));
    }

    private ObjectNode runSummary(ObjectNode run) {
        ObjectNode summary = view(run);
        summary.remove("ProfileConfiguration");
        return summary;
    }

    private String datasetOf(JsonNode job, String region) {
        if (job.hasNonNull("DatasetName")) {
            return job.get("DatasetName").asText();
        }
        return projects.getForAccount(projects.accountId(), key(region, job.path("ProjectName").asText()))
                .map(project -> project.path("DatasetName").asText()).orElse(null);
    }

    // ---------------------------------------------------------------- rulesets

    public synchronized ObjectNode createRuleset(JsonNode request, String region) {
        String name = name(request, "Name", 255);
        conflict(rulesets, region, name, "Ruleset");
        quota(rulesets, region, "rulesets");
        String target = text(request, "TargetArn", 2048);
        boolean exists = regional(datasets, region).stream()
                .anyMatch(dataset -> target.equals(dataset.path("ResourceArn").asText()));
        if (!exists) {
            throw invalid("TargetArn " + target + " is not a DataBrew dataset.");
        }
        ObjectNode ruleset = mapper.createObjectNode();
        ruleset.put("Name", name);
        if (present(request, "Description")) {
            ruleset.put("Description", text(request, "Description", 1024));
        }
        ruleset.put("TargetArn", target);
        ruleset.set("Rules", rules(request.path("Rules")));
        stamp(ruleset, true);
        ruleset.put("ResourceArn", arn(region, "ruleset", name));
        ruleset.set("Tags", tags(request.path("Tags")));
        ruleset.putObject("_").put("region", region);
        rulesets.putForAccount(rulesets.accountId(), key(region, name), ruleset);
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode describeRuleset(String name, String region) {
        return view(existing(rulesets, region, name, "Ruleset"));
    }

    public synchronized ObjectNode updateRuleset(String name, JsonNode request, String region) {
        ObjectNode ruleset = existing(rulesets, region, name, "Ruleset");
        if (present(request, "Description")) {
            ruleset.put("Description", text(request, "Description", 1024));
        } else {
            ruleset.remove("Description");
        }
        ruleset.set("Rules", rules(request.path("Rules")));
        stamp(ruleset, false);
        save(rulesets, region, name, ruleset);
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode deleteRuleset(String name, String region) {
        ObjectNode ruleset = existing(rulesets, region, name, "Ruleset");
        String rulesetArn = ruleset.path("ResourceArn").asText();
        for (ObjectNode job : regional(jobs, region)) {
            for (JsonNode validation : job.path("ValidationConfigurations")) {
                if (rulesetArn.equals(validation.path("RulesetArn").asText())) {
                    throw conflict("Ruleset " + name + " is used by job " + job.path("Name").asText() + ".");
                }
            }
        }
        rulesets.deleteForAccount(rulesets.accountId(), key(region, name));
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode listRulesets(JsonNode query, String region) {
        String target = query.path("targetArn").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode ruleset : regional(rulesets, region)) {
            if (target != null && !target.equals(ruleset.path("TargetArn").asText())) {
                continue;
            }
            ObjectNode item = view(ruleset);
            item.put("RuleCount", ruleset.path("Rules").size());
            item.remove("Rules");
            item.put("AccountId", rulesets.accountId());
            items.add(item);
        }
        return page(items, query, "Rulesets");
    }

    private ArrayNode rules(JsonNode input) {
        if (!input.isArray() || input.isEmpty()) {
            throw invalid("Rules must list at least one rule.");
        }
        Set<String> names = new HashSet<>();
        for (JsonNode rule : input) {
            String name = text(rule, "Name", 128);
            if (!names.add(name)) {
                throw invalid("Rule names must be unique; " + name + " repeats.");
            }
            String expression = text(rule, "CheckExpression", 1024);
            if (expression.length() < 4 || !expression.matches("^[<>0-9A-Za-z_.,:)(!= ]+$")) {
                throw invalid("CheckExpression " + expression + " is not a valid check expression.");
            }
            if (rule.has("Threshold")) {
                if (!rule.path("Threshold").path("Value").isNumber()) {
                    throw invalid("Threshold.Value must be a number.");
                }
            }
        }
        return (ArrayNode) input.deepCopy();
    }

    private Optional<ObjectNode> rulesetByArn(String region, String rulesetArn) {
        return regional(rulesets, region).stream()
                .filter(ruleset -> rulesetArn.equals(ruleset.path("ResourceArn").asText())).findFirst();
    }

    // ---------------------------------------------------------------- schedules

    public synchronized ObjectNode createSchedule(JsonNode request, String region) {
        String name = name(request, "Name", 255);
        conflict(schedules, region, name, "Schedule");
        quota(schedules, region, "schedules");
        ObjectNode schedule = mapper.createObjectNode();
        schedule.put("Name", name);
        applySchedule(schedule, request, region);
        stamp(schedule, true);
        schedule.put("ResourceArn", arn(region, "schedule", name));
        schedule.set("Tags", tags(request.path("Tags")));
        schedule.putObject("_").put("region", region).put("lastCheck", Instant.now().getEpochSecond());
        schedules.putForAccount(schedules.accountId(), key(region, name), schedule);
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode describeSchedule(String name, String region) {
        return view(existing(schedules, region, name, "Schedule"));
    }

    public synchronized ObjectNode updateSchedule(String name, JsonNode request, String region) {
        ObjectNode schedule = existing(schedules, region, name, "Schedule");
        schedule.remove("JobNames");
        applySchedule(schedule, request, region);
        stamp(schedule, false);
        save(schedules, region, name, schedule);
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode deleteSchedule(String name, String region) {
        existing(schedules, region, name, "Schedule");
        schedules.deleteForAccount(schedules.accountId(), key(region, name));
        return mapper.createObjectNode().put("Name", name);
    }

    public synchronized ObjectNode listSchedules(JsonNode query, String region) {
        String job = query.path("jobName").asText(null);
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode schedule : regional(schedules, region)) {
            boolean names = job == null;
            for (JsonNode value : schedule.path("JobNames")) {
                names |= value.asText().equals(job);
            }
            if (names) {
                result.add(view(schedule));
            }
        }
        return page(result, query, "Schedules");
    }

    private void applySchedule(ObjectNode schedule, JsonNode request, String region) {
        String expression = text(request, "CronExpression", 512);
        cron(expression);
        schedule.put("CronExpression", expression);
        schedule.putArray("JobNames");
        if (request.has("JobNames")) {
            JsonNode names = request.get("JobNames");
            if (!names.isArray() || names.size() > 50) {
                throw invalid("JobNames must list at most 50 jobs.");
            }
            ArrayNode stored = schedule.putArray("JobNames");
            for (JsonNode value : new LinkedHashSet<>(toList(names))) {
                if (jobs.getForAccount(jobs.accountId(), key(region, value.asText())).isEmpty()) {
                    throw invalid("Job " + value.asText() + " does not exist.");
                }
                stored.add(value.asText());
            }
        }
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    private static Cron cron(String expression) {
        String text = expression.strip();
        if (!text.startsWith("cron(") || !text.endsWith(")")) {
            throw invalid("CronExpression must have the form cron(minutes hours day-of-month month day-of-week year).");
        }
        try {
            return AwsCronDefinitions.newParser().parse("0 " + text.substring(5, text.length() - 1).strip());
        } catch (IllegalArgumentException e) {
            throw invalid("CronExpression " + expression + " is not valid: " + e.getMessage());
        }
    }

    /** Starts the jobs of every schedule whose cron fired since the last check. */
    void runSchedules() {
        long now = Instant.now().getEpochSecond();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : schedules.scanAllAccountEntries(key -> true)) {
            String account = entry.accountId();
            ObjectNode schedule = entry.value();
            String region = schedule.path("_").path("region").asText();
            try {
                RequestScopes.runAs(account, region, () -> fireIfDue(schedule, region, now));
            } catch (RuntimeException e) {
                LOG.debugv("DataBrew schedule {0} failed: {1}", schedule.path("Name").asText(), e.getMessage());
            }
        }
    }

    private synchronized void fireIfDue(ObjectNode stored, String region, long now) {
        String name = stored.path("Name").asText();
        ObjectNode schedule = schedules.getForAccount(schedules.accountId(), key(region, name)).orElse(null);
        if (schedule == null) {
            return;
        }
        long last = schedule.path("_").path("lastCheck").asLong(now);
        ExecutionTime execution = ExecutionTime.forCron(cron(schedule.path("CronExpression").asText()));
        Optional<ZonedDateTime> next = execution.nextExecution(Instant.ofEpochSecond(last).atZone(ZoneOffset.UTC));
        ((ObjectNode) schedule.get("_")).put("lastCheck", now);
        save(schedules, region, name, schedule);
        if (next.isEmpty() || next.get().toEpochSecond() > now) {
            return;
        }
        for (JsonNode jobName : schedule.path("JobNames")) {
            jobs.getForAccount(jobs.accountId(), key(region, jobName.asText())).ifPresent(job ->
                    startRun(job, region, "db_" + HexFormat.of().formatHex(bytes(32)), "schedule/" + name, 0));
        }
    }

    // ---------------------------------------------------------------- tags

    public synchronized ObjectNode listTags(String resourceArn, String region) {
        Located located = locate(resourceArn);
        ObjectNode response = mapper.createObjectNode();
        response.set("Tags", located.resource().path("Tags").deepCopy());
        return response;
    }

    public synchronized void tagResource(String resourceArn, JsonNode request) {
        Located located = locate(resourceArn);
        ObjectNode current = (ObjectNode) located.resource().get("Tags");
        current.setAll(tags(request.path("Tags")));
        if (current.size() > 200) {
            throw invalid("A resource may have at most 200 tags.");
        }
        located.save().run();
    }

    public synchronized void untagResource(String resourceArn, List<String> keys) {
        Located located = locate(resourceArn);
        ObjectNode current = (ObjectNode) located.resource().get("Tags");
        keys.forEach(current::remove);
        located.save().run();
    }

    private record Located(ObjectNode resource, Runnable save) {}

    private Located locate(String resourceArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid ResourceArn " + resourceArn + ".");
        }
        int slash = arn.resource().indexOf('/');
        if (!"databrew".equals(arn.service()) || slash < 0) {
            throw invalid("Invalid ResourceArn " + resourceArn + ".");
        }
        String type = arn.resource().substring(0, slash);
        String name = arn.resource().substring(slash + 1);
        String region = arn.region();
        if (!arn.accountId().equals(datasets.accountId())) {
            throw notFound("Resource " + resourceArn + " does not exist.");
        }
        AccountAwareStorageBackend<ObjectNode> storage = switch (type) {
            case "dataset" -> datasets;
            case "project" -> projects;
            case "job" -> jobs;
            case "ruleset" -> rulesets;
            case "schedule" -> schedules;
            case "recipe" -> recipes;
            default -> throw invalid("Invalid ResourceArn " + resourceArn + ".");
        };
        ObjectNode resource = storage.getForAccount(storage.accountId(), key(region, name))
                .orElseThrow(() -> notFound("Resource " + resourceArn + " does not exist."));
        return new Located(resource, () -> save(storage, region, name, resource));
    }

    @Override
    public synchronized void clear() {
        datasets.clear();
        recipes.clear();
        projects.clear();
        jobs.clear();
        runs.clear();
        rulesets.clear();
        schedules.clear();
    }

    // ---------------------------------------------------------------- helpers

    private ObjectNode sample(JsonNode input) {
        ObjectNode sample = mapper.createObjectNode();
        if (input == null || input.isMissingNode() || input.isNull()) {
            sample.put("Size", 500);
            sample.put("Type", "FIRST_N");
            return sample;
        }
        String type = oneOf(input, "Type", Set.of("FIRST_N", "LAST_N", "RANDOM"));
        int size = input.path("Size").asInt(500);
        if (size < 1 || size > 5000) {
            throw invalid("Sample.Size must be between 1 and 5000.");
        }
        sample.put("Size", size);
        sample.put("Type", type);
        return sample;
    }

    private static String role(String roleArn) {
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
            if (!"iam".equals(arn.service()) || !arn.resource().startsWith("role/")) {
                throw invalid("RoleArn must be an IAM role ARN.");
            }
        } catch (IllegalArgumentException e) {
            throw invalid("RoleArn must be an IAM role ARN.");
        }
        return roleArn;
    }

    private ObjectNode tags(JsonNode input) {
        ObjectNode tags = mapper.createObjectNode();
        if (input == null || !input.isObject()) {
            return tags;
        }
        input.properties().forEach(entry -> {
            if (entry.getKey().isEmpty() || entry.getKey().length() > 128 || entry.getValue().asText().length() > 256) {
                throw invalid("Tag keys must be 1 to 128 characters and values at most 256.");
            }
            tags.put(entry.getKey(), entry.getValue().asText());
        });
        return tags;
    }

    private void stamp(ObjectNode resource, boolean created) {
        double now = now();
        String caller = caller();
        if (created) {
            resource.put("CreatedBy", caller);
            resource.put("CreateDate", now);
            resource.put("AccountId", datasets.accountId());
        }
        resource.put("LastModifiedBy", caller);
        resource.put("LastModifiedDate", now);
    }

    private String caller() {
        return regionResolver.buildGlobalArn("iam", datasets.accountId(), "root");
    }

    private String arn(String region, String type, String name) {
        return regionResolver.buildArn("databrew", region, datasets.accountId(), type + "/" + name);
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private void conflict(AccountAwareStorageBackend<ObjectNode> storage, String region, String name, String kind) {
        if (storage.getForAccount(storage.accountId(), key(region, name)).isPresent()) {
            throw conflict(kind + " " + name + " already exists.");
        }
    }

    private void jobConflict(String region, String name) {
        conflict(jobs, region, name, "Job");
        quota(jobs, region, "jobs");
    }

    private void quota(AccountAwareStorageBackend<ObjectNode> storage, String region, String kind) {
        if (regional(storage, region).size() >= MAX_RESOURCES) {
            throw new AwsException("ServiceQuotaExceededException", "The account has reached its limit of "
                    + MAX_RESOURCES + " " + kind + ".", 402);
        }
    }

    private ObjectNode existing(AccountAwareStorageBackend<ObjectNode> storage, String region, String name,
                                String kind) {
        return storage.getForAccount(storage.accountId(), key(region, name))
                .orElseThrow(() -> notFound(kind + " " + name + " does not exist."));
    }

    private static void save(AccountAwareStorageBackend<ObjectNode> storage, String region, String name,
                             ObjectNode resource) {
        storage.putForAccount(storage.accountId(), key(region, name), resource);
    }

    private static List<ObjectNode> regional(AccountAwareStorageBackend<ObjectNode> storage, String region) {
        String prefix = region + "/";
        List<ObjectNode> result = new ArrayList<>(storage.scanForAccount(storage.accountId(),
                key -> key.startsWith(prefix) && key.indexOf('/', prefix.length()) < 0));
        result.sort(Comparator.comparing(resource -> resource.path("Name").asText()));
        return result;
    }

    private ObjectNode view(ObjectNode stored) {
        ObjectNode copy = stored.deepCopy();
        copy.remove("_");
        return copy;
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode query, String field) {
        Integer max = null;
        if (query.hasNonNull("maxResults")) {
            int value = query.get("maxResults").asInt(-1);
            if (value < 1 || value > 100) {
                throw invalid("MaxResults must be between 1 and 100.");
            }
            max = value;
        }
        String token = query.path("nextToken").asText(null);
        Function<ObjectNode, String> cursor = item -> item.path("Name").asText(item.path("RunId").asText());
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items, cursor, max == null ? 100 : max, token, field,
                bad -> invalid("Invalid NextToken."));
        ObjectNode response = mapper.createObjectNode();
        response.putArray(field).addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextToken", paged.nextToken());
        }
        return response;
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private static ObjectNode object(JsonNode parent, String field) {
        if (parent != null && parent.get(field) instanceof ObjectNode node) {
            return node;
        }
        throw invalid(field + " is required.");
    }

    private static String name(JsonNode request, String field, int maximum) {
        String value = text(request, field, maximum);
        if (value.isEmpty()) {
            throw invalid(field + " must not be empty.");
        }
        return value;
    }

    private static String text(JsonNode parent, String field, int maximum) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " is required.");
        }
        if (value.textValue().length() > maximum) {
            throw invalid(field + " must be at most " + maximum + " characters.");
        }
        return value.textValue();
    }

    private static String oneOf(JsonNode parent, String field, Set<String> allowed) {
        String value = text(parent, field, 1024);
        if (!allowed.contains(value)) {
            throw invalid(field + " must be one of " + String.join(", ", new TreeSet<>(allowed)) + ".");
        }
        return value;
    }

    private static boolean present(JsonNode node, String field) {
        return node != null && node.hasNonNull(field);
    }

    static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }
}
