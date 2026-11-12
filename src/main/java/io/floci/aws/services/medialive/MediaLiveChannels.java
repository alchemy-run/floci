package io.floci.aws.services.medialive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MediaLive channels and multiplexes. A channel is {@code CREATING}, then {@code IDLE}; starting it is
 * {@code STARTING}, then {@code RUNNING}, when {@link MediaLiveEncoder} runs its encoder; stopping it is
 * {@code STOPPING}, then {@code IDLE}; deleting it is {@code DELETING} until it is gone. Its encoder settings are
 * checked for the video and audio descriptions and destinations their outputs name. A schedule's actions are kept in
 * order of their start, and input switches run on a running channel when they start. State changes and alerts go to
 * the default event bus as {@code aws.medialive} events. Multiplexes and their programs keep their settings and
 * states; Floci doesn't multiplex transport streams.
 */
@ApplicationScoped
public class MediaLiveChannels {

    private static final Logger LOG = Logger.getLogger(MediaLiveChannels.class);

    private final MediaLiveStore store;
    private final ObjectMapper mapper;
    private final MediaLiveInputs inputs;
    private final Provider<MediaLiveEncoder> encoder;
    private final Provider<EventBridgeService> events;
    private final Vertx vertx;

    @Inject
    public MediaLiveChannels(MediaLiveStore store, MediaLiveInputs inputs, Provider<MediaLiveEncoder> encoder,
                             Provider<EventBridgeService> events, Vertx vertx) {
        this.store = store;
        this.mapper = store.mapper();
        this.inputs = inputs;
        this.encoder = encoder;
        this.events = events;
        this.vertx = vertx;
    }

    // ================================================================ transitions

    ObjectNode channel(String region, String id) {
        ObjectNode channel = store.get(region, MediaLiveStore.CHANNEL, id).orElse(null);
        if (channel != null) {
            settle(store.account(), region, MediaLiveStore.CHANNEL, channel);
            channel = store.get(region, MediaLiveStore.CHANNEL, id).orElse(null);
        }
        if (channel == null) {
            throw MediaLiveStore.notFound("The channel " + id + " doesn't exist.");
        }
        return channel;
    }

    private void settle(String account, String region, String kind, ObjectNode record) {
        ObjectNode meta = MediaLiveStore.meta(record);
        long at = meta.path("transitionAt").asLong(0);
        if (at == 0 || at > System.currentTimeMillis()) {
            return;
        }
        String next = meta.path("next").asText();
        String previous = record.path("State").asText();
        meta.remove("transitionAt");
        meta.remove("next");
        String id = record.path("Id").asText();
        if ("GONE".equals(next)) {
            store.deleteForAccount(account, region, kind, id);
            if (MediaLiveStore.CHANNEL.equals(kind)) {
                encoder.get().stop(record.path("Arn").asText());
            }
            stateChanged(account, region, kind, record, "DELETED", previous);
            return;
        }
        record.put("State", next);
        if (MediaLiveStore.CHANNEL.equals(kind)) {
            boolean running = "RUNNING".equals(next);
            record.put("PipelinesRunningCount", running ? pipelines(record) : 0);
            if (running) {
                ArrayNode details = record.putArray("PipelineDetails");
                for (int pipeline = 0; pipeline < pipelines(record); pipeline++) {
                    ObjectNode detail = details.addObject();
                    detail.put("PipelineId", "PIPELINE_" + pipeline);
                    detail.put("ActiveInputAttachmentName", record.path("InputAttachments").path(0)
                            .path("InputAttachmentName").asText(""));
                }
            } else {
                record.remove("PipelineDetails");
            }
            store.putForAccount(account, region, kind, id, record);
            if (running) {
                encoder.get().start(account, region, record);
            } else if ("IDLE".equals(next)) {
                encoder.get().stop(record.path("Arn").asText());
            }
        } else {
            record.put("PipelinesRunningCount", "RUNNING".equals(next) ? 2 : 0);
            store.putForAccount(account, region, kind, id, record);
        }
        stateChanged(account, region, kind, record, next, previous);
    }

    private void later(String region, String kind, ObjectNode record, String state, String next) {
        String account = store.account();
        long delay = Math.max(1, store.config().transitionMillis());
        record.put("State", state);
        MediaLiveStore.meta(record).put("transitionAt", System.currentTimeMillis() + delay).put("next", next);
        String id = record.path("Id").asText();
        store.put(region, kind, id, record);
        vertx.setTimer(delay, timer -> vertx.executeBlocking(() -> {
            RequestScopes.runAs(account, region, () -> store.getForAccount(account, region, kind, id)
                    .ifPresent(current -> settle(account, region, kind, current)));
            return null;
        }, false));
    }

    static int pipelines(ObjectNode channel) {
        return "SINGLE_PIPELINE".equals(channel.path("ChannelClass").asText()) ? 1 : 2;
    }

