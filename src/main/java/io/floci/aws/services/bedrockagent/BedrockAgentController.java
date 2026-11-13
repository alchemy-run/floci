package io.floci.aws.services.bedrockagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Agents for Amazon Bedrock (bedrock-agent) REST-JSON control plane: agents,
 * versions, aliases, action groups, knowledge base associations, knowledge
 * bases, data sources, ingestion jobs, and documents. Routes, methods, and
 * status codes follow the service model; tags are served on the shared
 * {@code /tags/{resourceArn}} path by {@link BedrockAgentTagHandler}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class BedrockAgentController {

    private final BedrockAgentService agents;
    private final BedrockKnowledgeBaseService knowledgeBases;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentController(BedrockAgentService agents, BedrockKnowledgeBaseService knowledgeBases,
                                  RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.agents = agents;
        this.knowledgeBases = knowledgeBases;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ── Agents ─────────────────────────────────────────────────────────────

    @PUT
    @Path("/agents/")
    public Response createAgent(@Context HttpHeaders headers, String body) {
        return status(202, agents.createAgent(read(body), region(headers)));
    }

    @POST
    @Path("/agents/")
    public Response listAgents(@Context HttpHeaders headers, String body) {
        return ok(agents.listAgents(read(body), region(headers)));
    }

    @GET
    @Path("/agents/{agentId}/")
    public Response getAgent(@PathParam("agentId") String agentId, @Context HttpHeaders headers) {
        return ok(agents.getAgent(agentId, region(headers)));
    }

    @PUT
    @Path("/agents/{agentId}/")
    public Response updateAgent(@PathParam("agentId") String agentId, @Context HttpHeaders headers, String body) {
        return status(202, agents.updateAgent(agentId, read(body), region(headers)));
    }

    @POST
    @Path("/agents/{agentId}/")
    public Response prepareAgent(@PathParam("agentId") String agentId, @Context HttpHeaders headers) {
        return status(202, agents.prepareAgent(agentId, region(headers)));
    }

    @DELETE
    @Path("/agents/{agentId}/")
    public Response deleteAgent(@PathParam("agentId") String agentId,
                                @QueryParam("skipResourceInUseCheck") boolean skip, @Context HttpHeaders headers) {
        return status(202, agents.deleteAgent(agentId, skip, region(headers)));
    }

    // ── Versions ───────────────────────────────────────────────────────────

    @POST
    @Path("/agents/{agentId}/agentversions/")
    public Response listAgentVersions(@PathParam("agentId") String agentId, @Context HttpHeaders headers,
                                      String body) {
        return ok(agents.listAgentVersions(agentId, read(body), region(headers)));
    }

    @GET
    @Path("/agents/{agentId}/agentversions/{agentVersion}/")
    public Response getAgentVersion(@PathParam("agentId") String agentId,
                                    @PathParam("agentVersion") String version, @Context HttpHeaders headers) {
        return ok(agents.getAgentVersion(agentId, version, region(headers)));
    }

    @DELETE
    @Path("/agents/{agentId}/agentversions/{agentVersion}/")
    public Response deleteAgentVersion(@PathParam("agentId") String agentId,
                                       @PathParam("agentVersion") String version,
                                       @QueryParam("skipResourceInUseCheck") boolean skip,
                                       @Context HttpHeaders headers) {
        return status(202, agents.deleteAgentVersion(agentId, version, skip, region(headers)));
    }

    // ── Aliases ────────────────────────────────────────────────────────────

    @PUT
    @Path("/agents/{agentId}/agentaliases/")
    public Response createAgentAlias(@PathParam("agentId") String agentId, @Context HttpHeaders headers,
                                     String body) {
        return status(202, agents.createAgentAlias(agentId, read(body), region(headers)));
    }

    @POST
    @Path("/agents/{agentId}/agentaliases/")
    public Response listAgentAliases(@PathParam("agentId") String agentId, @Context HttpHeaders headers,
                                     String body) {
        return ok(agents.listAgentAliases(agentId, read(body), region(headers)));
    }

    @GET
    @Path("/agents/{agentId}/agentaliases/{agentAliasId}/")
    public Response getAgentAlias(@PathParam("agentId") String agentId,
                                  @PathParam("agentAliasId") String aliasId, @Context HttpHeaders headers) {
        return ok(agents.getAgentAlias(agentId, aliasId, region(headers)));
    }

    @PUT
    @Path("/agents/{agentId}/agentaliases/{agentAliasId}/")
    public Response updateAgentAlias(@PathParam("agentId") String agentId,
                                     @PathParam("agentAliasId") String aliasId, @Context HttpHeaders headers,
                                     String body) {
        return status(202, agents.updateAgentAlias(agentId, aliasId, read(body), region(headers)));
    }

    @DELETE
    @Path("/agents/{agentId}/agentaliases/{agentAliasId}/")
    public Response deleteAgentAlias(@PathParam("agentId") String agentId,
                                     @PathParam("agentAliasId") String aliasId, @Context HttpHeaders headers) {
        return status(202, agents.deleteAgentAlias(agentId, aliasId, region(headers)));
    }

    // ── Action groups ──────────────────────────────────────────────────────

    @PUT
    @Path("/agents/{agentId}/agentversions/{agentVersion}/actiongroups/")
    public Response createAgentActionGroup(@PathParam("agentId") String agentId,
                                      @PathParam("agentVersion") String version, @Context HttpHeaders headers,
                                      String body) {
        return ok(agents.createActionGroup(agentId, version, read(body), region(headers)));
    }

    @POST
    @Path("/agents/{agentId}/agentversions/{agentVersion}/actiongroups/")
    public Response listAgentActionGroups(@PathParam("agentId") String agentId,
                                     @PathParam("agentVersion") String version, @Context HttpHeaders headers,
                                     String body) {
        return ok(agents.listActionGroups(agentId, version, read(body), region(headers)));
    }

    @GET
    @Path("/agents/{agentId}/agentversions/{agentVersion}/actiongroups/{actionGroupId}/")
    public Response getAgentActionGroup(@PathParam("agentId") String agentId, @PathParam("agentVersion") String version,
                                   @PathParam("actionGroupId") String groupId, @Context HttpHeaders headers) {
        return ok(agents.getActionGroup(agentId, version, groupId, region(headers)));
    }

    @PUT
    @Path("/agents/{agentId}/agentversions/{agentVersion}/actiongroups/{actionGroupId}/")
    public Response updateAgentActionGroup(@PathParam("agentId") String agentId,
                                      @PathParam("agentVersion") String version,
                                      @PathParam("actionGroupId") String groupId, @Context HttpHeaders headers,
                                      String body) {
        return ok(agents.updateActionGroup(agentId, version, groupId, read(body), region(headers)));
    }

    @DELETE
    @Path("/agents/{agentId}/agentversions/{agentVersion}/actiongroups/{actionGroupId}/")
    public Response deleteAgentActionGroup(@PathParam("agentId") String agentId,
                                      @PathParam("agentVersion") String version,
                                      @PathParam("actionGroupId") String groupId, @Context HttpHeaders headers) {
        agents.deleteActionGroup(agentId, version, groupId, region(headers));
        return Response.status(204).build();
    }

    // ── Knowledge base associations ────────────────────────────────────────

    @PUT
    @Path("/agents/{agentId}/agentversions/{agentVersion}/knowledgebases/")
    public Response associateAgentKnowledgeBase(@PathParam("agentId") String agentId,
                                           @PathParam("agentVersion") String version,
                                           @Context HttpHeaders headers, String body) {
        JsonNode request = read(body);
        String region = region(headers);
        return ok(agents.associateKnowledgeBase(agentId, version, request, region,
                knowledgeBases.exists(BedrockAgentService.text(request, "knowledgeBaseId"), region)));
    }

    @POST
    @Path("/agents/{agentId}/agentversions/{agentVersion}/knowledgebases/")
    public Response listAgentKnowledgeBases(@PathParam("agentId") String agentId,
                                            @PathParam("agentVersion") String version,
                                            @Context HttpHeaders headers, String body) {
        return ok(agents.listAgentKnowledgeBases(agentId, version, read(body), region(headers)));
    }

    @GET
    @Path("/agents/{agentId}/agentversions/{agentVersion}/knowledgebases/{knowledgeBaseId}/")
    public Response getAgentKnowledgeBase(@PathParam("agentId") String agentId,
                                          @PathParam("agentVersion") String version,
                                          @PathParam("knowledgeBaseId") String kbId, @Context HttpHeaders headers) {
        return ok(agents.getAgentKnowledgeBase(agentId, version, kbId, region(headers)));
    }

    @PUT
    @Path("/agents/{agentId}/agentversions/{agentVersion}/knowledgebases/{knowledgeBaseId}/")
    public Response updateAgentKnowledgeBase(@PathParam("agentId") String agentId,
                                             @PathParam("agentVersion") String version,
                                             @PathParam("knowledgeBaseId") String kbId,
                                             @Context HttpHeaders headers, String body) {
        return ok(agents.updateAgentKnowledgeBase(agentId, version, kbId, read(body), region(headers)));
    }

    @DELETE
    @Path("/agents/{agentId}/agentversions/{agentVersion}/knowledgebases/{knowledgeBaseId}/")
    public Response disassociateAgentKnowledgeBase(@PathParam("agentId") String agentId,
                                              @PathParam("agentVersion") String version,
                                              @PathParam("knowledgeBaseId") String kbId,
                                              @Context HttpHeaders headers) {
        agents.disassociateKnowledgeBase(agentId, version, kbId, region(headers));
        return Response.status(204).build();
    }

    // ── Knowledge bases ────────────────────────────────────────────────────

    @PUT
    @Path("/knowledgebases/")
    public Response createKnowledgeBase(@Context HttpHeaders headers, String body) {
        return status(202, knowledgeBases.createKnowledgeBase(read(body), region(headers)));
    }

    @POST
    @Path("/knowledgebases/")
    public Response listKnowledgeBases(@Context HttpHeaders headers, String body) {
        return ok(knowledgeBases.listKnowledgeBases(read(body), region(headers)));
    }

    @GET
    @Path("/knowledgebases/{knowledgeBaseId}")
    public Response getKnowledgeBase(@PathParam("knowledgeBaseId") String kbId, @Context HttpHeaders headers) {
        return ok(knowledgeBases.getKnowledgeBase(kbId, region(headers)));
    }

    @PUT
    @Path("/knowledgebases/{knowledgeBaseId}")
    public Response updateKnowledgeBase(@PathParam("knowledgeBaseId") String kbId, @Context HttpHeaders headers,
                                        String body) {
        return status(202, knowledgeBases.updateKnowledgeBase(kbId, read(body), region(headers)));
    }

    @DELETE
    @Path("/knowledgebases/{knowledgeBaseId}")
    public Response deleteKnowledgeBase(@PathParam("knowledgeBaseId") String kbId, @Context HttpHeaders headers) {
        return status(202, knowledgeBases.deleteKnowledgeBase(kbId, region(headers)));
    }

    // ── Data sources ───────────────────────────────────────────────────────

    @PUT
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/")
    public Response createDataSource(@PathParam("knowledgeBaseId") String kbId, @Context HttpHeaders headers,
                                     String body) {
        return ok(knowledgeBases.createDataSource(kbId, read(body), region(headers)));
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/")
    public Response listDataSources(@PathParam("knowledgeBaseId") String kbId, @Context HttpHeaders headers,
                                    String body) {
        return ok(knowledgeBases.listDataSources(kbId, read(body), region(headers)));
    }

    @GET
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}")
    public Response getDataSource(@PathParam("knowledgeBaseId") String kbId,
                                  @PathParam("dataSourceId") String dsId, @Context HttpHeaders headers) {
        return ok(knowledgeBases.getDataSource(kbId, dsId, region(headers)));
    }

    @PUT
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}")
    public Response updateDataSource(@PathParam("knowledgeBaseId") String kbId,
                                     @PathParam("dataSourceId") String dsId, @Context HttpHeaders headers,
                                     String body) {
        return ok(knowledgeBases.updateDataSource(kbId, dsId, read(body), region(headers)));
    }

    @DELETE
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}")
    public Response deleteDataSource(@PathParam("knowledgeBaseId") String kbId,
                                     @PathParam("dataSourceId") String dsId, @Context HttpHeaders headers) {
        return status(202, knowledgeBases.deleteDataSource(kbId, dsId, region(headers)));
    }

    // ── Ingestion jobs ─────────────────────────────────────────────────────

    @PUT
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/ingestionjobs/")
    public Response startIngestionJob(@PathParam("knowledgeBaseId") String kbId,
                                      @PathParam("dataSourceId") String dsId, @Context HttpHeaders headers,
                                      String body) {
        return status(202, knowledgeBases.startIngestionJob(kbId, dsId, read(body), region(headers)));
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/ingestionjobs/")
    public Response listIngestionJobs(@PathParam("knowledgeBaseId") String kbId,
                                      @PathParam("dataSourceId") String dsId, @Context HttpHeaders headers,
                                      String body) {
        return ok(knowledgeBases.listIngestionJobs(kbId, dsId, read(body), region(headers)));
    }

    @GET
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/ingestionjobs/{ingestionJobId}")
    public Response getIngestionJob(@PathParam("knowledgeBaseId") String kbId,
                                    @PathParam("dataSourceId") String dsId,
                                    @PathParam("ingestionJobId") String jobId, @Context HttpHeaders headers) {
        return ok(knowledgeBases.getIngestionJob(kbId, dsId, jobId, region(headers)));
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/ingestionjobs/{ingestionJobId}/stop")
    public Response stopIngestionJob(@PathParam("knowledgeBaseId") String kbId,
                                     @PathParam("dataSourceId") String dsId,
                                     @PathParam("ingestionJobId") String jobId, @Context HttpHeaders headers) {
        return status(202, knowledgeBases.stopIngestionJob(kbId, dsId, jobId, region(headers)));
    }

    // ── Documents ──────────────────────────────────────────────────────────

    @PUT
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/documents")
    public Response ingestKnowledgeBaseDocuments(@PathParam("knowledgeBaseId") String kbId,
                                    @PathParam("dataSourceId") String dsId, @Context HttpHeaders headers,
                                    String body) {
        return status(202, knowledgeBases.ingestDocuments(kbId, dsId, read(body), region(headers)));
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/documents")
    public Response listKnowledgeBaseDocuments(@PathParam("knowledgeBaseId") String kbId,
                                  @PathParam("dataSourceId") String dsId, @Context HttpHeaders headers,
                                  String body) {
        return ok(knowledgeBases.listDocuments(kbId, dsId, read(body), region(headers)));
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/documents/getDocuments")
    public Response getKnowledgeBaseDocuments(@PathParam("knowledgeBaseId") String kbId,
                                 @PathParam("dataSourceId") String dsId, @Context HttpHeaders headers,
                                 String body) {
        return ok(knowledgeBases.getDocuments(kbId, dsId, read(body), region(headers)));
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/documents/deleteDocuments")
    public Response deleteKnowledgeBaseDocuments(@PathParam("knowledgeBaseId") String kbId,
                                    @PathParam("dataSourceId") String dsId, @Context HttpHeaders headers,
                                    String body) {
        return status(202, knowledgeBases.deleteDocuments(kbId, dsId, read(body), region(headers)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode read(String body) {
        try {
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Malformed request body: " + e.getMessage(), 400);
        }
    }

    private static Response ok(Object entity) {
        return Response.ok(entity).build();
    }

    private static Response status(int code, Object entity) {
        return Response.status(code).entity(entity).build();
    }
}
