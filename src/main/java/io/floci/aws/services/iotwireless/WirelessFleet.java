package io.floci.aws.services.iotwireless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.iotwireless.WirelessStore.DESTINATION;
import static io.floci.aws.services.iotwireless.WirelessStore.DEVICE;
import static io.floci.aws.services.iotwireless.WirelessStore.DEVICE_PROFILE;
import static io.floci.aws.services.iotwireless.WirelessStore.FUOTA_TASK;
import static io.floci.aws.services.iotwireless.WirelessStore.GATEWAY;
import static io.floci.aws.services.iotwireless.WirelessStore.IMPORT_TASK;
import static io.floci.aws.services.iotwireless.WirelessStore.MULTICAST_GROUP;
import static io.floci.aws.services.iotwireless.WirelessStore.NETWORK_ANALYZER;
import static io.floci.aws.services.iotwireless.WirelessStore.PARTNER_ACCOUNT;
import static io.floci.aws.services.iotwireless.WirelessStore.TASK_DEFINITION;
import static io.floci.aws.services.iotwireless.WirelessStore.conflict;
import static io.floci.aws.services.iotwireless.WirelessStore.meta;
import static io.floci.aws.services.iotwireless.WirelessStore.notFound;
import static io.floci.aws.services.iotwireless.WirelessStore.validation;

/**
 * IoT Wireless at fleet scale: gateway update tasks delivered over CUPS; Sidewalk partner accounts and device import
 * tasks; multicast groups and their sessions, set up on each device with the LoRaWAN Remote Multicast Setup protocol
 * (McGroupSetupReq on FPort 200) and sent to through the participating gateways on RX2; FUOTA tasks, which read the
 * firmware image from S3 and send it as the Fragmented Data Block Transport protocol's data fragments (FPort 201);
 * and network analyzer configurations.
 */
@ApplicationScoped
public class WirelessFleet {

    private static final Logger LOG = Logger.getLogger(WirelessFleet.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WirelessStore store;
    private final ObjectMapper mapper;
    private final Instance<WirelessService> service;
    private final Instance<WirelessNetwork> network;
    private final Instance<S3Service> s3;
    private final ScheduledExecutorService runner = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "iotwireless-fleet");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public WirelessFleet(WirelessStore store, Instance<WirelessService> service, Instance<WirelessNetwork> network,
                         Instance<S3Service> s3) {
        this.store = store;
        this.mapper = store.mapper();
        this.service = service;
        this.network = network;
        this.s3 = s3;
    }

    @PreDestroy
    void stop() {
        runner.shutdownNow();
    }

    private ObjectNode require(String account, String region, String kind, String id) {
        return store.get(account, region, kind, id).orElseThrow(() -> notFound(kind, id));
    }

    private static ObjectNode tagsIn(ObjectMapper mapper, JsonNode in) {
        return WirelessStore.tagsOf(mapper, in.get("Tags"));
    }

    // ---------------------------------------------------------------- gateway update tasks

    ObjectNode createWirelessGatewayTaskDefinition(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateWirelessGatewayTaskDefinition", in);
        if (repeated != null) {
            return repeated;
        }
        JsonNode update = in.path("Update");
        if (update.has("UpdateDataSource")) {
            s3Location(update.get("UpdateDataSource").asText());
        }
        String id = WirelessStore.uuid();
        ObjectNode definition = store.record();
        definition.put("Id", id);
        definition.put("AutoCreateTasks", in.get("AutoCreateTasks").asBoolean());
        if (in.hasNonNull("Name")) {
            definition.put("Name", in.get("Name").asText());
        }
        if (in.has("Update")) {
            definition.set("Update", update.deepCopy());
        }
        definition.put("Arn", store.arn(account, region, TASK_DEFINITION, id));
        meta(definition).set("tags", tagsIn(mapper, in));
        store.put(account, region, TASK_DEFINITION, id, definition);
        if (definition.get("AutoCreateTasks").asBoolean()) {
            for (ObjectNode gateway : store.list(account, region, GATEWAY)) {
                if (versionMatches(meta(gateway).path("net").path("version"),
                        update.path("LoRaWAN").path("CurrentVersion"))) {
                    createTask(account, region, gateway, id);
                }
            }
        }
        ObjectNode out = mapper.createObjectNode().put("Id", id).put("Arn", definition.get("Arn").asText());
        store.remember(account, region, "CreateWirelessGatewayTaskDefinition", in, out);
        return out;
    }

    private static boolean versionMatches(JsonNode gateway, JsonNode expected) {
        if (!expected.isObject() || !gateway.isObject()) {
            return false;
        }
        for (String field : List.of("PackageVersion", "Model", "Station")) {
            if (expected.hasNonNull(field) && !expected.get(field).asText().equals(gateway.path(field).asText())) {
                return false;
            }
        }
        return true;
    }

    private static String[] s3Location(String uri) {
        if (uri.startsWith("s3://")) {
            String rest = uri.substring(5);
            int slash = rest.indexOf('/');
            if (slash > 0) {
                return new String[] {rest.substring(0, slash), rest.substring(slash + 1)};
            }
        }
        if (AwsArnUtils.isArn(uri)) {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(uri);
            if ("s3".equals(arn.service()) && arn.resource().contains("/")) {
                return new String[] {arn.resource().substring(0, arn.resource().indexOf('/')),
                    arn.resource().substring(arn.resource().indexOf('/') + 1)};
            }
        }
        throw validation("The S3 location " + uri + " is an s3:// URI or an S3 object ARN.");
    }

    ObjectNode getWirelessGatewayTaskDefinition(ObjectNode in, String region) {
        ObjectNode view = WirelessStore.view(require(store.account(), region, TASK_DEFINITION, in.get("Id").asText()));
        view.remove("Id");
        return view;
    }

