package io.floci.aws.services.bedrockdataautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Amazon Bedrock Data Automation's control plane: blueprints (a schema per stage, {@code LIVE} and
 * {@code DEVELOPMENT}, and numbered versions taken from {@code LIVE}), projects (standard and custom output
 * configuration per stage), libraries of vocabulary entities filled by ingestion jobs, and blueprint optimization,
 * which rewrites a blueprint's field instructions from labelled samples. The AWS-managed public blueprints are listed
 * with {@code resourceOwner: SERVICE}. Tags on all of them, and on runtime invocations, are kept here.
 */
@ApplicationScoped
public class BdaService implements Resettable {
    static final String STAGE_LIVE = "LIVE";
    private static final long ASYNC_DELAY_MILLIS = 500;
    private static final int MAX_TAGS = 200;

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/bedrock-data-automation-request-shapes.json", "Bedrock Data Automation request shapes",
            messages());

    static RequestConstraintCheck.Messages messages() {
        return new RequestConstraintCheck.Messages() {
            @Override
            public AwsException type(String path, String type) {
                return validation(path + " must be a " + type + ".");
            }

            @Override
            public AwsException bounds(String path, String type, Long min, Long max, long size) {
                return validation(path + " is outside the allowed bounds" + (min == null ? "" : " (min " + min
                        + ")") + (max == null ? "" : " (max " + max + ")") + ".");
            }

            @Override
            public AwsException pattern(String path, String pattern) {
                return validation(path + " does not match the pattern " + pattern + ".");
            }

            @Override
            public AwsException enumValue(String path, List<String> values) {
                return validation(path + " must be one of " + values + ".");
            }

            @Override
            public AwsException required(String path) {
                return validation(path + " is required.");
            }
        };
    }

    private final AccountAwareStorageBackend<ObjectNode> blueprints;
    private final AccountAwareStorageBackend<ObjectNode> projects;
    private final AccountAwareStorageBackend<ObjectNode> libraries;
    private final AccountAwareStorageBackend<ObjectNode> ingestionJobs;
    private final AccountAwareStorageBackend<ObjectNode> optimizations;
    private final AccountAwareStorageBackend<ObjectNode> tags;
    private final Instance<S3Service> s3;
    private final Instance<KmsService> kms;
    private final BdaProcessing processing;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "bda-control-jobs");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public BdaService(StorageFactory storageFactory, Instance<S3Service> s3, Instance<KmsService> kms,
                      BdaProcessing processing, RegionResolver regionResolver, ObjectMapper mapper) {
        this.blueprints = store(storageFactory, "bda-blueprints.json");
        this.projects = store(storageFactory, "bda-projects.json");
        this.libraries = store(storageFactory, "bda-libraries.json");
        this.ingestionJobs = store(storageFactory, "bda-library-ingestion-jobs.json");
        this.optimizations = store(storageFactory, "bda-blueprint-optimizations.json");
        this.tags = store(storageFactory, "bda-tags.json");
        this.s3 = s3;
        this.kms = kms;
        this.processing = processing;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    static AccountAwareStorageBackend<ObjectNode> store(StorageFactory factory, String file) {
        return factory.create("bedrock-data-automation", file, new TypeReference<Map<String, ObjectNode>>() {});
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    static void check(String operation, JsonNode request, String... members) {
        ObjectNode checked = request != null && request.isObject() ? ((ObjectNode) request).deepCopy()
                : new ObjectMapper().createObjectNode();
        for (int i = 0; i + 1 < members.length; i += 2) {
            if (members[i + 1] != null) {
                checked.put(members[i], members[i + 1]);
            }
        }
        SHAPES.check(operation, checked);
    }

    // ── Blueprints ─────────────────────────────────────────────────────────

    public synchronized ObjectNode createBlueprint(JsonNode in, String region) {
        check("CreateBlueprint", in);
        String name = in.path("blueprintName").asText();
        if (all(blueprints, region).stream().anyMatch(b -> name.equals(b.path("blueprintName").asText()))) {
            throw new AwsException("ConflictException", "A blueprint named " + name + " already exists.", 409);
        }
        JsonNode schema = checkSchema(in.path("schema").asText(), in.path("type").asText());
        checkEncryption(in.path("encryptionConfiguration"), region);
        String id = HexFormat.of().formatHex(bytes(6));
        double now = now();
        ObjectNode blueprint = mapper.createObjectNode();
        blueprint.put("blueprintArn", arn(region, "blueprint/" + id));
        blueprint.put("blueprintName", name);
        blueprint.put("type", in.path("type").asText());
        blueprint.put("creationTime", now);
        blueprint.put("lastModifiedTime", now);
        encryption(in.path("encryptionConfiguration"), blueprint);
        ObjectNode stages = blueprint.putObject("stages");
        stages.putObject(in.path("blueprintStage").asText(STAGE_LIVE)).put("schema", schema.toString())
                .put("lastModifiedTime", now);
        blueprint.putObject("versions");
        blueprints.put(region + "/" + id, blueprint);
        putTags(blueprint.path("blueprintArn").asText(), in.path("tags"));
        return mapper.createObjectNode().set("blueprint", render(blueprint, in.path("blueprintStage")
                .asText(STAGE_LIVE), null));
    }

    public synchronized ObjectNode getBlueprint(String arn, JsonNode in, String region) {
        check("GetBlueprint", in, "blueprintArn", arn);
        Optional<ObjectNode> publicBlueprint = BdaPublicBlueprints.find(arn);
        if (publicBlueprint.isPresent()) {
            return mapper.createObjectNode().set("blueprint", publicBlueprint.get());
        }
        ObjectNode blueprint = requireBlueprint(arn, region);
        String version = in.path("blueprintVersion").asText(null);
        String stage = in.path("blueprintStage").asText(STAGE_LIVE);
        if (version != null && !blueprint.path("versions").has(version)) {
            throw notFound("The blueprint " + arn + " has no version " + version + ".");
        }
        if (version == null && !blueprint.path("stages").has(stage)) {
            throw notFound("The blueprint " + arn + " has no " + stage + " stage.");
        }
        return mapper.createObjectNode().set("blueprint", render(blueprint, stage, version));
    }

    public synchronized ObjectNode updateBlueprint(String arn, JsonNode in, String region) {
        check("UpdateBlueprint", in, "blueprintArn", arn);
        ObjectNode blueprint = requireBlueprint(arn, region);
        String stage = in.path("blueprintStage").asText(STAGE_LIVE);
        JsonNode schema = checkSchema(in.path("schema").asText(), blueprint.path("type").asText());
        if (in.has("encryptionConfiguration")) {
            checkEncryption(in.path("encryptionConfiguration"), region);
            encryption(in.path("encryptionConfiguration"), blueprint);
        }
        double now = now();
        blueprint.with("stages").putObject(stage).put("schema", schema.toString()).put("lastModifiedTime", now);
        blueprint.put("lastModifiedTime", now);
        save(blueprint, region);
        return mapper.createObjectNode().set("blueprint", render(blueprint, stage, null));
    }

    /** Snapshots the {@code LIVE} stage as the next numbered version. */
    public synchronized ObjectNode createBlueprintVersion(String arn, JsonNode in, String region) {
        check("CreateBlueprintVersion", in, "blueprintArn", arn);
        ObjectNode blueprint = requireBlueprint(arn, region);
        JsonNode live = blueprint.path("stages").path(STAGE_LIVE);
        if (!live.isObject()) {
            throw validation("The blueprint has no LIVE stage to version; copy its DEVELOPMENT stage to LIVE first.");
        }
        ObjectNode versions = blueprint.with("versions");
        if (versions.size() >= 100) {
            throw new AwsException("ServiceQuotaExceededException", "A blueprint can have at most 100 versions.",
                    402);
        }
        String version = String.valueOf(versions.size() + 1);
        while (versions.has(version)) {
            version = String.valueOf(Integer.parseInt(version) + 1);
        }
        versions.putObject(version).put("schema", live.path("schema").asText()).put("creationTime", now());
        save(blueprint, region);
        return mapper.createObjectNode().set("blueprint", render(blueprint, STAGE_LIVE, version));
    }

    public synchronized void copyBlueprintStage(String arn, JsonNode in, String region) {
        check("CopyBlueprintStage", in, "blueprintArn", arn);
        ObjectNode blueprint = requireBlueprint(arn, region);
        String source = in.path("sourceStage").asText();
        String target = in.path("targetStage").asText();
        if (source.equals(target)) {
            throw validation("sourceStage and targetStage must differ.");
        }
        JsonNode from = blueprint.path("stages").path(source);
        if (!from.isObject()) {
            throw notFound("The blueprint " + arn + " has no " + source + " stage to copy.");
        }
        blueprint.with("stages").set(target, ((ObjectNode) from.deepCopy()).put("lastModifiedTime", now()));
        blueprint.put("lastModifiedTime", now());
        save(blueprint, region);
    }

    /** Deletes a version, or with no version the whole blueprint. */
    public synchronized void deleteBlueprint(String arn, String version, String region) {
        check("DeleteBlueprint", null, "blueprintArn", arn, "blueprintVersion", version);
        ObjectNode blueprint = requireBlueprint(arn, region);
        if (version != null) {
            if (!blueprint.path("versions").has(version)) {
                throw notFound("The blueprint " + arn + " has no version " + version + ".");
            }
            ((ObjectNode) blueprint.path("versions")).remove(version);
            save(blueprint, region);
            return;
        }
        blueprints.delete(key(region, arn));
        tags.delete(arn);
    }

    public synchronized ObjectNode listBlueprints(JsonNode in, String region) {
        check("ListBlueprints", in);
        String filterArn = in.path("blueprintArn").asText(null);
        String stageFilter = in.path("blueprintStageFilter").asText(STAGE_LIVE);
        List<ObjectNode> summaries = new ArrayList<>();
        if ("SERVICE".equals(in.path("resourceOwner").asText())) {
            BdaPublicBlueprints.all(region).forEach(b -> summaries.add(summary(b, b.path("blueprintStage").asText(),
                    null)));
        } else {
            List<String> used = null;
            if (in.path("projectFilter").isObject()) {
                ObjectNode project = requireProject(in.path("projectFilter").path("projectArn").asText(), region);
                JsonNode stage = project.path("stages").path(in.path("projectFilter").path("projectStage")
                        .asText(STAGE_LIVE));
                used = new ArrayList<>();
                for (JsonNode item : stage.path("customOutputConfiguration").path("blueprints")) {
                    used.add(item.path("blueprintArn").asText());
                }
            }
            for (ObjectNode blueprint : all(blueprints, region)) {
                String arn = blueprint.path("blueprintArn").asText();
                if (filterArn != null && !filterArn.equals(arn) || used != null && !used.contains(arn)) {
                    continue;
                }
                for (String stage : List.of(STAGE_LIVE, "DEVELOPMENT")) {
                    if (blueprint.path("stages").has(stage) && ("ALL".equals(stageFilter)
                            || stageFilter.equals(stage))) {
                        summaries.add(summary(blueprint, stage, null));
                    }
                }
                if (filterArn != null) {
                    blueprint.path("versions").fieldNames().forEachRemaining(v -> summaries.add(summary(blueprint,
                            STAGE_LIVE, v)));
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("blueprints");
        page(summaries, in, out).forEach(list::add);
        return out;
    }

    private ObjectNode summary(ObjectNode blueprint, String stage, String version) {
        ObjectNode out = mapper.createObjectNode();
        out.put("blueprintArn", blueprint.path("blueprintArn").asText());
        if (version != null) {
            out.put("blueprintVersion", version);
        }
        out.put("blueprintStage", stage);
        out.put("blueprintName", blueprint.path("blueprintName").asText());
        out.put("creationTime", blueprint.path("creationTime").asDouble());
        out.put("lastModifiedTime", blueprint.path("lastModifiedTime").asDouble());
        return out;
    }

    /** A blueprint as the API returns it, at a stage or a version. */
    ObjectNode render(ObjectNode stored, String stage, String version) {
        ObjectNode out = mapper.createObjectNode();
        out.put("blueprintArn", stored.path("blueprintArn").asText());
        JsonNode source = version != null ? stored.path("versions").path(version) : stored.path("stages").path(stage);
        out.put("schema", source.path("schema").asText());
        out.put("type", stored.path("type").asText());
        out.put("creationTime", stored.path("creationTime").asDouble());
        out.put("lastModifiedTime", source.path("lastModifiedTime").asDouble(stored.path("lastModifiedTime")
                .asDouble()));
        out.put("blueprintName", stored.path("blueprintName").asText());
        if (version != null) {
            out.put("blueprintVersion", version);
        } else {
            out.put("blueprintStage", stage);
        }
        copy(stored, out, "kmsKeyId", "kmsEncryptionContext");
        copy(source, out, "optimizationSamples", "optimizationTime");
        return out;
    }

    /** The schema of a blueprint (or public blueprint) at a stage or version, for processing. */
    synchronized Optional<BdaProcessing.Candidate> candidate(String arn, String version, String stage, String region) {
        Optional<ObjectNode> publicBlueprint = BdaPublicBlueprints.find(arn);
        if (publicBlueprint.isPresent()) {
            ObjectNode b = publicBlueprint.get();
            return Optional.of(new BdaProcessing.Candidate(arn, b.path("blueprintName").asText(), null, STAGE_LIVE,
                    parse(b.path("schema").asText())));
        }
        Optional<ObjectNode> stored = blueprints.get(key(region, arn));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        JsonNode source = version != null ? stored.get().path("versions").path(version)
                : stored.get().path("stages").path(stage == null ? STAGE_LIVE : stage);
        if (!source.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new BdaProcessing.Candidate(arn, stored.get().path("blueprintName").asText(), version,
                stage, parse(source.path("schema").asText())));
    }

    /** A blueprint schema: JSON draft-07 with a class, a description and at least one property of the right kind. */
    private JsonNode checkSchema(String text, String type) {
        JsonNode schema;
        try {
            schema = mapper.readTree(text);
        } catch (Exception e) {
            throw validation("The blueprint schema isn't valid JSON: " + e.getMessage());
        }
        if (!schema.isObject() || !schema.path("properties").isObject() || schema.path("properties").isEmpty()) {
            throw validation("The blueprint schema must be a JSON schema object with at least one property.");
        }
        if (!schema.hasNonNull("class")) {
            throw validation("The blueprint schema needs a class.");
        }
        List<String> problems = new ArrayList<>();
        schema.path("properties").fields().forEachRemaining(field -> {
            JsonNode spec = field.getValue();
            if (spec.has("$ref")) {
                String ref = spec.path("$ref").asText();
                if (!ref.startsWith("#/definitions/") || !schema.path("definitions").has(ref.substring(14))) {
                    problems.add(field.getKey() + " refers to " + ref + ", which isn't in definitions");
                }
                return;
            }
            String inference = spec.path("inferenceType").asText("");
            if (!List.of("explicit", "inferred").contains(inference)) {
                problems.add(field.getKey() + " needs an inferenceType of explicit or inferred");
            }
            if (!spec.hasNonNull("instruction")) {
                problems.add(field.getKey() + " needs an instruction");
            }
        });
        if (!problems.isEmpty()) {
            throw validation("The " + type + " blueprint schema isn't valid: " + String.join("; ", problems) + ".");
        }
        return schema;
    }

    // ── Projects ───────────────────────────────────────────────────────────

    public synchronized ObjectNode createProject(JsonNode in, String region) {
        check("CreateDataAutomationProject", in);
        String name = in.path("projectName").asText();
        if (all(projects, region).stream().anyMatch(p -> name.equals(p.path("projectName").asText()))) {
            throw new AwsException("ConflictException", "A project named " + name + " already exists.", 409);
        }
        checkProjectConfig(in, region);
        checkEncryption(in.path("encryptionConfiguration"), region);
        String id = HexFormat.of().formatHex(bytes(6));
        double now = now();
        ObjectNode project = mapper.createObjectNode();
        project.put("projectArn", arn(region, "data-automation-project/" + id));
        project.put("projectName", name);
        project.put("projectType", in.path("projectType").asText("ASYNC"));
        project.put("creationTime", now);
        project.put("lastModifiedTime", now);
        project.put("status", "COMPLETED");
        encryption(in.path("encryptionConfiguration"), project);
        String stage = in.path("projectStage").asText(STAGE_LIVE);
        project.putObject("stages").set(stage, projectStage(in, now));
        projects.put(region + "/" + id, project);
        putTags(project.path("projectArn").asText(), in.path("tags"));
        return mapper.createObjectNode().put("projectArn", project.path("projectArn").asText())
                .put("projectStage", stage).put("status", "COMPLETED");
    }

    public synchronized ObjectNode getProject(String arn, JsonNode in, String region) {
        check("GetDataAutomationProject", in, "projectArn", arn);
        ObjectNode project = requireProject(arn, region);
        String stage = in.path("projectStage").asText(STAGE_LIVE);
        if (!project.path("stages").has(stage)) {
            throw notFound("The project " + arn + " has no " + stage + " stage.");
        }
        return mapper.createObjectNode().set("project", renderProject(project, stage));
    }

    public synchronized ObjectNode updateProject(String arn, JsonNode in, String region) {
        check("UpdateDataAutomationProject", in, "projectArn", arn);
        ObjectNode project = requireProject(arn, region);
        checkProjectConfig(in, region);
        if (in.has("encryptionConfiguration")) {
            checkEncryption(in.path("encryptionConfiguration"), region);
            encryption(in.path("encryptionConfiguration"), project);
        }
        double now = now();
        String stage = in.path("projectStage").asText(STAGE_LIVE);
        project.with("stages").set(stage, projectStage(in, now));
        project.put("lastModifiedTime", now);
        project.put("status", "COMPLETED");
        projects.put(key(region, arn), project);
        return mapper.createObjectNode().put("projectArn", arn).put("projectStage", stage).put("status", "COMPLETED");
    }

    public synchronized ObjectNode deleteProject(String arn, String region) {
        check("DeleteDataAutomationProject", null, "projectArn", arn);
        requireProject(arn, region);
        projects.delete(key(region, arn));
        tags.delete(arn);
        return mapper.createObjectNode().put("projectArn", arn).put("status", "COMPLETED");
    }

    public synchronized ObjectNode listProjects(JsonNode in, String region) {
        check("ListDataAutomationProjects", in);
        String stageFilter = in.path("projectStageFilter").asText(STAGE_LIVE);
        String blueprintArn = in.path("blueprintFilter").path("blueprintArn").asText(null);
        String libraryArn = in.path("libraryFilter").path("libraryArn").asText(null);
        List<ObjectNode> summaries = new ArrayList<>();
        if ("SERVICE".equals(in.path("resourceOwner").asText())) {
            ObjectNode out = mapper.createObjectNode();
            out.putArray("projects");
            return out;
        }
        for (ObjectNode project : all(projects, region)) {
            for (String stage : List.of(STAGE_LIVE, "DEVELOPMENT")) {
                JsonNode config = project.path("stages").path(stage);
                if (!config.isObject() || !"ALL".equals(stageFilter) && !stageFilter.equals(stage)) {
                    continue;
                }
                if (blueprintArn != null && !uses(config.path("customOutputConfiguration").path("blueprints"),
                        "blueprintArn", blueprintArn)) {
                    continue;
                }
                if (libraryArn != null && !uses(config.path("dataAutomationLibraryConfiguration").path("libraries"),
                        "libraryArn", libraryArn)) {
                    continue;
                }
                ObjectNode summary = mapper.createObjectNode();
                summary.put("projectArn", project.path("projectArn").asText());
                summary.put("projectStage", stage);
                summary.put("projectType", project.path("projectType").asText());
                summary.put("projectName", project.path("projectName").asText());
                summary.put("creationTime", project.path("creationTime").asDouble());
                summaries.add(summary);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("projects");
        page(summaries, in, out).forEach(list::add);
        return out;
    }

    private static boolean uses(JsonNode items, String field, String arn) {
        for (JsonNode item : items) {
            if (arn.equals(item.path(field).asText())) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode projectStage(JsonNode in, double now) {
        ObjectNode stage = mapper.createObjectNode();
        copy(in, stage, "projectDescription", "standardOutputConfiguration", "customOutputConfiguration",
                "overrideConfiguration", "dataAutomationLibraryConfiguration");
        stage.put("lastModifiedTime", now);
        return stage;
    }

    /** Every blueprint and library a project names must exist, at the version or stage it names. */
    private void checkProjectConfig(JsonNode in, String region) {
        for (JsonNode item : in.path("customOutputConfiguration").path("blueprints")) {
            String arn = item.path("blueprintArn").asText();
            if (candidate(arn, item.path("blueprintVersion").asText(null), item.path("blueprintStage")
                    .asText(STAGE_LIVE), region).isEmpty()) {
                throw notFound("The blueprint " + arn + " doesn't exist at the version or stage the project names.");
            }
        }
        for (JsonNode item : in.path("customOutputConfiguration").path("document").path("fallbackBlueprints")) {
            if (candidate(item.path("blueprintArn").asText(), item.path("blueprintVersion").asText(null),
                    item.path("blueprintStage").asText(STAGE_LIVE), region).isEmpty()) {
                throw notFound("The fallback blueprint " + item.path("blueprintArn").asText() + " doesn't exist.");
            }
        }
        for (JsonNode item : in.path("dataAutomationLibraryConfiguration").path("libraries")) {
            requireLibrary(item.path("libraryArn").asText(), region);
        }
        if (in.path("customOutputConfiguration").path("blueprints").size() > 40) {
            throw validation("A project can use at most 40 blueprints.");
        }
    }

    private ObjectNode renderProject(ObjectNode stored, String stage) {
        ObjectNode out = mapper.createObjectNode();
        out.put("projectArn", stored.path("projectArn").asText());
        out.put("creationTime", stored.path("creationTime").asDouble());
        JsonNode config = stored.path("stages").path(stage);
        out.put("lastModifiedTime", config.path("lastModifiedTime").asDouble());
        out.put("projectName", stored.path("projectName").asText());
        out.put("projectStage", stage);
        out.put("projectType", stored.path("projectType").asText());
        copy(config, out, "projectDescription", "standardOutputConfiguration", "customOutputConfiguration",
                "overrideConfiguration", "dataAutomationLibraryConfiguration");
        out.put("status", stored.path("status").asText());
        copy(stored, out, "kmsKeyId", "kmsEncryptionContext");
        return out;
    }

    /** A project's configuration at a stage, for processing. */
    synchronized Optional<JsonNode> projectConfig(String arn, String stage, String region) {
        return projects.get(key(region, arn)).map(p -> p.path("stages").path(stage == null ? STAGE_LIVE : stage))
                .filter(JsonNode::isObject);
    }

    // ── Libraries ──────────────────────────────────────────────────────────

    public synchronized ObjectNode createLibrary(JsonNode in, String region) {
        check("CreateDataAutomationLibrary", in);
        String name = in.path("libraryName").asText();
        if (all(libraries, region).stream().anyMatch(l -> name.equals(l.path("libraryName").asText()))) {
            throw new AwsException("ConflictException", "A library named " + name + " already exists.", 409);
        }
        checkEncryption(in.path("encryptionConfiguration"), region);
        String id = UUID.randomUUID().toString();
        ObjectNode library = mapper.createObjectNode();
        library.put("libraryArn", arn(region, "data-automation-library/" + id));
        library.put("libraryName", name);
        copy(in, library, "libraryDescription");
        library.put("status", "ACTIVE");
        library.put("creationTime", now());
        encryption(in.path("encryptionConfiguration"), library);
        library.putObject("entities");
        libraries.put(region + "/" + id, library);
        putTags(library.path("libraryArn").asText(), in.path("tags"));
        return mapper.createObjectNode().put("libraryArn", library.path("libraryArn").asText()).put("status",
                "ACTIVE");
    }

    public synchronized ObjectNode getLibrary(String arn, String region) {
        check("GetDataAutomationLibrary", null, "libraryArn", arn);
        ObjectNode library = requireLibrary(arn, region);
        ObjectNode out = mapper.createObjectNode();
        copy(library, out, "libraryArn", "creationTime", "libraryName", "libraryDescription", "status", "kmsKeyId",
                "kmsEncryptionContext");
        ArrayNode types = out.putArray("entityTypes");
        int count = library.path("entities").size();
        types.addObject().put("entityType", "VOCABULARY").put("entityMetadata", "{\"entityCount\":" + count + "}");
        return mapper.createObjectNode().set("library", out);
    }

    public synchronized ObjectNode updateLibrary(String arn, JsonNode in, String region) {
        check("UpdateDataAutomationLibrary", in, "libraryArn", arn);
        ObjectNode library = requireLibrary(arn, region);
        if (in.has("libraryDescription")) {
            library.put("libraryDescription", in.path("libraryDescription").asText());
        }
        libraries.put(key(region, arn), library);
        return mapper.createObjectNode().put("libraryArn", arn).put("status", "ACTIVE");
    }

    public synchronized ObjectNode deleteLibrary(String arn, String region) {
        check("DeleteDataAutomationLibrary", null, "libraryArn", arn);
        requireLibrary(arn, region);
        boolean used = all(projects, region).stream().anyMatch(p -> {
            for (JsonNode stage : p.path("stages")) {
                if (uses(stage.path("dataAutomationLibraryConfiguration").path("libraries"), "libraryArn", arn)) {
                    return true;
                }
            }
            return false;
        });
        if (used) {
            throw new AwsException("ConflictException", "The library " + arn + " is used by a project; remove it "
                    + "from the project first.", 409);
        }
        libraries.delete(key(region, arn));
        ingestionJobs.keys().stream().filter(k -> k.startsWith(key(region, arn) + "/")).toList()
                .forEach(ingestionJobs::delete);
        tags.delete(arn);
        return mapper.createObjectNode().put("libraryArn", arn).put("status", "DELETING");
    }

    public synchronized ObjectNode listLibraries(JsonNode in, String region) {
        check("ListDataAutomationLibraries", in);
        List<String> used = null;
        if (in.path("projectFilter").isObject()) {
            ObjectNode project = requireProject(in.path("projectFilter").path("projectArn").asText(), region);
            JsonNode stage = project.path("stages").path(in.path("projectFilter").path("projectStage")
                    .asText(STAGE_LIVE));
            used = new ArrayList<>();
            for (JsonNode item : stage.path("dataAutomationLibraryConfiguration").path("libraries")) {
                used.add(item.path("libraryArn").asText());
            }
        }
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode library : all(libraries, region)) {
            if (used != null && !used.contains(library.path("libraryArn").asText())) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            copy(library, summary, "libraryArn", "libraryName", "creationTime");
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("libraries");
        page(summaries, in, out).forEach(list::add);
        return out;
    }

    public synchronized ObjectNode getEntity(String arn, String type, String entityId, String region) {
        check("GetDataAutomationLibraryEntity", null, "libraryArn", arn, "entityType", type, "entityId", entityId);
        ObjectNode library = requireLibrary(arn, region);
        JsonNode entity = library.path("entities").path(entityId);
        if (!entity.isObject()) {
            throw notFound("The library has no " + type + " entity " + entityId + ".");
        }
        ObjectNode out = mapper.createObjectNode();
        out.putObject("entity").set("vocabulary", entity.deepCopy());
        return out;
    }

    public synchronized ObjectNode listEntities(String arn, String type, JsonNode in, String region) {
        check("ListDataAutomationLibraryEntities", in, "libraryArn", arn, "entityType", type);
        ObjectNode library = requireLibrary(arn, region);
        List<ObjectNode> summaries = new ArrayList<>();
        library.path("entities").forEach(entity -> {
            ObjectNode vocabulary = mapper.createObjectNode();
            copy(entity, vocabulary, "entityId", "description", "language", "lastModifiedTime");
            vocabulary.put("numOfPhrases", entity.path("phrases").size());
            ObjectNode summary = mapper.createObjectNode();
            summary.set("vocabulary", vocabulary);
            summaries.add(summary);
        });
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("entities");
        page(summaries, in, out).forEach(list::add);
        return out;
    }

    /**
     * Upserts or deletes vocabulary entities, from the request (an inline payload) or from a JSON or JSON Lines
     * object in S3, in the background; the job's report goes to {@code <output s3Uri>/<job id>/report.json}.
     */
    public synchronized ObjectNode startIngestion(String arn, JsonNode in, String region) {
        check("InvokeDataAutomationLibraryIngestionJob", in, "libraryArn", arn);
        requireLibrary(arn, region);
        JsonNode input = in.path("inputConfiguration");
        if (input.has("s3Object") == input.has("inlinePayload")) {
            throw validation("inputConfiguration needs exactly one of s3Object and inlinePayload.");
        }
        if (in.hasNonNull("clientToken")) {
            Optional<ObjectNode> repeated = ingestionJobs.scan(k -> k.startsWith(key(region, arn) + "/")).stream()
                    .filter(j -> in.path("clientToken").asText().equals(j.path("clientToken").asText())).findFirst();
            if (repeated.isPresent()) {
                return mapper.createObjectNode().put("jobArn", repeated.get().path("jobArn").asText());
            }
        }
        String id = UUID.randomUUID().toString();
        ObjectNode job = mapper.createObjectNode();
        job.put("jobArn", arn(region, "data-automation-library-ingestion-job/" + id));
        job.put("creationTime", now());
        job.put("entityType", in.path("entityType").asText());
        job.put("operationType", in.path("operationType").asText());
        job.put("jobStatus", "IN_PROGRESS");
        job.set("outputConfiguration", in.path("outputConfiguration").deepCopy());
        job.set("inputConfiguration", input.deepCopy());
        if (in.hasNonNull("clientToken")) {
            job.put("clientToken", in.path("clientToken").asText());
        }
        ingestionJobs.put(key(region, arn) + "/" + id, job);
        putTags(job.path("jobArn").asText(), in.path("tags"));
        String account = regionResolver.getAccountId();
        worker.schedule(() -> RequestScopes.runAs(account, region, () -> runIngestion(arn, id, region)),
                ASYNC_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        return mapper.createObjectNode().put("jobArn", job.path("jobArn").asText());
    }

    private void runIngestion(String libraryArn, String id, String region) {
        ObjectNode job;
        synchronized (this) {
            job = ingestionJobs.get(key(region, libraryArn) + "/" + id).orElse(null);
            if (job == null) {
                return;
            }
        }
        List<JsonNode> upserts = new ArrayList<>();
        List<String> deletes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            JsonNode input = job.path("inputConfiguration");
            JsonNode payload = input.has("inlinePayload") ? input.path("inlinePayload")
                    : readPayload(input.path("s3Object").path("s3Uri").asText());
            payload.path("upsertEntitiesInfo").forEach(u -> upserts.add(u.path("vocabulary")));
            payload.path("deleteEntitiesInfo").path("entityIds").forEach(e -> deletes.add(e.asText()));
        } catch (AwsException e) {
            finishIngestion(libraryArn, id, region, "FAILED", e.getMessage(), List.of());
            return;
        }
        List<String> done = new ArrayList<>();
        synchronized (this) {
            ObjectNode library = libraries.get(key(region, libraryArn)).orElse(null);
            if (library == null) {
                finishIngestion(libraryArn, id, region, "FAILED", "The library was deleted.", List.of());
                return;
            }
            ObjectNode entities = library.with("entities");
            boolean upsert = "UPSERT".equals(job.path("operationType").asText());
            if (upsert) {
                for (JsonNode vocabulary : upserts) {
                    if (!vocabulary.path("phrases").isArray() || vocabulary.path("phrases").isEmpty()) {
                        errors.add("A vocabulary entity has no phrases.");
                        continue;
                    }
                    String entityId = vocabulary.path("entityId").asText(UUID.randomUUID().toString());
                    ObjectNode entity = vocabulary.deepCopy();
                    entity.put("entityId", entityId);
                    entity.put("lastModifiedTime", now());
                    entities.set(entityId, entity);
                    done.add(entityId);
                }
            } else {
                for (String entityId : deletes) {
                    if (entities.remove(entityId) == null) {
                        errors.add("The entity " + entityId + " doesn't exist.");
                    } else {
                        done.add(entityId);
                    }
                }
            }
            libraries.put(key(region, libraryArn), library);
        }
        String status = errors.isEmpty() ? "COMPLETED" : done.isEmpty() ? "FAILED" : "COMPLETED_WITH_ERRORS";
        finishIngestion(libraryArn, id, region, status, errors.isEmpty() ? null : String.join(" ", errors), done);
    }

    private JsonNode readPayload(String uri) {
        String[] location = s3Location(uri);
        String text;
        try {
            text = new String(s3.get().getObject(location[0], location[1]).getData(), StandardCharsets.UTF_8);
        } catch (AwsException e) {
            throw validation("The input object " + uri + " can't be read.");
        }
        try {
            JsonNode parsed = mapper.readTree(text);
            if (parsed.isObject()) {
                return parsed;
            }
        } catch (Exception e) {
            // Not one JSON document: read it as JSON Lines, one entity per line.
        }
        ObjectNode payload = mapper.createObjectNode();
        ArrayNode upserts = payload.putArray("upsertEntitiesInfo");
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode entity = mapper.readTree(line);
                upserts.addObject().set("vocabulary", entity.has("vocabulary") ? entity.path("vocabulary") : entity);
            } catch (Exception e) {
                throw validation("The input object " + uri + " isn't JSON or JSON Lines.");
            }
        }
        return payload;
    }

    private void finishIngestion(String libraryArn, String id, String region, String status, String error,
                                 List<String> entityIds) {
        ObjectNode job;
        synchronized (this) {
            job = ingestionJobs.get(key(region, libraryArn) + "/" + id).orElse(null);
            if (job == null) {
                return;
            }
            job.put("jobStatus", status);
            job.put("completionTime", now());
            if (error != null) {
                job.put("errorMessage", error);
                job.put("errorType", "FAILED".equals(status) ? "ClientError" : "PartialFailure");
            }
            ingestionJobs.put(key(region, libraryArn) + "/" + id, job);
        }
        try {
            String[] location = s3Location(job.path("outputConfiguration").path("s3Uri").asText());
            ObjectNode report = mapper.createObjectNode();
            report.put("jobArn", job.path("jobArn").asText());
            report.put("jobStatus", status);
            ArrayNode ids = report.putArray("entityIds");
            entityIds.forEach(ids::add);
            if (error != null) {
                report.put("errorMessage", error);
            }
            s3.get().putObject(location[0], joinKey(location[1], id + "/report.json"),
                    report.toString().getBytes(StandardCharsets.UTF_8), "application/json", Map.of());
        } catch (AwsException e) {
            // The report is best effort: the job's status already says how it went.
        }
    }

    public synchronized ObjectNode getIngestionJob(String libraryArn, String jobArn, String region) {
        check("GetDataAutomationLibraryIngestionJob", null, "libraryArn", libraryArn, "jobArn", jobArn);
        requireLibrary(libraryArn, region);
        ObjectNode job = ingestionJobs.get(key(region, libraryArn) + "/" + jobArn.substring(jobArn.lastIndexOf('/')
                + 1)).orElseThrow(() -> notFound("The ingestion job " + jobArn + " doesn't exist."));
        ObjectNode out = mapper.createObjectNode();
        copy(job, out, "jobArn", "creationTime", "entityType", "operationType", "jobStatus", "outputConfiguration",
                "completionTime", "errorMessage", "errorType");
        return mapper.createObjectNode().set("job", out);
    }

    public synchronized ObjectNode listIngestionJobs(String libraryArn, JsonNode in, String region) {
        check("ListDataAutomationLibraryIngestionJobs", in, "libraryArn", libraryArn);
        requireLibrary(libraryArn, region);
        List<ObjectNode> summaries = new ArrayList<>();
        ingestionJobs.scan(k -> k.startsWith(key(region, libraryArn) + "/")).stream()
                .sorted(Comparator.comparing(j -> -j.path("creationTime").asDouble())).forEach(job -> {
                    ObjectNode summary = mapper.createObjectNode();
                    copy(job, summary, "jobArn", "jobStatus", "entityType", "operationType", "creationTime",
                            "completionTime");
                    summaries.add(summary);
                });
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("jobs");
        page(summaries, in, out).forEach(list::add);
        return out;
    }

    // ── Blueprint optimization ─────────────────────────────────────────────

    /**
     * Optimizes a blueprint from samples: each sample is an asset and its ground truth (a JSON object of the
     * field values the asset should give). For each field whose value the asset's text shows, the instruction is
     * rewritten to name the label the value follows. The optimized schema replaces the blueprint stage's, and is
     * written to {@code <output s3Uri>/<invocation id>/optimized_blueprint.json} with the per-field accuracy.
     */
    public synchronized ObjectNode startOptimization(JsonNode in, String region) {
        check("InvokeBlueprintOptimizationAsync", in);
        if (!in.path("samples").isArray() || in.path("samples").isEmpty()) {
            throw validation("samples must name at least one sample asset with its ground truth.");
        }
        checkProfile(in.path("dataAutomationProfileArn").asText(), region);
        String blueprintArn = in.path("blueprint").path("blueprintArn").asText();
        String stage = in.path("blueprint").path("stage").asText(STAGE_LIVE);
        requireBlueprint(blueprintArn, region);
        if (candidate(blueprintArn, null, stage, region).isEmpty()) {
            throw notFound("The blueprint " + blueprintArn + " has no " + stage + " stage.");
        }
        String id = UUID.randomUUID().toString();
        ObjectNode invocation = mapper.createObjectNode();
        invocation.put("invocationArn", arn(region, "blueprint-optimization-invocation/" + id));
        invocation.put("status", "Created");
        invocation.put("blueprintArn", blueprintArn);
        invocation.put("stage", stage);
        invocation.set("samples", in.path("samples").deepCopy());
        invocation.set("outputConfiguration", in.path("outputConfiguration").deepCopy());
        optimizations.put(region + "/" + id, invocation);
        putTags(invocation.path("invocationArn").asText(), in.path("tags"));
        String account = regionResolver.getAccountId();
        worker.schedule(() -> RequestScopes.runAs(account, region, () -> runOptimization(id, region)),
                ASYNC_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        return mapper.createObjectNode().put("invocationArn", invocation.path("invocationArn").asText());
    }

    private void runOptimization(String id, String region) {
        ObjectNode invocation;
        BdaProcessing.Candidate candidate;
        synchronized (this) {
            invocation = optimizations.get(region + "/" + id).orElse(null);
            if (invocation == null) {
                return;
            }
            invocation.put("status", "InProgress");
            optimizations.put(region + "/" + id, invocation);
            candidate = candidate(invocation.path("blueprintArn").asText(), null, invocation.path("stage").asText(),
                    region).orElse(null);
        }
        try {
            if (candidate == null) {
                throw validation("The blueprint was deleted.");
            }
            ObjectNode schema = (ObjectNode) candidate.schema().deepCopy();
            ObjectNode accuracy = mapper.createObjectNode();
            for (JsonNode sample : invocation.path("samples")) {
                String[] asset = s3Location(sample.path("assetS3Object").path("s3Uri").asText());
                String[] truthLocation = s3Location(sample.path("groundTruthS3Object").path("s3Uri").asText());
                byte[] bytes = read(asset);
                JsonNode truth = mapper.readTree(read(truthLocation));
                String text = String.join("\n\n", BdaDocuments.pages(bytes));
                ObjectNode before = processing.infer(schema, text);
                schema.path("properties").fields().forEachRemaining(field -> {
                    String expected = truth.path(field.getKey()).asText(null);
                    if (expected == null || field.getValue().has("$ref")) {
                        return;
                    }
                    String label = labelBefore(text, expected);
                    if (label != null) {
                        ((ObjectNode) field.getValue()).put("instruction", "The value labelled \"" + label
                                + "\" in the document.");
                    }
                    String got = before.path(field.getKey()).asText(null);
                    accuracy.put(field.getKey(), expected.equals(got) ? 1.0 : 0.0);
                });
            }
            synchronized (this) {
                blueprints.get(key(region, invocation.path("blueprintArn").asText())).ifPresent(blueprint -> {
                    ObjectNode stage = blueprint.with("stages").with(invocation.path("stage").asText());
                    stage.put("schema", schema.toString());
                    stage.set("optimizationSamples", invocation.path("samples").deepCopy());
                    stage.put("optimizationTime", now());
                    stage.put("lastModifiedTime", now());
                    blueprint.put("lastModifiedTime", now());
                    save(blueprint, region);
                });
            }
            String[] output = s3Location(invocation.path("outputConfiguration").path("s3Object").path("s3Uri")
                    .asText());
            ObjectNode result = mapper.createObjectNode();
            result.put("blueprintArn", invocation.path("blueprintArn").asText());
            result.put("optimizedSchema", schema.toString());
            result.set("fieldAccuracyBeforeOptimization", accuracy);
            String outputKey = joinKey(output[1], id + "/optimized_blueprint.json");
            s3.get().putObject(output[0], outputKey, result.toString().getBytes(StandardCharsets.UTF_8),
                    "application/json", Map.of());
            synchronized (this) {
                invocation.put("status", "Success");
                invocation.putObject("result").put("s3Uri", "s3://" + output[0] + "/" + outputKey);
                optimizations.put(region + "/" + id, invocation);
            }
        } catch (Exception e) {
            synchronized (this) {
                invocation.put("status", "ClientError");
                invocation.put("errorType", "ValidationException");
                invocation.put("errorMessage", e.getMessage());
                optimizations.put(region + "/" + id, invocation);
            }
        }
    }

    /** The label on the line where a value appears ({@code Label: value} or {@code Label value}). */
    private static String labelBefore(String text, String value) {
        for (String line : text.split("\n")) {
            int at = line.indexOf(value);
            if (at <= 0) {
                continue;
            }
            String label = line.substring(0, at).replaceAll("[:=#\\s]+$", "").strip();
            if (!label.isEmpty() && label.length() <= 60) {
                return label;
            }
        }
        return null;
    }

    public synchronized ObjectNode optimizationStatus(String arn, String region) {
        check("GetBlueprintOptimizationStatus", null, "invocationArn", arn);
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        ObjectNode invocation = optimizations.get(region + "/" + id).filter(i -> arn.equals(i.path("invocationArn")
                .asText())).orElseThrow(() -> notFound("The optimization invocation " + arn + " doesn't exist."));
        ObjectNode out = mapper.createObjectNode();
        out.put("status", invocation.path("status").asText());
        copy(invocation, out, "errorType", "errorMessage");
        if (invocation.has("result")) {
            out.putObject("outputConfiguration").putObject("s3Object").put("s3Uri",
                    invocation.path("result").path("s3Uri").asText());
        }
        return out;
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    /** Whether an ARN names a Data Automation resource, whose tags this service keeps. */
    public static boolean owns(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "bedrock")) {
            return false;
        }
        String resource = AwsArnUtils.parse(arn).resource();
        return resource.startsWith("blueprint/") || resource.startsWith("blueprint-optimization-invocation/")
                || resource.startsWith("data-automation-");
    }

    public synchronized List<ObjectNode> listTags(String arn, String region) {
        requireTaggable(arn, region);
        List<ObjectNode> out = new ArrayList<>();
        tags.get(arn).ifPresent(t -> t.fields().forEachRemaining(e -> out.add(mapper.createObjectNode()
                .put("key", e.getKey()).put("value", e.getValue().asText()))));
        return out;
    }

    public synchronized void tag(String arn, JsonNode list, String region) {
        requireTaggable(arn, region);
        ObjectNode current = tags.get(arn).orElse(mapper.createObjectNode());
        list.forEach(t -> current.put(t.path("key").asText(), t.path("value").asText()));
        if (current.size() > MAX_TAGS) {
            throw new AwsException("ServiceQuotaExceededException", "A resource can have at most " + MAX_TAGS
                    + " tags.", 402);
        }
        tags.put(arn, current);
    }

    public synchronized void untag(String arn, JsonNode keys, String region) {
        requireTaggable(arn, region);
        tags.get(arn).ifPresent(current -> {
            keys.forEach(k -> current.remove(k.asText()));
            tags.put(arn, current);
        });
    }

    void putTags(String arn, JsonNode list) {
        if (list == null || !list.isArray() || list.isEmpty()) {
            return;
        }
        ObjectNode current = tags.get(arn).orElse(mapper.createObjectNode());
        list.forEach(t -> current.put(t.path("key").asText(), t.path("value").asText()));
        tags.put(arn, current);
    }

    private BdaRuntime runtime;

    /** The runtime registers itself so tags can check that its invocations exist. */
    void attach(BdaRuntime runtime) {
        this.runtime = runtime;
    }

    private void requireTaggable(String arn, String region) {
        if (!owns(arn)) {
            throw validation("The ARN " + arn + " isn't a Bedrock Data Automation resource.");
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String in = AwsArnUtils.parse(arn).region().isEmpty() ? region : AwsArnUtils.parse(arn).region();
        boolean exists;
        if (resource.startsWith("blueprint/")) {
            exists = blueprints.get(key(in, arn)).isPresent();
        } else if (resource.startsWith("data-automation-project/")) {
            exists = projects.get(key(in, arn)).isPresent();
        } else if (resource.startsWith("data-automation-library/")) {
            exists = libraries.get(key(in, arn)).isPresent();
        } else if (resource.startsWith("data-automation-invocation/")) {
            exists = runtime != null && runtime.exists(arn, in);
        } else if (resource.startsWith("data-automation-library-ingestion-job/")) {
            String jobId = resource.substring(resource.indexOf('/') + 1);
            exists = !ingestionJobs.scan(k -> k.startsWith(in + "/") && k.endsWith("/" + jobId)).isEmpty();
        } else if (resource.startsWith("blueprint-optimization-invocation/")) {
            exists = optimizations.get(key(in, arn)).isPresent();
        } else {
            exists = false;
        }
        if (!exists) {
            throw notFound("The resource " + arn + " doesn't exist.");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * A data automation profile ARN must name a cross-Region profile ({@code <geo>.data-automation-v1}) of the
     * Region's geography: {@code us} for {@code us-*} Regions, {@code eu} for {@code eu-*}, {@code apac} for
     * {@code ap-*}, as AWS checks it.
     */
    void checkProfile(String profileArn, String region) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(profileArn);
        String profile = arn.resource().substring(arn.resource().indexOf('/') + 1);
        String geo = region.startsWith("eu-") ? "eu" : region.startsWith("ap-") ? "apac"
                : region.startsWith("ca-") ? "ca" : "us";
        if (!profile.matches("[a-z]+\\.data-automation-v\\d+")) {
            throw validation("The data automation profile " + profile + " doesn't exist. Use "
                    + geo + ".data-automation-v1.");
        }
        if (!profile.startsWith(geo + ".") && !profile.startsWith("global.")) {
            throw validation("The data automation profile " + profile + " isn't available in " + region + "; use "
                    + geo + ".data-automation-v1.");
        }
    }

    private void checkEncryption(JsonNode configuration, String region) {
        if (configuration.isObject() && kms.isResolvable()
                && kms.get().findKeyArn(configuration.path("kmsKeyId").asText(), region).isEmpty()) {
            throw validation("The KMS key " + configuration.path("kmsKeyId").asText() + " doesn't exist.");
        }
    }

    private static void encryption(JsonNode configuration, ObjectNode target) {
        if (configuration.isObject()) {
            target.put("kmsKeyId", configuration.path("kmsKeyId").asText());
            if (configuration.has("kmsEncryptionContext")) {
                target.set("kmsEncryptionContext", configuration.path("kmsEncryptionContext").deepCopy());
            }
        }
    }

    ObjectNode requireBlueprint(String arn, String region) {
        return blueprints.get(key(region, arn)).orElseThrow(() -> notFound("The blueprint " + arn
                + " doesn't exist."));
    }

    ObjectNode requireProject(String arn, String region) {
        return projects.get(key(region, arn)).orElseThrow(() -> notFound("The project " + arn + " doesn't exist."));
    }

    ObjectNode requireLibrary(String arn, String region) {
        return libraries.get(key(region, arn)).orElseThrow(() -> notFound("The library " + arn + " doesn't exist."));
    }

    private void save(ObjectNode blueprint, String region) {
        blueprints.put(key(region, blueprint.path("blueprintArn").asText()), blueprint);
    }

    /** The store key of a resource ARN: its Region and the id after the resource type. */
    static String key(String region, String arn) {
        String resource;
        try {
            resource = AwsArnUtils.parse(arn).resource();
        } catch (IllegalArgumentException e) {
            return region + "/" + arn;
        }
        int slash = resource.indexOf('/');
        return region + "/" + (slash < 0 ? resource : resource.substring(slash + 1));
    }

    private List<ObjectNode> all(AccountAwareStorageBackend<ObjectNode> store, String region) {
        return store.scan(k -> k.startsWith(region + "/") && k.indexOf('/', region.length() + 1) < 0).stream()
                .sorted(Comparator.comparing(r -> r.path("creationTime").asDouble())).toList();
    }

    private List<ObjectNode> page(List<ObjectNode> all, JsonNode in, ObjectNode out) {
        int max = in.path("maxResults").asInt(100);
        int start = 0;
        if (in.hasNonNull("nextToken")) {
            try {
                start = Integer.parseInt(in.path("nextToken").asText());
            } catch (NumberFormatException e) {
                throw validation("The nextToken isn't valid.");
            }
        }
        if (start < 0 || start > all.size()) {
            throw validation("The nextToken isn't valid.");
        }
        int end = Math.min(all.size(), start + max);
        if (end < all.size()) {
            out.put("nextToken", String.valueOf(end));
        }
        return all.subList(start, end);
    }

    byte[] read(String[] location) {
        try {
            return s3.get().getObject(location[0], location[1]).getData();
        } catch (AwsException e) {
            throw validation("The S3 object s3://" + location[0] + "/" + location[1] + " can't be read.");
        }
    }

    static String[] s3Location(String uri) {
        if (uri == null || !uri.startsWith("s3://")) {
            throw validation("The S3 URI " + uri + " isn't valid.");
        }
        String rest = uri.substring(5);
        int slash = rest.indexOf('/');
        return slash < 0 ? new String[] {rest, ""} : new String[] {rest.substring(0, slash), rest.substring(slash + 1)};
    }

    static String joinKey(String prefix, String name) {
        if (prefix == null || prefix.isEmpty()) {
            return name;
        }
        return prefix.endsWith("/") ? prefix + name : prefix + "/" + name;
    }

    private JsonNode parse(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }

    String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("bedrock", region, regionResolver.getAccountId(), resource).toString();
    }

    private byte[] bytes(int n) {
        byte[] out = new byte[n];
        random.nextBytes(out);
        return out;
    }

    static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    @Override
    public void clear() {
        blueprints.clear();
        projects.clear();
        libraries.clear();
        ingestionJobs.clear();
        optimizations.clear();
        tags.clear();
    }
}
