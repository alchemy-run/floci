package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * restJson1 data plane for AWS Payment Cryptography Data
 * ({@code POST /keys/{id}/encrypt}, {@code /mac/generate}, {@code /pindata/*}, …).
 */
@Path("/")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PaymentCryptographyDataController {

    private final PaymentCryptographyService service;
    private final ObjectMapper objectMapper;

    @Inject
    public PaymentCryptographyDataController(PaymentCryptographyService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/keys/{keyIdentifier:.+}/encrypt")
    public Response encrypt(@PathParam("keyIdentifier") String keyIdentifier, String body) {
        return Response.ok(service.encryptData(decode(keyIdentifier), parse(body))).build();
    }

    @POST
    @Path("/keys/{keyIdentifier:.+}/decrypt")
    public Response decrypt(@PathParam("keyIdentifier") String keyIdentifier, String body) {
        return Response.ok(service.decryptData(decode(keyIdentifier), parse(body))).build();
    }

    @POST
    @Path("/keys/{keyIdentifier:.+}/reencrypt")
    public Response reencrypt(@PathParam("keyIdentifier") String keyIdentifier, String body) {
        return Response.ok(service.reEncryptData(decode(keyIdentifier), parse(body))).build();
    }

    @POST
    @Path("/mac/generate")
    public Response generateMac(String body) {
        return Response.ok(service.generateMac(parse(body))).build();
    }

    @POST
    @Path("/mac/verify")
    public Response verifyMac(String body) {
        return Response.ok(service.verifyMac(parse(body))).build();
    }

    @POST
    @Path("/cardvalidationdata/generate")
    public Response generateCardValidationData(String body) {
        return Response.ok(service.generateCardValidationData(parse(body))).build();
    }

    @POST
    @Path("/cardvalidationdata/verify")
    public Response verifyCardValidationData(String body) {
        return Response.ok(service.verifyCardValidationData(parse(body))).build();
    }

    @POST
    @Path("/pindata/generate")
    public Response generatePinData(String body) {
        return Response.ok(service.generatePinData(parse(body))).build();
    }

    @POST
    @Path("/pindata/verify")
    public Response verifyPinData(String body) {
        return Response.ok(service.verifyPinData(parse(body))).build();
    }

    @POST
    @Path("/pindata/translate")
    public Response translatePinData(String body) {
        return Response.ok(service.translatePinData(parse(body))).build();
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

    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
