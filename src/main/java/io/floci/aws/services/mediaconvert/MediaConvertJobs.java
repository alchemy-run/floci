package io.floci.aws.services.mediaconvert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MediaConvert jobs. A job's settings are its template's with its own over them, and each output's preset under the
 * output's own settings. It's {@code SUBMITTED} to its queue, and runs when the queue is active and has fewer jobs
 * progressing than its concurrent jobs, the highest priority first: {@code PROGRESSING} while its inputs are read
 * from S3 or HTTP(S) and probed, transcoded by {@link MediaConvertTranscoder}, and its outputs written to their
 * groups' destinations, then {@code COMPLETE}, or {@code ERROR} with MediaConvert's error code. State changes, input
 * information and output details go to the default event bus as {@code MediaConvert Job State Change} events.
 */
@ApplicationScoped
public class MediaConvertJobs {

    private static final Logger LOG = Logger.getLogger(MediaConvertJobs.class);
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final MediaConvertStore store;
    private final ObjectMapper mapper;
    private final MediaConvertResources resources;
    private final MediaConvertTranscoder transcoder;
    private final Provider<S3Service> s3;
    private final Provider<IamService> iam;
    private final Provider<EventBridgeService> events;
    private final ExecutorService workers;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Inject
    public MediaConvertJobs(MediaConvertStore store, MediaConvertResources resources,
                            MediaConvertTranscoder transcoder, Provider<S3Service> s3, Provider<IamService> iam,
                            Provider<EventBridgeService> events) {
        this.store = store;
        this.mapper = store.mapper();
        this.resources = resources;
        this.transcoder = transcoder;
        this.s3 = s3;
        this.iam = iam;
        this.events = events;
        this.workers = Executors.newFixedThreadPool(Math.max(1, store.config().maxRunningJobs()), runnable -> {
            Thread thread = new Thread(runnable, "mediaconvert-job");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    // ================================================================ creating

    public ObjectNode createJob(JsonNode in, String region) {
        String token = in.path("ClientRequestToken").asText(null);
        if (token != null) {
            for (ObjectNode job : store.list(region, MediaConvertStore.JOB)) {
                if (token.equals(job.path("ClientRequestToken").asText())) {
                    return wrap(job);
                }
            }
        }
        checkRole(in.path("Role").asText(""));
        ObjectNode template = in.hasNonNull("JobTemplate") ? resources.template(region, in.get("JobTemplate")
                .asText()) : null;
        ObjectNode settings = resolve(region, in.path("Settings"), template);
        ObjectNode policy = resources.policy(region);
        for (JsonNode input : settings.path("Inputs")) {
            String file = input.path("FileInput").asText("");
            String scheme = file.contains("://") ? file.substring(0, file.indexOf("://")).toLowerCase(Locale.ROOT)
                    : "";
            String member = switch (scheme) {
                case "s3" -> "S3Inputs";
                case "http" -> "HttpInputs";
                case "https" -> "HttpsInputs";
                default -> throw MediaConvertStore.badRequest("/inputs: FileInput " + file + " must be an s3://, "
                        + "http:// or https:// URL.");
            };
            if ("DISALLOWED".equals(policy.path(member).asText())) {
                throw MediaConvertStore.forbidden("The account's policy disallows " + scheme + " inputs.");
            }
        }
        String queueName = in.path("Queue").asText(template == null ? MediaConvertResources.DEFAULT_QUEUE
                : template.path("Queue").asText(MediaConvertResources.DEFAULT_QUEUE));
        ObjectNode queue = resources.queue(region, queueName);
        String id = System.currentTimeMillis() + "-" + MediaConvertStore.random(6, LOWER);
        ObjectNode job = store.record();
        job.put("Id", id);
        job.put("Arn", store.arn(region, "jobs/" + id));
        job.put("Role", in.path("Role").asText());
        job.set("Settings", in.path("Settings").deepCopy());
        if (template != null) {
            job.put("JobTemplate", template.path("Arn").asText());
        }
        job.put("Queue", queue.path("Arn").asText());
        job.put("Status", "SUBMITTED");
        job.put("Priority", in.path("Priority").asInt(template == null ? 0 : template.path("Priority").asInt(0)));
        job.put("StatusUpdateInterval", in.path("StatusUpdateInterval").asText(template == null ? "SECONDS_60"
                : template.path("StatusUpdateInterval").asText("SECONDS_60")));
        job.put("AccelerationStatus", "NOT_APPLICABLE");
        job.put("BillingTagsSource", in.path("BillingTagsSource").asText("JOB"));
        job.put("SimulateReservedQueue", in.path("SimulateReservedQueue").asText("DISABLED"));
        job.put("JobEngineVersionRequested", in.path("JobEngineVersion").asText(
                MediaConvertResources.ENGINE_VERSION));
        job.put("JobEngineVersionUsed", MediaConvertResources.ENGINE_VERSION);
        job.put("RetryCount", 0);
        job.put("ShareStatus", "NOT_SHARED");
        MediaConvertStore.copy(in, job, "UserMetadata", "AccelerationSettings", "HopDestinations",
                "ClientRequestToken");
        if (!job.has("UserMetadata")) {
            job.putObject("UserMetadata");
        }
        double now = MediaConvertStore.epoch();
        job.put("CreatedAt", now);
        job.putObject("Timing").put("SubmitTime", now);
        ObjectNode messages = job.putObject("Messages");
        messages.putArray("Info");
        messages.putArray("Warning");
        MediaConvertStore.meta(job).set("resolved", settings);
        MediaConvertStore.addTags(job, in.path("Tags"));
        store.put(region, MediaConvertStore.JOB, id, job);
        dispatch();
        return wrap(job);
    }

    private ObjectNode wrap(ObjectNode job) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Job", MediaConvertStore.view(job));
        return out;
    }

    private void checkRole(String roleArn) {
        if (!AwsArnUtils.isArnFor(roleArn, "iam") || !AwsArnUtils.parse(roleArn).resource().startsWith("role/")) {
            throw MediaConvertStore.badRequest("/role: Role must be an IAM role ARN.");
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        boolean exists;
        try {
            exists = iam.get().findRole(arn.accountId(), name).isPresent();
        } catch (RuntimeException e) {
            exists = false;
        }
        if (!exists) {
            throw MediaConvertStore.badRequest("Unable to assume role " + roleArn + ": the role doesn't exist or "
                    + "doesn't trust the MediaConvert service.");
        }
    }

    /** The settings a job runs: its template's under its own, each output's preset under the output. */
    private ObjectNode resolve(String region, JsonNode settings, ObjectNode template) {
        ObjectNode resolved = template == null ? mapper.createObjectNode() : template.path("Settings").deepCopy();
        if (settings.isObject()) {
            ArrayNode templateInputs = resolved.has("Inputs") ? (ArrayNode) resolved.get("Inputs") : null;
            settings.properties().forEach(entry -> resolved.set(entry.getKey(), entry.getValue().deepCopy()));
            if (templateInputs != null && settings.has("Inputs")) {
                ArrayNode inputs = resolved.putArray("Inputs");
                for (int i = 0; i < settings.get("Inputs").size(); i++) {
                    ObjectNode input = i < templateInputs.size() ? templateInputs.get(i).deepCopy()
                            : mapper.createObjectNode();
                    input.setAll((ObjectNode) settings.get("Inputs").get(i));
                    inputs.add(input);
                }
            }
        }
        if (!resolved.path("Inputs").isArray() || resolved.path("Inputs").isEmpty()) {
            throw MediaConvertStore.badRequest("/settings: a job needs at least one input.");
        }
        for (JsonNode input : resolved.path("Inputs")) {
            if (!input.hasNonNull("FileInput")) {
                throw MediaConvertStore.badRequest("/inputs: each input needs a FileInput.");
            }
        }
        if (!resolved.path("OutputGroups").isArray() || resolved.path("OutputGroups").isEmpty()) {
            throw MediaConvertStore.badRequest("/settings: a job needs at least one output group.");
        }
        for (JsonNode group : resolved.path("OutputGroups")) {
            if (!group.path("Outputs").isArray() || group.path("Outputs").isEmpty()) {
                throw MediaConvertStore.badRequest("/outputGroups: each output group needs an output.");
            }
            ArrayNode outputs = (ArrayNode) group.get("Outputs");
            for (int i = 0; i < outputs.size(); i++) {
                ObjectNode output = (ObjectNode) outputs.get(i);
                if (output.hasNonNull("Preset")) {
                    ObjectNode merged = resources.preset(region, output.get("Preset").asText()).path("Settings")
                            .deepCopy();
                    merged.setAll(output);
                    outputs.set(i, merged);
                    output = merged;
                }
                MediaConvertTranscoder.checkOutput(output);
            }
            String destination = destination(group);
            if (destination != null && !destination.startsWith("s3://")) {
                throw MediaConvertStore.badRequest("/outputGroups: Destination " + destination + " must be an s3:// "
                        + "URL.");
            }
        }
        return resolved;
    }

    private static String destination(JsonNode group) {
        for (JsonNode settings : group.path("OutputGroupSettings")) {
            if (settings.isObject() && settings.hasNonNull("Destination")) {
                return settings.get("Destination").asText();
            }
        }
        return null;
    }

    // ================================================================ reading

    ObjectNode job(String region, String id) {
        return store.get(region, MediaConvertStore.JOB, id).orElseThrow(() ->
                MediaConvertStore.notFound("The job " + id + " doesn't exist."));
    }

    public ObjectNode getJob(JsonNode in, String region) {
        return wrap(job(region, in.path("Id").asText()));
    }

    private List<ObjectNode> filtered(String region, JsonNode in, boolean search) {
        List<ObjectNode> jobs = new ArrayList<>();
        String queue = in.hasNonNull("Queue") ? resources.queue(region, in.get("Queue").asText()).path("Arn")
                .asText() : null;
        for (ObjectNode job : store.list(region, MediaConvertStore.JOB)) {
            if (queue != null && !queue.equals(job.path("Queue").asText())) {
                continue;
            }
            if (in.hasNonNull("Status") && !in.get("Status").asText().equals(job.path("Status").asText())) {
                continue;
            }
            if (search && in.hasNonNull("InputFile")) {
                boolean match = false;
                for (JsonNode input : job.path("_").path("resolved").path("Inputs")) {
                    match |= input.path("FileInput").asText().equals(in.get("InputFile").asText());
                }
                if (!match) {
                    continue;
                }
            }
            jobs.add(MediaConvertStore.view(job));
        }
        Comparator<ObjectNode> order = Comparator.comparingDouble(job -> job.path("CreatedAt").asDouble());
        jobs.sort("ASCENDING".equals(in.path("Order").asText()) ? order : order.reversed());
        return jobs;
    }

    public ObjectNode listJobs(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        MediaConvertStore.page(filtered(region, in, false), in, out, "Jobs", 20);
        return out;
    }

    public ObjectNode searchJobs(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        MediaConvertStore.page(filtered(region, in, true), in, out, "Jobs", 20);
        return out;
    }

    public ObjectNode cancelJob(JsonNode in, String region) {
        ObjectNode job = job(region, in.path("Id").asText());
        String status = job.path("Status").asText();
        if (!"SUBMITTED".equals(status) && !"PROGRESSING".equals(status)) {
            throw MediaConvertStore.conflict("The job is " + status + " and can't be canceled.");
        }
        finish(store.account(), region, job, "CANCELED", null);
        dispatch();
        return mapper.createObjectNode();
    }

    public ObjectNode createResourceShare(JsonNode in, String region) {
        ObjectNode job = job(region, in.path("JobId").asText());
        if (!in.hasNonNull("SupportCaseId")) {
            throw MediaConvertStore.badRequest("CreateResourceShare needs a SupportCaseId.");
        }
        job.put("ShareStatus", "SHARED");
        job.put("LastShareDetails", "Shared with AWS Support for case " + in.get("SupportCaseId").asText() + ".");
        store.put(region, MediaConvertStore.JOB, job.path("Id").asText(), job);
        return mapper.createObjectNode();
    }

    // ================================================================ jobs queries

    public ObjectNode startJobsQuery(JsonNode in, String region) {
        List<ObjectNode> jobs = new ArrayList<>();
        for (ObjectNode job : store.list(region, MediaConvertStore.JOB)) {
            if (queryMatches(in.path("FilterList"), job)) {
                jobs.add(MediaConvertStore.view(job));
            }
        }
        Comparator<ObjectNode> order = Comparator.comparingDouble(job -> job.path("CreatedAt").asDouble());
        jobs.sort("ASCENDING".equals(in.path("Order").asText()) ? order : order.reversed());
        String id = UUID.randomUUID().toString();
        ObjectNode query = store.record();
        query.put("Id", id);
        query.set("Jobs", mapper.valueToTree(jobs));
        query.put("MaxResults", in.path("MaxResults").asInt(20));
        MediaConvertStore.meta(query).put("doneAt", System.currentTimeMillis() + Math.max(1,
                store.config().transitionMillis() / 4));
        store.put(region, MediaConvertStore.QUERY, id, query);
        ObjectNode out = mapper.createObjectNode();
        out.put("Id", id);
        return out;
    }

    private static boolean queryMatches(JsonNode filters, ObjectNode job) {
        for (JsonNode filter : filters) {
            List<String> actual = new ArrayList<>();
            JsonNode resolved = job.path("_").path("resolved");
            switch (filter.path("Key").asText()) {
                case "queue" -> {
                    actual.add(job.path("Queue").asText());
                    actual.add(job.path("Queue").asText().substring(job.path("Queue").asText().lastIndexOf('/') + 1));
                }
                case "status" -> actual.add(job.path("Status").asText());
                case "fileInput" -> resolved.path("Inputs").forEach(i -> actual.add(i.path("FileInput").asText()));
                case "jobEngineVersionRequested" -> actual.add(job.path("JobEngineVersionRequested").asText());
                case "jobEngineVersionUsed" -> actual.add(job.path("JobEngineVersionUsed").asText());
                case "audioCodec" -> resolved.findValues("AudioDescriptions").forEach(list -> list.forEach(a ->
                        actual.add(a.path("CodecSettings").path("Codec").asText())));
                case "videoCodec" -> resolved.findValues("VideoDescription").forEach(v ->
                        actual.add(v.path("CodecSettings").path("Codec").asText()));
                default -> {
                    continue;
                }
            }
            boolean any = false;
            for (JsonNode value : filter.path("Values")) {
                any |= actual.contains(value.asText());
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    public ObjectNode getJobsQueryResults(JsonNode in, String region) {
        String id = in.path("Id").asText();
        ObjectNode query = store.get(region, MediaConvertStore.QUERY, id).orElseThrow(() ->
                MediaConvertStore.notFound("The jobs query " + id + " doesn't exist."));
        ObjectNode out = mapper.createObjectNode();
        if (query.path("_").path("doneAt").asLong() > System.currentTimeMillis()) {
            out.put("Status", "PROGRESSING");
            return out;
        }
        out.put("Status", "COMPLETE");
        List<JsonNode> jobs = new ArrayList<>();
        query.path("Jobs").forEach(jobs::add);
        ObjectNode paging = mapper.createObjectNode();
        paging.put("MaxResults", query.path("MaxResults").asInt(20));
        if (in.hasNonNull("NextToken")) {
            paging.put("NextToken", in.get("NextToken").asText());
        }
        MediaConvertStore.page(jobs, paging, out, "Jobs", 20);
        return out;
    }

    // ================================================================ probing

    public ObjectNode probe(JsonNode in, String region) {
        if (!in.path("InputFiles").isArray() || in.path("InputFiles").isEmpty()) {
            throw MediaConvertStore.badRequest("Probe needs InputFiles.");
        }
        ArrayNode results = mapper.createArrayNode();
        for (JsonNode file : in.path("InputFiles")) {
            String url = file.path("FileUrl").asText("");
            Fetched fetched = fetch(store.account(), region, url, true);
            Map<String, JsonNode> probes;
            try {
                probes = transcoder.probe(Map.of("in." + extension(url), fetched.data()));
            } catch (MediaConvertTranscoder.TranscodeFailure e) {
                throw MediaConvertStore.badRequest("The file " + url + " couldn't be probed: " + e.getMessage());
            }
            results.add(transcoder.probeResult(probes.values().iterator().next(), fetched.data().length,
                    fetched.etag(), fetched.lastModified(), fetched.contentType()));
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("ProbeResults", results);
        return out;
    }

    private record Fetched(byte[] data, String etag, double lastModified, String contentType) {
    }

    /** An input file from S3 or HTTP(S); a missing one is NotFoundException for {@code Probe}, 1030 for a job. */
    private Fetched fetch(String account, String region, String url, boolean forProbe) {
        if (url.startsWith("s3://")) {
            String path = url.substring(5);
            int slash = path.indexOf('/');
            if (slash <= 0) {
                throw missing(url, forProbe, "isn't an s3://bucket/key URL");
            }
            String bucket = path.substring(0, slash);
            String key = path.substring(slash + 1);
            try {
                S3Object object = RequestScopes.callAs(account, region, () -> s3.get().getObject(bucket, key));
                return new Fetched(object.getData(), object.getETag(), object.getLastModified() == null ? 0
                        : object.getLastModified().toEpochMilli() / 1000.0, object.getContentType());
            } catch (AwsException e) {
                throw missing(url, forProbe, e.getMessage());
            }
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 400) {
                    throw missing(url, forProbe, "HTTP " + response.statusCode());
                }
                return new Fetched(response.body(), response.headers().firstValue("ETag").orElse(""),
                        MediaConvertStore.epoch(), response.headers().firstValue("Content-Type")
                        .orElse("application/octet-stream"));
            } catch (IOException e) {
                throw missing(url, forProbe, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw missing(url, forProbe, "interrupted");
            }
        }
        throw missing(url, forProbe, "isn't an s3://, http:// or https:// URL");
    }

    private static RuntimeException missing(String url, boolean forProbe, String why) {
        if (forProbe) {
            return MediaConvertStore.notFound("The file " + url + " can't be read: " + why);
        }
        return new MediaConvertTranscoder.TranscodeFailure(1030, "Unable to open input file [" + url + "]: " + why);
    }

    private static String extension(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 ? name.substring(dot + 1).replaceAll("[^A-Za-z0-9]", "") : "";
        return extension.isEmpty() ? "bin" : extension;
    }

    // ================================================================ running

    /** Starts the next jobs their queues have room for, the highest priority and earliest first. */
    synchronized void dispatch() {
        List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> waiting = new ArrayList<>();
        Map<String, Integer> progressing = new LinkedHashMap<>();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(MediaConvertStore.JOB)) {
            String status = entry.value().path("Status").asText();
            String queue = entry.value().path("Queue").asText();
            if ("PROGRESSING".equals(status)) {
                progressing.merge(queue, 1, Integer::sum);
            } else if ("SUBMITTED".equals(status)) {
                waiting.add(entry);
            }
        }
        waiting.sort(Comparator.comparingInt((AccountAwareStorageBackend.AccountEntry<ObjectNode> entry) ->
                -entry.value().path("Priority").asInt()).thenComparingDouble(entry -> entry.value()
                .path("CreatedAt").asDouble()));
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : waiting) {
            ObjectNode job = entry.value();
            String account = entry.accountId();
            String region = job.path("_").path("region").asText();
            String queueArn = job.path("Queue").asText();
            ObjectNode queue;
            try {
                queue = RequestScopes.callAs(account, region, () -> resources.queue(region, queueArn));
            } catch (AwsException e) {
                continue;
            }
            if (!"ACTIVE".equals(queue.path("Status").asText())
                    || progressing.getOrDefault(queueArn, 0) >= queue.path("ConcurrentJobs").asInt(1)) {
                continue;
            }
            progressing.merge(queueArn, 1, Integer::sum);
            job.put("Status", "PROGRESSING");
            job.put("CurrentPhase", "PROBING");
            job.put("JobPercentComplete", 0);
            ((ObjectNode) job.get("Timing")).put("StartTime", MediaConvertStore.epoch());
            store.putForAccount(account, region, MediaConvertStore.JOB, job.path("Id").asText(), job);
            publish(account, region, job, "PROGRESSING", null);
            String id = job.path("Id").asText();
            workers.submit(() -> RequestScopes.runAs(account, region, () -> run(account, region, id)));
        }
    }

    private void run(String account, String region, String id) {
        try {
            ObjectNode job = current(account, region, id);
            if (job == null || !"PROGRESSING".equals(job.path("Status").asText())) {
                return;
            }
            JsonNode settings = job.path("_").path("resolved");
            Map<String, byte[]> inputs = new LinkedHashMap<>();
            List<JsonNode> inputSettings = new ArrayList<>();
            int index = 0;
            for (JsonNode input : settings.path("Inputs")) {
                String url = input.path("FileInput").asText();
                inputs.put("in" + index++ + "." + extension(url), fetch(account, region, url, false).data());
                inputSettings.add(input);
            }
            Map<String, JsonNode> probes = transcoder.probe(inputs);
            inputInformation(account, region, job, settings, probes);
            job = phase(account, region, id, "TRANSCODING", 10);
            if (job == null) {
                return;
            }
            String first = settings.path("Inputs").get(0).path("FileInput").asText();
            String base = first.substring(first.lastIndexOf('/') + 1);
            base = base.contains(".") ? base.substring(0, base.lastIndexOf('.')) : base;
            String inputBase = base.isEmpty() ? "output" : base;
            List<String> bases = new ArrayList<>();
            for (JsonNode group : settings.path("OutputGroups")) {
                String destination = destination(group);
                String named = destination == null || destination.endsWith("/") ? ""
                        : destination.substring(destination.lastIndexOf('/') + 1);
                bases.add(named.isEmpty() || destination.indexOf('/', 5) < 0 ? inputBase : named);
            }
            MediaConvertTranscoder.Result result = transcoder.run(inputs, inputSettings, probes,
                    settings.path("OutputGroups"), bases);
            job = phase(account, region, id, "UPLOADING", 90);
            if (job == null) {
                return;
            }
            List<String> destinations = new ArrayList<>();
            for (JsonNode group : settings.path("OutputGroups")) {
                destinations.add(destination(group));
            }
            Map<String, String> uris = upload(account, region, result, destinations);
            ArrayNode details = job.putArray("OutputGroupDetails");
            ArrayNode eventGroups = mapper.createArrayNode();
            int groupIndex = 0;
            for (JsonNode group : settings.path("OutputGroups")) {
                ObjectNode detail = details.addObject();
                ArrayNode outputDetails = detail.putArray("OutputDetails");
                ObjectNode eventGroup = eventGroups.addObject();
                eventGroup.put("type", group.path("OutputGroupSettings").path("Type").asText()
                        .replace("_SETTINGS", ""));
                ArrayNode eventOutputs = eventGroup.putArray("outputDetails");
                ArrayNode playlists = eventGroup.putArray("playlistFilePaths");
                for (MediaConvertTranscoder.Produced output : result.outputs()) {
                    if (output.group() != groupIndex) {
                        continue;
                    }
                    if (output.playlist() != null && !"HLS_GROUP_SETTINGS".equals(output.type())
                            && !"CMAF_GROUP_SETTINGS".equals(output.type())) {
                        playlists.add(uris.getOrDefault(output.playlist(), output.playlist()));
                        if ("CMAF_DASH".equals(output.type())) {
                            continue;
                        }
                    }
                    ObjectNode outputDetail = outputDetails.addObject();
                    outputDetail.put("DurationInMs", output.durationMs());
                    ObjectNode eventOutput = eventOutputs.addObject();
                    eventOutput.put("durationInMs", output.durationMs());
                    ArrayNode paths = eventOutput.putArray("outputFilePaths");
                    output.files().forEach(file -> paths.add(uris.getOrDefault(file, file)));
                    if (output.width() > 0) {
                        outputDetail.putObject("VideoDetails").put("WidthInPx", output.width())
                                .put("HeightInPx", output.height());
                        eventOutput.putObject("videoDetails").put("widthInPx", output.width())
                                .put("heightInPx", output.height());
                    }
                }
                String main = uris.get("g" + groupIndex + "/" + bases.get(groupIndex) + ".m3u8");
                if (main != null) {
                    playlists.add(main);
                }
                groupIndex++;
            }
            store.putForAccount(account, region, MediaConvertStore.JOB, id, job);
            ObjectNode detail = mapper.createObjectNode();
            detail.set("outputGroupDetails", eventGroups);
            finish(account, region, job, "COMPLETE", detail);
        } catch (MediaConvertTranscoder.TranscodeFailure e) {
            fail(account, region, id, e.code, e.getMessage());
        } catch (RuntimeException e) {
            LOG.warnv(e, "MediaConvert job {0} failed", id);
            fail(account, region, id, 1999, "The job failed: " + e.getMessage());
        } finally {
            dispatch();
        }
    }

    private ObjectNode current(String account, String region, String id) {
        return store.getForAccount(account, region, MediaConvertStore.JOB, id).orElse(null);
    }

    /** Moves a running job to a phase, or null when it was canceled meanwhile. */
    private ObjectNode phase(String account, String region, String id, String phase, int percent) {
        ObjectNode job = current(account, region, id);
        if (job == null || !"PROGRESSING".equals(job.path("Status").asText())) {
            return null;
        }
        job.put("CurrentPhase", phase);
        job.put("JobPercentComplete", percent);
        store.putForAccount(account, region, MediaConvertStore.JOB, id, job);
        return job;
    }

    private Map<String, String> upload(String account, String region, MediaConvertTranscoder.Result result,
                                       List<String> destinations) {
        Map<String, String> uris = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> file : result.files().entrySet()) {
            String path = file.getKey();
            int slash = path.indexOf('/');
            if (!path.startsWith("g") || slash < 0) {
                continue;
            }
            int group = Integer.parseInt(path.substring(1, slash));
            String relative = path.substring(slash + 1);
            String destination = destinations.get(group);
            if (destination == null) {
                throw new MediaConvertTranscoder.TranscodeFailure(1040, "Output group " + (group + 1)
                        + " has no Destination.");
            }
            String location = destination.substring(5);
            String bucket = location.contains("/") ? location.substring(0, location.indexOf('/')) : location;
            String prefix = location.contains("/") ? location.substring(location.indexOf('/') + 1) : "";
            String folder = prefix.isEmpty() || prefix.endsWith("/") ? prefix
                    : prefix.contains("/") ? prefix.substring(0, prefix.lastIndexOf('/') + 1) : "";
            String key = folder + relative;
            try {
                RequestScopes.runAs(account, region, () -> s3.get().putObject(bucket, key, file.getValue(),
                        contentType(key), Map.of()));
            } catch (AwsException e) {
                throw new MediaConvertTranscoder.TranscodeFailure(1404, "Unable to write to output file [s3://"
                        + bucket + "/" + key + "]: " + e.getMessage());
            }
            uris.put(path, "s3://" + bucket + "/" + key);
        }
        return uris;
    }

    private static String contentType(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (lower.endsWith(".mpd")) {
            return "application/dash+xml";
        }
        if (lower.endsWith(".ts") || lower.endsWith(".m2ts")) {
            return "video/MP2T";
        }
        if (lower.endsWith(".jpg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".m4s")) {
            return "video/mp4";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        return "application/octet-stream";
    }

    private void inputInformation(String account, String region, ObjectNode job, JsonNode settings,
                                  Map<String, JsonNode> probes) {
        ObjectNode detail = mapper.createObjectNode();
        ArrayNode inputs = detail.putArray("inputDetails");
        int index = 1;
        for (Map.Entry<String, JsonNode> probe : probes.entrySet()) {
            ObjectNode input = inputs.addObject();
            input.put("id", index);
            input.put("uri", settings.path("Inputs").get(index - 1).path("FileInput").asText());
            JsonNode video = MediaConvertTranscoder.stream(probe.getValue(), "video");
            if (video != null) {
                input.putArray("video").addObject().put("widthInPx", video.path("width").asInt())
                        .put("heightInPx", video.path("height").asInt());
            }
            index++;
        }
        publish(account, region, job, "INPUT_INFORMATION", detail);
    }

    private void fail(String account, String region, String id, int code, String message) {
        ObjectNode job = current(account, region, id);
        if (job == null || !"PROGRESSING".equals(job.path("Status").asText())) {
            return;
        }
        job.put("ErrorCode", code);
        job.put("ErrorMessage", message);
        ObjectNode detail = mapper.createObjectNode();
        detail.put("errorCode", code);
        detail.put("errorMessage", message);
        finish(account, region, job, "ERROR", detail);
    }

    private void finish(String account, String region, ObjectNode job, String status, ObjectNode detail) {
        job.put("Status", status);
        job.remove("CurrentPhase");
        if ("COMPLETE".equals(status)) {
            job.put("JobPercentComplete", 100);
        }
        ((ObjectNode) job.get("Timing")).put("FinishTime", MediaConvertStore.epoch());
        store.putForAccount(account, region, MediaConvertStore.JOB, job.path("Id").asText(), job);
        publish(account, region, job, status, detail);
    }

    private void publish(String account, String region, ObjectNode job, String status, ObjectNode extra) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("timestamp", System.currentTimeMillis());
        detail.put("accountId", account);
        detail.put("queue", job.path("Queue").asText());
        detail.put("jobId", job.path("Id").asText());
        detail.put("status", status);
        detail.set("userMetadata", job.path("UserMetadata").deepCopy());
        if (extra != null) {
            detail.setAll(extra);
        }
        try {
            RequestScopes.runAs(account, region, () -> events.get().publishServiceEvent("aws.mediaconvert",
                    "MediaConvert Job State Change", detail.toString(), job.path("Arn").asText(), region));
        } catch (RuntimeException e) {
            LOG.debugv("MediaConvert could not send the {0} event of job {1}: {2}", status, job.path("Id").asText(),
                    e.getMessage());
        }
    }

}
