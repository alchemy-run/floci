package io.floci.aws.services.imagebuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.imagebuilder.ImageBuilderRefs.Ref;
import static io.floci.aws.services.imagebuilder.ImageBuilderRefs.filter;
import static io.floci.aws.services.imagebuilder.ImageBuilderRefs.idempotent;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.alreadyExists;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.arnName;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.dependency;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.invalidParameter;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.invalidRequest;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.key;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.meta;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.notFound;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.now;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.requestId;

/**
 * EC2 Image Builder's configuration resources: components (AWSTOE documents), image and container recipes,
 * infrastructure and distribution configurations, workflows, image pipelines and lifecycle policies, with their
 * resource policies and tags. Components, recipes and workflows are versioned and immutable; Amazon's managed
 * images, components and workflows are listed and resolved beside the account's own.
 */
@ApplicationScoped
public class ImageBuilderService {

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> TAGGED = Set.of("component", "image-recipe", "container-recipe",
            "infrastructure-configuration", "distribution-configuration", "image-pipeline", "image", "workflow",
            "lifecycle-policy");
    static final Set<String> STEP_ACTIONS = Set.of("LaunchInstance", "BootstrapInstanceForContainer",
            "ExecuteComponents", "CollectImageMetadata", "RunSysPrep", "SanitizeInstance", "CreateImage",
            "CreateImageFromIso", "RegisterImage", "DistributeContainerImage", "CollectImageScanFindings",
            "RunCommand", "WaitForSSMAgent", "WaitForAction", "ExecuteStateMachine", "TerminateInstance",
            "DistributeImage", "ModifyImageAttributes", "ApplyImageConfigurations");

    private final ImageBuilderStore store;
    private final Instance<S3Service> s3;
    private final Instance<ImageBuilderEngine> engine;
    private final Instance<ImageBuilderLifecycle> lifecycle;

    @Inject
    public ImageBuilderService(ImageBuilderStore store, Instance<S3Service> s3, Instance<ImageBuilderEngine> engine,
                               Instance<ImageBuilderLifecycle> lifecycle) {
        this.store = store;
        this.s3 = s3;
        this.engine = engine;
        this.lifecycle = lifecycle;
    }

    /** Resumes pipeline schedules and lifecycle policies that were persisted. */
    public void restorePersistedRuntime() {
        if (!store.everywhere(key -> key.contains("/image-pipeline/")).isEmpty()) {
            engine.get().startScheduling();
        }
        lifecycle.get().restorePersistedRuntime();
    }

    private ObjectNode object() {
        return store.mapper().createObjectNode();
    }

    private ObjectNode response() {
        return object().put("requestId", requestId());
    }

    private static String resource(ObjectNode record) {
        return meta(record).path("resource").asText();
    }

    // ---------------------------------------------------------------- documents

    /** The document a create request gives inline as {@code data} or as an S3 {@code uri}. */
    String document(JsonNode input, String what) {
        boolean data = input.hasNonNull("data");
        boolean uri = input.hasNonNull("uri");
        if (data == uri) {
            throw ImageBuilderStore.invalidParameter("Specify exactly one of data and uri for the " + what + ".");
        }
        if (data) {
            return input.get("data").asText();
        }
        String location = input.get("uri").asText();
        if (!location.startsWith("s3://")) {
            throw invalidParameter("The uri " + location + " is not an S3 URI.");
        }
        String rest = location.substring("s3://".length());
        int slash = rest.indexOf('/');
        if (slash < 1) {
            throw invalidParameter("The uri " + location + " names no object.");
        }
        try {
            return new String(s3.get().getObject(rest.substring(0, slash), rest.substring(slash + 1)).getData(),
                    StandardCharsets.UTF_8);
        } catch (AwsException e) {
            throw invalidParameter("The " + what + " document at " + location + " could not be read: "
                    + e.getMessage());
        }
    }

    static JsonNode parseYaml(String data, String what) {
        try {
            JsonNode parsed = YAML.readTree(data);
            if (parsed == null || !parsed.isObject()) {
                throw invalidParameter("The " + what + " document is not a YAML object.");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw invalidParameter("The " + what + " document is not valid YAML: " + e.getOriginalMessage());
        }
    }

    // ---------------------------------------------------------------- versioned lookups

    /** Every stored build of a versioned kind matching a reference, newest first. */
    private List<ObjectNode> builds(Ref ref, String region) {
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode record : store.list(ref.account(), key(region, ref.namePrefix()))) {
            String version = record.path("version").asText();
            String bare = version.contains("/") ? version.substring(0, version.indexOf('/')) : version;
            String build = version.contains("/") ? version.substring(version.indexOf('/') + 1)
                    : meta(record).path("build").asText("1");
            if (ImageBuilderRefs.matches(ref.version(), bare) && (ref.build() == null || ref.build().equals(build))) {
                found.add(record);
            }
        }
        found.sort((a, b) -> {
            int byVersion = ImageBuilderRefs.compare(versionOf(b), versionOf(a));
            return byVersion != 0 ? byVersion : Integer.compare(buildOf(b), buildOf(a));
        });
        return found;
    }

    private static String versionOf(ObjectNode record) {
        String version = record.path("version").asText();
        return version.contains("/") ? version.substring(0, version.indexOf('/')) : version;
    }

    private static int buildOf(ObjectNode record) {
        String version = record.path("version").asText();
        return version.contains("/") ? Integer.parseInt(version.substring(version.indexOf('/') + 1))
                : meta(record).path("build").asInt(1);
    }

    /** A component (the newest build of a version ARN), Amazon's managed ones included. */
    ObjectNode component(String arn, String region) {
        Ref ref = ImageBuilderRefs.parse(arn, "component");
        if (ref.amazon()) {
            ImageBuilderManaged.ManagedComponent managed = ImageBuilderManaged.component(ref.name());
            if (managed == null || !ImageBuilderRefs.matches(ref.version(), managed.version())
                    || ref.build() != null && !"1".equals(ref.build())) {
                throw notFound("The component " + arn + " does not exist.");
            }
            return managedComponent(managed, ref.region());
        }
        List<ObjectNode> found = builds(ref, ref.region());
        if (found.isEmpty()) {
            throw notFound("The component " + arn + " does not exist.");
        }
        return found.get(0);
    }

    private ObjectNode managedComponent(ImageBuilderManaged.ManagedComponent managed, String region) {
        ObjectNode component = object();
        component.put("arn", ImageBuilderStore.arn(ImageBuilderManaged.OWNER_ACCOUNT, region, "component/"
                        + managed.name() + "/" + managed.version() + "/1"))
                .put("name", managed.name()).put("version", managed.version()).put("description",
                        managed.description()).put("type", managed.type()).put("platform", "Linux")
                .put("owner", "Amazon").put("data", managed.data()).put("encrypted", true)
                .put("dateCreated", "2026-11-01T00:00:00.000Z").put("publisher", "AWS").put("obfuscate", false);
        component.putObject("state").put("status", "ACTIVE");
        component.putArray("supportedOsVersions").add("Amazon Linux 2").add("Amazon Linux 2023").add("Ubuntu");
        component.putObject("tags");
        component.putObject("_").put("kind", "component").put("managed", true);
        return component;
    }

