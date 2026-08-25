package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * OpenSearch domain REST data plane, served under
 * {@code /_floci/opensearch/{domainName}/...} after
 * {@link OpenSearchRoutingFilter} rewrites the virtual-hosted AWS endpoint.
 */
@Path(OpenSearchRoutingFilter.INTERNAL_PREFIX + "/{domainName}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class OpenSearchDataPlaneController {

    private final OpenSearchService service;
    private final OpenSearchDataPlane dataPlane;
    private final ObjectMapper objectMapper;

    @Inject
    public OpenSearchDataPlaneController(OpenSearchService service, OpenSearchDataPlane dataPlane,
                                         ObjectMapper objectMapper) {
        this.service = service;
        this.dataPlane = dataPlane;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/{index}/_doc/{id}")
    public Response putDocument(@PathParam("domainName") String domainName,
                                @PathParam("index") String index,
                                @PathParam("id") String id,
                                String body) {
        requireDomain(domainName);
        return Response.ok(dataPlane.indexDocument(domainName, index, id, parseJson(body))).build();
    }

    @POST
    @Path("/{index}/_doc")
    public Response postDocument(@PathParam("domainName") String domainName,
                                 @PathParam("index") String index,
                                 String body) {
        requireDomain(domainName);
        String id = dataPlane.allocateId();
        return Response.status(201)
                .entity(dataPlane.indexDocument(domainName, index, id, parseJson(body)))
                .build();
    }

    @POST
    @Path("/{index}/_doc/{id}")
    public Response postDocumentWithId(@PathParam("domainName") String domainName,
                                       @PathParam("index") String index,
                                       @PathParam("id") String id,
                                       String body) {
        return putDocument(domainName, index, id, body);
    }

    @GET
    @Path("/{index}/_doc/{id}")
    public Response getDocument(@PathParam("domainName") String domainName,
                                @PathParam("index") String index,
                                @PathParam("id") String id) {
        requireDomain(domainName);
        OpenSearchDataPlane.GetResult result = dataPlane.getDocument(domainName, index, id);
        return Response.status(result.found() ? 200 : 404).entity(result.body()).build();
    }

    @HEAD
    @Path("/{index}/_doc/{id}")
    public Response headDocument(@PathParam("domainName") String domainName,
                                 @PathParam("index") String index,
                                 @PathParam("id") String id) {
        requireDomain(domainName);
        boolean exists = dataPlane.existsDocument(domainName, index, id);
        return Response.status(exists ? 200 : 404).build();
    }

    @DELETE
    @Path("/{index}/_doc/{id}")
    public Response deleteDocument(@PathParam("domainName") String domainName,
                                   @PathParam("index") String index,
                                   @PathParam("id") String id) {
        requireDomain(domainName);
        var body = dataPlane.deleteDocument(domainName, index, id);
        boolean missing = "not_found".equals(body.path("result").asText());
        return Response.status(missing ? 404 : 200).entity(body).build();
    }

    @POST
    @Path("/{index}/_update/{id}")
    public Response updateDocument(@PathParam("domainName") String domainName,
                                   @PathParam("index") String index,
                                   @PathParam("id") String id,
                                   String body) {
        requireDomain(domainName);
        OpenSearchDataPlane.UpdateResult result =
                dataPlane.updateDocument(domainName, index, id, parseJson(body));
        return Response.status(result.status()).entity(result.body()).build();
    }

    @POST
    @Path("/_bulk")
    public Response bulk(@PathParam("domainName") String domainName, String body) {
        requireDomain(domainName);
        return Response.ok(dataPlane.bulk(domainName, body)).build();
    }

    @GET
    @Path("/{index}/_search")
    public Response searchIndex(@PathParam("domainName") String domainName,
                                @PathParam("index") String index,
                                @QueryParam("source") String source) {
        requireDomain(domainName);
        return Response.ok(dataPlane.search(domainName, index, parseSource(source))).build();
    }

    @GET
    @Path("/_search")
    public Response searchAll(@PathParam("domainName") String domainName,
                              @QueryParam("source") String source) {
        requireDomain(domainName);
        return Response.ok(dataPlane.search(domainName, null, parseSource(source))).build();
    }

    @GET
    @Path("/{index}/_count")
    public Response countIndex(@PathParam("domainName") String domainName,
                               @PathParam("index") String index,
                               @QueryParam("source") String source) {
        requireDomain(domainName);
        return Response.ok(dataPlane.count(domainName, index, parseSource(source))).build();
    }

    @GET
    @Path("/_count")
    public Response countAll(@PathParam("domainName") String domainName,
                             @QueryParam("source") String source) {
        requireDomain(domainName);
        return Response.ok(dataPlane.count(domainName, null, parseSource(source))).build();
    }

    @GET
    @Path("/_cluster/health")
    public Response clusterHealth(@PathParam("domainName") String domainName) {
        requireDomain(domainName);
        return Response.ok(dataPlane.clusterHealth(domainName)).build();
    }

    private void requireDomain(String domainName) {
        service.describeDomain(domainName);
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Invalid JSON: " + e.getMessage(), 400);
        }
    }

    private JsonNode parseSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        return parseJson(source);
    }
}
