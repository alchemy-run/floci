package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.ram.model.PrincipalAssociation;
import io.github.hectorvent.floci.services.ram.model.RamPermission;
import io.github.hectorvent.floci.services.ram.model.RamPermission.PermissionVersion;
import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import io.github.hectorvent.floci.services.ram.model.ResourceShareInvitation;
import io.github.hectorvent.floci.services.ram.model.ShareAssociation;
import io.github.hectorvent.floci.services.ram.model.SharedResource;
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

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS Resource Access Manager (Smithy restJson1).
 *
 * <p>Serves the RAM calls AWS Landing Zone Accelerator makes: the organization-sharing opt-in
 * plus the share reads of the Custom::GetResourceShare / Custom::GetResourceShareItem Lambdas.
 * The literal paths take JAX-RS precedence over S3's {@code /{bucket}} template route, so no
 * extra routing wiring is needed, but any RAM path missing here falls through to S3 and
 * produces an XML error a restJson1 client cannot parse.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RamController {

    private final RamService service;
    private final ObjectMapper objectMapper;
    private final ObjectReader requestReader;
    private final RegionResolver regionResolver;
    private final OrganizationsService organizationsService;
    private final IamService iamService;

    @Inject
    public RamController(RamService service, ObjectMapper objectMapper, RegionResolver regionResolver,
                         OrganizationsService organizationsService, IamService iamService) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.requestReader = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.regionResolver = regionResolver;
        this.organizationsService = organizationsService;
        this.iamService = iamService;
    }

    @POST
    @Path("/enablesharingwithawsorganization")
    @Consumes(MediaType.WILDCARD)
    public Response enableSharingWithAwsOrganization() {
        String callerAccountId = regionResolver.getAccountId();
        organizationsService.enableAWSServiceAccess(callerAccountId, "ram.amazonaws.com");
        if (iamService.findRole(callerAccountId, "AWSServiceRoleForResourceAccessManager").isEmpty()) {
            iamService.createServiceLinkedRole("ram.amazonaws.com", null,
                    "Allows AWS Resource Access Manager to access AWS Organizations on your behalf.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("returnValue", service.enableSharingWithAwsOrganization(regionResolver.getAccountId()));
        return Response.ok(response).build();
    }

    @POST
    @Path("/createresourceshare")
    @Consumes(MediaType.WILDCARD)
    public Response createResourceShare(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        String name = request.path("name").asText();
        // The model defaults allowExternalPrincipals to true when omitted.
        boolean allowExternalPrincipals = request.path("allowExternalPrincipals").asBoolean(true);
        ResourceShare share = service.createResourceShare(
                name,
                stringList(request.path("principals")),
                stringList(request.path("resourceArns")),
                stringList(request.path("sources")),
                stringList(request.path("permissionArns")),
                tagMap(request.path("tags")),
                allowExternalPrincipals,
                regionResolver.resolveRegion(headers),
                regionResolver.getAccountId());

        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourceShare", shareNode(share));
        echoClientToken(response, request);
        return Response.ok(response).build();
    }

    @POST
    @Path("/getresourceshares")
    @Consumes(MediaType.WILDCARD)
    public Response getResourceShares(String body) {
        JsonNode request = readTree(body);
        String resourceOwner = request.hasNonNull("resourceOwner")
                ? request.path("resourceOwner").asText() : null;
        List<ResourceShare> shares = service.getResourceShares(
                regionResolver.getAccountId(),
                resourceOwner,
                request.hasNonNull("name") ? request.path("name").asText() : null,
                stringList(request.path("resourceShareArns")),
                request.hasNonNull("resourceShareStatus")
                        ? request.path("resourceShareStatus").asText() : null);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = objectMapper.createArrayNode();
        shares.forEach(share -> array.add(shareNode(share)));
        response.set("resourceShares", array);
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/deleteresourceshare")
    @Consumes(MediaType.WILDCARD)
    public Response deleteResourceShare(@QueryParam("resourceShareArn") String resourceShareArn,
                                        @QueryParam("clientToken") String clientToken) {
        service.deleteResourceShare(resourceShareArn, regionResolver.getAccountId());

        ObjectNode response = objectMapper.createObjectNode();
        response.put("returnValue", true);
        // The response models clientToken; AWS echoes what the caller sent so a retry can be
        // correlated with the original request.
        if (clientToken != null) {
            response.put("clientToken", clientToken);
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/updateresourceshare")
    @Consumes(MediaType.WILDCARD)
    public Response updateResourceShare(String body) {
        JsonNode request = readTree(body);
        ResourceShare updated = service.updateResourceShare(
                request.path("resourceShareArn").asText(),
                request.hasNonNull("name") ? request.path("name").asText() : null,
                request.hasNonNull("allowExternalPrincipals")
                        ? request.path("allowExternalPrincipals").asBoolean() : null,
                regionResolver.getAccountId());

        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourceShare", shareNode(updated));
        return Response.ok(response).build();
    }

    @POST
    @Path("/associateresourceshare")
    @Consumes(MediaType.WILDCARD)
    public Response associateResourceShare(String body) {
        JsonNode request = readTree(body);
        String resourceShareArn = request.path("resourceShareArn").asText();
        List<String> resourceArns = stringList(request.path("resourceArns"));
        List<String> principals = stringList(request.path("principals"));
        List<String> sources = stringList(request.path("sources"));
        ResourceShare updated = service.associateResourceShare(
                resourceShareArn, resourceArns, principals, sources, regionResolver.getAccountId());

        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourceShareAssociations",
                associationArray(updated, resourceArns, principals, sources, "ASSOCIATED"));
        echoClientToken(response, request);
        return Response.ok(response).build();
    }

    @POST
    @Path("/disassociateresourceshare")
    @Consumes(MediaType.WILDCARD)
    public Response disassociateResourceShare(String body) {
        JsonNode request = readTree(body);
        String resourceShareArn = request.path("resourceShareArn").asText();
        List<String> resourceArns = stringList(request.path("resourceArns"));
        List<String> principals = stringList(request.path("principals"));
        List<String> sources = stringList(request.path("sources"));
        ResourceShare updated = service.disassociateResourceShare(
                resourceShareArn, resourceArns, principals, sources, regionResolver.getAccountId());

        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourceShareAssociations",
                associationArray(updated, resourceArns, principals, sources, "DISASSOCIATED"));
        echoClientToken(response, request);
        return Response.ok(response).build();
    }

    @POST
    @Path("/getresourceshareassociations")
    @Consumes(MediaType.WILDCARD)
    public Response getResourceShareAssociations(String body) {
        JsonNode request = readTree(body);
        List<ShareAssociation> associations = service.getResourceShareAssociations(
                regionResolver.getAccountId(),
                optionalText(request, "associationType"),
                stringList(request.path("resourceShareArns")),
                optionalText(request, "resourceArn"),
                optionalText(request, "principal"),
                optionalText(request, "associationStatus"));
        RamService.Page<ShareAssociation> page = RamService.paginate(
                associations, optionalInt(request, "maxResults"), optionalText(request, "nextToken"));

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("resourceShareAssociations");
        for (ShareAssociation association : page.items()) {
            ObjectNode node = array.addObject();
            node.put("resourceShareArn", association.resourceShareArn());
            node.put("resourceShareName", association.resourceShareName());
            node.put("associatedEntity", association.associatedEntity());
            node.put("associationType", association.associationType());
            node.put("status", association.status());
            node.put("creationTime", epochSeconds(association.creationTime()));
            node.put("lastUpdatedTime", epochSeconds(association.lastUpdatedTime()));
            node.put("external", association.external());
        }
        putNextToken(response, page);
        return Response.ok(response).build();
    }

    @POST
    @Path("/listpendinginvitationresources")
    @Consumes(MediaType.WILDCARD)
    public Response listPendingInvitationResources(String body) {
        JsonNode request = readTree(body);
        List<SharedResource> resources = service.listPendingInvitationResources(
                regionResolver.getAccountId(),
                optionalText(request, "resourceShareInvitationArn"),
                optionalText(request, "resourceRegionScope"));
        RamService.Page<SharedResource> page = RamService.paginate(
                resources, optionalInt(request, "maxResults"), optionalText(request, "nextToken"));

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("resources");
        page.items().forEach(resource -> array.add(resourceNode(resource)));
        putNextToken(response, page);
        return Response.ok(response).build();
    }

    @POST
    @Path("/getresourcepolicies")
    @Consumes(MediaType.WILDCARD)
    public Response getResourcePolicies(String body) {
        JsonNode request = readTree(body);
        List<String> policies = service.getResourcePolicies(
                regionResolver.getAccountId(),
                stringList(request.path("resourceArns")),
                optionalText(request, "principal"));
        RamService.Page<String> page = RamService.paginate(
                policies, optionalInt(request, "maxResults"), optionalText(request, "nextToken"));

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("policies");
        page.items().forEach(array::add);
        putNextToken(response, page);
        return Response.ok(response).build();
    }

    @POST
    @Path("/listpermissions")
    @Consumes(MediaType.WILDCARD)
    public Response listPermissions(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        List<RamPermission> permissions = service.listPermissions(
                regionResolver.getAccountId(),
                regionResolver.resolveRegion(headers),
                optionalText(request, "resourceType"),
                optionalText(request, "permissionType"));
        RamService.Page<RamPermission> page = RamService.paginate(
                permissions, optionalInt(request, "maxResults"), optionalText(request, "nextToken"));

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("permissions");
        page.items().forEach(p -> array.add(permissionSummary(p, p.defaultVersionEntry())));
        putNextToken(response, page);
        return Response.ok(response).build();
    }

    @POST
    @Path("/getpermission")
    @Consumes(MediaType.WILDCARD)
    public Response getPermission(String body) {
        JsonNode request = readTree(body);
        Map.Entry<RamPermission, PermissionVersion> permission = service.getPermission(
                regionResolver.getAccountId(),
                optionalText(request, "permissionArn"),
                optionalInt(request, "permissionVersion"));

        ObjectNode response = objectMapper.createObjectNode();
        response.set("permission", permissionDetail(permission.getKey(), permission.getValue()));
        return Response.ok(response).build();
    }

    @POST
    @Path("/listpermissionversions")
    @Consumes(MediaType.WILDCARD)
    public Response listPermissionVersions(String body) {
        JsonNode request = readTree(body);
        String permissionArn = optionalText(request, "permissionArn");
        String callerAccountId = regionResolver.getAccountId();
        List<PermissionVersion> versions = service.listPermissionVersions(callerAccountId, permissionArn);
        RamPermission permission = service.getPermission(callerAccountId, permissionArn, null).getKey();
        RamService.Page<PermissionVersion> page = RamService.paginate(
                versions, optionalInt(request, "maxResults"), optionalText(request, "nextToken"));

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("permissions");
        page.items().forEach(version -> array.add(permissionSummary(permission, version)));
        putNextToken(response, page);
        return Response.ok(response).build();
    }

    @POST
    @Path("/createpermission")
    @Consumes(MediaType.WILDCARD)
    public Response createPermission(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        RamPermission permission = service.createPermission(
                regionResolver.getAccountId(),
                regionResolver.resolveRegion(headers),
                optionalText(request, "name"),
                optionalText(request, "resourceType"),
                optionalText(request, "policyTemplate"),
                tagMap(request.path("tags")));

        ObjectNode response = objectMapper.createObjectNode();
        response.set("permission", permissionSummary(permission, permission.defaultVersionEntry()));
        echoClientToken(response, request);
        return Response.ok(response).build();
    }

    @POST
    @Path("/createpermissionversion")
    @Consumes(MediaType.WILDCARD)
    public Response createPermissionVersion(String body) {
        JsonNode request = readTree(body);
        RamPermission permission = service.createPermissionVersion(
                regionResolver.getAccountId(),
                optionalText(request, "permissionArn"),
                optionalText(request, "policyTemplate"));

        ObjectNode response = objectMapper.createObjectNode();
        response.set("permission", permissionDetail(permission, permission.defaultVersionEntry()));
        echoClientToken(response, request);
        return Response.ok(response).build();
    }

    @POST
    @Path("/setdefaultpermissionversion")
    @Consumes(MediaType.WILDCARD)
    public Response setDefaultPermissionVersion(String body) {
        JsonNode request = readTree(body);
        service.setDefaultPermissionVersion(
                regionResolver.getAccountId(),
                optionalText(request, "permissionArn"),
                requiredVersion(optionalInt(request, "permissionVersion")));

        ObjectNode response = objectMapper.createObjectNode();
        response.put("returnValue", true);
        echoClientToken(response, request);
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/deletepermissionversion")
    @Consumes(MediaType.WILDCARD)
    public Response deletePermissionVersion(@QueryParam("permissionArn") String permissionArn,
                                            @QueryParam("permissionVersion") String permissionVersion,
                                            @QueryParam("clientToken") String clientToken) {
        RamPermission permission = service.deletePermissionVersion(
                regionResolver.getAccountId(), permissionArn, requiredVersion(parseVersion(permissionVersion)));

        ObjectNode response = objectMapper.createObjectNode();
        response.put("returnValue", true);
        response.put("permissionStatus", permission.statusOf(permission.defaultVersionEntry()));
        if (clientToken != null) {
            response.put("clientToken", clientToken);
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/deletepermission")
    @Consumes(MediaType.WILDCARD)
    public Response deletePermission(@QueryParam("permissionArn") String permissionArn,
                                     @QueryParam("clientToken") String clientToken) {
        service.deletePermission(regionResolver.getAccountId(), permissionArn);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("returnValue", true);
        // Floci deletes synchronously, so the permission is already gone rather than DELETING.
        response.put("permissionStatus", "DELETED");
        if (clientToken != null) {
            response.put("clientToken", clientToken);
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/listprincipals")
    @Consumes(MediaType.WILDCARD)
    public Response listPrincipals(String body) {
        JsonNode request = readTree(body);
        String resourceOwner = request.hasNonNull("resourceOwner")
                ? request.path("resourceOwner").asText() : null;
        List<PrincipalAssociation> principals = service.listPrincipals(
                regionResolver.getAccountId(), resourceOwner, stringList(request.path("resourceShareArns")));

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = objectMapper.createArrayNode();
        for (PrincipalAssociation principal : principals) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", principal.id());
            node.put("resourceShareArn", principal.resourceShareArn());
            node.put("creationTime", principal.creationTime().toEpochMilli() / 1000.0);
            node.put("lastUpdatedTime", principal.lastUpdatedTime().toEpochMilli() / 1000.0);
            node.put("external", principal.external());
            array.add(node);
        }
        response.set("principals", array);
        return Response.ok(response).build();
    }

    @POST
    @Path("/tagresource")
    @Consumes(MediaType.WILDCARD)
    public Response tagResource(String body) {
        JsonNode request = readTree(body);
        Map<String, String> tags = tagMap(request.path("tags"));
        String resourceArn = optionalText(request, "resourceArn");
        if (resourceArn != null) {
            requireSingleTagTarget(request);
            service.tagPermission(resourceArn, tags, regionResolver.getAccountId());
        } else {
            service.tagResource(request.path("resourceShareArn").asText(), tags,
                    regionResolver.getAccountId());
        }
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/untagresource")
    @Consumes(MediaType.WILDCARD)
    public Response untagResource(String body) {
        JsonNode request = readTree(body);
        List<String> tagKeys = stringList(request.path("tagKeys"));
        String resourceArn = optionalText(request, "resourceArn");
        if (resourceArn != null) {
            requireSingleTagTarget(request);
            service.untagPermission(resourceArn, tagKeys, regionResolver.getAccountId());
        } else {
            service.untagResource(request.path("resourceShareArn").asText(), tagKeys,
                    regionResolver.getAccountId());
        }
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    /** Tag/UntagResource address exactly one of resourceShareArn and resourceArn. */
    private static void requireSingleTagTarget(JsonNode request) {
        if (request.hasNonNull("resourceShareArn")) {
            throw new AwsException("InvalidParameterException",
                    "Specify either resourceShareArn or resourceArn, not both.", 400);
        }
    }

    @POST
    @Path("/getresourceshareinvitations")
    @Consumes(MediaType.WILDCARD)
    public Response getResourceShareInvitations(String body) {
        JsonNode request = readTree(body);
        List<ResourceShareInvitation> invitations = service.getResourceShareInvitations(
                regionResolver.getAccountId(),
                stringList(request.path("resourceShareArns")),
                stringList(request.path("resourceShareInvitationArns")));

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = objectMapper.createArrayNode();
        invitations.forEach(invitation -> array.add(invitationNode(invitation)));
        response.set("resourceShareInvitations", array);
        return Response.ok(response).build();
    }

    @POST
    @Path("/acceptresourceshareinvitation")
    @Consumes(MediaType.WILDCARD)
    public Response acceptResourceShareInvitation(String body) {
        JsonNode request = readTree(body);
        String clientToken = request.hasNonNull("clientToken") ? request.path("clientToken").asText() : null;
        ResourceShareInvitation invitation = service.acceptResourceShareInvitation(
                request.path("resourceShareInvitationArn").asText(), regionResolver.getAccountId());
        return invitationResponse(invitation, clientToken);
    }

    @POST
    @Path("/rejectresourceshareinvitation")
    @Consumes(MediaType.WILDCARD)
    public Response rejectResourceShareInvitation(String body) {
        JsonNode request = readTree(body);
        String clientToken = request.hasNonNull("clientToken") ? request.path("clientToken").asText() : null;
        ResourceShareInvitation invitation = service.rejectResourceShareInvitation(
                request.path("resourceShareInvitationArn").asText(), regionResolver.getAccountId());
        return invitationResponse(invitation, clientToken);
    }

    private Response invitationResponse(ResourceShareInvitation invitation, String clientToken) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourceShareInvitation", invitationNode(invitation));
        // Echoes what the caller sent (or, per AWS, a generated one if it sent none); floci
        // has no need to invent one when absent since nothing here retries on it.
        if (clientToken != null) {
            response.put("clientToken", clientToken);
        }
        return Response.ok(response).build();
    }

    private ObjectNode invitationNode(ResourceShareInvitation invitation) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceShareInvitationArn", invitation.resourceShareInvitationArn());
        node.put("resourceShareArn", invitation.resourceShareArn());
        node.put("resourceShareName", invitation.resourceShareName());
        node.put("senderAccountId", invitation.senderAccountId());
        node.put("receiverAccountId", invitation.receiverAccountId());
        node.put("invitationTimestamp", invitation.invitationTimestamp().toEpochMilli() / 1000.0);
        node.put("status", invitation.status());
        return node;
    }

    @POST
    @Path("/listresources")
    @Consumes(MediaType.WILDCARD)
    public Response listResources(String body) {
        JsonNode request = readTree(body);
        String resourceOwner = request.hasNonNull("resourceOwner")
                ? request.path("resourceOwner").asText() : null;
        List<SharedResource> resources = service.listResources(
                regionResolver.getAccountId(), resourceOwner, stringList(request.path("resourceShareArns")));

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = objectMapper.createArrayNode();
        resources.forEach(resource -> array.add(resourceNode(resource)));
        response.set("resources", array);
        return Response.ok(response).build();
    }

    private ObjectNode resourceNode(SharedResource resource) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", resource.arn());
        node.put("type", resource.type());
        node.put("resourceShareArn", resource.resourceShareArn());
        node.put("status", resource.status());
        return node;
    }

    private ObjectNode permissionSummary(RamPermission permission, PermissionVersion version) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", permission.arn());
        node.put("version", Integer.toString(version.version()));
        node.put("defaultVersion", version.version() == permission.defaultVersion());
        node.put("name", permission.name());
        node.put("resourceType", permission.resourceType());
        node.put("status", permission.statusOf(version));
        node.put("creationTime", epochSeconds(version.creationTime()));
        node.put("lastUpdatedTime", epochSeconds(version.lastUpdatedTime()));
        node.put("isResourceTypeDefault", permission.resourceTypeDefault());
        node.put("permissionType", permission.permissionType());
        node.put("featureSet", "STANDARD");
        node.set("tags", tagArray(permission.tags()));
        return node;
    }

    private ObjectNode permissionDetail(RamPermission permission, PermissionVersion version) {
        ObjectNode node = permissionSummary(permission, version);
        node.put("permission", version.policyTemplate());
        return node;
    }

    private ArrayNode tagArray(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        tags.forEach((key, value) -> {
            ObjectNode tag = array.addObject();
            tag.put("key", key);
            tag.put("value", value);
        });
        return array;
    }

    private static Map<String, String> tagMap(JsonNode tags) {
        Map<String, String> result = new LinkedHashMap<>();
        tags.forEach(tag -> result.put(tag.path("key").asText(), tag.path("value").asText()));
        return result;
    }

    private static String optionalText(JsonNode request, String field) {
        return request.hasNonNull(field) ? request.path(field).asText() : null;
    }

    private static Integer optionalInt(JsonNode request, String field) {
        if (!request.hasNonNull(field)) {
            return null;
        }
        JsonNode value = request.path(field);
        if (!value.canConvertToInt()) {
            throw new AwsException("InvalidParameterException", field + " must be an integer.", 400);
        }
        return value.asInt();
    }

    private static Integer parseVersion(String permissionVersion) {
        if (permissionVersion == null || permissionVersion.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(permissionVersion);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterException", "permissionVersion must be an integer.", 400);
        }
    }

    private static int requiredVersion(Integer permissionVersion) {
        if (permissionVersion == null) {
            throw new AwsException("InvalidParameterException", "permissionVersion is required.", 400);
        }
        return permissionVersion;
    }

    private static double epochSeconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    private static void putNextToken(ObjectNode response, RamService.Page<?> page) {
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
    }

    private static void echoClientToken(ObjectNode response, JsonNode request) {
        if (request.hasNonNull("clientToken")) {
            response.put("clientToken", request.path("clientToken").asText());
        }
    }

    private ObjectNode shareNode(ResourceShare share) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceShareArn", share.getResourceShareArn());
        node.put("name", share.getName());
        node.put("owningAccountId", share.getOwningAccountId());
        node.put("allowExternalPrincipals", share.isAllowExternalPrincipals());
        node.put("status", share.getStatus());
        // Shares created through the API are STANDARD; CREATED_FROM_POLICY covers shares RAM
        // derives from a resource policy, which floci has no path to produce.
        node.put("featureSet", "STANDARD");
        node.put("creationTime", share.getCreationTime().toEpochMilli() / 1000.0);
        node.put("lastUpdatedTime", share.getLastUpdatedTime().toEpochMilli() / 1000.0);
        node.set("tags", tagArray(share.getTags()));
        return node;
    }

    /** One row per associated/disassociated resourceArn, principal, and source, per the real RAM shape. */
    private ArrayNode associationArray(ResourceShare share, List<String> resourceArns,
                                       List<String> principals, List<String> sources, String status) {
        ArrayNode array = objectMapper.createArrayNode();
        double now = share.getLastUpdatedTime().toEpochMilli() / 1000.0;
        for (String resourceArn : resourceArns) {
            array.add(associationNode(share, resourceArn, "RESOURCE", status, now, false));
        }
        for (String principal : principals) {
            array.add(associationNode(share, principal, "PRINCIPAL", status, now,
                    service.isExternalPrincipal(share.getOwningAccountId(), principal)));
        }
        for (String source : sources) {
            array.add(associationNode(share, source, "SOURCE", status, now, false));
        }
        return array;
    }

    private ObjectNode associationNode(ResourceShare share, String associatedEntity,
                                       String associationType, String status, double time, boolean external) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceShareArn", share.getResourceShareArn());
        node.put("resourceShareName", share.getName());
        node.put("associatedEntity", associatedEntity);
        node.put("associationType", associationType);
        node.put("status", status);
        node.put("creationTime", time);
        node.put("lastUpdatedTime", time);
        node.put("external", external);
        return node;
    }

    /**
     * A body that is not valid JSON is a client error: restJson1 rejects it with 400
     * SerializationException. Left as an UncheckedIOException it escapes to Quarkus'
     * generic handler and the SDK sees a 500 InternalFailure instead.
     */
    private JsonNode readTree(String body) {
        try {
            return (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    // Strict: Jackson's default stops at the first complete value, so
                    // "{} not-json" would parse as an empty object and the request would run.
                    : requestReader.readTree(body);
        } catch (IOException e) {
            throw new AwsException("SerializationException",
                    "The request could not be parsed as valid JSON.", 400);
        }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(n -> values.add(n.asText()));
        return values;
    }
}
