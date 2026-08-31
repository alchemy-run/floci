package io.github.hectorvent.floci.services.route53profiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.route53profiles.model.ProfileResourceAssociation;
import io.github.hectorvent.floci.services.route53profiles.model.Route53Profile;
import io.github.hectorvent.floci.services.route53profiles.model.Route53ProfileAssociation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Route 53 Profiles restJson1. {@link Route53ProfilesRoutingFilter} prefixes signed
 * paths onto {@link Route53ProfilesRoutingFilter#INTERNAL_PREFIX} so they do not
 * collide with IAM Roles Anywhere. Tag APIs share {@code /tags/{arn}} via
 * {@code SharedTagsController}.
 */
@Path(Route53ProfilesRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class Route53ProfilesController {

    private final Route53ProfilesService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public Route53ProfilesController(
            Route53ProfilesService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/profile")
    public Response createProfile(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Route53Profile profile = service.createProfile(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("Profile", profileNode(profile));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/profile/{ProfileId}")
    @Consumes(MediaType.WILDCARD)
    public Response getProfile(@Context HttpHeaders headers, @PathParam("ProfileId") String profileId) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("Profile", profileNode(service.getProfile(region(headers), decode(profileId))));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @DELETE
    @Path("/profile/{ProfileId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteProfile(@Context HttpHeaders headers, @PathParam("ProfileId") String profileId) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("Profile", profileNode(service.deleteProfile(region(headers), decode(profileId))));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @GET
    @Path("/profiles")
    @Consumes(MediaType.WILDCARD)
    public Response listProfiles(@Context HttpHeaders headers) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("ProfileSummaries");
            for (Route53Profile profile : service.listProfiles(region(headers))) {
                ObjectNode item = summaries.addObject();
                item.put("Id", profile.getId());
                item.put("Arn", profile.getArn());
                item.put("Name", profile.getName());
                if (profile.getShareStatus() != null) {
                    item.put("ShareStatus", profile.getShareStatus());
                }
            }
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @POST
    @Path("/profileassociation")
    public Response associateProfile(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Route53ProfileAssociation association = service.associateProfile(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("ProfileAssociation", associationNode(association));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/profileassociation/{ProfileAssociationId}")
    @Consumes(MediaType.WILDCARD)
    public Response getProfileAssociation(
            @Context HttpHeaders headers,
            @PathParam("ProfileAssociationId") String profileAssociationId) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("ProfileAssociation",
                    associationNode(service.getProfileAssociation(region(headers), decode(profileAssociationId))));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @DELETE
    @Path("/profileassociation/Profileid/{ProfileId}/resourceid/{ResourceId}")
    @Consumes(MediaType.WILDCARD)
    public Response disassociateProfile(
            @Context HttpHeaders headers,
            @PathParam("ProfileId") String profileId,
            @PathParam("ResourceId") String resourceId) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("ProfileAssociation",
                    associationNode(service.disassociateProfile(
                            region(headers), decode(profileId), decode(resourceId))));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @GET
    @Path("/profileassociations")
    @Consumes(MediaType.WILDCARD)
    public Response listProfileAssociations(
            @Context HttpHeaders headers,
            @QueryParam("profileId") String profileId,
            @QueryParam("resourceId") String resourceId) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("ProfileAssociations");
            for (Route53ProfileAssociation association :
                    service.listProfileAssociations(region(headers), profileId, resourceId)) {
                items.add(associationNode(association));
            }
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @POST
    @Path("/profileresourceassociation")
    public Response associateResourceToProfile(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ProfileResourceAssociation association =
                    service.associateResourceToProfile(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("ProfileResourceAssociation", resourceAssociationNode(association));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/profileresourceassociation/{ProfileResourceAssociationId}")
    @Consumes(MediaType.WILDCARD)
    public Response getProfileResourceAssociation(
            @Context HttpHeaders headers,
            @PathParam("ProfileResourceAssociationId") String profileResourceAssociationId) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("ProfileResourceAssociation",
                    resourceAssociationNode(service.getProfileResourceAssociation(
                            region(headers), decode(profileResourceAssociationId))));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @PATCH
    @Path("/profileresourceassociation/{ProfileResourceAssociationId}")
    public Response updateProfileResourceAssociation(
            @Context HttpHeaders headers,
            @PathParam("ProfileResourceAssociationId") String profileResourceAssociationId,
            String body) {
        return handle(body, request -> {
            ProfileResourceAssociation association = service.updateProfileResourceAssociation(
                    region(headers), decode(profileResourceAssociationId), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("ProfileResourceAssociation", resourceAssociationNode(association));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/profileresourceassociation/profileid/{ProfileId}/resourcearn/{ResourceArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response disassociateResourceFromProfile(
            @Context HttpHeaders headers,
            @PathParam("ProfileId") String profileId,
            @PathParam("ResourceArn") String resourceArn) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("ProfileResourceAssociation",
                    resourceAssociationNode(service.disassociateResourceFromProfile(
                            region(headers), decode(profileId), decode(resourceArn))));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @GET
    @Path("/profileresourceassociations/profileid/{ProfileId}")
    @Consumes(MediaType.WILDCARD)
    public Response listProfileResourceAssociations(
            @Context HttpHeaders headers,
            @PathParam("ProfileId") String profileId,
            @QueryParam("resourceType") String resourceType) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("ProfileResourceAssociations");
            for (ProfileResourceAssociation association :
                    service.listProfileResourceAssociations(region(headers), decode(profileId), resourceType)) {
                items.add(resourceAssociationNode(association));
            }
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private ObjectNode profileNode(Route53Profile profile) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", profile.getId());
        node.put("Arn", profile.getArn());
        node.put("Name", profile.getName());
        if (profile.getOwnerId() != null) {
            node.put("OwnerId", profile.getOwnerId());
        }
        if (profile.getStatus() != null) {
            node.put("Status", profile.getStatus());
        }
        if (profile.getStatusMessage() != null) {
            node.put("StatusMessage", profile.getStatusMessage());
        }
        if (profile.getShareStatus() != null) {
            node.put("ShareStatus", profile.getShareStatus());
        }
        node.put("CreationTime", profile.getCreationTime());
        node.put("ModificationTime", profile.getModificationTime());
        if (profile.getClientToken() != null) {
            node.put("ClientToken", profile.getClientToken());
        }
        return node;
    }

    private ObjectNode associationNode(Route53ProfileAssociation association) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", association.getId());
        if (association.getName() != null) {
            node.put("Name", association.getName());
        }
        if (association.getOwnerId() != null) {
            node.put("OwnerId", association.getOwnerId());
        }
        node.put("ProfileId", association.getProfileId());
        node.put("ResourceId", association.getResourceId());
        if (association.getStatus() != null) {
            node.put("Status", association.getStatus());
        }
        if (association.getStatusMessage() != null) {
            node.put("StatusMessage", association.getStatusMessage());
        }
        node.put("CreationTime", association.getCreationTime());
        node.put("ModificationTime", association.getModificationTime());
        return node;
    }

    private ObjectNode resourceAssociationNode(ProfileResourceAssociation association) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", association.getId());
        if (association.getName() != null) {
            node.put("Name", association.getName());
        }
        if (association.getOwnerId() != null) {
            node.put("OwnerId", association.getOwnerId());
        }
        node.put("ProfileId", association.getProfileId());
        node.put("ResourceArn", association.getResourceArn());
        if (association.getResourceType() != null) {
            node.put("ResourceType", association.getResourceType());
        }
        if (association.getResourceProperties() != null) {
            node.put("ResourceProperties", association.getResourceProperties());
        }
        if (association.getStatus() != null) {
            node.put("Status", association.getStatus());
        }
        if (association.getStatusMessage() != null) {
            node.put("StatusMessage", association.getStatusMessage());
        }
        node.put("CreationTime", association.getCreationTime());
        node.put("ModificationTime", association.getModificationTime());
        return node;
    }

    private Response handle(String body, Handler handler) {
        try {
            return handler.handle(parse(body));
        } catch (AwsException e) {
            return error(e);
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("InvalidParameterException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterException", "Request body is not valid JSON.", 400);
        }
    }

    private Response error(AwsException exception) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", exception.jsonType());
        node.put("message", exception.getMessage());
        Map<String, Object> extended = exception.getExtendedData();
        if (extended != null) {
            for (Map.Entry<String, Object> entry : extended.entrySet()) {
                if (entry.getValue() != null) {
                    node.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(node)
                .build();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private static String decode(String value) {
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

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request);
    }
}
