package io.floci.aws.services.iotwireless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iot.IotService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static io.floci.aws.services.iotwireless.WirelessStore.DESTINATION;
import static io.floci.aws.services.iotwireless.WirelessStore.DEVICE;
import static io.floci.aws.services.iotwireless.WirelessStore.DEVICE_PROFILE;
import static io.floci.aws.services.iotwireless.WirelessStore.GATEWAY;
import static io.floci.aws.services.iotwireless.WirelessStore.SERVICE_PROFILE;
import static io.floci.aws.services.iotwireless.WirelessStore.conflict;
import static io.floci.aws.services.iotwireless.WirelessStore.meta;
import static io.floci.aws.services.iotwireless.WirelessStore.notFound;
import static io.floci.aws.services.iotwireless.WirelessStore.validation;

/**
 * IoT Wireless' control plane for LoRaWAN and Sidewalk: destinations (an IoT rule or MQTT topic, written through a
 * role), device and service profiles, wireless devices (OTAA or ABP, LoRaWAN 1.0.x or 1.1, or Sidewalk) and gateways,
 * their things and certificates, and tags. Profiles and destinations in use can't be deleted.
 */
@ApplicationScoped
public class WirelessService {

    static final Pattern EUI = Pattern.compile("[a-fA-F0-9]{16}");
    static final Pattern KEY = Pattern.compile("[a-fA-F0-9]{32}");
    static final Pattern DEV_ADDR = Pattern.compile("[a-fA-F0-9]{8}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WirelessStore store;
    private final ObjectMapper mapper;
    private final Instance<IamService> iam;
    private final Instance<IotService> iot;
    private final Instance<WirelessNetwork> network;

    @Inject
    public WirelessService(WirelessStore store, Instance<IamService> iam, Instance<IotService> iot,
                           Instance<WirelessNetwork> network) {
        this.store = store;
        this.mapper = store.mapper();
        this.iam = iam;
        this.iot = iot;
        this.network = network;
    }

    // ---------------------------------------------------------------- lookups

    ObjectNode require(String account, String region, String kind, String id) {
        return store.get(account, region, kind, id).orElseThrow(() -> notFound(kind, id));
    }

    ObjectNode device(String account, String region, String id) {
        return require(account, region, DEVICE, id);
    }

    ObjectNode gateway(String account, String region, String id) {
        return require(account, region, GATEWAY, id);
    }

    private void checkRole(String account, String roleArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(roleArn);
        } catch (RuntimeException e) {
            throw validation("The role ARN " + roleArn + " isn't valid.");
        }
        if (!"iam".equals(arn.service()) || !arn.resource().startsWith("role/")) {
            throw validation("The ARN " + roleArn + " isn't an IAM role.");
        }
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        try {
            RequestScopes.callAs(account, () -> iam.get().getRole(name));
        } catch (AwsException e) {
            throw validation("The role " + roleArn + " doesn't exist.");
        }
    }

    // ---------------------------------------------------------------- destinations

    ObjectNode createDestination(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateDestination", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.get("Name").asText();
        if (!name.matches("[a-zA-Z0-9-_]+")) {
            throw validation("A destination name is letters, digits, hyphens and underscores.");
        }
        if (store.get(account, region, DESTINATION, name).isPresent()) {
            throw conflict("Destination " + name + " already exists.", name, DESTINATION);
        }
        checkExpression(in.get("ExpressionType").asText(), in.get("Expression").asText());
        checkRole(account, in.get("RoleArn").asText());
        ObjectNode destination = store.record();
        destination.put("Arn", store.arn(account, region, DESTINATION, name));
        destination.put("Name", name);
        destination.put("Expression", in.get("Expression").asText());
        destination.put("ExpressionType", in.get("ExpressionType").asText());
        if (in.hasNonNull("Description")) {
            destination.put("Description", in.get("Description").asText());
        }
        destination.put("RoleArn", in.get("RoleArn").asText());
        meta(destination).set("tags", WirelessStore.tagsOf(mapper, in.get("Tags")));
        store.put(account, region, DESTINATION, name, destination);
        ObjectNode out = mapper.createObjectNode().put("Arn", destination.get("Arn").asText()).put("Name", name);
        store.remember(account, region, "CreateDestination", in, out);
        return out;
    }

    private static void checkExpression(String type, String expression) {
        if ("RuleName".equals(type) && !expression.matches("[a-zA-Z0-9_]+")) {
            throw validation("A rule name is letters, digits and underscores.");
        }
        if ("MqttTopic".equals(type) && (expression.isBlank() || expression.contains("#") || expression.contains("+"))) {
            throw validation("An MQTT topic destination is a topic, not a filter.");
        }
    }

    ObjectNode getDestination(ObjectNode in, String region) {
        return WirelessStore.view(require(store.account(), region, DESTINATION, in.get("Name").asText()));
    }

