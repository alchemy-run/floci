package io.floci.aws.services.appflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.Bucket;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Amazon AppFlow's definitions: connector profiles, flows, registered custom connectors, and the
 * connectors' metadata, with tags. Running flows is {@link AppFlowRuns}'. Floci moves data between
 * S3 buckets; flows with any other source or destination can be defined, but their runs fail with
 * the reason. Requests are checked against the constraints vendored from botocore by
 * {@code tools/aws/regen_appflow_shapes.py}.
 */
@ApplicationScoped
public class AppFlowService implements Resettable {
    static final String SERVICE_PRINCIPAL = "appflow.amazonaws.com"; // partition-literal: the service principal name AWS uses in every partition
    private static final Set<String> RUNNABLE = Set.of("S3");
    /** The connector types AppFlow offers, with whether each can be a source and a destination. */
    private static final Map<String, boolean[]> CONNECTORS = connectors();

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/appflow-request-shapes.json", "AppFlow request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return validation(path + " is outside the allowed bounds" + (min == null ? "" : " (min " + min
                            + ")") + (max == null ? "" : " (max " + max + ")") + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return validation(path + " does not match the pattern " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return validation(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return validation(path + " is required.");
                }
            });

    final AccountAwareStorageBackend<ObjectNode> flows;
    private final AccountAwareStorageBackend<ObjectNode> profiles;
    private final AccountAwareStorageBackend<ObjectNode> registrations;
    final Instance<S3Service> s3;
    private final Instance<LambdaService> lambda;
    final RegionResolver regionResolver;
    final ObjectMapper mapper;