    private void stateChanged(String account, String region, String kind, ObjectNode record, String state,
                              String previous) {
        boolean channel = MediaLiveStore.CHANNEL.equals(kind);
        ObjectNode detail = mapper.createObjectNode();
        detail.put(channel ? "channel_arn" : "multiplex_arn", record.path("Arn").asText());
        detail.put("state", state);
        detail.put("previous_state", previous);
        detail.put("message", (channel ? "Channel " : "Multiplex ") + "state changed to " + state + ".");
        detail.put("pipelines_running_count", record.path("PipelinesRunningCount").asInt(0));
        publish(account, region, channel ? "MediaLive Channel State Change" : "MediaLive Multiplex State Change",
                detail, record.path("Arn").asText());
    }

    void publish(String account, String region, String detailType, ObjectNode detail, String resourceArn) {
        try {
            RequestScopes.runAs(account, region, () -> events.get().publishServiceEvent("aws.medialive", detailType,
                    detail.toString(), resourceArn, region));
        } catch (RuntimeException e) {
            LOG.debugv("MediaLive could not send {0} for {1}: {2}", detailType, resourceArn, e.getMessage());
        }
    }

    /** Records an alert on a channel (or clears an earlier one of the type) and sends it as an event. */
    void alert(String account, String region, String channelId, String type, String message, boolean set) {
        store.getForAccount(account, region, MediaLiveStore.CHANNEL, channelId).ifPresent(channel -> {
            ArrayNode alerts = (ArrayNode) MediaLiveStore.meta(channel).withArray("alerts");
            ObjectNode existing = null;
            for (JsonNode alert : alerts) {
                if (type.equals(alert.path("AlertType").asText()) && "SET".equals(alert.path("State").asText())) {
                    existing = (ObjectNode) alert;
                }
            }
            double now = MediaLiveStore.epoch();
            if (set && existing == null) {
                ObjectNode alert = alerts.addObject();
                alert.put("Id", MediaLiveStore.random(12, "abcdef0123456789"));
                alert.put("AlertType", type);
                alert.put("Message", message);
                alert.put("PipelineId", "PIPELINE_0");
                alert.put("SetTimestamp", now);
                alert.put("State", "SET");
            } else if (!set && existing != null) {
                existing.put("State", "CLEARED");
                existing.put("ClearedTimestamp", now);
            } else {
                return;
            }
            store.putForAccount(account, region, MediaLiveStore.CHANNEL, channelId, channel);
            ObjectNode detail = mapper.createObjectNode();
            detail.put("alarm_state", set ? "SET" : "CLEARED");
            detail.put("alert_type", type);
            detail.put("message", message);
            detail.put("channel_arn", channel.path("Arn").asText());
            detail.put("pipeline", "0");
            publish(account, region, "MediaLive Channel Alert", detail, channel.path("Arn").asText());
        });
    }

    // ================================================================ channels

    private ObjectNode wrap(String member, ObjectNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(member, value);
        return out;
    }

    ObjectNode view(ObjectNode channel) {
        ObjectNode view = MediaLiveStore.view(channel);
        view.put("PipelinesRunningCount", "RUNNING".equals(view.path("State").asText()) ? pipelines(channel) : 0);
        return view;
    }

    private void check(String region, ObjectNode channel) {
        Set<String> videos = new HashSet<>();
        Set<String> audios = new HashSet<>();
        Set<String> destinations = new HashSet<>();
        JsonNode settings = channel.path("EncoderSettings");
        settings.path("VideoDescriptions").forEach(video -> videos.add(video.path("Name").asText()));
        settings.path("AudioDescriptions").forEach(audio -> audios.add(audio.path("Name").asText()));
        channel.path("Destinations").forEach(destination -> destinations.add(destination.path("Id").asText()));
        for (JsonNode group : settings.path("OutputGroups")) {
            for (JsonNode reference : group.findValues("DestinationRefId")) {
                if (!destinations.contains(reference.asText())) {
                    throw MediaLiveStore.unprocessable("The output group " + group.path("Name").asText("")
                            + " sends to the destination " + reference.asText() + ", which the channel doesn't "
                            + "have.");
                }
            }
            for (JsonNode output : group.path("Outputs")) {
                if (output.hasNonNull("VideoDescriptionName") && !videos.contains(output.get("VideoDescriptionName")
                        .asText())) {
                    throw MediaLiveStore.unprocessable("The output " + output.path("OutputName").asText("")
                            + " names the missing video description " + output.get("VideoDescriptionName").asText()
                            + ".");
                }
                for (JsonNode audio : output.path("AudioDescriptionNames")) {
                    if (!audios.contains(audio.asText())) {
                        throw MediaLiveStore.unprocessable("The output " + output.path("OutputName").asText("")
                                + " names the missing audio description " + audio.asText() + ".");
                    }
                }
            }
        }
        int pipelines = pipelines(channel);
        for (JsonNode destination : channel.path("Destinations")) {
            int urls = destination.path("Settings").size();
            if (urls > 0 && urls != pipelines) {
                throw MediaLiveStore.unprocessable("The destination " + destination.path("Id").asText() + " needs "
                        + pipelines + " URL" + (pipelines == 1 ? "" : "s") + ", one per pipeline.");
            }
        }
        Set<String> names = new HashSet<>();
        for (JsonNode attachment : channel.path("InputAttachments")) {
            ObjectNode input = inputs.input(region, attachment.path("InputId").asText());
            if (!names.add(attachment.path("InputAttachmentName").asText(input.path("Id").asText()))) {
                throw MediaLiveStore.badRequest("Input attachment names are unique within a channel.");
            }
            if ("SINGLE_PIPELINE".equals(input.path("InputClass").asText()) && pipelines == 2) {
                throw MediaLiveStore.unprocessable("The SINGLE_PIPELINE input " + input.path("Id").asText()
                        + " can't feed a STANDARD channel.");
            }
        }
        if (channel.hasNonNull("RoleArn") && !AwsArnUtils.isArnFor(channel.get("RoleArn").asText(), "iam")) {
            throw MediaLiveStore.badRequest("RoleArn must be an IAM role ARN.");
        }
    }

