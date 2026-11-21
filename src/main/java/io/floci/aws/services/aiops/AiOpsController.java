package io.floci.aws.services.aiops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** The CloudWatch investigations ({@code aiops}) REST API, under {@code /investigationGroups}. */
@Path("/investigationGroups")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class AiOpsController {
    private final AiOpsService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public AiOpsController(AiOpsService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    @POST
    public Response createInvestigationGroup(@Context HttpHeaders headers, String body) {
        return Response.status(201).entity(service.create(parse(body), regions.resolveRegion(headers))).build();
    }

    @GET
    public Response listInvestigationGroups(@Context HttpHeaders headers, @QueryParam("maxResults") Integer maxResults,
                                            @QueryParam("nextToken") String nextToken) {
        if (maxResults != null && (maxResults < 1 || maxResults > 50)) {
            throw AiOpsService.validation("maxResults must be between 1 and 50.");
        }
        return Response.ok(service.list(maxResults, nextToken, regions.resolveRegion(headers))).build();
    }

    @GET
    @Path("/{identifier: .+}")
    public Response getInvestigationGroup(@Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        return Response.ok(service.get(identifier, regions.resolveRegion(headers))).build();
    }

    @PATCH
    @Path("/{identifier: .+}")
    public Response updateInvestigationGroup(@Context HttpHeaders headers, @PathParam("identifier") String identifier,
                                             String body) {
        service.update(identifier, parse(body), regions.resolveRegion(headers));
        return Response.ok(Map.of()).build();
    }

    @DELETE
    @Path("/{identifier: .+}")
    public Response deleteInvestigationGroup(@Context HttpHeaders headers,
                                             @PathParam("identifier") String identifier) {
        service.delete(identifier, regions.resolveRegion(headers));
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/{identifier: .+}/policy")
    public Response putInvestigationGroupPolicy(@Context HttpHeaders headers,
                                                @PathParam("identifier") String identifier, String body) {
        return Response.ok(service.putPolicy(identifier, parse(body), regions.resolveRegion(headers))).build();
    }

    @GET
    @Path("/{identifier: .+}/policy")
    public Response getInvestigationGroupPolicy(@Context HttpHeaders headers,
                                                @PathParam("identifier") String identifier) {
        return Response.ok(service.getPolicy(identifier, regions.resolveRegion(headers))).build();
    }

    @DELETE
    @Path("/{identifier: .+}/policy")
    public Response deleteInvestigationGroupPolicy(@Context HttpHeaders headers,
                                                   @PathParam("identifier") String identifier) {
        service.deletePolicy(identifier, regions.resolveRegion(headers));
        return Response.ok(Map.of()).build();
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "The request body is not valid JSON.", 400);
        }
    }
}
