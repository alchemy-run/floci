package io.github.hectorvent.floci.services.bedrockdataautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.Blueprint;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.DataAutomationLibrary;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.InvocationRecord;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.IngestionJobRecord;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.LibraryEntityRecord;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.ProjectRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Amazon Bedrock Data Automation restJson1 — project, blueprint and library lifecycle, plus tags.
 *
 * <p>Tag operations use the service-specific {@code /listTagsForResource},
 * {@code /tagResource} and {@code /untagResource} paths (not {@code /tags/{arn}}),
 * because resource ARNs share the {@code bedrock} service segment with Agents.
 */
@ApplicationScoped
public class BedrockDataAutomationService {

    static final String SERVICE = "bedrock-data-automation";
    static final String DEFAULT_STAGE = "LIVE";
    private static final Pattern LIBRARY_NAME_PATTERN = Pattern.compile("[0-9A-Za-z._-]{1,128}");
    private static final Pattern BLUEPRINT_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9-_]{1,128}");
    private static final Pattern TYPE_PATTERN = Pattern.compile("DOCUMENT|IMAGE|AUDIO|VIDEO");
    private static final Pattern PROJECT_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9-_]{1,128}");
    private static final String LIBRARY_PREFIX = "data-automation-library/";
    private static final String BLUEPRINT_PREFIX = "blueprint/";
    private static final String PROJECT_PREFIX = "data-automation-project/";

    private final StorageBackend<String, DataAutomationLibrary> libraries;
    private final StorageBackend<String, Blueprint> blueprints;
    private final StorageBackend<String, ProjectRecord> projects;
    private final Map<String, InvocationRecord> invocations = new ConcurrentHashMap<>();
    private final Map<String, IngestionJobRecord> jobs = new ConcurrentHashMap<>();
    private final Map<String, LibraryEntityRecord> entities = new ConcurrentHashMap<>();
    private final RegionResolver regionResolver;

    @Inject
    public BedrockDataAutomationService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create("bedrock-data-automation", "bedrock-data-automation-libraries.json",
                        new TypeReference<Map<String, DataAutomationLibrary>>() {
                        }),
                storageFactory.create("bedrock-data-automation", "bedrock-data-automation-blueprints.json",
                        new TypeReference<Map<String, Blueprint>>() {
                        }),
                storageFactory.create("bedrock-data-automation", "bedrock-data-automation-projects.json",
                        new TypeReference<Map<String, ProjectRecord>>() {
                        }),
                regionResolver);
    }

    BedrockDataAutomationService(
            StorageBackend<String, DataAutomationLibrary> libraries, RegionResolver regionResolver) {
        this(libraries, null, null, regionResolver);
    }

    BedrockDataAutomationService(
            StorageBackend<String, DataAutomationLibrary> libraries,
            StorageBackend<String, Blueprint> blueprints,
            RegionResolver regionResolver) {
        this(libraries, blueprints, null, regionResolver);
    }

    BedrockDataAutomationService(
            StorageBackend<String, DataAutomationLibrary> libraries,
            StorageBackend<String, Blueprint> blueprints,
            StorageBackend<String, ProjectRecord> projects,
            RegionResolver regionResolver) {
        this.libraries = libraries;
        this.blueprints = blueprints;
        this.projects = projects;
        this.regionResolver = regionResolver;
    }

    public synchronized DataAutomationLibrary createLibrary(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "libraryName");
        validateLibraryName(name);
        if (findLibraryByName(region, name) != null) {
            throw new AwsException(
                    "ConflictException",
                    "Library " + name + " already exists.",
                    409);
        }

        String account = regionResolver.getAccountId();
        String arn = libraryArn(region, account, name);
        DataAutomationLibrary library = new DataAutomationLibrary();
        library.setLibraryArn(arn);
        library.setLibraryName(name);
        library.setLibraryDescription(optionalText(request, "libraryDescription"));
        library.setStatus("ACTIVE");
        library.setCreationTime(nowIso());
        library.setRegion(region);
        applyLibraryEncryption(library, request.get("encryptionConfiguration"));
        library.setTags(readTagList(request.get("tags")));
        libraries.put(libraryStorageKey(region, name), library);
        return library;
    }

    public DataAutomationLibrary getLibrary(String libraryArn) {
        return requireLibrary(libraryArn);
    }

    public synchronized DataAutomationLibrary updateLibrary(String libraryArn, JsonNode request) {
        requireObject(request, "Request body");
        DataAutomationLibrary library = requireLibrary(libraryArn);
        if (request.has("libraryDescription")) {
            JsonNode description = request.get("libraryDescription");
            if (description == null || description.isNull()) {
                library.setLibraryDescription(null);
            } else if (description.isTextual()) {
                library.setLibraryDescription(description.asText());
            }
        }
        libraries.put(libraryStorageKey(library.getRegion(), library.getLibraryName()), library);
        return library;
    }

    public synchronized DataAutomationLibrary deleteLibrary(String libraryArn) {
        DataAutomationLibrary library = requireLibrary(libraryArn);
        libraries.delete(libraryStorageKey(library.getRegion(), library.getLibraryName()));
        library.setStatus("DELETING");
        return library;
    }

    public List<DataAutomationLibrary> listLibraries(String region) {
        String prefix = region + "::";
        List<DataAutomationLibrary> result = new ArrayList<>(libraries.scan(key -> key.startsWith(prefix)));
        result.sort(Comparator.comparing(DataAutomationLibrary::getLibraryName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public synchronized ProjectRecord createProject(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireProjects();
        String name = requireText(request, "projectName");
        validateProjectName(name);
        String stage = optionalText(request, "projectStage");
        if (stage == null) {
            stage = DEFAULT_STAGE;
        }
        if (findProjectByName(region, name) != null) {
            throw new AwsException(
                    "ConflictException",
                    "Project " + name + " already exists.",
                    409);
        }
        JsonNode standard = request.get("standardOutputConfiguration");
        if (standard == null || standard.isNull()) {
            throw validation("standardOutputConfiguration is required.");
        }

        String now = nowIso();
        ProjectRecord project = new ProjectRecord();
        project.setProjectArn(projectArn(region, name));
        project.setProjectName(name);
        project.setProjectDescription(optionalText(request, "projectDescription"));
        project.setProjectStage(stage);
        String type = optionalText(request, "projectType");
        project.setProjectType(type == null ? "ASYNC" : type);
        project.setStatus("COMPLETED");
        project.setCreationTime(now);
        project.setLastModifiedTime(now);
        project.setRegion(region);
        project.setStandardOutputConfiguration(standard.deepCopy());
        project.setCustomOutputConfiguration(copyObject(request.get("customOutputConfiguration")));
        project.setOverrideConfiguration(copyObject(request.get("overrideConfiguration")));
        project.setDataAutomationLibraryConfiguration(copyObject(request.get("dataAutomationLibraryConfiguration")));
        applyProjectEncryption(project, request.get("encryptionConfiguration"));
        project.setTags(readTagList(request.get("tags")));
        projects.put(projectStorageKey(region, name), project);
        return project;
    }

    public ProjectRecord getProject(String projectArn, JsonNode request) {
        requireProjects();
        ProjectRecord project = requireProject(projectArn);
        String stage = request == null ? null : optionalText(request, "projectStage");
        if (stage != null && !stage.equals(project.getProjectStage())) {
            throw projectNotFound(decodeArn(projectArn));
        }
        return project;
    }

    public synchronized ProjectRecord updateProject(String projectArn, JsonNode request) {
        requireObject(request, "Request body");
        requireProjects();
        ProjectRecord project = requireProject(projectArn);
        String stage = optionalText(request, "projectStage");
        if (stage != null && !stage.equals(project.getProjectStage())) {
            throw projectNotFound(decodeArn(projectArn));
        }
        JsonNode standard = request.get("standardOutputConfiguration");
        if (standard == null || standard.isNull()) {
            throw validation("standardOutputConfiguration is required.");
        }
        project.setStandardOutputConfiguration(standard.deepCopy());
        if (request.has("projectDescription")) {
            JsonNode description = request.get("projectDescription");
            if (description == null || description.isNull() || !description.isTextual()) {
                project.setProjectDescription(null);
            } else {
                project.setProjectDescription(description.asText());
            }
        }
        if (request.has("customOutputConfiguration")) {
            project.setCustomOutputConfiguration(copyObject(request.get("customOutputConfiguration")));
        }
        if (request.has("overrideConfiguration")) {
            project.setOverrideConfiguration(copyObject(request.get("overrideConfiguration")));
        }
        if (request.has("dataAutomationLibraryConfiguration")) {
            project.setDataAutomationLibraryConfiguration(
                    copyObject(request.get("dataAutomationLibraryConfiguration")));
        }
        if (request.has("encryptionConfiguration")) {
            applyProjectEncryption(project, request.get("encryptionConfiguration"));
        }
        project.setLastModifiedTime(nowIso());
        projects.put(projectStorageKey(project.getRegion(), project.getProjectName()), project);
        return project;
    }

    public synchronized ProjectRecord deleteProject(String projectArn) {
        requireProjects();
        ProjectRecord project = requireProject(projectArn);
        projects.delete(projectStorageKey(project.getRegion(), project.getProjectName()));
        project.setStatus("DELETING");
        return project;
    }

    public List<ProjectRecord> listProjects(String region) {
        requireProjects();
        String prefix = region + "::";
        List<ProjectRecord> result = new ArrayList<>(projects.scan(key -> key.startsWith(prefix)));
        result.sort(Comparator.comparing(ProjectRecord::getProjectName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public synchronized Blueprint createBlueprint(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireBlueprints();
        String name = requireText(request, "blueprintName");
        validateBlueprintName(name);
        String type = requireText(request, "type");
        validateType(type);
        String schema = requireText(request, "schema");
        String stage = optionalText(request, "blueprintStage");
        if (stage == null) {
            stage = DEFAULT_STAGE;
        }
        if (findBlueprintByName(region, name, stage) != null) {
            throw new AwsException("ConflictException",
                    "Blueprint " + name + " already exists.", 409);
        }

        String now = nowIso();
        Blueprint blueprint = new Blueprint();
        blueprint.setBlueprintArn(blueprintArn(region, name));
        blueprint.setBlueprintName(name);
        blueprint.setType(type);
        blueprint.setSchema(schema);
        blueprint.setBlueprintStage(stage);
        blueprint.setCreationTime(now);
        blueprint.setLastModifiedTime(now);
        blueprint.setRegion(region);
        blueprint.setClientToken(optionalText(request, "clientToken"));
        applyBlueprintEncryption(blueprint, request.get("encryptionConfiguration"));
        blueprint.setTags(readTagList(request.get("tags")));
        blueprints.put(blueprintStorageKey(region, stage, name, null), blueprint);
        return blueprint;
    }

    public Blueprint getBlueprint(String region, String blueprintArn, JsonNode request) {
        requireBlueprints();
        String stage = request == null ? null : optionalText(request, "blueprintStage");
        String version = request == null ? null : optionalText(request, "blueprintVersion");
        return requireBlueprint(region, blueprintArn, stage, version);
    }

    public synchronized Blueprint updateBlueprint(String region, String blueprintArn, JsonNode request) {
        requireObject(request, "Request body");
        requireBlueprints();
        String stage = optionalText(request, "blueprintStage");
        Blueprint blueprint = requireBlueprint(region, blueprintArn, stage, null);
        blueprint.setSchema(requireText(request, "schema"));
        if (request.has("encryptionConfiguration")) {
            applyBlueprintEncryption(blueprint, request.get("encryptionConfiguration"));
        }
        if (stage != null) {
            blueprint.setBlueprintStage(stage);
        }
        blueprint.setLastModifiedTime(nowIso());
        blueprints.put(blueprintStorageKey(blueprint.getRegion(), blueprint.getBlueprintStage(),
                blueprint.getBlueprintName(), blueprint.getBlueprintVersion()), blueprint);
        return blueprint;
    }

    public synchronized void deleteBlueprint(String region, String blueprintArn, String blueprintVersion) {
        requireBlueprints();
        String arn = decodeArn(blueprintArn);
        List<Blueprint> matches = listBlueprintsMatching(region, arn);
        if (matches.isEmpty()) {
            throw blueprintNotFound(arn);
        }
        if (blueprintVersion != null && !blueprintVersion.isBlank()) {
            Blueprint found = null;
            for (Blueprint blueprint : matches) {
                if (blueprintVersion.equals(blueprint.getBlueprintVersion())) {
                    found = blueprint;
                    break;
                }
            }
            if (found == null) {
                throw blueprintNotFound(arn);
            }
            blueprints.delete(blueprintStorageKey(found.getRegion(), found.getBlueprintStage(),
                    found.getBlueprintName(), found.getBlueprintVersion()));
            return;
        }
        for (Blueprint blueprint : matches) {
            blueprints.delete(blueprintStorageKey(blueprint.getRegion(), blueprint.getBlueprintStage(),
                    blueprint.getBlueprintName(), blueprint.getBlueprintVersion()));
        }
    }

    public List<Blueprint> listBlueprints(String region, JsonNode request) {
        requireBlueprints();
        String filterArn = request == null ? null : optionalText(request, "blueprintArn");
        if (filterArn != null) {
            filterArn = decodeArn(filterArn);
        }
        String stageFilter = request == null ? null : optionalText(request, "blueprintStageFilter");
        List<Blueprint> result = new ArrayList<>();
        for (Blueprint blueprint : blueprints.scan(k -> true)) {
            if (blueprint.getRegion() != null && !region.equals(blueprint.getRegion())) {
                continue;
            }
            if (filterArn != null && !filterArn.equals(blueprint.getBlueprintArn())) {
                continue;
            }
            if (stageFilter != null && !stageFilter.equals(blueprint.getBlueprintStage())) {
                continue;
            }
            result.add(blueprint);
        }
        result.sort(Comparator.comparing(Blueprint::getBlueprintName,
                Comparator.nullsLast(String::compareTo)));
        return result;
    }

    public InvocationRecord invokeAsync(String region, JsonNode request) {
        requireObject(request, "Request body");
        String id = UUID.randomUUID().toString().replace("-", "");
        String arn = "arn:aws:bedrock:" + region + ":" + regionResolver.getAccountId()
                + ":data-automation-invocation/" + id;
        InvocationRecord invocation = new InvocationRecord();
        invocation.setInvocationArn(arn);
        invocation.setKind("DATA_AUTOMATION");
        invocation.setStatus("Success");
        String now = nowIso();
        invocation.setJobSubmissionTime(now);
        invocation.setJobCompletionTime(now);
        JsonNode output = request.path("outputConfiguration").path("s3Uri");
        if (output.isTextual()) {
            invocation.setOutputS3Uri(output.asText());
        }
        invocations.put(arn, invocation);
        return invocation;
    }

    public InvocationRecord getInvocation(String invocationArn) {
        String arn = decodeArn(invocationArn);
        InvocationRecord invocation = invocations.get(arn);
        if (invocation == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Invocation not found: " + arn, 404);
        }
        return invocation;
    }

    public void validateSyncInvoke(JsonNode request) {
        requireObject(request, "Request body");
        JsonNode input = request.path("inputConfiguration");
        boolean hasBytes = input.hasNonNull("bytes") && !input.get("bytes").asText("").isBlank();
        boolean hasS3 = input.hasNonNull("s3Uri") && !input.get("s3Uri").asText("").isBlank();
        if (!hasBytes && !hasS3) {
            throw validation("inputConfiguration must include bytes or s3Uri.");
        }
        if (optionalText(request, "dataAutomationProfileArn") == null) {
            throw validation("dataAutomationProfileArn is required.");
        }
    }

    public synchronized IngestionJobRecord invokeIngestion(String region, String libraryArn, JsonNode request) {
        DataAutomationLibrary library = requireLibrary(libraryArn);
        requireObject(request, "Request body");
        String entityType = optionalText(request, "entityType");
        if (entityType == null) {
            entityType = "VOCABULARY";
        }
        String operation = optionalText(request, "operationType");
        if (operation == null) {
            operation = "UPSERT";
        }
        String s3Uri = request.path("outputConfiguration").path("s3Uri").asText(null);
        if (s3Uri == null || s3Uri.isBlank()) {
            throw validation("outputConfiguration.s3Uri is required.");
        }
        String now = nowIso();
        IngestionJobRecord job = new IngestionJobRecord();
        job.setJobArn("arn:aws:bedrock:" + region + ":" + regionResolver.getAccountId()
                + ":" + LIBRARY_PREFIX + library.getLibraryName() + "/library-ingestion-job/" + UUID.randomUUID());
        job.setLibraryArn(library.getLibraryArn());
        job.setEntityType(entityType);
        job.setOperationType(operation);
        job.setJobStatus("COMPLETED");
        job.setS3Uri(s3Uri);
        job.setCreationTime(now);
        job.setCompletionTime(now);
        JsonNode inline = request.path("inputConfiguration").path("inlinePayload");
        if ("UPSERT".equals(operation) && inline.has("upsertEntitiesInfo")
                && inline.get("upsertEntitiesInfo").isArray()) {
            for (JsonNode item : inline.get("upsertEntitiesInfo")) {
                JsonNode vocabulary = item.get("vocabulary");
                if (vocabulary == null || !vocabulary.isObject()) {
                    continue;
                }
                String entityId = vocabulary.path("entityId").asText(UUID.randomUUID().toString());
                LibraryEntityRecord entity = new LibraryEntityRecord();
                entity.setLibraryArn(library.getLibraryArn());
                entity.setEntityType(entityType);
                entity.setEntityId(entityId);
                entity.setVocabulary(vocabulary.deepCopy());
                entity.setLastModifiedTime(now);
                entities.put(library.getLibraryArn() + "::" + entityType + "::" + entityId, entity);
            }
        }
        jobs.put(job.getJobArn(), job);
        return job;
    }

    public IngestionJobRecord getIngestionJob(String jobArn) {
        IngestionJobRecord job = jobs.get(decodeArn(jobArn));
        if (job == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Library ingestion job " + jobArn + " could not be found.", 404);
        }
        return job;
    }

    public List<IngestionJobRecord> listIngestionJobs(String libraryArn) {
        DataAutomationLibrary library = requireLibrary(libraryArn);
        List<IngestionJobRecord> result = new ArrayList<>();
        for (IngestionJobRecord job : jobs.values()) {
            if (library.getLibraryArn().equals(job.getLibraryArn())) {
                result.add(job);
            }
        }
        return result;
    }

    public LibraryEntityRecord getEntity(String libraryArn, String entityType, String entityId) {
        DataAutomationLibrary library = requireLibrary(libraryArn);
        LibraryEntityRecord entity = entities.get(library.getLibraryArn() + "::" + entityType + "::" + entityId);
        if (entity == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Entity " + entityId + " could not be found.", 404);
        }
        return entity;
    }

    public List<LibraryEntityRecord> listEntities(String libraryArn, String entityType) {
        DataAutomationLibrary library = requireLibrary(libraryArn);
        List<LibraryEntityRecord> result = new ArrayList<>();
        for (LibraryEntityRecord entity : entities.values()) {
            if (library.getLibraryArn().equals(entity.getLibraryArn())
                    && entityType.equals(entity.getEntityType())) {
                result.add(entity);
            }
        }
        return result;
    }

    public synchronized Blueprint createBlueprintVersion(String region, String blueprintArn, JsonNode request) {
        requireBlueprints();
        String stage = request == null ? DEFAULT_STAGE : optionalText(request, "blueprintStage");
        Blueprint live = requireBlueprint(region, blueprintArn, stage, null);
        int next = 1;
        for (Blueprint existing : listBlueprintsMatching(region, live.getBlueprintArn())) {
            if (existing.getBlueprintVersion() != null) {
                try {
                    next = Math.max(next, Integer.parseInt(existing.getBlueprintVersion()) + 1);
                } catch (NumberFormatException ignored) {
                    next = Math.max(next, 1);
                }
            }
        }
        String version = String.valueOf(next);
        Blueprint snapshot = new Blueprint();
        snapshot.setBlueprintArn(live.getBlueprintArn());
        snapshot.setBlueprintName(live.getBlueprintName());
        snapshot.setType(live.getType());
        snapshot.setSchema(live.getSchema());
        snapshot.setBlueprintStage(live.getBlueprintStage());
        snapshot.setBlueprintVersion(version);
        snapshot.setCreationTime(nowIso());
        snapshot.setLastModifiedTime(snapshot.getCreationTime());
        snapshot.setKmsKeyId(live.getKmsKeyId());
        snapshot.setKmsEncryptionContext(live.getKmsEncryptionContext());
        snapshot.setTags(new LinkedHashMap<>(live.getTags()));
        snapshot.setRegion(live.getRegion());
        snapshot.setClientToken(live.getClientToken());
        blueprints.put(blueprintStorageKey(snapshot.getRegion(), snapshot.getBlueprintStage(),
                snapshot.getBlueprintName(), version), snapshot);
        return snapshot;
    }

    public synchronized void copyBlueprintStage(String region, String blueprintArn, JsonNode request) {
        requireObject(request, "Request body");
        String sourceStage = requireText(request, "sourceStage");
        requireBlueprint(region, blueprintArn, sourceStage, null);
    }

    public synchronized InvocationRecord invokeOptimization(String region, JsonNode request) {
        requireObject(request, "Request body");
        JsonNode samples = request.get("samples");
        if (samples == null || !samples.isArray() || samples.isEmpty()) {
            throw validation("samples must contain at least one labeled pair.");
        }
        String now = nowIso();
        InvocationRecord invocation = new InvocationRecord();
        invocation.setInvocationArn("arn:aws:bedrock:" + region + ":" + regionResolver.getAccountId()
                + ":blueprint-optimization-invocation/" + UUID.randomUUID());
        invocation.setKind("blueprint-optimization");
        invocation.setStatus("Success");
        invocation.setOutputS3Uri(request.path("outputConfiguration").path("s3Object").path("s3Uri").asText(null));
        invocation.setJobSubmissionTime(now);
        invocation.setJobCompletionTime(now);
        invocations.put(invocation.getInvocationArn(), invocation);
        return invocation;
    }

    public InvocationRecord getOptimization(String invocationArn) {
        InvocationRecord invocation = invocations.get(decodeArn(invocationArn));
        if (invocation == null || !"blueprint-optimization".equals(invocation.getKind())) {
            throw new AwsException("ResourceNotFoundException",
                    "Blueprint optimization invocation " + invocationArn + " could not be found.", 404);
        }
        return invocation;
    }

    public Map<String, String> listTags(String resourceArn) {
        if (isProjectArn(resourceArn)) {
            return new LinkedHashMap<>(requireProject(resourceArn).getTags());
        }
        if (isBlueprintArn(resourceArn)) {
            return new LinkedHashMap<>(requireAnyBlueprint(resourceArn).getTags());
        }
        return new LinkedHashMap<>(requireLibrary(resourceArn).getTags());
    }

    public synchronized void tagResource(String resourceArn, JsonNode request) {
        requireObject(request, "Request body");
        Map<String, String> incoming = readTagList(request.get("tags"));
        if (isProjectArn(resourceArn)) {
            ProjectRecord project = requireProject(resourceArn);
            Map<String, String> tags = project.getTags();
            tags.putAll(incoming);
            project.setTags(tags);
            projects.put(projectStorageKey(project.getRegion(), project.getProjectName()), project);
            return;
        }
        if (isBlueprintArn(resourceArn)) {
            requireBlueprints();
            Blueprint blueprint = requireAnyBlueprint(resourceArn);
            Map<String, String> tags = blueprint.getTags();
            tags.putAll(incoming);
            blueprint.setTags(tags);
            blueprints.put(blueprintStorageKey(blueprint.getRegion(), blueprint.getBlueprintStage(),
                    blueprint.getBlueprintName(), blueprint.getBlueprintVersion()), blueprint);
            return;
        }
        DataAutomationLibrary library = requireLibrary(resourceArn);
        Map<String, String> tags = library.getTags();
        tags.putAll(incoming);
        library.setTags(tags);
        libraries.put(libraryStorageKey(library.getRegion(), library.getLibraryName()), library);
    }

    public synchronized void untagResource(String resourceArn, JsonNode request) {
        requireObject(request, "Request body");
        JsonNode keys = request.get("tagKeys");
        if (isProjectArn(resourceArn)) {
            ProjectRecord project = requireProject(resourceArn);
            if (keys != null && keys.isArray()) {
                for (JsonNode key : keys) {
                    if (key != null && key.isTextual()) {
                        project.getTags().remove(key.asText());
                    }
                }
            }
            projects.put(projectStorageKey(project.getRegion(), project.getProjectName()), project);
            return;
        }
        if (isBlueprintArn(resourceArn)) {
            requireBlueprints();
            Blueprint blueprint = requireAnyBlueprint(resourceArn);
            if (keys != null && keys.isArray()) {
                for (JsonNode key : keys) {
                    if (key != null && key.isTextual()) {
                        blueprint.getTags().remove(key.asText());
                    }
                }
            }
            blueprints.put(blueprintStorageKey(blueprint.getRegion(), blueprint.getBlueprintStage(),
                    blueprint.getBlueprintName(), blueprint.getBlueprintVersion()), blueprint);
            return;
        }
        DataAutomationLibrary library = requireLibrary(resourceArn);
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                if (key != null && key.isTextual()) {
                    library.getTags().remove(key.asText());
                }
            }
        }
        libraries.put(libraryStorageKey(library.getRegion(), library.getLibraryName()), library);
    }

    static String decodeLibraryArn(String raw) {
        if (raw == null || raw.isBlank()) {
            throw validation("libraryArn is required.");
        }
        return decodeArn(raw);
    }

    static String resourceArn(JsonNode request) {
        requireObject(request, "Request body");
        String arn = optionalText(request, "resourceARN");
        if (arn == null) {
            throw validation("resourceARN is required.");
        }
        return arn;
    }

    static String decodeArn(String raw) {
        if (raw == null || raw.isBlank()) {
            throw validation("ARN is required.");
        }
        String value = stripTrailingSlash(raw);
        if (value.contains("%")) {
            try {
                value = stripTrailingSlash(URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("ARN is invalid.");
            }
        }
        return value;
    }

    private DataAutomationLibrary requireLibrary(String libraryArn) {
        String arn = decodeLibraryArn(libraryArn);
        DataAutomationLibrary byArn = findLibraryByArn(arn);
        if (byArn != null) {
            return byArn;
        }
        throw new AwsException(
                "ResourceNotFoundException",
                "Library " + arn + " could not be found.",
                404);
    }

    private Blueprint requireBlueprint(String region, String blueprintArn, String stage, String version) {
        String arn = decodeArn(blueprintArn);
        String resolvedStage = stage == null ? DEFAULT_STAGE : stage;
        for (Blueprint blueprint : listBlueprintsMatching(region, arn)) {
            if (!resolvedStage.equals(blueprint.getBlueprintStage())) {
                continue;
            }
            if (version == null || version.isBlank()) {
                if (blueprint.getBlueprintVersion() == null) {
                    return blueprint;
                }
                continue;
            }
            if (version.equals(blueprint.getBlueprintVersion())) {
                return blueprint;
            }
        }
        throw blueprintNotFound(arn);
    }

    private Blueprint requireAnyBlueprint(String resourceArn) {
        requireBlueprints();
        String arn = decodeArn(resourceArn);
        List<Blueprint> matches = new ArrayList<>();
        for (Blueprint blueprint : blueprints.scan(k -> true)) {
            if (arn.equals(blueprint.getBlueprintArn())) {
                matches.add(blueprint);
            }
        }
        if (matches.isEmpty()) {
            throw blueprintNotFound(arn);
        }
        return matches.getFirst();
    }

    private List<Blueprint> listBlueprintsMatching(String region, String arn) {
        List<Blueprint> matches = new ArrayList<>();
        for (Blueprint blueprint : blueprints.scan(k -> true)) {
            if (arn.equals(blueprint.getBlueprintArn())
                    && (blueprint.getRegion() == null || region.equals(blueprint.getRegion()))) {
                matches.add(blueprint);
            }
        }
        return matches;
    }

    private DataAutomationLibrary findLibraryByArn(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!"bedrock".equals(parsed.service()) || !parsed.resource().startsWith(LIBRARY_PREFIX)) {
                return null;
            }
            String name = parsed.resource().substring(LIBRARY_PREFIX.length());
            int slash = name.indexOf('/');
            if (slash >= 0) {
                name = name.substring(0, slash);
            }
            if (name.isBlank()) {
                return null;
            }
            return libraries.get(libraryStorageKey(parsed.region(), name)).orElse(null);
        } catch (IllegalArgumentException e) {
            throw validation("libraryArn is invalid.");
        }
    }

    private DataAutomationLibrary findLibraryByName(String region, String name) {
        return libraries.get(libraryStorageKey(region, name)).orElse(null);
    }

    private Blueprint findBlueprintByName(String region, String name, String stage) {
        return blueprints.get(blueprintStorageKey(region, stage, name, null)).orElse(null);
    }

    private boolean isBlueprintArn(String resourceArn) {
        try {
            String arn = decodeArn(resourceArn);
            return AwsArnUtils.parse(arn).resource().startsWith(BLUEPRINT_PREFIX);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void requireBlueprints() {
        if (blueprints == null) {
            throw new AwsException("InternalServerException", "Blueprint store is not configured.", 500);
        }
    }

    private void requireProjects() {
        if (projects == null) {
            throw new AwsException("InternalServerException", "Project store is not configured.", 500);
        }
    }

    private ProjectRecord requireProject(String projectArn) {
        requireProjects();
        String arn = decodeArn(projectArn);
        ProjectRecord project = findProjectByArn(arn);
        if (project != null) {
            return project;
        }
        throw projectNotFound(arn);
    }

    private ProjectRecord findProjectByArn(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!"bedrock".equals(parsed.service()) || !parsed.resource().startsWith(PROJECT_PREFIX)) {
                return null;
            }
            String name = parsed.resource().substring(PROJECT_PREFIX.length());
            int slash = name.indexOf('/');
            if (slash >= 0) {
                name = name.substring(0, slash);
            }
            if (name.isBlank()) {
                return null;
            }
            return projects.get(projectStorageKey(parsed.region(), name)).orElse(null);
        } catch (IllegalArgumentException e) {
            throw validation("projectArn is invalid.");
        }
    }

    private ProjectRecord findProjectByName(String region, String name) {
        return projects.get(projectStorageKey(region, name)).orElse(null);
    }

    private boolean isProjectArn(String resourceArn) {
        try {
            String arn = decodeArn(resourceArn);
            return AwsArnUtils.parse(arn).resource().startsWith(PROJECT_PREFIX);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String projectArn(String region, String name) {
        return "arn:aws:bedrock:" + region + ":" + regionResolver.getAccountId() + ":" + PROJECT_PREFIX + name;
    }

    private static String projectStorageKey(String region, String name) {
        return region + "::" + name;
    }

    private static void applyProjectEncryption(ProjectRecord project, JsonNode encryption) {
        if (encryption == null || !encryption.isObject()) {
            return;
        }
        project.setKmsKeyId(optionalText(encryption, "kmsKeyId"));
        JsonNode context = encryption.get("kmsEncryptionContext");
        if (context != null && context.isObject()) {
            project.setKmsEncryptionContext(context.deepCopy());
        }
    }

    private static JsonNode copyObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.deepCopy();
    }

    private static void validateProjectName(String name) {
        if (!PROJECT_NAME_PATTERN.matcher(name).matches()) {
            throw validation("projectName must match [a-zA-Z0-9-_]+ and be at most 128 characters.");
        }
    }

    private static AwsException projectNotFound(String arn) {
        return new AwsException("ResourceNotFoundException", "Project not found: " + arn, 404);
    }

    private static void applyLibraryEncryption(DataAutomationLibrary library, JsonNode encryption) {
        if (encryption == null || !encryption.isObject()) {
            return;
        }
        library.setKmsKeyId(optionalText(encryption, "kmsKeyId"));
        JsonNode context = encryption.get("kmsEncryptionContext");
        if (context != null && context.isObject()) {
            Map<String, String> values = new LinkedHashMap<>();
            context.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
            library.setKmsEncryptionContext(values);
        }
    }

    private static void applyBlueprintEncryption(Blueprint blueprint, JsonNode encryption) {
        if (encryption == null || !encryption.isObject()) {
            return;
        }
        blueprint.setKmsKeyId(optionalText(encryption, "kmsKeyId"));
        JsonNode context = encryption.get("kmsEncryptionContext");
        if (context != null && context.isObject()) {
            Map<String, String> values = new LinkedHashMap<>();
            context.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
            blueprint.setKmsEncryptionContext(values);
        }
    }

    private static Map<String, String> readTagList(JsonNode tags) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tags == null || tags.isNull()) {
            return result;
        }
        if (tags.isObject()) {
            tags.fields().forEachRemaining(entry ->
                    result.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue().asText()));
            return result;
        }
        if (!tags.isArray()) {
            return result;
        }
        for (JsonNode tag : tags) {
            if (tag == null || !tag.isObject()) {
                continue;
            }
            String key = optionalText(tag, "key");
            if (key == null) {
                continue;
            }
            String value = optionalText(tag, "value");
            result.put(key, value == null ? "" : value);
        }
        return result;
    }

    private String blueprintArn(String region, String name) {
        return "arn:aws:bedrock:" + region + ":" + regionResolver.getAccountId() + ":" + BLUEPRINT_PREFIX + name;
    }

    private static String libraryArn(String region, String account, String name) {
        return "arn:aws:bedrock:" + region + ":" + account + ":" + LIBRARY_PREFIX + name;
    }

    private static String libraryStorageKey(String region, String name) {
        return region + "::" + name;
    }

    private static String blueprintStorageKey(String region, String stage, String name, String version) {
        String resolvedStage = stage == null || stage.isBlank() ? DEFAULT_STAGE : stage;
        String resolvedVersion = version == null ? "" : version;
        return region + "::" + resolvedStage + "::" + resolvedVersion + "::" + name;
    }

    private static String nowIso() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static void validateLibraryName(String name) {
        if (!LIBRARY_NAME_PATTERN.matcher(name).matches()) {
            throw validation("libraryName must match [0-9A-Za-z._-]{1,128}.");
        }
    }

    private static void validateBlueprintName(String name) {
        if (!BLUEPRINT_NAME_PATTERN.matcher(name).matches()) {
            throw validation("blueprintName must match [a-zA-Z0-9-_]+ and be at most 128 characters.");
        }
    }

    private static void validateType(String type) {
        if (!TYPE_PATTERN.matcher(type).matches()) {
            throw validation("type must be DOCUMENT, IMAGE, AUDIO, or VIDEO.");
        }
    }

    private static void requireObject(JsonNode request, String label) {
        if (request == null || !request.isObject()) {
            throw validation(label + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        if (request == null) {
            return null;
        }
        JsonNode node = request.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException blueprintNotFound(String arn) {
        return new AwsException("ResourceNotFoundException", "Blueprint not found: " + arn, 404);
    }
}