    /** A workflow build, Amazon's managed ones included. */
    ObjectNode workflow(String arn, String region) {
        Ref ref = ImageBuilderRefs.parse(arn, "workflow");
        if (ref.amazon()) {
            ImageBuilderManaged.ManagedWorkflow managed = ImageBuilderManaged.WORKFLOWS.get(ref.type() + "/"
                    + ref.name());
            if (managed == null || !ImageBuilderRefs.matches(ref.version(), "1.0.0")) {
                throw notFound("The workflow " + arn + " does not exist.");
            }
            ObjectNode workflow = object();
            workflow.put("arn", ImageBuilderStore.arn(ImageBuilderManaged.OWNER_ACCOUNT, ref.region(), "workflow/"
                            + ref.type() + "/" + managed.name() + "/1.0.0/1")).put("name", managed.name())
                    .put("version", "1.0.0").put("description", managed.description()).put("type", managed.type())
                    .put("owner", "Amazon").put("data", managed.data()).put("dateCreated", "2026-11-01T00:00:00.000Z");
            workflow.putObject("state").put("status", "ACTIVE");
            workflow.putObject("tags");
            workflow.putArray("parameters");
            workflow.putObject("_").put("kind", "workflow").put("managed", true);
            return workflow;
        }
        List<ObjectNode> found = builds(ref, ref.region());
        if (found.isEmpty()) {
            throw notFound("The workflow " + arn + " does not exist.");
        }
        return found.get(0);
    }

    /** A record a plain (unversioned or exactly versioned) ARN names in this account and Region. */
    ObjectNode require(String arn, String kind, String region) {
        Ref ref = ImageBuilderRefs.parse(arn, kind);
        if (!store.account().equals(ref.account()) || !region.equals(ref.region())) {
            throw notFound("The " + kind + " " + arn + " does not exist.");
        }
        String resource = kind + "/" + ref.name() + (ref.version() == null ? "" : "/" + ref.version())
                + (ref.build() == null ? "" : "/" + ref.build());
        return store.get(key(region, resource)).orElseThrow(() -> notFound("The " + kind + " " + arn
                + " does not exist."));
    }

    /** The newest build ARN of a version: {@code latestVersionReferences}. */
    private ObjectNode latestReferences(String arnPrefix, String version) {
        String[] parts = version.split("\\.");
        ObjectNode references = object();
        references.put("latestVersionArn", arnPrefix + "/x.x.x")
                .put("latestMajorVersionArn", arnPrefix + "/" + parts[0] + ".x.x")
                .put("latestMinorVersionArn", arnPrefix + "/" + parts[0] + "." + parts[1] + ".x")
                .put("latestPatchVersionArn", arnPrefix + "/" + version);
        return references;
    }

    // ---------------------------------------------------------------- components

    public ObjectNode createComponent(JsonNode input, String region) {
        return idempotent(store, region, "CreateComponent", input, () -> {
            String data = document(input, "component");
            return storeComponent(input, region, data, null);
        });
    }