    public ObjectNode createChannel(JsonNode in, String region) {
        String token = in.path("RequestId").asText(null);
        if (token != null) {
            for (ObjectNode existing : store.list(region, MediaLiveStore.CHANNEL)) {
                if (token.equals(existing.path("_").path("requestId").asText())) {
                    return wrap("Channel", view(existing));
                }
            }
        }
        ObjectNode channel = store.record();
        String id = store.numericId(region, MediaLiveStore.CHANNEL);
        channel.put("Id", id);
        channel.put("Arn", store.arn(region, "channel:" + id));
        channel.put("Name", in.path("Name").asText(""));
        channel.put("ChannelClass", in.path("ChannelClass").asText("STANDARD"));
        channel.put("LogLevel", in.path("LogLevel").asText("DISABLED"));
        MediaLiveStore.copy(in, channel, "RoleArn", "InputSpecification", "CdiInputSpecification",
                "AnywhereSettings", "LinkedChannelSettings", "ChannelSecurityGroups", "InferenceSettings");
        channel.set("Destinations", in.path("Destinations").isArray() ? in.get("Destinations").deepCopy()
                : mapper.createArrayNode());
        channel.set("EncoderSettings", in.path("EncoderSettings").isObject() ? in.get("EncoderSettings").deepCopy()
                : mapper.createObjectNode());
        channel.set("InputAttachments", in.path("InputAttachments").isArray() ? in.get("InputAttachments")
                .deepCopy() : mapper.createArrayNode());
        if (!channel.has("InputSpecification")) {
            channel.putObject("InputSpecification").put("Codec", "AVC").put("MaximumBitrate", "MAX_20_MBPS")
                    .put("Resolution", "HD");
        }
        ArrayNode egress = channel.putArray("EgressEndpoints");
        for (int pipeline = 0; pipeline < pipelines(channel); pipeline++) {
            egress.addObject().put("SourceIp", store.config().egressIp());
        }
        JsonNode maintenance = in.path("Maintenance");
        channel.putObject("Maintenance").put("MaintenanceDay", maintenance.path("MaintenanceDay").asText("SUNDAY"))
                .put("MaintenanceStartTime", maintenance.path("MaintenanceStartTime").asText("02:00"));
        channel.putObject("ChannelEngineVersion").put("Version", in.path("ChannelEngineVersion").path("Version")
                .asText(MediaLiveResources.ENGINE_VERSION));
        if (in.has("Vpc")) {
            ObjectNode vpc = channel.putObject("Vpc");
            MediaLiveStore.copy(in.get("Vpc"), vpc, "SubnetIds", "SecurityGroupIds");
            vpc.putArray("AvailabilityZones").add(region + "a");
            vpc.putArray("NetworkInterfaceIds");
        }
        channel.putObject("Tags");
        MediaLiveInputs.setTags(channel, in.path("Tags"));
        MediaLiveStore.meta(channel).put("requestId", token).putArray("schedule");
        check(region, channel);
        if (in.path("DryRun").asBoolean(false)) {
            channel.put("State", "IDLE");
            return wrap("Channel", view(channel));
        }
        inputs.attach(region, id, channel.path("InputAttachments"), true);
        later(region, MediaLiveStore.CHANNEL, channel, "CREATING", "IDLE");
        return wrap("Channel", view(channel));
    }

    public ObjectNode describeChannel(JsonNode in, String region) {
        return view(channel(region, in.path("ChannelId").asText()));
    }

