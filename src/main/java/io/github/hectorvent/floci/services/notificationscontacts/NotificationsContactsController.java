package io.github.hectorvent.floci.services.notificationscontacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.notificationscontacts.model.EmailContact;
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

/**
 * AWS User Notifications Contacts (Smithy restJson1). Public paths are
 * rewritten by {@link NotificationsContactsRoutingFilter} so they do not
 * collide with S3's path-style catch-all. Tag APIs share {@code /tags/{arn}}.
 */
@Path(NotificationsContactsRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NotificationsContactsController {

    private final NotificationsContactsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public NotificationsContactsController(NotificationsContactsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/2022-09-19/emailcontacts")
    public Response createEmailContact(String body) {
        EmailContact contact = service.createEmailContact(parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", contact.getArn());
        return Response.status(201).entity(response).build();
    }

    @GET
    @Path("/emailcontacts")
    @Consumes(MediaType.WILDCARD)
    public Response listEmailContacts(
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        var page = service.listEmailContacts(maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode contacts = response.putArray("emailContacts");
        for (EmailContact contact : page.contacts()) {
            contacts.add(toEmailContact(contact));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/emailcontacts/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getEmailContact(@PathParam("arn") String arn) {
        EmailContact contact = service.getEmailContact(arn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("emailContact", toEmailContact(contact));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/emailcontacts/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteEmailContact(@PathParam("arn") String arn) {
        service.deleteEmailContact(arn);
        return Response.ok().build();
    }

    @POST
    @Path("/2022-10-31/emailcontacts/{arn: .+}/activate/send")
    @Consumes(MediaType.WILDCARD)
    public Response sendActivationCode(@PathParam("arn") String arn) {
        service.sendActivationCode(arn);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/emailcontacts/{arn: .+}/activate/{code}")
    @Consumes(MediaType.WILDCARD)
    public Response activateEmailContact(@PathParam("arn") String arn, @PathParam("code") String code) {
        service.activateEmailContact(arn, code);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode toEmailContact(EmailContact contact) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", contact.getArn());
        node.put("name", contact.getName());
        node.put("address", contact.getAddress());
        node.put("status", contact.getStatus());
        node.put("creationTime", contact.getCreationTime());
        node.put("updateTime", contact.getUpdateTime());
        return node;
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
}