    public ObjectNode importComponent(JsonNode input, String region) {
        return idempotent(store, region, "ImportComponent", input, () -> {
            if (!"SHELL".equals(input.path("format").asText())) {
                throw invalidParameter("Image Builder imports components in the SHELL format.");
            }
            String script = document(input, "component");
            boolean test = "TEST".equals(input.path("type").asText());
            ObjectNode document = object();
            document.put("name", input.path("name").asText()).put("schemaVersion", "1.0");
            ArrayNode commands = document.putArray("phases").addObject().put("name", test ? "test" : "build")
                    .putArray("steps").addObject().put("name", "ImportedScript").put("action", "ExecuteBash")
                    .putObject("inputs").putArray("commands");
            commands.add(script);
            String data;
            try {
                data = YAML.writeValueAsString(document);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            ObjectNode response = storeComponent(input, region, data, input.path("type").asText());
            response.remove("latestVersionReferences");
            return response;
        });
    }

    private ObjectNode storeComponent(JsonNode input, String region, String data, String type) {
        String name = input.path("name").asText();
        String version = ImageBuilderRefs.semanticVersion(input.path("semanticVersion").asText(null));
        JsonNode document = parseYaml(data, "component");
        validateComponent(document);
        String resource = "component/" + arnName(name) + "/" + version + "/1";
        if (store.get(key(region, resource)).isPresent()) {
            throw alreadyExists("The component " + name + " version " + version + " already exists.");
        }
        Set<String> phases = new HashSet<>();
        document.path("phases").forEach(phase -> phases.add(phase.path("name").asText()));
        boolean testOnly = !phases.contains("build") && !phases.contains("validate");
        ObjectNode component = store.record("component", resource);
        component.put("arn", store.arn(region, resource)).put("name", name).put("version", version)
                .put("description", input.path("description").asText(document.path("description").asText("")))
                .put("changeDescription", input.path("changeDescription").asText(""))
                .put("type", type != null ? type : testOnly ? "TEST" : "BUILD")
                .put("platform", input.path("platform").asText());
        ArrayNode osVersions = component.putArray("supportedOsVersions");
        input.path("supportedOsVersions").forEach(osVersions::add);
        component.putObject("state").put("status", "ACTIVE");
        component.set("parameters", componentParameters(document));
        component.put("owner", store.account()).put("data", data).put("encrypted", true).put("dateCreated", now())
                .put("obfuscate", false);
        if (input.hasNonNull("kmsKeyId")) {
            component.put("kmsKeyId", input.get("kmsKeyId").asText());
        }
        component.set("tags", store.tags(input.path("tags"), null));
        if (input.path("dryRun").asBoolean(false)) {
            throw new AwsException("DryRunOperationException", "The request would have succeeded.", 412);
        }
        store.put(key(region, resource), component);
        ObjectNode response = response().put("clientToken", input.path("clientToken").asText())
                .put("componentBuildVersionArn", component.path("arn").asText());
        response.set("latestVersionReferences", latestReferences(store.arn(region, "component/" + arnName(name)),
                version));
        return response;
    }

    /** Checks a component document against the AWSTOE schema: phases of named steps with an action each. */
    static void validateComponent(JsonNode document) {
        String schema = document.path("schemaVersion").asText("");
        if (!"1.0".equals(schema) && !"1".equals(schema)) {
            throw invalidParameter("The component document's schemaVersion must be 1.0.");
        }
        JsonNode phases = document.path("phases");
        if (!phases.isArray() || phases.isEmpty()) {
            throw invalidParameter("The component document has no phases.");
        }
        Set<String> names = new HashSet<>();
        boolean known = false;
        for (JsonNode phase : phases) {
            String name = phase.path("name").asText("");
            if (!names.add(name)) {
                throw invalidParameter("The component document repeats the phase " + name + ".");
            }
            known |= Set.of("build", "validate", "test").contains(name);
            Set<String> steps = new HashSet<>();
            for (JsonNode step : phase.path("steps")) {
                if (!steps.add(step.path("name").asText(""))) {
                    throw invalidParameter("The phase " + name + " repeats the step " + step.path("name").asText()
                            + ".");
                }
                if (step.path("action").asText("").isEmpty()) {
                    throw invalidParameter("The step " + step.path("name").asText() + " has no action.");
                }
            }
        }
        if (!known) {
            throw invalidParameter("The component document needs a build, validate or test phase.");
        }
    }

    private ArrayNode componentParameters(JsonNode document) {
        ArrayNode parameters = store.mapper().createArrayNode();
        for (JsonNode entry : document.path("parameters")) {
            entry.fields().forEachRemaining(field -> {
                ObjectNode parameter = parameters.addObject().put("name", field.getKey())
                        .put("type", field.getValue().path("type").asText("string"));
                if (field.getValue().has("default")) {
                    parameter.putArray("defaultValue").add(field.getValue().get("default").asText());
                }
                if (field.getValue().has("description")) {
                    parameter.put("description", field.getValue().get("description").asText());
                }
            });
        }
        return parameters;
    }

    public ObjectNode getComponent(JsonNode input, String region) {
        String arn = input.path("componentBuildVersionArn").asText();
        ObjectNode component = component(arn, region);
        ObjectNode response = response();
        response.set("component", store.view(component));
        response.set("latestVersionReferences", latestReferences(component.path("arn").asText()
                .replaceAll("/[^/]+/[^/]+$", ""), component.path("version").asText()));
        return response;
    }

    public ObjectNode deleteComponent(JsonNode input, String region) {
        ObjectNode component = require(input.path("componentBuildVersionArn").asText(), "component", region);
        String arn = component.path("arn").asText();
        for (ObjectNode recipe : recipes(region)) {
            for (JsonNode reference : recipe.path("components")) {
                if (arn.equals(reference.path("componentArn").asText())) {
                    throw dependency("The component " + arn + " is used by " + recipe.path("arn").asText() + ".");
                }
            }
        }
        store.delete(key(region, resource(component)));
        return response().put("componentBuildVersionArn", arn);
    }

    private List<ObjectNode> recipes(String region) {
        List<ObjectNode> recipes = new ArrayList<>(store.list(key(region, "image-recipe/")));
        recipes.addAll(store.list(key(region, "container-recipe/")));
        return recipes;
    }

    public ObjectNode listComponents(JsonNode input, String region) {
        String owner = input.path("owner").asText("Self");
        List<ObjectNode> all = new ArrayList<>();
        if ("Amazon".equals(owner)) {
            ImageBuilderManaged.COMPONENTS.forEach(managed -> all.add(managedComponent(managed, region)));
        } else if ("Self".equals(owner)) {
            all.addAll(store.list(key(region, "component/")));
        }
        Map<String, ObjectNode> versions = new LinkedHashMap<>();
        for (ObjectNode component : all) {
            if (!passes(component, input.path("filters"), List.of("name", "platform", "type", "version"))) {
                continue;
            }
            String versionKey = input.path("byName").asBoolean(false) ? component.path("name").asText()
                    : component.path("name").asText() + "/" + component.path("version").asText();
            ObjectNode existing = versions.get(versionKey);
            if (existing == null || ImageBuilderRefs.compare(component.path("version").asText(),
                    existing.path("version").asText()) > 0) {
                versions.put(versionKey, component);
            }
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode component : versions.values()) {
            ObjectNode version = object();
            version.put("arn", component.path("arn").asText().replaceAll("/[^/]+$", ""));
            for (String field : List.of("name", "version", "description", "platform", "supportedOsVersions", "type",
                    "owner", "dateCreated")) {
                if (component.has(field)) {
                    version.set(field, component.get(field));
                }
            }
            version.put("status", "ACTIVE");
            items.add(version);
        }
        return store.page(items, input, "componentVersionList");
    }

    private static boolean passes(ObjectNode record, JsonNode filters, List<String> fields) {
        for (String field : fields) {
            List<String> values = filter(filters, field);
            if (values != null && !values.contains(record.path(field).asText())) {
                return false;
            }
        }
        List<String> os = filter(filters, "supportedOsVersion");
        if (os != null) {
            boolean any = false;
            for (JsonNode version : record.path("supportedOsVersions")) {
                any |= os.contains(version.asText());
            }
            return any;
        }
        return true;
    }

    public ObjectNode listComponentBuildVersions(JsonNode input, String region) {
        Ref ref = ImageBuilderRefs.parse(input.path("componentVersionArn").asText(), "component");
        List<ObjectNode> found = ref.amazon() ? List.of(component(input.path("componentVersionArn").asText(), region))
                : builds(ref, region);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode component : found) {
            ObjectNode summary = object();
            for (String field : List.of("arn", "name", "version", "platform", "supportedOsVersions", "state", "type",
                    "owner", "description", "changeDescription", "dateCreated", "tags", "publisher", "obfuscate")) {
                if (component.has(field)) {
                    summary.set(field, component.get(field));
                }
            }
            items.add(summary);
        }
        return store.page(items, input, "componentSummaryList");
    }

    // ---------------------------------------------------------------- recipes

    public ObjectNode createImageRecipe(JsonNode input, String region) {
        return idempotent(store, region, "CreateImageRecipe", input, () -> {
            ObjectNode recipe = recipe(input, region, "image-recipe");
            String parent = input.path("parentImage").asText("");
            recipe.put("type", "AMI").put("platform", engine.get().parentPlatform(parent, region))
                    .put("parentImage", parent);
            if (input.has("blockDeviceMappings")) {
                recipe.set("blockDeviceMappings", input.get("blockDeviceMappings").deepCopy());
            }
            recipe.put("workingDirectory", input.path("workingDirectory").asText("/tmp"));
            ObjectNode additional = input.has("additionalInstanceConfiguration")
                    ? (ObjectNode) input.get("additionalInstanceConfiguration").deepCopy() : object();
            if (!additional.has("systemsManagerAgent")) {
                additional.putObject("systemsManagerAgent").put("uninstallAfterBuild", false);
            }
            recipe.set("additionalInstanceConfiguration", additional);
            if (input.has("amiTags")) {
                recipe.set("amiTags", input.get("amiTags").deepCopy());
            }
            return save(recipe, input, region, "imageRecipeArn");
        });
    }

