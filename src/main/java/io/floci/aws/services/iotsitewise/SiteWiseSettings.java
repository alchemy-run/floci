package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.iotsitewise.SiteWiseStore.SETTINGS;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.invalid;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.meta;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.notFound;

/**
 * A Region's account-level configuration: storage (the retention period, whether disassociated data streams keep
 * data, whether null and NaN values are refused, and the cold tier: a customer managed S3 location that SiteWise
 * replicates time series, aggregates and asset metadata to, in the layout and Avro schemas SiteWise documents),
 * default encryption, logging to CloudWatch Logs, and tags on every SiteWise resource.
 */
@ApplicationScoped
public class SiteWiseSettings {

    private static final Logger LOG = Logger.getLogger(SiteWiseSettings.class);
    static final String LOG_GROUP = "/aws/iotsitewise";

    private static final Schema RAW = new Schema.Parser().parse("""
            {"type":"record","name":"RawData","namespace":"com.amazonaws.iotsitewise","fields":[
             {"name":"seriesId","type":"string"},{"name":"timeInSeconds","type":"long"},
             {"name":"offsetInNanos","type":"long"},{"name":"quality","type":"string"},
             {"name":"doubleValue","type":["null","double"],"default":null},
             {"name":"stringValue","type":["null","string"],"default":null},
             {"name":"integerValue","type":["null","int"],"default":null},
             {"name":"booleanValue","type":["null","boolean"],"default":null},
             {"name":"jsonValue","type":["null","string"],"default":null},
             {"name":"recordVersion","type":["null","long"],"default":null}]}
            """);
    private static final Schema AGG = new Schema.Parser().parse("""
            {"type":"record","name":"AggregatedData","namespace":"com.amazonaws.iotsitewise","fields":[
             {"name":"seriesId","type":"string"},{"name":"timeInSeconds","type":"long"},
             {"name":"offsetInNanos","type":"long"},{"name":"quality","type":"string"},
             {"name":"resolution","type":"string"},{"name":"count","type":"double"},
             {"name":"average","type":["null","double"],"default":null},
             {"name":"min","type":["null","double"],"default":null},
             {"name":"max","type":["null","double"],"default":null},
             {"name":"sum","type":["null","double"],"default":null},
             {"name":"recordVersion","type":["null","long"],"default":null}]}
            """);

    private final SiteWiseStore store;
    private final ObjectMapper mapper;
    private final Instance<S3Service> s3;
    private final Instance<KmsService> kms;
    private final Instance<CloudWatchLogsService> logs;
    private final Instance<SiteWiseData> data;
    private final Instance<SiteWiseAssets> assets;
    private ScheduledExecutorService exporter;

    @Inject
    public SiteWiseSettings(SiteWiseStore store, Instance<S3Service> s3, Instance<KmsService> kms,
                            Instance<CloudWatchLogsService> logs, Instance<SiteWiseData> data,
                            Instance<SiteWiseAssets> assets) {
        this.store = store;
        this.mapper = store.mapper();
        this.s3 = s3;
        this.kms = kms;
        this.logs = logs;
        this.data = data;
        this.assets = assets;
    }

    private ObjectNode settings(String account, String region) {
        return store.get(account, region, SETTINGS, "account").orElseGet(store::record);
    }

    private void save(String account, String region, ObjectNode settings) {
        store.put(account, region, SETTINGS, "account", settings);
    }

    // ---------------------------------------------------------------- storage

    boolean disassociatedStorage(String account, String region) {
        return !"DISABLED".equals(settings(account, region).path("storage").path("disassociatedDataStorage")
                .asText("ENABLED"));
    }

    boolean disallowNullNaN(String account, String region) {
        return settings(account, region).path("storage").path("disallowIngestNullNaN").asBoolean(false);
    }

    /** How long data is kept in the hot tier, in seconds; 0 for as long as it's wanted. */
    long retentionSeconds(String account, String region) {
        JsonNode retention = settings(account, region).path("storage").path("retentionPeriod");
        if (retention.path("unlimited").asBoolean(false) || !retention.has("numberOfDays")) {
            return 0;
        }
        return retention.get("numberOfDays").asLong() * 86400;
    }

