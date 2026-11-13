package io.floci.aws.services.bedrockdataautomation;

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
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.bedrockdataautomation.BdaService.joinKey;
import static io.floci.aws.services.bedrockdataautomation.BdaService.s3Location;
import static io.floci.aws.services.bedrockdataautomation.BdaService.validation;

/**
 * Bedrock Data Automation's runtime ({@code AmazonBedrockKeystoneRuntimeService}): an asynchronous invocation reads
 * an asset from S3, processes it with its project's configuration and blueprints ({@link BdaProcessing}), and writes
 * AWS's output layout under {@code <output s3Uri>/<invocation id>/}: {@code job_metadata.json}, and
 * {@code 0/standard_output/0/result.json} and {@code 0/custom_output/0/result.json} for the asset. With EventBridge
 * notifications on, it sends {@code aws.bedrock} {@code Bedrock Data Automation Job Created}, then
 * {@code ... Job Succeeded} or {@code ... Job Failed With Client Error}. A synchronous invocation returns the same
 * output inline.
 */
@ApplicationScoped
public class BdaRuntime implements Resettable {
    private static final long RUN_DELAY_MILLIS = 750;
    private static final String EVENT_SOURCE = "aws.bedrock";

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/bedrock-data-automation-runtime-request-shapes.json", "Bedrock Data Automation runtime request shapes",
            BdaService.messages());

    private final AccountAwareStorageBackend<ObjectNode> invocations;
    private final BdaService control;
    private final BdaProcessing processing;
    private final Instance<S3Service> s3;
    private final Instance<EventBridgeService> events;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "bda-invocations");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public BdaRuntime(StorageFactory storageFactory, BdaService control, BdaProcessing processing,
                      Instance<S3Service> s3, Instance<EventBridgeService> events, RegionResolver regionResolver,
                      ObjectMapper mapper) {
        this.invocations = BdaService.store(storageFactory, "bda-invocations.json");
        this.control = control;
        this.processing = processing;
        this.s3 = s3;
        this.events = events;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    @PostConstruct
    void register() {
        control.attach(this);
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    boolean exists(String arn, String region) {
        return invocations.get(BdaService.key(region, arn)).isPresent();
    }

    // ── Asynchronous ───────────────────────────────────────────────────────

    public synchronized ObjectNode invokeAsync(JsonNode in, String region) {
        SHAPES.check("InvokeDataAutomationAsync", in);
        control.checkProfile(in.path("dataAutomationProfileArn").asText(), region);
        if (in.hasNonNull("clientToken")) {
            Optional<ObjectNode> repeated = invocations.scan(k -> k.startsWith(region + "/")).stream()
                    .filter(i -> in.path("clientToken").asText().equals(i.path("clientToken").asText())).findFirst();
            if (repeated.isPresent()) {
                return mapper.createObjectNode().put("invocationArn", repeated.get().path("invocationArn").asText());
            }
        }
        Plan plan = plan(in, region);
        s3Location(in.path("outputConfiguration").path("s3Uri").asText());
        String id = UUID.randomUUID().toString();
        ObjectNode invocation = mapper.createObjectNode();
        invocation.put("invocationArn", control.arn(region, "data-automation-invocation/" + id));
        invocation.put("status", "Created");
        invocation.put("jobSubmissionTime", BdaService.now());
        invocation.set("request", in.deepCopy());
        if (in.hasNonNull("clientToken")) {
            invocation.put("clientToken", in.path("clientToken").asText());
        }
        invocations.put(region + "/" + id, invocation);
        control.putTags(invocation.path("invocationArn").asText(), in.path("tags"));
        boolean notify = in.path("notificationConfiguration").path("eventBridgeConfiguration")
                .path("eventBridgeEnabled").asBoolean(false);
        if (notify) {
            publish("Bedrock Data Automation Job Created", detail(id, "CREATED", null, in, null, null), region);
        }
        String account = regionResolver.getAccountId();
        worker.schedule(() -> RequestScopes.runAs(account, region, () -> run(id, plan, region)), RUN_DELAY_MILLIS,
                TimeUnit.MILLISECONDS);
        return mapper.createObjectNode().put("invocationArn", invocation.path("invocationArn").asText());
    }

    private void run(String id, Plan plan, String region) {
        ObjectNode invocation;
        synchronized (this) {
            invocation = invocations.get(region + "/" + id).orElse(null);
            if (invocation == null) {
                return;
            }
            invocation.put("status", "InProgress");
            invocations.put(region + "/" + id, invocation);
        }
        JsonNode in = invocation.path("request");
        boolean notify = in.path("notificationConfiguration").path("eventBridgeConfiguration")
                .path("eventBridgeEnabled").asBoolean(false);
        String inputUri = in.path("inputConfiguration").path("s3Uri").asText();
        String outputUri = in.path("outputConfiguration").path("s3Uri").asText();
        try {
            String[] input = s3Location(inputUri);
            byte[] bytes;
            try {
                bytes = s3.get().getObject(input[0], input[1]).getData();
            } catch (AwsException e) {
                throw new ClientError("S3InputAccessError", "The input object " + inputUri + " can't be read.");
            }
            BdaProcessing.Asset asset = asset(bytes, input[0], input[1], plan.config());
            Result result = process(asset, plan);
            String[] output = s3Location(outputUri);
            String base = joinKey(output[1], id);
            String standardKey = joinKey(base, "0/standard_output/0/result.json");
            write(output[0], standardKey, result.standard());
            ObjectNode segment = mapper.createObjectNode();
            segment.put("standard_output_path", "s3://" + output[0] + "/" + standardKey);
            if (!plan.blueprints().isEmpty()) {
                segment.put("custom_output_status", result.custom() == null ? "NO_MATCH" : "MATCH");
                if (result.custom() != null) {
                    String customKey = joinKey(base, "0/custom_output/0/result.json");
                    write(output[0], customKey, result.custom());
                    segment.put("custom_output_path", "s3://" + output[0] + "/" + customKey);
                }
            }
            ObjectNode metadata = mapper.createObjectNode();
            metadata.put("job_id", id);
            metadata.put("job_status", "PROCESSED");
            metadata.put("semantic_modality", asset.modality());
            ObjectNode assetMetadata = metadata.putArray("output_metadata").addObject();
            assetMetadata.put("asset_id", 0);
            assetMetadata.putArray("segment_metadata").add(segment);
            String metadataKey = joinKey(base, "job_metadata.json");
            write(output[0], metadataKey, metadata);
            finish(id, region, "Success", null, null, "s3://" + output[0] + "/" + metadataKey);
            if (notify) {
                publish("Bedrock Data Automation Job Succeeded", detail(id, "SUCCESS", asset.modality(), in,
                        "s3://" + output[0] + "/" + base, null), region);
            }
        } catch (ClientError e) {
            finish(id, region, "ClientError", e.type, e.getMessage(), null);
            if (notify) {
                publish("Bedrock Data Automation Job Failed With Client Error", detail(id, "FAILED", null, in, null,
                        e.getMessage()), region);
            }
        } catch (AwsException e) {
            finish(id, region, "ClientError", e.getErrorCode(), e.getMessage(), null);
            if (notify) {
                publish("Bedrock Data Automation Job Failed With Client Error", detail(id, "FAILED", null, in, null,
                        e.getMessage()), region);
            }
        } catch (RuntimeException e) {
            finish(id, region, "ServiceError", "InternalServerException", e.getMessage(), null);
            if (notify) {
                publish("Bedrock Data Automation Job Failed With Service Error", detail(id, "FAILED", null, in, null,
                        e.getMessage()), region);
            }
        }
    }

    private void finish(String id, String region, String status, String errorType, String errorMessage,
                        String output) {
        synchronized (this) {
            invocations.get(region + "/" + id).ifPresent(invocation -> {
                double now = BdaService.now();
                invocation.put("status", status);
                invocation.put("jobCompletionTime", now);
                invocation.put("jobDurationInSeconds", (int) Math.max(1, Math.round(now
                        - invocation.path("jobSubmissionTime").asDouble())));
                if (errorType != null) {
                    invocation.put("errorType", errorType);
                    invocation.put("errorMessage", errorMessage);
                }
                if (output != null) {
                    invocation.put("outputUri", output);
                }
                invocations.put(region + "/" + id, invocation);
            });
        }
    }

    public synchronized ObjectNode status(JsonNode in, String region) {
        SHAPES.check("GetDataAutomationStatus", in);
        String arn = in.path("invocationArn").asText();
        ObjectNode invocation = invocations.get(BdaService.key(region, arn))
                .filter(i -> arn.equals(i.path("invocationArn").asText()))
                .orElseThrow(() -> BdaService.notFound("The invocation " + arn + " doesn't exist."));
        ObjectNode out = mapper.createObjectNode();
        out.put("status", invocation.path("status").asText());
        for (String field : List.of("errorType", "errorMessage", "jobSubmissionTime", "jobCompletionTime",
                "jobDurationInSeconds")) {
            if (invocation.has(field)) {
                out.set(field, invocation.get(field));
            }
        }
        if (invocation.has("outputUri")) {
            out.putObject("outputConfiguration").put("s3Uri", invocation.path("outputUri").asText());
        }
        return out;
    }

    // ── Synchronous ────────────────────────────────────────────────────────

    /** Processes an asset given inline ({@code bytes}) or in S3 and returns its output segments. */
    public ObjectNode invoke(JsonNode in, String region) {
        SHAPES.check("InvokeDataAutomation", in);
        JsonNode input = in.path("inputConfiguration");
        if (input.hasNonNull("bytes") == input.hasNonNull("s3Uri")) {
            throw validation("inputConfiguration needs exactly one of bytes and s3Uri.");
        }
        control.checkProfile(in.path("dataAutomationProfileArn").asText(), region);
        Plan plan = plan(in, region);
        byte[] bytes;
        String bucket = null;
        String key = null;
        if (input.hasNonNull("bytes")) {
            try {
                bytes = Base64.getDecoder().decode(input.path("bytes").asText());
            } catch (IllegalArgumentException e) {
                throw validation("inputConfiguration.bytes isn't valid base64.");
            }
        } else {
            String[] location = s3Location(input.path("s3Uri").asText());
            bucket = location[0];
            key = location[1];
            bytes = control.read(location);
        }
        if (bytes.length == 0) {
            throw validation("The input is empty.");
        }
        BdaProcessing.Asset asset;
        Result result;
        try {
            asset = asset(bytes, bucket, key, plan.config());
            result = process(asset, plan);
        } catch (ClientError e) {
            throw validation(e.getMessage());
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("semanticModality", asset.modality());
        ObjectNode segment = out.putArray("outputSegments").addObject();
        segment.put("standardOutput", result.standard().toString());
        if (!plan.blueprints().isEmpty()) {
            segment.put("customOutputStatus", result.custom() == null ? "NO_MATCH" : "MATCH");
            if (result.custom() != null) {
                segment.put("customOutput", result.custom().toString());
            }
        }
        if (in.path("outputConfiguration").hasNonNull("s3Uri")) {
            String[] output = s3Location(in.path("outputConfiguration").path("s3Uri").asText());
            String base = joinKey(output[1], UUID.randomUUID().toString());
            write(output[0], joinKey(base, "0/standard_output/0/result.json"), result.standard());
            if (result.custom() != null) {
                write(output[0], joinKey(base, "0/custom_output/0/result.json"), result.custom());
            }
            out.putObject("outputConfiguration").put("s3Uri", "s3://" + output[0] + "/" + base);
        }
        return out;
    }

    // ── Processing ─────────────────────────────────────────────────────────

    /** What an invocation processes with: its project's configuration (or the defaults) and blueprints. */
    record Plan(JsonNode config, List<BdaProcessing.Candidate> blueprints) {
    }

    record Result(ObjectNode standard, ObjectNode custom) {
    }

    private Plan plan(JsonNode in, String region) {
        JsonNode configuration = in.path("dataAutomationConfiguration");
        JsonNode project = mapper.createObjectNode();
        List<BdaProcessing.Candidate> blueprints = new ArrayList<>();
        if (configuration.hasNonNull("dataAutomationProjectArn")) {
            String arn = configuration.path("dataAutomationProjectArn").asText();
            String stage = configuration.path("stage").asText(BdaService.STAGE_LIVE);
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!"aws".equals(parsed.accountId())) { // partition-literal: "aws" is the account that owns AWS-managed projects
                project = control.projectConfig(arn, stage, region).orElseThrow(() -> validation("The project " + arn
                        + " doesn't exist or has no " + stage + " stage."));
            }
            for (JsonNode item : project.path("customOutputConfiguration").path("blueprints")) {
                blueprints.add(resolve(item.path("blueprintArn").asText(), item.path("blueprintVersion").asText(null),
                        item.path("blueprintStage").asText(BdaService.STAGE_LIVE), region));
            }
        }
        for (JsonNode item : in.path("blueprints")) {
            blueprints.add(resolve(item.path("blueprintArn").asText(), item.path("version").asText(null),
                    item.path("stage").asText(BdaService.STAGE_LIVE), region));
        }
        return new Plan(project, blueprints);
    }

    private BdaProcessing.Candidate resolve(String arn, String version, String stage, String region) {
        return control.candidate(arn, version, stage, region).orElseThrow(() -> validation("The blueprint " + arn
                + " doesn't exist at the version or stage named."));
    }

    private BdaProcessing.Asset asset(byte[] bytes, String bucket, String key, JsonNode config) {
        String modality = BdaDocuments.modality(bytes, key);
        JsonNode routing = config.path("overrideConfiguration").path("modalityRouting");
        String extension = key == null ? "" : key.substring(key.lastIndexOf('.') + 1).toLowerCase();
        if (routing.hasNonNull("jpg".equals(extension) ? "jpeg" : extension)) {
            modality = routing.path("jpg".equals(extension) ? "jpeg" : extension).asText();
        }
        if (modality == null) {
            throw new ClientError("UnsupportedFileType", "The asset isn't a document, image, audio or video file "
                    + "Bedrock Data Automation can process.");
        }
        List<String> pages = "DOCUMENT".equals(modality) ? BdaDocuments.pages(bytes) : List.of();
        return new BdaProcessing.Asset(modality, pages, bucket, key);
    }

    private Result process(BdaProcessing.Asset asset, Plan plan) {
        ObjectNode standard = processing.standardOutput(asset, plan.config().path("standardOutputConfiguration"));
        ObjectNode custom = processing.customOutput(asset, plan.blueprints());
        return new Result(standard, custom);
    }

    private void write(String bucket, String key, JsonNode body) {
        try {
            s3.get().putObject(bucket, key, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(body),
                    "application/json", Map.of());
        } catch (AwsException e) {
            throw new ClientError("S3OutputAccessError", "The output location s3://" + bucket + "/" + key
                    + " can't be written: " + e.getMessage());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode detail(String id, String status, String modality, JsonNode in, String output, String error) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("job_id", id);
        detail.put("job_status", status);
        if (modality != null) {
            detail.put("semantic_modality", modality);
        }
        String[] input = s3Location(in.path("inputConfiguration").path("s3Uri").asText());
        detail.putObject("input_s3_object").put("s3_bucket", input[0]).put("name", input[1]);
        if (output != null) {
            String[] location = s3Location(output);
            detail.putObject("output_s3_location").put("s3_bucket", location[0]).put("name", location[1]);
        }
        if (error != null) {
            detail.put("error_message", error);
        }
        detail.put("job_duration_in_seconds", 1);
        return detail;
    }

    private void publish(String detailType, ObjectNode detail, String region) {
        if (events.isResolvable()) {
            events.get().publishServiceEvent(EVENT_SOURCE, detailType, detail.toString(), null, region);
        }
    }

    /** An invocation failure blamed on its input. */
    static final class ClientError extends RuntimeException {
        final String type;

        ClientError(String type, String message) {
            super(message);
            this.type = type;
        }
    }

    // ── Tags (runtime API) ─────────────────────────────────────────────────

    public ObjectNode listTags(JsonNode in, String region) {
        SHAPES.check("ListTagsForResource", in);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("tags");
        control.listTags(in.path("resourceARN").asText(), region).forEach(list::add);
        return out;
    }

    public ObjectNode tag(JsonNode in, String region) {
        SHAPES.check("TagResource", in);
        control.tag(in.path("resourceARN").asText(), in.path("tags"), region);
        return mapper.createObjectNode();
    }

    public ObjectNode untag(JsonNode in, String region) {
        SHAPES.check("UntagResource", in);
        control.untag(in.path("resourceARN").asText(), in.path("tagKeys"), region);
        return mapper.createObjectNode();
    }

    @Override
    public void clear() {
        invocations.clear();
    }
}
