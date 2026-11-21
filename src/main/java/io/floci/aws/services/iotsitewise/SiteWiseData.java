package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.floci.aws.services.iotsitewise.SiteWiseStore.ASSET;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.TIME_SERIES;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.invalid;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.notFound;

/**
 * SiteWise's time series: one for each asset property that has data (its ID is the asset ID and property ID joined
 * by an underscore) and one for each alias that received data before any property had it (a disassociated data
 * stream, with a UUID). This class ingests values (BatchPutAssetPropertyValue, checking each value's type, quality
 * and time stamp) and answers the data plane's queries: the latest value, history, aggregates over 1m, 15m, 1h and
 * 1d windows, and linear and last-observation-carried-forward interpolation, each also in batch. A point is stored
 * as {@code {t, n, q, v}}: seconds, nanoseconds, quality and the Variant value.
 */
@ApplicationScoped
public class SiteWiseData {

    static final Set<String> QUALITIES = Set.of("GOOD", "BAD", "UNCERTAIN");
    private static final long PAST_SECONDS = 7 * 24 * 3600;
    private static final long FUTURE_SECONDS = 10 * 60;

    private final SiteWiseStore store;
    private final ObjectMapper mapper;
    private final Instance<SiteWiseAssets> assets;
    private final Instance<SiteWiseCompute> compute;
    private final Instance<SiteWiseSettings> settings;

    @Inject
    public SiteWiseData(SiteWiseStore store, Instance<SiteWiseAssets> assets, Instance<SiteWiseCompute> compute,
                        Instance<SiteWiseSettings> settings) {
        this.store = store;
        this.mapper = store.mapper();
        this.assets = assets;
        this.compute = compute;
        this.settings = settings;
    }

    // ---------------------------------------------------------------- streams

    /** Where a request's values go or come from: an asset property, or a disassociated alias. */
    record Stream(String timeSeriesId, String assetId, String propertyId, String alias, String dataType,
                  String dataTypeSpec, ObjectNode asset, ObjectNode property) {
        boolean associated() {
            return assetId != null;
        }
    }

    static String associatedId(String assetId, String propertyId) {
        return assetId + "_" + propertyId;
    }

    /**
     * The stream an asset ID and property ID, or a property alias, names. An alias no asset property has names a
     * disassociated stream, created when {@code create} (as ingestion does, when disassociated data storage is on).
     * Returns null for an alias with no stream and no creation.
     */
    Stream stream(String account, String region, JsonNode in, boolean create) {
        String assetId = in.path("assetId").asText(null);
        String propertyId = in.path("propertyId").asText(null);
        String alias = in.path("propertyAlias").asText(null);
        if (alias == null) {
            alias = in.path("alias").asText(null);
        }
        if (alias != null && (assetId != null || propertyId != null)) {
            throw invalid("Name a property by its alias or by its asset ID and property ID, not both.");
        }
        if (alias == null) {
            if (assetId == null || propertyId == null) {
                throw invalid("Name a property by its alias or by its asset ID and property ID.");
            }
            ObjectNode asset = assets.get().require(account, region, assetId);
            ObjectNode property = assets.get().property(asset, propertyId);
            return associated(account, region, asset, property);
        }
        for (ObjectNode asset : assets.get().all(account, region)) {
            for (ObjectNode property : SiteWiseAssets.properties(asset)) {
                if (alias.equals(property.path("alias").asText(null))) {
                    return associated(account, region, asset, property);
                }
            }
        }
        ObjectNode series = disassociated(account, region, alias);
        if (series == null) {
            if (!create) {
                return null;
            }
            if (!settings.get().disassociatedStorage(account, region)) {
                throw notFound("No asset property has the alias " + alias
                        + ", and disassociated data storage is disabled.");
            }
            return new Stream(null, null, null, alias, null, null, null, null);
        }
        return new Stream(series.get("timeSeriesId").asText(), null, null, alias, series.get("dataType").asText(),
                series.path("dataTypeSpec").asText(null), null, null);
    }

    private Stream associated(String account, String region, ObjectNode asset, ObjectNode property) {
        String assetId = asset.get("assetId").asText();
        String propertyId = property.get("id").asText();
        return new Stream(associatedId(assetId, propertyId), assetId, propertyId,
                property.path("alias").asText(null), property.get("dataType").asText(),
                property.path("dataTypeSpec").asText(null), asset, property);
    }

    ObjectNode disassociated(String account, String region, String alias) {
        for (ObjectNode series : store.list(account, region, TIME_SERIES)) {
            if (!series.hasNonNull("assetId") && alias.equals(series.path("alias").asText(null))) {
                return series;
            }
        }
        return null;
    }

