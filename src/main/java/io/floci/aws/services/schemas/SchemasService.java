package io.floci.aws.services.schemas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.eventbridge.EventBusObserver;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Amazon EventBridge schema registry: registries and their resource policies, versioned schemas (OpenAPI 3 or JSON
 * Schema Draft 4), the AWS-managed {@code aws.events} registry, search, export, schema inference, code bindings
 * and discoverers.
 *
 * <p>A started discoverer infers a schema from every event its bus accepts, into {@code discovered-schemas}, named
 * {@code source@DetailType}; a sample that changes the inferred schema makes a new version. Registering a schema or
 * a new version publishes {@code Schema Created} or {@code Schema Version Created} from {@code aws.schemas} to the
 * default bus. Code bindings are generated source, zipped.
 */
@ApplicationScoped
public class SchemasService implements Resettable, EventBusObserver {

    private static final Logger LOG = Logger.getLogger(SchemasService.class);
    static final String AWS_EVENTS = "aws.events";
    static final String DISCOVERED = "discovered-schemas";
    private static final int SAMPLES = 20;

    /** Sample events the AWS-managed registry's schemas are inferred from. */
    private static final List<String> AWS_EVENT_SAMPLES = List.of(
            "{\"version\":\"0\",\"id\":\"1\",\"detail-type\":\"Scheduled Event\",\"source\":\"aws.events\","
                    + "\"account\":\"123456789012\",\"time\":\"2026-01-01T00:00:00Z\",\"region\":\"region\","
                    + "\"resources\":[\"arn\"],\"detail\":{}}",
            "{\"version\":\"0\",\"id\":\"1\",\"detail-type\":\"Object Created\",\"source\":\"aws.s3\","
                    + "\"account\":\"123456789012\",\"time\":\"2026-01-01T00:00:00Z\",\"region\":\"region\","
                    + "\"resources\":[\"arn\"],\"detail\":{\"version\":\"0\",\"bucket\":{\"name\":\"b\"},\"object\":"
                    + "{\"key\":\"k\",\"size\":1,\"etag\":\"e\",\"sequencer\":\"s\"},\"request-id\":\"r\","
                    + "\"requester\":\"a\",\"source-ip-address\":\"1.2.3.4\",\"reason\":\"PutObject\"}}",
            "{\"version\":\"0\",\"id\":\"1\",\"detail-type\":\"EC2 Instance State-change Notification\","
                    + "\"source\":\"aws.ec2\",\"account\":\"123456789012\",\"time\":\"2026-01-01T00:00:00Z\","
                    + "\"region\":\"region\",\"resources\":[\"arn\"],\"detail\":{\"instance-id\":\"i-1\","
                    + "\"state\":\"running\"}}");

