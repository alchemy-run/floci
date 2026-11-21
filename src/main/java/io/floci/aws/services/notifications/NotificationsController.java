package io.floci.aws.services.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RestJsonDispatcher;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;

/**
 * The AWS User Notifications REST API, routed here by {@link NotificationsRouteFilter} and matched to operations by its HTTP bindings
 * ({@link RestJsonDispatcher}).
 */
@Path(NotificationsRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class NotificationsController {

    /** AWS User Notifications' request constraints, worded as a ValidationException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/notifications-request-shapes.json", "AWS User Notifications request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return NotificationsStore.validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
                    return NotificationsStore.validation(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return NotificationsStore.validation(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return NotificationsStore.validation(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return NotificationsStore.validation(path + " is required.");
                }
            });

    private final NotificationsService service;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public NotificationsController(NotificationsService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "notifications");
    }

    @GET
    @Path("{path: .*}")
    public Response get(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("GET", headers, uri, body);
    }

    @POST
    @Path("{path: .*}")
    public Response post(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("POST", headers, uri, body);
    }

    @PUT
    @Path("{path: .*}")
    public Response put(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PUT", headers, uri, body);
    }

    @PATCH
    @Path("{path: .*}")
    public Response patch(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PATCH", headers, uri, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("DELETE", headers, uri, body);
    }

    private Response dispatch(String method, HttpHeaders headers, UriInfo uri, byte[] body) {
        String path = uri.getRequestUri().getRawPath().substring(NotificationsRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "AssociateChannel" -> service.associateChannel(input, region);
            case "AssociateManagedNotificationAccountContact" -> service.associateManagedNotificationAccountContact(input, region);
            case "AssociateManagedNotificationAdditionalChannel" -> service.associateManagedNotificationAdditionalChannel(input, region);
            case "AssociateOrganizationalUnit" -> service.associateOrganizationalUnit(input, region);
            case "CreateEventRule" -> service.createEventRule(input, region);
            case "CreateNotificationConfiguration" -> service.createNotificationConfiguration(input, region);
            case "DeleteEventRule" -> service.deleteEventRule(input, region);
            case "DeleteNotificationConfiguration" -> service.deleteNotificationConfiguration(input, region);
            case "DeregisterNotificationHub" -> service.deregisterNotificationHub(input, region);
            case "DisableNotificationsAccessForOrganization" -> service.disableNotificationsAccessForOrganization(input, region);
            case "DisassociateChannel" -> service.disassociateChannel(input, region);
            case "DisassociateManagedNotificationAccountContact" -> service.disassociateManagedNotificationAccountContact(input, region);
            case "DisassociateManagedNotificationAdditionalChannel" -> service.disassociateManagedNotificationAdditionalChannel(input, region);
            case "DisassociateOrganizationalUnit" -> service.disassociateOrganizationalUnit(input, region);
            case "EnableNotificationsAccessForOrganization" -> service.enableNotificationsAccessForOrganization(input, region);
            case "GetEventRule" -> service.getEventRule(input, region);
            case "GetManagedNotificationChildEvent" -> service.getManagedNotificationChildEvent(input, region);
            case "GetManagedNotificationConfiguration" -> service.getManagedNotificationConfiguration(input, region);
            case "GetManagedNotificationEvent" -> service.getManagedNotificationEvent(input, region);
            case "GetNotificationConfiguration" -> service.getNotificationConfiguration(input, region);
            case "GetNotificationEvent" -> service.getNotificationEvent(input, region);
            case "GetNotificationsAccessForOrganization" -> service.getNotificationsAccessForOrganization(input, region);
            case "ListChannels" -> service.listChannels(input, region);
            case "ListEventRules" -> service.listEventRules(input, region);
            case "ListManagedNotificationChannelAssociations" -> service.listManagedNotificationChannelAssociations(input, region);
            case "ListManagedNotificationChildEvents" -> service.listManagedNotificationChildEvents(input, region);
            case "ListManagedNotificationConfigurations" -> service.listManagedNotificationConfigurations(input, region);
            case "ListManagedNotificationEvents" -> service.listManagedNotificationEvents(input, region);
            case "ListMemberAccounts" -> service.listMemberAccounts(input, region);
            case "ListNotificationConfigurations" -> service.listNotificationConfigurations(input, region);
            case "ListNotificationEvents" -> service.listNotificationEvents(input, region);
            case "ListNotificationHubs" -> service.listNotificationHubs(input, region);
            case "ListOrganizationalUnits" -> service.listOrganizationalUnits(input, region);
            case "ListTagsForResource" -> service.listTagsForResource(input, region);
            case "RegisterNotificationHub" -> service.registerNotificationHub(input, region);
            case "TagResource" -> service.tagResource(input, region);
            case "UntagResource" -> service.untagResource(input, region);
            case "UpdateEventRule" -> service.updateEventRule(input, region);
            case "UpdateManagedNotificationChannelAssociation" -> service.updateManagedNotificationChannelAssociation(input, region);
            case "UpdateNotificationConfiguration" -> service.updateNotificationConfiguration(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