    /** The stream's time series record, created on its first data. */
    private ObjectNode series(String account, String region, Stream stream, String dataType) {
        String id = stream.timeSeriesId() == null ? SiteWiseStore.uuid() : stream.timeSeriesId();
        ObjectNode series = store.get(account, region, TIME_SERIES, id).orElse(null);
        if (series == null) {
            series = store.record();
            double now = SiteWiseStore.now();
            if (stream.associated()) {
                series.put("assetId", stream.assetId());
                series.put("propertyId", stream.propertyId());
            }
            if (stream.alias() != null) {
                series.put("alias", stream.alias());
            }
            series.put("timeSeriesId", id);
            series.put("dataType", dataType);
            if (stream.dataTypeSpec() != null) {
                series.put("dataTypeSpec", stream.dataTypeSpec());
            }
            series.put("timeSeriesCreationDate", now);
            series.put("timeSeriesLastUpdateDate", now);
            series.put("timeSeriesArn", store.arn(account, region, TIME_SERIES, id));
            store.put(account, region, TIME_SERIES, id, series);
        }
        return series;
    }

    // ---------------------------------------------------------------- points

    static double time(JsonNode point) {
        return point.path("t").asLong() + point.path("n").asInt() / 1e9;
    }

    static long nanos(JsonNode point) {
        return point.path("t").asLong() * 1_000_000_000L + point.path("n").asInt();
    }

    List<JsonNode> points(String account, String region, String timeSeriesId) {
        if (timeSeriesId == null) {
            return List.of();
        }
        List<JsonNode> points = new ArrayList<>();
        store.points(account, region, timeSeriesId).path("points").forEach(points::add);
        return points;
    }

    /** Writes points into a time series, replacing any at the same time stamp, and keeps it sorted. */
    void write(String account, String region, String timeSeriesId, List<ObjectNode> points) {
        ObjectNode record = store.points(account, region, timeSeriesId);
        List<JsonNode> merged = new ArrayList<>();
        record.path("points").forEach(merged::add);
        for (ObjectNode point : points) {
            long at = nanos(point);
            merged.removeIf(existing -> nanos(existing) == at);
            merged.add(point);
        }
        merged.sort((a, b) -> Long.compare(nanos(a), nanos(b)));
        long retention = settings.get().retentionSeconds(account, region);
        if (retention > 0) {
            long oldest = Instant.now().getEpochSecond() - retention;
            merged.removeIf(p -> p.path("t").asLong() < oldest);
        }
        ArrayNode array = record.putArray("points");
        merged.forEach(array::add);
        store.putPoints(account, region, timeSeriesId, record);
        store.get(account, region, TIME_SERIES, timeSeriesId).ifPresent(series -> {
            series.put("timeSeriesLastUpdateDate", SiteWiseStore.now());
            store.put(account, region, TIME_SERIES, timeSeriesId, series);
        });
    }

    ObjectNode point(long seconds, int nanos, String quality, JsonNode value) {
        ObjectNode point = mapper.createObjectNode();
        point.put("t", seconds);
        point.put("n", nanos);
        point.put("q", quality);
        point.set("v", value);
        return point;
    }

    /** AssetPropertyValue, as the API shows a point. */
    ObjectNode value(JsonNode point) {
        ObjectNode value = mapper.createObjectNode();
        value.set("value", point.get("v").deepCopy());
        value.putObject("timestamp").put("timeInSeconds", point.get("t").asLong())
                .put("offsetInNanos", point.get("n").asInt());
        value.put("quality", point.path("q").asText("GOOD"));
        return value;
    }

    /** Records the computed or ingested points of an asset property and runs what depends on them. */
    void record(String account, String region, ObjectNode asset, ObjectNode property, List<ObjectNode> points) {
        Stream stream = associated(account, region, asset, property);
        series(account, region, stream, stream.dataType());
        write(account, region, stream.timeSeriesId(), points);
        compute.get().ingested(account, region, asset, property, points);
    }

    /** Adds history to an asset property without running computations or notifications. */
    void recordQuietly(String account, String region, Stream stream, List<ObjectNode> points) {
        series(account, region, stream, stream.dataType());
        write(account, region, stream.timeSeriesId(), points);
    }

    /** Adds points to a disassociated stream, creating it with the data type of its first values. */
    void recordAlias(String account, String region, Stream stream, String dataType, List<ObjectNode> points) {
        ObjectNode series = series(account, region, stream, dataType);
        write(account, region, series.get("timeSeriesId").asText(), points);
    }

    // ---------------------------------------------------------------- ingestion

