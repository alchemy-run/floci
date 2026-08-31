package io.github.hectorvent.floci.services.iotsitewise;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iotsitewise.model.Asset;
import io.github.hectorvent.floci.services.iotsitewise.model.AssetModel;
import io.github.hectorvent.floci.services.iotsitewise.model.Gateway;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWS IoT SiteWise restJson1 — asset models, assets, gateways, and property data plane.
 *
 * <p>Provisioning is instantaneous ({@code ACTIVE}). Tag APIs share {@code /tags} via
 * {@link TagHandler} using ARN service {@code iotsitewise}.
 */
@ApplicationScoped
public class IotSiteWiseService implements TagHandler {

    static final String SERVICE = "iotsitewise";
    private static final Pattern ASSET_QUERY = Pattern.compile(
            "(?i)select\\s+(.+?)\\s+from\\s+asset(?:\\s+where\\s+asset_id\\s*=\\s*'([^']+)')?");

    private final StorageBackend<String, AssetModel> models;
    private final StorageBackend<String, Asset> assets;
    private final StorageBackend<String, Gateway> gateways;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, List<ObjectNode>> values = new ConcurrentHashMap<>();

    @Inject
    public IotSiteWiseService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create(SERVICE, "iotsitewise-asset-models.json",
                        new TypeReference<Map<String, AssetModel>>() {
                        }),
                storageFactory.create(SERVICE, "iotsitewise-assets.json",
                        new TypeReference<Map<String, Asset>>() {
                        }),
                storageFactory.create(SERVICE, "iotsitewise-gateways.json",
                        new TypeReference<Map<String, Gateway>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    IotSiteWiseService(
            StorageBackend<String, AssetModel> models,
            StorageBackend<String, Asset> assets,
            StorageBackend<String, Gateway> gateways,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.models = models;
        this.assets = assets;
        this.gateways = gateways;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized AssetModel createAssetModel(String region, JsonNode request) {
        requireObject(request);
        String name = requireText(request, "assetModelName");
        AssetModel existing = findModelByName(region, name);
        if (existing != null) {
            throw alreadyExists(existing.getId(), existing.getArn());
        }
        long now = Instant.now().getEpochSecond();
        String id = optionalText(request, "assetModelId");
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (models.get(key(region, id)).isPresent()) {
            AssetModel clash = models.get(key(region, id)).orElseThrow();
            throw alreadyExists(clash.getId(), clash.getArn());
        }
        AssetModel model = new AssetModel();
        model.setId(id);
        model.setArn(arn(region, "asset-model/" + id));
        model.setName(name);
        model.setType(optionalTextOr(request, "assetModelType", "ASSET_MODEL"));
        model.setDescription(optionalTextOr(request, "assetModelDescription", ""));
        model.setProperties(withIds(request.get("assetModelProperties")));
        model.setHierarchies(withIds(request.get("assetModelHierarchies")));
        model.setCompositeModels(withIds(request.get("assetModelCompositeModels")));
        model.setRegion(region);
        model.setCreationDate(now);
        model.setLastUpdateDate(now);
        model.setTags(readTags(request));
        models.put(key(region, id), model);
        return model;
    }

    public AssetModel describeAssetModel(String region, String assetModelId) {
        return requireModel(region, assetModelId);
    }

    public synchronized AssetModel updateAssetModel(String region, String assetModelId, JsonNode request) {
        requireObject(request);
        AssetModel model = requireModel(region, assetModelId);
        String name = requireText(request, "assetModelName");
        AssetModel named = findModelByName(region, name);
        if (named != null && !named.getId().equals(model.getId())) {
            throw alreadyExists(named.getId(), named.getArn());
        }
        model.setName(name);
        if (request.has("assetModelDescription")) {
            model.setDescription(optionalTextOr(request, "assetModelDescription", ""));
        }
        if (request.has("assetModelProperties")) {
            model.setProperties(withIds(request.get("assetModelProperties")));
        }
        if (request.has("assetModelHierarchies")) {
            model.setHierarchies(withIds(request.get("assetModelHierarchies")));
        }
        if (request.has("assetModelCompositeModels")) {
            model.setCompositeModels(withIds(request.get("assetModelCompositeModels")));
        }
        model.setLastUpdateDate(Instant.now().getEpochSecond());
        models.put(key(region, model.getId()), model);
        return model;
    }

    public synchronized AssetModel deleteAssetModel(String region, String assetModelId) {
        AssetModel model = requireModel(region, assetModelId);
        boolean inUse = listAssets(region, assetModelId).stream()
                .anyMatch(asset -> model.getId().equals(asset.getModelId()));
        if (inUse) {
            throw conflicting(model.getId(), model.getArn(),
                    "Asset model has associated assets and cannot be deleted.");
        }
        models.delete(key(region, assetModelId));
        return model;
    }

    public List<AssetModel> listAssetModels(String region) {
        List<AssetModel> items = models.scan(k -> k.startsWith(region + "::"));
        items.sort(Comparator.comparing(AssetModel::getName, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized Asset createAsset(String region, JsonNode request) {
        requireObject(request);
        String name = requireText(request, "assetName");
        String modelId = requireText(request, "assetModelId");
        AssetModel model = requireModel(region, modelId);
        Asset existing = findAssetByName(region, modelId, name);
        if (existing != null) {
            throw alreadyExists(existing.getId(), existing.getArn());
        }
        long now = Instant.now().getEpochSecond();
        String id = optionalText(request, "assetId");
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (assets.get(key(region, id)).isPresent()) {
            Asset clash = assets.get(key(region, id)).orElseThrow();
            throw alreadyExists(clash.getId(), clash.getArn());
        }
        Asset asset = new Asset();
        asset.setId(id);
        asset.setArn(arn(region, "asset/" + id));
        asset.setName(name);
        asset.setModelId(modelId);
        asset.setDescription(optionalTextOr(request, "assetDescription", ""));
        asset.setProperties(assetPropertiesFrom(model));
        asset.setHierarchies(emptyArray());
        asset.setRegion(region);
        asset.setCreationDate(now);
        asset.setLastUpdateDate(now);
        asset.setTags(readTags(request));
        assets.put(key(region, id), asset);
        return asset;
    }

    public Asset describeAsset(String region, String assetId) {
        return requireAsset(region, assetId);
    }

    public synchronized Asset updateAsset(String region, String assetId, JsonNode request) {
        requireObject(request);
        Asset asset = requireAsset(region, assetId);
        String name = requireText(request, "assetName");
        Asset named = findAssetByName(region, asset.getModelId(), name);
        if (named != null && !named.getId().equals(asset.getId())) {
            throw alreadyExists(named.getId(), named.getArn());
        }
        asset.setName(name);
        if (request.has("assetDescription")) {
            asset.setDescription(optionalTextOr(request, "assetDescription", ""));
        }
        asset.setLastUpdateDate(Instant.now().getEpochSecond());
        assets.put(key(region, asset.getId()), asset);
        return asset;
    }

    public synchronized Asset deleteAsset(String region, String assetId) {
        Asset asset = requireAsset(region, assetId);
        assets.delete(key(region, assetId));
        String prefix = region + "::" + assetId + "::";
        values.keySet().removeIf(k -> k.startsWith(prefix));
        return asset;
    }

    public List<Asset> listAssets(String region, String assetModelId) {
        List<Asset> items = assets.scan(k -> k.startsWith(region + "::"));
        if (assetModelId != null && !assetModelId.isBlank()) {
            requireModel(region, assetModelId);
            items.removeIf(asset -> !assetModelId.equals(asset.getModelId()));
        }
        items.sort(Comparator.comparing(Asset::getName, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized Gateway createGateway(String region, JsonNode request) {
        requireObject(request);
        String name = requireText(request, "gatewayName");
        JsonNode platform = request.get("gatewayPlatform");
        if (platform == null || !platform.isObject()) {
            throw invalid("gatewayPlatform is required.");
        }
        Gateway existing = findGatewayByName(region, name);
        if (existing != null) {
            throw alreadyExists(existing.getId(), existing.getArn());
        }
        long now = Instant.now().getEpochSecond();
        String id = UUID.randomUUID().toString();
        Gateway gateway = new Gateway();
        gateway.setId(id);
        gateway.setArn(arn(region, "gateway/" + id));
        gateway.setName(name);
        gateway.setPlatform(platform.deepCopy());
        gateway.setVersion(optionalText(request, "gatewayVersion"));
        gateway.setRegion(region);
        gateway.setCreationDate(now);
        gateway.setLastUpdateDate(now);
        gateway.setTags(readTags(request));
        gateways.put(key(region, id), gateway);
        return gateway;
    }

    public Gateway describeGateway(String region, String gatewayId) {
        return requireGateway(region, gatewayId);
    }

    public synchronized Gateway updateGateway(String region, String gatewayId, JsonNode request) {
        requireObject(request);
        Gateway gateway = requireGateway(region, gatewayId);
        String name = requireText(request, "gatewayName");
        Gateway named = findGatewayByName(region, name);
        if (named != null && !named.getId().equals(gateway.getId())) {
            throw alreadyExists(named.getId(), named.getArn());
        }
        gateway.setName(name);
        gateway.setLastUpdateDate(Instant.now().getEpochSecond());
        gateways.put(key(region, gateway.getId()), gateway);
        return gateway;
    }

    public synchronized void deleteGateway(String region, String gatewayId) {
        requireGateway(region, gatewayId);
        gateways.delete(key(region, gatewayId));
    }

    public List<Gateway> listGateways(String region) {
        List<Gateway> items = gateways.scan(k -> k.startsWith(region + "::"));
        items.sort(Comparator.comparing(Gateway::getName, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public ObjectNode listAssetProperties(String region, String assetId) {
        Asset asset = requireAsset(region, assetId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("assetPropertySummaries");
        JsonNode properties = asset.getProperties();
        if (properties != null && properties.isArray()) {
            for (JsonNode property : properties) {
                ObjectNode summary = summaries.addObject();
                summary.put("id", property.path("id").asText());
                if (property.hasNonNull("unit")) {
                    summary.put("unit", property.get("unit").asText());
                }
                if (property.hasNonNull("externalId")) {
                    summary.put("externalId", property.get("externalId").asText());
                }
                ArrayNode path = summary.putArray("path");
                ObjectNode segment = path.addObject();
                segment.put("id", property.path("id").asText());
                segment.put("name", property.path("name").asText());
            }
        }
        return response;
    }

    public ObjectNode batchPut(String region, JsonNode request) {
        requireObject(request);
        JsonNode entries = request.get("entries");
        if (entries == null || !entries.isArray()) {
            throw invalid("entries is required.");
        }
        ArrayNode errorEntries = objectMapper.createArrayNode();
        for (JsonNode entry : entries) {
            String entryId = optionalText(entry, "entryId");
            if (entryId == null || entryId.isBlank()) {
                throw invalid("entryId is required.");
            }
            String assetId = optionalText(entry, "assetId");
            String propertyId = optionalText(entry, "propertyId");
            if (assetId == null || propertyId == null) {
                errorEntries.add(putError(entryId, "InvalidRequestException", "assetId and propertyId are required."));
                continue;
            }
            Asset asset;
            try {
                asset = requireAsset(region, assetId);
            } catch (AwsException e) {
                errorEntries.add(putError(entryId, "ResourceNotFoundException", e.getMessage()));
                continue;
            }
            if (!hasProperty(asset, propertyId)) {
                errorEntries.add(putError(entryId, "ResourceNotFoundException", "Asset property not found."));
                continue;
            }
            JsonNode propertyValues = entry.get("propertyValues");
            if (propertyValues == null || !propertyValues.isArray()) {
                errorEntries.add(putError(entryId, "InvalidRequestException", "propertyValues is required."));
                continue;
            }
            List<ObjectNode> stored = values.computeIfAbsent(valueKey(region, assetId, propertyId), k -> new ArrayList<>());
            synchronized (stored) {
                for (JsonNode point : propertyValues) {
                    ObjectNode tqv = readTqv(point);
                    long time = tqv.path("timestamp").path("timeInSeconds").asLong();
                    stored.removeIf(existing -> existing.path("timestamp").path("timeInSeconds").asLong() == time);
                    stored.add(tqv);
                }
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("errorEntries", errorEntries);
        return response;
    }

    public ObjectNode getLatest(String region, String assetId, String propertyId) {
        requireAsset(region, assetId);
        if (propertyId == null || propertyId.isBlank()) {
            throw invalid("propertyId is required.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode latest = latest(region, assetId, propertyId);
        if (latest != null) {
            response.set("propertyValue", latest);
        }
        return response;
    }

    public ObjectNode getHistory(
            String region, String assetId, String propertyId, String startDate, String endDate, String timeOrdering) {
        requireAsset(region, assetId);
        if (propertyId == null || propertyId.isBlank()) {
            throw invalid("propertyId is required.");
        }
        long start = parseEpoch(startDate, 0);
        long end = parseEpoch(endDate, Instant.now().getEpochSecond());
        List<ObjectNode> points = history(region, assetId, propertyId, start, end);
        points.sort(Comparator.comparingLong((ObjectNode n) -> n.path("timestamp").path("timeInSeconds").asLong()));
        if (timeOrdering != null && timeOrdering.equalsIgnoreCase("DESCENDING")) {
            points = new ArrayList<>(points.reversed());
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode history = response.putArray("assetPropertyValueHistory");
        for (ObjectNode point : points) {
            history.add(point.deepCopy());
        }
        return response;
    }

    public ObjectNode getAggregates(
            String region,
            String assetId,
            String propertyId,
            List<String> aggregateTypes,
            String startDate,
            String endDate) {
        requireAsset(region, assetId);
        if (propertyId == null || propertyId.isBlank()) {
            throw invalid("propertyId is required.");
        }
        long start = parseEpoch(startDate, 0);
        long end = parseEpoch(endDate, Instant.now().getEpochSecond());
        List<ObjectNode> points = history(region, assetId, propertyId, start, end);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode aggregated = response.putArray("aggregatedValues");
        List<Double> numbers = new ArrayList<>();
        for (ObjectNode point : points) {
            Double number = numeric(point.get("value"));
            if (number != null) {
                numbers.add(number);
            }
        }
        if (numbers.isEmpty()) {
            return response;
        }
        ObjectNode value = objectMapper.createObjectNode();
        List<String> types = aggregateTypes == null ? List.of() : aggregateTypes;
        double sum = 0;
        double min = numbers.getFirst();
        double max = numbers.getFirst();
        for (double number : numbers) {
            sum += number;
            min = Math.min(min, number);
            max = Math.max(max, number);
        }
        if (types.isEmpty() || types.contains("AVERAGE")) {
            value.put("average", sum / numbers.size());
        }
        if (types.contains("COUNT")) {
            value.put("count", numbers.size());
        }
        if (types.contains("MAXIMUM")) {
            value.put("maximum", max);
        }
        if (types.contains("MINIMUM")) {
            value.put("minimum", min);
        }
        if (types.contains("SUM")) {
            value.put("sum", sum);
        }
        ObjectNode entry = aggregated.addObject();
        entry.put("timestamp", start);
        entry.put("quality", "GOOD");
        entry.set("value", value);
        return response;
    }

    public ObjectNode getInterpolated(
            String region,
            String assetId,
            String propertyId,
            String startTimeInSeconds,
            String endTimeInSeconds,
            String quality) {
        requireAsset(region, assetId);
        if (propertyId == null || propertyId.isBlank()) {
            throw invalid("propertyId is required.");
        }
        long start = parseEpoch(startTimeInSeconds, 0);
        long end = parseEpoch(endTimeInSeconds, Instant.now().getEpochSecond());
        List<ObjectNode> points = history(region, assetId, propertyId, start, end);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode interpolated = response.putArray("interpolatedAssetPropertyValues");
        for (ObjectNode point : points) {
            String pointQuality = point.path("quality").asText("GOOD");
            if (quality != null && !quality.isBlank() && !quality.equals(pointQuality)) {
                continue;
            }
            ObjectNode entry = interpolated.addObject();
            entry.set("timestamp", point.get("timestamp").deepCopy());
            entry.set("value", point.get("value").deepCopy());
        }
        return response;
    }

    public ObjectNode executeQuery(String region, JsonNode request) {
        requireObject(request);
        String statement = requireText(request, "queryStatement");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode columns = response.putArray("columns");
        ArrayNode rows = response.putArray("rows");
        Matcher matcher = ASSET_QUERY.matcher(statement.trim());
        if (!matcher.find()) {
            return response;
        }
        List<String> names = new ArrayList<>();
        for (String part : matcher.group(1).split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            names.add("asset_id");
            names.add("asset_name");
        }
        for (String name : names) {
            ObjectNode column = columns.addObject();
            column.put("name", name);
            ObjectNode type = column.putObject("type");
            type.put("scalarType", "STRING");
        }
        String assetIdFilter = matcher.group(2);
        for (Asset asset : listAssets(region, null)) {
            if (assetIdFilter != null && !assetIdFilter.equals(asset.getId())) {
                continue;
            }
            ObjectNode row = rows.addObject();
            ArrayNode data = row.putArray("data");
            for (String name : names) {
                ObjectNode datum = data.addObject();
                String value = switch (name.toLowerCase(Locale.ROOT)) {
                    case "asset_id", "assetid" -> asset.getId();
                    case "asset_name", "assetname" -> asset.getName();
                    case "asset_arn", "assetarn" -> asset.getArn();
                    default -> null;
                };
                if (value == null) {
                    datum.put("nullValue", true);
                } else {
                    datum.put("scalarValue", value);
                }
            }
        }
        return response;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(tagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = tagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        tagged.setTags(current);
        tagged.store();
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = tagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        tagged.setTags(current);
        tagged.store();
    }

    private Tagged tagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw notFound("Resource not found: " + arn);
        }
        if (!SERVICE.equals(parsed.service())) {
            throw notFound("Resource not found: " + arn);
        }
        String lookupRegion = parsed.region() == null || parsed.region().isEmpty() ? region : parsed.region();
        String resource = parsed.resource();
        if (resource != null && resource.startsWith("asset-model/")) {
            AssetModel model = requireModel(lookupRegion, resource.substring("asset-model/".length()));
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return model.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    model.setTags(tags);
                    model.setLastUpdateDate(Instant.now().getEpochSecond());
                }

                @Override
                public void store() {
                    models.put(key(lookupRegion, model.getId()), model);
                }
            };
        }
        if (resource != null && resource.startsWith("asset/")) {
            Asset asset = requireAsset(lookupRegion, resource.substring("asset/".length()));
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return asset.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    asset.setTags(tags);
                    asset.setLastUpdateDate(Instant.now().getEpochSecond());
                }

                @Override
                public void store() {
                    assets.put(key(lookupRegion, asset.getId()), asset);
                }
            };
        }
        if (resource != null && resource.startsWith("gateway/")) {
            Gateway gateway = requireGateway(lookupRegion, resource.substring("gateway/".length()));
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return gateway.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    gateway.setTags(tags);
                    gateway.setLastUpdateDate(Instant.now().getEpochSecond());
                }

                @Override
                public void store() {
                    gateways.put(key(lookupRegion, gateway.getId()), gateway);
                }
            };
        }
        throw notFound("Resource not found: " + arn);
    }

    private interface Tagged {
        Map<String, String> tags();

        void setTags(Map<String, String> tags);

        void store();
    }

    private AssetModel requireModel(String region, String id) {
        if (id == null || id.isBlank()) {
            throw invalid("assetModelId is required.");
        }
        return models.get(key(region, id)).orElseThrow(
                () -> notFound("Asset model " + id + " not found."));
    }

    private Asset requireAsset(String region, String id) {
        if (id == null || id.isBlank()) {
            throw invalid("assetId is required.");
        }
        return assets.get(key(region, id)).orElseThrow(
                () -> notFound("Asset " + id + " not found."));
    }

    private Gateway requireGateway(String region, String id) {
        if (id == null || id.isBlank()) {
            throw invalid("gatewayId is required.");
        }
        return gateways.get(key(region, id)).orElseThrow(
                () -> notFound("Gateway " + id + " not found."));
    }

    private AssetModel findModelByName(String region, String name) {
        for (AssetModel model : listAssetModels(region)) {
            if (name.equals(model.getName())) {
                return model;
            }
        }
        return null;
    }

    private Asset findAssetByName(String region, String modelId, String name) {
        for (Asset asset : assets.scan(k -> k.startsWith(region + "::"))) {
            if (modelId.equals(asset.getModelId()) && name.equals(asset.getName())) {
                return asset;
            }
        }
        return null;
    }

    private Gateway findGatewayByName(String region, String name) {
        for (Gateway gateway : listGateways(region)) {
            if (name.equals(gateway.getName())) {
                return gateway;
            }
        }
        return null;
    }

    private ArrayNode withIds(JsonNode array) {
        ArrayNode out = objectMapper.createArrayNode();
        if (array == null || array.isNull() || !array.isArray()) {
            return out;
        }
        for (JsonNode item : array) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ObjectNode copy = item.deepCopy();
            if (!copy.hasNonNull("id") || copy.get("id").asText().isBlank()) {
                copy.put("id", UUID.randomUUID().toString());
            }
            if (copy.has("properties")) {
                copy.set("properties", withIds(copy.get("properties")));
            }
            out.add(copy);
        }
        return out;
    }

    private ArrayNode assetPropertiesFrom(AssetModel model) {
        ArrayNode out = objectMapper.createArrayNode();
        JsonNode properties = model.getProperties();
        if (properties == null || !properties.isArray()) {
            return out;
        }
        for (JsonNode property : properties) {
            if (property == null || !property.isObject()) {
                continue;
            }
            ObjectNode copy = objectMapper.createObjectNode();
            copy.put("id", property.path("id").asText(UUID.randomUUID().toString()));
            if (property.hasNonNull("externalId")) {
                copy.put("externalId", property.get("externalId").asText());
            }
            copy.put("name", property.path("name").asText(""));
            copy.put("dataType", property.path("dataType").asText("STRING"));
            if (property.hasNonNull("dataTypeSpec")) {
                copy.put("dataTypeSpec", property.get("dataTypeSpec").asText());
            }
            if (property.hasNonNull("unit")) {
                copy.put("unit", property.get("unit").asText());
            }
            ArrayNode path = copy.putArray("path");
            ObjectNode segment = path.addObject();
            segment.put("id", copy.get("id").asText());
            segment.put("name", copy.get("name").asText());
            out.add(copy);
        }
        return out;
    }

    private ArrayNode emptyArray() {
        return objectMapper.createArrayNode();
    }

    private Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode node = request.get("tags");
        if (node == null || node.isNull() || !node.isObject()) {
            return tags;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && !value.isNull()) {
                tags.put(entry.getKey(), value.asText());
            }
        });
        return tags;
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), resource).toString();
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw invalid("Request body must be a JSON object.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        if (!request.hasNonNull(field)) {
            return null;
        }
        return request.get(field).asText();
    }

    private static String optionalTextOr(JsonNode request, String field, String fallback) {
        String value = optionalText(request, field);
        return value == null ? fallback : value;
    }

    private boolean hasProperty(Asset asset, String propertyId) {
        JsonNode properties = asset.getProperties();
        if (properties == null || !properties.isArray()) {
            return false;
        }
        for (JsonNode property : properties) {
            if (propertyId.equals(property.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    private static String valueKey(String region, String assetId, String propertyId) {
        return region + "::" + assetId + "::" + propertyId;
    }

    private ObjectNode putError(String entryId, String code, String message) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("entryId", entryId);
        ArrayNode errors = entry.putArray("errors");
        ObjectNode error = errors.addObject();
        error.put("errorCode", code);
        error.put("errorMessage", message);
        error.putArray("timestamps");
        return entry;
    }

    private ObjectNode readTqv(JsonNode point) {
        if (point == null || !point.isObject()) {
            throw invalid("propertyValues entries must be objects.");
        }
        JsonNode timestamp = point.get("timestamp");
        if (timestamp == null || !timestamp.hasNonNull("timeInSeconds")) {
            throw invalid("timestamp.timeInSeconds is required.");
        }
        JsonNode value = point.get("value");
        if (value == null || !value.isObject()) {
            throw invalid("value is required.");
        }
        ObjectNode tqv = objectMapper.createObjectNode();
        ObjectNode ts = tqv.putObject("timestamp");
        ts.put("timeInSeconds", timestamp.get("timeInSeconds").asLong());
        if (timestamp.hasNonNull("offsetInNanos")) {
            ts.put("offsetInNanos", timestamp.get("offsetInNanos").asInt());
        }
        tqv.set("value", value.deepCopy());
        tqv.put("quality", optionalTextOr(point, "quality", "GOOD"));
        return tqv;
    }

    private ObjectNode latest(String region, String assetId, String propertyId) {
        List<ObjectNode> stored = values.get(valueKey(region, assetId, propertyId));
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        synchronized (stored) {
            ObjectNode latest = stored.getFirst();
            for (ObjectNode point : stored) {
                if (point.path("timestamp").path("timeInSeconds").asLong()
                        > latest.path("timestamp").path("timeInSeconds").asLong()) {
                    latest = point;
                }
            }
            return latest.deepCopy();
        }
    }

    private List<ObjectNode> history(String region, String assetId, String propertyId, long start, long end) {
        List<ObjectNode> stored = values.get(valueKey(region, assetId, propertyId));
        List<ObjectNode> out = new ArrayList<>();
        if (stored == null) {
            return out;
        }
        synchronized (stored) {
            for (ObjectNode point : stored) {
                long time = point.path("timestamp").path("timeInSeconds").asLong();
                if (time >= start && time <= end) {
                    out.add(point.deepCopy());
                }
            }
        }
        return out;
    }

    private static long parseEpoch(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return (long) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Double numeric(JsonNode value) {
        if (value == null || !value.isObject()) {
            return null;
        }
        if (value.hasNonNull("doubleValue")) {
            return value.get("doubleValue").asDouble();
        }
        if (value.hasNonNull("integerValue")) {
            return value.get("integerValue").asDouble();
        }
        return null;
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException alreadyExists(String id, String arn) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("resourceId", id);
        extra.put("resourceArn", arn);
        return new AwsException("ResourceAlreadyExistsException", "Resource already exists.", 409, extra);
    }

    private static AwsException conflicting(String id, String arn, String message) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("resourceId", id);
        extra.put("resourceArn", arn);
        return new AwsException("ConflictingOperationException", message, 409, extra);
    }
}