    public ObjectNode updateChannel(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelId").asText());
        if (!"IDLE".equals(channel.path("State").asText())) {
            throw MediaLiveStore.conflict("The channel is " + channel.path("State").asText()
                    + "; only an IDLE channel is updated.");
        }
        ObjectNode updated = channel.deepCopy();
        MediaLiveStore.copy(in, updated, "Name", "LogLevel", "RoleArn", "Destinations", "EncoderSettings",
                "InputAttachments", "InputSpecification", "CdiInputSpecification", "AnywhereSettings",
                "LinkedChannelSettings", "ChannelSecurityGroups", "InferenceSettings");
        if (in.has("Maintenance")) {
            ObjectNode maintenance = (ObjectNode) updated.withObject("/Maintenance");
            MediaLiveStore.copy(in.get("Maintenance"), maintenance, "MaintenanceDay", "MaintenanceStartTime",
                    "MaintenanceScheduledDate");
        }
        if (in.has("ChannelEngineVersion")) {
            updated.withObject("/ChannelEngineVersion").put("Version", in.get("ChannelEngineVersion")
                    .path("Version").asText());
        }
        check(region, updated);
        if (in.path("DryRun").asBoolean(false)) {
            return wrap("Channel", view(updated));
        }
        inputs.attach(region, updated.path("Id").asText(), channel.path("InputAttachments"), false);
        inputs.attach(region, updated.path("Id").asText(), updated.path("InputAttachments"), true);
        store.put(region, MediaLiveStore.CHANNEL, updated.path("Id").asText(), updated);
        return wrap("Channel", view(updated));
    }

    public ObjectNode updateChannelClass(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelId").asText());
        if (!"IDLE".equals(channel.path("State").asText())) {
            throw MediaLiveStore.conflict("The channel is " + channel.path("State").asText()
                    + "; only an IDLE channel changes class.");
        }
        ObjectNode updated = channel.deepCopy();
        updated.put("ChannelClass", in.path("ChannelClass").asText());
        if (in.has("Destinations")) {
            updated.set("Destinations", in.get("Destinations").deepCopy());
        }
        ArrayNode egress = updated.putArray("EgressEndpoints");
        for (int pipeline = 0; pipeline < pipelines(updated); pipeline++) {
            egress.addObject().put("SourceIp", store.config().egressIp());
        }
        check(region, updated);
        store.put(region, MediaLiveStore.CHANNEL, updated.path("Id").asText(), updated);
        return wrap("Channel", view(updated));
    }

    public ObjectNode deleteChannel(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelId").asText());
        String state = channel.path("State").asText();
        if (!"IDLE".equals(state) && !"CREATE_FAILED".equals(state)) {
            throw MediaLiveStore.conflict("The channel is " + state + "; stop it before deleting it.");
        }
        inputs.attach(region, channel.path("Id").asText(), channel.path("InputAttachments"), false);
        later(region, MediaLiveStore.CHANNEL, channel, "DELETING", "GONE");
        return view(channel);
    }

    public ObjectNode listChannels(JsonNode in, String region) {
        List<ObjectNode> channels = new ArrayList<>();
        for (ObjectNode stored : store.list(region, MediaLiveStore.CHANNEL)) {
            settle(store.account(), region, MediaLiveStore.CHANNEL, stored);
            store.get(region, MediaLiveStore.CHANNEL, stored.path("Id").asText()).ifPresent(channel -> {
                ObjectNode summary = view(channel);
                summary.remove("EncoderSettings");
                summary.remove("PipelineDetails");
                channels.add(summary);
            });
        }
        ObjectNode out = mapper.createObjectNode();
        MediaLiveStore.page(channels, in, out, "Channels", 1000);
        return out;
    }

    public ObjectNode startChannel(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelId").asText());
        if (!"IDLE".equals(channel.path("State").asText())) {
            throw MediaLiveStore.conflict("The channel is " + channel.path("State").asText()
                    + " and can't be started.");
        }
        if (channel.path("InputAttachments").isEmpty()) {
            throw MediaLiveStore.unprocessable("The channel has no input attachments.");
        }
        later(region, MediaLiveStore.CHANNEL, channel, "STARTING", "RUNNING");
        return view(channel);
    }

    public ObjectNode stopChannel(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelId").asText());
        String state = channel.path("State").asText();
        if (!"RUNNING".equals(state) && !"STARTING".equals(state) && !"RECOVERING".equals(state)) {
            throw MediaLiveStore.conflict("The channel is " + state + " and can't be stopped.");
        }
        encoder.get().stop(channel.path("Arn").asText());
        later(region, MediaLiveStore.CHANNEL, channel, "STOPPING", "IDLE");
        return view(channel);
    }

    public ObjectNode restartChannelPipelines(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelId").asText());
        if (!"RUNNING".equals(channel.path("State").asText())) {
            throw MediaLiveStore.badRequest("Only a RUNNING channel's pipelines restart.");
        }
        encoder.get().stop(channel.path("Arn").asText());
        encoder.get().start(store.account(), region, channel);
        return view(channel);
    }