    ObjectNode listDestinations(ObjectNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode destination : store.list(store.account(), region, DESTINATION)) {
            items.add(WirelessStore.view(destination));
        }
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "DestinationList");
        return out;
    }

    ObjectNode updateDestination(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode destination = require(account, region, DESTINATION, in.get("Name").asText());
        String type = in.path("ExpressionType").asText(destination.get("ExpressionType").asText());
        String expression = in.path("Expression").asText(destination.get("Expression").asText());
        checkExpression(type, expression);
        if (in.hasNonNull("RoleArn")) {
            checkRole(account, in.get("RoleArn").asText());
            destination.put("RoleArn", in.get("RoleArn").asText());
        }
        destination.put("ExpressionType", type);
        destination.put("Expression", expression);
        if (in.hasNonNull("Description")) {
            destination.put("Description", in.get("Description").asText());
        }
        store.put(account, region, DESTINATION, destination.get("Name").asText(), destination);
        return mapper.createObjectNode();
    }

    ObjectNode deleteDestination(ObjectNode in, String region) {
        String account = store.account();
        String name = in.get("Name").asText();
        require(account, region, DESTINATION, name);
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            if (name.equals(device.path("DestinationName").asText())) {
                throw conflict("Destination " + name + " is used by wireless device " + device.get("Id").asText()
                        + ".", name, DESTINATION);
            }
        }
        store.delete(account, region, DESTINATION, name);
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- profiles

    ObjectNode createDeviceProfile(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateDeviceProfile", in);
        if (repeated != null) {
            return repeated;
        }
        if (in.has("LoRaWAN") == in.has("Sidewalk")) {
            throw validation("A device profile is LoRaWAN or Sidewalk.");
        }
        String id = WirelessStore.uuid();
        ObjectNode profile = store.record();
        profile.put("Arn", store.arn(account, region, DEVICE_PROFILE, id));
        if (in.hasNonNull("Name")) {
            profile.put("Name", in.get("Name").asText());
        }
        profile.put("Id", id);
        if (in.has("LoRaWAN")) {
            ObjectNode lorawan = in.get("LoRaWAN").deepCopy();
            if (lorawan.hasNonNull("RfRegion")) {
                LoRaRegions.region(lorawan.get("RfRegion").asText());
            }
            if (lorawan.hasNonNull("MacVersion") && !Set.of("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4", "1.1")
                    .contains(lorawan.get("MacVersion").asText())) {
                throw validation("The MAC version " + lorawan.get("MacVersion").asText() + " isn't supported.");
            }
            profile.set("LoRaWAN", lorawan);
        } else {
            ObjectNode sidewalk = profile.putObject("Sidewalk");
            sidewalk.put("ApplicationServerPublicKey", HexFormat.of().formatHex(ecPublicKey()));
            sidewalk.put("QualificationStatus", false);
            sidewalk.putArray("DakCertificateMetadata").addObject().put("CertificateId", hexId(16))
                    .put("MaxAllowedSignature", 1000).put("FactorySupport", false).put("ApId", hexId(4))
                    .put("DeviceTypeId", "fe" + hexId(2));
        }
        meta(profile).set("tags", WirelessStore.tagsOf(mapper, in.get("Tags")));
        store.put(account, region, DEVICE_PROFILE, id, profile);
        ObjectNode out = mapper.createObjectNode().put("Arn", profile.get("Arn").asText()).put("Id", id);
        store.remember(account, region, "CreateDeviceProfile", in, out);
        return out;
    }

    private static String hexId(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    private static byte[] ecPublicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair().getPublic().getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    ObjectNode getDeviceProfile(ObjectNode in, String region) {
        return WirelessStore.view(require(store.account(), region, DEVICE_PROFILE, in.get("Id").asText()));
    }

    ObjectNode listDeviceProfiles(ObjectNode in, String region) {
        String type = in.path("DeviceProfileType").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode profile : store.list(store.account(), region, DEVICE_PROFILE)) {
            if (type != null && !profile.has("LoRaWAN".equals(type) ? "LoRaWAN" : "Sidewalk")) {
                continue;
            }
            ObjectNode item = mapper.createObjectNode().put("Arn", profile.get("Arn").asText());
            if (profile.hasNonNull("Name")) {
                item.put("Name", profile.get("Name").asText());
            }
            items.add(item.put("Id", profile.get("Id").asText()));
        }
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "DeviceProfileList");
        return out;
    }

    ObjectNode deleteDeviceProfile(ObjectNode in, String region) {
        String account = store.account();
        String id = in.get("Id").asText();
        require(account, region, DEVICE_PROFILE, id);
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            if (id.equals(device.path("LoRaWAN").path("DeviceProfileId").asText())
                    || id.equals(device.path("Sidewalk").path("DeviceProfileId").asText())) {
                throw conflict("Device profile " + id + " is used by wireless device " + device.get("Id").asText()
                        + ".", id, DEVICE_PROFILE);
            }
        }
        store.delete(account, region, DEVICE_PROFILE, id);
        return mapper.createObjectNode();
    }

    ObjectNode createServiceProfile(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateServiceProfile", in);
        if (repeated != null) {
            return repeated;
        }
        String id = WirelessStore.uuid();
        ObjectNode profile = store.record();
        profile.put("Arn", store.arn(account, region, SERVICE_PROFILE, id));
        if (in.hasNonNull("Name")) {
            profile.put("Name", in.get("Name").asText());
        }
        profile.put("Id", id);
        JsonNode given = in.path("LoRaWAN");
        ObjectNode lorawan = profile.putObject("LoRaWAN");
        lorawan.put("UlRate", 60).put("UlBucketSize", 4096).put("UlRatePolicy", "Drop");
        lorawan.put("DlRate", 60).put("DlBucketSize", 4096).put("DlRatePolicy", "Drop");
        lorawan.put("AddGwMetadata", given.path("AddGwMetadata").asBoolean(false));
        lorawan.put("DevStatusReqFreq", 24).put("ReportDevStatusBattery", false).put("ReportDevStatusMargin", false);
        lorawan.put("DrMin", given.path("DrMin").asInt(0)).put("DrMax", given.path("DrMax").asInt(15));
        lorawan.put("PrAllowed", given.path("PrAllowed").asBoolean(false)).put("HrAllowed", false);
        lorawan.put("RaAllowed", given.path("RaAllowed").asBoolean(false)).put("NwkGeoLoc", false);
        lorawan.put("TargetPer", 5).put("MinGwDiversity", 1);
        for (String field : List.of("TxPowerIndexMin", "TxPowerIndexMax", "NbTransMin", "NbTransMax")) {
            if (given.has(field)) {
                lorawan.put(field, given.get(field).asInt());
            }
        }
        if (lorawan.path("DrMin").asInt() > lorawan.path("DrMax").asInt()) {
            throw validation("DrMin can't be greater than DrMax.");
        }
        meta(profile).set("tags", WirelessStore.tagsOf(mapper, in.get("Tags")));
        store.put(account, region, SERVICE_PROFILE, id, profile);
        ObjectNode out = mapper.createObjectNode().put("Arn", profile.get("Arn").asText()).put("Id", id);
        store.remember(account, region, "CreateServiceProfile", in, out);
        return out;
    }

    ObjectNode getServiceProfile(ObjectNode in, String region) {
        return WirelessStore.view(require(store.account(), region, SERVICE_PROFILE, in.get("Id").asText()));
    }

    ObjectNode listServiceProfiles(ObjectNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode profile : store.list(store.account(), region, SERVICE_PROFILE)) {
            ObjectNode item = mapper.createObjectNode().put("Arn", profile.get("Arn").asText());
            if (profile.hasNonNull("Name")) {
                item.put("Name", profile.get("Name").asText());
            }
            items.add(item.put("Id", profile.get("Id").asText()));
        }
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "ServiceProfileList");
        return out;
    }

    ObjectNode deleteServiceProfile(ObjectNode in, String region) {
        String account = store.account();
        String id = in.get("Id").asText();
        require(account, region, SERVICE_PROFILE, id);
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            if (id.equals(device.path("LoRaWAN").path("ServiceProfileId").asText())) {
                throw conflict("Service profile " + id + " is used by wireless device " + device.get("Id").asText()
                        + ".", id, SERVICE_PROFILE);
            }
        }
        store.delete(account, region, SERVICE_PROFILE, id);
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- devices

    ObjectNode createWirelessDevice(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateWirelessDevice", in);
        if (repeated != null) {
            return repeated;
        }
        String type = in.get("Type").asText();
        String destination = in.get("DestinationName").asText();
        require(account, region, DESTINATION, destination);
        String id = WirelessStore.uuid();
        ObjectNode device = store.record();
        device.put("Type", type);
        if (in.hasNonNull("Name")) {
            device.put("Name", in.get("Name").asText());
        }
        if (in.hasNonNull("Description")) {
            device.put("Description", in.get("Description").asText());
        }
        device.put("DestinationName", destination);
        device.put("Id", id);
        device.put("Arn", store.arn(account, region, DEVICE, id));
        device.put("Positioning", in.path("Positioning").asText("Disabled"));
        if ("LoRaWAN".equals(type)) {
            ObjectNode lorawan = checkLoRaWanDevice(account, region, in.get("LoRaWAN"), null);
            device.set("LoRaWAN", lorawan);
        } else {
            device.set("Sidewalk", sidewalkDevice(account, region, in.path("Sidewalk"), null));
        }
        meta(device).set("tags", WirelessStore.tagsOf(mapper, in.get("Tags")));
        network.get().provision(device);
        store.put(account, region, DEVICE, id, device);
        ObjectNode out = mapper.createObjectNode().put("Arn", device.get("Arn").asText()).put("Id", id);
        store.remember(account, region, "CreateWirelessDevice", in, out);
        return out;
    }

    /** Checks a LoRaWAN device's identity, profiles and activation method, exactly one of OTAA or ABP. */
    private ObjectNode checkLoRaWanDevice(String account, String region, JsonNode given, String self) {
        if (given == null || !given.isObject()) {
            throw validation("A LoRaWAN wireless device has LoRaWAN settings.");
        }
        ObjectNode lorawan = given.deepCopy();
        String devEui = lorawan.path("DevEui").asText(null);
        if (devEui == null || !EUI.matcher(devEui).matches()) {
            throw validation("DevEui is 16 hexadecimal digits.");
        }
        for (ObjectNode other : store.list(account, region, DEVICE)) {
            if (!other.get("Id").asText().equals(self)
                    && devEui.equalsIgnoreCase(other.path("LoRaWAN").path("DevEui").asText())) {
                throw conflict("A wireless device with DevEui " + devEui + " already exists.",
                        other.get("Id").asText(), DEVICE);
            }
        }
        String deviceProfileId = lorawan.path("DeviceProfileId").asText(null);
        String serviceProfileId = lorawan.path("ServiceProfileId").asText(null);
        if (deviceProfileId == null || serviceProfileId == null) {
            throw validation("A LoRaWAN wireless device has a device profile and a service profile.");
        }
        ObjectNode profile = require(account, region, DEVICE_PROFILE, deviceProfileId);
        require(account, region, SERVICE_PROFILE, serviceProfileId);
        if (!profile.has("LoRaWAN")) {
            throw validation("Device profile " + deviceProfileId + " isn't a LoRaWAN profile.");
        }
        int methods = 0;
        for (String method : List.of("OtaaV1_1", "OtaaV1_0_x", "AbpV1_1", "AbpV1_0_x")) {
            methods += lorawan.has(method) ? 1 : 0;
        }
        if (methods != 1) {
            throw validation("A LoRaWAN wireless device has exactly one of OtaaV1_1, OtaaV1_0_x, AbpV1_1 and "
                    + "AbpV1_0_x.");
        }
        boolean joins = profile.path("LoRaWAN").path("SupportsJoin").asBoolean(true);
        if ((lorawan.has("OtaaV1_1") || lorawan.has("OtaaV1_0_x")) && !joins) {
            throw validation("Device profile " + deviceProfileId + " doesn't support join, so the device is ABP.");
        }
        if (lorawan.has("OtaaV1_1")) {
            JsonNode otaa = lorawan.get("OtaaV1_1");
            key(otaa, "AppKey");
            key(otaa, "NwkKey");
            eui(otaa, "JoinEui");
        } else if (lorawan.has("OtaaV1_0_x")) {
            JsonNode otaa = lorawan.get("OtaaV1_0_x");
            key(otaa, "AppKey");
            if (!otaa.hasNonNull("AppEui") && !otaa.hasNonNull("JoinEui")) {
                throw validation("OtaaV1_0_x has an AppEui or a JoinEui.");
            }
            if (otaa.hasNonNull("AppEui")) {
                eui(otaa, "AppEui");
            }
            if (otaa.hasNonNull("JoinEui")) {
                eui(otaa, "JoinEui");
            }
        } else {
            JsonNode abp = lorawan.has("AbpV1_1") ? lorawan.get("AbpV1_1") : lorawan.get("AbpV1_0_x");
            String devAddr = abp.path("DevAddr").asText(null);
            if (devAddr == null || !DEV_ADDR.matcher(devAddr).matches()) {
                throw validation("DevAddr is 8 hexadecimal digits.");
            }
            JsonNode keys = abp.path("SessionKeys");
            for (String name : lorawan.has("AbpV1_1") ? List.of("FNwkSIntKey", "SNwkSIntKey", "NwkSEncKey", "AppSKey")
                    : List.of("NwkSKey", "AppSKey")) {
                key(keys, name);
            }
        }
        return lorawan;
    }

    private static void key(JsonNode node, String name) {
        String value = node.path(name).asText(null);
        if (value == null || !KEY.matcher(value).matches()) {
            throw validation(name + " is 32 hexadecimal digits.");
        }
    }

    private static void eui(JsonNode node, String name) {
        String value = node.path(name).asText(null);
        if (value == null || !EUI.matcher(value).matches()) {
            throw validation(name + " is 16 hexadecimal digits.");
        }
    }

    /** A Sidewalk device: its identities and the Ed25519 and P-256 key pairs it's provisioned with. */
    private ObjectNode sidewalkDevice(String account, String region, JsonNode given, ObjectNode existing) {
        ObjectNode sidewalk = existing == null ? mapper.createObjectNode() : existing;
        String profileId = given.path("DeviceProfileId").asText(null);
        if (existing == null) {
            if (profileId == null) {
                throw validation("A Sidewalk wireless device has a device profile.");
            }
            ObjectNode profile = require(account, region, DEVICE_PROFILE, profileId);
            if (!profile.has("Sidewalk")) {
                throw validation("Device profile " + profileId + " isn't a Sidewalk profile.");
            }
            String serial = given.path("SidewalkManufacturingSn").asText(hexId(16));
            for (ObjectNode other : store.list(account, region, DEVICE)) {
                if (serial.equals(other.path("Sidewalk").path("SidewalkManufacturingSn").asText())) {
                    throw conflict("A Sidewalk device with that manufacturing serial number already exists.",
                            other.get("Id").asText(), DEVICE);
                }
            }
            sidewalk.put("SidewalkId", hexId(5));
            sidewalk.put("SidewalkManufacturingSn", serial);
            sidewalk.put("DeviceProfileId", profileId);
            sidewalk.put("CertificateId", profile.at("/Sidewalk/DakCertificateMetadata/0/CertificateId").asText());
            sidewalk.put("Status", "PROVISIONED");
            ArrayNode certificates = sidewalk.putArray("DeviceCertificates");
            ArrayNode privateKeys = sidewalk.putArray("PrivateKeys");
            for (String algorithm : List.of("Ed25519", "P256r1")) {
                KeyPair pair = keyPair(algorithm);
                certificates.addObject().put("SigningAlg", algorithm)
                        .put("Value", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
                privateKeys.addObject().put("SigningAlg", algorithm)
                        .put("Value", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            }
        }
        if (given.has("Positioning")) {
            sidewalk.set("Positioning", given.get("Positioning").deepCopy());
        }
        return sidewalk;
    }

    private static KeyPair keyPair(String algorithm) {
        try {
            if ("Ed25519".equals(algorithm)) {
                return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A device by an identifier of the given type: its ID, DevEui, thing name or Sidewalk serial number. */
    ObjectNode deviceBy(String account, String region, String identifier, String type) {
        if (type == null || "WirelessDeviceId".equals(type)) {
            return device(account, region, identifier);
        }
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            boolean match = switch (type) {
                case "DevEui" -> identifier.equalsIgnoreCase(device.path("LoRaWAN").path("DevEui").asText());
                case "ThingName" -> identifier.equals(device.path("ThingName").asText(null));
                case "SidewalkManufacturingSn" -> identifier.equals(device.path("Sidewalk")
                        .path("SidewalkManufacturingSn").asText(null));
                default -> false;
            };
            if (match) {
                return device;
            }
        }
        throw notFound(DEVICE, identifier);
    }

    ObjectNode getWirelessDevice(ObjectNode in, String region) {
        String account = store.account();
        return WirelessStore.view(deviceBy(account, region, in.get("Identifier").asText(),
                in.get("IdentifierType").asText()));
    }

    ObjectNode listWirelessDevices(ObjectNode in, String region) {
        String account = store.account();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode device : store.list(account, region, DEVICE)) {
            if (!matches(in, "DestinationName", device.path("DestinationName").asText(null))
                    || !matches(in, "WirelessDeviceType", device.get("Type").asText())
                    || !matches(in, "DeviceProfileId", device.path("LoRaWAN").path("DeviceProfileId").asText(
                    device.path("Sidewalk").path("DeviceProfileId").asText(null)))
                    || !matches(in, "ServiceProfileId", device.path("LoRaWAN").path("ServiceProfileId").asText(null))) {
                continue;
            }
            if (in.hasNonNull("FuotaTaskId") && !meta(device).path("fuotaTasks").has(in.get("FuotaTaskId").asText())) {
                continue;
            }
            if (in.hasNonNull("MulticastGroupId")
                    && !meta(device).path("multicastGroups").has(in.get("MulticastGroupId").asText())) {
                continue;
            }
            items.add(deviceStatistics(device, in));
        }
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "WirelessDeviceList");
        return out;
    }

    private static boolean matches(JsonNode in, String field, String value) {
        return !in.hasNonNull(field) || in.get(field).asText().equals(value);
    }

    private ObjectNode deviceStatistics(ObjectNode device, JsonNode in) {
        ObjectNode item = mapper.createObjectNode();
        item.put("Arn", device.get("Arn").asText());
        item.put("Id", device.get("Id").asText());
        item.put("Type", device.get("Type").asText());
        if (device.hasNonNull("Name")) {
            item.put("Name", device.get("Name").asText());
        }
        item.put("DestinationName", device.get("DestinationName").asText());
        JsonNode last = meta(device).path("net").path("lastUplink");
        if (last.has("Timestamp")) {
            item.put("LastUplinkReceivedAt", last.get("Timestamp").asText());
        }
        if (device.has("LoRaWAN")) {
            item.putObject("LoRaWAN").put("DevEui", device.get("LoRaWAN").get("DevEui").asText());
        }
        if (device.has("Sidewalk")) {
            ObjectNode sidewalk = device.get("Sidewalk").deepCopy();
            sidewalk.remove("PrivateKeys");
            sidewalk.remove("CertificateId");
            item.set("Sidewalk", sidewalk);
        }
        if (in.hasNonNull("FuotaTaskId")) {
            item.put("FuotaDeviceStatus", meta(device).path("fuotaTasks").path(in.get("FuotaTaskId").asText())
                    .asText("Initial"));
        }
        if (in.hasNonNull("MulticastGroupId")) {
            JsonNode membership = meta(device).path("multicastGroups").path(in.get("MulticastGroupId").asText());
            item.put("MulticastDeviceStatus", membership.path("status").asText("Pending"));
            item.put("McGroupId", membership.path("mcGroupId").asInt(1));
        }
        item.put("Positioning", device.path("Positioning").asText("Disabled"));
        return item;
    }

    ObjectNode updateWirelessDevice(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode device = device(account, region, in.get("Id").asText());
        if (in.hasNonNull("DestinationName")) {
            require(account, region, DESTINATION, in.get("DestinationName").asText());
            device.put("DestinationName", in.get("DestinationName").asText());
        }
        for (String field : List.of("Name", "Description", "Positioning")) {
            if (in.hasNonNull(field)) {
                device.put(field, in.get(field).asText());
            }
        }
        JsonNode lorawan = in.get("LoRaWAN");
        if (lorawan != null) {
            if (!device.has("LoRaWAN")) {
                throw validation("Wireless device " + device.get("Id").asText() + " isn't a LoRaWAN device.");
            }
            ObjectNode current = (ObjectNode) device.get("LoRaWAN");
            if (lorawan.hasNonNull("DeviceProfileId")) {
                ObjectNode profile = require(account, region, DEVICE_PROFILE, lorawan.get("DeviceProfileId").asText());
                if (!profile.has("LoRaWAN")) {
                    throw validation("Device profile " + profile.get("Id").asText() + " isn't a LoRaWAN profile.");
                }
                current.put("DeviceProfileId", lorawan.get("DeviceProfileId").asText());
            }
            if (lorawan.hasNonNull("ServiceProfileId")) {
                require(account, region, SERVICE_PROFILE, lorawan.get("ServiceProfileId").asText());
                current.put("ServiceProfileId", lorawan.get("ServiceProfileId").asText());
            }
            for (String abp : List.of("AbpV1_1", "AbpV1_0_x")) {
                if (lorawan.has(abp)) {
                    if (!current.has(abp)) {
                        throw validation("The device isn't activated by " + abp + ".");
                    }
                    if (lorawan.get(abp).has("FCntStart")) {
                        ((ObjectNode) current.get(abp)).put("FCntStart", lorawan.get(abp).get("FCntStart").asInt());
                        network.get().resetCounters(device, lorawan.get(abp).get("FCntStart").asInt());
                    }
                }
            }
            if (lorawan.has("FPorts")) {
                ObjectNode ports = (ObjectNode) current.with("FPorts");
                lorawan.get("FPorts").fields().forEachRemaining(e -> ports.set(e.getKey(), e.getValue().deepCopy()));
            }
        }
        if (in.has("Sidewalk")) {
            if (!device.has("Sidewalk")) {
                throw validation("Wireless device " + device.get("Id").asText() + " isn't a Sidewalk device.");
            }
            sidewalkDevice(account, region, in.get("Sidewalk"), (ObjectNode) device.get("Sidewalk"));
        }
        store.put(account, region, DEVICE, device.get("Id").asText(), device);
        return mapper.createObjectNode();
    }

    ObjectNode deleteWirelessDevice(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode device = device(account, region, in.get("Id").asText());
        store.delete(account, region, DEVICE, device.get("Id").asText());
        return mapper.createObjectNode();
    }

    /** Deregistering a Sidewalk device returns it to PROVISIONED; a LoRaWAN device forgets its session. */
    ObjectNode deregisterWirelessDevice(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode device = deviceBy(account, region, in.get("Identifier").asText(), null);
        if (device.has("Sidewalk")) {
            ((ObjectNode) device.get("Sidewalk")).put("Status", "PROVISIONED");
        } else {
            network.get().forgetSession(device);
        }
        store.put(account, region, DEVICE, device.get("Id").asText(), device);
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- gateways

    ObjectNode createWirelessGateway(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateWirelessGateway", in);
        if (repeated != null) {
            return repeated;
        }
        ObjectNode lorawan = in.get("LoRaWAN").deepCopy();
        eui(lorawan, "GatewayEui");
        LoRaRegions.region(lorawan.path("RfRegion").asText(null));
        String eui = lorawan.get("GatewayEui").asText();
        for (ObjectNode other : store.list(account, region, GATEWAY)) {
            if (eui.equalsIgnoreCase(other.path("LoRaWAN").path("GatewayEui").asText())) {
                throw conflict("A wireless gateway with GatewayEui " + eui + " already exists.",
                        other.get("Id").asText(), GATEWAY);
            }
        }
        String id = WirelessStore.uuid();
        ObjectNode gateway = store.record();
        if (in.hasNonNull("Name")) {
            gateway.put("Name", in.get("Name").asText());
        }
        gateway.put("Id", id);
        if (in.hasNonNull("Description")) {
            gateway.put("Description", in.get("Description").asText());
        }
        gateway.set("LoRaWAN", lorawan);
        gateway.put("Arn", store.arn(account, region, GATEWAY, id));
        meta(gateway).set("tags", WirelessStore.tagsOf(mapper, in.get("Tags")));
        meta(gateway).set("certificate", mapper.createObjectNode().put("LoRaWANNetworkServerCertificateId",
                hexId(32)));
        store.put(account, region, GATEWAY, id, gateway);
        ObjectNode out = mapper.createObjectNode().put("Arn", gateway.get("Arn").asText()).put("Id", id);
        store.remember(account, region, "CreateWirelessGateway", in, out);
        return out;
    }

    ObjectNode gatewayBy(String account, String region, String identifier, String type) {
        if (type == null || "WirelessGatewayId".equals(type)) {
            return gateway(account, region, identifier);
        }
        for (ObjectNode gateway : store.list(account, region, GATEWAY)) {
            boolean match = switch (type) {
                case "GatewayEui" -> identifier.equalsIgnoreCase(gateway.path("LoRaWAN").path("GatewayEui").asText());
                case "ThingName" -> identifier.equals(gateway.path("ThingName").asText(null));
                default -> false;
            };
            if (match) {
                return gateway;
            }
        }
        throw notFound(GATEWAY, identifier);
    }

    ObjectNode getWirelessGateway(ObjectNode in, String region) {
        return WirelessStore.view(gatewayBy(store.account(), region, in.get("Identifier").asText(),
                in.get("IdentifierType").asText()));
    }

    ObjectNode listWirelessGateways(ObjectNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode gateway : store.list(store.account(), region, GATEWAY)) {
            ObjectNode item = WirelessStore.view(gateway);
            item.remove("ThingName");
            item.remove("ThingArn");
            String last = meta(gateway).path("net").path("lastUplinkReceivedAt").asText(null);
            if (last != null) {
                item.put("LastUplinkReceivedAt", last);
            }
            items.add(item);
        }
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "WirelessGatewayList");
        return out;
    }

    ObjectNode updateWirelessGateway(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode gateway = gateway(account, region, in.get("Id").asText());
        for (String field : List.of("Name", "Description")) {
            if (in.hasNonNull(field)) {
                gateway.put(field, in.get(field).asText());
            }
        }
        ObjectNode lorawan = (ObjectNode) gateway.get("LoRaWAN");
        for (String field : List.of("JoinEuiFilters", "NetIdFilters")) {
            if (in.has(field)) {
                lorawan.set(field, in.get(field).deepCopy());
            }
        }
        if (in.has("MaxEirp")) {
            lorawan.put("MaxEirp", in.get("MaxEirp").asDouble());
        }
        store.put(account, region, GATEWAY, gateway.get("Id").asText(), gateway);
        network.get().reconfigure(account, region, gateway);
        return mapper.createObjectNode();
    }

    ObjectNode deleteWirelessGateway(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode gateway = gateway(account, region, in.get("Id").asText());
        network.get().disconnect(account, region, gateway);
        store.delete(account, region, GATEWAY, gateway.get("Id").asText());
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- things and certificates

    private String thingName(String account, String region, String thingArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(thingArn);
        } catch (RuntimeException e) {
            throw validation("The thing ARN " + thingArn + " isn't valid.");
        }
        if (!"iot".equals(arn.service()) || !arn.resource().startsWith("thing/")) {
            throw validation("The ARN " + thingArn + " isn't an IoT thing.");
        }
        String name = arn.resource().substring("thing/".length());
        try {
            RequestScopes.callAs(account, region, () -> iot.get().describeThing(name, region));
        } catch (AwsException e) {
            throw notFound("Thing", name);
        }
        return name;
    }

    private ObjectNode associateThing(String kind, ObjectNode in, String region) {
        String account = store.account();
        ObjectNode record = require(account, region, kind, in.get("Id").asText());
        String name = thingName(account, region, in.get("ThingArn").asText());
        for (String other : List.of(DEVICE, GATEWAY)) {
            for (ObjectNode candidate : store.list(account, region, other)) {
                if (name.equals(candidate.path("ThingName").asText(null))
                        && !candidate.get("Id").asText().equals(record.get("Id").asText())) {
                    throw conflict("Thing " + name + " is already associated with " + candidate.get("Id").asText()
                            + ".", record.get("Id").asText(), kind);
                }
            }
        }
        if (record.hasNonNull("ThingName") && !name.equals(record.get("ThingName").asText())) {
            throw conflict(kind + " " + record.get("Id").asText() + " is already associated with a thing.",
                    record.get("Id").asText(), kind);
        }
        record.put("ThingName", name);
        record.put("ThingArn", in.get("ThingArn").asText());
        store.put(account, region, kind, record.get("Id").asText(), record);
        return mapper.createObjectNode();
    }

    private ObjectNode disassociateThing(String kind, ObjectNode in, String region) {
        String account = store.account();
        ObjectNode record = require(account, region, kind, in.get("Id").asText());
        record.remove("ThingName");
        record.remove("ThingArn");
        store.put(account, region, kind, record.get("Id").asText(), record);
        return mapper.createObjectNode();
    }

    ObjectNode associateWirelessDeviceWithThing(ObjectNode in, String region) {
        return associateThing(DEVICE, in, region);
    }

    ObjectNode disassociateWirelessDeviceFromThing(ObjectNode in, String region) {
        return disassociateThing(DEVICE, in, region);
    }

    ObjectNode associateWirelessGatewayWithThing(ObjectNode in, String region) {
        return associateThing(GATEWAY, in, region);
    }

    ObjectNode disassociateWirelessGatewayFromThing(ObjectNode in, String region) {
        return disassociateThing(GATEWAY, in, region);
    }

    ObjectNode associateWirelessGatewayWithCertificate(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode gateway = gateway(account, region, in.get("Id").asText());
        String certificate = in.get("IotCertificateId").asText();
        try {
            RequestScopes.callAs(account, region, () -> iot.get().describeCertificate(certificate, region));
        } catch (AwsException e) {
            throw notFound("IotCertificate", certificate);
        }
        for (ObjectNode other : store.list(account, region, GATEWAY)) {
            if (certificate.equals(meta(other).path("certificate").path("IotCertificateId").asText(null))
                    && !other.get("Id").asText().equals(gateway.get("Id").asText())) {
                throw conflict("Certificate " + certificate + " is associated with gateway " + other.get("Id").asText()
                        + ".", gateway.get("Id").asText(), GATEWAY);
            }
        }
        ((ObjectNode) meta(gateway).with("certificate")).put("IotCertificateId", certificate);
        store.put(account, region, GATEWAY, gateway.get("Id").asText(), gateway);
        return mapper.createObjectNode().put("IotCertificateId", certificate);
    }

    ObjectNode disassociateWirelessGatewayFromCertificate(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode gateway = gateway(account, region, in.get("Id").asText());
        ((ObjectNode) meta(gateway).with("certificate")).remove("IotCertificateId");
        store.put(account, region, GATEWAY, gateway.get("Id").asText(), gateway);
        return mapper.createObjectNode();
    }

    ObjectNode getWirelessGatewayCertificate(ObjectNode in, String region) {
        ObjectNode gateway = gateway(store.account(), region, in.get("Id").asText());
        return meta(gateway).path("certificate").deepCopy();
    }

    ObjectNode getWirelessGatewayFirmwareInformation(ObjectNode in, String region) {
        ObjectNode gateway = gateway(store.account(), region, in.get("Id").asText());
        ObjectNode out = mapper.createObjectNode();
        JsonNode version = meta(gateway).path("net").path("version");
        if (version.isObject()) {
            out.putObject("LoRaWAN").set("CurrentVersion", version.deepCopy());
        }
        return out;
    }

    // ---------------------------------------------------------------- tags

    private ObjectNode tagged(String account, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (RuntimeException e) {
            throw validation("The ARN " + arn + " isn't valid.");
        }
        if (!WirelessStore.SERVICE.equals(parsed.service())) {
            throw validation("The ARN " + arn + " isn't an IoT Wireless ARN.");
        }
        String resource = parsed.resource();
        int slash = resource.indexOf('/');
        if (slash < 0) {
            throw notFound("Resource", arn);
        }
        return store.get(account, parsed.region(), resource.substring(0, slash), resource.substring(slash + 1))
                .orElseThrow(() -> notFound(resource.substring(0, slash), resource.substring(slash + 1)));
    }

    private void saveTagged(String account, String arn, ObjectNode record) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        int slash = resource.indexOf('/');
        store.put(account, parsed.region(), resource.substring(0, slash), resource.substring(slash + 1), record);
    }

    ObjectNode listTagsForResource(ObjectNode in, String region) {
        ObjectNode record = tagged(store.account(), in.get("ResourceArn").asText());
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", WirelessStore.tagList(mapper, meta(record).path("tags")));
        return out;
    }

    ObjectNode tagResource(ObjectNode in, String region) {
        String account = store.account();
        String arn = in.get("ResourceArn").asText();
        ObjectNode record = tagged(account, arn);
        ObjectNode tags = (ObjectNode) meta(record).with("tags");
        tags.setAll(WirelessStore.tagsOf(mapper, in.get("Tags")));
        if (tags.size() > 200) {
            throw new AwsException("TooManyTagsException", "A resource can have at most 200 tags.", 400,
                    Map.of("ResourceName", arn));
        }
        saveTagged(account, arn, record);
        return mapper.createObjectNode();
    }

    ObjectNode untagResource(ObjectNode in, String region) {
        String account = store.account();
        String arn = in.get("ResourceArn").asText();
        ObjectNode record = tagged(account, arn);
        ObjectNode tags = (ObjectNode) meta(record).with("tags");
        in.get("TagKeys").forEach(key -> tags.remove(key.asText()));
        saveTagged(account, arn, record);
        return mapper.createObjectNode();
    }

    static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