    ObjectNode listWirelessGatewayTaskDefinitions(ObjectNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode definition : store.list(store.account(), region, TASK_DEFINITION)) {
            ObjectNode item = mapper.createObjectNode().put("Id", definition.get("Id").asText());
            JsonNode lorawan = definition.path("Update").path("LoRaWAN");
            if (lorawan.isObject()) {
                ObjectNode entry = item.putObject("LoRaWAN");
                for (String field : List.of("CurrentVersion", "UpdateVersion")) {
                    if (lorawan.has(field)) {
                        entry.set(field, lorawan.get(field).deepCopy());
                    }
                }
            }
            items.add(item.put("Arn", definition.get("Arn").asText()));
        }
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "TaskDefinitions");
        return out;
    }

    ObjectNode deleteWirelessGatewayTaskDefinition(ObjectNode in, String region) {
        String account = store.account();
        String id = in.get("Id").asText();
        require(account, region, TASK_DEFINITION, id);
        store.delete(account, region, TASK_DEFINITION, id);
        return mapper.createObjectNode();
    }

    private ObjectNode createTask(String account, String region, ObjectNode gateway, String definitionId) {
        ObjectNode task = mapper.createObjectNode();
        task.put("WirelessGatewayId", gateway.get("Id").asText());
        task.put("WirelessGatewayTaskDefinitionId", definitionId);
        task.put("TaskCreatedAt", WirelessStore.now());
        task.put("Status", "PENDING");
        meta(gateway).set("task", task);
        store.put(account, region, GATEWAY, gateway.get("Id").asText(), gateway);
        return task;
    }

    ObjectNode createWirelessGatewayTask(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode gateway = service.get().gateway(account, region, in.get("Id").asText());
        String definitionId = in.get("WirelessGatewayTaskDefinitionId").asText();
        require(account, region, TASK_DEFINITION, definitionId);
        JsonNode existing = meta(gateway).path("task");
        if (existing.isObject() && List.of("PENDING", "IN_PROGRESS", "FIRST_RETRY", "SECOND_RETRY")
                .contains(existing.path("Status").asText())) {
            throw conflict("Gateway " + gateway.get("Id").asText() + " already has a task in progress.",
                    gateway.get("Id").asText(), GATEWAY);
        }
        ObjectNode task = createTask(account, region, gateway, definitionId);
        return mapper.createObjectNode().put("WirelessGatewayTaskDefinitionId", definitionId)
                .put("Status", task.get("Status").asText());
    }

    ObjectNode getWirelessGatewayTask(ObjectNode in, String region) {
        ObjectNode gateway = service.get().gateway(store.account(), region, in.get("Id").asText());
        JsonNode task = meta(gateway).path("task");
        if (!task.isObject()) {
            throw notFound("WirelessGatewayTask", gateway.get("Id").asText());
        }
        ObjectNode out = task.deepCopy();
        String last = meta(gateway).path("net").path("lastUplinkReceivedAt").asText(null);
        if (last != null) {
            out.put("LastUplinkReceivedAt", last);
        }
        return out;
    }

    ObjectNode deleteWirelessGatewayTask(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode gateway = service.get().gateway(account, region, in.get("Id").asText());
        if (!meta(gateway).has("task")) {
            throw notFound("WirelessGatewayTask", gateway.get("Id").asText());
        }
        meta(gateway).remove("task");
        store.put(account, region, GATEWAY, gateway.get("Id").asText(), gateway);
        return mapper.createObjectNode();
    }

    /** A firmware update a CUPS response carries: the data, its signature, and the CRC of the signing key. */
    record Update(byte[] data, byte[] signature, long keyCrc) {
    }

    /**
     * What CUPS sends a gateway that posts update-info: records the version it reports (completing a task whose
     * update version it now runs), and returns the pending task's update, if the gateway runs the task's current
     * version. A task delivered and still not applied on later polls moves to FIRST_RETRY, SECOND_RETRY, then FAILED.
     */
    Update gatewayUpdate(String account, String region, ObjectNode stored, JsonNode request) {
        ObjectNode gateway = store.get(account, region, GATEWAY, stored.get("Id").asText()).orElse(stored);
        ObjectNode version = mapper.createObjectNode();
        version.put("PackageVersion", request.path("package").asText(""));
        version.put("Model", request.path("model").asText(""));
        version.put("Station", request.path("station").asText(""));
        ((ObjectNode) meta(gateway).with("net")).set("version", version);
        JsonNode task = meta(gateway).path("task");
        Update update = null;
        if (task.isObject()) {
            ObjectNode definition = store.get(account, region, TASK_DEFINITION,
                    task.get("WirelessGatewayTaskDefinitionId").asText()).orElse(null);
            String status = task.path("Status").asText();
            JsonNode lorawan = definition == null ? null : definition.path("Update").path("LoRaWAN");
            if (definition == null) {
                ((ObjectNode) task).put("Status", "FAILED");
            } else if (versionMatches(version, lorawan.path("UpdateVersion"))) {
                ((ObjectNode) task).put("Status", "COMPLETED");
            } else if (!List.of("COMPLETED", "FAILED").contains(status)
                    && (versionMatches(version, lorawan.path("CurrentVersion"))
                    || !lorawan.path("CurrentVersion").isObject())) {
                ((ObjectNode) task).put("Status", switch (status) {
                    case "PENDING" -> "IN_PROGRESS";
                    case "IN_PROGRESS" -> "FIRST_RETRY";
                    case "FIRST_RETRY" -> "SECOND_RETRY";
                    default -> "FAILED";
                });
                if (!"FAILED".equals(task.get("Status").asText())) {
                    update = updateOf(account, region, definition);
                }
            }
        }
        store.put(account, region, GATEWAY, gateway.get("Id").asText(), gateway);
        return update;
    }

    private Update updateOf(String account, String region, ObjectNode definition) {
        JsonNode update = definition.path("Update");
        if (!update.hasNonNull("UpdateDataSource")) {
            return null;
        }
        String[] location = s3Location(update.get("UpdateDataSource").asText());
        byte[] data;
        try {
            data = RequestScopes.callAs(account, region, () -> s3.get().getObject(location[0], location[1]).getData());
        } catch (AwsException e) {
            LOG.debugv(e, "IoT Wireless couldn't read the update {0}", update.get("UpdateDataSource").asText());
            return null;
        }
        String signature = update.path("LoRaWAN").path("UpdateSignature").asText("");
        byte[] sig;
        try {
            sig = signature.isEmpty() ? new byte[0] : Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            sig = signature.getBytes(StandardCharsets.UTF_8);
        }
        return new Update(data, sig, update.path("LoRaWAN").path("SigKeyCrc").asLong(0));
    }

    // ---------------------------------------------------------------- Sidewalk partner accounts

    private static String fingerprint(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    ObjectNode associateAwsAccountWithPartnerAccount(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "AssociateAwsAccountWithPartnerAccount", in);
        if (repeated != null) {
            return repeated;
        }
        JsonNode sidewalk = in.get("Sidewalk");
        String amazonId = sidewalk.path("AmazonId").asText(null);
        String key = sidewalk.path("AppServerPrivateKey").asText(null);
        if (amazonId == null || key == null) {
            throw validation("A Sidewalk account has an AmazonId and an AppServerPrivateKey.");
        }
        if (!key.matches("[a-fA-F0-9]{64}")) {
            throw validation("The AppServerPrivateKey is 64 hexadecimal digits.");
        }
        if (store.get(account, region, PARTNER_ACCOUNT, amazonId).isPresent()) {
            throw conflict("Sidewalk account " + amazonId + " is already associated.", amazonId, PARTNER_ACCOUNT);
        }
        ObjectNode partner = store.record();
        partner.put("AmazonId", amazonId);
        partner.put("Fingerprint", fingerprint(key));
        partner.put("Arn", store.arn(account, region, PARTNER_ACCOUNT, amazonId));
        meta(partner).put("key", key);
        meta(partner).set("tags", tagsIn(mapper, in));
        store.put(account, region, PARTNER_ACCOUNT, amazonId, partner);
        ObjectNode out = mapper.createObjectNode();
        out.putObject("Sidewalk").put("AmazonId", amazonId).put("AppServerPrivateKey", key);
        out.put("Arn", partner.get("Arn").asText());
        store.remember(account, region, "AssociateAwsAccountWithPartnerAccount", in, out);
        return out;
    }

    ObjectNode getPartnerAccount(ObjectNode in, String region) {
        ObjectNode partner = require(store.account(), region, PARTNER_ACCOUNT, in.get("PartnerAccountId").asText());
        ObjectNode out = mapper.createObjectNode();
        out.set("Sidewalk", WirelessStore.view(partner));
        out.put("AccountLinked", true);
        return out;
    }

    ObjectNode listPartnerAccounts(ObjectNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(store.account(), region, PARTNER_ACCOUNT).forEach(p -> items.add(WirelessStore.view(p)));
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "Sidewalk");
        return out;
    }

    ObjectNode updatePartnerAccount(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode partner = require(account, region, PARTNER_ACCOUNT, in.get("PartnerAccountId").asText());
        String key = in.get("Sidewalk").path("AppServerPrivateKey").asText(null);
        if (key != null) {
            if (!key.matches("[a-fA-F0-9]{64}")) {
                throw validation("The AppServerPrivateKey is 64 hexadecimal digits.");
            }
            partner.put("Fingerprint", fingerprint(key));
            meta(partner).put("key", key);
        }
        store.put(account, region, PARTNER_ACCOUNT, partner.get("AmazonId").asText(), partner);
        return mapper.createObjectNode();
    }

    ObjectNode disassociateAwsAccountFromPartnerAccount(ObjectNode in, String region) {
        String account = store.account();
        String id = in.get("PartnerAccountId").asText();
        require(account, region, PARTNER_ACCOUNT, id);
        store.delete(account, region, PARTNER_ACCOUNT, id);
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- Sidewalk import tasks

    ObjectNode startSingleWirelessDeviceImportTask(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "StartSingleWirelessDeviceImportTask", in);
        if (repeated != null) {
            return repeated;
        }
        require(account, region, DESTINATION, in.get("DestinationName").asText());
        ObjectNode task = newImportTask(account, region, in);
        ArrayNode devices = (ArrayNode) meta(task).withArray("devices");
        devices.addObject().put("SidewalkManufacturingSn", in.get("Sidewalk").get("SidewalkManufacturingSn").asText())
                .put("OnboardingStatus", "INITIALIZED").put("LastUpdateTime", WirelessStore.epoch());
        if (in.hasNonNull("DeviceName")) {
            meta(task).put("deviceName", in.get("DeviceName").asText());
        }
        store.put(account, region, IMPORT_TASK, task.get("Id").asText(), task);
        String id = task.get("Id").asText();
        runner.schedule(() -> RequestScopes.runAs(account, region, () -> onboard(account, region, id)), 0,
                TimeUnit.MILLISECONDS);
        ObjectNode out = mapper.createObjectNode().put("Id", id).put("Arn", task.get("Arn").asText());
        store.remember(account, region, "StartSingleWirelessDeviceImportTask", in, out);
        return out;
    }

    private ObjectNode newImportTask(String account, String region, JsonNode in) {
        String id = WirelessStore.uuid();
        ObjectNode task = store.record();
        task.put("Id", id);
        task.put("Arn", store.arn(account, region, IMPORT_TASK, id));
        task.put("DestinationName", in.get("DestinationName").asText());
        task.put("Positioning", in.path("Positioning").asText("Disabled"));
        ObjectNode sidewalk = task.putObject("Sidewalk");
        if (in.path("Sidewalk").has("Positioning")) {
            sidewalk.set("Positioning", in.get("Sidewalk").get("Positioning").deepCopy());
        }
        task.put("CreationTime", WirelessStore.epoch());
        task.put("Status", "INITIALIZING");
        meta(task).set("tags", tagsIn(mapper, in));
        return task;
    }

    ObjectNode startWirelessDeviceImportTask(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "StartWirelessDeviceImportTask", in);
        if (repeated != null) {
            return repeated;
        }
        require(account, region, DESTINATION, in.get("DestinationName").asText());
        ObjectNode task = newImportTask(account, region, in);
        JsonNode sidewalk = in.get("Sidewalk");
        String file = sidewalk.path("DeviceCreationFile").asText(null);
        if (file == null) {
            throw validation("A device import names its DeviceCreationFile.");
        }
        ((ObjectNode) task.get("Sidewalk")).putArray("DeviceCreationFileList").add(file);
        if (sidewalk.hasNonNull("Role")) {
            ((ObjectNode) task.get("Sidewalk")).put("Role", sidewalk.get("Role").asText());
        }
        readCreationFile(account, region, task, file);
        store.put(account, region, IMPORT_TASK, task.get("Id").asText(), task);
        String id = task.get("Id").asText();
        runner.schedule(() -> RequestScopes.runAs(account, region, () -> onboard(account, region, id)), 0,
                TimeUnit.MILLISECONDS);
        ObjectNode out = mapper.createObjectNode().put("Id", id).put("Arn", task.get("Arn").asText());
        store.remember(account, region, "StartWirelessDeviceImportTask", in, out);
        return out;
    }

    /** A device creation file: a CSV in S3 with one Sidewalk manufacturing serial number on each line. */
    private void readCreationFile(String account, String region, ObjectNode task, String file) {
        String[] location = s3Location(file);
        String text;
        try {
            text = new String(RequestScopes.callAs(account, region,
                    () -> s3.get().getObject(location[0], location[1]).getData()), StandardCharsets.UTF_8);
        } catch (AwsException e) {
            throw validation("The device creation file " + file + " couldn't be read.");
        }
        ArrayNode devices = (ArrayNode) meta(task).withArray("devices");
        for (String line : text.split("\\r?\\n")) {
            String serial = line.split(",")[0].trim();
            if (serial.isEmpty() || serial.equalsIgnoreCase("SidewalkManufacturingSn")
                    || serial.equalsIgnoreCase("smsn")) {
                continue;
            }
            boolean known = false;
            for (JsonNode device : devices) {
                known |= serial.equals(device.get("SidewalkManufacturingSn").asText());
            }
            if (!known) {
                devices.addObject().put("SidewalkManufacturingSn", serial).put("OnboardingStatus", "INITIALIZED")
                        .put("LastUpdateTime", WirelessStore.epoch());
            }
        }
    }

    /** Onboards an import task's INITIALIZED devices: each becomes a Sidewalk wireless device, or FAILED. */
    private void onboard(String account, String region, String id) {
        ObjectNode task = store.get(account, region, IMPORT_TASK, id).orElse(null);
        if (task == null || "DELETING".equals(task.path("Status").asText())) {
            return;
        }
        ObjectNode profile = null;
        for (ObjectNode candidate : store.list(account, region, DEVICE_PROFILE)) {
            if (candidate.has("Sidewalk")) {
                profile = candidate;
            }
        }
        for (JsonNode node : meta(task).path("devices")) {
            ObjectNode device = (ObjectNode) node;
            if (!"INITIALIZED".equals(device.path("OnboardingStatus").asText())) {
                continue;
            }
            device.put("LastUpdateTime", WirelessStore.epoch());
            if (profile == null) {
                device.put("OnboardingStatus", "FAILED").put("OnboardingStatusReason",
                        "No Sidewalk device profile exists.");
                continue;
            }
            ObjectNode create = mapper.createObjectNode();
            create.put("Type", "Sidewalk");
            create.put("DestinationName", task.get("DestinationName").asText());
            if (meta(task).hasNonNull("deviceName")) {
                create.put("Name", meta(task).get("deviceName").asText());
            }
            create.put("Positioning", task.path("Positioning").asText("Disabled"));
            create.putObject("Sidewalk").put("DeviceProfileId", profile.get("Id").asText())
                    .put("SidewalkManufacturingSn", device.get("SidewalkManufacturingSn").asText());
            create.set("Tags", WirelessStore.tagList(mapper, meta(task).path("tags")));
            try {
                service.get().createWirelessDevice(create, region);
                device.put("OnboardingStatus", "ONBOARDED");
            } catch (AwsException e) {
                device.put("OnboardingStatus", "FAILED").put("OnboardingStatusReason", e.getMessage());
            }
        }
        long failed = 0;
        for (JsonNode device : meta(task).path("devices")) {
            failed += "FAILED".equals(device.path("OnboardingStatus").asText()) ? 1 : 0;
        }
        task.put("Status", failed == meta(task).path("devices").size() && failed > 0 ? "FAILED" : "COMPLETE");
        store.put(account, region, IMPORT_TASK, id, task);
    }

    private ObjectNode importView(ObjectNode task) {
        ObjectNode view = WirelessStore.view(task);
        long[] counts = new long[4];
        for (JsonNode device : meta(task).path("devices")) {
            switch (device.path("OnboardingStatus").asText()) {
                case "INITIALIZED" -> counts[0]++;
                case "PENDING" -> counts[1]++;
                case "ONBOARDED" -> counts[2]++;
                default -> counts[3]++;
            }
        }
        view.put("InitializedImportedDeviceCount", counts[0]);
        view.put("PendingImportedDeviceCount", counts[1]);
        view.put("OnboardedImportedDeviceCount", counts[2]);
        view.put("FailedImportedDeviceCount", counts[3]);
        return view;
    }

    ObjectNode getWirelessDeviceImportTask(ObjectNode in, String region) {
        return importView(require(store.account(), region, IMPORT_TASK, in.get("Id").asText()));
    }

    ObjectNode listWirelessDeviceImportTasks(ObjectNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(store.account(), region, IMPORT_TASK).forEach(t -> items.add(importView(t)));
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "WirelessDeviceImportTaskList");
        return out;
    }

    ObjectNode listDevicesForWirelessDeviceImportTask(ObjectNode in, String region) {
        ObjectNode task = require(store.account(), region, IMPORT_TASK, in.get("Id").asText());
        String status = in.path("Status").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode device : meta(task).path("devices")) {
            if (status == null || status.equals(device.path("OnboardingStatus").asText())) {
                items.add(mapper.createObjectNode().set("Sidewalk", device.deepCopy()));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "ImportedWirelessDeviceList");
        out.put("DestinationName", task.get("DestinationName").asText());
        out.put("Positioning", task.path("Positioning").asText("Disabled"));
        if (task.path("Sidewalk").has("Positioning")) {
            out.putObject("Sidewalk").set("Positioning", task.get("Sidewalk").get("Positioning").deepCopy());
        }
        return out;
    }

    ObjectNode updateWirelessDeviceImportTask(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode task = require(account, region, IMPORT_TASK, in.get("Id").asText());
        String file = in.get("Sidewalk").path("DeviceCreationFile").asText(null);
        if (file != null) {
            readCreationFile(account, region, task, file);
            ((ArrayNode) task.with("Sidewalk").withArray("DeviceCreationFileList")).add(file);
            task.put("Status", "PENDING");
        }
        store.put(account, region, IMPORT_TASK, task.get("Id").asText(), task);
        String id = task.get("Id").asText();
        runner.schedule(() -> RequestScopes.runAs(account, region, () -> onboard(account, region, id)), 0,
                TimeUnit.MILLISECONDS);
        return mapper.createObjectNode();
    }

    ObjectNode deleteWirelessDeviceImportTask(ObjectNode in, String region) {
        String account = store.account();
        require(account, region, IMPORT_TASK, in.get("Id").asText());
        store.delete(account, region, IMPORT_TASK, in.get("Id").asText());
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- multicast groups

    ObjectNode createMulticastGroup(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateMulticastGroup", in);
        if (repeated != null) {
            return repeated;
        }
        ObjectNode lorawan = in.get("LoRaWAN").deepCopy();
        if (!lorawan.hasNonNull("RfRegion") || !lorawan.hasNonNull("DlClass")) {
            throw validation("A multicast group has an RfRegion and a DlClass.");
        }
        checkGateways(account, region, lorawan);
        String id = WirelessStore.uuid();
        ObjectNode group = store.record();
        group.put("Arn", store.arn(account, region, MULTICAST_GROUP, id));
        group.put("Id", id);
        if (in.hasNonNull("Name")) {
            group.put("Name", in.get("Name").asText());
        }
        if (in.hasNonNull("Description")) {
            group.put("Description", in.get("Description").asText());
        }
        group.put("Status", "Pending");
        group.set("LoRaWAN", lorawan);
        group.put("CreatedAt", WirelessStore.epoch());
        byte[] mcAddr = new byte[4];
        RANDOM.nextBytes(mcAddr);
        byte[] mcKey = new byte[16];
        RANDOM.nextBytes(mcKey);
        meta(group).put("mcAddr", LoRaWan.hex(mcAddr));
        meta(group).put("mcKey", LoRaWan.hex(mcKey));
        meta(group).put("mcFCnt", 0);
        meta(group).set("tags", tagsIn(mapper, in));
        store.put(account, region, MULTICAST_GROUP, id, group);
        ObjectNode out = mapper.createObjectNode().put("Arn", group.get("Arn").asText()).put("Id", id);
        store.remember(account, region, "CreateMulticastGroup", in, out);
        return out;
    }

    private void checkGateways(String account, String region, JsonNode lorawan) {
        for (JsonNode gateway : lorawan.path("ParticipatingGateways").path("GatewayList")) {
            service.get().gateway(account, region, gateway.asText());
        }
    }

    private ObjectNode groupView(String account, String region, ObjectNode group) {
        ObjectNode view = WirelessStore.view(group);
        int members = 0;
        String id = group.get("Id").asText();
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            if (meta(device).path("multicastGroups").has(id)) {
                members++;
            }
        }
        ObjectNode lorawan = (ObjectNode) view.with("LoRaWAN");
        lorawan.put("NumberOfDevicesRequested", members);
        lorawan.put("NumberOfDevicesInGroup", countReady(account, region, id));
        return view;
    }

    private int countReady(String account, String region, String groupId) {
        int ready = 0;
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            if ("Successful".equals(meta(device).path("multicastGroups").path(groupId).path("status").asText())) {
                ready++;
            }
        }
        return ready;
    }

    ObjectNode getMulticastGroup(ObjectNode in, String region) {
        String account = store.account();
        return groupView(account, region, require(account, region, MULTICAST_GROUP, in.get("Id").asText()));
    }

    ObjectNode listMulticastGroups(ObjectNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode group : store.list(store.account(), region, MULTICAST_GROUP)) {
            ObjectNode item = mapper.createObjectNode().put("Id", group.get("Id").asText())
                    .put("Arn", group.get("Arn").asText());
            if (group.hasNonNull("Name")) {
                item.put("Name", group.get("Name").asText());
            }
            items.add(item);
        }
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "MulticastGroupList");
        return out;
    }

    ObjectNode updateMulticastGroup(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode group = require(account, region, MULTICAST_GROUP, in.get("Id").asText());
        for (String field : List.of("Name", "Description")) {
            if (in.hasNonNull(field)) {
                group.put(field, in.get(field).asText());
            }
        }
        if (in.has("LoRaWAN")) {
            checkGateways(account, region, in.get("LoRaWAN"));
            ((ObjectNode) group.get("LoRaWAN")).setAll((ObjectNode) in.get("LoRaWAN").deepCopy());
        }
        store.put(account, region, MULTICAST_GROUP, group.get("Id").asText(), group);
        return mapper.createObjectNode();
    }

    ObjectNode deleteMulticastGroup(ObjectNode in, String region) {
        String account = store.account();
        String id = in.get("Id").asText();
        require(account, region, MULTICAST_GROUP, id);
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            if (meta(device).path("multicastGroups").has(id)) {
                ((ObjectNode) meta(device).get("multicastGroups")).remove(id);
                store.put(account, region, DEVICE, device.get("Id").asText(), device);
            }
        }
        store.delete(account, region, MULTICAST_GROUP, id);
        return mapper.createObjectNode();
    }

    ObjectNode associateWirelessDeviceWithMulticastGroup(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode group = require(account, region, MULTICAST_GROUP, in.get("Id").asText());
        ObjectNode device = service.get().device(account, region, in.get("WirelessDeviceId").asText());
        join(account, region, group, device);
        return mapper.createObjectNode();
    }

    private void join(String account, String region, ObjectNode group, ObjectNode device) {
        if (!device.has("LoRaWAN")) {
            throw validation("Only LoRaWAN devices join multicast groups.");
        }
        String rfRegion = store.get(account, region, DEVICE_PROFILE,
                device.path("LoRaWAN").path("DeviceProfileId").asText("")).map(p -> p.path("LoRaWAN")
                .path("RfRegion").asText("")).orElse("");
        if (!rfRegion.isEmpty() && !rfRegion.equals(group.path("LoRaWAN").path("RfRegion").asText())) {
            throw validation("Device " + device.get("Id").asText() + " is in " + rfRegion + ", the group in "
                    + group.path("LoRaWAN").path("RfRegion").asText() + ".");
        }
        ObjectNode memberships = (ObjectNode) meta(device).with("multicastGroups");
        if (!memberships.has(group.get("Id").asText())) {
            int used = 0;
            for (JsonNode membership : memberships) {
                used = Math.max(used, membership.path("mcGroupId").asInt(0));
            }
            memberships.putObject(group.get("Id").asText()).put("status", "Pending").put("mcGroupId", used + 1);
        }
        store.put(account, region, DEVICE, device.get("Id").asText(), device);
    }

    ObjectNode disassociateWirelessDeviceFromMulticastGroup(ObjectNode in, String region) {
        String account = store.account();
        require(account, region, MULTICAST_GROUP, in.get("Id").asText());
        ObjectNode device = service.get().device(account, region, in.get("WirelessDeviceId").asText());
        ((ObjectNode) meta(device).with("multicastGroups")).remove(in.get("Id").asText());
        store.put(account, region, DEVICE, device.get("Id").asText(), device);
        return mapper.createObjectNode();
    }

    /** The devices a bulk request names: all with the given tags, narrowed by a {@code key:value ...} query. */
    private List<ObjectNode> bulkDevices(String account, String region, JsonNode in) {
        ObjectNode tags = WirelessStore.tagsOf(mapper, in.get("Tags"));
        String query = in.path("QueryString").asText("");
        List<ObjectNode> devices = new ArrayList<>();
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            boolean match = true;
            JsonNode deviceTags = meta(device).path("tags");
            Iterator<Map.Entry<String, JsonNode>> fields = tags.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> tag = fields.next();
                match &= tag.getValue().asText().equals(deviceTags.path(tag.getKey()).asText(null));
            }
            for (String term : query.trim().isEmpty() ? new String[0] : query.trim().split("\\s+(AND\\s+)?")) {
                int colon = term.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String path = term.substring(0, colon);
                String value = term.substring(colon + 1).replace("\"", "");
                JsonNode actual = device;
                for (String segment : path.split("\\.")) {
                    actual = actual.path(segment);
                }
                if (actual.isMissingNode() && path.startsWith("tags.")) {
                    actual = deviceTags.path(path.substring(5));
                }
                match &= value.equals("*") ? !actual.isMissingNode() : value.equalsIgnoreCase(actual.asText(null));
            }
            if (match) {
                devices.add(device);
            }
        }
        return devices;
    }

    ObjectNode startBulkAssociateWirelessDeviceWithMulticastGroup(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode group = require(account, region, MULTICAST_GROUP, in.get("Id").asText());
        for (ObjectNode device : bulkDevices(account, region, in)) {
            try {
                join(account, region, group, device);
            } catch (AwsException e) {
                LOG.debugv("Device {0} wasn't added to multicast group {1}: {2}", device.get("Id").asText(),
                        group.get("Id").asText(), e.getMessage());
            }
        }
        return mapper.createObjectNode();
    }

    ObjectNode startBulkDisassociateWirelessDeviceFromMulticastGroup(ObjectNode in, String region) {
        String account = store.account();
        String id = require(account, region, MULTICAST_GROUP, in.get("Id").asText()).get("Id").asText();
        for (ObjectNode device : bulkDevices(account, region, in)) {
            if (meta(device).path("multicastGroups").has(id)) {
                ((ObjectNode) meta(device).get("multicastGroups")).remove(id);
                store.put(account, region, DEVICE, device.get("Id").asText(), device);
            }
        }
        return mapper.createObjectNode();
    }

    /**
     * Starts a multicast session: each member device gets McGroupSetupReq (the group's address and key, wrapped with
     * the device's McKEKey) and McClassCSessionReq on the multicast FPort; a device's McGroupSetupAns marks it
     * Successful.
     */
    ObjectNode startMulticastGroupSession(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode group = require(account, region, MULTICAST_GROUP, in.get("Id").asText());
        JsonNode session = in.get("LoRaWAN");
        if (!session.hasNonNull("SessionStartTime") || !session.hasNonNull("SessionTimeout")) {
            throw validation("A multicast session has a SessionStartTime and a SessionTimeout.");
        }
        ObjectNode stored = session.deepCopy();
        LoRaRegions.Region radio = LoRaRegions.region(group.path("LoRaWAN").path("RfRegion").asText());
        if (!stored.has("DlDr")) {
            stored.put("DlDr", group.path("LoRaWAN").path("DefaultSessionParameters").path("DlDr")
                    .asInt(radio.rx2Dr()));
        }
        if (!stored.has("DlFreq")) {
            stored.put("DlFreq", group.path("LoRaWAN").path("DefaultSessionParameters").path("DlFreq")
                    .asLong(radio.rx2Freq()));
        }
        meta(group).set("session", stored);
        group.put("Status", "Session_Pending");
        store.put(account, region, MULTICAST_GROUP, group.get("Id").asText(), group);
        double start = sessionStart(stored);
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            JsonNode membership = meta(device).path("multicastGroups").path(group.get("Id").asText());
            if (membership.isMissingNode()) {
                continue;
            }
            setupDevice(account, region, group, device, membership.path("mcGroupId").asInt(1) - 1, stored, start);
        }
        return mapper.createObjectNode();
    }

    private static double sessionStart(JsonNode session) {
        JsonNode start = session.get("SessionStartTime");
        if (start.isNumber()) {
            return start.asDouble();
        }
        try {
            return Instant.parse(start.asText()).getEpochSecond();
        } catch (RuntimeException e) {
            return Double.parseDouble(start.asText());
        }
    }

    private void setupDevice(String account, String region, ObjectNode group, ObjectNode device, int mcGroupId,
                             JsonNode session, double start) {
        JsonNode lorawan = device.get("LoRaWAN");
        JsonNode otaa = lorawan.has("OtaaV1_1") ? lorawan.get("OtaaV1_1") : lorawan.get("OtaaV1_0_x");
        String rootHex = otaa == null ? null : otaa.path("GenAppKey").asText(otaa.path("AppKey").asText(null));
        if (rootHex == null) {
            meta(device).with("multicastGroups").with(group.get("Id").asText()).put("status",
                    "Device_Not_Support_Multicast");
            store.put(account, region, DEVICE, device.get("Id").asText(), device);
            return;
        }
        byte[] mcKEKey = LoRaWan.aesEncrypt(LoRaWan.hex(rootHex), new byte[16]);
        byte[] encrypted = LoRaWan.aesDecrypt(mcKEKey, LoRaWan.hex(meta(group).get("mcKey").asText()));
        byte[] setup = new byte[1 + 1 + 4 + 16 + 4 + 4];
        setup[0] = 0x02;
        setup[1] = (byte) (mcGroupId & 0x03);
        System.arraycopy(LoRaWan.reverse(LoRaWan.hex(meta(group).get("mcAddr").asText())), 0, setup, 2, 4);
        System.arraycopy(encrypted, 0, setup, 6, 16);
        long minFCnt = meta(group).path("mcFCnt").asLong(0);
        long maxFCnt = minFCnt + 0xFFFF;
        for (int i = 0; i < 4; i++) {
            setup[22 + i] = (byte) (minFCnt >> (8 * i));
            setup[26 + i] = (byte) (maxFCnt >> (8 * i));
        }
        long gps = (long) start - 315_964_800L + 18;
        int timeout = (int) Math.min(15, Math.max(0, Math.round(Math.log(session.path("SessionTimeout").asInt(60))
                / Math.log(2))));
        long freq = session.path("DlFreq").asLong() / 100;
        byte[] classC = new byte[] {0x04, (byte) (mcGroupId & 0x03), (byte) gps, (byte) (gps >> 8),
            (byte) (gps >> 16), (byte) (gps >> 24), (byte) timeout, (byte) freq, (byte) (freq >> 8),
            (byte) (freq >> 16), (byte) session.path("DlDr").asInt()};
        int port = lorawan.path("FPorts").path("Multicast").asInt(200);
        try {
            network.get().queueOrSend(account, region, device, port, setup);
            network.get().queueOrSend(account, region, device, port, classC);
        } catch (AwsException e) {
            LOG.debugv(e, "IoT Wireless couldn't queue multicast setup for {0}", device.get("Id").asText());
        }
    }

    /** An uplink on a device's multicast or FUOTA port: the answers of the setup protocols. */
    void protocolAnswer(String account, String region, ObjectNode device, int fPort, byte[] payload) {
        JsonNode ports = device.path("LoRaWAN").path("FPorts");
        if (fPort == ports.path("Multicast").asInt(200) && payload.length >= 2 && payload[0] == 0x02) {
            int mcGroupId = payload[1] & 0x03;
            boolean error = (payload[1] & 0x04) != 0;
            ObjectNode memberships = (ObjectNode) meta(device).with("multicastGroups");
            memberships.fields().forEachRemaining(entry -> {
                if (entry.getValue().path("mcGroupId").asInt() - 1 == mcGroupId) {
                    ((ObjectNode) entry.getValue()).put("status", error ? "McGroupSetup_Failed" : "Successful");
                }
            });
            store.put(account, region, DEVICE, device.get("Id").asText(), device);
        }
        if (fPort == ports.path("Fuota").asInt(201) && payload.length >= 1) {
            ObjectNode tasks = (ObjectNode) meta(device).with("fuotaTasks");
            if (payload[0] == 0x02 && payload.length >= 2) {
                int status = payload[1] & 0x0F;
                String result = (status & 0x01) != 0 ? "FragAlgo_unsupported" : (status & 0x02) != 0
                        ? "Not_enough_memory" : (status & 0x04) != 0 ? "FragIndex_unsupported" : (status & 0x08) != 0
                        ? "Wrong_descriptor" : null;
                if (result != null) {
                    tasks.fieldNames().forEachRemaining(task -> tasks.put(task, result));
                }
            } else if (payload[0] == 0x01 && payload.length >= 5) {
                int missing = payload[3] & 0xFF;
                boolean memoryError = (payload[4] & 0x01) != 0;
                String result = memoryError ? "MemoryError" : missing == 0 ? "Successful" : "MissingFrag";
                tasks.fieldNames().forEachRemaining(task -> tasks.put(task, result));
            }
            store.put(account, region, DEVICE, device.get("Id").asText(), device);
        }
    }

    ObjectNode getMulticastGroupSession(ObjectNode in, String region) {
        ObjectNode group = require(store.account(), region, MULTICAST_GROUP, in.get("Id").asText());
        ObjectNode out = mapper.createObjectNode();
        if (meta(group).has("session")) {
            out.set("LoRaWAN", meta(group).get("session").deepCopy());
        }
        return out;
    }

    ObjectNode cancelMulticastGroupSession(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode group = require(account, region, MULTICAST_GROUP, in.get("Id").asText());
        meta(group).remove("session");
        group.put("Status", "Pending");
        store.put(account, region, MULTICAST_GROUP, group.get("Id").asText(), group);
        return mapper.createObjectNode();
    }

    /** A multicast downlink: encrypted with the group's session keys and sent on the session's RX2 parameters. */
    ObjectNode sendDataToMulticastGroup(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode group = require(account, region, MULTICAST_GROUP, in.get("Id").asText());
        JsonNode session = meta(group).path("session");
        if (!session.isObject()) {
            throw validation("Multicast group " + group.get("Id").asText() + " has no session; start one first.");
        }
        double start = sessionStart(session);
        double now = Instant.now().getEpochSecond();
        if (now < start || now > start + session.path("SessionTimeout").asInt(60)) {
            throw validation("Multicast group " + group.get("Id").asText() + "'s session isn't running now.");
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(in.get("PayloadData").asText());
        } catch (IllegalArgumentException e) {
            throw validation("PayloadData is base64.");
        }
        int fPort = in.get("WirelessMetadata").path("LoRaWAN").path("FPort").asInt(1);
        byte[] mcKey = LoRaWan.hex(meta(group).get("mcKey").asText());
        String mcAddr = meta(group).get("mcAddr").asText();
        byte[] block = new byte[16];
        block[0] = 0x01;
        System.arraycopy(LoRaWan.reverse(LoRaWan.hex(mcAddr)), 0, block, 1, 4);
        byte[] appSKey = LoRaWan.aesEncrypt(mcKey, block);
        block[0] = 0x02;
        byte[] nwkSKey = LoRaWan.aesEncrypt(mcKey, block);
        long fCnt = meta(group).path("mcFCnt").asLong(0);
        LoRaWan.SessionKeys keys = new LoRaWan.SessionKeys(nwkSKey, nwkSKey, nwkSKey, appSKey);
        byte[] frame = LoRaWan.buildDownlink(false, mcAddr, fCnt, false, false, null, fPort, payload, keys, false, 0);
        meta(group).put("mcFCnt", fCnt + 1);
        group.put("Status", "Session_InProgress");
        store.put(account, region, MULTICAST_GROUP, group.get("Id").asText(), group);
        network.get().sendMulticast(account, region, group, frame, session.path("DlDr").asInt(),
                session.path("DlFreq").asLong());
        return mapper.createObjectNode().put("MessageId", WirelessStore.uuid());
    }

    // ---------------------------------------------------------------- FUOTA

    ObjectNode createFuotaTask(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateFuotaTask", in);
        if (repeated != null) {
            return repeated;
        }
        s3Location(in.get("FirmwareUpdateImage").asText());
        String id = WirelessStore.uuid();
        ObjectNode task = store.record();
        task.put("Arn", store.arn(account, region, FUOTA_TASK, id));
        task.put("Id", id);
        task.put("Status", "Pending");
        for (String field : List.of("Name", "Description", "FirmwareUpdateImage", "FirmwareUpdateRole",
                "Descriptor")) {
            if (in.hasNonNull(field)) {
                task.put(field, in.get(field).asText());
            }
        }
        ObjectNode lorawan = task.putObject("LoRaWAN");
        if (in.path("LoRaWAN").hasNonNull("RfRegion")) {
            lorawan.put("RfRegion", in.get("LoRaWAN").get("RfRegion").asText());
        }
        task.put("CreatedAt", WirelessStore.epoch());
        task.put("RedundancyPercent", in.path("RedundancyPercent").asInt(0));
        task.put("FragmentSizeBytes", in.path("FragmentSizeBytes").asInt(40));
        task.put("FragmentIntervalMS", in.path("FragmentIntervalMS").asInt(1000));
        meta(task).set("tags", tagsIn(mapper, in));
        store.put(account, region, FUOTA_TASK, id, task);
        ObjectNode out = mapper.createObjectNode().put("Arn", task.get("Arn").asText()).put("Id", id);
        store.remember(account, region, "CreateFuotaTask", in, out);
        return out;
    }

    ObjectNode getFuotaTask(ObjectNode in, String region) {
        return WirelessStore.view(require(store.account(), region, FUOTA_TASK, in.get("Id").asText()));
    }

    ObjectNode listFuotaTasks(ObjectNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode task : store.list(store.account(), region, FUOTA_TASK)) {
            ObjectNode item = mapper.createObjectNode().put("Id", task.get("Id").asText())
                    .put("Arn", task.get("Arn").asText());
            if (task.hasNonNull("Name")) {
                item.put("Name", task.get("Name").asText());
            }
            items.add(item);
        }
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "FuotaTaskList");
        return out;
    }

    private ObjectNode pendingFuota(String account, String region, String id) {
        ObjectNode task = require(account, region, FUOTA_TASK, id);
        if (!"Pending".equals(task.path("Status").asText())) {
            throw conflict("FUOTA task " + id + " is " + task.path("Status").asText() + "; only a Pending task "
                    + "changes.", id, FUOTA_TASK);
        }
        return task;
    }

    ObjectNode updateFuotaTask(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode task = pendingFuota(account, region, in.get("Id").asText());
        for (String field : List.of("Name", "Description", "FirmwareUpdateImage", "FirmwareUpdateRole",
                "Descriptor")) {
            if (in.hasNonNull(field)) {
                task.put(field, in.get(field).asText());
            }
        }
        for (String field : List.of("RedundancyPercent", "FragmentSizeBytes", "FragmentIntervalMS")) {
            if (in.has(field)) {
                task.put(field, in.get(field).asInt());
            }
        }
        if (in.path("LoRaWAN").hasNonNull("RfRegion")) {
            ((ObjectNode) task.with("LoRaWAN")).put("RfRegion", in.get("LoRaWAN").get("RfRegion").asText());
        }
        store.put(account, region, FUOTA_TASK, task.get("Id").asText(), task);
        return mapper.createObjectNode();
    }

    ObjectNode deleteFuotaTask(ObjectNode in, String region) {
        String account = store.account();
        String id = in.get("Id").asText();
        require(account, region, FUOTA_TASK, id);
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            if (meta(device).path("fuotaTasks").has(id)) {
                ((ObjectNode) meta(device).get("fuotaTasks")).remove(id);
                store.put(account, region, DEVICE, device.get("Id").asText(), device);
            }
        }
        store.delete(account, region, FUOTA_TASK, id);
        return mapper.createObjectNode();
    }

    ObjectNode associateWirelessDeviceWithFuotaTask(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode task = pendingFuota(account, region, in.get("Id").asText());
        ObjectNode device = service.get().device(account, region, in.get("WirelessDeviceId").asText());
        for (ObjectNode other : store.list(account, region, FUOTA_TASK)) {
            String otherId = other.get("Id").asText();
            if (!otherId.equals(task.get("Id").asText()) && meta(device).path("fuotaTasks").has(otherId)
                    && !"FuotaDone".equals(other.path("Status").asText())) {
                throw conflict("Device " + device.get("Id").asText() + " is in FUOTA task " + otherId + ".",
                        device.get("Id").asText(), DEVICE);
            }
        }
        ((ObjectNode) meta(device).with("fuotaTasks")).put(task.get("Id").asText(), "Initial");
        store.put(account, region, DEVICE, device.get("Id").asText(), device);
        return mapper.createObjectNode();
    }

    ObjectNode disassociateWirelessDeviceFromFuotaTask(ObjectNode in, String region) {
        String account = store.account();
        require(account, region, FUOTA_TASK, in.get("Id").asText());
        ObjectNode device = service.get().device(account, region, in.get("WirelessDeviceId").asText());
        ((ObjectNode) meta(device).with("fuotaTasks")).remove(in.get("Id").asText());
        store.put(account, region, DEVICE, device.get("Id").asText(), device);
        return mapper.createObjectNode();
    }

    ObjectNode associateMulticastGroupWithFuotaTask(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode task = pendingFuota(account, region, in.get("Id").asText());
        String groupId = require(account, region, MULTICAST_GROUP, in.get("MulticastGroupId").asText()).get("Id")
                .asText();
        ArrayNode groups = (ArrayNode) meta(task).withArray("multicastGroups");
        boolean present = false;
        for (JsonNode group : groups) {
            present |= groupId.equals(group.asText());
        }
        if (!present) {
            groups.add(groupId);
        }
        store.put(account, region, FUOTA_TASK, task.get("Id").asText(), task);
        return mapper.createObjectNode();
    }

    ObjectNode disassociateMulticastGroupFromFuotaTask(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode task = require(account, region, FUOTA_TASK, in.get("Id").asText());
        ArrayNode groups = (ArrayNode) meta(task).withArray("multicastGroups");
        for (int i = groups.size() - 1; i >= 0; i--) {
            if (groups.get(i).asText().equals(in.get("MulticastGroupId").asText())) {
                groups.remove(i);
            }
        }
        store.put(account, region, FUOTA_TASK, task.get("Id").asText(), task);
        return mapper.createObjectNode();
    }

    ObjectNode listMulticastGroupsByFuotaTask(ObjectNode in, String region) {
        ObjectNode task = require(store.account(), region, FUOTA_TASK, in.get("Id").asText());
        List<ObjectNode> items = new ArrayList<>();
        meta(task).path("multicastGroups").forEach(g -> items.add(mapper.createObjectNode().put("Id", g.asText())));
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "MulticastGroupList");
        return out;
    }

    /**
     * Starts a FUOTA task: FuotaSession_Waiting until its start time, then In_FuotaSession while the image is sent as
     * FragSessionSetupReq followed by DataFragment commands (with the requested redundancy as XOR parity fragments)
     * to each device, one fragment every FragmentIntervalMS, and FuotaDone once all are out.
     */
    ObjectNode startFuotaTask(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode task = pendingFuota(account, region, in.get("Id").asText());
        String id = task.get("Id").asText();
        boolean members = !meta(task).path("multicastGroups").isEmpty();
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            members |= meta(device).path("fuotaTasks").has(id);
        }
        if (!members) {
            throw validation("FUOTA task " + id + " has no devices or multicast groups.");
        }
        double start = in.path("LoRaWAN").has("StartTime") ? date(in.get("LoRaWAN").get("StartTime"))
                : Instant.now().getEpochSecond();
        ((ObjectNode) task.with("LoRaWAN")).put("StartTime", start);
        task.put("Status", "FuotaSession_Waiting");
        store.put(account, region, FUOTA_TASK, id, task);
        long delay = Math.max(0, (long) ((start - Instant.now().getEpochSecond()) * 1000));
        runner.schedule(() -> RequestScopes.runAs(account, region, () -> runFuota(account, region, id)), delay,
                TimeUnit.MILLISECONDS);
        return mapper.createObjectNode();
    }

    private static double date(JsonNode value) {
        if (value.isNumber()) {
            return value.asDouble();
        }
        return Instant.parse(value.asText()).getEpochSecond();
    }

    private void runFuota(String account, String region, String id) {
        ObjectNode task = store.get(account, region, FUOTA_TASK, id).orElse(null);
        if (task == null) {
            return;
        }
        byte[] image;
        try {
            String[] location = s3Location(task.get("FirmwareUpdateImage").asText());
            image = s3.get().getObject(location[0], location[1]).getData();
        } catch (AwsException e) {
            task.put("Status", "FuotaDone");
            store.put(account, region, FUOTA_TASK, id, task);
            network.get().log(account, region, "FuotaTask", id, "ERROR", "The firmware image couldn't be read",
                    e.getMessage());
            return;
        }
        task.put("Status", "In_FuotaSession");
        store.put(account, region, FUOTA_TASK, id, task);
        int size = Math.max(1, task.path("FragmentSizeBytes").asInt(40));
        int fragments = (image.length + size - 1) / size;
        int redundancy = (int) Math.ceil(fragments * task.path("RedundancyPercent").asInt(0) / 100.0);
        byte[] padded = Arrays.copyOf(image, fragments * size);
        List<ObjectNode> devices = new ArrayList<>();
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            if (meta(device).path("fuotaTasks").has(id)) {
                devices.add(device);
            }
        }
        byte[] setup = new byte[11];
        setup[0] = 0x02;
        setup[1] = 0x00;
        setup[2] = (byte) fragments;
        setup[3] = (byte) (fragments >> 8);
        setup[4] = (byte) size;
        setup[5] = 0;
        setup[6] = 0;
        byte[] descriptor = task.hasNonNull("Descriptor") ? Arrays.copyOf(LoRaWan.hex(task.get("Descriptor").asText()
                .replaceAll("[^0-9a-fA-F]", "")), 4) : new byte[4];
        System.arraycopy(descriptor, 0, setup, 7, 4);
        for (ObjectNode device : devices) {
            int port = device.path("LoRaWAN").path("FPorts").path("Fuota").asInt(201);
            try {
                network.get().queueOrSend(account, region, device, port, setup);
            } catch (AwsException e) {
                LOG.debugv(e, "FUOTA setup for {0} wasn't queued", device.get("Id").asText());
            }
        }
        for (int n = 1; n <= fragments + redundancy; n++) {
            byte[] fragment = new byte[size];
            if (n <= fragments) {
                System.arraycopy(padded, (n - 1) * size, fragment, 0, size);
            } else {
                for (int k = 0; k < fragments; k++) {
                    if (((k + n) & 1) == 0) {
                        for (int b = 0; b < size; b++) {
                            fragment[b] ^= padded[k * size + b];
                        }
                    }
                }
            }
            byte[] command = new byte[3 + size];
            command[0] = 0x08;
            command[1] = (byte) n;
            command[2] = (byte) ((n >> 8) & 0x3F);
            System.arraycopy(fragment, 0, command, 3, size);
            for (String groupId : groupsOf(task)) {
                store.get(account, region, MULTICAST_GROUP, groupId).ifPresent(group -> {
                    JsonNode session = meta(group).path("session");
                    if (session.isObject()) {
                        network.get().sendMulticast(account, region, group, command, session.path("DlDr").asInt(),
                                session.path("DlFreq").asLong());
                    }
                });
            }
            for (ObjectNode device : devices) {
                int port = device.path("LoRaWAN").path("FPorts").path("Fuota").asInt(201);
                try {
                    ObjectNode current = store.get(account, region, DEVICE, device.get("Id").asText()).orElse(device);
                    network.get().queueOrSend(account, region, current, port, command);
                } catch (AwsException e) {
                    break;
                }
            }
            try {
                Thread.sleep(Math.min(5000, task.path("FragmentIntervalMS").asInt(1000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        task = store.get(account, region, FUOTA_TASK, id).orElse(null);
        if (task != null) {
            task.put("Status", "FuotaDone");
            store.put(account, region, FUOTA_TASK, id, task);
        }
    }

    private static List<String> groupsOf(ObjectNode task) {
        List<String> groups = new ArrayList<>();
        meta(task).path("multicastGroups").forEach(g -> groups.add(g.asText()));
        return groups;
    }

    // ---------------------------------------------------------------- network analyzer

    private void checkMembers(String account, String region, JsonNode in, String suffix) {
        for (JsonNode id : in.path("WirelessDevices" + suffix)) {
            service.get().device(account, region, id.asText());
        }
        for (JsonNode id : in.path("WirelessGateways" + suffix)) {
            service.get().gateway(account, region, id.asText());
        }
        for (JsonNode id : in.path("MulticastGroups" + suffix)) {
            require(account, region, MULTICAST_GROUP, id.asText());
        }
    }

    ObjectNode createNetworkAnalyzerConfiguration(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateNetworkAnalyzerConfiguration", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.get("Name").asText();
        if (store.get(account, region, NETWORK_ANALYZER, name).isPresent()) {
            throw conflict("Network analyzer configuration " + name + " already exists.", name, NETWORK_ANALYZER);
        }
        checkMembers(account, region, in, "");
        ObjectNode configuration = store.record();
        ObjectNode trace = configuration.putObject("TraceContent");
        trace.put("WirelessDeviceFrameInfo", in.path("TraceContent").path("WirelessDeviceFrameInfo").asText("DISABLED"));
        trace.put("LogLevel", in.path("TraceContent").path("LogLevel").asText("ERROR"));
        trace.put("MulticastFrameInfo", in.path("TraceContent").path("MulticastFrameInfo").asText("DISABLED"));
        for (String field : List.of("WirelessDevices", "WirelessGateways", "MulticastGroups")) {
            configuration.set(field, in.has(field) ? in.get(field).deepCopy() : mapper.createArrayNode());
        }
        if (in.hasNonNull("Description")) {
            configuration.put("Description", in.get("Description").asText());
        }
        configuration.put("Arn", store.arn(account, region, NETWORK_ANALYZER, name));
        configuration.put("Name", name);
        meta(configuration).set("tags", tagsIn(mapper, in));
        store.put(account, region, NETWORK_ANALYZER, name, configuration);
        ObjectNode out = mapper.createObjectNode().put("Arn", configuration.get("Arn").asText()).put("Name", name);
        store.remember(account, region, "CreateNetworkAnalyzerConfiguration", in, out);
        return out;
    }

    ObjectNode getNetworkAnalyzerConfiguration(ObjectNode in, String region) {
        return WirelessStore.view(require(store.account(), region, NETWORK_ANALYZER,
                in.get("ConfigurationName").asText()));
    }

    ObjectNode listNetworkAnalyzerConfigurations(ObjectNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode configuration : store.list(store.account(), region, NETWORK_ANALYZER)) {
            items.add(mapper.createObjectNode().put("Arn", configuration.get("Arn").asText())
                    .put("Name", configuration.get("Name").asText()));
        }
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "NetworkAnalyzerConfigurationList");
        return out;
    }

    ObjectNode updateNetworkAnalyzerConfiguration(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode configuration = require(account, region, NETWORK_ANALYZER, in.get("ConfigurationName").asText());
        checkMembers(account, region, in, "ToAdd");
        if (in.has("TraceContent")) {
            ((ObjectNode) configuration.with("TraceContent")).setAll((ObjectNode) in.get("TraceContent").deepCopy());
        }
        if (in.hasNonNull("Description")) {
            configuration.put("Description", in.get("Description").asText());
        }
        for (String field : List.of("WirelessDevices", "WirelessGateways", "MulticastGroups")) {
            ArrayNode list = (ArrayNode) configuration.withArray(field);
            for (JsonNode add : in.path(field + "ToAdd")) {
                boolean present = false;
                for (JsonNode existing : list) {
                    present |= existing.asText().equals(add.asText());
                }
                if (!present) {
                    list.add(add.asText());
                }
            }
            for (JsonNode remove : in.path(field + "ToRemove")) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (list.get(i).asText().equals(remove.asText())) {
                        list.remove(i);
                    }
                }
            }
        }
        store.put(account, region, NETWORK_ANALYZER, configuration.get("Name").asText(), configuration);
        return mapper.createObjectNode();
    }

    ObjectNode deleteNetworkAnalyzerConfiguration(ObjectNode in, String region) {
        String account = store.account();
        String name = in.get("ConfigurationName").asText();
        require(account, region, NETWORK_ANALYZER, name);
        store.delete(account, region, NETWORK_ANALYZER, name);
        return mapper.createObjectNode();
    }
}
