package io.floci.aws.services.imagebuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.ec2.Ec2ImageCatalog;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Image;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.sns.SnsService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.aws.services.imagebuilder.ImageBuilderStore.key;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.meta;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.now;

/**
 * Runs image builds as Image Builder does: a build, test and distribution stage, each a workflow document (the
 * image's own, or Amazon's managed {@code build-image}, {@code test-image}, {@code build-container},
 * {@code test-container}), interpreted step by step with its {@code if} conditions, JSONPath inputs, {@code {{ }}}
 * interpolation, retries, timeouts, {@code onFailure} and rollback. Each step and workflow is recorded as
 * {@code GetWorkflowExecution} and {@code GetWorkflowStepExecution} report it; the image moves through
 * {@code BUILDING}, {@code TESTING}, {@code DISTRIBUTING}, {@code INTEGRATING} and {@code AVAILABLE} with an
 * {@code EC2 Image Builder Image State Change} event at each, and a notification on the infrastructure
 * configuration's SNS topic when it ends. The actions are {@link ImageBuilderActions}'s. Image pipelines with a
 * schedule are started when it fires.
 */
@ApplicationScoped
public class ImageBuilderEngine implements Resettable {

    private static final Logger LOG = Logger.getLogger(ImageBuilderEngine.class);
    private static final Set<String> TERMINAL = Set.of("AVAILABLE", "CANCELLED", "FAILED", "DEPRECATED", "DISABLED",
            "DELETED");
    private static final Pattern INTERPOLATION = Pattern.compile("\\{\\{\\s*(\\$\\.[^}]+?)\\s*}}");

    /** A running build: its key, account and Region, and whether it was cancelled. */
    static final class Build {
        final String account;
        final String region;
        final String imageKey;
        volatile boolean cancelled;
        volatile Future<?> future;
        final List<String> instances = new ArrayList<>();

        Build(String account, String region, String imageKey) {
            this.account = account;
            this.region = region;
            this.imageKey = imageKey;
        }
    }

    /** A paused WaitForAction step's answer. */
    record Answer(String action, String reason) {
    }

