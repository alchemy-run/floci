package io.github.hectorvent.floci.services.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.notifications.model.EventRule;
import io.github.hectorvent.floci.services.notifications.model.EventRuleStatusSummary;
import io.github.hectorvent.floci.services.notifications.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.notifications.model.NotificationHub;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * AWS User Notifications restJson1. Public paths are rewritten by
 * {@link NotificationsRoutingFilter} so they do not collide with IoT Managed
 * Integrations or DevOps Guru. Tag APIs share {@code /tags/{arn}}.
 */
@Path(NotificationsRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NotificationsController {

    private final NotificationsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public NotificationsController(NotificationsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/notification-configurations")
    public Response createNotificationConfiguration(String body) {
        NotificationConfiguration config = service.createConfiguration(parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", config.getArn());
        response.put("status", service.configurationStatus(config));
        return Response.ok(response).build();
    }

    @GET
    @Path("/notification-configurations")
    @Consumes(MediaType.WILDCARD)
    public Response listNotificationConfigurations() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("notificationConfigurations");
        for (NotificationConfiguration config : service.listConfigurations()) {
            list.add(toConfiguration(config));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/notification-configurations/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getNotificationConfiguration(@PathParam("arn") String arn) {
        return Response.ok(toConfiguration(service.getConfiguration(arn))).build();
    }

    @PUT
    @Path("/notification-configurations/{arn: .+}")
    public Response updateNotificationConfiguration(@PathParam("arn") String arn, String body) {
        NotificationConfiguration config = service.updateConfiguration(arn, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", config.getArn());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/notification-configurations/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteNotificationConfiguration(@PathParam("arn") String arn) {
        service.deleteConfiguration(arn);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/event-rules")
    public Response createEventRule(String body) {
        EventRule rule = service.createEventRule(parse(body));
        return Response.ok(toCreateEventRule(rule)).build();
    }

    @GET
    @Path("/event-rules")
    @Consumes(MediaType.WILDCARD)
    public Response listEventRules(
            @QueryParam("notificationConfigurationArn") String notificationConfigurationArn) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("eventRules");
        for (EventRule rule : service.listEventRules(notificationConfigurationArn)) {
            list.add(toEventRule(rule));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/event-rules/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getEventRule(@PathParam("arn") String arn) {
        return Response.ok(toEventRule(service.getEventRule(arn))).build();
    }

    @PUT
    @Path("/event-rules/{arn: .+}")
    public Response updateEventRule(@PathParam("arn") String arn, String body) {
        EventRule rule = service.updateEventRule(arn, parse(body));
        return Response.ok(toCreateEventRule(rule)).build();
    }

    @DELETE
    @Path("/event-rules/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteEventRule(@PathParam("arn") String arn) {
        service.deleteEventRule(arn);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/channels")
    @Consumes(MediaType.WILDCARD)
    public Response listChannels(@QueryParam("notificationConfigurationArn") String notificationConfigurationArn) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("channels");
        for (String channel : service.listChannels(notificationConfigurationArn)) {
            list.add(channel);
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/channels/list-managed-notification-channel-associations")
    @Consumes(MediaType.WILDCARD)
    public Response listManagedNotificationChannelAssociations(
            @QueryParam("managedNotificationConfigurationArn") String managedNotificationConfigurationArn) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("channelAssociations");
        for (NotificationsService.ManagedNotificationChannelAssociation association :
                service.listManagedChannelAssociations(managedNotificationConfigurationArn)) {
            ObjectNode item = list.addObject();
            item.put("channelIdentifier", association.channelIdentifier());
            item.put("channelType", association.channelType());
            if (association.overrideOption() != null) {
                item.put("overrideOption", association.overrideOption());
            }
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/notification-events")
    @Consumes(MediaType.WILDCARD)
    public Response listNotificationEvents() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("notificationEvents");
        return Response.ok(response).build();
    }

    @GET
    @Path("/notification-events/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getNotificationEvent(@PathParam("arn") String arn) {
        service.requireNotificationEvent(arn);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/managed-notification-configurations")
    @Consumes(MediaType.WILDCARD)
    public Response listManagedNotificationConfigurations() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("managedNotificationConfigurations");
        for (NotificationsService.ManagedNotificationConfiguration config : service.listManagedConfigurations()) {
            ObjectNode item = list.addObject();
            item.put("arn", config.arn());
            item.put("name", config.name());
            item.put("description", config.description());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/managed-notification-configurations/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getManagedNotificationConfiguration(@PathParam("arn") String arn) {
        NotificationsService.ManagedNotificationConfiguration config = service.getManagedConfiguration(arn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", config.arn());
        response.put("name", config.name());
        response.put("description", config.description());
        response.put("category", config.category());
        response.put("subCategory", config.subCategory());
        return Response.ok(response).build();
    }

    @GET
    @Path("/managed-notification-events")
    @Consumes(MediaType.WILDCARD)
    public Response listManagedNotificationEvents() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("managedNotificationEvents");
        return Response.ok(response).build();
    }

    @GET
    @Path("/managed-notification-events/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getManagedNotificationEvent(@PathParam("arn") String arn) {
        service.requireManagedEvent(arn);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/managed-notification-child-events/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getManagedNotificationChildEvent(@PathParam("arn") String arn) {
        service.requireManagedChildEvent(arn);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/list-managed-notification-child-events/{aggregateManagedNotificationEventArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response listManagedNotificationChildEvents(
            @PathParam("aggregateManagedNotificationEventArn") String aggregateArn) {
        service.requireManagedEvent(aggregateArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("managedNotificationChildEvents");
        return Response.ok(response).build();
    }

    @POST
    @Path("/channels/associate/{arn: .+}")
    public Response associateChannel(@PathParam("arn") String arn, String body) {
        service.associateChannel(arn, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/channels/disassociate/{arn: .+}")
    public Response disassociateChannel(@PathParam("arn") String arn, String body) {
        service.disassociateChannel(arn, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/notification-hubs")
    public Response registerNotificationHub(String body) {
        return Response.ok(toHub(service.registerHub(parse(body)))).build();
    }

    @GET
    @Path("/notification-hubs")
    @Consumes(MediaType.WILDCARD)
    public Response listNotificationHubs() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("notificationHubs");
        for (NotificationHub hub : service.listHubs()) {
            list.add(toHub(hub));
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/notification-hubs/{notificationHubRegion}")
    @Consumes(MediaType.WILDCARD)
    public Response deregisterNotificationHub(@PathParam("notificationHubRegion") String notificationHubRegion) {
        NotificationHub hub = service.deregisterHub(notificationHubRegion);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("notificationHubRegion", hub.getNotificationHubRegion());
        response.set("statusSummary", statusSummary(hub.getStatus(), hub.getStatusReason()));
        return Response.ok(response).build();
    }

    private ObjectNode toConfiguration(NotificationConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", config.getArn());
        node.put("name", config.getName());
        node.put("description", config.getDescription());
        node.put("status", service.configurationStatus(config));
        node.put("creationTime", config.getCreationTime());
        if (config.getAggregationDuration() != null) {
            node.put("aggregationDuration", config.getAggregationDuration());
        }
        return node;
    }

    private ObjectNode toEventRule(EventRule rule) {
        ObjectNode node = toCreateEventRule(rule);
        node.put("creationTime", rule.getCreationTime());
        node.put("source", rule.getSource());
        node.put("eventType", rule.getEventType());
        node.put("eventPattern", rule.getEventPattern() == null ? "" : rule.getEventPattern());
        ArrayNode regions = node.putArray("regions");
        for (String region : rule.getRegions()) {
            regions.add(region);
        }
        ArrayNode managed = node.putArray("managedRules");
        for (String managedRule : rule.getManagedRules()) {
            managed.add(managedRule);
        }
        return node;
    }

    private ObjectNode toCreateEventRule(EventRule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", rule.getArn());
        node.put("notificationConfigurationArn", rule.getNotificationConfigurationArn());
        ObjectNode summaries = node.putObject("statusSummaryByRegion");
        Map<String, EventRuleStatusSummary> byRegion = rule.getStatusSummaryByRegion();
        if (byRegion != null) {
            byRegion.forEach((region, summary) -> summaries.set(region, statusSummary(summary.getStatus(), summary.getReason())));
        }
        return node;
    }

    private ObjectNode toHub(NotificationHub hub) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("notificationHubRegion", hub.getNotificationHubRegion());
        node.set("statusSummary", statusSummary(hub.getStatus(), hub.getStatusReason()));
        node.put("creationTime", hub.getCreationTime());
        if (hub.getLastActivationTime() != null) {
            node.put("lastActivationTime", hub.getLastActivationTime());
        }
        return node;
    }

    private ObjectNode statusSummary(String status, String reason) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", status == null ? "ACTIVE" : status);
        node.put("reason", reason == null ? "" : reason);
        return node;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw NotificationsService.validation("Request body must be a JSON object.");
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw NotificationsService.validation("Request body is not valid JSON.");
        }
    }
}
