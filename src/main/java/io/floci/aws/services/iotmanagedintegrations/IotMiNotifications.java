package io.floci.aws.services.iotmanagedintegrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.kinesis.KinesisService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.DESTINATION;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.NOTIFICATION;

/**
 * Notification destinations (Kinesis data streams written through a role managed integrations assumes) and the
 * notification configuration that routes each event type to one of them; and the delivery of events in the
 * envelope managed integrations uses ({@code messageType}, {@code source}, {@code resources}, {@code payload}).
 */
@ApplicationScoped
public class IotMiNotifications {

    private static final Logger LOG = Logger.getLogger(IotMiNotifications.class);
    private static final String SERVICE_PRINCIPAL = "iotmanagedintegrations.amazonaws.com"; // partition-literal: service principals keep the commercial name

    private final IotMiStore store;
    private final Instance<KinesisService> kinesis;
    private final Instance<IamService> iam;

    @Inject
    public IotMiNotifications(IotMiStore store, Instance<KinesisService> kinesis, Instance<IamService> iam) {
        this.store = store;
        this.kinesis = kinesis;
        this.iam = iam;
    }

    // ---------------------------------------------------------------- destinations

    public ObjectNode createDestination(JsonNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateDestination", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.path("Name").asText();
        if (store.get(account, region, DESTINATION, name).isPresent()) {
            throw IotMiStore.conflict("The destination " + name + " already exists.");
        }
        checkStream(account, region, in.path("DeliveryDestinationArn").asText());
        checkRole(account, in.path("RoleArn").asText());
        ObjectNode record = store.record();
        double now = IotMiStore.now();
        record.put("Name", name).put("DeliveryDestinationArn", in.path("DeliveryDestinationArn").asText())
                .put("DeliveryDestinationType", in.path("DeliveryDestinationType").asText())
                .put("RoleArn", in.path("RoleArn").asText()).put("CreatedAt", now).put("UpdatedAt", now);
        if (in.hasNonNull("Description")) {
            record.put("Description", in.path("Description").asText());
        }
        record.set("Tags", IotMiStore.tagsOf(store.mapper(), in.path("Tags")));
        store.put(account, region, DESTINATION, name, record);
        ObjectNode out = store.mapper().createObjectNode().put("Name", name);
        store.remember(account, region, "CreateDestination", in, out);
        return out;
    }

    public ObjectNode getDestination(String name, String region) {
        return IotMiStore.view(store.require(store.account(), region, DESTINATION, name, "destination"));
    }

    public ObjectNode updateDestination(String name, JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = store.require(account, region, DESTINATION, name, "destination");
        if (in.hasNonNull("DeliveryDestinationArn")) {
            checkStream(account, region, in.path("DeliveryDestinationArn").asText());
            record.put("DeliveryDestinationArn", in.path("DeliveryDestinationArn").asText());
        }
        if (in.hasNonNull("RoleArn")) {
            checkRole(account, in.path("RoleArn").asText());
            record.put("RoleArn", in.path("RoleArn").asText());
        }
        for (String field : List.of("DeliveryDestinationType", "Description")) {
            if (in.hasNonNull(field)) {
                record.put(field, in.path(field).asText());
            }
        }
        record.put("UpdatedAt", IotMiStore.now());
        store.put(account, region, DESTINATION, name, record);
        return store.mapper().createObjectNode();
    }

    public ObjectNode deleteDestination(String name, String region) {
        String account = store.account();
        store.require(account, region, DESTINATION, name, "destination");
        for (ObjectNode configuration : store.list(account, region, NOTIFICATION)) {
            if (name.equals(configuration.path("DestinationName").asText())) {
                throw IotMiStore.conflict("The destination " + name + " is used by the "
                        + configuration.path("EventType").asText() + " notification configuration.");
            }
        }
        store.delete(account, region, DESTINATION, name);
        return store.mapper().createObjectNode();
    }