    private final ImageBuilderStore store;
    private final Instance<ImageBuilderActions> actions;
    private final Instance<Ec2Service> ec2;
    private final Instance<Ec2ImageCatalog> catalog;
    private final Instance<EventBridgeService> events;
    private final Instance<SnsService> sns;
    private final Instance<CloudWatchLogsService> logs;
    private final Map<String, Build> builds = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Answer>> waiting = new ConcurrentHashMap<>();
    private final ExecutorService runner = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "imagebuilder-build");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "imagebuilder-schedule");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean scheduling;

    @Inject
    public ImageBuilderEngine(ImageBuilderStore store, Instance<ImageBuilderActions> actions,
                              Instance<Ec2Service> ec2, Instance<Ec2ImageCatalog> catalog,
                              Instance<EventBridgeService> events, Instance<SnsService> sns,
                              Instance<CloudWatchLogsService> logs) {
        this.store = store;
        this.actions = actions;
        this.ec2 = ec2;
        this.catalog = catalog;
        this.events = events;
        this.sns = sns;
        this.logs = logs;
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
        scheduler.shutdownNow();
    }

    @Override
    public void clear() {
        builds.values().forEach(build -> build.cancelled = true);
        builds.clear();
        waiting.clear();
    }

    ImageBuilderStore store() {
        return store;
    }

    // ---------------------------------------------------------------- parent images

    /** The platform of a recipe's parent image: Windows for Windows AMIs, Linux otherwise. */
    String parentPlatform(String parent, String region) {
        try {
            String ami = parentAmi(parent, region);
            List<Image> images = ec2.get().describeImages(region, List.of(ami), List.of());
            return !images.isEmpty() && "windows".equalsIgnoreCase(images.get(0).getPlatform()) ? "Windows" : "Linux";
        } catch (RuntimeException e) {
            return "Linux";
        }
    }

    /**
     * The AMI a parent image names: a managed image ARN ({@code image/amazon-linux-2023-x86/x.x.x}), an image
     * of this account (its output AMI in the Region), an AMI ID, or an {@code ssm:} parameter.
     */
    String parentAmi(String parent, String region) {
        if (parent == null || parent.isEmpty()) {
            throw ImageBuilderStore.invalidParameter("parentImage is required.");
        }
        if (parent.startsWith("ami-")) {
            return parent;
        }
        if (parent.startsWith("ssm:")) {
            String parameter = parent.substring("ssm:".length());
            return catalog.get().findByPublicParameterName(parameter).map(image -> image.imageId)
                    .orElseThrow(() -> ImageBuilderStore.invalidParameter("The SSM parameter " + parameter
                            + " names no AMI Floci knows."));
        }
        ImageBuilderRefs.Ref ref = ImageBuilderRefs.parse(parent, "image");
        if (ref.amazon()) {
            ImageBuilderManaged.ManagedImage managed = ImageBuilderManaged.image(ref.name());
            if (managed == null) {
                throw ImageBuilderStore.invalidParameter("The parent image " + parent + " does not exist.");
            }
            return managed.amiId();
        }
        ObjectNode image = latestImage(ref, region);
        for (JsonNode ami : image.path("outputResources").path("amis")) {
            if (region.equals(ami.path("region").asText())) {
                return ami.path("image").asText();
            }
        }
        throw ImageBuilderStore.invalidParameter("The parent image " + parent + " has no AMI in " + region + ".");
    }

    /** The newest AVAILABLE build of an image version ARN of this account. */
    ObjectNode latestImage(ImageBuilderRefs.Ref ref, String region) {
        ObjectNode best = null;
        for (ObjectNode image : store.list(ref.account(), key(region, "image/" + ref.name() + "/"))) {
            String[] version = image.path("version").asText().split("/");
            if (!ImageBuilderRefs.matches(ref.version(), version[0])
                    || ref.build() != null && !ref.build().equals(version[1])
                    || !"AVAILABLE".equals(image.path("state").path("status").asText())) {
                continue;
            }
            if (best == null || ImageBuilderRefs.compare(version[0], best.path("version").asText().split("/")[0]) > 0
                    || version[0].equals(best.path("version").asText().split("/")[0])
                    && Integer.parseInt(version[1]) > Integer.parseInt(best.path("version").asText().split("/")[1])) {
                best = image;
            }
        }
        if (best == null) {
            throw ImageBuilderStore.invalidParameter("No available image matches " + ref.name() + "/"
                    + ref.version() + ".");
        }
        return best;
    }

    // ---------------------------------------------------------------- starting and stopping builds

    /** Starts building an image record already stored as PENDING. */
    void start(String account, String region, String imageKey) {
        Build build = new Build(account, region, imageKey);
        builds.put(account + "|" + imageKey, build);
        build.future = runner.submit(() -> RequestScopes.runAs(account, region, () -> run(build)));
    }

    boolean running(String account, String imageKey) {
        return builds.containsKey(account + "|" + imageKey);
    }

    /** Cancels a running build: its running or waiting step is cancelled and the image becomes CANCELLED. */
    void cancel(String account, String imageKey) {
        Build build = builds.get(account + "|" + imageKey);
        if (build != null) {
            build.cancelled = true;
            waiting.forEach((stepId, answer) -> {
                ObjectNode step = store.get(account, key(build.region, "workflow-step/" + stepId)).orElse(null);
                if (step != null && imageKey.endsWith(meta(step).path("image").asText("\u0000"))) {
                    answer.complete(new Answer("STOP", "The image was cancelled."));
                }
            });
        }
    }

    /** Answers a waiting step: RESUME or STOP. */
    boolean answer(String stepExecutionId, String action, String reason) {
        CompletableFuture<Answer> pending = waiting.remove(stepExecutionId);
        if (pending == null) {
            return false;
        }
        pending.complete(new Answer(action, reason));
        return true;
    }

    private ObjectNode image(Build build) {
        return store.get(build.account, key(build.region, build.imageKey)).orElse(null);
    }

    private void saveImage(Build build, ObjectNode image) {
        store.put(build.account, key(build.region, build.imageKey), image);
    }

    /** Moves an image to a status, with its state change event. */
    void setStatus(Build build, String status, String reason) {
        ObjectNode image = image(build);
        if (image == null) {
            return;
        }
        String previous = image.path("state").path("status").asText();
        ObjectNode state = image.putObject("state").put("status", status);
        if (reason != null) {
            state.put("reason", reason);
        }
        saveImage(build, image);
        if (!status.equals(previous)) {
            ObjectNode detail = store.mapper().createObjectNode();
            detail.putObject("previous-state").put("status", previous);
            detail.putObject("state").put("status", status);
            publishEvent(build, "EC2 Image Builder Image State Change", detail, image.path("arn").asText());
        }
    }

    void publishEvent(Build build, String detailType, ObjectNode detail, String resource) {
        try {
            events.get().putEvents(List.of(EventBridgeService.serviceEventEntry("aws.imagebuilder", detailType,
                    detail.toString(), resource)), build.region, build.account);
        } catch (RuntimeException e) {
            LOG.debugv("Could not publish the Image Builder {0} event: {1}", detailType, e.getMessage());
        }
    }

    /** Writes a line to the image's CloudWatch Logs stream, {@code /aws/imagebuilder/<name>} {@code <version>}. */
    void log(Build build, String line) {
        ObjectNode image = image(build);
        if (image == null) {
            return;
        }
        String group = image.path("loggingConfiguration").path("logGroupName").asText("/aws/imagebuilder/"
                + image.path("name").asText());
        String stream = image.path("version").asText();
        try {
            CloudWatchLogsService service = logs.get();
            if (!service.logGroupExists(group, build.region)) {
                service.createLogGroup(group, null, null, build.region);
            }
            try {
                service.createLogStream(group, stream, build.region);
            } catch (AwsException e) {
                if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                    throw e;
                }
            }
            service.putLogEvents(group, stream, List.of(Map.of("timestamp", System.currentTimeMillis(),
                    "message", line)), build.region);
        } catch (RuntimeException e) {
            LOG.debugv("Could not log to {0}: {1}", group, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- running a build

    private void run(Build build) {
        String finalStatus = "AVAILABLE";
        String reason = null;
        Map<String, JsonNode> workflowOutputs = new HashMap<>();
        try {
            ObjectNode image = image(build);
            if (image == null) {
                return;
            }
            ObjectNode context = context(build, image);
            if (meta(image).path("distributeOnly").asBoolean(false)) {
                setStatus(build, "DISTRIBUTING", null);
                actions.get().distributeDefault(build, context);
                setStatus(build, "INTEGRATING", null);
                return;
            }
            setStatus(build, "BUILDING", null);
            boolean docker = "DOCKER".equals(image.path("type").asText());
            List<JsonNode> configured = new ArrayList<>();
            image.path("workflows").forEach(configured::add);
            JsonNode buildWorkflow = pick(configured, "BUILD", build.region,
                    docker ? "build/build-container" : "build/build-image");
            if (buildWorkflow != null && !runWorkflow(build, context, buildWorkflow, "BUILD", workflowOutputs, null)) {
                finalStatus = build.cancelled ? "CANCELLED" : "FAILED";
                reason = build.cancelled ? "The image was cancelled." : "The build workflow failed.";
                return;
            }
            image = image(build);
            boolean tests = image.path("imageTestsConfiguration").path("imageTestsEnabled").asBoolean(true);
            List<JsonNode> testWorkflows = new ArrayList<>();
            for (JsonNode configuration : configured) {
                if ("TEST".equals(workflowType(configuration, build.region))) {
                    testWorkflows.add(configuration);
                }
            }
            if (testWorkflows.isEmpty() && configured.stream().noneMatch(c -> "BUILD".equals(workflowType(c,
                    build.region)) && !isManaged(c))) {
                testWorkflows.add(managedConfiguration(build.region, docker ? "test/test-container"
                        : "test/test-image"));
            }
            if (tests && !testWorkflows.isEmpty()) {
                setStatus(build, "TESTING", null);
                int minutes = image.path("imageTestsConfiguration").path("timeoutMinutes").asInt(720);
                long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes);
                for (JsonNode test : testWorkflows) {
                    if (!runWorkflow(build, context, test, "TEST", workflowOutputs, deadline)) {
                        finalStatus = build.cancelled ? "CANCELLED" : "FAILED";
                        reason = build.cancelled ? "The image was cancelled." : "A test workflow failed.";
                        return;
                    }
                }
            }
            setStatus(build, "DISTRIBUTING", null);
            JsonNode distribution = pick(configured, "DISTRIBUTION", build.region, null);
            if (distribution != null) {
                if (!runWorkflow(build, context, distribution, "DISTRIBUTION", workflowOutputs, null)) {
                    finalStatus = build.cancelled ? "CANCELLED" : "FAILED";
                    reason = "The distribution workflow failed.";
                    return;
                }
            } else {
                actions.get().distributeDefault(build, context);
            }
            setStatus(build, "INTEGRATING", null);
        } catch (RuntimeException e) {
            LOG.warnv(e, "The image build {0} failed", build.imageKey);
            finalStatus = build.cancelled ? "CANCELLED" : "FAILED";
            reason = e.getMessage();
        } finally {
            finish(build, finalStatus, reason);
        }
    }

    private void finish(Build build, String status, String reason) {
        builds.remove(build.account + "|" + build.imageKey);
        ObjectNode image = image(build);
        if (image == null) {
            return;
        }
        if (!"AVAILABLE".equals(status)) {
            boolean terminate = image.path("infrastructureConfiguration").path("terminateInstanceOnFailure")
                    .asBoolean(true);
            if (terminate) {
                actions.get().terminateAll(build);
            }
        }
        setStatus(build, status, reason);
        log(build, "Image build ended: " + status + (reason == null ? "" : " (" + reason + ")"));
        image = image(build);
        String pipelineArn = image.path("sourcePipelineArn").asText("");
        if (!pipelineArn.isEmpty()) {
            String pipelineKey = key(build.region, "image-pipeline/" + pipelineArn.substring(pipelineArn
                    .lastIndexOf('/') + 1));
            store.get(build.account, pipelineKey).ifPresent(pipeline -> {
                pipeline.put("lastRunStatus", status);
                pipeline.put("consecutiveFailures", "FAILED".equals(status)
                        ? pipeline.path("consecutiveFailures").asInt(0) + 1 : 0);
                store.put(build.account, pipelineKey, pipeline);
                autoDisable(build.account, pipelineKey, pipeline);
            });
        }
        String topic = image.path("infrastructureConfiguration").path("snsTopicArn").asText("");
        if (!topic.isEmpty()) {
            try {
                sns.get().publish(topic, null, store.view(image).toString(), null, build.region);
            } catch (RuntimeException e) {
                LOG.debugv("Could not notify {0}: {1}", topic, e.getMessage());
            }
        }
    }

    private void autoDisable(String account, String pipelineKey, ObjectNode pipeline) {
        int limit = pipeline.path("schedule").path("autoDisablePolicy").path("failureCount").asInt(0);
        if (limit > 0 && pipeline.path("consecutiveFailures").asInt(0) >= limit) {
            pipeline.put("status", "DISABLED");
            store.put(account, pipelineKey, pipeline);
        }
    }

    /** The system variables of a build: {@code $.imagebuilder.*}. */
    private ObjectNode context(Build build, ObjectNode image) {
        ObjectNode context = store.mapper().createObjectNode();
        ObjectNode system = context.putObject("imagebuilder");
        JsonNode infrastructure = image.path("infrastructureConfiguration");
        String[] version = image.path("version").asText().split("/");
        boolean docker = "DOCKER".equals(image.path("type").asText());
        JsonNode recipe = docker ? image.path("containerRecipe") : image.path("imageRecipe");
        system.put("cloudWatchLogGroup", "/aws/imagebuilder/" + image.path("name").asText())
                .put("cloudWatchLogStream", image.path("version").asText())
                .put("collectImageMetadata", image.path("enhancedImageMetadataEnabled").asBoolean(true))
                .put("collectImageScanFindings", image.path("imageScanningConfiguration")
                        .path("imageScanningEnabled").asBoolean(false))
                .put("imageBuildNumber", Integer.parseInt(version[1]))
                .put("imageName", image.path("name").asText())
                .put("imageType", docker ? "DOCKER" : "AMI")
                .put("imageVersionNumber", version[0])
                .put("instanceProfileName", infrastructure.path("instanceProfileName").asText(""))
                .put("platform", image.path("platform").asText("Linux"))
                .put("sourceImageARN", image.path("arn").asText())
                .put("subnetId", infrastructure.path("subnetId").asText(""))
                .put("terminateInstanceOnCompletion", infrastructure.path("terminateInstanceOnCompletion")
                        .asBoolean(true))
                .put("terminateInstanceOnFailure", infrastructure.path("terminateInstanceOnFailure").asBoolean(true))
                .put("workingDirectory", recipe.path("workingDirectory").asText("/tmp"));
        system.set("securityGroups", infrastructure.path("securityGroupIds").deepCopy());
        system.set("s3Logs", infrastructure.path("logging").path("s3Logs").deepCopy());
        if (!docker && recipe.hasNonNull("parentImage")) {
            system.put("imageId", parentAmi(recipe.path("parentImage").asText(), build.region));
        }
        context.putObject("stepOutputs");
        context.putObject("parameters");
        context.putObject("workflowOutputs");
        return context;
    }

    private static boolean isManaged(JsonNode configuration) {
        ImageBuilderRefs.Ref ref = ImageBuilderRefs.parse(configuration.path("workflowArn").asText(), "workflow");
        return ref.amazon();
    }

    private String workflowType(JsonNode configuration, String region) {
        return actions.get().workflow(configuration.path("workflowArn").asText(), region).path("type").asText();
    }

    /** The workflow of a stage the image names, or Amazon's default for it when it names none. */
    private JsonNode pick(List<JsonNode> configured, String type, String region, String managedDefault) {
        for (JsonNode configuration : configured) {
            if (type.equals(workflowType(configuration, region))) {
                return configuration;
            }
        }
        boolean custom = !configured.isEmpty();
        if (managedDefault == null || custom && "BUILD".equals(type)) {
            return null;
        }
        return managedConfiguration(region, managedDefault);
    }

    private JsonNode managedConfiguration(String region, String path) {
        return store.mapper().createObjectNode().put("workflowArn", ImageBuilderStore.arn(
                ImageBuilderManaged.OWNER_ACCOUNT, region, "workflow/" + path + "/x.x.x"));
    }

    // ---------------------------------------------------------------- workflows

    /**
     * Runs one workflow of the image: its steps in order, recording the execution and its steps. Returns whether
     * it completed.
     */
    private boolean runWorkflow(Build build, ObjectNode context, JsonNode configuration, String stage,
                                Map<String, JsonNode> workflowOutputs, Long deadline) {
        ObjectNode workflow = actions.get().workflow(configuration.path("workflowArn").asText(), build.region);
        JsonNode document = ImageBuilderService.parseYaml(workflow.path("data").asText(), "workflow");
        ObjectNode image = image(build);
        String executionId = "wf-" + UUID.randomUUID();
        ObjectNode execution = store.record("workflow-execution", "workflow-execution/" + executionId);
        execution.put("workflowBuildVersionArn", workflow.path("arn").asText())
                .put("workflowExecutionId", executionId).put("imageBuildVersionArn", image.path("arn").asText())
                .put("type", workflow.path("type").asText(stage)).put("status", "RUNNING")
                .put("totalStepCount", document.path("steps").size()).put("totalStepsSucceeded", 0)
                .put("totalStepsFailed", 0).put("totalStepsSkipped", 0).put("startTime", now());
        if (configuration.hasNonNull("parallelGroup")) {
            execution.put("parallelGroup", configuration.get("parallelGroup").asText());
        }
        meta(execution).put("image", build.imageKey);
        String executionKey = key(build.region, "workflow-execution/" + executionId);
        store.put(build.account, executionKey, execution);
        ObjectNode workflowContext = context.deepCopy();
        workflowContext.set("parameters", parameters(document, configuration));
        workflowContext.set("stepOutputs", store.mapper().createObjectNode());
        ObjectNode outputs = store.mapper().createObjectNode();
        workflowOutputs.forEach(outputs::set);
        workflowContext.set("workflowOutputs", outputs);
        ((ObjectNode) workflowContext.path("imagebuilder")).put("workflowPhase", stage.charAt(0)
                + stage.substring(1).toLowerCase());
        log(build, "Workflow " + workflow.path("arn").asText() + " started (" + executionId + ")");
        List<ObjectNode> completed = new ArrayList<>();
        String status = "COMPLETED";
        String message = null;
        for (JsonNode step : document.path("steps")) {
            if (build.cancelled) {
                status = "CANCELLED";
                message = "The image was cancelled.";
                break;
            }
            if (deadline != null && System.currentTimeMillis() > deadline) {
                status = "FAILED";
                message = "The image tests timed out.";
                break;
            }
            ObjectNode stepRecord = runStep(build, workflowContext, execution, workflow, step, executionKey);
            String stepStatus = stepRecord.path("status").asText();
            execution = store.get(build.account, executionKey).orElse(execution);
            switch (stepStatus) {
                case "COMPLETED" -> {
                    execution.put("totalStepsSucceeded", execution.path("totalStepsSucceeded").asInt() + 1);
                    completed.add(stepRecord);
                }
                case "SKIPPED" -> execution.put("totalStepsSkipped", execution.path("totalStepsSkipped").asInt() + 1);
                default -> execution.put("totalStepsFailed", execution.path("totalStepsFailed").asInt() + 1);
            }
            store.put(build.account, executionKey, execution);
            if ("CANCELLED".equals(stepStatus)) {
                status = "CANCELLED";
                message = "The image was cancelled.";
                break;
            }
            if ("FAILED".equals(stepStatus) && !"Continue".equals(step.path("onFailure").asText("Abort"))) {
                status = "FAILED";
                message = "Step " + step.path("name").asText() + " failed: " + stepRecord.path("message").asText();
                if (rollbackEnabled(step, workflowContext)) {
                    execution.put("status", "ROLLBACK_IN_PROGRESS");
                    store.put(build.account, executionKey, execution);
                    rollback(build, completed);
                    status = "ROLLBACK_COMPLETED".equals(status) ? status : "FAILED";
                }
                break;
            }
        }
        if ("COMPLETED".equals(status)) {
            for (JsonNode output : document.path("outputs")) {
                JsonNode value = resolve(output.path("value").asText(), workflowContext);
                if (value != null && !value.isMissingNode()) {
                    workflowOutputs.put(output.path("name").asText(), value);
                }
            }
        }
        execution = store.get(build.account, executionKey).orElse(execution);
        execution.put("status", status).put("endTime", now());
        if (message != null) {
            execution.put("message", message);
        }
        store.put(build.account, executionKey, execution);
        log(build, "Workflow " + executionId + " ended: " + status + (message == null ? "" : " - " + message));
        return "COMPLETED".equals(status);
    }

    private boolean rollbackEnabled(JsonNode step, ObjectNode context) {
        JsonNode value = step.get("rollbackEnabled");
        if (value == null) {
            return true;
        }
        if (value.isTextual() && value.asText().startsWith("$.")) {
            JsonNode resolved = resolve(value.asText(), context);
            return resolved == null || resolved.asBoolean(true);
        }
        return value.asBoolean(true);
    }

    /** Rolls back the completed steps that can be, newest first: a launched instance is terminated. */
    private void rollback(Build build, List<ObjectNode> completed) {
        for (int i = completed.size() - 1; i >= 0; i--) {
            ObjectNode step = completed.get(i);
            String stepKey = key(build.region, "workflow-step/" + step.path("stepExecutionId").asText());
            if ("LaunchInstance".equals(step.path("action").asText())) {
                String instanceId = step.path("outputs").path("instanceId").asText("");
                ObjectNode image = image(build);
                boolean terminate = image == null || image.path("infrastructureConfiguration")
                        .path("terminateInstanceOnFailure").asBoolean(true);
                if (!instanceId.isEmpty() && terminate) {
                    actions.get().terminate(build, instanceId);
                }
                step.put("rollbackStatus", "COMPLETED");
            } else {
                step.put("rollbackStatus", "NO_ROLLBACK");
            }
            store.put(build.account, stepKey, step);
        }
    }

    private ObjectNode parameters(JsonNode document, JsonNode configuration) {
        ObjectNode parameters = store.mapper().createObjectNode();
        for (JsonNode parameter : document.path("parameters")) {
            String name = parameter.path("name").asText();
            String type = parameter.path("type").asText("string");
            JsonNode given = null;
            for (JsonNode value : configuration.path("parameters")) {
                if (name.equals(value.path("name").asText())) {
                    given = value.path("value");
                }
            }
            JsonNode resolved;
            if (given != null && given.size() > 0) {
                resolved = typed(given, type);
            } else if (parameter.has("default")) {
                resolved = parameter.get("default");
            } else {
                throw ImageBuilderStore.invalidParameter("The workflow parameter " + name + " needs a value.");
            }
            parameters.set(name, resolved);
        }
        return parameters;
    }

    private JsonNode typed(JsonNode values, String type) {
        return switch (type) {
            case "integer" -> store.mapper().getNodeFactory().numberNode(Long.parseLong(values.get(0).asText()));
            case "boolean" -> store.mapper().getNodeFactory().booleanNode(Boolean.parseBoolean(values.get(0)
                    .asText()));
            case "stringList" -> values.deepCopy();
            default -> TextNode.valueOf(values.get(0).asText());
        };
    }

    /** Runs one step: its condition, inputs, retries and timeout. Returns its record. */
    private ObjectNode runStep(Build build, ObjectNode context, ObjectNode execution, ObjectNode workflow,
                               JsonNode step, String executionKey) {
        String stepId = "step-" + UUID.randomUUID();
        ObjectNode record = store.record("workflow-step", "workflow-step/" + stepId);
        record.put("stepExecutionId", stepId).put("workflowBuildVersionArn", workflow.path("arn").asText())
                .put("workflowExecutionId", execution.path("workflowExecutionId").asText())
                .put("imageBuildVersionArn", execution.path("imageBuildVersionArn").asText())
                .put("name", step.path("name").asText()).put("action", step.path("action").asText())
                .put("onFailure", step.path("onFailure").asText("Abort")).put("status", "PENDING")
                .put("rollbackStatus", "SKIPPED");
        if (step.hasNonNull("description")) {
            record.put("description", step.get("description").asText());
        }
        int maxAttempts = Math.max(1, step.path("retry").path("maxAttempts").asInt(1));
        record.put("maxAttempts", maxAttempts).put("attemptNumber", 0);
        meta(record).put("image", build.imageKey).put("execution", execution.path("workflowExecutionId").asText());
        String stepKey = key(build.region, "workflow-step/" + stepId);
        store.put(build.account, stepKey, record);
        if (step.has("if") && !condition(step.get("if"), context)) {
            record.put("status", "SKIPPED").put("startTime", now()).put("endTime", now());
            store.put(build.account, stepKey, record);
            log(build, "Step " + step.path("name").asText() + " skipped");
            return record;
        }
        ObjectNode inputs = resolveInputs(step.path("inputs"), context);
        record.put("inputs", inputs.toString()).put("status", "RUNNING").put("startTime", now());
        int timeout = step.path("timeoutSeconds").asInt(ImageBuilderActions.defaultTimeout(step.path("action")
                .asText()));
        record.put("timeoutSeconds", timeout);
        store.put(build.account, stepKey, record);
        log(build, "Step " + step.path("name").asText() + " (" + step.path("action").asText() + ") started");
        int waitSeconds = step.path("waitSeconds").asInt(0);
        if (waitSeconds > 0) {
            sleep(TimeUnit.SECONDS.toMillis(waitSeconds));
        }
        ObjectNode outputs = null;
        String failure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            record.put("attemptNumber", attempt);
            store.put(build.account, stepKey, record);
            if (build.cancelled) {
                failure = null;
                break;
            }
            try {
                outputs = runAction(build, step.path("action").asText(), inputs, context, stepId, timeout);
                failure = null;
                break;
            } catch (TimeoutException e) {
                failure = "The step timed out after " + timeout + " seconds.";
            } catch (RuntimeException e) {
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (attempt < maxAttempts) {
                sleep(TimeUnit.SECONDS.toMillis(step.path("retry").path("backoffSeconds").asInt(30)));
            }
        }
        record = store.get(build.account, stepKey).orElse(record);
        if (build.cancelled && outputs == null) {
            record.put("status", "CANCELLED").put("message", "The image was cancelled.");
        } else if (failure != null) {
            record.put("status", "FAILED").put("message", failure);
            log(build, "Step " + step.path("name").asText() + " failed: " + failure);
        } else {
            record.put("status", "COMPLETED");
            if (outputs != null) {
                record.put("outputs", outputs.toString());
                ((ObjectNode) context.path("stepOutputs")).set(step.path("name").asText(), outputs);
            }
            log(build, "Step " + step.path("name").asText() + " completed");
        }
        record.put("endTime", now());
        store.put(build.account, stepKey, record);
        return record;
    }

    private ObjectNode runAction(Build build, String action, ObjectNode inputs, ObjectNode context, String stepId,
                                 int timeoutSeconds) throws TimeoutException {
        if ("WaitForAction".equals(action)) {
            return waitForAction(build, inputs, context, stepId, timeoutSeconds);
        }
        Future<ObjectNode> future = runner.submit(() -> RequestScopes.callAs(build.account, build.region,
                () -> actions.get().run(build, action, inputs, context)));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The step was interrupted.");
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    /** Pauses for SendWorkflowStepAction: publishes the waiting event, notifies, and waits for an answer. */
    private ObjectNode waitForAction(Build build, ObjectNode inputs, ObjectNode context, String stepId,
                                     int timeoutSeconds) throws TimeoutException {
        ObjectNode image = image(build);
        String stepKey = key(build.region, "workflow-step/" + stepId);
        ObjectNode step = store.get(build.account, stepKey).orElseThrow();
        CompletableFuture<Answer> pending = new CompletableFuture<>();
        waiting.put(stepId, pending);
        step.put("status", "WAITING");
        store.put(build.account, stepKey, step);
        ObjectNode message = store.mapper().createObjectNode();
        message.put("imageArn", image.path("arn").asText()).put("workflowArn", step.path("workflowBuildVersionArn")
                        .asText()).put("workflowExecutionId", step.path("workflowExecutionId").asText())
                .put("workflowStepExecutionId", stepId).put("workflowStepName", step.path("name").asText())
                .put("version", "1.0");
        if (inputs.hasNonNull("payload") && !inputs.get("payload").asText().isEmpty()) {
            message.put("customPayload", inputs.get("payload").asText());
        }
        ObjectNode detail = message.deepCopy();
        detail.put("workflow-execution-id", step.path("workflowExecutionId").asText())
                .put("workflow-step-execution-id", stepId).put("workflow-step-name", step.path("name").asText());
        publishEvent(build, "EC2 Image Builder Workflow Step Waiting", detail, image.path("arn").asText());
        actions.get().notifyWaiting(build, inputs, message);
        try {
            Answer answer = pending.get(timeoutSeconds, TimeUnit.SECONDS);
            if ("STOP".equals(answer.action())) {
                throw new IllegalStateException(answer.reason() == null ? "The workflow was stopped."
                        : answer.reason());
            }
            ObjectNode outputs = store.mapper().createObjectNode().put("action", answer.action());
            if (answer.reason() != null) {
                outputs.put("reason", answer.reason());
            }
            return outputs;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The step was interrupted.");
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            waiting.remove(stepId);
        }
    }

    // ---------------------------------------------------------------- expressions

    /** A step's inputs: a {@code name.$} input is resolved as a JSONPath, strings are interpolated. */
    ObjectNode resolveInputs(JsonNode inputs, ObjectNode context) {
        ObjectNode resolved = store.mapper().createObjectNode();
        if (inputs == null || !inputs.isObject()) {
            return resolved;
        }
        inputs.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            if (name.endsWith(".$")) {
                JsonNode value = resolve(entry.getValue().asText(), context);
                resolved.set(name.substring(0, name.length() - 2), value == null ? TextNode.valueOf("") : value);
            } else {
                resolved.set(name, interpolate(entry.getValue(), context));
            }
        });
        return resolved;
    }

    private JsonNode interpolate(JsonNode value, ObjectNode context) {
        if (value.isTextual()) {
            Matcher matcher = INTERPOLATION.matcher(value.asText());
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                JsonNode resolved = resolve(matcher.group(1), context);
                matcher.appendReplacement(out, Matcher.quoteReplacement(resolved == null ? ""
                        : resolved.isValueNode() ? resolved.asText() : resolved.toString()));
            }
            matcher.appendTail(out);
            return TextNode.valueOf(out.toString());
        }
        if (value.isObject()) {
            return resolveInputs(value, context);
        }
        if (value.isArray()) {
            ArrayNode array = store.mapper().createArrayNode();
            value.forEach(item -> array.add(interpolate(item, context)));
            return array;
        }
        return value;
    }

    /** A {@code $.<node>.<path>} reference, with {@code [index]} steps; null when it is not one. */
    JsonNode resolve(String path, ObjectNode context) {
        if (path == null || !path.startsWith("$.")) {
            return path == null ? null : TextNode.valueOf(path);
        }
        JsonNode current = context;
        for (String part : path.substring(2).split("\\.")) {
            Matcher indexed = Pattern.compile("([^\\[]*)((\\[\\d+])*)").matcher(part);
            if (!indexed.matches()) {
                return null;
            }
            if (!indexed.group(1).isEmpty()) {
                current = current.path(indexed.group(1));
            }
            Matcher index = Pattern.compile("\\[(\\d+)]").matcher(indexed.group(2));
            while (index.find()) {
                current = current.path(Integer.parseInt(index.group(1)));
            }
        }
        return current.isMissingNode() ? null : current;
    }

    /** Evaluates an {@code if} condition: and, or, not, and the comparison operators against {@code value}. */
    boolean condition(JsonNode condition, ObjectNode context) {
        if (condition.has("and")) {
            for (JsonNode part : condition.get("and")) {
                if (!condition(part, context)) {
                    return false;
                }
            }
            return true;
        }
        if (condition.has("or")) {
            for (JsonNode part : condition.get("or")) {
                if (condition(part, context)) {
                    return true;
                }
            }
            return false;
        }
        if (condition.has("not")) {
            return !condition(condition.get("not"), context);
        }
        JsonNode raw = condition.get("value");
        JsonNode value = raw != null && raw.isTextual() ? resolve(raw.asText(), context) : raw;
        String actual = value == null ? "" : value.asText();
        for (Map.Entry<String, JsonNode> entry : (Iterable<Map.Entry<String, JsonNode>>) condition::fields) {
            JsonNode expected = entry.getValue();
            switch (entry.getKey()) {
                case "booleanEquals" -> {
                    return expected.asBoolean() == Boolean.parseBoolean(actual);
                }
                case "stringEquals" -> {
                    return expected.asText().equals(actual);
                }
                case "numberEquals" -> {
                    return number(actual) == expected.asDouble();
                }
                case "numberGreaterThan" -> {
                    return number(actual) > expected.asDouble();
                }
                case "numberGreaterThanEquals" -> {
                    return number(actual) >= expected.asDouble();
                }
                case "numberLessThan" -> {
                    return number(actual) < expected.asDouble();
                }
                case "numberLessThanEquals" -> {
                    return number(actual) <= expected.asDouble();
                }
                default -> {
                }
            }
        }
        throw ImageBuilderStore.invalidParameter("The condition " + condition + " has no supported operator.");
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static boolean terminal(String status) {
        return TERMINAL.contains(status);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- pipeline schedules

    void pipelineChanged(String account, String region, ObjectNode pipeline) {
        startScheduling();
    }

    void pipelineDeleted(String account, String region, ObjectNode pipeline) {
    }

    /** When an enabled pipeline's schedule next fires, or null. */
    String nextRun(ObjectNode pipeline) {
        String expression = pipeline.path("schedule").path("scheduleExpression").asText("");
        if (expression.isEmpty() || !"ENABLED".equals(pipeline.path("status").asText())) {
            return null;
        }
        try {
            Instant from = pipeline.hasNonNull("dateLastRun") ? Instant.parse(pipeline.get("dateLastRun").asText())
                    : Instant.parse(pipeline.path("dateCreated").asText());
            Instant next = ImageBuilderSchedule.next(expression, pipeline.path("schedule").path("timezone")
                    .asText(null), from);
            return next.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Starts checking pipeline schedules every half minute, once. */
    synchronized void startScheduling() {
        if (scheduling) {
            return;
        }
        scheduling = true;
        scheduler.scheduleWithFixedDelay(this::firePipelines, 30, 30, TimeUnit.SECONDS);
    }

    private void firePipelines() {
        try {
            for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(
                    key -> key.contains("/image-pipeline/"))) {
                ObjectNode pipeline = entry.value();
                String next = nextRun(pipeline);
                if (next == null || Instant.parse(next).isAfter(Instant.now())) {
                    continue;
                }
                String region = entry.key().substring(0, entry.key().indexOf('/'));
                RequestScopes.runAs(entry.accountId(), region, () -> {
                    try {
                        actions.get().images().startScheduled(pipeline, region);
                    } catch (RuntimeException e) {
                        LOG.debugv("The scheduled run of {0} did not start: {1}", pipeline.path("arn").asText(),
                                e.getMessage());
                        ObjectNode current = store.get(entry.key()).orElse(pipeline);
                        current.put("dateLastRun", now());
                        store.put(entry.key(), current);
                    }
                });
            }
        } catch (RuntimeException e) {
            LOG.debugv("Image pipeline schedules failed: {0}", e.getMessage());
        }
    }
}