    public ObjectNode createContainerRecipe(JsonNode input, String region) {
        return idempotent(store, region, "CreateContainerRecipe", input, () -> {
            ObjectNode recipe = recipe(input, region, "container-recipe");
            recipe.put("containerType", input.path("containerType").asText("DOCKER"))
                    .put("platform", input.path("platformOverride").asText("Linux"))
                    .put("parentImage", input.path("parentImage").asText());
            String template = input.hasNonNull("dockerfileTemplateData") || input.hasNonNull("dockerfileTemplateUri")
                    ? dockerfile(input) : null;
            if (template == null) {
                throw invalidParameter("Specify dockerfileTemplateData or dockerfileTemplateUri.");
            }
            recipe.put("dockerfileTemplateData", template);
            if (input.has("instanceConfiguration")) {
                recipe.set("instanceConfiguration", input.get("instanceConfiguration").deepCopy());
            }
            if (input.hasNonNull("imageOsVersionOverride")) {
                recipe.put("imageOsVersionOverride", input.get("imageOsVersionOverride").asText());
            }
            recipe.put("workingDirectory", input.path("workingDirectory").asText("/tmp"));
            JsonNode target = input.path("targetRepository");
            if (!"ECR".equals(target.path("service").asText()) || target.path("repositoryName").asText("").isEmpty()) {
                throw invalidParameter("targetRepository names an ECR repository.");
            }
            recipe.set("targetRepository", target.deepCopy());
            recipe.put("encrypted", true);
            if (input.hasNonNull("kmsKeyId")) {
                recipe.put("kmsKeyId", input.get("kmsKeyId").asText());
            }
            return save(recipe, input, region, "containerRecipeArn");
        });
    }

    private String dockerfile(JsonNode input) {
        if (input.hasNonNull("dockerfileTemplateData")) {
            return input.get("dockerfileTemplateData").asText();
        }
        ObjectNode uri = object().put("uri", input.get("dockerfileTemplateUri").asText());
        return document(uri, "Dockerfile template");
    }

    private ObjectNode recipe(JsonNode input, String region, String kind) {
        String name = input.path("name").asText();
        String version = ImageBuilderRefs.semanticVersion(input.path("semanticVersion").asText(null));
        String resource = kind + "/" + arnName(name) + "/" + version;
        if (store.get(key(region, resource)).isPresent()) {
            throw alreadyExists("The " + kind + " " + name + " version " + version + " already exists.");
        }
        ObjectNode recipe = store.record(kind, resource);
        recipe.put("arn", store.arn(region, resource)).put("name", name)
                .put("description", input.path("description").asText("")).put("owner", store.account())
                .put("version", version);
        ArrayNode components = recipe.putArray("components");
        for (JsonNode reference : input.path("components")) {
            component(reference.path("componentArn").asText(), region);
            components.add(reference.deepCopy());
        }
        if (components.isEmpty()) {
            throw invalidParameter("A recipe needs at least one component.");
        }
        recipe.put("dateCreated", now());
        recipe.set("tags", store.tags(input.path("tags"), null));
        return recipe;
    }

    private ObjectNode save(ObjectNode record, JsonNode input, String region, String arnField) {
        if (input.path("dryRun").asBoolean(false)) {
            throw new AwsException("DryRunOperationException", "The request would have succeeded.", 412);
        }
        store.put(key(region, resource(record)), record);
        ObjectNode response = response().put("clientToken", input.path("clientToken").asText())
                .put(arnField, record.path("arn").asText());
        if (record.has("version") && !"image-pipeline".equals(meta(record).path("kind").asText())) {
            response.set("latestVersionReferences", latestReferences(record.path("arn").asText()
                    .replaceAll("/[^/]+$", ""), record.path("version").asText()));
        }
        return response;
    }

    public ObjectNode getImageRecipe(JsonNode input, String region) {
        ObjectNode response = response();
        response.set("imageRecipe", store.view(require(input.path("imageRecipeArn").asText(), "image-recipe", region)));
        return response;
    }

    public ObjectNode getContainerRecipe(JsonNode input, String region) {
        ObjectNode response = response();
        response.set("containerRecipe", store.view(require(input.path("containerRecipeArn").asText(),
                "container-recipe", region)));
        return response;
    }

    public ObjectNode deleteImageRecipe(JsonNode input, String region) {
        return deleteUsed(input.path("imageRecipeArn").asText(), "image-recipe", "imageRecipeArn", region);
    }

    public ObjectNode deleteContainerRecipe(JsonNode input, String region) {
        return deleteUsed(input.path("containerRecipeArn").asText(), "container-recipe", "containerRecipeArn",
                region);
    }

    /** Deletes a resource no pipeline uses: ResourceDependencyException otherwise. */
    private ObjectNode deleteUsed(String arn, String kind, String field, String region) {
        ObjectNode record = require(arn, kind, region);
        for (ObjectNode pipeline : store.list(key(region, "image-pipeline/"))) {
            if (record.path("arn").asText().equals(pipeline.path(field).asText())) {
                throw dependency("The " + kind + " " + arn + " is used by the image pipeline "
                        + pipeline.path("arn").asText() + ".");
            }
        }
        store.delete(key(region, resource(record)));
        return response().put(field, record.path("arn").asText());
    }

    public ObjectNode listImageRecipes(JsonNode input, String region) {
        return listRecipes(input, region, "image-recipe", "imageRecipeSummaryList", List.of("arn", "name", "platform",
                "owner", "parentImage", "dateCreated", "tags"));
    }

    public ObjectNode listContainerRecipes(JsonNode input, String region) {
        return listRecipes(input, region, "container-recipe", "containerRecipeSummaryList", List.of("arn",
                "containerType", "name", "platform", "owner", "parentImage", "dateCreated", "tags"));
    }

    private ObjectNode listRecipes(JsonNode input, String region, String kind, String field, List<String> fields) {
        String owner = input.path("owner").asText("Self");
        List<ObjectNode> items = new ArrayList<>();
        if ("Self".equals(owner)) {
            for (ObjectNode recipe : store.list(key(region, kind + "/"))) {
                if (!passes(recipe, input.path("filters"), List.of("name", "parentImage", "platform",
                        "containerType"))) {
                    continue;
                }
                ObjectNode summary = object();
                fields.forEach(f -> {
                    if (recipe.has(f)) {
                        summary.set(f, recipe.get(f));
                    }
                });
                items.add(summary);
            }
        }
        return store.page(items, input, field);
    }

    // ---------------------------------------------------------------- infrastructure configurations

    public ObjectNode createInfrastructureConfiguration(JsonNode input, String region) {
        return idempotent(store, region, "CreateInfrastructureConfiguration", input, () -> {
            String name = input.path("name").asText();
            String resource = "infrastructure-configuration/" + arnName(name);
            if (store.get(key(region, resource)).isPresent()) {
                throw alreadyExists("The infrastructure configuration " + name + " already exists.");
            }
            ObjectNode configuration = store.record("infrastructure-configuration", resource);
            configuration.put("arn", store.arn(region, resource)).put("name", name).put("dateCreated", now());
            applyInfrastructure(configuration, input);
            configuration.set("tags", store.tags(input.path("tags"), null));
            return save(configuration, input, region, "infrastructureConfigurationArn");
        });
    }

    private static final List<String> INFRASTRUCTURE_FIELDS = List.of("description", "instanceTypes",
            "instanceProfileName", "securityGroupIds", "subnetId", "logging", "keyPair", "snsTopicArn",
            "resourceTags", "instanceMetadataOptions", "placement", "parentInstance");

