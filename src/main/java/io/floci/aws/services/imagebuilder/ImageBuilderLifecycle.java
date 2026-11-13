package io.floci.aws.services.imagebuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ecr.EcrService;
import io.floci.aws.services.ecr.model.ImageIdentifier;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.imagebuilder.ImageBuilderStore.invalidParameter;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.key;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.meta;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.notFound;
import static io.floci.aws.services.imagebuilder.ImageBuilderStore.now;

/**
 * Image lifecycle management: {@code StartResourceStateUpdate} deprecates, disables, deletes or restores an image and
 * its AMIs, snapshots and container images, and each enabled lifecycle policy is evaluated every
 * {@code floci.services.imagebuilder.lifecycle-interval-seconds} against the images its recipes or tags select,
 * by age or count with its exclusion rules. Each run is a lifecycle execution with the resources it touched.
 */
@ApplicationScoped
public class ImageBuilderLifecycle {

    private static final Logger LOG = Logger.getLogger(ImageBuilderLifecycle.class);

    private final ImageBuilderStore store;
    private final Instance<ImageBuilderImages> images;
    private final Instance<Ec2Service> ec2;
    private final Instance<EcrService> ecr;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "imagebuilder-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean scheduled;

    @Inject
    public ImageBuilderLifecycle(ImageBuilderStore store, Instance<ImageBuilderImages> images,
                                 Instance<Ec2Service> ec2, Instance<EcrService> ecr) {
        this.store = store;
        this.images = images;
        this.ec2 = ec2;
        this.ecr = ecr;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private ObjectNode object() {
        return store.mapper().createObjectNode();
    }

    /** Starts evaluating lifecycle policies on their interval, once. */
    synchronized void startEvaluating() {
        if (scheduled) {
            return;
        }
        scheduled = true;
        long interval = Math.max(10, store.config().lifecycleIntervalSeconds());
        scheduler.scheduleWithFixedDelay(this::evaluateAll, interval, interval, TimeUnit.SECONDS);
    }

    /** Starts evaluating persisted lifecycle policies. */
    public void restorePersistedRuntime() {
        if (!store.everywhere(key -> key.contains("/lifecycle-policy/")).isEmpty()) {
            startEvaluating();
        }
    }

    private void evaluateAll() {
        try {
            for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(
                    key -> key.contains("/lifecycle-policy/"))) {
                if (!"ENABLED".equals(entry.value().path("status").asText())) {
                    continue;
                }
                String region = entry.key().substring(0, entry.key().indexOf('/'));
                RequestScopes.runAs(entry.accountId(), region, () -> evaluate(entry.value(), region));
            }
        } catch (RuntimeException e) {
            LOG.debugv("Lifecycle policy evaluation failed: {0}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- state updates

    public ObjectNode startResourceStateUpdate(JsonNode input, String region) {
        return ImageBuilderRefs.idempotent(store, region, "StartResourceStateUpdate", input, () -> {
            ObjectNode image = images.get().image(input.path("resourceArn").asText(), region);
            String status = input.path("state").path("status").asText();
            if (!Set.of("AVAILABLE", "DELETED", "DEPRECATED", "DISABLED").contains(status)) {
                throw invalidParameter("state.status is AVAILABLE, DELETED, DEPRECATED or DISABLED.");
            }
            ObjectNode execution = execution(region, null);
            String executionId = execution.path("lifecycleExecutionId").asText();
            ArrayNode resources = (ArrayNode) meta(execution).withArray("/resources");
            apply(image, status, input.path("includeResources"), executionId, resources, region);
            finish(execution, region);
            return object().put("lifecycleExecutionId", executionId).put("resourceArn", image.path("arn").asText());
        });
    }

    private ObjectNode execution(String region, String policyArn) {
        String id = "lce-" + UUID.randomUUID();
        ObjectNode execution = store.record("lifecycle-execution", "lifecycle-execution/" + id);
        execution.put("lifecycleExecutionId", id).put("startTime", now());
        if (policyArn != null) {
            execution.put("lifecyclePolicyArn", policyArn);
        }
        execution.putObject("state").put("status", "IN_PROGRESS");
        meta(execution).putArray("resources");
        store.put(key(region, "lifecycle-execution/" + id), execution);
        return execution;
    }

    private void finish(ObjectNode execution, String region) {
        execution.put("endTime", now());
        execution.putObject("state").put("status", "SUCCESS");
        execution.putObject("resourcesImpactedSummary").put("hasImpactedResources",
                meta(execution).path("resources").size() > 0);
        store.put(key(region, "lifecycle-execution/" + execution.path("lifecycleExecutionId").asText()), execution);
    }

    /** Moves an image and the resources it includes to a state, recording each in the execution. */
    private void apply(ObjectNode image, String status, JsonNode include, String executionId, ArrayNode resources,
                       String region) {
        boolean amis = include == null || include.isMissingNode() || include.path("amis").asBoolean(true);
        boolean containers = include != null && include.path("containers").asBoolean(false);
        String action = switch (status) {
            case "DELETED" -> "DELETE";
            case "DEPRECATED" -> "DEPRECATE";
            case "DISABLED" -> "DISABLE";
            default -> "AVAILABLE";
        };
        for (JsonNode ami : image.path("outputResources").path("amis")) {
            if (!amis) {
                continue;
            }
            String amiRegion = ami.path("region").asText(region);
            String amiId = ami.path("image").asText();
            ObjectNode resource = resources.addObject();
            resource.put("accountId", ami.path("accountId").asText(store.account())).put("resourceId", amiId)
                    .put("region", amiRegion).put("startTime", now()).put("endTime", now());
            resource.putObject("action").put("name", action);
            try {
                RequestScopes.runAs(ami.path("accountId").asText(store.account()), amiRegion, () -> {
                    switch (status) {
                        case "DELETED" -> ec2.get().deregisterImage(amiRegion, amiId, include == null
                                || include.path("snapshots").asBoolean(true));
                        case "DEPRECATED", "DISABLED", "AVAILABLE" -> ec2.get().createTags(amiRegion, List.of(amiId),
                                List.of(new Tag("ImageBuilderLifecycleState", status)));
                        default -> {
                        }
                    }
                });
                resource.putObject("state").put("status", "SUCCESS");
            } catch (RuntimeException e) {
                resource.putObject("state").put("status", "FAILED").put("reason", e.getMessage());
            }
        }
        if (containers) {
            for (JsonNode container : image.path("outputResources").path("containers")) {
                for (JsonNode uri : container.path("imageUris")) {
                    ObjectNode resource = resources.addObject();
                    resource.put("resourceId", uri.asText()).put("region", container.path("region").asText())
                            .put("accountId", store.account());
                    resource.putObject("action").put("name", action);
                    resource.putObject("state").put("status", deleteContainer(uri.asText(), status,
                            container.path("region").asText(region)) ? "SUCCESS" : "SKIPPED");
                }
            }
        }
        image.putObject("state").put("status", status);
        if ("DEPRECATED".equals(status)) {
            image.put("deprecationTime", now());
        }
        image.put("lifecycleExecutionId", executionId);
        store.put(key(region, meta(image).path("resource").asText()), image);
    }

    private boolean deleteContainer(String uri, String status, String region) {
        if (!"DELETED".equals(status)) {
            return false;
        }
        int colon = uri.lastIndexOf(':');
        String repository = uri.substring(uri.indexOf('/') + 1, colon);
        String tag = uri.substring(colon + 1);
        try {
            ecr.get().batchDeleteImage(repository, List.of(new ImageIdentifier(tag, null)), null, region);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- lifecycle policies

    /** Evaluates a lifecycle policy now: each rule's action on the images it selects. */
    void evaluate(ObjectNode policy, String region) {
        ObjectNode execution = execution(region, policy.path("arn").asText());
        String executionId = execution.path("lifecycleExecutionId").asText();
        ArrayNode resources = (ArrayNode) meta(execution).withArray("/resources");
        String type = "AMI_IMAGE".equals(policy.path("resourceType").asText()) ? "AMI" : "DOCKER";
        List<ObjectNode> selected = new ArrayList<>();
        for (ObjectNode image : store.list(key(region, "image/"))) {
            if (type.equals(image.path("type").asText()) && selects(policy.path("resourceSelection"), image)
                    && !"DELETED".equals(image.path("state").path("status").asText())
                    && ImageBuilderEngine.terminal(image.path("state").path("status").asText())) {
                selected.add(image);
            }
        }
        selected.sort((a, b) -> b.path("dateCreated").asText().compareTo(a.path("dateCreated").asText()));
        for (JsonNode detail : policy.path("policyDetails")) {
            List<ObjectNode> matched = matched(detail, selected);
            String status = switch (detail.path("action").path("type").asText()) {
                case "DELETE" -> "DELETED";
                case "DEPRECATE" -> "DEPRECATED";
                default -> "DISABLED";
            };
            for (ObjectNode image : matched) {
                if (!status.equals(image.path("state").path("status").asText())) {
                    apply(image, status, detail.path("action").path("includeResources"), executionId, resources,
                            region);
                }
            }
        }
        finish(execution, region);
        policy.put("dateLastRun", now());
        store.put(key(region, meta(policy).path("resource").asText()), policy);
    }

    private static boolean selects(JsonNode selection, ObjectNode image) {
        if (selection.has("recipes")) {
            String recipe = image.path("imageRecipe").path("name").asText(image.path("containerRecipe").path("name")
                    .asText());
            String version = image.path("version").asText().split("/")[0];
            for (JsonNode wanted : selection.get("recipes")) {
                if (wanted.path("name").asText().equals(recipe) && ImageBuilderRefs.matches(wanted
                        .path("semanticVersion").asText(), version)) {
                    return true;
                }
            }
            return false;
        }
        for (Map.Entry<String, JsonNode> tag : (Iterable<Map.Entry<String, JsonNode>>) selection.path("tagMap")::fields) {
            if (tag.getValue().asText().equals(image.path("tags").path(tag.getKey()).asText(null))) {
                return true;
            }
        }
        return false;
    }

    /** The selected images (newest first) a rule acts on, after its retention and exclusion rules. */
    private static List<ObjectNode> matched(JsonNode detail, List<ObjectNode> selected) {
        JsonNode filter = detail.path("filter");
        List<ObjectNode> eligible = new ArrayList<>();
        for (ObjectNode image : selected) {
            if (!excluded(detail.path("exclusionRules"), image)) {
                eligible.add(image);
            }
        }
        List<ObjectNode> matched = new ArrayList<>();
        if ("COUNT".equals(filter.path("type").asText())) {
            int keep = filter.path("value").asInt();
            for (int i = keep; i < eligible.size(); i++) {
                matched.add(eligible.get(i));
            }
            return matched;
        }
        int value = filter.path("value").asInt();
        Instant cutoff = switch (filter.path("unit").asText("DAYS")) {
            case "WEEKS" -> Instant.now().atZone(ZoneOffset.UTC).minusWeeks(value).toInstant();
            case "MONTHS" -> Instant.now().atZone(ZoneOffset.UTC).minusMonths(value).toInstant();
            case "YEARS" -> Instant.now().atZone(ZoneOffset.UTC).minusYears(value).toInstant();
            default -> Instant.now().atZone(ZoneOffset.UTC).minusDays(value).toInstant();
        };
        int retain = filter.path("retainAtLeast").asInt(0);
        for (int i = 0; i < eligible.size(); i++) {
            if (i >= retain && Instant.parse(eligible.get(i).path("dateCreated").asText()).isBefore(cutoff)) {
                matched.add(eligible.get(i));
            }
        }
        return matched;
    }

    private static boolean excluded(JsonNode rules, ObjectNode image) {
        for (Map.Entry<String, JsonNode> tag : (Iterable<Map.Entry<String, JsonNode>>) rules.path("tagMap")::fields) {
            if (tag.getValue().asText().equals(image.path("tags").path(tag.getKey()).asText(null))) {
                return true;
            }
        }
        for (Map.Entry<String, JsonNode> tag : (Iterable<Map.Entry<String, JsonNode>>) rules.path("amis")
                .path("tagMap")::fields) {
            if (tag.getValue().asText().equals(image.path("tags").path(tag.getKey()).asText(null))) {
                return true;
            }
        }
        JsonNode regions = rules.path("amis").path("regions");
        for (JsonNode ami : image.path("outputResources").path("amis")) {
            for (JsonNode region : regions) {
                if (region.asText().equals(ami.path("region").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- reading executions

    private ObjectNode stored(String id, String region) {
        return store.get(key(region, "lifecycle-execution/" + id)).orElseThrow(() ->
                notFound("The lifecycle execution " + id + " does not exist."));
    }

    public ObjectNode getLifecycleExecution(JsonNode input, String region) {
        ObjectNode response = object();
        response.set("lifecycleExecution", store.view(stored(input.path("lifecycleExecutionId").asText(), region)));
        return response;
    }

    public ObjectNode listLifecycleExecutions(JsonNode input, String region) {
        String resource = input.path("resourceArn").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode execution : store.list(key(region, "lifecycle-execution/"))) {
            boolean match = resource.equals(execution.path("lifecyclePolicyArn").asText());
            if (!match) {
                for (JsonNode image : store.list(key(region, "image/"))) {
                    match |= resource.equals(image.path("arn").asText()) && execution.path("lifecycleExecutionId")
                            .asText().equals(image.path("lifecycleExecutionId").asText());
                }
            }
            if (match) {
                items.add(store.view(execution));
            }
        }
        ObjectNode response = store.page(items, input, "lifecycleExecutions");
        response.remove("requestId");
        return response;
    }

    public ObjectNode listLifecycleExecutionResources(JsonNode input, String region) {
        ObjectNode execution = stored(input.path("lifecycleExecutionId").asText(), region);
        List<ObjectNode> items = new ArrayList<>();
        meta(execution).path("resources").forEach(resource -> items.add((ObjectNode) resource));
        ObjectNode response = store.page(items, input, "resources");
        response.remove("requestId");
        response.put("lifecycleExecutionId", execution.path("lifecycleExecutionId").asText());
        response.set("lifecycleExecutionState", execution.path("state").deepCopy());
        return response;
    }

    public ObjectNode cancelLifecycleExecution(JsonNode input, String region) {
        ObjectNode execution = stored(input.path("lifecycleExecutionId").asText(), region);
        if ("IN_PROGRESS".equals(execution.path("state").path("status").asText())) {
            execution.putObject("state").put("status", "CANCELLED");
            store.put(key(region, "lifecycle-execution/" + execution.path("lifecycleExecutionId").asText()),
                    execution);
        } else {
            throw new AwsException("InvalidRequestException", "The lifecycle execution "
                    + execution.path("lifecycleExecutionId").asText() + " has ended.", 400);
        }
        return object().put("lifecycleExecutionId", execution.path("lifecycleExecutionId").asText());
    }
}
