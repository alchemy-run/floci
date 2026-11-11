package io.floci.aws.services.imagebuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Image;
import io.floci.aws.services.inspector2.Inspector2Service;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.imagebuilder.ImageBuilderRefs.filter;
import static io.floci.aws.services.imagebuilder.ImageBuilderRefs.idempotent;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.arnName;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.invalidParameter;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.invalidRequest;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.key;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.meta;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.notFound;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.now;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.requestId;

/**
 * Image Builder's images: builds started from a recipe or a pipeline, each a build version
 * {@code image/<name>/<version>/<build>} that {@link ImageBuilderEngine} builds, and what they record: workflow and
 * step executions, packages, scan findings, and their output AMIs or container images. Imported VM images and
 * distribution-only runs are images too.
 */
@ApplicationScoped
public class ImageBuilderImages {

    private final ImageBuilderStore store;
    private final ImageBuilderService service;
    private final ImageBuilderEngine engine;
    private final Instance<S3Service> s3;
    private final Instance<Ec2Service> ec2;
    private final Instance<Inspector2Service> inspector;

    @Inject
    public ImageBuilderImages(ImageBuilderStore store, ImageBuilderService service, ImageBuilderEngine engine,
                              Instance<S3Service> s3, Instance<Ec2Service> ec2, Instance<Inspector2Service> inspector) {
        this.store = store;
        this.service = service;
        this.engine = engine;
        this.s3 = s3;
        this.ec2 = ec2;
        this.inspector = inspector;
    }

    S3Service s3() {
        return s3.get();
    }

    private ObjectNode object() {
        return store.mapper().createObjectNode();
    }

    private ObjectNode response() {
        return object().put("requestId", requestId());
    }

    /** An image build version this account has, by its build version ARN (or the newest build of a version). */
    ObjectNode image(String arn, String region) {
        ImageBuilderRefs.Ref ref = ImageBuilderRefs.parse(arn, "image");
        if (ref.version() == null) {
            throw invalidParameter("The ARN " + arn + " names no image version.");
        }
        if (ref.build() == null || ref.wildcard()) {
            ObjectNode best = null;
            for (ObjectNode image : store.list(ref.account(), key(region, "image/" + ref.name() + "/"))) {
                String[] version = image.path("version").asText().split("/");
                if (!ImageBuilderRefs.matches(ref.version(), version[0])) {
                    continue;
                }
                if (best == null || buildOrder(image, best) > 0) {
                    best = image;
                }
            }
            if (best == null) {
                throw notFound("The image " + arn + " does not exist.");
            }
            return best;
        }
        return store.get(ref.account(), key(region, "image/" + ref.name() + "/" + ref.version() + "/" + ref.build()))
                .orElseThrow(() -> notFound("The image " + arn + " does not exist."));
    }

    private static int buildOrder(ObjectNode a, ObjectNode b) {
        String[] x = a.path("version").asText().split("/");
        String[] y = b.path("version").asText().split("/");
        int byVersion = ImageBuilderRefs.compare(x[0], y[0]);
        return byVersion != 0 ? byVersion : Integer.compare(Integer.parseInt(x[1]), Integer.parseInt(y[1]));
    }

    // ---------------------------------------------------------------- creating builds

    public ObjectNode createImage(JsonNode input, String region) {
        return idempotent(store, region, "CreateImage", input, () -> {
            ObjectNode image = newImage(input, region, null, "USER_INITIATED");
            return response().put("clientToken", input.path("clientToken").asText())
                    .put("imageBuildVersionArn", image.path("arn").asText());
        });
    }

    public ObjectNode startImagePipelineExecution(JsonNode input, String region) {
        return idempotent(store, region, "StartImagePipelineExecution", input, () -> {
            ObjectNode pipeline = service.require(input.path("imagePipelineArn").asText(), "image-pipeline", region);
            ObjectNode image = fromPipeline(pipeline, region, "USER_INITIATED", input.path("tags"));
            return response().put("clientToken", input.path("clientToken").asText())
                    .put("imageBuildVersionArn", image.path("arn").asText());
        });
    }

