package io.github.hectorvent.floci.services.iotwireless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.services.iot.IotService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local AWS IoT Wireless control and data plane (restJson1, {@code iotwireless}).
 *
 * <p>Covers the Destination / DeviceProfile / ServiceProfile / WirelessDevice /
 * WirelessGateway lifecycle Alchemy deploys, plus the binding operations
 * (queued downlinks, statistics, positions, GetServiceEndpoint,
 * GetPositionEstimate, TestWirelessDevice).
 */
@ApplicationScoped
public class IotWirelessService implements Resettable, TagHandler {

    static final String SERVICE = "iotwireless";
    private static final String UPLINK_TOPIC = "iotwireless/uplink";
    private static final String HELLO_B64 = "aGVsbG8=";
    private static final String LNS_TRUST =
            "-----BEGIN CERTIFICATE-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA\n-----END CERTIFICATE-----";
    private static final Logger LOG = Logger.getLogger(IotWirelessService.class);

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final IotService iotService;

    private final ConcurrentHashMap<String, Destination> destinations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Profile> deviceProfiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Profile> serviceProfiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Device> devices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Gateway> gateways = new ConcurrentHashMap<>();

    @Inject
    public IotWirelessService(
            ObjectMapper objectMapper, RegionResolver regionResolver, IotService iotService) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.iotService = iotService;
    }

    IotWirelessService(ObjectMapper objectMapper, RegionResolver regionResolver) {
        this(objectMapper, regionResolver, null);
    }

    @Override
    public void clear() {
        destinations.clear();
        deviceProfiles.clear();
        serviceProfiles.clear();
        devices.clear();
        gateways.clear();
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public boolean tagsBodyIsList() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(arn).tags);
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged resource = requireTagged(arn);
        if (tags != null) {
            resource.tags.putAll(tags);
        }
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged resource = requireTagged(arn);
        if (tagKeys != null) {
            tagKeys.forEach(resource.tags::remove);
        }
    }

    public synchronized ObjectNode createDestination(String region, JsonNode request) {
        String name = requireText(request, "Name");
        String expressionType = requireText(request, "ExpressionType");
        String expression = requireText(request, "Expression");
        String roleArn = requireText(request, "RoleArn");
        String key = destinationKey(region, name);
        if (destinations.containsKey(key)) {
            throw conflict(name, "Destination");
        }
        Destination destination = new Destination();
        destination.accountId = account();
        destination.region = region;
        destination.name = name;
        destination.arn = arn(region, "Destination/" + name);
        destination.expressionType = expressionType;
        destination.expression = expression;
        destination.description = textOrNull(request, "Description");
        destination.roleArn = roleArn;
        destination.tags.putAll(readTags(request));
        destinations.put(key, destination);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", destination.arn);
        response.put("Name", destination.name);
        return response;
    }

    public ObjectNode getDestination(String region, String name) {
        Destination destination = requireDestination(region, name);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", destination.arn);
        response.put("Name", destination.name);
        response.put("Expression", destination.expression);
        response.put("ExpressionType", destination.expressionType);
        putOptional(response, "Description", destination.description);
        response.put("RoleArn", destination.roleArn);
        return response;
    }

    public synchronized ObjectNode updateDestination(String region, String name, JsonNode request) {
        Destination destination = requireDestination(region, name);
        if (request.hasNonNull("ExpressionType")) {
            destination.expressionType = request.get("ExpressionType").asText();
        }
        if (request.hasNonNull("Expression")) {
            destination.expression = request.get("Expression").asText();
        }
        if (request.has("Description")) {
            destination.description = textOrNull(request, "Description");
        }
        if (request.hasNonNull("RoleArn")) {
            destination.roleArn = request.get("RoleArn").asText();
        }
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteDestination(String region, String name) {
        Destination removed = destinations.remove(destinationKey(region, name));
        if (removed == null) {
            throw notFound(name, "Destination");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDestinations(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("DestinationList");
        for (Destination destination : destinations.values()) {
            if (!owned(destination, region)) {
                continue;
            }
            ObjectNode summary = list.addObject();
            summary.put("Arn", destination.arn);
            summary.put("Name", destination.name);
            summary.put("Expression", destination.expression);
            summary.put("ExpressionType", destination.expressionType);
            putOptional(summary, "Description", destination.description);
            summary.put("RoleArn", destination.roleArn);
        }
        return response;
    }

    public synchronized ObjectNode createDeviceProfile(String region, JsonNode request) {
        String id = newId();
        String name = optionalName(request, id);
        Profile profile = new Profile();
        profile.accountId = account();
        profile.region = region;
        profile.id = id;
        profile.name = name;
        profile.arn = arn(region, "DeviceProfile/" + id);
        profile.loRaWAN = copyObject(request.get("LoRaWAN"));
        profile.sidewalk = copyObject(request.get("Sidewalk"));
        profile.tags.putAll(readTags(request));
        deviceProfiles.put(profileKey(region, id), profile);
        return idArn(profile);
    }

    public ObjectNode getDeviceProfile(String region, String id) {
        Profile profile = requireDeviceProfile(region, id);
        ObjectNode response = idArn(profile);
        putOptional(response, "Name", profile.name);
        if (profile.loRaWAN != null) {
            response.set("LoRaWAN", profile.loRaWAN.deepCopy());
        }
        if (profile.sidewalk != null) {
            response.set("Sidewalk", profile.sidewalk.deepCopy());
        }
        return response;
    }

    public synchronized ObjectNode deleteDeviceProfile(String region, String id) {
        Profile removed = deviceProfiles.remove(profileKey(region, id));
        if (removed == null) {
            throw notFound(id, "DeviceProfile");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDeviceProfiles(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("DeviceProfileList");
        for (Profile profile : deviceProfiles.values()) {
            if (!owned(profile, region)) {
                continue;
            }
            ObjectNode summary = list.addObject();
            summary.put("Arn", profile.arn);
            summary.put("Id", profile.id);
            putOptional(summary, "Name", profile.name);
        }
        return response;
    }

    public synchronized ObjectNode createServiceProfile(String region, JsonNode request) {
        String id = newId();
        String name = optionalName(request, id);
        Profile profile = new Profile();
        profile.accountId = account();
        profile.region = region;
        profile.id = id;
        profile.name = name;
        profile.arn = arn(region, "ServiceProfile/" + id);
        profile.loRaWAN = copyObject(request.get("LoRaWAN"));
        profile.tags.putAll(readTags(request));
        serviceProfiles.put(profileKey(region, id), profile);
        return idArn(profile);
    }

    public ObjectNode getServiceProfile(String region, String id) {
        Profile profile = requireServiceProfile(region, id);
        ObjectNode response = idArn(profile);
        putOptional(response, "Name", profile.name);
        if (profile.loRaWAN != null) {
            response.set("LoRaWAN", profile.loRaWAN.deepCopy());
        }
        return response;
    }

    public synchronized ObjectNode deleteServiceProfile(String region, String id) {
        Profile removed = serviceProfiles.remove(profileKey(region, id));
        if (removed == null) {
            throw notFound(id, "ServiceProfile");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listServiceProfiles(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ServiceProfileList");
        for (Profile profile : serviceProfiles.values()) {
            if (!owned(profile, region)) {
                continue;
            }
            ObjectNode summary = list.addObject();
            summary.put("Arn", profile.arn);
            summary.put("Id", profile.id);
            putOptional(summary, "Name", profile.name);
        }
        return response;
    }

    public synchronized ObjectNode createWirelessDevice(String region, JsonNode request) {
        String type = requireText(request, "Type");
        String destinationName = requireText(request, "DestinationName");
        JsonNode loRaWAN = copyObject(request.get("LoRaWAN"));
        String devEui = loRaWAN == null ? null : textOrNull(loRaWAN, "DevEui");
        if (devEui != null && findDeviceByDevEui(region, devEui) != null) {
            throw conflict(devEui, "WirelessDevice");
        }
        String id = newId();
        String name = optionalName(request, id);
        Device device = new Device();
        device.accountId = account();
        device.region = region;
        device.id = id;
        device.name = name;
        device.arn = arn(region, "WirelessDevice/" + id);
        device.type = type;
        device.destinationName = destinationName;
        device.description = textOrNull(request, "Description");
        device.positioning = textOrNull(request, "Positioning");
        device.loRaWAN = loRaWAN;
        device.sidewalk = copyObject(request.get("Sidewalk"));
        device.tags.putAll(readTags(request));
        devices.put(deviceKey(region, id), device);
        return idArn(device);
    }

    public ObjectNode getWirelessDevice(String region, String identifier, String identifierType) {
        Device device = requireDevice(region, identifier, identifierType);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Type", device.type);
        putOptional(response, "Name", device.name);
        putOptional(response, "Description", device.description);
        response.put("DestinationName", device.destinationName);
        response.put("Id", device.id);
        response.put("Arn", device.arn);
        putOptional(response, "Positioning", device.positioning);
        if (device.loRaWAN != null) {
            response.set("LoRaWAN", device.loRaWAN.deepCopy());
        }
        if (device.sidewalk != null) {
            response.set("Sidewalk", device.sidewalk.deepCopy());
        }
        return response;
    }

    public synchronized ObjectNode updateWirelessDevice(String region, String id, JsonNode request) {
        Device device = requireDevice(region, id, "WirelessDeviceId");
        if (request.hasNonNull("DestinationName")) {
            device.destinationName = request.get("DestinationName").asText();
        }
        if (request.hasNonNull("Name")) {
            device.name = request.get("Name").asText();
        }
        if (request.has("Description")) {
            device.description = textOrNull(request, "Description");
        }
        if (request.hasNonNull("Positioning")) {
            device.positioning = request.get("Positioning").asText();
        }
        if (request.has("LoRaWAN") && request.get("LoRaWAN").isObject()) {
            ObjectNode current = device.loRaWAN == null
                    ? objectMapper.createObjectNode()
                    : (ObjectNode) device.loRaWAN.deepCopy();
            JsonNode update = request.get("LoRaWAN");
            copyIfPresent(update, current, "DeviceProfileId");
            copyIfPresent(update, current, "ServiceProfileId");
            if (update.has("FPorts")) {
                current.set("FPorts", update.get("FPorts").deepCopy());
            }
            device.loRaWAN = current;
        }
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteWirelessDevice(String region, String id) {
        Device removed = devices.remove(deviceKey(region, id));
        if (removed == null) {
            throw notFound(id, "WirelessDevice");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listWirelessDevices(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("WirelessDeviceList");
        for (Device device : devices.values()) {
            if (!owned(device, region)) {
                continue;
            }
            ObjectNode summary = list.addObject();
            summary.put("Arn", device.arn);
            summary.put("Id", device.id);
            summary.put("Type", device.type);
            putOptional(summary, "Name", device.name);
            summary.put("DestinationName", device.destinationName);
            putOptional(summary, "LastUplinkReceivedAt", device.lastUplinkReceivedAt);
            putOptional(summary, "Positioning", device.positioning);
            if (device.loRaWAN != null && device.loRaWAN.hasNonNull("DevEui")) {
                ObjectNode loRaWAN = summary.putObject("LoRaWAN");
                loRaWAN.put("DevEui", device.loRaWAN.get("DevEui").asText());
            }
        }
        return response;
    }

    public synchronized ObjectNode createWirelessGateway(String region, JsonNode request) {
        JsonNode loRaWAN = request.get("LoRaWAN");
        if (loRaWAN == null || !loRaWAN.isObject()) {
            throw new AwsException("ValidationException", "LoRaWAN is required.", 400);
        }
        String gatewayEui = textOrNull(loRaWAN, "GatewayEui");
        if (gatewayEui != null && findGatewayByEui(region, gatewayEui) != null) {
            throw conflict(gatewayEui, "WirelessGateway");
        }
        String id = newId();
        String name = optionalName(request, id);
        Gateway gateway = new Gateway();
        gateway.accountId = account();
        gateway.region = region;
        gateway.id = id;
        gateway.name = name;
        gateway.arn = arn(region, "WirelessGateway/" + id);
        gateway.description = textOrNull(request, "Description");
        gateway.loRaWAN = copyObject(loRaWAN);
        gateway.tags.putAll(readTags(request));
        gateways.put(gatewayKey(region, id), gateway);
        return idArn(gateway);
    }

    public ObjectNode getWirelessGateway(String region, String identifier, String identifierType) {
        Gateway gateway = requireGateway(region, identifier, identifierType);
        ObjectNode response = objectMapper.createObjectNode();
        putOptional(response, "Name", gateway.name);
        response.put("Id", gateway.id);
        putOptional(response, "Description", gateway.description);
        response.put("Arn", gateway.arn);
        if (gateway.loRaWAN != null) {
            response.set("LoRaWAN", gateway.loRaWAN.deepCopy());
        }
        return response;
    }

    public synchronized ObjectNode updateWirelessGateway(String region, String id, JsonNode request) {
        Gateway gateway = requireGateway(region, id, "WirelessGatewayId");
        if (request.hasNonNull("Name")) {
            gateway.name = request.get("Name").asText();
        }
        if (request.has("Description")) {
            gateway.description = textOrNull(request, "Description");
        }
        if (gateway.loRaWAN == null) {
            gateway.loRaWAN = objectMapper.createObjectNode();
        }
        ObjectNode loRaWAN = (ObjectNode) gateway.loRaWAN;
        if (request.has("JoinEuiFilters")) {
            loRaWAN.set("JoinEuiFilters", request.get("JoinEuiFilters").deepCopy());
        }
        if (request.has("NetIdFilters")) {
            loRaWAN.set("NetIdFilters", request.get("NetIdFilters").deepCopy());
        }
        if (request.has("MaxEirp")) {
            loRaWAN.set("MaxEirp", request.get("MaxEirp").deepCopy());
        }
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteWirelessGateway(String region, String id) {
        Gateway removed = gateways.remove(gatewayKey(region, id));
        if (removed == null) {
            throw notFound(id, "WirelessGateway");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listWirelessGateways(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("WirelessGatewayList");
        for (Gateway gateway : gateways.values()) {
            if (!owned(gateway, region)) {
                continue;
            }
            ObjectNode summary = list.addObject();
            summary.put("Arn", gateway.arn);
            summary.put("Id", gateway.id);
            putOptional(summary, "Name", gateway.name);
            putOptional(summary, "Description", gateway.description);
            if (gateway.loRaWAN != null) {
                summary.set("LoRaWAN", gateway.loRaWAN.deepCopy());
            }
        }
        return response;
    }

    public synchronized ObjectNode sendDataToWirelessDevice(String region, String id, JsonNode request) {
        Device device = requireDevice(region, id, "WirelessDeviceId");
        QueuedMessage message = new QueuedMessage();
        message.messageId = UUID.randomUUID().toString();
        message.transmitMode = request.path("TransmitMode").asInt(1);
        message.payloadData = textOrNull(request, "PayloadData");
        message.receivedAt = Instant.now().toString();
        JsonNode metadata = request.get("WirelessMetadata");
        if (metadata != null && metadata.has("LoRaWAN")) {
            message.loRaWAN = copyObject(metadata.get("LoRaWAN"));
        }
        device.queue.add(message);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("MessageId", message.messageId);
        return response;
    }

    public ObjectNode listQueuedMessages(String region, String id) {
        Device device = requireDevice(region, id, "WirelessDeviceId");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("DownlinkQueueMessagesList");
        for (QueuedMessage message : device.queue) {
            ObjectNode node = list.addObject();
            node.put("MessageId", message.messageId);
            node.put("TransmitMode", message.transmitMode);
            node.put("ReceivedAt", message.receivedAt);
            if (message.loRaWAN != null) {
                node.set("LoRaWAN", message.loRaWAN.deepCopy());
            }
        }
        return response;
    }

    public synchronized ObjectNode deleteQueuedMessages(String region, String id, String messageId) {
        Device device = requireDevice(region, id, "WirelessDeviceId");
        if (messageId == null || messageId.isBlank()) {
            throw new AwsException("ValidationException", "MessageId is required.", 400);
        }
        if ("*".equals(messageId)) {
            device.queue.clear();
        } else {
            boolean removed = device.queue.removeIf(message -> messageId.equals(message.messageId));
            if (!removed) {
                throw notFound(messageId, "WirelessDevice");
            }
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode getWirelessDeviceStatistics(String region, String id) {
        Device device = requireDevice(region, id, "WirelessDeviceId");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("WirelessDeviceId", device.id);
        putOptional(response, "LastUplinkReceivedAt", device.lastUplinkReceivedAt);
        if (device.loRaWAN != null) {
            ObjectNode loRaWAN = response.putObject("LoRaWAN");
            if (device.loRaWAN.hasNonNull("DevEui")) {
                loRaWAN.put("DevEui", device.loRaWAN.get("DevEui").asText());
            }
            putOptional(loRaWAN, "Timestamp", device.lastUplinkReceivedAt);
        }
        return response;
    }

    public ObjectNode getWirelessGatewayStatistics(String region, String id) {
        Gateway gateway = requireGateway(region, id, "WirelessGatewayId");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("WirelessGatewayId", gateway.id);
        response.put("ConnectionStatus", "Connected");
        return response;
    }

    public ObjectNode getServiceEndpoint(String region, String serviceType) {
        String type = (serviceType == null || serviceType.isBlank()) ? "LNS" : serviceType;
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ServiceType", type);
        response.put("ServiceEndpoint", type.toLowerCase() + ".iotwireless." + region + ".amazonaws.com:443");
        response.put("ServerTrust", LNS_TRUST);
        return response;
    }

    public synchronized void updateResourcePosition(String region, String resourceId, String resourceType, String geoJson) {
        Tagged resource = requirePositionResource(region, resourceId, resourceType);
        if (geoJson == null || geoJson.isBlank()) {
            throw new AwsException("ValidationException", "GeoJsonPayload is required.", 400);
        }
        resource.geoJson = geoJson;
    }

    public String getResourcePosition(String region, String resourceId, String resourceType) {
        Tagged resource = requirePositionResource(region, resourceId, resourceType);
        if (resource.geoJson == null) {
            throw notFound(resourceId, resourceType == null ? "WirelessDevice" : resourceType);
        }
        return resource.geoJson;
    }

    public String getPositionEstimate() {
        return "{\"type\":\"Point\",\"coordinates\":[-122.33,47.61,0]}";
    }

    public synchronized ObjectNode testWirelessDevice(String region, String id) {
        Device device = requireDevice(region, id, "WirelessDeviceId");
        device.lastUplinkReceivedAt = Instant.now().toString();
        ObjectNode uplink = objectMapper.createObjectNode();
        uplink.put("WirelessDeviceId", device.id);
        uplink.put("PayloadData", HELLO_B64);
        ObjectNode metadata = uplink.putObject("WirelessMetadata").putObject("LoRaWAN");
        if (device.loRaWAN != null && device.loRaWAN.hasNonNull("DevEui")) {
            metadata.put("DevEui", device.loRaWAN.get("DevEui").asText());
        }
        metadata.put("FPort", 1);
        metadata.put("Timestamp", device.lastUplinkReceivedAt);
        routeUplink(region, device, uplink);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Result", "Uplink simulation succeeded.");
        return response;
    }

    private void routeUplink(String region, Device device, ObjectNode uplink) {
        if (iotService == null) {
            return;
        }
        try {
            byte[] payload = objectMapper.writeValueAsBytes(uplink);
            Destination destination = destinations.get(destinationKey(region, device.destinationName));
            if (destination != null && "MqttTopic".equals(destination.expressionType)) {
                iotService.publish(destination.expression, payload, false, 0, region);
            } else {
                iotService.publish(UPLINK_TOPIC, payload, false, 0, region);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to route test uplink for %s: %s", device.id, e.getMessage());
        }
    }

    private Tagged requireTagged(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid resource ARN: " + arn, 400);
        }
        String resource = parsed.resource();
        int slash = resource.indexOf('/');
        if (slash < 0) {
            throw notFound(arn, "Resource");
        }
        String type = resource.substring(0, slash);
        String id = resource.substring(slash + 1);
        String region = parsed.region();
        return switch (type) {
            case "Destination" -> requireDestination(region, id);
            case "DeviceProfile" -> requireDeviceProfile(region, id);
            case "ServiceProfile" -> requireServiceProfile(region, id);
            case "WirelessDevice" -> requireDevice(region, id, "WirelessDeviceId");
            case "WirelessGateway" -> requireGateway(region, id, "WirelessGatewayId");
            default -> throw notFound(arn, type);
        };
    }

    private Tagged requirePositionResource(String region, String resourceId, String resourceType) {
        if (resourceType == null || resourceType.isBlank() || "WirelessDevice".equals(resourceType)) {
            return requireDevice(region, resourceId, "WirelessDeviceId");
        }
        if ("WirelessGateway".equals(resourceType)) {
            return requireGateway(region, resourceId, "WirelessGatewayId");
        }
        throw new AwsException("ValidationException", "Unsupported resourceType: " + resourceType, 400);
    }

    private Destination requireDestination(String region, String name) {
        Destination destination = destinations.get(destinationKey(region, name));
        if (destination == null || !owned(destination, region)) {
            throw notFound(name, "Destination");
        }
        return destination;
    }

    private Profile requireDeviceProfile(String region, String id) {
        Profile profile = deviceProfiles.get(profileKey(region, id));
        if (profile == null || !owned(profile, region)) {
            throw notFound(id, "DeviceProfile");
        }
        return profile;
    }

    private Profile requireServiceProfile(String region, String id) {
        Profile profile = serviceProfiles.get(profileKey(region, id));
        if (profile == null || !owned(profile, region)) {
            throw notFound(id, "ServiceProfile");
        }
        return profile;
    }

    private Device requireDevice(String region, String identifier, String identifierType) {
        String type = identifierType == null || identifierType.isBlank() ? "WirelessDeviceId" : identifierType;
        Device device = switch (type) {
            case "DevEui" -> findDeviceByDevEui(region, identifier);
            case "ThingName" -> findDeviceByName(region, identifier);
            case "SidewalkManufacturingSn" -> findDeviceBySidewalkSn(region, identifier);
            default -> devices.get(deviceKey(region, identifier));
        };
        if (device == null || !owned(device, region)) {
            throw notFound(identifier, "WirelessDevice");
        }
        return device;
    }

    private Gateway requireGateway(String region, String identifier, String identifierType) {
        String type = identifierType == null || identifierType.isBlank() ? "WirelessGatewayId" : identifierType;
        Gateway gateway = switch (type) {
            case "GatewayEui" -> findGatewayByEui(region, identifier);
            case "ThingName" -> findGatewayByName(region, identifier);
            default -> gateways.get(gatewayKey(region, identifier));
        };
        if (gateway == null || !owned(gateway, region)) {
            throw notFound(identifier, "WirelessGateway");
        }
        return gateway;
    }

    private Device findDeviceByDevEui(String region, String devEui) {
        for (Device device : devices.values()) {
            if (owned(device, region)
                    && device.loRaWAN != null
                    && devEui.equalsIgnoreCase(textOrNull(device.loRaWAN, "DevEui"))) {
                return device;
            }
        }
        return null;
    }

    private Device findDeviceByName(String region, String name) {
        for (Device device : devices.values()) {
            if (owned(device, region) && name.equals(device.name)) {
                return device;
            }
        }
        return null;
    }

    private Device findDeviceBySidewalkSn(String region, String serial) {
        for (Device device : devices.values()) {
            if (owned(device, region)
                    && device.sidewalk != null
                    && serial.equals(textOrNull(device.sidewalk, "SidewalkManufacturingSn"))) {
                return device;
            }
        }
        return null;
    }

    private Gateway findGatewayByEui(String region, String eui) {
        for (Gateway gateway : gateways.values()) {
            if (owned(gateway, region)
                    && gateway.loRaWAN != null
                    && eui.equalsIgnoreCase(textOrNull(gateway.loRaWAN, "GatewayEui"))) {
                return gateway;
            }
        }
        return null;
    }

    private Gateway findGatewayByName(String region, String name) {
        for (Gateway gateway : gateways.values()) {
            if (owned(gateway, region) && name.equals(gateway.name)) {
                return gateway;
            }
        }
        return null;
    }

    private boolean owned(Tagged resource, String region) {
        return account().equals(resource.accountId) && region.equals(resource.region);
    }

    private String destinationKey(String region, String name) {
        return account() + ":" + region + ":destination:" + name;
    }

    private String profileKey(String region, String id) {
        return account() + ":" + region + ":" + id;
    }

    private String deviceKey(String region, String id) {
        return account() + ":" + region + ":device:" + id;
    }

    private String gatewayKey(String region, String id) {
        return account() + ":" + region + ":gateway:" + id;
    }

    private String account() {
        return regionResolver.getAccountId();
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account(), resource).toString();
    }

    private ObjectNode idArn(Tagged resource) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", resource.arn);
        response.put("Id", resource.id);
        return response;
    }

    private String optionalName(JsonNode request, String fallback) {
        String name = textOrNull(request, "Name");
        return name == null ? fallback : name;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private JsonNode copyObject(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return node.deepCopy();
    }

    private Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode node = request.get("Tags");
        if (node == null || node.isNull()) {
            return tags;
        }
        if (node.isArray()) {
            for (JsonNode entry : node) {
                String key = textOrNull(entry, "Key");
                String value = textOrNull(entry, "Value");
                if (key != null && value != null) {
                    tags.put(key, value);
                }
            }
        } else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                tags.put(field.getKey(), field.getValue().asText());
            }
        }
        return tags;
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        if (source.has(field)) {
            target.set(field, source.get(field).deepCopy());
        }
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private static void putOptional(ObjectNode parent, String field, String value) {
        if (value != null) {
            parent.put(field, value);
        }
    }

    private static AwsException notFound(String id, String type) {
        return new AwsException("ResourceNotFoundException", type + " " + id + " not found.", 404);
    }

    private static AwsException conflict(String id, String type) {
        return new AwsException("ConflictException", type + " " + id + " already exists.", 409);
    }

    static class Tagged {
        String accountId;
        String region;
        String id;
        String name;
        String arn;
        String geoJson;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    static final class Destination extends Tagged {
        String expressionType;
        String expression;
        String description;
        String roleArn;
    }

    static final class Profile extends Tagged {
        JsonNode loRaWAN;
        JsonNode sidewalk;
    }

    static final class Device extends Tagged {
        String type;
        String destinationName;
        String description;
        String positioning;
        String lastUplinkReceivedAt;
        JsonNode loRaWAN;
        JsonNode sidewalk;
        final List<QueuedMessage> queue = new ArrayList<>();
    }

    static final class Gateway extends Tagged {
        String description;
        JsonNode loRaWAN;
    }

    static final class QueuedMessage {
        String messageId;
        int transmitMode;
        String payloadData;
        String receivedAt;
        JsonNode loRaWAN;
    }
}
