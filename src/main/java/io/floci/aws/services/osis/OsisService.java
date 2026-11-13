package io.floci.aws.services.osis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Amazon OpenSearch Ingestion. A pipeline runs its Data Prepper configuration in {@link OsisEngine}: it's
 * {@code CREATING} while its container starts, then {@code ACTIVE}, or {@code CREATE_FAILED} with Data Prepper's
 * error. Pipelines with push sources (HTTP, OpenTelemetry) answer on their ingest endpoint, which forwards to the
 * source the request's path names. VPC pipelines accept pipeline endpoints from other VPCs, and other accounts when
 * the pipeline's resource policy lets them.
 */
@ApplicationScoped
public class OsisService {

    private static final Logger LOG = Logger.getLogger(OsisService.class);
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String MIXED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String HEX = "0123456789abcdef";
    private static final int MAX_UNITS = 96;
    private static final int MAX_PIPELINES = 100;
    private static final Set<String> TRANSITIONAL = Set.of("CREATING", "UPDATING", "STARTING", "STOPPING",
            "DELETING");

    private final OsisStore store;
    private final ObjectMapper mapper;
    private final OsisEngine engine;
    private final Provider<IamService> iam;
    private final Provider<Ec2Service> ec2;
    private final Vertx vertx;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "osis-pipelines");
        thread.setDaemon(true);
        return thread;
    });
    private List<ObjectNode> blueprints;

    @Inject
    public OsisService(OsisStore store, OsisEngine engine, Provider<IamService> iam, Provider<Ec2Service> ec2,
                       Vertx vertx) {
        this.store = store;
        this.mapper = store.mapper();
        this.engine = engine;
        this.iam = iam;
        this.ec2 = ec2;
        this.vertx = vertx;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    /** Runs work after the transition delay, off the event loop. */
    private void later(Runnable task) {
        vertx.setTimer(Math.max(1, store.config().transitionMillis()), id -> worker.submit(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.warnv("OpenSearch Ingestion work failed: {0}", e.getMessage());
            }
        }));
    }

    private static String required(JsonNode in, String member) {
        String value = in.path(member).asText("");
        if (value.isBlank()) {
            throw OsisStore.validation(member + " is required.");
        }
        return value;
    }

    private ObjectNode pipeline(String region, String name) {
        return store.get(region, OsisStore.PIPELINE, name).orElseThrow(() ->
                OsisStore.notFound("Pipeline " + name + " doesn't exist."));
    }

    /** The account, Region and name an OpenSearch Ingestion pipeline ARN names. */
    private static String[] pipelineArn(String arn) {
        if (!AwsArnUtils.isArnFor(arn, OsisStore.SERVICE)) {
            throw OsisStore.validation("The ARN " + arn + " isn't an OpenSearch Ingestion pipeline ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!parsed.resource().startsWith("pipeline/")) {
            throw OsisStore.validation("The ARN " + arn + " isn't an OpenSearch Ingestion pipeline ARN.");
        }
        return new String[] {parsed.accountId(), parsed.region(), parsed.resource().substring("pipeline/".length())};
    }

    private Optional<ObjectNode> pipelineByArn(String arn) {
        String[] parts = pipelineArn(arn);
        return store.getForAccount(parts[0], parts[1], OsisStore.PIPELINE, parts[2]);
    }

    private void save(ObjectNode pipeline) {
        ObjectNode meta = OsisStore.meta(pipeline);
        store.putForAccount(meta.path("account").asText(), meta.path("region").asText(), OsisStore.PIPELINE,
                pipeline.path("PipelineName").asText(), pipeline);
    }

    private Optional<ObjectNode> reload(ObjectNode pipeline) {
        ObjectNode meta = OsisStore.meta(pipeline);
        return store.getForAccount(meta.path("account").asText(), meta.path("region").asText(), OsisStore.PIPELINE,
                pipeline.path("PipelineName").asText());
    }

    private static ObjectNode view(ObjectNode record) {
        return OsisStore.view(record);
    }

    // ================================================================ pipelines

    public ObjectNode createPipeline(JsonNode in, String region) {
        String name = required(in, "PipelineName");
        if (store.get(region, OsisStore.PIPELINE, name).isPresent()) {
            throw OsisStore.exists("Pipeline " + name + " already exists.");
        }
        if (store.list(region, OsisStore.PIPELINE).size() >= MAX_PIPELINES) {
            throw OsisStore.limit("The account has the maximum of " + MAX_PIPELINES + " pipelines in " + region
                    + ".");
        }
        int min = in.path("MinUnits").asInt();
        int max = in.path("MaxUnits").asInt();
        units(min, max);
        String body = required(in, "PipelineConfigurationBody");
        OsisPipelineConfig config = OsisPipelineConfig.parse(body);
        String roleArn = in.path("PipelineRoleArn").asText(null);
        roles(region, config, roleArn);
        String account = store.account();
        ObjectNode pipeline = store.record();
        pipeline.put("PipelineName", name);
        pipeline.put("PipelineArn", store.arnFor(account, region, "pipeline/" + name));
        pipeline.put("MinUnits", min);
        pipeline.put("MaxUnits", max);
        pipeline.put("Status", "CREATING");
        pipeline.putObject("StatusReason").put("Description", "The pipeline is being created. It is not able to "
                + "ingest data.");
        pipeline.put("PipelineConfigurationBody", body);
        double now = OsisStore.epoch();
        pipeline.put("CreatedAt", now);
        pipeline.put("LastUpdatedAt", now);
        String label = name + "-" + OsisStore.random(26, LOWER);
        OsisStore.meta(pipeline).put("hostLabel", label);
        ArrayNode urls = pipeline.putArray("IngestEndpointUrls");
        if (!config.pushSources().isEmpty()) {
            urls.add(ingestUrl(region, label));
        }
        logging(pipeline, in.path("LogPublishingOptions"));
        if (in.path("BufferOptions").isObject()) {
            pipeline.set("BufferOptions", in.get("BufferOptions").deepCopy());
        }
        if (in.path("EncryptionAtRestOptions").isObject()) {
            pipeline.set("EncryptionAtRestOptions", in.get("EncryptionAtRestOptions").deepCopy());
        }
        vpc(pipeline, region, name, in.path("VpcOptions"));
        destinations(pipeline, config);
        if (roleArn != null) {
            pipeline.put("PipelineRoleArn", roleArn);
        }
        OsisStore.tags(pipeline);
        OsisStore.addTags(pipeline, in.path("Tags"));
        change(pipeline, "CREATE");
        store.put(region, OsisStore.PIPELINE, name, pipeline);
        later(() -> run(pipeline, "CREATING", "CREATE_FAILED"));
        ObjectNode out = mapper.createObjectNode();
        out.set("Pipeline", view(pipeline));
        return out;
    }

    private static void units(int min, int max) {
        if (min < 1 || max < 1) {
            throw OsisStore.validation("MinUnits and MaxUnits must be at least 1.");
        }
        if (min > max) {
            throw OsisStore.validation("MinUnits can't be greater than MaxUnits.");
        }
        if (max > MAX_UNITS) {
            throw OsisStore.validation("MaxUnits can't be greater than " + MAX_UNITS + ".");
        }
    }

    /** The roles the configuration names, and the pipeline's role, must exist and trust OpenSearch Ingestion. */
    private void roles(String region, OsisPipelineConfig config, String pipelineRole) {
        Set<String> roles = new LinkedHashSet<>(config.roles());
        if (pipelineRole != null && !pipelineRole.isBlank()) {
            roles.add(pipelineRole);
        } else if (!config.pluginsWithoutRole().isEmpty()) {
            throw OsisStore.validation("The " + String.join(", ", config.pluginsWithoutRole()) + " plugin needs "
                    + "aws.sts_role_arn, or the pipeline needs a PipelineRoleArn.");
        }
        String account = store.account();
        for (String arn : roles) {
            String problem = role(account, region, arn);
            if (problem != null) {
                throw OsisStore.validation(problem);
            }
        }
    }

    /** Why a role can't be assumed by OpenSearch Ingestion, or null when it can. */
    private String role(String account, String region, String arn) {
        if (!AwsArnUtils.isArnFor(arn, "iam") || !AwsArnUtils.parse(arn).resource().startsWith("role/")) {
            return "The role " + arn + " isn't a role ARN.";
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        String principal = ServicePrincipals.of("osis-pipelines");
        String roleAccount = AwsArnUtils.accountOrDefault(arn, account);
        try {
            IamRole role = RequestScopes.callAs(roleAccount, region, () -> iam.get().getRole(name));
            String trust = role.getAssumeRolePolicyDocument() == null ? "" : URLDecoder.decode(
                    role.getAssumeRolePolicyDocument(), StandardCharsets.UTF_8);
            return trust.contains(principal) ? null : "The role " + arn + " doesn't trust " + principal + ".";
        } catch (AwsException e) {
            return "OpenSearch Ingestion couldn't assume the role " + arn + ": " + e.getMessage();
        }
    }

    private static void logging(ObjectNode pipeline, JsonNode options) {
        if (!options.isObject()) {
            return;
        }
        if (options.path("IsLoggingEnabled").asBoolean(false)) {
            String group = options.path("CloudWatchLogDestination").path("LogGroup").asText("");
            if (group.isBlank()) {
                throw OsisStore.validation("CloudWatchLogDestination is required when logging is enabled.");
            }
            if (!group.startsWith("/aws/vendedlogs/")) {
                throw OsisStore.validation("The log group name must start with /aws/vendedlogs/.");
            }
        }
        pipeline.set("LogPublishingOptions", options.deepCopy());
    }

    private void vpc(ObjectNode pipeline, String region, String name, JsonNode options) {
        ArrayNode endpoints = pipeline.putArray("VpcEndpoints");
        if (!options.isObject()) {
            return;
        }
        String vpcId = vpcOf(region, options);
        if ("CUSTOMER".equals(options.path("VpcEndpointManagement").asText())) {
            pipeline.put("VpcEndpointService", "com.amazonaws.osis." + region + "." + name + "-"
                    + OsisStore.random(26, LOWER));
        } else {
            ObjectNode endpoint = endpoints.addObject();
            endpoint.put("VpcEndpointId", "vpce-" + OsisStore.random(17, HEX));
            endpoint.put("VpcId", vpcId);
            endpoint.set("VpcOptions", options.deepCopy());
        }
        OsisStore.meta(pipeline).put("vpcId", vpcId);
        OsisStore.meta(pipeline).set("vpcOptions", options.deepCopy());
    }

    /** The VPC a set of subnets is in; they must all exist, in one VPC, with existing security groups. */
    private String vpcOf(String region, JsonNode options) {
        List<String> subnets = new ArrayList<>();
        options.path("SubnetIds").forEach(subnet -> subnets.add(subnet.asText()));
        if (subnets.isEmpty()) {
            throw OsisStore.validation("VpcOptions must name at least one subnet.");
        }
        String vpcId = null;
        for (String subnetId : subnets) {
            Subnet subnet;
            try {
                subnet = ec2.get().requireSubnet(region, subnetId);
            } catch (AwsException e) {
                throw OsisStore.validation("The subnet " + subnetId + " doesn't exist.");
            }
            if (vpcId != null && !vpcId.equals(subnet.getVpcId())) {
                throw OsisStore.validation("The subnets must all be in one VPC.");
            }
            vpcId = subnet.getVpcId();
        }
        List<String> groups = new ArrayList<>();
        options.path("SecurityGroupIds").forEach(group -> groups.add(group.asText()));
        if (!groups.isEmpty()) {
            try {
                ec2.get().requireSecurityGroups(region, groups);
            } catch (AwsException e) {
                throw OsisStore.validation(e.getMessage());
            }
        }
        return vpcId;
    }

    private static void destinations(ObjectNode pipeline, OsisPipelineConfig config) {
        ArrayNode destinations = pipeline.putArray("Destinations");
        config.destinations().forEach(destination -> destinations.addObject()
                .put("ServiceName", destination.serviceName()).put("Endpoint", destination.endpoint()));
    }

    /** Where clients push to a pipeline: its own host when Floci answers wildcard names, otherwise a path. */
    private String ingestUrl(String region, String label) {
        String base = store.endpoint();
        URI uri = URI.create(base);
        boolean local = List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
        if (local) {
            return uri.getScheme() + "://" + label + "." + region + ".osis." + OsisStore.DNS_SUFFIX
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        }
        return base + OsisIngestController.PREFIX + "/" + region + "/" + label;
    }

    /** Records the change a pipeline goes through, for GetPipelineChangeProgress. */
    private void change(ObjectNode pipeline, String kind) {
        ObjectNode change = OsisStore.meta(pipeline).putObject("change");
        change.put("StartTime", OsisStore.epoch());
        change.put("Status", "IN_PROGRESS");
        ArrayNode stages = change.putArray("ChangeProgressStages");
        List<String[]> names = switch (kind) {
            case "CREATE" -> List.of(new String[] {"Validation", "Validating the pipeline configuration"},
                    new String[] {"Create environment", "Creating the pipeline's resources"},
                    new String[] {"Start pipeline", "Starting Data Prepper with the configuration"});
            case "UPDATE" -> List.of(new String[] {"Validation", "Validating the pipeline configuration"},
                    new String[] {"Deploy configuration", "Restarting Data Prepper with the configuration"});
            case "START" -> List.<String[]>of(new String[] {"Start pipeline", "Starting Data Prepper"});
            default -> List.<String[]>of(new String[] {"Stop pipeline", "Stopping Data Prepper"});
        };
        for (int i = 0; i < names.size(); i++) {
            stages.addObject().put("Name", names.get(i)[0]).put("Status", i == 0 ? "COMPLETED" : "PENDING")
                    .put("Description", names.get(i)[1]).put("LastUpdatedAt", OsisStore.epoch());
        }
        change.put("TotalNumberOfStages", names.size());
    }

    private static void finishChange(ObjectNode pipeline, boolean ok) {
        ObjectNode change = (ObjectNode) OsisStore.meta(pipeline).path("change");
        if (!change.isObject() || !change.has("Status")) {
            return;
        }
        change.put("Status", ok ? "COMPLETED" : "FAILED");
        boolean failed = false;
        for (JsonNode stage : change.path("ChangeProgressStages")) {
            ObjectNode entry = (ObjectNode) stage;
            if (!"COMPLETED".equals(entry.path("Status").asText())) {
                entry.put("Status", ok ? "COMPLETED" : failed ? "PENDING" : "FAILED");
                failed |= !ok;
                entry.put("LastUpdatedAt", OsisStore.epoch());
            }
        }
    }

    /** Starts a pipeline's Data Prepper, settling it {@code ACTIVE} or in {@code failed} with the reason. */
    private void run(ObjectNode snapshot, String expected, String failed) {
        ObjectNode pipeline = reload(snapshot).orElse(null);
        if (pipeline == null || !expected.equals(pipeline.path("Status").asText())) {
            return;
        }
        String reason = null;
        if (engine.enabled()) {
            ObjectNode meta = OsisStore.meta(pipeline);
            String account = meta.path("account").asText();
            String region = meta.path("region").asText();
            String name = pipeline.path("PipelineName").asText();
            try {
                OsisPipelineConfig config = OsisPipelineConfig.parse(pipeline.path("PipelineConfigurationBody")
                        .asText());
                String role = pipeline.hasNonNull("PipelineRoleArn") ? pipeline.path("PipelineRoleArn").asText()
                        : config.roles().stream().findFirst().orElse(null);
                List<Integer> ports = new ArrayList<>();
                ArrayNode sources = meta.putArray("sources");
                config.pushSources().forEach(source -> {
                    ports.add(source.port());
                    sources.addObject().put("path", source.path()).put("port", source.port());
                });
                String logGroup = pipeline.path("LogPublishingOptions").path("IsLoggingEnabled").asBoolean(false)
                        ? pipeline.path("LogPublishingOptions").path("CloudWatchLogDestination").path("LogGroup")
                                .asText() : null;
                String scheme = URI.create(store.endpoint()).getScheme();
                engine.start(new OsisEngine.Launch(account, region, name, config.render(role, scheme), ports, role,
                        logGroup, config.buckets()));
            } catch (RuntimeException e) {
                reason = e.getMessage();
                LOG.infov("OpenSearch Ingestion pipeline {0} failed to start: {1}", name, reason);
            }
        }
        ObjectNode current = reload(pipeline).orElse(null);
        if (current == null || !expected.equals(current.path("Status").asText())) {
            return;
        }
        current.set("_", OsisStore.meta(pipeline).deepCopy());
        if (reason == null) {
            current.put("Status", "ACTIVE");
            current.putObject("StatusReason").put("Description", "The pipeline is ready to ingest data.");
        } else {
            current.put("Status", failed);
            current.putObject("StatusReason").put("Description", reason);
        }
        current.put("LastUpdatedAt", OsisStore.epoch());
        finishChange(current, reason == null);
        save(current);
    }

    public ObjectNode getPipeline(JsonNode in, String region) {
        ObjectNode pipeline = pipeline(region, required(in, "PipelineName"));
        ObjectNode out = mapper.createObjectNode();
        out.set("Pipeline", view(pipeline));
        return out;
    }

    public ObjectNode listPipelines(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode pipeline : store.list(region, OsisStore.PIPELINE)) {
            ObjectNode summary = mapper.createObjectNode();
            for (String field : List.of("Status", "StatusReason", "PipelineName", "PipelineArn", "MinUnits",
                    "MaxUnits", "CreatedAt", "LastUpdatedAt", "Destinations", "Tags")) {
                if (pipeline.has(field)) {
                    summary.set(field, pipeline.get(field).deepCopy());
                }
            }
            items.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        OsisStore.page(items, in, out, "Pipelines", 100);
        return out;
    }

    public ObjectNode updatePipeline(JsonNode in, String region) {
        ObjectNode pipeline = pipeline(region, required(in, "PipelineName"));
        String status = pipeline.path("Status").asText();
        if (TRANSITIONAL.contains(status)) {
            throw OsisStore.conflict("Pipeline " + pipeline.path("PipelineName").asText() + " is " + status
                    + "; update it once it settles.");
        }
        int min = in.has("MinUnits") ? in.path("MinUnits").asInt() : pipeline.path("MinUnits").asInt();
        int max = in.has("MaxUnits") ? in.path("MaxUnits").asInt() : pipeline.path("MaxUnits").asInt();
        units(min, max);
        String body = in.hasNonNull("PipelineConfigurationBody") ? in.get("PipelineConfigurationBody").asText()
                : pipeline.path("PipelineConfigurationBody").asText();
        OsisPipelineConfig config = OsisPipelineConfig.parse(body);
        String roleArn = in.hasNonNull("PipelineRoleArn") ? in.get("PipelineRoleArn").asText()
                : pipeline.path("PipelineRoleArn").asText(null);
        roles(region, config, roleArn);
        if (in.has("LogPublishingOptions")) {
            logging(pipeline, in.path("LogPublishingOptions"));
        }
        if (in.path("BufferOptions").isObject()) {
            pipeline.set("BufferOptions", in.get("BufferOptions").deepCopy());
        }
        if (in.path("EncryptionAtRestOptions").isObject()) {
            pipeline.set("EncryptionAtRestOptions", in.get("EncryptionAtRestOptions").deepCopy());
        }
        if (roleArn != null) {
            pipeline.put("PipelineRoleArn", roleArn);
        }
        pipeline.put("MinUnits", min);
        pipeline.put("MaxUnits", max);
        pipeline.put("PipelineConfigurationBody", body);
        pipeline.put("LastUpdatedAt", OsisStore.epoch());
        ArrayNode urls = pipeline.putArray("IngestEndpointUrls");
        if (!config.pushSources().isEmpty()) {
            urls.add(ingestUrl(region, OsisStore.meta(pipeline).path("hostLabel").asText()));
        }
        destinations(pipeline, config);
        if (!"STOPPED".equals(status)) {
            pipeline.put("Status", "UPDATING");
            pipeline.putObject("StatusReason").put("Description", "The pipeline is being updated. It can still "
                    + "ingest data.");
            change(pipeline, "UPDATE");
            save(pipeline);
            later(() -> run(pipeline, "UPDATING", "UPDATE_FAILED"));
        } else {
            save(pipeline);
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("Pipeline", view(pipeline));
        return out;
    }

    public ObjectNode deletePipeline(JsonNode in, String region) {
        String name = required(in, "PipelineName");
        ObjectNode pipeline = pipeline(region, name);
        String status = pipeline.path("Status").asText();
        if (TRANSITIONAL.contains(status)) {
            throw OsisStore.conflict("Pipeline " + name + " is " + status + "; delete it once it settles.");
        }
        pipeline.put("Status", "DELETING");
        pipeline.putObject("StatusReason").put("Description", "The pipeline is being deleted.");
        save(pipeline);
        String account = store.account();
        later(() -> {
            engine.remove(account, region, name);
            store.deleteForAccount(account, region, OsisStore.PIPELINE, name);
            store.deleteForAccount(account, region, OsisStore.POLICY, name);
        });
        return mapper.createObjectNode();
    }

    public ObjectNode startPipeline(JsonNode in, String region) {
        ObjectNode pipeline = pipeline(region, required(in, "PipelineName"));
        String status = pipeline.path("Status").asText();
        if (!"STOPPED".equals(status) && !"START_FAILED".equals(status)) {
            throw OsisStore.conflict("Pipeline " + pipeline.path("PipelineName").asText() + " is " + status
                    + "; only a stopped pipeline can be started.");
        }
        pipeline.put("Status", "STARTING");
        pipeline.putObject("StatusReason").put("Description", "The pipeline is starting. It is not able to ingest "
                + "data.");
        change(pipeline, "START");
        save(pipeline);
        later(() -> run(pipeline, "STARTING", "START_FAILED"));
        ObjectNode out = mapper.createObjectNode();
        out.set("Pipeline", view(pipeline));
        return out;
    }

    public ObjectNode stopPipeline(JsonNode in, String region) {
        String name = required(in, "PipelineName");
        ObjectNode pipeline = pipeline(region, name);
        String status = pipeline.path("Status").asText();
        if (!"ACTIVE".equals(status) && !"UPDATE_FAILED".equals(status)) {
            throw OsisStore.conflict("Pipeline " + name + " is " + status + "; only an active pipeline can be "
                    + "stopped.");
        }
        pipeline.put("Status", "STOPPING");
        pipeline.putObject("StatusReason").put("Description", "The pipeline is stopping.");
        change(pipeline, "STOP");
        save(pipeline);
        String account = store.account();
        later(() -> {
            engine.remove(account, region, name);
            ObjectNode current = reload(pipeline).orElse(null);
            if (current != null && "STOPPING".equals(current.path("Status").asText())) {
                current.put("Status", "STOPPED");
                current.putObject("StatusReason").put("Description", "The pipeline is stopped. It is not able to "
                        + "ingest data.");
                current.put("LastUpdatedAt", OsisStore.epoch());
                finishChange(current, true);
                save(current);
            }
        });
        ObjectNode out = mapper.createObjectNode();
        out.set("Pipeline", view(pipeline));
        return out;
    }

    public ObjectNode getPipelineChangeProgress(JsonNode in, String region) {
        ObjectNode pipeline = pipeline(region, required(in, "PipelineName"));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode statuses = out.putArray("ChangeProgressStatuses");
        JsonNode change = OsisStore.meta(pipeline).path("change");
        if (change.isObject() && change.has("Status")) {
            statuses.add(change.deepCopy());
        }
        return out;
    }

    public ObjectNode validatePipeline(JsonNode in, String region) {
        List<String> errors = new ArrayList<>();
        OsisPipelineConfig.parse(required(in, "PipelineConfigurationBody"), errors);
        ObjectNode out = mapper.createObjectNode();
        out.put("isValid", errors.isEmpty());
        ArrayNode messages = out.putArray("Errors");
        errors.forEach(error -> messages.addObject().put("Message", error));
        return out;
    }

    // ================================================================ blueprints

    private synchronized List<ObjectNode> blueprints() {
        if (blueprints == null) {
            try (InputStream stream = OsisService.class.getResourceAsStream("/osis/blueprints.yaml")) {
                JsonNode parsed = OsisPipelineConfig.YAML.readTree(stream);
                List<ObjectNode> list = new ArrayList<>();
                parsed.forEach(blueprint -> list.add((ObjectNode) blueprint));
                blueprints = list;
            } catch (IOException e) {
                throw new IllegalStateException("OpenSearch Ingestion's blueprints can't be read.", e);
            }
        }
        return blueprints;
    }

    public ObjectNode listPipelineBlueprints(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("Blueprints");
        blueprints().forEach(blueprint -> {
            ObjectNode summary = blueprint.deepCopy();
            summary.remove("PipelineConfigurationBody");
            list.add(summary);
        });
        return out;
    }

    public ObjectNode getPipelineBlueprint(JsonNode in, String region) {
        String name = required(in, "BlueprintName");
        ObjectNode blueprint = blueprints().stream()
                .filter(candidate -> name.equals(candidate.path("BlueprintName").asText())).findFirst()
                .orElseThrow(() -> OsisStore.notFound("Blueprint " + name + " doesn't exist."))
                .deepCopy();
        String format = in.path("Format").asText("YAML").toUpperCase(Locale.ROOT);
        if (!format.equals("YAML") && !format.equals("JSON")) {
            throw OsisStore.validation("Format must be YAML or JSON.");
        }
        if (format.equals("JSON")) {
            try {
                JsonNode body = OsisPipelineConfig.YAML.readTree(blueprint.path("PipelineConfigurationBody")
                        .asText());
                blueprint.put("PipelineConfigurationBody", mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(body));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("The blueprint " + name + " can't be converted to JSON.", e);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("Blueprint", blueprint);
        out.put("Format", format);
        return out;
    }

    // ================================================================ pipeline endpoints

    public ObjectNode createPipelineEndpoint(JsonNode in, String region) {
        String pipelineArn = required(in, "PipelineArn");
        ObjectNode pipeline = pipelineByArn(pipelineArn).orElseThrow(() ->
                OsisStore.notFound("Pipeline " + pipelineArn + " doesn't exist."));
        String account = store.account();
        String owner = OsisStore.meta(pipeline).path("account").asText();
        if (!owner.equals(account) && !allows(pipeline, account, "osis:CreatePipelineEndpoint")) {
            throw OsisStore.denied("The pipeline's resource policy doesn't let account " + account
                    + " create pipeline endpoints.");
        }
        if (!OsisStore.meta(pipeline).has("vpcId")) {
            throw OsisStore.validation("The pipeline is not configured for VPC.");
        }
        if (!region.equals(OsisStore.meta(pipeline).path("region").asText())) {
            throw OsisStore.validation("The pipeline endpoint must be in the pipeline's Region.");
        }
        JsonNode options = in.path("VpcOptions");
        String vpcId = vpcOf(region, options);
        if (owner.equals(account) && vpcId.equals(OsisStore.meta(pipeline).path("vpcId").asText())) {
            throw OsisStore.validation("Creating a pipeline endpoint in the same VPC as the pipeline is not "
                    + "supported.");
        }
        for (ObjectNode existing : store.list(region, OsisStore.ENDPOINT)) {
            if (pipelineArn.equals(existing.path("PipelineArn").asText()) && vpcId.equals(existing.path("VpcId")
                    .asText()) && !"DELETING".equals(existing.path("Status").asText())) {
                throw OsisStore.validation("The VPC " + vpcId + " already has an endpoint for this pipeline.");
            }
        }
        String id = OsisStore.random(20, MIXED);
        ObjectNode endpoint = store.record();
        endpoint.put("PipelineArn", pipelineArn);
        endpoint.put("EndpointId", id);
        endpoint.put("Status", "CREATING");
        endpoint.put("VpcId", vpcId);
        endpoint.set("VpcOptions", options.deepCopy());
        String label = OsisStore.meta(pipeline).path("hostLabel").asText() + "-" + id.toLowerCase(Locale.ROOT);
        endpoint.put("IngestEndpointUrl", ingestUrl(region, label));
        OsisStore.meta(endpoint).put("hostLabel", label);
        store.put(region, OsisStore.ENDPOINT, id, endpoint);
        later(() -> store.getForAccount(account, region, OsisStore.ENDPOINT, id).ifPresent(current -> {
            if ("CREATING".equals(current.path("Status").asText())) {
                current.put("Status", "ACTIVE");
                store.putForAccount(account, region, OsisStore.ENDPOINT, id, current);
            }
        }));
        ObjectNode out = mapper.createObjectNode();
        out.put("PipelineArn", pipelineArn);
        out.put("EndpointId", id);
        out.put("Status", "CREATING");
        out.put("VpcId", vpcId);
        return out;
    }

    public ObjectNode listPipelineEndpoints(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(region, OsisStore.ENDPOINT).forEach(endpoint -> items.add(view(endpoint)));
        ObjectNode out = mapper.createObjectNode();
        OsisStore.page(items, in, out, "PipelineEndpoints", 100);
        return out;
    }

    public ObjectNode deletePipelineEndpoint(JsonNode in, String region) {
        String id = required(in, "EndpointId");
        ObjectNode endpoint = store.get(region, OsisStore.ENDPOINT, id).orElseThrow(() ->
                OsisStore.validation("Pipeline endpoint " + id + " doesn't exist."));
        endpoint.put("Status", "DELETING");
        store.put(region, OsisStore.ENDPOINT, id, endpoint);
        String account = store.account();
        later(() -> store.deleteForAccount(account, region, OsisStore.ENDPOINT, id));
        return mapper.createObjectNode();
    }

    /** Endpoints in every account that connect to this account's pipelines in the Region. */
    private List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> connections(String region) {
        String account = store.account();
        List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> found = new ArrayList<>();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(OsisStore.ENDPOINT)) {
            String arn = entry.value().path("PipelineArn").asText();
            if (AwsArnUtils.isArnFor(arn, OsisStore.SERVICE) && account.equals(AwsArnUtils.parse(arn).accountId())
                    && region.equals(AwsArnUtils.parse(arn).region())) {
                found.add(entry);
            }
        }
        return found;
    }

    public ObjectNode listPipelineEndpointConnections(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : connections(region)) {
            ObjectNode connection = mapper.createObjectNode();
            connection.put("PipelineArn", entry.value().path("PipelineArn").asText());
            connection.put("EndpointId", entry.value().path("EndpointId").asText());
            connection.put("Status", entry.value().path("Status").asText());
            connection.put("VpcEndpointOwner", entry.accountId());
            items.add(connection);
        }
        ObjectNode out = mapper.createObjectNode();
        OsisStore.page(items, in, out, "PipelineEndpointConnections", 100);
        return out;
    }

    public ObjectNode revokePipelineEndpointConnections(JsonNode in, String region) {
        String pipelineArn = required(in, "PipelineArn");
        pipelineByArn(pipelineArn).orElseThrow(() -> OsisStore.validation("Pipeline " + pipelineArn
                + " doesn't exist."));
        Set<String> ids = new LinkedHashSet<>();
        in.path("EndpointIds").forEach(id -> ids.add(id.asText()));
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : connections(region)) {
            ObjectNode endpoint = entry.value().deepCopy();
            String id = endpoint.path("EndpointId").asText();
            if (pipelineArn.equals(endpoint.path("PipelineArn").asText()) && ids.contains(id)) {
                endpoint.put("Status", "REVOKING");
                store.putForAccount(entry.accountId(), region, OsisStore.ENDPOINT, id, endpoint);
                later(() -> store.getForAccount(entry.accountId(), region, OsisStore.ENDPOINT, id)
                        .ifPresent(current -> {
                            if ("REVOKING".equals(current.path("Status").asText())) {
                                current.put("Status", "REVOKED");
                                store.putForAccount(entry.accountId(), region, OsisStore.ENDPOINT, id, current);
                            }
                        }));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("PipelineArn", pipelineArn);
        return out;
    }

    // ================================================================ resource policies

    /** Whether a pipeline's resource policy allows {@code account} the action, with no statement denying it. */
    private boolean allows(ObjectNode pipeline, String account, String action) {
        ObjectNode meta = OsisStore.meta(pipeline);
        Optional<ObjectNode> stored = store.getForAccount(meta.path("account").asText(), meta.path("region")
                .asText(), OsisStore.POLICY, pipeline.path("PipelineName").asText());
        if (stored.isEmpty()) {
            return false;
        }
        boolean allowed = false;
        try {
            JsonNode policy = mapper.readTree(stored.get().path("Policy").asText());
            JsonNode statements = policy.path("Statement");
            for (JsonNode statement : statements.isArray() ? statements : mapper.createArrayNode().add(statements)) {
                if (!names(statement.path("Action")).contains(action) && !names(statement.path("Action"))
                        .contains("osis:*")) {
                    continue;
                }
                if (!names(statement.path("Principal").path("AWS")).contains(account)) {
                    continue;
                }
                if ("Deny".equals(statement.path("Effect").asText())) {
                    return false;
                }
                allowed = true;
            }
        } catch (JsonProcessingException e) {
            return false;
        }
        return allowed;
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> names.add(item.asText()));
        } else if (node.isTextual()) {
            names.add(node.asText());
        }
        return names;
    }

    /** OpenSearch Ingestion takes policies that grant {@code osis:CreatePipelineEndpoint} on the pipeline. */
    private void checkPolicy(String arn, String document) {
        JsonNode policy;
        try {
            policy = mapper.readTree(document);
        } catch (JsonProcessingException e) {
            throw OsisStore.validation("Invalid resource policy.");
        }
        JsonNode statements = policy.path("Statement");
        if (!policy.isObject() || statements.isMissingNode() || statements.isEmpty()) {
            throw OsisStore.validation("Invalid resource policy.");
        }
        for (JsonNode statement : statements.isArray() ? statements : mapper.createArrayNode().add(statements)) {
            String effect = statement.path("Effect").asText();
            List<String> actions = names(statement.path("Action"));
            List<String> principals = names(statement.path("Principal").path("AWS"));
            List<String> resources = names(statement.path("Resource"));
            boolean ok = (effect.equals("Allow") || effect.equals("Deny"))
                    && !actions.isEmpty() && actions.stream().allMatch("osis:CreatePipelineEndpoint"::equals)
                    && !principals.isEmpty() && principals.stream().allMatch(principal -> principal.matches("\\d{12}"))
                    && resources.stream().allMatch(resource -> resource.equals(arn));
            if (!ok) {
                throw OsisStore.validation("Invalid resource policy.");
            }
        }
    }

    public ObjectNode putResourcePolicy(JsonNode in, String region) {
        String arn = required(in, "ResourceArn");
        ObjectNode pipeline = pipelineByArn(arn).orElseThrow(() ->
                OsisStore.notFound("Pipeline " + arn + " doesn't exist."));
        String document = required(in, "Policy");
        checkPolicy(arn, document);
        ObjectNode meta = OsisStore.meta(pipeline);
        ObjectNode policy = store.record();
        policy.put("ResourceArn", arn);
        policy.put("Policy", document);
        store.putForAccount(meta.path("account").asText(), meta.path("region").asText(), OsisStore.POLICY,
                pipeline.path("PipelineName").asText(), policy);
        ObjectNode out = mapper.createObjectNode();
        out.put("ResourceArn", arn);
        out.put("Policy", document);
        return out;
    }

    public ObjectNode getResourcePolicy(JsonNode in, String region) {
        String arn = required(in, "ResourceArn");
        String[] parts = pipelineArn(arn);
        ObjectNode out = mapper.createObjectNode();
        out.put("ResourceArn", arn);
        out.put("Policy", store.getForAccount(parts[0], parts[1], OsisStore.POLICY, parts[2])
                .map(policy -> policy.path("Policy").asText()).orElse("{}"));
        return out;
    }

    public ObjectNode deleteResourcePolicy(JsonNode in, String region) {
        String arn = required(in, "ResourceArn");
        String[] parts = pipelineArn(arn);
        if (store.getForAccount(parts[0], parts[1], OsisStore.POLICY, parts[2]).isEmpty()) {
            throw OsisStore.notFound("Pipeline " + arn + " has no resource policy.");
        }
        store.deleteForAccount(parts[0], parts[1], OsisStore.POLICY, parts[2]);
        return mapper.createObjectNode();
    }

    // ================================================================ tags

    private ObjectNode tagged(String arn) {
        return pipelineByArn(arn).orElseThrow(() -> OsisStore.notFound("Pipeline " + arn + " doesn't exist."));
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        ObjectNode pipeline = tagged(required(in, "Arn"));
        OsisStore.addTags(pipeline, in.path("Tags"));
        save(pipeline);
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        ObjectNode pipeline = tagged(required(in, "Arn"));
        in.path("TagKeys").forEach(key -> OsisStore.removeTag(OsisStore.tags(pipeline), key.asText()));
        save(pipeline);
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode pipeline = tagged(required(in, "Arn"));
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", OsisStore.tags(pipeline).deepCopy());
        return out;
    }

    // ================================================================ ingest

    /** A pipeline an ingest host label names, through the pipeline itself or one of its endpoints. */
    Optional<ObjectNode> ingestTarget(String region, String label) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(OsisStore.PIPELINE)) {
            ObjectNode meta = OsisStore.meta(entry.value());
            if (label.equals(meta.path("hostLabel").asText()) && region.equals(meta.path("region").asText())) {
                return Optional.of(entry.value());
            }
        }
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(OsisStore.ENDPOINT)) {
            ObjectNode endpoint = entry.value();
            if (label.equals(OsisStore.meta(endpoint).path("hostLabel").asText())) {
                if (!"ACTIVE".equals(endpoint.path("Status").asText())) {
                    return Optional.empty();
                }
                return pipelineByArn(endpoint.path("PipelineArn").asText());
            }
        }
        return Optional.empty();
    }

    OsisEngine engine() {
        return engine;
    }
}