    /** Runs a pipeline whose schedule fired; the start condition may skip a run with no updated dependencies. */
    void startScheduled(ObjectNode pipeline, String region) {
        String condition = pipeline.path("schedule").path("pipelineExecutionStartCondition").asText(
                "EXPRESSION_MATCH_AND_DEPENDENCY_UPDATES_AVAILABLE");
        String fingerprint = dependencies(pipeline, region);
        ObjectNode current = service.require(pipeline.path("arn").asText(), "image-pipeline", region);
        current.put("dateLastRun", now());
        boolean changed = !fingerprint.equals(meta(current).path("dependencies").asText(""));
        meta(current).put("dependencies", fingerprint);
        store.put(key(region, meta(current).path("resource").asText()), current);
        if ("EXPRESSION_MATCH_ONLY".equals(condition) || changed) {
            fromPipeline(current, region, "SCHEDULED", null);
        }
    }

    /** What a pipeline's build depends on: its parent AMI and the component builds it resolves to. */
    private String dependencies(ObjectNode pipeline, String region) {
        StringBuilder out = new StringBuilder();
        boolean container = pipeline.hasNonNull("containerRecipeArn");
        ObjectNode recipe = container ? service.require(pipeline.get("containerRecipeArn").asText(),
                "container-recipe", region) : service.require(pipeline.path("imageRecipeArn").asText(),
                "image-recipe", region);
        out.append(container ? recipe.path("parentImage").asText() : engine.parentAmi(recipe.path("parentImage")
                .asText(), region));
        for (JsonNode reference : recipe.path("components")) {
            out.append('|').append(service.component(reference.path("componentArn").asText(), region).path("arn")
                    .asText());
        }
        return out.toString();
    }

    private ObjectNode fromPipeline(ObjectNode pipeline, String region, String buildType, JsonNode tags) {
        if (!"ENABLED".equals(pipeline.path("status").asText()) && "SCHEDULED".equals(buildType)) {
            throw invalidRequest("The image pipeline " + pipeline.path("arn").asText() + " is disabled.");
        }
        ObjectNode input = object();
        for (String field : List.of("imageRecipeArn", "containerRecipeArn", "infrastructureConfigurationArn",
                "distributionConfigurationArn", "imageTestsConfiguration", "enhancedImageMetadataEnabled",
                "imageScanningConfiguration", "workflows", "executionRole")) {
            if (pipeline.has(field)) {
                input.set(field, pipeline.get(field).deepCopy());
            }
        }
        if (pipeline.path("loggingConfiguration").hasNonNull("imageLogGroupName")) {
            input.putObject("loggingConfiguration").put("logGroupName", pipeline.path("loggingConfiguration")
                    .path("imageLogGroupName").asText());
        }
        ObjectNode imageTags = pipeline.has("imageTags") ? (ObjectNode) pipeline.get("imageTags").deepCopy()
                : object();
        if (tags != null && tags.isObject()) {
            imageTags.setAll((ObjectNode) tags);
        }
        input.set("tags", imageTags);
        ObjectNode image = newImage(input, region, pipeline, buildType);
        ObjectNode current = service.require(pipeline.path("arn").asText(), "image-pipeline", region);
        current.put("dateLastRun", now());
        store.put(key(region, meta(current).path("resource").asText()), current);
        return image;
    }

