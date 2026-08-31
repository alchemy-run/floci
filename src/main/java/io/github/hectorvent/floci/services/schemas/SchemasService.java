package io.github.hectorvent.floci.services.schemas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.schemas.model.CodeBinding;
import io.github.hectorvent.floci.services.schemas.model.Discoverer;
import io.github.hectorvent.floci.services.schemas.model.Registry;
import io.github.hectorvent.floci.services.schemas.model.Schema;
import io.github.hectorvent.floci.services.schemas.model.SchemaVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * EventBridge Schema Registry restJson1 — registries, versioned schemas,
 * discoverers, resource policies, and tags. Tag APIs share {@code /tags/{arn}}
 * via {@link TagHandler} using ARN service {@code schemas}.
 */
@ApplicationScoped
public class SchemasService implements TagHandler {

    static final String SERVICE = "schemas";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9-_.@]+");
    private static final int REGISTRY_NAME_MAX = 64;
    private static final int SCHEMA_NAME_MAX = 385;
    private static final Set<String> AWS_REGISTRIES = Set.of("aws.events", "discovered-schemas");
    private static final Set<String> TYPES = Set.of("OpenApi3", "JSONSchemaDraft4");

    private final StorageBackend<String, Registry> registries;
    private final StorageBackend<String, Schema> schemas;
    private final StorageBackend<String, Discoverer> discoverers;
    private final RegionResolver regionResolver;

    @Inject
    public SchemasService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create(SERVICE, "schemas-registries.json",
                        new TypeReference<Map<String, Registry>>() {
                        }),
                storageFactory.create(SERVICE, "schemas-schemas.json",
                        new TypeReference<Map<String, Schema>>() {
                        }),
                storageFactory.create(SERVICE, "schemas-discoverers.json",
                        new TypeReference<Map<String, Discoverer>>() {
                        }),
                regionResolver);
    }

    SchemasService(
            StorageBackend<String, Registry> registries,
            StorageBackend<String, Schema> schemas,
            StorageBackend<String, Discoverer> discoverers,
            RegionResolver regionResolver) {
        this.registries = registries;
        this.schemas = schemas;
        this.discoverers = discoverers;
        this.regionResolver = regionResolver;
    }

    public synchronized Registry createRegistry(String region, String registryName, JsonNode request) {
        validateName(registryName, "RegistryName", REGISTRY_NAME_MAX);
        if (registries.get(registryKey(region, registryName)).isPresent()) {
            throw new AwsException("ConflictException",
                    "Registry " + registryName + " already exists.", 409);
        }
        Registry registry = new Registry();
        registry.setRegistryName(registryName);
        registry.setRegistryArn(arn(region, "registry/" + registryName));
        registry.setDescription(textOrNull(request, "Description"));
        registry.setLastModified(now());
        registry.setTags(readTags(request));
        registries.put(registryKey(region, registryName), registry);
        return registry;
    }

    public Registry describeRegistry(String region, String registryName) {
        return requireRegistry(region, registryName);
    }

    public synchronized Registry updateRegistry(String region, String registryName, JsonNode request) {
        Registry registry = requireRegistry(region, registryName);
        if (request != null && request.has("Description")) {
            JsonNode value = request.get("Description");
            registry.setDescription(value == null || value.isNull() ? null : value.asText());
        }
        registry.setLastModified(now());
        registries.put(registryKey(region, registryName), registry);
        return registry;
    }

    public synchronized void deleteRegistry(String region, String registryName) {
        requireRegistry(region, registryName);
        String prefix = region + "::" + registryName + "::";
        boolean hasSchemas = !schemas.scan(key -> key.startsWith(prefix)).isEmpty();
        if (hasSchemas) {
            throw new AwsException("BadRequestException",
                    "Cannot delete registry " + registryName + " because it still contains schemas.",
                    400);
        }
        registries.delete(registryKey(region, registryName));
    }

    public List<Registry> listRegistries(String region, String prefix, String scope) {
        String effectiveScope = scope == null || scope.isBlank() ? "LOCAL" : scope;
        List<Registry> items = new ArrayList<>(registries.scan(key -> key.startsWith(region + "::")));
        items.sort(Comparator.comparing(Registry::getRegistryName, Comparator.nullsLast(String::compareTo)));
        return items.stream()
                .filter(registry -> prefix == null || prefix.isBlank()
                        || registry.getRegistryName().startsWith(prefix))
                .filter(registry -> matchesScope(registry.getRegistryName(), effectiveScope))
                .toList();
    }

    public Registry getResourcePolicy(String region, String registryName) {
        Registry registry = requireRegistry(region, registryName);
        if (registry.getPolicy() == null || registry.getPolicy().isBlank()) {
            throw notFound("Policy for registry " + registryName + " not found.");
        }
        return registry;
    }

    public synchronized Registry putResourcePolicy(String region, String registryName, JsonNode request) {
        Registry registry = requireRegistry(region, registryName);
        String policy = requireText(request, "Policy");
        registry.setPolicy(policy);
        String revision = textOrNull(request, "RevisionId");
        registry.setPolicyRevisionId(revision == null ? UUID.randomUUID().toString() : revision);
        registry.setLastModified(now());
        registries.put(registryKey(region, registryName), registry);
        return registry;
    }

    public synchronized Registry deleteResourcePolicy(String region, String registryName) {
        Registry registry = getResourcePolicy(region, registryName);
        registry.setPolicy(null);
        registry.setPolicyRevisionId(null);
        registry.setLastModified(now());
        registries.put(registryKey(region, registryName), registry);
        return registry;
    }

    public synchronized Schema createSchema(String region, String registryName, String schemaName, JsonNode request) {
        requireRegistry(region, registryName);
        validateName(schemaName, "SchemaName", SCHEMA_NAME_MAX);
        if (schemas.get(schemaKey(region, registryName, schemaName)).isPresent()) {
            throw new AwsException("ConflictException",
                    "Schema " + schemaName + " already exists in registry " + registryName + ".", 409);
        }
        String content = requireText(request, "Content");
        String type = typeOrDefault(textOrNull(request, "Type"));
        String created = now();
        Schema schema = new Schema();
        schema.setRegistryName(registryName);
        schema.setSchemaName(schemaName);
        schema.setSchemaArn(arn(region, "schema/" + registryName + "/" + schemaName));
        schema.setDescription(textOrNull(request, "Description"));
        schema.setLastModified(created);
        schema.setTags(readTags(request));
        schema.getVersions().add(new SchemaVersion("1", content, type, created));
        schemas.put(schemaKey(region, registryName, schemaName), schema);
        return schema;
    }

    public Schema describeSchema(String region, String registryName, String schemaName, String schemaVersion) {
        Schema schema = requireSchema(region, registryName, schemaName);
        if (schemaVersion == null || schemaVersion.isBlank()) {
            return schema;
        }
        SchemaVersion found = schema.getVersions().stream()
                .filter(version -> schemaVersion.equals(version.getVersion()))
                .findFirst()
                .orElseThrow(() -> notFound("Schema version " + schemaVersion + " not found."));
        Schema snapshot = copySchema(schema);
        snapshot.setVersions(List.of(found));
        return snapshot;
    }

    public synchronized Schema updateSchema(String region, String registryName, String schemaName, JsonNode request) {
        Schema schema = requireSchema(region, registryName, schemaName);
        boolean contentPresent = request != null && request.has("Content") && !request.get("Content").isNull();
        boolean descriptionPresent = request != null && request.has("Description");
        if (descriptionPresent) {
            JsonNode value = request.get("Description");
            schema.setDescription(value == null || value.isNull() ? null : value.asText());
        }
        if (contentPresent) {
            String content = requireText(request, "Content");
            SchemaVersion latest = schema.latestVersion();
            String type = typeOrDefault(textOrNull(request, "Type"));
            if (latest != null && textOrNull(request, "Type") == null) {
                type = latest.getType() == null ? "OpenApi3" : latest.getType();
            }
            int next = latest == null ? 1 : Integer.parseInt(latest.getVersion()) + 1;
            String created = now();
            schema.getVersions().add(new SchemaVersion(Integer.toString(next), content, type, created));
            schema.setLastModified(created);
        } else {
            schema.setLastModified(now());
        }
        schemas.put(schemaKey(region, registryName, schemaName), schema);
        return schema;
    }

    public synchronized void deleteSchema(String region, String registryName, String schemaName) {
        requireSchema(region, registryName, schemaName);
        schemas.delete(schemaKey(region, registryName, schemaName));
    }

    public List<Schema> listSchemas(String region, String registryName, String schemaNamePrefix) {
        requireRegistry(region, registryName);
        String prefix = region + "::" + registryName + "::";
        List<Schema> items = new ArrayList<>(schemas.scan(key -> key.startsWith(prefix)));
        items.sort(Comparator.comparing(Schema::getSchemaName, Comparator.nullsLast(String::compareTo)));
        if (schemaNamePrefix == null || schemaNamePrefix.isBlank()) {
            return items;
        }
        return items.stream()
                .filter(schema -> schema.getSchemaName().startsWith(schemaNamePrefix))
                .toList();
    }

    public synchronized Discoverer createDiscoverer(String region, JsonNode request) {
        String sourceArn = requireText(request, "SourceArn");
        boolean exists = discoverers.scan(key -> key.startsWith(region + "::")).stream()
                .anyMatch(existing -> sourceArn.equals(existing.getSourceArn()));
        if (exists) {
            throw new AwsException("ConflictException",
                    "A discoverer already exists for source " + sourceArn, 409);
        }
        String id = UUID.randomUUID().toString();
        Discoverer discoverer = new Discoverer();
        discoverer.setDiscovererId(id);
        discoverer.setDiscovererArn(arn(region, "discoverer/" + id));
        discoverer.setSourceArn(sourceArn);
        discoverer.setDescription(textOrNull(request, "Description"));
        discoverer.setCrossAccount(booleanOrDefault(request, "CrossAccount", true));
        discoverer.setState(Discoverer.STARTED);
        discoverer.setTags(readTags(request));
        discoverer.setRegion(region);
        discoverers.put(discovererKey(region, id), discoverer);
        return discoverer;
    }

    public Discoverer describeDiscoverer(String region, String discovererId) {
        return requireDiscoverer(region, discovererId);
    }

    public synchronized Discoverer updateDiscoverer(String region, String discovererId, JsonNode request) {
        Discoverer discoverer = requireDiscoverer(region, discovererId);
        if (request != null && request.has("Description")) {
            JsonNode value = request.get("Description");
            discoverer.setDescription(value == null || value.isNull() ? null : value.asText());
        }
        if (request != null && request.has("CrossAccount") && !request.get("CrossAccount").isNull()) {
            discoverer.setCrossAccount(request.get("CrossAccount").asBoolean());
        }
        discoverers.put(discovererKey(region, discovererId), discoverer);
        return discoverer;
    }

    public synchronized void deleteDiscoverer(String region, String discovererId) {
        requireDiscoverer(region, discovererId);
        discoverers.delete(discovererKey(region, discovererId));
    }

    public List<Discoverer> listDiscoverers(String region, String discovererIdPrefix, String sourceArnPrefix) {
        List<Discoverer> items = new ArrayList<>(discoverers.scan(key -> key.startsWith(region + "::")));
        items.sort(Comparator.comparing(Discoverer::getDiscovererId, Comparator.nullsLast(String::compareTo)));
        return items.stream()
                .filter(d -> discovererIdPrefix == null || discovererIdPrefix.isBlank()
                        || d.getDiscovererId().startsWith(discovererIdPrefix))
                .filter(d -> sourceArnPrefix == null || sourceArnPrefix.isBlank()
                        || d.getSourceArn().startsWith(sourceArnPrefix))
                .toList();
    }

    public synchronized Discoverer startDiscoverer(String region, String discovererId) {
        Discoverer discoverer = requireDiscoverer(region, discovererId);
        discoverer.setState(Discoverer.STARTED);
        discoverers.put(discovererKey(region, discovererId), discoverer);
        return discoverer;
    }

    public synchronized Discoverer stopDiscoverer(String region, String discovererId) {
        Discoverer discoverer = requireDiscoverer(region, discovererId);
        discoverer.setState(Discoverer.STOPPED);
        discoverers.put(discovererKey(region, discovererId), discoverer);
        return discoverer;
    }

    public List<Schema> searchSchemas(String region, String registryName, String keywords) {
        List<Schema> all = listSchemas(region, registryName, null);
        if (keywords == null || keywords.isBlank()) {
            return all;
        }
        String needle = keywords.toLowerCase(Locale.ROOT);
        List<Schema> matched = new ArrayList<>();
        for (Schema schema : all) {
            if (containsIgnoreCase(schema.getSchemaName(), needle)
                    || containsIgnoreCase(schema.getDescription(), needle)) {
                matched.add(schema);
                continue;
            }
            for (SchemaVersion version : schema.getVersions()) {
                if (containsIgnoreCase(version.getContent(), needle)) {
                    matched.add(schema);
                    break;
                }
            }
        }
        return matched;
    }

    public void exportSchema(String region, String registryName, String schemaName) {
        requireSchema(region, registryName, schemaName);
        if (!AWS_REGISTRIES.contains(registryName)) {
            throw new AwsException("ForbiddenException",
                    "You cannot export non discovered or non aws managed schemas.", 403);
        }
    }

    public String getDiscoveredSchema(String type, List<String> events) {
        if (events == null || events.isEmpty()) {
            throw new AwsException("BadRequestException", "Events is required.", 400);
        }
        String schemaType = type == null || type.isBlank() ? "OpenApi3" : type;
        ObjectNode properties = JSON.createObjectNode();
        for (String event : events) {
            collectProperties(properties, event);
        }
        try {
            if ("JSONSchemaDraft4".equals(schemaType)) {
                ObjectNode schema = JSON.createObjectNode();
                schema.put("$schema", "http://json-schema.org/draft-04/schema#");
                schema.put("type", "object");
                schema.set("properties", properties);
                return JSON.writeValueAsString(schema);
            }
            ObjectNode document = JSON.createObjectNode();
            document.put("openapi", "3.0.0");
            ObjectNode info = document.putObject("info");
            info.put("version", "1.0.0");
            info.put("title", "Discovered");
            document.putObject("paths");
            ObjectNode discovered = document.putObject("components").putObject("schemas")
                    .putObject("Discovered");
            discovered.put("type", "object");
            discovered.set("properties", properties);
            return JSON.writeValueAsString(document);
        } catch (IOException e) {
            throw new AwsException("InternalServerErrorException", e.getMessage(), 500);
        }
    }

    public synchronized CodeBinding putCodeBinding(String region, String registryName, String schemaName,
                                                   String language, String schemaVersion) {
        if (language == null || language.isBlank()) {
            throw new AwsException("BadRequestException", "Language is required.", 400);
        }
        Schema schema = requireSchema(region, registryName, schemaName);
        SchemaVersion version = resolveVersion(schema, schemaVersion);
        String bindingKey = language + "::" + version.getVersion();
        if (schema.getCodeBindings().containsKey(bindingKey)) {
            throw new AwsException("ConflictException",
                    "Code binding already exists for language " + language + ".", 409);
        }
        String created = now();
        CodeBinding binding = new CodeBinding();
        binding.setLanguage(language);
        binding.setSchemaVersion(version.getVersion());
        binding.setStatus("CREATE_COMPLETE");
        binding.setCreationDate(created);
        binding.setLastModified(created);
        binding.setSourceBase64(Base64.getEncoder().encodeToString(generateBindingZip(schema, language)));
        schema.getCodeBindings().put(bindingKey, binding);
        schemas.put(schemaKey(region, registryName, schemaName), schema);
        return binding;
    }

    public CodeBinding describeCodeBinding(String region, String registryName, String schemaName,
                                           String language, String schemaVersion) {
        Schema schema = requireSchema(region, registryName, schemaName);
        SchemaVersion version = resolveVersion(schema, schemaVersion);
        CodeBinding binding = schema.getCodeBindings().get(language + "::" + version.getVersion());
        if (binding == null) {
            throw notFound("Code binding not found.");
        }
        return binding;
    }

    public byte[] getCodeBindingSource(String region, String registryName, String schemaName,
                                       String language, String schemaVersion) {
        CodeBinding binding = describeCodeBinding(region, registryName, schemaName, language, schemaVersion);
        if (binding.getSourceBase64() == null) {
            throw notFound("Code binding source not found.");
        }
        return Base64.getDecoder().decode(binding.getSourceBase64());
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        tagged.applyTags(current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        tagged.applyTags(current);
    }

    private Tagged requireTagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw notFound("Resource " + arn + " not found.");
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null) {
            throw notFound("Resource " + arn + " not found.");
        }
        String resource = parsed.resource();
        if (resource.startsWith("registry/")) {
            String name = resource.substring("registry/".length());
            Registry registry = requireRegistry(region, name);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return registry.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    registry.setTags(tags);
                    registries.put(registryKey(region, name), registry);
                }
            };
        }
        if (resource.startsWith("schema/")) {
            String remainder = resource.substring("schema/".length());
            int slash = remainder.indexOf('/');
            if (slash <= 0 || slash == remainder.length() - 1) {
                throw notFound("Resource " + arn + " not found.");
            }
            String registryName = remainder.substring(0, slash);
            String schemaName = remainder.substring(slash + 1);
            Schema schema = requireSchema(region, registryName, schemaName);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return schema.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    schema.setTags(tags);
                    schemas.put(schemaKey(region, registryName, schemaName), schema);
                }
            };
        }
        if (resource.startsWith("discoverer/")) {
            String id = resource.substring("discoverer/".length());
            if (id.isBlank() || id.contains("/")) {
                throw notFound("Resource " + arn + " not found.");
            }
            Discoverer discoverer = requireDiscoverer(region, id);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return discoverer.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    discoverer.setTags(tags);
                    discoverers.put(discovererKey(region, id), discoverer);
                }
            };
        }
        throw notFound("Resource " + arn + " not found.");
    }

    private Registry requireRegistry(String region, String registryName) {
        if (registryName == null || registryName.isBlank()) {
            throw new AwsException("BadRequestException", "RegistryName is required.", 400);
        }
        return registries.get(registryKey(region, registryName))
                .orElseThrow(() -> notFound("Registry " + registryName + " not found."));
    }

    private Schema requireSchema(String region, String registryName, String schemaName) {
        requireRegistry(region, registryName);
        if (schemaName == null || schemaName.isBlank()) {
            throw new AwsException("BadRequestException", "SchemaName is required.", 400);
        }
        return schemas.get(schemaKey(region, registryName, schemaName))
                .orElseThrow(() -> notFound("Schema " + schemaName + " not found."));
    }

    private Discoverer requireDiscoverer(String region, String discovererId) {
        if (discovererId == null || discovererId.isBlank()) {
            throw new AwsException("BadRequestException", "DiscovererId is required.", 400);
        }
        return discoverers.get(discovererKey(region, discovererId))
                .orElseThrow(() -> notFound("Discoverer " + discovererId + " not found."));
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), resource).toString();
    }

    private static String registryKey(String region, String registryName) {
        return region + "::" + registryName;
    }

    private static String schemaKey(String region, String registryName, String schemaName) {
        return region + "::" + registryName + "::" + schemaName;
    }

    private static String discovererKey(String region, String discovererId) {
        return region + "::" + discovererId;
    }

    private static boolean matchesScope(String name, String scope) {
        boolean awsManaged = AWS_REGISTRIES.contains(name);
        if ("AWS".equalsIgnoreCase(scope)) {
            return awsManaged;
        }
        if ("ALL".equalsIgnoreCase(scope)) {
            return true;
        }
        return !awsManaged;
    }

    private static void validateName(String name, String field, int maxLength) {
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", field + " is required.", 400);
        }
        if (name.length() > maxLength || !NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("BadRequestException",
                    field + " must match [a-zA-Z0-9-_.@]+ and be at most " + maxLength + " characters.",
                    400);
        }
    }

    private static String typeOrDefault(String type) {
        String resolved = type == null || type.isBlank() ? "OpenApi3" : type;
        if (!TYPES.contains(resolved)) {
            throw new AwsException("BadRequestException",
                    "Type must be OpenApi3 or JSONSchemaDraft4.", 400);
        }
        return resolved;
    }

    private static Schema copySchema(Schema schema) {
        Schema copy = new Schema();
        copy.setRegistryName(schema.getRegistryName());
        copy.setSchemaName(schema.getSchemaName());
        copy.setSchemaArn(schema.getSchemaArn());
        copy.setDescription(schema.getDescription());
        copy.setLastModified(schema.getLastModified());
        copy.setTags(schema.getTags());
        copy.setVersions(schema.getVersions());
        copy.setCodeBindings(schema.getCodeBindings());
        return copy;
    }

    private static SchemaVersion resolveVersion(Schema schema, String schemaVersion) {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            SchemaVersion latest = schema.latestVersion();
            if (latest == null) {
                throw notFound("Schema version not found.");
            }
            return latest;
        }
        return schema.getVersions().stream()
                .filter(version -> schemaVersion.equals(version.getVersion()))
                .findFirst()
                .orElseThrow(() -> notFound("Schema version " + schemaVersion + " not found."));
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private void collectProperties(ObjectNode properties, String eventJson) {
        try {
            JsonNode event = JSON.readTree(eventJson);
            JsonNode source = event;
            if (event.isObject() && event.has("detail") && event.get("detail").isObject()) {
                source = event.get("detail");
            }
            if (!source.isObject()) {
                return;
            }
            source.fields().forEachRemaining(field -> {
                if (!properties.has(field.getKey())) {
                    ObjectNode property = properties.putObject(field.getKey());
                    property.put("type", jsonSchemaType(field.getValue()));
                }
            });
        } catch (IOException ignored) {
            // skip unparseable sample events
        }
    }

    private static String jsonSchemaType(JsonNode value) {
        if (value == null || value.isNull()) {
            return "string";
        }
        if (value.isNumber()) {
            return value.isIntegralNumber() ? "integer" : "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isObject()) {
            return "object";
        }
        return "string";
    }

    private byte[] generateBindingZip(Schema schema, String language) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            String filename = language.toLowerCase(Locale.ROOT).contains("python")
                    ? "schema.py"
                    : "schema.txt";
            zos.putNextEntry(new ZipEntry(filename));
            String body = "# generated code binding for "
                    + schema.getRegistryName() + "/" + schema.getSchemaName()
                    + " (" + language + ")\n";
            zos.write(body.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            throw new AwsException("InternalServerErrorException", e.getMessage(), 500);
        }
        return baos.toByteArray();
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (request == null) {
            return tags;
        }
        JsonNode node = request.get("tags");
        if (node == null || node.isNull()) {
            node = request.get("Tags");
        }
        if (node == null || node.isNull() || !node.isObject()) {
            return tags;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && value.isTextual()) {
                tags.put(entry.getKey(), value.textValue());
            }
        });
        return tags;
    }

    private static String requireText(JsonNode parent, String field) {
        if (parent == null) {
            throw new AwsException("BadRequestException", field + " is required.", 400);
        }
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw new AwsException("BadRequestException", field + " is required.", 400);
        }
        String text = value.textValue();
        if (text == null || text.isBlank()) {
            throw new AwsException("BadRequestException", field + " is required.", 400);
        }
        return text;
    }

    private static boolean booleanOrDefault(JsonNode parent, String field, boolean defaultValue) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return defaultValue;
        }
        return parent.get(field).asBoolean();
    }

    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            return value.asText(null);
        }
        String text = value.textValue();
        return text == null || text.isBlank() ? null : text;
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static AwsException notFound(String message) {
        return new AwsException("NotFoundException", message, 404);
    }

    private interface Tagged {
        Map<String, String> tags();

        void applyTags(Map<String, String> tags);
    }
}