    @Inject
    public AppFlowService(StorageFactory storageFactory, Instance<S3Service> s3, Instance<LambdaService> lambda,
                          RegionResolver regionResolver, ObjectMapper mapper) {
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.flows = storageFactory.create("appflow", "appflow-flows.json", type);
        this.profiles = storageFactory.create("appflow", "appflow-connector-profiles.json", type);
        this.registrations = storageFactory.create("appflow", "appflow-connectors.json", type);
        this.s3 = s3;
        this.lambda = lambda;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    private static Map<String, boolean[]> connectors() {
        Map<String, boolean[]> out = new LinkedHashMap<>();
        for (String source : List.of("Salesforce", "Singular", "Slack", "Redshift", "S3", "Marketo", "Googleanalytics",
                "Zendesk", "Servicenow", "Datadog", "Trendmicro", "Snowflake", "Dynatrace", "Infornexus", "Amplitude",
                "Veeva", "EventBridge", "LookoutMetrics", "Upsolver", "Honeycode", "CustomerProfiles", "SAPOData",
                "CustomConnector", "Pardot")) {
            boolean isSource = !Set.of("Redshift", "Snowflake", "EventBridge", "LookoutMetrics", "Upsolver",
                    "Honeycode", "CustomerProfiles").contains(source);
            boolean isDestination = Set.of("Salesforce", "Redshift", "S3", "Marketo", "Zendesk", "Snowflake",
                    "EventBridge", "LookoutMetrics", "Upsolver", "Honeycode", "CustomerProfiles", "SAPOData",
                    "CustomConnector").contains(source);
            out.put(source, new boolean[]{isSource, isDestination});
        }
        return out;
    }

    /** Checks a request against the model. */
    static void check(String operation, JsonNode request, String... pathMembers) {
        ObjectNode checked = request != null && request.isObject() ? ((ObjectNode) request).deepCopy()
                : new ObjectMapper().createObjectNode();
        for (int i = 0; i + 1 < pathMembers.length; i += 2) {
            if (pathMembers[i + 1] != null) {
                checked.put(pathMembers[i], pathMembers[i + 1]);
            }
        }
        SHAPES.check(operation, checked);
    }

    // ── Connector profiles ─────────────────────────────────────────────────

    public synchronized ObjectNode createProfile(JsonNode in, String region) {
        check("CreateConnectorProfile", in);
        String name = in.path("connectorProfileName").asText();
        if (profiles.get(key(region, name)).isPresent()) {
            throw new AwsException("ConflictException", "A connector profile named " + name + " already exists.", 409);
        }
        String type = in.path("connectorType").asText();
        checkConnectorType(type, in.path("connectorLabel").asText(null), region);
        JsonNode config = in.path("connectorProfileConfig");
        if (!config.path("connectorProfileCredentials").isObject()
                && !"CustomConnector".equals(type)) {
            throw validation("connectorProfileConfig.connectorProfileCredentials is required.");
        }
        verifyConnection(type, config.path("connectorProfileProperties").path(type),
                config.path("connectorProfileCredentials").path(type));
        long now = Instant.now().getEpochSecond();
        ObjectNode profile = mapper.createObjectNode();
        profile.put("connectorProfileArn", arn(region, "connectorprofile/" + name));
        profile.put("connectorProfileName", name);
        profile.put("connectorType", type);
        if (in.hasNonNull("connectorLabel")) {
            profile.put("connectorLabel", in.get("connectorLabel").asText());
        }
        profile.put("connectionMode", in.path("connectionMode").asText("Public"));
        profile.put("credentialsArn", AwsArnUtils.Arn.of("secretsmanager", region, regionResolver.getAccountId(),
                "secret:appflow!" + regionResolver.getAccountId() + "-" + name + "-" + now).toString());
        profile.set("connectorProfileProperties", config.path("connectorProfileProperties").isObject()
                ? config.get("connectorProfileProperties").deepCopy() : mapper.createObjectNode());
        profile.put("createdAt", now);
        profile.put("lastUpdatedAt", now);
        if ("Private".equals(profile.path("connectionMode").asText())) {
            profile.putObject("privateConnectionProvisioningState").put("status", "CREATED");
        }
        profile.set("credentials", config.path("connectorProfileCredentials").deepCopy());
        profiles.put(key(region, name), profile);
        return mapper.createObjectNode().put("connectorProfileArn", profile.path("connectorProfileArn").asText());
    }

    public synchronized ObjectNode updateProfile(JsonNode in, String region) {
        check("UpdateConnectorProfile", in);
        String name = in.path("connectorProfileName").asText();
        ObjectNode profile = requireProfile(name, region);
        JsonNode config = in.path("connectorProfileConfig");
        String type = profile.path("connectorType").asText();
        JsonNode properties = config.path("connectorProfileProperties").isObject()
                ? config.get("connectorProfileProperties") : profile.path("connectorProfileProperties");
        JsonNode credentials = config.path("connectorProfileCredentials").isObject()
                ? config.get("connectorProfileCredentials") : profile.path("credentials");
        verifyConnection(type, properties.path(type), credentials.path(type));
        if (config.path("connectorProfileProperties").isObject()) {
            profile.set("connectorProfileProperties", config.get("connectorProfileProperties").deepCopy());
        }
        if (config.path("connectorProfileCredentials").isObject()) {
            profile.set("credentials", config.get("connectorProfileCredentials").deepCopy());
        }
        if (in.hasNonNull("connectionMode")) {
            profile.put("connectionMode", in.get("connectionMode").asText());
        }
        profile.put("lastUpdatedAt", Instant.now().getEpochSecond());
        profiles.put(key(region, name), profile);
        return mapper.createObjectNode().put("connectorProfileArn", profile.path("connectorProfileArn").asText());
    }

    public synchronized void deleteProfile(JsonNode in, String region) {
        check("DeleteConnectorProfile", in);
        String name = in.path("connectorProfileName").asText();
        requireProfile(name, region);
        boolean inUse = flows.scan(k -> k.startsWith(region + "::")).stream().anyMatch(flow ->
                name.equals(flow.path("sourceFlowConfig").path("connectorProfileName").asText())
                        || flow.withArray("destinationFlowConfigList").findValuesAsText("connectorProfileName")
                        .contains(name));
        if (inUse && !in.path("forceDelete").asBoolean(false)) {
            throw new AwsException("ConflictException", "Connector profile " + name + " is used by a flow; delete the "
                    + "flow first or use forceDelete.", 409);
        }
        profiles.delete(key(region, name));
    }

    public ObjectNode describeProfiles(JsonNode in, String region) {
        check("DescribeConnectorProfiles", in);
        List<String> names = new ArrayList<>();
        in.path("connectorProfileNames").forEach(n -> names.add(n.asText()));
        String type = in.path("connectorType").asText(null);
        String label = in.path("connectorLabel").asText(null);
        List<ObjectNode> all = profiles.scan(k -> k.startsWith(region + "::")).stream()
                .filter(p -> names.isEmpty() || names.contains(p.path("connectorProfileName").asText()))
                .filter(p -> type == null || type.equals(p.path("connectorType").asText()))
                .filter(p -> label == null || label.equals(p.path("connectorLabel").asText()))
                .sorted(Comparator.comparingLong(p -> p.path("createdAt").asLong()))
                .map(p -> {
                    ObjectNode copy = p.deepCopy();
                    copy.remove("credentials");
                    return copy;
                }).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("connectorProfileDetails");
        page(all, in, out).forEach(list::add);
        return out;
    }

    /**
     * Checks a profile's connection as AppFlow does when it is saved. Salesforce's token is checked
     * against the instance's userinfo endpoint; a connector with an instance URL (or Snowflake's
     * account host) must answer at it. A host that can't be reached is a
     * {@code ConnectorServerException}, a rejected token a {@code ConnectorAuthenticationException}.
     * Connectors without an address to check are accepted as given.
     */
    private void verifyConnection(String type, JsonNode properties, JsonNode credentials) {
        String url = properties.path("instanceUrl").asText(properties.path("applicationHostUrl").asText(""));
        if ("Snowflake".equals(type) && properties.hasNonNull("accountName")) {
            url = "https://" + properties.get("accountName").asText() + ".snowflakecomputing.com"; // partition-literal: Snowflake's own domain
        }
        if (url.isEmpty()) {
            return;
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw validation("The connector's instance URL must be an https:// URL.");
        }
        boolean salesforce = "Salesforce".equals(type) || "Pardot".equals(type);
        String target = salesforce ? url.replaceAll("/+$", "") + "/services/oauth2/userinfo" : url;
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(8)).GET();
        } catch (IllegalArgumentException e) {
            throw validation("The connector's instance URL " + url + " is not valid.");
        }
        String token = credentials.path("accessToken").asText("");
        if (!token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        int status;
        try {
            status = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER).build()
                    .send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new AwsException("ConnectorServerException", "Couldn't connect to the " + type + " instance at "
                    + url + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), 400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AwsException("ConnectorServerException", "Couldn't connect to the " + type + " instance at "
                    + url + ".", 400);
        }
        if (salesforce && (status == 401 || status == 403)) {
            throw new AwsException("ConnectorAuthenticationException", "The " + type + " access token was "
                    + "rejected (HTTP " + status + ").", 401);
        }
        if (salesforce && status / 100 != 2) {
            throw new AwsException("ConnectorServerException", "The " + type + " instance at " + url + " answered "
                    + "HTTP " + status + ".", 400);
        }
    }

    ObjectNode requireProfile(String name, String region) {
        return profiles.get(key(region, name)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Connector profile " + name + " does not exist.", 404));
    }

    // ── Flows ──────────────────────────────────────────────────────────────

    public synchronized ObjectNode createFlow(JsonNode in, String region) {
        check("CreateFlow", in);
        String name = in.path("flowName").asText();
        if (flows.get(key(region, name)).isPresent()) {
            throw new AwsException("ConflictException", "A flow named " + name + " already exists.", 409);
        }
        checkDefinition(in, region);
        long now = Instant.now().getEpochSecond();
        ObjectNode flow = mapper.createObjectNode();
        flow.put("flowArn", arn(region, "flow/" + name));
        flow.put("flowName", name);
        flow.put("description", in.path("description").asText(""));
        if (in.hasNonNull("kmsArn")) {
            flow.put("kmsArn", in.get("kmsArn").asText());
        }
        applyDefinition(flow, in);
        String trigger = flow.path("triggerConfig").path("triggerType").asText();
        flow.put("flowStatus", "OnDemand".equals(trigger) ? "Active" : "Draft");
        flow.put("createdAt", now);
        flow.put("lastUpdatedAt", now);
        flow.put("createdBy", callerArn());
        flow.put("lastUpdatedBy", callerArn());
        flow.put("schemaVersion", 1);
        ObjectNode tags = flow.putObject("tags");
        in.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        flows.put(key(region, name), flow);
        return mapper.createObjectNode().put("flowArn", flow.path("flowArn").asText())
                .put("flowStatus", flow.path("flowStatus").asText());
    }

    public synchronized ObjectNode updateFlow(JsonNode in, String region, AppFlowRuns runs) {
        check("UpdateFlow", in);
        String name = in.path("flowName").asText();
        ObjectNode flow = requireFlow(name, region);
        checkDefinition(in, region);
        if (in.has("description")) {
            flow.put("description", in.path("description").asText(""));
        }
        String oldTrigger = flow.path("triggerConfig").path("triggerType").asText();
        applyDefinition(flow, in);
        String trigger = flow.path("triggerConfig").path("triggerType").asText();
        if (!trigger.equals(oldTrigger)) {
            runs.unschedule(name, region);
            flow.put("flowStatus", "OnDemand".equals(trigger) ? "Active" : "Draft");
        } else if ("Active".equals(flow.path("flowStatus").asText()) && "Scheduled".equals(trigger)) {
            runs.schedule(flow, region);
        }
        flow.put("lastUpdatedAt", Instant.now().getEpochSecond());
        flow.put("lastUpdatedBy", callerArn());
        flow.put("schemaVersion", flow.path("schemaVersion").asLong(1) + 1);
        flows.put(key(region, name), flow);
        return mapper.createObjectNode().put("flowStatus", flow.path("flowStatus").asText());
    }

    private void applyDefinition(ObjectNode flow, JsonNode in) {
        for (String field : List.of("triggerConfig", "sourceFlowConfig", "destinationFlowConfigList", "tasks",
                "metadataCatalogConfig")) {
            if (in.has(field)) {
                flow.set(field, in.get(field).deepCopy());
            }
        }
    }

    /**
     * Checks a flow's connectors as AppFlow does when the flow is saved: each must exist and be able to
     * play its part, a profile must be given where the connector needs one, and an S3 source must have
     * objects under its prefix and an S3 bucket must let AppFlow in.
     */
    private void checkDefinition(JsonNode in, String region) {
        JsonNode source = in.path("sourceFlowConfig");
        String sourceType = source.path("connectorType").asText();
        checkConnector(sourceType, source.path("connectorProfileName").asText(null), true, region);
        if ("S3".equals(sourceType)) {
            JsonNode properties = source.path("sourceConnectorProperties").path("S3");
            String bucket = properties.path("bucketName").asText("");
            String prefix = properties.path("bucketPrefix").asText("");
            checkBucket(bucket, "source");
            List<S3Object> objects = s3.get().listObjects(bucket, prefix.isEmpty() ? null : prefix, null, 10);
            if (objects.stream().noneMatch(o -> !o.getKey().endsWith("/"))) {
                throw new AwsException("ConnectorServerException", "There are no objects under the S3 source "
                        + "prefix s3://" + bucket + "/" + prefix + ".", 400);
            }
        }
        JsonNode destinations = in.path("destinationFlowConfigList");
        if (!destinations.isArray() || destinations.isEmpty()) {
            throw validation("A flow needs a destination.");
        }
        for (JsonNode destination : destinations) {
            String type = destination.path("connectorType").asText();
            checkConnector(type, destination.path("connectorProfileName").asText(null), false, region);
            if ("S3".equals(type)) {
                checkBucket(destination.path("destinationConnectorProperties").path("S3").path("bucketName")
                        .asText(""), "destination");
            }
        }
        JsonNode tasks = in.path("tasks");
        if (!tasks.isArray() || tasks.isEmpty()) {
            throw validation("A flow needs at least one task.");
        }
        JsonNode trigger = in.path("triggerConfig");
        if ("Scheduled".equals(trigger.path("triggerType").asText())) {
            String expression = trigger.path("triggerProperties").path("Scheduled").path("scheduleExpression")
                    .asText("");
            if (AppFlowRuns.rateMillis(expression) == null && !expression.startsWith("cron(")) {
                throw validation("A scheduled flow needs a rate(), such as rate(5minutes), or a cron() "
                        + "scheduleExpression.");
            }
            Long rate = AppFlowRuns.rateMillis(expression);
            if (rate != null && rate < 60_000L) {
                throw validation("A scheduled flow runs at most once a minute.");
            }
        }
    }

    private void checkConnector(String type, String profileName, boolean asSource, String region) {
        boolean[] roles = CONNECTORS.get(type);
        if (roles == null) {
            throw validation("Connector type " + type + " is not supported.");
        }
        if (asSource ? !roles[0] : !roles[1]) {
            throw validation(type + " can't be a flow " + (asSource ? "source" : "destination") + ".");
        }
        boolean needsProfile = !Set.of("S3", "EventBridge", "LookoutMetrics").contains(type);
        if (needsProfile && (profileName == null || profileName.isEmpty())) {
            throw validation("A " + type + " connector needs a connectorProfileName.");
        }
        if (profileName != null && !profileName.isEmpty()) {
            requireProfile(profileName, region);
        }
    }

    private void checkBucket(String bucket, String role) {
        if (!s3.isResolvable() || !s3.get().bucketExists(bucket)) {
            throw new AwsException("ConnectorServerException", "The S3 " + role + " bucket " + bucket
                    + " does not exist.", 400);
        }
        String policy;
        try {
            policy = s3.get().getBucketPolicy(bucket);
        } catch (AwsException e) {
            policy = "";
        }
        if (!policy.contains(SERVICE_PRINCIPAL)) {
            throw new AwsException("AccessDeniedException", "The bucket policy of " + bucket + " doesn't let "
                    + SERVICE_PRINCIPAL + " use the " + role + " bucket.", 403);
        }
    }

    public synchronized void deleteFlow(JsonNode in, String region, AppFlowRuns runs) {
        check("DeleteFlow", in);
        String name = in.path("flowName").asText();
        requireFlow(name, region);
        if (runs.hasRunning(name, region) && !in.path("forceDelete").asBoolean(false)) {
            throw new AwsException("ConflictException", "Flow " + name + " has a run in progress; wait for it, or "
                    + "use forceDelete.", 409);
        }
        runs.forget(name, region);
        flows.delete(key(region, name));
    }

    public ObjectNode describeFlow(JsonNode in, String region) {
        check("DescribeFlow", in);
        ObjectNode copy = requireFlow(in.path("flowName").asText(), region).deepCopy();
        if (!copy.has("flowStatusMessage")) {
            copy.put("flowStatusMessage", "");
        }
        return copy;
    }

    public ObjectNode listFlows(JsonNode in, String region) {
        check("ListFlows", in);
        List<ObjectNode> all = flows.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(Comparator.comparingLong(f -> f.path("createdAt").asLong()))
                .map(f -> {
                    ObjectNode summary = mapper.createObjectNode();
                    for (String field : List.of("flowArn", "description", "flowName", "flowStatus", "createdAt",
                            "lastUpdatedAt", "createdBy", "lastUpdatedBy", "tags", "lastRunExecutionDetails")) {
                        if (f.has(field)) {
                            summary.set(field, f.get(field).deepCopy());
                        }
                    }
                    summary.put("sourceConnectorType", f.path("sourceFlowConfig").path("connectorType").asText());
                    summary.put("destinationConnectorType", f.path("destinationFlowConfigList").path(0)
                            .path("connectorType").asText());
                    summary.put("triggerType", f.path("triggerConfig").path("triggerType").asText());
                    return summary;
                }).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("flows");
        page(all, in, out).forEach(list::add);
        return out;
    }

    ObjectNode requireFlow(String name, String region) {
        return flows.get(key(region, name)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Flow " + name + " does not exist.", 404));
    }

    Optional<ObjectNode> findFlow(String name, String region) {
        return flows.get(key(region, name));
    }

    void saveFlow(String region, ObjectNode flow) {
        flows.put(key(region, flow.path("flowName").asText()), flow);
    }

    static boolean runnable(ObjectNode flow) {
        if (!RUNNABLE.contains(flow.path("sourceFlowConfig").path("connectorType").asText())) {
            return false;
        }
        for (JsonNode destination : flow.withArray("destinationFlowConfigList")) {
            if (!RUNNABLE.contains(destination.path("connectorType").asText())) {
                return false;
            }
        }
        return true;
    }

    // ── Connectors ─────────────────────────────────────────────────────────

    private void checkConnectorType(String type, String label, String region) {
        if (!CONNECTORS.containsKey(type)) {
            throw validation("Connector type " + type + " is not supported.");
        }
        if ("CustomConnector".equals(type)) {
            if (label == null || registrations.get(key(region, label)).isEmpty()) {
                throw new AwsException("ResourceNotFoundException", "Custom connector " + label
                        + " is not registered.", 404);
            }
        }
    }

    public ObjectNode describeConnectors(JsonNode in, String region) {
        check("DescribeConnectors", in);
        List<String> types = new ArrayList<>();
        in.path("connectorTypes").forEach(t -> types.add(t.asText()));
        ObjectNode out = mapper.createObjectNode();
        ObjectNode configurations = out.putObject("connectorConfigurations");
        CONNECTORS.forEach((type, roles) -> {
            if (types.isEmpty() || types.contains(type)) {
                configurations.set(type, configuration(type, roles));
            }
        });
        ArrayNode connectors = out.putArray("connectors");
        registrations.scan(k -> k.startsWith(region + "::")).forEach(r -> connectors.add(detail(r)));
        return out;
    }

    private ObjectNode configuration(String type, boolean[] roles) {
        ObjectNode config = mapper.createObjectNode();
        config.put("canUseAsSource", roles[0]);
        config.put("canUseAsDestination", roles[1]);
        config.put("isPrivateLinkEnabled", false);
        config.put("isPrivateLinkEndpointUrlRequired", false);
        config.put("supportsExecutionStatusNotifications", false);
        ArrayNode triggers = config.putArray("supportedTriggerTypes");
        triggers.add("OnDemand");
        if (roles[0]) {
            triggers.add("Scheduled");
        }
        if (Set.of("Salesforce", "Pardot").contains(type)) {
            triggers.add("Event");
        }
        ArrayNode destinations = config.putArray("supportedDestinationConnectors");
        if (roles[0]) {
            CONNECTORS.forEach((other, otherRoles) -> {
                if (otherRoles[1]) {
                    destinations.add(other);
                }
            });
        }
        config.put("connectorType", type);
        config.put("connectorOwner", "AWS");
        config.put("connectorName", type);
        config.putObject("connectorMetadata");
        ArrayNode modes = config.putArray("supportedDataTransferTypes");
        modes.add("RECORD");
        if ("S3".equals(type)) {
            modes.add("FILE");
        }
        return config;
    }

    public ObjectNode listConnectors(JsonNode in, String region) {
        check("ListConnectors", in);
        List<ObjectNode> all = new ArrayList<>();
        CONNECTORS.keySet().forEach(type -> all.add(mapper.createObjectNode().put("connectorName", type)
                .put("connectorType", type).put("connectorOwner", "AWS").put("applicationType", type)));
        registrations.scan(k -> k.startsWith(region + "::")).forEach(r -> all.add(detail(r)));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("connectors");
        page(all, in, out).forEach(list::add);
        return out;
    }

    public ObjectNode describeConnector(JsonNode in, String region) {
        check("DescribeConnector", in);
        String type = in.path("connectorType").asText();
        String label = in.path("connectorLabel").asText(null);
        ObjectNode out = mapper.createObjectNode();
        if ("CustomConnector".equals(type)) {
            ObjectNode registration = registrations.get(key(region, label == null ? "" : label)).orElseThrow(() ->
                    new AwsException("ResourceNotFoundException", "Custom connector " + label
                            + " is not registered.", 404));
            ObjectNode config = configuration(type, CONNECTORS.get(type));
            config.setAll(detail(registration));
            config.put("registeredAt", registration.path("registeredAt").asLong());
            out.set("connectorConfiguration", config);
            return out;
        }
        if (!CONNECTORS.containsKey(type)) {
            throw new AwsException("ResourceNotFoundException", "Connector " + type + " does not exist.", 404);
        }
        out.set("connectorConfiguration", configuration(type, CONNECTORS.get(type)));
        return out;
    }

    public synchronized ObjectNode registerConnector(JsonNode in, String region) {
        check("RegisterConnector", in);
        String label = in.path("connectorLabel").asText();
        if (registrations.get(key(region, label)).isPresent()) {
            throw new AwsException("ConflictException", "A connector labelled " + label + " is already registered.",
                    409);
        }
        String lambdaArn = in.path("connectorProvisioningConfig").path("lambda").path("lambdaArn").asText("");
        checkLambda(lambdaArn, region);
        ObjectNode registration = mapper.createObjectNode();
        registration.put("connectorLabel", label);
        registration.put("connectorArn", arn(region, "connector/" + label));
        registration.put("connectorDescription", in.path("description").asText(""));
        registration.put("connectorProvisioningType", in.path("connectorProvisioningType").asText("LAMBDA"));
        registration.set("connectorProvisioningConfig", in.path("connectorProvisioningConfig").deepCopy());
        registration.put("registeredAt", Instant.now().getEpochSecond());
        registration.put("registeredBy", callerArn());
        registrations.put(key(region, label), registration);
        return mapper.createObjectNode().put("connectorArn", registration.path("connectorArn").asText());
    }

    public synchronized ObjectNode updateRegistration(JsonNode in, String region) {
        check("UpdateConnectorRegistration", in);
        String label = in.path("connectorLabel").asText();
        ObjectNode registration = registrations.get(key(region, label)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "Custom connector " + label + " is not registered.", 404));
        if (in.has("description")) {
            registration.put("connectorDescription", in.path("description").asText(""));
        }
        if (in.path("connectorProvisioningConfig").isObject()) {
            checkLambda(in.path("connectorProvisioningConfig").path("lambda").path("lambdaArn").asText(""), region);
            registration.set("connectorProvisioningConfig", in.get("connectorProvisioningConfig").deepCopy());
        }
        registrations.put(key(region, label), registration);
        return mapper.createObjectNode().put("connectorArn", registration.path("connectorArn").asText());
    }

    public synchronized void unregisterConnector(JsonNode in, String region) {
        check("UnregisterConnector", in);
        String label = in.path("connectorLabel").asText();
        if (registrations.get(key(region, label)).isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "Custom connector " + label + " is not registered.",
                    404);
        }
        boolean inUse = profiles.scan(k -> k.startsWith(region + "::")).stream()
                .anyMatch(p -> label.equals(p.path("connectorLabel").asText()));
        if (inUse && !in.path("forceDelete").asBoolean(false)) {
            throw new AwsException("ConflictException", "Connector " + label + " has connector profiles; delete them "
                    + "first or use forceDelete.", 409);
        }
        registrations.delete(key(region, label));
    }

    private void checkLambda(String lambdaArn, String region) {
        if (!AwsArnUtils.isArnFor(lambdaArn, "lambda")) {
            throw validation("connectorProvisioningConfig.lambda.lambdaArn must be a Lambda function ARN.");
        }
        if (lambda.isResolvable()) {
            try {
                lambda.get().getFunction(AwsArnUtils.parse(lambdaArn).region(), lambdaArn);
            } catch (AwsException e) {
                throw new AwsException("ResourceNotFoundException", "The connector's Lambda function " + lambdaArn
                        + " does not exist.", 404);
            }
        }
    }

    private ObjectNode detail(ObjectNode registration) {
        ObjectNode out = mapper.createObjectNode();
        out.put("connectorDescription", registration.path("connectorDescription").asText(""));
        out.put("connectorName", registration.path("connectorLabel").asText());
        out.put("connectorOwner", regionResolver.getAccountId());
        out.put("applicationType", "CustomConnector");
        out.put("connectorType", "CustomConnector");
        out.put("connectorLabel", registration.path("connectorLabel").asText());
        out.put("registeredAt", registration.path("registeredAt").asLong());
        out.put("registeredBy", registration.path("registeredBy").asText());
        out.put("connectorProvisioningType", registration.path("connectorProvisioningType").asText());
        out.put("connectorArn", registration.path("connectorArn").asText());
        return out;
    }

    // ── Connector entities ─────────────────────────────────────────────────

    /** The S3 connector's entities: buckets at the root, then prefixes and objects under a bucket. */
    public ObjectNode listEntities(JsonNode in, String region) {
        check("ListConnectorEntities", in);
        String type = in.path("connectorType").asText("S3");
        ObjectNode out = mapper.createObjectNode();
        ObjectNode map = out.putObject("connectorEntityMap");
        if (in.hasNonNull("connectorProfileName")) {
            requireProfile(in.get("connectorProfileName").asText(), region);
        }
        if (!"S3".equals(type)) {
            throw new AwsException("ConnectorServerException", "Floci can't reach " + type + " to list its entities.",
                    400);
        }
        String path = in.path("entitiesPath").asText("");
        ArrayNode entities = map.putArray(path);
        if (path.isEmpty()) {
            for (Bucket bucket : s3.get().listBuckets()) {
                entities.addObject().put("name", bucket.getName()).put("label", bucket.getName())
                        .put("hasNestedEntities", true);
            }
            return out;
        }
        String bucket = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        String prefix = path.contains("/") ? path.substring(path.indexOf('/') + 1) : "";
        if (!s3.get().bucketExists(bucket)) {
            throw new AwsException("ResourceNotFoundException", "Bucket " + bucket + " does not exist.", 404);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (S3Object object : s3.get().listObjects(bucket, prefix.isEmpty() ? null : prefix, null, 10_000)) {
            String rest = object.getKey().substring(prefix.length()).replaceAll("^/", "");
            String child = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
            if (!child.isEmpty() && seen.add(child)) {
                String name = bucket + "/" + (prefix.isEmpty() ? "" : prefix.replaceAll("/?$", "/")) + child;
                entities.addObject().put("name", name).put("label", child).put("hasNestedEntities", rest.contains("/"));
            }
        }
        return out;
    }

    /** The fields of an S3 entity: the columns of its first CSV object, or the keys of its first JSON record. */
    public ObjectNode describeEntity(JsonNode in, String region) {
        check("DescribeConnectorEntity", in);
        String type = in.path("connectorType").asText("S3");
        if (in.hasNonNull("connectorProfileName")) {
            requireProfile(in.get("connectorProfileName").asText(), region);
        }
        if (!"S3".equals(type)) {
            throw new AwsException("ConnectorServerException", "Floci can't reach " + type
                    + " to describe its entities.", 400);
        }
        String entity = in.path("connectorEntityName").asText();
        String bucket = entity.contains("/") ? entity.substring(0, entity.indexOf('/')) : entity;
        String prefix = entity.contains("/") ? entity.substring(entity.indexOf('/') + 1) : "";
        if (!s3.get().bucketExists(bucket)) {
            throw new AwsException("ResourceNotFoundException", "Bucket " + bucket + " does not exist.", 404);
        }
        S3Object first = s3.get().listObjects(bucket, prefix.isEmpty() ? null : prefix, null, 100).stream()
                .filter(o -> !o.getKey().endsWith("/")).findFirst().orElseThrow(() -> new AwsException(
                        "ResourceNotFoundException", "There are no objects under " + entity + ".", 404));
        String text = new String(s3.get().getObject(bucket, first.getKey()).getData(), StandardCharsets.UTF_8);
        List<String> fields = AppFlowRecords.fieldsOf(text, mapper);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("connectorEntityFields");
        for (String field : fields) {
            ObjectNode node = list.addObject();
            node.put("identifier", field);
            node.put("label", field);
            node.put("isPrimaryKey", false);
            node.put("isDeprecated", false);
            node.putArray("supportedFieldTypeDetails").addObject().putObject("v1").put("fieldType", "String")
                    .set("filterOperators", mapper.valueToTree(List.of("PROJECTION", "EQUAL_TO", "NOT_EQUAL_TO",
                            "CONTAINS", "LESS_THAN", "GREATER_THAN", "BETWEEN")));
            node.putObject("sourceProperties").put("isRetrievable", true).put("isQueryable", true);
            node.putObject("destinationProperties").put("isCreatable", true).put("isNullable", true)
                    .put("isUpsertable", false).put("isUpdatable", false).put("isDefaultedOnCreate", false);
        }
        return out;
    }

    public void resetMetadataCache(JsonNode in, String region) {
        check("ResetConnectorMetadataCache", in);
        if (in.hasNonNull("connectorProfileName")) {
            requireProfile(in.get("connectorProfileName").asText(), region);
        }
        if (in.hasNonNull("connectorType") && !CONNECTORS.containsKey(in.get("connectorType").asText())) {
            throw validation("Connector type " + in.get("connectorType").asText() + " is not supported.");
        }
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    private record Tagged(AccountAwareStorageBackend<ObjectNode> store, String key, ObjectNode resource) {}

    private Tagged tagged(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "appflow")) {
            throw validation("The resource ARN " + arn + " is not an AppFlow ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String arnRegion = parsed.region().isEmpty() ? region : parsed.region();
        if (parsed.resource().startsWith("flow/")) {
            String storeKey = key(arnRegion, parsed.resource().substring("flow/".length()));
            return flows.get(storeKey).map(f -> new Tagged(flows, storeKey, f)).orElseThrow(() ->
                    new AwsException("ResourceNotFoundException", "Resource " + arn + " not found.", 404));
        }
        throw new AwsException("ResourceNotFoundException", "Resource " + arn + " not found.", 404);
    }

    public Map<String, String> listTags(String arn, String region) {
        Map<String, String> out = new LinkedHashMap<>();
        tagged(arn, region).resource().path("tags").fields().forEachRemaining(e -> out.put(e.getKey(),
                e.getValue().asText()));
        return out;
    }

    public synchronized void tag(String arn, Map<String, String> tags, String region) {
        Tagged tagged = tagged(arn, region);
        ObjectNode current = tagged.resource().withObject("tags");
        tags.forEach(current::put);
        if (current.size() > 50) {
            throw validation("A resource can have at most 50 tags.");
        }
        tagged.store().put(tagged.key(), tagged.resource());
    }

    public synchronized void untag(String arn, List<String> keys, String region) {
        Tagged tagged = tagged(arn, region);
        keys.forEach(tagged.resource().withObject("tags")::remove);
        tagged.store().put(tagged.key(), tagged.resource());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    String callerArn() {
        return AwsArnUtils.Arn.global(regionResolver.getPartition(), "iam", regionResolver.getAccountId(), "root")
                .toString();
    }

    String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("appflow", region, regionResolver.getAccountId(), resource).toString();
    }

    static List<ObjectNode> page(List<ObjectNode> all, JsonNode in, ObjectNode out) {
        int max = in.path("maxResults").asInt(100);
        int start = 0;
        if (in.hasNonNull("nextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getDecoder().decode(in.get("nextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw validation("The nextToken is not valid.");
            }
        }
        int end = Math.min(all.size(), start + Math.max(1, max));
        if (end < all.size()) {
            out.put("nextToken", Base64.getEncoder().encodeToString(Integer.toString(end)
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return all.subList(Math.min(start, end), end);
    }

    static String key(String region, String name) {
        return region + "::" + name;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }


    @Override
    public void clear() {
        flows.clear();
        profiles.clear();
        registrations.clear();
    }
}