    private final AccountAwareStorageBackend<ObjectNode> registries;
    private final AccountAwareStorageBackend<ObjectNode> schemas;
    private final AccountAwareStorageBackend<ObjectNode> discoverers;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final SchemaInference inference;
    private final Provider<EventBridgeService> eventBridge;
    private final ExecutorService discovery = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "schemas-discovery");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public SchemasService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                          Provider<EventBridgeService> eventBridge) {
        this.registries = storageFactory.create("schemas", "registries.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.schemas = storageFactory.create("schemas", "schemas.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.discoverers = storageFactory.create("schemas", "discoverers.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.inference = new SchemaInference(mapper);
        this.eventBridge = eventBridge;
    }

    @PreDestroy
    void stop() {
        discovery.shutdownNow();
    }

    @Override
    public void clear() {
        registries.clear();
        schemas.clear();
        discoverers.clear();
    }

    public ObjectNode handle(String operation, JsonNode in, String region) {
        return switch (operation) {
            case "CreateRegistry" -> createRegistry(in, region);
            case "DescribeRegistry" -> registryView(registry(region, name(in, "RegistryName")));
            case "UpdateRegistry" -> updateRegistry(in, region);
            case "DeleteRegistry" -> deleteRegistry(in, region);
            case "ListRegistries" -> listRegistries(in, region);
            case "PutResourcePolicy" -> putPolicy(in, region);
            case "GetResourcePolicy" -> getPolicy(in, region);
            case "DeleteResourcePolicy" -> deletePolicy(in, region);
            case "CreateSchema" -> createSchema(in, region);
            case "UpdateSchema" -> updateSchema(in, region);
            case "DescribeSchema" -> describeSchema(in, region);
            case "DeleteSchema" -> deleteSchema(in, region);
            case "DeleteSchemaVersion" -> deleteSchemaVersion(in, region);
            case "ListSchemas" -> listSchemas(in, region);
            case "ListSchemaVersions" -> listSchemaVersions(in, region);
            case "SearchSchemas" -> searchSchemas(in, region);
            case "ExportSchema" -> exportSchema(in, region);
            case "GetDiscoveredSchema" -> getDiscoveredSchema(in);
            case "PutCodeBinding" -> putCodeBinding(in, region);
            case "DescribeCodeBinding" -> describeCodeBinding(in, region);
            case "GetCodeBindingSource" -> getCodeBindingSource(in, region);
            case "CreateDiscoverer" -> createDiscoverer(in, region);
            case "DescribeDiscoverer" -> discovererView(discoverer(region, name(in, "DiscovererId")));
            case "UpdateDiscoverer" -> updateDiscoverer(in, region);
            case "DeleteDiscoverer" -> deleteDiscoverer(in, region);
            case "ListDiscoverers" -> listDiscoverers(in, region);
            case "StartDiscoverer" -> setDiscovererState(in, region, "STARTED");
            case "StopDiscoverer" -> setDiscovererState(in, region, "STOPPED");
            case "TagResource" -> tag(in, region, true);
            case "UntagResource" -> tag(in, region, false);
            case "ListTagsForResource" -> listTags(in, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation + ".",
                    404);
        };
    }

    // ---------- registries ----------

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("schemas", region, registries.accountId(), resource).toString();
    }

    private ObjectNode createRegistry(JsonNode in, String region) {
        String name = name(in, "RegistryName");
        if (name.startsWith("aws.") || AWS_EVENTS.equals(name)) {
            throw new AwsException("BadRequestException", "Registry names starting with aws. are reserved.", 400);
        }
        return registryView(newRegistry(region, name, in.path("Description").asText(null), in.path("Tags")));
    }

    private ObjectNode newRegistry(String region, String name, String description, JsonNode tags) {
        if (registries.get(key(region, name)).isPresent()) {
            throw new AwsException("ConflictException", "Registry " + name + " already exists.", 409);
        }
        ObjectNode registry = mapper.createObjectNode();
        registry.put("RegistryName", name).put("RegistryArn", arn(region, "registry/" + name));
        if (description != null) {
            registry.put("Description", description);
        }
        registry.set("Tags", tags.isObject() ? tags.deepCopy() : mapper.createObjectNode());
        registries.put(key(region, name), registry);
        return registry;
    }

    private ObjectNode registry(String region, String name) {
        if (AWS_EVENTS.equals(name)) {
            ObjectNode managed = mapper.createObjectNode();
            managed.put("RegistryName", AWS_EVENTS).put("RegistryArn", AwsArnUtils.Arn.of("schemas", region, "",
                    "registry/" + AWS_EVENTS).toString()).put("Description", "Schemas of the events AWS services "
                    + "publish to EventBridge.");
            managed.putObject("Tags");
            return managed;
        }
        return registries.get(key(region, name)).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("Registry " + name + " does not exist."));
    }

    private ObjectNode registryView(ObjectNode registry) {
        ObjectNode view = registry.deepCopy();
        view.remove("_policy");
        return view;
    }

    private ObjectNode updateRegistry(JsonNode in, String region) {
        String name = name(in, "RegistryName");
        requireCustom(name, "updated");
        ObjectNode registry = registry(region, name);
        if (in.has("Description")) {
            registry.put("Description", in.get("Description").asText());
        }
        registries.put(key(region, name), registry);
        return registryView(registry);
    }

    private ObjectNode deleteRegistry(JsonNode in, String region) {
        String name = name(in, "RegistryName");
        requireCustom(name, "deleted");
        registry(region, name);
        if (!schemasOf(region, name).isEmpty()) {
            throw new AwsException("BadRequestException", "Registry " + name + " contains schemas. Delete them "
                    + "before deleting the registry.", 400);
        }
        registries.delete(key(region, name));
        return mapper.createObjectNode();
    }

    private void requireCustom(String registry, String action) {
        if (AWS_EVENTS.equals(registry)) {
            throw new AwsException("ForbiddenException", "The AWS-managed registry can't be " + action + ".", 403);
        }
    }

    private ObjectNode listRegistries(JsonNode in, String region) {
        String scope = in.path("Scope").asText("");
        List<ObjectNode> items = new ArrayList<>();
        if (!"LOCAL".equals(scope)) {
            items.add(summary(registry(region, AWS_EVENTS)));
        }
        if (!"AWS".equals(scope)) {
            for (ObjectNode registry : registries.scan(k -> k.startsWith(region + "/"))) {
                items.add(summary(registry));
            }
        }
        if (in.hasNonNull("RegistryNamePrefix")) {
            String prefix = in.get("RegistryNamePrefix").asText();
            items.removeIf(item -> !item.path("RegistryName").asText().startsWith(prefix));
        }
        items.sort(Comparator.comparing(item -> item.path("RegistryName").asText()));
        return page(items, in, "Registries");
    }

    private ObjectNode summary(ObjectNode registry) {
        ObjectNode out = mapper.createObjectNode();
        for (String field : List.of("RegistryArn", "RegistryName", "Tags")) {
            out.set(field, registry.get(field));
        }
        return out;
    }

    // ---------- resource policies ----------

    private String policyKey(JsonNode in) {
        return in.hasNonNull("RegistryName") ? in.get("RegistryName").asText() : null;
    }

    private ObjectNode putPolicy(JsonNode in, String region) {
        String name = policyKey(in);
        if (name == null) {
            throw new AwsException("BadRequestException", "RegistryName is required.", 400);
        }
        requireCustom(name, "given a policy");
        ObjectNode registry = registry(region, name);
        String policy = in.path("Policy").asText();
        try {
            if (!mapper.readTree(policy).has("Statement")) {
                throw new AwsException("BadRequestException", "The policy needs a Statement.", 400);
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "The policy isn't valid JSON.", 400);
        }
        JsonNode current = registry.path("_policy");
        if (in.hasNonNull("RevisionId") && !in.get("RevisionId").asText().equals(current.path("RevisionId")
                .asText())) {
            throw new AwsException("PreconditionFailedException", "The policy has changed since revision "
                    + in.get("RevisionId").asText() + ".", 412);
        }
        ObjectNode stored = registry.putObject("_policy").put("Policy", policy)
                .put("RevisionId", UUID.randomUUID().toString());
        registries.put(key(region, name), registry);
        return stored.deepCopy();
    }

    private ObjectNode getPolicy(JsonNode in, String region) {
        String name = policyKey(in);
        ObjectNode registry = name == null ? null : registry(region, name);
        if (registry == null || !registry.has("_policy")) {
            throw notFound("Resource policy for " + name + " does not exist.");
        }
        return registry.get("_policy").deepCopy();
    }

    private ObjectNode deletePolicy(JsonNode in, String region) {
        String name = policyKey(in);
        ObjectNode registry = name == null ? null : registry(region, name);
        if (registry == null || !registry.has("_policy")) {
            throw notFound("Resource policy for " + name + " does not exist.");
        }
        registry.remove("_policy");
        registries.put(key(region, name), registry);
        return mapper.createObjectNode();
    }

    // ---------- schemas ----------

    private ObjectNode createSchema(JsonNode in, String region) {
        String registryName = name(in, "RegistryName");
        requireCustom(registryName, "given schemas");
        registry(region, registryName);
        String name = name(in, "SchemaName");
        if (schemas.get(schemaKey(region, registryName, name)).isPresent()) {
            throw new AwsException("ConflictException", "Schema " + name + " already exists.", 409);
        }
        String type = in.path("Type").asText();
        JsonNode content = content(in.path("Content").asText(), type);
        ObjectNode schema = mapper.createObjectNode();
        schema.put("SchemaName", name).put("RegistryName", registryName)
                .put("SchemaArn", arn(region, "schema/" + registryName + "/" + name)).put("Type", type);
        if (in.hasNonNull("Description")) {
            schema.put("Description", in.get("Description").asText());
        }
        schema.set("Tags", in.path("Tags").isObject() ? in.get("Tags").deepCopy() : mapper.createObjectNode());
        schema.putArray("_versions");
        addVersion(schema, content, type);
        schemas.put(schemaKey(region, registryName, name), schema);
        publish(schema, "Schema Created", region);
        return schemaView(schema, null, false);
    }

    /** The content, parsed and checked against its type. */
    private JsonNode content(String text, String type) {
        JsonNode content;
        try {
            content = mapper.readTree(text);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "Content isn't valid JSON.", 400);
        }
        if (!content.isObject()) {
            throw new AwsException("BadRequestException", "Content must be a JSON object.", 400);
        }
        if (SchemaInference.OPEN_API.equals(type)
                && !content.path("openapi").asText().startsWith("3.")) {
            throw new AwsException("BadRequestException", "An OpenApi3 schema needs an openapi version of 3.x.",
                    400);
        }
        if (!SchemaInference.OPEN_API.equals(type) && !SchemaInference.JSON_SCHEMA.equals(type)) {
            throw new AwsException("BadRequestException", "Type must be OpenApi3 or JSONSchemaDraft4.", 400);
        }
        return content;
    }

    private void addVersion(ObjectNode schema, JsonNode content, String type) {
        ArrayNode versions = (ArrayNode) schema.get("_versions");
        int next = 1;
        for (JsonNode version : versions) {
            next = Math.max(next, version.path("SchemaVersion").asInt() + 1);
        }
        double now = now();
        versions.addObject().put("SchemaVersion", String.valueOf(next)).put("Type", type)
                .put("Content", content.toString()).put("VersionCreatedDate", now);
        schema.put("LastModified", now).put("Type", type);
    }

    private ObjectNode schema(String region, String registryName, String name) {
        registry(region, registryName);
        if (AWS_EVENTS.equals(registryName)) {
            for (ObjectNode managed : managedSchemas(region)) {
                if (managed.path("SchemaName").asText().equals(name)) {
                    return managed;
                }
            }
        }
        return schemas.get(schemaKey(region, registryName, name)).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("Schema " + name + " does not exist."));
    }

    private List<ObjectNode> managedSchemas(String region) {
        List<ObjectNode> out = new ArrayList<>();
        for (String sample : AWS_EVENT_SAMPLES) {
            List<JsonNode> events = inference.events(List.of(sample));
            String name = SchemaInference.schemaName(events.get(0));
            ObjectNode schema = mapper.createObjectNode();
            schema.put("SchemaName", name).put("RegistryName", AWS_EVENTS)
                    .put("SchemaArn", AwsArnUtils.Arn.of("schemas", region, "", "schema/" + AWS_EVENTS + "/" + name)
                            .toString()).put("Type", SchemaInference.OPEN_API).put("LastModified", 0);
            schema.putObject("Tags");
            schema.putArray("_versions").addObject().put("SchemaVersion", "1").put("Type", SchemaInference.OPEN_API)
                    .put("Content", inference.infer(events, SchemaInference.OPEN_API).toString())
                    .put("VersionCreatedDate", 0);
            out.add(schema);
        }
        return out;
    }

    private List<ObjectNode> schemasOf(String region, String registryName) {
        if (AWS_EVENTS.equals(registryName)) {
            return managedSchemas(region);
        }
        String prefix = region + "/" + registryName + "/";
        return schemas.scan(k -> k.startsWith(prefix));
    }

    private JsonNode version(ObjectNode schema, String version) {
        JsonNode latest = null;
        for (JsonNode candidate : schema.get("_versions")) {
            if (version != null && version.equals(candidate.path("SchemaVersion").asText())) {
                return candidate;
            }
            latest = candidate;
        }
        if (version != null || latest == null) {
            throw notFound("Schema version " + version + " does not exist.");
        }
        return latest;
    }

    private ObjectNode schemaView(ObjectNode schema, String version, boolean withContent) {
        JsonNode chosen = version(schema, version);
        ObjectNode view = mapper.createObjectNode();
        for (String field : List.of("SchemaArn", "SchemaName", "Description", "LastModified", "Tags")) {
            if (schema.hasNonNull(field)) {
                view.set(field, schema.get(field).deepCopy());
            }
        }
        view.put("SchemaVersion", chosen.path("SchemaVersion").asText()).put("Type", chosen.path("Type").asText())
                .put("VersionCreatedDate", chosen.path("VersionCreatedDate").asDouble());
        if (withContent) {
            view.put("Content", chosen.path("Content").asText());
        }
        return view;
    }

    private ObjectNode updateSchema(JsonNode in, String region) {
        String registryName = name(in, "RegistryName");
        requireCustom(registryName, "changed");
        ObjectNode schema = schema(region, registryName, name(in, "SchemaName"));
        if (in.has("Description")) {
            schema.put("Description", in.get("Description").asText());
            schema.put("LastModified", now());
        }
        boolean versioned = false;
        if (in.hasNonNull("Content")) {
            String type = in.path("Type").asText(schema.path("Type").asText());
            JsonNode content = content(in.get("Content").asText(), type);
            JsonNode latest = version(schema, null);
            if (!content.toString().equals(latest.path("Content").asText()) || !type.equals(latest.path("Type")
                    .asText())) {
                addVersion(schema, content, type);
                versioned = true;
            }
        }
        schemas.put(schemaKey(region, registryName, schema.path("SchemaName").asText()), schema);
        if (versioned) {
            publish(schema, "Schema Version Created", region);
        }
        return schemaView(schema, null, false);
    }

    private ObjectNode describeSchema(JsonNode in, String region) {
        ObjectNode schema = schema(region, name(in, "RegistryName"), name(in, "SchemaName"));
        return schemaView(schema, in.path("SchemaVersion").asText(null), true);
    }

    private ObjectNode deleteSchema(JsonNode in, String region) {
        String registryName = name(in, "RegistryName");
        requireCustom(registryName, "changed");
        ObjectNode schema = schema(region, registryName, name(in, "SchemaName"));
        schemas.delete(schemaKey(region, registryName, schema.path("SchemaName").asText()));
        return mapper.createObjectNode();
    }

    private ObjectNode deleteSchemaVersion(JsonNode in, String region) {
        String registryName = name(in, "RegistryName");
        requireCustom(registryName, "changed");
        ObjectNode schema = schema(region, registryName, name(in, "SchemaName"));
        String target = name(in, "SchemaVersion");
        ArrayNode versions = (ArrayNode) schema.get("_versions");
        version(schema, target);
        if (versions.size() == 1) {
            throw new AwsException("BadRequestException", "Version " + target + " is the schema's only version. "
                    + "Delete the schema instead.", 400);
        }
        for (int i = versions.size() - 1; i >= 0; i--) {
            if (target.equals(versions.get(i).path("SchemaVersion").asText())) {
                versions.remove(i);
            }
        }
        schemas.put(schemaKey(region, registryName, schema.path("SchemaName").asText()), schema);
        return mapper.createObjectNode();
    }

    private ObjectNode listSchemas(JsonNode in, String region) {
        String registryName = name(in, "RegistryName");
        registry(region, registryName);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode schema : schemasOf(region, registryName)) {
            if (in.hasNonNull("SchemaNamePrefix") && !schema.path("SchemaName").asText()
                    .startsWith(in.get("SchemaNamePrefix").asText())) {
                continue;
            }
            ObjectNode item = mapper.createObjectNode();
            for (String field : List.of("SchemaArn", "SchemaName", "LastModified", "Tags")) {
                item.set(field, schema.get(field));
            }
            item.put("VersionCount", schema.get("_versions").size());
            items.add(item);
        }
        items.sort(Comparator.comparing(item -> item.path("SchemaName").asText()));
        return page(items, in, "Schemas");
    }

    private ObjectNode listSchemaVersions(JsonNode in, String region) {
        ObjectNode schema = schema(region, name(in, "RegistryName"), name(in, "SchemaName"));
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode version : schema.get("_versions")) {
            items.add(mapper.createObjectNode().put("SchemaArn", schema.path("SchemaArn").asText())
                    .put("SchemaName", schema.path("SchemaName").asText())
                    .put("SchemaVersion", version.path("SchemaVersion").asText())
                    .put("Type", version.path("Type").asText()));
        }
        return page(items, in, "SchemaVersions");
    }

    /** Schemas whose name or any version's content contains one of the keywords, case-insensitively. */
    private ObjectNode searchSchemas(JsonNode in, String region) {
        String registryName = name(in, "RegistryName");
        registry(region, registryName);
        List<String> keywords = new ArrayList<>();
        for (String keyword : in.path("Keywords").asText().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!keyword.isBlank()) {
                keywords.add(keyword);
            }
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode schema : schemasOf(region, registryName)) {
            String name = schema.path("SchemaName").asText().toLowerCase(Locale.ROOT);
            boolean hit = false;
            ArrayNode versions = mapper.createArrayNode();
            for (JsonNode version : schema.get("_versions")) {
                String content = version.path("Content").asText().toLowerCase(Locale.ROOT);
                hit |= keywords.stream().anyMatch(keyword -> name.contains(keyword) || content.contains(keyword));
                versions.addObject().put("SchemaVersion", version.path("SchemaVersion").asText())
                        .put("Type", version.path("Type").asText())
                        .put("CreatedDate", version.path("VersionCreatedDate").asDouble());
            }
            if (hit) {
                ObjectNode item = mapper.createObjectNode().put("RegistryName", registryName)
                        .put("SchemaArn", schema.path("SchemaArn").asText())
                        .put("SchemaName", schema.path("SchemaName").asText());
                item.set("SchemaVersions", versions);
                items.add(item);
            }
        }
        return page(items, in, "Schemas");
    }

    /** AWS exports only discovered and AWS-managed schemas, and only to JSON Schema Draft 4. */
    private ObjectNode exportSchema(JsonNode in, String region) {
        String registryName = name(in, "RegistryName");
        ObjectNode schema = schema(region, registryName, name(in, "SchemaName"));
        if (!AWS_EVENTS.equals(registryName) && !DISCOVERED.equals(registryName)) {
            throw new AwsException("ForbiddenException", "Only discovered schemas and AWS-managed schemas can be "
                    + "exported. Schema " + schema.path("SchemaName").asText() + " is in registry " + registryName
                    + ".", 403);
        }
        if (!SchemaInference.JSON_SCHEMA.equals(in.path("Type").asText())) {
            throw new AwsException("BadRequestException", "Schemas export only to JSONSchemaDraft4.", 400);
        }
        JsonNode version = version(schema, in.path("SchemaVersion").asText(null));
        JsonNode content;
        try {
            content = mapper.readTree(version.path("Content").asText());
        } catch (Exception e) {
            throw new AwsException("InternalServerErrorException", "The stored schema isn't valid JSON.", 500);
        }
        JsonNode exported = SchemaInference.JSON_SCHEMA.equals(version.path("Type").asText()) ? content
                : inference.exportDraft4(content);
        return mapper.createObjectNode().put("Content", exported.toString())
                .put("SchemaArn", schema.path("SchemaArn").asText())
                .put("SchemaName", schema.path("SchemaName").asText())
                .put("SchemaVersion", version.path("SchemaVersion").asText()).put("Type", SchemaInference.JSON_SCHEMA);
    }

    private ObjectNode getDiscoveredSchema(JsonNode in) {
        List<String> raw = new ArrayList<>();
        in.path("Events").forEach(event -> raw.add(event.asText()));
        return mapper.createObjectNode().put("Content",
                inference.infer(inference.events(raw), in.path("Type").asText()).toString());
    }

    // ---------- code bindings ----------

    private ObjectNode putCodeBinding(JsonNode in, String region) {
        ObjectNode schema = schema(region, name(in, "RegistryName"), name(in, "SchemaName"));
        String language = language(in);
        String version = version(schema, in.path("SchemaVersion").asText(null)).path("SchemaVersion").asText();
        if (AWS_EVENTS.equals(schema.path("RegistryName").asText())) {
            return bindingView(version, mapper.createObjectNode().put("CreationDate", 0).put("LastModified", 0)
                    .put("ready", 0));
        }
        ObjectNode bindings = schema.withObject("/_bindings");
        String bindingKey = language + "/" + version;
        if (!bindings.has(bindingKey)) {
            double now = now();
            bindings.putObject(bindingKey).put("CreationDate", now).put("LastModified", now)
                    .put("ready", now + config.services().schemas().codeBindingMillis() / 1000.0);
            schemas.put(schemaKey(region, schema.path("RegistryName").asText(), schema.path("SchemaName").asText()),
                    schema);
        }
        return bindingView(version, (ObjectNode) bindings.get(bindingKey));
    }

    private static String language(JsonNode in) {
        String language = in.path("Language").asText();
        if (!CodeBindings.LANGUAGES.contains(language)) {
            throw new AwsException("BadRequestException", "Language must be one of "
                    + String.join(", ", CodeBindings.LANGUAGES) + ".", 400);
        }
        return language;
    }

    private ObjectNode bindingView(String version, ObjectNode binding) {
        return mapper.createObjectNode().put("SchemaVersion", version)
                .put("CreationDate", binding.path("CreationDate").asDouble())
                .put("LastModified", binding.path("LastModified").asDouble())
                .put("Status", now() >= binding.path("ready").asDouble() ? "CREATE_COMPLETE" : "CREATE_IN_PROGRESS");
    }

    private ObjectNode binding(JsonNode in, String region, ObjectNode schema) {
        String version = version(schema, in.path("SchemaVersion").asText(null)).path("SchemaVersion").asText();
        if (AWS_EVENTS.equals(schema.path("RegistryName").asText())) {
            return mapper.createObjectNode().put("ready", 0).put("SchemaVersion", version);
        }
        JsonNode binding = schema.path("_bindings").path(language(in) + "/" + version);
        if (binding.isMissingNode()) {
            throw notFound("There's no " + in.path("Language").asText() + " code binding for version " + version
                    + ". Call PutCodeBinding first.");
        }
        return ((ObjectNode) binding).deepCopy().put("SchemaVersion", version);
    }

    private ObjectNode describeCodeBinding(JsonNode in, String region) {
        ObjectNode schema = schema(region, name(in, "RegistryName"), name(in, "SchemaName"));
        ObjectNode binding = binding(in, region, schema);
        return bindingView(binding.path("SchemaVersion").asText(), binding);
    }

    private ObjectNode getCodeBindingSource(JsonNode in, String region) {
        ObjectNode schema = schema(region, name(in, "RegistryName"), name(in, "SchemaName"));
        ObjectNode binding = binding(in, region, schema);
        if (now() < binding.path("ready").asDouble()) {
            throw notFound("The code binding is still being created.");
        }
        JsonNode content;
        try {
            content = mapper.readTree(version(schema, binding.path("SchemaVersion").asText()).path("Content")
                    .asText());
        } catch (Exception e) {
            throw new AwsException("InternalServerErrorException", "The stored schema isn't valid JSON.", 500);
        }
        byte[] zip = CodeBindings.generate(language(in), schema.path("SchemaName").asText(), content);
        return mapper.createObjectNode().put("Body", Base64.getEncoder().encodeToString(zip));
    }

    // ---------- discoverers ----------

    private ObjectNode createDiscoverer(JsonNode in, String region) {
        String source = in.path("SourceArn").asText();
        if (!AwsArnUtils.isArnFor(source, "events") || !AwsArnUtils.parse(source).resource().startsWith("event-bus/")) {
            throw new AwsException("BadRequestException", "SourceArn must be an EventBridge event bus ARN.", 400);
        }
        AwsArnUtils.Arn bus = AwsArnUtils.parse(source);
        String busName = bus.resource().substring("event-bus/".length());
        try {
            eventBridge.get().describeEventBus(busName, bus.region());
        } catch (AwsException e) {
            throw notFound("Event bus " + source + " does not exist.");
        }
        String id = "events-event-bus-" + busName;
        if (discoverers.get(key(region, id)).isPresent()) {
            throw new AwsException("ConflictException", "Event bus " + busName + " already has a discoverer.", 409);
        }
        ObjectNode discoverer = mapper.createObjectNode();
        discoverer.put("DiscovererId", id).put("DiscovererArn", arn(region, "discoverer/" + id))
                .put("SourceArn", source).put("State", "STARTED")
                .put("CrossAccount", in.path("CrossAccount").asBoolean(true));
        if (in.hasNonNull("Description")) {
            discoverer.put("Description", in.get("Description").asText());
        }
        discoverer.set("Tags", in.path("Tags").isObject() ? in.get("Tags").deepCopy() : mapper.createObjectNode());
        discoverers.put(key(region, id), discoverer);
        if (registries.get(key(region, DISCOVERED)).isEmpty()) {
            newRegistry(region, DISCOVERED, "Schemas discovered from events on event buses.",
                    mapper.createObjectNode());
        }
        return discovererView(discoverer);
    }

    private ObjectNode discoverer(String region, String id) {
        return discoverers.get(key(region, id)).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("Discoverer " + id + " does not exist."));
    }

    private ObjectNode discovererView(ObjectNode discoverer) {
        return discoverer.deepCopy();
    }

    private ObjectNode updateDiscoverer(JsonNode in, String region) {
        ObjectNode discoverer = discoverer(region, name(in, "DiscovererId"));
        if (in.has("Description")) {
            discoverer.put("Description", in.get("Description").asText());
        }
        if (in.has("CrossAccount")) {
            discoverer.put("CrossAccount", in.get("CrossAccount").asBoolean());
        }
        discoverers.put(key(region, discoverer.path("DiscovererId").asText()), discoverer);
        return discovererView(discoverer);
    }

    private ObjectNode deleteDiscoverer(JsonNode in, String region) {
        ObjectNode discoverer = discoverer(region, name(in, "DiscovererId"));
        discoverers.delete(key(region, discoverer.path("DiscovererId").asText()));
        return mapper.createObjectNode();
    }

    private ObjectNode listDiscoverers(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode discoverer : discoverers.scan(k -> k.startsWith(region + "/"))) {
            if (in.hasNonNull("DiscovererIdPrefix") && !discoverer.path("DiscovererId").asText()
                    .startsWith(in.get("DiscovererIdPrefix").asText())) {
                continue;
            }
            if (in.hasNonNull("SourceArnPrefix") && !discoverer.path("SourceArn").asText()
                    .startsWith(in.get("SourceArnPrefix").asText())) {
                continue;
            }
            ObjectNode item = discoverer.deepCopy();
            item.remove("Description");
            items.add(item);
        }
        items.sort(Comparator.comparing(item -> item.path("DiscovererId").asText()));
        return page(items, in, "Discoverers");
    }

    private ObjectNode setDiscovererState(JsonNode in, String region, String state) {
        ObjectNode discoverer = discoverer(region, name(in, "DiscovererId"));
        discoverer.put("State", state);
        discoverers.put(key(region, discoverer.path("DiscovererId").asText()), discoverer);
        return mapper.createObjectNode().put("DiscovererId", discoverer.path("DiscovererId").asText())
                .put("State", state);
    }

    /** A started discoverer on the bus infers a schema from the event, off the putting caller's thread. */
    @Override
    public void accepted(String accountId, String region, String busName, String eventJson) {
        if (!config.services().schemas().enabled()) {
            return;
        }
        String id = "events-event-bus-" + busName;
        boolean started = RequestScopes.callAs(accountId, region, () -> discoverers.get(key(region, id))
                .map(discoverer -> "STARTED".equals(discoverer.path("State").asText())).orElse(false));
        if (started) {
            discovery.submit(() -> {
                try {
                    RequestScopes.runAs(accountId, region, () -> discover(region, eventJson));
                } catch (RuntimeException e) {
                    LOG.debugv("Schema discovery failed for an event on {0}: {1}", busName, e.getMessage());
                }
            });
        }
    }

    private synchronized void discover(String region, String eventJson) {
        List<JsonNode> sample = inference.events(List.of(eventJson));
        String name = SchemaInference.schemaName(sample.get(0));
        if (registries.get(key(region, DISCOVERED)).isEmpty()) {
            newRegistry(region, DISCOVERED, "Schemas discovered from events on event buses.",
                    mapper.createObjectNode());
        }
        String schemaKey = schemaKey(region, DISCOVERED, name);
        ObjectNode schema = schemas.get(schemaKey).map(ObjectNode::deepCopy).orElse(null);
        boolean created = schema == null;
        if (created) {
            schema = mapper.createObjectNode();
            schema.put("SchemaName", name).put("RegistryName", DISCOVERED)
                    .put("SchemaArn", arn(region, "schema/" + DISCOVERED + "/" + name))
                    .put("Type", SchemaInference.OPEN_API);
            schema.putObject("Tags");
            schema.putArray("_versions");
            schema.putArray("_samples");
        }
        ArrayNode samples = (ArrayNode) schema.withArray("/_samples");
        samples.add(eventJson);
        while (samples.size() > SAMPLES) {
            samples.remove(0);
        }
        List<String> raw = new ArrayList<>();
        samples.forEach(text -> raw.add(text.asText()));
        JsonNode inferred = inference.infer(inference.events(raw), SchemaInference.OPEN_API);
        boolean versioned = created || !inferred.toString().equals(version(schema, null).path("Content").asText());
        if (versioned) {
            addVersion(schema, inferred, SchemaInference.OPEN_API);
        }
        schemas.put(schemaKey, schema);
        if (versioned) {
            publish(schema, created ? "Schema Created" : "Schema Version Created", region);
        }
    }

    private void publish(ObjectNode schema, String detailType, String region) {
        JsonNode version = version(schema, null);
        ObjectNode detail = mapper.createObjectNode().put("SchemaName", schema.path("SchemaName").asText())
                .put("SchemaType", version.path("Type").asText())
                .put("RegistryName", schema.path("RegistryName").asText())
                .put("CreationDate", Instant.ofEpochMilli((long) (version.path("VersionCreatedDate").asDouble()
                        * 1000)).toString())
                .put("SchemaVersion", version.path("SchemaVersion").asText());
        eventBridge.get().publishServiceEvent("aws.schemas", detailType, detail.toString(),
                schema.path("SchemaArn").asText(), region);
    }

    // ---------- tags ----------

    private record Tagged(AccountAwareStorageBackend<ObjectNode> store, String key, ObjectNode record) {
    }

    private Tagged tagged(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "schemas")) {
            throw new AwsException("BadRequestException", "ResourceArn isn't a schemas ARN.", 400);
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String[] parts = resource.split("/");
        if (resource.startsWith("registry/") && parts.length == 2 && !AWS_EVENTS.equals(parts[1])) {
            return new Tagged(registries, key(region, parts[1]), registry(region, parts[1]));
        }
        if (resource.startsWith("schema/") && parts.length == 3 && !AWS_EVENTS.equals(parts[1])) {
            return new Tagged(schemas, schemaKey(region, parts[1], parts[2]), schema(region, parts[1], parts[2]));
        }
        if (resource.startsWith("discoverer/") && parts.length == 2) {
            return new Tagged(discoverers, key(region, parts[1]), discoverer(region, parts[1]));
        }
        throw notFound("Resource " + arn + " does not exist.");
    }

    private ObjectNode tag(JsonNode in, String region, boolean add) {
        Tagged target = tagged(decode(in.path("ResourceArn").asText()), region);
        ObjectNode tags = target.record().withObject("/Tags");
        if (add) {
            tags.setAll((ObjectNode) in.path("Tags"));
            if (tags.size() > 50) {
                throw new AwsException("BadRequestException", "A resource can have at most 50 tags.", 400);
            }
        } else {
            in.path("TagKeys").forEach(tagKey -> tags.remove(tagKey.asText()));
        }
        target.store().put(target.key(), target.record());
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in, String region) {
        Tagged target = tagged(decode(in.path("ResourceArn").asText()), region);
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", target.record().path("Tags").deepCopy());
        return out;
    }

    // ---------- shared ----------

    private ObjectNode page(List<ObjectNode> items, JsonNode in, String field) {
        int max = in.path("Limit").asInt(100);
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(in.get("NextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new AwsException("BadRequestException", "The NextToken is not valid.", 400);
            }
        }
        start = Math.max(0, Math.min(start, items.size()));
        int end = Math.min(items.size(), start + Math.max(1, max));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray(field);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static String name(JsonNode in, String field) {
        String value = in.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new AwsException("BadRequestException", field + " is required.", 400);
        }
        return decode(value);
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private static String schemaKey(String region, String registry, String name) {
        return region + "/" + registry + "/" + name;
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static String decode(String value) {
        return value.contains("%") ? URLDecoder.decode(value, StandardCharsets.UTF_8) : value;
    }

    private static AwsException notFound(String message) {
        return new AwsException("NotFoundException", message, 404);
    }
}
