package io.floci.aws.services.iotmanagedintegrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The public schema catalog: a capability for every Matter 1.4 cluster ({@code matter.OnOff@1.4}) and a definition
 * for every Matter data type, generated from the Matter data model by {@code tools/aws/regen_iotmi_matter_schemas.py}.
 * A capability is served in the managed integrations format ({@code AWS}), as the Matter cluster it was derived from
 * ({@code ZCL}), or as a connector sees it ({@code CONNECTOR}: the cluster's IDs keyed by capability names).
 */
@ApplicationScoped
public class IotMiSchemas {

    private static final String RESOURCE = "aws/iotmanagedintegrations-matter-schemas.json";

    private final ObjectMapper mapper;
    private final Map<String, Map<String, ObjectNode>> schemas = new LinkedHashMap<>();
    /** Capability by Matter cluster ID. */
    private final Map<Long, ObjectNode> byCluster = new LinkedHashMap<>();

    @Inject
    public IotMiSchemas(ObjectMapper mapper) {
        this.mapper = mapper;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            JsonNode document = mapper.readTree(in);
            for (String type : List.of("capability", "definition")) {
                Map<String, ObjectNode> entries = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = document.path("capability".equals(type) ? "capabilities"
                        : "definitions").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    entries.put(entry.getKey(), (ObjectNode) entry.getValue());
                    if ("capability".equals(type)) {
                        byCluster.put(number(entry.getValue().path("extrinsicId").asText()),
                                (ObjectNode) entry.getValue());
                    }
                }
                schemas.put(type, entries);
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("The managed integrations schema catalog " + RESOURCE
                    + " could not be loaded", e);
        }
    }

    public ObjectNode listSchemaVersions(String type, String schemaId, String namespace, String visibility,
                                         String version, String token, Integer max) {
        Map<String, ObjectNode> entries = entries(type);
        List<JsonNode> items = new ArrayList<>();
        if (!"PRIVATE".equals(visibility)) {
            for (Map.Entry<String, ObjectNode> entry : entries.entrySet()) {
                String id = entry.getKey().substring(0, entry.getKey().indexOf('@'));
                String semantic = entry.getKey().substring(entry.getKey().indexOf('@') + 1);
                String space = id.substring(0, id.indexOf('.'));
                if ((schemaId != null && !schemaId.equals(id)) || (namespace != null && !namespace.equals(space))
                        || (version != null && !"$latest".equals(version) && !version.equals(semantic))) {
                    continue;
                }
                items.add(summary(entry.getKey(), entry.getValue(), type));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        IotMiStore.page(items, token, max, out, "Items");
        return out;
    }

    public ObjectNode getSchemaVersion(String type, String versionedId, String format) {
        ObjectNode schema = find(type, versionedId)
                .orElseThrow(() -> IotMiStore.notFound("schema version", versionedId));
        String key = schema.path("$id").asText().substring(schema.path("$id").asText().lastIndexOf('/') + 1);
        ObjectNode out = summary(key, schema, type);
        String rendered = format == null ? "AWS" : format;
        if (!"capability".equals(type) && !"AWS".equals(rendered)) {
            throw IotMiStore.validation("Definitions are served only in the AWS format.");
        }
        out.set("Schema", switch (rendered) {
            case "ZCL" -> zcl(schema);
            case "CONNECTOR" -> connector(schema);
            case "AWS" -> schema.deepCopy();
            default -> throw IotMiStore.validation("Format must be AWS, ZCL or CONNECTOR.");
        });
        return out;
    }

    /** A capability or definition by versioned ID, {@code $latest} naming the newest version. */
    Optional<ObjectNode> find(String type, String versionedId) {
        Map<String, ObjectNode> entries = entries(type);
        if (versionedId.endsWith("@$latest")) {
            String prefix = versionedId.substring(0, versionedId.indexOf('@') + 1);
            return entries.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                    .map(Map.Entry::getValue).reduce((first, second) -> second);
        }
        return Optional.ofNullable(entries.get(versionedId));
    }

    /** The capability derived from a Matter cluster. */
    Optional<ObjectNode> capabilityForCluster(String clusterId) {
        return Optional.ofNullable(byCluster.get(number(clusterId)));
    }

    /** The capability a capability ID names: {@code matter.OnOff}, {@code matter.OnOff@1.4} or {@code aws.OnOff}. */
    Optional<ObjectNode> capability(String id) {
        String base = id.contains("@") ? id.substring(0, id.indexOf('@')) : id;
        String name = base.substring(base.indexOf('.') + 1);
        return find("capability", "matter." + name + "@$latest");
    }

    private Map<String, ObjectNode> entries(String type) {
        Map<String, ObjectNode> entries = schemas.get(type);
        if (entries == null) {
            throw IotMiStore.validation("Type must be capability or definition.");
        }
        return entries;
    }

    private ObjectNode summary(String key, ObjectNode schema, String type) {
        String id = key.substring(0, key.indexOf('@'));
        return mapper.createObjectNode().put("SchemaId", id).put("Type", type)
                .put("Description", schema.path("description").asText())
                .put("Namespace", id.substring(0, id.indexOf('.')))
                .put("SemanticVersion", key.substring(key.indexOf('@') + 1)).put("Visibility", "PUBLIC");
    }

    /** The capability as the Matter cluster it came from: its ID, revision, attributes, commands and events. */
    private ObjectNode zcl(ObjectNode schema) {
        ObjectNode cluster = mapper.createObjectNode();
        cluster.put("id", schema.path("extrinsicId").asText()).put("name", schema.path("name").asText())
                .put("revision", Integer.parseInt(schema.path("extrinsicVersion").asText("1")));
        ArrayNode attributes = cluster.putArray("attributes");
        schema.path("properties").fields().forEachRemaining(property -> attributes.addObject()
                .put("id", property.getValue().path("extrinsicId").asText()).put("name", property.getKey())
                .put("writable", property.getValue().path("mutable").asBoolean()));
        ArrayNode commands = cluster.putArray("commands");
        for (JsonNode action : schema.path("actions")) {
            ObjectNode command = commands.addObject().put("id", action.path("extrinsicId").asText())
                    .put("name", action.path("name").asText());
            ArrayNode fields = command.putArray("fields");
            action.path("request").path("parameters").fields().forEachRemaining(field -> fields.addObject()
                    .put("id", field.getValue().path("extrinsicId").asText()).put("name", field.getKey()));
        }
        ArrayNode events = cluster.putArray("events");
        for (JsonNode event : schema.path("events")) {
            events.addObject().put("id", event.path("extrinsicId").asText()).put("name", event.path("name").asText());
        }
        return cluster;
    }

    /** The capability as a connector addresses it: names mapped to the cluster's IDs. */
    private ObjectNode connector(ObjectNode schema) {
        ObjectNode connector = mapper.createObjectNode();
        connector.put("id", schema.path("$id").asText().substring(schema.path("$id").asText().lastIndexOf('/') + 1))
                .put("clusterId", schema.path("extrinsicId").asText());
        ObjectNode properties = connector.putObject("properties");
        schema.path("properties").fields().forEachRemaining(property -> properties.put(property.getKey(),
                property.getValue().path("extrinsicId").asText()));
        ObjectNode actions = connector.putObject("actions");
        for (JsonNode action : schema.path("actions")) {
            actions.put(action.path("name").asText(), action.path("extrinsicId").asText());
        }
        ObjectNode events = connector.putObject("events");
        for (JsonNode event : schema.path("events")) {
            events.put(event.path("name").asText(), event.path("extrinsicId").asText());
        }
        return connector;
    }

    static long number(String id) {
        String text = id.trim().toLowerCase(Locale.ROOT);
        return text.startsWith("0x") ? Long.parseLong(text.substring(2), 16) : Long.parseLong(text);
    }
}
