package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class CloudTrailLakeService {
    private static final Set<String> MUTABLE = Set.of("Name", "AdvancedEventSelectors", "MultiRegionEnabled",
            "OrganizationEnabled", "RetentionPeriod", "TerminationProtectionEnabled", "BillingMode", "KmsKeyId");
    private static final Set<String> FIELDS = Set.of("eventCategory", "eventSource", "eventName", "readOnly",
            "resources.type", "resources.ARN", "userIdentity.arn");
    private static final Set<String> OPERATORS = Set.of("Equals", "NotEquals", "StartsWith", "NotStartsWith",
            "EndsWith", "NotEndsWith");
    private final StorageBackend<String, ObjectNode> stores;
    private final StorageBackend<String, ObjectNode> events;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public CloudTrailLakeService(StorageFactory factory, RegionResolver regions, ObjectMapper mapper) {
        this.stores = factory.create("cloudtrail", "cloudtrail-event-data-stores.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.events = factory.create("cloudtrail", "cloudtrail-lake-events.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regions = regions;
        this.mapper = mapper;
    }

    public synchronized ObjectNode handle(String action, JsonNode request, String region) {
        purgeExpired();
        return switch (action) {
            case "CreateEventDataStore" -> create(request, region);
            case "ListEventDataStores" -> CloudTrailPages.page(mapper, request,
                    regions.getAccountId() + ":" + region + ":stores", "EventDataStores",
                    stores.scan(k -> k.startsWith(region + ":")).stream()
                            .sorted(Comparator.comparing(s -> s.path("EventDataStoreArn").asText()))
                            .map(this::publicStore).toList(), 100, 100, "InvalidMaxResultsException");
            case "GetEventDataStore" -> publicStore(find(request.path("EventDataStore").asText(null), region));
            case "UpdateEventDataStore" -> update(request, region);
            case "DeleteEventDataStore", "RestoreEventDataStore", "StartEventDataStoreIngestion",
                 "StopEventDataStoreIngestion" -> transition(action, request, region);
            case "StartQuery", "DescribeQuery", "GetQueryResults", "ListQueries", "CancelQuery", "GenerateQuery" ->
                    throw unsupported("CloudTrail Lake SQL query execution is not implemented.");
            default -> throw unsupported("CloudTrail Lake operation is not implemented: " + action);
        };
    }

    private ObjectNode create(JsonNode request, String region) {
        ObjectNode value = mapper.createObjectNode();
        value.put("Name", request.path("Name").asText(""));
        value.put("MultiRegionEnabled", true).put("OrganizationEnabled", false)
                .put("RetentionPeriod", 366).put("TerminationProtectionEnabled", true)
                .put("BillingMode", "EXTENDABLE_RETENTION_PRICING");
        value.putArray("AdvancedEventSelectors").addObject().putArray("FieldSelectors")
                .addObject().put("Field", "eventCategory").putArray("Equals").add("Management");
        MUTABLE.forEach(field -> { if (request.has(field)) value.set(field, request.get(field).deepCopy()); });
        validate(value);
        requireAvailableName(region, value.path("Name").asText(), null);
        if (request.has("StartIngestion") && !request.path("StartIngestion").isBoolean()) {
            throw invalid("StartIngestion must be a boolean.");
        }
        String arn = regions.buildArn("cloudtrail", region, "eventdatastore/" + UUID.randomUUID());
        double now = System.currentTimeMillis() / 1000.0;
        value.put("EventDataStoreArn", arn).put("CreatedTimestamp", now).put("UpdatedTimestamp", now)
                .put("Status", request.path("StartIngestion").asBoolean(true) ? "ENABLED" : "STOPPED_INGESTION");
        value.set("TagsList", request.has("TagsList") ? request.get("TagsList").deepCopy() : mapper.createArrayNode());
        validateTags(value.path("TagsList"));
        stores.put(key(region, arn), value);
        return value.deepCopy();
    }

    private ObjectNode update(JsonNode request, String region) {
        ObjectNode value = find(request.path("EventDataStore").asText(null), region).deepCopy();
        requireActive(value);
        MUTABLE.forEach(field -> { if (request.has(field)) value.set(field, request.get(field).deepCopy()); });
        validate(value);
        requireAvailableName(region, value.path("Name").asText(), value.path("EventDataStoreArn").asText());
        save(region, value);
        return publicStore(value);
    }

    private ObjectNode transition(String action, JsonNode request, String region) {
        ObjectNode value = find(request.path("EventDataStore").asText(null), region).deepCopy();
        String status = value.path("Status").asText();
        if ("RestoreEventDataStore".equals(action)) {
            if (!"PENDING_DELETION".equals(status)) {
                throw new AwsException("InvalidEventDataStoreStatusException", "Store is not pending deletion.", 400);
            }
            value.put("Status", "STOPPED_INGESTION");
            value.remove("_deleteAfter");
        } else {
            requireActive(value);
            switch (action) {
                case "DeleteEventDataStore" -> {
                    if (value.path("TerminationProtectionEnabled").asBoolean()) {
                        throw new AwsException("EventDataStoreTerminationProtectedException",
                                "Disable termination protection before deleting this event data store.", 400);
                    }
                    value.put("Status", "PENDING_DELETION")
                            .put("_deleteAfter", System.currentTimeMillis() + 7L * 86400000);
                }
                case "StartEventDataStoreIngestion" -> value.put("Status", "ENABLED");
                case "StopEventDataStoreIngestion" -> value.put("Status", "STOPPED_INGESTION");
                default -> throw new IllegalArgumentException(action);
            }
        }
        save(region, value);
        return "RestoreEventDataStore".equals(action) ? publicStore(value) : mapper.createObjectNode();
    }

    private void save(String region, ObjectNode value) {
        value.put("UpdatedTimestamp", System.currentTimeMillis() / 1000.0);
        stores.put(key(region, value.path("EventDataStoreArn").asText()), value);
    }

    private void requireActive(ObjectNode value) {
        if ("PENDING_DELETION".equals(value.path("Status").asText())) {
            throw new AwsException("InactiveEventDataStoreException", "Event data store is pending deletion.", 400);
        }
    }

    private ObjectNode find(String identifier, String region) {
        if (identifier == null || identifier.isBlank()) throw invalid("EventDataStore is required.");
        if (identifier.startsWith("arn:") && !identifier.matches(
                "arn:[^:]+:cloudtrail:[^:]+:[0-9]{12}:eventdatastore/[0-9a-fA-F-]{36}")) {
            throw new AwsException("EventDataStoreARNInvalidException", "Invalid event data store ARN.", 400);
        }
        ObjectNode found = stores.scan(k -> k.startsWith(region + ":")).stream()
                .filter(s -> identifier.equals(s.path("EventDataStoreArn").asText())
                        || identifier.equals(id(s.path("EventDataStoreArn").asText())))
                .findFirst().orElse(null);
        if (found == null) throw new AwsException("EventDataStoreNotFoundException",
                "Event data store not found: " + identifier, 400);
        return found;
    }

    private void requireAvailableName(String region, String name, String ownArn) {
        if (stores.scan(k -> k.startsWith(region + ":")).stream().anyMatch(s ->
                name.equals(s.path("Name").asText()) && !s.path("EventDataStoreArn").asText().equals(ownArn))) {
            throw new AwsException("EventDataStoreAlreadyExistsException", "Event data store name is already in use.", 400);
        }
    }

    private void validate(ObjectNode value) {
        if (!value.path("Name").isTextual() || !value.path("Name").asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{1,126}[A-Za-z0-9]")) {
            throw invalid("Name must be 3-128 characters with alphanumeric first and last characters.");
        }
        String billing = value.path("BillingMode").asText();
        if (!Set.of("EXTENDABLE_RETENTION_PRICING", "FIXED_RETENTION_PRICING").contains(billing)) {
            throw invalid("Invalid BillingMode.");
        }
        int retention = value.path("RetentionPeriod").asInt();
        if (!value.path("RetentionPeriod").isIntegralNumber() || !value.path("RetentionPeriod").canConvertToInt() || retention < 7
                || retention > ("FIXED_RETENTION_PRICING".equals(billing) ? 3653 : 2557)) {
            throw invalid("Invalid RetentionPeriod for the billing mode.");
        }
        for (String field : List.of("MultiRegionEnabled", "OrganizationEnabled", "TerminationProtectionEnabled")) {
            if (!value.path(field).isBoolean()) throw invalid(field + " must be a boolean.");
        }
        if (value.path("OrganizationEnabled").asBoolean()) {
            throw unsupported("Organization-wide Lake ingestion is not implemented.");
        }
        if (value.hasNonNull("KmsKeyId")) throw unsupported("KMS-encrypted Lake storage is not implemented.");
        JsonNode selectors = value.path("AdvancedEventSelectors");
        if (!selectors.isArray() || selectors.isEmpty() || selectors.size() > 5) {
            throw new AwsException("InvalidEventSelectorsException", "Provide one to five advanced selectors.", 400);
        }
        for (JsonNode selector : selectors) {
            JsonNode fields = selector.path("FieldSelectors");
            if (!fields.isArray() || fields.isEmpty()) throw invalid("FieldSelectors must not be empty.");
            boolean supportedCategory = false;
            for (JsonNode field : fields) {
                String name = field.path("Field").asText();
                if (!FIELDS.contains(name)) throw unsupported("Unsupported Lake selector field: " + name);
                int operators = 0;
                var names = field.fieldNames();
                while (names.hasNext()) {
                    String operator = names.next();
                    if ("Field".equals(operator)) continue;
                    if (!OPERATORS.contains(operator) || !field.path(operator).isArray() || field.path(operator).isEmpty()) {
                        throw invalid("Invalid selector operator: " + operator);
                    }
                    for (JsonNode operand : field.path(operator)) {
                        if (!operand.isTextual() || operand.asText().isEmpty()) throw invalid("Invalid selector value.");
                    }
                    operators++;
                }
                if (operators == 0) throw invalid("A selector field requires an operator.");
                if ("eventCategory".equals(name)) {
                    JsonNode categories = field.path("Equals");
                    supportedCategory = categories.isArray() && !categories.isEmpty();
                    for (JsonNode category : categories) {
                        if (!"Management".equals(category.asText())) {
                            throw unsupported("Lake currently ingests captured management events only.");
                        }
                    }
                }
            }
            if (!supportedCategory) throw unsupported("Lake selectors must explicitly select Management events.");
        }
    }

    private ObjectNode taggedStore(String arn, String region) {
        purgeExpired();
        try {
            return find(arn, region);
        } catch (AwsException error) {
            if ("EventDataStoreNotFoundException".equals(error.getErrorCode())) {
                throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 400);
            }
            throw error;
        }
    }

    public synchronized Map<String, String> tags(String arn, String region) {
        ObjectNode value = taggedStore(arn, region);
        Map<String, String> result = new java.util.LinkedHashMap<>();
        value.path("TagsList").forEach(t -> result.put(t.path("Key").asText(), t.path("Value").asText("")));
        return result;
    }

    public synchronized void updateTags(String arn, String region, Map<String, String> additions, List<String> removals) {
        ObjectNode value = taggedStore(arn, region).deepCopy();
        requireActive(value);
        Map<String, String> tags = tags(arn, region);
        tags.putAll(additions);
        removals.forEach(tags::remove);
        var array = value.putArray("TagsList");
        tags.forEach((k, v) -> array.addObject().put("Key", k).put("Value", v == null ? "" : v));
        validateTags(array);
        save(region, value);
    }

    private static void validateTags(JsonNode tags) {
        if (!tags.isArray()) throw invalid("TagsList must be an array.");
        if (tags.size() > 50) throw new AwsException("TagsLimitExceededException", "Maximum 50 tags.", 400);
        Set<String> keys = new java.util.HashSet<>();
        for (JsonNode tag : tags) {
            if (!tag.path("Key").isTextual() || tag.path("Key").asText().isEmpty()
                    || tag.path("Key").asText().length() > 128 || !keys.add(tag.path("Key").asText())
                    || (tag.has("Value") && (!tag.path("Value").isTextual() || tag.path("Value").asText().length() > 256))) {
                throw new AwsException("InvalidTagParameterException", "Invalid or duplicate tag.", 400);
            }
        }
    }

    public synchronized void ingest(ObjectNode event) {
        purgeExpired();
        for (String key : stores.keys()) {
            ObjectNode store = stores.get(key).orElse(null);
            if (store == null || !"ENABLED".equals(store.path("Status").asText())) continue;
            if (!store.path("MultiRegionEnabled").asBoolean()
                    && !key.startsWith(event.path("awsRegion").asText() + ":")) continue;
            if (matchesSelectors(store.path("AdvancedEventSelectors"), event)) {
                events.put(store.path("EventDataStoreArn").asText() + "/" + event.path("eventID").asText(), event.deepCopy());
            }
        }
    }

    // Collected records remain available while ingestion is stopped; SQL execution is separate.
    synchronized List<ObjectNode> collectedEvents(String arn, String region) {
        purgeExpired();
        find(arn, region);
        return events.scan(k -> k.startsWith(arn + "/")).stream().map(ObjectNode::deepCopy).toList();
    }

    static boolean matchesSelectors(JsonNode selectors, ObjectNode event) {
        for (JsonNode selector : selectors) {
            boolean matches = !selector.path("FieldSelectors").isEmpty();
            for (JsonNode field : selector.path("FieldSelectors")) {
                String path = field.path("Field").asText();
                List<String> values;
                if (path.startsWith("resources.")) {
                    var resources = new java.util.ArrayList<String>();
                    event.path("resources").forEach(r -> {
                        if (r.has(path.substring(10))) resources.add(r.path(path.substring(10)).asText());
                    });
                    values = resources;
                } else if ("userIdentity.arn".equals(path)) {
                    values = event.path("userIdentity").has("arn")
                            ? List.of(event.path("userIdentity").path("arn").asText()) : List.of();
                } else {
                    values = event.has(path) ? List.of(event.path(path).asText()) : List.of();
                }
                if (values.isEmpty()) { matches = false; break; }
                for (String operator : OPERATORS) {
                    if (!field.has(operator)) continue;
                    boolean negative = operator.startsWith("Not");
                    boolean any = false;
                    for (JsonNode operand : field.path(operator)) {
                        String expected = operand.asText();
                        any |= values.stream().anyMatch(v -> operator.endsWith("Equals") ? v.equals(expected)
                                : operator.endsWith("StartsWith") ? v.startsWith(expected) : v.endsWith(expected));
                    }
                    if (negative ? any : !any) matches = false;
                }
            }
            if (matches) return true;
        }
        return false;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        for (String key : stores.keys()) {
            ObjectNode value = stores.get(key).orElse(null);
            if (value == null) continue;
            String arn = value.path("EventDataStoreArn").asText();
            boolean deleted = value.has("_deleteAfter") && value.path("_deleteAfter").asLong() <= now;
            long cutoff = now - value.path("RetentionPeriod").asLong(366) * 86400000;
            for (String eventKey : events.keys()) {
                if (!eventKey.startsWith(arn + "/")) continue;
                ObjectNode event = events.get(eventKey).orElse(null);
                if (deleted || (event != null && Instant.parse(event.path("eventTime").asText()).toEpochMilli() < cutoff)) {
                    events.delete(eventKey);
                }
            }
            if (deleted) stores.delete(key);
        }
    }

    private ObjectNode publicStore(ObjectNode store) {
        ObjectNode copy = store.deepCopy();
        copy.remove(List.of("TagsList", "_deleteAfter"));
        return copy;
    }

    private static String id(String arn) { return arn.substring(arn.lastIndexOf('/') + 1); }
    private static String key(String region, String arn) { return region + ":" + id(arn); }
    private static AwsException invalid(String message) { return new AwsException("InvalidParameterException", message, 400); }
    private static AwsException unsupported(String message) { return new AwsException("UnsupportedOperationException", message, 400); }
}
