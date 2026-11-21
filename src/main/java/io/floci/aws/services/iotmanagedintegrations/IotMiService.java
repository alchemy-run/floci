package io.floci.aws.services.iotmanagedintegrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.iot.IotService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.EVENT_LOG;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.LOCKER;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.PROFILE;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.SETTINGS;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.THING;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.meta;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.view;

/**
 * Credential lockers, provisioning profiles and managed things (devices and hubs) with their capabilities, state,
 * metadata, certificates and log settings; and the account's hub, encryption and custom endpoint settings and tags.
 * A managed thing's onboarding material is checked against its type (a Zigbee install code, a Z-Wave SmartStart
 * code, a Matter setup payload), and its capabilities against the schema catalog.
 */
@ApplicationScoped
public class IotMiService {

    private static final Pattern ZIGBEE = Pattern.compile("Z:[0-9A-Fa-f]{16}\\$I:[0-9A-Fa-f]{12,36}(\\$.*)?");
    private static final Pattern ZWAVE = Pattern.compile("90\\d{88,}");
    private static final Pattern MATTER = Pattern.compile("MT:[0-9A-Z.\\-]{19,}");
    private static final Set<String> TAGGABLE = Set.of(THING, PROFILE, IotMiStore.OTA_TASK, LOCKER,
            IotMiStore.ASSOCIATION);
    private static final List<String> THING_FIELDS = List.of("Owner", "CredentialLockerId", "SerialNumber", "Brand",
            "Model", "Name", "Classification", "HubNetworkMode", "WiFiSimpleSetupConfiguration", "MetaData");

    private final IotMiStore store;
    private final IotMiSchemas schemas;
    private final IotMiNotifications notifications;
    private final Instance<FlociCertificateAuthority> authority;
    private final Instance<IotService> iot;

    @Inject
    public IotMiService(IotMiStore store, IotMiSchemas schemas, IotMiNotifications notifications,
                        Instance<FlociCertificateAuthority> authority, Instance<IotService> iot) {
        this.store = store;
        this.schemas = schemas;
        this.notifications = notifications;
        this.authority = authority;
        this.iot = iot;
    }

    // ---------------------------------------------------------------- credential lockers

    public ObjectNode createCredentialLocker(JsonNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateCredentialLocker", in);
        if (repeated != null) {
            return repeated;
        }
        if (store.list(account, region, LOCKER).size() >= store.config().maxCredentialLockers()) {
            throw IotMiStore.quota("The account has the most credential lockers it can have.");
        }
        String id = IotMiStore.id();
        ObjectNode record = store.record();
        record.put("Id", id).put("Arn", store.arn(account, region, LOCKER, id))
                .put("Name", in.path("Name").asText(id)).put("CreatedAt", IotMiStore.now());
        record.set("Tags", IotMiStore.tagsOf(store.mapper(), in.path("Tags")));
        store.put(account, region, LOCKER, id, record);
        ObjectNode out = store.mapper().createObjectNode().put("Id", id).put("Arn", record.path("Arn").asText())
                .put("CreatedAt", record.path("CreatedAt").asDouble());
        store.remember(account, region, "CreateCredentialLocker", in, out);
        return out;
    }

    public ObjectNode getCredentialLocker(String id, String region) {
        return view(store.require(store.account(), region, LOCKER, id, "credential locker"));
    }

    public ObjectNode deleteCredentialLocker(String id, String region) {
        String account = store.account();
        store.require(account, region, LOCKER, id, "credential locker");
        for (ObjectNode thing : store.list(account, region, THING)) {
            if (id.equals(thing.path("CredentialLockerId").asText()) && !deleted(thing)) {
                throw IotMiStore.conflict("The credential locker " + id + " holds managed thing "
                        + thing.path("Id").asText() + ".");
            }
        }
        store.delete(account, region, LOCKER, id);
        return store.mapper().createObjectNode();
    }