    ObjectNode putStorageConfiguration(ObjectNode in, String region) {
        String account = store.account();
        String type = in.get("storageType").asText();
        ObjectNode storage = mapper.createObjectNode();
        storage.put("storageType", type);
        if ("MULTI_LAYER_STORAGE".equals(type)) {
            JsonNode customer = in.path("multiLayerStorage").path("customerManagedS3Storage");
            if (customer.isMissingNode()) {
                throw invalid("MULTI_LAYER_STORAGE needs multiLayerStorage.customerManagedS3Storage.");
            }
            String bucket = bucketOf(customer.get("s3ResourceArn").asText());
            if (!RequestScopes.callAs(account, region, () -> s3.get().bucketExists(bucket))) {
                throw invalid("The bucket " + bucket + " doesn't exist.");
            }
            AwsArnUtils.parse(customer.get("roleArn").asText());
            storage.set("multiLayerStorage", in.get("multiLayerStorage").deepCopy());
        } else if (in.has("multiLayerStorage")) {
            throw invalid("multiLayerStorage is only for MULTI_LAYER_STORAGE.");
        }
        storage.put("disassociatedDataStorage", in.path("disassociatedDataStorage").asText("ENABLED"));
        JsonNode retention = in.get("retentionPeriod");
        if (retention != null) {
            if (retention.path("unlimited").asBoolean(false) == retention.has("numberOfDays")) {
                throw invalid("A retention period is a number of days or unlimited.");
            }
            if (retention.has("numberOfDays") && retention.get("numberOfDays").asInt() < 30) {
                throw invalid("A retention period is at least 30 days.");
            }
            if (!"MULTI_LAYER_STORAGE".equals(type) && retention.has("numberOfDays")) {
                throw invalid("A retention period needs MULTI_LAYER_STORAGE.");
            }
            storage.set("retentionPeriod", retention.deepCopy());
        }
        String warm = in.path("warmTier").asText(null);
        if (warm != null) {
            storage.put("warmTier", warm);
        }
        if (in.has("warmTierRetentionPeriod")) {
            if (!"ENABLED".equals(warm)) {
                throw invalid("A warm tier retention period needs the warm tier ENABLED.");
            }
            storage.set("warmTierRetentionPeriod", in.get("warmTierRetentionPeriod").deepCopy());
        }
        if (in.has("disallowIngestNullNaN")) {
            storage.put("disallowIngestNullNaN", in.get("disallowIngestNullNaN").asBoolean());
        }
        storage.put("lastUpdateDate", SiteWiseStore.now());
        ObjectNode settings = settings(account, region);
        boolean coldBefore = settings.path("storage").has("multiLayerStorage");
        settings.set("storage", storage);
        if ("MULTI_LAYER_STORAGE".equals(type) && !coldBefore) {
            meta(settings).put("exportedThrough", 0);
        }
        save(account, region, settings);
        if ("MULTI_LAYER_STORAGE".equals(type)) {
            startExporter();
            exportNow(account, region);
        }
        ObjectNode out = storage.deepCopy();
        out.remove("lastUpdateDate");
        out.putObject("configurationStatus").put("state", "ACTIVE");
        return out;
    }

    ObjectNode describeStorageConfiguration(ObjectNode in, String region) {
        String account = store.account();
        JsonNode storage = settings(account, region).path("storage");
        ObjectNode out = storage.isMissingNode() ? mapper.createObjectNode()
                .put("storageType", "SITEWISE_DEFAULT_STORAGE").put("disassociatedDataStorage", "ENABLED")
                : (ObjectNode) storage.deepCopy();
        out.putObject("configurationStatus").put("state", "ACTIVE");
        return out;
    }