    private void applyInfrastructure(ObjectNode configuration, JsonNode input) {
        if (input.path("instanceProfileName").asText("").isEmpty()) {
            throw invalidParameter("instanceProfileName is required.");
        }
        for (String field : INFRASTRUCTURE_FIELDS) {
            if (input.hasNonNull(field)) {
                configuration.set(field, input.get(field).deepCopy());
            } else {
                configuration.remove(field);
            }
        }
        configuration.put("terminateInstanceOnFailure", input.path("terminateInstanceOnFailure").asBoolean(true));
        configuration.put("terminateInstanceOnCompletion",
                input.path("terminateInstanceOnCompletion").asBoolean(true));
    }

    public ObjectNode updateInfrastructureConfiguration(JsonNode input, String region) {
        return idempotent(store, region, "UpdateInfrastructureConfiguration", input, () -> {
            ObjectNode configuration = require(input.path("infrastructureConfigurationArn").asText(),
                    "infrastructure-configuration", region);
            applyInfrastructure(configuration, input);
            configuration.put("dateUpdated", now());
            store.put(key(region, resource(configuration)), configuration);
            return response().put("clientToken", input.path("clientToken").asText())
                    .put("infrastructureConfigurationArn", configuration.path("arn").asText());
        });
    }

    public ObjectNode getInfrastructureConfiguration(JsonNode input, String region) {
        ObjectNode response = response();
        response.set("infrastructureConfiguration", store.view(require(input.path("infrastructureConfigurationArn")
                .asText(), "infrastructure-configuration", region)));
        return response;
    }

    public ObjectNode deleteInfrastructureConfiguration(JsonNode input, String region) {
        return deleteUsed(input.path("infrastructureConfigurationArn").asText(), "infrastructure-configuration",
                "infrastructureConfigurationArn", region);
    }