    public ObjectNode listCredentialLockers(String token, Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, LOCKER)) {
            ObjectNode summary = view(record);
            summary.remove("Tags");
            items.add(summary);
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "Items");
        return out;
    }

    // ---------------------------------------------------------------- provisioning profiles

    public ObjectNode createProvisioningProfile(JsonNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateProvisioningProfile", in);
        if (repeated != null) {
            return repeated;
        }
        String type = in.path("ProvisioningType").asText();
        String name = in.path("Name").asText(null);
        if (name != null) {
            for (ObjectNode other : store.list(account, region, PROFILE)) {
                if (name.equals(other.path("Name").asText())) {
                    throw IotMiStore.conflict("The provisioning profile " + name + " already exists.");
                }
            }
        }
        String id = IotMiStore.id();
        ObjectNode record = store.record();
        record.put("Id", id).put("Arn", store.arn(account, region, PROFILE, id)).put("ProvisioningType", type)
                .put("Status", "CREATED");
        if (name != null) {
            record.put("Name", name);
        }
        ObjectNode out;
        if ("FLEET_PROVISIONING".equals(type)) {
            // A fleet-provisioning profile gets a claim certificate, issued by Floci's CA, that devices present to
            // obtain their own certificate.
            CertificateGenerator.GeneratedCertificate claim = authority.get().issueClientCertificate(
                    "managed-integrations-claim-" + id);
            record.put("ClaimCertificate", claim.certificatePem());
            out = view(record).put("ClaimCertificatePrivateKey", claim.privateKeyPem());
        } else {
            String ca = in.path("CaCertificate").asText(null);
            if (ca == null || !ca.contains("BEGIN CERTIFICATE")) {
                throw IotMiStore.validation("A JITR provisioning profile needs the CA certificate that signs the "
                        + "devices' certificates.");
            }
            meta(record).put("caCertificate", ca);
            out = view(record);
        }
        record.set("Tags", IotMiStore.tagsOf(store.mapper(), in.path("Tags")));
        store.put(account, region, PROFILE, id, record);
        out.remove("Tags");
        store.remember(account, region, "CreateProvisioningProfile", in, out);
        return out;
    }

    public ObjectNode getProvisioningProfile(String identifier, String region) {
        return view(profile(store.account(), region, identifier));
    }

    public ObjectNode deleteProvisioningProfile(String identifier, String region) {
        String account = store.account();
        ObjectNode record = profile(account, region, identifier);
        store.delete(account, region, PROFILE, record.path("Id").asText());
        return store.mapper().createObjectNode();
    }

    public ObjectNode listProvisioningProfiles(String token, Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, PROFILE)) {
            ObjectNode summary = store.mapper().createObjectNode().put("Id", record.path("Id").asText())
                    .put("Arn", record.path("Arn").asText())
                    .put("ProvisioningType", record.path("ProvisioningType").asText())
                    .put("Status", record.path("Status").asText());
            if (record.has("Name")) {
                summary.put("Name", record.path("Name").asText());
            }
            items.add(summary);
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "Items");
        return out;
    }

    /** A provisioning profile by ID or name. */
    private ObjectNode profile(String account, String region, String identifier) {
        return store.get(account, region, PROFILE, identifier).orElseGet(() -> store.list(account, region, PROFILE)
                .stream().filter(record -> identifier.equals(record.path("Name").asText())).findFirst()
                .orElseThrow(() -> IotMiStore.notFound("provisioning profile", identifier)));
    }

    // ---------------------------------------------------------------- managed things

    public ObjectNode createManagedThing(JsonNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateManagedThing", in);
        if (repeated != null) {
            return repeated;
        }
        ObjectNode thing = newThing(account, region, in, null);
        ObjectNode out = store.mapper().createObjectNode().put("Id", thing.path("Id").asText())
                .put("Arn", thing.path("Arn").asText()).put("CreatedAt", thing.path("CreatedAt").asDouble());
        store.remember(account, region, "CreateManagedThing", in, out);
        return out;
    }

    /**
     * Creates a managed thing. {@code connector} carries what a cloud connector's discovery knows of the device (its
     * connector device ID, destination and capability report), for devices created from a discovery.
     */
    ObjectNode newThing(String account, String region, JsonNode in, ObjectNode connector) {
        String role = in.path("Role").asText();
        String type = in.path("AuthenticationMaterialType").asText();
        String material = in.path("AuthenticationMaterial").asText();
        checkMaterial(type, material, role);
        for (ObjectNode other : store.list(account, region, THING)) {
            if (!deleted(other) && material.equals(meta(other).path("authenticationMaterial").asText())
                    && type.equals(meta(other).path("authenticationMaterialType").asText())) {
                throw IotMiStore.conflict("A managed thing with this authentication material already exists: "
                        + other.path("Id").asText() + ".");
            }
        }
        if (in.hasNonNull("CredentialLockerId")) {
            store.require(account, region, LOCKER, in.path("CredentialLockerId").asText(), "credential locker");
        }
        if (store.list(account, region, THING).size() >= store.config().maxManagedThings()) {
            throw IotMiStore.quota("The account has the most managed things it can have.");
        }
        checkCapabilities(in.path("CapabilityReport"), in.path("CapabilitySchemas"));
        String id = IotMiStore.id();
        double now = IotMiStore.now();
        ObjectNode record = store.record();
        record.put("Id", id).put("Arn", store.arn(account, region, THING, id)).put("Role", role)
                .put("CreatedAt", now).put("UpdatedAt", now);
        for (String field : THING_FIELDS) {
            if (in.hasNonNull(field)) {
                record.set(field, in.get(field).deepCopy());
            }
        }
        if (!record.has("Name")) {
            record.put("Name", id);
        }
        // A device or hub onboarded with a code waits, UNASSOCIATED, for a hub's discovery (or its own first
        // connection) to find it; a device a cloud connector or a hub already discovered is ACTIVATED.
        boolean discovered = "DISCOVERED_DEVICE".equals(type) || "PRE_ONBOARDED_CLOUD".equals(type)
                || connector != null;
        record.put("ProvisioningStatus", discovered ? "ACTIVATED" : "UNASSOCIATED");
        if (discovered) {
            record.put("ActivatedAt", now);
        }
        if (connector != null) {
            connector.fields().forEachRemaining(field -> record.set(field.getKey(), field.getValue()));
        }
        ObjectNode meta = meta(record);
        meta.put("authenticationMaterial", material).put("authenticationMaterialType", type);
        for (String field : List.of("CapabilityReport", "CapabilitySchemas", "Capabilities")) {
            if (in.hasNonNull(field)) {
                meta.set(field, in.get(field).deepCopy());
            }
        }
        if ("CONTROLLER".equals(role)) {
            // A hub connects to managed integrations itself, with a device certificate from Floci's CA.
            CertificateGenerator.GeneratedCertificate certificate = authority.get().issueClientCertificate(
                    "managed-thing-" + id);
            meta.put("certificatePem", certificate.certificatePem());
        }
        record.set("Tags", IotMiStore.tagsOf(store.mapper(), in.path("Tags")));
        store.put(account, region, THING, id, record);
        notifications.lifecycle(account, region, record);
        return record;
    }

    public ObjectNode getManagedThing(String id, String region) {
        ObjectNode view = view(thing(store.account(), region, id));
        return view;
    }

    public ObjectNode updateManagedThing(String id, JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = thing(account, region, id);
        requireLive(record);
        if (in.hasNonNull("CredentialLockerId")) {
            store.require(account, region, LOCKER, in.path("CredentialLockerId").asText(), "credential locker");
        }
        checkCapabilities(in.path("CapabilityReport"), in.path("CapabilitySchemas"));
        for (String field : THING_FIELDS) {
            if (in.hasNonNull(field)) {
                record.set(field, in.get(field).deepCopy());
            }
        }
        for (String field : List.of("CapabilityReport", "CapabilitySchemas", "Capabilities")) {
            if (in.hasNonNull(field)) {
                meta(record).set(field, in.get(field).deepCopy());
            }
        }
        record.put("UpdatedAt", IotMiStore.now());
        store.put(account, region, THING, id, record);
        return store.mapper().createObjectNode();
    }

    /**
     * Deletes a managed thing: it is DELETE_IN_PROGRESS, then gone. A hub with devices behind it needs
     * {@code Force}, which deletes them too.
     */
    public ObjectNode deleteManagedThing(String id, boolean force, String region) {
        String account = store.account();
        ObjectNode record = thing(account, region, id);
        if (deleted(record)) {
            return store.mapper().createObjectNode();
        }
        List<ObjectNode> children = store.list(account, region, THING).stream()
                .filter(other -> id.equals(other.path("ParentControllerId").asText()) && !deleted(other)).toList();
        if (!children.isEmpty() && !force) {
            throw IotMiStore.conflict("The hub " + id + " has devices; delete them first or use Force.");
        }
        for (ObjectNode child : children) {
            markDeleted(account, region, child);
        }
        markDeleted(account, region, record);
        return store.mapper().createObjectNode();
    }

    private void markDeleted(String account, String region, ObjectNode record) {
        record.put("ProvisioningStatus", "DELETE_IN_PROGRESS").put("UpdatedAt", IotMiStore.now());
        meta(record).put("deleteAt", Instant.now().plusMillis(store.config().deletionMillis()).toString());
        store.put(account, region, THING, record.path("Id").asText(), record);
        notifications.lifecycle(account, region, record);
    }

    public ObjectNode listManagedThings(Map<String, String> filters, String token, Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, THING)) {
            ObjectNode thing = settle(store.account(), region, record);
            if (thing == null || !matches(thing, filters)) {
                continue;
            }
            ObjectNode summary = store.mapper().createObjectNode();
            for (String field : List.of("Id", "Arn", "AdvertisedProductId", "Brand", "Classification",
                    "ConnectorDeviceId", "ConnectorDestinationId", "Model", "Name", "Owner", "CredentialLockerId",
                    "ParentControllerId", "ProvisioningStatus", "Role", "SerialNumber", "CreatedAt", "UpdatedAt",
                    "ActivatedAt")) {
                if (thing.has(field)) {
                    summary.set(field, thing.get(field));
                }
            }
            items.add(summary);
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "Items");
        return out;
    }

    private static boolean matches(ObjectNode thing, Map<String, String> filters) {
        Map<String, String> fields = Map.of("OwnerFilter", "Owner", "CredentialLockerFilter", "CredentialLockerId",
                "RoleFilter", "Role", "ParentControllerIdentifierFilter", "ParentControllerId",
                "ConnectorDestinationIdFilter", "ConnectorDestinationId", "ConnectorDeviceIdFilter",
                "ConnectorDeviceId", "SerialNumberFilter", "SerialNumber", "ProvisioningStatusFilter",
                "ProvisioningStatus");
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            String field = fields.get(filter.getKey());
            if (field != null && filter.getValue() != null
                    && !filter.getValue().equals(thing.path(field).asText(null))) {
                return false;
            }
        }
        return true;
    }

    public ObjectNode getManagedThingCapabilities(String id, String region) {
        ObjectNode record = thing(store.account(), region, id);
        ObjectNode out = store.mapper().createObjectNode().put("ManagedThingId", record.path("Id").asText());
        if (meta(record).has("Capabilities")) {
            out.set("Capabilities", meta(record).get("Capabilities"));
        }
        if (meta(record).has("CapabilityReport")) {
            out.set("CapabilityReport", meta(record).get("CapabilityReport"));
        }
        return out;
    }

    public ObjectNode getManagedThingMetaData(String id, String region) {
        ObjectNode record = thing(store.account(), region, id);
        ObjectNode out = store.mapper().createObjectNode().put("ManagedThingId", record.path("Id").asText());
        if (record.has("MetaData")) {
            out.set("MetaData", record.get("MetaData"));
        }
        return out;
    }

    public ObjectNode getManagedThingCertificate(String id, String region) {
        ObjectNode record = thing(store.account(), region, id);
        String pem = meta(record).path("certificatePem").asText(null);
        if (pem == null) {
            throw IotMiStore.notFound("managed thing certificate", id);
        }
        return store.mapper().createObjectNode().put("ManagedThingId", id).put("CertificatePem", pem);
    }

    /** Whether the thing is reachable: through its cloud connector, or never yet for a device behind a hub. */
    public ObjectNode getManagedThingConnectivityData(String id, String region) {
        ObjectNode record = thing(store.account(), region, id);
        JsonNode connectivity = meta(record).path("connectivity");
        boolean connected = connectivity.path("connected").asBoolean(false);
        ObjectNode out = store.mapper().createObjectNode().put("ManagedThingId", id).put("Connected", connected)
                .put("DisconnectReason", connectivity.path("disconnectReason").asText(connected ? "NONE" : "NONE"));
        if (connectivity.has("timestamp")) {
            out.put("Timestamp", connectivity.path("timestamp").asDouble());
        }
        return out;
    }

    public ObjectNode getManagedThingState(String id, String region) {
        ObjectNode record = thing(store.account(), region, id);
        ObjectNode out = store.mapper().createObjectNode();
        out.set("Endpoints", meta(record).has("state") ? meta(record).get("state").deepCopy()
                : store.mapper().createArrayNode());
        return out;
    }

    /** The schema of each of the thing's capabilities, by endpoint: a catalog capability or one the thing brought. */
    public ObjectNode listManagedThingSchemas(String id, String endpointFilter, String capabilityFilter, String token,
                                              Integer max, String region) {
        ObjectNode record = thing(store.account(), region, id);
        List<JsonNode> items = new ArrayList<>();
        JsonNode custom = meta(record).path("CapabilitySchemas");
        for (JsonNode endpoint : meta(record).path("CapabilityReport").path("endpoints")) {
            String endpointId = endpoint.path("id").asText();
            if (endpointFilter != null && !endpointFilter.equals(endpointId)) {
                continue;
            }
            for (JsonNode capability : endpoint.path("capabilities")) {
                String capabilityId = capability.path("id").asText();
                if (capabilityFilter != null && !capabilityFilter.equals(capabilityId)) {
                    continue;
                }
                JsonNode schema = null;
                for (JsonNode item : custom) {
                    if (capabilityId.equals(item.path("CapabilityId").asText())) {
                        schema = item.path("Schema");
                    }
                }
                if (schema == null) {
                    schema = schemas.capability(capabilityId).orElse(null);
                }
                ObjectNode item = store.mapper().createObjectNode().put("EndpointId", endpointId)
                        .put("CapabilityId", capabilityId);
                if (schema != null) {
                    item.set("Schema", schema);
                }
                items.add(item);
            }
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "Items");
        return out;
    }

    // ---------------------------------------------------------------- runtime and event logs

    public ObjectNode putRuntimeLogConfiguration(String id, JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = thing(account, region, id);
        meta(record).set("runtimeLog", in.path("RuntimeLogConfigurations").deepCopy());
        store.put(account, region, THING, id, record);
        return store.mapper().createObjectNode();
    }

    public ObjectNode getRuntimeLogConfiguration(String id, String region) {
        ObjectNode record = thing(store.account(), region, id);
        ObjectNode out = store.mapper().createObjectNode().put("ManagedThingId", id);
        ObjectNode configuration = meta(record).has("runtimeLog") ? (ObjectNode) meta(record).get("runtimeLog")
                .deepCopy() : store.mapper().createObjectNode();
        // A managed thing that has none set uses the device SDK's defaults.
        if (!configuration.has("LogLevel")) {
            configuration.put("LogLevel", "INFO");
        }
        if (!configuration.has("LogFlushLevel")) {
            configuration.put("LogFlushLevel", "DEBUG");
        }
        if (!configuration.has("UploadLog")) {
            configuration.put("UploadLog", false);
        }
        out.set("RuntimeLogConfigurations", configuration);
        return out;
    }

    public ObjectNode resetRuntimeLogConfiguration(String id, String region) {
        String account = store.account();
        ObjectNode record = thing(account, region, id);
        meta(record).remove("runtimeLog");
        store.put(account, region, THING, id, record);
        return store.mapper().createObjectNode();
    }

    public ObjectNode createEventLogConfiguration(JsonNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateEventLogConfiguration", in);
        if (repeated != null) {
            return repeated;
        }
        String type = in.path("ResourceType").asText();
        String resource = in.path("ResourceId").asText(null);
        for (ObjectNode other : store.list(account, region, EVENT_LOG)) {
            if (type.equals(other.path("ResourceType").asText())
                    && Objects.equals(resource, other.path("ResourceId").asText(null))) {
                throw IotMiStore.conflict("An event log configuration for " + type
                        + (resource == null ? "" : " " + resource) + " already exists.");
            }
        }
        String id = IotMiStore.id();
        ObjectNode record = store.record();
        record.put("Id", id).put("ResourceType", type).put("EventLogLevel", in.path("EventLogLevel").asText());
        if (resource != null) {
            record.put("ResourceId", resource);
        }
        store.put(account, region, EVENT_LOG, id, record);
        ObjectNode out = store.mapper().createObjectNode().put("Id", id);
        store.remember(account, region, "CreateEventLogConfiguration", in, out);
        return out;
    }

    public ObjectNode getEventLogConfiguration(String id, String region) {
        return view(store.require(store.account(), region, EVENT_LOG, id, "event log configuration"));
    }

    public ObjectNode updateEventLogConfiguration(String id, JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = store.require(account, region, EVENT_LOG, id, "event log configuration");
        record.put("EventLogLevel", in.path("EventLogLevel").asText());
        store.put(account, region, EVENT_LOG, id, record);
        return store.mapper().createObjectNode();
    }

    public ObjectNode deleteEventLogConfiguration(String id, String region) {
        String account = store.account();
        store.require(account, region, EVENT_LOG, id, "event log configuration");
        store.delete(account, region, EVENT_LOG, id);
        return store.mapper().createObjectNode();
    }

    public ObjectNode listEventLogConfigurations(String token, Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        store.list(store.account(), region, EVENT_LOG).forEach(record -> items.add(view(record)));
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "EventLogConfigurationList");
        return out;
    }

    // ---------------------------------------------------------------- account settings

    public ObjectNode putHubConfiguration(JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = store.mapper().createObjectNode()
                .put("HubTokenTimerExpirySettingInSeconds", in.path("HubTokenTimerExpirySettingInSeconds").asLong())
                .put("UpdatedAt", IotMiStore.now());
        store.put(account, region, SETTINGS, "hub", record);
        return store.mapper().createObjectNode().put("HubTokenTimerExpirySettingInSeconds",
                record.path("HubTokenTimerExpirySettingInSeconds").asLong());
    }

    public ObjectNode getHubConfiguration(String region) {
        return store.require(store.account(), region, SETTINGS, "hub", "hub configuration").deepCopy();
    }

    public ObjectNode putDefaultEncryptionConfiguration(JsonNode in, String region) {
        String account = store.account();
        String type = in.path("encryptionType").asText();
        String key = in.path("kmsKeyArn").asText(null);
        if ("CUSTOMER_KEY_ENCRYPTION".equals(type) && key == null) {
            throw IotMiStore.validation("CUSTOMER_KEY_ENCRYPTION needs a kmsKeyArn.");
        }
        if ("MANAGED_INTEGRATIONS_DEFAULT_ENCRYPTION".equals(type) && key != null) {
            throw IotMiStore.validation("MANAGED_INTEGRATIONS_DEFAULT_ENCRYPTION takes no kmsKeyArn.");
        }
        ObjectNode record = store.mapper().createObjectNode().put("encryptionType", type);
        record.putObject("configurationStatus").put("state", "ENABLED");
        if (key != null) {
            record.put("kmsKeyArn", key);
        }
        store.put(account, region, SETTINGS, "encryption", record);
        return record.deepCopy();
    }

    public ObjectNode getDefaultEncryptionConfiguration(String region) {
        return store.get(store.account(), region, SETTINGS, "encryption").map(ObjectNode::deepCopy)
                .orElseGet(() -> {
                    ObjectNode out = store.mapper().createObjectNode()
                            .put("encryptionType", "MANAGED_INTEGRATIONS_DEFAULT_ENCRYPTION");
                    out.putObject("configurationStatus").put("state", "ENABLED");
                    return out;
                });
    }

    /** The account's endpoint for hubs and devices: Floci's AWS IoT endpoint, where its MQTT broker listens. */
    public ObjectNode registerCustomEndpoint(String region) {
        String account = store.account();
        String address = store.get(account, region, SETTINGS, "endpoint").map(record -> record.path("address")
                .asText()).orElseGet(() -> iot.get().describeEndpoint("iot:Data-ATS"));
        store.put(account, region, SETTINGS, "endpoint", store.mapper().createObjectNode().put("address", address));
        return store.mapper().createObjectNode().put("EndpointAddress", address);
    }

    public ObjectNode getCustomEndpoint(String region) {
        ObjectNode record = store.require(store.account(), region, SETTINGS, "endpoint", "custom endpoint");
        return store.mapper().createObjectNode().put("EndpointAddress", record.path("address").asText());
    }

    // ---------------------------------------------------------------- tags

    public ObjectNode listTagsForResource(String arn) {
        ObjectNode out = store.mapper().createObjectNode();
        out.set("tags", tagged(arn).path("Tags").deepCopy());
        return out;
    }

    public ObjectNode tagResource(String arn, JsonNode tags) {
        ObjectNode record = tagged(arn);
        ObjectNode current = record.has("Tags") ? (ObjectNode) record.get("Tags") : record.putObject("Tags");
        current.setAll(IotMiStore.tagsOf(store.mapper(), tags));
        if (current.size() > 50) {
            throw new AwsException("InvalidRequestException", "A resource can have at most 50 tags.", 400);
        }
        save(arn, record);
        return store.mapper().createObjectNode();
    }

    public ObjectNode untagResource(String arn, List<String> keys) {
        ObjectNode record = tagged(arn);
        if (record.path("Tags") instanceof ObjectNode current) {
            keys.forEach(current::remove);
        }
        save(arn, record);
        return store.mapper().createObjectNode();
    }

    private ObjectNode tagged(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidRequestException", "The ARN " + arn + " isn't valid.", 400);
        }
        String kind = parsed.resource().contains("/") ? parsed.resource().substring(0, parsed.resource().indexOf('/'))
                : "";
        if (!IotMiStore.SERVICE.equals(parsed.service()) || !TAGGABLE.contains(kind)
                || !parsed.accountId().equals(store.account())) {
            throw IotMiStore.notFound("resource", arn);
        }
        String id = parsed.resource().substring(kind.length() + 1);
        return store.get(parsed.accountId(), parsed.region(), kind, id)
                .orElseThrow(() -> IotMiStore.notFound("resource", arn));
    }

    private void save(String arn, ObjectNode record) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String kind = parsed.resource().substring(0, parsed.resource().indexOf('/'));
        store.put(parsed.accountId(), parsed.region(), kind, parsed.resource().substring(kind.length() + 1), record);
    }

    // ---------------------------------------------------------------- helpers

    /** A managed thing, its deletion finished if its time has come. */
    ObjectNode thing(String account, String region, String id) {
        ObjectNode record = store.get(account, region, THING, id).orElse(null);
        ObjectNode thing = record == null ? null : settle(account, region, record);
        if (thing == null) {
            throw IotMiStore.notFound("managed thing", id);
        }
        return thing;
    }

    private ObjectNode settle(String account, String region, ObjectNode record) {
        if ("DELETE_IN_PROGRESS".equals(record.path("ProvisioningStatus").asText())
                && !Instant.now().isBefore(Instant.parse(meta(record).path("deleteAt").asText()))) {
            store.delete(account, region, THING, record.path("Id").asText());
            return null;
        }
        return record;
    }

    static boolean deleted(ObjectNode thing) {
        String status = thing.path("ProvisioningStatus").asText();
        return "DELETE_IN_PROGRESS".equals(status) || "DELETED".equals(status);
    }

    private static void requireLive(ObjectNode thing) {
        if (deleted(thing)) {
            throw IotMiStore.conflict("The managed thing " + thing.path("Id").asText() + " is being deleted.");
        }
    }

    /** Checks onboarding material against its type, as managed integrations does before anything else. */
    private static void checkMaterial(String type, String material, String role) {
        boolean valid = switch (type) {
            case "ZIGBEE_QR_BAR_CODE" -> ZIGBEE.matcher(material).matches();
            case "ZWAVE_QR_BAR_CODE" -> ZWAVE.matcher(material).matches();
            case "WIFI_SETUP_QR_BAR_CODE" -> MATTER.matcher(material).matches();
            default -> !material.isBlank();
        };
        if (!valid) {
            throw IotMiStore.validation("The authentication material isn't a valid " + type + ".");
        }
        if ("CONTROLLER".equals(role) && Set.of("ZIGBEE_QR_BAR_CODE", "ZWAVE_QR_BAR_CODE").contains(type)) {
            throw IotMiStore.validation("A CONTROLLER is onboarded with a WiFi, custom or pre-onboarded "
                    + "authentication material, not " + type + ".");
        }
    }

    /** Each capability a report names must be in the catalog or among the thing's own capability schemas. */
    void checkCapabilities(JsonNode report, JsonNode custom) {
        Set<String> own = new LinkedHashSet<>();
        custom.forEach(item -> own.add(item.path("CapabilityId").asText()));
        for (JsonNode endpoint : report.path("endpoints")) {
            for (JsonNode capability : endpoint.path("capabilities")) {
                String id = capability.path("id").asText();
                if (!own.contains(id) && schemas.capability(id).isEmpty()) {
                    throw IotMiStore.validation("The capability " + id + " isn't in the schema catalog or the "
                            + "managed thing's capability schemas.");
                }
            }
        }
    }

    /** Records a state report for a thing and announces it as DEVICE_STATE. */
    void reportState(String account, String region, ObjectNode thing, ArrayNode endpoints) {
        ArrayNode state = meta(thing).has("state") ? (ArrayNode) meta(thing).get("state")
                : meta(thing).putArray("state");
        for (JsonNode endpoint : endpoints) {
            ObjectNode target = null;
            for (JsonNode existing : state) {
                if (existing.path("endpointId").asText().equals(endpoint.path("endpointId").asText())) {
                    target = (ObjectNode) existing;
                }
            }
            if (target == null) {
                target = state.addObject().put("endpointId", endpoint.path("endpointId").asText());
                target.putArray("capabilities");
            }
            ArrayNode capabilities = (ArrayNode) target.get("capabilities");
            for (JsonNode capability : endpoint.path("capabilities")) {
                ObjectNode existing = null;
                for (JsonNode candidate : capabilities) {
                    if (candidate.path("id").asText().equals(capability.path("id").asText())) {
                        existing = (ObjectNode) candidate;
                    }
                }
                if (existing == null) {
                    capabilities.add(capability.deepCopy());
                } else {
                    ObjectNode properties = existing.has("properties") ? (ObjectNode) existing.get("properties")
                            : existing.putObject("properties");
                    capability.path("properties").fields()
                            .forEachRemaining(property -> properties.set(property.getKey(), property.getValue()));
                }
            }
        }
        thing.put("UpdatedAt", IotMiStore.now());
        store.put(account, region, THING, thing.path("Id").asText(), thing);
        ObjectNode payload = store.mapper().createObjectNode();
        payload.putObject("addedStates").set("endpoints", endpoints.deepCopy());
        notifications.thingEvent(account, region, "DEVICE_STATE", thing, payload);
    }
}
