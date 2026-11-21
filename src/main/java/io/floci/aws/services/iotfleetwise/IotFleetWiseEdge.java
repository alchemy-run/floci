package io.floci.aws.services.iotfleetwise;

import com.amazon.ion.IonSystem;
import com.amazon.ion.IonType;
import com.amazon.ion.IonWriter;
import com.amazon.ion.system.IonBinaryWriterBuilder;
import com.amazon.ion.system.IonSystemBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.iot.IotService;
import io.floci.aws.services.iot.IotTopicListener;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.xerial.snappy.Snappy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static io.floci.aws.services.iotfleetwise.IotFleetWiseModels.strings;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.CAMPAIGN;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.FLEET;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.VEHICLE;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.key;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.meta;

/**
 * IoT FleetWise's conversation with the Edge Agent on each vehicle, over AWS IoT MQTT. A vehicle's decoder manifest
 * and the collection schemes of its running campaigns are published, retained, on
 * {@code $aws/iotfleetwise/vehicles/<vehicle>/decoder_manifests} and {@code .../collection_schemes}; the agent's
 * check-ins on {@code .../checkins} report which documents it has enacted, and the data it collects on
 * {@code .../signals} is decoded and delivered to each campaign's destinations: Amazon S3 (processed data as JSON
 * or Parquet under Hive-style time partitions, and the raw messages as Amazon Ion) and AWS IoT topics.
 */
@ApplicationScoped
public class IotFleetWiseEdge implements IotTopicListener, Resettable {

    private static final Logger LOG = Logger.getLogger(IotFleetWiseEdge.class);
    static final String PREFIX = "$aws/iotfleetwise/vehicles/";
    /** How often a batch of processed data is tried before it is dropped. */
    private static final int MAX_DELIVERY_ATTEMPTS = 10;
    private static final Map<String, Integer> PRIMITIVE_TYPES = Map.ofEntries(Map.entry("BOOLEAN", 1),
            Map.entry("UINT8", 2), Map.entry("UINT16", 3), Map.entry("UINT32", 4), Map.entry("UINT64", 5),
            Map.entry("INT8", 6), Map.entry("INT16", 7), Map.entry("INT32", 8), Map.entry("INT64", 9),
            Map.entry("FLOAT", 10), Map.entry("DOUBLE", 11), Map.entry("STRING", 12), Map.entry("UNIX_TIMESTAMP", 5));
    private static final Map<String, Integer> ROS2_TYPES = Map.ofEntries(Map.entry("BOOL", 1), Map.entry("BYTE", 2),
            Map.entry("CHAR", 6), Map.entry("UINT8", 2), Map.entry("UINT16", 3), Map.entry("UINT32", 4),
            Map.entry("UINT64", 5), Map.entry("INT8", 6), Map.entry("INT16", 7), Map.entry("INT32", 8),
            Map.entry("INT64", 9), Map.entry("FLOAT32", 10), Map.entry("FLOAT64", 11));

    /** Processed records waiting for a destination's next object, per campaign destination. */
    private record Batch(String account, String region, String campaign, int destination) {
    }

