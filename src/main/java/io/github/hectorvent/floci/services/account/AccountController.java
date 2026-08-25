package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.account.model.AccountInformation;
import io.github.hectorvent.floci.services.account.model.AlternateContact;
import io.github.hectorvent.floci.services.account.model.ContactInformation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * AWS Account Management (Smithy restJson1).
 *
 * <p>Literal {@code /get*} and {@code /put*} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} catch-all. Requests are signed as {@code account}; that credential
 * scope must be catalogued or {@code AwsProtocolClaimFilter} rejects the call.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccountController {

    private final AccountService service;
    private final ObjectMapper objectMapper;

    @Inject
    public AccountController(AccountService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/getAccountInformation")
    @Consumes(MediaType.WILDCARD)
    public Response getAccountInformation(String body) {
        return handle(body, request -> {
            AccountInformation info = service.getAccountInformation(request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("AccountId", info.getAccountId());
            response.put("AccountName", info.getAccountName());
            response.put("AccountCreatedDate", info.getAccountCreatedDate());
            response.put("AccountState", info.getAccountState());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/putAccountName")
    public Response putAccountName(String body) {
        return handle(body, request -> {
            service.putAccountName(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/getAlternateContact")
    @Consumes(MediaType.WILDCARD)
    public Response getAlternateContact(String body) {
        return handle(body, request -> {
            AlternateContact contact = service.getAlternateContact(request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("AlternateContact", objectMapper.valueToTree(contact));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/putAlternateContact")
    public Response putAlternateContact(String body) {
        return handle(body, request -> {
            service.putAlternateContact(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/deleteAlternateContact")
    public Response deleteAlternateContact(String body) {
        return handle(body, request -> {
            service.deleteAlternateContact(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/getContactInformation")
    @Consumes(MediaType.WILDCARD)
    public Response getContactInformation(String body) {
        return handle(body, request -> {
            ContactInformation contact = service.getContactInformation(request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("ContactInformation", objectMapper.valueToTree(contact));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/putContactInformation")
    public Response putContactInformation(String body) {
        return handle(body, request -> {
            service.putContactInformation(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
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
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request);
    }
}
