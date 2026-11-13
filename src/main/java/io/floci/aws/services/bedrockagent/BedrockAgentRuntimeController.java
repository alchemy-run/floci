package io.floci.aws.services.bedrockagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * Agents for Amazon Bedrock Runtime (bedrock-agent-runtime) REST-JSON routes.
 * InvokeAgent and RetrieveAndGenerateStream answer with
 * {@code application/vnd.amazon.eventstream} bodies, the ids they report in
 * the headers the service model names.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class BedrockAgentRuntimeController {
    private static final String EVENT_STREAM = "application/vnd.amazon.eventstream";

    private final BedrockAgentRuntimeService runtime;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentRuntimeController(BedrockAgentRuntimeService runtime, RegionResolver regionResolver,
                                         ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Blocking
    @Path("/agents/{agentId}/agentAliases/{agentAliasId}/sessions/{sessionId}/text")
    public Response invokeAgent(@PathParam("agentId") String agentId, @PathParam("agentAliasId") String aliasId,
                                @PathParam("sessionId") String sessionId, @Context HttpHeaders headers,
                                String body) {
        BedrockAgentRuntimeService.Invocation invocation = runtime.invokeAgent(agentId, aliasId, sessionId,
                read(body), regionResolver.resolveRegion(headers));
        Response.ResponseBuilder response = Response.ok(streaming(invocation.completion()))
                .header("Content-Type", EVENT_STREAM)
                .header("x-amzn-bedrock-agent-content-type", "application/json")
                .header("x-amz-bedrock-agent-session-id", invocation.sessionId());
        if (invocation.memoryId() != null) {
            response.header("x-amz-bedrock-agent-memory-id", invocation.memoryId());
        }
        return response.build();
    }

    @GET
    @Path("/agents/{agentId}/agentAliases/{agentAliasId}/memories")
    public Response getAgentMemory(@PathParam("agentId") String agentId, @PathParam("agentAliasId") String aliasId,
                                   @QueryParam("memoryType") String memoryType,
                                   @QueryParam("memoryId") String memoryId,
                                   @QueryParam("maxItems") Integer maxItems,
                                   @QueryParam("nextToken") String nextToken, @Context HttpHeaders headers) {
        return Response.ok(runtime.getAgentMemory(agentId, aliasId, memoryType, memoryId, maxItems, nextToken,
                regionResolver.resolveRegion(headers))).build();
    }

    @DELETE
    @Path("/agents/{agentId}/agentAliases/{agentAliasId}/memories")
    public Response deleteAgentMemory(@PathParam("agentId") String agentId,
                                      @PathParam("agentAliasId") String aliasId,
                                      @QueryParam("memoryId") String memoryId,
                                      @QueryParam("sessionId") String sessionId, @Context HttpHeaders headers) {
        runtime.deleteAgentMemory(agentId, aliasId, memoryId, sessionId, regionResolver.resolveRegion(headers));
        return Response.status(202).entity(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/rerank")
    public Response rerank(String body) {
        return Response.ok(runtime.rerank(read(body))).build();
    }

    @POST
    @Path("/knowledgebases/{knowledgeBaseId}/retrieve")
    public Response retrieve(@PathParam("knowledgeBaseId") String kbId, @Context HttpHeaders headers, String body) {
        return Response.ok(runtime.retrieve(kbId, read(body), regionResolver.resolveRegion(headers))).build();
    }

    @POST
    @Blocking
    @Path("/retrieveAndGenerate")
    public Response retrieveAndGenerate(@Context HttpHeaders headers, String body) {
        BedrockAgentRuntimeService.Generated generated = runtime.retrieveAndGenerate(read(body),
                regionResolver.resolveRegion(headers));
        return Response.ok(runtime.retrieveAndGenerateResponse(generated)).build();
    }

    @POST
    @Blocking
    @Path("/retrieveAndGenerateStream")
    public Response retrieveAndGenerateStream(@Context HttpHeaders headers, String body) {
        BedrockAgentRuntimeService.Generated generated = runtime.retrieveAndGenerate(read(body),
                regionResolver.resolveRegion(headers));
        return Response.ok(streaming(runtime.retrieveAndGenerateStream(generated)))
                .header("Content-Type", EVENT_STREAM)
                .header("x-amzn-bedrock-knowledge-base-session-id", generated.sessionId())
                .build();
    }

    private static GenericEntity<StreamingOutput> streaming(Consumer<OutputStream> stream) {
        return new GenericEntity<>(stream::accept, StreamingOutput.class);
    }

    private JsonNode read(String body) {
        try {
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Malformed request body: " + e.getMessage(), 400);
        }
    }
}