    ObjectNode batchPutAssetPropertyValue(ObjectNode in, String region) {
        String account = store.account();
        JsonNode entries = in.get("entries");
        if (entries.size() > 10) {
            throw invalid("A request can have at most 10 entries.");
        }
        ArrayNode errorEntries = mapper.createArrayNode();
        long now = Instant.now().getEpochSecond();
        boolean refuseNullNaN = settings.get().disallowNullNaN(account, region);
        for (JsonNode entry : entries) {
            String entryId = entry.get("entryId").asText();
            JsonNode values = entry.get("propertyValues");
            if (values.size() > 10) {
                error(errorEntries, entryId, "InvalidRequestException", "An entry can have at most 10 values.",
                        values);
                continue;
            }
            Stream stream;
            try {
                stream = stream(account, region, entry, true);
            } catch (AwsException e) {
                error(errorEntries, entryId, e.getErrorCode(), e.getMessage(), values);
                continue;
            }
            if (stream.property() != null) {
                ObjectNode definition = assets.get().modelProperty(account, region, stream.asset(),
                        stream.propertyId());
                JsonNode type = definition == null ? null : definition.get("type");
                if (type != null && (type.has("transform") || type.has("metric"))) {
                    error(errorEntries, entryId, "InvalidRequestException", "Property " + stream.propertyId()
                            + " is a " + (type.has("transform") ? "transform" : "metric")
                            + ", which SiteWise computes; it can't take values.", values);
                    continue;
                }
                if (!"ACTIVE".equals(stream.asset().path("assetStatus").path("state").asText())
                        && !"UPDATING".equals(stream.asset().path("assetStatus").path("state").asText())) {
                    error(errorEntries, entryId, "ConflictingOperationException", "Asset " + stream.assetId()
                            + " isn't ACTIVE.", values);
                    continue;
                }
            }
            String dataType = stream.dataType();
            List<ObjectNode> accepted = new ArrayList<>();
            ArrayNode outOfRange = mapper.createArrayNode();
            ArrayNode badType = mapper.createArrayNode();
            String typeMessage = null;
            for (JsonNode value : values) {
                JsonNode timestamp = value.get("timestamp");
                long seconds = timestamp.get("timeInSeconds").asLong();
                int offset = timestamp.path("offsetInNanos").asInt(0);
                if (seconds < now - PAST_SECONDS || seconds > now + FUTURE_SECONDS) {
                    outOfRange.add(timestamp.deepCopy());
                    continue;
                }
                JsonNode variant = value.get("value");
                String quality = value.path("quality").asText("GOOD");
                String valueType = variantType(variant);
                if (valueType == null) {
                    typeMessage = "Each value has exactly one of stringValue, integerValue, doubleValue, booleanValue"
                            + " and nullValue.";
                    badType.add(timestamp.deepCopy());
                    continue;
                }
                if (dataType == null) {
                    dataType = valueType;
                }
                if (!valueType.equals(dataType) && !("STRING".equals(valueType) && Set.of("STRUCT", "JSON")
                        .contains(dataType))) {
                    typeMessage = "The property's data type is " + dataType + ", not " + valueType + ".";
                    badType.add(timestamp.deepCopy());
                    continue;
                }
                if (refuseNullNaN && (variant.has("nullValue") || variant.hasNonNull("doubleValue")
                        && Double.isNaN(variant.get("doubleValue").asDouble()))) {
                    typeMessage = "This account's storage configuration refuses null and NaN values.";
                    badType.add(timestamp.deepCopy());
                    continue;
                }
                if (variant.has("nullValue") && "GOOD".equals(quality)) {
                    typeMessage = "A null value's quality is BAD or UNCERTAIN.";
                    badType.add(timestamp.deepCopy());
                    continue;
                }
                accepted.add(point(seconds, offset, quality, variant.deepCopy()));
            }
            ArrayNode errors = mapper.createArrayNode();
            if (!outOfRange.isEmpty()) {
                errors.addObject().put("errorCode", "TimestampOutOfRangeException")
                        .put("errorMessage", "Values must be within 7 days in the past and 10 minutes in the future.")
                        .set("timestamps", outOfRange);
            }
            if (!badType.isEmpty()) {
                errors.addObject().put("errorCode", "InvalidRequestException").put("errorMessage", typeMessage)
                        .set("timestamps", badType);
            }
            if (!errors.isEmpty()) {
                errorEntries.addObject().put("entryId", entryId).set("errors", errors);
            }
            if (accepted.isEmpty()) {
                continue;
            }
            if (stream.associated()) {
                if (stream.property() != null) {
                    record(account, region, stream.asset(), stream.property(), accepted);
                }
            } else {
                ObjectNode series = series(account, region, stream, dataType);
                write(account, region, series.get("timeSeriesId").asText(), accepted);
            }
        }
        if (!errorEntries.isEmpty()) {
            ObjectNode event = mapper.createObjectNode().put("operation", "BatchPutAssetPropertyValue");
            event.set("errorEntries", errorEntries.deepCopy());
            settings.get().log(account, region, "ERROR", event);
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("errorEntries", errorEntries);
        return out;
    }

    private void error(ArrayNode errorEntries, String entryId, String code, String message, JsonNode values) {
        ArrayNode timestamps = mapper.createArrayNode();
        values.forEach(v -> timestamps.add(v.path("timestamp").deepCopy()));
        ArrayNode errors = mapper.createArrayNode();
        errors.addObject().put("errorCode", code).put("errorMessage", message).set("timestamps", timestamps);
        errorEntries.addObject().put("entryId", entryId).set("errors", errors);
    }

    /** The data type a Variant carries, or null when it carries none or several. */
    static String variantType(JsonNode variant) {
        String type = null;
        int fields = 0;
        for (Map.Entry<String, String> field : Map.of("stringValue", "STRING", "integerValue", "INTEGER",
                "doubleValue", "DOUBLE", "booleanValue", "BOOLEAN").entrySet()) {
            if (variant.hasNonNull(field.getKey())) {
                type = field.getValue();
                fields++;
            }
        }
        if (variant.has("nullValue")) {
            fields++;
            type = switch (variant.path("nullValue").path("valueType").asText()) {
                case "D" -> "DOUBLE";
                case "B" -> "BOOLEAN";
                case "S" -> "STRING";
                case "I" -> "INTEGER";
                default -> null;
            };
        }
        return fields == 1 ? type : null;
    }

    /** A Variant's value as the formula engine reads it: a Double or a String; null for a null value. */
    static Object number(JsonNode variant) {
        if (variant.hasNonNull("doubleValue")) {
            return variant.get("doubleValue").asDouble();
        }
        if (variant.hasNonNull("integerValue")) {
            return (double) variant.get("integerValue").asLong();
        }
        if (variant.hasNonNull("booleanValue")) {
            return variant.get("booleanValue").asBoolean() ? 1.0 : 0.0;
        }
        if (variant.hasNonNull("stringValue")) {
            return variant.get("stringValue").asText();
        }
        return null;
    }

    /** Writes each asset's attribute defaults that have no value yet, at the asset's creation time. */
    void attributeDefaults(String account, String region, ObjectNode asset, ObjectNode model) {
        for (ObjectNode definition : SiteWiseModels.allProperties(model)) {
            JsonNode attribute = definition.path("type").path("attribute");
            if (!attribute.hasNonNull("defaultValue")) {
                continue;
            }
            String id = associatedId(asset.get("assetId").asText(), definition.get("id").asText());
            if (!points(account, region, id).isEmpty()) {
                continue;
            }
            ObjectNode property = assets.get().property(asset, definition.get("id").asText());
            JsonNode variant = variant(definition.get("dataType").asText(), attribute.get("defaultValue").asText());
            long created = (long) asset.get("assetCreationDate").asDouble();
            Stream stream = associated(account, region, asset, property);
            series(account, region, stream, stream.dataType());
            write(account, region, id, List.of(point(created, 0, "GOOD", variant)));
        }
    }

    ObjectNode variant(String dataType, String text) {
        ObjectNode variant = mapper.createObjectNode();
        switch (dataType) {
            case "INTEGER" -> variant.put("integerValue", Integer.parseInt(text));
            case "DOUBLE" -> variant.put("doubleValue", Double.parseDouble(text));
            case "BOOLEAN" -> variant.put("booleanValue", Boolean.parseBoolean(text));
            default -> variant.put("stringValue", text);
        }
        return variant;
    }

    void forgetAsset(String account, String region, ObjectNode asset) {
        String prefix = asset.get("assetId").asText() + "_";
        for (ObjectNode series : store.list(account, region, TIME_SERIES)) {
            if (series.get("timeSeriesId").asText().startsWith(prefix)) {
                store.delete(account, region, TIME_SERIES, series.get("timeSeriesId").asText());
                store.deletePoints(account, region, series.get("timeSeriesId").asText());
            }
        }
    }

    void forgetProperty(String account, String region, String assetId, String propertyId) {
        String id = associatedId(assetId, propertyId);
        store.delete(account, region, TIME_SERIES, id);
        store.deletePoints(account, region, id);
    }

    /** An alias set on an asset property can't be one a disassociated stream already has. */
    void claimAlias(String account, String region, String alias, String assetId, String propertyId) {
        ObjectNode series = disassociated(account, region, alias);
        if (series != null) {
            throw SiteWiseStore.conflicting("The disassociated time series " + series.get("timeSeriesId").asText()
                    + " has the alias " + alias + "; associate it with AssociateTimeSeriesToAssetProperty.",
                    series.get("timeSeriesId").asText(), series.get("timeSeriesArn").asText());
        }
        store.get(account, region, TIME_SERIES, associatedId(assetId, propertyId)).ifPresent(own -> {
            own.put("alias", alias);
            store.put(account, region, TIME_SERIES, associatedId(assetId, propertyId), own);
        });
    }

    void releaseAlias(String account, String region, String assetId, String propertyId) {
        store.get(account, region, TIME_SERIES, associatedId(assetId, propertyId)).ifPresent(own -> {
            own.remove("alias");
            store.put(account, region, TIME_SERIES, associatedId(assetId, propertyId), own);
        });
    }

    // ---------------------------------------------------------------- time series

    ObjectNode describeTimeSeries(ObjectNode in, String region) {
        String account = store.account();
        return SiteWiseStore.view(require(account, region, in));
    }

    private ObjectNode require(String account, String region, JsonNode in) {
        Stream stream = stream(account, region, in, false);
        ObjectNode series = stream == null || stream.timeSeriesId() == null ? null
                : store.get(account, region, TIME_SERIES, stream.timeSeriesId()).orElse(null);
        if (series == null) {
            throw notFound("The time series doesn't exist.");
        }
        return series;
    }

    ObjectNode listTimeSeries(ObjectNode in, String region) {
        String account = store.account();
        String assetReference = in.path("assetId").asText(null);
        String assetId = assetReference == null ? null
                : assets.get().require(account, region, assetReference).get("assetId").asText();
        String prefix = in.path("aliasPrefix").asText(null);
        String type = in.path("timeSeriesType").asText(null);
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode series : store.list(account, region, TIME_SERIES)) {
            boolean associated = series.hasNonNull("assetId");
            if (assetId != null && !assetId.equals(series.path("assetId").asText())) {
                continue;
            }
            if (prefix != null && !series.path("alias").asText("").startsWith(prefix)) {
                continue;
            }
            if ("ASSOCIATED".equals(type) && !associated || "DISASSOCIATED".equals(type) && associated) {
                continue;
            }
            summaries.add(SiteWiseStore.view(series));
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "TimeSeriesSummaries");
        return out;
    }

