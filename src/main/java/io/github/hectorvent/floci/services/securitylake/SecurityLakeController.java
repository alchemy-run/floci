package io.github.hectorvent.floci.services.securitylake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon Security Lake restJson1. Literal {@code /v1/datalake/*} paths take
 * JAX-RS precedence over S3's {@code /{bucket}} catch-all after
 * {@link SecurityLakeRoutingFilter} prefixes the SigV4 {@code securitylake}
 * scope.
 */
@Path(SecurityLakeRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SecurityLakeController {

    private final SecurityLakeService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public SecurityLakeController(
            SecurityLakeService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/v1/datalake/exceptions")
    @Consumes(MediaType.WILDCARD)
    public Response listDataLakeExceptions(@Context HttpHeaders headers, String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode exceptions = response.putArray("exceptions");
        for (Object exception : service.listExceptions()) {
            exceptions.add(objectMapper.valueToTree(exception));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/v1/datalake/sources")
    @Consumes(MediaType.WILDCARD)
    public Response getDataLakeSources(@Context HttpHeaders headers, String body) {
        parse(body);
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("dataLakeArn", service.dataLakeArn(region));
        ArrayNode sources = response.putArray("dataLakeSources");
        for (Object source : service.listSources()) {
            sources.add(objectMapper.valueToTree(source));
        }
        return Response.ok(response).build();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw SecurityLakeService.validation("Request body must be a JSON object.");
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw SecurityLakeService.validation("Request body is not valid JSON.");
        }
    }
}
