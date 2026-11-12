package io.floci.aws.services.opensearchserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.services.sqs.model.Message;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Amazon OpenSearch Serverless. A collection needs an encryption policy that covers its name; it's {@code CREATING}
 * while its OpenSearch ({@link AossEngine}) starts, then {@code ACTIVE}, with an endpoint Floci serves. Requests to the
 * endpoint, and the index APIs, need a network policy that reaches the collection and a data access policy that grants
 * the caller the permission the request needs. Ingestion jobs index the documents under an S3 prefix; ingestion streams
 * index the objects their SQS queue announces.
 */
@ApplicationScoped
public class AossService {

    private static final Logger LOG = Logger.getLogger(AossService.class);
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String ACCOUNT = "account";

    private final AossStore store;
    private final ObjectMapper mapper;
    private final AossEngine engine;
    private final Provider<Ec2Service> ec2;
    private final Provider<IamService> iam;
    private final Provider<S3Service> s3;
    private final Provider<SqsService> sqs;
    private final Instance<RequestContext> requestContext;
    private final RegionResolver regionResolver;
    private final Vertx vertx;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "aoss");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public AossService(AossStore store, AossEngine engine, Provider<Ec2Service> ec2, Provider<IamService> iam,
                       Provider<S3Service> s3, Provider<SqsService> sqs, Instance<RequestContext> requestContext,
                       RegionResolver regionResolver, Vertx vertx) {
        this.store = store;
        this.mapper = store.mapper();
        this.engine = engine;
        this.ec2 = ec2;
        this.iam = iam;
        this.s3 = s3;
        this.sqs = sqs;
        this.requestContext = requestContext;
        this.regionResolver = regionResolver;
        this.vertx = vertx;
    }

    void onStart(@Observes StartupEvent event) {
        if (store.config().enabled()) {
            vertx.setPeriodic(Math.max(1, store.config().streamPollSeconds()) * 1000L, id -> worker.submit(
                    this::pollStreams));
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    private void later(String account, String region, Runnable task) {
        vertx.setTimer(Math.max(1, store.config().transitionMillis()), id -> worker.submit(() -> {
            try {
                RequestScopes.runAs(account, region, task);
            } catch (RuntimeException e) {
                LOG.warnv("OpenSearch Serverless work failed: {0}", e.getMessage());
            }
        }));
    }

    private static String required(JsonNode in, String member) {
        String value = in.path(member).asText("");
        if (value.isBlank()) {
            throw AossStore.validation(member + " is required.");
        }
        return value;
    }

    private ObjectNode empty() {
        return mapper.createObjectNode();
    }

    private String arn(String region, String account, String resource) {
        return AwsArnUtils.Arn.of(AossStore.SERVICE, region, account, resource).toString();
    }

    private ObjectNode idempotent(String region, String operation, JsonNode in, Supplier<ObjectNode> create) {
        String token = in.path("clientToken").asText(null);
        if (token == null) {
            return create.get();
        }
        String key = operation + "/" + token;
        ObjectNode earlier = store.get(region, "token", key).orElse(null);
        ObjectNode request = in.deepCopy();
        request.remove("clientToken");
        if (earlier != null) {
            if (!earlier.path("request").equals(request)) {
                throw AossStore.conflict("The client token " + token + " was used with different parameters.");
            }
            return earlier.path("response").deepCopy();
        }
        ObjectNode out = create.get();
        ObjectNode entry = store.record();
        entry.set("request", request);
        entry.set("response", out.deepCopy());
        store.put(region, "token", key, entry);
        return out;
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode in, String name) {
        ObjectNode out = empty();
        AossStore.page(items, in, out, name, 100);
        return out;
    }

    // ================================================================ security and lifecycle policies

    private static String policyKey(String type, String name) {
        return type + "/" + name;
    }

    private ObjectNode createPolicy(JsonNode in, String region, String kind, String detailName) {
        String type = required(in, "type");
        String name = required(in, "name");
        String document = required(in, "policy");
        JsonNode policy = AossPolicies.parse(mapper, document);
        switch (type) {
            case "encryption" -> AossPolicies.checkEncryption(policy);
            case "network" -> AossPolicies.checkNetwork(policy);
            case "data" -> AossPolicies.checkData(policy);
            default -> AossPolicies.checkRetention(policy);
        }
        return idempotent(region, "Create" + kind, in, () -> {
            if (store.get(region, kind, policyKey(type, name)).isPresent()) {
                throw AossStore.conflict("Policy with name " + name + " and type " + type + " already exists");
            }
            if (store.list(region, kind).size() >= 500) {
                throw AossStore.quota("The account has the maximum number of policies.");
            }
            ObjectNode record = store.record();
            long now = AossStore.now();
            record.put("type", type);
            record.put("name", name);
            record.put("policyVersion", AossStore.version());
            if (in.hasNonNull("description")) {
                record.put("description", in.get("description").asText());
            }
            record.set("policy", policy);
            record.put("createdDate", now);
            record.put("lastModifiedDate", now);
            store.put(region, kind, policyKey(type, name), record);
            ObjectNode out = empty();
            out.set(detailName, AossStore.view(record));
            return out;
        });
    }

    private ObjectNode policy(String region, String kind, JsonNode in) {
        String type = required(in, "type");
        String name = required(in, "name");
        return store.get(region, kind, policyKey(type, name)).orElseThrow(() ->
                AossStore.notFound("Policy with name " + name + " and type " + type + " is not found"));
    }

    private ObjectNode getPolicy(JsonNode in, String region, String kind, String detailName) {
        ObjectNode out = empty();
        out.set(detailName, AossStore.view(policy(region, kind, in)));
        return out;
    }

    private ObjectNode updatePolicy(JsonNode in, String region, String kind, String detailName) {
        ObjectNode record = policy(region, kind, in);
        if (!required(in, "policyVersion").equals(record.path("policyVersion").asText())) {
            throw AossStore.conflict("Policy version specified in the request refers to an older version and policy "
                    + "has since changed");
        }
        boolean changed = false;
        if (in.hasNonNull("policy")) {
            JsonNode policy = AossPolicies.parse(mapper, in.get("policy").asText());
            switch (record.path("type").asText()) {
                case "encryption" -> AossPolicies.checkEncryption(policy);
                case "network" -> AossPolicies.checkNetwork(policy);
                case "data" -> AossPolicies.checkData(policy);
                default -> AossPolicies.checkRetention(policy);
            }
            changed |= !policy.equals(record.path("policy"));
            record.set("policy", policy);
        }
        if (in.has("description")) {
            changed |= !in.path("description").asText("").equals(record.path("description").asText(""));
            record.put("description", in.path("description").asText(""));
        }
        if (changed) {
            record.put("policyVersion", AossStore.version());
            record.put("lastModifiedDate", AossStore.now());
            store.put(region, kind, policyKey(record.path("type").asText(), record.path("name").asText()), record);
        }
        ObjectNode out = empty();
        out.set(detailName, AossStore.view(record));
        return out;
    }

    private ObjectNode deletePolicy(JsonNode in, String region, String kind) {
        ObjectNode record = policy(region, kind, in);
        store.delete(region, kind, policyKey(record.path("type").asText(), record.path("name").asText()));
        return empty();
    }

    private ObjectNode listPolicies(JsonNode in, String region, String kind, String name) {
        String type = required(in, "type");
        List<String> resources = new ArrayList<>();
        in.path(in.has("resources") ? "resources" : "resource").forEach(resource -> resources.add(resource.asText()));
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, kind)) {
            if (!type.equals(record.path("type").asText())) {
                continue;
            }
            if (!resources.isEmpty() && !covers(record.path("policy"), resources)) {
                continue;
            }
            ObjectNode summary = AossStore.view(record);
            summary.remove("policy");
            items.add(summary);
        }
        return page(items, in, name);
    }

    private static boolean covers(JsonNode policy, List<String> resources) {
        List<JsonNode> statements = new ArrayList<>();
        if (policy.isArray()) {
            policy.forEach(statements::add);
        } else {
            statements.add(policy);
        }
        for (JsonNode statement : statements) {
            for (JsonNode rule : statement.path("Rules")) {
                for (JsonNode pattern : rule.path("Resource")) {
                    for (String resource : resources) {
                        if (AossPolicies.matches(pattern.asText(), resource) || pattern.asText().equals(resource)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public ObjectNode createSecurityPolicy(JsonNode in, String region) {
        return createPolicy(in, region, AossStore.SECURITY_POLICY, "securityPolicyDetail");
    }

    public ObjectNode getSecurityPolicy(JsonNode in, String region) {
        return getPolicy(in, region, AossStore.SECURITY_POLICY, "securityPolicyDetail");
    }

    public ObjectNode updateSecurityPolicy(JsonNode in, String region) {
        return updatePolicy(in, region, AossStore.SECURITY_POLICY, "securityPolicyDetail");
    }

    public ObjectNode deleteSecurityPolicy(JsonNode in, String region) {
        return deletePolicy(in, region, AossStore.SECURITY_POLICY);
    }

    public ObjectNode listSecurityPolicies(JsonNode in, String region) {
        return listPolicies(in, region, AossStore.SECURITY_POLICY, "securityPolicySummaries");
    }

    public ObjectNode createAccessPolicy(JsonNode in, String region) {
        return createPolicy(in, region, AossStore.ACCESS_POLICY, "accessPolicyDetail");
    }

    public ObjectNode getAccessPolicy(JsonNode in, String region) {
        return getPolicy(in, region, AossStore.ACCESS_POLICY, "accessPolicyDetail");
    }

    public ObjectNode updateAccessPolicy(JsonNode in, String region) {
        return updatePolicy(in, region, AossStore.ACCESS_POLICY, "accessPolicyDetail");
    }

    public ObjectNode deleteAccessPolicy(JsonNode in, String region) {
        return deletePolicy(in, region, AossStore.ACCESS_POLICY);
    }

    public ObjectNode listAccessPolicies(JsonNode in, String region) {
        return listPolicies(in, region, AossStore.ACCESS_POLICY, "accessPolicySummaries");
    }

    public ObjectNode createLifecyclePolicy(JsonNode in, String region) {
        return createPolicy(in, region, AossStore.LIFECYCLE_POLICY, "lifecyclePolicyDetail");
    }

    public ObjectNode updateLifecyclePolicy(JsonNode in, String region) {
        return updatePolicy(in, region, AossStore.LIFECYCLE_POLICY, "lifecyclePolicyDetail");
    }

    public ObjectNode deleteLifecyclePolicy(JsonNode in, String region) {
        return deletePolicy(in, region, AossStore.LIFECYCLE_POLICY);
    }

    public ObjectNode listLifecyclePolicies(JsonNode in, String region) {
        return listPolicies(in, region, AossStore.LIFECYCLE_POLICY, "lifecyclePolicySummaries");
    }

    public ObjectNode batchGetLifecyclePolicy(JsonNode in, String region) {
        ObjectNode out = empty();
        ArrayNode details = out.putArray("lifecyclePolicyDetails");
        ArrayNode errors = out.putArray("lifecyclePolicyErrorDetails");
        for (JsonNode identifier : in.path("identifiers")) {
            String type = identifier.path("type").asText();
            String name = identifier.path("name").asText();
            store.get(region, AossStore.LIFECYCLE_POLICY, policyKey(type, name)).ifPresentOrElse(
                    record -> details.add(AossStore.view(record)),
                    () -> errors.addObject().put("type", type).put("name", name).put("errorCode", "ResourceNotFound")
                            .put("errorMessage", "Policy with name " + name + " and type " + type + " is not found"));
        }
        return out;
    }

    public ObjectNode batchGetEffectiveLifecyclePolicy(JsonNode in, String region) {
        ObjectNode out = empty();
        ArrayNode details = out.putArray("effectiveLifecyclePolicyDetails");
        ArrayNode errors = out.putArray("effectiveLifecyclePolicyErrorDetails");
        for (JsonNode identifier : in.path("resourceIdentifiers")) {
            String type = identifier.path("type").asText();
            String resource = identifier.path("resource").asText();
            ObjectNode best = null;
            JsonNode bestRule = null;
            for (ObjectNode record : store.list(region, AossStore.LIFECYCLE_POLICY)) {
                JsonNode rule = AossPolicies.retention(record.path("policy"), resource);
                if (rule != null && type.equals(record.path("type").asText())) {
                    best = record;
                    bestRule = rule;
                }
            }
            if (best == null) {
                errors.addObject().put("type", type).put("resource", resource).put("errorCode", "ResourceNotFound")
                        .put("errorMessage", "No lifecycle policy applies to " + resource + ".");
                continue;
            }
            ObjectNode detail = details.addObject();
            detail.put("type", type);
            detail.put("resource", resource);
            detail.put("policyName", best.path("name").asText());
            detail.put("resourceType", "index");
            if (bestRule.path("NoMinIndexRetention").asBoolean(false)) {
                detail.put("noMinRetentionPeriod", true);
            } else {
                detail.put("retentionPeriod", bestRule.path("MinIndexRetention").asText());
            }
        }
        return out;
    }

    public ObjectNode getPoliciesStats(JsonNode in, String region) {
        long encryption = 0;
        long network = 0;
        for (ObjectNode record : store.list(region, AossStore.SECURITY_POLICY)) {
            if ("encryption".equals(record.path("type").asText())) {
                encryption++;
            } else {
                network++;
            }
        }
        long data = store.list(region, AossStore.ACCESS_POLICY).size();
        long saml = store.list(region, AossStore.SECURITY_CONFIG).stream().filter(config ->
                "saml".equals(config.path("type").asText())).count();
        long retention = store.list(region, AossStore.LIFECYCLE_POLICY).size();
        ObjectNode out = empty();
        out.putObject("AccessPolicyStats").put("DataPolicyCount", data);
        out.putObject("SecurityPolicyStats").put("EncryptionPolicyCount", encryption).put("NetworkPolicyCount",
                network);
        out.putObject("SecurityConfigStats").put("SamlConfigCount", saml);
        out.putObject("LifecyclePolicyStats").put("RetentionPolicyCount", retention);
        out.put("TotalPolicyCount", encryption + network + data + saml + retention);
        return out;
    }

    // ================================================================ security configurations

    private ObjectNode config(String region, String id) {
        return store.get(region, AossStore.SECURITY_CONFIG, id.replace('/', '|')).orElseThrow(() ->
                AossStore.notFound("Security config with id " + id + " is not found"));
    }

    public ObjectNode createSecurityConfig(JsonNode in, String region) {
        String type = required(in, "type");
        String name = required(in, "name");
        return idempotent(region, "CreateSecurityConfig", in, () -> {
            String account = store.account();
            String id = type + "/" + account + "/" + name;
            if (store.get(region, AossStore.SECURITY_CONFIG, id.replace('/', '|')).isPresent()) {
                throw AossStore.conflict("Security config with name " + name + " and type " + type
                        + " already exists");
            }
            ObjectNode record = store.record();
            long now = AossStore.now();
            record.put("id", id);
            record.put("type", type);
            record.put("configVersion", AossStore.version());
            if (in.hasNonNull("description")) {
                record.put("description", in.get("description").asText());
            }
            switch (type) {
                case "saml" -> {
                    if (!in.path("samlOptions").isObject()) {
                        throw AossStore.validation("samlOptions is required for saml configs.");
                    }
                    ObjectNode saml = in.get("samlOptions").deepCopy();
                    if (!saml.has("sessionTimeout")) {
                        saml.put("sessionTimeout", 60);
                    }
                    record.set("samlOptions", saml);
                }
                case "iamidentitycenter" -> {
                    JsonNode options = in.path("iamIdentityCenterOptions");
                    String instance = required(options, "instanceArn");
                    ObjectNode center = record.putObject("iamIdentityCenterOptions");
                    center.put("instanceArn", instance);
                    center.put("applicationArn", instance.replace(":instance/", ":application/") + "/apl-"
                            + AossStore.random(16, LOWER));
                    center.put("applicationName", "OpenSearchServerless-" + name);
                    center.put("applicationDescription", "OpenSearch Serverless security config " + name);
                    center.put("userAttribute", options.path("userAttribute").asText("UserId"));
                    center.put("groupAttribute", options.path("groupAttribute").asText("GroupId"));
                }
                default -> record.set("iamFederationOptions", in.path("iamFederationOptions").isObject()
                        ? in.get("iamFederationOptions").deepCopy() : empty());
            }
            record.put("createdDate", now);
            record.put("lastModifiedDate", now);
            store.put(region, AossStore.SECURITY_CONFIG, id.replace('/', '|'), record);
            ObjectNode out = empty();
            out.set("securityConfigDetail", AossStore.view(record));
            return out;
        });
    }

    public ObjectNode getSecurityConfig(JsonNode in, String region) {
        ObjectNode out = empty();
        out.set("securityConfigDetail", AossStore.view(config(region, required(in, "id"))));
        return out;
    }

    public ObjectNode updateSecurityConfig(JsonNode in, String region) {
        ObjectNode record = config(region, required(in, "id"));
        if (!required(in, "configVersion").equals(record.path("configVersion").asText())) {
            throw AossStore.conflict("Config version specified in the request refers to an older version and config "
                    + "has since changed");
        }
        ObjectNode before = record.deepCopy();
        if (in.has("description")) {
            record.put("description", in.path("description").asText(""));
        }
        if (in.path("samlOptions").isObject()) {
            ObjectNode saml = in.get("samlOptions").deepCopy();
            if (!saml.has("sessionTimeout")) {
                saml.put("sessionTimeout", 60);
            }
            record.set("samlOptions", saml);
        }
        if (in.path("iamIdentityCenterOptionsUpdates").isObject()) {
            ((ObjectNode) record.path("iamIdentityCenterOptions")).setAll((ObjectNode) in.get(
                    "iamIdentityCenterOptionsUpdates").deepCopy());
        }
        if (in.path("iamFederationOptions").isObject()) {
            record.set("iamFederationOptions", in.get("iamFederationOptions").deepCopy());
        }
        if (!before.equals(record)) {
            record.put("configVersion", AossStore.version());
            record.put("lastModifiedDate", AossStore.now());
            store.put(region, AossStore.SECURITY_CONFIG, record.path("id").asText().replace('/', '|'), record);
        }
        ObjectNode out = empty();
        out.set("securityConfigDetail", AossStore.view(record));
        return out;
    }

    public ObjectNode deleteSecurityConfig(JsonNode in, String region) {
        ObjectNode record = config(region, required(in, "id"));
        store.delete(region, AossStore.SECURITY_CONFIG, record.path("id").asText().replace('/', '|'));
        return empty();
    }

    public ObjectNode listSecurityConfigs(JsonNode in, String region) {
        String type = required(in, "type");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, AossStore.SECURITY_CONFIG)) {
            if (type.equals(record.path("type").asText())) {
                ObjectNode summary = empty();
                for (String field : List.of("id", "type", "configVersion", "description", "createdDate",
                        "lastModifiedDate")) {
                    if (record.has(field)) {
                        summary.set(field, record.get(field));
                    }
                }
                items.add(summary);
            }
        }
        return page(items, in, "securityConfigSummaries");
    }

    // ================================================================ account settings

    private ObjectNode settings(String region) {
        return store.get(region, AossStore.SETTINGS, ACCOUNT).orElseGet(() -> {
            ObjectNode record = store.record();
            record.putObject("capacityLimits").put("maxIndexingCapacityInOCU", 10).put("maxSearchCapacityInOCU", 10);
            return record;
        });
    }

    public ObjectNode getAccountSettings(JsonNode in, String region) {
        ObjectNode out = empty();
        out.set("accountSettingsDetail", AossStore.view(settings(region)));
        return out;
    }

    public ObjectNode updateAccountSettings(JsonNode in, String region) {
        ObjectNode record = settings(region);
        JsonNode limits = in.path("capacityLimits");
        for (String field : List.of("maxIndexingCapacityInOCU", "maxSearchCapacityInOCU")) {
            if (limits.hasNonNull(field)) {
                int value = limits.get(field).asInt();
                if (value < 2 || value > 1700) {
                    throw AossStore.validation(field + " must be from 2 to 1700.");
                }
                ((ObjectNode) record.path("capacityLimits")).put(field, value);
            }
        }
        store.put(region, AossStore.SETTINGS, ACCOUNT, record);
        ObjectNode out = empty();
        out.set("accountSettingsDetail", AossStore.view(record));
        return out;
    }

    // ================================================================ collection groups

    private ObjectNode group(String region, String id) {
        return store.get(region, AossStore.GROUP, id).orElseThrow(() ->
                AossStore.notFound("Collection group " + id + " is not found"));
    }

    private ObjectNode groupByName(String region, String name) {
        return store.list(region, AossStore.GROUP).stream().filter(group -> name.equals(group.path("name").asText()))
                .findFirst().orElse(null);
    }

    public ObjectNode createCollectionGroup(JsonNode in, String region) {
        String name = required(in, "name");
        return idempotent(region, "CreateCollectionGroup", in, () -> {
            if (groupByName(region, name) != null) {
                throw AossStore.conflict("Collection group with name " + name + " already exists");
            }
            String account = store.account();
            String id = AossStore.random(20, LOWER);
            ObjectNode record = store.record();
            record.put("id", id);
            record.put("arn", arn(region, account, "collectiongroup/" + id));
            record.put("name", name);
            record.put("standbyReplicas", required(in, "standbyReplicas"));
            if (in.hasNonNull("description")) {
                record.put("description", in.get("description").asText());
            }
            record.put("createdDate", AossStore.now());
            record.put("lastModifiedDate", AossStore.now());
            record.set("capacityLimits", in.path("capacityLimits").isObject() ? in.get("capacityLimits").deepCopy()
                    : empty());
            record.put("generation", in.path("generation").asText("CLASSIC"));
            AossStore.addTags(record, in.path("tags"));
            store.put(region, AossStore.GROUP, id, record);
            ObjectNode detail = AossStore.view(record);
            detail.remove("lastModifiedDate");
            detail.set("tags", AossStore.tagList(record, mapper));
            ObjectNode out = empty();
            out.set("createCollectionGroupDetail", detail);
            return out;
        });
    }

    private ObjectNode groupDetail(String region, ObjectNode record) {
        ObjectNode detail = AossStore.view(record);
        detail.remove("lastModifiedDate");
        detail.set("tags", AossStore.tagList(record, mapper));
        long members = store.list(region, AossStore.COLLECTION).stream().filter(collection ->
                record.path("name").asText().equals(collection.path("collectionGroupName").asText())).count();
        detail.put("numberOfCollections", members);
        ObjectNode capacity = detail.putObject("currentCapacity");
        capacity.putObject("search").put("currentOCU", members > 0 ? 1.0 : 0.0);
        capacity.putObject("indexing").put("currentOCU", members > 0 ? 1.0 : 0.0);
        return detail;
    }

    public ObjectNode batchGetCollectionGroup(JsonNode in, String region) {
        ObjectNode out = empty();
        ArrayNode details = out.putArray("collectionGroupDetails");
        ArrayNode errors = out.putArray("collectionGroupErrorDetails");
        for (JsonNode id : in.path("ids")) {
            store.get(region, AossStore.GROUP, id.asText()).ifPresentOrElse(group -> details.add(groupDetail(region,
                    group)), () -> errors.addObject().put("id", id.asText()).put("errorCode", "NOT_FOUND")
                    .put("errorMessage", "Collection group " + id.asText() + " is not found"));
        }
        for (JsonNode name : in.path("names")) {
            ObjectNode group = groupByName(region, name.asText());
            if (group == null) {
                errors.addObject().put("name", name.asText()).put("errorCode", "NOT_FOUND")
                        .put("errorMessage", "Collection group " + name.asText() + " is not found");
            } else {
                details.add(groupDetail(region, group));
            }
        }
        return out;
    }

    public ObjectNode listCollectionGroups(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, AossStore.GROUP)) {
            ObjectNode summary = empty();
            for (String field : List.of("id", "arn", "name", "createdDate", "capacityLimits")) {
                summary.set(field, record.get(field));
            }
            summary.put("numberOfCollections", groupDetail(region, record).path("numberOfCollections").asInt());
            items.add(summary);
        }
        return page(items, in, "collectionGroupSummaries");
    }

    public ObjectNode updateCollectionGroup(JsonNode in, String region) {
        ObjectNode record = group(region, required(in, "id"));
        if (in.has("description")) {
            record.put("description", in.path("description").asText(""));
        }
        if (in.path("capacityLimits").isObject()) {
            record.set("capacityLimits", in.get("capacityLimits").deepCopy());
        }
        record.put("lastModifiedDate", AossStore.now());
        store.put(region, AossStore.GROUP, record.path("id").asText(), record);
        ObjectNode detail = AossStore.view(record);
        detail.remove(List.of("standbyReplicas"));
        ObjectNode out = empty();
        out.set("updateCollectionGroupDetail", detail);
        return out;
    }

    public ObjectNode deleteCollectionGroup(JsonNode in, String region) {
        ObjectNode record = group(region, required(in, "id"));
        if (groupDetail(region, record).path("numberOfCollections").asInt() > 0) {
            throw AossStore.conflict("Collection group " + record.path("name").asText() + " still has collections");
        }
        store.delete(region, AossStore.GROUP, record.path("id").asText());
        return empty();
    }

    // ================================================================ collections

    private ObjectNode collection(String region, String id) {
        return store.get(region, AossStore.COLLECTION, id).orElseThrow(() ->
                AossStore.notFound("Collection " + id + " is not found"));
    }

    private ObjectNode collectionByName(String region, String name) {
        return store.list(region, AossStore.COLLECTION).stream().filter(collection ->
                name.equals(collection.path("name").asText())).findFirst().orElse(null);
    }

    /** Where a collection answers: its own host when Floci answers wildcard names, otherwise a path. */
    private String endpoint(String region, String id) {
        String base = store.endpoint();
        URI uri = URI.create(base);
        boolean local = List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
        if (local) {
            return uri.getScheme() + "://" + id + "." + region + ".aoss." + AossDataPlane.DNS_SUFFIX
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        }
        return base + AossDataPlane.PREFIX + "/" + region + "/" + id;
    }

    public ObjectNode createCollection(JsonNode in, String region) {
        String name = required(in, "name");
        return idempotent(region, "CreateCollection", in, () -> {
            if (name.length() < 3 || name.length() > 32) {
                throw AossStore.validation("Collection names are 3-32 characters.");
            }
            if (collectionByName(region, name) != null) {
                throw AossStore.conflict("Collection with name " + name + " already exists");
            }
            JsonNode encryptionConfig = in.path("encryptionConfig");
            String kms = encryptionConfig.path("kmsKeyArn").asText(null);
            if (!encryptionConfig.isObject() && store.list(region, AossStore.SECURITY_POLICY).stream().noneMatch(
                    policy -> "encryption".equals(policy.path("type").asText())
                            && AossPolicies.encrypts(policy.path("policy"), name))) {
                throw AossStore.validation("No matching security policy of encryption type found for collection name: "
                        + name + ". Please create security policy of encryption type for this collection.");
            }
            for (ObjectNode policy : store.list(region, AossStore.SECURITY_POLICY)) {
                if (kms == null && "encryption".equals(policy.path("type").asText())
                        && AossPolicies.encrypts(policy.path("policy"), name)) {
                    kms = policy.path("policy").path("KmsARN").asText(null);
                }
            }
            String groupName = in.path("collectionGroupName").asText(null);
            if (groupName != null && groupByName(region, groupName) == null) {
                throw AossStore.validation("Collection group " + groupName + " doesn't exist.");
            }
            String account = store.account();
            String id = AossStore.random(20, LOWER);
            ObjectNode record = store.record();
            long now = AossStore.now();
            record.put("id", id);
            record.put("name", name);
            record.put("status", "CREATING");
            record.put("type", in.path("type").asText("SEARCH"));
            if (in.hasNonNull("description")) {
                record.put("description", in.get("description").asText());
            }
            record.put("arn", arn(region, account, "collection/" + id));
            record.put("kmsKeyArn", kms == null ? "auto" : kms);
            record.put("standbyReplicas", in.path("standbyReplicas").asText("ENABLED"));
            record.put("deletionProtection", in.path("deletionProtection").asText("DISABLED"));
            if (in.path("vectorOptions").isObject()) {
                record.set("vectorOptions", in.get("vectorOptions").deepCopy());
            }
            record.put("createdDate", now);
            record.put("lastModifiedDate", now);
            record.put("collectionEndpoint", endpoint(region, id));
            record.put("dashboardEndpoint", endpoint(region, id) + "/_dashboards");
            if (groupName != null) {
                record.put("collectionGroupName", groupName);
            }
            AossStore.addTags(record, in.path("tags"));
            store.put(region, AossStore.COLLECTION, id, record);
            later(account, region, () -> provision(account, region, id));
            ObjectNode detail = AossStore.view(record);
            detail.remove(List.of("collectionEndpoint", "dashboardEndpoint"));
            ObjectNode out = empty();
            out.set("createCollectionDetail", detail);
            return out;
        });
    }

    private void provision(String account, String region, String id) {
        String failure = null;
        if (engine.enabled()) {
            try {
                engine.start(account, region, id);
            } catch (RuntimeException e) {
                failure = e.getMessage();
            }
        }
        ObjectNode record = store.getForAccount(account, region, AossStore.COLLECTION, id).orElse(null);
        if (record == null || !"CREATING".equals(record.path("status").asText())) {
            return;
        }
        if (failure == null) {
            record.put("status", "ACTIVE");
        } else {
            record.put("status", "FAILED");
            record.put("failureCode", "INTERNAL_ERROR");
            record.put("failureMessage", failure);
        }
        record.put("lastModifiedDate", AossStore.now());
        store.putForAccount(account, region, AossStore.COLLECTION, id, record);
    }

    public ObjectNode batchGetCollection(JsonNode in, String region) {
        boolean ids = in.path("ids").size() > 0;
        boolean names = in.path("names").size() > 0;
        if (ids == names) {
            throw AossStore.validation("Specify either ids or names.");
        }
        ObjectNode out = empty();
        ArrayNode details = out.putArray("collectionDetails");
        ArrayNode errors = out.putArray("collectionErrorDetails");
        for (JsonNode id : in.path("ids")) {
            store.get(region, AossStore.COLLECTION, id.asText()).ifPresentOrElse(collection -> details.add(
                    AossStore.view(collection)), () -> errors.addObject().put("id", id.asText())
                    .put("errorCode", "NOT_FOUND").put("errorMessage", "Collection " + id.asText()
                            + " is not found"));
        }
        for (JsonNode name : in.path("names")) {
            ObjectNode collection = collectionByName(region, name.asText());
            if (collection == null) {
                errors.addObject().put("name", name.asText()).put("errorCode", "NOT_FOUND")
                        .put("errorMessage", "Collection " + name.asText() + " is not found");
            } else {
                details.add(AossStore.view(collection));
            }
        }
        return out;
    }

    public ObjectNode listCollections(JsonNode in, String region) {
        JsonNode filters = in.path("collectionFilters");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, AossStore.COLLECTION)) {
            boolean matches = true;
            for (String field : List.of("name", "status", "collectionGroupName")) {
                matches &= !filters.hasNonNull(field) || filters.get(field).asText().equals(record.path(field)
                        .asText());
            }
            if (matches) {
                ObjectNode summary = empty();
                for (String field : List.of("id", "name", "status", "arn", "kmsKeyArn", "collectionGroupName")) {
                    if (record.has(field)) {
                        summary.set(field, record.get(field));
                    }
                }
                items.add(summary);
            }
        }
        return page(items, in, "collectionSummaries");
    }

    public ObjectNode updateCollection(JsonNode in, String region) {
        ObjectNode record = collection(region, required(in, "id"));
        if (!"ACTIVE".equals(record.path("status").asText())) {
            throw AossStore.conflict("Collection " + record.path("name").asText() + " is "
                    + record.path("status").asText());
        }
        if (in.has("description")) {
            record.put("description", in.path("description").asText(""));
        }
        if (in.path("vectorOptions").isObject()) {
            record.set("vectorOptions", in.get("vectorOptions").deepCopy());
        }
        if (in.hasNonNull("deletionProtection")) {
            record.put("deletionProtection", in.get("deletionProtection").asText());
        }
        if (in.path("encryptionConfig").hasNonNull("kmsKeyArn")) {
            record.put("kmsKeyArn", in.path("encryptionConfig").path("kmsKeyArn").asText());
        }
        record.put("lastModifiedDate", AossStore.now());
        store.put(region, AossStore.COLLECTION, record.path("id").asText(), record);
        ObjectNode detail = AossStore.view(record);
        detail.remove(List.of("collectionEndpoint", "dashboardEndpoint", "standbyReplicas", "collectionGroupName"));
        ObjectNode out = empty();
        out.set("updateCollectionDetail", detail);
        return out;
    }

    public ObjectNode deleteCollection(JsonNode in, String region) {
        ObjectNode record = collection(region, required(in, "id"));
        if ("ENABLED".equals(record.path("deletionProtection").asText())) {
            throw AossStore.validation("Collection " + record.path("name").asText() + " has deletion protection "
                    + "enabled");
        }
        if ("CREATING".equals(record.path("status").asText()) || "DELETING".equals(record.path("status").asText())) {
            throw AossStore.conflict("Collection " + record.path("name").asText() + " is "
                    + record.path("status").asText());
        }
        String id = record.path("id").asText();
        record.put("status", "DELETING");
        store.put(region, AossStore.COLLECTION, id, record);
        String account = store.account();
        later(account, region, () -> {
            engine.remove(account, region, id);
            store.deleteForAccount(account, region, AossStore.COLLECTION, id);
            for (String kind : List.of(AossStore.INGESTION_JOB, AossStore.INGESTION_STREAM)) {
                for (ObjectNode item : store.listForAccount(account, region, kind)) {
                    if (id.equals(item.path("collectionId").asText())) {
                        store.deleteForAccount(account, region, kind, AossStore.meta(item).path("id").asText());
                    }
                }
            }
        });
        ObjectNode out = empty();
        out.putObject("deleteCollectionDetail").put("id", id).put("name", record.path("name").asText())
                .put("status", "DELETING").put("deletionProtection", record.path("deletionProtection").asText());
        return out;
    }

    // ================================================================ access control

    /** The principals a request is made as: the caller's ARNs, and the account root for root credentials. */
    List<String> principals() {
        Set<String> principals = new LinkedHashSet<>();
        String account = store.account();
        try {
            String key = requestContext.get().getAccessKeyId();
            iam.get().resolveCallerArns(key).ifPresentOrElse(caller -> {
                principals.add(caller.principalArn());
                principals.add(caller.callerArn());
            }, () -> principals.add(regionResolver.buildGlobalArn("iam", account, "root")));
        } catch (RuntimeException e) {
            principals.add(regionResolver.buildGlobalArn("iam", account, "root"));
        }
        principals.removeIf(Objects::isNull);
        return new ArrayList<>(principals);
    }

    /** Fails unless a network policy reaches the collection and a data access policy grants the permission. */
    void authorize(String region, ObjectNode collection, String type, String resource, String permission) {
        String name = collection.path("name").asText();
        boolean reachable = store.list(region, AossStore.SECURITY_POLICY).stream().anyMatch(policy ->
                "network".equals(policy.path("type").asText()) && AossPolicies.reaches(policy.path("policy"), name));
        if (!reachable) {
            throw new AwsException("AccessDeniedException", "No network policy allows access to collection " + name
                    + ".", 403);
        }
        List<String> principals = principals();
        boolean granted = store.list(region, AossStore.ACCESS_POLICY).stream().anyMatch(policy ->
                AossPolicies.grants(policy.path("policy"), principals, type, resource, permission));
        if (!granted) {
            throw new AwsException("AccessDeniedException", "User does not have permissions for the requested "
                    + "resource", 403);
        }
    }

    // ================================================================ indexes

    private AossEngine.Backend activeBackend(String region, ObjectNode collection) {
        if (!"ACTIVE".equals(collection.path("status").asText())) {
            throw AossStore.conflict("Collection " + collection.path("name").asText() + " is "
                    + collection.path("status").asText());
        }
        if (!engine.enabled()) {
            throw AossStore.validation("Floci runs no search engine (floci.services.opensearch-serverless.engine is "
                    + "none).");
        }
        AossEngine.Backend backend = engine.backend(store.account(), region, collection.path("id").asText());
        return backend != null ? backend : engine.start(store.account(), region, collection.path("id").asText());
    }

    private ObjectNode indexCall(JsonNode in, String region, String permission) {
        ObjectNode collection = collection(region, required(in, "id"));
        String index = required(in, "indexName");
        authorize(region, collection, "index", "index/" + collection.path("name").asText() + "/" + index,
                permission);
        return collection;
    }

    private AossEngine.Answer send(String region, ObjectNode collection, String method, String path, JsonNode body) {
        return engine.send(activeBackend(region, collection), method, path, "application/json",
                body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode json(AossEngine.Answer answer) {
        try {
            return mapper.readTree(answer.body());
        } catch (IOException e) {
            return empty();
        }
    }

    public ObjectNode createIndex(JsonNode in, String region) {
        ObjectNode collection = indexCall(in, region, "aoss:CreateIndex");
        String index = in.path("indexName").asText();
        AossEngine.Answer answer = send(region, collection, "PUT", "/" + index, in.path("indexSchema").isObject()
                ? in.get("indexSchema") : empty());
        if (answer.status() == 400 && new String(answer.body(), StandardCharsets.UTF_8).contains(
                "resource_already_exists_exception")) {
            throw AossStore.conflict("Index " + index + " already exists");
        }
        if (answer.status() >= 300) {
            throw AossStore.validation(json(answer).path("error").path("reason").asText("The index can't be "
                    + "created."));
        }
        return empty();
    }

    public ObjectNode getIndex(JsonNode in, String region) {
        ObjectNode collection = indexCall(in, region, "aoss:DescribeIndex");
        String index = in.path("indexName").asText();
        AossEngine.Answer answer = send(region, collection, "GET", "/" + index, null);
        if (answer.status() == 404) {
            throw AossStore.notFound("Index " + index + " is not found");
        }
        JsonNode described = json(answer).path(index);
        ObjectNode schema = empty();
        schema.set("mappings", described.path("mappings").deepCopy());
        ObjectNode settings = schema.putObject("settings");
        JsonNode indexSettings = described.path("settings").path("index");
        for (String field : List.of("knn", "knn.algo_param", "analysis")) {
            if (indexSettings.has(field)) {
                settings.putObject("index").set(field, indexSettings.get(field).deepCopy());
            }
        }
        ObjectNode out = empty();
        out.set("indexSchema", schema);
        return out;
    }

    public ObjectNode updateIndex(JsonNode in, String region) {
        ObjectNode collection = indexCall(in, region, "aoss:UpdateIndex");
        String index = in.path("indexName").asText();
        JsonNode schema = in.path("indexSchema");
        if (schema.path("mappings").isObject()) {
            AossEngine.Answer answer = send(region, collection, "PUT", "/" + index + "/_mapping",
                    schema.get("mappings"));
            if (answer.status() == 404) {
                throw AossStore.notFound("Index " + index + " is not found");
            }
            if (answer.status() >= 300) {
                throw AossStore.validation(json(answer).path("error").path("reason").asText("The mappings can't be "
                        + "updated."));
            }
        }
        if (schema.path("settings").isObject()) {
            AossEngine.Answer answer = send(region, collection, "PUT", "/" + index + "/_settings",
                    schema.get("settings"));
            if (answer.status() >= 300) {
                throw AossStore.validation(json(answer).path("error").path("reason").asText("The settings can't be "
                        + "updated."));
            }
        }
        return empty();
    }

    public ObjectNode deleteIndex(JsonNode in, String region) {
        ObjectNode collection = indexCall(in, region, "aoss:DeleteIndex");
        String index = in.path("indexName").asText();
        AossEngine.Answer answer = send(region, collection, "DELETE", "/" + index, null);
        if (answer.status() == 404) {
            throw AossStore.notFound("Index " + index + " is not found");
        }
        return empty();
    }

    // ================================================================ VPC endpoints

    private String vpcOf(String region, String vpcId, JsonNode subnets) {
        for (JsonNode subnetId : subnets) {
            Subnet subnet;
            try {
                subnet = ec2.get().requireSubnet(region, subnetId.asText());
            } catch (AwsException e) {
                throw AossStore.validation("The subnet " + subnetId.asText() + " doesn't exist.");
            }
            if (!vpcId.equals(subnet.getVpcId())) {
                throw AossStore.validation("The subnet " + subnetId.asText() + " isn't in " + vpcId + ".");
            }
        }
        return vpcId;
    }

    private ObjectNode vpcEndpoint(String region, String id) {
        return store.get(region, AossStore.VPC_ENDPOINT, id).orElseThrow(() ->
                AossStore.notFound("VPC endpoint " + id + " is not found"));
    }

    public ObjectNode createVpcEndpoint(JsonNode in, String region) {
        String name = required(in, "name");
        return idempotent(region, "CreateVpcEndpoint", in, () -> {
            if (store.list(region, AossStore.VPC_ENDPOINT).stream().anyMatch(endpoint -> name.equals(endpoint
                    .path("name").asText()))) {
                throw AossStore.conflict("VPC endpoint with name " + name + " already exists");
            }
            String vpcId = vpcOf(region, required(in, "vpcId"), in.path("subnetIds"));
            if (in.path("securityGroupIds").size() > 0) {
                List<String> groups = new ArrayList<>();
                in.path("securityGroupIds").forEach(group -> groups.add(group.asText()));
                try {
                    ec2.get().requireSecurityGroups(region, groups);
                } catch (AwsException e) {
                    throw AossStore.validation(e.getMessage());
                }
            }
            String id = "vpce-" + AossStore.random(17, "0123456789abcdef");
            ObjectNode record = store.record();
            record.put("id", id);
            record.put("name", name);
            record.put("vpcId", vpcId);
            record.set("subnetIds", in.path("subnetIds").deepCopy());
            record.set("securityGroupIds", in.path("securityGroupIds").isArray() ? in.get("securityGroupIds")
                    .deepCopy() : mapper.createArrayNode());
            record.put("status", "PENDING");
            record.put("createdDate", AossStore.now());
            store.put(region, AossStore.VPC_ENDPOINT, id, record);
            String account = store.account();
            later(account, region, () -> store.getForAccount(account, region, AossStore.VPC_ENDPOINT, id)
                    .ifPresent(current -> {
                        if ("PENDING".equals(current.path("status").asText())) {
                            current.put("status", "ACTIVE");
                            store.putForAccount(account, region, AossStore.VPC_ENDPOINT, id, current);
                        }
                    }));
            ObjectNode out = empty();
            out.putObject("createVpcEndpointDetail").put("id", id).put("name", name).put("status", "PENDING");
            return out;
        });
    }

    public ObjectNode batchGetVpcEndpoint(JsonNode in, String region) {
        ObjectNode out = empty();
        ArrayNode details = out.putArray("vpcEndpointDetails");
        ArrayNode errors = out.putArray("vpcEndpointErrorDetails");
        for (JsonNode id : in.path("ids")) {
            store.get(region, AossStore.VPC_ENDPOINT, id.asText()).ifPresentOrElse(endpoint -> details.add(
                    AossStore.view(endpoint)), () -> errors.addObject().put("id", id.asText())
                    .put("errorCode", "NOT_FOUND").put("errorMessage", "VPC endpoint " + id.asText()
                            + " is not found"));
        }
        return out;
    }

    public ObjectNode listVpcEndpoints(JsonNode in, String region) {
        String status = in.path("vpcEndpointFilters").path("status").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, AossStore.VPC_ENDPOINT)) {
            if (status == null || status.equals(record.path("status").asText())) {
                items.add(empty().put("id", record.path("id").asText()).put("name", record.path("name").asText())
                        .put("status", record.path("status").asText()));
            }
        }
        return page(items, in, "vpcEndpointSummaries");
    }

    public ObjectNode updateVpcEndpoint(JsonNode in, String region) {
        ObjectNode record = vpcEndpoint(region, required(in, "id"));
        vpcOf(region, record.path("vpcId").asText(), in.path("addSubnetIds"));
        for (String field : List.of("subnetIds", "securityGroupIds")) {
            String suffix = field.equals("subnetIds") ? "SubnetIds" : "SecurityGroupIds";
            Set<String> values = new LinkedHashSet<>();
            record.path(field).forEach(value -> values.add(value.asText()));
            in.path("add" + suffix).forEach(value -> values.add(value.asText()));
            in.path("remove" + suffix).forEach(value -> values.remove(value.asText()));
            ArrayNode list = record.putArray(field);
            values.forEach(list::add);
        }
        if (record.path("subnetIds").isEmpty()) {
            throw AossStore.validation("A VPC endpoint needs at least one subnet.");
        }
        record.put("lastModifiedDate", AossStore.now());
        store.put(region, AossStore.VPC_ENDPOINT, record.path("id").asText(), record);
        ObjectNode detail = empty();
        for (String field : List.of("id", "name", "status", "subnetIds", "securityGroupIds", "lastModifiedDate")) {
            detail.set(field, record.get(field));
        }
        ObjectNode out = empty();
        out.set("UpdateVpcEndpointDetail", detail);
        return out;
    }

    public ObjectNode deleteVpcEndpoint(JsonNode in, String region) {
        ObjectNode record = vpcEndpoint(region, required(in, "id"));
        record.put("status", "DELETING");
        String id = record.path("id").asText();
        store.put(region, AossStore.VPC_ENDPOINT, id, record);
        String account = store.account();
        later(account, region, () -> store.deleteForAccount(account, region, AossStore.VPC_ENDPOINT, id));
        ObjectNode out = empty();
        out.putObject("deleteVpcEndpointDetail").put("id", id).put("name", record.path("name").asText())
                .put("status", "DELETING");
        return out;
    }

    // ================================================================ ingestion

    private ObjectNode ingestion(String region, String kind, String collectionId, String id) {
        return store.get(region, kind, id).filter(item -> collectionId.equals(item.path("collectionId").asText()))
                .orElseThrow(() -> AossStore.notFound("Ingestion " + id + " is not found"));
    }

    private static String bucketName(String bucket) {
        return AwsArnUtils.isArn(bucket) ? AwsArnUtils.parse(bucket).resource() : bucket;
    }

    /** Indexes the documents of an S3 object: a JSON array, a JSON object, or newline-delimited JSON. */
    private int[] ingestObject(String region, ObjectNode collection, String bucket, String key, String index,
                               String failureIndex) {
        byte[] data = s3.get().getObject(bucket, key).getData();
        String text = new String(data == null ? new byte[0] : data, StandardCharsets.UTF_8).trim();
        List<JsonNode> documents = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            JsonNode parsed = mapper.readTree(text);
            if (parsed != null && parsed.isArray()) {
                parsed.forEach(documents::add);
            } else if (parsed != null && parsed.isObject() && !text.contains("\n")) {
                documents.add(parsed);
            } else {
                throw new IOException("not a single document");
            }
        } catch (IOException e) {
            for (String line : text.split("\\r?\\n")) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    documents.add(mapper.readTree(line));
                } catch (IOException bad) {
                    failures.add(line);
                }
            }
        }
        StringBuilder bulk = new StringBuilder();
        for (JsonNode document : documents) {
            bulk.append("{\"index\":{\"_index\":\"").append(index).append("\"}}\n").append(document).append('\n');
        }
        int indexed = 0;
        if (!documents.isEmpty()) {
            AossEngine.Answer answer = engine.send(activeBackend(region, collection), "POST", "/_bulk?refresh=true",
                    "application/x-ndjson", bulk.toString().getBytes(StandardCharsets.UTF_8));
            JsonNode result = json(answer);
            for (JsonNode item : result.path("items")) {
                if (item.path("index").path("status").asInt(500) < 300) {
                    indexed++;
                } else {
                    failures.add(item.path("index").path("error").toString());
                }
            }
        }
        if (!failures.isEmpty() && failureIndex != null) {
            StringBuilder failed = new StringBuilder();
            for (String failure : failures) {
                failed.append("{\"index\":{\"_index\":\"").append(failureIndex).append("\"}}\n")
                        .append(empty().put("source", "s3://" + bucket + "/" + key).put("document", failure))
                        .append('\n');
            }
            engine.send(activeBackend(region, collection), "POST", "/_bulk?refresh=true", "application/x-ndjson",
                    failed.toString().getBytes(StandardCharsets.UTF_8));
        }
        return new int[] {indexed, failures.size()};
    }

    private static ObjectNode stats(ObjectNode item) {
        if (!item.path("statistics").isObject()) {
            item.putObject("statistics");
        }
        ObjectNode statistics = (ObjectNode) item.get("statistics");
        if (!statistics.path("s3").isObject()) {
            statistics.putObject("s3");
        }
        return (ObjectNode) statistics.get("s3");
    }

    private void count(ObjectNode item, boolean ok) {
        ObjectNode stats = stats(item);
        stats.put(ok ? "objectsIngested" : "objectsFailed", stats.path(ok ? "objectsIngested" : "objectsFailed")
                .asLong() + 1);
    }

    private ObjectNode ingestionRecord(JsonNode in, String region, String kind, String nameField) {
        String collectionId = required(in, "collectionId");
        ObjectNode collection = collection(region, collectionId);
        authorize(region, collection, "index", "index/" + collection.path("name").asText() + "/"
                + required(in, "indexName"), "aoss:WriteDocument");
        String account = store.account();
        String id = AossStore.random(20, LOWER);
        ObjectNode record = store.record();
        record.put(kind.equals(AossStore.INGESTION_JOB) ? "ingestionJobId" : "ingestionStreamId", id);
        record.put("arn", arn(region, account, "collection/" + collectionId + "/"
                + (kind.equals(AossStore.INGESTION_JOB) ? "ingestion-job/" : "ingestion-stream/") + id));
        record.put("collectionId", collectionId);
        record.put(nameField, required(in, nameField));
        record.put("indexName", in.path("indexName").asText());
        if (in.hasNonNull("failureIndexName")) {
            record.put("failureIndexName", in.get("failureIndexName").asText());
        }
        record.set("source", in.path("source").deepCopy());
        record.put("status", "CREATING");
        record.put("state", "STARTING");
        record.putObject("statistics").putObject("s3").put("objectsDiscovered", 0).put("objectsIngested", 0)
                .put("objectsFailed", 0);
        record.put("revision", 1);
        double now = AossStore.now() / 1000.0;
        record.put("createdAt", now);
        record.put("updatedAt", now);
        AossStore.addTags(record, in.path("tags"));
        AossStore.meta(record).put("id", id);
        store.put(region, kind, id, record);
        return record;
    }

    public ObjectNode createIngestionJob(JsonNode in, String region) {
        ObjectNode record = ingestionRecord(in, region, AossStore.INGESTION_JOB, "ingestionJobName");
        String account = store.account();
        String id = record.path("ingestionJobId").asText();
        later(account, region, () -> runJob(account, region, id));
        ObjectNode out = empty();
        out.set("ingestionJobDetail", AossStore.view(record));
        return out;
    }

    private void runJob(String account, String region, String id) {
        ObjectNode job = store.getForAccount(account, region, AossStore.INGESTION_JOB, id).orElse(null);
        if (job == null || !"CREATING".equals(job.path("status").asText())) {
            return;
        }
        job.put("status", "ACTIVE");
        job.put("state", "SCANNING");
        store.putForAccount(account, region, AossStore.INGESTION_JOB, id, job);
        JsonNode source = job.path("source").path("s3");
        String bucket = bucketName(source.path("bucket").asText());
        try {
            ObjectNode collection = collection(region, job.path("collectionId").asText());
            List<S3Object> objects = s3.get().listObjects(bucket, source.path("keyPrefix").asText(""), null, 10000);
            ((ObjectNode) job.path("statistics").path("s3")).put("objectsDiscovered", objects.size());
            job.put("state", "INGESTION_IN_PROGRESS");
            for (S3Object object : objects) {
                ObjectNode current = store.getForAccount(account, region, AossStore.INGESTION_JOB, id).orElse(job);
                if ("CANCELLING".equals(current.path("status").asText())) {
                    job.put("status", "CANCELLED");
                    job.put("state", "STOPPED");
                    store.putForAccount(account, region, AossStore.INGESTION_JOB, id, job);
                    return;
                }
                try {
                    int[] result = ingestObject(region, collection, bucket, object.getKey(), job.path("indexName")
                            .asText(), job.path("failureIndexName").asText(null));
                    count(job, result[1] == 0);
                } catch (RuntimeException e) {
                    count(job, false);
                }
            }
            job.put("status", "COMPLETED");
            job.put("state", "INGESTION_COMPLETE");
        } catch (AwsException e) {
            job.put("status", "FAILED");
            job.put("state", "FAILED");
            job.putArray("failureReasons").addObject().put("code", "SOURCE_RESOURCE_NOT_FOUND")
                    .put("message", e.getMessage());
        } catch (RuntimeException e) {
            job.put("status", "FAILED");
            job.put("state", "FAILED");
            job.putArray("failureReasons").addObject().put("code", "INTERNAL_SERVER_ERROR")
                    .put("message", e.getMessage());
        }
        job.put("updatedAt", AossStore.now() / 1000.0);
        job.put("revision", job.path("revision").asLong() + 1);
        store.putForAccount(account, region, AossStore.INGESTION_JOB, id, job);
    }

    public ObjectNode getIngestionJob(JsonNode in, String region) {
        ObjectNode out = empty();
        ObjectNode job = ingestion(region, AossStore.INGESTION_JOB, required(in, "collectionId"),
                required(in, "ingestionJobId"));
        ObjectNode view = AossStore.view(job);
        view.set("tags", AossStore.tagList(job, mapper));
        out.set("ingestionJobDetail", view);
        return out;
    }

    public ObjectNode listIngestionJobs(JsonNode in, String region) {
        String collectionId = required(in, "collectionId");
        collection(region, collectionId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode job : store.list(region, AossStore.INGESTION_JOB)) {
            if (collectionId.equals(job.path("collectionId").asText())) {
                ObjectNode summary = empty();
                for (String field : List.of("ingestionJobId", "arn", "ingestionJobName", "indexName", "status",
                        "createdAt", "updatedAt")) {
                    summary.set(field, job.get(field));
                }
                items.add(summary);
            }
        }
        return page(items, in, "ingestionJobSummaries");
    }

    public ObjectNode cancelIngestionJob(JsonNode in, String region) {
        ObjectNode job = ingestion(region, AossStore.INGESTION_JOB, required(in, "collectionId"),
                required(in, "ingestionJobId"));
        String status = job.path("status").asText();
        if (!Set.of("CREATING", "ACTIVE").contains(status)) {
            throw AossStore.conflict("Ingestion job is " + status);
        }
        job.put("status", "CREATING".equals(status) ? "CANCELLED" : "CANCELLING");
        if ("CANCELLED".equals(job.path("status").asText())) {
            job.put("state", "STOPPED");
        }
        store.put(region, AossStore.INGESTION_JOB, job.path("ingestionJobId").asText(), job);
        ObjectNode out = empty();
        for (String field : List.of("ingestionJobId", "collectionId", "ingestionJobName", "status")) {
            out.set(field, job.get(field));
        }
        return out;
    }

    public ObjectNode createIngestionStream(JsonNode in, String region) {
        ObjectNode record = ingestionRecord(in, region, AossStore.INGESTION_STREAM, "ingestionStreamName");
        record.put("roleArn", required(in, "roleArn"));
        String id = record.path("ingestionStreamId").asText();
        store.put(region, AossStore.INGESTION_STREAM, id, record);
        String account = store.account();
        later(account, region, () -> store.getForAccount(account, region, AossStore.INGESTION_STREAM, id)
                .ifPresent(current -> {
                    current.put("status", "ACTIVE");
                    current.put("state", "INGESTION_IN_PROGRESS");
                    store.putForAccount(account, region, AossStore.INGESTION_STREAM, id, current);
                }));
        ObjectNode out = empty();
        out.set("ingestionStreamDetail", AossStore.view(record));
        return out;
    }

    public ObjectNode getIngestionStream(JsonNode in, String region) {
        ObjectNode stream = ingestion(region, AossStore.INGESTION_STREAM, required(in, "collectionId"),
                required(in, "ingestionStreamId"));
        ObjectNode view = AossStore.view(stream);
        view.set("tags", AossStore.tagList(stream, mapper));
        ObjectNode out = empty();
        out.set("ingestionStreamDetail", view);
        return out;
    }

    public ObjectNode listIngestionStreams(JsonNode in, String region) {
        String collectionId = required(in, "collectionId");
        collection(region, collectionId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stream : store.list(region, AossStore.INGESTION_STREAM)) {
            if (collectionId.equals(stream.path("collectionId").asText())) {
                ObjectNode summary = empty();
                for (String field : List.of("ingestionStreamId", "arn", "ingestionStreamName", "indexName", "status",
                        "createdAt", "updatedAt")) {
                    summary.set(field, stream.get(field));
                }
                items.add(summary);
            }
        }
        return page(items, in, "ingestionStreamSummaries");
    }

    public ObjectNode deleteIngestionStream(JsonNode in, String region) {
        ObjectNode stream = ingestion(region, AossStore.INGESTION_STREAM, required(in, "collectionId"),
                required(in, "ingestionStreamId"));
        String id = stream.path("ingestionStreamId").asText();
        stream.put("status", "DELETING");
        store.put(region, AossStore.INGESTION_STREAM, id, stream);
        String account = store.account();
        later(account, region, () -> store.deleteForAccount(account, region, AossStore.INGESTION_STREAM, id));
        ObjectNode out = empty();
        out.put("ingestionStreamId", id);
        out.put("status", "DELETING");
        return out;
    }

    /** Indexes the objects each active ingestion stream's queue announces. */
    void pollStreams() {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(AossStore.INGESTION_STREAM)) {
            ObjectNode stream = entry.value();
            if (!"ACTIVE".equals(stream.path("status").asText())) {
                continue;
            }
            String region = AossStore.meta(stream).path("region").asText();
            try {
                RequestScopes.runAs(entry.accountId(), region, () -> drain(entry.accountId(), region, stream));
            } catch (RuntimeException e) {
                LOG.debugv("Ingestion stream {0} couldn't poll: {1}", stream.path("ingestionStreamId").asText(),
                        e.getMessage());
            }
        }
    }

    private void drain(String account, String region, ObjectNode stream) {
        String sqsArn = stream.path("source").path("s3").path("sqsArn").asText();
        AwsArnUtils.Arn queue = AwsArnUtils.parse(sqsArn);
        String url = sqs.get().getQueueUrl(queue.resource(), queue.region());
        Set<String> buckets = new LinkedHashSet<>();
        stream.path("source").path("s3").path("buckets").forEach(bucket -> buckets.add(bucketName(bucket
                .path("bucket").asText())));
        ObjectNode collection = collection(region, stream.path("collectionId").asText());
        String id = stream.path("ingestionStreamId").asText();
        for (Message message : sqs.get().receiveMessage(url, 10, 60, 0, queue.region())) {
            try {
                JsonNode notification = mapper.readTree(message.getBody());
                for (JsonNode record : notification.path("Records")) {
                    String bucket = record.path("s3").path("bucket").path("name").asText();
                    String key = URLDecoder.decode(record.path("s3").path("object").path("key").asText(),
                            StandardCharsets.UTF_8);
                    if (bucket.isEmpty() || (!buckets.isEmpty() && !buckets.contains(bucket))) {
                        continue;
                    }
                    ObjectNode current = store.getForAccount(account, region, AossStore.INGESTION_STREAM, id)
                            .orElse(stream);
                    ObjectNode stats = stats(current);
                    stats.put("objectsDiscovered", stats.path("objectsDiscovered").asLong() + 1);
                    try {
                        int[] result = ingestObject(region, collection, bucket, key, current.path("indexName")
                                .asText(), current.path("failureIndexName").asText(null));
                        count(current, result[1] == 0);
                    } catch (RuntimeException e) {
                        count(current, false);
                    }
                    current.put("updatedAt", AossStore.now() / 1000.0);
                    store.putForAccount(account, region, AossStore.INGESTION_STREAM, id, current);
                }
                sqs.get().deleteMessage(url, message.getReceiptHandle(), queue.region());
            } catch (IOException e) {
                LOG.debugv("An ingestion stream message isn't JSON: {0}", e.getMessage());
            }
        }
    }

    // ================================================================ tags

    private ObjectNode tagged(String region, String arn) {
        if (!AwsArnUtils.isArnFor(arn, AossStore.SERVICE)) {
            throw AossStore.validation("The ARN " + arn + " isn't an OpenSearch Serverless ARN.");
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String[] parts = resource.split("/");
        String kind = switch (parts[0]) {
            case "collection" -> parts.length > 2 ? (parts[2].equals("ingestion-job") ? AossStore.INGESTION_JOB
                    : AossStore.INGESTION_STREAM) : AossStore.COLLECTION;
            case "collectiongroup" -> AossStore.GROUP;
            default -> throw AossStore.notFound("Resource " + arn + " is not found");
        };
        String id = parts[parts.length - 1];
        return store.get(region, kind, id).map(record -> {
            AossStore.meta(record).put("kind", kind);
            return record;
        }).orElseThrow(() -> AossStore.notFound("Resource " + arn + " is not found"));
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        ObjectNode record = tagged(region, required(in, "resourceArn"));
        AossStore.addTags(record, in.path("tags"));
        String kind = AossStore.meta(record).path("kind").asText();
        AossStore.meta(record).remove("kind");
        store.put(region, kind, AossStore.meta(record).path("id").asText(), record);
        return empty();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        ObjectNode record = tagged(region, required(in, "resourceArn"));
        in.path("tagKeys").forEach(key -> AossStore.tags(record).remove(key.asText()));
        String kind = AossStore.meta(record).path("kind").asText();
        AossStore.meta(record).remove("kind");
        store.put(region, kind, AossStore.meta(record).path("id").asText(), record);
        return empty();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode out = empty();
        out.set("tags", AossStore.tagList(tagged(region, required(in, "resourceArn")), mapper));
        return out;
    }

    /** A collection by id in any account, for the data plane. */
    Optional<AccountAwareStorageBackend.AccountEntry<ObjectNode>> collectionAnywhere(String region,
                                                                                               String id) {
        return store.everywhere(AossStore.COLLECTION).stream().filter(entry -> id.equals(entry.value().path("id")
                .asText()) && region.equals(AossStore.meta(entry.value()).path("region").asText())).findFirst();
    }

    AossEngine engine() {
        return engine;
    }
}