    ObjectNode deleteTimeSeries(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode series = require(account, region, in);
        String id = series.get("timeSeriesId").asText();
        store.delete(account, region, TIME_SERIES, id);
        store.deletePoints(account, region, id);
        return mapper.createObjectNode();
    }

    ObjectNode associateTimeSeriesToAssetProperty(ObjectNode in, String region) {
        String account = store.account();
        String alias = in.get("alias").asText();
        ObjectNode series = disassociated(account, region, alias);
        if (series == null) {
            throw notFound("No disassociated time series has the alias " + alias + ".");
        }
        ObjectNode asset = assets.get().require(account, region, in.get("assetId").asText());
        ObjectNode property = assets.get().property(asset, in.get("propertyId").asText());
        if (property.hasNonNull("alias") && !alias.equals(property.get("alias").asText())) {
            throw invalid("Property " + property.get("id").asText() + " already has the alias "
                    + property.get("alias").asText() + ".");
        }
        if (!series.get("dataType").asText().equals(property.get("dataType").asText())) {
            throw invalid("The time series holds " + series.get("dataType").asText() + " values, but the property is "
                    + property.get("dataType").asText() + ".");
        }
        String target = associatedId(asset.get("assetId").asText(), property.get("id").asText());
        List<ObjectNode> moved = new ArrayList<>();
        points(account, region, series.get("timeSeriesId").asText()).forEach(p -> moved.add((ObjectNode) p));
        store.delete(account, region, TIME_SERIES, series.get("timeSeriesId").asText());
        store.deletePoints(account, region, series.get("timeSeriesId").asText());
        property.put("alias", alias);
        store.put(account, region, ASSET, asset.get("assetId").asText(), asset);
        Stream stream = associated(account, region, asset, property);
        ObjectNode created = series(account, region, stream, stream.dataType());
        created.put("timeSeriesCreationDate", series.get("timeSeriesCreationDate").asDouble());
        store.put(account, region, TIME_SERIES, target, created);
        if (!moved.isEmpty()) {
            write(account, region, target, moved);
        }
        return mapper.createObjectNode();
    }

