package io.floci.aws.services.location;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Amazon Location Service's resources: maps, place indexes, route calculators, geofence
 * collections, trackers, API keys and tracker consumers, with their tags. Each resource is stored
 * per account and Region under its name and answers in the API's shapes.
 */
@ApplicationScoped
public class LocationService implements Resettable {
    /** One resource kind: its name member, ARN type and storage file. */
    enum Kind {
        MAP("MapName", "map", "Map"),
        PLACE_INDEX("IndexName", "place-index", "Index"),
        ROUTE_CALCULATOR("CalculatorName", "route-calculator", "Calculator"),
        GEOFENCE_COLLECTION("CollectionName", "geofence-collection", "Collection"),
        TRACKER("TrackerName", "tracker", "Tracker"),
        KEY("KeyName", "api-key", "Key");

        final String nameMember;
        final String arnType;
        final String arnMemberPrefix;

        Kind(String nameMember, String arnType, String arnMemberPrefix) {
            this.nameMember = nameMember;
            this.arnType = arnType;
            this.arnMemberPrefix = arnMemberPrefix;
        }

        String arnMember() {
            return arnMemberPrefix + "Arn";
        }

        String label() {
            return switch (this) {
                case MAP -> "map";
                case PLACE_INDEX -> "place index";
                case ROUTE_CALCULATOR -> "route calculator";
                case GEOFENCE_COLLECTION -> "geofence collection";
                case TRACKER -> "tracker";
                case KEY -> "key";
            };
        }
    }