    public ObjectNode listDestinations(String token, Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, DESTINATION)) {
            ObjectNode summary = IotMiStore.view(record);
            for (String field : List.of("CreatedAt", "UpdatedAt", "Tags")) {
                summary.remove(field);
            }
            items.add(summary);
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "DestinationList");
        return out;
    }

    private void checkStream(String account, String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw IotMiStore.validation("The delivery destination " + arn + " isn't an ARN.");
        }
        if (!"kinesis".equals(parsed.service()) || !parsed.resource().startsWith("stream/")) {
            throw IotMiStore.validation("The delivery destination must be a Kinesis data stream.");
        }
        try {
            RequestScopes.runAs(account, region, () -> kinesis.get().describeStreamForAccount(parsed.accountId(),
                    parsed.resource().substring("stream/".length()), parsed.region()));
        } catch (AwsException e) {
            throw IotMiStore.validation("The Kinesis data stream " + arn + " doesn't exist.");
        }
    }

    private IamRole checkRole(String account, String roleArn) {
        String name = roleArn.substring(roleArn.lastIndexOf('/') + 1);
        try {
            return RequestScopes.callAs(account, () -> iam.get().getRole(name));
        } catch (AwsException e) {
            throw IotMiStore.validation("The role " + roleArn + " doesn't exist.");
        }
    }

    // ---------------------------------------------------------------- notification configurations

    public ObjectNode createNotificationConfiguration(JsonNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateNotificationConfiguration", in);
        if (repeated != null) {
            return repeated;
        }
        String type = in.path("EventType").asText();
        if (store.get(account, region, NOTIFICATION, type).isPresent()) {
            throw IotMiStore.conflict("A notification configuration for " + type + " already exists.");
        }
        String destination = in.path("DestinationName").asText();
        store.require(account, region, DESTINATION, destination, "destination");
        ObjectNode record = store.record();
        double now = IotMiStore.now();
        record.put("EventType", type).put("DestinationName", destination).put("CreatedAt", now)
                .put("UpdatedAt", now);
        record.set("Tags", IotMiStore.tagsOf(store.mapper(), in.path("Tags")));
        store.put(account, region, NOTIFICATION, type, record);
        ObjectNode out = store.mapper().createObjectNode().put("EventType", type);
        store.remember(account, region, "CreateNotificationConfiguration", in, out);
        return out;
    }

    public ObjectNode getNotificationConfiguration(String type, String region) {
        return IotMiStore.view(store.require(store.account(), region, NOTIFICATION, type,
                "notification configuration"));
    }

    public ObjectNode updateNotificationConfiguration(String type, JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = store.require(account, region, NOTIFICATION, type, "notification configuration");
        String destination = in.path("DestinationName").asText();
        store.require(account, region, DESTINATION, destination, "destination");
        record.put("DestinationName", destination).put("UpdatedAt", IotMiStore.now());
        store.put(account, region, NOTIFICATION, type, record);
        return store.mapper().createObjectNode();
    }

    public ObjectNode deleteNotificationConfiguration(String type, String region) {
        String account = store.account();
        store.require(account, region, NOTIFICATION, type, "notification configuration");
        store.delete(account, region, NOTIFICATION, type);
        return store.mapper().createObjectNode();
    }

    public ObjectNode listNotificationConfigurations(String token, Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, NOTIFICATION)) {
            items.add(store.mapper().createObjectNode().put("EventType", record.path("EventType").asText())
                    .put("DestinationName", record.path("DestinationName").asText()));
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "NotificationConfigurationList");
        return out;
    }

    // ---------------------------------------------------------------- delivery

    /**
     * Sends an event to the destination its type is routed to, if any: a record on the destination's Kinesis stream,
     * written as the destination's role, which must trust managed integrations.
     */
    void publish(String account, String region, String type, List<String> resources, ObjectNode payload) {
        ObjectNode configuration = store.get(account, region, NOTIFICATION, type).orElse(null);
        if (configuration == null) {
            return;
        }
        ObjectNode destination = store.get(account, region, DESTINATION,
                configuration.path("DestinationName").asText()).orElse(null);
        if (destination == null) {
            return;
        }
        try {
            IamRole role = checkRole(account, destination.path("RoleArn").asText());
            if (role.getAssumeRolePolicyDocument() == null
                    || !role.getAssumeRolePolicyDocument().contains(SERVICE_PRINCIPAL)) {
                LOG.debugv("The role {0} doesn't trust managed integrations; {1} isn't delivered",
                        role.getArn(), type);
                return;
            }
            ObjectNode message = store.mapper().createObjectNode();
            message.put("version", "1.0.0").put("messageId", IotMiStore.id()).put("messageType", type)
                    .put("source", "aws." + IotMiStore.SERVICE).put("customerAccountId", account)
                    .put("timestamp", Instant.now().toString()).put("region", region);
            ArrayNode list = message.putArray("resources");
            resources.forEach(list::add);
            message.set("payload", payload);
            AwsArnUtils.Arn stream = AwsArnUtils.parse(destination.path("DeliveryDestinationArn").asText());
            String partitionKey = resources.isEmpty() ? type : resources.get(0);
            RequestScopes.runAs(stream.accountId(), stream.region(), () -> kinesis.get().putRecord(
                    stream.resource().substring("stream/".length()),
                    message.toString().getBytes(StandardCharsets.UTF_8), partitionKey, stream.region()));
        } catch (RuntimeException e) {
            LOG.debugv(e, "Managed integrations could not deliver a {0} notification", type);
        }
    }

    /** An event about one managed thing. */
    void thingEvent(String account, String region, String type, ObjectNode thing, ObjectNode payload) {
        publish(account, region, type, List.of(thing.path("Arn").asText()), payload);
    }

    /** The lifecycle event of a managed thing whose provisioning status changed. */
    void lifecycle(String account, String region, ObjectNode thing) {
        ObjectNode payload = store.mapper().createObjectNode();
        ObjectNode details = payload.putObject("deviceDetails");
        details.put("id", thing.path("Id").asText()).put("arn", thing.path("Arn").asText())
                .put("createdAt", Instant.ofEpochMilli((long) (thing.path("CreatedAt").asDouble() * 1000)).toString())
                .put("updatedAt", Instant.ofEpochMilli((long) (thing.path("UpdatedAt").asDouble() * 1000)).toString());
        payload.put("status", thing.path("ProvisioningStatus").asText());
        thingEvent(account, region, "DEVICE_LIFE_CYCLE", thing, payload);
    }
}