    private final IotFleetWiseStore store;
    private final Instance<IotService> iot;
    private final Instance<S3Service> s3;
    private final Instance<FlociDuckClient> duck;
    private final Instance<CloudWatchLogsService> logs;
    private final IonSystem ion = IonSystemBuilder.standard().build();
    private final Map<Batch, List<ObjectNode>> batches = new ConcurrentHashMap<>();
    private final Map<Batch, Integer> attempts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "iotfleetwise-s3");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public IotFleetWiseEdge(IotFleetWiseStore store, Instance<IotService> iot, Instance<S3Service> s3,
                            Instance<FlociDuckClient> duck, Instance<CloudWatchLogsService> logs) {
        this.store = store;
        this.iot = iot;
        this.s3 = s3;
        this.duck = duck;
        this.logs = logs;
    }

    @PreDestroy
    void stop() {
        flusher.shutdownNow();
    }

    @Override
    public void clear() {
        batches.clear();
        attempts.clear();
    }

    // ---------------------------------------------------------------- cloud to edge

    /** Publishes a vehicle's decoder manifest and the collection schemes of its running campaigns. */
    void deploy(String account, String region, String vehicleName) {
        ObjectNode vehicle = store.get(account, key(region, VEHICLE, vehicleName)).orElse(null);
        if (vehicle == null) {
            return;
        }
        try {
            ObjectNode decoder = byArn(account, vehicle.path("decoderManifestArn").asText());
            ObjectNode model = byArn(account, vehicle.path("modelManifestArn").asText());
            ObjectNode catalog = byArn(account, model.path("signalCatalogArn").asText());
            Map<String, Integer> ids = IotFleetWiseModels.signalIds(catalog);
            Map<String, ObjectNode> tree = IotFleetWiseModels.tree(catalog);
            publish(region, vehicleName, "decoder_manifests", decoderManifest(decoder, ids, tree));
            IotFleetWiseProto.Writer schemes = new IotFleetWiseProto.Writer();
            for (ObjectNode campaign : campaignsFor(account, region, vehicleName)) {
                if ("RUNNING".equals(campaign.path("status").asText())) {
                    schemes.message(1, collectionScheme(campaign, decoder, ids, tree));
                }
            }
            schemes.uint(2, System.currentTimeMillis());
            publish(region, vehicleName, "collection_schemes", schemes.bytes());
        } catch (AwsException e) {
            LOG.debugv("IoT FleetWise could not deploy to {0}: {1}", vehicleName, e.getMessage());
        }
    }

    /** Redeploys every vehicle a campaign targets. */
    void deployCampaign(String account, String region, ObjectNode campaign) {
        for (String vehicle : targets(account, region, campaign.path("targetArn").asText())) {
            deploy(account, region, vehicle);
        }
    }

    /** Clears a deleted vehicle's retained documents. */
    void forget(String account, String region, String vehicleName) {
        for (String topic : List.of("decoder_manifests", "collection_schemes")) {
            try {
                iot.get().publish(PREFIX + vehicleName + "/" + topic, new byte[0], true, 1, region, null);
            } catch (RuntimeException e) {
                LOG.debugv("Could not clear {0}: {1}", topic, e.getMessage());
            }
        }
    }

    private void publish(String region, String vehicle, String topic, byte[] payload) {
        iot.get().publish(PREFIX + vehicle + "/" + topic, payload, true, 1, region, null);
    }

    private ObjectNode byArn(String account, String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        return store.get(account, parsed.region() + "/" + parsed.resource())
                .orElseThrow(() -> IotFleetWiseStore.notFound("resource", arn));
    }

    List<ObjectNode> campaignsFor(String account, String region, String vehicle) {
        Set<String> targets = new LinkedHashSet<>();
        targets.add(store.arn(account, region, VEHICLE, vehicle));
        for (ObjectNode fleet : store.list(account, region, FLEET)) {
            if (strings(fleet.path("_").path("vehicles")).contains(vehicle)) {
                targets.add(fleet.path("arn").asText());
            }
        }
        List<ObjectNode> campaigns = new ArrayList<>();
        for (ObjectNode campaign : store.list(account, region, CAMPAIGN)) {
            if (targets.contains(campaign.path("targetArn").asText())) {
                campaigns.add(campaign);
            }
        }
        return campaigns;
    }

    private List<String> targets(String account, String region, String targetArn) {
        if (targetArn.contains(":" + VEHICLE + "/")) {
            return List.of(IotFleetWiseModels.nameOf(targetArn, VEHICLE));
        }
        return store.get(account, key(region, FLEET, IotFleetWiseModels.nameOf(targetArn, FLEET)))
                .map(fleet -> strings(fleet.path("_").path("vehicles"))).orElse(List.of());
    }

    /** A vehicle's status in a campaign, from the campaign's state and the vehicle's last check-in. */
    String status(String account, String region, String vehicleName, ObjectNode campaign) {
        String status = campaign.path("status").asText();
        if ("CREATING".equals(status) || "WAITING_FOR_APPROVAL".equals(status)) {
            return "CREATED";
        }
        if ("SUSPENDED".equals(status)) {
            return "SUSPENDED";
        }
        ObjectNode vehicle = store.get(account, key(region, VEHICLE, vehicleName)).orElse(null);
        if (vehicle == null) {
            return "DELETING";
        }
        List<String> enacted = strings(vehicle.path("_").path("checkin").path("documentSyncIds"));
        return enacted.contains(campaign.path("arn").asText()) ? "HEALTHY" : "READY";
    }

    byte[] decoderManifest(ObjectNode decoder, Map<String, Integer> ids, Map<String, ObjectNode> tree) {
        Map<String, JsonNode> interfaces = IotFleetWiseModels.interfaces(decoder);
        IotFleetWiseProto.Writer manifest = new IotFleetWiseProto.Writer().string(1, decoder.path("arn").asText());
        int[] nextType = {1};
        for (JsonNode signal : IotFleetWiseModels.signals(decoder).values()) {
            String name = signal.path("fullyQualifiedName").asText();
            Integer id = ids.get(name);
            if (id == null) {
                continue;
            }
            int primitive = PRIMITIVE_TYPES.getOrDefault(dataType(tree, name), 11);
            String interfaceId = signal.path("interfaceId").asText();
            switch (signal.path("type").asText()) {
                case "CAN_SIGNAL" -> {
                    JsonNode can = signal.path("canSignal");
                    manifest.message(2, new IotFleetWiseProto.Writer().uint(1, id).string(2, interfaceId)
                            .uint(3, can.path("messageId").asLong()).bool(4, can.path("isBigEndian").asBoolean())
                            .bool(5, can.path("isSigned").asBoolean()).uint(6, can.path("startBit").asLong())
                            .dbl(7, can.path("offset").asDouble()).dbl(8, can.path("factor").asDouble())
                            .uint(9, can.path("length").asLong()).uint(10, primitive)
                            .uint(11, "FLOATING_POINT".equals(can.path("signalValueType").asText()) ? 1 : 0));
                }
                case "OBD_SIGNAL" -> {
                    JsonNode obd = signal.path("obdSignal");
                    manifest.message(3, new IotFleetWiseProto.Writer().uint(1, id).string(2, interfaceId)
                            .uint(3, obd.path("pidResponseLength").asLong())
                            .uint(4, obd.path("serviceMode").asLong()).uint(5, obd.path("pid").asLong())
                            .dbl(6, obd.path("scaling").asDouble()).dbl(7, obd.path("offset").asDouble())
                            .uint(8, obd.path("startByte").asLong()).uint(9, obd.path("byteLength").asLong())
                            .uint(10, obd.path("bitRightShift").asLong()).uint(11, obd.path("bitMaskLength").asLong(8))
                            .uint(12, primitive)
                            .uint(13, "FLOATING_POINT".equals(obd.path("signalValueType").asText()) ? 1 : 0)
                            .bool(14, obd.path("isSigned").asBoolean()));
                }
                case "CUSTOM_DECODING_SIGNAL" -> manifest.message(6, new IotFleetWiseProto.Writer().uint(1, id)
                        .string(2, interfaceId).string(3, signal.path("customDecodingSignal").path("id").asText())
                        .uint(4, primitive));
                case "MESSAGE_SIGNAL" -> {
                    JsonNode message = signal.path("messageSignal");
                    int root = complexType(manifest, message.path("structuredMessage"), nextType);
                    manifest.message(5, new IotFleetWiseProto.Writer().uint(1, id).string(2, interfaceId)
                            .string(3, message.path("topicName").asText()).uint(4, root));
                }
                default -> {
                }
            }
        }
        return manifest.bytes();
    }

    /** Writes a ROS 2 message definition's complex types, returning the ID of its root type. */
    private int complexType(IotFleetWiseProto.Writer manifest, JsonNode message, int[] nextType) {
        int id = nextType[0]++;
        IotFleetWiseProto.Writer type = new IotFleetWiseProto.Writer().uint(1, id);
        if (message.has("primitiveMessageDefinition")) {
            JsonNode primitive = message.path("primitiveMessageDefinition").path("ros2PrimitiveMessageDefinition");
            String ros = primitive.path("primitiveType").asText();
            if ("STRING".equals(ros) || "WSTRING".equals(ros)) {
                type.message(5, new IotFleetWiseProto.Writer().uint(1, "WSTRING".equals(ros) ? 1 : 0)
                        .uint(2, primitive.path("upperBound").asLong()));
            } else {
                type.message(2, new IotFleetWiseProto.Writer().uint(1, ROS2_TYPES.getOrDefault(ros, 11))
                        .dbl(2, primitive.path("scaling").asDouble(1)).dbl(3, primitive.path("offset").asDouble()));
            }
        } else if (message.has("structuredMessageListDefinition")) {
            JsonNode list = message.path("structuredMessageListDefinition");
            int member = complexType(manifest, list.path("memberType"), nextType);
            long size = "FIXED_CAPACITY".equals(list.path("listType").asText()) ? list.path("capacity").asLong()
                    : -1;
            type.message(4, new IotFleetWiseProto.Writer().uint(1, size).uint(2, member));
        } else {
            IotFleetWiseProto.Writer struct = new IotFleetWiseProto.Writer();
            for (JsonNode field : message.path("structuredMessageDefinition")) {
                int member = complexType(manifest, field.path("dataType"), nextType);
                struct.message(1, new IotFleetWiseProto.Writer().uint(1, member));
            }
            type.message(3, struct);
        }
        manifest.message(4, type);
        return id;
    }

    private static String dataType(Map<String, ObjectNode> tree, String name) {
        ObjectNode node = tree.get(name);
        return node == null ? "DOUBLE" : node.path(IotFleetWiseModels.type(node)).path("dataType").asText("DOUBLE");
    }

    IotFleetWiseProto.Writer collectionScheme(ObjectNode campaign, ObjectNode decoder, Map<String, Integer> ids,
                                              Map<String, ObjectNode> tree) {
        IotFleetWiseProto.Writer scheme = new IotFleetWiseProto.Writer()
                .string(1, campaign.path("arn").asText()).string(2, decoder.path("arn").asText())
                .uint(3, (long) (campaign.path("startTime").asDouble() * 1000))
                .uint(4, (long) (campaign.path("expiryTime").asDouble() * 1000));
        JsonNode collection = campaign.path("collectionScheme");
        if (collection.has("timeBasedCollectionScheme")) {
            scheme.message(5, new IotFleetWiseProto.Writer().uint(1,
                    collection.path("timeBasedCollectionScheme").path("periodMs").asLong()));
        } else {
            JsonNode condition = collection.path("conditionBasedCollectionScheme");
            scheme.message(6, new IotFleetWiseProto.Writer()
                    .uint(1, condition.path("minimumTriggerIntervalMs").asLong())
                    .uint(2, condition.path("conditionLanguageVersion").asLong(1))
                    .message(3, IotFleetWiseExpressions.compile(condition.path("expression").asText(), ids,
                            "expression").node())
                    .uint(4, "RISING_EDGE".equals(condition.path("triggerMode").asText()) ? 1 : 0));
        }
        scheme.uint(7, campaign.path("postTriggerCollectionDuration").asLong())
                .bool(8, "SEND_ACTIVE_DTCS".equals(campaign.path("diagnosticsMode").asText()));
        List<String> partitions = new ArrayList<>();
        campaign.path("dataPartitions").forEach(partition -> partitions.add(partition.path("id").asText()));
        for (JsonNode signal : campaign.path("signalsToCollect")) {
            int partition = partitions.indexOf(signal.path("dataPartitionId").asText(""));
            for (String name : IotFleetWiseService.expand(tree, signal.path("name").asText())) {
                scheme.message(9, new IotFleetWiseProto.Writer().uint(1, ids.get(name))
                        .uint(2, signal.path("maxSampleCount").asLong())
                        .uint(3, signal.path("minimumSamplingIntervalMs").asLong())
                        .uint(7, partition < 0 ? 0 : partition + 1));
            }
        }
        scheme.bool(11, "TO_DISK".equals(campaign.path("spoolingMode").asText()))
                .bool(12, "SNAPPY".equals(campaign.path("compression").asText()))
                .uint(13, campaign.path("priority").asLong());
        if (!partitions.isEmpty()) {
            IotFleetWiseProto.Writer storeAndForward = new IotFleetWiseProto.Writer();
            for (JsonNode partition : campaign.path("dataPartitions")) {
                JsonNode storage = partition.path("storageOptions");
                IotFleetWiseProto.Writer configuration = new IotFleetWiseProto.Writer().message(1,
                        new IotFleetWiseProto.Writer().uint(1, bytes(storage.path("maximumSize")))
                                .string(2, storage.path("storageLocation").asText())
                                .uint(3, seconds(storage.path("minimumTimeToLive"))));
                if (partition.path("uploadOptions").has("expression")) {
                    configuration.message(2, new IotFleetWiseProto.Writer().message(1, IotFleetWiseExpressions
                            .compile(partition.path("uploadOptions").path("expression").asText(), ids, "expression")
                            .node()));
                }
                storeAndForward.message(1, configuration);
            }
            scheme.message(17, storeAndForward);
        }
        for (JsonNode fetch : campaign.path("signalsToFetch")) {
            IotFleetWiseProto.Writer information = new IotFleetWiseProto.Writer()
                    .uint(1, ids.get(fetch.path("fullyQualifiedName").asText()));
            JsonNode config = fetch.path("signalFetchConfig");
            if (config.has("timeBased")) {
                information.message(2, new IotFleetWiseProto.Writer()
                        .uint(2, config.path("timeBased").path("executionFrequencyMs").asLong()));
            } else {
                JsonNode condition = config.path("conditionBased");
                information.message(3, new IotFleetWiseProto.Writer()
                        .message(1, IotFleetWiseExpressions.compile(condition.path("conditionExpression").asText(),
                                ids, "conditionExpression").node())
                        .uint(2, "RISING_EDGE".equals(condition.path("triggerMode").asText()) ? 1 : 0));
            }
            information.uint(4, fetch.path("conditionLanguageVersion").asLong(1));
            for (JsonNode action : fetch.path("actions")) {
                information.message(5, IotFleetWiseExpressions.compile(action.asText(), ids, "actions").node());
            }
            scheme.message(18, information);
        }
        return scheme.string(19, campaign.path("arn").asText());
    }

    private static long bytes(JsonNode size) {
        long value = size.path("value").asLong();
        return switch (size.path("unit").asText()) {
            case "TB" -> value << 40;
            case "GB" -> value << 30;
            default -> value << 20;
        };
    }

    private static long seconds(JsonNode ttl) {
        long value = ttl.path("value").asLong();
        return switch (ttl.path("unit").asText()) {
            case "WEEKS" -> value * 604800;
            case "DAYS" -> value * 86400;
            default -> value * 3600;
        };
    }

    // ---------------------------------------------------------------- edge to cloud

    @Override
    public void onPublish(String topic, byte[] payload, String region) {
        if (!topic.startsWith(PREFIX) || !store.config().enabled()) {
            return;
        }
        String rest = topic.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return;
        }
        String vehicleName = rest.substring(0, slash);
        String kind = rest.substring(slash + 1);
        if (!"checkins".equals(kind) && !"signals".equals(kind)) {
            return;
        }
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : store.everywhere(key(region, VEHICLE, vehicleName)::equals)) {
            String account = entry.accountId();
            try {
                RequestScopes.runAs(account, region, () -> {
                    if ("checkins".equals(kind)) {
                        checkin(account, region, entry.value(), payload);
                    } else {
                        signals(account, region, entry.value(), payload);
                    }
                });
            } catch (RuntimeException e) {
                LOG.debugv(e, "IoT FleetWise dropped a {0} message from {1}", kind, vehicleName);
                logError(account, region, "The " + kind + " message from vehicle " + vehicleName
                        + " was dropped: " + e.getMessage());
            }
        }
    }

    private void checkin(String account, String region, ObjectNode vehicle, byte[] payload) {
        List<String> ids = new ArrayList<>();
        long timestamp = 0;
        for (IotFleetWiseProto.Field field : IotFleetWiseProto.read(payload)) {
            if (field.number() == 1) {
                ids.add(field.asString());
            } else if (field.number() == 2) {
                timestamp = field.asLong();
            }
        }
        ObjectNode checkin = meta(vehicle).putObject("checkin");
        ArrayNode list = checkin.putArray("documentSyncIds");
        ids.forEach(list::add);
        checkin.put("timestampMs", timestamp).put("receivedAt", Instant.now().toString());
        store.put(account, key(region, VEHICLE, vehicle.path("vehicleName").asText()), vehicle);
    }

    private void signals(String account, String region, ObjectNode vehicle, byte[] payload) {
        byte[] message = uncompressOrRaw(payload);
        Map<Integer, IotFleetWiseProto.Field> fields = IotFleetWiseProto.fields(message);
        String campaignArn = fields.containsKey(1) ? fields.get(1).asString() : "";
        String vehicleName = vehicle.path("vehicleName").asText();
        ObjectNode campaign = store.get(account, key(region, CAMPAIGN,
                IotFleetWiseModels.nameOf(campaignArn, CAMPAIGN))).orElse(null);
        if (campaign == null || !campaignArn.equals(campaign.path("arn").asText())) {
            throw new IllegalArgumentException("the campaign " + campaignArn + " doesn't exist");
        }
        if (!"RUNNING".equals(campaign.path("status").asText())
                || campaignsFor(account, region, vehicleName).stream()
                .noneMatch(c -> campaignArn.equals(c.path("arn").asText()))) {
            throw new IllegalArgumentException("the campaign " + campaign.path("name").asText()
                    + " isn't running on the vehicle");
        }
        ObjectNode catalog = byArn(account, campaign.path("signalCatalogArn").asText());
        Map<Integer, String> names = new HashMap<>();
        IotFleetWiseModels.signalIds(catalog).forEach((name, id) -> names.put(id, name));
        Map<String, ObjectNode> tree = IotFleetWiseModels.tree(catalog);
        long eventId = fields.containsKey(3) ? fields.get(3).asLong() : 0;
        long eventTime = fields.containsKey(4) ? fields.get(4).asLong() : System.currentTimeMillis();
        List<ObjectNode> records = new ArrayList<>();
        List<String> dtcs = new ArrayList<>();
        for (IotFleetWiseProto.Field field : IotFleetWiseProto.read(message)) {
            if (field.number() == 5) {
                ObjectNode record = record(vehicle, campaign, eventId, eventTime, field.asBytes(), names, tree);
                if (record != null) {
                    records.add(record);
                }
            } else if (field.number() == 6) {
                for (IotFleetWiseProto.Field dtc : IotFleetWiseProto.read(field.asBytes())) {
                    if (dtc.number() == 2) {
                        dtcs.add(dtc.asString());
                    }
                }
            }
        }
        JsonNode destinations = campaign.path("dataDestinationConfigs");
        for (int i = 0; i < destinations.size(); i++) {
            JsonNode destination = destinations.get(i);
            if (destination.has("s3Config")) {
                List<ObjectNode> batch = batches.computeIfAbsent(new Batch(account, region,
                        campaign.path("name").asText(), i), ignored -> {
                            flusher.schedule(this::flushAll, Math.max(0, store.config().s3FlushSeconds()),
                                    TimeUnit.SECONDS);
                            return new ArrayList<>();
                        });
                synchronized (batch) {
                    batch.addAll(records);
                }
                rawData(destination.path("s3Config"), vehicleName, campaign, eventId, eventTime, records, dtcs);
            } else if (destination.has("mqttTopicConfig")) {
                String topicArn = destination.path("mqttTopicConfig").path("mqttTopicArn").asText();
                String topic = topicArn.substring(topicArn.indexOf(":topic/") + 7);
                ArrayNode array = store.mapper().createArrayNode();
                records.forEach(array::add);
                iot.get().publish(topic, array.toString().getBytes(StandardCharsets.UTF_8), false, 0, region, null);
            }
        }
    }

    /** The payload, snappy-uncompressed when the campaign has the agent compress what it sends. */
    private static byte[] uncompressOrRaw(byte[] payload) {
        try {
            if (!Snappy.isValidCompressedBuffer(payload)) {
                return payload;
            }
            byte[] uncompressed = Snappy.uncompress(payload);
            IotFleetWiseProto.read(uncompressed);
            return uncompressed;
        } catch (IOException | IllegalArgumentException e) {
            return payload;
        }
    }

    /** One captured signal as a processed record, typed by the catalog's data type. */
    private ObjectNode record(ObjectNode vehicle, ObjectNode campaign, long eventId, long eventTime, byte[] captured,
                              Map<Integer, String> names, Map<String, ObjectNode> tree) {
        long relative = 0;
        int signalId = -1;
        Double number = null;
        String text = null;
        for (IotFleetWiseProto.Field field : IotFleetWiseProto.read(captured)) {
            switch (field.number()) {
                case 1 -> relative = IotFleetWiseProto.zigzag(field.asLong());
                case 2 -> signalId = (int) field.asLong();
                case 10 -> {
                    for (IotFleetWiseProto.Field inner : IotFleetWiseProto.read(field.asBytes())) {
                        if (inner.number() == 1) {
                            signalId = (int) inner.asLong();
                        }
                    }
                }
                case 3 -> number = field.asDouble();
                case 4 -> text = field.asString();
                default -> {
                }
            }
        }
        String name = names.get(signalId);
        if (name == null) {
            return null;
        }
        ObjectNode record = store.mapper().createObjectNode();
        record.put("vehicleName", vehicle.path("vehicleName").asText()).put("campaignName",
                campaign.path("name").asText()).put("eventId", String.valueOf(eventId))
                .put("time", eventTime + relative).put("name", name);
        for (JsonNode dimension : campaign.path("dataExtraDimensions")) {
            record.put(dimension.asText(), vehicle.path("attributes").path(dimension.asText()).asText(null));
        }
        String type = dataType(tree, name);
        record.putNull("measure_value_BIGINT").putNull("measure_value_DOUBLE").putNull("measure_value_BOOLEAN")
                .putNull("measure_value_VARCHAR");
        double value = number == null ? 0 : number;
        if (text != null || "STRING".equals(type)) {
            record.put("measure_value_VARCHAR", text != null ? text : String.valueOf(value));
        } else if ("BOOLEAN".equals(type)) {
            record.put("measure_value_BOOLEAN", value != 0);
        } else if (type.startsWith("INT") || type.startsWith("UINT") || "UNIX_TIMESTAMP".equals(type)) {
            record.put("measure_value_BIGINT", (long) value);
        } else {
            record.put("measure_value_DOUBLE", value);
        }
        return record;
    }

    /** The message itself, as Amazon Ion, under {@code raw-data/<vehicle>/}. */
    private void rawData(JsonNode s3Config, String vehicle, ObjectNode campaign, long eventId, long eventTime,
                         List<ObjectNode> records, List<String> dtcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (IonWriter writer = IonBinaryWriterBuilder.standard().build(out)) {
            writer.stepIn(IonType.STRUCT);
            writer.setFieldName("vehicleName");
            writer.writeString(vehicle);
            writer.setFieldName("campaignName");
            writer.writeString(campaign.path("name").asText());
            writer.setFieldName("eventId");
            writer.writeInt(eventId);
            writer.setFieldName("eventTime");
            writer.writeInt(eventTime);
            writer.setFieldName("signals");
            writer.stepIn(IonType.LIST);
            for (ObjectNode record : records) {
                writer.writeValue(ion.singleValue(record.toString()));
            }
            writer.stepOut();
            writer.setFieldName("activeDtcCodes");
            writer.stepIn(IonType.LIST);
            for (String dtc : dtcs) {
                writer.writeString(dtc);
            }
            writer.stepOut();
            writer.stepOut();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String key = prefix(s3Config) + "raw-data/" + vehicle + "/" + eventId + "-" + eventTime + ".10n";
        put(s3Config, key, out.toByteArray(), "application/octet-stream");
    }

    private void flushAll() {
        for (Batch batch : new ArrayList<>(batches.keySet())) {
            List<ObjectNode> records = batches.remove(batch);
            if (records == null || records.isEmpty()) {
                continue;
            }
            try {
                RequestScopes.runAs(batch.account(), batch.region(), () -> flush(batch, records));
                attempts.remove(batch);
            } catch (RuntimeException e) {
                int attempt = attempts.merge(batch, 1, Integer::sum);
                LOG.warnv(e, "IoT FleetWise could not deliver campaign {0}''s data (attempt {1})", batch.campaign(),
                        attempt);
                logError(batch.account(), batch.region(), "Campaign " + batch.campaign()
                        + "'s data couldn't be delivered to Amazon S3: " + e.getMessage());
                if (attempt < MAX_DELIVERY_ATTEMPTS) {
                    // Keep the records for the next attempt, ahead of any that arrived meanwhile.
                    batches.merge(batch, records, (newer, older) -> {
                        List<ObjectNode> merged = new ArrayList<>(older);
                        merged.addAll(newer);
                        return merged;
                    });
                    flusher.schedule(this::flushAll, Math.max(1, store.config().s3FlushSeconds()), TimeUnit.SECONDS);
                } else {
                    attempts.remove(batch);
                }
            }
        }
    }

    /** Writes a batch of processed records as one object under {@code processed-data/year=/month=/date=/hour=/}. */
    private void flush(Batch batch, List<ObjectNode> records) {
        ObjectNode campaign = store.get(batch.account(), key(batch.region(), CAMPAIGN, batch.campaign()))
                .orElse(null);
        if (campaign == null || campaign.path("dataDestinationConfigs").size() <= batch.destination()) {
            return;
        }
        JsonNode s3Config = campaign.path("dataDestinationConfigs").get(batch.destination()).path("s3Config");
        ZonedDateTime at = Instant.now().atZone(ZoneOffset.UTC);
        String directory = prefix(s3Config) + String.format("processed-data/year=%04d/month=%02d/date=%02d/hour=%02d/",
                at.getYear(), at.getMonthValue(), at.getDayOfMonth(), at.getHour());
        boolean gzip = "GZIP".equals(s3Config.path("storageCompressionFormat").asText("GZIP"));
        String id = UUID.randomUUID().toString();
        StringBuilder lines = new StringBuilder();
        records.forEach(record -> lines.append(record).append('\n'));
        byte[] json = lines.toString().getBytes(StandardCharsets.UTF_8);
        if ("JSON".equals(s3Config.path("dataFormat").asText("PARQUET"))) {
            put(s3Config, directory + "part-0000-" + id + (gzip ? ".gz.json" : ".json"), gzip ? gzip(json) : json,
                    "application/json");
            return;
        }
        String bucket = bucket(s3Config);
        String staging = directory + ".staging-" + id + ".json";
        put(s3Config, staging, json, "application/json");
        try {
            duck.get().execute("COPY (SELECT * FROM read_json_auto('s3://" + bucket + "/" + staging
                    + "', format = 'newline_delimited')) TO 's3://" + bucket + "/" + directory + "part-0000-" + id
                    + (gzip ? ".gz.parquet" : ".parquet") + "' (FORMAT PARQUET, COMPRESSION "
                    + (gzip ? "GZIP" : "UNCOMPRESSED") + ")", null, null, batch.account());
        } finally {
            s3.get().deleteObject(bucket, staging);
        }
    }

    private void put(JsonNode s3Config, String key, byte[] body, String type) {
        s3.get().putObject(bucket(s3Config), key, body, type, Map.of());
    }

    private static String bucket(JsonNode s3Config) {
        String arn = s3Config.path("bucketArn").asText();
        return arn.substring(arn.lastIndexOf(':') + 1);
    }

    private static String prefix(JsonNode s3Config) {
        String prefix = s3Config.path("prefix").asText("");
        return prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(data);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** Logs an ingestion error to the account's log group when its logging options ask for errors. */
    private void logError(String account, String region, String message) {
        JsonNode delivery = store.get(account, key(region, "settings", "logging"))
                .map(options -> options.path("cloudWatchLogDelivery")).orElse(null);
        if (delivery == null || !"ERROR".equals(delivery.path("logType").asText())) {
            return;
        }
        String group = delivery.path("logGroupName").asText();
        String stream = "iotfleetwise-errors";
        try {
            RequestScopes.runAs(account, region, () -> {
                try {
                    logs.get().createLogStreamForAccount(account, group, stream, region);
                } catch (AwsException e) {
                    if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                        throw e;
                    }
                }
                ObjectNode line = store.mapper().createObjectNode().put("logLevel", "ERROR").put("message", message)
                        .put("accountId", account).put("timestamp", Instant.now().toString());
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("timestamp", System.currentTimeMillis());
                event.put("message", line.toString());
                logs.get().putLogEventsForAccount(account, group, stream, List.of(event), region);
            });
        } catch (RuntimeException e) {
            LOG.debugv("IoT FleetWise could not log to {0}: {1}", group, e.getMessage());
        }
    }
}