    /** Stores a new build version of an image and starts building it. */
    private ObjectNode newImage(JsonNode input, String region, ObjectNode pipeline, String buildType) {
        boolean container = input.hasNonNull("containerRecipeArn");
        if (container == input.hasNonNull("imageRecipeArn")) {
            throw invalidParameter("Specify exactly one of imageRecipeArn and containerRecipeArn.");
        }
        ObjectNode recipe = container ? service.require(input.get("containerRecipeArn").asText(), "container-recipe",
                region) : service.require(input.get("imageRecipeArn").asText(), "image-recipe", region);
        ObjectNode infrastructure = service.require(input.path("infrastructureConfigurationArn").asText(),
                "infrastructure-configuration", region);
        ObjectNode distribution = input.hasNonNull("distributionConfigurationArn")
                ? service.require(input.get("distributionConfigurationArn").asText(), "distribution-configuration",
                region) : null;
        String name = recipe.path("name").asText();
        String version = recipe.path("version").asText();
        int build = 1;
        for (ObjectNode existing : store.list(key(region, "image/" + arnName(name) + "/" + version + "/"))) {
            build = Math.max(build, Integer.parseInt(existing.path("version").asText().split("/")[1]) + 1);
        }
        String resource = "image/" + arnName(name) + "/" + version + "/" + build;
        ObjectNode image = store.record("image", resource);
        image.put("arn", store.arn(region, resource)).put("type", container ? "DOCKER" : "AMI").put("name", name)
                .put("version", version + "/" + build).put("platform", recipe.path("platform").asText("Linux"))
                .put("enhancedImageMetadataEnabled", input.path("enhancedImageMetadataEnabled").asBoolean(true));
        String osVersion = osVersion(recipe, region);
        if (osVersion != null) {
            image.put("osVersion", osVersion);
        }
        image.putObject("state").put("status", "PENDING");
        image.set(container ? "containerRecipe" : "imageRecipe", store.view(recipe));
        if (pipeline != null) {
            image.put("sourcePipelineName", pipeline.path("name").asText())
                    .put("sourcePipelineArn", pipeline.path("arn").asText());
        }
        image.set("infrastructureConfiguration", store.view(infrastructure));
        if (distribution != null) {
            image.set("distributionConfiguration", store.view(distribution));
        }
        ObjectNode tests = object();
        tests.put("imageTestsEnabled", input.path("imageTestsConfiguration").path("imageTestsEnabled")
                .asBoolean(true));
        tests.put("timeoutMinutes", input.path("imageTestsConfiguration").path("timeoutMinutes").asInt(720));
        image.set("imageTestsConfiguration", tests);
        image.put("dateCreated", now());
        image.putObject("outputResources").putArray("amis");
        image.set("tags", store.tags(input.path("tags"), null));
        image.put("buildType", buildType).put("imageSource", "CUSTOM");
        ObjectNode scanning = input.has("imageScanningConfiguration")
                ? (ObjectNode) input.get("imageScanningConfiguration").deepCopy() : object();
        if (!scanning.has("imageScanningEnabled")) {
            scanning.put("imageScanningEnabled", false);
        }
        image.set("imageScanningConfiguration", scanning);
        if (input.hasNonNull("executionRole")) {
            image.put("executionRole", input.get("executionRole").asText());
        }
        if (input.has("workflows")) {
            image.set("workflows", service.workflows(input.get("workflows"), region));
        }
        if (input.has("loggingConfiguration")) {
            image.set("loggingConfiguration", input.get("loggingConfiguration").deepCopy());
        }
        store.put(key(region, resource), image);
        engine.start(store.account(), region, resource);
        return image;
    }

    private static String osVersion(ObjectNode recipe, String region) {
        String parent = recipe.path("parentImage").asText("");
        if (parent.contains(":image/")) {
            String name = parent.substring(parent.indexOf(":image/") + ":image/".length()).split("/")[0];
            ImageBuilderManaged.ManagedImage managed = ImageBuilderManaged.image(name);
            if (managed != null) {
                return managed.osVersion();
            }
        }
        return recipe.path("imageOsVersionOverride").asText(null);
    }

    // ---------------------------------------------------------------- reading images

    public ObjectNode getImage(JsonNode input, String region) {
        ObjectNode image = image(input.path("imageBuildVersionArn").asText(), region);
        ObjectNode response = response();
        response.set("image", store.view(image));
        return response;
    }

    public ObjectNode listImages(JsonNode input, String region) {
        String owner = input.path("owner").asText("Self");
        List<ObjectNode> versions = new ArrayList<>();
        if ("Amazon".equals(owner)) {
            for (ImageBuilderManaged.ManagedImage managed : ImageBuilderManaged.IMAGES) {
                ObjectNode version = object();
                version.put("arn", ImageBuilderStore.arn(ImageBuilderManaged.OWNER_ACCOUNT, region, "image/"
                                + managed.name() + "/" + ImageBuilderManaged.DATE_VERSION)).put("name", managed.name())
                        .put("type", "AMI").put("version", ImageBuilderManaged.DATE_VERSION)
                        .put("platform", managed.platform()).put("osVersion", managed.osVersion()).put("owner",
                                "Amazon").put("dateCreated", "2026-11-01T00:00:00.000Z").put("buildType", "IMPORT")
                        .put("imageSource", "AMAZON_MANAGED");
                if (passes(version, input.path("filters"))) {
                    versions.add(version);
                }
            }
            return store.page(versions, input, "imageVersionList");
        }
        if (!"Self".equals(owner)) {
            return store.page(versions, input, "imageVersionList");
        }
        Map<String, ObjectNode> byVersion = new LinkedHashMap<>();
        for (ObjectNode image : store.list(key(region, "image/"))) {
            String status = image.path("state").path("status").asText();
            if ("DEPRECATED".equals(status) && !input.path("includeDeprecated").asBoolean(false)
                    || "DELETED".equals(status)) {
                continue;
            }
            String[] version = image.path("version").asText().split("/");
            ObjectNode summary = object();
            summary.put("arn", image.path("arn").asText().replaceAll("/[^/]+$", "")).put("name",
                    image.path("name").asText()).put("type", image.path("type").asText()).put("version", version[0])
                    .put("platform", image.path("platform").asText()).put("owner", store.account())
                    .put("dateCreated", image.path("dateCreated").asText()).put("buildType",
                            image.path("buildType").asText()).put("imageSource", image.path("imageSource").asText());
            if (image.has("osVersion")) {
                summary.put("osVersion", image.get("osVersion").asText());
            }
            if (!passes(summary, input.path("filters"))) {
                continue;
            }
            String versionKey = input.path("byName").asBoolean(false) ? image.path("name").asText()
                    : image.path("name").asText() + "/" + version[0];
            ObjectNode existing = byVersion.get(versionKey);
            if (existing == null || ImageBuilderRefs.compare(version[0], existing.path("version").asText()) > 0) {
                byVersion.put(versionKey, summary);
            }
        }
        versions.addAll(byVersion.values());
        return store.page(versions, input, "imageVersionList");
    }

