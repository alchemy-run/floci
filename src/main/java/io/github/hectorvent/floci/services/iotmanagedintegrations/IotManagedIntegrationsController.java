package io.github.hectorvent.floci.services.iotmanagedintegrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iotmanagedintegrations.model.CloudConnector;
import io.github.hectorvent.floci.services.iotmanagedintegrations.model.CredentialLocker;
import io.github.hectorvent.floci.services.iotmanagedintegrations.model.Destination;
import io.github.hectorvent.floci.services.iotmanagedintegrations.model.DeviceDiscovery;
import io.github.hectorvent.floci.services.iotmanagedintegrations.model.ManagedThing;
import io.github.hectorvent.floci.services.iotmanagedintegrations.model.NotificationConfiguration;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * AWS IoT Managed Integrations restJson1.
 *
 * <p>Literal kebab-case paths such as {@code /managed-thing-states/{id}} take
 * JAX-RS precedence over S3's {@code /{bucket}} catch-all. Requests are signed
 * as {@code iotmanagedintegrations}. GET routes accept any content type because
 * the SDK often omits {@code Content-Type} on reads.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IotManagedIntegrationsController {

    private final IotManagedIntegrationsService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public IotManagedIntegrationsController(
            IotManagedIntegrationsService service,
            ObjectMapper objectMapper,
            RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/managed-thing-states/{ManagedThingId}")
    @Consumes(MediaType.WILDCARD)
    public Response getManagedThingState(
            @Context HttpHeaders headers, @PathParam("ManagedThingId") String managedThingId) {
        return run(() -> Response.ok(service.getManagedThingState(region(headers), managedThingId)).build());
    }

    @GET
    @Path("/managed-things-capabilities/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getManagedThingCapabilities(
            @Context HttpHeaders headers, @PathParam("Identifier") String identifier) {
        return run(() -> Response.ok(service.getManagedThingCapabilities(region(headers), identifier)).build());
    }

    @GET
    @Path("/managed-things-certificate/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getManagedThingCertificate(
            @Context HttpHeaders headers, @PathParam("Identifier") String identifier) {
        return run(() -> Response.ok(service.getManagedThingCertificate(region(headers), identifier)).build());
    }

    @POST
    @Path("/managed-things-connectivity-data/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getManagedThingConnectivityData(
            @Context HttpHeaders headers, @PathParam("Identifier") String identifier) {
        return run(() -> Response.ok(service.getManagedThingConnectivityData(region(headers), identifier)).build());
    }

    @GET
    @Path("/managed-things-metadata/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getManagedThingMetaData(
            @Context HttpHeaders headers, @PathParam("Identifier") String identifier) {
        return run(() -> Response.ok(service.getManagedThingMetaData(region(headers), identifier)).build());
    }

    @GET
    @Path("/managed-thing-schemas/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response listManagedThingSchemas(
            @Context HttpHeaders headers, @PathParam("Identifier") String identifier) {
        return run(() -> Response.ok(service.listManagedThingSchemas(region(headers), identifier)).build());
    }

    @POST
    @Path("/managed-things-command/{ManagedThingId}")
    public Response sendManagedThingCommand(
            @Context HttpHeaders headers, @PathParam("ManagedThingId") String managedThingId, String body) {
        return run(() -> Response.ok(service.sendManagedThingCommand(region(headers), managedThingId, parse(body)))
                .build());
    }

    @POST
    @Path("/managed-things")
    public Response createManagedThing(@Context HttpHeaders headers, String body) {
        return run(() -> {
            ManagedThing thing = service.createManagedThing(region(headers), parse(body));
            return Response.ok(service.toCreateManagedThing(thing)).build();
        });
    }

    @GET
    @Path("/managed-things/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getManagedThing(@Context HttpHeaders headers, @PathParam("Identifier") String identifier) {
        return run(() -> Response.ok(service.toManagedThing(service.getManagedThing(region(headers), identifier)))
                .build());
    }

    @PUT
    @Path("/managed-things/{Identifier}")
    public Response updateManagedThing(
            @Context HttpHeaders headers, @PathParam("Identifier") String identifier, String body) {
        return run(() -> {
            service.updateManagedThing(region(headers), identifier, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @DELETE
    @Path("/managed-things/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteManagedThing(@Context HttpHeaders headers, @PathParam("Identifier") String identifier) {
        return run(() -> {
            service.deleteManagedThing(region(headers), identifier);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/managed-things")
    @Consumes(MediaType.WILDCARD)
    public Response listManagedThings(@Context HttpHeaders headers) {
        return run(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("Items");
            for (ManagedThing thing : service.listManagedThings(region(headers))) {
                items.add(service.toManagedThingSummary(thing));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/cloud-connectors")
    public Response createCloudConnector(@Context HttpHeaders headers, String body) {
        return run(() -> {
            CloudConnector connector = service.createCloudConnector(region(headers), parse(body));
            return Response.ok(service.toCreateCloudConnector(connector)).build();
        });
    }

    @GET
    @Path("/cloud-connectors/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getCloudConnector(@Context HttpHeaders headers, @PathParam("Identifier") String identifier) {
        return run(() -> Response.ok(service.toCloudConnector(service.getCloudConnector(region(headers), identifier)))
                .build());
    }

    @POST
    @Path("/connector-event/{ConnectorId}")
    public Response sendConnectorEvent(
            @Context HttpHeaders headers, @PathParam("ConnectorId") String connectorId, String body) {
        return run(() -> Response.ok(service.sendConnectorEvent(region(headers), connectorId, parse(body))).build());
    }

    @POST
    @Path("/device-discoveries")
    public Response startDeviceDiscovery(@Context HttpHeaders headers, String body) {
        return run(() -> {
            DeviceDiscovery discovery = service.startDeviceDiscovery(region(headers), parse(body));
            return Response.ok(service.toStartDeviceDiscovery(discovery)).build();
        });
    }

    @GET
    @Path("/device-discoveries/{Identifier}/devices")
    @Consumes(MediaType.WILDCARD)
    public Response listDiscoveredDevices(
            @Context HttpHeaders headers, @PathParam("Identifier") String identifier) {
        return run(() -> Response.ok(service.listDiscoveredDevices(region(headers), identifier)).build());
    }

    @GET
    @Path("/device-discoveries/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getDeviceDiscovery(@Context HttpHeaders headers, @PathParam("Identifier") String identifier) {
        return run(() -> Response.ok(service.toDeviceDiscovery(service.getDeviceDiscovery(region(headers), identifier)))
                .build());
    }

    @GET
    @Path("/device-discoveries")
    @Consumes(MediaType.WILDCARD)
    public Response listDeviceDiscoveries(@Context HttpHeaders headers) {
        return run(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("Items");
            for (DeviceDiscovery discovery : service.listDeviceDiscoveries(region(headers))) {
                ObjectNode summary = items.addObject();
                summary.put("Id", discovery.getId());
                summary.put("DiscoveryType", discovery.getDiscoveryType());
                summary.put("Status", discovery.getStatus());
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/schema-versions/{Type}/{SchemaVersionedId}")
    @Consumes(MediaType.WILDCARD)
    public Response getSchemaVersion(
            @PathParam("Type") String type, @PathParam("SchemaVersionedId") String schemaVersionedId) {
        return run(() -> Response.ok(service.getSchemaVersion(type, schemaVersionedId)).build());
    }

    @GET
    @Path("/schema-versions/{Type}")
    @Consumes(MediaType.WILDCARD)
    public Response listSchemaVersions(
            @PathParam("Type") String type, @QueryParam("MaxResults") String maxResults) {
        return run(() -> Response.ok(service.listSchemaVersions(type, maxResults)).build());
    }

    @GET
    @Path("/custom-endpoint")
    @Consumes(MediaType.WILDCARD)
    public Response getCustomEndpoint(@Context HttpHeaders headers) {
        return run(() -> Response.ok(service.getCustomEndpoint(region(headers))).build());
    }

    @POST
    @Path("/credential-lockers")
    public Response createCredentialLocker(@Context HttpHeaders headers, String body) {
        return run(() -> {
            CredentialLocker locker = service.createCredentialLocker(region(headers), parse(body));
            return Response.ok(service.toCreateCredentialLocker(locker)).build();
        });
    }

    @GET
    @Path("/credential-lockers/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getCredentialLocker(@Context HttpHeaders headers, @PathParam("Identifier") String identifier) {
        return run(() -> Response.ok(service.toCredentialLocker(service.getCredentialLocker(region(headers), identifier)))
                .build());
    }

    @DELETE
    @Path("/credential-lockers/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteCredentialLocker(
            @Context HttpHeaders headers, @PathParam("Identifier") String identifier) {
        return run(() -> {
            service.deleteCredentialLocker(region(headers), identifier);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/credential-lockers")
    @Consumes(MediaType.WILDCARD)
    public Response listCredentialLockers(@Context HttpHeaders headers) {
        return run(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("Items");
            for (CredentialLocker locker : service.listCredentialLockers(region(headers))) {
                ObjectNode summary = items.addObject();
                summary.put("Id", locker.getId());
                putOptional(summary, "Name", locker.getName());
                summary.put("Arn", locker.getArn());
                summary.put("CreatedAt", locker.getCreatedAt());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/destinations")
    public Response createDestination(@Context HttpHeaders headers, String body) {
        return run(() -> {
            Destination destination = service.createDestination(region(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("Name", destination.getName());
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/destinations/{Name}")
    @Consumes(MediaType.WILDCARD)
    public Response getDestination(@Context HttpHeaders headers, @PathParam("Name") String name) {
        return run(() -> Response.ok(service.toDestination(service.getDestination(region(headers), name))).build());
    }

    @PUT
    @Path("/destinations/{Name}")
    public Response updateDestination(@Context HttpHeaders headers, @PathParam("Name") String name, String body) {
        return run(() -> {
            service.updateDestination(region(headers), name, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @DELETE
    @Path("/destinations/{Name}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDestination(@Context HttpHeaders headers, @PathParam("Name") String name) {
        return run(() -> {
            service.deleteDestination(region(headers), name);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/destinations")
    @Consumes(MediaType.WILDCARD)
    public Response listDestinations(
            @Context HttpHeaders headers,
            @QueryParam("MaxResults") String maxResults,
            @QueryParam("NextToken") String nextToken) {
        return run(() -> {
            IotManagedIntegrationsService.Page<Destination> page =
                    service.listDestinations(region(headers), maxResults, nextToken);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("DestinationList");
            for (Destination destination : page.items()) {
                items.add(service.toDestinationSummary(destination));
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/notification-configurations")
    public Response createNotificationConfiguration(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.toNotificationConfiguration(
                service.createNotificationConfiguration(region(headers), parse(body)))).build());
    }

    @GET
    @Path("/notification-configurations/{EventType}")
    @Consumes(MediaType.WILDCARD)
    public Response getNotificationConfiguration(
            @Context HttpHeaders headers, @PathParam("EventType") String eventType) {
        return run(() -> Response.ok(service.toNotificationConfiguration(
                service.getNotificationConfiguration(region(headers), eventType))).build());
    }

    @PUT
    @Path("/notification-configurations/{EventType}")
    public Response updateNotificationConfiguration(
            @Context HttpHeaders headers, @PathParam("EventType") String eventType, String body) {
        return run(() -> {
            service.updateNotificationConfiguration(region(headers), eventType, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @DELETE
    @Path("/notification-configurations/{EventType}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteNotificationConfiguration(
            @Context HttpHeaders headers, @PathParam("EventType") String eventType) {
        return run(() -> {
            service.deleteNotificationConfiguration(region(headers), eventType);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/notification-configurations")
    @Consumes(MediaType.WILDCARD)
    public Response listNotificationConfigurations(@Context HttpHeaders headers) {
        return run(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("NotificationConfigurationList");
            for (NotificationConfiguration config : service.listNotificationConfigurations(region(headers))) {
                items.add(service.toNotificationConfiguration(config));
            }
            return Response.ok(response).build();
        });
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    private Response run(Handler handler) {
        try {
            return handler.handle();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }

    private static void putOptional(ObjectNode parent, String field, String value) {
        if (value != null) {
            parent.put(field, value);
        }
    }

    @FunctionalInterface
    private interface Handler {
        Response handle();
    }
}
