package io.github.hectorvent.floci.services.iotmanagedintegrations;

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
import io.github.hectorvent.floci.services.iotmanagedintegrations.model.CloudConnector;
import io.github.hectorvent.floci.services.iotmanagedintegrations.model.CredentialLocker;
import io.github.hectorvent.floci.services.iotmanagedintegrations.model.Destination;
import io.github.hectorvent.floci.services.iotmanagedintegrations.model.DeviceDiscovery;
import io.github.hectorvent.floci.services.iotmanagedintegrations.model.ManagedThing;
import io.github.hectorvent.floci.services.iotmanagedintegrations.model.NotificationConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS IoT Managed Integrations restJson1.
 *
 * <p>Tag APIs share {@code /tags/{arn}} via {@link TagHandler} using ARN service
 * {@code iotmanagedintegrations}.
 */
@ApplicationScoped
public class IotManagedIntegrationsService implements TagHandler {

    static final String SERVICE = "iotmanagedintegrations";
    private static final Pattern NAME_PATTERN = Pattern.compile("[\\p{L}\\p{N}._-]{1,128}");
    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile("[0-9A-Za-z_\\- ]{1,256}");
    private static final Pattern CLIENT_TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9=_-]{1,64}");
    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Set<String> EVENT_TYPES = Set.of(
            "DEVICE_COMMAND",
            "DEVICE_COMMAND_REQUEST",
            "DEVICE_DISCOVERY_STATUS",
            "DEVICE_EVENT",
            "DEVICE_LIFE_CYCLE",
            "DEVICE_STATE",
            "DEVICE_OTA",
            "DEVICE_WSS",
            "CONNECTOR_ASSOCIATION",
            "ACCOUNT_ASSOCIATION",
            "CONNECTOR_ERROR_REPORT");

    private final StorageBackend<String, Destination> destinations;
    private final StorageBackend<String, NotificationConfiguration> notifications;
    private final StorageBackend<String, CredentialLocker> lockers;
    private final StorageBackend<String, ManagedThing> things;
    private final StorageBackend<String, CloudConnector> connectors;
    private final StorageBackend<String, DeviceDiscovery> discoveries;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public IotManagedIntegrationsService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create(SERVICE, "iotmanagedintegrations-destinations.json",
                        new TypeReference<Map<String, Destination>>() {
                        }),
                storageFactory.create(SERVICE, "iotmanagedintegrations-notification-configurations.json",
                        new TypeReference<Map<String, NotificationConfiguration>>() {
                        }),
                storageFactory.create(SERVICE, "iotmanagedintegrations-credential-lockers.json",
                        new TypeReference<Map<String, CredentialLocker>>() {
                        }),
                storageFactory.create(SERVICE, "iotmanagedintegrations-managed-things.json",
                        new TypeReference<Map<String, ManagedThing>>() {
                        }),
                storageFactory.create(SERVICE, "iotmanagedintegrations-cloud-connectors.json",
                        new TypeReference<Map<String, CloudConnector>>() {
                        }),
                storageFactory.create(SERVICE, "iotmanagedintegrations-device-discoveries.json",
                        new TypeReference<Map<String, DeviceDiscovery>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    IotManagedIntegrationsService(
            StorageBackend<String, Destination> destinations,
            StorageBackend<String, NotificationConfiguration> notifications,
            StorageBackend<String, CredentialLocker> lockers,
            StorageBackend<String, ManagedThing> things,
            StorageBackend<String, CloudConnector> connectors,
            StorageBackend<String, DeviceDiscovery> discoveries,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.destinations = destinations;
        this.notifications = notifications;
        this.lockers = lockers;
        this.things = things;
        this.connectors = connectors;
        this.discoveries = discoveries;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized Destination createDestination(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateName(name);
        String deliveryArn = requireText(request, "DeliveryDestinationArn");
        String type = optionalText(request, "DeliveryDestinationType");
        if (type == null) {
            type = "KINESIS";
        }
        validateType(type);
        String roleArn = requireText(request, "RoleArn");
        String description = optionalText(request, "Description");
        if (description != null) {
            validateDescription(description);
        }
        String clientToken = optionalText(request, "ClientToken");
        if (clientToken != null) {
            validateClientToken(clientToken);
        }

        String key = destinationKey(region, name);
        Destination existing = destinations.get(key).orElse(null);
        if (existing != null) {
            if (clientToken != null && clientToken.equals(existing.getClientToken())
                    && Objects.equals(deliveryArn, existing.getDeliveryDestinationArn())
                    && Objects.equals(type, existing.getDeliveryDestinationType())
                    && Objects.equals(roleArn, existing.getRoleArn())
                    && Objects.equals(description, existing.getDescription())) {
                return existing;
            }
            throw conflict("Destination " + name + " already exists.");
        }

        long now = Instant.now().getEpochSecond();
        Destination destination = new Destination();
        destination.setName(name);
        destination.setDeliveryDestinationArn(deliveryArn);
        destination.setDeliveryDestinationType(type);
        destination.setRoleArn(roleArn);
        destination.setDescription(description);
        destination.setClientToken(clientToken);
        destination.setCreatedAt(now);
        destination.setUpdatedAt(now);
        destination.setTags(readTags(request));
        destinations.put(key, destination);
        return destination;
    }

    public Destination getDestination(String region, String name) {
        return requireDestination(region, name);
    }

    public synchronized Destination updateDestination(String region, String name, JsonNode request) {
        requireObject(request, "Request body");
        Destination destination = requireDestination(region, name);
        boolean changed = false;
        if (request.has("DeliveryDestinationArn") && !request.get("DeliveryDestinationArn").isNull()) {
            destination.setDeliveryDestinationArn(requireText(request, "DeliveryDestinationArn"));
            changed = true;
        }
        if (request.has("DeliveryDestinationType") && !request.get("DeliveryDestinationType").isNull()) {
            String type = requireText(request, "DeliveryDestinationType");
            validateType(type);
            destination.setDeliveryDestinationType(type);
            changed = true;
        }
        if (request.has("RoleArn") && !request.get("RoleArn").isNull()) {
            destination.setRoleArn(requireText(request, "RoleArn"));
            changed = true;
        }
        if (request.has("Description")) {
            if (request.get("Description").isNull()) {
                destination.setDescription(null);
            } else {
                String description = requireText(request, "Description");
                validateDescription(description);
                destination.setDescription(description);
            }
            changed = true;
        }
        if (changed) {
            destination.setUpdatedAt(Instant.now().getEpochSecond());
            destinations.put(destinationKey(region, destination.getName()), destination);
        }
        return destination;
    }

    public synchronized void deleteDestination(String region, String name) {
        Destination destination = requireDestination(region, name);
        destinations.delete(destinationKey(region, destination.getName()));
    }

    public List<Destination> listDestinations(String region) {
        return listDestinations(region, null, null).items();
    }

    public Page<Destination> listDestinations(String region, String maxResultsValue, String nextToken) {
        List<Destination> items = destinations.scan(key -> key.startsWith(destinationPrefix(region)));
        items.sort(Comparator.comparing(Destination::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResultsValue, nextToken);
    }

    public ObjectNode toDestinationSummary(Destination destination) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", destination.getName());
        node.put("DeliveryDestinationArn", destination.getDeliveryDestinationArn());
        node.put("DeliveryDestinationType", destination.getDeliveryDestinationType());
        node.put("RoleArn", destination.getRoleArn());
        if (destination.getDescription() != null) {
            node.put("Description", destination.getDescription());
        }
        return node;
    }

    public ObjectNode toDestination(Destination destination) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", destination.getName());
        node.put("DeliveryDestinationArn", destination.getDeliveryDestinationArn());
        node.put("DeliveryDestinationType", destination.getDeliveryDestinationType());
        node.put("RoleArn", destination.getRoleArn());
        if (destination.getDescription() != null) {
            node.put("Description", destination.getDescription());
        }
        node.put("CreatedAt", destination.getCreatedAt());
        node.put("UpdatedAt", destination.getUpdatedAt());
        putTags(node, destination.getTags());
        return node;
    }

    public synchronized NotificationConfiguration createNotificationConfiguration(String region, JsonNode request) {
        requireObject(request, "Request body");
        String eventType = requireEventType(optionalText(request, "EventType"));
        String destinationName = requireText(request, "DestinationName");
        validateName(destinationName);
        String clientToken = optionalText(request, "ClientToken");
        if (clientToken != null) {
            validateClientToken(clientToken);
        }

        String key = storageKey(region, eventType);
        NotificationConfiguration existing = notifications.get(key).orElse(null);
        if (existing != null) {
            if (clientToken != null
                    && Objects.equals(destinationName, existing.getDestinationName())) {
                return existing;
            }
            throw conflict("Notification configuration " + eventType + " already exists.");
        }

        long now = Instant.now().getEpochSecond();
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setEventType(eventType);
        configuration.setRegion(region);
        configuration.setDestinationName(destinationName);
        configuration.setTags(readTags(request));
        configuration.setCreatedAt(now);
        configuration.setUpdatedAt(now);
        notifications.put(key, configuration);
        return configuration;
    }

    public NotificationConfiguration getNotificationConfiguration(String region, String eventType) {
        return requireNotification(region, eventType);
    }

    public synchronized NotificationConfiguration updateNotificationConfiguration(
            String region, String eventType, JsonNode request) {
        requireObject(request, "Request body");
        NotificationConfiguration configuration = requireNotification(region, eventType);
        String destinationName = requireText(request, "DestinationName");
        validateName(destinationName);
        if (!destinationName.equals(configuration.getDestinationName())) {
            configuration.setDestinationName(destinationName);
            configuration.setUpdatedAt(Instant.now().getEpochSecond());
            notifications.put(storageKey(region, configuration.getEventType()), configuration);
        }
        return configuration;
    }

    public synchronized void deleteNotificationConfiguration(String region, String eventType) {
        NotificationConfiguration configuration = requireNotification(region, eventType);
        notifications.delete(storageKey(region, configuration.getEventType()));
    }

    public List<NotificationConfiguration> listNotificationConfigurations(String region) {
        List<NotificationConfiguration> items = notifications.scan(key -> key.startsWith(regionPrefix(region)));
        items.sort(Comparator.comparing(
                NotificationConfiguration::getEventType, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public ObjectNode toNotificationConfiguration(NotificationConfiguration configuration) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("EventType", configuration.getEventType());
        node.put("DestinationName", configuration.getDestinationName());
        node.put("CreatedAt", configuration.getCreatedAt());
        node.put("UpdatedAt", configuration.getUpdatedAt());
        putTags(node, configuration.getTags());
        return node;
    }

    public synchronized CredentialLocker createCredentialLocker(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = optionalText(request, "Name");
        if (name != null) {
            validateName(name);
        }
        String clientToken = optionalText(request, "ClientToken");
        if (clientToken != null) {
            validateClientToken(clientToken);
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        long now = Instant.now().getEpochSecond();
        CredentialLocker locker = new CredentialLocker();
        locker.setId(id);
        locker.setArn(arn(region, "credential-locker/" + id));
        locker.setName(name);
        locker.setClientToken(clientToken);
        locker.setCreatedAt(now);
        locker.setTags(readTags(request));
        lockers.put(storageKey(region, id), locker);
        return locker;
    }

    public CredentialLocker getCredentialLocker(String region, String identifier) {
        return requireLocker(region, identifier);
    }

    public synchronized void deleteCredentialLocker(String region, String identifier) {
        CredentialLocker locker = requireLocker(region, identifier);
        lockers.delete(storageKey(region, locker.getId()));
    }

    public List<CredentialLocker> listCredentialLockers(String region) {
        List<CredentialLocker> items = lockers.scan(key -> key.startsWith(regionPrefix(region)));
        items.sort(Comparator.comparing(CredentialLocker::getId, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public ObjectNode toCreateCredentialLocker(CredentialLocker locker) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", locker.getId());
        node.put("Arn", locker.getArn());
        node.put("CreatedAt", locker.getCreatedAt());
        return node;
    }

    public ObjectNode toCredentialLocker(CredentialLocker locker) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", locker.getId());
        node.put("Arn", locker.getArn());
        if (locker.getName() != null) {
            node.put("Name", locker.getName());
        }
        node.put("CreatedAt", locker.getCreatedAt());
        putTags(node, locker.getTags());
        return node;
    }

    public synchronized ManagedThing createManagedThing(String region, JsonNode request) {
        requireObject(request, "Request body");
        String role = requireText(request, "Role");
        requireText(request, "AuthenticationMaterial");
        requireText(request, "AuthenticationMaterialType");
        String id = UUID.randomUUID().toString().replace("-", "");
        long now = Instant.now().getEpochSecond();
        ManagedThing thing = new ManagedThing();
        thing.setId(id);
        thing.setArn(arn(region, "managed-thing/" + id));
        thing.setRegion(region);
        thing.setRole(role);
        thing.setName(optionalText(request, "Name"));
        thing.setOwner(optionalText(request, "Owner"));
        thing.setCredentialLockerId(optionalText(request, "CredentialLockerId"));
        thing.setSerialNumber(optionalText(request, "SerialNumber"));
        thing.setBrand(optionalText(request, "Brand"));
        thing.setModel(optionalText(request, "Model"));
        thing.setClassification(optionalText(request, "Classification"));
        thing.setCapabilities(optionalText(request, "Capabilities"));
        if (request.has("CapabilityReport") && request.get("CapabilityReport").isObject()) {
            thing.setCapabilityReport(request.get("CapabilityReport"));
        }
        thing.setMetaData(readStringMap(request, "MetaData"));
        thing.setTags(readTags(request));
        thing.setProvisioningStatus("UNASSOCIATED");
        thing.setCreatedAt(now);
        thing.setUpdatedAt(now);
        things.put(storageKey(region, id), thing);
        return thing;
    }

    public ManagedThing getManagedThing(String region, String identifier) {
        return requireThing(region, identifier);
    }

    public synchronized ManagedThing updateManagedThing(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        ManagedThing thing = requireThing(region, identifier);
        boolean changed = false;
        if (request.has("Name")) {
            thing.setName(optionalText(request, "Name"));
            changed = true;
        }
        if (request.has("Brand")) {
            thing.setBrand(optionalText(request, "Brand"));
            changed = true;
        }
        if (request.has("Model")) {
            thing.setModel(optionalText(request, "Model"));
            changed = true;
        }
        if (request.has("SerialNumber")) {
            thing.setSerialNumber(optionalText(request, "SerialNumber"));
            changed = true;
        }
        if (request.has("Classification")) {
            thing.setClassification(optionalText(request, "Classification"));
            changed = true;
        }
        if (request.has("Capabilities")) {
            thing.setCapabilities(optionalText(request, "Capabilities"));
            changed = true;
        }
        if (request.has("CapabilityReport") && request.get("CapabilityReport").isObject()) {
            thing.setCapabilityReport(request.get("CapabilityReport"));
            changed = true;
        }
        if (request.has("MetaData")) {
            thing.setMetaData(readStringMap(request, "MetaData"));
            changed = true;
        }
        if (changed) {
            thing.setUpdatedAt(Instant.now().getEpochSecond());
            things.put(storageKey(region, thing.getId()), thing);
        }
        return thing;
    }

    public synchronized void deleteManagedThing(String region, String identifier) {
        ManagedThing thing = requireThing(region, identifier);
        things.delete(storageKey(region, thing.getId()));
    }

    public List<ManagedThing> listManagedThings(String region) {
        List<ManagedThing> items = things.scan(key -> key.startsWith(regionPrefix(region)));
        items.sort(Comparator.comparing(ManagedThing::getId, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public ObjectNode toCreateManagedThing(ManagedThing thing) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", thing.getId());
        node.put("Arn", thing.getArn());
        node.put("CreatedAt", thing.getCreatedAt());
        return node;
    }

    public ObjectNode toManagedThing(ManagedThing thing) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", thing.getId());
        node.put("Arn", thing.getArn());
        putOptional(node, "Name", thing.getName());
        putOptional(node, "Role", thing.getRole());
        putOptional(node, "Owner", thing.getOwner());
        putOptional(node, "CredentialLockerId", thing.getCredentialLockerId());
        putOptional(node, "ProvisioningStatus", thing.getProvisioningStatus());
        putOptional(node, "SerialNumber", thing.getSerialNumber());
        putOptional(node, "Brand", thing.getBrand());
        putOptional(node, "Model", thing.getModel());
        putOptional(node, "Classification", thing.getClassification());
        putOptional(node, "Capabilities", thing.getCapabilities());
        node.put("CreatedAt", thing.getCreatedAt());
        node.put("UpdatedAt", thing.getUpdatedAt());
        if (thing.getMetaData() != null && !thing.getMetaData().isEmpty()) {
            ObjectNode meta = node.putObject("MetaData");
            thing.getMetaData().forEach(meta::put);
        }
        putTags(node, thing.getTags());
        return node;
    }

    public ObjectNode toManagedThingSummary(ManagedThing thing) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", thing.getId());
        node.put("Arn", thing.getArn());
        putOptional(node, "Name", thing.getName());
        putOptional(node, "Role", thing.getRole());
        putOptional(node, "ProvisioningStatus", thing.getProvisioningStatus());
        return node;
    }

    public ObjectNode getManagedThingState(String region, String managedThingId) {
        requireThing(region, managedThingId);
        ObjectNode node = objectMapper.createObjectNode();
        node.putArray("Endpoints");
        return node;
    }

    public ObjectNode getManagedThingCapabilities(String region, String identifier) {
        ManagedThing thing = requireThing(region, identifier);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ManagedThingId", thing.getId());
        if (thing.getCapabilities() != null) {
            node.put("Capabilities", thing.getCapabilities());
        }
        if (thing.getCapabilityReport() != null) {
            node.set("CapabilityReport", thing.getCapabilityReport());
        }
        return node;
    }

    public ObjectNode getManagedThingCertificate(String region, String identifier) {
        ManagedThing thing = requireThing(region, identifier);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ManagedThingId", thing.getId());
        node.put("CertificatePem", "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n");
        return node;
    }

    public ObjectNode getManagedThingConnectivityData(String region, String identifier) {
        ManagedThing thing = requireThing(region, identifier);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ManagedThingId", thing.getId());
        node.put("Connected", false);
        node.put("Timestamp", Instant.now().getEpochSecond());
        node.put("DisconnectReason", "NONE");
        return node;
    }

    public ObjectNode getManagedThingMetaData(String region, String identifier) {
        ManagedThing thing = requireThing(region, identifier);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ManagedThingId", thing.getId());
        ObjectNode meta = node.putObject("MetaData");
        if (thing.getMetaData() != null) {
            thing.getMetaData().forEach(meta::put);
        }
        return node;
    }

    public ObjectNode listManagedThingSchemas(String region, String identifier) {
        requireThing(region, identifier);
        ObjectNode node = objectMapper.createObjectNode();
        node.putArray("Items");
        return node;
    }

    public ObjectNode sendManagedThingCommand(String region, String managedThingId, JsonNode request) {
        requireObject(request, "Request body");
        requireThing(region, managedThingId);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("TraceId", UUID.randomUUID().toString());
        return node;
    }

    public synchronized CloudConnector createCloudConnector(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateName(name);
        if (!request.has("EndpointConfig") || !request.get("EndpointConfig").isObject()) {
            throw validation("EndpointConfig must be a JSON object.");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        CloudConnector connector = new CloudConnector();
        connector.setId(id);
        connector.setArn(arn(region, "cloud-connector/" + id));
        connector.setRegion(region);
        connector.setName(name);
        connector.setDescription(optionalText(request, "Description"));
        connector.setEndpointType(optionalText(request, "EndpointType"));
        connector.setEndpointConfig(request.get("EndpointConfig"));
        connector.setCreatedAt(Instant.now().getEpochSecond());
        connectors.put(storageKey(region, id), connector);
        return connector;
    }

    public CloudConnector getCloudConnector(String region, String identifier) {
        return requireConnector(region, identifier);
    }

    public ObjectNode toCreateCloudConnector(CloudConnector connector) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", connector.getId());
        return node;
    }

    public ObjectNode toCloudConnector(CloudConnector connector) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", connector.getId());
        node.put("Name", connector.getName());
        if (connector.getDescription() != null) {
            node.put("Description", connector.getDescription());
        }
        if (connector.getEndpointType() != null) {
            node.put("EndpointType", connector.getEndpointType());
        }
        if (connector.getEndpointConfig() != null) {
            node.set("EndpointConfig", connector.getEndpointConfig());
        }
        return node;
    }

    public ObjectNode sendConnectorEvent(String region, String connectorId, JsonNode request) {
        requireObject(request, "Request body");
        requireText(request, "Operation");
        requireConnector(region, connectorId);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ConnectorId", connectorId);
        return node;
    }

    public synchronized DeviceDiscovery startDeviceDiscovery(String region, JsonNode request) {
        requireObject(request, "Request body");
        String discoveryType = requireText(request, "DiscoveryType");
        String id = UUID.randomUUID().toString().replace("-", "");
        long now = Instant.now().getEpochSecond();
        DeviceDiscovery discovery = new DeviceDiscovery();
        discovery.setId(id);
        discovery.setArn(arn(region, "device-discovery/" + id));
        discovery.setRegion(region);
        discovery.setDiscoveryType(discoveryType);
        discovery.setStatus("SUCCEEDED");
        discovery.setControllerId(optionalText(request, "ControllerIdentifier"));
        discovery.setConnectorAssociationId(optionalText(request, "ConnectorAssociationIdentifier"));
        discovery.setAccountAssociationId(optionalText(request, "AccountAssociationId"));
        discovery.setTags(readTags(request));
        discovery.setStartedAt(now);
        discovery.setFinishedAt(now);
        discoveries.put(storageKey(region, id), discovery);
        return discovery;
    }

    public DeviceDiscovery getDeviceDiscovery(String region, String identifier) {
        return requireDiscovery(region, identifier);
    }

    public List<DeviceDiscovery> listDeviceDiscoveries(String region) {
        List<DeviceDiscovery> items = discoveries.scan(key -> key.startsWith(regionPrefix(region)));
        items.sort(Comparator.comparing(DeviceDiscovery::getId, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public ObjectNode toStartDeviceDiscovery(DeviceDiscovery discovery) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", discovery.getId());
        node.put("StartedAt", discovery.getStartedAt());
        return node;
    }

    public ObjectNode toDeviceDiscovery(DeviceDiscovery discovery) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", discovery.getId());
        node.put("Arn", discovery.getArn());
        node.put("DiscoveryType", discovery.getDiscoveryType());
        node.put("Status", discovery.getStatus());
        node.put("StartedAt", discovery.getStartedAt());
        if (discovery.getFinishedAt() != null) {
            node.put("FinishedAt", discovery.getFinishedAt());
        }
        return node;
    }

    public ObjectNode listDiscoveredDevices(String region, String identifier) {
        requireDiscovery(region, identifier);
        ObjectNode node = objectMapper.createObjectNode();
        node.putArray("Items");
        return node;
    }

    public ObjectNode getSchemaVersion(String type, String schemaVersionedId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Type", type);
        node.put("SchemaId", schemaVersionedId);
        node.put("SemanticVersion", "1.0.0");
        node.put("Visibility", "PUBLIC");
        node.putObject("Schema");
        return node;
    }

    public ObjectNode listSchemaVersions(String type, String maxResults) {
        if (type == null || type.isBlank()) {
            throw validation("Type is required.");
        }
        if (!"capability".equals(type) && !"definition".equals(type)) {
            throw validation("Type must be capability or definition.");
        }
        int limit = parseMaxResults(maxResults);
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode items = node.putArray("Items");
        List<ObjectNode> catalog = schemaCatalog(type);
        for (int i = 0; i < Math.min(limit, catalog.size()); i++) {
            items.add(catalog.get(i));
        }
        return node;
    }

    private List<ObjectNode> schemaCatalog(String type) {
        List<ObjectNode> items = new ArrayList<>();
        if ("capability".equals(type)) {
            items.add(schemaItem("aws.iot.OnOff", type, "On/Off capability"));
            items.add(schemaItem("aws.iot.ColorControl", type, "Color control capability"));
            items.add(schemaItem("aws.iot.LevelControl", type, "Level control capability"));
            items.add(schemaItem("aws.iot.TemperatureMeasurement", type, "Temperature measurement capability"));
        } else if ("definition".equals(type)) {
            items.add(schemaItem("aws.iot.MatterCluster", type, "Matter cluster definition"));
        }
        return items;
    }

    private ObjectNode schemaItem(String schemaId, String type, String description) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("SchemaId", schemaId);
        item.put("Type", type);
        item.put("Description", description);
        item.put("Namespace", "aws");
        item.put("SemanticVersion", "1.0.0");
        item.put("Visibility", "PUBLIC");
        return item;
    }

    public ObjectNode getCustomEndpoint(String region) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("EndpointAddress", "api.iotmanagedintegrations." + region + ".amazonaws.com");
        return node;
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
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(tagsOf(region, arn));
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        tagged.updateTags(current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        tagged.updateTags(current);
    }

    String destinationArn(String region, String name) {
        return arn(region, "destination/" + name);
    }

    String notificationConfigurationArn(String region, String eventType) {
        return arn(region, "notification-configuration/" + eventType);
    }

    private Map<String, String> tagsOf(String region, String arn) {
        return requireTagged(region, arn).tags();
    }

    private Tagged requireTagged(String region, String arn) {
        String decoded = decode(arn);
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw notFound(decoded);
        }
        if (!SERVICE.equals(parsed.service())) {
            throw notFound(decoded);
        }
        String resource = parsed.resource();
        String lookupRegion = parsed.region() == null || parsed.region().isEmpty() ? region : parsed.region();
        if (resource != null && resource.startsWith("destination/")) {
            Destination destination = requireDestination(lookupRegion, resource.substring("destination/".length()));
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return destination.getTags();
                }

                @Override
                public void updateTags(Map<String, String> tags) {
                    destination.setTags(tags);
                    destination.setUpdatedAt(Instant.now().getEpochSecond());
                    destinations.put(destinationKey(lookupRegion, destination.getName()), destination);
                }
            };
        }
        if (resource != null && resource.startsWith("notification-configuration/")) {
            NotificationConfiguration configuration = requireNotification(
                    lookupRegion, resource.substring("notification-configuration/".length()));
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return configuration.getTags();
                }

                @Override
                public void updateTags(Map<String, String> tags) {
                    configuration.setTags(tags);
                    configuration.setUpdatedAt(Instant.now().getEpochSecond());
                    notifications.put(storageKey(lookupRegion, configuration.getEventType()), configuration);
                }
            };
        }
        if (resource != null && resource.startsWith("credential-locker/")) {
            CredentialLocker locker = requireLocker(lookupRegion, resource.substring("credential-locker/".length()));
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return locker.getTags();
                }

                @Override
                public void updateTags(Map<String, String> tags) {
                    locker.setTags(tags);
                    lockers.put(storageKey(lookupRegion, locker.getId()), locker);
                }
            };
        }
        if (resource != null && resource.startsWith("managed-thing/")) {
            ManagedThing thing = requireThing(lookupRegion, resource.substring("managed-thing/".length()));
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return thing.getTags();
                }

                @Override
                public void updateTags(Map<String, String> tags) {
                    thing.setTags(tags);
                    thing.setUpdatedAt(Instant.now().getEpochSecond());
                    things.put(storageKey(lookupRegion, thing.getId()), thing);
                }
            };
        }
        throw notFound(decoded);
    }

    private Destination requireDestination(String region, String name) {
        String decoded = decode(name);
        validateName(decoded);
        return destinations.get(destinationKey(region, decoded)).orElseThrow(() -> notFound(decoded));
    }

    private NotificationConfiguration requireNotification(String region, String eventType) {
        String decoded = requireEventType(decode(eventType));
        return notifications.get(storageKey(region, decoded)).orElseThrow(() -> notFound(decoded));
    }

    private CredentialLocker requireLocker(String region, String identifier) {
        String decoded = decode(identifier);
        if (decoded == null || decoded.isBlank()) {
            throw validation("Identifier is required.");
        }
        return lockers.get(storageKey(region, decoded)).orElseThrow(() -> notFound(decoded));
    }

    private ManagedThing requireThing(String region, String identifier) {
        String decoded = decode(identifier);
        if (decoded == null || decoded.isBlank()) {
            throw validation("Identifier is required.");
        }
        return things.get(storageKey(region, decoded)).orElseThrow(() -> notFound(decoded));
    }

    private CloudConnector requireConnector(String region, String identifier) {
        String decoded = decode(identifier);
        if (decoded == null || decoded.isBlank()) {
            throw validation("Identifier is required.");
        }
        return connectors.get(storageKey(region, decoded)).orElseThrow(() -> notFound(decoded));
    }

    private DeviceDiscovery requireDiscovery(String region, String identifier) {
        String decoded = decode(identifier);
        if (decoded == null || decoded.isBlank()) {
            throw validation("Identifier is required.");
        }
        return discoveries.get(storageKey(region, decoded)).orElseThrow(() -> notFound(decoded));
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), resource).toString();
    }

    private String destinationKey(String region, String name) {
        return regionResolver.getAccountId() + "::" + storageKey(region, name);
    }

    private String destinationPrefix(String region) {
        return regionResolver.getAccountId() + "::" + regionPrefix(region);
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String regionPrefix(String region) {
        return region + "::";
    }

    private static String requireEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw validation("EventType is required.");
        }
        if (!EVENT_TYPE_PATTERN.matcher(eventType).matches()) {
            throw validation("EventType is invalid.");
        }
        if (!EVENT_TYPES.contains(eventType)) {
            throw validation("EventType is invalid.");
        }
        return eventType;
    }

    private static void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("Name must match [\\p{L}\\p{N}._-]{1,128}.");
        }
    }

    private static void validateDescription(String description) {
        if (!DESCRIPTION_PATTERN.matcher(description).matches()) {
            throw validation("Description must match [0-9A-Za-z_\\- ]{1,256}.");
        }
    }

    private static void validateClientToken(String clientToken) {
        if (!CLIENT_TOKEN_PATTERN.matcher(clientToken).matches()) {
            throw validation("ClientToken must match [a-zA-Z0-9=_-]{1,64}.");
        }
    }

    private static void validateType(String type) {
        if (!"KINESIS".equals(type)) {
            throw validation("DeliveryDestinationType must be KINESIS.");
        }
    }

    private void putTags(ObjectNode node, Map<String, String> tags) {
        ObjectNode tagsNode = node.putObject("Tags");
        if (tags != null) {
            tags.forEach(tagsNode::put);
        }
    }

    private static void putOptional(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static Map<String, String> readTags(JsonNode request) {
        return readStringMap(request, "Tags");
    }

    private static Map<String, String> readStringMap(JsonNode request, String field) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return new LinkedHashMap<>();
        }
        JsonNode tagsNode = request.get(field);
        if (!tagsNode.isObject()) {
            throw validation(field + " must be an object.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && !value.isNull()) {
                tags.put(entry.getKey(), value.asText());
            }
        });
        return tags;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException notFound(String name) {
        return new AwsException("ResourceNotFoundException", name + " not found.", 404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static <T> Page<T> page(List<T> items, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(new ArrayList<>(items.subList(offset, end)), responseToken);
    }

    private static int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return 50;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 50) {
                throw validation("MaxResults must be between 1 and 50.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("MaxResults must be an integer between 1 and 50.");
        }
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(new String(
                    java.util.Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            if (offset < 0 || offset > resultSize) {
                throw validation("NextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("NextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private interface Tagged {
        Map<String, String> tags();

        void updateTags(Map<String, String> tags);
    }
}