    private static final Pattern NAME = Pattern.compile("[-._\\w]{1,100}");
    private static final Set<String> DATA_SOURCES = Set.of("Esri", "Here", "Grab");
    private static final Set<String> MAP_STYLES = Set.of("VectorEsriDarkGrayCanvas", "RasterEsriImagery",
            "VectorEsriLightGrayCanvas", "VectorEsriTopographic", "VectorEsriStreets", "VectorEsriNavigation",
            "VectorHereContrast", "VectorHereExplore", "VectorHereExploreTruck", "RasterHereExploreSatellite",
            "HybridHereExploreSatellite", "VectorGrabStandardLight", "VectorGrabStandardDark",
            "VectorOpenDataStandardLight", "VectorOpenDataStandardDark", "VectorOpenDataVisualizationLight",
            "VectorOpenDataVisualizationDark");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<Kind, StorageBackend<String, ObjectNode>> stores = new LinkedHashMap<>();
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public LocationService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper mapper) {
        for (Kind kind : Kind.values()) {
            stores.put(kind, storageFactory.create("location", "location-" + kind.arnType + "s.json",
                    new TypeReference<Map<String, ObjectNode>>() {}));
        }
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    // ── Generic resource lifecycle ─────────────────────────────────────────

    public synchronized ObjectNode create(Kind kind, JsonNode request, String region) {
        String name = request.path(kind.nameMember).asText(null);
        validName(kind, name);
        StorageBackend<String, ObjectNode> store = stores.get(kind);
        if (store.get(key(region, name)).isPresent()) {
            throw new AwsException("ConflictException", "A " + kind.label() + " named " + name + " already exists.",
                    409);
        }
        String now = now();
        ObjectNode resource = mapper.createObjectNode();
        resource.put(kind.nameMember, name);
        resource.put(kind.arnMember(), arn(kind, region, name));
        resource.put("Description", request.path("Description").asText(""));
        resource.put("CreateTime", now);
        resource.put("UpdateTime", now);
        resource.set("Tags", tags(request.path("Tags")));
        switch (kind) {
            case MAP -> {
                JsonNode configuration = request.path("Configuration");
                String style = configuration.path("Style").asText(null);
                if (style == null || !MAP_STYLES.contains(style)) {
                    throw validation("Configuration.Style must be one of " + MAP_STYLES + ".");
                }
                resource.set("Configuration", configuration.deepCopy());
                resource.put("DataSource", mapDataSource(style));
            }
            case PLACE_INDEX -> {
                resource.put("DataSource", dataSource(request));
                ObjectNode config = resource.putObject("DataSourceConfiguration");
                config.put("IntendedUse", request.path("DataSourceConfiguration").path("IntendedUse")
                        .asText("SingleUse"));
            }
            case ROUTE_CALCULATOR -> resource.put("DataSource", dataSource(request));
            case GEOFENCE_COLLECTION -> copyIfPresent(request, resource, "KmsKeyId", "PricingPlanDataSource");
            case TRACKER -> {
                copyIfPresent(request, resource, "KmsKeyId", "PricingPlanDataSource");
                resource.put("PositionFiltering", positionFiltering(request.path("PositionFiltering").asText(null),
                        "TimeBased"));
                resource.put("EventBridgeEnabled", request.path("EventBridgeEnabled").asBoolean(false));
                resource.put("KmsKeyEnableGeospatialQueries",
                        request.path("KmsKeyEnableGeospatialQueries").asBoolean(false));
            }
            case KEY -> {
                resource.set("Restrictions", restrictions(request.path("Restrictions")));
                resource.put("ExpireTime", expireTime(request));
                resource.put("Key", "v1.public." + token(160));
            }
        }
        if (request.hasNonNull("PricingPlan")) {
            resource.put("PricingPlan", request.get("PricingPlan").asText());
        }
        store.put(key(region, name), resource);
        ObjectNode response = mapper.createObjectNode();
        response.put(kind.nameMember, name);
        response.put(kind.arnMember(), resource.path(kind.arnMember()).asText());
        response.put("CreateTime", now);
        if (kind == Kind.KEY) {
            response.put("Key", resource.path("Key").asText());
        }
        return response;
    }

    public ObjectNode describe(Kind kind, String name, String region) {
        ObjectNode resource = require(kind, name, region).deepCopy();
        resource.remove("Consumers");
        return resource;
    }

    public synchronized ObjectNode update(Kind kind, String name, JsonNode request, String region) {
        ObjectNode resource = require(kind, name, region);
        if (request.hasNonNull("Description")) {
            resource.put("Description", request.get("Description").asText());
        }
        if (request.hasNonNull("PricingPlan")) {
            resource.put("PricingPlan", request.get("PricingPlan").asText());
        }
        switch (kind) {
            case MAP -> {
                JsonNode update = request.path("ConfigurationUpdate");
                ObjectNode configuration = (ObjectNode) resource.get("Configuration");
                if (update.has("PoliticalView")) {
                    String view = update.get("PoliticalView").asText();
                    if (view.isEmpty()) {
                        configuration.remove("PoliticalView");
                    } else {
                        configuration.put("PoliticalView", view);
                    }
                }
                if (update.has("CustomLayers")) {
                    configuration.set("CustomLayers", update.get("CustomLayers").deepCopy());
                }
            }
            case PLACE_INDEX -> {
                if (request.path("DataSourceConfiguration").hasNonNull("IntendedUse")) {
                    ((ObjectNode) resource.get("DataSourceConfiguration")).put("IntendedUse",
                            request.path("DataSourceConfiguration").get("IntendedUse").asText());
                }
            }
            case TRACKER -> {
                if (request.hasNonNull("PositionFiltering")) {
                    resource.put("PositionFiltering", positionFiltering(request.get("PositionFiltering").asText(),
                            null));
                }
                copyIfPresent(request, resource, "EventBridgeEnabled", "KmsKeyEnableGeospatialQueries",
                        "PricingPlanDataSource");
            }
            case GEOFENCE_COLLECTION -> copyIfPresent(request, resource, "PricingPlanDataSource");
            case KEY -> {
                if (request.has("Restrictions")) {
                    resource.set("Restrictions", restrictions(request.get("Restrictions")));
                }
                if (request.has("ExpireTime") || request.has("NoExpiry")) {
                    resource.put("ExpireTime", expireTime(request));
                }
            }
            default -> { }
        }
        String now = now();
        resource.put("UpdateTime", now);
        stores.get(kind).put(key(region, name), resource);
        ObjectNode response = mapper.createObjectNode();
        response.put(kind.nameMember, name);
        response.put(kind.arnMember(), resource.path(kind.arnMember()).asText());
        response.put("UpdateTime", now);
        return response;
    }

    public synchronized void delete(Kind kind, String name, String region) {
        require(kind, name, region);
        stores.get(kind).delete(key(region, name));
    }

    /** {@code ListMaps} and its siblings: summaries, newest name order, paginated. */
    public ObjectNode list(Kind kind, JsonNode request, String region) {
        int max = request.path("MaxResults").asInt(100);
        if (max < 1 || max > 100) {
            throw validation("MaxResults must be between 1 and 100.");
        }
        String status = request.path("Filter").path("KeyStatus").asText(null);
        List<ObjectNode> all = stores.get(kind).scan(k -> k.startsWith(region + "::")).stream()
                .filter(resource -> status == null || status.equals(keyStatus(resource)))
                .sorted(Comparator.comparing(resource -> resource.path(kind.nameMember).asText()))
                .toList();
        int start = offset(request.path("NextToken").asText(null));
        int end = Math.min(all.size(), start + max);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        for (ObjectNode resource : all.subList(Math.min(start, end), end)) {
            ObjectNode entry = entries.addObject();
            entry.put(kind.nameMember, resource.path(kind.nameMember).asText());
            for (String field : List.of("Description", "DataSource", "PricingPlan", "PricingPlanDataSource",
                    "CreateTime", "UpdateTime", "ExpireTime", "Restrictions")) {
                if (resource.has(field)) {
                    entry.set(field, resource.get(field).deepCopy());
                }
            }
        }
        if (end < all.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return response;
    }

    private static String keyStatus(ObjectNode key) {
        return Instant.parse(key.path("ExpireTime").asText()).isAfter(Instant.now()) ? "Active" : "Expired";
    }

    ObjectNode require(Kind kind, String name, String region) {
        validName(kind, name);
        return stores.get(kind).get(key(region, name)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The " + kind.label() + " " + name + " was not found.", 404));
    }

    /** The resource the ARN names, or {@code ResourceNotFoundException}. */
    ObjectNode byArn(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "geo")) {
            throw validation("The ARN " + arn + " is not an Amazon Location resource ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String[] parts = parsed.resource().split("/", 2);
        for (Kind kind : Kind.values()) {
            if (kind.arnType.equals(parts[0]) && parts.length == 2) {
                return require(kind, parts[1], parsed.region().isEmpty() ? region : parsed.region());
            }
        }
        throw new AwsException("ResourceNotFoundException", "The resource " + arn + " was not found.", 404);
    }

    // ── Tracker consumers ──────────────────────────────────────────────────

    public synchronized void associateConsumer(String trackerName, String consumerArn, String region) {
        ObjectNode tracker = require(Kind.TRACKER, trackerName, region);
        ObjectNode collection = byArn(consumerArn, region);
        if (!collection.has(Kind.GEOFENCE_COLLECTION.arnMember())) {
            throw validation("A tracker's consumer must be a geofence collection.");
        }
        ArrayNode consumers = tracker.withArray("Consumers");
        for (JsonNode existing : consumers) {
            if (existing.asText().equals(consumerArn)) {
                throw new AwsException("ConflictException", "The consumer is already associated with the tracker.",
                        409);
            }
        }
        if (consumers.size() >= 5) {
            throw new AwsException("ServiceQuotaExceededException", "A tracker can have at most 5 consumers.", 402);
        }
        consumers.add(consumerArn);
        stores.get(Kind.TRACKER).put(key(region, trackerName), tracker);
    }

    public synchronized void disassociateConsumer(String trackerName, String consumerArn, String region) {
        ObjectNode tracker = require(Kind.TRACKER, trackerName, region);
        ArrayNode consumers = tracker.withArray("Consumers");
        for (int i = 0; i < consumers.size(); i++) {
            if (consumers.get(i).asText().equals(consumerArn)) {
                consumers.remove(i);
                stores.get(Kind.TRACKER).put(key(region, trackerName), tracker);
                return;
            }
        }
        throw new AwsException("ResourceNotFoundException", "The consumer " + consumerArn
                + " is not associated with tracker " + trackerName + ".", 404);
    }

    public ObjectNode listConsumers(String trackerName, String region) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("ConsumerArns");
        require(Kind.TRACKER, trackerName, region).withArray("Consumers").forEach(list::add);
        return response;
    }

    /** The geofence collections linked to a tracker that still exist. */
    List<String> consumerCollections(ObjectNode tracker, String region) {
        List<String> names = new ArrayList<>();
        for (JsonNode arn : tracker.withArray("Consumers")) {
            String resource = AwsArnUtils.parse(arn.asText()).resource();
            String name = resource.substring(resource.indexOf('/') + 1);
            if (stores.get(Kind.GEOFENCE_COLLECTION).get(key(region, name)).isPresent()) {
                names.add(name);
            }
        }
        return names;
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    public Map<String, String> listTags(String arn, String region) {
        Map<String, String> tags = new LinkedHashMap<>();
        byArn(arn, region).path("Tags").fields().forEachRemaining(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        return tags;
    }

    public synchronized void tagResource(String arn, Map<String, String> tags, String region) {
        ObjectNode resource = byArn(arn, region);
        ObjectNode current = (ObjectNode) resource.get("Tags");
        (tags == null ? Map.<String, String>of() : tags).forEach(current::put);
        if (current.size() > 50) {
            throw validation("A resource can have at most 50 tags.");
        }
        save(resource, region);
    }

    public synchronized void untagResource(String arn, List<String> keys, String region) {
        ObjectNode resource = byArn(arn, region);
        ObjectNode current = (ObjectNode) resource.get("Tags");
        (keys == null ? List.<String>of() : keys).forEach(current::remove);
        save(resource, region);
    }

    private void save(ObjectNode resource, String region) {
        for (Kind kind : Kind.values()) {
            if (resource.has(kind.nameMember) && resource.has(kind.arnMember())) {
                stores.get(kind).put(key(region, resource.path(kind.nameMember).asText()), resource);
                return;
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    String arn(Kind kind, String region, String name) {
        return regionResolver.buildArn("geo", region, kind.arnType + "/" + name);
    }

    private static void validName(Kind kind, String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw validation(kind.nameMember + " must be 1-100 letters, digits, hyphens, periods or underscores.");
        }
    }

    private static String dataSource(JsonNode request) {
        String source = request.path("DataSource").asText(null);
        if (source == null || !DATA_SOURCES.contains(source)) {
            throw validation("DataSource must be one of " + DATA_SOURCES + ".");
        }
        return source;
    }

    private static String mapDataSource(String style) {
        if (style.contains("Esri")) {
            return "Esri";
        }
        if (style.contains("Here")) {
            return "Here";
        }
        return style.contains("Grab") ? "Grab" : "OpenData";
    }

    private static String positionFiltering(String value, String fallback) {
        String filtering = value == null ? fallback : value;
        if (!Set.of("TimeBased", "DistanceBased", "AccuracyBased").contains(filtering)) {
            throw validation("PositionFiltering must be TimeBased, DistanceBased or AccuracyBased.");
        }
        return filtering;
    }

    private ObjectNode restrictions(JsonNode restrictions) {
        JsonNode actions = restrictions.path("AllowActions");
        JsonNode resources = restrictions.path("AllowResources");
        if (!actions.isArray() || actions.isEmpty() || !resources.isArray() || resources.isEmpty()) {
            throw validation("Restrictions need AllowActions and AllowResources.");
        }
        for (JsonNode action : actions) {
            if (!action.asText().matches("(geo|geo-maps|geo-places|geo-routes):\\w*\\*?")) {
                throw validation("AllowActions entry " + action.asText() + " is not a geo action.");
            }
        }
        for (JsonNode resource : resources) {
            String value = resource.asText();
            if (!value.equals("*") && !value.startsWith("arn:")) { // partition-literal: wildcard ARNs (arn:aws:geo:*:*:map/*) that AwsArnUtils rejects
                throw validation("AllowResources entry " + value + " is not an ARN.");
            }
        }
        return (ObjectNode) restrictions.deepCopy();
    }

    private static String expireTime(JsonNode request) {
        boolean noExpiry = request.path("NoExpiry").asBoolean(false);
        String expire = request.path("ExpireTime").asText(null);
        if (noExpiry == (expire != null)) {
            throw validation("Specify exactly one of ExpireTime and NoExpiry.");
        }
        return noExpiry ? "9999-12-31T23:59:59Z" : Instant.parse(expire).toString();
    }

    private ObjectNode tags(JsonNode tags) {
        ObjectNode result = mapper.createObjectNode();
        if (tags.isObject()) {
            if (tags.size() > 50) {
                throw validation("A resource can have at most 50 tags.");
            }
            result.setAll((ObjectNode) tags);
        }
        return result;
    }

    private static void copyIfPresent(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }

    static String token(int bits) {
        byte[] bytes = new byte[bits / 8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String now() {
        return Instant.now().toString();
    }

    static int offset(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(token));
        } catch (NumberFormatException e) {
            throw validation("Invalid NextToken.");
        }
    }

    static String key(String region, String name) {
        return region + "::" + name;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    @Override
    public void clear() {
        stores.values().forEach(StorageBackend::clear);
    }
}