    static String bucketOf(String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!"s3".equals(parsed.service())) {
            throw invalid("The S3 resource ARN " + arn + " isn't an S3 ARN.");
        }
        String resource = parsed.resource();
        return resource.contains("/") ? resource.substring(0, resource.indexOf('/')) : resource;
    }

    static String prefixOf(String arn) {
        String resource = AwsArnUtils.parse(arn).resource();
        if (!resource.contains("/")) {
            return "";
        }
        String prefix = resource.substring(resource.indexOf('/') + 1);
        return prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
    }

    // ---------------------------------------------------------------- cold tier

    public synchronized void startExporter() {
        if (exporter != null) {
            return;
        }
        exporter = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "iotsitewise-cold-tier");
            thread.setDaemon(true);
            return thread;
        });
        long every = Math.max(1, store.config().coldTierExportSeconds());
        exporter.scheduleWithFixedDelay(this::exportEverywhere, every, every, TimeUnit.SECONDS);
    }

    @PreDestroy
    synchronized void stop() {
        if (exporter != null) {
            exporter.shutdownNow();
            exporter = null;
        }
    }

    private void exportEverywhere() {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : store.everywhere(key -> key.endsWith("/" + SETTINGS + "/account"))) {
            if (!entry.value().path("storage").has("multiLayerStorage")) {
                continue;
            }
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            try {
                RequestScopes.runAs(entry.accountId(), region, () -> exportNow(entry.accountId(), region));
            } catch (RuntimeException e) {
                LOG.warnv(e, "IoT SiteWise could not export to the cold tier in {0}", region);
            }
        }
    }

    /**
     * Writes every point not exported yet to the cold tier: raw values under {@code raw/}, their one-minute
     * aggregates under {@code agg/}, one Avro file for each series, exclusive start time and quality, plus asset
     * and hierarchy metadata and the index files.
     */
    synchronized void exportNow(String account, String region) {
        ObjectNode settings = settings(account, region);
        JsonNode customer = settings.path("storage").path("multiLayerStorage").path("customerManagedS3Storage");
        if (customer.isMissingNode()) {
            return;
        }
        String bucket = bucketOf(customer.get("s3ResourceArn").asText());
        String prefix = prefixOf(customer.get("s3ResourceArn").asText());
        long through = meta(settings).path("exportedThrough").asLong(0);
        long until = System.currentTimeMillis() * 1_000_000L;
        for (ObjectNode series : store.list(account, region, SiteWiseStore.TIME_SERIES)) {
            String id = series.get("timeSeriesId").asText();
            Map<String, List<JsonNode>> files = new TreeMap<>();
            for (JsonNode point : data.get().points(account, region, id)) {
                long at = SiteWiseData.nanos(point);
                if (at <= through || at > until) {
                    continue;
                }
                long start = Math.floorDiv(point.get("t").asLong(), 21600L) * 21600L;
                files.computeIfAbsent(start + "_" + point.path("q").asText("GOOD"), k -> new ArrayList<>()).add(point);
            }
            for (Map.Entry<String, List<JsonNode>> file : files.entrySet()) {
                long start = Long.parseLong(file.getKey().substring(0, file.getKey().indexOf('_')));
                String quality = file.getKey().substring(file.getKey().indexOf('_') + 1);
                ZonedDateTime day = Instant.ofEpochSecond(start).atZone(ZoneOffset.UTC);
                String partition = "startYear=" + day.getYear() + "/startMonth=" + day.getMonthValue() + "/startDay="
                        + day.getDayOfMonth() + "/seriesBucket=" + bucket(id) + "/";
                String name = id + "_" + start + "_" + quality;
                put(bucket, prefix + "raw/" + partition + "raw_" + name + ".avro", raw(id, file.getValue()),
                        "application/avro");
                put(bucket, prefix + "agg/" + partition + "agg_" + name + ".avro", agg(id, quality, file.getValue()),
                        "application/avro");
                put(bucket, prefix + "index/series=" + id + "/startYear=" + day.getYear() + "/startMonth="
                        + String.format("%02d", day.getMonthValue()) + "/startDay="
                        + String.format("%02d", day.getDayOfMonth()) + "/index_" + name,
                        ("raw_" + name + ".avro\n").getBytes(StandardCharsets.UTF_8), "text/plain");
            }
        }
        for (ObjectNode asset : assets.get().all(account, region)) {
            put(bucket, prefix + "asset_metadata/asset_" + asset.get("assetId").asText() + ".ndjson",
                    metadata(account, region, asset), "application/x-ndjson");
            Map<String, StringBuilder> hierarchies = new LinkedHashMap<>();
            for (ObjectNode child : assets.get().children(account, region, asset.get("assetId").asText(), null)) {
                String hierarchy = meta(child).path("parent").path("hierarchyId").asText();
                ObjectNode line = mapper.createObjectNode().put("sourceAssetId", asset.get("assetId").asText())
                        .put("targetAssetId", child.get("assetId").asText()).put("hierarchyId", hierarchy)
                        .put("associationType", "CHILD");
                hierarchies.computeIfAbsent(hierarchy, k -> new StringBuilder()).append(line).append('\n');
            }
            hierarchies.forEach((hierarchy, lines) -> put(bucket, prefix + "asset_hierarchy_metadata/"
                    + asset.get("assetId").asText() + "_" + hierarchy + ".ndjson",
                    lines.toString().getBytes(StandardCharsets.UTF_8), "application/x-ndjson"));
        }
        meta(settings).put("exportedThrough", until);
        save(account, region, settings);
    }

    private void put(String bucket, String key, byte[] body, String type) {
        s3.get().putObject(bucket, key, body, type, Map.of());
    }

    static String bucket(String timeSeriesId) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(timeSeriesId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 1);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] metadata(String account, String region, ObjectNode asset) {
        StringBuilder lines = new StringBuilder();
        ObjectNode model = store.get(account, region, SiteWiseStore.ASSET_MODEL, asset.get("assetModelId").asText())
                .orElse(null);
        for (JsonNode property : asset.path("assetProperties")) {
            lines.append(metadataLine(asset, model, property, null)).append('\n');
        }
        for (JsonNode composite : asset.path("assetCompositeModels")) {
            for (JsonNode property : composite.path("properties")) {
                lines.append(metadataLine(asset, model, property, composite)).append('\n');
            }
        }
        return lines.toString().getBytes(StandardCharsets.UTF_8);
    }

    private ObjectNode metadataLine(ObjectNode asset, ObjectNode model, JsonNode property, JsonNode composite) {
        String series = SiteWiseData.associatedId(asset.get("assetId").asText(), property.get("id").asText());
        ObjectNode line = mapper.createObjectNode();
        line.put("assetId", asset.get("assetId").asText());
        line.put("assetExternalId", asset.path("assetExternalId").asText(null));
        line.put("assetName", asset.get("assetName").asText());
        line.put("assetModelId", asset.get("assetModelId").asText());
        line.put("assetModelExternalId", model == null ? null : model.path("assetModelExternalId").asText(null));
        line.put("assetModelName", model == null ? null : model.path("assetModelName").asText(null));
        line.put("assetPropertyId", property.get("id").asText());
        line.put("assetPropertyExternalId", property.path("externalId").asText(null));
        line.put("assetPropertyName", property.get("name").asText());
        line.put("assetPropertyDataType", property.get("dataType").asText());
        line.put("assetPropertyUnit", property.path("unit").asText(null));
        line.put("assetPropertyAlias", property.path("alias").asText(null));
        line.put("timeSeriesId", series);
        line.put("timeSeriesBucket", bucket(series));
        line.put("assetArn", asset.get("assetArn").asText());
        line.put("assetCompositeModelDescription", composite == null ? null
                : composite.path("description").asText(null));
        line.put("assetCompositeModelName", composite == null ? null : composite.get("name").asText());
        line.put("assetCompositeModelType", composite == null ? null : composite.get("type").asText());
        line.put("assetCompositeModelId", composite == null ? null : composite.get("id").asText());
        line.put("assetCompositeModelExternalId", composite == null ? null
                : composite.path("externalId").asText(null));
        line.put("assetCreationDate", (long) asset.get("assetCreationDate").asDouble());
        line.put("assetLastUpdateDate", (long) asset.get("assetLastUpdateDate").asDouble());
        line.putNull("assetStatusErrorCode");
        line.putNull("assetStatusErrorMessage");
        line.put("assetStatusState", asset.path("assetStatus").path("state").asText());
        return line;
    }

    private static byte[] raw(String id, List<JsonNode> points) {
        List<GenericRecord> records = new ArrayList<>();
        for (JsonNode point : points) {
            GenericRecord record = new GenericData.Record(RAW);
            record.put("seriesId", id);
            record.put("timeInSeconds", point.get("t").asLong());
            record.put("offsetInNanos", point.get("n").asLong());
            record.put("quality", point.path("q").asText("GOOD"));
            JsonNode v = point.get("v");
            record.put("doubleValue", v.hasNonNull("doubleValue") ? v.get("doubleValue").asDouble() : null);
            record.put("stringValue", v.hasNonNull("stringValue") ? v.get("stringValue").asText() : null);
            record.put("integerValue", v.hasNonNull("integerValue") ? v.get("integerValue").asInt() : null);
            record.put("booleanValue", v.hasNonNull("booleanValue") ? v.get("booleanValue").asBoolean() : null);
            record.put("jsonValue", null);
            record.put("recordVersion", null);
            records.add(record);
        }
        return avro(RAW, records);
    }

    private static byte[] agg(String id, String quality, List<JsonNode> points) {
        Map<Long, List<Double>> minutes = new TreeMap<>();
        Map<Long, Integer> counts = new TreeMap<>();
        for (JsonNode point : points) {
            long minute = Math.floorDiv(point.get("t").asLong(), 60L) * 60L;
            counts.merge(minute, 1, Integer::sum);
            if (SiteWiseData.number(point.get("v")) instanceof Double d) {
                minutes.computeIfAbsent(minute, k -> new ArrayList<>()).add(d);
            }
        }
        List<GenericRecord> records = new ArrayList<>();
        for (Map.Entry<Long, Integer> minute : counts.entrySet()) {
            List<Double> values = minutes.getOrDefault(minute.getKey(), List.of());
            GenericRecord record = new GenericData.Record(AGG);
            record.put("seriesId", id);
            record.put("timeInSeconds", minute.getKey());
            record.put("offsetInNanos", 0L);
            record.put("quality", quality);
            record.put("resolution", "PT1M");
            record.put("count", (double) minute.getValue());
            double sum = values.stream().mapToDouble(Double::doubleValue).sum();
            record.put("average", values.isEmpty() ? null : sum / values.size());
            record.put("min", values.stream().mapToDouble(Double::doubleValue).min().stream().boxed().findFirst()
                    .orElse(null));
            record.put("max", values.stream().mapToDouble(Double::doubleValue).max().stream().boxed().findFirst()
                    .orElse(null));
            record.put("sum", values.isEmpty() ? null : sum);
            record.put("recordVersion", null);
            records.add(record);
        }
        return avro(AGG, records);
    }

    private static byte[] avro(Schema schema, List<GenericRecord> records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataFileWriter<GenericRecord> writer = new DataFileWriter<>(new GenericDatumWriter<>(schema))) {
            writer.setCodec(CodecFactory.snappyCodec());
            writer.create(schema, out);
            for (GenericRecord record : records) {
                writer.append(record);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not write Avro", e);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- encryption

    ObjectNode putDefaultEncryptionConfiguration(ObjectNode in, String region) {
        String account = store.account();
        String type = in.get("encryptionType").asText();
        ObjectNode encryption = mapper.createObjectNode().put("encryptionType", type);
        if ("KMS_BASED_ENCRYPTION".equals(type)) {
            String key = in.path("kmsKeyId").asText(null);
            if (key == null) {
                throw invalid("KMS_BASED_ENCRYPTION needs a kmsKeyId.");
            }
            String arn;
            try {
                arn = kms.get().describeKey(key, region).getArn();
            } catch (AwsException e) {
                throw invalid("The KMS key " + key + " doesn't exist.");
            }
            encryption.put("kmsKeyArn", arn);
        } else if (in.hasNonNull("kmsKeyId")) {
            throw invalid("A kmsKeyId is only for KMS_BASED_ENCRYPTION.");
        }
        ObjectNode settings = settings(account, region);
        settings.set("encryption", encryption);
        save(account, region, settings);
        ObjectNode out = encryption.deepCopy();
        out.putObject("configurationStatus").put("state", "ACTIVE");
        return out;
    }

    ObjectNode describeDefaultEncryptionConfiguration(ObjectNode in, String region) {
        String account = store.account();
        JsonNode encryption = settings(account, region).path("encryption");
        ObjectNode out = encryption.isMissingNode()
                ? mapper.createObjectNode().put("encryptionType", "SITEWISE_DEFAULT_ENCRYPTION")
                : (ObjectNode) encryption.deepCopy();
        out.putObject("configurationStatus").put("state", "ACTIVE");
        return out;
    }

    // ---------------------------------------------------------------- logging

    ObjectNode putLoggingOptions(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode settings = settings(account, region);
        String scope = in.hasNonNull("workspaceName") ? "logging:" + in.get("workspaceName").asText() : "logging";
        settings.set(scope, in.get("loggingOptions").deepCopy());
        save(account, region, settings);
        return mapper.createObjectNode();
    }

    ObjectNode describeLoggingOptions(ObjectNode in, String region) {
        String account = store.account();
        String scope = in.hasNonNull("workspaceName") ? "logging:" + in.get("workspaceName").asText() : "logging";
        JsonNode options = settings(account, region).path(scope);
        ObjectNode out = mapper.createObjectNode();
        out.set("loggingOptions", options.isMissingNode() ? mapper.createObjectNode().put("level", "OFF")
                : options.deepCopy());
        return out;
    }

    /** Writes an event to {@code /aws/iotsitewise} when the logging level lets it through. */
    void log(String account, String region, String level, ObjectNode event) {
        String configured = settings(account, region).path("logging").path("level").asText("OFF");
        if ("OFF".equals(configured) || "INFO".equals(level) && "ERROR".equals(configured)) {
            return;
        }
        try {
            if (!logs.get().logGroupExists(LOG_GROUP, region)) {
                logs.get().createLogGroupForAccount(account, LOG_GROUP, null, Map.of(), region);
            }
            String stream = LocalDate.now(ZoneOffset.UTC).toString();
            try {
                logs.get().createLogStreamForAccount(account, LOG_GROUP, stream, region);
            } catch (AwsException e) {
                if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                    throw e;
                }
            }
            event.put("level", level);
            event.put("timestamp", Instant.now().toString());
            logs.get().putLogEventsForAccount(account, LOG_GROUP, stream, List.of(Map.of(
                    "timestamp", System.currentTimeMillis(), "message", event.toString())), region);
        } catch (RuntimeException e) {
            LOG.debugv(e, "IoT SiteWise could not write its log in {0}", region);
        }
    }

    // ---------------------------------------------------------------- tags

    private ObjectNode tagged(String account, String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (RuntimeException e) {
            throw invalid("The ARN " + arn + " isn't valid.");
        }
        if (!SiteWiseStore.SERVICE.equals(parsed.service())) {
            throw invalid("The ARN " + arn + " isn't an IoT SiteWise ARN.");
        }
        String resource = parsed.resource();
        int slash = resource.indexOf('/');
        if (slash < 0) {
            throw notFound("The resource " + arn + " doesn't exist.");
        }
        String kind = resource.substring(0, slash);
        String id = resource.substring(slash + 1);
        return store.get(account, parsed.region(), kind, id)
                .orElseThrow(() -> notFound("The resource " + arn + " doesn't exist."));
    }

    private void saveTagged(String account, String arn, ObjectNode record) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        int slash = resource.indexOf('/');
        store.put(account, parsed.region(), resource.substring(0, slash), resource.substring(slash + 1), record);
    }

    ObjectNode listTagsForResource(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode record = tagged(account, region, in.get("resourceArn").asText());
        ObjectNode out = mapper.createObjectNode();
        out.set("tags", meta(record).path("tags").isObject() ? meta(record).get("tags").deepCopy()
                : mapper.createObjectNode());
        return out;
    }

    ObjectNode tagResource(ObjectNode in, String region) {
        String account = store.account();
        String arn = in.get("resourceArn").asText();
        ObjectNode record = tagged(account, region, arn);
        ObjectNode tags = meta(record).has("tags") ? (ObjectNode) meta(record).get("tags") : meta(record).putObject("tags");
        tags.setAll(SiteWiseStore.tagsOf(mapper, in.get("tags")));
        if (tags.size() > 50) {
            throw new AwsException("TooManyTagsException", "A resource can have at most 50 tags.", 400,
                    Map.of("resourceName", arn));
        }
        saveTagged(account, arn, record);
        return mapper.createObjectNode();
    }

    ObjectNode untagResource(ObjectNode in, String region) {
        String account = store.account();
        String arn = in.get("resourceArn").asText();
        ObjectNode record = tagged(account, region, arn);
        if (meta(record).has("tags")) {
            ObjectNode tags = (ObjectNode) meta(record).get("tags");
            ArrayNode keys = (ArrayNode) in.get("tagKeys");
            keys.forEach(key -> tags.remove(key.asText()));
        }
        saveTagged(account, arn, record);
        return mapper.createObjectNode();
    }
}
