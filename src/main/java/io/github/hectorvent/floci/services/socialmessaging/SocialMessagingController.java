package io.github.hectorvent.floci.services.socialmessaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * AWS End User Messaging Social restJson1. Public AWS paths are under
 * {@code /v1/whatsapp} and {@code /v1/tags}; {@link SocialMessagingRoutingFilter}
 * prefixes them so they do not collide with S3 path-style routes. Requests are
 * signed as {@code social-messaging}.
 */
@Path(SocialMessagingRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SocialMessagingController {

    private final SocialMessagingService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public SocialMessagingController(
            SocialMessagingService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/v1/whatsapp/signup")
    public Response associateWhatsAppBusinessAccount(@Context HttpHeaders headers, String body) {
        return Response.ok(service.associateAccount(region(headers), parse(body))).build();
    }

    @GET
    @Path("/v1/whatsapp/waba/list")
    @Consumes(MediaType.WILDCARD)
    public Response listLinkedWhatsAppBusinessAccounts() {
        return Response.ok(service.listAccounts()).build();
    }

    @GET
    @Path("/v1/whatsapp/waba/details")
    @Consumes(MediaType.WILDCARD)
    public Response getLinkedWhatsAppBusinessAccount(@QueryParam("id") String id) {
        return Response.ok(service.getAccount(id)).build();
    }

    @DELETE
    @Path("/v1/whatsapp/waba/disassociate")
    @Consumes(MediaType.WILDCARD)
    public Response disassociateWhatsAppBusinessAccount(@QueryParam("id") String id) {
        return Response.ok(service.disassociateAccount(id)).build();
    }

    @PUT
    @Path("/v1/whatsapp/waba/eventdestinations")
    public Response putWhatsAppBusinessAccountEventDestinations(String body) {
        return Response.ok(service.putEventDestinations(parse(body))).build();
    }

    @GET
    @Path("/v1/tags/list")
    @Consumes(MediaType.WILDCARD)
    public Response listTagsForResource(@QueryParam("resourceArn") String resourceArn) {
        return Response.ok(service.listTags(resourceArn)).build();
    }

    @POST
    @Path("/v1/tags/tag-resource")
    public Response tagResource(String body) {
        return Response.ok(service.tagResource(parse(body))).build();
    }

    @GET
    @Path("/v1/whatsapp/template/list")
    @Consumes(MediaType.WILDCARD)
    public Response listWhatsAppMessageTemplates(@QueryParam("id") String id) {
        return Response.ok(service.listTemplates(id)).build();
    }

    @GET
    @Path("/v1/whatsapp/flow/list")
    @Consumes(MediaType.WILDCARD)
    public Response listWhatsAppFlows(@QueryParam("id") String id) {
        return Response.ok(service.listFlows(id)).build();
    }

    @GET
    @Path("/v1/whatsapp/waba/phone/details")
    @Consumes(MediaType.WILDCARD)
    public Response getLinkedWhatsAppBusinessAccountPhoneNumber(@QueryParam("id") String id) {
        return Response.ok(service.getPhoneNumber(id)).build();
    }

    @POST
    @Path("/v1/whatsapp/send")
    public Response sendWhatsAppMessage(String body) {
        return Response.ok(service.sendMessage(parse(body))).build();
    }

    @POST
    @Path("/v1/whatsapp/media/get")
    public Response getWhatsAppMessageMedia(String body) {
        return Response.ok(service.getMessageMedia(parse(body))).build();
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
                throw new AwsException("InvalidParametersException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParametersException", "Request body is not valid JSON.", 400);
        }
    }
}
