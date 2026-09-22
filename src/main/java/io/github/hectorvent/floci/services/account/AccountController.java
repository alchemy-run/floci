package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.account.model.AlternateContact;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccountController {
    private final AccountService accountService;
    private final ObjectMapper objectMapper;
    private final RequestContext requestContext;

    @Inject
    public AccountController(AccountService accountService, ObjectMapper objectMapper, RequestContext requestContext) {
        this.accountService = accountService;
        this.objectMapper = objectMapper;
        this.requestContext = requestContext;
    }

    @POST
    @Path("/putAlternateContact")
    public Response putAlternateContact(String body) {
        accountService.putAlternateContact(requestContext.getAccountId(), readTree(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/getAlternateContact")
    public Response getAlternateContact(String body) {
        AlternateContact contact = accountService.getAlternateContact(requestContext.getAccountId(), readTree(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("AlternateContact", objectMapper.valueToTree(contact));
        return Response.ok(response).build();
    }

    @POST
    @Path("/deleteAlternateContact")
    public Response deleteAlternateContact(String body) {
        accountService.deleteAlternateContact(requestContext.getAccountId(), readTree(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/getAccountInformation")
    public Response getAccountInformation(String body) {
        return Response.ok(objectMapper.valueToTree(
                accountService.getAccountInformation(requestContext.getAccountId(), readObject(body)))).build();
    }

    @POST
    @Path("/putAccountName")
    public Response putAccountName(String body) {
        accountService.putAccountName(requestContext.getAccountId(), readObject(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/getContactInformation")
    public Response getContactInformation(String body) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ContactInformation", objectMapper.valueToTree(
                accountService.getContactInformation(requestContext.getAccountId(), readObject(body))));
        return Response.ok(response).build();
    }

    @POST
    @Path("/putContactInformation")
    public Response putContactInformation(String body) {
        accountService.putContactInformation(requestContext.getAccountId(), readObject(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/listRegions")
    public Response listRegions(String body) {
        return Response.ok(objectMapper.valueToTree(
                accountService.listRegions(requestContext.getAccountId(), readObject(body)))).build();
    }

    @POST
    @Path("/getRegionOptStatus")
    public Response getRegionOptStatus(String body) {
        return Response.ok(objectMapper.valueToTree(
                accountService.getRegionOptStatus(requestContext.getAccountId(), readObject(body)))).build();
    }

    @POST
    @Path("/enableRegion")
    public Response enableRegion(String body) {
        accountService.rejectRegionChange(requestContext.getAccountId(), readObject(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/disableRegion")
    public Response disableRegion(String body) {
        accountService.rejectRegionChange(requestContext.getAccountId(), readObject(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private JsonNode readObject(String body) {
        JsonNode request = readTree(body);
        if (request == null || !request.isObject()) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
        return request;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
