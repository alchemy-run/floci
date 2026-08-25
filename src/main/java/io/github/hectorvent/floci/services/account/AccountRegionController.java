package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * AWS Account Management restJson1 — Region opt-in lifecycle.
 *
 * <p>Literal {@code /getRegionOptStatus}, {@code /enableRegion},
 * {@code /disableRegion}, and {@code /listRegions} paths take JAX-RS precedence
 * over S3's {@code /{bucket}} catch-all. Requests are signed as {@code account}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccountRegionController {

    private final AccountRegionService service;
    private final ObjectMapper objectMapper;

    @Inject
    public AccountRegionController(AccountRegionService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/getRegionOptStatus")
    @Consumes(MediaType.WILDCARD)
    public Response getRegionOptStatus(String body) {
        return handle(body, request -> {
            AccountRegionService.RegionOpt status = service.getRegionOptStatus(request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("RegionName", status.regionName());
            response.put("RegionOptStatus", status.regionOptStatus());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/enableRegion")
    public Response enableRegion(String body) {
        return handle(body, request -> {
            service.enableRegion(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/disableRegion")
    public Response disableRegion(String body) {
        return handle(body, request -> {
            service.disableRegion(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/listRegions")
    @Consumes(MediaType.WILDCARD)
    public Response listRegions(String body) {
        return handle(body, request -> {
            AccountRegionService.RegionPage page = service.listRegions(request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode regions = response.putArray("Regions");
            for (AccountRegionService.RegionOpt region : page.regions()) {
                ObjectNode item = regions.addObject();
                item.put("RegionName", region.regionName());
                item.put("RegionOptStatus", region.regionOptStatus());
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
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
