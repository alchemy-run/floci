package io.floci.aws.services.mediaconvert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MediaConvert's queues, presets and job templates, with tags; the account's input policy, associated certificates,
 * endpoints and job engine versions. Every Region has the {@code Default} queue, and a few system presets and job
 * templates ({@code System-...}) that can be read and used but not changed.
 */
@ApplicationScoped
public class MediaConvertResources {

    static final String DEFAULT_QUEUE = "Default";
    private static final Pattern NAME = Pattern.compile("[^/\\s]{1,128}");
    static final String ENGINE_VERSION = "2025-09-01";

    private final MediaConvertStore store;
    private final ObjectMapper mapper;
    private final Provider<MediaConvertJobs> jobs;

    @Inject
    public MediaConvertResources(MediaConvertStore store, Provider<MediaConvertJobs> jobs) {
        this.store = store;
        this.mapper = store.mapper();
        this.jobs = jobs;
    }

    private static String name(JsonNode in, String what) {
        String name = in.path("Name").asText(null);
        if (name == null || !NAME.matcher(name).matches()) {
            throw MediaConvertStore.badRequest("The " + what + " name must be 1-128 characters without slashes or "
                    + "spaces.");
        }
        return name;
    }

    // ================================================================ queues

    /** A queue of this account by name or ARN; the Default queue exists in every Region. */
    ObjectNode queue(String region, String nameOrArn) {
        String name = nameOrArn == null || nameOrArn.isEmpty() ? DEFAULT_QUEUE
                : nameOrArn.contains("/") ? nameOrArn.substring(nameOrArn.lastIndexOf('/') + 1) : nameOrArn;
        ObjectNode queue = store.get(region, MediaConvertStore.QUEUE, name).orElse(null);
        if (queue == null && DEFAULT_QUEUE.equals(name)) {
            queue = defaultQueue(region);
        }
        if (queue == null) {
            throw MediaConvertStore.notFound("The queue " + nameOrArn + " doesn't exist.");
        }
        return queue;
    }

    private ObjectNode defaultQueue(String region) {
        ObjectNode queue = store.record();
        queue.put("Name", DEFAULT_QUEUE);
        queue.put("Arn", store.arn(region, "queues/" + DEFAULT_QUEUE));
        queue.put("Description", "The default queue jobs go to when they name none.");
        queue.put("Status", "ACTIVE");
        queue.put("PricingPlan", "ON_DEMAND");
        queue.put("Type", "SYSTEM");
        queue.put("ConcurrentJobs", store.config().concurrentJobs());
        double now = MediaConvertStore.epoch();
        queue.put("CreatedAt", now);
        queue.put("LastUpdated", now);
        MediaConvertStore.meta(queue).put("seq", 0);
        store.put(region, MediaConvertStore.QUEUE, DEFAULT_QUEUE, queue);
        return queue;
    }

    ObjectNode queueView(String region, ObjectNode queue) {
        ObjectNode view = MediaConvertStore.view(queue);
        int submitted = 0;
        int progressing = 0;
        for (ObjectNode job : store.list(region, MediaConvertStore.JOB)) {
            if (queue.path("Arn").asText().equals(job.path("Queue").asText())) {
                submitted += "SUBMITTED".equals(job.path("Status").asText()) ? 1 : 0;
                progressing += "PROGRESSING".equals(job.path("Status").asText()) ? 1 : 0;
            }
        }
        view.put("SubmittedJobsCount", submitted);
        view.put("ProgressingJobsCount", progressing);
        view.putArray("ServiceOverrides");
        return view;
    }

    private ObjectNode wrap(String member, ObjectNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(member, value);
        return out;
    }