    // ================================================================ schedule

    private static double startOf(JsonNode action) {
        JsonNode start = action.path("ScheduleActionStartSettings");
        if (start.has("FixedModeScheduleActionStartSettings")) {
            try {
                return Instant.parse(start.get("FixedModeScheduleActionStartSettings").path("Time").asText())
                        .toEpochMilli() / 1000.0;
            } catch (DateTimeParseException e) {
                throw MediaLiveStore.unprocessable("A fixed start Time is ISO 8601, YYYY-MM-DDTHH:MM:SS.SSSZ.");
            }
        }
        return 0;
    }

    public ObjectNode batchUpdateSchedule(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelId").asText());
        ArrayNode schedule = (ArrayNode) MediaLiveStore.meta(channel).withArray("schedule");
        ObjectNode out = mapper.createObjectNode();
        ArrayNode deleted = out.putObject("Deletes").putArray("ScheduleActions");
        for (JsonNode name : in.path("Deletes").path("ActionNames")) {
            boolean found = false;
            for (int i = schedule.size() - 1; i >= 0; i--) {
                if (name.asText().equals(schedule.get(i).path("ActionName").asText())) {
                    deleted.add(schedule.remove(i));
                    found = true;
                }
            }
            if (!found) {
                throw MediaLiveStore.unprocessable("The schedule has no action named " + name.asText() + ".");
            }
        }
        ArrayNode created = out.putObject("Creates").putArray("ScheduleActions");
        Set<String> attachments = new HashSet<>();
        channel.path("InputAttachments").forEach(a -> attachments.add(a.path("InputAttachmentName").asText()));
        for (JsonNode action : in.path("Creates").path("ScheduleActions")) {
            String name = action.path("ActionName").asText("");
            for (JsonNode existing : schedule) {
                if (name.equals(existing.path("ActionName").asText())) {
                    throw MediaLiveStore.unprocessable("The schedule already has an action named " + name + ".");
                }
            }
            JsonNode start = action.path("ScheduleActionStartSettings");
            if (start.has("ImmediateModeScheduleActionStartSettings")
                    && !"RUNNING".equals(channel.path("State").asText())) {
                throw MediaLiveStore.unprocessable("Immediate actions need a RUNNING channel.");
            }
            if (start.has("FixedModeScheduleActionStartSettings")) {
                startOf(action);
            }
            JsonNode inputSwitch = action.path("ScheduleActionSettings").path("InputSwitchSettings");
            if (inputSwitch.isObject() && !attachments.contains(inputSwitch.path("InputAttachmentNameReference")
                    .asText())) {
                throw MediaLiveStore.unprocessable("The input switch names the missing input attachment "
                        + inputSwitch.path("InputAttachmentNameReference").asText() + ".");
            }
            schedule.add(action.deepCopy());
            created.add(action.deepCopy());
            if (inputSwitch.isObject() && "RUNNING".equals(channel.path("State").asText())) {
                double delay = Math.max(0, startOf(action) * 1000 - System.currentTimeMillis());
                String account = store.account();
                String channelId = channel.path("Id").asText();
                String target = inputSwitch.path("InputAttachmentNameReference").asText();
                vertx.setTimer(Math.max(1, (long) delay), timer -> vertx.executeBlocking(() -> {
                    RequestScopes.runAs(account, region, () -> switchInput(account, region, channelId, target));
                    return null;
                }, false));
            }
        }
        List<JsonNode> sorted = new ArrayList<>();
        schedule.forEach(sorted::add);
        sorted.sort((a, b) -> Double.compare(startOf(a), startOf(b)));
        schedule.removeAll();
        sorted.forEach(schedule::add);
        store.put(region, MediaLiveStore.CHANNEL, channel.path("Id").asText(), channel);
        return out;
    }

    private void switchInput(String account, String region, String channelId, String attachmentName) {
        store.getForAccount(account, region, MediaLiveStore.CHANNEL, channelId).ifPresent(channel -> {
            if (!"RUNNING".equals(channel.path("State").asText())) {
                return;
            }
            for (JsonNode detail : channel.path("PipelineDetails")) {
                ((ObjectNode) detail).put("ActiveInputAttachmentName", attachmentName);
            }
            store.putForAccount(account, region, MediaLiveStore.CHANNEL, channelId, channel);
            encoder.get().stop(channel.path("Arn").asText());
            encoder.get().start(account, region, channel);
            ObjectNode detail = mapper.createObjectNode();
            detail.put("channel_arn", channel.path("Arn").asText());
            detail.put("message", "Switched to the input attachment " + attachmentName + ".");
            detail.put("active_input_attachment_name", attachmentName);
            publish(account, region, "MediaLive Channel Input Change", detail, channel.path("Arn").asText());
        });
    }

