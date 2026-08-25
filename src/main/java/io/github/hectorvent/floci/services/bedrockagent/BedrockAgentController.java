package io.github.hectorvent.floci.services.bedrockagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagent.model.Agent;
import io.github.hectorvent.floci.services.bedrockagent.model.AgentAlias;
import io.github.hectorvent.floci.services.bedrockagent.model.DataSource;
import io.github.hectorvent.floci.services.bedrockagent.model.IngestionJob;
import io.github.hectorvent.floci.services.bedrockagent.model.KnowledgeBase;
import io.github.hectorvent.floci.services.bedrockagent.model.KnowledgeBaseDocument;
import io.github.hectorvent.floci.services.bedrockruntime.BedrockEventStreams;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Amazon Bedrock Agents restJson1 (service {@code bedrock-agent}, signing name {@code bedrock}).
 *
 * <p>Literal {@code /agents} paths take JAX-RS precedence over S3's {@code /{bucket}}
 * catch-all. Tag APIs share {@code /tags/{arn}} and are dispatched by SharedTagsController.
 * Knowledge-base and retrieve routes live under {@code /knowledgebases}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentController {

    private final BedrockAgentService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public BedrockAgentController(
            BedrockAgentService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @PUT
    @Path("/agents/")
    public Response createAgent(@Context HttpHeaders headers, String body) {
        Agent agent = service.createAgent(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("agent", toAgent(agent));
        return Response.ok(response).build();
    }

    @POST
    @Path("/agents/")
    @Consumes(MediaType.WILDCARD)
    public Response listAgents(@Context HttpHeaders headers, String body) {
        java.util.List<Agent> agents = service.listAgents(regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("agentSummaries");
        for (Agent agent : agents) {
            summaries.add(toSummary(agent));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/agents/{agentId}/")
    @Consumes(MediaType.WILDCARD)
    public Response getAgent(@Context HttpHeaders headers, @PathParam("agentId") String agentId) {
        Agent agent = service.getAgent(regionResolver.resolveRegion(headers), agentId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("agent", toAgent(agent));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/agents/{agentId}/")
    public Response updateAgent(
            @Context HttpHeaders headers, @PathParam("agentId") String agentId, String body) {
        Agent agent = service.updateAgent(regionResolver.resolveRegion(headers), agentId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("agent", toAgent(agent));
        return Response.ok(response).build();
    }

    @POST
    @Path("/agents/{agentId}/")
    @Consumes(MediaType.WILDCARD)
    public Response prepareAgent(@Context HttpHeaders headers, @PathParam("agentId") String agentId) {
        Agent agent = service.prepareAgent(regionResolver.resolveRegion(headers), agentId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("agentId", agent.getAgentId());
        response.put("agentStatus", agent.getAgentStatus());
        response.put("agentVersion", agent.getAgentVersion() == null ? "DRAFT" : agent.getAgentVersion());
        response.put("preparedAt", agent.getPreparedAt());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/agents/{agentId}/")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAgent(@Context HttpHeaders headers, @PathParam("agentId") String agentId) {
        Agent agent = service.deleteAgent(regionResolver.resolveRegion(headers), agentId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("agentId", agent.getAgentId());
        response.put("agentStatus", "DELETING");
        return Response.ok(response).build();
    }

    @PUT
    @Path("/agents/{agentId}/agentaliases/")
    public Response createAgentAlias(
            @Context HttpHeaders headers, @PathParam("agentId") String agentId, String body) {
        AgentAlias alias = service.createAlias(
                regionResolver.resolveRegion(headers), agentId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("agentAlias", toAlias(alias));
        return Response.ok(response).build();
    }

    @POST
    @Path("/agents/{agentId}/agentaliases/")
    @Consumes(MediaType.WILDCARD)
    public Response listAgentAliases(
            @Context HttpHeaders headers, @PathParam("agentId") String agentId, String body) {
        java.util.List<AgentAlias> aliases = service.listAliases(
                regionResolver.resolveRegion(headers), agentId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("agentAliasSummaries");
        for (AgentAlias alias : aliases) {
            summaries.add(toAliasSummary(alias));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/agents/{agentId}/agentaliases/{agentAliasId}/")
    @Consumes(MediaType.WILDCARD)
    public Response getAgentAlias(
            @Context HttpHeaders headers,
            @PathParam("agentId") String agentId,
            @PathParam("agentAliasId") String agentAliasId) {
        AgentAlias alias = service.getAlias(
                regionResolver.resolveRegion(headers), agentId, agentAliasId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("agentAlias", toAlias(alias));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/agents/{agentId}/agentaliases/{agentAliasId}/")
    public Response updateAgentAlias(
            @Context HttpHeaders headers,
            @PathParam("agentId") String agentId,
            @PathParam("agentAliasId") String agentAliasId,
            String body) {
        AgentAlias alias = service.updateAlias(
                regionResolver.resolveRegion(headers), agentId, agentAliasId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("agentAlias", toAlias(alias));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/agents/{agentId}/agentaliases/{agentAliasId}/")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAgentAlias(
            @Context HttpHeaders headers,
            @PathParam("agentId") String agentId,
            @PathParam("agentAliasId") String agentAliasId) {
        AgentAlias alias = service.deleteAlias(
                regionResolver.resolveRegion(headers), agentId, agentAliasId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("agentId", alias.getAgentId());
        response.put("agentAliasId", alias.getAgentAliasId());
        response.put("agentAliasStatus", "DELETING");
        return Response.ok(response).build();
    }

    @POST
    @Path("/agents/{agentId}/agentAliases/{agentAliasId}/sessions/{sessionId}/text")
    @Consumes(MediaType.WILDCARD)
    public Response invokeAgent(
            @Context HttpHeaders headers,
            @PathParam("agentId") String agentId,
            @PathParam("agentAliasId") String agentAliasId,
            @PathParam("sessionId") String sessionId,
            String body) {
        parse(body);
        service.requireAlias(regionResolver.resolveRegion(headers), agentId, agentAliasId);
        byte[] stream = BedrockEventStreams.encodeChunk(
                objectMapper, BedrockEventStreams.utf8("Paris is the capital of France."));
        return Response.ok(stream)
                .type("application/vnd.amazon.eventstream")
                .header("x-amzn-bedrock-agent-content-type", "application/json")
                .header("x-amz-bedrock-agent-session-id", sessionId)
                .build();
    }

    @GET
    @Path("/agents/{agentId}/agentAliases/{agentAliasId}/memories")
    @Consumes(MediaType.WILDCARD)
    public Response getAgentMemory(
            @Context HttpHeaders headers,
            @PathParam("agentId") String agentId,
            @PathParam("agentAliasId") String agentAliasId,
            @QueryParam("memoryType") String memoryType,
            @QueryParam("memoryId") String memoryId,
            @QueryParam("maxItems") Integer maxItems) {
        if (memoryType == null || memoryType.isBlank()) {
            throw new AwsException("ValidationException", "memoryType is required.", 400);
        }
        if (memoryId == null || memoryId.isBlank()) {
            throw new AwsException("ValidationException", "memoryId is required.", 400);
        }
        List<ObjectNode> contents = service.getMemory(
                regionResolver.resolveRegion(headers), agentId, agentAliasId, memoryId);
        int limit = maxItems == null || maxItems <= 0 ? contents.size() : Math.min(maxItems, contents.size());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("memoryContents");
        for (int i = 0; i < limit; i++) {
            array.add(contents.get(i));
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/agents/{agentId}/agentAliases/{agentAliasId}/memories")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAgentMemory(
            @Context HttpHeaders headers,
            @PathParam("agentId") String agentId,
            @PathParam("agentAliasId") String agentAliasId,
            @QueryParam("memoryId") String memoryId) {
        service.deleteMemory(regionResolver.resolveRegion(headers), agentId, agentAliasId, memoryId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/rerank")
    public Response rerank(String body) {
        JsonNode request = parse(body);
        JsonNode queries = request.get("queries");
        JsonNode sources = request.get("sources");
        if (queries == null || !queries.isArray() || queries.isEmpty()) {
            throw new AwsException("ValidationException",
                    "queries is required and must be a non-empty array.", 400);
        }
        if (sources == null || !sources.isArray() || sources.isEmpty()) {
            throw new AwsException("ValidationException",
                    "sources is required and must be a non-empty array.", 400);
        }
        String queryText = extractQueryText(queries);
        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            scored.add(new int[] {i, score(queryText, extractSourceText(sources.get(i)))});
        }
        scored.sort(Comparator
                .comparingInt((int[] row) -> row[1]).reversed()
                .thenComparingInt(row -> row[0]));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode results = response.putArray("results");
        for (int[] row : scored) {
            ObjectNode result = results.addObject();
            result.put("index", row[0]);
            result.put("relevanceScore", row[1] / 1000.0);
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/knowledgebases/")
    public Response createKnowledgeBase(@Context HttpHeaders headers, String body) {
        KnowledgeBase kb = service.createKnowledgeBase(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("knowledgeBase", toKb(kb));
        return Response.ok(response).build();
    }

    @POST
    @Path("/knowledgebases/")
    @Consumes(MediaType.WILDCARD)
    public Response listKnowledgeBases(@Context HttpHeaders headers, String body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("knowledgeBaseSummaries");
        for (KnowledgeBase kb : service.listKnowledgeBases(regionResolver.resolveRegion(headers))) {
            summaries.add(toKbSummary(kb));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/knowledgebases/{knowledgeBaseId}")
    @Consumes(MediaType.WILDCARD)
    public Response getKnowledgeBase(
            @Context HttpHeaders headers, @PathParam("knowledgeBaseId") String knowledgeBaseId) {
        KnowledgeBase kb = service.getKnowledgeBase(regionResolver.resolveRegion(headers), knowledgeBaseId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("knowledgeBase", toKb(kb));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/knowledgebases/{knowledgeBaseId}")
    public Response updateKnowledgeBase(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            String body) {
        KnowledgeBase kb = service.updateKnowledgeBase(
                regionResolver.resolveRegion(headers), knowledgeBaseId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("knowledgeBase", toKb(kb));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/knowledgebases/{knowledgeBaseId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteKnowledgeBase(
            @Context HttpHeaders headers, @PathParam("knowledgeBaseId") String knowledgeBaseId) {
        KnowledgeBase kb = service.deleteKnowledgeBase(regionResolver.resolveRegion(headers), knowledgeBaseId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("knowledgeBaseId", kb.getKnowledgeBaseId());
        response.put("status", kb.getStatus());
        return Response.ok(response).build();
    }

    @PUT
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/")
    public Response createDataSource(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            String body) {
        DataSource source = service.createDataSource(
                regionResolver.resolveRegion(headers), knowledgeBaseId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("dataSource", toDs(source));
        return Response.ok(response).build();
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/")
    @Consumes(MediaType.WILDCARD)
    public Response listDataSources(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            String body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("dataSourceSummaries");
        for (DataSource source : service.listDataSources(regionResolver.resolveRegion(headers), knowledgeBaseId)) {
            summaries.add(toDsSummary(source));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}")
    @Consumes(MediaType.WILDCARD)
    public Response getDataSource(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            @PathParam("dataSourceId") String dataSourceId) {
        DataSource source = service.getDataSource(
                regionResolver.resolveRegion(headers), knowledgeBaseId, dataSourceId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("dataSource", toDs(source));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}")
    public Response updateDataSource(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            @PathParam("dataSourceId") String dataSourceId,
            String body) {
        DataSource source = service.updateDataSource(
                regionResolver.resolveRegion(headers), knowledgeBaseId, dataSourceId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("dataSource", toDs(source));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDataSource(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            @PathParam("dataSourceId") String dataSourceId) {
        DataSource source = service.deleteDataSource(
                regionResolver.resolveRegion(headers), knowledgeBaseId, dataSourceId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("knowledgeBaseId", source.getKnowledgeBaseId());
        response.put("dataSourceId", source.getDataSourceId());
        response.put("status", source.getStatus());
        return Response.ok(response).build();
    }

    @PUT
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/documents")
    public Response ingestDocuments(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            @PathParam("dataSourceId") String dataSourceId,
            String body) {
        return Response.ok(wrapDocuments(service.ingestDocuments(
                regionResolver.resolveRegion(headers), knowledgeBaseId, dataSourceId, parse(body)))).build();
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/documents")
    @Consumes(MediaType.WILDCARD)
    public Response listDocuments(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            @PathParam("dataSourceId") String dataSourceId,
            String body) {
        return Response.ok(wrapDocuments(service.listDocuments(
                regionResolver.resolveRegion(headers), knowledgeBaseId, dataSourceId))).build();
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/documents/getDocuments")
    public Response getDocuments(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            @PathParam("dataSourceId") String dataSourceId,
            String body) {
        return Response.ok(wrapDocuments(service.getDocuments(
                regionResolver.resolveRegion(headers), knowledgeBaseId, dataSourceId, parse(body)))).build();
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/documents/deleteDocuments")
    public Response deleteDocuments(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            @PathParam("dataSourceId") String dataSourceId,
            String body) {
        return Response.ok(wrapDocuments(service.deleteDocuments(
                regionResolver.resolveRegion(headers), knowledgeBaseId, dataSourceId, parse(body)))).build();
    }

    @PUT
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/ingestionjobs/")
    public Response startIngestionJob(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            @PathParam("dataSourceId") String dataSourceId,
            String body) {
        IngestionJob job = service.startIngestionJob(
                regionResolver.resolveRegion(headers), knowledgeBaseId, dataSourceId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ingestionJob", toJob(job));
        return Response.ok(response).build();
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/ingestionjobs/")
    @Consumes(MediaType.WILDCARD)
    public Response listIngestionJobs(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            @PathParam("dataSourceId") String dataSourceId,
            String body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("ingestionJobSummaries");
        for (IngestionJob job : service.listIngestionJobs(
                regionResolver.resolveRegion(headers), knowledgeBaseId, dataSourceId)) {
            summaries.add(toJob(job));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/ingestionjobs/{ingestionJobId}")
    @Consumes(MediaType.WILDCARD)
    public Response getIngestionJob(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            @PathParam("dataSourceId") String dataSourceId,
            @PathParam("ingestionJobId") String ingestionJobId) {
        IngestionJob job = service.getIngestionJob(
                regionResolver.resolveRegion(headers), knowledgeBaseId, dataSourceId, ingestionJobId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ingestionJob", toJob(job));
        return Response.ok(response).build();
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/datasources/{dataSourceId}/ingestionjobs/{ingestionJobId}/stop")
    public Response stopIngestionJob(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            @PathParam("dataSourceId") String dataSourceId,
            @PathParam("ingestionJobId") String ingestionJobId,
            String body) {
        IngestionJob job = service.stopIngestionJob(
                regionResolver.resolveRegion(headers), knowledgeBaseId, dataSourceId, ingestionJobId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ingestionJob", toJob(job));
        return Response.ok(response).build();
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/retrieve")
    public Response retrieve(
            @Context HttpHeaders headers,
            @PathParam("knowledgeBaseId") String knowledgeBaseId,
            String body) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode results = response.putArray("retrievalResults");
        for (KnowledgeBaseDocument document : service.retrieve(
                regionResolver.resolveRegion(headers), knowledgeBaseId)) {
            ObjectNode result = results.addObject();
            ObjectNode content = result.putObject("content");
            content.put("type", "TEXT");
            content.put("text", document.getText() == null ? "" : document.getText());
            result.put("score", 1.0);
            result.put("documentId", document.getDocumentId());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/retrieveAndGenerate")
    public Response retrieveAndGenerate(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        String knowledgeBaseId = knowledgeBaseIdFromRag(request);
        String answer = service.generateAnswer(regionResolver.resolveRegion(headers), knowledgeBaseId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("sessionId", UUID.randomUUID().toString());
        response.putObject("output").put("text", answer);
        return Response.ok(response).build();
    }

    @POST
    @Path("/retrieveAndGenerateStream")
    @Produces("application/vnd.amazon.eventstream")
    public Response retrieveAndGenerateStream(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        String knowledgeBaseId = knowledgeBaseIdFromRag(request);
        String answer = service.generateAnswer(regionResolver.resolveRegion(headers), knowledgeBaseId);
        String sessionId = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("text", answer);
        byte[] message = BedrockEventStreams.encodeJsonEvents(
                objectMapper, List.of(new BedrockEventStreams.NamedEvent("output", payload)));
        return Response.ok(message)
                .type("application/vnd.amazon.eventstream")
                .header("x-amzn-bedrock-knowledge-base-session-id", sessionId)
                .build();
    }

    private ObjectNode toAgent(Agent agent) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("agentId", agent.getAgentId());
        node.put("agentName", agent.getAgentName());
        node.put("agentArn", agent.getAgentArn());
        node.put("agentVersion", agent.getAgentVersion() == null ? "DRAFT" : agent.getAgentVersion());
        putText(node, "instruction", agent.getInstruction());
        node.put("agentStatus", agent.getAgentStatus());
        putText(node, "foundationModel", agent.getFoundationModel());
        putText(node, "description", agent.getDescription());
        node.put("idleSessionTTLInSeconds",
                agent.getIdleSessionTTLInSeconds() == null ? 600 : agent.getIdleSessionTTLInSeconds());
        putText(node, "agentResourceRoleArn", agent.getAgentResourceRoleArn());
        putText(node, "customerEncryptionKeyArn", agent.getCustomerEncryptionKeyArn());
        node.put("createdAt", agent.getCreatedAt());
        node.put("updatedAt", agent.getUpdatedAt());
        putText(node, "preparedAt", agent.getPreparedAt());
        setIfPresent(node, "guardrailConfiguration", agent.getGuardrailConfiguration());
        setIfPresent(node, "memoryConfiguration", agent.getMemoryConfiguration());
        putTags(node, agent.getTags());
        return node;
    }

    private ObjectNode toSummary(Agent agent) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("agentId", agent.getAgentId());
        summary.put("agentName", agent.getAgentName());
        summary.put("agentStatus", agent.getAgentStatus());
        putText(summary, "description", agent.getDescription());
        summary.put("updatedAt", agent.getUpdatedAt());
        if (agent.getNextVersion() > 0) {
            summary.put("latestAgentVersion", String.valueOf(agent.getNextVersion()));
        }
        setIfPresent(summary, "guardrailConfiguration", agent.getGuardrailConfiguration());
        return summary;
    }

    private ObjectNode toAlias(AgentAlias alias) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("agentId", alias.getAgentId());
        node.put("agentAliasId", alias.getAgentAliasId());
        node.put("agentAliasName", alias.getAgentAliasName());
        node.put("agentAliasArn", alias.getAgentAliasArn());
        putText(node, "description", alias.getDescription());
        JsonNode routing = alias.getRoutingConfiguration();
        if (routing != null && routing.isArray()) {
            node.set("routingConfiguration", routing);
        } else {
            node.putArray("routingConfiguration");
        }
        node.put("createdAt", alias.getCreatedAt());
        node.put("updatedAt", alias.getUpdatedAt());
        node.put("agentAliasStatus", alias.getAgentAliasStatus());
        putText(node, "aliasInvocationState", alias.getAliasInvocationState());
        putTags(node, alias.getTags());
        return node;
    }

    private ObjectNode toAliasSummary(AgentAlias alias) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("agentAliasId", alias.getAgentAliasId());
        summary.put("agentAliasName", alias.getAgentAliasName());
        putText(summary, "description", alias.getDescription());
        JsonNode routing = alias.getRoutingConfiguration();
        if (routing != null && routing.isArray()) {
            summary.set("routingConfiguration", routing);
        }
        summary.put("agentAliasStatus", alias.getAgentAliasStatus());
        summary.put("createdAt", alias.getCreatedAt());
        summary.put("updatedAt", alias.getUpdatedAt());
        putText(summary, "aliasInvocationState", alias.getAliasInvocationState());
        return summary;
    }

    private static void putText(ObjectNode parent, String field, String value) {
        if (value != null) {
            parent.put(field, value);
        }
    }

    private static void setIfPresent(ObjectNode parent, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            parent.set(field, value);
        }
    }

    private void putTags(ObjectNode parent, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        ObjectNode tagsNode = parent.putObject("tags");
        tags.forEach(tagsNode::put);
    }

    private ObjectNode toKb(KnowledgeBase kb) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("knowledgeBaseId", kb.getKnowledgeBaseId());
        node.put("name", kb.getName());
        node.put("knowledgeBaseArn", kb.getKnowledgeBaseArn());
        putText(node, "description", kb.getDescription());
        node.put("roleArn", kb.getRoleArn());
        setIfPresent(node, "knowledgeBaseConfiguration", kb.getKnowledgeBaseConfiguration());
        setIfPresent(node, "storageConfiguration", kb.getStorageConfiguration());
        node.put("status", kb.getStatus());
        node.put("createdAt", kb.getCreatedAt());
        node.put("updatedAt", kb.getUpdatedAt());
        return node;
    }

    private ObjectNode toKbSummary(KnowledgeBase kb) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("knowledgeBaseId", kb.getKnowledgeBaseId());
        node.put("name", kb.getName());
        putText(node, "description", kb.getDescription());
        node.put("status", kb.getStatus());
        node.put("updatedAt", kb.getUpdatedAt());
        return node;
    }

    private ObjectNode toDs(DataSource source) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("knowledgeBaseId", source.getKnowledgeBaseId());
        node.put("dataSourceId", source.getDataSourceId());
        node.put("name", source.getName());
        node.put("status", source.getStatus());
        putText(node, "description", source.getDescription());
        setIfPresent(node, "dataSourceConfiguration", source.getDataSourceConfiguration());
        setIfPresent(node, "vectorIngestionConfiguration", source.getVectorIngestionConfiguration());
        putText(node, "dataDeletionPolicy", source.getDataDeletionPolicy());
        node.put("createdAt", source.getCreatedAt());
        node.put("updatedAt", source.getUpdatedAt());
        return node;
    }

    private ObjectNode toDsSummary(DataSource source) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("knowledgeBaseId", source.getKnowledgeBaseId());
        node.put("dataSourceId", source.getDataSourceId());
        node.put("name", source.getName());
        node.put("status", source.getStatus());
        putText(node, "description", source.getDescription());
        node.put("updatedAt", source.getUpdatedAt());
        return node;
    }

    private ObjectNode wrapDocuments(List<KnowledgeBaseDocument> documents) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("documentDetails");
        for (KnowledgeBaseDocument document : documents) {
            ObjectNode node = details.addObject();
            node.put("knowledgeBaseId", document.getKnowledgeBaseId());
            node.put("dataSourceId", document.getDataSourceId());
            node.put("status", document.getStatus());
            setIfPresent(node, "identifier", document.getIdentifier());
            putText(node, "updatedAt", document.getUpdatedAt());
        }
        return response;
    }

    private ObjectNode toJob(IngestionJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("knowledgeBaseId", job.getKnowledgeBaseId());
        node.put("dataSourceId", job.getDataSourceId());
        node.put("ingestionJobId", job.getIngestionJobId());
        putText(node, "description", job.getDescription());
        node.put("status", job.getStatus());
        node.put("startedAt", job.getStartedAt());
        node.put("updatedAt", job.getUpdatedAt());
        return node;
    }

    private static String knowledgeBaseIdFromRag(JsonNode request) {
        String knowledgeBaseId = request.path("retrieveAndGenerateConfiguration")
                .path("knowledgeBaseConfiguration")
                .path("knowledgeBaseId")
                .asText(null);
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new AwsException("ValidationException",
                    "retrieveAndGenerateConfiguration.knowledgeBaseConfiguration.knowledgeBaseId is required.",
                    400);
        }
        return knowledgeBaseId;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new io.github.hectorvent.floci.core.common.AwsException(
                        "ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (io.github.hectorvent.floci.core.common.AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new io.github.hectorvent.floci.core.common.AwsException(
                    "ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    private static String extractQueryText(JsonNode queries) {
        StringBuilder text = new StringBuilder();
        for (JsonNode query : queries) {
            JsonNode value = query.path("textQuery").path("text");
            if (value.isTextual()) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(value.asText());
            }
        }
        return text.toString();
    }

    private static String extractSourceText(JsonNode source) {
        JsonNode text = source.path("inlineDocumentSource").path("textDocument").path("text");
        return text.isTextual() ? text.asText() : source.toString();
    }

    private static int score(String query, String source) {
        if (source == null || source.isBlank()) {
            return 0;
        }
        String haystack = source.toLowerCase(Locale.ROOT);
        if (query == null || query.isBlank()) {
            return 100;
        }
        int hits = 0;
        int tokens = 0;
        for (String token : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() < 3) {
                continue;
            }
            tokens++;
            if (haystack.contains(token)) {
                hits++;
            }
        }
        if (tokens == 0) {
            return 100;
        }
        return (hits * 1000) / tokens;
    }
}
