package io.github.hectorvent.floci.services.omics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.omics.model.ReferenceStore;
import io.github.hectorvent.floci.services.omics.model.RunGroup;
import io.github.hectorvent.floci.services.omics.model.SequenceStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon HealthOmics restJson1. Public AWS paths are {@code /sequencestore},
 * {@code /referencestore}, {@code /runGroup} and {@code /run};
 * {@link OmicsRoutingFilter} prefixes them so they do not collide with S3
 * path-style routes. Tag APIs share {@code /tags/{arn}} and are dispatched by
 * {@code SharedTagsController}. Requests are signed as {@code omics}.
 */
@Path(OmicsRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OmicsController {

    private final OmicsService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public OmicsController(
            OmicsService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/sequencestore")
    public Response createSequenceStore(@Context HttpHeaders headers, String body) {
        SequenceStore store = service.createSequenceStore(region(headers), parse(body));
        return Response.ok(service.toSequenceStore(store)).build();
    }

    @POST
    @Path("/sequencestores")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    public Response listSequenceStores(String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode stores = response.putArray("sequenceStores");
        for (SequenceStore store : service.listSequenceStores()) {
            stores.add(service.toSequenceStoreSummary(store));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/sequencestore/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getSequenceStore(@PathParam("id") String id) {
        return Response.ok(service.toSequenceStore(service.getSequenceStore(id))).build();
    }

    @DELETE
    @Path("/sequencestore/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteSequenceStore(@PathParam("id") String id) {
        service.deleteSequenceStore(id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/sequencestore/{sequenceStoreId}/readsets")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    public Response listReadSets(@PathParam("sequenceStoreId") String sequenceStoreId, String body) {
        parse(body);
        return Response.ok(service.listReadSets(sequenceStoreId)).build();
    }

    @GET
    @Path("/sequencestore/{sequenceStoreId}/readset/{id}/metadata")
    @Consumes(MediaType.WILDCARD)
    public Response getReadSetMetadata(
            @PathParam("sequenceStoreId") String sequenceStoreId, @PathParam("id") String id) {
        return Response.ok(service.getReadSetMetadata(sequenceStoreId, id)).build();
    }

    @POST
    @Path("/referencestore")
    public Response createReferenceStore(@Context HttpHeaders headers, String body) {
        ReferenceStore store = service.createReferenceStore(region(headers), parse(body));
        return Response.ok(service.toReferenceStore(store)).build();
    }

    @POST
    @Path("/referencestores")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    public Response listReferenceStores(String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode stores = response.putArray("referenceStores");
        for (ReferenceStore store : service.listReferenceStores()) {
            stores.add(service.toReferenceStore(store));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/referencestore/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getReferenceStore(@PathParam("id") String id) {
        return Response.ok(service.toReferenceStore(service.getReferenceStore(id))).build();
    }

    @DELETE
    @Path("/referencestore/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteReferenceStore(@PathParam("id") String id) {
        service.deleteReferenceStore(id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/referencestore/{referenceStoreId}/references")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    public Response listReferences(@PathParam("referenceStoreId") String referenceStoreId, String body) {
        parse(body);
        return Response.ok(service.listReferences(referenceStoreId)).build();
    }

    @GET
    @Path("/referencestore/{referenceStoreId}/reference/{id}/metadata")
    @Consumes(MediaType.WILDCARD)
    public Response getReferenceMetadata(
            @PathParam("referenceStoreId") String referenceStoreId, @PathParam("id") String id) {
        return Response.ok(service.getReferenceMetadata(referenceStoreId, id)).build();
    }

    @POST
    @Path("/runGroup")
    public Response createRunGroup(@Context HttpHeaders headers, String body) {
        RunGroup group = service.createRunGroup(region(headers), parse(body));
        return Response.ok(service.toCreateRunGroup(group)).build();
    }

    @GET
    @Path("/runGroup")
    @Consumes(MediaType.WILDCARD)
    public Response listRunGroups() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (RunGroup group : service.listRunGroups()) {
            items.add(service.toRunGroupSummary(group));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/runGroup/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getRunGroup(@PathParam("id") String id) {
        return Response.ok(service.toRunGroup(service.getRunGroup(id))).build();
    }

    @POST
    @Path("/runGroup/{id}")
    public Response updateRunGroup(@PathParam("id") String id, String body) {
        service.updateRunGroup(id, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/runGroup/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteRunGroup(@PathParam("id") String id) {
        service.deleteRunGroup(id);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/run")
    @Consumes(MediaType.WILDCARD)
    public Response listRuns() {
        return Response.ok(service.listRuns()).build();
    }

    @GET
    @Path("/run/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getRun(@PathParam("id") String id) {
        return Response.ok(service.getRun(id)).build();
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