    public ObjectNode describeSchedule(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelId").asText());
        List<JsonNode> actions = new ArrayList<>();
        channel.path("_").path("schedule").forEach(actions::add);
        ObjectNode out = mapper.createObjectNode();
        MediaLiveStore.page(actions, in, out, "ScheduleActions", 100);
        return out;
    }

    public ObjectNode deleteSchedule(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelId").asText());
        MediaLiveStore.meta(channel).putArray("schedule");
        store.put(region, MediaLiveStore.CHANNEL, channel.path("Id").asText(), channel);
        return mapper.createObjectNode();
    }

    // ================================================================ alerts and thumbnails

    public ObjectNode listAlerts(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelId").asText());
        String filter = in.path("StateFilter").asText("ALL");
        List<JsonNode> alerts = new ArrayList<>();
        for (JsonNode alert : channel.path("_").path("alerts")) {
            if ("ALL".equals(filter) || filter.equals(alert.path("State").asText())) {
                alerts.add(alert.deepCopy());
            }
        }
        ObjectNode out = mapper.createObjectNode();
        MediaLiveStore.page(alerts, in, out, "Alerts", 100);
        return out;
    }

    public ObjectNode describeThumbnails(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelId").asText());
        if (!"RUNNING".equals(channel.path("State").asText())) {
            throw MediaLiveStore.badRequest("The channel is " + channel.path("State").asText()
                    + "; thumbnails come from a RUNNING channel.");
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode details = out.putArray("ThumbnailDetails");
        String pipeline = in.path("PipelineId").asText("0");
        String image = encoder.get().thumbnail(channel.path("Arn").asText());
        ObjectNode detail = details.addObject();
        detail.put("PipelineId", pipeline);
        ArrayNode thumbnails = detail.putArray("Thumbnails");
        if (image != null) {
            ObjectNode thumbnail = thumbnails.addObject();
            thumbnail.put("Body", image);
            thumbnail.put("ContentType", "image/jpeg");
            thumbnail.put("ThumbnailType", "CURRENT_ACTIVE");
            thumbnail.put("TimeStamp", MediaLiveStore.epoch());
        }
        return out;
    }

    // ================================================================ batches

    public ObjectNode batchStart(JsonNode in, String region) {
        return batch(in, region, "start");
    }

    public ObjectNode batchStop(JsonNode in, String region) {
        return batch(in, region, "stop");
    }

    public ObjectNode batchDelete(JsonNode in, String region) {
        return batch(in, region, "delete");
    }

    private ObjectNode batch(JsonNode in, String region, String action) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode successful = out.putArray("Successful");
        ArrayNode failed = out.putArray("Failed");
        List<String[]> targets = new ArrayList<>();
        in.path("ChannelIds").forEach(id -> targets.add(new String[] {"channel", id.asText()}));
        in.path("MultiplexIds").forEach(id -> targets.add(new String[] {"multiplex", id.asText()}));
        in.path("InputIds").forEach(id -> targets.add(new String[] {"input", id.asText()}));
        in.path("InputSecurityGroupIds").forEach(id -> targets.add(new String[] {"isg", id.asText()}));
        for (String[] target : targets) {
            ObjectNode request = mapper.createObjectNode();
            try {
                ObjectNode result = switch (target[0]) {
                    case "channel" -> {
                        request.put("ChannelId", target[1]);
                        yield switch (action) {
                            case "start" -> startChannel(request, region);
                            case "stop" -> stopChannel(request, region);
                            default -> deleteChannel(request, region);
                        };
                    }
                    case "multiplex" -> {
                        request.put("MultiplexId", target[1]);
                        yield switch (action) {
                            case "start" -> startMultiplex(request, region);
                            case "stop" -> stopMultiplex(request, region);
                            default -> deleteMultiplex(request, region);
                        };
                    }
                    case "input" -> {
                        request.put("InputId", target[1]);
                        ObjectNode input = inputs.inputView(inputs.input(region, target[1]));
                        inputs.deleteInput(request, region);
                        yield input;
                    }
                    default -> {
                        request.put("InputSecurityGroupId", target[1]);
                        ObjectNode group = inputs.describeInputSecurityGroup(request, region);
                        inputs.deleteInputSecurityGroup(request, region);
                        yield group;
                    }
                };
                successful.addObject().put("Arn", result.path("Arn").asText()).put("Id", target[1])
                        .put("State", result.path("State").asText("DELETED"));
            } catch (AwsException e) {
                failed.addObject().put("Id", target[1]).put("Code", e.getErrorCode()).put("Message",
                        e.getMessage());
            }
        }
        return out;
    }

    // ================================================================ multiplexes

    ObjectNode multiplex(String region, String id) {
        ObjectNode multiplex = store.get(region, MediaLiveStore.MULTIPLEX, id).orElse(null);
        if (multiplex != null) {
            settle(store.account(), region, MediaLiveStore.MULTIPLEX, multiplex);
            multiplex = store.get(region, MediaLiveStore.MULTIPLEX, id).orElse(null);
        }
        if (multiplex == null) {
            throw MediaLiveStore.notFound("The multiplex " + id + " doesn't exist.");
        }
        return multiplex;
    }