    public ObjectNode createQueue(JsonNode in, String region) {
        queue(region, DEFAULT_QUEUE);
        String name = name(in, "queue");
        if (store.get(region, MediaConvertStore.QUEUE, name).isPresent()) {
            throw MediaConvertStore.conflict("A queue named " + name + " already exists.");
        }
        if (store.list(region, MediaConvertStore.QUEUE).size() > store.config().maxQueues()) {
            throw MediaConvertStore.tooMany("The account has the maximum number of queues.");
        }
        ObjectNode queue = store.record();
        queue.put("Name", name);
        queue.put("Arn", store.arn(region, "queues/" + name));
        queue.put("Type", "CUSTOM");
        queue.put("Status", in.path("Status").asText("ACTIVE"));
        queue.put("PricingPlan", in.path("PricingPlan").asText("ON_DEMAND"));
        MediaConvertStore.copy(in, queue, "Description");
        if ("RESERVED".equals(queue.path("PricingPlan").asText())) {
            queue.set("ReservationPlan", reservation(in.path("ReservationPlanSettings"), null));
            queue.put("ConcurrentJobs", queue.path("ReservationPlan").path("ReservedSlots").asInt());
        } else {
            int concurrent = in.path("ConcurrentJobs").asInt(store.config().concurrentJobs());
            if (concurrent < 1 || concurrent > 150) {
                throw MediaConvertStore.badRequest("ConcurrentJobs is between 1 and 150.");
            }
            queue.put("ConcurrentJobs", concurrent);
        }
        MediaConvertStore.copy(in, queue, "MaximumConcurrentFeeds");
        double now = MediaConvertStore.epoch();
        queue.put("CreatedAt", now);
        queue.put("LastUpdated", now);
        MediaConvertStore.addTags(queue, in.path("Tags"));
        store.put(region, MediaConvertStore.QUEUE, name, queue);
        return wrap("Queue", queueView(region, queue));
    }

    private ObjectNode reservation(JsonNode settings, JsonNode current) {
        if (!settings.isObject()) {
            throw MediaConvertStore.badRequest("A RESERVED queue needs ReservationPlanSettings.");
        }
        int slots = settings.path("ReservedSlots").asInt(0);
        if (slots < 1) {
            throw MediaConvertStore.badRequest("ReservationPlanSettings needs ReservedSlots of at least 1.");
        }
        ObjectNode plan = mapper.createObjectNode();
        Instant purchased = current != null && current.has("PurchasedAt")
                ? Instant.ofEpochMilli((long) (current.get("PurchasedAt").asDouble() * 1000)) : Instant.now();
        plan.put("Commitment", settings.path("Commitment").asText("ONE_YEAR"));
        plan.put("RenewalType", settings.path("RenewalType").asText("AUTO_RENEW"));
        plan.put("ReservedSlots", slots);
        plan.put("Status", "ACTIVE");
        plan.put("PurchasedAt", purchased.toEpochMilli() / 1000.0);
        plan.put("ExpiresAt", purchased.plus(365, ChronoUnit.DAYS).toEpochMilli() / 1000.0);
        return plan;
    }

    public ObjectNode getQueue(JsonNode in, String region) {
        return wrap("Queue", queueView(region, queue(region, in.path("Name").asText())));
    }

    public ObjectNode updateQueue(JsonNode in, String region) {
        ObjectNode queue = queue(region, in.path("Name").asText());
        MediaConvertStore.copy(in, queue, "Description", "Status", "MaximumConcurrentFeeds");
        if (in.has("ConcurrentJobs")) {
            if ("RESERVED".equals(queue.path("PricingPlan").asText())) {
                throw MediaConvertStore.badRequest("A reserved queue's concurrency is its reserved slots.");
            }
            queue.put("ConcurrentJobs", in.get("ConcurrentJobs").asInt());
        }
        if (in.has("ReservationPlanSettings")) {
            if (!"RESERVED".equals(queue.path("PricingPlan").asText())) {
                throw MediaConvertStore.badRequest("Only a RESERVED queue has a reservation plan.");
            }
            queue.set("ReservationPlan", reservation(in.get("ReservationPlanSettings"), queue.get(
                    "ReservationPlan")));
            queue.put("ConcurrentJobs", queue.path("ReservationPlan").path("ReservedSlots").asInt());
        }
        queue.put("LastUpdated", MediaConvertStore.epoch());
        store.put(region, MediaConvertStore.QUEUE, queue.path("Name").asText(), queue);
        jobs.get().dispatch();
        return wrap("Queue", queueView(region, queue));
    }