    ObjectNode disassociateTimeSeriesFromAssetProperty(ObjectNode in, String region) {
        String account = store.account();
        String alias = in.get("alias").asText();
        ObjectNode asset = assets.get().require(account, region, in.get("assetId").asText());
        ObjectNode property = assets.get().property(asset, in.get("propertyId").asText());
        if (!alias.equals(property.path("alias").asText(null))) {
            throw notFound("Property " + property.get("id").asText() + " doesn't have the alias " + alias + ".");
        }
        String source = associatedId(asset.get("assetId").asText(), property.get("id").asText());
        List<JsonNode> moved = points(account, region, source);
        property.remove("alias");
        store.put(account, region, ASSET, asset.get("assetId").asText(), asset);
        store.delete(account, region, TIME_SERIES, source);
        store.deletePoints(account, region, source);
        Stream stream = new Stream(null, null, null, alias, property.get("dataType").asText(),
                property.path("dataTypeSpec").asText(null), null, null);
        ObjectNode series = series(account, region, stream, property.get("dataType").asText());
        List<ObjectNode> points = new ArrayList<>();
        moved.forEach(p -> points.add((ObjectNode) p));
        if (!points.isEmpty()) {
            write(account, region, series.get("timeSeriesId").asText(), points);
        }
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- queries

    /** A time in a request: epoch seconds, or an ISO 8601 date as query strings carry it. */
    static double date(JsonNode value, double fallback) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return fallback;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        String text = value.asText();
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            try {
                return Instant.parse(text).toEpochMilli() / 1000.0;
            } catch (DateTimeParseException again) {
                throw invalid("The date " + text + " isn't valid.");
            }
        }
    }

    private Stream readable(String account, String region, JsonNode in) {
        Stream stream = stream(account, region, in, false);
        if (stream == null) {
            throw notFound("No time series has the alias " + in.path("propertyAlias").asText() + ".");
        }
        return stream;
    }

    ObjectNode getAssetPropertyValue(ObjectNode in, String region) {
        String account = store.account();
        Stream stream = readable(account, region, in);
        List<JsonNode> points = points(account, region, stream.timeSeriesId());
        ObjectNode out = mapper.createObjectNode();
        if (!points.isEmpty()) {
            out.set("propertyValue", value(points.get(points.size() - 1)));
        }
        return out;
    }

    private static Set<String> qualities(JsonNode in, Set<String> fallback) {
        if (!in.has("qualities") || in.get("qualities").isEmpty()) {
            return fallback;
        }
        if (in.get("qualities").size() > 1) {
            throw invalid("Filter by one quality.");
        }
        return Set.of(in.get("qualities").get(0).asText());
    }

    List<JsonNode> history(String account, String region, Stream stream, JsonNode in) {
        double start = date(in.get("startDate"), 0);
        double end = date(in.get("endDate"), SiteWiseStore.now());
        if (start > end) {
            throw invalid("The startDate is after the endDate.");
        }
        Set<String> qualities = qualities(in, QUALITIES);
        List<JsonNode> matched = new ArrayList<>();
        for (JsonNode point : points(account, region, stream.timeSeriesId())) {
            double at = time(point);
            if (at >= start && at <= end && qualities.contains(point.path("q").asText("GOOD"))) {
                matched.add(point);
            }
        }
        if ("DESCENDING".equals(in.path("timeOrdering").asText("ASCENDING"))) {
            Collections.reverse(matched);
        }
        return matched;
    }

    ObjectNode getAssetPropertyValueHistory(ObjectNode in, String region) {
        String account = store.account();
        Stream stream = readable(account, region, in);
        List<ObjectNode> values = new ArrayList<>();
        history(account, region, stream, in).forEach(p -> values.add(value(p)));
        ObjectNode out = mapper.createObjectNode();
        int max = Math.min(in.path("maxResults").asInt(100), 20000);
        ObjectNode bounded = in.deepCopy().put("maxResults", max);
        SiteWiseStore.page(values, bounded, 100, out, "assetPropertyValueHistory");
        return out;
    }

    static long resolutionSeconds(String resolution) {
        return switch (resolution) {
            case "1m" -> 60;
            case "15m" -> 900;
            case "1h" -> 3600;
            case "1d" -> 86400;
            default -> throw invalid("The resolution " + resolution + " isn't 1m, 15m, 1h or 1d.");
        };
    }

    List<ObjectNode> aggregates(String account, String region, Stream stream, JsonNode in) {
        long resolution = resolutionSeconds(in.get("resolution").asText());
        double start = date(in.get("startDate"), 0);
        double end = date(in.get("endDate"), SiteWiseStore.now());
        if (start > end) {
            throw invalid("The startDate is after the endDate.");
        }
        Set<String> types = new LinkedHashSet<>();
        in.get("aggregateTypes").forEach(t -> types.add(t.asText()));
        Set<String> qualities = qualities(in, Set.of("GOOD"));
        String quality = qualities.iterator().next();
        Map<Long, List<Double>> windows = new TreeMap<>();
        Map<Long, Integer> counts = new TreeMap<>();
        boolean numeric = !"STRING".equals(stream.dataType());
        if (!numeric && !(types.size() == 1 && types.contains("COUNT"))) {
            throw invalid("Only COUNT aggregates a STRING property.");
        }
        long first = (long) Math.floor(start / resolution) * resolution;
        for (JsonNode point : points(account, region, stream.timeSeriesId())) {
            double at = time(point);
            if (at < first || at >= end || !qualities.contains(point.path("q").asText("GOOD"))) {
                continue;
            }
            long window = (long) Math.floor(at / resolution) * resolution;
            counts.merge(window, 1, Integer::sum);
            Object value = number(point.get("v"));
            if (value instanceof Double d) {
                windows.computeIfAbsent(window, k -> new ArrayList<>()).add(d);
            }
        }
        List<ObjectNode> aggregated = new ArrayList<>();
        for (Map.Entry<Long, Integer> window : counts.entrySet()) {
            if (window.getKey() + resolution <= start) {
                continue;
            }
            List<Double> values = windows.getOrDefault(window.getKey(), List.of());
            ObjectNode entry = mapper.createObjectNode();
            entry.put("timestamp", (double) window.getKey());
            entry.put("quality", quality);
            ObjectNode value = entry.putObject("value");
            double sum = values.stream().mapToDouble(Double::doubleValue).sum();
            for (String type : types) {
                switch (type) {
                    case "AVERAGE" -> {
                        if (!values.isEmpty()) {
                            value.put("average", sum / values.size());
                        }
                    }
                    case "COUNT" -> value.put("count", (double) window.getValue());
                    case "MAXIMUM" -> values.stream().mapToDouble(Double::doubleValue).max()
                            .ifPresent(m -> value.put("maximum", m));
                    case "MINIMUM" -> values.stream().mapToDouble(Double::doubleValue).min()
                            .ifPresent(m -> value.put("minimum", m));
                    case "SUM" -> value.put("sum", sum);
                    case "STANDARD_DEVIATION" -> {
                        if (!values.isEmpty()) {
                            double mean = sum / values.size();
                            double squares = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
                            value.put("standardDeviation", values.size() < 2 ? 0.0
                                    : Math.sqrt(squares / (values.size() - 1)));
                        }
                    }
                    default -> throw invalid("The aggregate type " + type + " isn't valid.");
                }
            }
            aggregated.add(entry);
        }
        if ("DESCENDING".equals(in.path("timeOrdering").asText("ASCENDING"))) {
            Collections.reverse(aggregated);
        }
        return aggregated;
    }

    ObjectNode getAssetPropertyAggregates(ObjectNode in, String region) {
        String account = store.account();
        Stream stream = readable(account, region, in);
        List<ObjectNode> aggregated = aggregates(account, region, stream, in);
        ObjectNode out = mapper.createObjectNode();
        int max = Math.min(in.path("maxResults").asInt(100), 2500);
        SiteWiseStore.page(aggregated, in.deepCopy().put("maxResults", max), 100, out, "aggregatedValues");
        return out;
    }

    List<ObjectNode> interpolate(String account, String region, Stream stream, JsonNode in) {
        String type = in.get("type").asText();
        boolean linear = "LINEAR_INTERPOLATION".equals(type);
        if (!linear && !"LOCF_INTERPOLATION".equals(type)) {
            throw invalid("The interpolation type " + type + " isn't LINEAR_INTERPOLATION or LOCF_INTERPOLATION.");
        }
        if (!"GOOD".equals(in.get("quality").asText())) {
            throw invalid("Interpolation reads only GOOD data.");
        }
        if (linear && !Set.of("DOUBLE", "INTEGER").contains(stream.dataType())) {
            throw invalid("Linear interpolation needs a DOUBLE or INTEGER property.");
        }
        long startNanos = in.get("startTimeInSeconds").asLong() * 1_000_000_000L
                + in.path("startTimeOffsetInNanos").asLong(0);
        long endNanos = in.get("endTimeInSeconds").asLong() * 1_000_000_000L + in.path("endTimeOffsetInNanos").asLong(0);
        if (startNanos > endNanos) {
            throw invalid("The start time is after the end time.");
        }
        long interval = in.get("intervalInSeconds").asLong() * 1_000_000_000L;
        long window = in.hasNonNull("intervalWindowInSeconds") ? in.get("intervalWindowInSeconds").asLong()
                * 1_000_000_000L : interval;
        List<JsonNode> good = new ArrayList<>();
        for (JsonNode point : points(account, region, stream.timeSeriesId())) {
            if ("GOOD".equals(point.path("q").asText("GOOD")) && !point.get("v").has("nullValue")) {
                good.add(point);
            }
        }
        List<ObjectNode> results = new ArrayList<>();
        for (long at = startNanos; at <= endNanos && results.size() < 100_000; at += interval) {
            JsonNode before = null;
            JsonNode after = null;
            for (JsonNode point : good) {
                long when = nanos(point);
                if (when <= at) {
                    before = point;
                } else {
                    after = point;
                    break;
                }
            }
            JsonNode variant = null;
            if (linear) {
                if (before != null && nanos(before) == at) {
                    variant = before.get("v");
                } else if (before != null && after != null && at - nanos(before) <= window
                        && nanos(after) - at <= window) {
                    double a = (Double) number(before.get("v"));
                    double b = (Double) number(after.get("v"));
                    double fraction = (double) (at - nanos(before)) / (nanos(after) - nanos(before));
                    variant = mapper.createObjectNode().put("doubleValue", a + (b - a) * fraction);
                }
            } else if (before != null && at - nanos(before) <= Math.max(window, interval)) {
                variant = before.get("v");
            }
            if (variant != null) {
                ObjectNode entry = mapper.createObjectNode();
                entry.putObject("timestamp").put("timeInSeconds", Math.floorDiv(at, 1_000_000_000L))
                        .put("offsetInNanos", (int) Math.floorMod(at, 1_000_000_000L));
                entry.set("value", variant.deepCopy());
                results.add(entry);
            }
        }
        return results;
    }

    ObjectNode getInterpolatedAssetPropertyValues(ObjectNode in, String region) {
        String account = store.account();
        Stream stream = readable(account, region, in);
        List<ObjectNode> results = interpolate(account, region, stream, in);
        ObjectNode out = mapper.createObjectNode();
        int max = Math.min(in.path("maxResults").asInt(10), 250);
        SiteWiseStore.page(results, in.deepCopy().put("maxResults", max), 10, out, "interpolatedAssetPropertyValues");
        return out;
    }

    // ---------------------------------------------------------------- batch queries

    @FunctionalInterface
    private interface EntryHandler {
        void handle(JsonNode entry, Stream stream, ObjectNode success);
    }

    private ObjectNode batch(ObjectNode in, String region, int maxEntries, String successName, EntryHandler handler) {
        String account = store.account();
        JsonNode entries = in.get("entries");
        if (entries.size() > maxEntries) {
            throw invalid("A request can have at most " + maxEntries + " entries.");
        }
        ArrayNode errors = mapper.createArrayNode();
        ArrayNode successes = mapper.createArrayNode();
        int start = SiteWiseStore.start(in.path("nextToken").asText(null), entries.size());
        for (int i = start; i < entries.size(); i++) {
            JsonNode entry = entries.get(i);
            String entryId = entry.get("entryId").asText();
            try {
                Stream stream = readable(account, region, entry);
                ObjectNode success = mapper.createObjectNode().put("entryId", entryId);
                handler.handle(entry, stream, success);
                successes.add(success);
            } catch (AwsException e) {
                String code = "ResourceNotFoundException".equals(e.getErrorCode()) ? e.getErrorCode()
                        : "InvalidRequestException";
                errors.addObject().put("errorCode", code).put("errorMessage", e.getMessage()).put("entryId", entryId);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("errorEntries", errors);
        out.set(successName, successes);
        out.putArray("skippedEntries");
        return out;
    }

    ObjectNode batchGetAssetPropertyValue(ObjectNode in, String region) {
        String account = store.account();
        return batch(in, region, 128, "successEntries", (entry, stream, success) -> {
            List<JsonNode> points = points(account, region, stream.timeSeriesId());
            if (!points.isEmpty()) {
                success.set("assetPropertyValue", value(points.get(points.size() - 1)));
            }
        });
    }

    ObjectNode batchGetAssetPropertyValueHistory(ObjectNode in, String region) {
        String account = store.account();
        int max = Math.min(in.path("maxResults").asInt(20000), 20000);
        return batch(in, region, 16, "successEntries", (entry, stream, success) -> {
            ArrayNode values = success.putArray("assetPropertyValueHistory");
            history(account, region, stream, entry).stream().limit(max).forEach(p -> values.add(value(p)));
        });
    }

    ObjectNode batchGetAssetPropertyAggregates(ObjectNode in, String region) {
        String account = store.account();
        int max = Math.min(in.path("maxResults").asInt(4000), 4000);
        return batch(in, region, 16, "successEntries", (entry, stream, success) -> {
            ArrayNode values = success.putArray("aggregatedValues");
            aggregates(account, region, stream, entry).stream().limit(max).forEach(values::add);
        });
    }
}