    private ObjectNode multiplexView(String region, ObjectNode multiplex) {
        ObjectNode view = MediaLiveStore.view(multiplex);
        int programs = 0;
        for (ObjectNode program : store.list(region, MediaLiveStore.PROGRAM)) {
            programs += multiplex.path("Id").asText().equals(program.path("_").path("multiplex").asText()) ? 1 : 0;
        }
        view.put("ProgramCount", programs);
        return view;
    }

    public ObjectNode createMultiplex(JsonNode in, String region) {
        if (!in.path("AvailabilityZones").isArray() || in.path("AvailabilityZones").size() != 2) {
            throw MediaLiveStore.badRequest("A multiplex runs in two Availability Zones.");
        }
        JsonNode settings = in.path("MultiplexSettings");
        if (!settings.has("TransportStreamBitrate") || !settings.has("TransportStreamId")) {
            throw MediaLiveStore.badRequest("MultiplexSettings needs TransportStreamBitrate and TransportStreamId.");
        }
        String id = store.numericId(region, MediaLiveStore.MULTIPLEX);
        ObjectNode multiplex = store.record();
        multiplex.put("Id", id);
        multiplex.put("Arn", store.arn(region, "multiplex:" + id));
        multiplex.put("Name", in.path("Name").asText(""));
        multiplex.set("AvailabilityZones", in.get("AvailabilityZones").deepCopy());
        multiplex.set("MultiplexSettings", settings.deepCopy());
        ArrayNode destinations = multiplex.putArray("Destinations");
        for (int pipeline = 0; pipeline < 2; pipeline++) {
            destinations.addObject().putObject("MediaConnectSettings").put("EntitlementArn",
                    AwsArnUtils.Arn.of("mediaconnect", region, store.account(), "entitlement:"
                            + "1-" + MediaLiveStore.random(16, "ABCDEFabcdef0123456789") + "-"
                            + MediaLiveStore.random(12, "abcdef0123456789") + ":multiplex-" + id + "-" + pipeline)
                            .toString());
        }
        multiplex.put("PipelinesRunningCount", 0);
        multiplex.putObject("Tags");
        MediaLiveInputs.setTags(multiplex, in.path("Tags"));
        later(region, MediaLiveStore.MULTIPLEX, multiplex, "CREATING", "IDLE");
        return wrap("Multiplex", multiplexView(region, multiplex));
    }

    public ObjectNode describeMultiplex(JsonNode in, String region) {
        return multiplexView(region, multiplex(region, in.path("MultiplexId").asText()));
    }

    public ObjectNode updateMultiplex(JsonNode in, String region) {
        ObjectNode multiplex = multiplex(region, in.path("MultiplexId").asText());
        MediaLiveStore.copy(in, multiplex, "Name", "MultiplexSettings");
        store.put(region, MediaLiveStore.MULTIPLEX, multiplex.path("Id").asText(), multiplex);
        return wrap("Multiplex", multiplexView(region, multiplex));
    }

    public ObjectNode deleteMultiplex(JsonNode in, String region) {
        ObjectNode multiplex = multiplex(region, in.path("MultiplexId").asText());
        if (!"IDLE".equals(multiplex.path("State").asText())) {
            throw MediaLiveStore.conflict("The multiplex is " + multiplex.path("State").asText()
                    + "; stop it before deleting it.");
        }
        if (multiplexView(region, multiplex).path("ProgramCount").asInt() > 0) {
            throw MediaLiveStore.conflict("The multiplex has programs; delete them first.");
        }
        later(region, MediaLiveStore.MULTIPLEX, multiplex, "DELETING", "GONE");
        return multiplexView(region, multiplex);
    }

    public ObjectNode listMultiplexes(JsonNode in, String region) {
        List<ObjectNode> multiplexes = new ArrayList<>();
        for (ObjectNode stored : store.list(region, MediaLiveStore.MULTIPLEX)) {
            settle(store.account(), region, MediaLiveStore.MULTIPLEX, stored);
            store.get(region, MediaLiveStore.MULTIPLEX, stored.path("Id").asText()).ifPresent(multiplex ->
                    multiplexes.add(multiplexView(region, multiplex)));
        }
        ObjectNode out = mapper.createObjectNode();
        MediaLiveStore.page(multiplexes, in, out, "Multiplexes", 1000);
        return out;
    }

    public ObjectNode startMultiplex(JsonNode in, String region) {
        ObjectNode multiplex = multiplex(region, in.path("MultiplexId").asText());
        if (!"IDLE".equals(multiplex.path("State").asText())) {
            throw MediaLiveStore.conflict("The multiplex is " + multiplex.path("State").asText()
                    + " and can't be started.");
        }
        later(region, MediaLiveStore.MULTIPLEX, multiplex, "STARTING", "RUNNING");
        return multiplexView(region, multiplex);
    }