    private static boolean passes(ObjectNode record, JsonNode filters) {
        for (String field : List.of("name", "platform", "osVersion", "type", "version", "buildType", "imageSource")) {
            List<String> values = filter(filters, field);
            if (values != null && !values.contains(record.path(field).asText())) {
                return false;
            }
        }
        return true;
    }

    private ObjectNode summary(ObjectNode image) {
        ObjectNode summary = object();
        for (String field : List.of("arn", "name", "type", "version", "platform", "osVersion", "state", "dateCreated",
                "outputResources", "tags", "buildType", "imageSource", "deprecationTime", "lifecycleExecutionId",
                "loggingConfiguration")) {
            if (image.has(field)) {
                summary.set(field, image.get(field));
            }
        }
        summary.put("owner", store.account());
        return summary;
    }

    public ObjectNode listImageBuildVersions(JsonNode input, String region) {
        String arn = input.path("imageVersionArn").asText();
        ImageBuilderRefs.Ref ref = ImageBuilderRefs.parse(arn, "image");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode image : store.list(key(region, "image/" + ref.name() + "/"))) {
            String[] version = image.path("version").asText().split("/");
            if (ImageBuilderRefs.matches(ref.version(), version[0]) && passes(summary(image), input.path("filters"))) {
                items.add(summary(image));
            }
        }
        return store.page(items, input, "imageSummaryList");
    }

    public ObjectNode listImagePipelineImages(JsonNode input, String region) {
        ObjectNode pipeline = service.require(input.path("imagePipelineArn").asText(), "image-pipeline", region);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode image : store.list(key(region, "image/"))) {
            if (pipeline.path("arn").asText().equals(image.path("sourcePipelineArn").asText())
                    && passes(summary(image), input.path("filters"))) {
                items.add(summary(image));
            }
        }
        return store.page(items, input, "imageSummaryList");
    }

    public ObjectNode listImagePackages(JsonNode input, String region) {
        ObjectNode image = image(input.path("imageBuildVersionArn").asText(), region);
        if (!"AVAILABLE".equals(image.path("state").path("status").asText())) {
            throw invalidRequest("The image " + image.path("arn").asText() + " is not AVAILABLE; packages are listed"
                    + " for available images.");
        }
        if (!"AMI".equals(image.path("type").asText())) {
            throw invalidRequest("Packages are listed for AMI images.");
        }
        List<ObjectNode> packages = new ArrayList<>();
        meta(image).path("packages").forEach(p -> packages.add((ObjectNode) p));
        return store.page(packages, input, "imagePackageList");
    }

    // ---------------------------------------------------------------- changing builds

    public ObjectNode deleteImage(JsonNode input, String region) {
        ObjectNode image = image(input.path("imageBuildVersionArn").asText(), region);
        String status = image.path("state").path("status").asText();
        if (!ImageBuilderEngine.terminal(status) || engine.running(store.account(), meta(image).path("resource")
                .asText())) {
            throw invalidRequest("The image " + image.path("arn").asText() + " is " + status
                    + " and cannot be deleted until it finishes.");
        }
        String imageArn = image.path("arn").asText();
        for (ObjectNode execution : store.list(key(region, "workflow-execution/"))) {
            if (imageArn.equals(execution.path("imageBuildVersionArn").asText())) {
                store.delete(key(region, meta(execution).path("resource").asText()));
            }
        }
        for (ObjectNode step : store.list(key(region, "workflow-step/"))) {
            if (imageArn.equals(step.path("imageBuildVersionArn").asText())) {
                store.delete(key(region, meta(step).path("resource").asText()));
            }
        }
        store.delete(key(region, meta(image).path("resource").asText()));
        return response().put("imageBuildVersionArn", imageArn);
    }

    public ObjectNode cancelImageCreation(JsonNode input, String region) {
        return idempotent(store, region, "CancelImageCreation", input, () -> {
            ObjectNode image = image(input.path("imageBuildVersionArn").asText(), region);
            String status = image.path("state").path("status").asText();
            if (ImageBuilderEngine.terminal(status)) {
                throw new AwsException("ResourceInUseException", "The image " + image.path("arn").asText() + " is "
                        + status + " and cannot be cancelled.", 400);
            }
            engine.cancel(store.account(), meta(image).path("resource").asText());
            return response().put("clientToken", input.path("clientToken").asText())
                    .put("imageBuildVersionArn", image.path("arn").asText());
        });
    }

    public ObjectNode retryImage(JsonNode input, String region) {
        return idempotent(store, region, "RetryImage", input, () -> {
            ObjectNode image = image(input.path("imageBuildVersionArn").asText(), region);
            if (!"FAILED".equals(image.path("state").path("status").asText())) {
                throw invalidRequest("Only FAILED images can be retried.");
            }
            String imageArn = image.path("arn").asText();
            for (ObjectNode execution : store.list(key(region, "workflow-execution/"))) {
                if (imageArn.equals(execution.path("imageBuildVersionArn").asText())) {
                    execution.put("retried", true);
                    store.put(key(region, meta(execution).path("resource").asText()), execution);
                }
            }
            image.putObject("state").put("status", "PENDING");
            image.putObject("outputResources").putArray("amis");
            store.put(key(region, meta(image).path("resource").asText()), image);
            engine.start(store.account(), region, meta(image).path("resource").asText());
            return object().put("clientToken", input.path("clientToken").asText()).put("imageBuildVersionArn",
                    imageArn);
        });
    }

    /** Imports a VM image: an image whose AMI is the one an EC2 VM import task produced. */
    public ObjectNode importVmImage(JsonNode input, String region) {
        return idempotent(store, region, "ImportVmImage", input, () -> {
            String task = input.path("vmImportTaskId").asText();
            List<Image> found = ec2.get().describeImages(region, null, null, Map.of("tag:ImportTaskId", List.of(task)));
            String amiId = found.isEmpty() ? task.startsWith("ami-") ? task : null : found.get(0).getImageId();
            if (amiId == null) {
                throw invalidParameter("The VM import task " + task + " produced no AMI.");
            }
            ObjectNode image = imported(input, region, "IMPORT", amiId);
            return response().put("clientToken", input.path("clientToken").asText()).put("imageArn",
                    image.path("arn").asText());
        });
    }

    public ObjectNode importDiskImage(JsonNode input, String region) {
        return idempotent(store, region, "ImportDiskImage", input, () -> {
            service.require(input.path("infrastructureConfigurationArn").asText(), "infrastructure-configuration",
                    region);
            ObjectNode image = imported(input, region, "IMPORT_ISO", null);
            image.putObject("state").put("status", "FAILED").put("reason",
                    "Floci cannot convert ISO disk images to AMIs.");
            store.put(key(region, meta(image).path("resource").asText()), image);
            return object().put("clientToken", input.path("clientToken").asText()).put("imageBuildVersionArn",
                    image.path("arn").asText());
        });
    }

    private ObjectNode imported(JsonNode input, String region, String buildType, String amiId) {
        String name = input.path("name").asText();
        String version = ImageBuilderRefs.semanticVersion(input.path("semanticVersion").asText(null));
        int build = 1;
        for (ObjectNode existing : store.list(key(region, "image/" + arnName(name) + "/" + version + "/"))) {
            build = Math.max(build, Integer.parseInt(existing.path("version").asText().split("/")[1]) + 1);
        }
        String resource = "image/" + arnName(name) + "/" + version + "/" + build;
        ObjectNode image = store.record("image", resource);
        image.put("arn", store.arn(region, resource)).put("type", "AMI").put("name", name)
                .put("version", version + "/" + build).put("platform", input.path("platform").asText("Linux"))
                .put("dateCreated", now()).put("buildType", buildType).put("imageSource", "IMPORTED");
        if (input.hasNonNull("osVersion")) {
            image.put("osVersion", input.get("osVersion").asText());
        }
        image.putObject("state").put("status", "AVAILABLE");
        ArrayNode amis = image.putObject("outputResources").putArray("amis");
        if (amiId != null) {
            ObjectNode ami = amis.addObject().put("region", region).put("image", amiId).put("name", name)
                    .put("accountId", store.account());
            ami.putObject("state").put("status", "AVAILABLE");
        }
        image.set("tags", store.tags(input.path("tags"), null));
        if (input.has("loggingConfiguration")) {
            image.set("loggingConfiguration", input.get("loggingConfiguration").deepCopy());
        }
        store.put(key(region, resource), image);
        return image;
    }

    /** Distributes an existing AMI with a distribution configuration: a distribution-only image build. */
    public ObjectNode distributeImage(JsonNode input, String region) {
        return idempotent(store, region, "DistributeImage", input, () -> {
            ObjectNode distribution = service.require(input.path("distributionConfigurationArn").asText(),
                    "distribution-configuration", region);
            String source = input.path("sourceImage").asText();
            String amiId = source.startsWith("ami-") ? source : null;
            String name = "distribute-" + (amiId == null ? "image" : amiId);
            if (amiId == null) {
                ObjectNode sourceImage = image(source, region);
                amiId = sourceImage.path("outputResources").path("amis").path(0).path("image").asText(null);
                name = sourceImage.path("name").asText();
                if (amiId == null) {
                    throw invalidParameter("The source image " + source + " has no AMI.");
                }
            }
            ObjectNode request = object().put("name", name).put("semanticVersion", "1.0.0");
            request.set("tags", input.path("tags"));
            ObjectNode image = imported(request, region, "USER_INITIATED", null);
            image.set("distributionConfiguration", store.view(distribution));
            image.put("imageSource", "CUSTOM").putObject("state").put("status", "PENDING");
            image.put("executionRole", input.path("executionRole").asText());
            image.putObject("imageTestsConfiguration").put("imageTestsEnabled", false).put("timeoutMinutes", 60);
            meta(image).put("buildAmi", amiId).put("distributeOnly", true);
            image.putObject("infrastructureConfiguration");
            image.putArray("workflows");
            store.put(key(region, meta(image).path("resource").asText()), image);
            engine.start(store.account(), region, meta(image).path("resource").asText());
            return object().put("clientToken", input.path("clientToken").asText()).put("imageBuildVersionArn",
                    image.path("arn").asText());
        });
    }

    // ---------------------------------------------------------------- workflow executions

    public ObjectNode listWorkflowExecutions(JsonNode input, String region) {
        ObjectNode image = image(input.path("imageBuildVersionArn").asText(), region);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode execution : store.list(key(region, "workflow-execution/"))) {
            if (image.path("arn").asText().equals(execution.path("imageBuildVersionArn").asText())) {
                ObjectNode metadata = store.view(execution);
                metadata.remove("imageBuildVersionArn");
                items.add(metadata);
            }
        }
        ObjectNode response = store.page(items, input, "workflowExecutions");
        response.put("imageBuildVersionArn", image.path("arn").asText());
        if (image.path("state").has("reason")) {
            response.put("message", image.path("state").path("reason").asText());
        }
        return response;
    }

    private ObjectNode execution(String id, String region) {
        return store.get(key(region, "workflow-execution/" + id)).orElseThrow(() ->
                notFound("The workflow execution " + id + " does not exist."));
    }

    public ObjectNode getWorkflowExecution(JsonNode input, String region) {
        ObjectNode response = response();
        response.setAll(store.view(execution(input.path("workflowExecutionId").asText(), region)));
        response.remove("retried");
        return response;
    }

    public ObjectNode listWorkflowStepExecutions(JsonNode input, String region) {
        ObjectNode execution = execution(input.path("workflowExecutionId").asText(), region);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode step : store.list(key(region, "workflow-step/"))) {
            if (execution.path("workflowExecutionId").asText().equals(step.path("workflowExecutionId").asText())) {
                ObjectNode metadata = object();
                for (String field : List.of("stepExecutionId", "name", "description", "action", "status",
                        "rollbackStatus", "message", "inputs", "outputs", "startTime", "endTime", "attemptNumber",
                        "maxAttempts")) {
                    if (step.has(field)) {
                        metadata.set(field, step.get(field));
                    }
                }
                items.add(metadata);
            }
        }
        ObjectNode response = store.page(items, input, "steps");
        response.put("workflowBuildVersionArn", execution.path("workflowBuildVersionArn").asText())
                .put("workflowExecutionId", execution.path("workflowExecutionId").asText())
                .put("imageBuildVersionArn", execution.path("imageBuildVersionArn").asText());
        if (execution.has("message")) {
            response.put("message", execution.get("message").asText());
        }
        return response;
    }

    public ObjectNode getWorkflowStepExecution(JsonNode input, String region) {
        String id = input.path("stepExecutionId").asText();
        ObjectNode step = store.get(key(region, "workflow-step/" + id)).orElseThrow(() ->
                notFound("The workflow step execution " + id + " does not exist."));
        ObjectNode response = response();
        response.setAll(store.view(step));
        return response;
    }

    public ObjectNode listWaitingWorkflowSteps(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode step : store.list(key(region, "workflow-step/"))) {
            if ("WAITING".equals(step.path("status").asText())) {
                ObjectNode waiting = object();
                for (String field : List.of("stepExecutionId", "imageBuildVersionArn", "workflowExecutionId",
                        "workflowBuildVersionArn", "name", "action", "startTime")) {
                    waiting.set(field, step.get(field));
                }
                items.add(waiting);
            }
        }
        ObjectNode response = store.page(items, input, "steps");
        response.remove("requestId");
        return response;
    }

    public ObjectNode sendWorkflowStepAction(JsonNode input, String region) {
        return idempotent(store, region, "SendWorkflowStepAction", input, () -> {
            String id = input.path("stepExecutionId").asText();
            ObjectNode step = store.get(key(region, "workflow-step/" + id)).orElseThrow(() ->
                    notFound("The workflow step execution " + id + " does not exist."));
            if (!step.path("imageBuildVersionArn").asText().equals(input.path("imageBuildVersionArn").asText())) {
                throw invalidParameter("The step " + id + " does not belong to that image.");
            }
            String action = input.path("action").asText();
            if (!"RESUME".equals(action) && !"STOP".equals(action)) {
                throw invalidParameter("action is RESUME or STOP.");
            }
            if (!"WAITING".equals(step.path("status").asText()) || !engine.answer(id, action,
                    input.path("reason").asText(null))) {
                throw new AwsException("ResourceInUseException", "The step " + id + " is not waiting.", 400);
            }
            return object().put("stepExecutionId", id).put("imageBuildVersionArn", step.path("imageBuildVersionArn")
                    .asText()).put("clientToken", input.path("clientToken").asText());
        });
    }

    // ---------------------------------------------------------------- scan findings

    /** Collects Inspector's findings for an image's test instance into the image's scan findings. */
    void collectFindings(ImageBuilderEngine.Build build, String instanceId) {
        ObjectNode image = store.get(build.account, key(build.region, build.imageKey)).orElse(null);
        if (image == null) {
            return;
        }
        ArrayNode findings = meta(image).putArray("findings");
        try {
            ObjectNode request = object();
            request.putObject("filterCriteria").putArray("resourceId").addObject().put("comparison", "EQUALS")
                    .put("value", instanceId);
            Map<String, Object> listed = inspector.get().listFindings(build.region, build.account, request);
            JsonNode found = store.mapper().valueToTree(listed.getOrDefault("findings", List.of()));
            for (JsonNode finding : found) {
                ObjectNode scan = findings.addObject();
                scan.put("awsAccountId", build.account).put("imageBuildVersionArn", image.path("arn").asText())
                        .put("type", finding.path("type").asText("PACKAGE_VULNERABILITY"))
                        .put("description", finding.path("description").asText(""))
                        .put("title", finding.path("title").asText(""))
                        .put("severity", finding.path("severity").asText("INFORMATIONAL"))
                        .put("firstObservedAt", finding.path("firstObservedAt").asText(now()))
                        .put("updatedAt", finding.path("updatedAt").asText(now()))
                        .put("fixAvailable", finding.path("fixAvailable").asText("NO"));
                if (image.hasNonNull("sourcePipelineArn")) {
                    scan.put("imagePipelineArn", image.get("sourcePipelineArn").asText());
                }
                if (finding.has("inspectorScore")) {
                    scan.set("inspectorScore", finding.get("inspectorScore"));
                }
                if (finding.has("packageVulnerabilityDetails")) {
                    scan.set("packageVulnerabilityDetails", finding.get("packageVulnerabilityDetails"));
                }
                if (finding.has("remediation")) {
                    scan.set("remediation", finding.get("remediation"));
                }
            }
            image.putObject("scanState").put("status", "COMPLETED");
        } catch (RuntimeException e) {
            image.putObject("scanState").put("status", "ABANDONED").put("reason", e.getMessage());
        }
        store.put(build.account, key(build.region, build.imageKey), image);
    }

    private List<ObjectNode> findings(String region) {
        List<ObjectNode> findings = new ArrayList<>();
        for (ObjectNode image : store.list(key(region, "image/"))) {
            meta(image).path("findings").forEach(finding -> findings.add((ObjectNode) finding));
        }
        return findings;
    }

    public ObjectNode listImageScanFindings(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode finding : findings(region)) {
            boolean keep = true;
            for (JsonNode filter : input.path("filters")) {
                String field = switch (filter.path("name").asText()) {
                    case "imageBuildVersionArn" -> "imageBuildVersionArn";
                    case "imagePipelineArn" -> "imagePipelineArn";
                    case "severity" -> "severity";
                    case "fixAvailable" -> "fixAvailable";
                    case "vulnerabilityId" -> "title";
                    default -> null;
                };
                if (field == null) {
                    continue;
                }
                boolean any = false;
                for (JsonNode value : filter.path("values")) {
                    any |= value.asText().equals(finding.path(field).asText());
                }
                keep &= any;
            }
            if (keep) {
                items.add(finding);
            }
        }
        return store.page(items, input, "findings");
    }

    public ObjectNode listImageScanFindingAggregations(JsonNode input, String region) {
        String name = input.path("filter").path("name").asText("");
        String value = input.path("filter").path("values").path(0).asText("");
        Map<String, Map<String, Integer>> groups = new LinkedHashMap<>();
        String type = switch (name) {
            case "imageBuildVersionArn" -> "imageBuildVersionArn";
            case "imagePipelineArn" -> "imagePipelineArn";
            case "vulnerabilityId" -> "vulnerabilityId";
            default -> "accountId";
        };
        for (ObjectNode finding : findings(region)) {
            if ("imageBuildVersionArn".equals(name) && !value.equals(finding.path("imageBuildVersionArn").asText())
                    || "imagePipelineArn".equals(name) && !value.equals(finding.path("imagePipelineArn").asText())) {
                continue;
            }
            String group = switch (type) {
                case "imageBuildVersionArn" -> finding.path("imageBuildVersionArn").asText();
                case "imagePipelineArn" -> finding.path("imagePipelineArn").asText();
                case "vulnerabilityId" -> finding.path("title").asText();
                default -> finding.path("awsAccountId").asText();
            };
            groups.computeIfAbsent(group, ignored -> new LinkedHashMap<>())
                    .merge(finding.path("severity").asText(), 1, Integer::sum);
        }
        ArrayNode responses = store.mapper().createArrayNode();
        groups.forEach((group, counts) -> {
            ObjectNode aggregation = responses.addObject();
            ObjectNode severity = object();
            for (String level : List.of("critical", "high", "medium")) {
                severity.put(level, counts.getOrDefault(level.toUpperCase(), 0));
            }
            severity.put("all", counts.values().stream().mapToInt(Integer::intValue).sum());
            String field = switch (type) {
                case "imageBuildVersionArn" -> "imageAggregation";
                case "imagePipelineArn" -> "imagePipelineAggregation";
                case "vulnerabilityId" -> "vulnerabilityIdAggregation";
                default -> "accountAggregation";
            };
            ObjectNode inner = aggregation.putObject(field);
            inner.put("vulnerabilityId".equals(type) ? "vulnerabilityId" : "accountAggregation".equals(field)
                    ? "accountId" : "imageBuildVersionArn".equals(type) ? "imageBuildVersionArn" : "imagePipelineArn",
                    group);
            inner.set("severityCounts", severity);
        });
        ObjectNode response = response().put("aggregationType", type);
        response.set("responses", responses);
        return response;
    }
}