    public ObjectNode deleteQueue(JsonNode in, String region) {
        ObjectNode queue = queue(region, in.path("Name").asText());
        if ("SYSTEM".equals(queue.path("Type").asText())) {
            throw MediaConvertStore.badRequest("The Default queue can't be deleted.");
        }
        ObjectNode view = queueView(region, queue);
        if (view.path("SubmittedJobsCount").asInt() + view.path("ProgressingJobsCount").asInt() > 0) {
            throw MediaConvertStore.conflict("The queue has jobs that haven't finished.");
        }
        store.delete(region, MediaConvertStore.QUEUE, queue.path("Name").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listQueues(JsonNode in, String region) {
        queue(region, DEFAULT_QUEUE);
        List<ObjectNode> queues = new ArrayList<>();
        for (ObjectNode queue : store.list(region, MediaConvertStore.QUEUE)) {
            queues.add(queueView(region, queue));
        }
        sort(queues, in);
        ObjectNode out = mapper.createObjectNode();
        MediaConvertStore.page(queues, in, out, "Queues", 20);
        return out;
    }

    private static void sort(List<ObjectNode> items, JsonNode in) {
        Comparator<ObjectNode> order = "CREATION_DATE".equals(in.path("ListBy").asText("NAME"))
                ? Comparator.comparingDouble(item -> item.path("CreatedAt").asDouble())
                : Comparator.comparing(item -> item.path("Name").asText());
        if ("DESCENDING".equals(in.path("Order").asText())) {
            order = order.reversed();
        }
        items.sort(order);
    }

    // ================================================================ presets

    ObjectNode preset(String region, String nameOrArn) {
        String name = nameOrArn.contains("/") ? nameOrArn.substring(nameOrArn.lastIndexOf('/') + 1) : nameOrArn;
        ObjectNode preset = store.get(region, MediaConvertStore.PRESET, name).orElse(null);
        if (preset == null) {
            preset = MediaConvertSystem.preset(mapper, store.arn(region, "presets/" + name), name);
        }
        if (preset == null) {
            throw MediaConvertStore.notFound("The preset " + nameOrArn + " doesn't exist.");
        }
        return preset;
    }

    private void checkPresetSettings(JsonNode settings) {
        if (!settings.isObject()) {
            throw MediaConvertStore.badRequest("A preset needs Settings.");
        }
        if (!settings.has("ContainerSettings") && !settings.has("VideoDescription")
                && !settings.has("AudioDescriptions")) {
            throw MediaConvertStore.badRequest("A preset's Settings need a container, video or audio.");
        }
        MediaConvertTranscoder.checkOutput(settings);
    }

    public ObjectNode createPreset(JsonNode in, String region) {
        String name = name(in, "preset");
        if (store.get(region, MediaConvertStore.PRESET, name).isPresent() || name.startsWith("System-")) {
            throw MediaConvertStore.conflict("A preset named " + name + " already exists.");
        }
        checkPresetSettings(in.path("Settings"));
        ObjectNode preset = store.record();
        preset.put("Name", name);
        preset.put("Arn", store.arn(region, "presets/" + name));
        preset.put("Type", "CUSTOM");
        MediaConvertStore.copy(in, preset, "Category", "Description", "Settings");
        double now = MediaConvertStore.epoch();
        preset.put("CreatedAt", now);
        preset.put("LastUpdated", now);
        MediaConvertStore.addTags(preset, in.path("Tags"));
        store.put(region, MediaConvertStore.PRESET, name, preset);
        return wrap("Preset", MediaConvertStore.view(preset));
    }

    public ObjectNode getPreset(JsonNode in, String region) {
        return wrap("Preset", MediaConvertStore.view(preset(region, in.path("Name").asText())));
    }

    public ObjectNode updatePreset(JsonNode in, String region) {
        ObjectNode preset = preset(region, in.path("Name").asText());
        if ("SYSTEM".equals(preset.path("Type").asText())) {
            throw MediaConvertStore.badRequest("System presets can't be changed.");
        }
        if (in.has("Settings")) {
            checkPresetSettings(in.get("Settings"));
        }
        MediaConvertStore.copy(in, preset, "Category", "Description", "Settings");
        preset.put("LastUpdated", MediaConvertStore.epoch());
        store.put(region, MediaConvertStore.PRESET, preset.path("Name").asText(), preset);
        return wrap("Preset", MediaConvertStore.view(preset));
    }

    public ObjectNode deletePreset(JsonNode in, String region) {
        ObjectNode preset = preset(region, in.path("Name").asText());
        if ("SYSTEM".equals(preset.path("Type").asText())) {
            throw MediaConvertStore.badRequest("System presets can't be deleted.");
        }
        store.delete(region, MediaConvertStore.PRESET, preset.path("Name").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listPresets(JsonNode in, String region) {
        List<ObjectNode> presets = new ArrayList<>();
        boolean system = "SYSTEM".equals(in.path("ListBy").asText());
        if (system) {
            for (String name : MediaConvertSystem.PRESETS.keySet()) {
                presets.add(MediaConvertSystem.preset(mapper, store.arn(region, "presets/" + name), name));
            }
        } else {
            for (ObjectNode preset : store.list(region, MediaConvertStore.PRESET)) {
                presets.add(MediaConvertStore.view(preset));
            }
        }
        presets.removeIf(preset -> in.hasNonNull("Category") && !in.get("Category").asText().equals(
                preset.path("Category").asText()));
        sort(presets, in);
        ObjectNode out = mapper.createObjectNode();
        MediaConvertStore.page(presets, in, out, "Presets", 20);
        return out;
    }

    // ================================================================ job templates

    ObjectNode template(String region, String nameOrArn) {
        String name = nameOrArn.contains("/") ? nameOrArn.substring(nameOrArn.lastIndexOf('/') + 1) : nameOrArn;
        ObjectNode template = store.get(region, MediaConvertStore.TEMPLATE, name).orElse(null);
        if (template == null) {
            template = MediaConvertSystem.template(mapper, store.arn(region, "jobTemplates/" + name), name,
                    store.arn(region, "queues/" + DEFAULT_QUEUE));
        }
        if (template == null) {
            throw MediaConvertStore.notFound("The job template " + nameOrArn + " doesn't exist.");
        }
        return template;
    }

    private void checkTemplate(String region, JsonNode in) {
        if (in.hasNonNull("Queue")) {
            queue(region, in.get("Queue").asText());
        }
        int priority = in.path("Priority").asInt(0);
        if (priority < -50 || priority > 50) {
            throw MediaConvertStore.badRequest("Priority is between -50 and 50.");
        }
        for (JsonNode group : in.path("Settings").path("OutputGroups")) {
            for (JsonNode output : group.path("Outputs")) {
                if (output.hasNonNull("Preset")) {
                    preset(region, output.get("Preset").asText());
                } else {
                    MediaConvertTranscoder.checkOutput(output);
                }
            }
        }
    }

    public ObjectNode createJobTemplate(JsonNode in, String region) {
        String name = name(in, "job template");
        if (store.get(region, MediaConvertStore.TEMPLATE, name).isPresent() || name.startsWith("System-")) {
            throw MediaConvertStore.conflict("A job template named " + name + " already exists.");
        }
        if (!in.path("Settings").isObject()) {
            throw MediaConvertStore.badRequest("A job template needs Settings.");
        }
        checkTemplate(region, in);
        ObjectNode template = store.record();
        template.put("Name", name);
        template.put("Arn", store.arn(region, "jobTemplates/" + name));
        template.put("Type", "CUSTOM");
        template.put("Priority", in.path("Priority").asInt(0));
        template.put("StatusUpdateInterval", in.path("StatusUpdateInterval").asText("SECONDS_60"));
        template.put("Queue", queue(region, in.path("Queue").asText(DEFAULT_QUEUE)).path("Arn").asText());
        MediaConvertStore.copy(in, template, "Category", "Description", "Settings", "AccelerationSettings",
                "HopDestinations");
        double now = MediaConvertStore.epoch();
        template.put("CreatedAt", now);
        template.put("LastUpdated", now);
        MediaConvertStore.addTags(template, in.path("Tags"));
        store.put(region, MediaConvertStore.TEMPLATE, name, template);
        return wrap("JobTemplate", MediaConvertStore.view(template));
    }

    public ObjectNode getJobTemplate(JsonNode in, String region) {
        return wrap("JobTemplate", MediaConvertStore.view(template(region, in.path("Name").asText())));
    }

    public ObjectNode updateJobTemplate(JsonNode in, String region) {
        ObjectNode template = template(region, in.path("Name").asText());
        if ("SYSTEM".equals(template.path("Type").asText())) {
            throw MediaConvertStore.badRequest("System job templates can't be changed.");
        }
        checkTemplate(region, in);
        MediaConvertStore.copy(in, template, "Category", "Description", "Settings", "AccelerationSettings",
                "HopDestinations", "Priority", "StatusUpdateInterval");
        if (in.hasNonNull("Queue")) {
            template.put("Queue", queue(region, in.get("Queue").asText()).path("Arn").asText());
        }
        template.put("LastUpdated", MediaConvertStore.epoch());
        store.put(region, MediaConvertStore.TEMPLATE, template.path("Name").asText(), template);
        return wrap("JobTemplate", MediaConvertStore.view(template));
    }

    public ObjectNode deleteJobTemplate(JsonNode in, String region) {
        ObjectNode template = template(region, in.path("Name").asText());
        if ("SYSTEM".equals(template.path("Type").asText())) {
            throw MediaConvertStore.badRequest("System job templates can't be deleted.");
        }
        store.delete(region, MediaConvertStore.TEMPLATE, template.path("Name").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listJobTemplates(JsonNode in, String region) {
        List<ObjectNode> templates = new ArrayList<>();
        if ("SYSTEM".equals(in.path("ListBy").asText())) {
            for (String name : MediaConvertSystem.TEMPLATES.keySet()) {
                templates.add(MediaConvertSystem.template(mapper, store.arn(region, "jobTemplates/" + name), name,
                        store.arn(region, "queues/" + DEFAULT_QUEUE)));
            }
        } else {
            for (ObjectNode template : store.list(region, MediaConvertStore.TEMPLATE)) {
                templates.add(MediaConvertStore.view(template));
            }
        }
        templates.removeIf(template -> in.hasNonNull("Category") && !in.get("Category").asText().equals(
                template.path("Category").asText()));
        sort(templates, in);
        ObjectNode out = mapper.createObjectNode();
        MediaConvertStore.page(templates, in, out, "JobTemplates", 20);
        return out;
    }

    // ================================================================ tags

    private record Tagged(String kind, ObjectNode record) {
    }

    private Tagged tagged(String region, String arn) {
        if (!AwsArnUtils.isArn(arn)) {
            throw MediaConvertStore.badRequest("The resource ARN " + arn + " is not valid.");
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String name = resource.substring(resource.indexOf('/') + 1);
        if (resource.startsWith("queues/")) {
            return new Tagged(MediaConvertStore.QUEUE, queue(region, name));
        }
        if (resource.startsWith("presets/")) {
            return new Tagged(MediaConvertStore.PRESET, preset(region, name));
        }
        if (resource.startsWith("jobTemplates/")) {
            return new Tagged(MediaConvertStore.TEMPLATE, template(region, name));
        }
        if (resource.startsWith("jobs/")) {
            return new Tagged(MediaConvertStore.JOB, store.get(region, MediaConvertStore.JOB, name).orElseThrow(() ->
                    MediaConvertStore.notFound("The job " + name + " doesn't exist.")));
        }
        throw MediaConvertStore.badRequest("The resource ARN " + arn + " doesn't name a queue, preset, job template "
                + "or job.");
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        Tagged tagged = tagged(region, in.path("Arn").asText());
        if ("SYSTEM".equals(tagged.record().path("Type").asText())) {
            throw MediaConvertStore.badRequest("System resources can't be tagged.");
        }
        MediaConvertStore.addTags(tagged.record(), in.path("Tags"));
        save(region, tagged);
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        Tagged tagged = tagged(region, in.path("Arn").asText());
        in.path("TagKeys").forEach(key -> MediaConvertStore.tags(tagged.record()).remove(key.asText()));
        save(region, tagged);
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        Tagged tagged = tagged(region, in.path("Arn").asText());
        ObjectNode out = mapper.createObjectNode();
        ObjectNode resourceTags = out.putObject("ResourceTags");
        resourceTags.put("Arn", in.path("Arn").asText());
        resourceTags.set("Tags", MediaConvertStore.tags(tagged.record()).deepCopy());
        return out;
    }

    private void save(String region, Tagged tagged) {
        String id = "job".equals(tagged.kind()) ? tagged.record().path("Id").asText()
                : tagged.record().path("Name").asText();
        store.put(region, tagged.kind(), id, tagged.record());
    }

    // ================================================================ policy, certificates, endpoints, versions

    ObjectNode policy(String region) {
        ObjectNode policy = store.get(region, MediaConvertStore.POLICY, "inputs").map(MediaConvertStore::view)
                .orElseGet(mapper::createObjectNode);
        for (String member : List.of("HttpInputs", "HttpsInputs", "S3Inputs")) {
            if (!policy.has(member)) {
                policy.put(member, "ALLOWED");
            }
        }
        return policy;
    }

    public ObjectNode getPolicy(JsonNode in, String region) {
        return wrap("Policy", policy(region));
    }

    public ObjectNode putPolicy(JsonNode in, String region) {
        if (!in.path("Policy").isObject()) {
            throw MediaConvertStore.badRequest("PutPolicy needs a Policy.");
        }
        ObjectNode policy = store.record();
        MediaConvertStore.copy(in.get("Policy"), policy, "HttpInputs", "HttpsInputs", "S3Inputs");
        store.put(region, MediaConvertStore.POLICY, "inputs", policy);
        return wrap("Policy", policy(region));
    }

    public ObjectNode deletePolicy(JsonNode in, String region) {
        store.delete(region, MediaConvertStore.POLICY, "inputs");
        return mapper.createObjectNode();
    }

    public ObjectNode associateCertificate(JsonNode in, String region) {
        String arn = in.path("Arn").asText("");
        if (!AwsArnUtils.isArnFor(arn, "acm")) {
            throw MediaConvertStore.badRequest("Arn must be an AWS Certificate Manager certificate ARN.");
        }
        ObjectNode certificate = store.record();
        certificate.put("Arn", arn);
        store.put(region, MediaConvertStore.CERTIFICATE, Integer.toHexString(arn.hashCode()), certificate);
        return mapper.createObjectNode();
    }

    public ObjectNode disassociateCertificate(JsonNode in, String region) {
        String arn = in.path("Arn").asText("");
        if (store.get(region, MediaConvertStore.CERTIFICATE, Integer.toHexString(arn.hashCode())).isEmpty()) {
            throw MediaConvertStore.notFound("The certificate " + arn + " isn't associated.");
        }
        store.delete(region, MediaConvertStore.CERTIFICATE, Integer.toHexString(arn.hashCode()));
        return mapper.createObjectNode();
    }

    public ObjectNode describeEndpoints(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode endpoints = out.putArray("Endpoints");
        endpoints.addObject().put("Url", store.endpoint());
        return out;
    }

    public ObjectNode listVersions(JsonNode in, String region) {
        ObjectNode version = mapper.createObjectNode();
        version.put("Version", ENGINE_VERSION);
        version.put("ExpirationDate", Instant.now().plus(365, ChronoUnit.DAYS).toEpochMilli() / 1000.0);
        ObjectNode out = mapper.createObjectNode();
        MediaConvertStore.page(List.of(version), in, out, "Versions", 20);
        return out;
    }
}