    public ObjectNode stopMultiplex(JsonNode in, String region) {
        ObjectNode multiplex = multiplex(region, in.path("MultiplexId").asText());
        if (!"RUNNING".equals(multiplex.path("State").asText())) {
            throw MediaLiveStore.conflict("The multiplex is " + multiplex.path("State").asText()
                    + " and can't be stopped.");
        }
        later(region, MediaLiveStore.MULTIPLEX, multiplex, "STOPPING", "IDLE");
        return multiplexView(region, multiplex);
    }

    public ObjectNode listMultiplexAlerts(JsonNode in, String region) {
        multiplex(region, in.path("MultiplexId").asText());
        ObjectNode out = mapper.createObjectNode();
        out.putArray("MultiplexAlerts");
        return out;
    }

    private String programKey(String multiplexId, String name) {
        return multiplexId + "/" + name;
    }

    private ObjectNode program(String region, String multiplexId, String name) {
        return store.get(region, MediaLiveStore.PROGRAM, programKey(multiplexId, name)).orElseThrow(() ->
                MediaLiveStore.notFound("The multiplex program " + name + " doesn't exist."));
    }

    public ObjectNode createMultiplexProgram(JsonNode in, String region) {
        ObjectNode multiplex = multiplex(region, in.path("MultiplexId").asText());
        String name = in.path("ProgramName").asText("");
        if (name.isEmpty()) {
            throw MediaLiveStore.badRequest("A multiplex program needs a ProgramName.");
        }
        if (store.get(region, MediaLiveStore.PROGRAM, programKey(multiplex.path("Id").asText(), name)).isPresent()) {
            throw MediaLiveStore.conflict("The multiplex already has a program named " + name + ".");
        }
        JsonNode settings = in.path("MultiplexProgramSettings");
        if (!settings.has("ProgramNumber")) {
            throw MediaLiveStore.badRequest("MultiplexProgramSettings needs a ProgramNumber.");
        }
        ObjectNode program = store.record();
        program.put("ProgramName", name);
        program.set("MultiplexProgramSettings", settings.deepCopy());
        int number = settings.path("ProgramNumber").asInt();
        ObjectNode pids = program.putObject("PacketIdentifiersMap");
        pids.put("PmtPid", 480 + number);
        pids.putArray("VideoPid").add(481 + number);
        pids.putArray("AudioPids").add(482 + number);
        pids.put("PcrPid", 481 + number);
        ArrayNode details = program.putArray("PipelineDetails");
        details.addObject().put("PipelineId", "0");
        details.addObject().put("PipelineId", "1");
        program.putObject("_").put("multiplex", multiplex.path("Id").asText()).put("seq", System.nanoTime());
        store.put(region, MediaLiveStore.PROGRAM, programKey(multiplex.path("Id").asText(), name), program);
        return wrap("MultiplexProgram", MediaLiveStore.view(program));
    }

    public ObjectNode describeMultiplexProgram(JsonNode in, String region) {
        return MediaLiveStore.view(program(region, in.path("MultiplexId").asText(), in.path("ProgramName")
                .asText()));
    }

    public ObjectNode updateMultiplexProgram(JsonNode in, String region) {
        ObjectNode program = program(region, in.path("MultiplexId").asText(), in.path("ProgramName").asText());
        MediaLiveStore.copy(in, program, "MultiplexProgramSettings");
        store.put(region, MediaLiveStore.PROGRAM, programKey(in.path("MultiplexId").asText(), in.path("ProgramName")
                .asText()), program);
        return wrap("MultiplexProgram", MediaLiveStore.view(program));
    }

    public ObjectNode deleteMultiplexProgram(JsonNode in, String region) {
        ObjectNode program = program(region, in.path("MultiplexId").asText(), in.path("ProgramName").asText());
        store.delete(region, MediaLiveStore.PROGRAM, programKey(in.path("MultiplexId").asText(),
                in.path("ProgramName").asText()));
        return MediaLiveStore.view(program);
    }

    public ObjectNode listMultiplexPrograms(JsonNode in, String region) {
        String multiplexId = multiplex(region, in.path("MultiplexId").asText()).path("Id").asText();
        List<ObjectNode> programs = new ArrayList<>();
        for (ObjectNode program : store.list(region, MediaLiveStore.PROGRAM)) {
            if (multiplexId.equals(program.path("_").path("multiplex").asText())) {
                ObjectNode summary = mapper.createObjectNode();
                summary.put("ProgramName", program.path("ProgramName").asText());
                summary.put("ChannelId", program.path("MultiplexProgramSettings").path("ChannelId").asText(""));
                programs.add(summary);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        MediaLiveStore.page(programs, in, out, "MultiplexPrograms", 1000);
        return out;
    }

}
