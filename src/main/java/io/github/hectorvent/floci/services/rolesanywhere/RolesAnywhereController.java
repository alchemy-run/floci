package io.github.hectorvent.floci.services.rolesanywhere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.rolesanywhere.model.Crl;
import io.github.hectorvent.floci.services.rolesanywhere.model.Profile;
import io.github.hectorvent.floci.services.rolesanywhere.model.Subject;
import io.github.hectorvent.floci.services.rolesanywhere.model.TrustAnchor;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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

import java.util.ArrayList;
import java.util.List;

/**
 * IAM Roles Anywhere restJson1.
 *
 * <p>Public AWS paths are rewritten onto
 * {@link RolesAnywhereRoutingFilter#INTERNAL_PREFIX} so they do not collide
 * with S3's path-style catch-all.
 */
@Path(RolesAnywhereRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RolesAnywhereController {

    private final RolesAnywhereService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public RolesAnywhereController(
            RolesAnywhereService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/subjects")
    @Consumes(MediaType.WILDCARD)
    public Response listSubjects(
            @Context HttpHeaders headers,
            @QueryParam("pageSize") String pageSize,
            @QueryParam("nextToken") String nextToken) {
        RolesAnywhereService.Page<Subject> page =
                service.listSubjects(region(headers), pageSize, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode subjects = response.putArray("subjects");
        for (Subject subject : page.items()) {
            subjects.add(service.subjectSummary(subject));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/subject/{subjectId}")
    @Consumes(MediaType.WILDCARD)
    public Response getSubject(@Context HttpHeaders headers, @PathParam("subjectId") String subjectId) {
        Subject subject = service.getSubject(region(headers), subjectId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("subject", service.subjectDetail(subject));
        return Response.ok(response).build();
    }

    @POST
    @Path("/trustanchors")
    public Response createTrustAnchor(@Context HttpHeaders headers, String body) {
        TrustAnchor anchor = service.createTrustAnchor(region(headers), parse(body));
        return wrap("trustAnchor", service.trustAnchorDetail(anchor));
    }

    @GET
    @Path("/trustanchors")
    @Consumes(MediaType.WILDCARD)
    public Response listTrustAnchors(
            @Context HttpHeaders headers,
            @QueryParam("pageSize") String pageSize,
            @QueryParam("nextToken") String nextToken) {
        RolesAnywhereService.Page<TrustAnchor> page =
                service.listTrustAnchors(region(headers), pageSize, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("trustAnchors");
        for (TrustAnchor anchor : page.items()) {
            items.add(service.trustAnchorDetail(anchor));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/trustanchor/{trustAnchorId}")
    @Consumes(MediaType.WILDCARD)
    public Response getTrustAnchor(
            @Context HttpHeaders headers, @PathParam("trustAnchorId") String trustAnchorId) {
        return wrap("trustAnchor", service.trustAnchorDetail(service.getTrustAnchor(region(headers), trustAnchorId)));
    }

    @PATCH
    @Path("/trustanchor/{trustAnchorId}")
    public Response updateTrustAnchor(
            @Context HttpHeaders headers, @PathParam("trustAnchorId") String trustAnchorId, String body) {
        TrustAnchor anchor = service.updateTrustAnchor(region(headers), trustAnchorId, parse(body));
        return wrap("trustAnchor", service.trustAnchorDetail(anchor));
    }

    @DELETE
    @Path("/trustanchor/{trustAnchorId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteTrustAnchor(
            @Context HttpHeaders headers, @PathParam("trustAnchorId") String trustAnchorId) {
        TrustAnchor anchor = service.deleteTrustAnchor(region(headers), trustAnchorId);
        return wrap("trustAnchor", service.trustAnchorDetail(anchor));
    }

    @POST
    @Path("/trustanchor/{trustAnchorId}/enable")
    @Consumes(MediaType.WILDCARD)
    public Response enableTrustAnchor(
            @Context HttpHeaders headers, @PathParam("trustAnchorId") String trustAnchorId) {
        return wrap("trustAnchor",
                service.trustAnchorDetail(service.setTrustAnchorEnabled(region(headers), trustAnchorId, true)));
    }

    @POST
    @Path("/trustanchor/{trustAnchorId}/disable")
    @Consumes(MediaType.WILDCARD)
    public Response disableTrustAnchor(
            @Context HttpHeaders headers, @PathParam("trustAnchorId") String trustAnchorId) {
        return wrap("trustAnchor",
                service.trustAnchorDetail(service.setTrustAnchorEnabled(region(headers), trustAnchorId, false)));
    }

    @PATCH
    @Path("/put-notifications-settings")
    public Response putNotificationSettings(@Context HttpHeaders headers, String body) {
        return wrap("trustAnchor",
                service.trustAnchorDetail(service.putNotificationSettings(region(headers), parse(body))));
    }

    @PATCH
    @Path("/reset-notifications-settings")
    public Response resetNotificationSettings(@Context HttpHeaders headers, String body) {
        return wrap("trustAnchor",
                service.trustAnchorDetail(service.resetNotificationSettings(region(headers), parse(body))));
    }

    @POST
    @Path("/profiles")
    public Response createProfile(@Context HttpHeaders headers, String body) {
        return wrap("profile", service.profileDetail(service.createProfile(region(headers), parse(body))));
    }

    @GET
    @Path("/profiles")
    @Consumes(MediaType.WILDCARD)
    public Response listProfiles(
            @Context HttpHeaders headers,
            @QueryParam("pageSize") String pageSize,
            @QueryParam("nextToken") String nextToken) {
        RolesAnywhereService.Page<Profile> page = service.listProfiles(region(headers), pageSize, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("profiles");
        for (Profile profile : page.items()) {
            items.add(service.profileDetail(profile));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/profile/{profileId}")
    @Consumes(MediaType.WILDCARD)
    public Response getProfile(@Context HttpHeaders headers, @PathParam("profileId") String profileId) {
        return wrap("profile", service.profileDetail(service.getProfile(region(headers), profileId)));
    }

    @PATCH
    @Path("/profile/{profileId}")
    public Response updateProfile(
            @Context HttpHeaders headers, @PathParam("profileId") String profileId, String body) {
        return wrap("profile", service.profileDetail(service.updateProfile(region(headers), profileId, parse(body))));
    }

    @DELETE
    @Path("/profile/{profileId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteProfile(@Context HttpHeaders headers, @PathParam("profileId") String profileId) {
        return wrap("profile", service.profileDetail(service.deleteProfile(region(headers), profileId)));
    }

    @POST
    @Path("/profile/{profileId}/enable")
    @Consumes(MediaType.WILDCARD)
    public Response enableProfile(@Context HttpHeaders headers, @PathParam("profileId") String profileId) {
        return wrap("profile",
                service.profileDetail(service.setProfileEnabled(region(headers), profileId, true)));
    }

    @POST
    @Path("/profile/{profileId}/disable")
    @Consumes(MediaType.WILDCARD)
    public Response disableProfile(@Context HttpHeaders headers, @PathParam("profileId") String profileId) {
        return wrap("profile",
                service.profileDetail(service.setProfileEnabled(region(headers), profileId, false)));
    }

    @PUT
    @Path("/profiles/{profileId}/mappings")
    public Response putAttributeMapping(
            @Context HttpHeaders headers, @PathParam("profileId") String profileId, String body) {
        return wrap("profile",
                service.profileDetail(service.putAttributeMapping(region(headers), profileId, parse(body))));
    }

    @DELETE
    @Path("/profiles/{profileId}/mappings")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAttributeMapping(
            @Context HttpHeaders headers,
            @PathParam("profileId") String profileId,
            @QueryParam("certificateField") String certificateField,
            @QueryParam("specifiers") List<String> specifiers) {
        List<String> keys = specifiers == null ? List.of() : new ArrayList<>(specifiers);
        return wrap("profile",
                service.profileDetail(
                        service.deleteAttributeMapping(region(headers), profileId, certificateField, keys)));
    }

    @POST
    @Path("/crls")
    public Response importCrl(@Context HttpHeaders headers, String body) {
        return wrap("crl", service.crlDetail(service.importCrl(region(headers), parse(body))));
    }

    @GET
    @Path("/crls")
    @Consumes(MediaType.WILDCARD)
    public Response listCrls(
            @Context HttpHeaders headers,
            @QueryParam("pageSize") String pageSize,
            @QueryParam("nextToken") String nextToken) {
        RolesAnywhereService.Page<Crl> page = service.listCrls(region(headers), pageSize, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("crls");
        for (Crl crl : page.items()) {
            items.add(service.crlDetail(crl));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/crl/{crlId}")
    @Consumes(MediaType.WILDCARD)
    public Response getCrl(@Context HttpHeaders headers, @PathParam("crlId") String crlId) {
        return wrap("crl", service.crlDetail(service.getCrl(region(headers), crlId)));
    }

    @PATCH
    @Path("/crl/{crlId}")
    public Response updateCrl(@Context HttpHeaders headers, @PathParam("crlId") String crlId, String body) {
        return wrap("crl", service.crlDetail(service.updateCrl(region(headers), crlId, parse(body))));
    }

    @DELETE
    @Path("/crl/{crlId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteCrl(@Context HttpHeaders headers, @PathParam("crlId") String crlId) {
        return wrap("crl", service.crlDetail(service.deleteCrl(region(headers), crlId)));
    }

    @POST
    @Path("/crl/{crlId}/enable")
    @Consumes(MediaType.WILDCARD)
    public Response enableCrl(@Context HttpHeaders headers, @PathParam("crlId") String crlId) {
        return wrap("crl", service.crlDetail(service.setCrlEnabled(region(headers), crlId, true)));
    }

    @POST
    @Path("/crl/{crlId}/disable")
    @Consumes(MediaType.WILDCARD)
    public Response disableCrl(@Context HttpHeaders headers, @PathParam("crlId") String crlId) {
        return wrap("crl", service.crlDetail(service.setCrlEnabled(region(headers), crlId, false)));
    }

    @GET
    @Path("/ListTagsForResource")
    @Consumes(MediaType.WILDCARD)
    public Response listTagsForResource(
            @Context HttpHeaders headers, @QueryParam("resourceArn") String resourceArn) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("tags", service.tagArray(service.listTags(region(headers), resourceArn)));
        return Response.ok(response).build();
    }

    @POST
    @Path("/TagResource")
    public Response tagResource(@Context HttpHeaders headers, String body) {
        service.tagResource(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/UntagResource")
    public Response untagResource(@Context HttpHeaders headers, String body) {
        service.untagResource(region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw RolesAnywhereService.validation("Request body must be a JSON object.");
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw RolesAnywhereService.validation("Request body is not valid JSON.");
        }
    }

    private Response wrap(String field, ObjectNode detail) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(field, detail);
        return Response.ok(response).build();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }
}