    public ObjectNode listInfrastructureConfigurations(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode configuration : store.list(key(region, "infrastructure-configuration/"))) {
            if (passes(configuration, input.path("filters"), List.of("name"))) {
                items.add(summary(configuration, "arn", "name", "description", "dateCreated", "dateUpdated",
                        "resourceTags", "tags", "instanceTypes", "instanceProfileName", "placement"));
            }
        }
        return store.page(items, input, "infrastructureConfigurationSummaryList");
    }

    // ---------------------------------------------------------------- distribution configurations

    public ObjectNode createDistributionConfiguration(JsonNode input, String region) {
        return idempotent(store, region, "CreateDistributionConfiguration", input, () -> {
            String name = input.path("name").asText();
            String resource = "distribution-configuration/" + arnName(name);
            if (store.get(key(region, resource)).isPresent()) {
                throw alreadyExists("The distribution configuration " + name + " already exists.");
            }
            ObjectNode configuration = store.record("distribution-configuration", resource);
            configuration.put("arn", store.arn(region, resource)).put("name", name).put("dateCreated", now());
            applyDistribution(configuration, input);
            configuration.set("tags", store.tags(input.path("tags"), null));
            return save(configuration, input, region, "distributionConfigurationArn");
        });
    }

    private void applyDistribution(ObjectNode configuration, JsonNode input) {
        if (input.hasNonNull("description")) {
            configuration.put("description", input.get("description").asText());
        } else {
            configuration.remove("description");
        }
        JsonNode distributions = input.path("distributions");
        if (!distributions.isArray() || distributions.isEmpty()) {
            throw invalidParameter("A distribution configuration needs at least one distribution.");
        }
        Set<String> regions = new HashSet<>();
        for (JsonNode distribution : distributions) {
            if (!regions.add(distribution.path("region").asText())) {
                throw invalidParameter("The distribution configuration repeats the Region "
                        + distribution.path("region").asText() + ".");
            }
            String amiName = distribution.path("amiDistributionConfiguration").path("name").asText("");
            if (!amiName.isEmpty() && !amiName.contains("{{")) {
                throw invalidParameter("The AMI name " + amiName + " must include {{ imagebuilder:buildDate }} or"
                        + " another variable, as each build makes an AMI.");
            }
        }
        configuration.set("distributions", distributions.deepCopy());
        configuration.put("timeoutMinutes", 720);
    }

    public ObjectNode updateDistributionConfiguration(JsonNode input, String region) {
        return idempotent(store, region, "UpdateDistributionConfiguration", input, () -> {
            ObjectNode configuration = require(input.path("distributionConfigurationArn").asText(),
                    "distribution-configuration", region);
            applyDistribution(configuration, input);
            configuration.put("dateUpdated", now());
            store.put(key(region, resource(configuration)), configuration);
            return response().put("clientToken", input.path("clientToken").asText())
                    .put("distributionConfigurationArn", configuration.path("arn").asText());
        });
    }

    public ObjectNode getDistributionConfiguration(JsonNode input, String region) {
        ObjectNode response = response();
        response.set("distributionConfiguration", store.view(require(input.path("distributionConfigurationArn")
                .asText(), "distribution-configuration", region)));
        return response;
    }

    public ObjectNode deleteDistributionConfiguration(JsonNode input, String region) {
        return deleteUsed(input.path("distributionConfigurationArn").asText(), "distribution-configuration",
                "distributionConfigurationArn", region);
    }

    public ObjectNode listDistributionConfigurations(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode configuration : store.list(key(region, "distribution-configuration/"))) {
            if (passes(configuration, input.path("filters"), List.of("name"))) {
                ObjectNode summary = summary(configuration, "arn", "name", "description", "dateCreated",
                        "dateUpdated", "tags");
                ArrayNode regions = summary.putArray("regions");
                configuration.path("distributions").forEach(d -> regions.add(d.path("region").asText()));
                items.add(summary);
            }
        }
        return store.page(items, input, "distributionConfigurationSummaryList");
    }

    // ---------------------------------------------------------------- workflows

    public ObjectNode createWorkflow(JsonNode input, String region) {
        return idempotent(store, region, "CreateWorkflow", input, () -> {
            String name = input.path("name").asText();
            String version = ImageBuilderRefs.semanticVersion(input.path("semanticVersion").asText(null));
            String type = input.path("type").asText();
            String data = document(input, "workflow");
            JsonNode document = parseYaml(data, "workflow");
            validateWorkflow(document, type);
            String resource = "workflow/" + type.toLowerCase() + "/" + arnName(name) + "/" + version + "/1";
            if (store.get(key(region, resource)).isPresent()) {
                throw alreadyExists("The workflow " + name + " version " + version + " already exists.");
            }
            ObjectNode workflow = store.record("workflow", resource);
            workflow.put("arn", store.arn(region, resource)).put("name", name).put("version", version)
                    .put("description", input.path("description").asText(""))
                    .put("changeDescription", input.path("changeDescription").asText("")).put("type", type);
            workflow.putObject("state").put("status", "ACTIVE");
            workflow.put("owner", store.account()).put("data", data).put("dateCreated", now());
            if (input.hasNonNull("kmsKeyId")) {
                workflow.put("kmsKeyId", input.get("kmsKeyId").asText());
            }
            workflow.set("tags", store.tags(input.path("tags"), null));
            ArrayNode parameters = workflow.putArray("parameters");
            for (JsonNode parameter : document.path("parameters")) {
                ObjectNode detail = parameters.addObject().put("name", parameter.path("name").asText())
                        .put("type", parameter.path("type").asText("string"));
                if (parameter.has("default")) {
                    ArrayNode defaults = detail.putArray("defaultValue");
                    if (parameter.get("default").isArray()) {
                        parameter.get("default").forEach(v -> defaults.add(v.asText()));
                    } else {
                        defaults.add(parameter.get("default").asText());
                    }
                }
                if (parameter.has("description")) {
                    detail.put("description", parameter.get("description").asText());
                }
            }
            if (input.path("dryRun").asBoolean(false)) {
                throw new AwsException("DryRunOperationException", "The request would have succeeded.", 412);
            }
            store.put(key(region, resource), workflow);
            ObjectNode response = object().put("clientToken", input.path("clientToken").asText())
                    .put("workflowBuildVersionArn", workflow.path("arn").asText());
            response.set("latestVersionReferences", latestReferences(store.arn(region, "workflow/"
                    + type.toLowerCase() + "/" + arnName(name)), version));
            return response;
        });
    }

    /** Checks a workflow document: schema 1.0, up to 15 uniquely named steps of supported actions. */
    static void validateWorkflow(JsonNode document, String type) {
        String schema = document.path("schemaVersion").asText("");
        if (!"1.0".equals(schema) && !"1".equals(schema)) {
            throw invalidParameter("The workflow document's schemaVersion must be 1.0.");
        }
        JsonNode steps = document.path("steps");
        if (!steps.isArray() || steps.isEmpty() || steps.size() > 15) {
            throw invalidParameter("A workflow document has 1 to 15 steps.");
        }
        Set<String> names = new HashSet<>();
        for (JsonNode step : steps) {
            String name = step.path("name").asText("");
            if (!name.matches("[A-Za-z0-9_]{3,128}") || !names.add(name)) {
                throw invalidParameter("The workflow step name " + name + " is not valid or not unique.");
            }
            String action = step.path("action").asText("");
            if (!STEP_ACTIONS.contains(action)) {
                throw invalidParameter("The workflow step " + name + " uses the unsupported action " + action + ".");
            }
            if ("ExecuteComponents".equals(action) && "DISTRIBUTION".equals(type)
                    || Set.of("DistributeImage", "ModifyImageAttributes", "ApplyImageConfigurations",
                    "DistributeContainerImage").contains(action) && !"DISTRIBUTION".equals(type)
                    || "CollectImageScanFindings".equals(action) && !"TEST".equals(type)) {
                throw invalidParameter("The action " + action + " is not allowed in a " + type + " workflow.");
            }
        }
        if (document.path("parameters").size() > 25 || document.path("outputs").size() > 25) {
            throw invalidParameter("A workflow document has at most 25 parameters and 25 outputs.");
        }
    }

    public ObjectNode getWorkflow(JsonNode input, String region) {
        ObjectNode response = object();
        response.set("workflow", store.view(workflow(input.path("workflowBuildVersionArn").asText(), region)));
        return response;
    }

    public ObjectNode deleteWorkflow(JsonNode input, String region) {
        String arn = input.path("workflowBuildVersionArn").asText();
        Ref ref = ImageBuilderRefs.parse(arn, "workflow");
        if (ref.amazon()) {
            throw invalidRequest("Amazon-managed workflows cannot be deleted.");
        }
        ObjectNode workflow = store.get(key(region, "workflow/" + ref.type() + "/" + ref.name() + "/" + ref.version()
                + "/" + ref.build())).orElseThrow(() -> notFound("The workflow " + arn + " does not exist."));
        for (ObjectNode pipeline : store.list(key(region, "image-pipeline/"))) {
            for (JsonNode configuration : pipeline.path("workflows")) {
                if (arn.equals(configuration.path("workflowArn").asText())) {
                    throw dependency("The workflow " + arn + " is used by " + pipeline.path("arn").asText() + ".");
                }
            }
        }
        store.delete(key(region, resource(workflow)));
        return object().put("workflowBuildVersionArn", arn);
    }

    public ObjectNode listWorkflows(JsonNode input, String region) {
        String owner = input.path("owner").asText("Self");
        List<ObjectNode> all = new ArrayList<>();
        if ("Amazon".equals(owner)) {
            ImageBuilderManaged.WORKFLOWS.forEach((path, managed) -> all.add(workflow(ImageBuilderStore.arn(
                    ImageBuilderManaged.OWNER_ACCOUNT, region, "workflow/" + path + "/x.x.x"), region)));
        } else if ("Self".equals(owner)) {
            all.addAll(store.list(key(region, "workflow/")));
        }
        Map<String, ObjectNode> versions = new LinkedHashMap<>();
        for (ObjectNode workflow : all) {
            if (!passes(workflow, input.path("filters"), List.of("name", "type"))) {
                continue;
            }
            String versionKey = workflow.path("type").asText() + "/" + workflow.path("name").asText()
                    + (input.path("byName").asBoolean(false) ? "" : "/" + workflow.path("version").asText());
            ObjectNode existing = versions.get(versionKey);
            if (existing == null || ImageBuilderRefs.compare(workflow.path("version").asText(),
                    existing.path("version").asText()) > 0) {
                versions.put(versionKey, workflow);
            }
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode workflow : versions.values()) {
            ObjectNode version = summary(workflow, "name", "version", "description", "type", "owner", "dateCreated");
            version.put("arn", workflow.path("arn").asText().replaceAll("/[^/]+$", ""));
            items.add(version);
        }
        return store.page(items, input, "workflowVersionList");
    }

    public ObjectNode listWorkflowBuildVersions(JsonNode input, String region) {
        String arn = input.path("workflowVersionArn").asText();
        Ref ref = ImageBuilderRefs.parse(arn, "workflow");
        List<ObjectNode> found = ref.amazon() ? List.of(workflow(arn, region)) : builds(ref, region);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode workflow : found) {
            items.add(summary(workflow, "arn", "name", "version", "description", "changeDescription", "type", "owner",
                    "state", "dateCreated", "tags"));
        }
        return store.page(items, input, "workflowSummaryList");
    }

    // ---------------------------------------------------------------- image pipelines

    public ObjectNode createImagePipeline(JsonNode input, String region) {
        return idempotent(store, region, "CreateImagePipeline", input, () -> {
            String name = input.path("name").asText();
            String resource = "image-pipeline/" + arnName(name);
            if (store.get(key(region, resource)).isPresent()) {
                throw alreadyExists("The image pipeline " + name + " already exists.");
            }
            ObjectNode pipeline = store.record("image-pipeline", resource);
            pipeline.put("arn", store.arn(region, resource)).put("name", name).put("dateCreated", now());
            applyPipeline(pipeline, input, region);
            pipeline.set("tags", store.tags(input.path("tags"), null));
            if (input.has("imageTags")) {
                pipeline.set("imageTags", input.get("imageTags").deepCopy());
            }
            ObjectNode response = save(pipeline, input, region, "imagePipelineArn");
            engine.get().pipelineChanged(store.account(), region, pipeline);
            return response;
        });
    }

    private void applyPipeline(ObjectNode pipeline, JsonNode input, String region) {
        boolean image = input.hasNonNull("imageRecipeArn");
        boolean container = input.hasNonNull("containerRecipeArn");
        if (image == container) {
            throw invalidParameter("Specify exactly one of imageRecipeArn and containerRecipeArn.");
        }
        ObjectNode recipe = image ? require(input.get("imageRecipeArn").asText(), "image-recipe", region)
                : require(input.get("containerRecipeArn").asText(), "container-recipe", region);
        require(input.path("infrastructureConfigurationArn").asText(), "infrastructure-configuration", region);
        if (input.hasNonNull("distributionConfigurationArn")) {
            require(input.get("distributionConfigurationArn").asText(), "distribution-configuration", region);
        }
        pipeline.remove(List.of("imageRecipeArn", "containerRecipeArn", "distributionConfigurationArn",
                "description", "schedule", "workflows", "loggingConfiguration", "executionRole"));
        if (input.hasNonNull("description")) {
            pipeline.put("description", input.get("description").asText());
        }
        pipeline.put("platform", recipe.path("platform").asText("Linux"));
        pipeline.put("enhancedImageMetadataEnabled", input.path("enhancedImageMetadataEnabled").asBoolean(true));
        pipeline.put(image ? "imageRecipeArn" : "containerRecipeArn", recipe.path("arn").asText());
        pipeline.put("infrastructureConfigurationArn", input.path("infrastructureConfigurationArn").asText());
        if (input.hasNonNull("distributionConfigurationArn")) {
            pipeline.put("distributionConfigurationArn", input.get("distributionConfigurationArn").asText());
        }
        ObjectNode tests = object();
        tests.put("imageTestsEnabled", input.path("imageTestsConfiguration").path("imageTestsEnabled")
                .asBoolean(true));
        tests.put("timeoutMinutes", input.path("imageTestsConfiguration").path("timeoutMinutes").asInt(720));
        pipeline.set("imageTestsConfiguration", tests);
        if (input.hasNonNull("schedule")) {
            ObjectNode schedule = (ObjectNode) input.get("schedule").deepCopy();
            ImageBuilderSchedule.validate(schedule.path("scheduleExpression").asText(""));
            if (!schedule.has("pipelineExecutionStartCondition")) {
                schedule.put("pipelineExecutionStartCondition", "EXPRESSION_MATCH_AND_DEPENDENCY_UPDATES_AVAILABLE");
            }
            pipeline.set("schedule", schedule);
        }
        pipeline.put("status", input.path("status").asText("ENABLED"));
        ObjectNode scanning = input.has("imageScanningConfiguration")
                ? (ObjectNode) input.get("imageScanningConfiguration").deepCopy() : object();
        if (!scanning.has("imageScanningEnabled")) {
            scanning.put("imageScanningEnabled", false);
        }
        pipeline.set("imageScanningConfiguration", scanning);
        if (input.has("workflows")) {
            pipeline.set("workflows", workflows(input.get("workflows"), region));
        }
        if (input.has("loggingConfiguration")) {
            pipeline.set("loggingConfiguration", input.get("loggingConfiguration").deepCopy());
        }
        if (input.hasNonNull("executionRole")) {
            pipeline.put("executionRole", input.get("executionRole").asText());
        }
        if (input.has("imageTags")) {
            pipeline.set("imageTags", input.get("imageTags").deepCopy());
        }
    }

    /** Checks a workflow list: each workflow exists, at most one build and one distribution, ten in all. */
    ArrayNode workflows(JsonNode given, String region) {
        ArrayNode workflows = store.mapper().createArrayNode();
        int builds = 0;
        int distributions = 0;
        for (JsonNode configuration : given) {
            ObjectNode workflow = workflow(configuration.path("workflowArn").asText(), region);
            builds += "BUILD".equals(workflow.path("type").asText()) ? 1 : 0;
            distributions += "DISTRIBUTION".equals(workflow.path("type").asText()) ? 1 : 0;
            workflows.add(configuration.deepCopy());
        }
        if (builds > 1 || distributions > 1 || workflows.size() > 10) {
            throw invalidParameter("An image runs at most one build and one distribution workflow, ten in all.");
        }
        return workflows;
    }

    public ObjectNode updateImagePipeline(JsonNode input, String region) {
        return idempotent(store, region, "UpdateImagePipeline", input, () -> {
            ObjectNode pipeline = require(input.path("imagePipelineArn").asText(), "image-pipeline", region);
            applyPipeline(pipeline, input, region);
            pipeline.put("dateUpdated", now());
            store.put(key(region, resource(pipeline)), pipeline);
            engine.get().pipelineChanged(store.account(), region, pipeline);
            return response().put("clientToken", input.path("clientToken").asText())
                    .put("imagePipelineArn", pipeline.path("arn").asText());
        });
    }

    public ObjectNode getImagePipeline(JsonNode input, String region) {
        ObjectNode pipeline = require(input.path("imagePipelineArn").asText(), "image-pipeline", region);
        ObjectNode view = store.view(pipeline);
        String next = engine.get().nextRun(pipeline);
        if (next != null) {
            view.put("dateNextRun", next);
        }
        ObjectNode response = response();
        response.set("imagePipeline", view);
        return response;
    }

    public ObjectNode deleteImagePipeline(JsonNode input, String region) {
        ObjectNode pipeline = require(input.path("imagePipelineArn").asText(), "image-pipeline", region);
        store.delete(key(region, resource(pipeline)));
        engine.get().pipelineDeleted(store.account(), region, pipeline);
        return response().put("imagePipelineArn", pipeline.path("arn").asText());
    }

    public ObjectNode listImagePipelines(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode pipeline : store.list(key(region, "image-pipeline/"))) {
            if (passes(pipeline, input.path("filters"), List.of("name", "status", "description"))) {
                items.add(store.view(pipeline));
            }
        }
        return store.page(items, input, "imagePipelineList");
    }

    // ---------------------------------------------------------------- lifecycle policies

    public ObjectNode createLifecyclePolicy(JsonNode input, String region) {
        return idempotent(store, region, "CreateLifecyclePolicy", input, () -> {
            String name = input.path("name").asText();
            String resource = "lifecycle-policy/" + arnName(name);
            if (store.get(key(region, resource)).isPresent()) {
                throw alreadyExists("The lifecycle policy " + name + " already exists.");
            }
            ObjectNode policy = store.record("lifecycle-policy", resource);
            policy.put("arn", store.arn(region, resource)).put("name", name).put("dateCreated", now());
            applyLifecycle(policy, input);
            policy.set("tags", store.tags(input.path("tags"), null));
            if (input.path("dryRun").asBoolean(false)) {
                throw new AwsException("DryRunOperationException", "The request would have succeeded.", 412);
            }
            store.put(key(region, resource), policy);
            lifecycle.get().startEvaluating();
            return object().put("clientToken", input.path("clientToken").asText())
                    .put("lifecyclePolicyArn", policy.path("arn").asText());
        });
    }

    private void applyLifecycle(ObjectNode policy, JsonNode input) {
        String type = input.path("resourceType").asText();
        if (!Set.of("AMI_IMAGE", "CONTAINER_IMAGE").contains(type)) {
            throw invalidParameter("resourceType is AMI_IMAGE or CONTAINER_IMAGE.");
        }
        JsonNode details = input.path("policyDetails");
        if (!details.isArray() || details.isEmpty() || details.size() > 3) {
            throw invalidParameter("A lifecycle policy has 1 to 3 policy details.");
        }
        for (JsonNode detail : details) {
            String filter = detail.path("filter").path("type").asText();
            if ("AGE".equals(filter) && !detail.path("filter").has("unit")) {
                throw invalidParameter("An AGE filter needs a unit.");
            }
        }
        JsonNode selection = input.path("resourceSelection");
        if (!selection.has("recipes") && !selection.has("tagMap")) {
            throw invalidParameter("resourceSelection names recipes or a tagMap.");
        }
        policy.put("description", input.path("description").asText(""))
                .put("status", input.path("status").asText("ENABLED"))
                .put("executionRole", input.path("executionRole").asText()).put("resourceType", type);
        policy.set("policyDetails", details.deepCopy());
        policy.set("resourceSelection", selection.deepCopy());
    }

    public ObjectNode updateLifecyclePolicy(JsonNode input, String region) {
        return idempotent(store, region, "UpdateLifecyclePolicy", input, () -> {
            ObjectNode policy = require(input.path("lifecyclePolicyArn").asText(), "lifecycle-policy", region);
            applyLifecycle(policy, input);
            policy.put("dateUpdated", now());
            store.put(key(region, resource(policy)), policy);
            return object().put("lifecyclePolicyArn", policy.path("arn").asText());
        });
    }

    public ObjectNode getLifecyclePolicy(JsonNode input, String region) {
        ObjectNode response = object();
        response.set("lifecyclePolicy", store.view(require(input.path("lifecyclePolicyArn").asText(),
                "lifecycle-policy", region)));
        return response;
    }

    public ObjectNode deleteLifecyclePolicy(JsonNode input, String region) {
        ObjectNode policy = require(input.path("lifecyclePolicyArn").asText(), "lifecycle-policy", region);
        store.delete(key(region, resource(policy)));
        return object().put("lifecyclePolicyArn", policy.path("arn").asText());
    }

    public ObjectNode listLifecyclePolicies(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode policy : store.list(key(region, "lifecycle-policy/"))) {
            if (passes(policy, input.path("filters"), List.of("name", "status", "resourceType"))) {
                items.add(summary(policy, "arn", "name", "description", "status", "executionRole", "resourceType",
                        "dateCreated", "dateUpdated", "dateLastRun", "tags"));
            }
        }
        return store.page(items, input, "lifecyclePolicySummaryList");
    }

    // ---------------------------------------------------------------- resource policies

    private ObjectNode putPolicy(String arn, String kind, String policy, String field, String region) {
        ObjectNode record = require(arn, kind, region);
        try {
            JsonNode document = store.mapper().readTree(policy);
            if (!document.isObject() || !document.has("Statement")) {
                throw invalidParameter("The policy has no Statement.");
            }
        } catch (JsonProcessingException e) {
            throw invalidParameter("The policy is not valid JSON.");
        }
        meta(record).put("policy", policy);
        store.put(key(region, resource(record)), record);
        return response().put(field, arn);
    }

    private ObjectNode getPolicy(String arn, String kind, String region) {
        ObjectNode record = require(arn, kind, region);
        String policy = meta(record).path("policy").asText(null);
        if (policy == null) {
            throw notFound("The " + kind + " " + arn + " has no policy.");
        }
        return response().put("policy", policy);
    }

    public ObjectNode putComponentPolicy(JsonNode input, String region) {
        return putPolicy(input.path("componentArn").asText(), "component", input.path("policy").asText(),
                "componentArn", region);
    }

    public ObjectNode getComponentPolicy(JsonNode input, String region) {
        return getPolicy(input.path("componentArn").asText(), "component", region);
    }

    public ObjectNode putImageRecipePolicy(JsonNode input, String region) {
        return putPolicy(input.path("imageRecipeArn").asText(), "image-recipe", input.path("policy").asText(),
                "imageRecipeArn", region);
    }

    public ObjectNode getImageRecipePolicy(JsonNode input, String region) {
        return getPolicy(input.path("imageRecipeArn").asText(), "image-recipe", region);
    }

    public ObjectNode putContainerRecipePolicy(JsonNode input, String region) {
        return putPolicy(input.path("containerRecipeArn").asText(), "container-recipe", input.path("policy").asText(),
                "containerRecipeArn", region);
    }

    public ObjectNode getContainerRecipePolicy(JsonNode input, String region) {
        return getPolicy(input.path("containerRecipeArn").asText(), "container-recipe", region);
    }

    public ObjectNode putImagePolicy(JsonNode input, String region) {
        return putPolicy(input.path("imageArn").asText(), "image", input.path("policy").asText(), "imageArn",
                region);
    }

    public ObjectNode getImagePolicy(JsonNode input, String region) {
        return getPolicy(input.path("imageArn").asText(), "image", region);
    }

    public ObjectNode getMarketplaceResource(JsonNode input, String region) {
        throw notFound("The marketplace resource " + input.path("resourceArn").asText() + " does not exist.");
    }

    // ---------------------------------------------------------------- tags

    private ObjectNode tagged(String arn, String region) {
        AwsArnUtils.Arn parsed = ImageBuilderStore.parse(arn);
        if (parsed == null) {
            throw new AwsException("InvalidParameterException", "The ARN " + arn + " is not valid.", 400);
        }
        String kind = parsed.resource().substring(0, Math.max(0, parsed.resource().indexOf('/')));
        if (!TAGGED.contains(kind) || !store.account().equals(parsed.accountId())) {
            throw notFound("The resource " + arn + " does not exist.");
        }
        return store.get(key(region, parsed.resource())).orElseThrow(() -> notFound("The resource " + arn
                + " does not exist."));
    }

    public ObjectNode listTagsForResource(String arn, String region) {
        ObjectNode response = object();
        response.set("tags", tagged(arn, region).path("tags").deepCopy());
        return response;
    }

    public ObjectNode tagResource(String arn, JsonNode input, String region) {
        ObjectNode record = tagged(arn, region);
        record.set("tags", store.tags(input.path("tags"), (ObjectNode) record.path("tags")));
        store.put(key(region, resource(record)), record);
        return object();
    }

    public ObjectNode untagResource(String arn, List<String> keys, String region) {
        ObjectNode record = tagged(arn, region);
        ObjectNode tags = (ObjectNode) record.path("tags");
        if (keys != null) {
            keys.forEach(tags::remove);
        }
        store.put(key(region, resource(record)), record);
        return object();
    }

    private ObjectNode summary(ObjectNode record, String... fields) {
        ObjectNode summary = object();
        for (String field : fields) {
            if (record.has(field)) {
                summary.set(field, record.get(field));
            }
        }
        return summary;
    }
}
