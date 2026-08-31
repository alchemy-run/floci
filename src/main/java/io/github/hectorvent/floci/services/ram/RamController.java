package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ram.model.RamAssociation;
import io.github.hectorvent.floci.services.ram.model.RamInvitation;
import io.github.hectorvent.floci.services.ram.model.RamPermission;
import io.github.hectorvent.floci.services.ram.model.RamPermissionVersion;
import io.github.hectorvent.floci.services.ram.model.RamResourceShare;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AWS RAM restJson1 (camelCase wire names, lowercase paths).
 *
 * <p>Literal paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RamController {

    private final RamService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public RamController(RamService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/createresourceshare")
    public Response createResourceShare(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            RamResourceShare share = service.createResourceShare(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("resourceShare", shareNode(share));
            putClientToken(response, request);
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/getresourceshares")
    @Consumes(MediaType.WILDCARD)
    public Response getResourceShares(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            RamService.Page<RamResourceShare> page = service.getResourceShares(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("resourceShares");
            for (RamResourceShare share : page.items()) {
                items.add(shareNode(share));
            }
            putNextToken(response, page.nextToken());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/updateresourceshare")
    public Response updateResourceShare(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            RamResourceShare share = service.updateResourceShare(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("resourceShare", shareNode(share));
            putClientToken(response, request);
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/deleteresourceshare")
    @Consumes(MediaType.WILDCARD)
    public Response deleteResourceShare(
            @Context HttpHeaders headers,
            @QueryParam("resourceShareArn") String resourceShareArn,
            @QueryParam("clientToken") String clientToken) {
        try {
            service.deleteResourceShare(region(headers), resourceShareArn);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("returnValue", true);
            if (clientToken != null && !clientToken.isBlank()) {
                response.put("clientToken", clientToken);
            }
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @POST
    @Path("/associateresourceshare")
    public Response associateResourceShare(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            List<RamAssociation> associations = service.associateResourceShare(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("resourceShareAssociations");
            for (RamAssociation association : associations) {
                items.add(associationNode(association));
            }
            putClientToken(response, request);
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/disassociateresourceshare")
    public Response disassociateResourceShare(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            List<RamAssociation> associations = service.disassociateResourceShare(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("resourceShareAssociations");
            for (RamAssociation association : associations) {
                items.add(associationNode(association));
            }
            putClientToken(response, request);
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/getresourceshareassociations")
    @Consumes(MediaType.WILDCARD)
    public Response getResourceShareAssociations(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            RamService.Page<RamAssociation> page =
                    service.getResourceShareAssociations(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("resourceShareAssociations");
            for (RamAssociation association : page.items()) {
                items.add(associationNode(association));
            }
            putNextToken(response, page.nextToken());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/getresourceshareinvitations")
    @Consumes(MediaType.WILDCARD)
    public Response getResourceShareInvitations(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            RamService.Page<RamInvitation> page =
                    service.getResourceShareInvitations(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("resourceShareInvitations");
            for (RamInvitation invitation : page.items()) {
                items.add(invitationNode(invitation));
            }
            putNextToken(response, page.nextToken());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/acceptresourceshareinvitation")
    public Response acceptResourceShareInvitation(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            RamInvitation invitation = service.acceptInvitation(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("resourceShareInvitation", invitationNode(invitation));
            putClientToken(response, request);
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/rejectresourceshareinvitation")
    public Response rejectResourceShareInvitation(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            RamInvitation invitation = service.rejectInvitation(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("resourceShareInvitation", invitationNode(invitation));
            putClientToken(response, request);
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/listpendinginvitationresources")
    public Response listPendingInvitationResources(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            RamService.Page<RamAssociation> page =
                    service.listPendingInvitationResources(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("resources");
            for (RamAssociation association : page.items()) {
                items.add(resourceNode(association));
            }
            putNextToken(response, page.nextToken());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/listresources")
    @Consumes(MediaType.WILDCARD)
    public Response listResources(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            RamService.Page<RamAssociation> page = service.listResources(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("resources");
            for (RamAssociation association : page.items()) {
                items.add(resourceNode(association));
            }
            putNextToken(response, page.nextToken());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/listprincipals")
    @Consumes(MediaType.WILDCARD)
    public Response listPrincipals(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            RamService.Page<RamAssociation> page = service.listPrincipals(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("principals");
            for (RamAssociation association : page.items()) {
                items.add(principalNode(association));
            }
            putNextToken(response, page.nextToken());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/getresourcepolicies")
    public Response getResourcePolicies(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            List<String> policies = service.getResourcePolicies(request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("policies");
            for (String policy : policies) {
                items.add(policy);
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/createpermission")
    public Response createPermission(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        RamPermission permission = service.createPermission(region(headers), request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("permission", summaryNode(permission, service.defaultVersion(permission), false));
        echoClientToken(request, response);
        return Response.ok(response).build();
    }

    @POST
    @Path("/getpermission")
    public Response getPermission(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        RamPermission permission = service.getPermission(region(headers), request);
        Integer requested = request.hasNonNull("permissionVersion")
                ? request.get("permissionVersion").asInt()
                : null;
        RamPermissionVersion version = requested == null
                ? service.defaultVersion(permission)
                : service.requireVersion(permission, requested);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("permission", summaryNode(permission, version, true));
        return Response.ok(response).build();
    }

    @POST
    @Path("/listpermissions")
    @Consumes(MediaType.WILDCARD)
    public Response listPermissions(@Context HttpHeaders headers, String body) {
        RamService.Page<RamPermission> page = service.listPermissions(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode permissions = response.putArray("permissions");
        for (RamPermission permission : page.items()) {
            permissions.add(summaryNode(permission, service.defaultVersion(permission), false));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/listpermissionversions")
    public Response listPermissionVersions(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        RamPermission permission = service.getPermission(region(headers), request);
        RamService.Page<RamPermissionVersion> page = service.listPermissionVersions(region(headers), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode permissions = response.putArray("permissions");
        for (RamPermissionVersion version : page.items()) {
            permissions.add(summaryNode(permission, version, false));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/createpermissionversion")
    public Response createPermissionVersion(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        RamPermission permission = service.createPermissionVersion(region(headers), request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("permission", summaryNode(permission, service.defaultVersion(permission), true));
        echoClientToken(request, response);
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/deletepermissionversion")
    @Consumes(MediaType.WILDCARD)
    public Response deletePermissionVersion(
            @Context HttpHeaders headers,
            @QueryParam("permissionArn") String permissionArn,
            @QueryParam("permissionVersion") Integer permissionVersion,
            @QueryParam("clientToken") String clientToken) {
        RamPermission permission = service.deletePermissionVersion(
                region(headers), permissionArn, permissionVersion);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("returnValue", true);
        response.put("permissionStatus", service.defaultVersion(permission).getStatus());
        if (clientToken != null && !clientToken.isBlank()) {
            response.put("clientToken", clientToken);
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/deletepermission")
    @Consumes(MediaType.WILDCARD)
    public Response deletePermission(
            @Context HttpHeaders headers,
            @QueryParam("permissionArn") String permissionArn,
            @QueryParam("clientToken") String clientToken) {
        service.deletePermission(region(headers), permissionArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("returnValue", true);
        response.put("permissionStatus", RamService.STATUS_DELETED);
        if (clientToken != null && !clientToken.isBlank()) {
            response.put("clientToken", clientToken);
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/tagresource")
    public Response tagResource(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.tagResource(region(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/untagresource")
    public Response untagResource(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.untagResource(region(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    private ObjectNode shareNode(RamResourceShare share) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceShareArn", share.getArn());
        node.put("name", share.getName());
        node.put("owningAccountId", share.getOwningAccountId());
        node.put("allowExternalPrincipals", share.isAllowExternalPrincipals());
        node.put("status", share.getStatus());
        if (share.getStatusMessage() != null) {
            node.put("statusMessage", share.getStatusMessage());
        }
        node.put("featureSet", share.getFeatureSet());
        node.put("creationTime", share.getCreationTime());
        node.put("lastUpdatedTime", share.getLastUpdatedTime());
        node.set("tags", tagsNode(share.getTags()));
        return node;
    }

    private ObjectNode associationNode(RamAssociation association) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceShareArn", association.getResourceShareArn());
        node.put("resourceShareName", association.getResourceShareName());
        node.put("associatedEntity", association.getAssociatedEntity());
        node.put("associationType", association.getAssociationType());
        node.put("status", association.getStatus());
        if (association.getStatusMessage() != null) {
            node.put("statusMessage", association.getStatusMessage());
        }
        node.put("creationTime", association.getCreationTime());
        node.put("lastUpdatedTime", association.getLastUpdatedTime());
        node.put("external", association.isExternal());
        return node;
    }

    private ObjectNode invitationNode(RamInvitation invitation) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceShareInvitationArn", invitation.getArn());
        node.put("resourceShareArn", invitation.getResourceShareArn());
        node.put("resourceShareName", invitation.getResourceShareName());
        node.put("senderAccountId", invitation.getSenderAccountId());
        node.put("receiverAccountId", invitation.getReceiverAccountId());
        node.put("invitationTimestamp", invitation.getInvitationTimestamp());
        node.put("status", invitation.getStatus());
        return node;
    }

    private ObjectNode resourceNode(RamAssociation association) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", association.getAssociatedEntity());
        if (association.getResourceType() != null) {
            node.put("type", association.getResourceType());
        }
        node.put("resourceShareArn", association.getResourceShareArn());
        node.put("status", "AVAILABLE");
        node.put("creationTime", association.getCreationTime());
        node.put("lastUpdatedTime", association.getLastUpdatedTime());
        node.put("resourceRegionScope", "REGIONAL");
        return node;
    }

    private ObjectNode principalNode(RamAssociation association) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", association.getAssociatedEntity());
        node.put("resourceShareArn", association.getResourceShareArn());
        node.put("creationTime", association.getCreationTime());
        node.put("lastUpdatedTime", association.getLastUpdatedTime());
        node.put("external", association.isExternal());
        return node;
    }

    private ObjectNode summaryNode(RamPermission permission, RamPermissionVersion version, boolean includePolicy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", permission.getArn());
        node.put("version", String.valueOf(version.getVersion()));
        node.put("defaultVersion", version.getVersion() == permission.getDefaultVersion());
        node.put("name", permission.getName());
        node.put("resourceType", permission.getResourceType());
        if (includePolicy && version.getPolicyTemplate() != null) {
            node.put("permission", version.getPolicyTemplate());
        }
        node.put("creationTime", version.getCreationTime());
        node.put("lastUpdatedTime", version.getLastUpdatedTime());
        node.put("isResourceTypeDefault", false);
        if (permission.getPermissionType() != null) {
            node.put("permissionType", permission.getPermissionType());
        }
        if (permission.getFeatureSet() != null) {
            node.put("featureSet", permission.getFeatureSet());
        }
        if (version.getStatus() != null) {
            node.put("status", version.getStatus());
        }
        ArrayNode tags = node.putArray("tags");
        for (Map.Entry<String, String> tag : permission.getTags().entrySet()) {
            ObjectNode item = tags.addObject();
            item.put("key", tag.getKey());
            item.put("value", tag.getValue());
        }
        return node;
    }

    private ArrayNode tagsNode(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        if (tags != null) {
            tags.forEach((key, value) -> {
                ObjectNode tag = array.addObject();
                tag.put("key", key);
                tag.put("value", value);
            });
        }
        return array;
    }

    private void putClientToken(ObjectNode response, JsonNode request) {
        String token = request != null && request.has("clientToken") && request.get("clientToken").isTextual()
                ? request.get("clientToken").asText()
                : UUID.randomUUID().toString();
        response.put("clientToken", token);
    }

    private static void echoClientToken(JsonNode request, ObjectNode response) {
        if (request != null && request.hasNonNull("clientToken") && request.get("clientToken").isTextual()) {
            response.put("clientToken", request.get("clientToken").textValue());
        }
    }

    private static void putNextToken(ObjectNode response, String nextToken) {
        if (nextToken != null) {
            response.put("nextToken", nextToken);
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
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
        node.put("Message", exception.getMessage());
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(node)
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request);
    }
}
